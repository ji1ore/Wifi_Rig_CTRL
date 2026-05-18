#!/bin/bash
# api.py を完全に再作成する
set -e

if [ -f /home/pi/fastapi/api.py ]; then
    cp /home/pi/fastapi/api.py /home/pi/fastapi/api.py.bak2
    echo "バックアップ: api.py.bak2"
fi

# fastapi.service に EnvironmentFile を追加（API_KEY 設定用）
sudo mkdir -p /etc/systemd/system/fastapi.service.d/
cat << 'ENVEOF' | sudo tee /etc/systemd/system/fastapi.service.d/env.conf
[Service]
EnvironmentFile=-/home/pi/fastapi/.env
ENVEOF
sudo mkdir -p /etc/systemd/system/fastapi-audio.service.d/
cat << 'ENVEOF2' | sudo tee /etc/systemd/system/fastapi-audio.service.d/env.conf
[Service]
EnvironmentFile=-/home/pi/fastapi/.env
ENVEOF2

# .env ファイルが未作成なら空テンプレートを生成
if [ ! -f /home/pi/fastapi/.env ]; then
    echo "# API Key 認証。キーを設定する場合は下の行を編集して有効にする" > /home/pi/fastapi/.env
    echo "# API_KEY=your_secret_key_here" >> /home/pi/fastapi/.env
    echo ".env テンプレート生成: /home/pi/fastapi/.env"
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
from pathlib import Path
import queue as _queue
import select
import signal
import socket
import struct
import subprocess
import threading
import time
import asyncio
try:
    import serial as _serial
    _HAS_SERIAL = True
except ImportError:
    _HAS_SERIAL = False

# API Key 認証（環境変数 API_KEY が設定されている場合のみ有効）
API_KEY = os.environ.get("API_KEY", "")
API_VERSION = "1.50"
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

# ─── CW bridge subprocess 管理 ───
_cw_bridge_proc = None
_cw_bridge_lock = threading.Lock()
_cw_bridge_port = ""

# ─── ファイル原子書き込みヘルパー (電源即切り対策) ───
def _atomic_write(path: str, content: str):
    """temp ファイルへ書き込み → fsync → rename で原子的にファイルを更新する。
    電源突然遮断時のファイル破損を防ぐ。"""
    import tempfile
    dir_path = os.path.dirname(os.path.abspath(path))
    fd, tmp_path = tempfile.mkstemp(dir=dir_path, suffix=".tmp")
    try:
        with os.fdopen(fd, 'w') as f:
            f.write(content)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_path, path)
    except Exception:
        try:
            os.unlink(tmp_path)
        except Exception:
            pass
        raise

# 常時起動ffmpegの状態管理
_ffmpeg_proc = None
_ffmpeg_rate = None
_ffmpeg_lock = threading.Lock()
_ffmpeg_buf = _queue.Queue(maxsize=64)  # 64×4096B ≈ 16秒分バッファ

def _ffmpeg_reader(proc):
    """ffmpeg stdout → _ffmpeg_buf へ供給。buf満杯なら古いデータを捨てる。"""
    while proc.poll() is None:
        try:
            r, _, _ = select.select([proc.stdout], [], [], 0.1)
            if r:
                data = proc.stdout.read(4096)
                if not data:
                    break
                while _ffmpeg_buf.full():
                    try:
                        _ffmpeg_buf.get_nowait()
                    except _queue.Empty:
                        break
                try:
                    _ffmpeg_buf.put_nowait(data)
                except _queue.Full:
                    pass
        except Exception:
            break

def _start_persistent_ffmpeg(rate: str):
    global _ffmpeg_proc, _ffmpeg_rate
    old = _ffmpeg_proc
    if old and old.poll() is None:
        try:
            os.killpg(os.getpgid(old.pid), signal.SIGTERM)
            try:
                old.wait(timeout=0.5)
            except subprocess.TimeoutExpired:
                os.killpg(os.getpgid(old.pid), signal.SIGKILL)
                old.wait(timeout=0.5)
        except Exception:
            pass
    subprocess.run(["pkill", "-9", "direwolf"], capture_output=True)
    time.sleep(0.05)
    cmd = [
        "ffmpeg", "-f", "alsa", "-thread_queue_size", "1024",
        "-ar", rate, "-i", "plughw:CARD=CODEC,DEV=0",
        "-ac", "1",
        "-af", "highpass=f=300,lowpass=f=4000,volume=10.0",
        "-f", "s16le", "-acodec", "pcm_s16le",
        "-nostdin", "-vn", "-sn", "-dn", "-map", "0:a",
        "-flush_packets", "1", "-nostats", "pipe:1"
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            start_new_session=True)
    _ffmpeg_proc = proc
    _ffmpeg_rate = rate
    # バッファをリセット
    while True:
        try:
            _ffmpeg_buf.get_nowait()
        except _queue.Empty:
            break
    threading.Thread(target=_ffmpeg_reader, args=(proc,), daemon=True).start()
    return proc

