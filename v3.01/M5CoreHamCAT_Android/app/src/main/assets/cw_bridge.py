#!/usr/bin/env python3
"""M5ATOM RemoteKeyer CW ブリッジ  v2.03

シリアルモード (M5ATOMLite / 旧バージョン互換):
    python3 cw_bridge.py /dev/ttyUSBx
    python3 cw_bridge.py --mode serial /dev/ttyUSBx

USB-NCMモード (M5ATOMS3Lite):
    python3 cw_bridge.py --mode ncm [SERVER_NCM_IP]
    python3 cw_bridge.py --mode ncm               # デフォルト: 192.168.7.1

USB-NCM モードについて:
    ATOM S3 Lite サーバーが USB-NCM デバイスとして Pi に接続されます。
    Pi が USB ホストとなり、DHCP で 192.168.7.2 を取得します。
    cw_bridge は UDP でサーバー (192.168.7.1:8888) と直接通信します。
    シリアル通信は不要です。
"""
import argparse
import json
import os
import socket
import threading
import time
import sys

UDP_CLIENT_PORT = 8889   # iOS/Android/クライアントアプリ向けポート
UDP_SERVER_PORT = 8888   # M5 Server への UDP ポート (WiFi・NCM 共通)
BAUD            = 115200
PING_INTERVAL   = 5.0
STATUS_FILE     = "/tmp/cw_bridge_status.json"

# ─────────────────────────────────────────────────────────────────────────────
# ユーティリティ
# ─────────────────────────────────────────────────────────────────────────────
def write_status(synced: bool, offset_ms: float = 0, max_late_ms: float = 0):
    tmp = STATUS_FILE + ".tmp"
    try:
        with open(tmp, 'w') as f:
            json.dump({"synced": synced, "offset_ms": offset_ms,
                       "max_late_ms": max_late_ms, "t": time.time()}, f)
        os.replace(tmp, STATUS_FILE)
    except Exception:
        pass

# ─────────────────────────────────────────────────────────────────────────────
# USB-NCM モード
# ─────────────────────────────────────────────────────────────────────────────
def run_ncm(server_ncm_ip: str):
    """ATOM S3 Lite サーバーと UDP で直接通信する UDP リレー。
    クライアント (iOS/Android) → Pi:8889 → Server NCM:8888 → Pi → クライアント
    Server が SYNC・PONG を UDP で処理するため Pi での変換は不要。"""

    print(f"[cw_bridge] NCM モード起動: Server={server_ncm_ip}:{UDP_SERVER_PORT}", flush=True)

    # クライアント向けソケット (iOS/Android アプリから受信)
    client_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    client_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        client_sock.bind(("0.0.0.0", UDP_CLIENT_PORT))
    except Exception as e:
        print(f"[cw_bridge] bind 失敗: {e}", flush=True)
        sys.exit(1)
    client_sock.settimeout(0.05)

    # サーバー向けソケット (NCM インタフェース経由で ATOM S3 Lite と通信)
    server_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    server_sock.settimeout(0.05)

    last_client_addr = [None]
    addr_lock = threading.Lock()
    active = [True]

    # Server → Client 転送スレッド
    def server_to_client():
        while active[0]:
            try:
                data, _ = server_sock.recvfrom(64)
                with addr_lock:
                    ca = last_client_addr[0]
                if ca:
                    client_sock.sendto(data, ca)
                    if data and data[0] == 0xFE:
                        print("[cw_bridge] PONG → Client", flush=True)
                    elif len(data) == 9 and data[0] == 0xE1:
                        print("[cw_bridge] SYNC resp → Client", flush=True)
            except socket.timeout:
                pass
            except Exception as e:
                if active[0]:
                    print(f"[cw_bridge] server_to_client エラー: {e}", flush=True)

    t = threading.Thread(target=server_to_client, daemon=True)
    t.start()

    print(f"[cw_bridge] UDP :{UDP_CLIENT_PORT} → {server_ncm_ip}:{UDP_SERVER_PORT}", flush=True)
    write_status(True, 0)

    # Client → Server 転送メインループ
    while True:
        try:
            data, addr = client_sock.recvfrom(64)
        except socket.timeout:
            continue
        except Exception as e:
            print(f"[cw_bridge] recv エラー: {e}", flush=True)
            break

        with addr_lock:
            last_client_addr[0] = addr

        try:
            server_sock.sendto(data, (server_ncm_ip, UDP_SERVER_PORT))
        except Exception as e:
            print(f"[cw_bridge] server 送信エラー: {e}", flush=True)
            continue

        # ログ
        if len(data) == 5 and data[0] == 0xE0:
            print(f"[cw_bridge] SYNC req → Server", flush=True)
        elif len(data) == 10 and data[0] in (0x00, 0x01):
            print(f"[cw_bridge] KEY {'ON' if data[0]==0x01 else 'OFF'} → Server", flush=True)
        elif len(data) == 1 and data[0] == 0xFF:
            print(f"[cw_bridge] PING → Server", flush=True)

    active[0] = False
    client_sock.close()
    server_sock.close()
    print("[cw_bridge] NCM 停止", flush=True)

