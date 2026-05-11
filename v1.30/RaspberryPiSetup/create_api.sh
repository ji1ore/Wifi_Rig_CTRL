#!/bin/bash
# api.py を完全に再作成する
set -e

if [ -f /home/pi/fastapi/api.py ]; then
    cp /home/pi/fastapi/api.py /home/pi/fastapi/api.py.bak2
    echo "バックアップ: api.py.bak2"
fi

# direwolf.service が SIGKILL 後に再起動しないよう drop-in を追加
sudo mkdir -p /etc/systemd/system/direwolf.service.d/
cat << 'DROPINEOF' | sudo tee /etc/systemd/system/direwolf.service.d/no-kill-restart.conf
[Service]
RestartPreventExitStatus=SIGKILL
DROPINEOF
sudo systemctl daemon-reload
echo "drop-in 設定完了 (direwolf RestartPreventExitStatus=SIGKILL)"

cat << 'APIEOF' > /home/pi/fastapi/api.py
from fastapi import FastAPI, BackgroundTasks, Depends, HTTPException, Request, Security, Form
from fastapi.responses import StreamingResponse
from fastapi.security import APIKeyHeader
from starlette.background import BackgroundTask
from pydantic import BaseModel
import array
import glob
import os
import queue as _queue
import select
import signal
import socket
import subprocess
import threading
import time
import asyncio

# API Key 認証（環境変数 API_KEY が設定されている場合のみ有効）
API_KEY = os.environ.get("API_KEY", "")
_api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)

async def verify_key(key: str = Security(_api_key_header)):
    if API_KEY and key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")

app = FastAPI(dependencies=[Depends(verify_key)])

rig_lock = threading.Lock()
radio_cache = {
    "freq": 0,
    "mode": "",
    "width": 0,
    "signal": 0.0,
    "tx": False,
    "power": 0.0,
    "sql": 0.0
}

current_model = None
current_cat = None
current_baud = None
poll_started = False
poll_enabled = True
tx_in_progress = False
last_user_freq_change = 0
last_user_mode_change = 0
last_heartbeat = time.time()
last_ptt_state = 0

rigctld_process = None

aprs_running = False
aprs_thread = None
aprs_last_heartbeat = 0
aprs_freq = None
aprs_interval = None
normal_freq = None
aprs_use_gps = True
aprs_manual_lat = 0.0
aprs_manual_lon = 0.0
aprs_cfg = None

tx_started = False
tx_done = False
tx_watch_thread = None
tx_watch_running = False

latest_gps = {"lat": 0.0, "lon": 0.0}

KISS_HOST = "127.0.0.1"
KISS_PORT = 8001


class GPSData(BaseModel):
    lat: float
    lon: float


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


def rigctl_cmd(cmd: str) -> str:
    with rig_lock:
        s = None
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(2.0)
            s.connect(("localhost", 4532))
            s.sendall((cmd + "\n").encode())
            data = s.recv(4096)
            raw = data.decode(errors="replace").strip()
            print(f"[rigctl] '{cmd}' -> '{raw[:60]}'")
            return raw
        except socket.timeout:
            print(f"[rigctl] timeout: '{cmd}'")
            return ""
        except Exception as e:
            print(f"[rigctl] error '{cmd}': {e}")
            return ""
        finally:
            if s:
                try:
                    s.close()
                except Exception:
                    pass


def rigctl_cmd_priority(cmd: str) -> str:
    s = None
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(2.0)
        s.connect(("localhost", 4532))
        s.sendall((cmd + "\n").encode())
        data = s.recv(4096)
        raw = data.decode(errors="replace").strip()
        print(f"[rigctl_prio] '{cmd}' -> '{raw[:60]}'")
        return raw
    except socket.timeout:
        print(f"[rigctl_prio] timeout: '{cmd}'")
        return ""
    except Exception as e:
        print(f"[rigctl_prio] error '{cmd}': {e}")
        return ""
    finally:
        if s:
            try:
                s.close()
            except Exception:
                pass


def rigctl_alive() -> bool:
    try:
        s = socket.socket()
        s.settimeout(0.5)
        s.connect(("localhost", 4532))
        s.close()
        return True
    except Exception:
        return False