def _ensure_ffmpeg(rate: str):
    global _ffmpeg_proc
    with _ffmpeg_lock:
        if _ffmpeg_proc and _ffmpeg_proc.poll() is None and _ffmpeg_rate == rate:
            return _ffmpeg_proc  # 既存プロセスを再利用
        return _start_persistent_ffmpeg(rate)

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
    # 1. 追跡しているプロセス参照を graceful に停止
    if rigctld_process and rigctld_process.poll() is None:
        try:
            rigctld_process.terminate()
            rigctld_process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            rigctld_process.kill()
            rigctld_process.wait(timeout=2)
        except Exception:
            pass
    rigctld_process = None
    # 2. 孤立プロセスを SIGTERM で掃討してから SIGKILL で確実に終了
    subprocess.run(["pkill", "-TERM", "-f", "rigctld"], capture_output=True)
    # 3. ポート 4532 が解放されるまで最大 5 秒待機
    deadline = time.time() + 5.0
    while time.time() < deadline:
        try:
            s = socket.socket()
            s.settimeout(0.2)
            s.connect(("localhost", 4532))
            s.close()
            time.sleep(0.2)  # まだ bind されている — 待機継続
        except Exception:
            break  # 接続拒否 = ポート解放済み
    # 4. 残存プロセスを SIGKILL で強制終了
    subprocess.run(["pkill", "-KILL", "-f", "rigctld"], capture_output=True)
    time.sleep(0.2)
    # 5. 新しい rigctld を起動
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
            time.sleep(0.5)  # TX中はrigctld負荷を軽減（aplayのCPU優先度を上げる）
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
            time.sleep(0.5)  # TX中はrigctld負荷を軽減
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
    return {**radio_cache, "tx_in_progress": tx_in_progress, "api_version": API_VERSION}


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
        # direwolf の自動再起動はしない
        # audio RX が再接続すると /radio/audio が direwolf を停止して ffmpeg を起動するため
        # ここで direwolf を再起動すると ffmpeg と ALSA デバイスが競合してしまう
        # APRS 用の direwolf は /aprs_config・/aprs_stop が管理する
        return {"status": "ok", "ptt": 0}
    # ハートビート受信 — 即座に更新 (rigctlタイムアウトで遅延しないよう先頭で実施)
    last_heartbeat = time.time()
    if last_ptt_state == 1:
        # 既にTX中: rigctlを再送せず即リターン (watchdog対策)
        return {"status": "ok", "ptt": 1}
    # TX開始 (初回のみ): direwolfをSIGKILLで停止
    # ffmpegはaudio_tx開始時に停止する（audio RXストリームを早期に切断しない）
    subprocess.run(["pkill", "-9", "direwolf"], capture_output=True)
    result = rigctl_cmd_priority("T 1")
    if not result:
        # rigctld タイムアウト → クライアントに失敗を通知してリトライを促す
        raise HTTPException(status_code=500, detail="rigctld timeout")
    radio_cache["tx"] = True
    last_ptt_state = 1
    return {"status": "ok", "ptt": 1}


@app.post("/radio/poll")
def set_poll(state: int = Form(...)):
    global poll_enabled
    poll_enabled = bool(state)
    return {"poll_enabled": poll_enabled}


