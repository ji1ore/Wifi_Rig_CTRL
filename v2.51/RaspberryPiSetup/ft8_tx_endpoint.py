# api.py への追加コード: /radio/ft8_tx エンドポイント
# 既存の api.py の適切な場所（/radio/audio_tx の直後など）に貼り付けてください
# 必要なimportはapi.pyの先頭にすでにある想定（array, subprocess, time, asyncio など）

@app.post("/radio/ft8_tx")
async def ft8_tx(request: Request):
    """
    FT8/FT4 メッセージをPi側でエンコードして送信する。
    Android から PCM を送る代わりに、メッセージテキストだけを受け取る。
    PTT は Android 側が /radio/ptt で制御する（このエンドポイントは音声再生のみ）。

    Request JSON:
      {
        "msg":       "CQ JA1ABC PM95",   // FT8メッセージ
        "audio_freq": 1500,               // オーディオ周波数 [Hz]
        "rate":      12000,               // サンプルレート [Hz]
        "is_ft4":    false                // FT4モードならtrue
      }
    Response JSON:
      {"status": "ok", "rc": 0}           // aplay終了コード
    """
    data = await request.json()
    msg        = data.get("msg", "")
    audio_freq = float(data.get("audio_freq", 1500))
    rate       = int(data.get("rate", 12000))
    is_ft4     = bool(data.get("is_ft4", False))

    loop = asyncio.get_running_loop()

    def encode_and_play():
        # ─── エンコード ────────────────────────────────────────────
        try:
            enc = subprocess.run(
                ["/usr/local/bin/ft8_encode",
                 msg, str(audio_freq), str(rate), "1" if is_ft4 else "0"],
                capture_output=True, timeout=10
            )
        except subprocess.TimeoutExpired:
            print("[ft8_tx] encode timeout")
            return -10
        except FileNotFoundError:
            print("[ft8_tx] ft8_encode not found — run setup_ft8_encode.sh")
            return -11

        if enc.returncode != 0 or not enc.stdout:
            print(f"[ft8_tx] encode error rc={enc.returncode}: {enc.stderr.decode(errors='replace')}")
            return -1

        pcm_raw = enc.stdout
        print(f"[ft8_tx] encoded {len(pcm_raw)} bytes, msg='{msg}', freq={audio_freq}, rate={rate}")

        # ─── 既存音声プロセスを停止 ────────────────────────────────
        subprocess.run(["pkill", "-9", "ffmpeg"], capture_output=True)
        subprocess.run(["pkill", "-9", "aplay"],  capture_output=True)
        time.sleep(0.1)

        # ─── TX_GAIN 適用 (audio_tx と同じ 2.0 倍) ───────────────
        TX_GAIN = 2.0
        samples = array.array('h', pcm_raw)
        for i in range(len(samples)):
            samples[i] = max(-32768, min(32767, int(samples[i] * TX_GAIN)))
        pcm = samples.tobytes()

        # ─── aplay で再生 (~12.64秒) ──────────────────────────────
        proc = subprocess.Popen(
            ["aplay", "-D", "plughw:CARD=CODEC,DEV=0",
             "-f", "S16_LE", "-r", str(rate), "-c", "1", "-B", "100000"],
            stdin=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0
        )

        # 1秒チャンクずつ書き込み（watchdog の last_heartbeat を更新しながら）
        chunk = rate * 2  # 1秒分
        offset = 0
        while offset < len(pcm):
            end = min(offset + chunk, len(pcm))
            proc.stdin.write(pcm[offset:end])
            global last_heartbeat
            last_heartbeat = time.time()  # watchdog をリセット
            offset = end
        proc.stdin.close()
        proc.wait()

        return proc.returncode

    rc = await loop.run_in_executor(None, encode_and_play)
    return {"status": "ok" if rc == 0 else "error", "rc": rc}
