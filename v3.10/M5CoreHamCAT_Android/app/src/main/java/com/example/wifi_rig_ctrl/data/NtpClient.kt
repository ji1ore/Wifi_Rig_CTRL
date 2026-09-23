package com.ji1ore.wifi_rig_ctrl.data

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

private const val NTP_EPOCH_OFFSET = 2208988800L  // seconds between 1900-01-01 and 1970-01-01
private const val TIMEOUT_MS = 4000

/**
 * NTP サーバーに SNTP(RFC 4330) リクエストを送り、正確な Unix 時刻(ms)を返す。
 * サーバーに到達できない場合は null を返す。
 */
fun queryNtpMs(server: String = "pool.ntp.org"): Long? = try {
    val socket = DatagramSocket()
    socket.soTimeout = TIMEOUT_MS
    val packet = ByteArray(48).also { it[0] = 0x1B }  // LI=0, VN=3, Mode=3(client)
    val addr = InetAddress.getByName(server)
    val t1 = System.currentTimeMillis()
    socket.send(DatagramPacket(packet, 48, addr, 123))
    val resp = DatagramPacket(ByteArray(48), 48)
    socket.receive(resp)
    val t4 = System.currentTimeMillis()
    socket.close()

    val data = resp.data
    // Receive Timestamp: bytes 32-39, Transmit Timestamp: bytes 40-47
    fun readSecs(offset: Int): Long {
        var v = 0L
        for (i in offset until offset + 4) v = (v shl 8) or (data[i].toLong() and 0xFF)
        return v - NTP_EPOCH_OFFSET
    }
    val t2 = readSecs(32) * 1000
    val t3 = readSecs(40) * 1000
    // NTP offset formula: ((t2-t1) + (t3-t4)) / 2
    val offset = ((t2 - t1) + (t3 - t4)) / 2
    t4 + offset
} catch (_: Exception) { null }