def start_rigctld(model, cat, baud, ptt="", ptt_type="RTS"):
    global rigctld_process
    subprocess.run(["pkill", "-f", "rigctld"], capture_output=True)
    time.sleep(0.5)
    cmd = ["rigctld", "-m", str(model), "-r", f"/dev/{cat}", "-s", str(baud), "-t", "4532"]
    if ptt and ptt.upper() != "NONE":
        cmd += ["-p", f"/dev/{ptt}", "-P", ptt_type.upper()]
    print(f"starting rigctld: {' '.join(cmd)}")
    rigctld_process = subprocess.Popen(cmd, stderr=subprocess.PIPE)
    time.sleep(1.5)
    if rigctld_process.poll() is not None:
        err = rigctld_process.stderr.read().decode(errors="replace")
        print(f"rigctld exited early: {err}")
    else:
        print(f"rigctld running pid={rigctld_process.pid}")


def poll_rig():
    global poll_enabled, last_user_freq_change, last_user_mode_change
    print("[poll_rig] waiting for rigctld...")
    for _ in range(30):
        if rigctl_alive():
            break
        time.sleep(0.5)
    print("[poll_rig] rigctld ready, starting poll loop")
    while True:
        try:
            tx_raw = rigctl_cmd("t")
            tx = int(tx_raw.split()[0]) if tx_raw and tx_raw.split()[0].isdigit() else 0
            radio_cache["tx"] = bool(tx)
        except Exception:
            tx = 0
            radio_cache["tx"] = False

        if tx:
            time.sleep(0.1)
            continue

        if not poll_enabled:
            time.sleep(0.2)
            continue

        try:
            freq_raw = rigctl_cmd("f")
            val = freq_raw.split()[0] if freq_raw else ""
            if val.lstrip("-").isdigit() and time.time() - last_user_freq_change > 0.5:
                radio_cache["freq"] = int(val)
        except Exception:
            pass

        try:
            mode_raw = rigctl_cmd("m")
            parts = mode_raw.split() if mode_raw else []
            if parts and time.time() - last_user_mode_change > 0.5:
                radio_cache["mode"] = parts[0]
                if len(parts) >= 2 and parts[1].lstrip("-").isdigit():
                    radio_cache["width"] = int(parts[1])
        except Exception:
            pass

        time.sleep(0.5)


def poll_signal():
    global poll_enabled
    for _ in range(30):
        if rigctl_alive():
            break
        time.sleep(0.5)
    last_power = 0
    last_sql = 0
    while True:
        if radio_cache.get("tx", False):
            time.sleep(0.1)
            continue

        try:
            raw = rigctl_cmd("l STRENGTH")
            val = raw.split()[0] if raw else ""
            if val:
                sig = float(val)
                radio_cache["signal"] = max(sig, 0.0)
        except Exception:
            pass

        if time.time() - last_power > 3:
            try:
                raw = rigctl_cmd("l RFPOWER")
                val = raw.split()[0] if raw else ""
                if val:
                    radio_cache["power"] = float(val)
            except Exception:
                pass
            last_power = time.time()

        if time.time() - last_sql > 3:
            try:
                raw = rigctl_cmd("l SQL")
                val = raw.split()[0] if raw else ""
                if val:
                    radio_cache["sql"] = float(val)
            except Exception:
                pass
            last_sql = time.time()

        time.sleep(0.5)


def watchdog_heartbeat():
    global last_heartbeat, last_ptt_state
    while True:
        if radio_cache.get("tx", False):
            if time.time() - last_heartbeat > 3.0:
                print("[watchdog] heartbeat lost -> TX OFF")
                try:
                    rigctl_cmd_priority("T 0")
                except Exception:
                    pass
                radio_cache["tx"] = False
                last_ptt_state = 0  # 次のハートビートでTXを再開できるようリセット
        time.sleep(0.1)


@app.on_event("startup")
def startup_event():
    threading.Thread(target=watchdog_heartbeat, daemon=True).start()


@app.get("/devices")
def list_devices():
    usb = glob.glob("/dev/ttyUSB*")
    acm = glob.glob("/dev/ttyACM*")
    serial = sorted([os.path.basename(d) for d in usb + acm])
    audio = []
    try:
        result = subprocess.run(["arecord", "-L"], capture_output=True, text=True)
        lines = result.stdout.splitlines()
        current_id = None
        for line in lines:
            if not line.strip():
                continue
            if not line.startswith(" "):
                current_id = line.strip()
            else:
                if current_id:
                    audio.append({"id": current_id, "label": f"{line.strip()} ({current_id})"})
                    current_id = None
    except Exception as e:
        print(f"arecord error: {e}")
    return {"serial": serial, "audio": audio}


