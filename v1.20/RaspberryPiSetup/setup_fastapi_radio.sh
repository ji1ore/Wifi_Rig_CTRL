#!/bin/bash
set -e

sudo apt update -y
sudo apt upgrade -y
sudo apt install -y build-essential libtool libusb-1.0-0-dev libncurses5-dev
sudo apt install -y git build-essential autoconf automake libtool pkg-config
sudo apt install -y ffmpeg alsa-utils python3-fastapi python3-pip
sudo apt install sox libsox-fmt-all -y

wget https://github.com/Hamlib/Hamlib/releases/download/4.7.1/hamlib-4.7.1.tar.gz
tar xvf hamlib-4.7.1.tar.gz
cd hamlib-4.7.1
./configure --prefix=/usr/local
make -j4
sudo make install
echo "/usr/local/lib" | sudo tee /etc/ld.so.conf.d/hamlib.conf
sudo ldconfig

rigctl --version


sudo sysctl --system

sudo apt update -y
sudo apt install -y python3-requests
sudo apt install -y python3-pip python3-venv

python3 -m venv ~/fastapi
source ~/fastapi/bin/activate

pip install fastapi uvicorn
pip3 install uvicorn
sudo apt install -y uvicorn


source /home/pi/fastapi/bin/activate
pip install python-multipart
deactivate


sudo apt update
sudo apt install -y git cmake build-essential libasound2-dev

cd ~
git clone https://www.github.com/wb2osz/direwolf
cd direwolf

mkdir build && cd build
cmake ..
make -j4
sudo make install
sudo make install-conf


cat << 'EOF' > /home/pi/fastapi/api.py
from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from starlette.background import BackgroundTask
from fastapi import BackgroundTasks
from fastapi import Request
from fastapi import Form
from pydantic import BaseModel
import glob
import os
import subprocess
import socket
import signal
import select
import time
import threading 

app = FastAPI()

# ★ グローバル変数（可変リグ対応）
rig = None 
current_model = None 
current_cat = None 
current_baud = None
skip_poll_until = 0
poll_enabled = True
last_user_freq_change = 0
last_user_mode_change = 0

# --- APRS 送信管理 ---
aprs_running = False
aprs_thread = None
aprs_last_heartbeat = 0
aprs_freq = None
aprs_interval = None
normal_freq = None

# --- APRS GPS / 手動座標 ---
aprs_use_gps = True
aprs_manual_lat = 0.0
aprs_manual_lon = 0.0

radio_cache = {
    "freq": "",
    "mode": None,
    "width": None,
    "signal": "",
    "tx": False,
    "power": "",
    "sql": ""
}

rig_lock = threading.Lock()

last_power = 0
last_sql = 0

KISS_HOST = "127.0.0.1"
KISS_PORT = 8001
aprs_cfg = None

tx_started = False
tx_done = False
tx_watch_thread = None
tx_watch_running = False
tx_in_progress = False

class GPSData(BaseModel):
    lat: float
    lon: float

def on_tx_complete():
    global tx_done
    tx_done = True

def wait_tx_complete(timeout=5.0):
    global tx_started, tx_done

    tx_started = False
    tx_done = False

    start = time.time()

    # --- TX 開始を待つ ---
    while time.time() - start < timeout:
        if tx_started:
            break
        time.sleep(0.05)

    if not tx_started:
        print("⚠️ TX 開始が検出できなかった")
        return False

    # --- TX 完了を待つ ---
    while time.time() - start < timeout:
        if tx_done:
            return True
        time.sleep(0.05)

    return False



