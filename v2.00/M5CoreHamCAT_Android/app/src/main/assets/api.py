from fastapi import FastAPI, BackgroundTasks, Depends, HTTPException, Request, Security, Form
from fastapi.responses import StreamingResponse
from fastapi.security import APIKeyHeader, APIKeyQuery
from starlette.background import BackgroundTask
from pydantic import BaseModel
import array
import atexit
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
API_VERSION = "1.82"
IS_AUDIO_SERVICE = os.environ.get("AUDIO_SERVICE") == "1"

# ALSAデバイス設定 (環境変数 or POST /radio/audio_device で変更可)
_alsa_capture_dev  = os.environ.get("ALSA_CAPTURE",  "plughw:CARD=CODEC,DEV=0")
_alsa_playback_dev = os.environ.get("ALSA_PLAYBACK", "plughw:CARD=CODEC,DEV=0")
_api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)
_api_key_query  = APIKeyQuery(name="api_key",    auto_error=False)

async def verify_key(
    request: Request,
    header_key: str = Security(_api_key_header),
    query_key:  str = Security(_api_key_query),
):
    # オーディオストリームはクライアントがヘッダーを付加できないため認証不要
    if request.url.path in ("/radio/audio", "/radio/audio_tx", "/radio/ft8_tx", "/radio/audio_sub"):
        return
    if API_KEY and header_key != API_KEY and query_key != API_KEY:
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

# USB Audio CODEC Playback ボリューム初期化 (TX音声がコーデックから出力されるよう)
def _init_alsa_volume():
    for ctrl in ["Speaker", "Headphone", "PCM", "Master",
                 "Speaker Playback Volume", "Headphone Playback Volume",
                 "PCM Playback Volume", "Master Playback Volume"]:
        r = subprocess.run(["amixer", "-c", "CODEC", "sset", ctrl, "100%", "unmute"],
                           capture_output=True)
        if r.returncode == 0:
            print(f"[alsa] {ctrl} = 100% unmuted", flush=True)
    # Captureボリュームも設定 (FT8デコード感度向上)
    for ctrl in ["Mic", "Capture", "ADC", "Input",
                 "Mic Capture Volume", "Capture Volume",
                 "Mic Boost", "Auto Gain Control"]:
        r = subprocess.run(["amixer", "-c", "CODEC", "sset", ctrl, "100%", "cap"],
                           capture_output=True)
        if r.returncode == 0:
            print(f"[alsa] {ctrl} = 100% cap", flush=True)
_init_alsa_volume()

if IS_AUDIO_SERVICE:
    def _warmup_ffmpeg():
        time.sleep(5)  # サービス起動後ALSA安定待ち
        _ensure_ffmpeg("12000")
        print("[warmup] ffmpeg pre-started", flush=True)
    threading.Thread(target=_warmup_ffmpeg, daemon=True).start()

    def _ffmpeg_watchdog():
        """ffmpegが予期せず死亡した場合にaplay不在を確認して自動再起動"""
        time.sleep(10)  # 起動直後はwarmupに任せる
        while True:
            time.sleep(3)
            p = _ffmpeg_proc
            if p is None or p.poll() is not None:
                if subprocess.run(["pgrep", "-x", "aplay"], capture_output=True).returncode != 0:
                    print("[watchdog] ffmpeg dead, restarting", flush=True)
                    _ensure_ffmpeg("12000")
    threading.Thread(target=_ffmpeg_watchdog, daemon=True).start()

# 常時起動ffmpegの状態管理
_ffmpeg_proc = None
_ffmpeg_rate = None
_ffmpeg_lock = threading.Lock()
_ffmpeg_buf = _queue.Queue(maxsize=64)  # 64×4096B ≈ 16秒分バッファ
_ffmpeg_subscribers = []
_ffmpeg_sub_lock = threading.Lock()

def _ffmpeg_stderr_logger(proc):
    try:
        for raw in iter(proc.stderr.readline, b''):
            line = raw.decode(errors='replace').rstrip()
            if line:
                print(f"[ffmpeg] {line}", flush=True)
    except Exception:
        pass

def _ffmpeg_reader(proc):
    """ffmpeg stdout → _ffmpeg_buf + 登録済みサブスクライバーへ配信"""
    first = True
    try:
        for data in iter(lambda: proc.stdout.read1(4096), b''):
            if first:
                print(f"[ffmpeg] first chunk {len(data)}B pid={proc.pid}", flush=True)
                first = False
            while _ffmpeg_buf.full():
                try:
                    _ffmpeg_buf.get_nowait()
                except _queue.Empty:
                    break
            try:
                _ffmpeg_buf.put_nowait(data)
            except _queue.Full:
                pass
            with _ffmpeg_sub_lock:
                for _sq in list(_ffmpeg_subscribers):
                    try:
                        _sq.put_nowait(data)
                    except _queue.Full:
                        pass
    except Exception as e:
        print(f"[ffmpeg_reader] error pid={proc.pid}: {e}", flush=True)
    try:
        rc = proc.wait(timeout=1.0)
    except subprocess.TimeoutExpired:
        rc = proc.poll()
    print(f"[ffmpeg_reader] exited pid={proc.pid} rc={rc}", flush=True)
    if rc is not None and rc < 0:
        # SIGKILLで死んだ — 誰が殺したか診断
        who = subprocess.run(["pgrep", "-af", "plughw:CARD=CODEC"],
                             capture_output=True, text=True).stdout.strip()
        dw  = subprocess.run(["pgrep", "-af", "direwolf"],
                             capture_output=True, text=True).stdout.strip()
        print(f"[ffmpeg_reader] KILLED rc={rc}: plughw_procs={who!r} direwolf={dw!r}", flush=True)

