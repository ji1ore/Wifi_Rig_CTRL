package com.ji1ore.wifi_rig_ctrl.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

// CI-V control via Bluetooth RFCOMM (SPP) for IC-705 etc.
// Sends/receives raw CI-V frames over serial port profile.
// No audio: BT CI-V is for rig control only (freq, mode, PTT).
class CivBtService {

    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "CivBt"
        private const val CTRL_ADDR = 0xE0
    }

    var civAddress: Int = 0xA4
    @Volatile var isConnected: Boolean = false
    var lastError: String = ""

    private var socket: BluetoothSocket? = null
    private var outStream: OutputStream? = null
    @Volatile private var rxActive = false
    private var rxThread: Thread? = null

    private val rxQueue = LinkedBlockingDeque<ByteArray>(500)
    private var pollModeCount = 0

    // ── Connect / Disconnect ──

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        disconnect()
        lastError = ""
        pollModeCount = 0
        return try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            outStream = s.outputStream
            startRxThread(s.inputStream)
            isConnected = true
            // Enable transceive mode so IC-705 broadcasts freq/mode/TX changes
            Thread.sleep(200)
            sendCivFrame(buildCivFrame(0x16, 0x02, 0x01))
            Log.i(TAG, "connect OK addr=0x${civAddress.toString(16)} device=${device.address}")
            true
        } catch (e: Exception) {
            lastError = "BT接続失敗: ${e.message}"
            Log.e(TAG, lastError, e)
            disconnect()
            false
        }
    }

    fun disconnect() {
        isConnected = false
        rxActive = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outStream = null
        rxThread?.join(500)
        rxThread = null
        rxQueue.clear()
        Log.i(TAG, "disconnect")
    }

    // ── CI-V frame builder ──

    private fun buildCivFrame(vararg bytes: Int): ByteArray {
        val b = ByteArray(4 + bytes.size + 1)
        b[0] = 0xFE.toByte(); b[1] = 0xFE.toByte()
        b[2] = civAddress.toByte(); b[3] = CTRL_ADDR.toByte()
        bytes.forEachIndexed { i, v -> b[4 + i] = v.toByte() }
        b[b.size - 1] = 0xFD.toByte()
        return b
    }

    private fun sendCivFrame(frame: ByteArray): Boolean {
        return try {
            outStream?.write(frame)
            outStream?.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "send: ${e.message}")
            isConnected = false
            false
        }
    }

    // ── RX thread: reads bytes from BT socket, accumulates CI-V frames ──

    private fun startRxThread(inputStream: InputStream) {
        rxActive = true
        rxThread = Thread {
            val buf = ByteArray(256)
            val acc = mutableListOf<Byte>()
            Log.i(TAG, "rxThread start")
            while (rxActive) {
                try {
                    val n = inputStream.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val b = buf[i]
                        acc.add(b)
                        if ((b.toInt() and 0xFF) == 0xFD) {
                            val frame = acc.toByteArray()
                            acc.clear()
                            if (frame.size >= 5 &&
                                (frame[0].toInt() and 0xFF) == 0xFE &&
                                (frame[1].toInt() and 0xFF) == 0xFE) {
                                rxQueue.offerLast(frame)
                                Log.d(TAG, "rxFrame ${frame.size}B cmd=0x${(frame.getOrNull(4)?.toInt()?.and(0xFF))?.toString(16)}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (rxActive) Log.e(TAG, "rxThread: ${e.message}")
                    break
                }
            }
            rxActive = false
            isConnected = false
            Log.i(TAG, "rxThread exit")
        }.also { it.isDaemon = true; it.name = "civBtRx"; it.start() }
    }

    // ── Exchange: send a CI-V query and wait for matching response ──

    @Synchronized
    private fun exchange(frame: ByteArray, cmd: Int, subCmd: Int = -1, timeoutMs: Int = 1500): ByteArray? {
        if (!isConnected) return null
        rxQueue.clear()
        if (!sendCivFrame(frame)) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val r = rxQueue.pollFirst(50, TimeUnit.MILLISECONDS) ?: continue
            extractCivBody(r, cmd, subCmd)?.let { return it }
            // Also accept 0xFB (ACK) for write commands
            if (cmd == 0xFB) extractCivBody(r, 0xFB, -1)?.let { return it }
        }
        return null
    }

    // Extract CI-V body from a complete frame (FE FE dest src cmd... FD).
    // Returns body bytes [dest, src, cmd, ...] matching CivTcpService format.
    private fun extractCivBody(frame: ByteArray, cmd: Int, subCmd: Int): ByteArray? {
        if (frame.size < 5) return null
        if ((frame[0].toInt() and 0xFF) != 0xFE || (frame[1].toInt() and 0xFF) != 0xFE) return null
        val dest = frame[2].toInt() and 0xFF
        val src  = frame[3].toInt() and 0xFF
        // Only accept responses from rig to controller (filter out echoes of our own commands)
        if (dest != CTRL_ADDR || src != civAddress) return null
        val framecmd = frame[4].toInt() and 0xFF
        if (framecmd != cmd) return null
        if (subCmd >= 0 && (frame.size < 6 || (frame[5].toInt() and 0xFF) != subCmd)) return null
        return frame.copyOfRange(2, frame.size - 1)  // strip FE FE prefix and FD suffix
    }

    // ── BCD helpers (same as CivTcpService) ──

    private fun bcdLevel(b0: Int, b1: Int): Int =
        ((b0 shr 4) * 1000) + ((b0 and 0xF) * 100) + ((b1 shr 4) * 10) + (b1 and 0xF)

    private fun bcdLevelBytes(v: Int): Pair<Int, Int> {
        val c = v.coerceIn(0, 9999)
        return Pair((c / 1000 shl 4) or (c / 100 % 10), (c / 10 % 10 shl 4) or (c % 10))
    }

    // ── Frequency ──

    fun getFrequency(): Long? {
        val r = exchange(buildCivFrame(0x03), 0x03) ?: return null
        if (r.size < 8) return null
        // r = [dest, src, cmd, b0, b1, b2, b3, b4] — 5 BCD bytes LSB first
        var freq = 0L; var mult = 1L
        for (i in 3..7) {
            val b = r[i].toInt() and 0xFF
            freq += (b and 0xF) * mult; mult *= 10
            freq += (b shr 4) * mult;  mult *= 10
        }
        return freq
    }

    fun setFrequency(hz: Long): Boolean {
        var f = hz
        val bcd = IntArray(5) { val lo = (f % 10).toInt(); f /= 10; val hi = (f % 10).toInt(); f /= 10; (hi shl 4) or lo }
        return exchange(buildCivFrame(0x05, bcd[0], bcd[1], bcd[2], bcd[3], bcd[4]), 0xFB) != null
    }

    // ── Mode ──

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
        val r = exchange(buildCivFrame(0x04), 0x04, -1, 800) ?: return null
        return parseModeBody(r)
    }

    fun setMode(mode: String, width: Int): Boolean {
        val mc = when (mode.uppercase()) {
            "LSB" -> 0x00; "USB" -> 0x01; "AM" -> 0x02; "CW" -> 0x03
            "RTTY" -> 0x04; "FM" -> 0x05; "WFM" -> 0x06; "CWR", "CW-R" -> 0x07
            "RTTYR" -> 0x08; "DSTAR", "D-STAR" -> 0x17; else -> 0x01
        }
        val isCw = mc == 0x03 || mc == 0x07
        val fc = when {
            isCw -> when { width <= 150 -> 0x03; width <= 350 -> 0x02; else -> 0x01 }
            else -> when { width <= 600 -> 0x03; width <= 2000 -> 0x02; else -> 0x01 }
        }
        return exchange(buildCivFrame(0x06, mc, fc), 0xFB) != null
    }

    // ── S-meter ──

    fun getSmeterSignal(): Float? {
        val r = exchange(buildCivFrame(0x15, 0x02), 0x15, 0x02, 400) ?: return null
        if (r.size < 6) return null
        val raw = bcdLevel(r[4].toInt() and 0xFF, r[5].toInt() and 0xFF).toFloat()
        return (raw - 120f) * 54f / 120f
    }

    // ── TX / PTT ──

    fun getTxState(): Boolean? {
        val r = exchange(buildCivFrame(0x1C, 0x00), 0x1C, 0x00, 400) ?: return null
        return if (r.size >= 5) (r[4].toInt() and 0xFF) == 0x01 else null
    }

    fun setPtt(on: Boolean): Boolean {
        val ok = exchange(buildCivFrame(0x1C, 0x00, if (on) 0x01 else 0x00), 0xFB, timeoutMs = if (on) 1500 else 3000) != null
        Log.i(TAG, "setPtt: ${if (on) "ON" else "OFF"} ok=$ok")
        return ok
    }

    fun civPttDown() {
        if (!isConnected) return
        sendCivFrame(buildCivFrame(0x1C, 0x00, 0x01))
    }

    fun civPttUp() {
        if (!isConnected) return
        sendCivFrame(buildCivFrame(0x1C, 0x00, 0x00))
    }

    // ── RF Power ──

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

    fun setBkIn(on: Boolean): Boolean =
        exchange(buildCivFrame(0x16, 0x47, if (on) 0x01 else 0x00), 0xFB) != null

    // ── NR ──

    fun setNrLevel(level: Int): Boolean {
        val on = level > 0
        if (exchange(buildCivFrame(0x16, 0x40, if (on) 0x01 else 0x00), 0xFB) == null) return false
        if (!on) return true
        val raw = (level * 51).coerceIn(0, 255)
        val (b0, b1) = bcdLevelBytes(raw)
        return exchange(buildCivFrame(0x14, 0x06, b0, b1), 0xFB) != null
    }

    // ── CW ──

    fun setKeySpeed(wpm: Int): Boolean {
        val raw = ((wpm.coerceIn(6, 48) - 6) * 255 / 42).coerceIn(0, 255)
        val (b0, b1) = bcdLevelBytes(raw)
        return exchange(buildCivFrame(0x14, 0x0C, b0, b1), 0xFB) != null
    }

    fun sendCwMessage(text: String): Boolean {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val frame = ByteArray(5 + bytes.size + 1)
        frame[0] = 0xFE.toByte(); frame[1] = 0xFE.toByte()
        frame[2] = civAddress.toByte(); frame[3] = CTRL_ADDR.toByte()
        frame[4] = 0x17.toByte()
        bytes.copyInto(frame, 5)
        frame[frame.size - 1] = 0xFD.toByte()
        return sendCivFrame(frame)
    }

    fun stopCwMessage() {
        sendCivFrame(buildCivFrame(0x17))
    }

    // ── Repeater settings ──

    fun sendRepeaterSettings(toneMode: String, toneHz: Double, offsetDir: String, offsetHz: Long) {
        if (!isConnected) return
        when (toneMode) {
            "Tone" -> {
                setCtcssToneFreq(toneHz)
                exchange(buildCivFrame(0x16, 0x42, 0x01), 0xFB)
            }
            "TSQL" -> {
                setCtcssToneFreq(toneHz)
                setCtcssSqlFreq(toneHz)
                exchange(buildCivFrame(0x16, 0x42, 0x02), 0xFB)
            }
            else -> exchange(buildCivFrame(0x16, 0x42, 0x00), 0xFB)
        }
        val code = when (offsetDir) { "+" -> 0x11; "-" -> 0x10; else -> 0x00 }
        exchange(buildCivFrame(0x0F, code), 0xFB)
        if (offsetHz > 0L) {
            var f = offsetHz
            val bcd = IntArray(5) { val lo = (f % 10).toInt(); f /= 10; val hi = (f % 10).toInt(); f /= 10; (hi shl 4) or lo }
            exchange(buildCivFrame(0x0D, bcd[0], bcd[1], bcd[2], bcd[3], bcd[4]), 0xFB)
        }
    }

    private fun setCtcssToneFreq(hz: Double): Boolean {
        val t = (hz * 10).toInt()
        val b0 = (t / 1000 shl 4) or ((t / 100) % 10)
        val b1 = ((t / 10) % 10 shl 4) or (t % 10)
        return exchange(buildCivFrame(0x1B, 0x00, b0, b1), 0xFB) != null
    }

    private fun setCtcssSqlFreq(hz: Double): Boolean {
        val t = (hz * 10).toInt()
        val b0 = (t / 1000 shl 4) or ((t / 100) % 10)
        val b1 = ((t / 10) % 10 shl 4) or (t % 10)
        return exchange(buildCivFrame(0x1B, 0x01, b0, b1), 0xFB) != null
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

    // ── Poll ──

    fun pollStatus(): CivStatus? {
        if (!isConnected) return null
        val freq = getFrequency() ?: return null
        val signal = getSmeterSignal()
        val tx = getTxState() ?: false
        pollModeCount++
        val mode  = if (pollModeCount % 5  == 0) getMode()    else null
        val power = if (pollModeCount % 10 == 1) getRfPower()  else null
        val sql   = if (pollModeCount % 10 == 6) getSql()      else null
        val bkIn  = if (pollModeCount % 10 == 8) getBkIn()     else null
        return CivStatus(freq, signal, tx, mode, power, sql, bkIn)
    }
}