@app.get("/rigs")
def list_rigs():
    result = subprocess.run(["rigctl", "-l"], capture_output=True, text=True)
    rigs = []
    for line in result.stdout.splitlines():
        parts = line.strip().split()
        if len(parts) >= 3 and parts[0].isdigit():
            rigs.append({"id": int(parts[0]), "name": f"{parts[1]} {parts[2]}"})
    return {"rigs": rigs}


@app.get("/radio/open")
def open_radio(model: int, cat: str, baud: int = 38400, audio: str = "", ptt: str = "", ptt_type: str = "RTS"):
    global current_model, current_cat, current_baud, poll_started
    current_model = model
    current_cat = cat
    current_baud = baud
    start_rigctld(model, cat, baud, ptt, ptt_type)
    if not poll_started:
        threading.Thread(target=poll_rig, daemon=True).start()
        threading.Thread(target=poll_signal, daemon=True).start()
        poll_started = True
    return {"status": "ok"}


@app.get("/radio/status")
def radio_status():
    return {**radio_cache, "tx_in_progress": tx_in_progress}


@app.get("/radio/caps")
def radio_caps():
    modes = ["AM", "CW", "CWR", "DIGL", "DIGU", "FM", "LSB", "PKTFM", "PKTLSB", "PKTUSB", "USB"]
    return {"modes": modes, "raw": ""}


@app.get("/radio/modes")
def list_modes():
    return {"modes": ["LSB", "USB", "CW", "CWR", "AM", "FM", "DIGL", "DIGU", "PKTLSB", "PKTUSB", "PKTFM"]}


@app.post("/radio/setfreq")
def set_freq(f: int = Form(...)):
    global last_user_freq_change
    radio_cache["freq"] = f
    last_user_freq_change = time.time()
    threading.Thread(target=lambda: rigctl_cmd(f"F {f}"), daemon=True).start()
    return {"status": "ok", "freq": f}


@app.post("/radio/setmode")
def set_mode(mode: str = Form(...), width: int = Form(...)):
    global last_user_mode_change
    radio_cache["mode"] = mode
    radio_cache["width"] = width
    last_user_mode_change = time.time()
    threading.Thread(target=lambda: rigctl_cmd(f"M {mode} {width}"), daemon=True).start()
    return {"status": "ok", "mode": mode, "width": width}


@app.post("/radio/ptt")
def ptt(state: int = Form(...)):
    global last_ptt_state, last_heartbeat
    if state == 0:
        rigctl_cmd_priority("T 0")
        radio_cache["tx"] = False
        last_ptt_state = 0
        # TX終了: direwolfを再開(バックグラウンド)
        def restart_after_tx():
            subprocess.run(["sudo", "systemctl", "start", "direwolf.service"], capture_output=True)
        threading.Thread(target=restart_after_tx, daemon=True).start()
        return {"status": "ok", "ptt": 0}
    # ハートビート受信 — 即座に更新 (rigctlタイムアウトで遅延しないよう先頭で実施)
    last_heartbeat = time.time()
    if last_ptt_state == 1:
        # 既にTX中: rigctlを再送せず即リターン (watchdog対策)
        return {"status": "ok", "ptt": 1}
    # TX開始 (初回のみ): direwolfをSIGKILLで停止
    subprocess.run(["pkill", "-9", "direwolf"], capture_output=True)
    subprocess.run(["pkill", "-9", "-f", "ffmpeg"], capture_output=True)
    rigctl_cmd_priority("T 1")
    radio_cache["tx"] = True
    last_ptt_state = 1
    return {"status": "ok", "ptt": 1}


@app.post("/radio/poll")
def set_poll(state: int = Form(...)):
    global poll_enabled
    poll_enabled = bool(state)
    return {"poll_enabled": poll_enabled}


@app.post("/radio/setlevel")
def set_level(name: str = Form(...), value: float = Form(...)):
    radio_cache[name.lower()] = value
    threading.Thread(target=lambda: rigctl_cmd(f"L {name.upper()} {value}"), daemon=True).start()
    return {"status": "ok", "level": name, "value": value}


