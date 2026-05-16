package com.example.wifi_rig_ctrl.data

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlin.math.PI
import kotlin.math.sin

/**
 * USB中継モード CWサービス
 *
 * M5ATOM Lite (CH340) または M5ATOM S3 Lite (ESP32-S3 CDC) から
 * キー状態 (0x01=ON / 0x00=OFF) を受信し、
 * ・サイドトーンをAndroidから再生
 * ・Mode名にCWが含まれる場合: Pi の /cw/key にキー状態を中継
 * ・Mode名にCWが含まれない場合: Pi の /radio/audio_tx にCW音声トーンをストリーム
 */
class CwUsbService(private val context: Context) {

    companion object {
        private const val TAG = "CwUsbService"
        private const val SIDETONE_FREQ_HZ = 700
        private const val SAMPLE_RATE = 8000
        private const val BAUD_RATE = 115200

        private val SUPPORTED_DEVICES = listOf(
            0x0403 to 0x6001, // FTDI FT232R (M5ATOM Lite 一部ロット)
            0x0403 to 0x6010, // FTDI FT2232H
            0x0403 to 0x6011, // FTDI FT4232H
            0x0403 to 0x6014, // FTDI FT232H
            0x0403 to 0x6015, // FTDI FT231X
            0x1A86 to 0x7523, // CH340/CH341 (M5ATOM Lite 旧ロット)
            0x1A86 to 0x7522, // CH340C
            0x303A to 0x1001, // ESP32-S3 Native USB CDC (M5ATOM S3 Lite)
            0x303A to 0x4001, // ESP32-S3 variant
        )
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var port: UsbSerialPort? = null
    @Volatile private var running = false

    private var audioTrack: AudioTrack? = null
    private var toneThread: Thread? = null
    @Volatile private var keyOn = false

    private var cwAudioStream: CwAudioStream? = null

    var onKeyStateChange: ((Boolean, ByteArray) -> Unit)? = null
    var onConnectionStateChange: ((Boolean) -> Unit)? = null

    @Volatile var currentMode: String = ""
    @Volatile var sidetoneEnabled: Boolean = true
    @Volatile private var lastReportedKeyState: Boolean? = null

    val isConnected: Boolean get() = running && port != null

    // ───────── USB接続 ─────────

    fun isSupportedDevice(device: UsbDevice): Boolean =
        SUPPORTED_DEVICES.any { (vid, pid) -> device.vendorId == vid && device.productId == pid }

    fun findM5AtomDevice(): UsbDevice? =
        usbManager.deviceList?.values?.firstOrNull { isSupportedDevice(it) }

    fun connect(device: UsbDevice): Boolean {
        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "USB permission not granted for ${device.deviceName}")
            return false
        }
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: run {
            Log.e(TAG, "No serial driver found for ${device.deviceName} (VID=${device.vendorId} PID=${device.productId})")
            return false
        }
        val conn = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Failed to open USB device")
            return false
        }
        val serialPort = driver.ports.firstOrNull() ?: run {
            conn.close()
            Log.e(TAG, "No serial ports on device")
            return false
        }
        try {
            serialPort.open(conn)
            serialPort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open serial port: ${e.message}")
            try { serialPort.close() } catch (_: Exception) {}
            conn.close()
            return false
        }

        port = serialPort
        running = true

        initSidetone()
        startReadLoop()
        onConnectionStateChange?.invoke(true)
        Log.i(TAG, "Connected to ${device.deviceName}")
        return true
    }

    fun disconnect() {
        running = false
        cwAudioStream?.stop()
        cwAudioStream = null
        stopSidetone()
        audioTrack?.release()
        audioTrack = null
        try { port?.close() } catch (_: Exception) {}
        port = null
        onConnectionStateChange?.invoke(false)
        Log.i(TAG, "Disconnected")
    }

    // ───────── USB読み込みループ ─────────

    private fun startReadLoop() {
        Thread {
            val buf = ByteArray(64)
            var accum = ByteArray(0)
            while (running) {
                try {
                    val n = port?.read(buf, 1000) ?: -1
                    if (n > 0) {
                        accum += buf.sliceArray(0 until n)
                        while (accum.isNotEmpty()) {
                            val b0 = accum[0]
                            if (b0 != 0x01.toByte() && b0 != 0x00.toByte()) {
                                accum = accum.sliceArray(1 until accum.size); continue
                            }
                            // 10バイトパケット判定: byte[1]==0x01(trxSel) かつ 10バイト揃っている
                            if (accum.size >= 10 && accum[1] == 0x01.toByte()) {
                                val pkt = accum.sliceArray(0 until 10)
                                accum = accum.sliceArray(10 until accum.size)
                                handleKeyState(b0 == 0x01.toByte(), pkt)
                            } else if (accum.size >= 2 && accum[1] != 0x01.toByte()) {
                                // 旧1バイト形式: byte[1] が ON/OFF バイトなので1バイト消費
                                val pkt = byteArrayOf(b0, 0x01, 0,0,0,0,0,0,0,1)
                                accum = accum.sliceArray(1 until accum.size)
                                handleKeyState(b0 == 0x01.toByte(), pkt)
                            } else {
                                break  // データ待ち
                            }
                        }
                        if (accum.size > 40) accum = ByteArray(0)
                    }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "Read error: ${e.message}")
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ───────── キー状態ハンドリング ─────────

    private fun handleKeyState(isOn: Boolean, rawPacket: ByteArray) {
        keyOn = isOn
        if (isOn != lastReportedKeyState) {
            lastReportedKeyState = isOn
            onKeyStateChange?.invoke(isOn, rawPacket)
        }

        val isCwMode = currentMode.contains("CW", ignoreCase = true)
        if (!isCwMode) {
            if (isOn) cwAudioStream?.keyOn() else cwAudioStream?.keyOff()
        }
    }

    // ───────── サイドトーン ─────────

    private fun initSidetone() {
        // デバイスネイティブレートを使用: SRC不要 → 最低レイテンシ、リサンプリングノイズなし
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nativeSR = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull() ?: 48000

        val minBuf = AudioTrack.getMinBufferSize(
            nativeSR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(nativeSR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        // バッファ深さを ~10ms に絞りサイドトーンのon/off遅延を低減
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            audioTrack?.setBufferSizeInFrames(nativeSR / 100)  // 10ms
        }

        toneThread = Thread {
            audioTrack?.play()
            // 2msチャンク: バッファ深さを最小化 (48000Hz → 96サンプル)
            val chunkSamples = nativeSR / 500
            val chunk = ShortArray(chunkSamples)
            var phase = 0.0
            val phaseInc = 2.0 * PI * SIDETONE_FREQ_HZ / nativeSR
            var envelope = 0.0
            val rampStep = 1.0 / (nativeSR * 3 / 1000)  // 3ms ランプ
            while (running) {
                val target = if (keyOn && sidetoneEnabled) 1.0 else 0.0
                // 常に書き込む (アンダーランによるクリックノイズを防止)
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

    private fun playSidetone() {}
    private fun stopSidetone() {}

    // ───────── 非CWモード用 音声ストリーム管理 ─────────

    fun startCwAudioStream(api: RigApiService, apiKey: String) {
        cwAudioStream?.stop()
        cwAudioStream = CwAudioStream(api, apiKey, SIDETONE_FREQ_HZ, SAMPLE_RATE)
        cwAudioStream?.start()
    }

    fun stopCwAudioStream() {
        cwAudioStream?.stop()
        cwAudioStream = null
    }

    fun ensureCwAudioStream(api: RigApiService, apiKey: String) {
        if (cwAudioStream == null) startCwAudioStream(api, apiKey)
    }

    // PTTボタンCWトーンモード用: サイドトーンも同時制御
    fun pttToneKeyOn() {
        keyOn = true
        cwAudioStream?.keyOn()
    }

    fun pttToneKeyOff() {
        keyOn = false
        cwAudioStream?.keyOff()
    }
}

/**
 * 非CWモード用: CW音声トーンを Pi の /radio/audio_tx にストリーミング
 */
class CwAudioStream(
    private val api: RigApiService,
    private val apiKey: String,
    private val toneHz: Int,
    private val sampleRate: Int
) {
    @Volatile private var running = false
    @Volatile private var keyIsOn = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread { streamAudio() }.also { it.isDaemon = true; it.start() }
    }

    fun keyOn() { keyIsOn = true }
    fun keyOff() { keyIsOn = false }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun streamAudio() {
        val chunkSize = sampleRate / 100  // 10ms chunks for low latency
        val buf = ByteArray(chunkSize * 2)
        val phaseInc = 2.0 * PI * toneHz / sampleRate

        while (running) {
            var phase = 0.0
            try {
                val url = java.net.URL(api.getAudioTxUrl(sampleRate))
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.doOutput = true
                conn.requestMethod = "POST"
                if (apiKey.isNotEmpty()) conn.setRequestProperty("X-API-Key", apiKey)
                conn.setChunkedStreamingMode(0)
                conn.connectTimeout = 3000
                conn.readTimeout = 0
                conn.connect()
                val out = conn.outputStream
                // 10ms/chunk でレート制限: バッファ過充填を防ぎトーン遅延を排除
                var nextWriteMs = System.currentTimeMillis()
                while (running) {
                    nextWriteMs += 10L
                    if (keyIsOn) {
                        for (i in 0 until chunkSize) {
                            val sample = (Short.MAX_VALUE * 0.8 * sin(phase)).toInt().toShort()
                            buf[i * 2] = (sample.toInt() and 0xFF).toByte()
                            buf[i * 2 + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
                            phase += phaseInc
                            if (phase > 2.0 * PI) phase -= 2.0 * PI
                        }
                    } else {
                        buf.fill(0)
                        phase = 0.0
                    }
                    try { out.write(buf); out.flush() } catch (_: Exception) { break }
                    val sleepMs = nextWriteMs - System.currentTimeMillis()
                    if (sleepMs > 0) Thread.sleep(sleepMs)
                }
                try { out.close() } catch (_: Exception) {}
                try { conn.disconnect() } catch (_: Exception) {}
            } catch (e: Exception) {
                if (!running) break
                Log.w("CwAudioStream", "disconnected, reconnecting: ${e.message}")
            }
            if (running) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }
    }
}
