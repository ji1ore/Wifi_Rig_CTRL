#!/bin/bash
set -e

sudo apt update -y
sudo apt upgrade -y
sudo apt install -y build-essential libtool libusb-1.0-0-dev libncurses5-dev
sudo apt install -y git build-essential autoconf automake libtool pkg-config
sudo apt install -y ffmpeg alsa-utils python3-fastapi python3-pip
sudo apt install sox libsox-fmt-all -y

git clone https://github.com/Hamlib/Hamlib.git
cd Hamlib
./bootstrap
./configure
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


cat << 'EOF' > /home/pi/fastapi/api.py
from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from starlette.background import BackgroundTask
from fastapi import BackgroundTasks
from fastapi import Request
from fastapi import Form
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

def rigctl_alive():
    try:
        s = socket.socket()
        s.settimeout(0.2)
        s.connect(("localhost", 4532))
        s.close()
        return True
    except:
        return False


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
            radio_cache["freq"] = int(freq_raw.split()[0])
        except:
            pass

        try:
            mode_raw = rigctl_cmd("m")
            parts = mode_raw.split()
            if len(parts) >= 1:
                radio_cache["mode"] = parts[0]
            if len(parts) >= 2:
                radio_cache["width"] = int(parts[1])
        except:
            pass

        time.sleep(1.6)

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
            radio_cache["signal"] = max(sig, 0.0)
        except:
            radio_cache["signal"] = 0.0

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

        time.sleep(0.8)




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


@app.post("/radio/poll")
def set_poll(state: int = Form(...)):
    global poll_enabled
    poll_enabled = bool(state)
    return {"poll_enabled": poll_enabled}


@app.on_event("startup")
def startup_event():
    pass


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
    rigctl_cmd(f"L {name.upper()} {value}")
    return {"status": "ok", "level": name, "value": value}



@app.get("/radio/freq")
def get_freq():
    data = rigctl_cmd("f")
    return {"freq": data}


@app.post("/radio/setfreq")
def set_freq(f: int = Form(...)):
    radio_cache["freq"] = f
    rigctl_cmd(f"F {f}")
    return {"status": "ok", "freq": f}



@app.get("/radio/mode")
def get_mode():
    data = rigctl_cmd("m")
    return {"mode": data}


@app.post("/radio/setmode")
def set_mode(
    mode: str = Form(...),
    width: int = Form(...)
):
    radio_cache["mode"] = mode
    radio_cache["width"] = width
    rigctl_cmd(f"M {mode} {width}")
    return {"status": "ok", "mode": mode, "width": width}

@app.post("/radio/ptt")
def ptt(state: int = Form(...)):
    # state: 1 = ON, 0 = OFF

    # ★ OFF は必ず通す（取りこぼし防止）
    if state == 0:
        rigctl_cmd("T 0")
        return {"status": "ok", "ptt": 0}

    # ★ ON も同期で実行
    rigctl_cmd("T 1")
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
    rigctl_cmd(f"L RFPOWER {value}")
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



sudo systemctl daemon-reload
sudo systemctl enable fastapi
sudo systemctl start fastapi

sudo systemctl status fastapi




sudo systemctl daemon-reload
sudo systemctl enable fastapi-audio
sudo systemctl start fastapi-audio

sudo systemctl status fastapi-audio



