from fastapi import FastAPI, BackgroundTasks, Depends, HTTPException, Request, Security, Form, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from fastapi.security import APIKeyHeader
from fastapi.staticfiles import StaticFiles
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
import datetime
try:
    import serial as _serial
    _HAS_SERIAL = True
except ImportError:
    _HAS_SERIAL = False

# API Key 認証（環境変数 API_KEY が設定されている場合のみ有効）
API_KEY = os.environ.get("API_KEY", "")
API_VERSION = "3.01"

# ALSAデバイス設定 (環境変数 or POST /radio/audio_device で変更可)
_alsa_capture_dev  = os.environ.get("ALSA_CAPTURE",  "plughw:CARD=CODEC,DEV=0")
_alsa_playback_dev = os.environ.get("ALSA_PLAYBACK", "plughw:CARD=CODEC,DEV=0")
_api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)

async def verify_key(key: str = Security(_api_key_header), api_key: str = Query(default=None)):
    actual = key or api_key or ""
    if API_KEY and actual != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")

app = FastAPI(dependencies=[Depends(verify_key)])

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# 動的パス（ユーザー名に依存しない）
_HOME_DIR = Path(__file__).resolve().parent.parent  # /home/<user>
_FASTAPI_DIR = Path(__file__).resolve().parent       # /home/<user>/fastapi
_VENV_PY = str(_FASTAPI_DIR / "bin" / "python3")
_CW_BRIDGE_PY = str(_HOME_DIR / "cw_bridge.py")

# ─── FT8 サーバーサイドデコード ────────────────────────────────────────────
import wave
import tempfile
import json as _json

_ft8_rx_clients: list = []
_ft8_rx_clients_lock = threading.Lock()
_ft8_decode_running  = False
_ft8_decode_thread: threading.Thread | None = None
_jt9_decode_done = threading.Event()
_jt9_decode_done.set()  # 初期値: jt9未実行 = "完了" 状態

FT8_SAMPLE_RATE = 12000
FT8_PERIOD_S    = 15.0
FT8_BYTES       = int(FT8_SAMPLE_RATE * FT8_PERIOD_S) * 2  # 360000

_MFSK_DECODE_BIN = os.environ.get(
    "MFSK_DECODE_BIN",
    os.path.expanduser("~/mfsk-decode/target/release/mfsk-decode")
)
_ft8_last_decode_info: dict = {"stdout": "", "stderr": "", "decoded": 0, "period": -1}
_ft8_decode_depth: int = 3  # jt9 -d parameter (1/2/3)
_ft8_decode_filter: list = []  # [(center_hz, range_hz), ...], empty = no filter

# ─── AutoTX state ───────────────────────────────────────────────────────────
_auto_tx: dict = {
    "active": False,
    "mode": "even",   # "even"=period 0,2 (UTC 0s/30s)  /  "odd"=period 1,3 (15s/45s)
    "msg": "",
    "audio_freq": 1500,
}

# ─── CQ AUTO mode ────────────────────────────────────────────────────────────
_cq_auto: dict = {
    "active": False,
    "msg": "",         # CQ message (e.g. "CQ JH1XYZ PM95")
    "mode": "even",
    "audio_freq": 1500,
    "my_call": "",
    "my_grid": "",
}

# ─── QSO sequence state machine ─────────────────────────────────────────────
# state: 0=send_first 1=wait_report 2=send_rr73 3=wait_73 4=done
_qso: dict = {
    "active": False,
    "dx_call": "",
    "my_call": "",
    "state": 0,
    # state 0=send_step1  1=wait_reply1
    # state 2=send_step2  3=wait_reply2
    # state 4=send_step3  5=wait_step4  6=done
    "tx_mode": "odd",
    "audio_freq": 1500,
    "next_msg": "",
    "step1_msg":   "",    # DX MY GRID
    "step_snr_msg":"",   # DX MY NN   (Rなし SNR。DX指定でstep1→step2として使用、空なら省略)
    "step2_msg":   "",   # DX MY R-NN (空なら skip して step3 へ)
    "step3_msg":   "",   # DX MY RR73
    "step4_msg":   "",   # DX MY 73   (空なら step3 後に done)
    "snr_rcvd": "",      # DX からの SNR レポート
    "snr_sent": "",      # こちらから送った SNR レポート
    "snr_dx_peak": None, # QSO 中に受信した DX の最強 SNR (int)
    "cq_sender": False,  # True=CQ発信側（step2はSNRのみ）、False=DX指定呼び出し側（R-SNR）
}


_ft8_known_calls: set = set()  # セッション中に確認されたコールサイン
_ft8_my_call: str = ""        # 最後に設定された自局コールサイン（QSO外でも参照用）
_last_compound_dx: str = ""   # 最後にデコードされた複合コールサイン（/付き）


def _is_unknown_hash(tok: str) -> bool:
    """<...> <......> など jt9 の未解決ハッシュトークンを判定（ドット数不問）"""
    return (len(tok) >= 4 and tok[0] == '<' and tok[-1] == '>'
            and len(tok[1:-1]) > 0 and all(c in '.?' for c in tok[1:-1]))


def _resolve_unknown_hashes(msgs: list) -> list:
    """jt9 が解決できなかった <...> をQSOコンテキストの既知コールサインで置換。
    Type 4 メッセージで複合コールサインと組み合わさると my_call も <...> になるため。"""
    global _ft8_known_calls, _last_compound_dx

    # 今期のデコード済みコールサインをセッションキャッシュに追加
    for m in msgs:
        for tok in m["msg"].split():
            clean = tok.strip("<>")
            if clean and clean != "..." and not all(c in ".?" for c in clean):
                clean_upper = clean.upper()
                _ft8_known_calls.add(clean_upper)
                # 複合コールサイン（/付き）は最後にデコードされたものを記録
                if "/" in clean_upper:
                    _last_compound_dx = clean_upper

    qso_active = _qso.get("active", False)
    qso_dx = _qso.get("dx_call", "").upper()
    qso_my = _qso.get("my_call", "").upper() or _ft8_my_call.upper()

    # QSO外でも my_call が既知なら、複合コールサイン候補（/を含む）を探す
    compound_candidates = sorted(
        {c for c in _ft8_known_calls if "/" in c}, key=len
    ) if not qso_active else []

    result = []
    for m in msgs:
        parts = m["msg"].split()
        if len(parts) >= 2:
            p0 = parts[0].strip("<>").upper()
            p1 = parts[1].strip("<>").upper()
            uh0 = _is_unknown_hash(parts[0])
            uh1 = _is_unknown_hash(parts[1])
            if uh0 or uh1:
                print(f"[hash_resolve] msg={m['msg']!r} qso_active={qso_active} qso_dx={qso_dx!r} qso_my={qso_my!r} uh0={uh0} uh1={uh1} compound_cands={compound_candidates}")
            if qso_active:
                # position 0 が DX、position 1 が <...> → my_call で置換
                if qso_dx and qso_my and p0 == qso_dx and uh1:
                    m = dict(m); parts[1] = qso_my; m["msg"] = " ".join(parts)
                # position 0 が my_call、position 1 が <...> → dx_call で置換
                elif qso_my and qso_dx and p0 == qso_my and uh1:
                    m = dict(m); parts[1] = qso_dx; m["msg"] = " ".join(parts)
                # position 0 が <...>、position 1 が DX → my_call で置換
                elif qso_dx and qso_my and uh0 and p1 == qso_dx:
                    m = dict(m); parts[0] = qso_my; m["msg"] = " ".join(parts)
                # position 0 が <...>、position 1 が my_call → dx_call で置換
                elif qso_my and qso_dx and uh0 and p1 == qso_my:
                    m = dict(m); parts[0] = qso_dx; m["msg"] = " ".join(parts)
            elif qso_my and uh0 and p1 == qso_my and len(compound_candidates) == 1:
                # QSO外: position 0 が <...>、position 1 が my_call、複合候補が1局のみ
                m = dict(m); parts[0] = compound_candidates[0]; m["msg"] = " ".join(parts)
        result.append(m)
    return result


def _ft8_broadcast(data: dict):
    msg = ("data: " + _json.dumps(data, ensure_ascii=False) + "\n\n").encode()
    with _ft8_rx_clients_lock:
        dead = []
        for q in list(_ft8_rx_clients):
            try:
                q.put_nowait(msg)
            except _queue.Full:
                dead.append(q)
        for q in dead:
            try:
                _ft8_rx_clients.remove(q)
            except ValueError:
                pass


def _parse_jt9_output(stdout: str) -> list:
    """jt9 出力をパース。フォーマットはバージョンにより異なる。
    確認済みフォーマット例:
      UTC SNR  DT   FREQ [~] MESSAGE  (Raspberry Pi OS wsjtx)
      UTC  DT FREQ  SNR  [~] MESSAGE  (WSJT-X 標準)
    戦略:
      1. FREQ を先に特定 (整数 100–4000) — SNR 範囲と重複しない
      2. FREQ より前の整数から SNR を取得 (FREQ 前にあれば)
      3. なければ FREQ 直後の整数を SNR として取得 (標準フォーマット)
      4. メッセージは FREQ (+ SNR があれば) の後ろ、旗文字を除外
    """
    msgs = []
    for line in stdout.splitlines():
        parts = line.strip().split()
        if len(parts) < 4:
            continue
        try:
            # Step 1: FREQ を探す (index 1 以降の最初の整数 100–4000)
            freq_idx = -1
            for i in range(1, len(parts)):
                try:
                    iv = int(parts[i])
                    if 100 <= iv <= 4000:
                        freq_idx = i
                        break
                except ValueError:
                    pass
            if freq_idx < 0:
                continue
            freq = int(parts[freq_idx])

            # Step 2: SNR を探す (FREQ より前の整数 -40–+30)
            snr = None
            for i in range(1, freq_idx):
                try:
                    iv = int(parts[i])
                    if -40 <= iv <= 30:
                        snr = iv
                        break
                except ValueError:
                    pass

            # Step 3: FREQ 直後に SNR がある場合 (標準フォーマット)
            msg_start = freq_idx + 1
            if snr is None and msg_start < len(parts):
                try:
                    iv = int(parts[msg_start])
                    if -40 <= iv <= 30:
                        snr = iv
                        msg_start += 1
                except ValueError:
                    pass

            if snr is None:
                continue

            # Step 4: syncスコア (1桁整数) と旗文字 (~, ?, *) をスキップ
            # jt9 出力: "... FREQ  0 ~ MESSAGE" のように syncスコアが ~ の前に来ることがある
            while msg_start < len(parts):
                tok = parts[msg_start]
                if tok in ("~", "?", "*", "OOO"):
                    msg_start += 1
                elif len(tok) == 1 and tok.isdigit():
                    msg_start += 1  # sync score (0-9)
                else:
                    break
            msg = " ".join(parts[msg_start:])
            # jt9 によっては2デコード結果を ";" でつなげて1行出力するため分割
            for sub in msg.split(";"):
                sub = sub.strip()
                if sub:
                    msgs.append({"dt": 0.0, "freq": freq, "snr": snr, "msg": sub})
        except (ValueError, IndexError):
            continue
    return msgs


