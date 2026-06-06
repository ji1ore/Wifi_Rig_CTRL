#!/usr/bin/env python3
"""M5ATOM Server USB中継モード向け CW UDP→Serial ブリッジ
Usage: python3 cw_bridge.py /dev/ttyUSBx

Android が各パケットに currentTimeMillis()+buffer_ms を埋め込む。
Pi は SYNC プロトコルで Server の millis() オフセットを取得し、
Android タイムスタンプを Server millis() に変換して即時転送する。
Server 側が GPIO 発火タイミングをスケジューリングするため VPN ジッターを吸収できる。"""
import json
import os
import socket
import serial
import sys
import threading
import time

UDP_PORT = 8889
BAUD = 115200
PING_INTERVAL = 5.0
STATUS_FILE = "/tmp/cw_bridge_status.json"

_server_offset_ms = None  # server_millis_at_sync - pi_unix_ms_at_sync (大きな負の値)

def main():
    global _server_offset_ms
    max_late_ms = [0]  # 最大観測遅延[ms] (Piへの到達遅延+クロック差-バッファ設定)
    dev = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"
    print(f"[cw_bridge] opening {dev}", flush=True)
    try:
        ser = serial.Serial()
        ser.port = dev
        ser.baudrate = BAUD
        ser.bytesize = serial.EIGHTBITS
        ser.parity = serial.PARITY_NONE
        ser.stopbits = serial.STOPBITS_ONE
        ser.timeout = 0.5
        ser.dtr = False
        ser.rts = False
        ser.open()
        time.sleep(0.1)
        ser.reset_input_buffer()
        print(f"[cw_bridge] serial ok", flush=True)
    except Exception as e:
        print(f"[cw_bridge] serial open failed: {e}", flush=True)
        sys.exit(1)

    write_lock = threading.Lock()

    def _write_status(synced: bool, offset_ms: float = 0):
        tmp = STATUS_FILE + ".tmp"
        try:
            with open(tmp, 'w') as f:
                json.dump({"synced": synced, "offset_ms": offset_ms, "max_late_ms": max_late_ms[0], "t": time.time()}, f)
            os.replace(tmp, STATUS_FILE)
        except Exception:
            pass

    def _sync_clock() -> bool:
        """Server の millis() と Pi の time.time() のオフセットを計測する。
        Pi→Server: 0xE0 + 4bytes(0埋め)  5バイト
        Server→Pi: 0xE1 + 4bytes(echo) + 4bytes(server_millis big-endian)  9バイト"""
        global _server_offset_ms
        req = bytes([0xE0, 0x00, 0x00, 0x00, 0x00])
        try:
            with write_lock:
                ser.reset_input_buffer()
                t_send = time.time()
                ser.write(req)
                ser.flush()
            # write_lock 外で読む (write_lock はライタースレッドとの排他のみ)
            resp = ser.read(9)
            t_recv = time.time()
            if len(resp) == 9 and resp[0] == 0xE1:
                server_ms = int.from_bytes(resp[5:9], 'big')
                rtt_ms = (t_recv - t_send) * 1000
                # RTT 中点でのServer時刻とPi時刻のズレを計算
                server_at_midpoint = server_ms - rtt_ms / 2
                pi_midpoint_ms = (t_send + t_recv) / 2 * 1000
                _server_offset_ms = server_at_midpoint - pi_midpoint_ms
                print(f"[cw_bridge] SYNC ok server_ms={server_ms} rtt={rtt_ms:.1f}ms offset={_server_offset_ms:.0f}ms", flush=True)
                return True
            else:
                print(f"[cw_bridge] SYNC bad resp len={len(resp)}", flush=True)
                return False
        except Exception as e:
            print(f"[cw_bridge] SYNC error: {e}", flush=True)
            return False

    # UDP ソケットを先にbind（SYNC中もポートを確保しパケット損失を防ぐ）
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("0.0.0.0", UDP_PORT))
    except Exception as e:
        print(f"[cw_bridge] bind failed: {e}", flush=True)
        sys.exit(1)
    sock.settimeout(0.01)
    print(f"[cw_bridge] UDP :{UDP_PORT} listening", flush=True)

    # 起動時: 最大3回 SYNC を試みる
    for attempt in range(3):
        if _sync_clock():
            break
        time.sleep(0.5)

    def ping_loop():
        """定期的に SYNC を実行して生存確認とオフセット更新を兼ねる"""
        while True:
            time.sleep(PING_INTERVAL)
            ok = _sync_clock()
            off = _server_offset_ms if _server_offset_ms is not None else 0
            _write_status(ok, off)
            print(f"[cw_bridge] ping {'ok' if ok else 'fail'}", flush=True)

    threading.Thread(target=ping_loop, daemon=True).start()

    print(f"[cw_bridge] UDP :{UDP_PORT} -> {dev}", flush=True)

    GUARD_MS = 20  # Pi→Server USB転送マージン

    while True:
        try:
            data, addr = sock.recvfrom(16)
        except socket.timeout:
            continue
        except Exception as e:
            print(f"[cw_bridge] recv error: {e}", flush=True)
            break

        if len(data) == 5 and data[0] == 0xE0:
            # M5 Client からのSYNCリクエスト: Server millis()を推定して返す
            # _server_offset_ms 未取得時はPi時刻をそのまま使用（LED青表示には十分）
            pi_now_ms = int(time.time() * 1000)
            if _server_offset_ms is not None:
                server_now_ms = int(pi_now_ms + _server_offset_ms) & 0xFFFFFFFF
            else:
                server_now_ms = pi_now_ms & 0xFFFFFFFF  # Pi時刻でフォールバック
            resp = bytes([0xE1]) + data[1:5] + server_now_ms.to_bytes(4, 'big')
            sock.sendto(resp, addr)
            print(f"[cw_bridge] SYNC tunnel server_now_ms={server_now_ms} (offset={'ok' if _server_offset_ms is not None else 'fallback'})", flush=True)

        elif len(data) == 10 and data[0] in (0x00, 0x01):
            trx_byte = data[1] if data[1] in (0x01, 0x02) else 0x01
            android_fire_ms = int.from_bytes(data[2:10], 'big')

            if android_fire_ms > 1 and _server_offset_ms is not None:
                pi_now_ms = int(time.time() * 1000)
                server_now_ms = int(pi_now_ms + _server_offset_ms)

                if android_fire_ms <= 0xFFFFFFFF:
                    # M5 Client SYNC済み: opTimeMsはM5 Server millis()基準 → そのまま使用
                    server_fire_ms = android_fire_ms
                    mode_str = "M5ms"
                else:
                    # 旧形式: Android Unix ms → Server millis() に変換
                    server_fire_ms = int(android_fire_ms + _server_offset_ms)
                    mode_str = "Ams"

                if server_fire_ms < server_now_ms + GUARD_MS:
                    late_ms = server_now_ms - server_fire_ms
                    if late_ms > 0 and late_ms > max_late_ms[0]:
                        max_late_ms[0] = late_ms
                    server_fire_ms = server_now_ms + GUARD_MS
                print(f"[cw_bridge] key {'ON' if data[0]==0x01 else 'OFF'} {mode_str}={android_fire_ms}", flush=True)
                pkt = bytes([data[0], trx_byte]) + server_fire_ms.to_bytes(8, 'big')
            else:
                # SYNC 未取得 or opTimeMs=1 (即時発火)
                pkt = bytes([data[0], trx_byte]) + bytes(7) + b'\x01'
            with write_lock:
                try:
                    ser.write(pkt)
                except Exception as e:
                    print(f"[cw_bridge] write error: {e}", flush=True)
        else:
            if len(data) not in (5, 10):
                print(f"[cw_bridge] unknown pkt len={len(data)} hex={data.hex()}", flush=True)

    sock.close()
    ser.close()
    print("[cw_bridge] stopped", flush=True)

if __name__ == "__main__":
    main()
