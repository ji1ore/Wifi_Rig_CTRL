package com.example.wifi_rig_ctrl.data

import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpPttService {

    private var socket: DatagramSocket? = null

    // syncTime()でキャッシュ — sendPtt()のたびにDNS解決しない
    @Volatile private var cachedAddr: InetAddress? = null
    @Volatile private var cachedHost: String? = null

    // M5Atomのmillis()とAndroidのelapsedRealtime()の差分
    // null = 未同期（PTTは即時実行タイムスタンプ0を使用）
    @Volatile private var m5TimeOffset: Long? = null

    private fun ensureSocket() {
        if (socket == null || socket!!.isClosed) {
            socket = DatagramSocket()
        }
    }

    private fun resolveAddr(host: String): InetAddress {
        val cached = cachedAddr
        if (cached != null && cachedHost == host) return cached
        val addr = InetAddress.getByName(host)
        cachedAddr = addr
        cachedHost = host
        return addr
    }

    // PTTパケット送信: 10バイト [on/off, trxSel(0x01), opTimeMs(8バイト big-endian)]
    // OFF時は60ms間隔で5回送信: M5Atomの重複検出(SAME_MAX=4, MIN_EVENT_INTERVAL=40ms)では
    // 60ms間隔ならi=2以降(120ms>=120ms)の同一判定が外れるため全パケットが受理される
    fun sendPtt(host: String, port: Int, on: Boolean): Boolean = try {
        ensureSocket()
        val addr = resolveAddr(host)
        val offset = m5TimeOffset

        if (on) {
            val nowMs = if (offset != null) SystemClock.elapsedRealtime() + offset else 0L
            socket!!.send(DatagramPacket(buildPkt(true, nowMs), 10, addr, port))
            Log.d("UdpPTT", "ON → $host:$port ts=$nowMs")
        } else {
            // OFFは60ms間隔で5回送信（タイムスタンプを毎回更新して重複検出を回避）
            repeat(5) { i ->
                val nowMs = if (offset != null) SystemClock.elapsedRealtime() + offset else 0L
                socket!!.send(DatagramPacket(buildPkt(false, nowMs), 10, addr, port))
                Log.d("UdpPTT", "OFF[$i] → $host:$port ts=$nowMs")
                if (i < 4) Thread.sleep(60)
            }
        }
        true
    } catch (e: Exception) {
        Log.e("UdpPTT", "sendPtt failed: ${e.message}")
        false
    }

    private fun buildPkt(on: Boolean, nowMs: Long): ByteArray {
        val pkt = ByteArray(10)
        pkt[0] = if (on) 0x01 else 0x00
        pkt[1] = 0x01
        for (i in 0 until 8) pkt[2 + i] = ((nowMs shr (56 - i * 8)) and 0xFF).toByte()
        return pkt
    }

    // M5Atomと時刻同期: 0xE0 + 4バイトのクライアント時刻を送信 (計5バイト)
    // M5Atomから 0xE1 + echo(4) + serverMillis(4) が返ってくる
    // InetAddressもここでキャッシュしてsendPtt()の解決コストをゼロにする
    fun syncTime(host: String, port: Int): Boolean = try {
        ensureSocket()
        val addr = InetAddress.getByName(host).also {
            cachedAddr = it
            cachedHost = host
        }
        val clientMs = SystemClock.elapsedRealtime() and 0xFFFFFFFFL
        val pkt = ByteArray(5)
        pkt[0] = 0xE0.toByte()
        for (i in 0 until 4) pkt[1 + i] = ((clientMs shr (24 - i * 8)) and 0xFF).toByte()
        socket!!.send(DatagramPacket(pkt, pkt.size, addr, port))
        Log.d("UdpPTT", "syncTime → $host:$port clientMs=$clientMs")

        try {
            socket!!.soTimeout = 1000
            val buf = ByteArray(9)
            val recv = DatagramPacket(buf, buf.size)
            socket!!.receive(recv)
            if (recv.length >= 9 && buf[0] == 0xE1.toByte()) {
                var serverMs = 0L
                for (i in 0 until 4) serverMs = (serverMs shl 8) or (buf[5 + i].toLong() and 0xFF)
                m5TimeOffset = serverMs - SystemClock.elapsedRealtime()
                Log.d("UdpPTT", "syncTime OK: serverMs=$serverMs offset=$m5TimeOffset")
            } else {
                Log.d("UdpPTT", "syncTime: unexpected reply 0x${buf[0].toInt().and(0xFF).toString(16)}")
            }
        } catch (_: Exception) {
            Log.d("UdpPTT", "syncTime: no response (PTT will use ts=0 for immediate execution)")
        } finally {
            socket!!.soTimeout = 0  // 受信タイムアウトをリセット
        }
        true
    } catch (e: Exception) {
        Log.e("UdpPTT", "syncTime failed: ${e.message}")
        false
    }

    fun close() {
        socket?.close()
        socket = null
        cachedAddr = null
        cachedHost = null
        m5TimeOffset = null
    }
}