@app.post("/radio/setpower")
def set_power(value: float = Form(...)):
    radio_cache["power"] = value
    threading.Thread(target=lambda: rigctl_cmd(f"L RFPOWER {value}"), daemon=True).start()
    return {"status": "ok", "power": value}


@app.get("/radio/audio")
def audio_stream(request: Request, background_tasks: BackgroundTasks):
    from fastapi.responses import Response
    rate = request.query_params.get("rate", "48000")
    if not rate.isdigit():
        rate = "48000"
    print(f"[audio_rx] rate={rate}")
    subprocess.run(["pkill", "-f", "ffmpeg"], capture_output=True)
    subprocess.run(["pkill", "-9", "aplay"], capture_output=True)
    subprocess.run(["pkill", "-9", "direwolf"], capture_output=True)
    time.sleep(0.3)
    subprocess.run(["pkill", "-9", "direwolf"], capture_output=True)
    time.sleep(0.1)
    cmd = [
        "ffmpeg", "-f", "alsa", "-thread_queue_size", "1024",
        "-ar", rate, "-i", "plughw:CARD=CODEC,DEV=0",
        "-ac", "1",
        "-af", "highpass=f=300,lowpass=f=4000,volume=10.0",
        "-f", "s16le", "-acodec", "pcm_s16le",
        "-nostdin", "-vn", "-sn", "-dn", "-map", "0:a",
        "-flush_packets", "1", "-nostats", "pipe:1"
    ]
    process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               start_new_session=True)
    time.sleep(0.5)
    if process.poll() is not None:
        err = process.stderr.read().decode(errors="replace").strip()
        print(f"[audio_rx] ffmpeg failed: {err}")
        return Response(status_code=500, content=f"ffmpeg error: {err}")

    def cleanup():
        try:
            if process.poll() is None:
                os.killpg(os.getpgid(process.pid), signal.SIGTERM)
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    os.killpg(os.getpgid(process.pid), signal.SIGKILL)
        except Exception as e:
            print(f"[audio_rx] cleanup error: {e}")
        finally:
            try:
                process.stdout.close()
            except Exception:
                pass
            threading.Thread(
                target=lambda: subprocess.run(["sudo", "systemctl", "start", "direwolf.service"], capture_output=True),
                daemon=True
            ).start()

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
            cleanup()

    return StreamingResponse(stream(), media_type="application/octet-stream",
                             background=BackgroundTask(cleanup))


@app.post("/radio/audio_tx")
async def audio_tx(request: Request, rate: int = 8000):
    print(f"[audio_tx] received rate={rate}")
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, lambda: [
        subprocess.run(["pkill", "-9", "direwolf"], capture_output=True),
        subprocess.run(["pkill", "-9", "aplay"], capture_output=True),
    ])
    await asyncio.sleep(0.5)
    process = subprocess.Popen(
        ["aplay", "-D", "plughw:CARD=CODEC,DEV=0", "-f", "S16_LE", "-r", str(rate), "-c", "1"],
        stdin=subprocess.PIPE, stderr=subprocess.PIPE,
        bufsize=0
    )
    print(f"[audio_tx] aplay started pid={process.pid} rate={rate}")
    await asyncio.sleep(0.1)
    if process.poll() is not None:
        err = process.stderr.read().decode(errors="replace")
        print(f"[audio_tx] aplay failed to start: {err}")
        return {"error": f"aplay failed: {err}"}

    stats = {"chunks": 0, "bytes": 0, "write_errors": 0, "dropped": 0}
    TX_GAIN = 2.0
    # キューで asyncio イベントループと書き込みスレッドを完全に分離
    # → ハートビート等の sync endpoint がスレッドプールを取れないことを防ぐ
    chunk_q = _queue.Queue(maxsize=16)

    def write_chunk(c):
        try:
            data = bytes(c)
            if len(data) % 2:
                data = data[:-1]
            if not data:
                return
            samples = array.array('h', data)
            for i in range(len(samples)):
                samples[i] = max(-32768, min(32767, int(samples[i] * TX_GAIN)))
            out = samples.tobytes()
            fd = process.stdin.fileno()
            mv = memoryview(out)
            offset = 0
            while offset < len(out):
                n = os.write(fd, mv[offset:])
                offset += n
            stats["bytes"] += len(c)
        except OSError as e:
            stats["write_errors"] += 1
            print(f"[audio_tx] write error: {e}")

    def writer():
        while True:
            item = chunk_q.get()
            if item is None:
                break
            write_chunk(item)

    wt = threading.Thread(target=writer, daemon=True)
    wt.start()

    try:
        async for chunk in request.stream():
            if process.poll() is not None:
                err = process.stderr.read().decode(errors="replace")
                print(f"[audio_tx] aplay exited early: {err}")
                break
            stats["chunks"] += 1
            try:
                chunk_q.put_nowait(chunk)
            except _queue.Full:
                stats["dropped"] += 1  # ライターが遅い場合はドロップ
    except Exception as e:
        print(f"[audio_tx] stream {type(e).__name__}: {e!r}")
    finally:
        def _cleanup_aplay():
            try:
                chunk_q.put(None, timeout=2)
            except Exception:
                pass
            wt.join(timeout=3)
            try:
                process.stdin.close()
            except Exception:
                pass
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            ae = ""
            try:
                ae = process.stderr.read(512).decode(errors="replace").strip()
            except Exception:
                pass
            return ae
        aplay_err = await loop.run_in_executor(None, _cleanup_aplay)
        print(f"[audio_tx] done chunks={stats['chunks']} bytes={stats['bytes']} "
              f"dropped={stats['dropped']} write_errors={stats['write_errors']} "
              f"rc={process.returncode} aplay_err={aplay_err!r}")
    return {"status": "ok"}


