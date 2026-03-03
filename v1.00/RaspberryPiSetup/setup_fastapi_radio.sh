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


cat << 'EOF' > /home/pi/fastapi/api.py
from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from starlette.background import BackgroundTask
from fastapi import BackgroundTasks
from fastapi import Request
import glob
import os
import subprocess
import socket
import signal
import select

app = FastAPI()

supported_modes = []

# ★ グローバル変数（可変リグ対応）
rig = None 
current_model = None 
current_cat = None 
current_baud = None

def get_supported_modes_from_rigctl_cli(model, cat, baud):
    result = subprocess.run(
        ["rigctl", "-m", str(model), "-r", f"/dev/{cat}", "-s", str(baud), "dump_caps"],
        capture_output=True, text=True
    )
    return result.stdout

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


def rigctl_cmd(cmd: str):
    s = socket.socket()
    s.connect(("localhost", 4532))
    s.sendall((cmd + "\n").encode())

    chunks = []
    s.settimeout(0.05)

    while True:
        try:
            chunk = s.recv(4096)
            if not chunk:
                break
            chunks.append(chunk)
        except socket.timeout:
            break

    s.close()
    return b"".join(chunks).decode().strip()


@app.get("/radio/open")
def open_radio(model: int, cat: str, baud: int = 38400, audio: str = ""):
    global current_model, current_cat, current_baud, supported_modes

    # 既存の rigctld を停止
    subprocess.run(["pkill", "-f", "rigctld"])

    # rigctld 起動
    cmd = [
        "rigctld",
        "-m", str(model),
        "-r", f"/dev/{cat}",
        "-s", str(baud),
        "-t", "4532"
    ]
    subprocess.Popen(cmd)

    # rigctld の起動待ち
    import time
    for _ in range(20):
        try:
            s = socket.socket()
            s.settimeout(0.1)
            s.connect(("localhost", 4532))
            s.close()
            break
        except:
            time.sleep(0.1)

    # ★ hamlib を使わずに mode list を取得
    #supported_modes = get_supported_modes_from_rigctld()

    current_model = model
    current_cat = cat
    current_baud = baud

    return {
        "status": "ok",
        "model": model,
        "cat": cat,
        "baud": baud
    }

@app.get("/radio/setlevel")
def set_level(name: str, value: float):
    try:
        rigctl_cmd(f"L {name.upper()} {value}")
        return {"status": "ok", "level": name, "value": value}
    except Exception as e:
        return {"status": "error", "message": str(e)}

@app.get("/radio/freq")
def get_freq():
    data = rigctl_cmd("f")
    return {"freq": data}

@app.get("/radio/setfreq")
def set_freq(f: int):
    rigctl_cmd(f"F {f}")
    return {"status": "ok", "freq": f}

@app.get("/radio/mode")
def get_mode():
    data = rigctl_cmd("m")
    return {"mode": data}

@app.get("/radio/setmode")
def set_mode(mode: str, width: int = 2400):
    rigctl_cmd(f"M {mode} {width}")
    return {"status": "ok", "mode": mode, "width": width}

@app.get("/radio/ptt")
def ptt(state: int):
    rigctl_cmd(f"T {state}")
    return {"status": "ok", "ptt": state}

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
    cur = rigctl_cmd("f")
    try:
        cur = int(cur)
    except:
        return {"error": "freq read error", "raw": cur}

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
    freq = rigctl_cmd("f")

    mode_raw = rigctl_cmd("m")
    parts = mode_raw.split()

    if len(parts) >= 1:
        mode = parts[0]
        width = int(parts[1]) if len(parts) > 1 else None
    else:
        mode = None
        width = None

    mode = parts[0]
    width = int(parts[1]) if len(parts) > 1 else None

    signal = rigctl_cmd("l STRENGTH")

    tx_raw = rigctl_cmd("t")
    try:
        tx = int(tx_raw)
    except:
        tx = 0

    try:
        power = rigctl_cmd("l RFPOWER")
    except:
        power = None
    try: 
        sql = rigctl_cmd("l SQL") 
    except: 
        sql = None

    return {
        "freq": freq,
        "mode": mode,
        "width": width,
        "signal": signal,
        "tx": bool(tx),  
        "power": power,
        "sql": sql
    }


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


@app.get("/radio/setpower")
def set_power(value: float):
    try:
        rigctl_cmd(f"L RFPOWER {value}")
        return {"status": "ok", "power": value}
    except Exception as e:
        return {"status": "error", "message": str(e)}


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
ExecStart=/usr/bin/uvicorn api:app --host 0.0.0.0 --port 50000
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



