#!/bin/bash
# Wifi_Rig_CTRL Raspberry Pi 環境セットアップ
# 初回・再実行どちらも安全（べき等）
# 実行方法: sudo bash setup_fastapi_radio.sh
# ※ set -e は使わない — 一部ステップの失敗で全体が止まらないよう

ME=${SUDO_USER:-$(whoami)}
if [ "$ME" = "root" ]; then
    echo "ERROR: sudo 経由で実行してください: sudo bash setup_fastapi_radio.sh"
    exit 1
fi
ME_HOME=$(getent passwd "$ME" | cut -d: -f6)

echo "=== Wifi_Rig_CTRL セットアップ開始 (ユーザー: $ME, HOME: $ME_HOME) ==="

# ── パッケージインストール ─────────────────────────────────────
sudo apt update -y
sudo apt install -y \
    build-essential libtool libusb-1.0-0-dev libncurses-dev \
    git autoconf automake pkg-config \
    ffmpeg alsa-utils sox \
    python3-pip python3-venv \
    cmake libasound2-dev \
    openssl wget curl

# ── USB シリアルドライバー（FTDI / CH340）─────────────────────
sudo modprobe ftdi_sio 2>/dev/null || true
sudo modprobe ch341   2>/dev/null || true
grep -q "ftdi_sio" /etc/modules || echo "ftdi_sio" | sudo tee -a /etc/modules
grep -q "ch341"    /etc/modules || echo "ch341"    | sudo tee -a /etc/modules

# ── Hamlib ビルド・インストール（未インストールの場合のみ）────
# 判定は PATH 上の rigctl ではなく /usr/local/bin/rigctl で行う（apt 版 /usr/bin/rigctl が残っていても誤判定しない）
# --version はライブラリ側のバージョンを表示するため、apt 版 libhamlib を誤って読み込んでいる場合も再ビルド対象になる
if ! /usr/local/bin/rigctl --version 2>/dev/null | grep -q "4\.7\.2"; then
    echo "=== Hamlib 4.7.2 をビルド中 ==="
    cd "$ME_HOME"
    wget -q https://github.com/Hamlib/Hamlib/releases/download/4.7.2/hamlib-4.7.2.tar.gz
    tar xf hamlib-4.7.2.tar.gz
    cd hamlib-4.7.2
    # rpath を埋め込み、apt 版 libhamlib (同じ soname libhamlib.so.4) より /usr/local/lib を必ず優先させる
    ./configure --prefix=/usr/local LDFLAGS="-Wl,-rpath,/usr/local/lib"
    make -j4
    sudo make install
    echo "/usr/local/lib" | sudo tee /etc/ld.so.conf.d/hamlib.conf
    sudo ldconfig
    cd "$ME_HOME"
    rm -rf hamlib-4.7.2 hamlib-4.7.2.tar.gz
    echo "Hamlib インストール完了: $(/usr/local/bin/rigctl --version 2>&1 | head -1)"
else
    echo "Hamlib 4.7.2 既存: スキップ ($(/usr/local/bin/rigctl --version 2>&1 | head -1))"
fi

# api.py は ~/.local/bin/rigctld を最優先で使うため、古い版が残っていると 4.7.2 が使われない → 退避する
_LOCAL_RIGCTLD="$ME_HOME/.local/bin/rigctld"
if [ -x "$_LOCAL_RIGCTLD" ] && ! "$_LOCAL_RIGCTLD" --version 2>/dev/null | grep -q "4\.7\.2"; then
    for _b in rigctld rigctl; do
        [ -e "$ME_HOME/.local/bin/$_b" ] && mv -f "$ME_HOME/.local/bin/$_b" "$ME_HOME/.local/bin/$_b.old"
    done
    echo "古い ~/.local/bin/rigctld を退避: rigctld.old ($("$ME_HOME/.local/bin/rigctld.old" --version 2>&1 | head -1))"
fi