def _start_persistent_ffmpeg(rate: str):
    global _ffmpeg_proc, _ffmpeg_rate
    old = _ffmpeg_proc
    if old and old.poll() is None:
        print(f"[ffmpeg] killing old pid={old.pid}", flush=True)
        try:
            os.kill(old.pid, signal.SIGKILL)  # SIGKILL直接: killpgより確実 (SIGTERM無視やPGID不一致を回避)
            old.wait(timeout=1.0)
        except Exception as e:
            print(f"[ffmpeg] kill old failed: {e}", flush=True)
    # pkill前に何が ALSA を掴んでいるか記録
    alsa_before = subprocess.run(["pgrep", "-af", "plughw:CARD=CODEC"],
                                  capture_output=True, text=True).stdout.strip()
    dw_before   = subprocess.run(["pgrep", "-af", "direwolf"],
                                  capture_output=True, text=True).stdout.strip()
    if alsa_before or dw_before:
        print(f"[ffmpeg] before-pkill: alsa={alsa_before!r} dw={dw_before!r}", flush=True)
    # 孤立ffmpegのみを一掃する。aplayはFT8 TX中に動いている可能性があるため
    # plughw:CARD=CODEC 全体をkillするとTXが中断する。ffmpegのみを対象にする。
    r = subprocess.run(["pkill", "-9", "ffmpeg"], capture_output=True)
    if r.returncode == 0:
        print("[ffmpeg] pkill: cleared untracked ffmpeg(s)", flush=True)
        time.sleep(0.5)  # SIGKILL後のALSA解放待ち (何かkillした時のみ)
    # pkillで何も殺さなかった場合 (aplay正常終了直後など) は待ち不要
    cmd = [
        "ffmpeg", "-f", "alsa", "-thread_queue_size", "1024",
        "-ar", rate, "-i", _alsa_capture_dev,
        "-ac", "1",
        "-af", "volume=enable='between(t,0,0.5)':volume=0,highpass=f=50,lowpass=f=4000,volume=0.3",
        "-f", "s16le", "-acodec", "pcm_s16le",
        "-nostdin", "-vn", "-sn", "-dn", "-map", "0:a",
        "-flush_packets", "1", "-nostats", "pipe:1"
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            start_new_session=True)
    _ffmpeg_proc = proc
    _ffmpeg_rate = rate
    print(f"[ffmpeg] started pid={proc.pid} rate={rate}", flush=True)
    # バッファをリセット
    while True:
        try:
            _ffmpeg_buf.get_nowait()
        except _queue.Empty:
            break
    threading.Thread(target=_ffmpeg_reader, args=(proc,), daemon=True).start()
    threading.Thread(target=_ffmpeg_stderr_logger, args=(proc,), daemon=True).start()
    return proc

def _ensure_ffmpeg(rate: str):
    global _ffmpeg_proc
    with _ffmpeg_lock:
        if _ffmpeg_proc and _ffmpeg_proc.poll() is None:
            return _ffmpeg_proc  # レートに関わらず既存プロセスを再利用 (レート競合でorphanが生じるのを防ぐ)
        # aplay (FT8 TX) が動いている間はffmpegを起動しない
        # 起動してもALSA競合で即死し、しかもaplayを pkill したくない
        if subprocess.run(["pgrep", "-x", "aplay"], capture_output=True).returncode == 0:
            print("[ffmpeg] aplay TX active — ffmpeg start deferred", flush=True)
            return _ffmpeg_proc  # dead proc を返す; 呼び出し元は再試行するか無音を流す
        # 常時12000Hz固定: FT8↔SPK切替でレートが変わってもffmpegを再起動しない
        # これによりクラッキング（再起動時のALSAモード切替ノイズ）を根絶する
        return _start_persistent_ffmpeg("12000")

def _atexit_kill_ffmpeg():
    """サービス終了時に ffmpeg を確実に kill (次回起動時の "Device busy" を防ぐ)"""
    p = _ffmpeg_proc
    if p and p.poll() is None:
        try:
            os.killpg(os.getpgid(p.pid), signal.SIGKILL)
        except Exception:
            pass

atexit.register(_atexit_kill_ffmpeg)

