package com.ji1ore.wifi_rig_ctrl.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Local FT8 engine for CI-V mode.
 *
 * When enabled (useCIV=true, localFt8=true):
 *  - Records audio from the radio via Bluetooth SCO or USB mic at 12 kHz
 *  - Decodes each 15-second window using ft8lib (via Ft8Jni)
 *  - Encodes TX messages to PCM audio and plays via AudioTrack
 *  - Controls PTT via CI-V (CivTcpService or CivBtService)
 *
 * The engine emits SSE-equivalent events via [Listener.onSseEvent] with the
 * same JSON structure as the Pi's SSE endpoint, so Ft8Fragment.kt's existing
 * event handler works unchanged.
 */
class Ft8LocalEngine(
    private val context: Context,
    private val civTcp: CivTcpService?,
    private val civBt: CivBtService?,
    private val isBt: Boolean
) {

    interface Listener {
        /** Called on the main thread for each SSE-equivalent event. */
        fun onSseEvent(eventType: String, data: JSONObject)
        /** Called each time new spectrum data (waterfall bins) is available. */
        fun onSpectrumBins(bins: IntArray, newPeriod: Boolean, periodNo: Int, utcSec: Int)
    }

    var listener: Listener? = null

    // ── State ──────────────────────────────────────────────────────────────

    private enum class TxState { OFF, ONE_SHOT, AUTO, CQ_AUTO }
    @Volatile private var txState = TxState.OFF
    @Volatile private var autoMsg = ""
    @Volatile private var autoMode = "even"   // "even" | "odd"
    @Volatile private var txFreqHz = 1500f
    @Volatile private var myCall = ""
    @Volatile private var myGrid = ""
    @Volatile private var qsoDxCall = ""
    @Volatile private var qsoWaitFor = ""     // "snr" | "r_snr" | "rr73" | ""
    @Volatile private var periodNo = 0

    // ── Mode flag ──────────────────────────────────────────────────────────
    // WiFi CI-V:   RX audio from RS-BA1 at 8 kHz (civTcp != null, isBt=false).
    // BT hybrid:   BT CI-V control + RS-BA1 audio (civTcp=civ audioOnly, isBt=true).
    // BT pure:     RX audio from phone mic/cable at 12 kHz (civTcp=null, isBt=true).
    private val isWifiCiv = (civTcp != null)  // true = use RS-BA1 audio path

    // ── Audio config ───────────────────────────────────────────────────────

    private val SAMPLE_RATE    = if (isWifiCiv) 8000 else 12000
    private val TX_GAIN_DEFAULT = if (isWifiCiv) 0.43f else 0.5f
    private val SPECTRUM_CHUNK = if (isWifiCiv) 1280 else 1920  // ~0.16s per FT8 symbol
    @Volatile var isFt4: Boolean = false
    private val PERIOD_S:       Double  get() = if (isFt4) 7.5 else 15.0
    private val PERIOD_SAMPLES: Int     get() = (SAMPLE_RATE * PERIOD_S).toInt()
    @Volatile var rxGain: Float = 1.0f
    @Volatile var txGain: Float = TX_GAIN_DEFAULT

    // ── WiFi CI-V RX audio pipe ────────────────────────────────────────────
    // RS-BA1 audio packets (8 kHz LE16 PCM) are queued here from CivTcpService's audioRxThread.
    private val wifiRxChannel = Channel<ShortArray>(capacity = 400, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // ── Coroutine scope ────────────────────────────────────────────────────

    private var engineScope: CoroutineScope? = null
    private var rxJob: Job? = null

    // ── AudioRecord / AudioTrack ───────────────────────────────────────────

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var isRunning = false
    @Volatile private var isTxing = false

    // ── NTP offset (set by Ft8Fragment via NtpClient) ─────────────────────

    var ntpOffsetMs: Long = 0L

    // ── Public API ─────────────────────────────────────────────────────────

    fun start(myCallSign: String, myGridSquare: String) {
        if (isRunning) return
        myCall = myCallSign
        myGrid = myGridSquare
        isRunning = true
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        if (isWifiCiv) {
            civTcp!!.rxAudioCallback = { data, offset, len -> onWifiRxAudio(data, offset, len) }
            Log.i(TAG, "started myCall=$myCallSign mode=WiFiCIV sampleRate=$SAMPLE_RATE")
        } else {
            startAudioRecord()
            Log.i(TAG, "started myCall=$myCallSign mode=BT sampleRate=$SAMPLE_RATE")
        }
        rxJob = engineScope!!.launch {
            while (isRunning && isActive) {
                try { rxLoop() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Log.w(TAG, "rxLoop error, restarting: $e")
                    if (isRunning) delay(1000)
                }
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        if (isWifiCiv) {
            civTcp?.rxAudioCallback = null
            while (wifiRxChannel.tryReceive().isSuccess) {}  // drain stale samples
        }
        rxJob?.cancel()
        engineScope?.cancel()
        stopAudioRecord()
        stopAudioTrack()
        txState = TxState.OFF
        Log.i(TAG, "stopped")
    }

    fun setFt4Mode(enabled: Boolean) {
        isFt4 = enabled
        if (!isRunning) return
        // Cancel the current (possibly mid-15s) rxLoop and restart immediately with new period.
        if (isWifiCiv) engineScope?.launch { while (wifiRxChannel.tryReceive().isSuccess) {} }
        rxJob?.cancel()
        rxJob = engineScope?.launch {
            while (isRunning && isActive) {
                try { rxLoop() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Log.w(TAG, "rxLoop error, restarting: $e")
                    if (isRunning) delay(1000)
                }
            }
        }
        Log.i(TAG, "setFt4Mode: isFt4=$enabled, rxLoop restarted")
    }

    fun sendOneShotTx(msg: String, audioFreqHz: Int, mode: String) {
        txState = TxState.ONE_SHOT
        autoMsg = msg
        txFreqHz = audioFreqHz.toFloat()
        autoMode = mode
    }

    fun setAutoTx(active: Boolean, mode: String, msg: String, audioFreqHz: Int) {
        autoMode = mode
        txFreqHz = audioFreqHz.toFloat()
        if (active) {
            autoMsg = msg
            txState = TxState.AUTO
        } else {
            txState = TxState.OFF
        }
    }

    fun startCqAuto(active: Boolean, msg: String, mode: String,
                    audioFreqHz: Int, call: String, grid: String) {
        myCall = call.ifEmpty { myCall }
        myGrid = grid.ifEmpty { myGrid }
        txFreqHz = audioFreqHz.toFloat()
        autoMode = mode
        if (active) {
            autoMsg = msg
            txState = TxState.CQ_AUTO
        } else {
            txState = TxState.OFF
        }
    }

    /**
     * Start a manually-initiated QSO (equivalent to Pi's ft8StartQso).
     * @param dxCall   Target callsign
     * @param firstMsg First message to transmit
     * @param txMode   "even" or "odd"
     * @param audioFreqHz  TX audio frequency in Hz
     * @param initialState  0=step1(grid), 1=step2(snr), 2=step3(R-snr), 4=step4(rr73), 6=step5(73)
     */
    fun startQso(dxCall: String, firstMsg: String, txMode: String,
                 audioFreqHz: Int, initialState: Int) {
        qsoDxCall = dxCall
        qsoWaitFor = when (initialState) {
            0    -> "snr"
            1    -> "r_snr"
            2, 4 -> "rr73"
            else -> ""
        }
        autoMode  = txMode
        txFreqHz  = audioFreqHz.toFloat()
        autoMsg   = firstMsg
        txState   = TxState.AUTO
    }

    fun cancelQso() {
        qsoDxCall = ""
        qsoWaitFor = ""
        if (txState == TxState.AUTO) txState = TxState.OFF
    }

    // ── RX loop ────────────────────────────────────────────────────────────

    private suspend fun rxLoop() {
        while (isRunning && currentCoroutineContext().isActive) {
            val buf = ShortArray(PERIOD_SAMPLES)
            // Wait for start of next period boundary (UTC)
            val nowMs = System.currentTimeMillis() + ntpOffsetMs
            val periodMs = (PERIOD_S * 1000).toLong()
            val utcMs = nowMs % periodMs
            // If rawWait ≥ 90% of the period, we're right at a boundary (recording just ended).
            // Skip the wait so consecutive periods are recorded back-to-back without a full-period gap.
            val rawWait = periodMs - utcMs
            val waitMs = if (rawWait > periodMs * 9L / 10L) 0L else rawWait
            val periodNoInMinute = (nowMs % 60000L / periodMs).toInt()
            val alignedSec = (periodNoInMinute * PERIOD_S).toInt() % 60

            val pNo = ++periodNo

            // Announce period boundary for waterfall marker (current period)
            withContext(Dispatchers.Main) {
                listener?.onSpectrumBins(IntArray(0), true, pNo, alignedSec)
            }

            // TX/RX decisions must use the period we'll ACTUALLY be in after the wait.
            // When waitMs > 0 we act at the NEXT period boundary, so alignedSec (current
            // period) would produce an off-by-one even/odd error.
            val actingAlignedSec = if (waitMs == 0L) alignedSec else {
                val afterNoInMinute = ((nowMs + waitMs) % 60000L / periodMs).toInt()
                (afterNoInMinute * PERIOD_S).toInt() % 60
            }

            // Decide if we TX this period
            val shouldTx = shouldTransmitThisPeriod(actingAlignedSec)
            val txMsgThisPeriod = if (shouldTx) buildNextTxMsg() else null

            if (txMsgThisPeriod != null) {
                val pendingJson = JSONObject().apply {
                    put("msg", txMsgThisPeriod)
                    put("utc_at_tx", actingAlignedSec)
                }
                withContext(Dispatchers.Main) { listener?.onSseEvent("tx_pending", pendingJson) }
                // Show spectrum during pre-TX wait (same as RX path) so waterfall keeps scrolling.
                // Drain the channel just before TX starts — audio accumulated during the wait
                // would otherwise be processed at burst speed by the post-TX dispatchSpectrumDuringWait,
                // causing the waterfall to suddenly speed up (especially noticeable in FT4).
                if (waitMs > 50) dispatchSpectrumDuringWait(waitMs, pNo, actingAlignedSec)
                else delay(maxOf(0, waitMs))
                if (isWifiCiv) while (wifiRxChannel.tryReceive().isSuccess) {}
                try { doTx(txMsgThisPeriod) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w(TAG, "doTx error: $e") }
            } else {
                // 待機中もオーディオを読んでスペクトラムを送り、ウォーターフォールの凍結を防ぐ
                if (waitMs > 50) dispatchSpectrumDuringWait(waitMs, pNo, actingAlignedSec)
                else delay(maxOf(0, waitMs))
                // Drain stale audio that accumulated during the wait (period-alignment)
                if (isWifiCiv) while (wifiRxChannel.tryReceive().isSuccess) {}
                // Record period in chunks — also dispatches spectrum lines for waterfall
                val recorded = recordPeriodWithSpectrum(buf, pNo, actingAlignedSec)

                if (recorded >= buf.size / 2) {
                    val g = rxGain
                    val snapshot = if (g == 1.0f) buf.copyOf() else
                        ShortArray(buf.size) { i -> (buf[i] * g).toInt().coerceIn(-32768, 32767).toShort() }
                    val pDecoded = pNo
                    engineScope?.launch {
                        decodeAndDispatch(snapshot, pDecoded, actingAlignedSec)
                    }
                }
            }
        }
    }

    private fun shouldTransmitThisPeriod(alignedSec: Int): Boolean {
        if (txState == TxState.OFF) return false
        // FT8: even at 0,30s; odd at 15,45s → check % 30
        // FT4: even at 0,15,30,45s; odd at ~7,22,37,52s → check % 15
        val isEven = if (isFt4) (alignedSec % 15 == 0) else (alignedSec % 30 == 0)
        return when (autoMode) {
            "even" -> isEven
            "odd"  -> !isEven
            else   -> isEven
        }
    }

    private fun buildNextTxMsg(): String? {
        return when (txState) {
            TxState.OFF       -> null
            TxState.ONE_SHOT  -> autoMsg.also { txState = TxState.OFF }
            TxState.AUTO, TxState.CQ_AUTO -> autoMsg.ifEmpty { null }
        }
    }

    // ── TX ─────────────────────────────────────────────────────────────────

    private suspend fun doTx(msg: String) {
        if (!isRunning) return
        isTxing = true
        val gain = txGain
        Log.i(TAG, "TX start: msg=$msg isWifiCiv=$isWifiCiv txGain=$gain SAMPLE_RATE=$SAMPLE_RATE")

        try {
            val pcm = Ft8Jni.encode(msg, txFreqHz, SAMPLE_RATE, isFt4)
            if (pcm == null) {
                Log.e(TAG, "encode failed for: $msg")
                return
            }
            val maxPeak = pcm.maxOrNull() ?: 0
            Log.i(TAG, "TX encode OK: samples=${pcm.size} peak=$maxPeak txFreqHz=$txFreqHz")
            for (i in pcm.indices) {
                pcm[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
            }
            Log.i(TAG, "TX gain applied: peak after=${pcm.maxOrNull() ?: 0}")

            pttOn()
            delay(50)

            if (isWifiCiv) {
                streamViaCivTcp(pcm)
            } else {
                playPcm(pcm)
            }

            pttOff()

            val sentJson = JSONObject().apply { put("msg", msg) }
            withContext(Dispatchers.Main) { listener?.onSseEvent("auto_tx_sent", sentJson) }

            if (msg.uppercase().trimEnd().endsWith("73") ||
                msg.uppercase().trimEnd().endsWith("RR73")) {
                handleSentFinalMsg()
            }

        } finally {
            isTxing = false
        }
    }

    private fun pttOn() {
        if (isBt) setPtt(true)           // BT (hybrid or pure): PTT via BT RFCOMM
        else civTcp?.civPttDown()        // WiFi CI-V: PTT via RS-BA1 CI-V stream
    }

    private fun pttOff() {
        if (isBt) setPtt(false)          // BT (hybrid or pure): PTT via BT RFCOMM
        else {
            civTcp?.civPttUp()
            civTcp?.notifyFt8TxEnd()
        }
    }

    /** Stream FT8 PCM via RS-BA1 in 20ms chunks. Volume-independent (bypasses AudioTrack). */
    private suspend fun streamViaCivTcp(pcm: ShortArray) {
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[i * 2]     = (pcm[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        val CHUNK = 320           // 160 samples × 2 bytes = 20ms at 8 kHz
        val INTERVAL_NS = 20_000_000L
        var offset = 0
        var pktCount = 0
        var nextSendNs = System.nanoTime()
        Log.i(TAG, "streamViaCivTcp: start totalBytes=${bytes.size}")
        while (offset < bytes.size && isRunning) {
            if (civTcp?.isConnected != true) {
                Log.w(TAG, "streamViaCivTcp: connection lost at pkt=$pktCount — aborting TX")
                break
            }
            val len = minOf(CHUNK, bytes.size - offset)
            civTcp!!.sendTxAudioDirect(bytes, offset, len)
            offset += len; pktCount++
            nextSendNs += INTERVAL_NS
            val waitNs = nextSendNs - System.nanoTime()
            if (waitNs > 1_000_000L) Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
        }
        Log.i(TAG, "streamViaCivTcp: done pktSent=$pktCount")
    }

    private suspend fun handleSentFinalMsg() {
        withContext(Dispatchers.Main) {
            when (txState) {
                TxState.CQ_AUTO -> {
                    // Pi-mode: cq_auto_restart restores CQ. Here we reset state.
                    qsoDxCall = ""; qsoWaitFor = ""
                    val restartJson = JSONObject().apply { put("msg", autoMsg) }
                    listener?.onSseEvent("cq_auto_restart", restartJson)
                }
                TxState.AUTO -> {
                    val doneDx = qsoDxCall
                    txState = TxState.OFF
                    qsoDxCall = ""; qsoWaitFor = ""
                    val doneJson = JSONObject().apply {
                        put("dx_call", doneDx)
                        put("my_call", myCall)
                        put("snr_rcvd", ""); put("snr_sent", "")
                    }
                    listener?.onSseEvent("qso_done", doneJson)
                }
                else -> {}
            }
        }
    }

    // ── Audio playback ─────────────────────────────────────────────────────

    /** BT CI-V TX: play PCM via AudioTrack at fixed max volume (restoring original after TX). */
    private fun playPcm(pcm: ShortArray) {
        // Force max stream volume so TX level is independent of the phone's media volume setting.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val savedVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol   = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)

        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack = track
            track.write(pcm, 0, pcm.size)
            track.play()

            val durationMs = (pcm.size.toLong() * 1000L / SAMPLE_RATE)
            Thread.sleep(durationMs + 100L)

            track.stop()
            track.release()
            audioTrack = null
        } finally {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0)
        }
    }

    private fun stopAudioTrack() {
        audioTrack?.apply { try { stop(); release() } catch (_: Exception) {} }
        audioTrack = null
    }

    // ── Audio recording ────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startAudioRecord() {
        val rawMin = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (rawMin <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize error=$rawMin — mic unavailable")
            isRunning = false; return
        }
        val bufSize = maxOf(rawMin, PERIOD_SAMPLES * 2)
        fun trySource(src: Int): AudioRecord? = try {
            AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize)
                .also { if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); throw Exception("not initialized") } }
        } catch (_: Exception) { null }
        val rec = if (isBt) {
            trySource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        } else {
            // UNPROCESSED (raw ADC, no AEC/AGC/NS) preferred for FT8 decode accuracy.
            // Fallback chain mirrors CivTcpService to handle devices that restrict MIC (source=1).
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                trySource(MediaRecorder.AudioSource.UNPROCESSED) else null)
                ?: trySource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                ?: trySource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                ?: trySource(MediaRecorder.AudioSource.MIC)
        }
        if (rec == null) {
            Log.e(TAG, "AudioRecord init failed — all sources failed, RECORD_AUDIO permission may be denied")
            isRunning = false; return
        }
        audioRecord = rec
        rec.startRecording()
        Log.i(TAG, "AudioRecord started source=${rec.audioSource}")
    }

    private fun stopAudioRecord() {
        audioRecord?.apply { try { stop(); release() } catch (_: Exception) {} }
        audioRecord = null
    }

    // Records one 15-second period in chunks, dispatching spectrum lines for the waterfall.
    // Returns the number of samples actually written into buf.
    private suspend fun recordPeriodWithSpectrum(buf: ShortArray, pNo: Int, utcSec: Int): Int =
        if (isWifiCiv) recordPeriodWifiCiv(buf, pNo, utcSec)
        else           recordPeriodFromMic(buf, pNo, utcSec)

    /** WiFi CI-V RX: drain the wifiRxChannel (fed by CivTcpService's audioRxThread via RS-BA1). */
    private suspend fun dispatchSpectrumDuringWait(waitMs: Long, pNo: Int, utcSec: Int) {
        val deadline = System.currentTimeMillis() + waitMs
        if (isWifiCiv) {
            // Use the same SPECTRUM_CHUNK accumulator as recordPeriodWifiCiv so the waterfall
            // scrolls at the same rate whether we're in the wait phase or the record phase.
            // Without this, a burst of buffered RS-BA1 packets (e.g. after IC-705 maintenance)
            // would dispatch spectrum at one-event-per-packet (50/s) instead of one-per-1280-samples
            // (6.25/s), causing the FT4 waterfall to appear to speed up 8×.
            val accum = ShortArray(SPECTRUM_CHUNK)
            var accPos = 0
            while (System.currentTimeMillis() < deadline && isRunning) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val samples = try {
                    withTimeoutOrNull(minOf(300L, remaining)) { wifiRxChannel.receive() }
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { break }
                if (samples == null) {
                    withContext(Dispatchers.Main) {
                        listener?.onSpectrumBins(IntArray(256) { 0 }, false, pNo, utcSec)
                    }
                } else {
                    var si = 0
                    while (si < samples.size) {
                        val toCopy = minOf(samples.size - si, SPECTRUM_CHUNK - accPos)
                        samples.copyInto(accum, accPos, si, si + toCopy)
                        si     += toCopy
                        accPos += toCopy
                        if (accPos >= SPECTRUM_CHUNK) {
                            val bins = computeSpectrum(accum, SPECTRUM_CHUNK)
                            withContext(Dispatchers.Main) { listener?.onSpectrumBins(bins, false, pNo, utcSec) }
                            accPos = 0
                        }
                    }
                }
            }
        } else {
            val rec = audioRecord
            if (rec == null) { delay(waitMs); return }
            val chunk = ShortArray(SPECTRUM_CHUNK)
            while (System.currentTimeMillis() < deadline && isRunning) {
                val n = rec.read(chunk, 0, SPECTRUM_CHUNK)
                if (n > 0) {
                    val bins = computeSpectrum(chunk, n)
                    withContext(Dispatchers.Main) { listener?.onSpectrumBins(bins, false, pNo, utcSec) }
                } else if (n < 0) {
                    delay(20)
                }
            }
        }
    }

    private suspend fun recordPeriodWifiCiv(buf: ShortArray, pNo: Int, utcSec: Int): Int {
        var pos = 0
        val accum = ShortArray(SPECTRUM_CHUNK)
        var accPos = 0

        while (pos < buf.size && isRunning && !isTxing) {
            val samples = try {
                withTimeoutOrNull(300) { wifiRxChannel.receive() }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { break }

            if (samples == null) {
                // No audio packet arrived — dispatch flat spectrum to keep waterfall scrolling
                withContext(Dispatchers.Main) {
                    listener?.onSpectrumBins(IntArray(256) { 0 }, false, pNo, utcSec)
                }
                continue
            }

            // Append samples into accum; flush one SPECTRUM_CHUNK at a time to buf + waterfall.
            var si = 0
            while (si < samples.size) {
                val toCopyToAccum = minOf(samples.size - si, SPECTRUM_CHUNK - accPos)
                samples.copyInto(accum, accPos, si, si + toCopyToAccum)
                si     += toCopyToAccum
                accPos += toCopyToAccum

                if (accPos >= SPECTRUM_CHUNK) {
                    // Safe copy to buf — guard the last partial chunk at period boundary
                    val toCopyToBuf = minOf(SPECTRUM_CHUNK, buf.size - pos)
                    if (toCopyToBuf > 0) {
                        accum.copyInto(buf, pos, 0, toCopyToBuf)
                        pos += toCopyToBuf
                    }
                    val bins = computeSpectrum(accum, SPECTRUM_CHUNK)
                    withContext(Dispatchers.Main) { listener?.onSpectrumBins(bins, false, pNo, utcSec) }
                    accPos = 0
                    if (pos >= buf.size) break
                }
            }
            if (pos >= buf.size) break
        }
        return pos
    }

    /** BT CI-V RX: read from AudioRecord (phone mic via audio cable from radio). */
    private suspend fun recordPeriodFromMic(buf: ShortArray, pNo: Int, utcSec: Int): Int {
        val rec = audioRecord ?: return 0
        var pos = 0
        val chunk = ShortArray(SPECTRUM_CHUNK)

        while (pos < buf.size && isRunning && !isTxing) {
            val want = minOf(SPECTRUM_CHUNK, buf.size - pos)
            val n = rec.read(chunk, 0, want)
            if (n < 0) {
                Log.w(TAG, "AudioRecord.read error=$n")
                withContext(Dispatchers.Main) {
                    listener?.onSpectrumBins(IntArray(256) { 0 }, false, pNo, utcSec)
                }
                delay(20)
                continue
            }
            if (n == 0) continue
            chunk.copyInto(buf, pos, 0, n)
            pos += n

            val bins = computeSpectrum(chunk, n)
            withContext(Dispatchers.Main) { listener?.onSpectrumBins(bins, false, pNo, utcSec) }
        }
        return pos
    }

    /** Called from CivTcpService's audioRxThread — decodes RS-BA1 LE16 PCM and queues it for RX decode. */
    private fun onWifiRxAudio(data: ByteArray, offset: Int, len: Int) {
        if (!isRunning || isTxing || len < 2) return
        val samples = ShortArray(len / 2)
        for (i in samples.indices) {
            val lo = data[offset + i * 2].toInt() and 0xFF
            val hi = data[offset + i * 2 + 1].toInt() and 0xFF
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        wifiRxChannel.trySend(samples)  // non-blocking; DROP_OLDEST policy handles backpressure
    }

    // ── Decode ─────────────────────────────────────────────────────────────

    private suspend fun decodeAndDispatch(samples: ShortArray, pNo: Int, utcSec: Int) {
        val dxCallSnapshot = qsoDxCall

        val results = Ft8Jni.decode(
            samples = samples,
            rate = SAMPLE_RATE,
            freqMin = 100f,
            freqMax = 3000f,
            myCall = myCall,
            dxCall = dxCallSnapshot,
            isFt4 = isFt4
        )

        if (results.isEmpty()) return

        for (jsonStr in results) {
            try {
                val obj = JSONObject(jsonStr)
                val msg = obj.optString("msg", "")
                val freq = obj.optDouble("freq", 0.0).toInt()
                val snr  = obj.optInt("snr", 0)
                val dt   = obj.optDouble("dt", 0.0)

                val dispatchObj = JSONObject().apply {
                    put("freq", freq); put("snr", snr); put("dt", dt)
                    put("msg", msg); put("period", pNo); put("utc_sec", utcSec)
                }
                withContext(Dispatchers.Main) {
                    listener?.onSseEvent("decode_msg", dispatchObj)
                }

                // QSO state machine: process incoming message
                handleIncomingMsg(msg, freq, snr, pNo)

            } catch (e: Exception) {
                Log.w(TAG, "parse error: $e")
            }
        }
    }

    // ── QSO state machine ──────────────────────────────────────────────────

    // Strip FT8 compound-callsign angle brackets: "<8J4ARDF>" → "8J4ARDF"
    private fun stripBrackets(s: String) = s.trimStart('<').trimEnd('>')

    private suspend fun handleIncomingMsg(msg: String, freq: Int, snr: Int, pNo: Int) {
        val parts = msg.uppercase().trim().split(Regex("\\s+"))
        if (parts.size < 2) return

        when (txState) {
            TxState.CQ_AUTO -> {
                // CQ mode: look for someone calling us.
                // Standard:  "DXCALL  MYCALL  REPORT"  → parts[0]=DX, parts[1]=MYCALL
                // Compound:  "MYCALL <DXCALL> REPORT"  → parts[0]=MYCALL, parts[1]=<DX>
                val myCallUp = myCall.uppercase()
                val (dx, report) = when {
                    parts.size >= 3 && parts[1] == myCallUp ->
                        Pair(parts[0], parts[2])                      // standard
                    parts.size >= 3 && parts[0] == myCallUp && parts[1].startsWith("<") ->
                        Pair(stripBrackets(parts[1]), parts[2])       // compound
                    else -> return
                }
                qsoDxCall = dx
                qsoWaitFor = "r_snr"
                txFreqHz = freq.toFloat()

                // Build R+SNR response
                val rsnr = if (report.startsWith("R")) report
                           else buildSnrReport(snr, rPrefix = true)
                val replyMsg = "${myCallUp} ${dx.uppercase()} $rsnr"
                autoMsg = replyMsg
                txState = TxState.AUTO

                val stateJson = JSONObject().apply {
                    put("state", "calling_back")
                    put("msg", replyMsg); put("dx_call", dx)
                    put("my_call", myCall); put("snr_rcvd", report); put("snr_sent", "")
                }
                withContext(Dispatchers.Main) { listener?.onSseEvent("qso_state", stateJson) }
            }

            TxState.AUTO -> {
                if (qsoDxCall.isEmpty()) return
                val dx = qsoDxCall.uppercase()
                val myCallUp = myCall.uppercase()

                // DX responding to us.
                // Standard:  "DXCALL  MYCALL  WORD"   → parts[0]=DX, parts[1]=MYCALL
                // Compound:  "MYCALL <DXCALL> WORD"   → parts[0]=MYCALL, parts[1]=<DX>
                val word = when {
                    parts.size >= 3 && parts[0] == dx && parts[1] == myCallUp ->
                        parts[2]
                    parts.size >= 3 && parts[0] == myCallUp && stripBrackets(parts[1]) == dx ->
                        parts[2]
                    else -> return
                }
                when {
                    word == "RR73" -> {
                        // DX confirmed exchange — queue our 73 reply, then doTx → handleSentFinalMsg fires qso_done
                        qsoWaitFor = "73"
                        autoMsg = "${myCall.uppercase()} ${dx.uppercase()} 73"
                        val stateJson = JSONObject().apply {
                            put("state", "sending_73")
                            put("msg", autoMsg); put("dx_call", dx)
                            put("my_call", myCall); put("snr_rcvd", word); put("snr_sent", "")
                        }
                        withContext(Dispatchers.Main) { listener?.onSseEvent("qso_state", stateJson) }
                    }
                    word == "73" -> {
                        // DX sent plain 73, QSO done (no reply needed)
                        val doneJson = JSONObject().apply {
                            put("dx_call", dx); put("my_call", myCall)
                            put("snr_rcvd", word); put("snr_sent", "")
                        }
                        withContext(Dispatchers.Main) { listener?.onSseEvent("qso_done", doneJson) }
                        txState = TxState.OFF
                        qsoDxCall = ""; qsoWaitFor = ""
                    }
                    word.startsWith("R") && word.drop(1).toIntOrNull() != null -> {
                        // R+SNR: DX confirmed our report, now send RR73
                        qsoWaitFor = "rr73"
                        autoMsg = "${myCall.uppercase()} ${dx.uppercase()} RR73"
                        val stateJson = JSONObject().apply {
                            put("state", "sending_rr73")
                            put("msg", autoMsg); put("dx_call", dx)
                            put("my_call", myCall); put("snr_rcvd", word); put("snr_sent", "")
                        }
                        withContext(Dispatchers.Main) { listener?.onSseEvent("qso_state", stateJson) }
                    }
                    word.toIntOrNull() != null || word.startsWith("-") || word.startsWith("+") -> {
                        // SNR report: reply with R+SNR
                        qsoWaitFor = "r_snr"
                        autoMsg = "${myCall.uppercase()} ${dx.uppercase()} ${buildSnrReport(snr, true)}"
                        val stateJson = JSONObject().apply {
                            put("state", "sending_r_snr")
                            put("msg", autoMsg); put("dx_call", dx)
                            put("my_call", myCall); put("snr_rcvd", word); put("snr_sent", "")
                        }
                        withContext(Dispatchers.Main) { listener?.onSseEvent("qso_state", stateJson) }
                    }
                    else -> {}
                }
            }

            else -> {}
        }
    }

    private fun buildSnrReport(snr: Int, rPrefix: Boolean): String {
        val clamped = snr.coerceIn(-30, 30)
        val str = if (clamped >= 0) "+%02d".format(clamped) else "%+03d".format(clamped)
        return if (rPrefix) "R$str" else str
    }

    // ── PTT control via CI-V ───────────────────────────────────────────────

    private fun setPtt(on: Boolean) {
        try {
            if (isBt) civBt?.setPtt(on)
            else      civTcp?.setPtt(on)
        } catch (e: Exception) {
            Log.w(TAG, "setPtt($on) error: ${e.message}")
        }
    }

    // ── Spectrum (waterfall) ───────────────────────────────────────────────

    /** Compute waterfall spectrum from [validLen] samples in [samples]. Returns 256 magnitude bins.
     *  Output spans 0–3000 Hz regardless of sample rate (matches WaterfallView's 3000 Hz scale). */
    private fun computeSpectrum(samples: ShortArray, validLen: Int = samples.size): IntArray {
        val fftSize = minOf(SPECTRUM_CHUNK, validLen)
        val out = IntArray(256)
        if (fftSize < 64) return out

        // Limit output to 3000 Hz; for 8kHz audio this crops at 75% of Nyquist (4000 Hz)
        val maxBin = (3000.0 / (SAMPLE_RATE / 2.0) * (fftSize / 2)).toInt().coerceAtMost(fftSize / 2 - 1)
        val binsPerOut = maxBin.toFloat() / out.size
        val floats = FloatArray(fftSize) { samples[it].toFloat() / 32768f }

        for (k in out.indices) {
            val binIdx = (k * binsPerOut).toInt().coerceIn(0, maxBin)
            var re = 0.0; var im = 0.0
            val freq = binIdx.toDouble() / fftSize
            for (n in 0 until fftSize) {
                val angle = 2 * PI * freq * n
                re += floats[n] * cos(angle); im += floats[n] * sin(angle)
            }
            val mag = sqrt(re * re + im * im)
            out[k] = (20 * log10(mag + 1e-10) + 80).toInt().coerceIn(0, 120)
        }
        return out
    }

    companion object {
        private const val TAG = "Ft8LocalEngine"
    }
}
