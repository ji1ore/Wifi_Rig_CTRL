package com.ji1ore.wifi_rig_ctrl.data

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlin.math.PI
import kotlin.math.sin

/**
 * USB relay mode CW service
 *
 * Receives key state (0x01=ON / 0x00=OFF) from M5ATOM Lite (CH340) or M5ATOM S3 Lite (ESP32-S3 CDC),
 * - Plays sidetone from Android
 * - If Mode name contains CW: relay key state to Pi /cw/key
 * - If Mode name does not contain CW: stream CW audio tone to Pi /radio/audio_tx
 */
class CwUsbService(private val context: Context) {

    companion object {
        private const val TAG = "CwUsbService"
        private const val SIDETONE_FREQ_HZ = 700
        private const val SAMPLE_RATE = 8000
        private const val BAUD_RATE = 115200

        private val SUPPORTED_DEVICES = listOf(
            0x0403 to 0x6001, // FTDI FT232R (M5ATOM Lite some batches)
            0x0403 to 0x6010, // FTDI FT2232H
            0x0403 to 0x6011, // FTDI FT4232H
            0x0403 to 0x6014, // FTDI FT232H
            0x0403 to 0x6015, // FTDI FT231X
            0x1A86 to 0x7523, // CH340/CH341 (M5ATOM Lite older batches)
            0x1A86 to 0x7522, // CH340C
            0x1A86 to 0x55D4, // CH340K (M5ATOM S3)
            0x1A86 to 0x55D3, // CH9102 (M5ATOM S3 some batches)
            0x10C4 to 0xEA60, // CP2101/2/3/4 (M5ATOM Lite)
            0x10C4 to 0xEA70, // CP2105
            0x10C4 to 0xEA71, // CP2108
            0x303A to 0x1001, // ESP32-S3 USB-CDC (M5ATOM S3 / S3 Lite)
            0x303A to 0x4001, // ESP32-S3 USB-Serial
            0x303A to 0x0002, // ESP32 USB Serial/JTAG
            0x303A to 0x8000, // ESP32-S3 variant
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
    var onAudioStreamNeeded: (() -> Unit)? = null


    @Volatile var currentMode: String = ""
    @Volatile var sidetoneEnabled: Boolean = true
    @Volatile private var lastReportedKeyState: Boolean? = null

    // Pi UDP address (for SYNC tunnel): set by MainViewModel on CW connection
    var piSyncAddress: java.net.InetSocketAddress? = null

    val isConnected: Boolean get() = running && port != null

    // ───────── USB connection ─────────

    fun isSupportedDevice(device: UsbDevice): Boolean =
        SUPPORTED_DEVICES.any { (vid, pid) -> device.vendorId == vid && device.productId == pid }

    fun findM5AtomDevice(): UsbDevice? =
        usbManager.deviceList?.values?.firstOrNull { isSupportedDevice(it) }

    private fun buildProber(): UsbSerialProber {
        val table = ProbeTable()
        table.addProduct(0x0403, 0x6001, FtdiSerialDriver::class.java)
        table.addProduct(0x0403, 0x6010, FtdiSerialDriver::class.java)
        table.addProduct(0x0403, 0x6011, FtdiSerialDriver::class.java)
        table.addProduct(0x0403, 0x6014, FtdiSerialDriver::class.java)
        table.addProduct(0x0403, 0x6015, FtdiSerialDriver::class.java)
        table.addProduct(0x1A86, 0x7523, Ch34xSerialDriver::class.java)
        table.addProduct(0x1A86, 0x7522, Ch34xSerialDriver::class.java)
        table.addProduct(0x1A86, 0x55D4, Ch34xSerialDriver::class.java) // CH340K (M5ATOM S3)
        table.addProduct(0x1A86, 0x55D3, Ch34xSerialDriver::class.java) // CH9102
        table.addProduct(0x10C4, 0xEA60, Cp21xxSerialDriver::class.java) // CP2104 (M5ATOM Lite)
        table.addProduct(0x10C4, 0xEA70, Cp21xxSerialDriver::class.java)
        table.addProduct(0x10C4, 0xEA71, Cp21xxSerialDriver::class.java)
        table.addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java) // ESP32-S3 USB-CDC
        table.addProduct(0x303A, 0x4001, CdcAcmSerialDriver::class.java) // ESP32-S3 USB-Serial
        table.addProduct(0x303A, 0x0002, CdcAcmSerialDriver::class.java) // ESP32 USB Serial/JTAG
        table.addProduct(0x303A, 0x8000, CdcAcmSerialDriver::class.java) // ESP32-S3 variant
        return UsbSerialProber(table)
    }

    fun connect(device: UsbDevice): Boolean {
        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "USB permission not granted for ${device.deviceName}")
            return false
        }
        val driver = (UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: buildProber().probeDevice(device)) ?: run {
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
            try {
                serialPort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (e: Exception) {
                Log.w(TAG, "setParameters skipped (${e.message})")
            }
            // ESP32-S3 native USB (VID=0x303A): set DTR and send wakeup byte to trigger CDC data flow.
            // CP2104/FTDI/CH340 (VID != 0x303A): physical DTR/RTS wired to RESET/BOOT — do NOT set.
            if (device.vendorId == 0x303A) {
                try {
                    serialPort.dtr = true
                    serialPort.rts = true
                } catch (e: Exception) {
                    Log.w(TAG, "DTR/RTS set skipped (${e.message})")
                }
                try { serialPort.write(byteArrayOf(0xFF.toByte()), 200) } catch (_: Exception) {}
            }
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

    // ───────── USB read loop ─────────

    private fun startReadLoop() {
        Thread {
            val buf = ByteArray(64)
            var accum = ByteArray(0)
            while (running) {
                try {
                    val n = port?.read(buf, 1000) ?: -1
                    if (n > 0) {
                        accum += buf.sliceArray(0 until n)
                        accum = processAccum(accum)
                        if (accum.size > 64) accum = ByteArray(0)
                    }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "Read error: ${e.message}")
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun processAccum(input: ByteArray): ByteArray {
        var accum = input
        while (accum.isNotEmpty()) {
            val b0 = accum[0]
            when {
                b0 == 0xE0.toByte() -> {
                    // SYNC packet: 5 bytes (0xE0 + 4 bytes client msec)
                    if (accum.size < 5) return accum
                    val syncPkt = accum.sliceArray(0 until 5)
                    accum = accum.sliceArray(5 until accum.size)
                    handleSyncPacket(syncPkt)
                }
                b0 == 0x01.toByte() || b0 == 0x00.toByte() -> {
                    if (accum.size >= 10 && accum[1] == 0x01.toByte()) {
                        val pkt = accum.sliceArray(0 until 10)
                        accum = accum.sliceArray(10 until accum.size)
                        handleKeyState(b0 == 0x01.toByte(), pkt)
                    } else if (accum.size >= 2 && accum[1] != 0x01.toByte()) {
                        val pkt = byteArrayOf(b0, 0x01, 0,0,0,0,0,0,0,1)
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

    // Forward 0xE0 SYNC from M5 Client to Pi UDP, then return 0xE1 response to M5 Client
    private fun handleSyncPacket(pkt: ByteArray) {
        val addr = piSyncAddress ?: return
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
                port?.write(response, 100)
                Log.d(TAG, "SYNC tunnel: 0xE1 forwarded to M5 Client")
            }
        } catch (e: Exception) {
            Log.w(TAG, "SYNC forward failed: ${e.message}")
        }
    }

    // ───────── Key state handling ─────────

    private fun handleKeyState(isOn: Boolean, rawPacket: ByteArray) {
        // Sidetone fires immediately here — VPN delay is applied only to the Pi-side packet
        // inside onKeyStateChange (timestamp adjustment in MainViewModel.cwDelayMs).
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

    // ───────── Sidetone ─────────

    private fun initSidetone() {
        // Use device native sample rate: no SRC needed → minimum latency, no resampling noise
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

        // Limit buffer depth to ~10ms to reduce sidetone on/off delay
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            audioTrack?.setBufferSizeInFrames(nativeSR / 100)  // 10ms
        }

        toneThread = Thread {
            audioTrack?.play()
            // 2ms chunks: minimize buffer depth (48000Hz → 96 samples)
            val chunkSamples = nativeSR / 500
            val chunk = ShortArray(chunkSamples)
            var phase = 0.0
            val phaseInc = 2.0 * PI * SIDETONE_FREQ_HZ / nativeSR
            var envelope = 0.0
            val rampStep = 1.0 / (nativeSR * 3 / 1000)  // 3ms ramp
            while (running) {
                val target = if (keyOn && sidetoneEnabled) 1.0 else 0.0
                // Always write (prevent click noise from underrun)
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

    // ───────── Audio stream management for non-CW mode ─────────

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

    // For PTT button CW tone mode: also controls sidetone simultaneously
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
 * For non-CW mode: stream CW audio tone to Pi /radio/audio_tx
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
                // Rate limit at 10ms/chunk: prevents buffer overflow and eliminates tone delay
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