def _bandpass_wav(src: str, dst: str, flow: int, fhigh: int) -> bool:
    """WAV にバンドパスフィルタを適用して dst に書き込む。sox → numpy の順で試みる。"""
    # --- 方法1: sox (推奨、Pi に標準インストールされていることが多い) ---
    try:
        r = subprocess.run(
            ["sox", src, dst, "sinc", f"{flow}-{fhigh}"],
            capture_output=True, timeout=10.0
        )
        if r.returncode == 0 and os.path.exists(dst):
            return True
    except Exception:
        pass

    # --- 方法2: numpy FFT ---
    try:
        import numpy as np
        with wave.open(src, "rb") as wf:
            nch  = wf.getnchannels()
            rate = wf.getframerate()
            raw  = wf.readframes(wf.getnframes())
        data = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
        if nch > 1:
            data = data[::nch]
        N    = len(data)
        fft  = np.fft.rfft(data)
        bins = np.fft.rfftfreq(N, d=1.0 / rate)
        fft[(bins < flow) | (bins > fhigh)] = 0
        out  = np.clip(np.fft.irfft(fft, n=N), -32767, 32767).astype(np.int16)
        with wave.open(dst, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(rate)
            wf.writeframes(out.tobytes())
        return True
    except Exception as e:
        print(f"[bandpass] numpy failed: {e}")
        return False


def _ft8_jt9_worker(wav_path: str, period_n: int, utc_sec: int):
    """mfsk-decode によるFT8デコードをバックグラウンドで実行し、ストリーミングブロードキャスト。
    デコードが完了し次第 decode_msg を逐次送信 → Androidが徐々にデコード欄を埋める。"""
    global _ft8_last_decode_info
    _jt9_decode_done.clear()
    all_msgs = []
    try:
        cmd = [_MFSK_DECODE_BIN]

        # 自局・相手局コールサインをmfsk-decodeに渡してハッシュテーブルを事前構築
        my_call = _qso.get("my_call", "").strip() or _ft8_my_call.strip()
        dx_call = _qso.get("dx_call", "").strip() if _qso.get("active") else ""
        if not dx_call:
            dx_call = _last_compound_dx or next(
                (c for c in sorted(_ft8_known_calls) if "/" in c), "")
        if my_call:
            cmd += ["-c", my_call]
        if dx_call:
            cmd += ["-x", dx_call]
        # セッション中に確認された全コンパウンドコールをハッシュテーブルに追加
        for kc in sorted(_ft8_known_calls):
            if "/" in kc and kc != dx_call and kc != my_call:
                cmd += ["-k", kc]

        # SICラウンド数 (depth 1/2/3 → jt9 -d 相当)
        cmd += ["--sic-rounds", str(_ft8_decode_depth)]

        # フィルター帯域を mfsk-decode に直接渡す (jt9 と異なり WAV 前処理が不要)
        if _ft8_decode_filter:
            centers = [c for c, _ in _ft8_decode_filter]
            rngs    = [r for _, r in _ft8_decode_filter]
            flow    = max(100,  min(centers) - max(rngs) * 2)
            fhigh   = min(3000, max(centers) + max(rngs) * 2)
            cmd += ["--freq-min", str(flow), "--freq-max", str(fhigh)]

        cmd.append(wav_path)
        t1 = time.time()
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            for line in proc.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    m = _json.loads(line)
                    # post-filter: フィルター帯域外を除去
                    if _ft8_decode_filter and not any(
                        abs(m.get("freq", 0) - center) <= rng
                        for center, rng in _ft8_decode_filter
                    ):
                        continue
                    # <...> ハッシュをQSOコンテキストで解決
                    resolved = _resolve_unknown_hashes([m])
                    m = resolved[0]
                    # 逐次ブロードキャスト: デコード完了した局から順に送信
                    _ft8_broadcast({
                        "type": "decode_msg",
                        "period": period_n,
                        "utc_sec": utc_sec,
                        "freq": int(round(m.get("freq", 0))),
                        "snr":  int(round(m.get("snr",  0))),
                        "dt":   round(m.get("dt", 0.0), 2),
                        "msg":  m.get("msg", ""),
                    })
                    all_msgs.append(m)
                except Exception as e:
                    print(f"[mfsk_decode] parse: {e!r} line={line!r}")
            proc.wait(timeout=5.0)
        except subprocess.TimeoutExpired:
            proc.kill()

        msgs = all_msgs
        elapsed = time.time() - t1
        print(f"[mfsk_decode] took {elapsed:.2f}s period={period_n} utc={utc_sec}s decoded={len(msgs)}")
        _ft8_last_decode_info = {"decoded": len(msgs), "period": period_n}
        _ft8_broadcast({
            "type": "decode_done",
            "period": period_n,
            "utc_sec": utc_sec,
            "count": len(msgs),
        })
        # QSO state machine: DX 返信を検出して AUTO TX のメッセージを次ステップへ更新
        # FT8 message format: DESTCALL SRCCALL REPORT (first word = recipient)
        if _qso["active"] and msgs:
            dx = _qso["dx_call"]
            me = _qso["my_call"]
            state = _qso["state"]
            # 自分宛の DX メッセージを全部取り出し、SNR 最強のものを使う
            dx_msgs = [m for m in msgs
                       if len(m["msg"].split()) >= 2
                       and m["msg"].upper().split()[0] == me
                       and m["msg"].upper().split()[1] == dx]
            dx_best  = max(dx_msgs, key=lambda m: m.get("snr", -99)) if dx_msgs else None
            dx_reply = dx_best["msg"].upper().split() if dx_best else None

            # QSO 中に受信した DX の SNR を step2 メッセージに反映（改善時のみ更新）
            if dx_best is not None:
                cur_snr = dx_best.get("snr", -99)
                peak    = _qso.get("snr_dx_peak")
                if peak is None or cur_snr > peak:
                    _qso["snr_dx_peak"] = cur_snr
                    snr_str = f"+{cur_snr:02d}" if cur_snr >= 0 else f"{cur_snr:03d}"
                    parts = _qso["step2_msg"].split()
                    if len(parts) >= 3:
                        # CQ発信側はRなし、DX指定呼び出し側はR付き
                        parts[2] = snr_str if _qso.get("cq_sender") else f"R{snr_str}"
                        _qso["step2_msg"] = " ".join(parts)
                        _qso["snr_sent"]  = parts[2]
                        # state 3 はすでに step2 を繰り返し送信中 → AUTO TX も即時更新してUIに通知
                        if state == 3:
                            _auto_tx["msg"] = _qso["step2_msg"]
                            _ft8_broadcast({"type": "qso_state", "state": "snr_update",
                                            "msg": _qso["step2_msg"],
                                            "dx_call": dx, "my_call": me, "tx_mode": _qso["tx_mode"]})
                    # step_snr_msg (Rなし SNR ステップ) も同様に更新
                    snr_parts = _qso.get("step_snr_msg", "").split()
                    if len(snr_parts) >= 3:
                        snr_parts[2] = snr_str
                        _qso["step_snr_msg"] = " ".join(snr_parts)
                        if state == 2:
                            _auto_tx["msg"] = _qso["step_snr_msg"]
                            _ft8_broadcast({"type": "qso_state", "state": "snr_update",
                                            "msg": _qso["step_snr_msg"],
                                            "dx_call": dx, "my_call": me, "tx_mode": _qso["tx_mode"]})

            if state == 1 and dx_reply:
                # step1 送信後 → DX の SNR レポート受信でのみ次へ進む
                snr = dx_reply[2] if len(dx_reply) >= 3 else ""
                snr_is_report = snr.lstrip("+-").isdigit()
                if snr_is_report:
                    _qso["snr_rcvd"] = snr
                    # 呼びかけ側: GRID → R-SNR と交互に送信（step_snr_msg はスキップ）
                    # step_snr_msg は initital_state=1 で明示的に開始した場合のみ使用
                    nxt = _qso["step2_msg"] if _qso["step2_msg"] else _qso["step3_msg"]
                    new_state = 3 if _qso["step2_msg"] else 5
                    _qso["next_msg"] = nxt
                    _qso["state"] = new_state
                    _auto_tx["msg"] = nxt
                    _ft8_broadcast({"type": "qso_state", "state": "got_report",
                                    "msg": nxt, "snr": snr,
                                    "dx_call": dx, "my_call": me, "tx_mode": _qso["tx_mode"]})
                elif snr in ("RR73", "73"):
                    # DXがRR73/73を先送り（step2をスキップ）→ 73で即応答
                    step4 = _qso.get("step4_msg", "")
                    nxt = step4 if step4 else f"{dx} {me} 73"
                    _qso["state"] = 6
                    _qso["next_msg"] = nxt
                    _auto_tx["msg"] = nxt
                    _ft8_broadcast({"type": "qso_state", "state": "sending_73", "msg": nxt,
                                    "dx_call": dx, "my_call": me,
                                    "snr_rcvd": _qso.get("snr_rcvd", ""),
                                    "snr_sent": _qso.get("snr_sent", "")})
                # else: グリッド等は無視 → step1 を再送し続ける
            elif state == 2 and dx_reply:
                # step_snr_msg(Rなし SNR)送信後 → DX の応答を処理
                word3 = dx_reply[2] if len(dx_reply) >= 3 else ""
                if word3 in ("RR73", "73"):
                    step4 = _qso.get("step4_msg", "")
                    nxt = step4 if step4 else f"{dx} {me} 73"
                    _qso["state"] = 6
                    _qso["next_msg"] = nxt
                    _auto_tx["msg"] = nxt
                    _ft8_broadcast({"type": "qso_state", "state": "sending_73", "msg": nxt,
                                    "dx_call": dx, "my_call": me,
                                    "snr_rcvd": _qso.get("snr_rcvd", ""),
                                    "snr_sent": _qso.get("snr_sent", "")})
                elif len(word3) >= 2 and word3[0] == "R" and word3[1:].lstrip("+-").isdigit():
                    # R-SNR 受信 → RR73へ進む（Rなし SNR に対してDXがR付き返信 = レポート交換成立）
                    nxt = _qso["step3_msg"]
                    _qso["state"] = 5
                    _qso["next_msg"] = nxt
                    _auto_tx["msg"] = nxt
                    _ft8_broadcast({"type": "qso_state", "state": "got_report",
                                    "msg": nxt, "snr": word3,
                                    "dx_call": dx, "my_call": me, "tx_mode": _qso["tx_mode"]})
                # else: 無視 → step_snr_msg を再送し続ける
            elif state == 3 and dx_reply:
                # step2 送信後の応答を処理
                word3 = dx_reply[2] if len(dx_reply) >= 3 else ""
                if word3 in ("RR73", "73"):
                    # DXがRR73/73送信 → 73で応答（CQ側・DX指定側共通）
                    step4 = _qso.get("step4_msg", "")
                    nxt = step4 if step4 else f"{dx} {me} 73"
                    _qso["next_msg"] = nxt
                    _qso["state"] = 6
                    _auto_tx["msg"] = nxt
                    _ft8_broadcast({"type": "qso_state", "state": "sending_73", "msg": nxt,
                                    "dx_call": dx, "my_call": me,
                                    "snr_rcvd": _qso["snr_rcvd"],
                                    "snr_sent": _qso["snr_sent"]})
                elif (len(word3) >= 2 and word3[0] == "R"
                      and word3[1:].lstrip("+-").isdigit()):
                    # DXからR-SNRが返ってきた → RR73を送信（CQ側・DX指定側共通）
                    nxt = _qso["step3_msg"]
                    _qso["state"] = 5
                    _qso["next_msg"] = nxt
                    _auto_tx["msg"] = nxt
                    _ft8_broadcast({"type": "qso_state", "state": "retry_rr73", "msg": nxt,
                                    "dx_call": dx, "my_call": me,
                                    "snr_rcvd": _qso["snr_rcvd"],
                                    "snr_sent": _qso["snr_sent"]})
                # else: SNR再送・グリッド等は無視 → step2 を再送し続ける
            elif state == 5 and dx_reply:
                # step3(RR73) 送信後 → 73/RR73 で即QSO完了（こちらから73は不要）
                word3 = dx_reply[2] if len(dx_reply) >= 3 else ""
                if word3 in ("73", "RR73"):
                    _qso["active"] = False
                    _qso["state"] = 6
                    _auto_tx["active"] = False
                    _ft8_broadcast({"type": "qso_done", "dx_call": dx, "my_call": me,
                                    "snr_rcvd": _qso["snr_rcvd"],
                                    "snr_sent": _qso["snr_sent"]})
                else:
                    _auto_tx["msg"] = _qso["step3_msg"]  # RR73 再送
                    _ft8_broadcast({"type": "qso_state", "state": "retry_rr73",
                                    "msg": _qso["step3_msg"],
                                    "dx_call": dx, "my_call": me, "tx_mode": _qso["tx_mode"]})
        # CQ AUTO: QSO未開始のとき、自分宛の返信を検出したら自動QSO開始
        if _cq_auto.get("active") and not _qso.get("active") and msgs:
            me_cq = _cq_auto["my_call"].upper()
            cq_replies = [m for m in msgs
                          if len(m["msg"].split()) >= 2
                          and m["msg"].upper().split()[0] == me_cq]
            if cq_replies:
                reply  = cq_replies[0]   # 最初に引っかかった局
                parts  = reply["msg"].upper().split()
                dx_call = parts[1]
                snr    = reply.get("snr", 0)
                snr_str = f"+{snr:02d}" if snr >= 0 else f"{snr:03d}"
                step2  = f"{dx_call} {me_cq} {snr_str}"   # CQ発信側: Rなし
                step3  = f"{dx_call} {me_cq} RR73"
                _qso["active"]     = True
                _qso["dx_call"]    = dx_call
                _qso["my_call"]    = me_cq
                _qso["state"]      = 3   # step2送信待ち
                _qso["tx_mode"]    = _cq_auto["mode"]
                _qso["audio_freq"] = _cq_auto["audio_freq"]
                _qso["step1_msg"]  = ""
                _qso["step2_msg"]  = step2
                _qso["step3_msg"]  = step3
                _qso["step4_msg"]  = ""   # CQ発信側: DXの73受信後は73送らずCQ再開
                _qso["next_msg"]   = step2
                _qso["snr_rcvd"]   = str(snr)
                _qso["snr_sent"]   = snr_str   # CQ発信側: Rなし
                _qso["snr_dx_peak"]= snr
                _qso["cq_sender"]  = True
                _auto_tx["active"]    = True
                _auto_tx["msg"]       = step2
                _auto_tx["mode"]      = _cq_auto["mode"]
                _auto_tx["audio_freq"]= _cq_auto["audio_freq"]
                print(f"[cq_auto] auto QSO: {dx_call} snr={snr}")
                _ft8_broadcast({"type": "qso_state", "state": "got_report",
                                "dx_call": dx_call, "msg": step2,
                                "tx_mode": _cq_auto["mode"]})
    except Exception as e:
        print(f"[mfsk_decode] worker error: {e}")
    finally:
        _jt9_decode_done.set()
        try: os.unlink(wav_path)
        except Exception: pass


def _ft8_auto_tx_worker(msg: str, audio_freq: int, period_utc: int = -1):
    """AutoTX: FT8メッセージをPCM生成→aplayで送出する。"""
    global _ft8_tx_active
    if _ft8_tx_active:
        return
    # 並行jt9デコードの完了を待ってから最新メッセージを読む（最大2秒）
    # jt9が終わっていればイベントはセット済みなので即リターン
    _jt9_decode_done.wait(timeout=2.0)
    # デコード結果でQSOステップが進んだ場合は更新後のメッセージを使う
    if _auto_tx.get("active") and _auto_tx.get("msg"):
        msg = _auto_tx["msg"]
    if not _auto_tx.get("active") or not msg:
        return
    _ft8_tx_active = True
    # period_utc が渡されていればそれを使う（time.time()の再計算でズレない）
    tx_utc = period_utc if period_utc >= 0 else (int(time.time()) // 15 * 15 % 60)
    print(f"[auto_tx] TX start: utc={tx_utc}s period_n={(tx_utc//15)%4} mode={_auto_tx['mode']} msg={msg!r}")
    _ft8_broadcast({"type": "tx_pending", "msg": msg,
                    "wait_sec": 0, "utc_at_tx": tx_utc})
    _mgr_sub.mute(14.0)
    try:
        subprocess.run(["pkill", "-9", "aplay"], capture_output=True)
        time.sleep(0.1)
        rigctl_cmd("T 1")
        time.sleep(0.3)
        symbols = _ft8_get_symbols(msg)
        pcm_data = _ft8_symbols_to_pcm(symbols, audio_freq, 12000)
        proc = subprocess.Popen(
            ["aplay", "-D", _alsa_playback_dev, "-f", "S16_LE", "-r", "12000", "-c", "1"],
            stdin=subprocess.PIPE, stderr=subprocess.PIPE
        )
        proc.stdin.write(pcm_data)
        proc.stdin.close()
        proc.wait(timeout=20)
        _ft8_broadcast({"type": "auto_tx_sent", "msg": msg, "utc_at_tx": tx_utc})
        # QSO state 6: 73(step4)を実際に送信した場合のみ完了とする
        # TX開始後にデコードでstate=6になったとき73はまだ未送信なので次のTXで送る
        if _qso.get("active") and _qso.get("state") == 6:
            dx_done = _qso["dx_call"]
            me_done = _qso["my_call"]
            snr_r   = _qso.get("snr_rcvd", "")
            snr_s   = _qso.get("snr_sent", "")
            step4   = _qso.get("step4_msg", "")
            done_msg = (step4 if step4 else f"{dx_done} {me_done} 73").upper().strip()
            if msg.upper().strip() == done_msg:
                _qso["active"] = False
                _auto_tx["active"] = False
                _ft8_broadcast({"type": "qso_done",
                                "dx_call": dx_done, "my_call": me_done,
                                "snr_rcvd": snr_r, "snr_sent": snr_s})
            # else: 73以外を送信済み → _auto_tx["msg"]の73を次のTXピリオドで送る
    except Exception as e:
        print(f"[auto_tx] error: {e}")
        _ft8_broadcast({"type": "auto_tx_error", "error": str(e)})
    finally:
        rigctl_cmd("T 0")
        _ft8_tx_active = False


def _ft8_decode_loop():
    global _ft8_decode_running
    print("[ft8_decode] started")
    while _ft8_decode_running:
        # 次のFT8ピリオド境界まで待機
        # 注: 録音直後は now がピリオド境界に非常に近い（例: 30.004s）ため
        # (int(now/15)+1)*15 だと次の次（45s）に飛ばしてしまう。
        # 現在ピリオドの開始から 0.5s 以内なら そのピリオドをそのまま処理する。
        now = time.time()
        current_start = int(now / FT8_PERIOD_S) * FT8_PERIOD_S
        elapsed = now - current_start
        if elapsed < 0.5:
            next_start = current_start   # このピリオドをすぐ処理
        else:
            next_start = current_start + FT8_PERIOD_S
        wait = next_start - now
        if wait > 0.05:
            time.sleep(wait - 0.05)
        while time.time() < next_start:
            time.sleep(0.002)

        if not _ft8_decode_running:
            break

        period_start = time.time()
        utc_sec  = int(period_start) % 60
        period_n = (utc_sec // 15) % 4

        # ピリオド開始を即座に通知（ウォーターフォールの横線タイミング用）
        _ft8_broadcast({"type": "period_start", "period": period_n, "utc_sec": utc_sec})

        # QSO 送信は AUTO TX に委譲（_auto_tx["msg"] を更新して AUTO TX が送信）

        # AutoTX: このピリオドがTX担当なら送信して次ピリオドへ
        if _auto_tx["active"] and not _ft8_tx_active and _auto_tx["msg"]:
            is_even = period_n % 2 == 0
            should_tx = (is_even and _auto_tx["mode"] == "even") or                         (not is_even and _auto_tx["mode"] == "odd")
            if should_tx:
                threading.Thread(
                    target=_ft8_auto_tx_worker,
                    args=(_auto_tx["msg"], _auto_tx["audio_freq"], utc_sec),
                    daemon=True
                ).start()
                # 次のピリオド境界まで待機（continueするとelapsed<0.5でスピンループになりSSEキューが溢れる）
                _wait = period_start + FT8_PERIOD_S - time.time()
                if _wait > 0:
                    time.sleep(_wait)
                continue

        if _ft8_tx_active:
            print(f"[ft8_decode] skip period={period_n} (TX active)")
            # 次のピリオド境界まで待機（スピンループ防止）
            _wait = period_start + FT8_PERIOD_S - time.time()
            if _wait > 0:
                time.sleep(_wait)
            continue

        # _mgr_sub を購読して15秒分の12kHz PCMを収集
        _mgr_sub.ensure("12000")
        sid, q = _mgr_sub.subscribe(maxsize=512)
        pcm = bytearray()
        deadline = period_start + FT8_PERIOD_S
        try:
            while len(pcm) < FT8_BYTES and _ft8_decode_running:
                rem = deadline - time.time()
                if rem <= 0:
                    break
                try:
                    chunk = q.get(timeout=min(0.5, rem + 0.1))
                    pcm.extend(chunk)
                except _queue.Empty:
                    if time.time() >= deadline:
                        break
        finally:
            _mgr_sub.unsubscribe(sid)

        if not _ft8_decode_running or len(pcm) < FT8_BYTES // 4:
            continue

        # WAVに書いてjt9をバックグラウンドスレッドで起動（次ピリオドの録音をブロックしない）
        # ※ d=3 時は jt9 が30秒超かかるため、スレッド化しないと全4ピリオドのうち
        #   半分しかキャプチャできない（15s/45sしかデコードされない問題の根本原因）
        fd, wav_path = -1, ""
        try:
            fd, wav_path = tempfile.mkstemp(suffix=".wav")
            os.close(fd); fd = -1

            pcm_raw = bytes(pcm[:FT8_BYTES])
            try:
                import numpy as _np
                _smp = _np.frombuffer(pcm_raw, dtype=_np.int16).astype(_np.float32)
                _peak = float(_np.max(_np.abs(_smp)))
                if _peak > 16384.0:
                    _smp = _smp * (16384.0 / _peak)
                pcm_write = _np.clip(_smp, -32768, 32767).astype(_np.int16).tobytes()
            except Exception:
                pcm_write = pcm_raw

            with wave.open(wav_path, "wb") as wf:
                wf.setnchannels(1)
                wf.setsampwidth(2)
                wf.setframerate(FT8_SAMPLE_RATE)
                wf.writeframes(pcm_write)

            _wav_for_thread = wav_path
            wav_path = ""  # worker がファイル削除担当
            threading.Thread(
                target=_ft8_jt9_worker,
                args=(_wav_for_thread, period_n, utc_sec),
                daemon=True
            ).start()
        except Exception as e:
            print(f"[ft8_decode] wav write error: {e}")
        finally:
            if fd >= 0:
                try: os.close(fd)
                except Exception: pass
            if wav_path:
                try: os.unlink(wav_path)
                except Exception: pass

    print("[ft8_decode] stopped")

rig_lock = threading.Lock()
vfo_toggle_lock = threading.Lock()  # /radio/vfo_toggle連打対策の排他ロック(rig_lockとは別)
radio_cache = {
    "freq": 0,
    "mode": "",
    "width": 0,
    "signal": 0.0,
    "tx": False,
    "power": 0.0,
    "sql": 0.0,
    "bk_in": 0,
    "rig_wpm": 0
}

current_model = None
current_cat = None
current_baud = None
current_ptt = ""
current_ptt_type = "RIG"
poll_started = False
poll_enabled = True
tx_in_progress = False
last_user_freq_change = 0
last_user_mode_change = 0
last_heartbeat = time.time()
last_ptt_state = 0

rigctld_process = None
_rigctld_restarting = False
_timeout_streak = 0  # poll_rig タイムアウト連続回数（グローバル化してリスタート時にリセット）
_rigctld_restart_lock = threading.Lock()

# ---------------------------------------------------------------------------
# UDP ブロードキャストによるデバイス発見 (ポート 5001)
# Android アプリの「スキャン」ボタンからの検索要求に応答する
# ---------------------------------------------------------------------------
_UDP_DISCOVERY_PORT = 5001

def _udp_discovery_server():
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('', _UDP_DISCOVERY_PORT))
        hostname = socket.gethostname()
        api_port   = int(os.environ.get("API_PORT",   8210))
        audio_port = int(os.environ.get("AUDIO_PORT", 8211))
        resp = f"WIFI_RIG_CTRL_HERE:{hostname}:{api_port}:{audio_port}".encode()
        print(f"[discovery] UDP listening on port {_UDP_DISCOVERY_PORT}", flush=True)
        while True:
            try:
                data, addr = sock.recvfrom(256)
                if data.strip() == b"WIFI_RIG_CTRL_DISCOVER":
                    sock.sendto(resp, addr)
            except Exception:
                pass
    except Exception as e:
        print(f"[discovery] error: {e}", flush=True)

threading.Thread(target=_udp_discovery_server, daemon=True).start()

# ---- VFO A/B or MAIN/SUB 判定(機種選択・接続のたびにrigctld再起動後に自動検出) ----
rig_vfo_mode = None  # "mainsub" | "ab" | None(未検出)

# ---- 機種固有のモード一覧(dump_capsの"Mode list:"から検出。C4FM/DSTAR等はここに含まれる) ----
current_mode_list = None  # 検出できるまではNone → /radio/modes等は静的な汎用リストにフォールバック
_FALLBACK_MODE_LIST = ["LSB", "USB", "CW", "CWR", "AM", "FM", "DIGL", "DIGU", "PKTLSB", "PKTUSB", "PKTFM"]

# Hamlibが理論値として報告するが実際のアマチュア無線機では使用できないモード。
# /radio/caps の mode list から除外してアプリのモード選択を整理する。
_HAMLIB_NOISE_MODES = {
    "ECSSUSB", "ECSSLSB",          # ECSS (Exalted Carrier SS) — 実機非対応
    "FAX",                           # HF FAX — アマチュア機では通常使用しない
    "SAM", "SAL", "SAH",             # Synchronous AM variants — 実機非対応
    "DSB",                           # Double Sideband — 実機非対応
    "AM-D", "AMN", "AMS",            # AM variants — 実機非対応
    "P25", "DPMR", "NXDN-VN", "NXDN-N", "DCR",  # 業務用デジタル規格 — アマチュア機非対応
    "PSK", "PSKR",                   # PSK31等はPKTUSB/PKTLSBで運用するため除外
}


_last_tx_debug: dict = {"status": "none", "aplay_rc": None, "aplay_err": "", "chunks": 0, "dev": ""}

aprs_running = False
aprs_seq = 0  # /aprs_start・/aprs_stop のたびに増分。バックグラウンドのCAT設定ワーカーが
              # 古い(=すでに上書きされた)要求で無線機を再度ONに戻してしまうのを防ぐ。
_rig_tx_latch_until = 0.0  # FTX-1内蔵モデムのAUTO BEACON送信(1秒未満で終わる)をM5側の
                            # ポーリングが確実に拾えるよう、検知後しばらくTX表示を保持する
aprs_thread = None
aprs_last_heartbeat = 0
aprs_freq = None
aprs_interval = None
normal_freq = None
aprs_use_gps = True
aprs_manual_lat = 0.0
aprs_manual_lon = 0.0
aprs_cfg = None
_last_rig_aprs_key: tuple | None = None  # (freq, baud, modem_sel) — 前回適用済みの値

# ---- APRS受信(ビーコン受信表示) ----
# ★ 受信はTX方式(DireWolf/FTX-1内蔵モデム)とは無関係に、常にPiのUSBオーディオを
#   direwolfでデコードして拾う。DireWolf-TX中はTX用direwolfがそのまま受信も兼ねる。
#   FTX-1-TX中/APRS停止中は、PTT設定なしの受信専用direwolfを裏で起動しておく。
aprs_rx_running = False
aprs_rx_thread = None
aprs_heard_lock = threading.Lock()
aprs_heard = []  # [{call, path, lat, lon, symbol, comment, heard_at}, ...] 新しい順
_aprs_direwolf_conf_kind = None  # (kind, baud) kind="tx"|"rx" — 直近に書いたdirewolf.confの種類

# ---- APRS受信通知(無線機のような「ビーコン受信」ポップアップ用) ----
aprs_notify_suppress_sec = 600  # 同一局の通知を再度出すまでの最短間隔。/aprs_configで更新される
_aprs_notify_last = {}  # call -> 直近に通知した時刻(epoch)
aprs_notify_queue = []          # M5用通知キュー(/aprs_notifyで払い出して消費)
aprs_notify_queue_android = []  # Android用通知キュー(/aprs_notify_androidで払い出して消費)

tx_started = False
tx_done = False
tx_watch_thread = None
tx_watch_running = False

_ft8_tx_active = False

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

# ─── ffmpeg サブスクライバーパターン ───
# メイン(SPK)とFT8が独立したキューを持ち、同一プロセスの出力をファンアウトする。
# 異なるレート/フィルターは別 _FfmpegMgr インスタンスで管理する。

class _FfmpegMgr:
    """1つの ffmpeg プロセスを管理し、複数サブスクライバーへブロードキャストする。
    購読者がゼロになると idle_stop_sec 秒後に ffmpeg を停止し ALSA を解放する。"""
    def __init__(self, af: str = None, kill_direwolf: bool = False, idle_stop_sec: int = 5,
                 low_latency: bool = False, use_arecord: bool = False):
        self._af = af
        self._kill_direwolf = kill_direwolf
        self._low_latency = low_latency
        self._use_arecord = use_arecord
        self.proc = None
        self._rate = None
        self._device = None
        self._lock = threading.Lock()
        self._subs_lock = threading.Lock()
        self._subs: dict = {}
        self._idle_stop_sec = idle_stop_sec
        self._idle_timer = None
        self._mute_until = 0.0
        self.capture_dev_override = ""  # "" = use global _alsa_capture_dev

    def subscribe(self, maxsize: int = 32):
        q = _queue.Queue(maxsize=maxsize)
        sid = id(q)
        with self._subs_lock:
            if self._idle_timer:
                self._idle_timer.cancel()
                self._idle_timer = None
            self._subs[sid] = q
        return sid, q

    def unsubscribe(self, sid):
        with self._subs_lock:
            self._subs.pop(sid, None)
            if not self._subs and self._idle_stop_sec > 0:
                t = threading.Timer(self._idle_stop_sec, self._idle_stop)
                t.daemon = True
                self._idle_timer = t
                t.start()

    def _idle_stop(self):
        with self._subs_lock:
            if self._subs:
                return
            self._idle_timer = None
        self.stop()

    def mute(self, sec: float):
        self._mute_until = time.time() + sec

    def stop(self):
        with self._lock:
            p = self.proc
            if p and p.poll() is None:
                try:
                    os.killpg(os.getpgid(p.pid), signal.SIGTERM)
                    try:
                        p.wait(timeout=0.5)
                    except subprocess.TimeoutExpired:
                        os.killpg(os.getpgid(p.pid), signal.SIGKILL)
                        p.wait(timeout=0.5)
                except Exception:
                    pass
            self.proc = None
            self._rate = None
            self._device = None

    def _active_capture_dev(self):
        return self.capture_dev_override if self.capture_dev_override else _alsa_capture_dev

    def ensure(self, rate: str):
        with self._lock:
            dev = self._active_capture_dev()
            if (self.proc and self.proc.poll() is None
                    and self._rate == rate
                    and self._device == dev):
                return self.proc
            return self._start(rate)

    def _start(self, rate: str):
        dev = self._active_capture_dev()
        old = self.proc
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
        if self._kill_direwolf:
            subprocess.run(["pkill", "-9", "direwolf"], capture_output=True)
            time.sleep(0.05)
        if self._use_arecord:
            # arecord | sox パイプライン
            # ffmpegより起動が速い (Pi Zero: ~0.2s vs ~1s)
            # soxでvolume=8.0相当の音量増幅を維持
            # 要: sudo apt-get install sox
            import re as _re
            m = _re.search(r'volume=(\d+(?:\.\d+)?)', self._af or '')
            vol = m.group(1).rstrip('0').rstrip('.') if m else '1'
            cmd_str = (f"arecord -D '{dev}' -f S16_LE -r {rate} -c 1 -t raw | "
                       f"sox -t raw -r {rate} -e signed -b 16 -c 1 - "
                       f"-t raw -r {rate} -e signed -b 16 -c 1 - vol {vol}")
            proc = subprocess.Popen(cmd_str, shell=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, start_new_session=True)
            self.proc = proc
            self._rate = rate
            self._device = dev
            threading.Thread(target=self._reader, args=(proc,), daemon=True).start()
            return proc
        tqs = "64" if self._low_latency else "512"
        cmd = [
            "ffmpeg", "-f", "alsa", "-thread_queue_size", tqs,
            "-ar", rate, "-i", dev, "-ac", "1",
        ]
        if self._af:
            cmd += ["-af", self._af]
        extra = ["-fflags", "+nobuffer"] if self._low_latency else []
        cmd += extra + [
            "-f", "s16le", "-acodec", "pcm_s16le",
            "-nostdin", "-vn", "-sn", "-dn", "-map", "0:a",
            "-flush_packets", "1", "-nostats", "pipe:1"
        ]
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                start_new_session=True)
        self.proc = proc
        self._rate = rate
        self._device = dev
        threading.Thread(target=self._reader, args=(proc,), daemon=True).start()
        return proc

    def _reader(self, proc):
        fd = proc.stdout.fileno() if self._low_latency else None
        chunk = 512 if self._low_latency else 4096
        while proc.poll() is None:
            try:
                r, _, _ = select.select([proc.stdout], [], [], 0.1)
                if r:
                    data = os.read(fd, chunk) if fd is not None else proc.stdout.read(chunk)
                    if not data:
                        break
                    with self._subs_lock:
                        out = bytes(len(data)) if time.time() < self._mute_until else data
                        for q in list(self._subs.values()):
                            while q.full():
                                try:
                                    q.get_nowait()
                                except _queue.Empty:
                                    break
                            try:
                                q.put_nowait(out)
                            except _queue.Full:
                                pass
            except Exception:
                break

# ── ノイズリダクション設定 ──────────────────────────────────
_noise_reduction_level = 0  # 0=OFF, 1=Light, 2=Medium, 3=Strong, 4=Stronger, 5=Max
_cw_decode_active = False    # True時はafftdnをスキップしバンドパスのみ適用（CWタイミング保護）

def _build_rx_af(level: int) -> str:
    base = "highpass=f=300,lowpass=f=4000,volume=10.0"
    # NR ON時: ノイズ除去後にダイナミック圧縮で弱い信号を持ち上げる
    enhance = ",acompressor=threshold=-20dB:ratio=3:attack=5:release=50"
    if level == 0:
        return base
    # CWデコード中: afftdnはキーON/OFFのトランジェントを遅延させCWタイミングを歪める。
    # baseのhighpass/lowpassがバンドパスNRとして機能するためafftdnをスキップする。
    if _cw_decode_active:
        return base + enhance
    nr = {
        1: ",afftdn=nf=-30:nr=15",
        2: ",afftdn=nf=-25:nr=20",
        3: ",afftdn=nf=-20:nr=25:tn=1",
        4: ",afftdn=nf=-20:nr=33:tn=1",
        5: ",afftdn=nf=-20:nr=40:tn=1",
    }
    return base + nr.get(level, "") + enhance

# メイン音声: SPK用フィルター+音量ブースト、direwolf停止あり
_mgr_rx  = _FfmpegMgr(af=_build_rx_af(0), kill_direwolf=True)
# FT8音声: arecord|sox パイプライン（ffmpegより起動が速い: ~0.2s vs ~1.5s）
# 全二重失敗でarecordが死んでも ~200ms で復旧できる
_mgr_sub = _FfmpegMgr(af="volume=8.0", kill_direwolf=False, idle_stop_sec=0, low_latency=True, use_arecord=True)

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
    comment: str = ""
    destination: str
    sound_device: str
    rig_id: str
    cat_device: str
    use_rig_modem: bool = False  # true: FTX-1内蔵APRSモデム(CAT) / false: DireWolf(Pi)
    modem_sel: int = 2  # 1:AUTO 2:MAIN 3:SUB (use_rig_modem時のみ意味を持つ)
    enabled: bool = True  # APRS機能そのもののON/OFF(M5のAPRS Enabledトグル)。
                           # falseの間は受信デコード用direwolfも止める。
    heard_suppress_sec: int = 600  # 同一局の受信通知を再度出すまでの最短間隔(秒)


class AprsStart(BaseModel):
    freq: float
    interval: int


def _ts() -> str:
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]