# ── Python venv + FastAPI ─────────────────────────────────────
# uvicorn の有無ではなく import できるかで判定（OS 更新で Python が上がると既存 venv は壊れる）
_VENV="$ME_HOME/fastapi"
if sudo -u "$ME" "$_VENV/bin/python3" -c "import fastapi, uvicorn, multipart, serial" > /dev/null 2>&1; then
    echo "Python venv 既存: パッケージ更新のみ"
    sudo -u "$ME" "$_VENV/bin/pip" install --quiet --upgrade fastapi uvicorn python-multipart pyserial
else
    if [ -d "$_VENV/bin" ]; then
        # --upgrade は api.py / .env など venv 内に置いたファイルを消さずに Python だけ更新する
        echo "=== Python venv が壊れているため修復中 ==="
        sudo -u "$ME" python3 -m venv --upgrade "$_VENV"
    else
        echo "=== Python venv を作成中 ==="
        sudo -u "$ME" python3 -m venv "$_VENV"
    fi
    sudo -u "$ME" "$_VENV/bin/pip" install --quiet fastapi uvicorn python-multipart pyserial
    echo "venv 準備完了: $_VENV"
fi

# ── Direwolf ビルド・インストール（未インストールの場合のみ）─
# apt 版 (/usr/bin/direwolf) があればそれを使う。サービスは下で実際のパスを指定する
if ! command -v direwolf > /dev/null 2>&1; then
    echo "=== Direwolf をビルド中 ==="
    cd "$ME_HOME"
    if [ -d direwolf ]; then
        echo "既存の direwolf ディレクトリを削除して再クローン"
        rm -rf direwolf
    fi
    git clone https://www.github.com/wb2osz/direwolf
    cd direwolf
    mkdir -p build && cd build
    cmake ..
    make -j4
    sudo make install
    sudo make install-conf
    cd "$ME_HOME"
    echo "Direwolf インストール完了"
else
    echo "Direwolf 既存: スキップ ($(command -v direwolf))"
fi
# サービスの ExecStart に使う実際のパス（自前ビルド /usr/local/bin を優先、なければ apt 版など）
if [ -x /usr/local/bin/direwolf ]; then
    DIREWOLF_BIN=/usr/local/bin/direwolf
else
    DIREWOLF_BIN=$(command -v direwolf || echo /usr/local/bin/direwolf)
fi
echo "Direwolf 使用バイナリ: $DIREWOLF_BIN"