_ffmpeg_autostop_timer = None
_ffmpeg_autostop_lock  = threading.Lock()

def _schedule_ffmpeg_autostop():
    """全サブスクライバー切断後60秒でffmpegを停止 (ALSAデバイスを解放してSPK/TX競合を防ぐ)
    60秒: 画面切替は通常5秒以内なので再起動が発生しない → SPKパリパリ音の根本対策"""
    global _ffmpeg_autostop_timer
    with _ffmpeg_autostop_lock:
        if _ffmpeg_autostop_timer:
            _ffmpeg_autostop_timer.cancel()
        def _do_stop():
            print("[ffmpeg] autostop timer fired", flush=True)
            with _ffmpeg_sub_lock:
                if _ffmpeg_subscribers:
                    print("[ffmpeg] autostop cancelled (subscriber present)", flush=True)
                    return  # 再接続された
            p = _ffmpeg_proc
            if p and p.poll() is None:
                try:
                    os.killpg(os.getpgid(p.pid), signal.SIGTERM)
                    print(f"[ffmpeg] auto-stopped pid={p.pid} (ALSA released)", flush=True)
                except Exception:
                    pass
        _ffmpeg_autostop_timer = threading.Timer(60.0, _do_stop)
        _ffmpeg_autostop_timer.daemon = True
        _ffmpeg_autostop_timer.start()

def _cancel_ffmpeg_autostop():
    global _ffmpeg_autostop_timer
    with _ffmpeg_autostop_lock:
        if _ffmpeg_autostop_timer:
            _ffmpeg_autostop_timer.cancel()
            _ffmpeg_autostop_timer = None

def _ffmpeg_subscribe():
    """FT8デコード用: ffmpeg 音声を受信するサブスクライバーキューを登録して返す。"""
    _cancel_ffmpeg_autostop()
    q = _queue.Queue(maxsize=512)
    with _ffmpeg_sub_lock:
        _ffmpeg_subscribers.append(q)
    return q

def _ffmpeg_unsubscribe(q):
    with _ffmpeg_sub_lock:
        try:
            _ffmpeg_subscribers.remove(q)
        except ValueError:
            pass
    if not _ffmpeg_subscribers:
        _schedule_ffmpeg_autostop()

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


# ─── FT8/FT4 サーバーサイドデコード ───────────────────────────────
import wave as _wave
import datetime as _datetime
import re as _re

_ft8_state_lock   = threading.Lock()

_ft8_running      = False
_ft8_is_ft4       = False
_ft8_thread       = None
_ft8_results      = []
_ft8_results_lock = threading.Lock()
_ft8_build_status = "ok"
_ft8_build_error  = ""
_FT8_SAMPLE_RATE  = 12000

def _ft8_find_decoder():
    """ft8wav (mfsk-core Rust) のパスを返す。(path, is_jt9)"""
    import shutil as _shutil
    p = _shutil.which('ft8wav')
    if p: return p, False
    for path in [
        os.path.expanduser('~/ft8wav'),
        '/usr/local/bin/ft8wav',
    ]:
        if os.path.isfile(path):
            return path, False
    return None, False

_ft8_decoder_path, _ = _ft8_find_decoder()
print(f'[ft8] decoder: {_ft8_decoder_path}')

# ── ft8enc: FT8 PCM エンコーダ (TX用、Rust/mfsk-core) ──────────────────
_ft8_encoder_path = None

def _ft8_find_encoder():
    import shutil as _shutil
    p = _shutil.which('ft8enc')
    if p: return p
    for path in [
        os.path.expanduser('~/ft8wav/target/release/ft8enc'),
        '/usr/local/bin/ft8enc',
        os.path.expanduser('~/ft8enc'),
    ]:
        if os.path.isfile(path): return path
    return None

_ft8_encoder_path = _ft8_find_encoder()
print(f'[ft8] encoder: {_ft8_encoder_path}')

def _ft8_wav_write(path, pcm_bytes):
    with _wave.open(path, 'wb') as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(_FT8_SAMPLE_RATE)
        wf.writeframes(pcm_bytes)

def _ft8_parse_jt9(lines):
    results = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        # Format A (~ あり): "[HHMMSS]  SNR  DT  Hz ~  Message"
        m = _re.match(r'^(?:\d{6}\s+)?([+-]?\d+)\s+([+-]?\d+\.?\d*)\s+(\d+)\s+~\s*(.+)$', line)
        if not m:
            # Format B (~ なし): "[HHMMSS]  SNR  DT  Hz  Message"
            m = _re.match(r'^(?:\d{6}\s+)?([+-]?\d+)\s+([+-]?\d+\.?\d*)\s+(\d+)\s+(.+)$', line)
        if m:
            results.append({
                'snr': int(m.group(1)),
                'dt':  float(m.group(2)),
                'hz':  int(m.group(3)),
                'msg': m.group(4).strip(),
                'utc': _datetime.datetime.utcnow().strftime('%H%M')
            })
    return results