def watch_direwolf_tx():
    global tx_watch_running, tx_started, tx_done

    tx_watch_running = True

    proc = subprocess.Popen(
        ["journalctl", "-u", "direwolf", "-f", "-n", "0"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )

    for line in proc.stdout:
        if not tx_watch_running:
            break

        line = line.strip()

        # --- TX 開始検出 ---
        if ("PTT ON" in line or
            "Transmit" in line or
            "Sending packet" in line or
            "audio: transmit" in line):
            tx_started = True
            # print("[DEBUG] TX START detected")

        # --- TX 完了検出 ---
        if ("PTT OFF" in line or
            "TX complete" in line or
            "audio: PTT OFF" in line):
            tx_done = True
            # print("[DEBUG] TX DONE detected")


def send_kiss(frame: bytes):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    s.connect((KISS_HOST, KISS_PORT))
    s.sendall(frame)
    s.close()

def kiss_frame(payload: str) -> bytes:
    data = payload.encode("ascii")

    inner = bytearray()
    inner.append(0x00)  # KISS コマンド

    # エスケープ処理
    for b in data:
        if b == 0xC0:
            inner.extend([0xDB, 0xDC])
        elif b == 0xDB:
            inner.extend([0xDB, 0xDD])
        else:
            inner.append(b)

    # ★ 外側の SLIP framing を付ける（Direwolf が必要とする）
    frame = bytearray()
    frame.append(0xC0)
    frame.extend(inner)
    frame.append(0xC0)

    return bytes(frame)

def encode_ax25_addr(callsign: str, ssid: int, last: bool) -> bytes:
    """
    AX.25 アドレスフィールド 7バイトを生成
    callsign: "JI1ORE" など（6文字まで）
    ssid: 0〜15
    last: 最後のアドレスなら True（HDLC アドレスの LSB=1）
    """
    call = callsign.upper().ljust(6)[:6]
    addr = bytearray()

    # コールサイン 6文字を 7bit 左シフト
    for c in call:
        addr.append(ord(c) << 1)

    # SSID フィールド
    ssid_byte = 0x60 | ((ssid & 0x0F) << 1)  # C-bit=0, reserved=1,1
    if last:
        ssid_byte |= 0x01  # HDLC アドレスの末尾フラグ
    addr.append(ssid_byte)

    return bytes(addr)


def build_ax25_ui_frame(src_call: str, src_ssid: int,
                        dest_call: str, dest_ssid: int,
                        path: list[str],
                        info: str) -> bytes:
    """
    AX.25 UI フレーム（バイナリ）を生成
    path: ["WIDE1-1", "WIDE2-1"] など
    info: "!3552.95N/13938.29E-" など APRS 情報フィールド
    """
    addrs = bytearray()

    # 宛先アドレス（最初）
    addrs += encode_ax25_addr(dest_call, dest_ssid, last=False)

    # 送信元アドレス（2番目）
    #   path がある場合は last=False, path が無い場合は last=True
    last_src = (len(path) == 0)
    addrs += encode_ax25_addr(src_call, src_ssid, last=last_src)

    # 経路（WIDE1-1, WIDE2-1 など）
    for i, p in enumerate(path):
        if "-" in p:
            call, ssid_str = p.split("-", 1)
            ssid = int(ssid_str)
        else:
            call, ssid = p, 0

        last = (i == len(path) - 1)  # 最後の経路だけ last=True
        addrs += encode_ax25_addr(call, ssid, last=last)

    # コントロールフィールド（UIフレーム）
    control = 0x03
    # PID（no layer 3）
    pid = 0xF0

    frame = bytearray()
    frame += addrs
    frame.append(control)
    frame.append(pid)
    frame += info.encode("ascii")

    return bytes(frame)


def kiss_wrap(ax25_frame: bytes) -> bytes:
    """
    AX.25 フレームを KISS フレームにラップ
    ポート0, コマンド=0x00（データ）
    """
    FEND = 0xC0
    FESC = 0xDB
    TFEND = 0xDC
    TFESC = 0xDD

    out = bytearray()
    out.append(FEND)
    out.append(0x00)  # ポート0, コマンド=データ

    # エスケープ処理
    for b in ax25_frame:
        if b == FEND:
            out.append(FESC)
            out.append(TFEND)
        elif b == FESC:
            out.append(FESC)
            out.append(TFESC)
        else:
            out.append(b)

    out.append(FEND)
    return bytes(out)


def aprs_lat(lat):
    deg = int(lat)
    minutes = (lat - deg) * 60
    return f"{deg:02d}{minutes:05.2f}N"

def aprs_lon(lon):
    deg = int(lon)
    minutes = (lon - deg) * 60
    return f"{deg:03d}{minutes:05.2f}E"

#@app.get("/aprs")
#def aprs_send():
#    ax25 = "JI1ORE-7>APRS:!3530.00N/13930.00E-"
#    frame = kiss_frame(ax25)
#    send_kiss(frame)
#    return {"status": "sent", "frame": ax25}

class AprsConfig(BaseModel):
    callsign: str
    ssid: int
    path: str
    interval: int
    freq: float
    baud: int
    use_gps: bool
    manual_lat: float
    manual_lon: float
    symbol: str
    destination: str
    sound_device: str
    rig_id: str          
    cat_device: str     

class AprsStart(BaseModel):
    freq: float
    interval: int

@app.post("/aprs_heartbeat")
def aprs_heartbeat():
    global aprs_last_heartbeat
    aprs_last_heartbeat = time.time()
    return {"status": "ok"}


def aprs_loop():
    global aprs_running, aprs_freq, aprs_interval, normal_freq, aprs_last_heartbeat, tx_done, tx_in_progress

    try:
        while aprs_running:

            # ★ TX中なら次のTXを開始しない
            if tx_in_progress:
                time.sleep(0.1)
                continue

            loop_start = time.time()

            # --- heartbeat チェック ---
            if time.time() - aprs_last_heartbeat > 15:
                print("⚠️ APRS heartbeat 消失 → APRS 停止")
                aprs_running = False
                break

            # --- 現在の周波数を取得（APRS 切替前に必ず実行） ---
            cur = rigctl_cmd_priority("f")
            normal_freq = int(cur.split()[0])

            # --- モード確認 ---
            try:
                mode_raw = rigctl_cmd("m")
                lines = [l.strip() for l in mode_raw.splitlines() if l.strip()]
                mode = lines[0].upper() if lines else ""
            except:
                mode = ""

            if not mode.startswith("FM"):
                print(f"⚠️ APRS 中断：現在のモードが FM ではありません → {mode}")
                time.sleep(1)
                continue

            # --- GPS or manual ---
            if aprs_use_gps:
                lat = latest_gps["lat"]
                lon = latest_gps["lon"]
            else:
                lat = aprs_manual_lat
                lon = aprs_manual_lon

            print(f"[APRS] TX lat={lat} lon={lon}")

            # --- APRS 周波数へ変更 ---
            tx_in_progress = True   # ★ TXロック開始
            aprs_last_heartbeat = time.time()
            rigctl_cmd_priority(f"F {int(aprs_freq * 1_000_000)}")
            time.sleep(0.25)   # ★ IC-705 の PLL 安定待ち

            # --- KISS フレーム送信 ---
            lat_aprs = aprs_lat(lat)
            lon_aprs = aprs_lon(lon)
            info = f"!{lat_aprs}/{lon_aprs}>"

            ax25 = build_ax25_ui_frame(
                src_call=aprs_cfg.callsign,            
                src_ssid=aprs_cfg.ssid,
                dest_call="APDW18",
                dest_ssid=0,
                path=aprs_cfg.path.split(","),   # "WIDE1-1,WIDE2-1" → ["WIDE1-1","WIDE2-1"]
                info=info
            )

            frame = kiss_wrap(ax25)

            tx_done = False
            print("[DEBUG] before send_kiss")
            send_kiss(frame)
            print("[DEBUG] after send_kiss")
            time.sleep(0.15)
            print("[APRS] KISS フレーム送信 → Direwolf TX 完了待ち")
            print("[AX25]", ax25)

            # --- TX 完了待ち（最大 5 秒） ---
            if wait_tx_complete(timeout=5.0):
                print("[APRS] Direwolf TX 完了")
            else:
                print("⚠️ Direwolf TX 完了がタイムアウト（強制復帰）")

            time.sleep(0.15)  # ★ PTT OFF 安定待ち

            # --- 元の周波数へ戻す ---
            if normal_freq:
                rigctl_cmd_priority(f"F {normal_freq}")
                time.sleep(0.15)  # ★ 復帰後の安定待ち

            tx_in_progress = False   # ★ TXロック解除

            # --- 周期調整（30秒を正確に保つ） ---
            elapsed = time.time() - loop_start
            sleep_time = max(0, aprs_interval - elapsed)
            time.sleep(sleep_time)

    except Exception as e:
        print("APRS thread crashed:", e)

    finally:
        tx_in_progress = False
        aprs_running = False


@app.post("/aprs_start")
def aprs_start(cfg: AprsStart):
    global aprs_running, aprs_thread, aprs_freq, aprs_interval, aprs_last_heartbeat
    global tx_watch_thread, tx_watch_running
    global aprs_cfg

    if aprs_cfg is None:
        return {"error": "APRS config not set. Call /aprs_config first."}

    subprocess.run(["sudo", "systemctl", "stop", "fastapi-audio.service"], check=True)

    aprs_running = False
    time.sleep(0.1)

    aprs_freq = cfg.freq
    aprs_interval = cfg.interval
    aprs_last_heartbeat = time.time()

    aprs_running = True
    aprs_thread = threading.Thread(target=aprs_loop, daemon=True)
    aprs_thread.start()

    tx_watch_running = True
    tx_watch_thread = threading.Thread(target=watch_direwolf_tx, daemon=True)
    tx_watch_thread.start()

    return {"status": "started"}



@app.post("/aprs_stop")
def aprs_stop():
    global aprs_running
    global poll_enabled
    global tx_watch_running

    poll_enabled = True

    aprs_running = False
    tx_watch_running = False

    # APRS 停止後に fastapi-audio を再起動
    subprocess.run(["sudo", "systemctl", "start", "fastapi-audio.service"])
    subprocess.run(["sudo", "systemctl", "restart", "direwolf.service"])

    return {"status": "stopped"}


@app.post("/gps")
def update_gps(data: GPSData):
    global latest_gps
    latest_gps = {"lat": data.lat, "lon": data.lon}
    return {"status": "ok"}

@app.get("/gps")
def get_gps():
    return latest_gps

def rigctl_alive():
    try:
        s = socket.socket()
        s.settimeout(0.2)
        s.connect(("localhost", 4532))
        s.close()
        return True
    except:
        return False

@app.post("/aprs_config")
def update_aprs_config(cfg: AprsConfig):
    global aprs_use_gps, aprs_manual_lat, aprs_manual_lon
    global aprs_cfg

    # --- M5 からの値を取得 ---
    callsign = cfg.callsign
    ssid = cfg.ssid
    baud = cfg.baud
    sound_device = cfg.sound_device  
    rig_id = cfg.rig_id
    cat_device = cfg.cat_device

    if not cat_device.startswith("/dev/"):
        cat_device = f"/dev/{cat_device}"

    # MODEM 設定
    modem = 1200 if baud == 1200 else 9600

    # --- direwolf.conf を生成 ---
    conf = f"""ADEVICE null {sound_device}
CHANNEL 0

MYCALL {callsign}-{ssid}
MODEM {modem}

# KISS / AGW
KISSPORT 8001
AGWPORT 8050

# PTT
PTT RIG {rig_id} {cat_device}
"""

    with open("/home/pi/direwolf.conf", "w") as f:
        f.write(conf)

    # Direwolf 再起動
    import time
    subprocess.run(["sudo", "systemctl", "restart", "direwolf"])
    time.sleep(0.50)
    # APRS 設定を保存
    aprs_cfg = cfg

    return {"status": "ok"}


def rigctl_cmd_priority(cmd: str):
    """PTT専用：ロックを待たず即実行"""
    start = time.time()
    print(f"[rigctl_cmd_priority] START cmd='{cmd}' t={start}")

    try:
        s = socket.socket()
        s.settimeout(0.15)  # 優先なので短め
        s.connect(("localhost", 4532))
        s.sendall((cmd + "\n").encode())

        try:
            raw = s.recv(4096).decode().strip()
        except socket.timeout:
            raw = ""

        print(f"[rigctl_cmd_priority] RAW cmd='{cmd}' -> '{raw}'")
        return raw

    finally:
        end = time.time()
        print(f"[rigctl_cmd_priority] END cmd='{cmd}' duration={end-start:.3f}s")
        s.close()


def rigctl_cmd(cmd: str):
    with rig_lock:
        start = time.time()
        print(f"[rigctl_cmd] START cmd='{cmd}' t={start}")

        try:
            s = socket.socket()
            s.settimeout(0.3)
            s.connect(("localhost", 4532))
            s.sendall((cmd + "\n").encode())

            chunks = []
            while True:
                try:
                    chunk = s.recv(4096)
                    if not chunk:
                        break
                    chunks.append(chunk)
                except socket.timeout:
                    break

            raw = b"".join(chunks).decode().strip()
            print(f"[rigctl_cmd] RAW cmd='{cmd}' -> '{raw}'")  # ★ 追加
            return raw

        finally:
            end = time.time()
            print(f"[rigctl_cmd] END cmd='{cmd}' duration={end-start:.3f}s")
            s.close()

last_tx = 0

def poll_rig():
    global poll_enabled, last_tx

    while not rigctl_alive():
        time.sleep(0.2)

    while True:

        # --- TX 状態は高速ポーリング（50ms） ---
        try:
            tx_raw = rigctl_cmd("t")
            tx = int(tx_raw.split()[0])
            radio_cache["tx"] = bool(tx)
        except:
            tx = 0
            radio_cache["tx"] = False

        # TX → RX の瞬間だけクールダウン
        if last_tx == 1 and tx == 0:
            time.sleep(0.2)
        last_tx = tx

        # TX中は他の rigctl を叩かない
        if tx == 1:
            time.sleep(0.05)
            continue

        # ★ poll_enabled=0 のときは完全軽量化（MENU_PTT 相当）
        if not poll_enabled:
            time.sleep(0.1)
            continue

        # freq/mode の取得
        try:
            freq_raw = rigctl_cmd("f")
            # ★ ユーザー操作から 0.5 秒以内は上書きしない
            if time.time() - last_user_freq_change > 0.5:
                radio_cache["freq"] = int(freq_raw.split()[0])

        except:
            pass

        try:
            mode_raw = rigctl_cmd("m")
            parts = mode_raw.split()

            # ★ ユーザー操作直後は上書きしない（0.5秒間）
            if time.time() - last_user_mode_change > 0.5:
                if len(parts) >= 1:
                    radio_cache["mode"] = parts[0]
                if len(parts) >= 2:
                    radio_cache["width"] = int(parts[1])

        except:
            pass

        time.sleep(0.5)

def poll_signal():
    global last_power, last_sql, poll_enabled

    while not rigctl_alive():
        time.sleep(0.2)

    while True:

        # ★ TX中は signal/power/sql を取らない
        if radio_cache.get("tx", False):
            time.sleep(0.1)
            continue

        # ★ poll_enabled=0 のときは signal だけ取る
        if not poll_enabled:
            try:
                raw = rigctl_cmd("l STRENGTH")
                sig = float(raw.split()[0])
                radio_cache["signal"] = max(sig, 0.0)
            except:
                radio_cache["signal"] = 0.0

            time.sleep(0.2)
            continue

        # ★ poll_enabled=1 のときは signal/power/sql を全部取る

        # signal
        try:
            raw = rigctl_cmd("l STRENGTH")
            sig = float(raw.split()[0])

            # --- 弱い信号補正（0〜2 → S1〜S5 相当に持ち上げる） ---
            if sig <= 2.0:
                # 0 → 1（S1）
                # 1 → 3（S3）
                # 2 → 5（S5）
                sig = 1.0 + sig * 2.0

            radio_cache["signal"] = sig

        except:
            radio_cache["signal"] = 1.0   # ノイズフロア扱い

        # power
        if time.time() - last_power > 3:
            try:
                raw = rigctl_cmd("l RFPOWER")
                radio_cache["power"] = float(raw.split()[0])
            except:
                pass
            last_power = time.time()

        # sql
        if time.time() - last_sql > 3:
            try:
                raw = rigctl_cmd("l SQL")
                radio_cache["sql"] = float(raw.split()[0])
            except:
                pass
            last_sql = time.time()

        time.sleep(0.5)




supported_modes = []

def get_supported_modes_from_rigctl_cli(model, cat, baud):
    result = subprocess.run(
        ["rigctl", "-m", str(model), "-r", f"/dev/{cat}", "-s", str(baud), "dump_caps"],
        capture_output=True, text=True
    )
    return result.stdout

rigctld_process = None

def start_rigctld(model, cat, baud):
    global rigctld_process

    # すでに動いているなら何もしない
    if rigctld_process and rigctld_process.poll() is None:
        print("rigctld already running")
        return

    print("starting rigctld...")
    rigctld_process = subprocess.Popen([
        "rigctld",
        "-m", str(model),
        "-r", f"/dev/{cat}",
        "-s", str(baud),
        "-t", "4532"
    ])

def watchdog_heartbeat():
    timeout = 3.0  # 3秒 heartbeat が来なければ異常
    while True:
        if radio_cache.get("tx", False):
            if time.time() - last_heartbeat > timeout:
                print("⚠️ Heartbeat 消失 → TX 強制 OFF")
                try:
                    rigctl_cmd("T 0")
                except:
                    pass
                radio_cache["tx"] = False
        time.sleep(0.1)



@app.post("/radio/poll")
def set_poll(state: int = Form(...)):
    global poll_enabled
    poll_enabled = bool(state)
    return {"poll_enabled": poll_enabled}


@app.on_event("startup")
def startup_event():
    threading.Thread(target=watchdog_heartbeat, daemon=True).start()


@app.get("/devices")
def list_devices():
    # --- シリアルデバイス（CAT用） ---
    usb = glob.glob("/dev/ttyUSB*")
    acm = glob.glob("/dev/ttyACM*")
    serial = [os.path.basename(d) for d in usb + acm]

    # --- 録音デバイス（Audio用） ---
    audio = []
    try:
        result = subprocess.run(["arecord", "-L"], capture_output=True, text=True, check=True)
        lines = result.stdout.splitlines()

        current_id = None
        current_label = None

        for line in lines:
            if not line.strip():
                continue
            if not line.startswith(" "):  # デバイスID行
                current_id = line.strip()
                current_label = None
            else:  # 説明行
                desc = line.strip()
                if current_id:
                    label = f"{desc} ({current_id})"
                    audio.append({
                        "id": current_id,
                        "label": label
                    })
                    current_id = None  # 次のデバイスに備える
    except Exception as e:
        print(f"arecord -L error: {e}")

    return {
        "serial": serial,
        "audio": audio
    }

@app.get("/rigs")
def list_rigs():
    result = subprocess.run(["rigctl", "-l"], capture_output=True, text=True)
    lines = result.stdout.splitlines()

    rigs = []
    for line in lines:
        parts = line.strip().split()
        if len(parts) >= 3 and parts[0].isdigit():
            rig_id = int(parts[0])
            manufacturer = parts[1]
            model = parts[2]
            rigs.append({
                "id": rig_id,
                "name": f"{manufacturer} {model}"
            })

    return {"rigs": rigs}

poll_started = False

@app.get("/radio/open")
def open_radio(model: int, cat: str, baud: int = 38400, audio: str = ""):
    global current_model, current_cat, current_baud, poll_started

    current_model = model
    current_cat = cat
    current_baud = baud

    start_rigctld(model, cat, baud)
    time.sleep(1.0)

    # ★ スレッドは一度だけ起動
    if not poll_started:
        threading.Thread(target=poll_rig, daemon=True).start()
        threading.Thread(target=poll_signal, daemon=True).start()
        poll_started = True

    return {"status": "ok"}



@app.post("/radio/setlevel")
def set_level(
    name: str = Form(...),
    value: float = Form(...)
):
    radio_cache[name.lower()] = value
    threading.Thread(target=lambda: rigctl_cmd(f"L {name.upper()} {value}"), daemon=True).start()

    return {"status": "ok", "level": name, "value": value}



@app.get("/radio/freq")
def get_freq():
    data = rigctl_cmd("f")
    return {"freq": data}


@app.post("/radio/setfreq")
def set_freq(f: int = Form(...)):
    global last_user_freq_change
    radio_cache["freq"] = f
    last_user_freq_change = time.time()
    threading.Thread(target=lambda: rigctl_cmd(f"F {f}"), daemon=True).start()
    return {"status": "ok", "freq": f}


@app.get("/radio/mode")
def get_mode():
    data = rigctl_cmd("m")
    return {"mode": data}


@app.post("/radio/setmode")
def set_mode(mode: str = Form(...), width: int = Form(...)):
    global last_user_mode_change
    radio_cache["mode"] = mode
    radio_cache["width"] = width
    last_user_mode_change = time.time()
    threading.Thread(target=lambda: rigctl_cmd(f"M {mode} {width}"), daemon=True).start()
    return {"status": "ok", "mode": mode, "width": width}


last_heartbeat = time.time()

last_ptt_state = 0   # 0=OFF, 1=ON


@app.post("/radio/ptt")
def ptt(state: int = Form(...)):
    global last_ptt_state, last_heartbeat

    # OFF は常に即時反映
    if state == 0:
        rigctl_cmd_priority("T 0")   # ← 優先実行
        radio_cache["tx"] = False
        last_ptt_state = 0
        return {"status": "ok", "ptt": 0}

    # ON は毎回即時実行
    rigctl_cmd_priority("T 1")       # ← 優先実行
    radio_cache["tx"] = True
    last_ptt_state = 1

    # heartbeat 更新
    last_heartbeat = time.time()

    return {"status": "ok", "ptt": 1}


@app.get("/radio/vfo")
def set_vfo(v: str):
    rigctl_cmd(f"V {v}")
    return {"status": "ok", "vfo": v}

@app.get("/radio/split")
def split(state: int):
    rigctl_cmd(f"S {state}")
    return {"status": "ok", "split": state}

@app.get("/radio/smeter")
def smeter():
    data = rigctl_cmd("l STRENGTH")
    return {"smeter": data}

@app.get("/radio/step")
def freq_step(step: int):
    cur = int(rigctl_cmd("f"))
    new = cur + step
    rigctl_cmd(f"F {new}")
    return {"status": "ok", "old": cur, "new": new}


@app.get("/radio/modes")
def list_modes():
    modes = [
        "LSB", "USB", "CW", "CWR", "AM", "FM",
        "DIGL", "DIGU", "PKTLSB", "PKTUSB", "PKTFM"
    ]
    return {"modes": modes}

@app.get("/radio/ptt_toggle")
def ptt_toggle():
    state = rigctl_cmd("t")
    try:
        state = int(state)
    except:
        return {"error": "ptt read error", "raw": state}

    new_state = 0 if state == 1 else 1
    rigctl_cmd(f"T {new_state}")

    return {"status": "ok", "old": state, "new": new_state}

@app.get("/radio/vfo_toggle")
def vfo_toggle():
    cur = rigctl_cmd("v")

    if "A" in cur:
        new = "VFOB"
    else:
        new = "VFOA"

    rigctl_cmd(f"V {new}")

    return {"status": "ok", "old": cur, "new": new}

@app.get("/radio/status")
def radio_status():
    return radio_cache

@app.get("/radio/caps")
def radio_caps():
    out = get_supported_modes_from_rigctl_cli(current_model, current_cat, current_baud)

    modes = []
    for raw in out.splitlines():
        line = raw.strip().lower()
        if line.startswith("mode list:") or line.startswith("modes:") or line.startswith("available modes:"):
            parts = raw.split(":", 1)[1].strip().split()
            modes.extend(m.upper() for m in parts)

    return {
        "modes": sorted(set(modes)),
        "raw": out
    }


@app.post("/radio/setpower")
def set_power(value: float = Form(...)):
    radio_cache["power"] = value
    threading.Thread(target=lambda: rigctl_cmd(f"L RFPOWER {value}"), daemon=True).start()
    return {"status": "ok", "power": value}



@app.get("/radio/audio")
def audio_stream(request: Request, background_tasks: BackgroundTasks):
    rate = request.query_params.get("rate", "48000") 
    if not rate.isdigit(): 
        rate = "48000" 
    print(f"★ サンプリングレート: {rate} Hz")

    subprocess.run(["pkill", "-f", "ffmpeg"])
    cmd = [
        "ffmpeg",
        "-f", "alsa",
        "-thread_queue_size", "1024",
        "-ar", rate,
        "-sample_fmt", "s16",
        "-i", "plughw:CARD=CODEC,DEV=0",
        "-ac", "1",
        "-af", "highpass=f=300,lowpass=f=4000",
        "-filter:a", "volume=10.0" ,
        "-f", "s16le",
        "-acodec", "pcm_s16le",
        "-nostdin",
        "-vn", "-sn", "-dn",
        "-map", "0:a",
        "-flush_packets", "1",
        "-nostats", "-loglevel", "quiet",
        "pipe:1"
    ]


    process = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        start_new_session=True
    )

    def cleanup():
        try:
            if process.poll() is None:
                os.killpg(os.getpgid(process.pid), signal.SIGTERM)
                print(f"★ SIGTERM 送信 → PGID {os.getpgid(process.pid)}")
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    print("★ SIGTERMで終了せず → SIGKILL")
                    os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            else:
                print("★ cleanup: ffmpeg はすでに終了していました")
        except Exception as e:
            print("★ cleanup error:", e)
        finally:
            try:
                if process.stdout:
                    process.stdout.close()
            except Exception as e:
                print("★ stdout close error:", e)


    def stream():
        try:
            while True:
                rlist, _, _ = select.select([process.stdout], [], [], 0.01)
                if process.stdout in rlist:
                    data = process.stdout.read(4096)
                    if not data:
                        break
                    yield data
        except GeneratorExit:
            print("★ クライアント切断") 
            cleanup()

    return StreamingResponse(stream(), media_type="application/octet-stream", background=BackgroundTask(cleanup))