# ── USB オーディオデバイス検出 ───────────────────────────────
# arecord -l からUSBオーディオのカード短縮名一覧を取得（mawk対応）
_usb_cards=$(arecord -l 2>/dev/null | awk '/USB Audio/{
    line=$0; sub(/.*card [0-9]+: /, "", line); sub(/[ \[,].*/, "", line); print line
}' | sort -u)
_card_count=$(echo "$_usb_cards" | grep -c . 2>/dev/null); _card_count=${_card_count:-0}

if [ "$_card_count" -eq 0 ]; then
    echo "警告: USB オーディオデバイスが見つかりません。CODEC をデフォルトとして使用します。"
    echo "      無線機の USB ケーブルを接続してから再実行するか、後で .env を手動編集してください。"
    ALSA_CARD="CODEC"
elif [ "$_card_count" -eq 1 ]; then
    ALSA_CARD="$_usb_cards"
    echo "USB オーディオデバイスを自動検出: CARD=$ALSA_CARD"
else
    echo ""
    echo "複数の USB オーディオデバイスが見つかりました。無線機に使用するものを選択してください:"
    i=1
    for _c in $_usb_cards; do
        echo "  $i) $_c"
        i=$((i+1))
    done
    # 非対話環境（SSH自動実行・パイプ）では stdin が TTY でないため自動的に 1 を選択
    if [ ! -t 0 ]; then
        echo "非対話モード: 1 を自動選択します。"
        _sel=1
    else
        printf "番号を入力 [1]: "
        read _sel
        _sel=${_sel:-1}
        # 1以上かつ_card_count以下でなければ1にフォールバック
        if ! echo "$_sel" | grep -qE '^[0-9]+$' || [ "$_sel" -lt 1 ] || [ "$_sel" -gt "$_card_count" ]; then
            echo "無効な番号 ($_sel) — 1 を使用します。"
            _sel=1
        fi
    fi
    ALSA_CARD=$(echo "$_usb_cards" | sed -n "${_sel}p")
    if [ -z "$ALSA_CARD" ]; then
        ALSA_CARD=$(echo "$_usb_cards" | head -1)
    fi
    echo "選択: CARD=$ALSA_CARD"
fi
ALSA_DEV="plughw:CARD=${ALSA_CARD},DEV=0"

# ── Direwolf 設定ファイル（新規生成 or 既存の ADEVICE を自動修正）──
if [ ! -f "$ME_HOME/direwolf.conf" ]; then
    cat << EOF > "$ME_HOME/direwolf.conf"
ADEVICE null $ALSA_DEV
CHANNEL 0
MYCALL NOCALL
MODEM 1200
KISSPORT 8001
AGWPORT 8050
EOF
    echo "direwolf.conf 生成完了 (ADEVICE=$ALSA_DEV)"
else
    # 既存ファイルの ADEVICE を正しいデバイスに更新（MYCALL 等のユーザー設定は維持）
    if grep -q "^ADEVICE" "$ME_HOME/direwolf.conf"; then
        sed -i "s|^ADEVICE .*|ADEVICE null $ALSA_DEV|" "$ME_HOME/direwolf.conf"
    else
        sed -i "1s|^|ADEVICE null $ALSA_DEV\n|" "$ME_HOME/direwolf.conf"
    fi
    echo "direwolf.conf ADEVICE を自動更新: null $ALSA_DEV"
fi

# ── .env テンプレート生成（なければ）────────────────────────
mkdir -p "$ME_HOME/fastapi"
if [ ! -f "$ME_HOME/fastapi/.env" ]; then
    cat << EOF > "$ME_HOME/fastapi/.env"
# API Key 認証。設定する場合は下の行を有効にする
# API_KEY=your_secret_key_here
# ALSAオーディオデバイス（自動検出: setup_fastapi_radio.sh 実行時）
ALSA_CAPTURE=$ALSA_DEV
ALSA_PLAYBACK=$ALSA_DEV
EOF
    echo ".env 生成完了 (ALSA_CAPTURE=$ALSA_DEV)"
else
    # 既存 .env の ALSA_CAPTURE/ALSA_PLAYBACK を常に最新デバイスで上書き
    grep -v "^ALSA_CAPTURE=\|^ALSA_PLAYBACK=" "$ME_HOME/fastapi/.env" > /tmp/_env_tmp 2>/dev/null \
        && mv /tmp/_env_tmp "$ME_HOME/fastapi/.env"
    echo "ALSA_CAPTURE=$ALSA_DEV" >> "$ME_HOME/fastapi/.env"
    echo "ALSA_PLAYBACK=$ALSA_DEV" >> "$ME_HOME/fastapi/.env"
    echo ".env ALSA デバイスを更新: $ALSA_DEV"
fi

# ── systemd サービスファイル ──────────────────────────────────
echo "=== systemd サービスファイルを更新中 ==="

sudo tee /etc/systemd/system/fastapi.service > /dev/null << EOF
[Unit]
Description=FastAPI Radio Control Service
After=network.target

[Service]
User=$ME
Group=$ME
WorkingDirectory=$ME_HOME/fastapi
EnvironmentFile=-$ME_HOME/fastapi/.env
ExecStart=$ME_HOME/fastapi/bin/uvicorn api:app --host 0.0.0.0 --port 8000
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

sudo tee /etc/systemd/system/fastapi-audio.service > /dev/null << EOF
[Unit]
Description=FastAPI Audio Streaming Service
After=network.target sound.target

[Service]
User=$ME
WorkingDirectory=$ME_HOME/fastapi
EnvironmentFile=-$ME_HOME/fastapi/.env
ExecStart=$ME_HOME/fastapi/bin/uvicorn api:app --host 0.0.0.0 --port 50000
Restart=always
RestartSec=3
KillMode=control-group

[Install]
WantedBy=multi-user.target
EOF

sudo tee /etc/systemd/system/direwolf.service > /dev/null << EOF
[Unit]
Description=Direwolf KISS TNC
After=sound.target network.target

[Service]
User=$ME
WorkingDirectory=$ME_HOME
ExecStart=$DIREWOLF_BIN -c $ME_HOME/direwolf.conf -t 0
Restart=always

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable direwolf fastapi fastapi-audio
echo "サービスファイル更新完了"

# ── sudoers ──────────────────────────────────────────────────
echo "$ME ALL=(ALL) NOPASSWD: /bin/systemctl restart fastapi, /bin/systemctl restart fastapi-audio, /bin/systemctl restart webft8, /bin/systemctl restart direwolf, /bin/systemctl start direwolf, /bin/systemctl stop direwolf" \
    | sudo tee /etc/sudoers.d/fastapi-restart > /dev/null
sudo chmod 0440 /etc/sudoers.d/fastapi-restart
echo "sudoers 設定完了"

# ── システム設定 ───────────────────────────────────────────────
sudo sed -i 's/#Storage=auto/Storage=persistent/' /etc/systemd/journald.conf 2>/dev/null || true
sudo systemctl restart systemd-journald
sudo usermod -aG dialout,audio,systemd-journal "$ME"
amixer -c "$ALSA_CARD" sset 'PCM' 100% 2>/dev/null || true
sudo alsactl store 2>/dev/null || true
echo "システム設定完了"

# ── api.py・webft8 セットアップ（create_api.sh を root で実行）─
echo ""
echo "=== api.py・webft8 セットアップ開始 ==="
export SUDO_USER="$ME"
bash "$ME_HOME/create_api.sh"

# root で作成したファイルのオーナーを $ME に戻す
chown "$ME":"$ME" "$ME_HOME/fastapi/api.py"     2>/dev/null || true
chown "$ME":"$ME" "$ME_HOME/cw_bridge.py"       2>/dev/null || true
chown -R "$ME":"$ME" "$ME_HOME/webft8_static/"  2>/dev/null || true

# ── UpdatePi 相当: 最新版ファイルを適用 ──────────────────────
# create_api.sh は埋め込み旧版 api.py を書き込む。
# UpdatePi と同等になるよう、スクリプトと同じディレクトリの最新版で上書きする。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo ""
echo "=== 最新版ファイルを適用中 ==="

if [ -f "$SCRIPT_DIR/api.py" ]; then
    cp "$SCRIPT_DIR/api.py" "$ME_HOME/fastapi/api.py"
    chown "$ME":"$ME" "$ME_HOME/fastapi/api.py"
    echo "api.py         : 最新版を適用"
fi

if [ -f "$SCRIPT_DIR/server_webft8.py" ]; then
    cp "$SCRIPT_DIR/server_webft8.py" "$ME_HOME/webft8_static/web/server.py"
    chown "$ME":"$ME" "$ME_HOME/webft8_static/web/server.py"
    echo "server.py      : 最新版を適用"
fi

if [ -f "$SCRIPT_DIR/cw_bridge.py" ] && ! [ "$SCRIPT_DIR/cw_bridge.py" -ef "$ME_HOME/cw_bridge.py" ]; then
    cp "$SCRIPT_DIR/cw_bridge.py" "$ME_HOME/cw_bridge.py"
    chown "$ME":"$ME" "$ME_HOME/cw_bridge.py"
    echo "cw_bridge.py   : 最新版を適用"
fi

# ── サービス起動 ──────────────────────────────────────────────
echo ""
echo "=== サービスを起動中 ==="
sudo systemctl restart fastapi fastapi-audio || true
sudo systemctl restart direwolf || true

echo ""
echo "=== セットアップ完了 ==="
echo ""
echo "サービス状態確認:"
sudo systemctl is-active fastapi       && echo "  fastapi       : OK" || echo "  fastapi       : NG"
sudo systemctl is-active fastapi-audio && echo "  fastapi-audio : OK" || echo "  fastapi-audio : NG"
sudo systemctl is-active direwolf      && echo "  direwolf      : OK" || echo "  direwolf      : NG"
echo ""
echo "注意: グループ変更は再ログイン後に有効。"
echo "  sudo reboot  # 推奨"