def _ft8_decode_one(pcm_bytes, is_ft4):
    tmp_wav = None
    try:
        import tempfile as _tempfile
        with _tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
            tmp_wav = f.name
        _ft8_wav_write(tmp_wav, pcm_bytes)
        if not _ft8_decoder_path:
            print('[ft8] ft8wav が見つかりません。create_api.sh を再実行してください。')
            return
        cmd = [_ft8_decoder_path] + (['--ft4'] if is_ft4 else []) + [tmp_wav]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        decoded = _ft8_parse_jt9(r.stdout.splitlines())
        print(f'[ft8] decoded {len(decoded)} messages (rc={r.returncode})')
        if len(decoded) == 0 and r.stdout.strip():
            print(f'[ft8] raw stdout: {r.stdout[:400]!r}')
        if r.stderr.strip():
            print(f'[ft8] stderr: {r.stderr[:400]!r}')
        with _ft8_results_lock:
            global _ft8_results
            _ft8_results.extend(decoded)
    except Exception as e:
        print(f'[ft8] decode error: {e}')
    finally:
        if tmp_wav:
            try:
                os.unlink(tmp_wav)
            except Exception:
                pass

def _ft8_loop():
    global _ft8_running
    is_ft4 = _ft8_is_ft4
    cycle_ms = 7500 if is_ft4 else 15000
    cycle_bytes = _FT8_SAMPLE_RATE * 2 * cycle_ms // 1000  # 16-bit mono

    if IS_AUDIO_SERVICE:
        _ensure_ffmpeg(str(_FT8_SAMPLE_RATE))
    audio_q = _ffmpeg_subscribe()
    pcm_buf = bytearray()
    last_cycle_id = -1
    try:
        while _ft8_running:
            try:
                chunk = audio_q.get(timeout=0.1)
                pcm_buf.extend(chunk)
            except _queue.Empty:
                pass
            now_ms = int(time.time() * 1000) % 60000
            cycle_id = now_ms // cycle_ms
            if cycle_id != last_cycle_id and last_cycle_id >= 0:
                if len(pcm_buf) >= cycle_bytes // 4:
                    decode_buf = bytes(pcm_buf[:cycle_bytes])
                    threading.Thread(target=_ft8_decode_one, args=(decode_buf, is_ft4),
                                     daemon=True).start()
                else:
                    print(f'[ft8] skip: {len(pcm_buf)} bytes (startup partial)')
                pcm_buf = bytearray()
            last_cycle_id = cycle_id
    finally:
        _ffmpeg_unsubscribe(audio_q)
        _ft8_running = False
        print('[ft8] loop stopped')


def _ft8_do_start(is_ft4: bool):
    global _ft8_running, _ft8_thread, _ft8_is_ft4
    with _ft8_state_lock:
        if _ft8_running:
            _ft8_running = False
            if _ft8_thread and _ft8_thread.is_alive():
                _ft8_thread.join(timeout=2)
        _ft8_is_ft4 = is_ft4
        _ft8_running = True
        _ft8_thread = threading.Thread(target=_ft8_loop, daemon=True, name='ft8-decode')
        _ft8_thread.start()


def _ft8_proxy_get(path: str) -> dict:
    """port 8000 から port 50000 の ft8 エンドポイントにプロキシ。"""
    import http.client as _http, json as _json
    try:
        conn = _http.HTTPConnection("127.0.0.1", 50000, timeout=5)
        headers = {"X-API-Key": API_KEY} if API_KEY else {}
        conn.request("GET", path, headers=headers)
        resp = conn.getresponse()
        body = resp.read().decode(errors="replace")
        conn.close()
        return _json.loads(body)
    except Exception as e:
        return {"error": str(e)}


@app.get("/ft8/start")
def ft8_start(is_ft4: bool = False):
    if not IS_AUDIO_SERVICE:
        return _ft8_proxy_get(f"/ft8/start?is_ft4={str(is_ft4).lower()}")
    if not _ft8_decoder_path:
        return {"status": "error", "error": "ft8wav not found — run create_api.sh"}
    _ft8_do_start(is_ft4)
    return {"status": "started", "is_ft4": is_ft4}


@app.get("/ft8/stop")
def ft8_stop():
    global _ft8_running
    if not IS_AUDIO_SERVICE:
        return _ft8_proxy_get("/ft8/stop")
    _ft8_running = False
    return {"status": "stopped"}


@app.get("/ft8/results")
def ft8_results(drain: bool = False):
    if not IS_AUDIO_SERVICE:
        return _ft8_proxy_get(f"/ft8/results?drain={str(drain).lower()}")
    with _ft8_results_lock:
        out = list(_ft8_results)
        if drain:
            _ft8_results.clear()
        return {"results": out, "build_status": _ft8_build_status, "build_error": _ft8_build_error}


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


@app.get("/radio/audio_device")
def get_audio_device():
    return {"capture": _alsa_capture_dev, "playback": _alsa_playback_dev}