# ---------- APRS ----------

def encode_ax25_addr(callsign: str, ssid: int, last: bool) -> bytes:
    call = callsign.upper().ljust(6)[:6]
    addr = bytearray()
    for c in call:
        addr.append(ord(c) << 1)
    ssid_byte = 0x60 | ((ssid & 0x0F) << 1)
    if last:
        ssid_byte |= 0x01
    addr.append(ssid_byte)
    return bytes(addr)


def build_ax25_ui_frame(src_call, src_ssid, dest_call, dest_ssid, path, info):
    addrs = bytearray()
    addrs += encode_ax25_addr(dest_call, dest_ssid, last=False)
    last_src = (len(path) == 0)
    addrs += encode_ax25_addr(src_call, src_ssid, last=last_src)
    for i, p in enumerate(path):
        call, ssid = (p.split("-", 1)[0], int(p.split("-", 1)[1])) if "-" in p else (p, 0)
        addrs += encode_ax25_addr(call, ssid, last=(i == len(path) - 1))
    frame = bytearray()
    frame += addrs
    frame.append(0x03)
    frame.append(0xF0)
    frame += info.encode("ascii")
    return bytes(frame)


def kiss_wrap(ax25_frame: bytes) -> bytes:
    FEND, FESC, TFEND, TFESC = 0xC0, 0xDB, 0xDC, 0xDD
    out = bytearray([FEND, 0x00])
    for b in ax25_frame:
        if b == FEND:
            out.extend([FESC, TFEND])
        elif b == FESC:
            out.extend([FESC, TFESC])
        else:
            out.append(b)
    out.append(FEND)
    return bytes(out)


def send_kiss(frame: bytes):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    s.connect((KISS_HOST, KISS_PORT))
    s.sendall(frame)
    s.close()


def aprs_lat(lat):
    deg = int(lat)
    return f"{deg:02d}{(lat - deg) * 60:05.2f}N"


def aprs_lon(lon):
    deg = int(lon)
    return f"{deg:03d}{(lon - deg) * 60:05.2f}E"


def wait_tx_complete(timeout=5.0):
    global tx_started, tx_done
    tx_started = False
    tx_done = False
    start = time.time()
    while time.time() - start < timeout:
        if tx_started:
            break
        time.sleep(0.05)
    if not tx_started:
        return False
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
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    for line in proc.stdout:
        if not tx_watch_running:
            break
        if any(k in line for k in ("PTT ON", "Transmit", "Sending packet", "audio: transmit")):
            tx_started = True
        if any(k in line for k in ("PTT OFF", "TX complete", "audio: PTT OFF")):
            tx_done = True


