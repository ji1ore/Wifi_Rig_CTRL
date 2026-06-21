package com.ji1ore.wifi_rig_ctrl.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

/**
 * Bluetooth SPP relay mode CW service.
 * Mirrors CwUsbService but uses BluetoothSocket (RFCOMM/SPP) instead of UsbSerialPort.
 * Same packet protocol: 0xE0 SYNC, 0xE1 response, 0x01/0x00 key state, 0xE2 WPM.
 */
class CwBtService(private val context: Context) {

    companion object {
        private const val TAG = "CwBtService"
        private const val SIDETONE_FREQ_HZ = 700
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    @Volatile private var running = false
    private val syncQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(8)

    private var audioTrack: AudioTrack? = null
    private var toneThread: Thread? = null
    @Volatile private var keyOn = false

    var onKeyStateChange: ((Boolean, ByteArray) -> Unit)? = null
    var onConnectionStateChange: ((Boolean) -> Unit)? = null
    var onWpmChange: ((Int) -> Unit)? = null

    @Volatile var currentMode: String = ""
    @Volatile var sidetoneEnabled: Boolean = true
    @Volatile private var lastReportedKeyState: Boolean? = null

    var piSyncAddress: java.net.InetSocketAddress? = null
    @Volatile var keeyerWpm: Int = 20
    private var syncResponseCount = 0

    val isConnected: Boolean get() = running && socket?.isConnected == true

    // ───────── Connection ─────────

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        return try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            running = true
            syncResponseCount = 0
            initSidetone()
            startReadLoop()
            onConnectionStateChange?.invoke(true)
            Log.i(TAG, "BT connected to ${device.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "BT connect failed: ${e.message}")
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            false
        }
    }

    fun disconnect() {
        running = false
        syncQueue.clear()
        stopSidetone()
        audioTrack?.release()
        audioTrack = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        onConnectionStateChange?.invoke(false)
        Log.i(TAG, "BT disconnected")
    }

    private fun write(data: ByteArray) {
        try { socket?.outputStream?.write(data) } catch (e: Exception) {
            Log.w(TAG, "BT write error: ${e.message}")
        }
    }

    // ───────── Read loop ─────────