@app.post("/radio/audio_device")
async def set_audio_device(request: Request):
    global _alsa_capture_dev, _alsa_playback_dev
    data = {}
    try: data = await request.json()
    except Exception: pass
    capture  = str(data.get("capture",  "") or "").strip()
    playback = str(data.get("playback", "") or "").strip()
    changed = False
    if capture:
        _alsa_capture_dev = capture
        changed = True
    if playback:
        _alsa_playback_dev = playback
    if IS_AUDIO_SERVICE and changed:
        _ensure_ffmpeg("12000")
    print(f"[audio_device] capture={_alsa_capture_dev} playback={_alsa_playback_dev}", flush=True)
    return {"capture": _alsa_capture_dev, "playback": _alsa_playback_dev}

@app.get("/rigs")
def list_rigs():
    result = subprocess.run(["rigctl", "-l"], capture_output=True, text=True)
    rigs = []
    for line in result.stdout.splitlines():
        parts = line.strip().split()
        if len(parts) >= 3 and parts[0].isdigit():
            rigs.append({"id": int(parts[0]), "name": f"{parts[1]} {parts[2]}"})
    return {"rigs": rigs}


@app.get("/time")
def get_time():
    return {"ms": int(time.time() * 1000)}


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


@app.post("/radio/simplex")
def set_simplex():
    """スプリット解除 + VFO A選択"""
    rigctl_cmd("V VFOA")
    rigctl_cmd("S 0 VFOA")
    return {"status": "ok"}


@app.post("/radio/ft8_stop")
def ft8_capture_stop():
    """FT8画面離脱時の通知 (no-op: サブスクライバーは cleanup() が削除, ffmpegは autostop timer が停止)
    以前は subscribers.clear() + ffmpeg kill を行っていたが、stream() ジェネレータが
    GeneratorExit を受け取る前に sub_q が削除されると auto-restart ループに陥り rc=-9 を連発する。
    autostop 機構 (_schedule_ffmpeg_autostop) が全サブスクライバー切断後 5s で ALSA を解放する。"""
    print("[ft8_capture_stop] called", flush=True)
    return {"status": "ok"}


@app.get("/radio/audio_sub")
def audio_sub_stream(request: Request, background_tasks: BackgroundTasks):
    """FT8専用サブスクライバー音声ストリーム (12000Hz固定, /radio/audioと競合しない)"""
    from fastapi.responses import JSONResponse
    # port 8000 (メインAPI) はffmpegを管理しない。port 50000 (AUDIO_SERVICE) にプロキシする。
    if not IS_AUDIO_SERVICE:
        import http.client as _http
        def proxy_stream():
            conn = None
            try:
                conn = _http.HTTPConnection("127.0.0.1", 50000)
                _proxy_headers = {"X-API-Key": API_KEY} if API_KEY else {}
                conn.request("GET", "/radio/audio_sub", headers=_proxy_headers)
                resp = conn.getresponse()
                while True:
                    chunk = resp.read(4096)
                    if not chunk:
                        break
                    yield chunk
            except GeneratorExit:
                pass
            except Exception as e:
                print(f"[audio_sub_proxy] error: {e}", flush=True)
            finally:
                if conn:
                    try: conn.close()
                    except Exception: pass
        return StreamingResponse(proxy_stream(), media_type="application/octet-stream")
    # FT8は12000Hz必須。既存ffmpegが異なるレートなら強制再起動。
    # TX中 (aplay実行中) はALSA競合 → 最大2秒待ってから起動
    with _ffmpeg_lock:
        if not (_ffmpeg_proc and _ffmpeg_proc.poll() is None) or _ffmpeg_rate != "12000":
            for _w in range(20):
                if subprocess.run(["pgrep", "-x", "aplay"], capture_output=True).returncode != 0:
                    break
                print(f"[audio_sub] aplay TX active, waiting... ({_w+1}/20)", flush=True)
                time.sleep(0.1)
            _start_persistent_ffmpeg("12000")
    time.sleep(0.4)  # ffmpegがALSAデバイスを開くまで少し待つ
    proc = _ffmpeg_proc
    if proc and proc.poll() is not None:
        err = ""
        try:
            err = (proc.stderr.read(512) or b"").decode(errors="replace").strip()
        except Exception:
            pass
        print(f"[audio_sub] ffmpeg dead: {err}")
        return JSONResponse({"error": "ffmpeg failed", "detail": err}, status_code=503)

    sub_q = _ffmpeg_subscribe()

    def stream():
        try:
            silence_since = None
            while True:
                try:
                    data = sub_q.get(timeout=0.2)
                    yield data
                    silence_since = None
                except _queue.Empty:
                    # ffmpegが停止中でも接続を維持し無音PCMを送る; 3秒死亡で自動再起動
                    if not (_ffmpeg_proc and _ffmpeg_proc.poll() is None):
                        now = time.time()
                        if silence_since is None:
                            silence_since = now
                        elif now - silence_since > 3.0:
                            # sub_q が外部から削除された場合 (旧 ft8_capture_stop バグ等) に再登録
                            with _ffmpeg_sub_lock:
                                if sub_q not in _ffmpeg_subscribers:
                                    _ffmpeg_subscribers.append(sub_q)
                            _ensure_ffmpeg("12000")
                            silence_since = None
                            print("[audio_sub] ffmpeg auto-restarted", flush=True)
                        yield b'\x00' * 512  # S16_LE無音 (約21ms相当)
        except GeneratorExit:
            pass

    def cleanup():
        _ffmpeg_unsubscribe(sub_q)
        print("[audio_sub] client disconnected")

    return StreamingResponse(stream(), media_type="application/octet-stream",
                             background=BackgroundTask(cleanup))


