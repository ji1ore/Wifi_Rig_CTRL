package com.ji1ore.wifi_rig_ctrl.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

// CI-V service for ICOM WLAN Remote Control (RS-BA1 compatible UDP protocol)
// port1=50001 control/auth  port2=50002 CI-V serial  port3=50003 audio
class CivTcpService {

    companion object {
        private const val TAG = "CivWlan"
        private const val CONTROL_SIZE = 0x10        // 16
        private const val PING_SIZE = 0x15           // 21 (also CIV data header size)
        private const val TOKEN_SIZE = 0x40          // 64
        private const val LOGIN_SIZE = 0x80          // 128
        private const val LOGIN_RESPONSE_SIZE = 0x60 // 96
        private const val STATUS_SIZE = 0x50         // 80
        private const val CONNINFO_SIZE = 0x90       // 144
        private const val OPENCLOSE_SIZE = 0x16      // 22
        // RS-BA1 audio packet: 16B header + 8B sub-header + audio payload
        // Sub-header: [0]=0x81(type) [1]=pad [2:6]=seqBE32 [6:8]=payloadLenBE16
        private const val AUDIO_HDR = 24             // audio payload starts at byte 24
        // TX audio jitter buffer declared to IC-705 in RequestStream (offset 0x84).
        // IC-705 buffers this many ms of incoming TX audio before playing, absorbing WiFi jitter.
        // Trade-off: PTT→modulation latency increases by this amount. 200ms is imperceptible in voice.
        const val TX_JITTER_BUF_MS = 200
        // Retransmit cache: how many sent TX audio packets to keep for IC-705 re-requests.
        // 128 packets × 20ms = 2.56s; must exceed TX_JITTER_BUF_MS significantly.
        private const val TX_CACHE_SIZE = 128
    }

    // ── Network state ──
    private var ctrlSocket: DatagramSocket? = null
    private var civSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var civInetAddr: InetAddress? = null
    private var civPort1: Int = 50001
    private var civPort2: Int = 50002
    private var civPort3: Int = 50003

    var civAddress: Int = 0xA4
    private val ctrlAddress = 0xE0

    @Volatile var isConnected: Boolean = false
    var lastError: String = ""

    // Sequence counters
    private var ctrlSeq: Int = 0
    private var civPktSeq: Int = 0
    private var civSendSeqB: Int = 0
    private var audioPktSeq: Int = 0   // outer RS-BA1 header seq — shared by keepalive + TX data