def _rigctl_log(tag: str, cmd: str, raw: str):
    ts = _ts()
    # PTT変化は目立つよう [PTT] タグを追加
    c = cmd.strip()
    if c in ("T 0", "T 1"):
        state = "ON" if c == "T 1" else "OFF"
        print(f"[{ts}] *** [PTT {state}] {tag} cmd='{c}' -> '{raw[:60]}'", flush=True)
    elif c == "t":
        # PTT問い合わせは詳細ログ抑制（毎秒呼ばれるため）
        pass
    else:
        print(f"[{ts}] [{tag}] '{c}' -> '{raw[:60]}'", flush=True)


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
            _rigctl_log("rigctl", cmd, raw)
            return raw
        except socket.timeout:
            print(f"[{_ts()}] [rigctl] timeout: '{cmd}'", flush=True)
            return ""
        except Exception as e:
            print(f"[{_ts()}] [rigctl] error '{cmd}': {e}", flush=True)
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
        _rigctl_log("rigctl_prio", cmd, raw)
        return raw
    except socket.timeout:
        print(f"[{_ts()}] [rigctl_prio] timeout: '{cmd}'", flush=True)
        return ""
    except Exception as e:
        print(f"[{_ts()}] [rigctl_prio] error '{cmd}': {e}", flush=True)
        return ""
    finally:
        if s:
            try:
                s.close()
            except Exception:
                pass


def _rig_send_no_reply(cmd: str) -> str:
    """FTX-1のEX/FA/FB等のSETコマンド専用。実機で確認したところ、これらのコマンドは
    CAT応答を一切返さない(rigctl_cmd_priorityの2秒タイムアウトを毎回使い切っていた)。
    コマンド自体はsendall()の時点で無線機に届いているため、短いタイムアウトで即座に
    諦めても実害はなく、AP96/AP12切替(7コマンド連続)の待ち時間を大幅に短縮できる。"""
    s = None
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(0.3)
        s.connect(("localhost", 4532))
        s.sendall((cmd + "\n").encode())
        try:
            data = s.recv(4096)
            raw = data.decode(errors="replace").strip()
        except socket.timeout:
            raw = ""
        _rigctl_log("rig_fast", cmd, raw)
        return raw
    except Exception as e:
        print(f"[{_ts()}] [rig_fast] error '{cmd}': {e}", flush=True)
        return ""
    finally:
        if s:
            try:
                s.close()
            except Exception:
                pass


def _rig_get_vfo_freq(vfo_cmd: str):
    """MAIN(FA)/SUB(FB)VFOの周波数を素のCATクエリ(パラメータなし)で直接取得する。
    実機で確認: "w FA;"/"w FB;"(問い合わせ)は"FA432680000;"のように応答が返る
    (パラメータ付きのSET形"w FAnnnnnnnnn;"は無応答なのと対照的、_rig_send_no_reply参照)。
    HamlibのVFO抽象化("f"コマンド)はMain/Sub機でraw VS切替後の実際の動作VFOを
    追従しないため(常にMain側の周波数を返す)、mainsub機種ではこちらを使う。"""
    s = None
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(1.0)
        s.connect(("localhost", 4532))
        s.sendall(f"w {vfo_cmd};\n".encode())
        # ★ 実機の応答が複数回のTCP書き込みに分かれて届くことがあり、recv()を1回だけ
        #   呼ぶと最初の空/断片データしか拾えず無反応に見えることがあった。応答の終端
        #   ";"が現れるか1秒のデッドラインまで、受信バッファに読み溜める。
        buf = b""
        deadline = time.time() + 1.0
        while time.time() < deadline:
            try:
                chunk = s.recv(4096)
            except socket.timeout:
                break
            if not chunk:
                break
            buf += chunk
            if b";" in buf:
                break
        raw = buf.decode(errors="replace").strip()
        if raw.startswith(vfo_cmd):
            digits = raw[len(vfo_cmd):].split(";")[0].strip()
            if digits.isdigit():
                return int(digits)
        else:
            print(f"[{_ts()}] [rig_get_freq] unexpected reply for '{vfo_cmd}': '{raw}'", flush=True)
    except Exception as e:
        print(f"[{_ts()}] [rig_get_freq] error '{vfo_cmd}': {e}", flush=True)
    finally:
        if s:
            try:
                s.close()
            except Exception:
                pass
    return None


def rigctl_alive() -> bool:
    try:
        s = socket.socket()
        s.settimeout(0.5)
        s.connect(("localhost", 4532))
        s.close()
        return True
    except Exception:
        return False


def _wait_usb_device(dev: str, timeout: float = 5.0) -> bool:
    """Wait for /dev/{dev} to appear after USB re-enumeration."""
    path = f"/dev/{dev}"
    if os.path.exists(path):
        return True
    subprocess.run(["sudo", "udevadm", "trigger", "--action=add"], capture_output=True)
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(0.3)
        if os.path.exists(path):
            return True
    return False


# ★ Pi側で機種を見てRTS/DTR→RIGへ自動変換するのはやめた。M5のPTT設定画面で
#   ユーザーが選んだ方式(RTS/DTR/RIG)をそのまま使う(Android版と同じ設計)。
#   IC-705等でRTS/DTRのUSBオーディオリセット問題を避けたい場合は、ユーザー側で
#   明示的に"RIG"を選んでもらう。
def _resolve_ptt_type(model: int, cat: str, ptt_type: str) -> str:
    return (ptt_type or "RTS").upper()


def _trigger_rigctld_restart(*args, **kwargs) -> bool:
    """start_rigctldをバックグラウンドスレッドで起動する。
    poll_rigのタイムアウト検知・PTT ON失敗・音声USBリセット検知など複数箇所から
    ほぼ同時に再起動要求が来ることがある(PTT連打時にrigctld再起動の最中に次の
    PTTが来る等)。呼び出し側の"not _rigctld_restarting"チェックだけではcheck-
    then-actの間に競合し、複数のstart_rigctldが同時に走って共倒れする恐れが
    あるため、ロックで排他し2つ目以降は何もせず即falseを返す(既に走っている
    再起動が完了すれば全員の状況は改善するので無視して問題ない)。"""
    if not _rigctld_restart_lock.acquire(blocking=False):
        return False

    def _run():
        try:
            start_rigctld(*args, **kwargs)
        finally:
            _rigctld_restart_lock.release()

    threading.Thread(target=_run, daemon=True).start()
    return True


def start_rigctld(model, cat, baud, ptt="", ptt_type="RTS", release_ptt=True):
    global rigctld_process, _rigctld_restarting
    _rigctld_restarting = True
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
    # 5. CATデバイスが存在するか確認。USB断線後の再列挙を待つ
    if not _wait_usb_device(cat, timeout=5.0):
        print(f"[{_ts()}] [rigctld] WARNING: /dev/{cat} not found after 5s", flush=True)
    # 6. 新しい rigctld を起動（nice +10 で uvicorn より低優先度に設定）
    _local_rigctld = os.path.join(os.path.expanduser("~"), ".local", "bin", "rigctld")
    _rigctld_bin = _local_rigctld if os.path.isfile(_local_rigctld) else "rigctld"
    cmd = ["nice", "-n", "10", _rigctld_bin, "-m", str(model), "-r", f"/dev/{cat}", "-s", str(baud), "-t", "4532"]
    # モデル別 stop_bits: FTX-1(1051) はデフォルト(1)を明示指定
    _stop_bits = {1051: 1}
    if model in _stop_bits:
        cmd += ["-C", f"stop_bits={_stop_bits[model]}"]
    effective_ptt_type = _resolve_ptt_type(model, cat, ptt_type)
    if effective_ptt_type != ptt_type.upper():
        print(f"[rigctld] using -P {effective_ptt_type} instead of -P {ptt_type.upper()}", flush=True)
    if ptt and ptt.upper() != "NONE":
        cmd += ["-p", f"/dev/{ptt}", "-P", effective_ptt_type]
    print(f"starting rigctld: {' '.join(cmd)}")
    rigctld_process = subprocess.Popen(cmd, stderr=subprocess.PIPE)
    # Phase 1: ポート 4532 が TCP 接続を受け付けるまで待つ。
    _alive_deadline = time.time() + 5.0
    _port_ready = False
    while time.time() < _alive_deadline:
        if rigctld_process.poll() is not None:
            err = rigctld_process.stderr.read().decode(errors="replace")
            print(f"rigctld exited early: {err.strip()}", flush=True)
            break
        if rigctl_alive():
            print(f"rigctld port open pid={rigctld_process.pid}", flush=True)
            _port_ready = True
            break
        time.sleep(0.2)
    else:
        print(f"rigctld port-open timeout (5s): pid={rigctld_process.pid if rigctld_process else '?'}", flush=True)
    # Phase 2: rigctld が実際にコマンドに応答するまで待つ。
    # ポートが開いただけでは CAT 接続確立前にクラッシュすることがある。
    # その短い窓で _rigctld_restarting = False にすると poll_rig のストリークが
    # 即座に溜まり再起動ループが発生する。応答確認後にのみ False にする。
    if _port_ready and rigctld_process and rigctld_process.poll() is None:
        _resp_deadline = time.time() + 6.0
        while time.time() < _resp_deadline:
            if rigctld_process.poll() is not None:
                err = rigctld_process.stderr.read().decode(errors="replace")
                print(f"rigctld exited during CAT init: {err.strip()}", flush=True)
                break
            _rs = None
            try:
                _rs = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                _rs.settimeout(2.0)
                _rs.connect(("localhost", 4532))
                _rs.sendall(b"t\n")
                _resp = _rs.recv(4096).decode(errors="replace").strip()
                if _resp:
                    print(f"rigctld running pid={rigctld_process.pid} (t='{_resp}')", flush=True)
                    break
            except Exception:
                pass
            finally:
                try:
                    if _rs:
                        _rs.close()
                except Exception:
                    pass
            time.sleep(0.3)
        else:
            print(f"rigctld CAT-ready timeout (6s): pid={rigctld_process.pid if rigctld_process else '?'}", flush=True)
    # Hamlib RIG/RTS PTT modes can accidentally assert PTT during rig_open() initialization.
    # Explicitly release PTT and reset TX state after every rigctld start.
    # release_ptt=False skips this during audio TX recovery (IC-705 stays in TX mode after USB reset).
    if release_ptt:
        radio_cache["tx"] = False
        global last_ptt_state
        if last_ptt_state == 0:
            # アクティブなTXなし: T 0 を送って RTS などの誤アサートを解除する。
            # -P RTS 使用時: rig_open() でシリアルポートが開く際に RTS が一瞬
            # HIGH になることがある。即座に T 0 で LOW に戻す。
            # last_ptt_state==1 のとき(Androidがリトライして T 1 を送信中)は
            # T 0 を送ってはいけない — アクティブな送信を中断してしまう。
            time.sleep(0.3)
            try:
                result = rigctl_cmd_priority("T 0")
                print(f"[{_ts()}] [start_rigctld] PTT release: T 0 -> '{result}'", flush=True)
            except Exception as e:
                print(f"[{_ts()}] [start_rigctld] PTT release failed: {e}", flush=True)
        else:
            print(f"[{_ts()}] [start_rigctld] PTT release skipped (TX active, last_ptt_state=1)", flush=True)
        last_ptt_state = 0
    global _timeout_streak
    _timeout_streak = 0  # リスタート中に積み増したカウントをリセット
    _rigctld_restarting = False


def _dump_caps_text(timeout_total: float = 3.0) -> str:
    """rigctldに\\dump_capsを送り、応答が止まる(=送り終わり)まで読み続けて返す。
    通常のコマンドと違い応答が複数行/不定長のため、専用の読み取りループにしている。"""
    s = None
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(1.0)
        s.connect(("localhost", 4532))
        s.sendall(b"\\dump_caps\n")
        chunks = []
        deadline = time.time() + timeout_total
        while time.time() < deadline:
            try:
                data = s.recv(4096)
            except socket.timeout:
                break
            if not data:
                break
            chunks.append(data)
        return b"".join(chunks).decode(errors="replace")
    except Exception as e:
        print(f"[{_ts()}] [vfo_mode] dump_caps error: {e}", flush=True)
        return ""
    finally:
        if s:
            try:
                s.close()
            except Exception:
                pass


def _detect_vfo_mode():
    """機種選択で接続したrigのHamlibバックエンドが MAIN/SUB(2波同時受信機) か
    VFO A/B(通常機) かを\\dump_capsの"VFO list:"行から判定する。
    あわせて"Mode list:"行から、その機種がHamlib上で実際にサポートしている
    モード一覧(C4FM/DSTAR等、機種固有のデジタルモードを含む)も検出し、
    current_mode_listに保存する。
    /radio/openのたびにバックグラウンドで呼ばれ、rig_vfo_mode / current_mode_list を更新する。"""
    global rig_vfo_mode, current_mode_list
    # rigctld のポートが開くまで待つ(dump_caps はリグ接続不要でHamlibの内部データを返すため
    # ポートが開けば即実行できる)。
    for _ in range(30):
        if rigctl_alive():
            break
        time.sleep(0.5)
    # dump_caps は独立したソケット接続を開くため rig_lock は不要。
    # rig_lock を保持したまま実行すると poll_rig の周波数/モード取得が最大3秒ブロックされる。
    text = _dump_caps_text()
    if not text:
        rig_vfo_mode = "ab"  # 判定不能時は最も一般的なA/B型として扱う
        current_mode_list = None  # 検出失敗時は/radio/modes等が静的リストにフォールバック
        print("[vfo_mode] dump_caps empty, defaulting to 'ab'", flush=True)
        return
    vfo_line = ""
    mode_line = ""
    for line in text.splitlines():
        low = line.lower()
        if not vfo_line and "vfo list" in low:
            vfo_line = line
        if not mode_line and "mode list" in low:
            mode_line = line
    tokens = vfo_line.upper()
    if "MAIN" in tokens and "SUB" in tokens:
        rig_vfo_mode = "mainsub"
    else:
        rig_vfo_mode = "ab"
    print(f"[vfo_mode] detected: {rig_vfo_mode} (line: '{vfo_line.strip()}')", flush=True)

    if ":" in mode_line:
        raw_modes = [m for m in mode_line.split(":", 1)[1].split() if m]
    else:
        raw_modes = []

    # ノイズモードのフィルタリング
    modes = [m for m in raw_modes if m not in _HAMLIB_NOISE_MODES]

    current_mode_list = modes if modes else None
    print(f"[mode_list] raw={raw_modes}", flush=True)
    print(f"[mode_list] filtered: {current_mode_list}", flush=True)


def poll_rig():
    global poll_enabled, last_user_freq_change, last_user_mode_change, _rig_tx_latch_until, _timeout_streak
    print("[poll_rig] waiting for rigctld...")
    for _ in range(30):
        if rigctl_alive():
            break
        time.sleep(0.5)
    last_bkin_rig = 0
    _m_skip_remaining = 0  # C4FM モード検出時、m クエリを数サイクルスキップ
    print("[poll_rig] rigctld ready, starting poll loop")
    while True:
        # ★ APRS送信中は "t" 問い合わせ自体を止める。tx_raw の結果で radio_cache["tx"] を
        #   毎回上書きしてしまうため、tx_in_progress 側で立てた値がすぐ書き戻されていた。
        if tx_in_progress:
            time.sleep(0.5)
            continue
        rig_beacon_active = aprs_running and aprs_cfg is not None and aprs_cfg.use_rig_modem
        try:
            tx_raw = rigctl_cmd("t")
            if tx_raw:
                tx = int(tx_raw.split()[0]) if tx_raw.split()[0].isdigit() else 0
                if tx and rig_beacon_active:
                    # AUTO BEACON送信はごく短時間(1秒未満)で終わるため、検知したら
                    # M5側の/radio/statusポーリングが確実に拾えるよう表示を少し保持する
                    _rig_tx_latch_until = time.time() + 1.5
                radio_cache["tx"] = bool(tx) or time.time() < _rig_tx_latch_until
                _timeout_streak = 0
            else:
                # timeout: keep last TX state so watchdog can fire if needed
                tx = int(radio_cache.get("tx", False))
                # Don't restart rigctld during TX: DTR reset would cut transmission
                # Don't count timeouts while a restart is in progress (_rigctld_restarting=True);
                # start_rigctld resets _timeout_streak when it finishes.
                if not tx and not _rigctld_restarting:
                    _timeout_streak += 1
                    # rigctld プロセスが終了していれば即再起動。
                    # プロセスは生きているが応答しない場合は 4 回連続失敗してから再起動
                    # (FT-991 C4FM モードでは 'm' がタイムアウトしても 't' は正常に応答するため
                    #  閾値を低くすると誤再起動 → T 0 送信 → brief TX が発生する)。
                    _proc_dead = rigctld_process is not None and rigctld_process.poll() is not None
                    if (_proc_dead or _timeout_streak >= 4) and current_model and current_cat:
                        print(f"[{_ts()}] [poll_rig] {_timeout_streak} consecutive timeouts (proc_dead={_proc_dead}) — restarting rigctld", flush=True)
                        _timeout_streak = 0
                        _trigger_rigctld_restart(current_model, current_cat, current_baud,
                                                  current_ptt, current_ptt_type)
        except Exception:
            tx = 0
            radio_cache["tx"] = False
            _timeout_streak = 0

        if tx and last_ptt_state == 1:
            # サーバーがPTTを命令した場合のみポーリングをスキップ。
            # last_ptt_state=0 のとき(Hamlibの誤検知など)はスキップしない。
            time.sleep(1.0)  # TX中はrigctld負荷を軽減
            continue

        if not poll_enabled:
            time.sleep(0.2)
            continue

        try:
            # ★ Main/Sub機種はHamlibの抽象化コマンド"f"がraw VS切替後の実際の動作VFOに
            #   追従せず、常にMain側の周波数を返してしまう(実機で確認: S/M(Sub動作)中でも
            #   "f"はMain周波数のまま)。そのためmainsub機種はrig_cycle_idxから現在の動作
            #   VFOを判定し、FA(Main)/FB(Sub)を素のCATクエリで直接読む。
            if rig_vfo_mode == "mainsub":
                vfo_cmd = "FB" if rig_cycle_idx == 2 else "FA"
                with rig_lock:
                    freq_val = _rig_get_vfo_freq(vfo_cmd)
                if freq_val is not None and time.time() - last_user_freq_change > 0.5:
                    radio_cache["freq"] = freq_val
            else:
                freq_raw = rigctl_cmd("f")
                val = freq_raw.split()[0] if freq_raw else ""
                if val.lstrip("-").isdigit() and time.time() - last_user_freq_change > 0.5:
                    radio_cache["freq"] = int(val)
        except Exception:
            pass

        try:
            if _m_skip_remaining > 0:
                # C4FM モードでは FT-991 が m クエリに応答しないため 2 秒タイムアウト
                # が発生し、並行する T 1 (PTT) もブロックされてラグが生じる。
                # タイムアウト後は数サイクルスキップして T 1 のブロックを防ぐ。
                _m_skip_remaining -= 1
            else:
                mode_raw = rigctl_cmd("m")
                parts = mode_raw.split() if mode_raw else []
                if parts and time.time() - last_user_mode_change > 0.5:
                    radio_cache["mode"] = parts[0]
                    if len(parts) >= 2 and parts[1].lstrip("-").isdigit():
                        radio_cache["width"] = int(parts[1])
                if not mode_raw:
                    # タイムアウト: 次の 2 サイクルをスキップして T 1 のブロックを防ぐ
                    _m_skip_remaining = 2
        except Exception:
            pass

        if time.time() - last_bkin_rig > 15:
            try:
                for func in ("SBKIN", "FBKIN"):
                    raw = rigctl_cmd(f"u {func}")
                    v = raw.split()[0] if raw else ""
                    if v.lstrip("-").isdigit():
                        radio_cache["bk_in"] = int(v)
                        break
            except Exception:
                pass
            last_bkin_rig = time.time()

        # ★ FTX-1内蔵モデムでビーコン運用中は"t"問い合わせの間隔を縮めて、
        #   短時間で終わるAUTO BEACON送信の取りこぼしを減らす
        time.sleep(0.2 if rig_beacon_active else 1.0)


def poll_signal():
    global poll_enabled
    for _ in range(30):
        if rigctl_alive():
            break
        time.sleep(0.5)
    last_power = 0
    last_sql = 0
    while True:
        if tx_in_progress or radio_cache.get("tx", False):
            time.sleep(1.0)  # TX中はrigctld負荷を軽減
            continue

        try:
            raw = rigctl_cmd("l STRENGTH")
            val = raw.split()[0] if raw else ""
            if val:
                sig = float(val)
                radio_cache["signal"] = sig
        except Exception:
            pass

        if time.time() - last_power > 5:
            try:
                raw = rigctl_cmd("l RFPOWER")
                val = raw.split()[0] if raw else ""
                if val:
                    radio_cache["power"] = float(val)
            except Exception:
                pass
            last_power = time.time()

        if time.time() - last_sql > 5:
            try:
                raw = rigctl_cmd("l SQL")
                val = raw.split()[0] if raw else ""
                if val:
                    radio_cache["sql"] = float(val)
            except Exception:
                pass
            last_sql = time.time()

        time.sleep(1.0)


def watchdog_heartbeat():
    global last_heartbeat, last_ptt_state
    while True:
        # last_ptt_state=1 のときのみ watchdog を発動する。
        # last_ptt_state=0 のとき(Hamlibの誤検知・外部PTT等)は T 0 を送らない。
        # FT-991 の C4FM モードで rigctl t が誤って 1 を返す場合、
        # watchdog が T 0 を送り続けて断続送信を引き起こすのを防ぐ。
        if radio_cache.get("tx", False) and last_ptt_state == 1:
            # APRS TX 中は direwolf が PTT を管理するので watchdog を抑制
            if not tx_in_progress and time.time() - last_heartbeat > 5.0:
                print(f"[{_ts()}] *** [PTT OFF] watchdog: heartbeat lost -> TX OFF", flush=True)
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


@app.post("/ft8/start")
async def ft8_start():
    global _ft8_decode_running, _ft8_decode_thread
    if _ft8_decode_running:
        return {"status": "already_running"}
    _ft8_decode_running = True
    _ft8_decode_thread = threading.Thread(target=_ft8_decode_loop, daemon=True)
    _ft8_decode_thread.start()
    return {"status": "started"}