EOF

cat << 'EOF' | sudo tee /etc/systemd/system/fastapi.service
[Unit]
Description=FastAPI Service
After=network.target

[Service]
User=pi
Group=pi
WorkingDirectory=/home/pi/fastapi
ExecStart=/home/pi/fastapi/bin/uvicorn api:app --host 0.0.0.0 --port 8000
Restart=always

[Install]
WantedBy=multi-user.target
EOF

cat << 'EOF' | sudo tee /etc/systemd/system/fastapi-audio.service
[Unit]
Description=FastAPI Audio Service
After=network.target sound.target

[Service]
User=pi
WorkingDirectory=/home/pi/fastapi
ExecStart=/home/pi/fastapi/bin/uvicorn api:app --host 0.0.0.0 --port 50000
Restart=always
KillMode=control-group

[Install]
WantedBy=multi-user.target
EOF

cat << 'EOF' | sudo tee /etc/systemd/system/direwolf.service
[Unit]
Description=Direwolf KISS TNC
After=sound.target network.target

[Service]
User=pi
WorkingDirectory=/home/pi
ExecStart=/usr/local/bin/direwolf -c /home/pi/direwolf.conf -t 0
Restart=always

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable direwolf fastapi fastapi-audio
sudo systemctl restart direwolf fastapi fastapi-audio

cat << 'EOF' | sudo tee /etc/sudoers.d/fastapi
pi ALL=NOPASSWD: /usr/bin/systemctl stop fastapi-audio.service
pi ALL=NOPASSWD: /usr/bin/systemctl start fastapi-audio.service
pi ALL=NOPASSWD: /usr/bin/systemctl restart direwolf.service
EOF

sudo chmod 440 /etc/sudoers.d/fastapi