@app.get("/radio/tuner")
def get_tuner():
    raw = rigctl_cmd("u TUNER")
    parts = raw.split() if raw else []
    if parts and parts[0] in ("0", "1"):
        return {"supported": True, "state": parts[0] == "1"}
    return {"supported": False, "state": False}


@app.post("/radio/tuner")
def set_tuner(state: int = Form(...)):
    raw = rigctl_cmd(f"U TUNER {state}")
    return {"ok": bool(raw and "RPRT 0" in raw)}


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
        # aplayを即座にkillしてSPK復帰を高速化 (自然終了待ちをやめる)
        subprocess.run(["pkill", "-9", "aplay"], capture_output=True)
        if IS_AUDIO_SERVICE:
            def _ptt_off_restart():
                for _ in range(5):  # 最大0.5秒待機 (pkillで即死するはず)
                    if subprocess.run(["pgrep", "-x", "aplay"], capture_output=True).returncode != 0:
                        break
                    time.sleep(0.1)
                _ensure_ffmpeg("12000")
                print("[ptt_off] ffmpeg pre-started for SPK recovery", flush=True)
            threading.Thread(target=_ptt_off_restart, daemon=True).start()
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
    if proc is None or proc.poll() is not None:
        print(f"[audio_rx] ffmpeg dead, waiting for aplay release...")
        deadline = time.time() + 0.6
        while time.time() < deadline:
            if subprocess.run(["pgrep", "-x", "aplay"], capture_output=True).returncode != 0:
                break
            time.sleep(0.05)
        proc = _ensure_ffmpeg(rate)  # ロック経由で再起動 (FT8サブスクライバーがいる場合は12000Hzを優先)
        time.sleep(0.1)  # ALSA open待ち
        if proc is None or proc.poll() is not None:
            err = ""
            try:
                if proc:
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

    def stream():
        # TX中はffmpegが停止するが、EOFを送るとAndroidがMAX_RETRYを消費して諦める。
        # 代わりに無音PCMを送り続け、PTT=0後にffmpegが復活したら自動再生を再開する。
        try:
            silence_since = None
            while True:
                try:
                    data = _ffmpeg_buf.get(timeout=0.5)
                    silence_since = None
                    yield data
                except _queue.Empty:
                    p = _ffmpeg_proc
                    if p is None or p.poll() is not None:
                        # ffmpeg死亡中 → 無音送信で接続維持 (TX中など)
                        now = time.time()
                        if silence_since is None:
                            silence_since = now
                        elif now - silence_since > 30.0:
                            # 30秒以上ffmpegが復活しない場合のみストリームを閉じる
                            print("[audio_rx] ffmpeg dead 30s, closing stream", flush=True)
                            return
                        yield b'\x00' * 12000  # 500ms相当の無音 (12000Hz mono 16bit)
        except GeneratorExit:
            pass

    def cleanup():
        print("[audio_rx] client disconnected")

    return StreamingResponse(stream(), media_type="application/octet-stream",
                             headers={"X-Audio-Rate": _ffmpeg_rate or rate},
                             background=BackgroundTask(cleanup))