@app.post("/radio/ptt_heartbeat")
def ptt_heartbeat():
    # WiFi PTTモード専用: last_heartbeatだけ更新、rigctlは呼ばない
    global last_heartbeat
    last_heartbeat = time.time()
    return {"status": "ok"}


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
    rate = request.query_params.get("rate", "8000")
    if not rate.isdigit():
        rate = "8000"
    t0 = time.time()
    print(f"[audio_rx] connect rate={rate}")

    proc = _ensure_ffmpeg(rate)

    # ffmpeg即死 = aplayとのALSA競合が原因の場合、aplay解放まで待ってから再起動
    # (aplayはTX終了後300msのバッファドレインで解放される)
    if proc.poll() is not None:
        print(f"[audio_rx] ffmpeg dead, waiting for aplay release...")
        deadline = time.time() + 0.6
        while time.time() < deadline:
            if subprocess.run(["pgrep", "-x", "aplay"], capture_output=True).returncode != 0:
                break
            time.sleep(0.05)
        proc = _start_persistent_ffmpeg(rate)
        time.sleep(0.1)  # ALSA open待ち
        if proc.poll() is not None:
            err = ""
            try:
                err = proc.stderr.read(512).decode(errors="replace").strip()
            except Exception:
                pass
            print(f"[audio_rx] ffmpeg dead after aplay-wait: {err}")
            return Response(status_code=503, content=f"ffmpeg error: {err}")

    # TX中に溜まった無音/サイドトーンを捨ててリアルタイムから再開
    drained = 0
    while True:
        try:
            _ffmpeg_buf.get_nowait()
            drained += 1
        except _queue.Empty:
            break
    print(f"[audio_rx] ready {time.time()-t0:.3f}s drained={drained}")

    proc_ref = proc

    def stream():
        try:
            while proc_ref.poll() is None:
                try:
                    data = _ffmpeg_buf.get(timeout=0.2)
                    yield data
                except _queue.Empty:
                    continue
        except GeneratorExit:
            pass

    def cleanup():
        print("[audio_rx] client disconnected")

    return StreamingResponse(stream(), media_type="application/octet-stream",
                             background=BackgroundTask(cleanup))