    private fun startReadLoop() {
        // SYNC forwarder: handles UDP relay off the read thread so key packets aren't blocked
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (running) {
                val pkt = try {
                    syncQueue.poll(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) { break }
                if (pkt != null) handleSyncPacket(pkt)
            }
        }.also { it.isDaemon = true; it.name = "CwBt-SyncFwd"; it.start() }

        // Main BT read thread: never blocked by SYNC UDP
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(64)
            var accum = ByteArray(0)
            while (running) {
                try {
                    val n = socket?.inputStream?.read(buf) ?: -1
                    if (n > 0) {
                        accum += buf.sliceArray(0 until n)
                        accum = processAccum(accum)
                        if (accum.size > 64) accum = ByteArray(0)
                    } else if (n < 0) {
                        Log.w(TAG, "BT stream ended")
                        break
                    }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "BT read error: ${e.message}")
                    break
                }
            }
            if (running) {
                running = false
                socket = null
                onConnectionStateChange?.invoke(false)
            }
        }.also { it.isDaemon = true; it.name = "CwBt-Read"; it.start() }
    }

    private fun processAccum(input: ByteArray): ByteArray {
        var accum = input
        while (accum.isNotEmpty()) {
            val b0 = accum[0]
            when {
                b0 == 0xE0.toByte() -> {
                    if (accum.size < 5) return accum
                    val syncPkt = accum.sliceArray(0 until 5)
                    accum = accum.sliceArray(5 until accum.size)
                    syncQueue.offer(syncPkt)  // hand off to forwarder thread; never blocks read loop
                }
                b0 == 0xE2.toByte() -> {
                    if (accum.size < 2) return accum
                    val wpm = accum[1].toInt() and 0xFF
                    accum = accum.sliceArray(2 until accum.size)
                    if (wpm in 5..99) { keeyerWpm = wpm; onWpmChange?.invoke(wpm) }
                }
                b0 == 0x01.toByte() || b0 == 0x00.toByte() -> {
                    if (accum.size >= 10 && accum[1] == 0x01.toByte()) {
                        val pkt = accum.sliceArray(0 until 10)
                        accum = accum.sliceArray(10 until accum.size)
                        handleKeyState(b0 == 0x01.toByte(), pkt)
                    } else if (accum.size >= 2 && accum[1] != 0x01.toByte()) {
                        val pkt = byteArrayOf(b0, 0x01, 0, 0, 0, 0, 0, 0, 0, 1)
                        accum = accum.sliceArray(1 until accum.size)
                        handleKeyState(b0 == 0x01.toByte(), pkt)
                    } else {
                        return accum
                    }
                }
                else -> accum = accum.sliceArray(1 until accum.size)
            }
        }
        return accum
    }

    fun sendWpmToM5(wpm: Int) {
        keeyerWpm = wpm.coerceIn(5, 99)
        if (!running) return
        write(byteArrayOf(0xE2.toByte(), keeyerWpm.toByte()))
    }

    fun sendPaddleSwapToM5(swapped: Boolean) {
        if (!running) return
        write(byteArrayOf(0xE3.toByte(), if (swapped) 0x01.toByte() else 0x00.toByte()))
    }

    private fun handleSyncPacket(pkt: ByteArray) {
        val addr = piSyncAddress
        if (addr == null) {
            sendZeroSyncResponse()
            return
        }
        try {
            val sock = java.net.DatagramSocket()
            sock.soTimeout = 300
            sock.send(java.net.DatagramPacket(pkt, pkt.size, addr.address, addr.port))
            val recvBuf = ByteArray(16)
            val recvPkt = java.net.DatagramPacket(recvBuf, recvBuf.size)
            sock.receive(recvPkt)
            sock.close()
            val response = recvBuf.sliceArray(0 until recvPkt.length)
            if (response.size == 9 && response[0] == 0xE1.toByte()) {
                write(response)
                Log.d(TAG, "SYNC tunnel: 0xE1 forwarded to M5 via BT")
                syncResponseCount++
                if (syncResponseCount == 10) sendWpmToM5(keeyerWpm)
            }
        } catch (e: Exception) {
            Log.w(TAG, "SYNC forward failed: ${e.message}")
            sendZeroSyncResponse()
        }
    }

    private fun sendZeroSyncResponse() {
        val response = ByteArray(9)
        response[0] = 0xE1.toByte()
        write(response)
        syncResponseCount++
        if (syncResponseCount == 10) sendWpmToM5(keeyerWpm)
    }

    private fun handleKeyState(isOn: Boolean, rawPacket: ByteArray) {
        keyOn = isOn
        if (isOn != lastReportedKeyState) {
            lastReportedKeyState = isOn
            onKeyStateChange?.invoke(isOn, rawPacket)
        }
    }

    // ───────── Sidetone (same as CwUsbService) ─────────

    private fun initSidetone() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nativeSR = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        val minBuf = AudioTrack.getMinBufferSize(
            nativeSR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(nativeSR)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            audioTrack?.setBufferSizeInFrames(nativeSR / 100)
        }
        toneThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            audioTrack?.play()
            val chunkSamples = nativeSR / 500
            val chunk = ShortArray(chunkSamples)
            var phase = 0.0
            val phaseInc = 2.0 * PI * SIDETONE_FREQ_HZ / nativeSR
            var envelope = 0.0
            val rampStep = 1.0 / (nativeSR * 3 / 1000)
            while (running) {
                val target = if (keyOn && sidetoneEnabled) 1.0 else 0.0
                for (i in chunk.indices) {
                    envelope = when {
                        envelope < target -> minOf(target, envelope + rampStep)
                        envelope > target -> maxOf(target, envelope - rampStep)
                        else -> envelope
                    }
                    chunk[i] = (Short.MAX_VALUE * 0.5 * envelope * sin(phase)).toInt().toShort()
                    phase += phaseInc
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                }
                audioTrack?.write(chunk, 0, chunk.size)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopSidetone() {
        toneThread?.interrupt()
        toneThread = null
        try { audioTrack?.stop() } catch (_: Exception) {}
    }
}
