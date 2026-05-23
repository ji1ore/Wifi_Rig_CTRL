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

    while True:
        try:
            data, _ = sock.recvfrom(16)
        except socket.timeout:
            continue
        except Exception as e:
            print(f"[cw_bridge] recv error: {e}", flush=True)
            break

        if len(data) == 10 and data[0] in (0x00, 0x01):
            state_str = "ON " if data[0] == 0x01 else "OFF"
            android_fire_ms = int.from_bytes(data[2:10], 'big')
            print(f"[cw_bridge] key {state_str} trxSel=0x{data[1]:02x} android_ms={android_fire_ms}", flush=True)
            trx_byte = data[1] if data[1] in (0x01, 0x02) else 0x01
            if android_fire_ms > 1 and _server_offset_ms is not None:
                # Android タイムスタンプ → Server millis() に変換して転送
                server_fire_ms = int(android_fire_ms + _server_offset_ms)
                # VPN遅延でパケットが遅延到着した場合の検出:
                # server_fire_ms < server_now なら既に過去 → リスケジュール
                # (旧: server_fire_ms < 1 は負値のみ検出で不十分だった)
                pi_now_ms = int(time.time() * 1000)
                server_now_ms = int(pi_now_ms + _server_offset_ms)
                GUARD_MS = 20  # Pi→Server USB転送マージン
                if server_fire_ms < server_now_ms + GUARD_MS:
                    late_ms = server_now_ms - server_fire_ms
                    if late_ms > 0:
                        if late_ms > max_late_ms[0]:
                            max_late_ms[0] = late_ms
                        print(f"[cw_bridge] VPN遅延 {late_ms}ms超過 -> +{GUARD_MS}ms reschedule", flush=True)
                    server_fire_ms = server_now_ms + GUARD_MS
                pkt = bytes([data[0], trx_byte]) + server_fire_ms.to_bytes(8, 'big')
            else:
                # SYNC 未取得 or 旧形式 (opTimeMs=0): 即時発火
                pkt = bytes([data[0], trx_byte]) + bytes(7) + b'\x01'
            with write_lock:
                try:
                    ser.write(pkt)
                except Exception as e:
                    print(f"[cw_bridge] write error: {e}", flush=True)
        elif len(data) != 10:
            print(f"[cw_bridge] unknown pkt len={len(data)} hex={data.hex()}", flush=True)

    sock.close()
    ser.close()
    print("[cw_bridge] stopped", flush=True)

if __name__ == "__main__":
    main()