@app.post("/radio/audio_tx")
async def audio_tx(request: Request, rate: int = 8000):
    print(f"[audio_tx] received rate={rate}")
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, lambda: [
        subprocess.run(["pkill", "-9", "direwolf"], capture_output=True),
        subprocess.run(["pkill", "-9", "ffmpeg"],   capture_output=True),  # ALSAデバイスを解放
        subprocess.run(["pkill", "-9", "aplay"],    capture_output=True),
    ])
    await asyncio.sleep(0.2)  # ffmpeg解放待ち (0.1→0.2)
    process = subprocess.Popen(
        ["aplay", "-D", "plughw:CARD=CODEC,DEV=0", "-f", "S16_LE", "-r", str(rate), "-c", "1", "-B", "100000"],
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


# ---------- CW USB中継 (cw_bridge.py subprocess 経由) ----------

@app.get("/cw/open")
def cw_open(port: str = "ttyACM0", delay_ms: int = 0):
    """cw_bridge.py を起動。タイムスタンプ変換はcw_bridge側で実施するためdelay_msは無視。"""
    global _cw_bridge_proc, _cw_bridge_port
    dev = f"/dev/{port}" if not port.startswith("/dev/") else port
    with _cw_bridge_lock:
        if _cw_bridge_proc and _cw_bridge_proc.poll() is None:
            _cw_bridge_proc.terminate()
            try:
                _cw_bridge_proc.wait(timeout=2)
            except Exception:
                _cw_bridge_proc.kill()
        try:
            venv_py = "/home/pi/fastapi/bin/python3"
            _cw_bridge_proc = subprocess.Popen([venv_py, "/home/pi/cw_bridge.py", dev])
            _cw_bridge_port = dev
            print(f"[cw] bridge started pid={_cw_bridge_proc.pid} dev={dev}")
            return {"status": "ok", "port": dev}
        except Exception as e:
            print(f"[cw] bridge start failed: {e}")
            raise HTTPException(status_code=500, detail=str(e))


@app.post("/cw/close")
def cw_close():
    """cw_bridge.py を停止する"""
    global _cw_bridge_proc, _cw_bridge_port
    with _cw_bridge_lock:
        if _cw_bridge_proc and _cw_bridge_proc.poll() is None:
            _cw_bridge_proc.terminate()
            try:
                _cw_bridge_proc.wait(timeout=2)
            except Exception:
                _cw_bridge_proc.kill()
        _cw_bridge_proc = None
        _cw_bridge_port = ""
    return {"status": "ok"}


@app.get("/cw/status")
def cw_status():
    """cw_bridge.py の稼働状況と M5ATOM Server SYNC結果を返す"""
    with _cw_bridge_lock:
        running = bool(_cw_bridge_proc and _cw_bridge_proc.poll() is None)
    synced = False
    offset_ms = 0
    max_late_ms = 0
    if running:
        try:
            import json as _json
            with open("/tmp/cw_bridge_status.json") as _f:
                _st = _json.load(_f)
            if time.time() - _st.get("t", 0) < 15:
                synced = bool(_st.get("synced", False))
                offset_ms = float(_st.get("offset_ms", 0))
                max_late_ms = int(_st.get("max_late_ms", 0))
        except Exception:
            pass
    return {"connected": running, "synced": synced, "offset_ms": offset_ms, "max_late_ms": max_late_ms}


@app.post("/cw/key")
def cw_key(is_on: bool = Form(...)):
    """後方互換スタブ: UDP切替後は使用されない"""
    return {"status": "no_device"}


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
    global aprs_running, tx_in_progress, aprs_last_heartbeat, normal_freq, last_user_freq_change
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
                read_freq = int(cur.split()[0])
                aprs_freq_hz = int(aprs_freq * 1_000_000)
                # APRSの送信周波数と一致する場合は更新しない（前回復元失敗の可能性）
                if abs(read_freq - aprs_freq_hz) > 100:
                    normal_freq = read_freq
                elif normal_freq is None:
                    time.sleep(1)
                    continue
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
            try:
                send_kiss(kiss_wrap(ax25))
            except Exception as kiss_err:
                print(f"[APRS] KISS send failed (direwolf not running?): {kiss_err}")
                if normal_freq:
                    rigctl_cmd_priority(f"F {normal_freq}")
                    radio_cache["freq"] = normal_freq
                    last_user_freq_change = time.time()
                tx_in_progress = False
                time.sleep(5)
                continue
            time.sleep(0.15)
            if wait_tx_complete(timeout=5.0):
                print("[APRS] TX complete")
            else:
                print("[APRS] TX timeout")
            time.sleep(0.15)
            if normal_freq:
                rigctl_cmd_priority(f"F {normal_freq}")
                radio_cache["freq"] = normal_freq
                last_user_freq_change = time.time()
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
    _atomic_write("/home/pi/direwolf.conf", conf)
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


@app.post("/admin/update")
async def admin_update(request: Request):
    """api.py をアップデートしてサービスを再起動する"""
    content = await request.body()
    if not content:
        raise HTTPException(status_code=400, detail="Empty body")
    # Python 構文チェック
    try:
        compile(content.decode("utf-8"), "<api.py>", "exec")
    except SyntaxError as e:
        raise HTTPException(status_code=422, detail=f"Syntax error: {e}")
    api_path = Path("/home/pi/fastapi/api.py")
    bak_path = Path("/home/pi/fastapi/api.py.bak_update")
    try:
        if api_path.exists():
            import shutil
            shutil.copy2(api_path, bak_path)
        api_path.write_bytes(content)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Write failed: {e}")
    # systemd の Restart=on-failure/always で自動再起動させる
    def _restart():
        import time as _time
        _time.sleep(0.5)
        os.kill(os.getpid(), signal.SIGTERM)
    threading.Thread(target=_restart, daemon=True).start()
    return {"status": "ok", "message": "restarting"}
APIEOF

# ─── cw_bridge.py: 独立 UDP→Serial CW ブリッジ (タイムスタンプ変換方式) ───
cat << 'CWBEOF' > /home/pi/cw_bridge.py
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
            android_fire_ms = int.from_bytes(data[2:10], 'big')
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
                pkt = data[:2] + server_fire_ms.to_bytes(8, 'big')
            else:
                # SYNC 未取得 or 旧形式 (opTimeMs=0): 即時発火
                pkt = data[:2] + bytes(7) + b'\x01'
            with write_lock:
                try:
                    ser.write(pkt)
                except Exception as e:
                    print(f"[cw_bridge] write error: {e}", flush=True)

    sock.close()
    ser.close()
    print("[cw_bridge] stopped", flush=True)

if __name__ == "__main__":
    main()
CWBEOF
chmod +x /home/pi/cw_bridge.py
echo "cw_bridge.py 生成完了"

sudo systemctl restart fastapi fastapi-audio
echo ""
echo "完了。30秒後にログを確認:"
echo "  sudo journalctl -u fastapi -f"
echo "  sudo journalctl -u fastapi-audio -f"