def aprs_loop():
    global aprs_running, tx_in_progress, aprs_last_heartbeat, normal_freq
    try:
        while aprs_running:
            if tx_in_progress:
                time.sleep(0.1)
                continue
            loop_start = time.time()
            if time.time() - aprs_last_heartbeat > 15:
                aprs_running = False
                break
            cur = rigctl_cmd_priority("f")
            try:
                normal_freq = int(cur.split()[0])
            except Exception:
                time.sleep(1)
                continue
            try:
                mode_raw = rigctl_cmd("m")
                mode = mode_raw.split()[0].upper() if mode_raw else ""
            except Exception:
                mode = ""
            if not mode.startswith("FM"):
                time.sleep(1)
                continue
            lat = latest_gps["lat"] if aprs_use_gps else aprs_manual_lat
            lon = latest_gps["lon"] if aprs_use_gps else aprs_manual_lon
            tx_in_progress = True
            aprs_last_heartbeat = time.time()
            rigctl_cmd_priority(f"F {int(aprs_freq * 1_000_000)}")
            time.sleep(0.25)
            info = f"!{aprs_lat(lat)}/{aprs_lon(lon)}>"
            ax25 = build_ax25_ui_frame(
                src_call=aprs_cfg.callsign, src_ssid=aprs_cfg.ssid,
                dest_call="APDW18", dest_ssid=0,
                path=aprs_cfg.path.split(","), info=info
            )
            send_kiss(kiss_wrap(ax25))
            time.sleep(0.15)
            if wait_tx_complete(timeout=5.0):
                print("[APRS] TX complete")
            else:
                print("[APRS] TX timeout")
            time.sleep(0.15)
            if normal_freq:
                rigctl_cmd_priority(f"F {normal_freq}")
                time.sleep(0.15)
            tx_in_progress = False
            elapsed = time.time() - loop_start
            time.sleep(max(0, aprs_interval - elapsed))
    except Exception as e:
        print(f"[APRS] thread crashed: {e}")
    finally:
        tx_in_progress = False
        aprs_running = False


@app.post("/aprs_config")
def update_aprs_config(cfg: AprsConfig):
    global aprs_use_gps, aprs_manual_lat, aprs_manual_lon, aprs_cfg
    cat_device = cfg.cat_device
    if not cat_device.startswith("/dev/"):
        cat_device = f"/dev/{cat_device}"
    modem = 1200 if cfg.baud == 1200 else 9600
    conf = (f"ADEVICE null {cfg.sound_device}\nCHANNEL 0\n"
            f"MYCALL {cfg.callsign}-{cfg.ssid}\nMODEM {modem}\n")
    if modem == 9600:
        conf += "ARATE 48000\n"
    conf += (f"KISSPORT 8001\nAGWPORT 8050\nPTT RIG {cfg.rig_id} {cat_device}\n")
    with open("/home/pi/direwolf.conf", "w") as f:
        f.write(conf)
    subprocess.run(["sudo", "systemctl", "restart", "direwolf"])
    time.sleep(0.5)
    aprs_use_gps = cfg.use_gps
    aprs_manual_lat = cfg.manual_lat
    aprs_manual_lon = cfg.manual_lon
    aprs_cfg = cfg
    return {"status": "ok"}


@app.post("/aprs_start")
def aprs_start(cfg: AprsStart):
    global aprs_running, aprs_thread, aprs_freq, aprs_interval, aprs_last_heartbeat
    global tx_watch_thread, tx_watch_running
    if aprs_cfg is None:
        return {"error": "APRS config not set"}
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
    global aprs_running, poll_enabled, tx_watch_running
    poll_enabled = True
    aprs_running = False
    tx_watch_running = False
    subprocess.run(["sudo", "systemctl", "start", "fastapi-audio.service"])
    subprocess.run(["sudo", "systemctl", "restart", "direwolf.service"])
    return {"status": "stopped"}


@app.post("/aprs_heartbeat")
def aprs_heartbeat():
    global aprs_last_heartbeat
    aprs_last_heartbeat = time.time()
    return {"status": "ok"}


@app.post("/gps")
def update_gps(data: GPSData):
    global latest_gps
    latest_gps = {"lat": data.lat, "lon": data.lon}
    return {"status": "ok"}


@app.get("/gps")
def get_gps():
    return latest_gps
APIEOF

sudo systemctl restart fastapi
echo ""
echo "完了。30秒後にログを確認:"
echo "  sudo journalctl -u fastapi -f"