@app.post("/radio/audio_tx")
async def audio_tx(request: Request, rate: int = 8000):
    print(f"[audio_tx] connected rate={rate}")
    loop = asyncio.get_running_loop()
    def _kill_procs():
        subprocess.run(["pkill", "-9", "direwolf"], capture_output=True)
        # ffmpegは殺さない: USBコーデックは全二重対応のため、TX中もffmpegで録音継続可
        # (v1.50と同じ動作。ffmpegを殺すとSPK復帰遅延とパリパリ音の原因になる)
        subprocess.run(["pkill", "-9", "aplay"],    capture_output=True)
    await loop.run_in_executor(None, _kill_procs)
    proc = subprocess.Popen(
        ["aplay", "-D", _alsa_playback_dev,
         "-f", "S16_LE", "-r", str(rate), "-c", "1"],
        stdin=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0
    )
    try:
        async for chunk in request.stream():
            if proc.poll() is not None:
                break
            global last_heartbeat
            last_heartbeat = time.time()
            await loop.run_in_executor(None, proc.stdin.write, chunk)
    except Exception as e:
        print(f"[audio_tx] stream: {type(e).__name__}: {e}")
    finally:
        try:
            proc.stdin.close()
        except Exception:
            pass
        await loop.run_in_executor(None, proc.wait)
        ae = ""
        try:
            ae = proc.stderr.read(512).decode(errors='replace').strip()
        except Exception:
            pass
        print(f"[audio_tx] done rc={proc.returncode} err={ae!r}")
        # TX完了後fallback: AUDIO_SERVICEのみffmpegを再起動 (port 8000はffmpegを管理しない)
        if IS_AUDIO_SERVICE:
            def _restart_spk():
                time.sleep(0.1)
                _ensure_ffmpeg("12000")
                print("[audio_tx] ffmpeg restart (fallback) after PTT TX", flush=True)
            threading.Thread(target=_restart_spk, daemon=True).start()
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
    try:
        compile(content.decode("utf-8"), "<api.py>", "exec")
    except SyntaxError as e:
        raise HTTPException(status_code=422, detail=f"Syntax error: {e}")
    api_path = Path("/home/pi/fastapi/api.py")
    try:
        if api_path.exists():
            import shutil
            shutil.copy2(api_path, Path("/home/pi/fastapi/api.py.bak_update"))
        api_path.write_bytes(content)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Write failed: {e}")
    print(f"[admin/update] api.py updated ({len(content)} bytes), restarting...")
    def _restart():
        import time as _time
        _time.sleep(0.5)
        r = subprocess.run(["sudo", "systemctl", "restart", "fastapi"], capture_output=True)
        if r.returncode != 0:
            os.kill(os.getpid(), signal.SIGTERM)
    threading.Thread(target=_restart, daemon=True).start()
    return {"status": "ok", "message": "restarting"}


@app.post("/radio/ft8_tx")
async def ft8_tx(request: Request):
    """FT8/FT4 メッセージをPi側でエンコードして送信する。
    Android はメッセージテキストのみ送信; Pi が ft8_encode でPCM生成 → aplay (~12.64s)。
    PTT は Android が /radio/ptt で制御する。"""
    data = await request.json()
    msg        = data.get("msg", "")
    audio_freq = float(data.get("audio_freq", 1500))
    rate       = int(data.get("rate", 12000))
    is_ft4     = bool(data.get("is_ft4", False))

    loop = asyncio.get_running_loop()

    def encode_and_play():
        encoder = _ft8_encoder_path or "/usr/local/bin/ft8enc"
        is_ft8enc = os.path.basename(encoder) == 'ft8enc'
        if is_ft8enc:
            enc_cmd = [encoder, msg, str(audio_freq)] + (["--ft4"] if is_ft4 else [])
            play_rate = 12000
        else:
            enc_cmd = [encoder, msg, str(audio_freq), str(rate), "1" if is_ft4 else "0"]
            play_rate = rate
        try:
            enc = subprocess.run(enc_cmd, capture_output=True, timeout=10)
        except subprocess.TimeoutExpired:
            print("[ft8_tx] encode timeout")
            return -10
        except FileNotFoundError:
            print(f"[ft8_tx] encoder not found ({encoder}) — create_api.sh を再実行してください")
            return -11

        if enc.returncode != 0 or not enc.stdout:
            print(f"[ft8_tx] encode error rc={enc.returncode}: {enc.stderr.decode(errors='replace')}")
            return -1

        pcm_raw = enc.stdout
        print(f"[ft8_tx] encoded {len(pcm_raw)} bytes, msg='{msg}', freq={audio_freq}, rate={play_rate}")

        # ffmpegはUSBコーデック全二重対応のため殺さない (port 50000が管理するffmpegを保護)
        subprocess.run(["pkill", "-9", "aplay"],  capture_output=True)
        time.sleep(0.1)

        TX_GAIN = 1.0
        samples = array.array('h', pcm_raw)
        for i in range(len(samples)):
            samples[i] = max(-32768, min(32767, int(samples[i] * TX_GAIN)))
        pcm = samples.tobytes()

        proc = subprocess.Popen(
            ["aplay", "-D", _alsa_playback_dev,
             "-f", "S16_LE", "-r", str(play_rate), "-c", "1", "-B", "100000"],
            stdin=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0
        )

        chunk = play_rate * 2  # 1秒分
        offset = 0
        try:
            while offset < len(pcm):
                end = min(offset + chunk, len(pcm))
                proc.stdin.write(pcm[offset:end])
                global last_heartbeat
                last_heartbeat = time.time()
                offset = end
        except OSError as e:
            print(f"[ft8_tx] aplay write error at offset={offset}: {e}", flush=True)
            proc.kill()
        finally:
            try:
                proc.stdin.close()
            except Exception:
                pass
        proc.wait()

        ae = ""
        try:
            ae = proc.stderr.read(512).decode(errors="replace").strip()
        except Exception:
            pass
        print(f"[ft8_tx] done rc={proc.returncode} err={ae!r}", flush=True)

        # TX完了後: AUDIO_SERVICEのみffmpegを再起動 (port 8000はffmpegを管理しない)
        if IS_AUDIO_SERVICE:
            time.sleep(1.5)
            _ensure_ffmpeg("12000")  # サブスクライバーの有無に関わらず再起動 (audio_rx stream() が待機中のため)
            print("[ft8_tx] ffmpeg restarted after TX", flush=True)

        return proc.returncode

    rc = await loop.run_in_executor(None, encode_and_play)
    return {"status": "ok" if rc == 0 else "error", "rc": rc}