# ─────────────────────────────────────────────────────────────────────────────
# シリアルモード (M5ATOMLite / 旧バージョン互換)
# ─────────────────────────────────────────────────────────────────────────────
def run_serial(dev: str):
    """シリアル経由で ATOM Lite サーバーと通信するブリッジ。
    元の実装と同一の動作。"""
    try:
        import serial as pyserial
    except ImportError:
        print("[cw_bridge] pyserial が見つかりません: pip install pyserial", flush=True)
        sys.exit(1)

    print(f"[cw_bridge] シリアルモード起動: {dev}", flush=True)
    try:
        ser = pyserial.Serial()
        ser.port      = dev
        ser.baudrate  = BAUD
        ser.bytesize  = pyserial.EIGHTBITS
        ser.parity    = pyserial.PARITY_NONE
        ser.stopbits  = pyserial.STOPBITS_ONE
        ser.timeout   = 0.5
        ser.dtr       = False
        ser.rts       = False
        ser.open()
        time.sleep(0.1)
        ser.reset_input_buffer()
        print("[cw_bridge] シリアル OK", flush=True)
    except Exception as e:
        print(f"[cw_bridge] シリアルオープン失敗: {e}", flush=True)
        sys.exit(1)

    write_lock  = threading.Lock()
    max_late_ms = [0]
    _server_offset_ms = [None]

    def _write_status_local(synced):
        write_status(synced, _server_offset_ms[0] or 0, max_late_ms[0])

    def _sync_clock():
        req = bytes([0xE0, 0x00, 0x00, 0x00, 0x00])
        try:
            with write_lock:
                ser.reset_input_buffer()
                t_send = time.time()
                ser.write(req); ser.flush()
            resp  = ser.read(9)
            t_recv = time.time()
            if len(resp) == 9 and resp[0] == 0xE1:
                server_ms  = int.from_bytes(resp[5:9], 'big')
                rtt_ms     = (t_recv - t_send) * 1000
                midpoint   = server_ms - rtt_ms / 2
                pi_mid_ms  = (t_send + t_recv) / 2 * 1000
                _server_offset_ms[0] = midpoint - pi_mid_ms
                print(f"[cw_bridge] SYNC ok server_ms={server_ms} rtt={rtt_ms:.1f}ms "
                      f"offset={_server_offset_ms[0]:.0f}ms", flush=True)
                return True
            print(f"[cw_bridge] SYNC 応答不正 len={len(resp)}", flush=True)
            return False
        except Exception as e:
            print(f"[cw_bridge] SYNC エラー: {e}", flush=True)
            return False

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("0.0.0.0", UDP_CLIENT_PORT))
    except Exception as e:
        print(f"[cw_bridge] bind 失敗: {e}", flush=True)
        sys.exit(1)
    sock.settimeout(0.01)
    print(f"[cw_bridge] UDP :{UDP_CLIENT_PORT} リッスン中", flush=True)

    for _ in range(3):
        if _sync_clock(): break
        time.sleep(0.5)

    def ping_loop():
        while True:
            time.sleep(PING_INTERVAL)
            ok = _sync_clock()
            _write_status_local(ok)

    threading.Thread(target=ping_loop, daemon=True).start()

    GUARD_MS = 20
    print(f"[cw_bridge] UDP :{UDP_CLIENT_PORT} → {dev}", flush=True)

    while True:
        try:
            data, addr = sock.recvfrom(16)
        except socket.timeout:
            continue
        except Exception as e:
            print(f"[cw_bridge] recv エラー: {e}", flush=True)
            break

        if len(data) == 5 and data[0] == 0xE0:
            if _server_offset_ms[0] is not None:
                pi_now_ms    = int(time.time() * 1000)
                server_now   = int(pi_now_ms + _server_offset_ms[0]) & 0xFFFFFFFF
                resp = bytes([0xE1]) + data[1:5] + server_now.to_bytes(4, 'big')
                sock.sendto(resp, addr)
                print(f"[cw_bridge] SYNC tunnel server_now_ms={server_now}", flush=True)

        elif len(data) == 10 and data[0] in (0x00, 0x01):
            trx_byte       = data[1] if data[1] in (0x01, 0x02) else 0x01
            android_fire   = int.from_bytes(data[2:10], 'big')
            if android_fire > 1 and _server_offset_ms[0] is not None:
                pi_now_ms  = int(time.time() * 1000)
                server_now = int(pi_now_ms + _server_offset_ms[0])
                if android_fire <= 0xFFFFFFFF:
                    server_fire = android_fire
                    mode_str    = "M5ms"
                else:
                    server_fire = int(android_fire + _server_offset_ms[0])
                    mode_str    = "Ams"
                if server_fire < server_now + GUARD_MS:
                    late = server_now - server_fire
                    if late > 0 and late > max_late_ms[0]: max_late_ms[0] = late
                    server_fire = server_now + GUARD_MS
                print(f"[cw_bridge] KEY {'ON' if data[0]==0x01 else 'OFF'} "
                      f"{mode_str}={android_fire}", flush=True)
                pkt = bytes([data[0], trx_byte]) + server_fire.to_bytes(8, 'big')
            else:
                pkt = bytes([data[0], trx_byte]) + bytes(7) + b'\x01'
            with write_lock:
                try:
                    ser.write(pkt)
                except Exception as e:
                    print(f"[cw_bridge] write エラー: {e}", flush=True)

    sock.close(); ser.close()
    print("[cw_bridge] シリアル停止", flush=True)

# ─────────────────────────────────────────────────────────────────────────────
# エントリーポイント
# ─────────────────────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description="RemoteKeyer CW ブリッジ")
    parser.add_argument("--mode", choices=["serial", "ncm"], default=None,
                        help="動作モード: serial (デフォルト) / ncm")
    parser.add_argument("arg", nargs="?", default=None,
                        help="シリアルモード: デバイスパス (/dev/ttyUSBx)\n"
                             "NCM モード: Server NCM IP (省略時: 192.168.7.1)")
    args = parser.parse_args()

    # モード自動判定: 引数が /dev/ で始まればシリアル, それ以外は NCM
    if args.mode is None:
        if args.arg and args.arg.startswith("/dev/"):
            args.mode = "serial"
        else:
            args.mode = "ncm"

    if args.mode == "ncm":
        server_ip = args.arg if args.arg and not args.arg.startswith("/dev/") else "192.168.7.1"
        run_ncm(server_ip)
    else:
        dev = args.arg or "/dev/ttyUSB0"
        run_serial(dev)

if __name__ == "__main__":
    main()