@app.post("/ft8/stop")
async def ft8_stop():
    global _ft8_decode_running
    _ft8_decode_running = False
    return {"status": "stopped"}


@app.post("/ft8/set_depth")
async def ft8_set_depth(depth: int = Query(...), request: Request = None):
    global _ft8_decode_depth
    if depth not in (1, 2, 3):
        raise HTTPException(status_code=400, detail="depth must be 1, 2, or 3")
    _ft8_decode_depth = depth
    return {"depth": depth}


@app.post("/ft8/set_decode_filter")
async def ft8_set_decode_filter(request: Request):
    global _ft8_decode_filter
    data = await request.json()
    freqs = data.get("freqs", [])
    range_hz = int(data.get("range", 60))
    _ft8_decode_filter = [(int(f), range_hz) for f in freqs] if freqs else []
    return {"ok": True, "filter": _ft8_decode_filter}


@app.get("/ft8/decode_debug")
async def ft8_decode_debug(request: Request):
    """最後の jt9 デコード結果（stdout/stderr）を返す。デバッグ用。"""
    qkey = request.query_params.get("api_key", "")
    hkey = request.headers.get("X-API-Key", "")
    if API_KEY and qkey != API_KEY and hkey != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")
    return _ft8_last_decode_info


@app.get("/ft8/rx_msgs", dependencies=[])
async def ft8_rx_msgs(request: Request):
    """FT8デコード結果を SSE ストリームで配信する。"""
    qkey = request.query_params.get("api_key", "")
    hkey = request.headers.get("X-API-Key", "")
    if API_KEY and qkey != API_KEY and hkey != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")

    q: _queue.Queue = _queue.Queue(maxsize=32)
    with _ft8_rx_clients_lock:
        _ft8_rx_clients.append(q)

    async def _stream():
        try:
            yield b"data: {\"type\":\"connected\"}\n\n"
            # 再接続時: 進行中のQSOがあれば即送信してAndroid側のtxStateを復元
            if _qso.get("active"):
                import json as _json
                _state_map = {1: "pending", 3: "got_report", 5: "sending_73"}
                _qs = _state_map.get(_qso.get("state", 0), "pending")
                _qso_evt = _json.dumps({
                    "type": "qso_state", "state": _qs,
                    "dx_call": _qso.get("dx_call", ""),
                    "msg": _qso.get("next_msg", ""),
                    "tx_mode": _qso.get("tx_mode", ""),
                })
                yield f"data: {_qso_evt}\n\n".encode()
            loop = asyncio.get_running_loop()
            while True:
                try:
                    msg = await loop.run_in_executor(None, lambda: q.get(timeout=20.0))
                    yield msg
                except _queue.Empty:
                    yield b": keepalive\n\n"
        except (asyncio.CancelledError, GeneratorExit):
            pass
        except Exception as e:
            print(f"[ft8_rx_msgs] stream error: {e}")
        finally:
            with _ft8_rx_clients_lock:
                try:
                    _ft8_rx_clients.remove(q)
                except ValueError:
                    pass

    return StreamingResponse(
        _stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


# ─── FT8 ウォーターフォール用スペクトラム SSE ─────────────────────────────
_spectrum_clients:     list = []
_spectrum_clients_lock = threading.Lock()
_spectrum_running      = False
_spectrum_thread: threading.Thread | None = None

SPECTRUM_FFT   = 2048
SPECTRUM_BINS  = 256          # 出力ビン数 (0〜3 kHz を 256 分割)
SPECTRUM_HZ    = 4            # 毎秒更新回数
SPECTRUM_CHUNK = FT8_SAMPLE_RATE // SPECTRUM_HZ  # 3000 サンプル/フレーム


def _spectrum_broadcast(bins: list, extra: dict | None = None):
    payload: dict = {"bins": bins}
    if extra:
        payload.update(extra)
    msg = ("data: " + _json.dumps(payload) + "\n\n").encode()
    with _spectrum_clients_lock:
        dead = []
        for q in list(_spectrum_clients):
            try:
                q.put_nowait(msg)
            except _queue.Full:
                dead.append(q)
        for q in dead:
            try: _spectrum_clients.remove(q)
            except ValueError: pass


def _spectrum_loop():
    global _spectrum_running
    try:
        import numpy as np
    except ImportError:
        print("[spectrum] numpy not available — pip install numpy")
        _spectrum_running = False
        return
    print("[spectrum] started")
    _mgr_sub.ensure("12000")
    sid, q = _mgr_sub.subscribe(maxsize=128)
    buf          = bytearray()
    need         = SPECTRUM_CHUNK * 2  # 16-bit PCM
    hann         = np.hanning(SPECTRUM_FFT).astype(np.float32)
    last_period  = -1
    try:
        while _spectrum_running:
            try:
                chunk = q.get(timeout=0.5)
            except _queue.Empty:
                continue
            buf.extend(chunk)
            while len(buf) >= need:
                frame, buf = buf[:need], buf[need:]
                pcm = np.frombuffer(bytes(frame), dtype=np.int16).astype(np.float32)
                pcm = np.pad(pcm, (0, max(0, SPECTRUM_FFT - len(pcm))))[:SPECTRUM_FFT]
                mag = np.abs(np.fft.rfft(pcm * hann))
                # 12 kHz / 2048 * 512 = 3000 Hz → bins 0..511
                mag = mag[:512]
                # 512 → 256 bins (max pool)
                mag = mag.reshape(SPECTRUM_BINS, 2).max(axis=1)
                # dB 変換・自動ノイズフロア正規化
                # 20%ile をノイズフロア(=黒)、その 30dB 上を赤にすることで
                # 絶対音量レベルに依存しない表示を実現する
                db    = 20.0 * np.log10(mag + 1.0)
                floor = float(np.percentile(db, 20))
                norm  = np.clip((db - floor) / 30.0 * 255.0, 0, 255).astype(np.uint8)
                # FT8ピリオド境界をスペクトラムフレームに埋め込む（横線同期用）
                # チャンク末尾ではなく開始時刻で判定（1チャンク=0.25秒分を補正）
                audio_t  = time.time() - SPECTRUM_CHUNK / FT8_SAMPLE_RATE
                utc_s    = int(audio_t) % 60
                period_n = (utc_s // 15) % 4
                extra    = None
                if period_n != last_period:
                    last_period = period_n
                    extra = {"new_period": True, "period": period_n, "utc_sec": utc_s}
                _spectrum_broadcast(norm.tolist(), extra)
    finally:
        _mgr_sub.unsubscribe(sid)
        print("[spectrum] stopped")


@app.get("/ft8/spectrum")
async def ft8_spectrum(request: Request):
    """ウォーターフォール用 FFT スペクトラムを SSE ストリームで配信する。"""
    global _spectrum_thread, _spectrum_running
    qkey = request.query_params.get("api_key", "")
    hkey = request.headers.get("X-API-Key", "")
    if API_KEY and qkey != API_KEY and hkey != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")

    q: _queue.Queue = _queue.Queue(maxsize=8)
    with _spectrum_clients_lock:
        _spectrum_clients.append(q)

    if not _spectrum_running or _spectrum_thread is None or not _spectrum_thread.is_alive():
        _spectrum_running = True
        _spectrum_thread = threading.Thread(target=_spectrum_loop, daemon=True, name="ft8_spectrum")
        _spectrum_thread.start()

    async def _stream():
        try:
            yield b"data: {\"type\":\"spectrum_start\"}\n\n"
            loop = asyncio.get_running_loop()
            while True:
                try:
                    msg = await loop.run_in_executor(None, lambda: q.get(timeout=2.0))
                    yield msg
                except _queue.Empty:
                    yield b": keepalive\n\n"
        except (asyncio.CancelledError, GeneratorExit):
            pass
        finally:
            with _spectrum_clients_lock:
                try: _spectrum_clients.remove(q)
                except ValueError: pass
            if not _spectrum_clients:
                _spectrum_running = False

    return StreamingResponse(
        _stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


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
    global current_model, current_cat, current_baud, current_ptt, current_ptt_type, poll_started, rig_cycle_idx, current_mode_list
    current_model = model
    current_cat = cat
    current_baud = baud
    current_ptt = ptt
    # Store effective PTT type (mirrors start_rigctld's Icom USB-to-RIG conversion)
    current_ptt_type = _resolve_ptt_type(model, cat, ptt_type)
    # ★ VFO切替サイクルの想定状態は接続のたびにリセット(前回の状態は無効になるため)
    rig_cycle_idx = 0
    # ★ モード一覧も機種が変わるたびにリセット(前回接続機種のリストが一瞬残らないように)
    current_mode_list = None
    start_rigctld(model, cat, baud, ptt, ptt_type)
    # ★ 機種選択・接続のたびにVFO A/B か MAIN/SUB かを判定し直す
    threading.Thread(target=_detect_vfo_mode, daemon=True).start()
    if not poll_started:
        threading.Thread(target=poll_rig, daemon=True).start()
        threading.Thread(target=poll_signal, daemon=True).start()
        poll_started = True
    return {"status": "ok"}


@app.get("/radio/vfo_mode")
def get_vfo_mode():
    return {"mode": rig_vfo_mode or "unknown"}


def _normalize_vfo_label(raw: str, mode: str) -> str:
    """Hamlibの生VFO文字列("VFOA"/"Main"等)を、M5に表示する短いラベルに正規化する。"""
    up = (raw or "").upper()
    if mode == "mainsub":
        return "Sub" if "SUB" in up else "Main"
    return "B" if ("VFOB" in up or up == "B") else "A"


# ★ MAIN/SUB機(2波同時受信機)向け3状態サイクル。
#   Main(シングル表示) -> M/S(デュアル表示,Main動作) -> S/M(デュアル表示,Sub動作)
#   -> Main(シングル表示) ...
#   実機で確認した制約:
#     ・シングル表示のままVFO切替(VSコマンド)を送っても実際には反映されない
#       (フリーズはしないが無視される)。デュアル表示中に送った時だけ確実に効く。
#     ・「シングル表示でSubのみ動作」という組み合わせ自体は無線機を直接操作すれば
#       可能だが、CAT経由では安定して到達できなかったため、このサイクルには含めない。
#   そのため全ての切替は必ずデュアル表示(FR00)経由で行い、Main側に戻る時だけ
#   最後にシングル表示(FR01)に戻す。
# 0:Main(single) 1:M/S(dual,Main動作) 2:S/M(dual,Sub動作)
_VFO_CYCLE_LABELS = ["Main", "M/S", "S/M"]
rig_cycle_idx = 0


@app.get("/radio/vfo_current")
def get_vfo_current():
    """接続直後などトグルしていない時点での、現在のVFO側をM5が知るためのエンドポイント。
    ★ rigctl_cmd()自体が内部でrig_lockを取得するため、ここで外側からも
      with rig_lock:すると同一スレッドでの二重ロックとなり永久デッドロックする
      (このバグでpoll_rig等の他のrig_lock使用箇所も巻き込んで全て停止していた)。"""
    mode = rig_vfo_mode or "ab"
    if mode == "mainsub":
        return {"mode": mode, "side": _VFO_CYCLE_LABELS[rig_cycle_idx]}
    cur = rigctl_cmd("v").strip()
    return {"mode": mode, "side": _normalize_vfo_label(cur, mode), "raw": cur}


@app.post("/radio/vfo_toggle")
def toggle_vfo():
    global rig_cycle_idx
    mode = rig_vfo_mode or "ab"

    # ★ ボタン連打対策: 前回のトグル要求がCAT送受信を含めて完全に終わる前に次の要求が
    #   来ると、rig_cycle_idxの読み取り→書き込みが複数リクエストにまたがって重なり、
    #   M5の表示と実機の状態がズレる不具合があった。非ブロッキングでロックを試み、
    #   既に処理中なら新しい要求はCATコマンドを一切送らずその場で拒否する
    #   (キューイングして後で処理すると、連打分が後からまとめて発火して余計に混乱するため)。
    if not vfo_toggle_lock.acquire(blocking=False):
        raise HTTPException(status_code=409, detail="VFO toggle already in progress")

    try:
        if mode != "mainsub":
            # A/B機種はシンプルな2状態トグル(フリーズ問題が確認されていないため現状維持)
            cur = rigctl_cmd("v").strip()
            cur_up = cur.upper()
            target = "VFOB" if ("VFOA" in cur_up or cur_up == "A") else "VFOA"
            raw = rigctl_cmd(f"V {target}")
            side = _normalize_vfo_label(target, mode)
            print(f"[vfo_toggle] {cur} -> {target} ('{raw.strip()}')", flush=True)
            return {"mode": mode, "from": cur, "to": target, "side": side}

        # ★ HamlibのVFO抽象化コマンド("V"/"v")はこの機種のバックエンドで正しく動作しない
        #   ことが実機で確認された(M5表示は切り替わるが無線機側は変わらない)。代わりに
        #   CATリファレンスで確認済みの生コマンド"VS"(VFO SELECT: 0=Main動作 1=Sub動作)を
        #   直接送る。EX/FA/FBと同様、応答が返らないコマンドのため_rig_send_no_replyを使う。
        #
        # ★ 実機で確認: VSコマンドはシングル表示のままだと(フリーズはしないが)実際には
        #   反映されない。デュアル表示中に送った時だけ確実に効く。そのため、どの遷移でも
        #   必ず一旦デュアル表示(FR00)にしてから切り替え、目標がシングル表示の状態(Main
        #   単独)ならその後シングル(FR01)に戻す、という手順で統一する。
        #
        # ★ next_idxの計算・CAT送信・rig_cycle_idxへの書き戻しを1回のrig_lockでまとめて
        #   保持する。分割していた頃はコマンド間のtime.sleep(0.3)の間にロックが解放され、
        #   poll_rig/poll_signalのバックグラウンドポーリング(f/m/t/l STRENGTH等、約1秒
        #   ごと)がその隙間に割り込んでCATを送ってしまい、無線機側のFR00→VS→FR01という
        #   状態遷移が想定通りに進まなかった。
        with rig_lock:
            next_idx = (rig_cycle_idx + 1) % 3
            target_main_active = next_idx in (0, 1)   # Main動作: 0=Main(single), 1=M/S(dual)
            target_single = next_idx == 0             # シングル表示に戻す: 0=Main のみ

            _rig_send_no_reply("w FR00;")
            time.sleep(0.3)
            _rig_send_no_reply(f"w VS{0 if target_main_active else 1};")
            if target_single:
                time.sleep(0.3)
                _rig_send_no_reply("w FR01;")

            rig_cycle_idx = next_idx

        label = _VFO_CYCLE_LABELS[rig_cycle_idx]
        print(f"[vfo_toggle] cycle -> {label}", flush=True)
        return {"mode": mode, "side": label}
    finally:
        vfo_toggle_lock.release()


@app.get("/radio/status")
def radio_status():
    return {**radio_cache, "tx_in_progress": tx_in_progress, "api_version": API_VERSION, "ft8_decode_running": _ft8_decode_running}


@app.get("/radio/caps")
def radio_caps():
    # current_mode_listはopen_radio後にバックグラウンドでdump_capsから検出される、
    # 現在接続中の機種がHamlib上で実際にサポートするモード一覧(C4FM/DSTAR等の
    # 機種固有デジタルモードを含む)。未検出/未接続時は汎用の静的リストにフォールバック。
    modes = current_mode_list if current_mode_list else sorted(_FALLBACK_MODE_LIST)
    return {"modes": modes, "raw": ""}


@app.get("/radio/modes")
def list_modes():
    modes = current_mode_list if current_mode_list else _FALLBACK_MODE_LIST
    return {"modes": modes}


def _setfreq_with_retry(f: int):
    for attempt in range(4):
        result = rigctl_cmd(f"F {f}")
        if result is not None and "RPRT" in result:
            return
        time.sleep(1.0)

@app.post("/radio/setfreq")
def set_freq(f: int = Form(...)):
    global last_user_freq_change
    radio_cache["freq"] = f
    last_user_freq_change = time.time()
    threading.Thread(target=_setfreq_with_retry, args=(f,), daemon=True).start()
    return {"status": "ok", "freq": f}


@app.post("/radio/setmode")
def set_mode(mode: str = Form(...), width: int = Form(...)):
    global last_user_mode_change
    _prev_mode = radio_cache.get("mode")
    _prev_width = radio_cache.get("width", 0)
    radio_cache["mode"] = mode
    radio_cache["width"] = width
    last_user_mode_change = time.time()
    def _set_and_check():
        global last_user_mode_change
        result = rigctl_cmd(f"M {mode} {width}")
        if result and result.startswith("RPRT") and result != "RPRT 0":
            # C4FM: FT-991A はモード切替中に CAT 応答が遅れ RPRT -14(BUSBUSY) を返すが、
            # リグは実際に C4FM へ切り替わっている(直後の 'm' タイムアウトで確認済み)。
            # BUSBUSY は成功として扱い、キャッシュを C4FM のまま維持する。
            if mode == "C4FM" and result == "RPRT -14":
                last_user_mode_change = time.time()
                print(f"[{_ts()}] [setmode] C4FM BUSBUSY treated as success", flush=True)
                return
            radio_cache["mode"] = _prev_mode
            radio_cache["width"] = _prev_width
            print(f"[{_ts()}] [setmode] '{mode}' failed ({result}), cache reverted to '{_prev_mode}'", flush=True)
        else:
            # M コマンド完了後にガードタイマーをリセット。
            # リクエスト受信時点(0.5s前)からではなく、コマンド完了後からポーリングを抑制することで、
            # リグの切替完了前に m クエリが古いモードを返してキャッシュを上書きするのを防ぐ。
            last_user_mode_change = time.time()
    threading.Thread(target=_set_and_check, daemon=True).start()
    return {"status": "ok", "mode": mode, "width": width}


def _ptt_off_watchdog():
    """FTX-1F等、出力ランプアップ中や送信時のRF回り込みでUSB/rigctldがリセット
    されることがあるリグ向け。"t"問い合わせでOFFを確認できるまで、一定間隔で
    T 0を送り続ける(最大20秒)。

    rigctld再起動(release_ptt=False、音声USBリセット復旧用)を挟んだ場合、
    再起動直後のrigctldは無線機の実際のTX状態を把握していない可能性があるため、
    "t"の単発の"0"応答を信用せず2回連続で"0"を確認するまで待つ。さらにその
    場合は、確認後・タイムアウト後のいずれでも念のため最後にもう一度無条件で
    T 0を送っておく(既にOFFなら無害、まだONなら最後の保険になる)。

    CW(内蔵キーヤー)・APRS(direwolf)はT 1/last_ptt_stateを経由せず独自にPTTを
    制御するため、それらが動き出したらこのwatchdogは無関係なT 0を送らないよう
    即座に打ち切る。"""
    PTT_OFF_RETRY_INTERVAL = 1.0
    PTT_OFF_WATCHDOG_SEC = 20.0
    deadline = time.time() + PTT_OFF_WATCHDOG_SEC
    _starting_proc = rigctld_process
    saw_restart = False
    consecutive_off = 0

    def _superseded():
        # ★ radio_cache["tx"]はpoll_rigが無線機への実際の"t"問い合わせ結果でも
        #   更新するため、「無線機がまだONのまま」という、このwatchdogがまさに
        #   直そうとしている状態そのものでTrueになりうる。それを"追い越された"
        #   と誤判定して即座に諦めてしまっていたため、ここではlast_ptt_state
        #   (ソフトウェアが明示的にONを命令したか)とtx_in_progress(APRS等の
        #   ソフトウェア駆動の送信中フラグ、poll_rigは触らない)のみを見る。
        return last_ptt_state == 1 or tx_in_progress or _morse_sending or _ft8_tx_active

    while time.time() < deadline:
        time.sleep(PTT_OFF_RETRY_INTERVAL)
        if _superseded():
            return  # 音声PTT ON / APRS TX / CW送信 / FT8送信に追い越された

        if rigctld_process is not _starting_proc:
            saw_restart = True  # rigctldプロセスが入れ替わった = 再起動が発生した

        if _rigctld_restarting:
            # 再起動処理の真っ最中はコマンドを送っても無駄。復旧を待つ。
            saw_restart = True
            consecutive_off = 0
            continue

        required_confirms = 2 if saw_restart else 1
        ptt_raw = rigctl_cmd_priority("t")
        if ptt_raw and ptt_raw.strip().split("\n")[0].strip() == "0":
            consecutive_off += 1
            if consecutive_off >= required_confirms:
                if saw_restart:
                    print(f"[{_ts()}] *** [PTT OFF watchdog] confirmed OFF after rigctld restart, sending final safety T 0", flush=True)
                    rigctl_cmd_priority("T 0")
                return  # OFF確認済み
            continue

        consecutive_off = 0
        if not ptt_raw:
            # "t"問い合わせ自体が無応答 = rigctldが落ちている(USBリセット等)。
            # 通常はaudio_tx側のUSBリセット検知(last_ptt_state==1が条件)が再起動を
            # キックするが、PTT ON→OFFが極端に速いとOFF処理でlast_ptt_stateが先に
            # 0になり、誰も再起動をキックしないまま放置されることがあった。
            # ここでwatchdog自身が再起動をキックして取りこぼしを防ぐ
            # (release_ptt=True: last_ptt_state==0なのでstart_rigctld自身のPTT解除
            #  ロジックにもT 0を送らせ、OFFを二重に確実にする)。
            print(f"[{_ts()}] *** [PTT OFF watchdog] rigctld unresponsive, triggering restart", flush=True)
            saw_restart = True
            if current_model and current_cat:
                _trigger_rigctld_restart(current_model, current_cat, current_baud,
                                          current_ptt, current_ptt_type, release_ptt=True)
            continue

        print(f"[{_ts()}] *** [PTT OFF watchdog] still ON, resending T 0", flush=True)
        rigctl_cmd_priority("T 0")

    if saw_restart:
        print(f"[{_ts()}] *** [PTT OFF watchdog] gave up after {PTT_OFF_WATCHDOG_SEC:.0f}s (rigctld restart occurred) — sending final safety T 0", flush=True)
        rigctl_cmd_priority("T 0")
    else:
        print(f"[{_ts()}] *** [PTT OFF watchdog] gave up after {PTT_OFF_WATCHDOG_SEC:.0f}s — radio may be stuck in TX!", flush=True)


@app.post("/radio/ptt")
def ptt(state: int = Form(...)):
    global last_ptt_state, last_heartbeat, _ft8_tx_active
    if state == 0:
        if last_ptt_state == 1:
            # サーバーが TX を命令していた場合のみ T 0 を送る。
            # last_ptt_state=0 の場合に T 0 を送ると C4FM モードで brief TX が発生する。
            # FTX-1F は ON 直後の出力ランプアップ中(~6秒)は T 0 を CI-V 的には正常応答
            # (RPRT 0)しつつ実際には無視することがあるため、単発リトライでは検知できない。
            # まず即座に1回送り、応答を待たせないようレスポンスは返す。実際に "t" 問い合わせで
            # OFFを確認できるまでは、バックグラウンドで一定間隔で送り続けるwatchdogに任せる。
            rigctl_cmd_priority("T 0")
            threading.Thread(target=_ptt_off_watchdog, daemon=True).start()
        else:
            print(f"[{_ts()}] [PTT OFF] T 0 skipped (last_ptt_state was already 0)", flush=True)
        radio_cache["tx"] = False
        last_ptt_state = 0
        _ft8_tx_active = False
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
        # rigctld タイムアウト → 即座に再起動してクライアントにリトライを促す
        if current_model and current_cat and not _rigctld_restarting:
            print(f"[{_ts()}] [ptt] T 1 timeout — triggering rigctld restart", flush=True)
            _trigger_rigctld_restart(current_model, current_cat, current_baud,
                                      current_ptt, current_ptt_type)
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


@app.post("/radio/setbkin")
def set_bk_in(state: int = Form(...)):
    try:
        # 1. Hamlib standard (works on some rigs)
        raw1 = rigctl_cmd("U SBKIN " + str(state))
        if raw1 is not None and "RPRT 0" in raw1:
            radio_cache["bk_in"] = state
            return {"ok": True, "bk_in": state, "raw": raw1}
        # 2. FT-991A raw CAT: BK1; = semi break-in ON, BK0; = OFF
        # B=\x42  K=\x4b  1=\x31 / 0=\x30  ;=\x3b
        val_hex = "31" if state else "30"
        raw2 = rigctl_cmd("w \\x42\\x4b\\x" + val_hex + "\\x3b")
        ok = raw2 is not None
        if ok:
            radio_cache["bk_in"] = state
        return {"ok": ok, "bk_in": state, "raw1": str(raw1), "raw2": str(raw2)}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@app.get("/radio/getbkin")
def get_bk_in():
    # Try SBKIN first (semi break-in), fall back to FBKIN (full break-in)
    val = 0
    for func in ("SBKIN", "FBKIN"):
        raw = rigctl_cmd(f"u {func}")
        try:
            v = raw.split()[0] if raw else ""
            if v.lstrip("-").isdigit():
                val = int(v)
                break
        except Exception:
            pass
    radio_cache["bk_in"] = val
    return {"bk_in": val}


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

    _mgr_sub.stop()  # FT8用arecord|soxを停止してALSAを解放
    # shellのSIGTERM後もarecordが残留することがあるため強制kill
    subprocess.run(["pkill", "-9", "arecord"], capture_output=True)
    time.sleep(0.15)  # ALSAデバイス解放を確実に待機
    proc = _mgr_rx.ensure(rate)

    # ffmpeg即死 = aplayとのALSA競合 or デバイス未解放の場合、待ってから再起動
    if proc.poll() is not None:
        print(f"[audio_rx] ffmpeg dead, waiting for aplay/arecord release...")
        deadline = time.time() + 0.8
        while time.time() < deadline:
            busy = (subprocess.run(["pgrep", "-x", "aplay"],   capture_output=True).returncode == 0 or
                    subprocess.run(["pgrep", "-x", "arecord"], capture_output=True).returncode == 0)
            if not busy:
                break
            time.sleep(0.05)
        proc = _mgr_rx._start(rate)
        time.sleep(0.1)
        if proc.poll() is not None:
            err = ""
            try:
                err = proc.stderr.read(512).decode(errors="replace").strip()
            except Exception:
                pass
            print(f"[audio_rx] ffmpeg dead after aplay-wait: {err}")
            return Response(status_code=503, content=f"ffmpeg error: {err}")

    sid, q = _mgr_rx.subscribe(maxsize=32)
    print(f"[audio_rx] ready {time.time()-t0:.3f}s")

    def stream():
        try:
            while True:
                try:
                    data = q.get(timeout=0.2)
                    yield data
                except _queue.Empty:
                    if _mgr_rx.proc is not proc or proc.poll() is not None:
                        break
        except GeneratorExit:
            pass

    def cleanup():
        _mgr_rx.unsubscribe(sid)
        print("[audio_rx] client disconnected")

    return StreamingResponse(stream(), media_type="application/octet-stream",
                             background=BackgroundTask(cleanup))


@app.get("/radio/audio_device")
def get_audio_device():
    return {"capture": _alsa_capture_dev, "playback": _alsa_playback_dev}

@app.post("/radio/audio_device")
async def set_audio_device(request: Request):
    global _alsa_capture_dev, _alsa_playback_dev
    try:
        data = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON")
    capture = data.get("capture", "").strip()
    playback = data.get("playback", "").strip()
    if capture:
        _alsa_capture_dev = capture
    if playback:
        _alsa_playback_dev = playback
    # ffmpegは次回接続時に _ensure_ffmpeg() がデバイス変更を検知して自動再起動する
    print(f"[audio_device] capture={_alsa_capture_dev} playback={_alsa_playback_dev}")
    return {"capture": _alsa_capture_dev, "playback": _alsa_playback_dev}

@app.post("/radio/audio_device_ft8")
async def set_audio_device_ft8(request: Request):
    try:
        data = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON")
    capture = data.get("capture", "").strip()
    _mgr_sub.capture_dev_override = capture
    _mgr_sub.stop()  # 次回接続時に新デバイスで再起動
    print(f"[audio_device_ft8] capture_override={capture!r}")
    return {"capture_ft8": capture}


@app.get("/radio/noise_reduction")
def get_noise_reduction():
    return {"level": _noise_reduction_level}

@app.post("/radio/noise_reduction")
def set_noise_reduction(level: int = Form(...)):
    global _noise_reduction_level
    level = max(0, min(5, level))
    _noise_reduction_level = level
    _mgr_rx._af = _build_rx_af(level)
    _mgr_rx.stop()  # AudioStreamServiceの自動再接続で新フィルターを適用
    return {"level": level}


@app.post("/radio/cw_decode")
def set_cw_decode(active: int = Form(...)):
    global _cw_decode_active
    _cw_decode_active = bool(active)
    # NRが有効な場合のみフィルター切替が発生する
    if _noise_reduction_level > 0:
        _mgr_rx._af = _build_rx_af(_noise_reduction_level)
        _mgr_rx.stop()
    return {"cw_decode": _cw_decode_active}


@app.post("/radio/audio_tx")
async def audio_tx(request: Request, rate: int = 8000, ptt: int = 0):
    global last_heartbeat, last_ptt_state, _ft8_tx_active
    if _ft8_tx_active and ptt == 0:
        print(f"[audio_tx] rejected (FT8 TX active): rate={rate}")
        raise HTTPException(status_code=503, detail="TX in progress")
    print(f"[audio_tx] connected rate={rate} ptt={ptt}")
    loop = asyncio.get_running_loop()
    if ptt:
        # FT8 TX: _mgr_sub(ffmpeg capture)を維持したまま aplay を起動する。
        # USBコーデックが全二重対応なら RX ffmpeg は TX 中も動作し続け、
        # TX完了後に即座に audio_sub へ再接続できる（~130ms）。
        # ffmpeg が ALSA 競合でクラッシュした場合は _ensure_sub_ready() が検出して再起動。
        # 自己CQデコード防止: TX中の capture は _mgr_sub.mute() で無音化。
        def _kill_procs_ft8():
            subprocess.run(["pkill", "-9", "direwolf"], capture_output=True)
            subprocess.run(["pkill", "-9", "aplay"],    capture_output=True)
            # _mgr_sub は kill しない — 全二重スタンバイ維持
            p = _mgr_rx.proc
            _mgr_rx.proc = None
            if p is not None:
                try: p.kill()
                except Exception: pass
                try: p.wait(timeout=0.5)
                except Exception: pass
            time.sleep(0.1)
        await loop.run_in_executor(None, _kill_procs_ft8)
        # TX中の自己CQデコード防止: ffmpegが動作中のため capture 出力を無音化
        _mgr_sub.mute(13.0)
        _ft8_tx_active = True
        result = await loop.run_in_executor(None, lambda: rigctl_cmd_priority("T 1"))
        if not result:
            _ft8_tx_active = False
            raise HTTPException(status_code=500, detail="rigctld timeout")
        radio_cache["tx"] = True
        last_ptt_state = 1
        last_heartbeat = time.time() + 60
        await asyncio.sleep(0.5)
        # aplay + plughw: 音声TXと同じ方式。plughwがレート変換を担当。
        # ffmpegをaplayに変更することでキャプチャffmpegとのALSA競合を解消
        proc = subprocess.Popen(
            ["aplay", "-D", _alsa_playback_dev, "-f", "S16_LE", "-r", str(rate), "-c", "1"],
            stdin=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0
        )
    else:
        # 音声TX: aplayで即時再生 (plughwが8000Hz→32000Hzを内部変換)
        # ffmpegはPi Zero上で起動に2〜3秒かかるためaplayを使う
        # captureプロセスを停止してALSAデバイス競合を防ぐ
        def _kill_voice():
            subprocess.run(["pkill", "-9", "direwolf"], capture_output=True)
            subprocess.run(["pkill", "-9", "aplay"],    capture_output=True)
            subprocess.run(["pkill", "-9", "arecord"],  capture_output=True)
            _mgr_sub.stop()
            p = _mgr_rx.proc
            _mgr_rx.proc = None
            if p is not None:
                try: p.kill()
                except Exception: pass
                try: p.wait(timeout=0.5)
                except Exception: pass
            time.sleep(0.3)
        await loop.run_in_executor(None, _kill_voice)
        _last_tx_debug["dev"] = _alsa_playback_dev
        _last_tx_debug["status"] = "aplay_starting"
        _last_tx_debug["chunks"] = 0
        card_name = _alsa_playback_dev.split("CARD=")[-1].split(",")[0] if "CARD=" in _alsa_playback_dev else "CODEC"
        proc = subprocess.Popen(
            ["aplay", "-D", _alsa_playback_dev, "-f", "S16_LE", "-r", str(rate), "-c", "1"],
            stdin=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0
        )
        await asyncio.sleep(0.05)
        if proc.poll() is not None:
            err = proc.stderr.read(512).decode(errors="replace").strip() if proc.stderr else ""
            print(f"[audio_tx] aplay died immediately rc={proc.returncode} err={err!r}")
            _last_tx_debug["status"] = "aplay_died_immediately"
            # IC-705 USB reset: restart rigctld in background, recovery handled in streaming loop
            if last_ptt_state == 1 and "No such device" in err:
                print(f"[{_ts()}] [audio_tx] USB reset detected — recovering in stream loop", flush=True)
                if current_model and current_cat and not _rigctld_restarting:
                    _trigger_rigctld_restart(
                        current_model, current_cat, current_baud, current_ptt, current_ptt_type,
                        release_ptt=False)
            proc = None
        else:
            _last_tx_debug["status"] = "aplay_running"
    # Streaming loop: keep reading chunks from client regardless of aplay state.
    # This holds the HTTP connection open during IC-705 USB reset recovery.
    # Chunks are written to aplay when running, discarded while CODEC is absent.
    chunk_count = 0
    _skip_check = 0  # rate-limit /proc/asound/cards checks to every N chunks
    try:
        async for chunk in request.stream():
            chunk_count += 1
            if proc is not None and proc.poll() is None:
                # aplay running — write chunk
                await loop.run_in_executor(None, proc.stdin.write, chunk)
            else:
                # aplay dead or not started — check if CODEC is ready to (re)start
                _skip_check += 1
                if _skip_check < 8:
                    continue  # discard chunk, check CODEC less frequently
                _skip_check = 0
                try:
                    codec_ok = (card_name in open("/proc/asound/cards").read()
                                and bool(glob.glob("/dev/snd/controlC*")))
                except Exception:
                    codec_ok = False
                if codec_ok:
                    if proc is not None:
                        try: proc.stdin.close()
                        except Exception: pass
                        try: proc.wait(timeout=0.2)
                        except Exception: pass
                    proc = subprocess.Popen(
                        ["aplay", "-D", _alsa_playback_dev, "-f", "S16_LE", "-r", str(rate), "-c", "1"],
                        stdin=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0
                    )
                    await asyncio.sleep(0.05)
                    if proc.poll() is None:
                        print(f"[{_ts()}] [audio_tx] aplay restarted at chunk {chunk_count}", flush=True)
                        _last_tx_debug["status"] = "aplay_running"
                    else:
                        err2 = proc.stderr.read(256).decode(errors="replace").strip() if proc.stderr else ""
                        print(f"[{_ts()}] [audio_tx] aplay restart failed: {err2[:40]!r}", flush=True)
                        proc = None
                # discard this chunk regardless (next chunk will write if aplay started)
        if ptt == 0:
            _last_tx_debug["chunks"] = chunk_count
    except Exception as e:
        print(f"[audio_tx] stream: {type(e).__name__}: {e}")
    finally:
        if proc is not None:
            try:
                proc.stdin.close()
            except Exception:
                pass
            await loop.run_in_executor(None, proc.wait)
        if ptt:
            # TX完了後: ffmpegが生きていればmuteを解除して即座に再接続（~0ms）
            # ALSA競合でクラッシュしていた場合のみ再起動（最大1.5s）
            def _post_tx_restore():
                _mgr_sub._mute_until = 0.0  # 即座にunmute
                p = _mgr_sub.proc
                if p is not None and p.poll() is None:
                    print("[audio_tx] ffmpeg survived TX — instant standby")
                    return
                print("[audio_tx] ffmpeg died during TX — restarting")
                for _ in range(10):
                    proc = _mgr_sub.ensure("12000")
                    time.sleep(0.15)
                    if proc.poll() is None:
                        return
            loop.run_in_executor(None, _post_tx_restore)
            await loop.run_in_executor(None, lambda: rigctl_cmd("T 0"))
            radio_cache["tx"] = False
            last_ptt_state = 0
            last_heartbeat = time.time()
            _ft8_tx_active = False
        # APRS が動作中だった場合は direwolf を復旧
        if aprs_running:
            print("[audio_tx] restarting direwolf for APRS")
            await loop.run_in_executor(None, lambda: subprocess.run(
                ["sudo", "systemctl", "start", "direwolf"], capture_output=True))
        ae = ""
        if proc is not None:
            try: ae = proc.stderr.read(512).decode(errors="replace").strip()
            except: pass
            print(f"[audio_tx] done rc={proc.returncode} err={ae!r}")
        else:
            print(f"[audio_tx] done (proc=None, aplay never started)")
    return {"status": "ok"}


@app.get("/radio/audio_sub", dependencies=[])
async def audio_sub(request: Request, background_tasks: BackgroundTasks):
    """FT8/webft8 向け 12kHz PCM ストリーム。メイン音声と独立したプロセス+キューで動作。
    クエリパラメータ api_key も受け付ける（WebView JS fetch 互換）。"""
    from fastapi.responses import Response as FR
    qkey = request.query_params.get("api_key", "")
    hkey = request.headers.get("X-API-Key", "")
    if API_KEY and qkey != API_KEY and hkey != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")

    rate = request.query_params.get("rate", "12000")
    if not rate.isdigit():
        rate = "12000"
    t0 = time.time()
    print(f"[audio_sub] connect rate={rate}")

    _mgr_rx.stop()  # メイン用ffmpegを停止してALSAを解放
    proc = _mgr_sub.ensure(rate)
    if proc.poll() is not None:
        print(f"[audio_sub] ffmpeg dead, restarting...")
        deadline = time.time() + 0.6
        while time.time() < deadline:
            if subprocess.run(["pgrep", "-x", "aplay"], capture_output=True).returncode != 0:
                break
            time.sleep(0.05)
        # ALSAデバイスリセットに最大1.5sかかるため150ms×10回リトライ
        for attempt in range(10):
            proc = _mgr_sub._start(rate)
            time.sleep(0.15)
            if proc.poll() is None:
                break
            print(f"[audio_sub] ffmpeg retry {attempt+1}/10")
        if proc.poll() is not None:
            err = ""
            try: err = proc.stderr.read(512).decode(errors="replace").strip()
            except Exception: pass
            print(f"[audio_sub] ffmpeg dead after retry: {err}")
            return FR(status_code=503, content=f"ffmpeg error: {err}")

    sid, q = _mgr_sub.subscribe(maxsize=64)
    print(f"[audio_sub] ready {time.time()-t0:.3f}s")

    def stream():
        try:
            while True:
                try:
                    data = q.get(timeout=0.2)
                    yield data
                except _queue.Empty:
                    if _mgr_sub.proc is not proc or proc.poll() is not None:
                        break
        except GeneratorExit:
            pass

    def cleanup():
        _mgr_sub.unsubscribe(sid)
        print("[audio_sub] client disconnected")

    return StreamingResponse(stream(), media_type="application/octet-stream",
                             background=BackgroundTask(cleanup))


def _ft8_find_encoder(is_ft4: bool = False) -> "str | None":
    """ft8code バイナリを探す。見つからなければ None。"""
    prefix = "ft4code" if is_ft4 else "ft8code"
    candidates = [
        f"/usr/local/bin/{prefix}", f"/usr/bin/{prefix}",
        f"/usr/lib/wsjtx/{prefix}",
        f"/usr/lib/x86_64-linux-gnu/wsjtx/{prefix}",
        f"/usr/lib/aarch64-linux-gnu/wsjtx/{prefix}",
        f"/usr/lib/arm-linux-gnueabihf/wsjtx/{prefix}",
        f"/opt/wsjtx/bin/{prefix}", f"/opt/wsjtx/{prefix}",
    ]
    found = next((p for p in candidates if os.path.isfile(p)), None)
    if found:
        return found
    # which で探す
    r = subprocess.run(["which", prefix], capture_output=True, text=True)
    if r.returncode == 0 and r.stdout.strip():
        return r.stdout.strip()
    # find コマンドで全探索 (最終手段)
    try:
        r = subprocess.run(
            ["find", "/usr", "/opt", "/home", "-name", prefix, "-type", "f"],
            capture_output=True, text=True, timeout=15)
        hits = [l.strip() for l in r.stdout.splitlines() if l.strip()]
        if hits:
            return hits[0]
    except Exception:
        pass
    # ft8 prefix でも探す (ft8code が ft4code を兼ねる場合)
    if is_ft4:
        return _ft8_find_encoder(False)
    return None


def _ft8_parse_symbols(stdout: str) -> "list | None":
    """ft8code 出力からシンボルリストを取得する。失敗なら None。

    ft8code の実際の出力形式:
        Channel symbols (79 tones):
          Sync               Data               Sync               Data               Sync
        3140652 00040627560751057171745533047 3140652 74207354566256247575072653100 3140652
    → 数字グループがスペース区切りで並び、各文字が 1 シンボル (0-7)
    """
    lines = stdout.splitlines()

    # 戦略1: "Channel symbols" 行の後のデータ行を探す (ft8code 実際のフォーマット)
    in_symbols_section = False
    for line in lines:
        low = line.lower()
        if "channel symbols" in low:
            in_symbols_section = True
            continue
        if in_symbols_section:
            stripped = line.strip()
            if not stripped:
                continue
            # ヘッダー行 ("Sync", "Data", "----" など) はスキップ
            if stripped.startswith("Sync") or stripped.startswith("Data") or stripped.startswith("-"):
                continue
            # 数字とスペースだけで構成される行 → 各文字がシンボル
            digits = [c for c in stripped if c.isdigit()]
            if len(digits) >= 50:
                return [int(d) for d in digits[:79]]

    # 戦略2: スペース除去後に50文字以上の数字のみの行
    for line in lines:
        no_sp = line.strip().replace(" ", "")
        if len(no_sp) >= 50 and all(c.isdigit() for c in no_sp):
            return [int(c) for c in no_sp[:79]]

    # 戦略3: "channel symbols" キーワード行に数字が続く場合
    for line in lines:
        low = line.lower()
        if any(kw in low for kw in ["channel symbols", "encoded message", "symbols:"]):
            after = line.split(":", 1)[-1].strip() if ":" in line else line
            digits = [c for c in after if c.isdigit()]
            if len(digits) >= 50:
                return [int(d) for d in digits[:79]]

    # 戦略4: スペース区切りトークンを整数として解釈 (旧フォーマット対応)
    for line in lines:
        syms: list = []
        for tok in line.split():
            try:
                n = int(tok)
                if 0 <= n <= 7:
                    syms.append(n)
            except ValueError:
                pass
        if len(syms) >= 50:
            return syms[-79:]

    return None


def _ft8_get_symbols(msg: str, is_ft4: bool = False) -> list:
    """ft8code (wsjtxに付属) でチャンネルシンボル (0-7, 79個) を取得する。"""
    binary = _ft8_find_encoder(is_ft4)
    if binary is None:
        raise RuntimeError(
            "ft8code が見つかりません。\n"
            "インストール: sudo apt install wsjtx\n"
            "場所確認: find /usr /opt -name ft8code 2>/dev/null")

    result = subprocess.run([binary, msg], capture_output=True, text=True, timeout=5)
    stdout = result.stdout
    print(f"[ft8code] binary={binary} rc={result.returncode} stdout={stdout!r}")

    symbols = _ft8_parse_symbols(stdout)
    if symbols is not None:
        return symbols

    raise RuntimeError(
        f"ft8code がシンボルを出力しませんでした。\n"
        f"binary: {binary}\n"
        f"returncode: {result.returncode}\n"
        f"stdout: {stdout!r}\n"
        f"stderr: {result.stderr!r}")


def _ft8_symbols_to_pcm(symbols: list, audio_freq: int, rate: int) -> bytes:
    """8-FSK シンボル列を位相連続 S16_LE PCM に変換する (FT8: 160ms/sym, 6.25Hz間隔)。"""
    import numpy as np
    symbol_period = 0.160   # 160ms / symbol (6.25 baud)
    tone_spacing  = 6.25    # Hz
    n_per_sym = int(round(rate * symbol_period))
    amplitude = 24000
    parts: list = []
    phase = 0.0
    for sym in symbols:
        freq = audio_freq + sym * tone_spacing
        t = np.arange(n_per_sym) / rate
        wave = amplitude * np.sin(2 * np.pi * freq * t + phase)
        phase = (phase + 2 * np.pi * freq * n_per_sym / rate) % (2 * np.pi)
        parts.append(wave.astype(np.int16))
    if not parts:
        raise RuntimeError("symbol list is empty")
    return np.concatenate(parts).tobytes()


class FT8TxRequest(BaseModel):
    msg: str
    audio_freq: int = 1500
    rate: int = 12000
    is_ft4: bool = False
    tx_mode: str = ""   # "even"/"odd"/""  (空=自動選択なし)


@app.post("/radio/ft8_tx")
async def ft8_tx(req: FT8TxRequest):
    """FT8/FT4 メッセージを送信する。ft8code で PCM 生成 → aplay で送出。"""
    global tx_in_progress, _ft8_tx_active
    if tx_in_progress or _ft8_tx_active:
        raise HTTPException(status_code=409, detail="TX in progress")
    tx_in_progress = True
    _ft8_tx_active = True
    _mgr_sub.mute(14.0)
    loop = asyncio.get_running_loop()
    try:
        # PCM生成: ft8code (wsjtx) → numpy 8-FSK合成
        try:
            pcm_data = await loop.run_in_executor(
                None,
                lambda: _ft8_symbols_to_pcm(
                    _ft8_get_symbols(req.msg, req.is_ft4),
                    req.audio_freq, req.rate
                )
            )
        except RuntimeError as enc_err:
            raise HTTPException(status_code=500, detail=str(enc_err))

        def _play():
            # 次のFT8ピリオド境界まで精密待機
            # PCM生成に時間がかかるため、ここで再計算する
            period_s = 7.5 if req.is_ft4 else FT8_PERIOD_S
            now = time.time()
            # ピリオド境界から 0.5s 以内なら現在ピリオドをそのまま使う
            # （0.001s 後に呼ばれた場合に +1 で次へ飛ばすバグを防ぐ）
            current_start = int(now / period_s) * period_s
            if now - current_start < 0.5:
                next_start = current_start
            else:
                next_start = current_start + period_s

            # tx_mode 決定優先順: リクエスト > QSO > AUTO TX > なし
            if req.tx_mode in ("even", "odd"):
                tx_mode = req.tx_mode
            elif _qso.get("active"):
                tx_mode = _qso["tx_mode"]
            elif _auto_tx.get("active"):
                tx_mode = _auto_tx["mode"]
            else:
                tx_mode = None
            if tx_mode and not req.is_ft4:
                for _ in range(4):  # 最大4ピリオド(60秒)先まで探す
                    utc_check = int(next_start) % 60
                    pn = (utc_check // 15) % 4
                    is_even = pn % 2 == 0
                    if (tx_mode == "even" and is_even) or (tx_mode == "odd" and not is_even):
                        break
                    next_start += FT8_PERIOD_S  # 合わないピリオドをスキップ

            wait_sec = next_start - time.time()
            utc_at_tx = int(next_start) % 60
            print(f"[ft8_tx] waiting {wait_sec:.2f}s → TX at UTC {utc_at_tx}s (tx_mode={tx_mode})")
            # tx_pending を待機前に送信 → Androidのリストにすぐ表示（Auto TXと同じ動作）
            _ft8_broadcast({"type": "tx_pending",
                            "msg": req.msg,
                            "wait_sec": round(wait_sec, 1),
                            "utc_at_tx": utc_at_tx})
            subprocess.run(["pkill", "-9", "aplay"], capture_output=True)
            if wait_sec > 0.15:
                time.sleep(wait_sec - 0.10)
            while time.time() < next_start:
                time.sleep(0.002)
            _tx_start = time.time()
            print(f"[ft8_tx] TX START {time.strftime('%H:%M:%S', time.localtime(_tx_start))}.{int(_tx_start*1000)%1000:03d}  UTC={int(_tx_start)%60}s")
            rigctl_cmd("T 1")
            time.sleep(0.3)
            proc = subprocess.Popen(
                ["aplay", "-D", _alsa_playback_dev,
                 "-f", "S16_LE", "-r", str(req.rate), "-c", "1"],
                stdin=subprocess.PIPE, stderr=subprocess.PIPE
            )
            proc.stdin.write(pcm_data)
            proc.stdin.close()
            proc.wait(timeout=20)
            rigctl_cmd("T 0")

        await loop.run_in_executor(None, _play)
        return {"status": "ok", "msg": req.msg, "is_ft4": req.is_ft4}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        try: rigctl_cmd("T 0")
        except Exception: pass
        tx_in_progress = False
        _ft8_tx_active = False



@app.get("/ft8/debug_encode")
async def ft8_debug_encode(msg: str = Query(default="CQ TEST JA1XXX")):
    """ft8code の生出力とシンボル解析結果を返す（TX診断用）。"""
    binary = _ft8_find_encoder(False)
    if binary is None:
        # find コマンドで全探索して結果を表示
        fr = subprocess.run(["find", "/usr", "/opt", "/home", "-name", "ft8code"],
                            capture_output=True, text=True, timeout=10)
        return {"error": "ft8code not found",
                "find_output": fr.stdout,
                "hint": "sudo apt install wsjtx"}
    try:
        r = subprocess.run([binary, msg], capture_output=True, text=True, timeout=5)
        syms = _ft8_parse_symbols(r.stdout)
        return {
            "binary": binary,
            "stdout": r.stdout,
            "stderr": r.stderr,
            "returncode": r.returncode,
            "parsed_symbols": syms,
            "symbol_count": len(syms) if syms else 0,
        }
    except Exception as e:
        return {"error": str(e)}


class StartQsoRequest(BaseModel):
    first_msg: str           # step1: DX MY GRID
    dx_call: str
    my_call: str
    tx_mode: str = "odd"
    audio_freq: int = 1500
    initial_state: int = 0   # 0=step1, 2=step2, 4=step3, 6=step4(73)
    step_snr_msg: str = ""   # step1後のSNRのみステップ (Rなし。空なら省略してstep2へ直行)
    step2_msg: str = ""      # step2: DX MY R-NN (空なら step1 返信後すぐ step3 へ)
    step3_msg: str = ""      # step3: DX MY RR73 (空なら自動生成)
    step4_msg: str = ""      # step4: DX MY 73   (空なら step3 後に done)


@app.post("/ft8/start_qso")
async def ft8_start_qso(req: StartQsoRequest):
    """QSOシーケンスを開始する。デコード一覧タップ時にAndroidから呼ばれる。"""
    global _qso, _ft8_my_call
    dx = req.dx_call.upper()
    me = req.my_call.upper()
    if me:
        _ft8_my_call = me
    step3 = req.step3_msg.upper() if req.step3_msg else f"{dx} {me} RR73"
    step4 = req.step4_msg.upper() if req.step4_msg else ""
    # initial_state: 0→step1(state=1), 1→step2snr(state=2), 2→step2(state=3), 4→step3(state=5), 6→step4(state=6,done)
    wait_state = {0: 1, 1: 2, 2: 3, 4: 5, 6: 5}.get(req.initial_state, 1)
    first_tx = {1: req.first_msg.upper(),
                2: req.step_snr_msg.upper() if req.step_snr_msg else (req.step2_msg.upper() if req.step2_msg else step3),
                3: req.step2_msg.upper() if req.step2_msg else step3,
                5: step4 if req.initial_state == 6 else step3}.get(wait_state, req.first_msg.upper())
    # extract snr_sent from step2_msg (e.g. "JA1XXX JA2YYY R-05" → "R-05")
    step2_parts = (req.step2_msg or "").upper().split()
    snr_sent = step2_parts[2] if len(step2_parts) >= 3 else ""
    _qso["active"]    = True
    _qso["dx_call"]   = dx
    _qso["my_call"]   = me
    _qso["state"]     = wait_state
    _qso["tx_mode"]   = req.tx_mode
    _qso["audio_freq"]= req.audio_freq
    _qso["step1_msg"]   = req.first_msg.upper()
    _qso["step_snr_msg"]= req.step_snr_msg.upper()
    _qso["step2_msg"]   = req.step2_msg.upper()
    _qso["step3_msg"]   = step3
    _qso["step4_msg"]   = step4
    _qso["cq_sender"]   = False   # DX指定呼び出し側: R-SNR使用
    _qso["next_msg"]  = first_tx
    _qso["snr_rcvd"]   = ""
    _qso["snr_sent"]   = snr_sent
    # step2_msg の R±NN から初期ピーク値を設定（以降は改善時のみ更新）
    _initial_peak = None
    if len(step2_parts) >= 3 and step2_parts[2].startswith("R"):
        try: _initial_peak = int(step2_parts[2][1:])
        except ValueError: pass
    _qso["snr_dx_peak"] = _initial_peak
    # AUTO TX を有効化して送信を委譲
    _auto_tx["active"]    = True
    _auto_tx["msg"]       = first_tx
    _auto_tx["mode"]      = req.tx_mode
    _auto_tx["audio_freq"]= req.audio_freq
    _ft8_broadcast({"type": "qso_state", "state": "pending",
                    "dx_call": dx, "msg": first_tx, "tx_mode": req.tx_mode})
    return {"ok": True, **_qso}


@app.post("/ft8/cancel_qso")
async def ft8_cancel_qso():
    """QSOシーケンスをキャンセルする。"""
    global _qso
    _qso["active"]      = False
    _qso["state"]       = 4
    _auto_tx["active"]  = False
    return {"ok": True}


@app.post("/ft8/auto_tx")
async def ft8_set_auto_tx(request: Request):
    """AutoTXの有効/無効とパラメータを設定する。"""
    global _auto_tx
    body = await request.json()
    _auto_tx["active"]     = bool(body.get("active", False))
    _auto_tx["mode"]       = str(body.get("mode", "even"))
    _auto_tx["msg"]        = str(body.get("msg", ""))
    _auto_tx["audio_freq"] = int(body.get("audio_freq", 1500))
    return {**_auto_tx}


@app.get("/ft8/auto_tx")
async def ft8_get_auto_tx():
    """AutoTX現在の状態を返す。"""
    return {**_auto_tx}


@app.post("/ft8/cq_auto")
async def ft8_cq_auto(request: Request):
    """CQ AUTO モード: CQ送信→DX返信で自動QSO→73後にCQ再開。"""
    global _cq_auto, _auto_tx, _qso, _ft8_my_call
    body = await request.json()
    if not body.get("active"):
        _cq_auto["active"] = False
        _auto_tx["active"] = False
        _qso["active"] = False
        return {"ok": True, "active": False}
    _cq_auto["active"]    = True
    _cq_auto["msg"]       = str(body.get("msg", ""))
    _cq_auto["mode"]      = str(body.get("mode", "even"))
    _cq_auto["audio_freq"]= int(body.get("audio_freq", 1500))
    _cq_auto["my_call"]   = str(body.get("my_call", ""))
    _cq_auto["my_grid"]   = str(body.get("my_grid", ""))
    if _cq_auto["my_call"]:
        _ft8_my_call = _cq_auto["my_call"].upper()
    _auto_tx["active"]    = True
    _auto_tx["msg"]       = _cq_auto["msg"]
    _auto_tx["mode"]      = _cq_auto["mode"]
    _auto_tx["audio_freq"]= _cq_auto["audio_freq"]
    print(f"[cq_auto] start: {_cq_auto['msg']} mode={_cq_auto['mode']}")
    return {**_cq_auto}


# ---------- CW send_morse (Hamlib rigctld b コマンド) ----------

_morse_sock: "Optional[socket.socket]" = None  # type: ignore
_morse_lock = threading.Lock()
_morse_sending = False
_morse_stop_event = threading.Event()


def _abort_morse():
    """実行中の send_morse を中断する（待機を即座に解除して PTT OFF）"""
    global _morse_sock, _morse_sending
    _morse_stop_event.set()
    with _morse_lock:
        s = _morse_sock
        _morse_sock = None
    if s:
        try:
            s.close()
        except Exception:
            pass
        # \stop_morse: hamlib が内部キーヤーのバッファをクリアする (FT-991 等の New CAT では KY; を送出)
        # IC-7300はこのコマンドをサポートしないため2秒タイムアウトになる。
        # アクティブなセッション停止時のみ呼ぶ（初回送信時はスキップ）。
        try:
            rigctl_cmd_priority("\\stop_morse")
        except Exception:
            pass
    _morse_sending = False


@app.post("/cw/send_morse")
def cw_send_morse(text: str = Form(...), wpm: int = Form(default=20),
                  ptt_poll: bool = Form(default=False)):
    """テキストを Hamlib send_morse (rigctld b コマンド) で CW 送信する。
    ptt_poll=True: PTT状態をポーリングして終了検出 (FT-991等 CAT PTT対応リグ向け)
    ptt_poll=False: 推定送信時間で待機 (IC-7300/IC-705等 内部キーヤー向け・デフォルト)"""
    global _morse_sock, _morse_sending
    text = text.strip().upper()
    if not text:
        raise HTTPException(status_code=400, detail="empty text")
    _abort_morse()
    _morse_stop_event.clear()
    _morse_sending = True

    # 送信推定時間を計算 (PARIS 標準: dit = 1200/wpm ms)
    # 1文字平均 13 dit (エレメント+符号間) + 語間スペースは +4 dit
    n_chars = sum(1 for c in text if c != ' ')
    n_spaces = text.count(' ')
    est_dits = n_chars * 13 + n_spaces * 4 + 5
    est_sec = est_dits * 1200.0 / max(5, min(60, wpm)) / 1000 + 0.3

    # CW TX は常に RIG(CI-V) PTT を使用: IC-7300 等 RTS では動作しないため
    _cw_orig_ptt = current_ptt_type
    _cw_switch = (
        current_ptt_type.upper() not in ("RIG", "CAT") and
        bool(current_model) and bool(current_cat) and not _rigctld_restarting
    )

    def worker():
        global _morse_sock, _morse_sending
        sock = None
        try:
            if _cw_switch:
                print(f"[{_ts()}] [morse] switching to RIG PTT for CW TX (was {_cw_orig_ptt})", flush=True)
                start_rigctld(current_model, current_cat, current_baud, current_ptt, "RIG")
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(30.0)
            sock.connect(("localhost", 4532))
            with _morse_lock:
                _morse_sock = sock
            sock.sendall(f"b {text}\n".encode())
            try:
                sock.recv(4096)
            except Exception:
                pass
            if ptt_poll:
                # PTTポーリングモード: PTT=1を確認してからPTT=0になるまで待機
                # FT-991等、内部キーヤー中にCAT get_pttが正しく動作するリグ向け
                print(f"[{_ts()}] [morse] ptt_poll mode, waiting TX start (wpm={wpm} chars={n_chars})", flush=True)
                tx_started = False
                for _ in range(30):  # 最大3秒待機
                    if _morse_stop_event.is_set():
                        break
                    ptt_raw = rigctl_cmd_priority("t")
                    if ptt_raw and ptt_raw.strip().split("\n")[0].strip() == "1":
                        tx_started = True
                        break
                    _morse_stop_event.wait(timeout=0.1)
                if tx_started:
                    print(f"[{_ts()}] [morse] TX started, polling until PTT=0, deadline={est_sec+5.0:.1f}s", flush=True)
                    deadline = time.time() + est_sec + 5.0
                    while time.time() < deadline and not _morse_stop_event.is_set():
                        ptt_raw = rigctl_cmd_priority("t")
                        if ptt_raw and ptt_raw.strip().split("\n")[0].strip() == "0":
                            print(f"[{_ts()}] [morse] TX ended (PTT=0)", flush=True)
                            break
                        _morse_stop_event.wait(timeout=0.5)
                    else:
                        print(f"[{_ts()}] [morse] TX stopped by user", flush=True)
                else:
                    print(f"[{_ts()}] [morse] TX never started, falling back to time wait", flush=True)
                    _morse_stop_event.wait(timeout=est_sec)
            else:
                # 時間ベースモード: 推定送信時間だけ待機
                # IC-7300/IC-705等、内部キーヤー中にCAT PTTが0を返すリグ向け
                print(f"[{_ts()}] [morse] time mode, waiting {est_sec:.1f}s (wpm={wpm} chars={n_chars})", flush=True)
                _morse_stop_event.wait(timeout=est_sec)
                if _morse_stop_event.is_set():
                    print(f"[{_ts()}] [morse] TX stopped by user", flush=True)
                else:
                    print(f"[{_ts()}] [morse] TX complete (estimated)", flush=True)
        except Exception as e:
            print(f"[{_ts()}] [morse] error: {e}", flush=True)
        finally:
            with _morse_lock:
                if _morse_sock is sock:
                    _morse_sock = None
            if sock:
                try:
                    sock.close()
                except Exception:
                    pass
            try:
                rigctl_cmd_priority("\\stop_morse")
            except Exception:
                pass
            # 内部キーヤー使用時は T 1 を送っていないため T 0 不要
            # (IC-705/IC-7300 は内部キーヤー完了後に自動でRXへ戻る)
            _morse_sending = False
            if _cw_switch and current_model and current_cat:
                print(f"[{_ts()}] [morse] restoring PTT to {_cw_orig_ptt}", flush=True)
                start_rigctld(current_model, current_cat, current_baud, current_ptt, _cw_orig_ptt)

    threading.Thread(target=worker, daemon=True).start()
    return {"status": "ok"}


@app.post("/cw/stop_morse")
def cw_stop_morse():
    """CW 送信を中断して PTT を解除する"""
    _abort_morse()   # \stop_morse + socket close 済み
    rigctl_cmd_priority("T 0")
    return {"status": "stopped"}


@app.get("/cw/morse_status")
def cw_morse_status():
    """send_morse が実行中かどうかを返す"""
    return {"sending": _morse_sending}


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
        # 追跡外の既存プロセス(手動起動・前セッション残留)も kill してポート競合を防ぐ
        try:
            subprocess.run(["pkill", "-f", "cw_bridge.py"], timeout=3)
            time.sleep(0.5)
        except Exception:
            pass
        try:
            _cw_bridge_proc = subprocess.Popen([_VENV_PY, _CW_BRIDGE_PY, dev])
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
        try:
            subprocess.run(["pkill", "-f", "cw_bridge.py"], timeout=3)
        except Exception:
            pass
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


@app.get("/cw/time")
def cw_time():
    return {"ms": int(time.time() * 1000)}


@app.get("/time")
def get_time():
    """Pi の現在 Unix 時刻 (ms) を返す。Android 側クロックオフセット補正用。"""
    return {"ms": int(time.time() * 1000)}


@app.post("/admin/set_time")
async def admin_set_time(request: Request):
    """時刻を標準時間に同期する。
    1. NTP に接続できる場合: ntpdate / chronyc でインターネット標準時間と同期（最高精度）
    2. NTP 不可の場合: Android が送信した時刻（Android は通信網経由で NTP 同期済み）で代替
    FT8 デコードに必要な ±1 秒以内の精度を確保する。"""
    try:
        data = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON")
    android_ms = data.get("ms", 0)
    pi_ms_before = int(time.time() * 1000)

    # ── 方法 1: chronyc makestep (chrony インストール済みの場合) ──
    r = subprocess.run(["sudo", "-n", "chronyc", "makestep"], capture_output=True, timeout=8)
    if r.returncode == 0:
        drift_ms = pi_ms_before - int(time.time() * 1000)
        print(f"[{_ts()}] [set_time] NTP(chrony) ok drift≈{drift_ms:+d}ms", flush=True)
        return {"ok": True, "source": "ntp_chrony", "drift_ms": drift_ms}

    # ── 方法 2: ntpdate (ntpdate インストール済みの場合) ──
    for srv in ["pool.ntp.org", "ntp.nict.jp"]:
        r = subprocess.run(["sudo", "-n", "ntpdate", "-s", srv], capture_output=True, timeout=8)
        if r.returncode == 0:
            drift_ms = pi_ms_before - int(time.time() * 1000)
            print(f"[{_ts()}] [set_time] NTP(ntpdate/{srv}) ok drift≈{drift_ms:+d}ms", flush=True)
            return {"ok": True, "source": f"ntp_ntpdate/{srv}", "drift_ms": drift_ms}

    # ── 方法 3: timedatectl set-ntp true で即時同期要求 ──
    r = subprocess.run(["sudo", "-n", "timedatectl", "set-ntp", "true"], capture_output=True, timeout=5)
    if r.returncode == 0:
        time.sleep(2)
        drift_ms = pi_ms_before - int(time.time() * 1000)
        print(f"[{_ts()}] [set_time] NTP(timedatectl) requested drift≈{drift_ms:+d}ms", flush=True)
        return {"ok": True, "source": "ntp_timedatectl", "drift_ms": drift_ms}

    # ── 方法 4: フォールバック — Android 端末の時刻で設定 ──
    # date -s "@timestamp" 形式は常に UTC で解釈されるためタイムゾーン問題を回避できる
    if not android_ms:
        return {"ok": False, "source": "none", "drift_ms": 0}
    ts_sec = android_ms // 1000
    drift_ms = android_ms - pi_ms_before
    r = subprocess.run(["sudo", "-n", "date", "-s", f"@{ts_sec}"], capture_output=True, timeout=5)
    ok = r.returncode == 0
    import datetime as _dt
    date_str = _dt.datetime.fromtimestamp(ts_sec, tz=_dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    print(f"[{_ts()}] [set_time] Android fallback drift={drift_ms:+d}ms set={date_str} ok={ok}", flush=True)
    return {"ok": ok, "source": "android", "drift_ms": drift_ms}


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


def _kiss_unescape(data: bytes) -> bytes:
    FEND, FESC, TFEND, TFESC = 0xC0, 0xDB, 0xDC, 0xDD
    out = bytearray()
    i = 0
    while i < len(data):
        b = data[i]
        if b == FESC and i + 1 < len(data):
            nxt = data[i + 1]
            if nxt == TFEND:
                out.append(FEND); i += 2; continue
            elif nxt == TFESC:
                out.append(FESC); i += 2; continue
        out.append(b)
        i += 1
    return bytes(out)


def _decode_ax25_addr(b: bytes):
    call = "".join(chr(c >> 1) for c in b[:6]).strip()
    ssid = (b[6] >> 1) & 0x0F
    last = bool(b[6] & 0x01)
    return call, ssid, last


def _decode_ax25_frame(frame: bytes):
    """KISSアンエスケープ済みのAX.25フレームをデコードし、送信元コールサイン/パス/情報部を返す。"""
    pos = 0
    addrs = []
    while True:
        if pos + 7 > len(frame):
            return None
        call, ssid, last = _decode_ax25_addr(frame[pos:pos + 7])
        addrs.append((call, ssid))
        pos += 7
        if last or len(addrs) > 10:
            break
    if len(addrs) < 2 or pos + 2 > len(frame):
        return None
    pos += 2  # control + PID
    info = frame[pos:]
    dest_call, _ = addrs[0]
    src_call, src_ssid = addrs[1]
    path = addrs[2:]
    try:
        info_str = info.decode("ascii", errors="replace")
    except Exception:
        info_str = ""
    return {
        "src": f"{src_call}-{src_ssid}" if src_ssid else src_call,
        "dest": dest_call,
        "path": [f"{c}-{s}" if s else c for c, s in path],
        "info": info_str,
    }


def _parse_mic_e(dest6: str, info: str) -> dict | None:
    """Mic-E形式の位置情報デコード (APRS Protocol Reference §10)。"""
    try:
        if len(dest6) < 6 or len(info) < 9:
            return None

        def _df(ch):
            ch = ch.upper()
            if '0' <= ch <= '9': return int(ch), 0
            if 'A' <= ch <= 'J': return ord(ch) - ord('A'), 1
            if 'P' <= ch <= 'Y': return ord(ch) - ord('P'), 1
            return 0, 0

        d1, _  = _df(dest6[0])
        d2, _  = _df(dest6[1])
        d3, _  = _df(dest6[2])
        d4, ns = _df(dest6[3])   # N/S: 1=North
        d5, lo = _df(dest6[4])   # 経度オフセット: 1=+100°
        d6, ew = _df(dest6[5])   # E/W: 1=East

        # D1=緯度度の十の位, D2=一の位, D3=分の十の位, D4=分の一の位, D5=分の0.1, D6=分の0.01
        lat_deg = d1 * 10 + d2
        lat_min = d3 * 10 + d4 + d5 / 10.0 + d6 / 100.0
        lat = lat_deg + lat_min / 60.0
        if not ns:
            lat = -lat

        lon_d  = ord(info[1]) - 28
        lon_m  = ord(info[2]) - 28
        lon_mh = ord(info[3]) - 28

        if lo:
            lon_d += 100
        if 180 <= lon_d <= 189:
            lon_d -= 80
        elif 190 <= lon_d <= 199:
            lon_d -= 190
        if lon_m >= 60:
            lon_m -= 60

        lon = lon_d + (lon_m + lon_mh / 100.0) / 60.0
        # FTX-1 firmware bug: D6 uses plain digit ('0'-'9') instead of P-Y range for East.
        # When lo=1 and D6 is a plain digit, the East flag is missing — treat as East.
        ch6 = dest6[5].upper()
        if not ew:
            if not (lo and '0' <= ch6 <= '9'):
                lon = -lon

        # Mic-E info layout: [0]=DTI [1-3]=lon deg/min/mh [4-6]=speed/course [7]=sym_code [8]=sym_table
        sym_code  = info[7]
        sym_table = info[8]
        raw_comment = info[9:].strip() if len(info) > 9 else ""
        # 先頭のエンコードバイト(/'_6 等のYaesステータスコード/アルチチュードエンコード)を除去し
        # 最初のアルファベットから後を表示用コメントとする
        idx = next((i for i, c in enumerate(raw_comment) if c.isalpha()), -1)
        comment = raw_comment[idx:].strip() if idx >= 0 else ""

        print(f"[APRS/mic-e] dest6={repr(dest6)} lat={round(lat,4)} lon={round(lon,4)} sym={repr(sym_table+sym_code)}", flush=True)
        return {
            "lat": round(lat, 6),
            "lon": round(lon, 6),
            "symbol": sym_table + sym_code,
            "comment": comment[:40],
        }
    except Exception:
        return None


def _parse_aprs_position(info: str, dest: str = "") -> dict | None:
    """APRS位置情報デコード。非圧縮形式 (!/=/@) および Mic-E に対応。"""
    if not info:
        return None
    dti = info[0]
    # Mic-E (バックティック/アポストロフィDTI)
    if dti in ('`', "'", '\x1c', '\x1d'):
        return _parse_mic_e(dest, info) if dest else None
    body = info[1:]
    if dti in ("/", "@"):
        if len(body) < 7:
            return None
        body = body[7:]  # タイムスタンプ(7文字)を読み飛ばす
    elif dti not in ("!", "="):
        return None
    if len(body) < 19:
        return None
    lat_str, sym_table, lon_str, sym_code = body[0:8], body[8], body[9:18], body[18]
    comment = body[19:]
    try:
        lat = int(lat_str[0:2]) + float(lat_str[2:7]) / 60.0
        if lat_str[7] == "S":
            lat = -lat
        lon = int(lon_str[0:3]) + float(lon_str[3:8]) / 60.0
        if lon_str[8] == "W":
            lon = -lon
    except (ValueError, IndexError):
        return None
    return {"lat": lat, "lon": lon, "symbol": sym_table + sym_code, "comment": comment.strip()}


def _record_heard(ax: dict):
    global aprs_notify_queue
    info = ax["info"]
    pos = _parse_aprs_position(info, dest=ax.get("dest", ""))
    now = time.time()
    entry = {
        "call": ax["src"],
        "path": ax["path"],
        "lat": pos["lat"] if pos else None,
        "lon": pos["lon"] if pos else None,
        "symbol": pos["symbol"] if pos else None,
        "comment": (pos["comment"] if pos else info)[:40],
        "heard_at": now,
    }
    with aprs_heard_lock:
        aprs_heard[:] = [e for e in aprs_heard if e["call"] != entry["call"]]
        aprs_heard.insert(0, entry)
        del aprs_heard[100:]

        # ★ 無線機のようなビーコン受信ポップアップ用。同一局は heard_suppress_sec
        #   経過するまで再通知しない(頻繁なビーコンでポップアップが連発しないように)。
        last = _aprs_notify_last.get(entry["call"], 0)
        if now - last >= aprs_notify_suppress_sec:
            _aprs_notify_last[entry["call"]] = now
            aprs_notify_queue.append(entry)
            del aprs_notify_queue[:-10]
            aprs_notify_queue_android.append(entry)
            del aprs_notify_queue_android[:-10]

    fmt = repr(info[0]) if info else "?"
    sym = pos["symbol"] if pos else "none"
    print(f"[APRS/rx] heard {entry['call']} pos={'yes' if pos else 'no'} dti={fmt} symbol={repr(sym)}", flush=True)


def _handle_kiss_frame(raw: bytes):
    if not raw or (raw[0] & 0x0F) != 0x00:
        return  # ポート/コマンドニブルが0(データフレーム)以外は無視
    frame = _kiss_unescape(raw[1:])
    ax = _decode_ax25_frame(frame)
    if ax is not None:
        _record_heard(ax)


def _aprs_rx_loop():
    """direwolfのKISSポートに接続し続け、受信パケットをデコードしてaprs_heardに記録する。
    TX方式(DireWolf/FTX-1)やTXの有無に関わらず、direwolfが起動していればこれだけで受信を拾える。"""
    global aprs_rx_running
    print("[APRS/rx] monitor thread started", flush=True)
    while aprs_rx_running:
        s = None
        buf = bytearray()
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(2.0)
            s.connect((KISS_HOST, KISS_PORT))
            s.settimeout(1.0)
            print("[APRS/rx] KISS connected", flush=True)
            while aprs_rx_running:
                try:
                    chunk = s.recv(1024)
                except socket.timeout:
                    continue
                if not chunk:
                    break
                buf.extend(chunk)
                FEND = 0xC0
                while True:
                    try:
                        start = buf.index(FEND)
                    except ValueError:
                        buf.clear()
                        break
                    try:
                        end = buf.index(FEND, start + 1)
                    except ValueError:
                        del buf[:start]
                        break
                    frame_bytes = bytes(buf[start + 1:end])
                    del buf[:end]
                    if frame_bytes:
                        try:
                            _handle_kiss_frame(frame_bytes)
                        except Exception as e:
                            print(f"[APRS/rx] decode error: {e}", flush=True)
        except Exception as e:
            print(f"[APRS/rx] connect error: {e}", flush=True)
        finally:
            if s:
                try:
                    s.close()
                except Exception:
                    pass
        if aprs_rx_running:
            time.sleep(2.0)
    print("[APRS/rx] monitor thread stopped", flush=True)


def _write_rx_only_direwolf_conf(baud: int, sound_device: str):
    """PTT/MYCALL無しの受信専用direwolf設定(FTX-1内蔵モデムTX中/APRS停止中でも受信だけは続ける用)。
    ★ ADEVICE は "ADEVICE <入力> <出力>" の順。TX用conf(ADEVICE null {device})は入力=null
      (=受信オーディオを一切読まない)になっていたため、受信専用confでは逆に出力側をnullにする。"""
    modem = 9600 if baud == 9600 else 1200
    conf = f"ADEVICE {sound_device} null\nCHANNEL 0\nMODEM {modem}\n"
    if modem == 9600:
        conf += "ARATE 48000\n"
    conf += "KISSPORT 8001\nAGWPORT 8050\n"
    _atomic_write(str(_HOME_DIR / "direwolf.conf"), conf)


def _ensure_aprs_rx(cfg: "AprsConfig"):
    """受信監視スレッド/direwolfのRX待受状態を、現在のTX状況に合わせて整える。
    バックグラウンドスレッドから呼ぶこと(direwolf再起動を伴う場合があるため)。"""
    global aprs_rx_running, aprs_rx_thread, _aprs_direwolf_conf_kind
    if not cfg.enabled:
        aprs_rx_running = False
        return

    # DireWolf-TXが稼働中はそちらのフル設定(PTT/MYCALL込み)がそのまま受信も兼ねる。
    # それ以外(FTX-1内蔵モデムTX中 or APRS停止中)は受信専用の軽量設定に切り替える。
    need_own_rx_conf = cfg.use_rig_modem or not aprs_running
    if need_own_rx_conf and _aprs_direwolf_conf_kind != ("rx", cfg.baud):
        _write_rx_only_direwolf_conf(cfg.baud, cfg.sound_device)
        subprocess.run(["sudo", "systemctl", "restart", "direwolf"], capture_output=True)
        _aprs_direwolf_conf_kind = ("rx", cfg.baud)
        _wait_direwolf_kiss_ready(15.0)

    _start_aprs_rx_thread_if_needed()


def _start_aprs_rx_thread_if_needed():
    global aprs_rx_running, aprs_rx_thread
    if not (aprs_rx_thread and aprs_rx_thread.is_alive()):
        aprs_rx_running = True
        aprs_rx_thread = threading.Thread(target=_aprs_rx_loop, daemon=True)
        aprs_rx_thread.start()
    else:
        aprs_rx_running = True


def _wait_direwolf_kiss_ready(timeout: float = 15.0) -> bool:
    """KISS ポート (8001) が LISTEN になるまで最大 timeout 秒待つ (Pi Zero は Hamlib 初期化で ~10s かかる)"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            s = socket.socket()
            s.settimeout(0.5)
            s.connect((KISS_HOST, KISS_PORT))
            s.close()
            return True
        except OSError:
            time.sleep(0.3)
    return False


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
        lu = line.upper()
        if any(k in lu for k in ("PTT ON", "PTT KEY", "TRANSMIT", "SENDING PACKET", "AUDIO: TRANSMIT", "CHANNEL 0")):
            tx_started = True
        if any(k in lu for k in ("PTT OFF", "TX COMPLETE", "AUDIO: PTT OFF")):
            tx_done = True


def aprs_loop():
    global aprs_running, tx_in_progress, aprs_last_heartbeat, normal_freq, last_user_freq_change
    try:
        while aprs_running:
            if tx_in_progress:
                time.sleep(0.1)
                continue
            # Don't start APRS TX while radio is keyed by CW TX or voice PTT
            if radio_cache.get("tx", False):
                time.sleep(0.5)
                continue
            loop_start = time.time()
            if time.time() - aprs_last_heartbeat > 15:
                aprs_running = False
                break
            cur = rigctl_cmd_priority("f")
            try:
                read_freq = int(cur.split()[0])
                aprs_freq_hz = int(aprs_freq * 1_000_000)
                if abs(read_freq - aprs_freq_hz) > 100:
                    normal_freq = read_freq  # save home freq before switching to APRS
                if normal_freq is None:
                    normal_freq = 0  # already on APRS freq at start: no home freq to restore
            except Exception:
                time.sleep(1)
                continue
            try:
                mode_raw = rigctl_cmd("m")
                mode = mode_raw.split()[0].upper() if mode_raw else ""
            except Exception:
                mode = ""
            if "FM" not in mode:
                time.sleep(1)
                continue
            if aprs_use_gps and (latest_gps["lat"] != 0.0 or latest_gps["lon"] != 0.0):
                lat = latest_gps["lat"]
                lon = latest_gps["lon"]
            else:
                lat = aprs_manual_lat
                lon = aprs_manual_lon
            print(f"[APRS] TX lat={lat:.5f} lon={lon:.5f} (gps={aprs_use_gps} latest={latest_gps})")
            tx_in_progress = True
            # ★ poll_rig/poll_signal は radio_cache["tx"] を見てポーリング負荷を下げる仕組みが
            #   既に音声PTTにはあるが、APRS送信では今まで立てていなかった。そのため送信中も
            #   rigctld へのポーリングが止まらず、direwolfのPTT(同じrigctld経由)と競合して
            #   USBバスリセット(FTX-1のCDCごと瞬断)を誘発していた。ここで明示的に立てる。
            radio_cache["tx"] = True
            aprs_last_heartbeat = time.time()
            rigctl_cmd_priority(f"F {int(aprs_freq * 1_000_000)}")
            time.sleep(0.25)
            sym = (aprs_cfg.symbol or ">") if aprs_cfg else ">"
            comment = (getattr(aprs_cfg, "comment", "") or "").encode("ascii", errors="ignore").decode("ascii")
            info = f"!{aprs_lat(lat)}/{aprs_lon(lon)}{sym}{comment}"
            _path_str = aprs_cfg.path or ""
            _path = _path_str.split(",") if _path_str and _path_str.upper() not in ("NONE", "DIRECT") else []
            ax25 = build_ax25_ui_frame(
                src_call=aprs_cfg.callsign, src_ssid=aprs_cfg.ssid,
                dest_call=aprs_cfg.destination or "APDW18", dest_ssid=0,
                path=_path, info=info
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
                radio_cache["tx"] = False
                # direwolf KISS ポートが応答するまで待つ（再起動が必要なら実施）
                if not _wait_direwolf_kiss_ready(2.0):
                    print("[APRS] restarting direwolf (KISS port not ready)")
                    subprocess.run(["pkill", "-9", "aplay"], capture_output=True)
                    time.sleep(0.5)
                    subprocess.run(["sudo", "systemctl", "restart", "direwolf"],
                                   capture_output=True, timeout=10)
                    _wait_direwolf_kiss_ready(15.0)
                else:
                    time.sleep(2)
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
            radio_cache["tx"] = False
            elapsed = time.time() - loop_start
            # 細切れ sleep で aprs_running=False に素早く反応する
            wait_end = time.time() + max(0, aprs_interval - elapsed)
            while time.time() < wait_end and aprs_running:
                time.sleep(0.5)
    except Exception as e:
        print(f"[APRS] thread crashed: {e}")
    finally:
        tx_in_progress = False
        radio_cache["tx"] = False
        aprs_running = False


# ---------- APRS: 無線機内蔵モデム(FTX-1 CAT)経由 ----------
# FTX-1 CATオペレーションリファレンスマニュアルの EX コマンド(表3 メニュー一覧表)に基づく。
# EX P1(2桁) P2(2桁) P3(2桁) P4(可変桁) ; 形式。rigctld の send_cmd("w <生CAT>") でそのまま無線機に転送する。
_BCN_INTERVAL_MAP = {30: 0, 60: 1, 120: 2, 180: 3, 300: 4, 600: 5}  # aprsIntervalSec(sec) -> BCN INTERVAL TIME index


def _rig_ex_set(p1: int, p2: int, p3: int, p4) -> str:
    """FTX-1 の EX メニュー項目(P1/P2/P3)に値P4を設定する。rigctld経由の生CATコマンド送信。
    ★ rig_lockで直列化し、他のポーリングや互いのコマンドと衝突しないようにする
      (単発コマンド自体は_rig_send_no_replyがロックを取らない設計のため)。"""
    cmd = f"EX{p1:02d}{p2:02d}{p3:02d}{p4};"
    with rig_lock:
        raw = _rig_send_no_reply(f"w {cmd}")
    print(f"[APRS/rig] {cmd} -> '{raw.strip()}'", flush=True)
    return raw


def _rig_set_vfo_freq(vfo_cmd: str, freq_hz: int) -> str:
    """MAIN(FA)/SUB(FB) VFOの周波数をCAT直接コマンドで設定する。"""
    cmd = f"{vfo_cmd}{freq_hz:09d};"
    with rig_lock:
        raw = _rig_send_no_reply(f"w {cmd}")
    print(f"[APRS/rig] {cmd} -> '{raw.strip()}'", flush=True)
    return raw


def _rig_aprs_configure(cfg: "AprsConfig"):
    """無線機内蔵APRSモデムをCAT経由で設定する。direwolf/USBオーディオは一切使わない。"""
    freq_hz = int(cfg.freq * 1_000_000)
    modem_sel = cfg.modem_sel if cfg.modem_sel in (1, 2, 3) else 2

    # ★ MODEM SELECT=AUTOの場合、明示的にQSYさせると無線機側で意図しない周波数に
    #   変わってしまう問題が確認されたため、AUTO時は周波数を触らない。
    #   MAIN/SUB選択時は、それぞれの側のVFOをAPRS周波数に合わせる。
    if modem_sel == 2:      # MAIN
        _rig_set_vfo_freq("FA", freq_hz)
    elif modem_sel == 3:    # SUB
        _rig_set_vfo_freq("FB", freq_hz)

    # APRS SETTING > GENERAL > MODEM SELECT: 0:OFF 1:AUTO 2:MAIN 3:SUB
    _rig_ex_set(6, 1, 1, modem_sel)
    # APRS SETTING > GENERAL > MODEM TYPE: 0:1200bps 1:9600bps
    _rig_ex_set(6, 1, 2, 1 if cfg.baud == 9600 else 0)
    modem_sel_name = {1: "AUTO", 2: "MAIN", 3: "SUB"}.get(modem_sel, "MAIN")
    print(f"[APRS/rig] configured: modem={modem_sel_name} type={cfg.baud}bps freq={cfg.freq}MHz "
          f"(コールサイン/シンボル/位置情報は無線機側メニューで設定してください)", flush=True)


def _rig_aprs_watch_loop():
    """無線機内蔵モデム使用時、M5からのハートビートが途絶えたらビーコンを止める安全装置。
    実際のビーコン送信タイミングは無線機自身のAUTO BEACON機能が管理するため、ここではKISS送出は行わない。"""
    global aprs_running
    while aprs_running:
        if time.time() - aprs_last_heartbeat > 15:
            print("[APRS/rig] heartbeat lost -> stopping rig beacon", flush=True)
            _rig_ex_set(7, 1, 1, 0)  # APRS BEACON > BEACON SET. > BEACON TYPE = 0:OFF
            radio_cache["tx"] = False
            aprs_running = False
            break
        time.sleep(1.0)


@app.post("/aprs_config")
def update_aprs_config(cfg: AprsConfig):
    global aprs_use_gps, aprs_manual_lat, aprs_manual_lon, aprs_cfg, aprs_notify_suppress_sec
    global _last_rig_aprs_key
    aprs_use_gps = cfg.use_gps
    aprs_manual_lat = cfg.manual_lat
    aprs_manual_lon = cfg.manual_lon
    aprs_cfg = cfg
    aprs_notify_suppress_sec = max(0, cfg.heard_suppress_sec)

    if cfg.use_rig_modem:
        # 無線機内蔵APRSモデムを使う場合、TX(CAT)自体はdirewolf/USBオーディオに一切触れない。
        # ★ CAT設定コマンドはタイムアウト・リトライを挟むと数秒かかることがあり、
        #   同期的に実行するとM5側のHTTPタイムアウトより長引いて失敗と誤判定される。
        #   DireWolf方式(direwolf再起動)と同様にバックグラウンドスレッドで実行する。
        # ★ 定期再送時に同じ設定で繰り返しCATコマンドを送らないよう、変更があった時だけ実行する。
        rig_key = (cfg.freq, cfg.baud, cfg.modem_sel)
        if rig_key != _last_rig_aprs_key:
            _last_rig_aprs_key = rig_key
            threading.Thread(target=_rig_aprs_configure, args=(cfg,), daemon=True).start()
        # ★ TXはCAT制御でもRXは別問題(オーディオデコード)なので、受信専用direwolfは
        #   別スレッドで独立に用意する。
        threading.Thread(target=_ensure_aprs_rx, args=(cfg,), daemon=True).start()
        return {"status": "ok"}

    cat_device = cfg.cat_device
    if not cat_device.startswith("/dev/"):
        cat_device = f"/dev/{cat_device}"
    modem = 1200 if cfg.baud == 1200 else 9600
    new_conf = (f"ADEVICE {cfg.sound_device} {cfg.sound_device}\nCHANNEL 0\n"
                f"MYCALL {cfg.callsign}-{cfg.ssid}\nMODEM {modem}\n")
    if modem == 9600:
        new_conf += "ARATE 48000\n"
    new_conf += "KISSPORT 8001\nAGWPORT 8050\nPTT RIG 2 localhost:4532\n"

    conf_path = str(_HOME_DIR / "direwolf.conf")
    try:
        with open(conf_path) as f:
            existing_conf = f.read()
    except Exception:
        existing_conf = ""
    conf_changed = existing_conf.strip() != new_conf.strip()
    _atomic_write(conf_path, new_conf)

    def _restart_and_track_rx():
        global _aprs_direwolf_conf_kind
        if conf_changed:
            print(f"[aprs_config] direwolf.conf changed, restarting direwolf", flush=True)
            subprocess.run(["sudo", "systemctl", "restart", "direwolf"])
        else:
            print(f"[aprs_config] direwolf.conf unchanged, skipping restart", flush=True)
        _aprs_direwolf_conf_kind = ("tx", cfg.baud)
        if cfg.enabled:
            _wait_direwolf_kiss_ready(15.0)
            _start_aprs_rx_thread_if_needed()
        else:
            global aprs_rx_running
            aprs_rx_running = False

    threading.Thread(target=_restart_and_track_rx, daemon=True).start()
    return {"status": "ok"}


@app.post("/aprs_start")
def aprs_start(cfg: AprsStart):
    global aprs_running, aprs_thread, aprs_freq, aprs_interval, aprs_last_heartbeat
    global tx_watch_thread, tx_watch_running, aprs_seq
    if aprs_cfg is None:
        return {"error": "APRS config not set"}
    # 既存ループを停止フラグだけ立てて即座に返す
    aprs_running = False
    tx_watch_running = False
    aprs_freq = cfg.freq
    aprs_interval = cfg.interval
    aprs_last_heartbeat = time.time()
    aprs_seq += 1
    my_seq = aprs_seq

    if aprs_cfg.use_rig_modem:
        # DireWolf TX モードから切り替えた場合に残存する direwolf プロセスを停止する。
        # (direwolf は PTT RIG 経由でリグを制御するため、kill しないとリグ側の PTT が残る)
        subprocess.run(["pkill", "-9", "direwolf"], capture_output=True)
        # 無線機内蔵モデムのAUTOビーコンを開始する。direwolf/aprs_loopのKISS送出は使わない。
        # ★ CAT設定4連続はタイムアウト・リトライを挟むと合計で数秒〜10秒近くかかることがあり、
        #   同期実行するとM5側のHTTPタイムアウトで失敗と誤判定されるため、バックグラウンドで実行する。
        def _rig_start_worker():
            idx = _BCN_INTERVAL_MAP.get(cfg.interval, 1)
            _rig_ex_set(7, 2, 1, idx)  # APRS BEACON > AUTO BEACON > INTERVAL TIME
            # ★ PROPORTIONAL/DECAYがONだと静止中(移動なし)は間隔が大きく延びてしまい、
            #   設定したINTERVAL TIME通りに送信されない。固定間隔で送信させるため明示的にOFFにする。
            _rig_ex_set(7, 2, 2, 0)   # APRS BEACON > AUTO BEACON > PROPORTIONAL = 0:OFF
            _rig_ex_set(7, 2, 3, 0)   # APRS BEACON > AUTO BEACON > DECAY = 0:OFF
            # ★ ここまでの間に/aprs_stopや別のAP96/AP12切替(=新しいaprs_start)が来ていたら、
            #   このワーカーは既に古い要求。BEACON TYPE=ONで無線機を上書きせず中断する。
            #   (これが無いと、OFFにしたのにAP12押下時の遅延ワーカーが後からONに戻し
            #   「OFFにしてもビーコンが止まらない」状態になる)
            if aprs_seq != my_seq:
                print(f"[APRS/rig] start worker superseded (seq {my_seq} != {aprs_seq}), aborting before beacon ON", flush=True)
                return
            _rig_ex_set(7, 1, 1, 1)    # APRS BEACON > BEACON SET. > BEACON TYPE = 1:AUTO
            radio_cache["tx"] = False

        threading.Thread(target=_rig_start_worker, daemon=True).start()
        # ★ 既にwatchループが生きている場合は再利用する(稼働中のプリセット切替など、
        #   aprs_running=False->True の一瞬の切り替えだけでは古いスレッドが
        #   確実に終了するとは限らないため、二重起動を避けて明示的にチェックする)
        watch_alive = aprs_thread is not None and aprs_thread.is_alive()
        aprs_running = True
        if not watch_alive:
            aprs_thread = threading.Thread(target=_rig_aprs_watch_loop, daemon=True)
            aprs_thread.start()
        return {"status": "starting (rig internal modem)"}

    def _start_worker():
        global aprs_running, aprs_thread, tx_watch_thread, tx_watch_running
        # 古いスレッドが終了するまで最大 3s 待つ（2スレッド起動防止）
        old = aprs_thread
        if old and old.is_alive():
            old.join(timeout=3.0)
        # KISS ポートが開くまで待つ（Pi Zero は Hamlib 初期化で ~10s かかる）
        subprocess.run(["pkill", "-9", "aplay"], capture_output=True)
        time.sleep(0.5)
        if not _wait_direwolf_kiss_ready(20.0):
            if subprocess.run(["pgrep", "-x", "direwolf"], capture_output=True).returncode != 0:
                print("[aprs_start] direwolf not running, starting")
                subprocess.run(["sudo", "systemctl", "start", "direwolf"],
                               capture_output=True, timeout=10)
                _wait_direwolf_kiss_ready(15.0)
            else:
                print("[aprs_start] KISS port not ready after 20s, proceeding anyway")
        aprs_running = True
        aprs_thread = threading.Thread(target=aprs_loop, daemon=True)
        aprs_thread.start()
        tx_watch_running = True
        tx_watch_thread = threading.Thread(target=watch_direwolf_tx, daemon=True)
        tx_watch_thread.start()
        # Start KISS RX monitor thread so received beacons are captured even when
        # /aprs_config was not called first (e.g. panel button tap from iOS).
        _start_aprs_rx_thread_if_needed()

    threading.Thread(target=_start_worker, daemon=True).start()
    return {"status": "starting"}


@app.post("/aprs_stop")
def aprs_stop():
    global aprs_running, poll_enabled, tx_watch_running, last_ptt_state, aprs_seq, aprs_rx_running
    global _last_rig_aprs_key
    poll_enabled = True
    aprs_running = False
    tx_watch_running = False
    aprs_seq += 1  # 実行中/待機中のstartワーカーを無効化(ビーコンONで上書きされるのを防ぐ)
    _last_rig_aprs_key = None  # 次回 start 時に必ずCAT設定を再適用する

    # リグ内蔵APRSモデムのAUTO BEACONを常に無効化する。
    # use_rig_modem/aprs_cfg の状態によらず送信する。サーバー状態の不整合時も確実に止めるため。
    # 非FTX-1機種ではコマンドが失敗するがログ出力のみで副作用なし。
    _rig_ex_set(7, 1, 1, 0)  # APRS BEACON > BEACON SET. > BEACON TYPE = 0:OFF

    if aprs_cfg is not None and aprs_cfg.use_rig_modem:
        # DireWolf TX モードから切り替えた場合に残存する PTT 制御を解除する。
        rigctl_cmd_priority("T 0")
        subprocess.run(["pkill", "-9", "direwolf"], capture_output=True)
        last_ptt_state = 0
        radio_cache["tx"] = False
        # ★ TXは止めるが、APRS機能自体(cfg.enabled)がONならAUTO BEACON以外の局からの
        #   受信は引き続き拾えるよう、受信専用direwolfへ切り替えて維持する。
        threading.Thread(target=_ensure_aprs_rx, args=(aprs_cfg,), daemon=True).start()
        return {"status": "stopped (rig internal modem)"}

    rigctl_cmd_priority("T 0")
    radio_cache["tx"] = False
    last_ptt_state = 0
    if aprs_cfg is not None and aprs_cfg.enabled:
        # ★ TX(PTT/MYCALL込み)設定は不要になったが、APRS機能自体はONのままなので
        #   direwolfをkillせず、受信専用設定に切り替えて受信だけ継続する。
        threading.Thread(target=_ensure_aprs_rx, args=(aprs_cfg,), daemon=True).start()
    else:
        subprocess.run(["pkill", "-9", "direwolf"], capture_output=True)
        aprs_rx_running = False
    return {"status": "stopped"}


@app.get("/aprs_notify")
def aprs_notify():
    """無線機のような「ビーコン受信」ポップアップ用。溜まっている未取得の通知イベントを
    払い出して(=消費して)返す。M5はこれをメイン画面表示中、数秒おきにポーリングする。"""
    global aprs_notify_queue
    with aprs_heard_lock:
        events = list(aprs_notify_queue)
        aprs_notify_queue = []
    return {"events": events}


@app.get("/aprs_notify_android")
def aprs_notify_android():
    """Android用のビーコン受信通知キュー。M5の/aprs_notifyとは独立して管理される。"""
    global aprs_notify_queue_android
    with aprs_heard_lock:
        events = list(aprs_notify_queue_android)
        aprs_notify_queue_android = []
    return {"events": events}


@app.get("/aprs_received")
def aprs_received():
    now = time.time()
    with aprs_heard_lock:
        stations = [
            {**{k: v for k, v in e.items() if k != "heard_at"}, "age_sec": int(now - e["heard_at"])}
            for e in aprs_heard
        ]
    return {"stations": stations}


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
    # 書き込み先パスを決定（_HOME_DIR 経由でユーザー名に依存しない）
    api_path = _HOME_DIR / "fastapi" / "api.py"
    if not api_path.parent.exists():
        api_path = Path(__file__).resolve()
    bak_path = api_path.with_suffix(".py.bak_update")
    try:
        if api_path.exists():
            import shutil
            shutil.copy2(api_path, bak_path)
        api_path.write_bytes(content)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Write failed: {e}")
    def _restart():
        import time as _time
        _time.sleep(0.5)
        # 方法1: sudo -n systemctl (NOPASSWD 設定済みの場合) — 各サービスを個別に試行
        r = subprocess.run(
            ["sudo", "-n", "systemctl", "restart", "fastapi"],
            capture_output=True, timeout=10
        )
        if r.returncode == 0:
            # fastapi-audio が存在すれば再起動 (失敗しても無視)
            subprocess.run(["sudo", "-n", "systemctl", "restart", "fastapi-audio"],
                           capture_output=True, timeout=10)
            return
        # 方法2: sudo 不可 → nohup スクリプト + SIGTERM
        # systemd が Restart= で再起動するケースと手動起動のケースを両立するため
        # 「uvicorn が起動していなければ起動する」チェックを入れて2重起動を防ぐ
        restart_sh = (
            "#!/bin/bash\n"
            "sleep 2\n"
            "pkill -TERM -f 'uvicorn api' 2>/dev/null || true\n"
            "sleep 1\n"
            "pkill -9 -f 'uvicorn api' 2>/dev/null || true\n"
            "sleep 15\n"
            # systemd が既に再起動していれば何もしない (Pi Zero は ~10-15s かかる)
            "if pgrep -f 'uvicorn api' >/dev/null 2>&1; then\n"
            "  exit 0\n"
            "fi\n"
            f"cd {_FASTAPI_DIR}\n"
            f"{_VENV_PY.replace('python3','uvicorn')} api:app --host 0.0.0.0 --port 8000 "
            ">>/tmp/uvicorn_restart.log 2>&1 &\n"
            f"{_VENV_PY.replace('python3','uvicorn')} api:app --host 0.0.0.0 --port 50000 "
            ">>/tmp/uvicorn_audio_restart.log 2>&1\n"
        )
        sh = "/tmp/_fastapi_restart.sh"
        with open(sh, "w") as _f:
            _f.write(restart_sh)
        os.chmod(sh, 0o755)
        subprocess.Popen(
            ["nohup", "bash", sh],
            stdout=open("/tmp/uvicorn_restart.log", "w"),
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        # 現プロセスも SIGTERM で終了 (systemd Restart=on-failure があれば自動再起動)
        _time.sleep(0.5)
        os.kill(os.getpid(), signal.SIGTERM)
    threading.Thread(target=_restart, daemon=True).start()
    return {"status": "ok", "message": "restarting"}


@app.post("/admin/update_cw_bridge")
async def admin_update_cw_bridge(request: Request):
    """cw_bridge.py をアップデートして再起動する"""
    content = await request.body()
    if not content:
        raise HTTPException(status_code=400, detail="Empty body")
    try:
        compile(content.decode("utf-8"), "<cw_bridge.py>", "exec")
    except SyntaxError as e:
        raise HTTPException(status_code=422, detail=f"Syntax error: {e}")
    bridge_path = Path(_CW_BRIDGE_PY)
    bak_path = Path(_CW_BRIDGE_PY + ".bak")
    try:
        if bridge_path.exists():
            import shutil
            shutil.copy2(bridge_path, bak_path)
        bridge_path.write_bytes(content)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Write failed: {e}")
    # cw_bridge プロセスを再起動
    global _cw_bridge_proc, _cw_bridge_port
    with _cw_bridge_lock:
        saved_port = _cw_bridge_port
        if _cw_bridge_proc and _cw_bridge_proc.poll() is None:
            _cw_bridge_proc.terminate()
            try:
                _cw_bridge_proc.wait(timeout=2)
            except Exception:
                _cw_bridge_proc.kill()
        try:
            subprocess.run(["pkill", "-f", "cw_bridge.py"], timeout=3)
            time.sleep(0.5)
        except Exception:
            pass
        _cw_bridge_proc = None
        if saved_port:
            try:
                _cw_bridge_proc = subprocess.Popen([_VENV_PY, _CW_BRIDGE_PY, saved_port])
                _cw_bridge_port = saved_port
            except Exception as e:
                return {"status": "updated", "restart": f"failed: {e}"}
    return {"status": "ok", "message": "cw_bridge updated and restarted"}



@app.post("/admin/setup")
async def admin_setup(request: Request):
    """create_api.sh を受け取りホームディレクトリに保存してバックグラウンド実行する"""
    content = await request.body()
    if not content:
        raise HTTPException(status_code=400, detail="Empty body")
    script_path = _HOME_DIR / "create_api.sh"
    try:
        script_path.write_bytes(content)
        script_path.chmod(0o755)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Write failed: {e}")
    log_path = "/tmp/create_api.log"
    subprocess.Popen(
        ["bash", str(script_path)],
        stdout=open(log_path, "w"),
        stderr=subprocess.STDOUT,
        cwd=str(_HOME_DIR),
        start_new_session=True,
    )
    return {"status": "ok", "message": f"setup running in background, log: {log_path}"}


@app.get("/debug/tx")
def debug_tx():
    """直近の audio_tx (ptt=0) の結果を返す — aplay が起動できたか/失敗理由を確認するためのデバッグ用"""
    return _last_tx_debug


@app.post("/debug/test_tx")
async def debug_test_tx(_: None = Depends(verify_key)):
    """Android を介さず Pi 単体で PTT + 700Hz トーン 2 秒再生。Pi→無線機の音声パスを確認するためのデバッグ用"""
    loop = asyncio.get_running_loop()

    def _play():
        import math, struct
        subprocess.run(["pkill", "-9", "aplay"],   capture_output=True)
        subprocess.run(["pkill", "-9", "ffmpeg"],  capture_output=True)
        time.sleep(0.3)

        rate, freq, dur = 8000, 700, 2
        samples = rate * dur
        data = bytearray()
        for i in range(samples):
            v = int(32767 * 0.8 * math.sin(2 * math.pi * freq * i / rate))
            data += struct.pack('<h', v)

        ptt_ok = bool(rigctl_cmd_priority("T 1"))
        time.sleep(0.3)

        proc = subprocess.Popen(
            ["aplay", "-D", _alsa_playback_dev, "-f", "S16_LE", "-r", str(rate), "-c", "1"],
            stdin=subprocess.PIPE, stderr=subprocess.PIPE
        )
        proc.stdin.write(bytes(data))
        proc.stdin.close()
        try:
            rc = proc.wait(timeout=6)
            err = proc.stderr.read(512).decode(errors="replace").strip()
        except subprocess.TimeoutExpired:
            proc.kill()
            rc, err = -1, "timeout"

        rigctl_cmd_priority("T 0")
        return {"ptt_ok": ptt_ok, "aplay_rc": rc, "aplay_err": err,
                "dev": _alsa_playback_dev, "note": "700Hz 2sec tone sent"}

    return await loop.run_in_executor(None, _play)


@app.get("/admin/setup_log")
async def admin_setup_log(lines: int = 60):
    """create_api.sh の実行ログ末尾を返す"""
    log_path = Path("/tmp/create_api.log")
    if not log_path.exists():
        return {"running": False, "log": ""}
    text = log_path.read_text(errors="replace")
    tail = "\n".join(text.splitlines()[-lines:])
    running = Path("/proc").exists() and any(
        "create_api" in Path(f"/proc/{p}/cmdline").read_text(errors="replace")
        for p in os.listdir("/proc") if p.isdigit()
        if Path(f"/proc/{p}/cmdline").exists()
    )
    return {"running": running, "log": tail}


_MFSK_BUILD_LOG = "/tmp/mfsk_build.log"
_CARGO_TOML = r"""
[package]
name        = "mfsk-decode"
version     = "0.1.0"
edition     = "2021"
publish     = false

[[bin]]
name = "mfsk-decode"
path = "src/main.rs"

[dependencies]
mfsk-core = { git = "https://github.com/jl1nie/mfsk-core", branch = "main", package = "mfsk-core" }
hound = "3"

[profile.release]
opt-level = 3
lto = true
codegen-units = 1
""".strip()

_MAIN_RS = r"""
use std::collections::HashSet;
use std::env;
use std::io::{self, Write};
use std::sync::Mutex;
use mfsk_core::ft8::Ft8;
use mfsk_core::ft8::decode::DecodeResult;
use mfsk_core::msg::decode_request::DecodeRequest;
use mfsk_core::msg::hash_table::CallsignHashTable;
use mfsk_core::ProtocolId;
fn main() {
    let args: Vec<String> = env::args().collect();
    let mut wav_path: Option<String> = None;
    let mut my_call: Option<String> = None;
    let mut dx_call: Option<String> = None;
    let mut freq_min: f32 = 100.0;
    let mut freq_max: f32 = 3000.0;
    let mut sic_rounds: u32 = 3;
    let mut known_calls: Vec<String> = Vec::new();
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "-c"|"--my-call" => { my_call = args.get(i+1).cloned(); i+=2; }
            "-x"|"--dx-call" => { dx_call = args.get(i+1).cloned(); i+=2; }
            "--freq-min" => { freq_min = args.get(i+1).and_then(|s|s.parse().ok()).unwrap_or(100.0); i+=2; }
            "--freq-max" => { freq_max = args.get(i+1).and_then(|s|s.parse().ok()).unwrap_or(3000.0); i+=2; }
            "--sic-rounds" => { sic_rounds = args.get(i+1).and_then(|s|s.parse().ok()).unwrap_or(3); i+=2; }
            "-k"|"--known-call" => { if let Some(v) = args.get(i+1) { known_calls.push(v.clone()); } i+=2; }
            arg if !arg.starts_with('-') => { wav_path = Some(arg.to_string()); i+=1; }
            _ => { i+=1; }
        }
    }
    let wav_path = match wav_path {
        Some(p) => p,
        None => { eprintln!("Usage: mfsk-decode [-c MY_CALL] [-x DX_CALL] [--freq-min HZ] [--freq-max HZ] <wav>"); std::process::exit(1); }
    };
    let audio = match load_wav_12khz(&wav_path) {
        Ok(a) => a,
        Err(e) => { eprintln!("[mfsk-decode] WAV load error: {}", e); std::process::exit(1); }
    };
    let mut hash_table = CallsignHashTable::new();
    if let Some(ref c) = my_call { hash_table.insert(c.as_str()); }
    if let Some(ref c) = dx_call { hash_table.insert(c.as_str()); }
    for c in &known_calls { hash_table.insert(c.as_str()); }
    let stdout = io::stdout();
    let seen: Mutex<HashSet<Vec<u8>>> = Mutex::new(HashSet::new());
    let on_result = |r: &DecodeResult| {
        let key = r.message77().to_vec();
        { let mut s = seen.lock().unwrap(); if !s.insert(key) { return; } }
        if let Some(decoded) = r.to_decoded(ProtocolId::Ft8, Some(&hash_table)) {
            let escaped = decoded.text.replace('\\', "\\\\").replace('"', "\\\"").replace('\n', "\\n").replace('\r', "\\r");
            let line = format!("{{\"freq\":{:.1},\"snr\":{:.1},\"dt\":{:.2},\"msg\":\"{}\"}}",
                decoded.freq_hz, decoded.snr_db, decoded.dt_sec, escaped);
            let mut out = stdout.lock(); writeln!(out, "{}", line).ok(); let _ = out.flush();
        }
    };
    let _outcome = DecodeRequest::<Ft8>::new(&audio, freq_min, freq_max, 1.5, 150)
        .sic_rounds(sic_rounds as usize).on_result(&on_result).decode();
}
fn load_wav_12khz(path: &str) -> Result<Vec<i16>, Box<dyn std::error::Error>> {
    let mut reader = hound::WavReader::open(path)?;
    let spec = reader.spec();
    if spec.channels != 1 { return Err(format!("mono expected, got {} ch", spec.channels).into()); }
    if spec.sample_rate != 12000 { eprintln!("[mfsk-decode] warning: {} Hz", spec.sample_rate); }
    let samples: Vec<i16> = match (spec.sample_format, spec.bits_per_sample) {
        (hound::SampleFormat::Int, 16) => reader.samples::<i16>().map(|s| s.unwrap_or(0)).collect(),
        (hound::SampleFormat::Float, _) => reader.samples::<f32>().map(|s| (s.unwrap_or(0.0).clamp(-1.0, 1.0) * 32767.0) as i16).collect(),
        (hound::SampleFormat::Int, b) => { let scale = (1i64 << (b-1)) as f64; reader.samples::<i32>().map(|s| ((s.unwrap_or(0) as f64 / scale) * 32767.0) as i16).collect() }
    };
    Ok(samples)
}
""".strip()


def _mfsk_build_worker():
    import datetime as _dt
    log = open(_MFSK_BUILD_LOG, "w", buffering=1)
    def w(msg):
        ts = _dt.datetime.now().strftime("%H:%M:%S")
        log.write(f"[{ts}] {msg}\n")
        log.flush()
    try:
        w(f"mfsk-decode ビルド開始")
        cargo_env = str(_HOME_DIR / ".cargo" / "env")
        # Rust インストール確認
        cargo_bin = str(_HOME_DIR / ".cargo" / "bin" / "cargo")
        if not os.path.isfile(cargo_bin):
            w("Rust をインストール中 (10〜15分かかります)...")
            r = subprocess.run(
                ["sh", "-c",
                 "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs"
                 " | sh -s -- -y --no-modify-path"],
                capture_output=True, text=True, timeout=900
            )
            w(r.stdout[-500:] if r.stdout else "")
            if r.returncode != 0:
                w(f"Rust インストール失敗: {r.stderr[-300:]}")
                w("=== 失敗 ===")
                return
            w("Rust インストール完了")

        # ソース展開
        src_dir = _HOME_DIR / "mfsk-decode" / "src"
        src_dir.mkdir(parents=True, exist_ok=True)
        (_HOME_DIR / "mfsk-decode" / "Cargo.toml").write_text(_CARGO_TOML)
        (src_dir / "main.rs").write_text(_MAIN_RS)
        w("ソース展開完了")

        # ビルド
        w("cargo build --release 開始 (初回は 10〜15 分かかります)...")
        env = os.environ.copy()
        env["PATH"] = str(_HOME_DIR / ".cargo" / "bin") + ":" + env.get("PATH", "")
        env["HOME"] = str(_HOME_DIR)
        proc = subprocess.Popen(
            [cargo_bin, "build", "--release"],
            cwd=str(_HOME_DIR / "mfsk-decode"),
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, env=env
        )
        for line in proc.stdout:
            log.write(line)
            log.flush()
        proc.wait()
        if proc.returncode == 0:
            bin_path = _HOME_DIR / "mfsk-decode" / "target" / "release" / "mfsk-decode"
            w(f"ビルド完了: {bin_path}")
            w("=== 完了 ===")
        else:
            w(f"ビルド失敗 (returncode={proc.returncode})")
            w("=== 失敗 ===")
    except Exception as e:
        log.write(f"[error] {e}\n=== 失敗 ===\n")
    finally:
        log.close()


@app.post("/admin/build_mfsk")
async def admin_build_mfsk(force: bool = False):
    """mfsk-decode をバックグラウンドでビルドする (初回 Update Pi 後に Android から呼ぶ)"""
    bin_path = _HOME_DIR / "mfsk-decode" / "target" / "release" / "mfsk-decode"
    if bin_path.exists() and not force:
        return {"status": "already_built", "path": str(bin_path)}
    if bin_path.exists() and force:
        try: bin_path.unlink()
        except Exception: pass
    threading.Thread(target=_mfsk_build_worker, daemon=False).start()
    return {"status": "building", "log": _MFSK_BUILD_LOG}


@app.get("/admin/mfsk_build_log")
async def admin_mfsk_build_log():
    """mfsk-decode ビルドログの末尾を返す"""
    log_path = Path(_MFSK_BUILD_LOG)
    if not log_path.exists():
        return {"status": "not_started", "log": ""}
    text = log_path.read_text(errors="replace")
    lines = text.splitlines()
    done = any("=== 完了 ===" in l for l in lines)
    failed = any("=== 失敗 ===" in l for l in lines)
    status = "done" if done else ("failed" if failed else "building")
    return {"status": status, "log": "\n".join(lines[-40:])}


@app.post("/admin/reboot")
async def admin_reboot():
    """ラズパイを再起動する"""
    # start_new_session=True でデタッチ: FastAPI が _restart() で kill されても reboot は実行される
    subprocess.Popen(
        ["bash", "-c", "sleep 2 && sudo -n /bin/systemctl reboot"],
        start_new_session=True,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    return {"ok": True}


@app.post("/admin/install_hamlib")
async def admin_install_hamlib(request: Request):
    """install_hamlib.sh を受け取りバックグラウンドで実行する"""
    content = await request.body()
    if not content:
        raise HTTPException(status_code=400, detail="Empty body")
    script_path = _HOME_DIR / "install_hamlib.sh"
    try:
        script_path.write_bytes(content)
        script_path.chmod(0o755)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Write failed: {e}")
    log_path = "/tmp/hamlib_build.log"
    open(log_path, "w").close()
    subprocess.Popen(
        ["bash", str(script_path)],
        stdout=open(log_path, "w"),
        stderr=subprocess.STDOUT,
        cwd=str(_HOME_DIR),
        start_new_session=True,
    )
    return {"status": "ok", "message": f"Hamlib install running, log: {log_path}"}


@app.get("/admin/hamlib_log")
async def admin_hamlib_log(lines: int = 60):
    """Hamlib ビルドログの末尾を返す"""
    log_path = Path("/tmp/hamlib_build.log")
    if not log_path.exists():
        return {"log": "(no log yet)"}
    text = log_path.read_text(errors="replace")
    tail = "\n".join(text.splitlines()[-lines:])
    running = Path("/proc").exists() and any(
        "install_hamlib" in Path(f"/proc/{p}/cmdline").read_text(errors="replace")
        for p in os.listdir("/proc") if p.isdigit()
        if Path(f"/proc/{p}/cmdline").exists()
    )
    return {"running": running, "log": tail}

@app.get("/admin/version")
async def admin_version():
    _local = Path.home() / ".local" / "bin" / "rigctld"
    _bin = str(_local) if _local.exists() else "rigctld"
    try:
        _r = subprocess.run([_bin, "--version"], capture_output=True, text=True, timeout=3)
        _rigctld = _r.stdout.strip().splitlines()[0] if _r.returncode == 0 else "unknown"
    except Exception as _e:
        _rigctld = str(_e)
    return {"api_version": API_VERSION, "rigctld": _rigctld}