@app.post("/radio/ft8_decode")
async def ft8_decode_endpoint(request: Request, rate: int = 12000, is_ft4: bool = False):
    """Android から生PCM (S16_LE mono) を受け取り ft8_decode バイナリでデコードする。
    Body: raw S16_LE mono PCM  Response: {"results": [{"snr":-10,"dt":0.1,"freq":1234,"msg":"..."}]}
    """
    import tempfile
    pcm = await request.body()
    if not pcm:
        return {"results": []}

    data_size = len(pcm)
    _sample_count = data_size // 2
    _duration_sec = _sample_count / rate if rate > 0 else 0.0

    tmp_path = None
    try:
        # 録音音量が低い場合の自動ゲインブースト (FT8弱信号デコード感度向上)
        s_arr = array.array('h')
        s_arr.frombytes(pcm)
        if s_arr:
            peak = max(abs(max(s_arr)), abs(min(s_arr)))
            if 0 < peak < 24000:
                gain = min(20.0, 24000.0 / peak)
                # インプレース処理でメモリ節約 (Pi Zero OOM対策)
                for i in range(len(s_arr)):
                    v = int(s_arr[i] * gain)
                    if v > 32767: v = 32767
                    elif v < -32768: v = -32768
                    s_arr[i] = v
                pcm = s_arr.tobytes()
                data_size = len(pcm)
                print(f"[ft8_decode] audio boost x{gain:.1f} peak={peak} -> {int(peak*gain)}", flush=True)
            else:
                print(f"[ft8_decode] audio peak={peak} (no boost: {'silence' if peak==0 else 'loud'})", flush=True)

        wav_header = (
            b'RIFF' + struct.pack('<I', 36 + data_size) +
            b'WAVE' +
            b'fmt ' + struct.pack('<I', 16) +
            struct.pack('<H', 1) +          # PCM
            struct.pack('<H', 1) +          # mono
            struct.pack('<I', rate) +       # sample rate
            struct.pack('<I', rate * 2) +   # byte rate
            struct.pack('<H', 2) +          # block align
            struct.pack('<H', 16) +         # bits per sample
            b'data' + struct.pack('<I', data_size)
        )

        with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
            tmp_path = f.name
            f.write(wav_header + pcm)

        # デバッグ用: 最後のWAVを保存 (Pi上で ft8wav /tmp/ft8_debug.wav で手動テスト可)
        try:
            import shutil as _shutil2
            _shutil2.copy2(tmp_path, "/tmp/ft8_debug.wav")
        except Exception:
            pass

        decoder = _ft8_decoder_path
        if not decoder or not os.path.exists(decoder):
            return {"results": [], "error": "ft8wav not found — run create_api.sh"}
        args = [decoder] + (["--ft4"] if is_ft4 else []) + [tmp_path]

        _t0 = time.time()
        print(f"[ft8_decode] start {_duration_sec:.1f}s audio", flush=True)
        loop = asyncio.get_running_loop()
        proc = await loop.run_in_executor(
            None,
            lambda: subprocess.run(args, capture_output=True, timeout=30, text=True)
        )
        _elapsed = time.time() - _t0

        messages = []
        for line in proc.stdout.splitlines():
            line = line.strip()
            if '~' not in line:
                continue
            try:
                left, right = line.split('~', 1)
                nums = left.split()
                if len(nums) < 3:
                    continue
                messages.append({
                    "snr":  int(nums[0]),
                    "dt":   float(nums[1]),
                    "freq": int(nums[2]),
                    "msg":  right.strip()
                })
            except (ValueError, IndexError):
                continue

        dec_name = os.path.basename(decoder) if decoder else "none"
        print(f"[ft8_decode] {len(messages)} messages rc={proc.returncode} took={_elapsed:.1f}s audio={_duration_sec:.1f}s dec={dec_name}", flush=True)
        if proc.stderr:
            print(f"[ft8_decode] stderr: {proc.stderr[:300]}", flush=True)
        if not messages and proc.stdout:
            print(f"[ft8_decode] stdout: {proc.stdout[:300]}", flush=True)
        return {"results": messages}

    except Exception as e:
        print(f"[ft8_decode] error: {e}", flush=True)
        return {"results": [], "error": str(e)}
    finally:
        if tmp_path:
            try: os.unlink(tmp_path)
            except Exception: pass