    // TX packet retransmit cache: stores last TX_CACHE_SIZE sent audio packets keyed by outer seq.
    // Written by txAudio thread, read by audioRxThread on IC-705 retransmit request.
    private val txCacheGuard = Object()
    private val txRetransmitCache: java.util.LinkedHashMap<Int, ByteArray> =
        object : java.util.LinkedHashMap<Int, ByteArray>(TX_CACHE_SIZE + 2, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) = size > TX_CACHE_SIZE
        }
    private var audioTxSeq: Int = 0    // inner sub-header seq — TX data packets only
    @Volatile private var lastTxAudioMs: Long = 0L  // for idle keepalive suppression during TX
    @Volatile private var pttOffPending = false     // true when setPtt(false) exchange failed; poll retries
    @Volatile private var pttIntent = false         // true while user wants PTT on; guards fire-and-forget path
    @Volatile var micGain: Float = 1.0f             // post-AGC gain multiplier; set from UI mic gain slider

    // RS-BA1 IDs
    private var ctrlMyId: Long = 0L
    private var civMyId: Long = 0L
    private var audioMyId: Long = 0L
    private var ctrlRemoteId: Long = 0L
    private var civRemoteId: Long = 0L
    private var audioRemoteId: Long = 0L
    private var token: Long = 0L
    private var tokRequest: Int = 0
    private var authSeq: Int = 0

    // Poll state
    private var pollModeCount = 0
    private var pollFailCount = 0
    private val POLL_FAIL_MAX = 9999  // Never disconnect from CI-V timeouts alone; ctrl-ping watchdog handles real disconnects
    @Volatile private var lastCivPingMs = 0L  // last time IC-705 sent a ping on port 50002; for monitoring
    @Volatile private var lastCtrlPingMs = 0L   // last time IC-705 sent a ping on port 50001; watchdog uses this
    @Volatile private var lastAudioPingMs = 0L  // last time IC-705 sent a ping on port 50003; watchdog uses this
    @Volatile private var lastAudioDataMs = 0L  // last time IC-705 sent audio PCM data; silence detection
    @Volatile var civStreamOpened = false
    private var lastOpenCloseMs = 0L
    private var lastKnownTx = false
    private var transcieveEnabled = false
    // Soft restart cooldown: prevents repeated attempts if audio doesn't actually recover
    private var lastSoftRestartMs = 0L
    // When true, connect() keeps civSocket open and skips AYR to test if AYR causes CI-V dead window
    @Volatile private var keepCivSocket = false

    // Saved for stream renewal (periodic Token + RequestStream to prevent IC-705 maintenance cycle)
    private var savedUsername = ""
    private var savedMacAddr: ByteArray? = null
    private var savedRadioName: ByteArray? = null
    // When true, disconnect/connect keeps AudioTrack alive so its buffer bridges the audio gap.
    // Set before disconnect(), cleared inside connect() after success.
    @Volatile private var seamlessReconnect = false

    // CI-V commands queued while CI-V stream is in the post-maintenance dead window (35-64s).
    // Executed automatically when civStreamOpened first becomes true after reconnect.
    @Volatile private var pendingFreqHz: Long? = null
    @Volatile private var pendingMode: Pair<String, Int>? = null

    @Volatile private var maintenanceDetectedMs = 0L
    @Volatile private var maintenanceLastRetryMs = 0L

    @Volatile private var lastStreamRenewalMs = 0L
    @Volatile private var lastRenewalAttemptMs = 0L
    // Periodic Token Renewal (magic=0x05): sent every 60s by ctrlRxThread to reset IC-705 maintenance timer.
    @Volatile private var lastTokenRenewalMs = 0L

    // CI-V receive thread: reads civSocket continuously and buffers packets.
    // Allows exchange() and pollStatus() to receive CI-V data (including transceive broadcasts)
    // without blocking the poll thread. Broadcasts arriving between polls are not lost.
    private val civRxQueue = java.util.concurrent.LinkedBlockingDeque<ByteArray>(2000)
    @Volatile private var civRxActive = false
    private var civRxThread: Thread? = null


    // CI-V broadcast cache: key=(cmd shl 8)|sub → (body, timestampMs)
    // Populated from any CI-V data received in exchange(); used by getters before explicit query
    private val civCache = HashMap<Int, Pair<ByteArray, Long>>()

    // CTRL socket receive thread: responds to IC-705 pings on port 50001 every ~60ms
    private var ctrlRxActive = false
    @Volatile private var ctrlRxPaused = false  // paused during soft restart to avoid STATUS race
    private var ctrlRxThread: Thread? = null

    // Audio output (SPK): continuous LPCM16 playback from IC-705
    private var audioTrack: AudioTrack? = null
    @Volatile private var audioRxActive = false
    private var audioRxThread: Thread? = null

    // Audio input (TX): persistent AudioRecord thread keeps Android AGC warm between PTT presses.
    // txAudioActive=true → send captured audio; false → discard (warmup only)
    @Volatile private var txAudioActive = false
    @Volatile private var audioRecordRunning = false
    @Volatile private var halWarm = false   // true once HAL-AGC has initialized (rms > 500 seen)
    private var txAudioThread: Thread? = null

    // True after Audio AYT→IAH→AYR→IAmReady handshake completes; required for TX audio
    @Volatile private var audioSessionReady = false

    // SPK output enable: false=mute (default), true=play. Toggled by user button.
    @Volatile var spkEnabled: Boolean = false

    // ── passcode() substitution cipher ──
    private val PASSCODE = intArrayOf(
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0x47,0x5d,0x4c,0x42,0x66,0x20,0x23,0x46,0x4e,0x57,0x45,0x3d,0x67,0x76,0x60,0x41,
        0x62,0x39,0x59,0x2d,0x68,0x7e,0x7c,0x65,0x7d,0x49,0x29,0x72,0x73,0x78,0x21,0x6e,
        0x5a,0x5e,0x4a,0x3e,0x71,0x2c,0x2a,0x54,0x3c,0x3a,0x63,0x4f,0x43,0x75,0x27,0x79,
        0x5b,0x35,0x70,0x48,0x6b,0x56,0x6f,0x34,0x32,0x6c,0x30,0x61,0x6d,0x7b,0x2f,0x4b,
        0x64,0x38,0x2b,0x2e,0x50,0x40,0x3f,0x55,0x33,0x37,0x25,0x77,0x24,0x26,0x74,0x6a,
        0x28,0x53,0x4d,0x69,0x22,0x5c,0x44,0x31,0x36,0x58,0x3b,0x7a,0x51,0x5f,0x52,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0
    )

    private fun passcode(input: String): ByteArray {
        val ascii = input.toByteArray(Charsets.ISO_8859_1)
        return ByteArray(ascii.size) { i ->
            var p = (ascii[i].toInt() and 0xFF) + i
            if (p > 126) p = 32 + p % 127
            PASSCODE[p].toByte()
        }
    }

    // ── Endian helpers ──
    private fun putLE32(buf: ByteArray, off: Int, v: Long) {
        buf[off]   = (v         and 0xFF).toByte()
        buf[off+1] = ((v shr 8) and 0xFF).toByte()
        buf[off+2] = ((v shr 16) and 0xFF).toByte()
        buf[off+3] = ((v shr 24) and 0xFF).toByte()
    }
    private fun putLE16(buf: ByteArray, off: Int, v: Int) {
        buf[off]   = (v        and 0xFF).toByte()
        buf[off+1] = ((v shr 8) and 0xFF).toByte()
    }
    private fun putBE16(buf: ByteArray, off: Int, v: Int) {
        buf[off]   = ((v shr 8) and 0xFF).toByte()
        buf[off+1] = (v         and 0xFF).toByte()
    }
    private fun putBE32(buf: ByteArray, off: Int, v: Long) {
        buf[off]   = ((v shr 24) and 0xFF).toByte()
        buf[off+1] = ((v shr 16) and 0xFF).toByte()
        buf[off+2] = ((v shr 8)  and 0xFF).toByte()
        buf[off+3] = (v          and 0xFF).toByte()
    }
    private fun getLE32(buf: ByteArray, off: Int): Long =
        (buf[off].toLong() and 0xFF) or
        ((buf[off+1].toLong() and 0xFF) shl 8) or
        ((buf[off+2].toLong() and 0xFF) shl 16) or
        ((buf[off+3].toLong() and 0xFF) shl 24)
    private fun getLE16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off+1].toInt() and 0xFF) shl 8)
    private fun getBE16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off+1].toInt() and 0xFF)

    private fun fillHeader(p: ByteArray, len: Int, type: Int, seq: Int, sentId: Long, rcvdId: Long) {
        putLE32(p, 0, len.toLong())
        putLE16(p, 4, type)
        putLE16(p, 6, seq)
        putLE32(p, 8, sentId)
        putLE32(p, 12, rcvdId)
    }

    // ── Packet builders ──
    private fun makeCtrl(type: Int, seq: Int, sentId: Long, rcvdId: Long): ByteArray {
        val p = ByteArray(CONTROL_SIZE)
        fillHeader(p, CONTROL_SIZE, type, seq, sentId, rcvdId)
        return p
    }

    private fun handlePingAndSendPong(r: ByteArray): Boolean {
        if (r.size != PING_SIZE) return false
        if (getLE16(r, 4) != 0x07) return false
        if ((r[16].toInt() and 0xFF) != 0x00) return false
        lastCivPingMs = System.currentTimeMillis()
        val senderId = getLE32(r, 8)
        when {
            civRemoteId == 0L -> {
                civRemoteId = senderId
                Log.i(TAG, "civRx: civRemoteId learned from ping=0x${civRemoteId.toString(16)}")
            }
            senderId != civRemoteId -> {
                Log.i(TAG, "civRx: session rotate old=0x${civRemoteId.toString(16)} → new=0x${senderId.toString(16)}")
                civRemoteId = senderId
            }
        }
        val pong = ByteArray(PING_SIZE)
        putLE32(pong, 0, PING_SIZE.toLong())
        putLE16(pong, 4, 0x07)
        putLE16(pong, 6, getLE16(r, 6))
        putLE32(pong, 8, civMyId)
        putLE32(pong, 12, civRemoteId)
        pong[16] = 0x01
        r.copyInto(pong, 17, 17, 21)
        sendCiv(pong)
        return true
    }

    private fun makeLogin(seq: Int, innerSeq: Int, tokReq: Int,
                          username: String, password: String): ByteArray {
        val p = ByteArray(LOGIN_SIZE)
        fillHeader(p, LOGIN_SIZE, 0x00, seq, ctrlMyId, ctrlRemoteId)
        putBE32(p, 16, (LOGIN_SIZE - 0x10).toLong())
        p[20] = 0x01; p[21] = 0x00
        putBE16(p, 22, innerSeq)
        putLE16(p, 26, tokReq)
        passcode(username).also { it.copyInto(p, 0x40, 0, minOf(it.size, 16)) }
        passcode(password).also { it.copyInto(p, 0x50, 0, minOf(it.size, 16)) }
        "Android".toByteArray(Charsets.US_ASCII).also { it.copyInto(p, 0x60, 0, minOf(it.size, 16)) }
        return p
    }

    private fun makeToken(seq: Int, innerSeq: Int, tokReq: Int,
                          tokenVal: Long, magic: Int): ByteArray {
        val p = ByteArray(TOKEN_SIZE)
        fillHeader(p, TOKEN_SIZE, 0x00, seq, ctrlMyId, ctrlRemoteId)
        putBE32(p, 16, (TOKEN_SIZE - 0x10).toLong())
        p[20] = 0x01; p[21] = magic.toByte()
        putBE16(p, 22, innerSeq)
        putLE16(p, 26, tokReq)
        putLE32(p, 28, tokenVal)
        putBE16(p, 0x24, 0x0798)
        return p
    }

    private fun makeRequestStream(seq: Int, innerSeq: Int, tokReq: Int, tokenVal: Long,
                                   civLocalPort: Int, audioLocalPort: Int, username: String,
                                   macAddr: ByteArray? = null,
                                   radioName: ByteArray? = null): ByteArray {
        val p = ByteArray(CONNINFO_SIZE)
        fillHeader(p, CONNINFO_SIZE, 0x00, seq, ctrlMyId, ctrlRemoteId)
        putBE32(p, 16, (CONNINFO_SIZE - 0x10).toLong())
        p[20] = 0x01; p[21] = 0x03
        putBE16(p, 22, innerSeq)
        putLE16(p, 26, tokReq)
        putLE32(p, 28, tokenVal)
        putLE16(p, 0x27, 0x8010)
        macAddr?.copyInto(p, 0x2a, 0, minOf(macAddr.size, 6))
        radioName?.copyInto(p, 0x40, 0, minOf(radioName.size, 32))
        passcode(username).also { it.copyInto(p, 0x60, 0, minOf(it.size, 16)) }
        p[0x70] = 0x01
        p[0x71] = 0x01          // TX audio (matches SDR-Control; enables TX+RX mode)
        p[0x72] = 0x04
        p[0x73] = 0x04          // TX audio codec (matches SDR-Control)
        putBE32(p, 0x74, 8000L)  // RX sample rate 8kHz (matches AudioTrack; SDR-Control uses 16000 but we stay 8kHz)
        putBE32(p, 0x78, 8000L)  // TX sample rate 8kHz — must match AudioRecord rate (was 16000 = mismatch → wrong modulation)
        putBE32(p, 0x7c, civLocalPort.toLong())
        putBE32(p, 0x80, audioLocalPort.toLong())
        putBE32(p, 0x84, TX_JITTER_BUF_MS.toLong()) // TX audio jitter buffer (ms): IC-705 buffers incoming TX audio
        p[0x88] = 0x01          // TX+RX session mode (SDR-Control=0x01; was 0x00=RX-only)
        return p
    }

    private fun makeOpenClose(seq: Int, sendSeq: Int, open: Boolean,
                               sentId: Long, rcvdId: Long): ByteArray {
        val p = ByteArray(OPENCLOSE_SIZE)
        fillHeader(p, OPENCLOSE_SIZE, 0x00, seq, sentId, rcvdId)
        putLE16(p, 16, 0x01c0)
        putBE16(p, 19, sendSeq)
        p[21] = if (open) 0x04 else 0x00
        return p
    }

    private fun makeCivData(seq: Int, sendSeq: Int, civData: ByteArray): ByteArray {
        val totalLen = PING_SIZE + civData.size
        val p = ByteArray(totalLen)
        fillHeader(p, totalLen, 0x00, seq, civMyId, civRemoteId)
        p[16] = 0xc1.toByte()
        putLE16(p, 17, civData.size)
        putBE16(p, 19, sendSeq)
        civData.copyInto(p, PING_SIZE)
        return p
    }

    // TX audio packet: [16]=0x80 (controller→IC-705 direction), [17]=0x07 (PCM16 codec, same as IC-705 RX)
    // Sub-header is big-endian. BE32 seq required — BE16 caused IC-705 to see 65536-gap per packet.
    private fun makeTxAudioPacket(audio: ByteArray): ByteArray {
        val totalLen = AUDIO_HDR + audio.size
        val p = ByteArray(totalLen)
        // Outer RS-BA1 header: shared audioPktSeq (monotone across keepalive + data)
        val seq = ++audioPktSeq and 0xFFFF
        fillHeader(p, totalLen, 0x00, seq, audioMyId, audioRemoteId)
        p[16] = 0x80.toByte()
        p[17] = 0x00.toByte()                          // proven format (0x07 was experimental)
        putBE16(p, 18, ++audioTxSeq and 0xFFFF)        // inner seq BE16; [20..21] remain 0x00
        putBE16(p, 22, audio.size)
        audio.copyInto(p, AUDIO_HDR)
        // Cache for retransmit: IC-705 may request re-delivery of missing packets within jitter window.
        synchronized(txCacheGuard) { txRetransmitCache[seq] = p.copyOf() }
        return p
    }

    // ── Socket I/O ──
    private fun sendCtrl(pkt: ByteArray) {
        val addr = civInetAddr ?: return
        try { ctrlSocket?.send(DatagramPacket(pkt, pkt.size, addr, civPort1)) }
        catch (e: Exception) { Log.e(TAG, "sendCtrl: ${e.message}") }
    }
    private fun sendCiv(pkt: ByteArray) {
        val addr = civInetAddr ?: return
        try { civSocket?.send(DatagramPacket(pkt, pkt.size, addr, civPort2)) }
        catch (e: Exception) { Log.e(TAG, "sendCiv: ${e.message}") }
    }
    private fun sendAudio(pkt: ByteArray) {
        val addr = civInetAddr ?: return
        try { audioSocket?.send(DatagramPacket(pkt, pkt.size, addr, civPort3)) }
        catch (e: Exception) { Log.e(TAG, "sendAudio: ${e.message}") }
    }

    // ── CTRL socket ping handler (IC-705 sends pings on port 50001 every ~60ms) ──
    private fun handleCtrlPingAndPong(r: ByteArray) {
        if (r.size != PING_SIZE) return
        if (getLE16(r, 4) != 0x07) return
        if ((r[16].toInt() and 0xFF) != 0x00) return
        val senderId = getLE32(r, 8)
        if (ctrlRemoteId != 0L && senderId != ctrlRemoteId) {
            Log.i(TAG, "ctrlRx: session rotate old=0x${ctrlRemoteId.toString(16)} → new=0x${senderId.toString(16)}")
            ctrlRemoteId = senderId
        }
        val pong = ByteArray(PING_SIZE)
        putLE32(pong, 0, PING_SIZE.toLong())
        putLE16(pong, 4, 0x07)
        putLE16(pong, 6, getLE16(r, 6))
        putLE32(pong, 8, ctrlMyId)
        putLE32(pong, 12, ctrlRemoteId)
        pong[16] = 0x01
        r.copyInto(pong, 17, 17, 21)
        sendCtrl(pong)
    }

    // Drain queued ctrl pings and pong each (used only during connect() before ctrlRxThread starts)
    private fun drainCtrlPings() {
        var count = 0
        while (count++ < 30) {
            val cr = recvCtrl(5) ?: break
            if (cr.size == PING_SIZE && getLE16(cr, 4) == 0x07) handleCtrlPingAndPong(cr)
        }
    }

    // ── Dedicated CTRL receive thread ──
    // IC-705 sends pings on port 50001 every ~60ms. Must respond within ~2s or IC-705 disconnects.
    // Also sends keepalive every 2s so IC-705 knows we are alive.
    private fun runCtrlRx() {
        Log.i(TAG, "ctrlRxThread start")
        var lastKeepalive = System.currentTimeMillis()
        while (ctrlRxActive) {
            if (ctrlRxPaused) { Thread.sleep(20); continue }
            val r = recvCtrl(200)
            if (!ctrlRxActive) break
            val now = System.currentTimeMillis()
            if (now - lastKeepalive > 2000 && ctrlRemoteId != 0L) {
                sendCtrl(makeCtrl(0x00, ++ctrlSeq, ctrlMyId, ctrlRemoteId))
                lastKeepalive = now
            }
            // Token Renewal (magic=0x05) every 60s — resets IC-705 maintenance timer
            val ltr = lastTokenRenewalMs
            if (ltr > 0 && now - ltr > 60_000L && token != 0L && ctrlRemoteId != 0L) {
                Log.i(TAG, "ctrlRx: token renewal ${now - ltr}ms since last")
                sendCtrl(makeToken(++ctrlSeq, authSeq++, tokRequest, token, 0x05))
                lastTokenRenewalMs = now
            }
            if (r == null) continue
            if (r.size == PING_SIZE && getLE16(r, 4) == 0x07 && (r[16].toInt() and 0xFF) == 0x00) {
                lastCtrlPingMs = System.currentTimeMillis()
                handleCtrlPingAndPong(r)
            }
            // Token renewal response: TOKEN_SIZE, requestreply=0x02, requesttype=0x05
            if (r.size == TOKEN_SIZE && getLE16(r, 4) != 0x01
                    && (r[20].toInt() and 0xFF) == 0x02 && (r[21].toInt() and 0xFF) == 0x05) {
                handleTokenRenewalResponse(r)
            }
        }
        Log.i(TAG, "ctrlRxThread end")
    }

    private fun handleTokenRenewalResponse(r: ByteArray) {
        lastTokenRenewalMs = System.currentTimeMillis()
        val renewErr = getLE32(r, 0x30)
        if (renewErr == 0L) {
            Log.i(TAG, "token renewal: accepted")
            return
        }
        Log.w(TAG, "token renewal: rejected err=0x${renewErr.toString(16)} — extracting new token, resending RequestStream")
        val newTokReq = getLE16(r, 0x1a)
        val newToken  = getLE32(r, 0x1c)
        if (newToken != 0L) { tokRequest = newTokReq; token = newToken }
        val civPort   = civSocket?.localPort  ?: return
        val audioPort = audioSocket?.localPort ?: return
        if (savedUsername.isEmpty()) return
        sendCtrl(makeRequestStream(++ctrlSeq, authSeq++, tokRequest, token,
            civPort, audioPort, savedUsername, savedMacAddr, savedRadioName))
    }

    private fun startCtrlRxThread() {
        ctrlRxActive = true
        ctrlRxThread = Thread { runCtrlRx() }.also {
            it.isDaemon = true; it.name = "civCtrlRx"; it.start()
        }
    }

    private fun stopCtrlRxThread() {
        ctrlRxActive = false
        ctrlRxThread?.join(500)
        ctrlRxThread = null
    }

    private fun startCivRxThread() {
        civRxActive = false
        civRxThread?.join(300)
        civRxQueue.clear()
        civRxActive = true
        civRxThread = Thread {
            Log.i(TAG, "civRxThread started (v2: keepalive+pong)")
            var lastKeepalive = System.currentTimeMillis()
            var pingCount = 0
            while (civRxActive) {
                val s = civSocket ?: break
                try {
                    s.soTimeout = 200
                    val buf = ByteArray(1024)
                    val pkt = java.net.DatagramPacket(buf, buf.size)
                    s.receive(pkt)
                    val data = buf.copyOf(pkt.length)
                    // Pong CI-V pings immediately — same as ctrlRxThread/audioRxThread.
                    // Do NOT queue them; exchange() would double-pong if it saw them.
                    if (handlePingAndSendPong(data)) {
                        pingCount++
                        if (pingCount % 100 == 0)
                            Log.d(TAG, "civRxThread: ponged $pingCount CI-V pings")
                    } else {
                        civRxQueue.offerLast(data)
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Send proactive CI-V keepalive every 2s independent of exchange().
                    // Prevents IC-705 from timing out the CI-V session (~80s timeout)
                    // even during maintenance wait or CI-V dead window.
                    val now = System.currentTimeMillis()
                    if (now - lastKeepalive > 2000 && civRemoteId != 0L) {
                        sendCiv(makeCtrl(0x00, ++civPktSeq, civMyId, civRemoteId))
                        lastKeepalive = now
                        Log.d(TAG, "civRxThread: CI-V keepalive sent (ping=$pingCount)")
                    }
                } catch (e: Exception) {
                    if (civRxActive) Log.e(TAG, "civRxThread: ${e.message}")
                    break
                }
            }
            civRxActive = false
            Log.d(TAG, "civRxThread exited (ponged $pingCount pings)")
        }.also { it.isDaemon = true; it.name = "civRxThread"; it.start() }
    }

    // ── Audio ping handler (called from audio receive thread) ──
    // Returns true if we ponged (current session ping or session rotation).
    // IC-705 rotates its audio session ID periodically (~80s); we must follow it.
    private fun handleAudioPing(r: ByteArray): Boolean {
        if (r.size != PING_SIZE) return false
        if (getLE16(r, 4) != 0x07) return false
        if ((r[16].toInt() and 0xFF) != 0x00) return false
        val senderId = getLE32(r, 8)
        when {
            audioRemoteId == 0L -> {
                audioRemoteId = senderId
                Log.i(TAG, "audioRemoteId learned=0x${audioRemoteId.toString(16)}")
            }
            senderId != audioRemoteId -> {
                // IC-705 rotated audio session (happens ~every 80s).
                // Transition to new session so pong keeps the new session alive.
                // Old session ID (audioRemoteId) gets no more pongs → expires on IC-705.
                Log.i(TAG, "audioRx: session rotate old=0x${audioRemoteId.toString(16)} → new=0x${senderId.toString(16)}")
                audioRemoteId = senderId
            }
        }
        lastAudioPingMs = System.currentTimeMillis()
        val pong = ByteArray(PING_SIZE)
        putLE32(pong, 0, PING_SIZE.toLong())
        putLE16(pong, 4, 0x07)
        putLE16(pong, 6, getLE16(r, 6))
        putLE32(pong, 8, audioMyId)
        putLE32(pong, 12, audioRemoteId)
        pong[16] = 0x01
        r.copyInto(pong, 17, 17, 21)
        sendAudio(pong)
        return true
    }

    // ── Audio receive thread: runs continuously while connected ──
    // Handles audio pings (pong back), audio data (write to AudioTrack for SPK),
    // and sends keepalive (type=0x00) every 2s so IC-705 doesn't drop the audio session.
    private fun runAudioRx() {
        Log.i(TAG, "audioRxThread start")
        var pingCount = 0L; var dataCount = 0L
        var lastStatMs = System.currentTimeMillis()
        var lastKeepaliveMs = System.currentTimeMillis()
        var lastPingRxMs = 0L
        var lastPingGapWarnMs = 0L

        while (audioRxActive) {
            val sock = audioSocket ?: break
            val r = try {
                sock.soTimeout = 100
                val buf = ByteArray(2048)
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                buf.copyOf(pkt.length)
            } catch (e: SocketTimeoutException) {
                null
            } catch (e: Exception) {
                Log.e(TAG, "audioRx exit: ${e.javaClass.simpleName}: ${e.message}")
                break
            }

            // Send periodic keepalive every 2s using shared audioPktSeq.
            // Skip if TX audio was sent within 2s — data packets already serve as keepalive.
            val now = System.currentTimeMillis()
            if (now - lastKeepaliveMs > 2000L && audioRemoteId != 0L) {
                if (now - lastTxAudioMs > 2000L) {
                    sendAudio(makeCtrl(0x00, ++audioPktSeq and 0xFFFF, audioMyId, audioRemoteId))
                }
                lastKeepaliveMs = now
            }

            // Warn when audio pings go silent (helps pinpoint when IC-705 drops session)
            if (lastPingRxMs > 0 && now - lastPingRxMs > 1000L && now - lastPingGapWarnMs > 1000L) {
                Log.w(TAG, "audioRx: ping gap ${now - lastPingRxMs}ms (id=0x${audioRemoteId.toString(16)})")
                lastPingGapWarnMs = now
            }

            if (r == null) {
                if (now - lastStatMs > 10_000L) {
                    val route = audioTrack?.routedDevice?.type ?: -1
                    Log.i(TAG, "audioRx alive ping=$pingCount data=$dataCount spk=$spkEnabled trackState=${audioTrack?.playState} route=$route")
                    pingCount = 0; dataCount = 0; lastStatMs = now
                }
                continue
            }

            when {
                // Audio ping (21B, type=0x07, reply=0x00) → pong current session only
                r.size == PING_SIZE && getLE16(r, 4) == 0x07 && (r[16].toInt() and 0xFF) == 0x00 -> {
                    if (handleAudioPing(r)) { pingCount++; lastPingRxMs = now }
                }
                // Audio data (>24B, byte[16]=0x81) → play via AudioTrack
                r.size > AUDIO_HDR && (r[16].toInt() and 0xFF) == 0x81 -> {
                    val audioLen = r.size - AUDIO_HDR
                    if (audioLen > 0) {
                        if (dataCount == 0L) Log.i(TAG, "audioRx: first audio data size=${r.size} r[17]=0x${(r.getOrNull(17)?.toInt()?.and(0xFF))?.toString(16)}")
                        dataCount++
                        lastAudioDataMs = System.currentTimeMillis()
                        // Every 100 packets: sample audio to detect silence vs signal
                        if (dataCount % 100 == 0L) {
                            val nonZero = (AUDIO_HDR until minOf(r.size, AUDIO_HDR + 20)).any { r[it] != 0.toByte() }
                            Log.i(TAG, "audioRx: data[$dataCount] ${if (nonZero) "HAS SIGNAL" else "SILENCE"} len=${r.size}")
                        }
                        // During TX: discard IC-705 RX audio instead of writing to AudioTrack.
                        // AudioTrack is paused during TX; WRITE_BLOCKING on a full paused buffer
                        // blocks forever → this thread stops reading UDP → audio ping timestamps
                        // go stale → 5s watchdog fires → isConnected=false → PTT drops.
                        if (spkEnabled && !txAudioActive) {
                            val track = audioTrack
                            if (track != null) {
                                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    Log.w(TAG, "audioRx: AudioTrack stopped (state=${track.playState}), restarting")
                                    try { track.play() } catch (e: Exception) { Log.e(TAG, "AudioTrack.play: ${e.message}") }
                                }
                                val n = track.write(r, AUDIO_HDR, audioLen, AudioTrack.WRITE_BLOCKING)
                                if (n < 0) Log.e(TAG, "audioRx: write=$n state=${track.playState}")
                            }
                        }
                    }
                }
                // Single retransmit request (16B, type=0x0001): IC-705 detected a gap in outer seq.
                // Seq of missing packet is in bytes 6-7 (LE16). Resend twice per kappanhang convention.
                r.size == CONTROL_SIZE && getLE16(r, 4) == 0x0001 -> {
                    val missingSeq = getLE16(r, 6).toInt()
                    val cached = synchronized(txCacheGuard) { txRetransmitCache[missingSeq]?.copyOf() }
                    if (cached != null) {
                        sendAudio(cached); sendAudio(cached)
                        Log.d(TAG, "audioRx: retransmit seq=$missingSeq — resent x2")
                    } else {
                        // Cache miss: send idle with the missing seq so IC-705 skips past it
                        val idle = makeCtrl(0x00, missingSeq, audioMyId, audioRemoteId)
                        sendAudio(idle)
                        Log.d(TAG, "audioRx: retransmit seq=$missingSeq — not in cache, sent idle")
                    }
                }
                // Range retransmit request (20B, type=0x0001): seqs start=bytes[16:18] end=bytes[18:20] (LE16).
                r.size == 20 && getLE16(r, 4) == 0x0001 -> {
                    val startSeq = getLE16(r, 16).toInt()
                    val endSeq   = getLE16(r, 18).toInt()
                    Log.d(TAG, "audioRx: range retransmit $startSeq–$endSeq")
                    var s = startSeq
                    while (s != (endSeq + 1) and 0xFFFF) {
                        val cached = synchronized(txCacheGuard) { txRetransmitCache[s]?.copyOf() }
                        if (cached != null) { sendAudio(cached); sendAudio(cached) }
                        else sendAudio(makeCtrl(0x00, s, audioMyId, audioRemoteId))
                        s = (s + 1) and 0xFFFF
                    }
                }
                else -> {
                    val t = if (r.size >= 6) getLE16(r, 4) else -1
                    val s = if (r.size >= 8) getLE16(r, 6) else -1
                    Log.i(TAG, "audioRx: unhandled ${r.size}B type=0x${t.toString(16)} seq=$s hex=${r.take(8).joinToString(" ") { "%02x".format(it) }}")
                }
            }
        }
        Log.i(TAG, "audioRxThread end (ping=$pingCount data=$dataCount)")
    }

    private fun startAudioRxThread() {
        audioRxActive = true
        audioRxThread = Thread { runAudioRx() }.also {
            it.isDaemon = true; it.name = "civAudioRx"; it.start()
        }
    }

    private fun stopAudioRxThread() {
        audioRxActive = false
        // audioSocket.close() (called in disconnect) causes receive() to throw → thread exits
        audioRxThread?.join(500)
        audioRxThread = null
    }

    private fun initAudioTrack() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(8000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(maxOf(minBuf * 4, 16000))  // ~1s at 8kHz; resilient to brief network gaps
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
            Log.i(TAG, "AudioTrack init ok minBuf=$minBuf")
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack init failed: ${e.message}")
        }
    }

    private fun releaseAudioTrack() {
        audioTrack?.apply {
            try { stop() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        audioTrack = null
    }

    // ── Persistent AudioRecord thread ──
    // Runs while CI-V is connected (audioRecordRunning=true).
    // txAudioActive=false → reads mic and updates AGC but discards audio (keeps Android HAL-AGC warm).
    // txAudioActive=true  → processes and sends audio to IC-705.
    // This eliminates the 3-second HAL-AGC warmup on first PTT: by the time PTT is pressed,
    // the AGC is already converged and the gain is correct.
    private fun startAudioRecordThread() {
        if (txAudioThread?.isAlive == true) return
        audioRecordRunning = true
        txAudioActive = false
        txAudioThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            // Explicit error guard: getMinBufferSize() returns ERROR(-1) or ERROR_BAD_VALUE(-2)
            // when mic is unavailable (permission denied, audio system error).
            // Bail out early rather than proceeding with a bogus buffer size.
            val rawMin = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (rawMin <= 0) {
                Log.e(TAG, "AudioRecord.getMinBufferSize error=$rawMin — mic unavailable or permission denied")
                audioRecordRunning = false; return@Thread
            }
            val minBuf = maxOf(rawMin, 640)
            fun tryAudioRecord(source: Int): AudioRecord? = try {
                AudioRecord(source, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
                    .also { if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); throw Exception("not initialized") } }
            } catch (_: Exception) { null }
            // Source priority: UNPROCESSED (API 24+, truly raw — no AEC/AGC/NS) →
            //   VOICE_RECOGNITION (no NoiseSuppressor, partial processing) →
            //   VOICE_COMMUNICATION (fallback). UNPROCESSED mirrors iOS .measurement mode.
            val rec = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                    tryAudioRecord(MediaRecorder.AudioSource.UNPROCESSED)
                        ?.also { Log.i(TAG, "AudioRecord: UNPROCESSED") }
                else null)
                ?: tryAudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    ?.also { Log.i(TAG, "AudioRecord: VOICE_RECOGNITION") }
                ?: tryAudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    ?.also { Log.i(TAG, "AudioRecord: VOICE_COMMUNICATION (fallback)") }
                ?: run { Log.e(TAG, "AudioRecord: all sources failed"); audioRecordRunning = false; return@Thread }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized"); rec.release(); audioRecordRunning = false; return@Thread
            }
            // Disable NoiseSuppressor and AcousticEchoCanceler BEFORE startRecording().
            // AEC is the root cause of audio cuts at 5cm: IC-705 speaker/fan output is captured
            // by the mic, AEC treats it as echo and suppresses TX audio. Mirrors iOS .measurement
            // mode which disables all mic signal processing for raw ADC capture.
            val ns = if (android.media.audiofx.NoiseSuppressor.isAvailable())
                android.media.audiofx.NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = false }
            else null
            val aec = if (android.media.audiofx.AcousticEchoCanceler.isAvailable())
                android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = false }
            else null
            Log.i(TAG, "AudioRecord started — NoiseSuppressor=${ns != null} AcousticEchoCanceler=${aec != null} disabled")
            rec.startRecording()

            // Fixed gain + tanh soft limiter (no AGC state).
            // micGain slider (UI) scales the base gain; tanh maps any output smoothly into
            // (-HARD_LIMIT, +HARD_LIMIT) — no hard clipping, no pumping, no state to tune.
            // tanh(x/L)*L: linear for |x| << L, saturates near ±L for |x| >> L.
            val FIXED_GAIN  = 8f     // gain at micGain=1.0; typical Android mic (-20dBFS peak≈3277) → ~21k out
            val HARD_LIMIT  = 29491  // ≈ 0.9 × 32767; leaves IC-705 internal processing headroom
            val silence = ByteArray(320)
            val PKT_INTERVAL_NS = 20_000_000L
            val chunk = ByteArray(320)
            var txPktCount = 0L; var maxPeak = 0
            var nextSendNs = System.nanoTime()
            var prevTxActive = false
            halWarm = false
            var halWarmCount = 0

            while (audioRecordRunning) {
                if (audioSocket == null) { Thread.sleep(20); continue }
                val read = rec.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                val nowActive = txAudioActive

                val payload = if (read == chunk.size) chunk.copyOf() else chunk.copyOf(read)
                var sumSq = 0L
                for (i in 0 until payload.size step 2) {
                    val s = ((payload[i].toInt() and 0xFF) or ((payload[i+1].toInt() and 0xFF) shl 8)).toShort().toInt()
                    sumSq += s.toLong() * s
                }
                val rms = kotlin.math.sqrt((sumSq / (payload.size / 2)).toDouble()).toFloat()

                // HAL warmup: during init HAL mutes mic (rms≈0); ambient noise (rms>30) means ready.
                // Require 5 consecutive chunks (100ms) to avoid false positives from glitches.
                if (!halWarm) {
                    if (rms > 30f) halWarmCount++ else halWarmCount = 0
                    if (halWarmCount >= 5) {
                        halWarm = true
                        Log.i(TAG, "HAL warm after ${halWarmCount * 20}ms: rms=${"%.0f".format(rms)}")
                    }
                }

                // PTT start: wait for HAL to stop muting. Non-blocking: loop reads audio until warm.
                if (nowActive && !prevTxActive) {
                    if (!halWarm) continue
                    if (!audioSessionReady) Log.w(TAG, "txAudio: audioSessionReady=false — IC-705 may ignore packets")
                    Log.i(TAG, "txAudio start gain=${"%.1f".format(FIXED_GAIN * micGain)} micGain=${"%.2f".format(micGain)}")
                    repeat(6) { sendAudio(makeTxAudioPacket(silence)) }
                    nextSendNs = System.nanoTime()
                    txPktCount = 0L; maxPeak = 0
                }
                if (!nowActive && prevTxActive) {
                    Log.i(TAG, "txAudio end: sent $txPktCount maxPeak=$maxPeak")
                }
                prevTxActive = nowActive

                if (!nowActive) continue

                nextSendNs += PKT_INTERVAL_NS
                val totalGain = FIXED_GAIN * micGain
                val hl = HARD_LIMIT.toDouble()
                var outPeak = 0
                for (i in 0 until payload.size step 2) {
                    val s = ((payload[i].toInt() and 0xFF) or ((payload[i+1].toInt() and 0xFF) shl 8)).toShort().toInt()
                    val limited = (kotlin.math.tanh(s * totalGain / hl) * hl).toInt()
                    payload[i]     = (limited and 0xFF).toByte()
                    payload[i + 1] = ((limited ushr 8) and 0xFF).toByte()
                    val abs = if (limited < 0) -limited else limited
                    if (abs > outPeak) outPeak = abs
                }
                if (outPeak > maxPeak) maxPeak = outPeak
                if (txPktCount % 10 == 0L) Log.i(TAG, "txAudio rms=${"%.0f".format(rms)} gain=${"%.1f".format(totalGain)} outPeak=$outPeak maxOut=$maxPeak")
                txPktCount++
                val waitNs = nextSendNs - System.nanoTime()
                if (waitNs > 2_000_000L) Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                sendAudio(makeTxAudioPacket(payload))
                lastTxAudioMs = System.currentTimeMillis()
            }
            Log.i(TAG, "audioRecord thread exit")
            try { aec?.release() } catch (_: Exception) {}
            try { ns?.release() } catch (_: Exception) {}
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }.also { it.isDaemon = true; it.name = "civAudioRecord"; it.start() }
    }

    private fun startTxAudio() {
        if (txAudioThread?.isAlive != true) {
            Log.w(TAG, "startTxAudio: audioRecord thread not alive, restarting")
            startAudioRecordThread()
        }
        txAudioActive = true
    }

    private fun stopTxAudio() {
        txAudioActive = false
        // Reset audio-data timestamp so the IC-705 maintenance check doesn't fire immediately after PTT release.
        // During TX, IC-705 sends no audio data packets (it's transmitting our audio, not receiving).
        // Without this reset, lastAudioDataMs is stale by the TX duration → maintenance triggers on next poll.
        lastAudioDataMs = System.currentTimeMillis()
    }

    private fun stopAudioRecord() {
        audioRecordRunning = false
        txAudioActive = false
        txAudioThread?.join(2000)
        txAudioThread = null
    }

    // ── CI-V broadcast cache ──
    // Parse all FE FE frames in received CI-V data and cache by (cmd shl 8)|sub
    private fun cacheCivFrames(data: ByteArray) {
        var i = 0
        while (i < data.size - 1) {
            if ((data[i].toInt() and 0xFF) != 0xFE || (data[i+1].toInt() and 0xFF) != 0xFE) { i++; continue }
            val body = mutableListOf<Int>()
            var j = i + 2
            while (j < data.size) {
                val b = data[j].toInt() and 0xFF
                if (b == 0xFD) break
                body.add(b); j++
            }
            // Only cache IC-705→PC responses (dest=ctrlAddress=0xE0), not echoes
            if (body.size >= 3 && (body[0].toInt() and 0xFF) == ctrlAddress) {
                val cmd = body[2]
                val sub = if (body.size >= 4) body[3] else 0xFF
                val key = (cmd shl 8) or sub
                civCache[key] = Pair(body.map { it.toByte() }.toByteArray(), System.currentTimeMillis())
                Log.d(TAG, "civCache: cmd=0x${cmd.toString(16)} sub=0x${sub.toString(16)}")
            }
            i++
        }
    }

    // Retrieve a cached CI-V body if fresh enough.
    // sub<0 means "any sub for this cmd" (used for mode query where sub=mode code).
    private fun getCached(cmd: Int, sub: Int, maxAgeMs: Long = 3000L): ByteArray? {
        val now = System.currentTimeMillis()
        if (sub < 0) {
            return civCache.entries
                .filter { (k, _) -> (k shr 8) == cmd }
                .firstOrNull { (_, v) -> now - v.second < maxAgeMs }
                ?.value?.first
        }
        val key = (cmd shl 8) or (sub and 0xFF)
        val entry = civCache[key] ?: return null
        return if (now - entry.second < maxAgeMs) entry.first else null
    }

    private fun recvFrom(sock: DatagramSocket?, timeoutMs: Int): ByteArray? {
        val s = sock ?: return null
        return try {
            s.soTimeout = timeoutMs
            val buf = ByteArray(1024)
            val pkt = DatagramPacket(buf, buf.size)
            s.receive(pkt)
            val data = buf.copyOf(pkt.length)
            Log.d(TAG, "recv ${pkt.length}B: ${data.joinToString(" ") { "%02X".format(it) }}")
            data
        } catch (e: SocketTimeoutException) { null }
        catch (e: Exception) {
            // EBADF / socket closed is expected when disconnect() closes the socket while
            // a receive thread is blocking on recvfrom — suppress at Error level in that case.
            if (isConnected) Log.e(TAG, "recv: ${e.message}")
            null
        }
    }
    private fun recvCtrl(ms: Int) = recvFrom(ctrlSocket, ms)
    // Read from civRxThread's queue when the thread is running; fall back to socket-direct otherwise.
    private fun recvCiv(ms: Int): ByteArray? =
        if (civRxActive)
            if (ms <= 0) civRxQueue.pollFirst()
            else civRxQueue.pollFirst(ms.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        else recvFrom(civSocket, ms)

    private fun computeMyId(ip: Inet4Address, port: Int): Long {
        val b = ip.address
        val ip3 = b[2].toLong() and 0xFF
        val ip4 = b[3].toLong() and 0xFF
        return (ip3 shl 24) or (ip4 shl 16) or (port.toLong() and 0xFFFF)
    }

    private fun getLocalIp(dest: InetAddress, port: Int, network: android.net.Network?): Inet4Address? {
        return try {
            val s = DatagramSocket()
            network?.bindSocket(s)
            s.connect(dest, port)
            val local = s.localAddress as? Inet4Address
            s.close()
            local
        } catch (e: Exception) { Log.w(TAG, "getLocalIp: ${e.message}"); null }
    }

    // ── Connect ──
    fun connect(host: String, port1: Int, port2: Int, port3: Int,
                username: String, password: String,
                network: android.net.Network? = null): Boolean {
        val hadKeptCivSocket = keepCivSocket && civSocket != null
        disconnect()         // keepCivSocket flag controls whether civSocket is closed
        keepCivSocket = false
        lastError = ""
        ctrlSeq = 0; civPktSeq = 0; civSendSeqB = 0; authSeq = 0; audioTxSeq = 0
        pollFailCount = 0; token = 0L; tokRequest = 0; lastCtrlPingMs = 0L; lastAudioPingMs = 0L; lastAudioDataMs = 0L
        ctrlMyId = 0L; audioMyId = 0L
        if (!hadKeptCivSocket) { civMyId = 0L; civRemoteId = 0L }
        ctrlRemoteId = 0L; audioRemoteId = 0L
        transcieveEnabled = false
        savedUsername = username; savedMacAddr = null; savedRadioName = null
        lastStreamRenewalMs = 0L; lastRenewalAttemptMs = 0L; maintenanceDetectedMs = 0L; maintenanceLastRetryMs = 0L
        lastTokenRenewalMs = 0L
        civCache.clear()

        return try {
            civInetAddr = InetAddress.getByName(host)
            civPort1 = port1; civPort2 = port2
            val remoteAddr = civInetAddr!!

            val localIp = getLocalIp(remoteAddr, port1, network)
            Log.i(TAG, "localIp=$localIp host=$host")

            // Use ephemeral local ports (0) so ctrlMyId/civMyId differ each connect.
            // IC-705 keys stale sessions on ctrlMyId (derived from localIP:localPort).
            // A fixed port means the same ctrlMyId every connect → IC-705 may ignore
            // Login or skip the CI-V handshake until the stale session ages out.
            // Ephemeral ports give a fresh identity each time, matching iOS behaviour.
            ctrlSocket = try {
                DatagramSocket(null).also { s ->
                    s.bind(java.net.InetSocketAddress(localIp ?: InetAddress.getByName("0.0.0.0"), 0))
                    network?.bindSocket(s)
                    Log.i(TAG, "ctrlSocket bound ${s.localAddress}:${s.localPort}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ctrlSocket fallback: ${e.message}")
                DatagramSocket(0).also { network?.bindSocket(it) }
            }
            val ctrlLocalPort = ctrlSocket!!.localPort
            ctrlMyId = localIp?.let { computeMyId(it, ctrlLocalPort) } ?: (ctrlLocalPort.toLong() and 0xFFFF)

            if (!hadKeptCivSocket) {
                civSocket = try {
                    DatagramSocket(null).also { s ->
                        s.bind(java.net.InetSocketAddress(localIp ?: InetAddress.getByName("0.0.0.0"), 0))
                        network?.bindSocket(s)
                        Log.i(TAG, "civSocket bound ${s.localAddress}:${s.localPort}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "civSocket fallback: ${e.message}")
                    DatagramSocket(0).also { network?.bindSocket(it) }
                }
                civMyId = localIp?.let { computeMyId(it, civSocket!!.localPort) } ?: (civSocket!!.localPort.toLong() and 0xFFFF)
                // Start CI-V receive thread immediately after civSocket is ready.
                // It buffers all incoming CI-V packets so that transceive broadcasts arriving
                // between exchange() calls (including during dead window) are not lost.
                startCivRxThread()
            } else {
                Log.i(TAG, "keepCivSocket: reusing civSocket port=${civSocket!!.localPort} civMyId=0x${civMyId.toString(16)} civRemoteId=0x${civRemoteId.toString(16)}")
            }
            val civLocalPort = civSocket!!.localPort

            civPort3 = port3  // IC-705's audio port — we still SEND to here
            // Ephemeral local port: each reconnect gets a unique port so IC-705 can't accumulate
            // stale audio sessions for our IP. Old sessions expire on IC-705's pong timeout.
            audioSocket = try {
                val bindAddr = java.net.InetSocketAddress(localIp ?: InetAddress.getByName("0.0.0.0"), 0)
                DatagramSocket(null).also { s ->
                    s.bind(bindAddr); network?.bindSocket(s)
                    Log.i(TAG, "audioSocket bound ${s.localAddress}:${s.localPort}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "audioSocket fallback: ${e.message}")
                DatagramSocket(0).also { network?.bindSocket(it) }
            }
            val audioLocalPort = audioSocket!!.localPort
            audioMyId = localIp?.let { computeMyId(it, audioLocalPort) } ?: (audioLocalPort.toLong() and 0xFFFF)

            // ── Pre-wake: burst AYT to wake IC-705 from WiFi PSM (子機モード) ──
            Log.i(TAG, "pre-wake AYT burst")
            ctrlSocket!!.broadcast = true
            val aytWake = makeCtrl(0x03, 0, ctrlMyId, 0L)
            val bcastAddr: InetAddress? = localIp?.let {
                val b = it.address.clone(); b[3] = 0xFF.toByte()
                runCatching { InetAddress.getByAddress(b) }.getOrNull()
            }
            for (i in 0..4) {
                sendCtrl(aytWake)
                bcastAddr?.let { bc ->
                    try { ctrlSocket!!.send(DatagramPacket(aytWake, aytWake.size, bc, port1)) }
                    catch (_: Exception) {}
                }
                Thread.sleep(150)
            }
            Thread.sleep(300)

            // ── Step 1: AreYouThere → IAmHere ──
            // Loop for up to 90s so connect() catches IC-705 immediately when maintenance ends,
            // avoiding an extra 30s backoff cycle in the ViewModel retry logic.
            Log.i(TAG, "step1 AreYouThere (up to 90s)")
            var gotIAH = false
            val aytEnd = System.currentTimeMillis() + 90_000L
            var aytAttempt = 0
            while (System.currentTimeMillis() < aytEnd) {
                aytAttempt++
                sendCtrl(makeCtrl(0x03, 0, ctrlMyId, 0L))
                val r = recvCtrl(1000) ?: continue
                if (r.size == CONTROL_SIZE && getLE16(r, 4) == 0x04) {
                    ctrlRemoteId = getLE32(r, 8)
                    Log.i(TAG, "IAmHere attempt=$aytAttempt remoteId=0x${ctrlRemoteId.toString(16)}")
                    gotIAH = true; break
                }
            }
            if (!gotIAH) { lastError = "IC-705応答なし (AreYouThere 90s)"; disconnect(); return false }

            // Drain queued ctrl packets accumulated during AYT burst BEFORE sending AYR.
            // When IC-705 is already running, the 5-packet pre-wake burst generates 5 IAmHere (0x04)
            // responses that queue in the socket buffer and would interfere with the AYR step.
            run {
                var drained = 0
                while (recvCtrl(10) != null && drained++ < 30) { /* discard */ }
                Log.i(TAG, "drained $drained packets before AYR")
            }

            // ── Step 2: AreYouReady → IAmReady ──
            Log.i(TAG, "step2 AreYouReady")
            sendCtrl(makeCtrl(0x06, 1, ctrlMyId, ctrlRemoteId))
            var gotIAR = false
            val ayrEnd = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < ayrEnd) {
                val r = recvCtrl(500) ?: continue
                if (r.size == CONTROL_SIZE && getLE16(r, 4) == 0x06) { gotIAR = true; break }
            }
            if (!gotIAR) { lastError = "IC-705応答なし (IAmReady)"; disconnect(); return false }

            // ── Step 3: Login → LoginResponse ──
            Log.i(TAG, "step3 Login")
            tokRequest = Random.nextInt(0x0001, 0xFFFF)
            sendCtrl(makeLogin(++ctrlSeq, authSeq++, tokRequest, username, password))
            var gotLoginResp = false
            val loginEnd = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < loginEnd) {
                val r = recvCtrl(500) ?: continue
                if (r.size != LOGIN_RESPONSE_SIZE) continue
                val err = getLE32(r, 0x30)
                if (err == 0xFEFFFFFFL) { lastError = "認証失敗 (ユーザー名/パスワードが違う)"; disconnect(); return false }
                token = getLE32(r, 0x1c)
                Log.i(TAG, "LoginResp tok=0x${token.toString(16)}")
                gotLoginResp = true; break
            }
            if (!gotLoginResp) { lastError = "IC-705応答なし (Login)"; disconnect(); return false }

            // ── Step 4: Token → capabilities + CONNINFO ──
            Log.i(TAG, "step4 Token")
            sendCtrl(makeToken(++ctrlSeq, authSeq++, tokRequest, token, 0x02))
            var gotTokenResp = false
            var macFromCaps: ByteArray? = null
            var radioNameFromCaps: ByteArray? = null
            val CAPS_HDR = 0x42
            val tokenEnd = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < tokenEnd) {
                val r = recvCtrl(500) ?: continue
                Log.d(TAG, "token-wait ${r.size}B type=0x${getLE16(r, 4).toString(16)}")
                when {
                    r.size == TOKEN_SIZE && getLE16(r, 4) != 0x01 -> {
                        if ((r[21].toInt() and 0xFF) == 0x05) { gotTokenResp = true; break }
                    }
                    r.size == CONNINFO_SIZE && getLE16(r, 4) != 0x01 -> {
                        if (macFromCaps == null) macFromCaps = r.copyOfRange(0x2a, 0x30)
                        // Proceed even if IC-705 still has our previous session (0x60=1); we are the sole client
                        gotTokenResp = true; break
                    }
                    r.size > CONNINFO_SIZE && getLE16(r, 4) != 0x01 -> {
                        if (r.size >= CAPS_HDR + 0x10) macFromCaps = r.copyOfRange(CAPS_HDR + 0x0a, CAPS_HDR + 0x10)
                        if (r.size >= CAPS_HDR + 0x30) radioNameFromCaps = r.copyOfRange(CAPS_HDR + 0x10, CAPS_HDR + 0x30)
                        if (r.size >= CAPS_HDR + 0x53) {
                            val capsCivAddr = r[CAPS_HDR + 0x52].toInt() and 0xFF
                            if (capsCivAddr != 0) civAddress = capsCivAddr
                            Log.i(TAG, "caps civAddr=0x${civAddress.toString(16)}")
                        }
                    }
                }
            }
            if (!gotTokenResp) { lastError = "IC-705応答なし (Token)"; disconnect(); return false }
            savedMacAddr = macFromCaps; savedRadioName = radioNameFromCaps
            lastTokenRenewalMs = System.currentTimeMillis()  // start 60s token renewal timer

            // ── Step 5: RequestStream → STATUS ──
            Log.i(TAG, "step5 RequestStream")
            sendCtrl(makeRequestStream(++ctrlSeq, authSeq++, tokRequest, token, civLocalPort, audioLocalPort, username, macFromCaps, radioNameFromCaps))
            var gotStatus = false
            val rsEnd = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < rsEnd && !gotStatus) {
                val r = recvCtrl(500) ?: continue
                when (r.size) {
                    STATUS_SIZE -> {
                        val err = getLE32(r, 0x30)
                        if (err == 0xFFFFFFFFL) { lastError = "接続失敗 (Status ff)"; disconnect(); return false }
                        Log.i(TAG, "STATUS ok err=0x${err.toString(16)} civPort=${getBE16(r, 0x42)}")
                        gotStatus = true
                    }
                    CONNINFO_SIZE -> {
                        val busy = getLE32(r, 0x60)
                        if (busy <= 1L) {
                            sendCtrl(makeRequestStream(++ctrlSeq, authSeq++, tokRequest, token,
                                civLocalPort, audioLocalPort, username, r.copyOfRange(0x2a, 0x30), radioNameFromCaps))
                        }
                    }
                }
            }
            if (!gotStatus) { lastError = "IC-705応答なし (RequestStream)"; disconnect(); return false }

            // ── Step 5.5: Audio AYT → IAmHere → AYR → IAmReady ──
            // IC-705 may send pings instead of IAmHere on the first response; retry up to 5×
            Log.i(TAG, "step5.5 Audio AYT")
            audioSessionReady = false
            var audioSessionReadyLocal = false
            for (aytAttempt in 1..5) {
                sendAudio(makeCtrl(0x03, 0, audioMyId, 0L))
                Log.i(TAG, "Audio AYT attempt $aytAttempt")
                val waitEnd = System.currentTimeMillis() + 1000
                while (System.currentTimeMillis() < waitEnd) {
                    val audioRsp = recvFrom(audioSocket, 100) ?: continue
                    val rType = if (audioRsp.size >= 6) getLE16(audioRsp, 4) else -1
                    when {
                        audioRsp.size == CONTROL_SIZE && rType == 0x04 -> {
                            audioRemoteId = getLE32(audioRsp, 8)
                            Log.i(TAG, "Audio IAH audioRemoteId=0x${audioRemoteId.toString(16)}")
                            sendAudio(makeCtrl(0x06, 1, audioMyId, audioRemoteId))
                            val iarEnd = System.currentTimeMillis() + 1000
                            while (System.currentTimeMillis() < iarEnd) {
                                val iar = recvFrom(audioSocket, 100) ?: continue
                                when (getLE16(iar, 4)) {
                                    0x06 -> { Log.i(TAG, "Audio IAmReady ok"); audioSessionReadyLocal = true }
                                    0x07 -> handleAudioPing(iar)
                                }
                                if (audioSessionReadyLocal) break
                            }
                            break
                        }
                        audioRsp.size == PING_SIZE && rType == 0x07 -> handleAudioPing(audioRsp)
                    }
                    if (audioSessionReadyLocal) break
                }
                if (audioSessionReadyLocal) break
                if (aytAttempt < 5) Thread.sleep(200)
            }
            audioSessionReady = audioSessionReadyLocal
            Log.i(TAG, "Audio session ready=$audioSessionReady audioRemoteId=0x${audioRemoteId.toString(16)}")

            // ── Step 6: CIV socket handshake ──
            civRxQueue.clear()  // flush pings accumulated during maintenance/reconnect
            if (!hadKeptCivSocket) {
                Log.i(TAG, "step6 CIV handshake")
                var gotCivIAH = false; var gotCivReady = false
                val civEnd = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < civEnd) {
                    if (!gotCivIAH) sendCiv(makeCtrl(0x03, 0, civMyId, 0L))
                    val r = recvCiv(500) ?: continue
                    handlePingAndSendPong(r)
                    if (r.size != CONTROL_SIZE) continue
                    when (getLE16(r, 4)) {
                        0x04 -> {
                            civRemoteId = getLE32(r, 8)
                            Log.i(TAG, "CIV IAH civRemoteId=0x${civRemoteId.toString(16)}")
                            if (!gotCivIAH) {  // Send AYR exactly once; IC-705 resets CIV state on each AYR
                                gotCivIAH = true
                                sendCiv(makeCtrl(0x06, 1, civMyId, civRemoteId))
                                Log.i(TAG, "CIV AYR sent")
                            }
                        }
                        0x06 -> {
                            civRemoteId = getLE32(r, 8)
                            Log.i(TAG, "CIV IAmReady → OpenClose(open)")
                            sendCiv(makeOpenClose(++civPktSeq, civSendSeqB++, true, civMyId, civRemoteId))
                            gotCivReady = true; break
                        }
                    }
                }
                if (!gotCivReady && gotCivIAH) {
                    Log.w(TAG, "CIV: no type=0x06, sending OpenClose anyway")
                    sendCiv(makeOpenClose(++civPktSeq, civSendSeqB++, true, civMyId, civRemoteId))
                }
                if (!gotCivIAH) {
                    // IC-705 may have already established the CI-V session via ping/pong
                    // (civRxThread ponged its pings before step6 ran, so IC-705 skips IAH).
                    // If civRemoteId was learned from pings, send OpenClose and continue.
                    if (civRemoteId != 0L) {
                        Log.w(TAG, "CIV: no IAH in 8s but civRemoteId=0x${civRemoteId.toString(16)} (from pings) — sending OpenClose")
                        sendCiv(makeOpenClose(++civPktSeq, civSendSeqB++, true, civMyId, civRemoteId))
                    } else {
                        lastError = "IC-705 CIVソケット応答なし"; disconnect(); return false
                    }
                }
            } else {
                // AYR skipped: civSocket was kept — test whether AYR causes the CI-V dead window.
                // civRxThread has been ponging CI-V pings throughout the ctrl reconnect.
                // If CI-V responds without AYR → dead window avoided. If dead window still occurs → firmware timer.
                Log.i(TAG, "keepCivSocket: step6 AYR skipped — sending OpenClose only (no AYR)")
                sendCiv(makeOpenClose(++civPktSeq, civSendSeqB++, true, civMyId, civRemoteId))
            }

            // Send transceive enable immediately after OpenClose so IC-705 will auto-broadcast
            // frequency/mode/TX state as soon as its CI-V subsystem becomes ready.
            sendCiv(makeCivData(++civPktSeq, civSendSeqB++, buildCivFrame(0x16, 0x02, 0x01)))
            transcieveEnabled = true
            Log.i(TAG, "CIV transceive enable sent in connect()")

            lastOpenCloseMs = System.currentTimeMillis()

            startCtrlRxThread()
            // Seamless reconnect: reuse existing AudioTrack to avoid re-init overhead.
            if (!seamlessReconnect || audioTrack == null) initAudioTrack()
            else {
                Log.i(TAG, "seamless reconnect: reusing AudioTrack (buffer preserved)")
                if (spkEnabled) try { audioTrack?.play() } catch (_: Exception) {}
            }
            startAudioRxThread()

            civStreamOpened = false
            seamlessReconnect = false  // clear flag — next disconnect will release AudioTrack unless set again

            isConnected = true
            lastStreamRenewalMs = System.currentTimeMillis()  // start proactive renewal timer
            startAudioRecordThread()  // start persistent mic capture so HAL-AGC is warm before first PTT
            Log.i(TAG, "connect OK civAddr=0x${civAddress.toString(16)}")
            true
        } catch (e: Exception) {
            lastError = "接続失敗: ${e.message}"
            Log.e(TAG, lastError, e); disconnect(); false
        }
    }

    fun disconnect() {
        Log.i(TAG, "disconnect() called thread=${Thread.currentThread().id}", Throwable("disconnect stacktrace"))
        isConnected = false
        civStreamOpened = false
        audioSessionReady = false
        stopAudioRecord()  // stop persistent AudioRecord thread (joins with 2s timeout)
        ctrlRxActive = false   // signal ctrl thread to stop
        audioRxActive = false  // signal audio thread to stop

        val addr = civInetAddr
        // Token delete (magic=0x01): explicitly release IC-705 session so next connect() starts clean.
        // Skip during seamlessReconnect — ctrl session stays alive for handoff; CI-V socket preserved.
        if (!seamlessReconnect && addr != null && ctrlSocket != null && ctrlMyId != 0L && ctrlRemoteId != 0L && token != 0L) {
            try { sendCtrl(makeToken(++ctrlSeq, authSeq++, tokRequest, token, 0x01)) }
            catch (_: Exception) {}
        }
        if (!keepCivSocket && addr != null && civSocket != null && civMyId != 0L && civRemoteId != 0L) {
            try { sendCiv(makeOpenClose(++civPktSeq, civSendSeqB++, false, civMyId, civRemoteId)) }
            catch (_: Exception) {}
        }
        if (addr != null && ctrlSocket != null && ctrlRemoteId != 0L) {
            try {
                val disc = makeCtrl(0x05, 0, ctrlMyId, ctrlRemoteId)
                ctrlSocket?.send(DatagramPacket(disc, disc.size, addr, civPort1))
            } catch (_: Exception) {}
        }
        try { Thread.sleep(100) } catch (_: Exception) {}

        // For maintenance reconnect: keep AudioTrack playing so its 4s buffer bridges the gap.
        // For full disconnect (user action, error): stop and release AudioTrack.
        if (!seamlessReconnect) {
            pendingFreqHz = null
            pendingMode = null
            releaseAudioTrack()
        }

        // Close audioSocket first — causes audioRxThread's receive() to throw → thread exits
        try { audioSocket?.close() } catch (_: Exception) {}
        audioSocket = null
        audioRxThread?.join(500); audioRxThread = null

        // Close ctrlSocket — causes ctrlRxThread's recvCtrl() to throw → thread exits
        try { ctrlSocket?.close() } catch (_: Exception) {}
        ctrlSocket = null
        ctrlRxThread?.join(500); ctrlRxThread = null

        if (!keepCivSocket) {
            civRxActive = false
            try { civSocket?.close() } catch (_: Exception) {}
            civSocket = null; civInetAddr = null
            civRxThread?.join(400); civRxThread = null
            civRxQueue.clear()
        } else {
            Log.i(TAG, "disconnect: keepCivSocket — civSocket/civRxThread preserved")
        }
    }

    // ── CI-V frame builder ──
    private fun buildCivFrame(vararg bytes: Int): ByteArray {
        val b = ByteArray(4 + bytes.size + 1)
        b[0] = 0xFE.toByte(); b[1] = 0xFE.toByte()
        b[2] = civAddress.toByte(); b[3] = ctrlAddress.toByte()
        bytes.forEachIndexed { i, v -> b[4 + i] = v.toByte() }
        b[b.size - 1] = 0xFD.toByte()
        return b
    }

    // Send CI-V command and wait for matching response.
    // Also caches all CI-V broadcasts received during the wait (for transceive mode).
    @Synchronized
    private fun exchange(civFrame: ByteArray, cmd: Int, subCmd: Int = -1, timeoutMs: Int = 1500): ByteArray? {
        if (ctrlSocket == null || civSocket == null) return null
        // While CI-V stream is not yet confirmed open, use a short timeout so operations
        // fail quickly rather than blocking for 1500ms each during IC-705 maintenance.
        val effectiveTimeout = if (civStreamOpened) timeoutMs else minOf(timeoutMs, 400)
        return try {
            // CI-V keepalives and pong responses are now handled by civRxThread (like ctrl/audio).
            // Send OpenClose BEFORE the data query when stream not yet confirmed open.
            // IC-705 ignores CI-V data queries received before OpenClose; sending it first
            // ensures the stream is ready when the query arrives.
            if (!civStreamOpened) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastOpenCloseMs > 100) {
                    sendCiv(makeOpenClose(++civPktSeq, civSendSeqB++, true, civMyId, civRemoteId))
                    lastOpenCloseMs = nowMs
                }
            }

            val pkt = makeCivData(++civPktSeq, civSendSeqB++, civFrame)
            Log.d(TAG, "CIV-send ${pkt.size}B cmd=0x${cmd.toString(16)}")
            sendCiv(pkt)

            val deadline = System.currentTimeMillis() + effectiveTimeout
            while (System.currentTimeMillis() < deadline) {
                if (civSocket == null) { Log.w(TAG, "exchange: civSocket null → disconnect"); isConnected = false; return null }

                if (!civStreamOpened) {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastOpenCloseMs > 200) {
                        sendCiv(makeOpenClose(++civPktSeq, civSendSeqB++, true, civMyId, civRemoteId))
                        lastOpenCloseMs = nowMs
                    }
                }
                val r = recvCiv(50)
                if (r == null) {
                    // If IC-705 audio PCM has been silent for 400ms while pings are still
                    // alive and CI-V was open, this is a maintenance event. Bail out early
                    // so pollStatus() can detect silence and trigger seamless reconnect,
                    // rather than blocking here for the full 1500ms timeout.
                    val ad = lastAudioDataMs
                    if (civStreamOpened && ad > 0 && System.currentTimeMillis() - ad > 400L
                            && lastAudioPingMs > 0 && !lastKnownTx) {
                        return null
                    }
                    continue
                }
                handlePingAndSendPong(r)
                if (r.size != PING_SIZE || getLE16(r, 4) != 0x07) {
                    Log.d(TAG, "civRX ${r.size}B type=0x${if (r.size>=6) getLE16(r,4).toString(16) else "?"}")
                }
                // When CI-V stream is not yet open, IC-705 may re-initiate the
                // IAH→IAmHere→IAmReady handshake in response to our OpenClose.
                // Respond immediately so IC-705 processes the CI-V command we sent.
                if (!civStreamOpened && r.size == CONTROL_SIZE) {
                    when (getLE16(r, 4)) {
                        0x04 -> {  // IAH — respond with IAmHere
                            val sid = getLE32(r, 8)
                            if (civRemoteId == 0L || sid != civRemoteId) civRemoteId = sid
                            sendCiv(makeCtrl(0x06, 1, civMyId, civRemoteId))
                            continue
                        }
                        0x06 -> {  // IAmReady — resend the CI-V query
                            val sid = getLE32(r, 8)
                            if (civRemoteId == 0L || sid != civRemoteId) civRemoteId = sid
                            sendCiv(makeCivData(++civPktSeq, civSendSeqB++, civFrame))
                            continue
                        }
                    }
                }
                if (r.size <= PING_SIZE) continue
                if (getLE16(r, 4) == 0x01) continue
                val dataLen = getLE16(r, 17)
                if (r.size < PING_SIZE + dataLen || dataLen <= 0) continue
                val civData = r.copyOfRange(PING_SIZE, PING_SIZE + dataLen)
                Log.d(TAG, "CIV-data ${civData.size}B cmd?=${civData.getOrNull(2)?.let{"%02X".format(it)}}")

                // Cache ALL CI-V frames received (transceive broadcasts from IC-705)
                cacheCivFrames(civData)

                val body = extractCivBody(civData, cmd, subCmd)
                if (body != null) {
                    civStreamOpened = true
                    return body
                }
                // Accept cmd=0x00 broadcast for frequency queries
                if (cmd == 0x03) {
                    val bodyBroadcast = extractCivBody(civData, 0x00, -1)
                    if (bodyBroadcast != null) {
                        civStreamOpened = true
                        return bodyBroadcast
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "exchange: ${e.message}"); isConnected = false; null
        }
    }

    private fun extractCivBody(data: ByteArray, cmd: Int, subCmd: Int): ByteArray? {
        var i = 0
        while (i < data.size - 1) {
            if ((data[i].toInt() and 0xFF) != 0xFE || (data[i+1].toInt() and 0xFF) != 0xFE) { i++; continue }
            val body = mutableListOf<Int>()
            var j = i + 2
            while (j < data.size) {
                val b = data[j].toInt() and 0xFF
                if (b == 0xFD) break
                body.add(b); j++
            }
            // body[0]=dest, body[1]=src, body[2]=cmd
            // Only accept IC-705→PC responses (dest=ctrlAddress=0xE0).
            // Ignore echoes of our own queries (dest=civAddress).
            if (body.size >= 3 && (body[0].toInt() and 0xFF) == ctrlAddress && body[2] == cmd &&
                (subCmd < 0 || (body.size >= 4 && body[3] == subCmd))) {
                return body.map { it.toByte() }.toByteArray()
            }
            i++
        }
        return null
    }

    private fun bcdLevel(b0: Int, b1: Int): Int =
        ((b0 shr 4) * 1000) + ((b0 and 0xF) * 100) + ((b1 shr 4) * 10) + (b1 and 0xF)
    private fun bcdLevelBytes(v: Int): Pair<Int, Int> {
        val c = v.coerceIn(0, 9999)
        return Pair((c / 1000 shl 4) or (c / 100 % 10), (c / 10 % 10 shl 4) or (c % 10))
    }

    // ── Frequency ──
    fun getFrequency(): Long? {
        // Use cached value if fresh (0x03 response or 0x00 transceive broadcast)
        for (cacheCmd in intArrayOf(0x03, 0x00)) {
            getCached(cacheCmd, -1)?.let { body ->
                if (body.size >= 8) {
                    var freq = 0L; var mult = 1L
                    for (i in 3..7) { val b = body[i].toInt() and 0xFF; freq += (b and 0xF) * mult; mult *= 10; freq += (b shr 4) * mult; mult *= 10 }
                    Log.d(TAG, "freq from cache: $freq cmd=0x${cacheCmd.toString(16)}")
                    return freq
                }
            }
        }
        // Cache miss: send live query
        val r = exchange(buildCivFrame(0x03), 0x03) ?: return null
        if (r.size < 8) return null
        var freq = 0L; var mult = 1L
        for (i in 3..7) { val b = r[i].toInt() and 0xFF; freq += (b and 0xF) * mult; mult *= 10; freq += (b shr 4) * mult; mult *= 10 }
        return freq
    }

    fun setFrequency(hz: Long): Boolean {
        var f = hz
        val bcd = IntArray(5) { _ -> val lo = (f % 10).toInt(); f /= 10; val hi = (f % 10).toInt(); f /= 10; (hi shl 4) or lo }
        if (!civStreamOpened) {
            pendingFreqHz = hz
            // IC-705 ignores CI-V commands during the dead window; fire-and-forget was confirmed
            // ineffective and caused "sendCiv: null" errors when civSocket is closed during disconnect.
            Log.w(TAG, "setFrequency: CI-V dead window — queued ${hz}Hz, will apply when CI-V recovers")
            return false
        }
        val ok = exchange(buildCivFrame(0x05, bcd[0], bcd[1], bcd[2], bcd[3], bcd[4]), 0xFB) != null
        if (ok) pendingFreqHz = null  // clear only on success; keep queued if exchange failed
        return ok
    }

    // ── Mode ──
    data class CivMode(val name: String, val filterWidth: Int)

    private fun parseModeBody(body: ByteArray): CivMode? {
        if (body.size < 5) return null
        val mc = body[3].toInt() and 0xFF; val fc = body[4].toInt() and 0xFF
        val name = when (mc) {
            0x00 -> "LSB"; 0x01 -> "USB"; 0x02 -> "AM"; 0x03 -> "CW"
            0x04 -> "RTTY"; 0x05 -> "FM"; 0x06 -> "WFM"; 0x07 -> "CWR"
            0x08 -> "RTTYR"; 0x17 -> "DSTAR"; else -> "USB"
        }
        val w = when (mc) {
            0x03, 0x07 -> when (fc) { 0x03 -> 100; 0x02 -> 250; else -> 500 }
            0x02 -> 6000; 0x05 -> 15000; 0x06 -> 200000
            else -> when (fc) { 0x03 -> 500; 0x02 -> 1800; else -> 2400 }
        }
        return CivMode(name, w)
    }

    fun getMode(): CivMode? {
        getCached(0x04, -1)?.let { body ->
            parseModeBody(body)?.let { Log.d(TAG, "mode from cache: ${it.name}"); return it }
        }
        for (attempt in 1..3) {
            val r = exchange(buildCivFrame(0x04), 0x04, -1, 800) ?: continue
            parseModeBody(r)?.let { return it }
        }
        return null
    }

    fun setMode(mode: String, width: Int): Boolean {
        Log.i(TAG, "setMode: $mode/$width thread=${Thread.currentThread().id}")
        if (!civStreamOpened) {
            pendingMode = Pair(mode, width)
            Log.i(TAG, "setMode: CI-V not ready, queued $mode/$width for retry")
            return false
        }
        val mc = when (mode.uppercase()) {
            "LSB" -> 0x00; "USB" -> 0x01; "AM" -> 0x02; "CW" -> 0x03
            "RTTY" -> 0x04; "FM" -> 0x05; "WFM" -> 0x06; "CWR", "CW-R" -> 0x07
            "RTTYR" -> 0x08; else -> 0x01
        }
        val isCw = mc == 0x03 || mc == 0x07
        val fc = when {
            isCw -> when { width <= 150 -> 0x03; width <= 350 -> 0x02; else -> 0x01 }
            else -> when { width <= 600 -> 0x03; width <= 2000 -> 0x02; else -> 0x01 }
        }
        val ok = exchange(buildCivFrame(0x06, mc, fc), 0xFB) != null
        if (ok) pendingMode = null
        return ok
    }

    // ── S-meter ──
    fun getSmeterSignal(): Float? {
        // Try transceive broadcast cache first (IC-705 sends 0x15 0x02 broadcasts in transceive mode)
        getCached(0x15, 0x02)?.let { body ->
            if (body.size >= 6) {
                val raw = bcdLevel(body[4].toInt() and 0xFF, body[5].toInt() and 0xFF).toFloat()
                Log.d(TAG, "smeter from cache: raw=$raw")
                return (raw - 120f) * 54f / 120f
            }
        }
        // Explicit query with short timeout (S-meter may not respond if IC-705 is busy)
        val r = exchange(buildCivFrame(0x15, 0x02), 0x15, 0x02, 400) ?: return null
        if (r.size < 6) return null
        val raw = bcdLevel(r[4].toInt() and 0xFF, r[5].toInt() and 0xFF).toFloat()
        return (raw - 120f) * 54f / 120f
    }

    // ── TX / PTT ──
    fun getTxState(): Boolean? {
        // Try transceive broadcast cache (IC-705 sends 0x1C 0x00 on PTT change)
        getCached(0x1C, 0x00)?.let { body ->
            if (body.size >= 5) {
                val tx = (body[4].toInt() and 0xFF) == 0x01
                Log.d(TAG, "txState from cache: $tx")
                return tx
            }
        }
        val r = exchange(buildCivFrame(0x1C, 0x00), 0x1C, 0x00, 400) ?: return null
        return if (r.size >= 5) (r[4].toInt() and 0xFF) == 0x01 else null
    }

    fun setPtt(on: Boolean): Boolean {
        Log.i(TAG, "setPtt: ${if (on) "ON" else "OFF"} thread=${Thread.currentThread().id}")
        if (on) {
            // Clear pttOffPending immediately: if a previous PTT OFF timed out, pollStatus() would keep
            // sending PTT OFF fire-and-forget during this new TX, causing IC-705 to drop out of TX.
            pttOffPending = false
            pttIntent = true
            val ok = exchange(buildCivFrame(0x1C, 0x00, 0x01), 0xFB) != null
            if (ok && pttIntent) {
                lastKnownTx = true
                // Pause SPK during TX: Android AEC (echo canceller) cancels voice if AudioTrack plays IC-705 RX
                try { audioTrack?.pause(); audioTrack?.flush() } catch (_: Exception) {}
                startTxAudio()
            } else if (isConnected && pttIntent) {
                // exchange() timed out but connection is alive and user still wants PTT.
                // PTT ON frame WAS sent by exchange() — IC-705 may have processed it even without ACK.
                // Covers both: CI-V dead window (!civStreamOpened) and transient CI-V timeout (civStreamOpened=true).
                Log.w(TAG, "setPtt(true): exchange failed civStream=$civStreamOpened → PTT fire-and-forget, starting audio")
                lastKnownTx = true
                try { audioTrack?.pause(); audioTrack?.flush() } catch (_: Exception) {}
                startTxAudio()
            } else if (!pttIntent) {
                Log.i(TAG, "setPtt(true): pttIntent cleared before audio start — PTT already released, skipping")
            }
            return ok
        } else {
            pttIntent = false  // clear BEFORE stopTxAudio so concurrent setPtt(true) won't start audio
            // Stop audio FIRST so no TX packets arrive at IC-705 after PTT off command.
            // lastKnownTx must stay true until AFTER exchange() — the early bail in exchange()
            // checks !lastKnownTx; if audio is silent (IC-705 in TX) and we set it false first,
            // ALL subsequent exchanges bail immediately including this one.
            stopTxAudio()
            val ok = exchange(buildCivFrame(0x1C, 0x00, 0x00), 0xFB, timeoutMs = 3000) != null
            if (ok) {
                lastKnownTx = false
                pttOffPending = false
            } else {
                // exchange() timed out — DO NOT set lastKnownTx=false here.
                // IC-705 may still be in TX; setting false would trigger early bail for ALL
                // subsequent exchanges (frequency, poll, etc.) since IC-705 in TX sends no audio.
                // Set pttOffPending so pollStatus() keeps retrying PTT off until IC-705 confirms RX.
                pttOffPending = true
                if (isConnected) {
                    sendCiv(makeCivData(++civPktSeq, civSendSeqB++, buildCivFrame(0x1C, 0x00, 0x00)))
                    Log.w(TAG, "setPtt(false): exchange timeout, sent PTT off fire-and-forget; pttOffPending=true")
                }
            }
            // Resume SPK after TX if it was enabled by user
            if (spkEnabled) try { audioTrack?.play() } catch (_: Exception) {}
            return ok
        }
    }

    // CW PTT: fire-and-forget TX activate/deactivate without audio side effects.
    // Required before civKeyDown — IC-705 ignores SEND (0x1C 0x01) unless PTT is active
    // or BK-IN is enabled. Using explicit PTT makes CW work regardless of BK-IN setting.
    fun civPttDown() {
        if (!isConnected) return
        lastKnownTx = true
        sendCiv(makeCivData(++civPktSeq, civSendSeqB++, buildCivFrame(0x1C, 0x00, 0x01)))
    }
    fun civPttUp() {
        if (!isConnected) return
        lastKnownTx = false
        sendCiv(makeCivData(++civPktSeq, civSendSeqB++, buildCivFrame(0x1C, 0x00, 0x00)))
    }

    // 0x1C 0x01 = ATU control, NOT CW key — these were sending ATU commands accidentally.
    @Deprecated("Sent ATU command (0x1C 0x01), not CW key — use sendCwMessage() instead")
    fun civKeyDown() {}
    @Deprecated("Sent ATU command (0x1C 0x01), not CW key — use sendCwMessage() instead")
    fun civKeyUp() {}

    // IC-705 built-in CW keyer via CI-V 0x17. Requires BK-IN ON and CW mode.
    // text: filtered to 0-9 A-Z space /?,.@ (IC-705 supported chars), max 30 per call.
    fun sendCwMessage(text: String) {
        if (!isConnected) return
        val filtered = text.uppercase().filter { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ /?,.@" }.take(30)
        if (filtered.isEmpty()) return
        val textInts = filtered.map { it.code }.toIntArray()
        sendCiv(makeCivData(++civPktSeq, civSendSeqB++, buildCivFrame(0x17, *textInts)))
    }

    // Stop IC-705 CW keyer immediately (CI-V 0x17 with 0xFF stop byte).
    fun stopCwMessage() {
        if (!isConnected) return
        sendCiv(makeCivData(++civPktSeq, civSendSeqB++, buildCivFrame(0x17, 0xFF)))
    }

    // Set IC-705 CW key speed (CI-V 0x14 0x0C). wpm 6-48 mapped to BCD 0000-0255.
    fun setKeySpeed(wpm: Int) {
        if (!isConnected) return
        val raw = ((wpm.coerceIn(6, 48) - 6) * 255 / 42).coerceIn(0, 255)
        val (b0, b1) = bcdLevelBytes(raw)
        sendCiv(makeCivData(++civPktSeq, civSendSeqB++, buildCivFrame(0x14, 0x0C, b0, b1)))
    }

    // Send raw PCM audio chunk for CW-over-SSB (caller owns pacing — not thread safe with startTxAudio)
    fun sendTxAudioDirect(pcm: ByteArray, offset: Int, len: Int) {
        if (!isConnected) return
        val chunk = pcm.copyOfRange(offset, offset + len)
        sendAudio(makeTxAudioPacket(chunk))
    }

    // ── RF power ──
    fun getRfPower(): Float? {
        val r = exchange(buildCivFrame(0x14, 0x0A), 0x14, 0x0A, 400) ?: return null
        if (r.size < 6) return null
        return bcdLevel(r[4].toInt() and 0xFF, r[5].toInt() and 0xFF) / 255f
    }

    fun setRfPower(value: Float): Boolean {
        val raw = (value * 255).toInt().coerceIn(0, 255)
        val (b0, b1) = bcdLevelBytes(raw)
        return exchange(buildCivFrame(0x14, 0x0A, b0, b1), 0xFB) != null
    }

    // ── SQL ──
    fun getSql(): Float? {
        val r = exchange(buildCivFrame(0x14, 0x03), 0x14, 0x03, 400) ?: return null
        if (r.size < 6) return null
        return bcdLevel(r[4].toInt() and 0xFF, r[5].toInt() and 0xFF) / 255f
    }

    fun setSql(value: Float): Boolean {
        val raw = (value * 255).toInt().coerceIn(0, 255)
        val (b0, b1) = bcdLevelBytes(raw)
        return exchange(buildCivFrame(0x14, 0x03, b0, b1), 0xFB) != null
    }

    // ── Break-in ──
    fun getBkIn(): Boolean? {
        val r = exchange(buildCivFrame(0x16, 0x47), 0x16, 0x47, 400) ?: return null
        return if (r.size >= 5) (r[4].toInt() and 0xFF) != 0x00 else null
    }

    fun setBkIn(on: Boolean): Boolean {
        Log.i(TAG, "setBkIn: $on thread=${Thread.currentThread().id}")
        return exchange(buildCivFrame(0x16, 0x47, if (on) 0x01 else 0x00), 0xFB) != null
    }

    // ── Model / Modes ──
    fun getModelFromCivAddress(): String = when (civAddress) {
        0xA4 -> "IC-705"; 0x90 -> "IC-7300"; 0xA2 -> "IC-9700"
        0x98 -> "IC-7610"; 0x88 -> "IC-7100"; 0x70 -> "IC-7600"
        0x74 -> "IC-7700"; 0x8A -> "IC-7200"; else -> "ICOM (0x${civAddress.toString(16).uppercase()})"
    }

    fun getSupportedModes(): List<String> = when (civAddress) {
        0xA4, 0xA2 -> listOf("LSB", "USB", "AM", "CW", "CWR", "RTTY", "RTTYR", "FM", "WFM", "DSTAR")
        else -> listOf("LSB", "USB", "AM", "CW", "CWR", "RTTY", "RTTYR", "FM")
    }

    // Token + RequestStream on the existing ctrl session WITHOUT stopping audioRxThread.
    // audioRxThread continues answering audio pings throughout → IC-705 audio session stays alive,
    // no AYT/AYR needed, and audio PCM resumes as soon as IC-705 accepts RequestStream.
    // After STATUS ok, send OpenClose + transceive enable to refresh the CI-V data channel
    // (IC-705 pauses CI-V data during maintenance; OpenClose revives it immediately).
    // Returns true if IC-705 accepted the reconnect.
    private fun tryAudioOnlyReconnect(): Boolean {
        if (ctrlSocket == null || token == 0L || ctrlRemoteId == 0L) return false
        val civPort  = civSocket?.localPort ?: return false
        val audioPort = audioSocket?.localPort ?: return false
        val uname = savedUsername.ifEmpty { return false }
        Log.i(TAG, "tryAudioOnlyReconnect: Token+RequestStream (audioRxThread kept alive)")
        ctrlRxPaused = true
        Thread.sleep(210)  // wait for ctrlRxThread's recvCtrl(200) to return before we read
        return try {
            sendCtrl(makeToken(++ctrlSeq, authSeq++, tokRequest, token, 0x02))
            var tokenOk = false
            val tokenEnd = System.currentTimeMillis() + 1000L
            while (System.currentTimeMillis() < tokenEnd) {
                val r = recvCtrl(100) ?: continue
                if (r.size == PING_SIZE && getLE16(r, 4) == 0x07) { handleCtrlPingAndPong(r); continue }
                if (r.size >= TOKEN_SIZE) { tokenOk = true; break }
            }
            if (!tokenOk) { Log.w(TAG, "tryAudioOnlyReconnect: Token timeout"); return false }

            sendCtrl(makeRequestStream(++ctrlSeq, authSeq++, tokRequest, token,
                civPort, audioPort, uname, savedMacAddr, savedRadioName))
            val statusEnd = System.currentTimeMillis() + 1500L
            while (System.currentTimeMillis() < statusEnd) {
                val r = recvCtrl(100) ?: continue
                if (r.size == PING_SIZE && getLE16(r, 4) == 0x07) { handleCtrlPingAndPong(r); continue }
                if (r.size == STATUS_SIZE) {
                    val err = getLE32(r, 0x30)
                    if (err == 0xFFFFFFFFL) { Log.w(TAG, "tryAudioOnlyReconnect: err=FF — still in maintenance"); return false }
                    // Refresh CI-V data channel and re-enable transceive mode immediately
                    sendCiv(makeOpenClose(++civPktSeq, civSendSeqB++, true, civMyId, civRemoteId))
                    sendCiv(makeCivData(++civPktSeq, civSendSeqB++, buildCivFrame(0x16, 0x02, 0x01)))
                    val nowOk = System.currentTimeMillis()
                    lastOpenCloseMs = nowOk
                    lastAudioDataMs = nowOk
                    lastStreamRenewalMs = nowOk  // reset IC-705 maintenance timer
                    if (spkEnabled) try { audioTrack?.play() } catch (_: Exception) {}
                    Log.i(TAG, "tryAudioOnlyReconnect: SUCCESS — audio+CI-V refreshed, timer reset")
                    return true
                }
            }
            Log.w(TAG, "tryAudioOnlyReconnect: STATUS timeout")
            false
        } catch (e: Exception) {
            Log.w(TAG, "tryAudioOnlyReconnect exception: ${e.message}")
            false
        } finally {
            ctrlRxPaused = false
        }
    }

    // ── Poll ──
    // Drain civRxQueue for any CI-V data that arrived between polls (transceive broadcasts, etc.).
    // Uses the same packet classification as exchange(): size > PING_SIZE, not IAck, has valid dataLen,
    // and CI-V data starts with FE FE. This excludes IC-705 keep-alive pings (exactly PING_SIZE=21 bytes)
    // and OpenClose responses (OPENCLOSE_SIZE=22 but dataLen byte is not real CI-V data).
    // civStreamOpened is NOT set here — only exchange() sets it when a CI-V query is actually answered.
    private fun drainCivBroadcasts() {
        while (true) {
            val r = civRxQueue.pollFirst() ?: return
            handlePingAndSendPong(r)
            if (r.size > PING_SIZE && getLE16(r, 4) != 0x01) {
                val dataLen = getLE16(r, 17)
                if (dataLen > 0 && r.size >= PING_SIZE + dataLen) {
                    val civData = r.copyOfRange(PING_SIZE, PING_SIZE + dataLen)
                    if (civData.size >= 2 &&
                        (civData[0].toInt() and 0xFF) == 0xFE &&
                        (civData[1].toInt() and 0xFF) == 0xFE) {
                        cacheCivFrames(civData)
                        Log.d(TAG, "CI-V broadcast drained during dead window (${civData.size}B)")
                    }
                }
            }
        }
    }

    fun pollStatus(): CivStatus? {
        if (!isConnected) return null
        // Drain any CI-V data that arrived since last poll (buffered by civRxThread).
        // When IC-705 exits the dead window and starts sending transceive broadcasts,
        // this catches them immediately without waiting for exchange() to succeed.
        if (!civStreamOpened) drainCivBroadcasts()
        val now2 = System.currentTimeMillis()
        val ad = lastAudioDataMs
        val ap = lastAudioPingMs

        // IC-705 maintenance check FIRST — must precede all watchdogs.
        // During maintenance, audio PCM stops but ctrl/audio/CI-V pings remain alive.
        // Strategy: retry RequestStream on the EXISTING ctrl TCP socket every 10s.
        // When IC-705 exits maintenance it accepts → audio resumes without full ctrl reconnect
        // → civStreamOpened stays true → no CI-V dead window.
        // Full reconnect only if ctrl pings die or 90s timeout.
        if (maintenanceDetectedMs != 0L ||
            (ad > 0 && now2 - ad > 3000L && ap > 0 && now2 - ap < 20_000L && !lastKnownTx)) {
            if (maintenanceDetectedMs == 0L) {
                maintenanceDetectedMs = now2
                maintenanceLastRetryMs = 0L
                Log.w(TAG, "IC-705 maintenance: audio PCM silent ${now2 - ad}ms — will retry RequestStream on existing ctrl")
            }
            val elapsed = now2 - maintenanceDetectedMs
            // Ctrl pings stopped → IC-705 dropped WLAN — full reconnect
            val ctrlPing = lastCtrlPingMs
            if (ctrlPing > 0 && now2 - ctrlPing > 5000L) {
                Log.w(TAG, "IC-705 maintenance: ctrl pings stopped → full reconnect")
                maintenanceDetectedMs = 0L; maintenanceLastRetryMs = 0L
                seamlessReconnect = true; isConnected = false
                return null
            }
            // 90s failsafe → full reconnect with CI-V socket preserved
            if (elapsed > 90_000L) {
                Log.w(TAG, "IC-705 maintenance: 90s timeout → full reconnect")
                maintenanceDetectedMs = 0L; maintenanceLastRetryMs = 0L
                keepCivSocket = true; seamlessReconnect = true; isConnected = false
                return null
            }
            // Retry RequestStream on existing ctrl every 3s.
            // Not closing ctrl/CIV sockets avoids triggering IC-705's CI-V dead window.
            if (maintenanceLastRetryMs == 0L || now2 - maintenanceLastRetryMs > 3_000L) {
                maintenanceLastRetryMs = now2
                Log.i(TAG, "IC-705 maintenance: ${elapsed}ms — retrying RequestStream on existing ctrl")
                if (tryAudioOnlyReconnect()) {
                    Log.i(TAG, "IC-705 maintenance: resumed after ${elapsed}ms — no dead window!")
                    maintenanceDetectedMs = 0L; maintenanceLastRetryMs = 0L
                    lastAudioDataMs = now2  // prevent immediate re-trigger of maintenance detection
                } else {
                    Log.i(TAG, "IC-705 maintenance: still in maintenance — retry in 3s")
                }
            }
            return null
        }
        maintenanceDetectedMs = 0L; maintenanceLastRetryMs = 0L

        // During TX, IC-705 stops sending pings (it is receiving our audio, not transmitting its own).
        // Suppress watchdogs while actively sending TX audio, plus a 5s grace period after TX ends
        // to give IC-705 time to resume pings. Grace uses lastTxAudioMs (updated every 20ms during TX)
        // rather than lastKnownTx, which can get stuck at true and suppress watchdogs indefinitely.
        val msSinceLastTxAudio = now2 - lastTxAudioMs
        val inTxOrGrace = txAudioActive || (lastTxAudioMs > 0L && msSinceLastTxAudio < 5_000L)

        // Ctrl ping watchdog: IC-705 sends pings every ~60ms. If none for 5s, IC-705 dropped the WLAN session.
        val lp = lastCtrlPingMs
        if (lp > 0 && now2 - lp > 5000L && !inTxOrGrace) {
            Log.w(TAG, "ctrl ping watchdog: no ctrl ping for 5s → IC-705 WLAN disconnected")
            isConnected = false
            return null
        }
        // Audio ping watchdog: IC-705 also sends pings every ~60ms on port 50003.
        // If none for 5s while ctrl is alive AND maintenance is not active, audio session dropped.
        if (ap > 0 && now2 - ap > 5000L && !inTxOrGrace) {
            Log.w(TAG, "audio ping watchdog: no audio ping for 5s → IC-705 audio session dropped")
            isConnected = false
            return null
        }
        // Thread health watchdog: if audioRxThread died while we think we're connected, reconnect.
        if (audioRxThread?.isAlive != true) {
            Log.w(TAG, "audioRxThread dead while connected → reconnect")
            isConnected = false
            return null
        }

        val freq = getFrequency() ?: run {
            if (lastKnownTx) {
                Log.d(TAG, "freq timeout during TX — ignored")
            } else {
                pollFailCount++
                Log.w(TAG, "poll fail $pollFailCount/$POLL_FAIL_MAX")
                if (pollFailCount >= POLL_FAIL_MAX) isConnected = false
                // IC-705 closes CI-V data channel during its maintenance cycle.
                // Modelled after icom-wlan-node: keep hammering OpenClose(true) every 2s until
                // IC-705 responds. IC-705 only starts sending CI-V data after receiving OpenClose.
                if (pollFailCount == 3) {
                    Log.w(TAG, "CI-V stream dead ($pollFailCount fails) — reopening CI-V data channel")
                    sendCiv(makeOpenClose(++civPktSeq, civSendSeqB++, true, civMyId, civRemoteId))
                    lastOpenCloseMs = now2
                    civStreamOpened = false
                    transcieveEnabled = false
                }
            }
            return null
        }
        pollFailCount = 0

        // Enable CI-V transceive mode on first successful poll so IC-705 auto-broadcasts:
        // mode changes (0x04), TX state changes (0x1C/0x00), S-meter (0x15/0x02), etc.
        if (!transcieveEnabled) {
            val tvFrame = buildCivFrame(0x16, 0x02, 0x01)
            sendCiv(makeCivData(++civPktSeq, civSendSeqB++, tvFrame))
            transcieveEnabled = true
            Log.w(TAG, "CI-V stream recovered — transceive mode enabled")
        }
        // Apply any pending freq/mode queued during CI-V dead window.
        // Retried every poll until exchange() succeeds (clears the pending on success).
        pendingFreqHz?.also { hz ->
            Log.w(TAG, "applying pending setFrequency: ${hz}Hz")
            setFrequency(hz)
        }
        pendingMode?.also { (mode, width) ->
            Log.w(TAG, "applying pending setMode: $mode/$width")
            setMode(mode, width)
        }

        val signal = getSmeterSignal()
        val tx = getTxState() ?: lastKnownTx
        // Don't clobber TX state with stale cache while we're actively sending audio.
        // After setPtt(true), the IC-705 CI-V broadcast takes ~1s to arrive; until then
        // the cache still shows false → maintenance check fires spuriously → TX暴走.
        lastKnownTx = tx || txAudioActive
        if (!lastKnownTx) {
            pttOffPending = false
        } else if (pttOffPending && isConnected) {
            // PTT off exchange failed earlier; IC-705 still in TX — keep sending fire-and-forget
            sendCiv(makeCivData(++civPktSeq, civSendSeqB++, buildCivFrame(0x1C, 0x00, 0x00)))
            Log.w(TAG, "pollStatus: pttOffPending, IC-705 still TX, retrying PTT off")
        }
        pollModeCount++
        // Stagger slow polls (each adds ~400ms max): mode every 5, power/sql/bkin every 10 (offset)
        val mode  = if (pollModeCount % 5  == 0) getMode()     else null
        val power = if (pollModeCount % 10 == 1) getRfPower()   else null
        val sql   = if (pollModeCount % 10 == 6) getSql()       else null
        val bkIn  = if (pollModeCount % 10 == 8) getBkIn()      else null
        return CivStatus(freq, signal, lastKnownTx, mode, power, sql, bkIn)
    }

    data class CivStatus(
        val freq: Long, val signal: Float?, val tx: Boolean, val mode: CivMode?,
        val power: Float? = null, val sql: Float? = null, val bkIn: Boolean? = null
    )
}
