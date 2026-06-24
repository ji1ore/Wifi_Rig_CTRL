package com.ji1ore.wifi_rig_ctrl.data

import android.annotation.SuppressLint
import android.bluetooth.*
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
 * BLE Nordic UART Service (NUS) CW relay.
 * Connects to DualKey-BLE or RemoteKeyer-BLE (BLE NUS keyer).
 * Packet protocol: 0xE0 SYNC, 0xE1 response, 0x01/0x00 key state, 0xE2 WPM.
 */
class CwBleService(private val context: Context) {

    companion object {
        private const val TAG = "CwBleService"
        private const val SIDETONE_FREQ_HZ = 700
        private val NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NUS_RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NUS_TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD_UUID        = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    @Volatile private var running = false
    private val syncQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(8)

    private var audioTrack: AudioTrack? = null
    private var toneThread: Thread? = null
    @Volatile private var keyOn = false

    private var cwAudioStream: CwAudioStream? = null

    var onKeyStateChange: ((Boolean, ByteArray) -> Unit)? = null
    var onConnectionStateChange: ((Boolean) -> Unit)? = null
    var onWpmChange: ((Int) -> Unit)? = null
    var onAudioStreamNeeded: (() -> Unit)? = null

    @Volatile var currentMode: String = ""
    @Volatile var sidetoneEnabled: Boolean = true
    @Volatile private var lastReportedKeyState: Boolean? = null

    var piSyncAddress: java.net.InetSocketAddress? = null
    @Volatile var keeyerWpm: Int = 20
    private var syncResponseCount = 0

    private var accumData = ByteArray(0)

    val isConnected: Boolean get() = running && gatt != null

    // ───────── Connection ─────────

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        return try {
            running = true
            syncResponseCount = 0
            accumData = ByteArray(0)
            initSidetone()
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            true
        } catch (e: Exception) {
            running = false
            Log.e(TAG, "BLE connect failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        running = false
        syncQueue.clear()
        stopSidetone()
        cwAudioStream?.stop()
        cwAudioStream = null
        audioTrack?.release()
        audioTrack = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        rxChar = null
        onConnectionStateChange?.invoke(false)
        Log.i(TAG, "BLE disconnected")
    }

    @SuppressLint("MissingPermission")
    private fun write(data: ByteArray) {
        val ch = rxChar ?: return
        val g  = gatt  ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                ch.value = data
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        } catch (e: Exception) {
            Log.w(TAG, "BLE write error: ${e.message}")
        }
    }

    // ───────── GATT callbacks ─────────

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "BLE GATT connected, discovering services")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "BLE GATT disconnected (status=$status)")
                    this@CwBleService.gatt?.close()
                    this@CwBleService.gatt = null
                    rxChar = null
                    if (running) {
                        running = false
                        onConnectionStateChange?.invoke(false)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onServicesDiscovered failed: status=$status")
                gatt.disconnect()
                return
            }
            val service = gatt.getService(NUS_SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "NUS service not found on device")
                gatt.disconnect()
                return
            }
            rxChar = service.getCharacteristic(NUS_RX_CHAR_UUID)
            val txChar = service.getCharacteristic(NUS_TX_CHAR_UUID) ?: run {
                Log.e(TAG, "NUS TX characteristic not found")
                gatt.disconnect()
                return
            }
            gatt.setCharacteristicNotification(txChar, true)
            val desc = txChar.getDescriptor(CCCD_UUID)
            if (desc != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(desc)
                }
            }

            Log.i(TAG, "BLE NUS ready")
            startSyncForwarder()
            onConnectionStateChange?.invoke(true)
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processIncoming(value)
        }

        // API < 33
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            processIncoming(characteristic.value ?: return)
        }
    }

    // ───────── Packet processing ─────────

    private fun processIncoming(data: ByteArray) {
        accumData += data
        accumData = processAccum(accumData)
        if (accumData.size > 64) accumData = ByteArray(0)
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
                    syncQueue.offer(syncPkt)
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

    private fun startSyncForwarder() {
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (running) {
                val pkt = try {
                    syncQueue.poll(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) { break }
                if (pkt != null) handleSyncPacket(pkt)
            }
        }.also { it.isDaemon = true; it.name = "CwBle-SyncFwd"; it.start() }
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
                Log.d(TAG, "SYNC tunnel: 0xE1 forwarded to DualKey via BLE")
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
        val isCwMode = currentMode.contains("CW", ignoreCase = true)
        if (!isCwMode) {
            if (cwAudioStream == null) onAudioStreamNeeded?.invoke()
            if (isOn) cwAudioStream?.keyOn() else cwAudioStream?.keyOff()
        }
    }

    fun startCwAudioStream(api: RigApiService, apiKey: String) {
        cwAudioStream?.stop()
        cwAudioStream = CwAudioStream(api, apiKey, SIDETONE_FREQ_HZ, 8000)
        cwAudioStream?.start()
    }

    fun stopCwAudioStream() {
        cwAudioStream?.stop()
        cwAudioStream = null
    }

    // ───────── Sidetone ─────────

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
            audioTrack?.setBufferSizeInFrames(nativeSR / 20)  // 50ms: headroom for BLE scheduling jitter
        }
        toneThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            audioTrack?.play()
            val chunkSamples = nativeSR / 100  // 10ms chunks
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
