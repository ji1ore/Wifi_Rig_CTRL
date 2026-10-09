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

# ── Wi-Fi 省電力(パワーセーブ)を無効化 ───────────────────────
# Pi Zero 2W は Wi-Fi パワーセーブ有効だとアイドル時に無線が眠り「ネットワークから消える/
# Unknown host」が頻発する。恒久化(conf.d)＋即時適用(iw)で無効化する。
# ※ SSH は Wi-Fi 経由のため NetworkManager の restart は行わない(セッション切断防止)。
#    conf.d は次回起動(本スクリプト末尾で再起動推奨)から恒久的に効く。
if command -v nmcli >/dev/null 2>&1; then
    sudo mkdir -p /etc/NetworkManager/conf.d
    printf '[connection]\nwifi.powersave = 2\n' | sudo tee /etc/NetworkManager/conf.d/wifi-powersave-off.conf >/dev/null
    echo "Wi-Fi パワーセーブ恒久無効化 (NetworkManager wifi.powersave=2, 再起動後有効)"
fi
# 即時適用(再起動前から効かせる・接続は切らない)。失敗は無視。
for _wif in wlan0 wlan1; do sudo iw dev "$_wif" set power_save off 2>/dev/null && echo "iw $_wif power_save off" || true; done

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
    # --disable-static: 静的ライブラリを作らずビルド量を約半分に。--without-cxx-binding: 不要なC++束縛を省略
    #   (rigctld はCのみ使用)。Pi Zero 2W のビルド時間を大幅短縮。
    ./configure --prefix=/usr/local --disable-static --without-cxx-binding LDFLAGS="-Wl,-rpath,/usr/local/lib"
    # 512MB機(Zero 2W)は -j4 だとスワップで逆に遅く/OOMになるため -j2 に抑える（1GB未満判定）
    HJOBS=$(nproc); [ "$(awk '/MemTotal/{print $2}' /proc/meminfo 2>/dev/null || echo 999999)" -lt 1048576 ] && HJOBS=2
    echo "Hamlib make -j${HJOBS}"
    make -j"$HJOBS"
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
# FastAPI/DireWolf service management (no password required)
echo "$ME ALL=(ALL) NOPASSWD: /bin/systemctl restart fastapi, /bin/systemctl restart fastapi-audio, /bin/systemctl restart webft8, /bin/systemctl restart direwolf, /bin/systemctl start direwolf, /bin/systemctl stop direwolf, /bin/systemctl daemon-reload, /usr/bin/systemctl restart fastapi, /usr/bin/systemctl restart fastapi-audio, /usr/bin/systemctl daemon-reload, /bin/mkdir, /usr/bin/tee" \
    | sudo tee /etc/sudoers.d/fastapi-restart > /dev/null
sudo chmod 0440 /etc/sudoers.d/fastapi-restart
# WireGuard setup — allows api.py to perform privileged network operations
# without a sudo password. This eliminates the need to send a sudo password
# over HTTP when using WireGuard Setup from the iOS/Android app.
echo "$ME ALL=(ALL) NOPASSWD: /usr/bin/apt-get, /usr/bin/python3, /usr/sbin/modprobe, /sbin/modprobe, /sbin/ip, /usr/sbin/ip, /usr/bin/wg-quick, /usr/sbin/wg-quick, /sbin/wg-quick" \
    | sudo tee /etc/sudoers.d/wireguard-setup > /dev/null
sudo chmod 0440 /etc/sudoers.d/wireguard-setup
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

# ── オンデマンドGUI: 常時CUI起動 + VNC接続時だけ X を起動 ──
# 無線サーバーはCPUリアルタイム性が重要なため既定はコンソール起動。
# GUIが必要な時だけ TigerVNC 仮想デスクトップ(X11)を手動起動して使う。
# Wayland非依存・lightdm不要。固定パスワードを焼き込み、Pi側での入力を不要にする。
# 第1引数でデスクトップ種別を選択: none(VNCなし) / xfce(既定) / pixel
VNC_DESKTOP="${1:-xfce}"
case "$VNC_DESKTOP" in
    none|off|no|"") VNC_DESKTOP="none" ;;
    pixel) VNC_DE_PKGS="raspberrypi-ui-mods"; VNC_DE_SESSION="startlxde-pi"; VNC_DE_CHECK="startlxde-pi" ;;
    *)     VNC_DESKTOP="xfce"; VNC_DE_PKGS="xfce4"; VNC_DE_SESSION="startxfce4"; VNC_DE_CHECK="startxfce4" ;;
esac
if [ "$VNC_DESKTOP" = "none" ]; then
echo ""
echo "=== VNC/デスクトップ: なし（インストールをスキップ。起動ターゲットも変更しない） ==="
else
echo ""
echo "=== オンデマンドGUI (TigerVNC / デスクトップ=$VNC_DESKTOP) をセットアップ中 ==="

# (a) グラフィカル起動が既定なら コンソール起動へ切替（Liteは元からCUIなので何もしない・可逆）
if command -v systemctl >/dev/null 2>&1; then
    if [ "$(systemctl get-default 2>/dev/null)" = "graphical.target" ]; then
        echo "  デスクトップ常時起動を検出 → コンソール起動へ切替"
        sudo systemctl set-default multi-user.target
        for dm in lightdm gdm gdm3; do
            systemctl list-unit-files 2>/dev/null | grep -q "^$dm" && sudo systemctl disable "$dm" 2>/dev/null || true
        done
    else
        echo "  起動ターゲット: $(systemctl get-default 2>/dev/null)（変更なし）"
    fi
fi

# (b)-(e) デスクトップ/VNC の導入と設定は「バックグラウンドで」実行する。
#   理由: Pi Zero 2W など低速機では xfce4/raspberrypi-ui-mods の apt 導入に時間がかかり、
#   本体セットアップ(Hamlibビルド等)と合わせて SSH セッションを長時間占有し、
#   アプリ側がタイムアウトする。GUI 導入を detached(setsid+nohup) 化することで
#   本体セットアップは短時間で完了・SSH を解放し、GUI は Pi 側で継続導入される。
VNC_PASS="raspberry"
export ME ME_HOME VNC_DESKTOP VNC_DE_PKGS VNC_DE_SESSION VNC_DE_CHECK VNC_PASS
GUI_LOG="$ME_HOME/ondemand_gui_setup.log"

# クォート付きヒアドキュメント(<<'GUISCRIPT')で literal に書き出し、実行時に環境変数で展開する。
cat > /tmp/ondemand_gui.sh <<'GUISCRIPT'
#!/bin/bash
# On-demand GUI (TigerVNC + 選択DE) installer — runs detached (root).
set +e
echo "[on-demand-gui] start desktop=$VNC_DESKTOP"
# 共通土台
if ! command -v tigervncserver >/dev/null 2>&1; then
    apt install -y xserver-xorg xfonts-base dbus-x11 openbox lxterminal \
                   tigervnc-standalone-server tigervnc-common
fi
# 選択デスクトップ
if ! command -v "$VNC_DE_CHECK" >/dev/null 2>&1; then
    apt install -y $VNC_DE_PKGS
fi
# 新しいTigerVNC(Debian13/trixie等)は設定を ~/.config/tigervnc に置く。旧 ~/.vnc からの自動移行が
# headless環境で失敗し起動できないため、最初から ~/.config/tigervnc に作成し、旧 ~/.vnc は撤去して
# 移行を回避する（実機 Pi Zero 2W/trixie で起動成功を確認済みの構成）。
VNC_CFG="$ME_HOME/.config/tigervnc"
mkdir -p "$VNC_CFG"
# 固定パスワード(TigerVNC版 tigervncpasswd -f。RealVNC版 /usr/bin/vncpasswd は使わない)
printf '%s' "$VNC_PASS" | tigervncpasswd -f > "$VNC_CFG/passwd"
chmod 600 "$VNC_CFG/passwd"
# 接続時に起動するデスクトップ
cat > "$VNC_CFG/xstartup" <<XEOF
#!/bin/sh
unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS
exec $VNC_DE_SESSION
XEOF
chmod +x "$VNC_CFG/xstartup"
# 旧 ~/.vnc があると起動時に移行を試みて失敗するため撤去
rm -rf "$ME_HOME/.vnc"
# 背景インストールは root 実行のため所有者を本ユーザーに戻す
chown -R "$ME":"$ME" "$ME_HOME/.config"
# オンデマンド systemd サービス。自動終了タイマーは付けない:
# -MaxDisconnectionTime/-MaxIdleTime は「未接続」を起動直後からカウントし、接続前にサーバが落ちて
# "connection refused" になる不具合があったため廃止。使い終わりは手動で stop して解放する。
cat > /etc/systemd/system/vncserver@.service <<SEOF
[Unit]
Description=TigerVNC virtual desktop for display %i
After=network.target
[Service]
Type=simple
User=$ME
WorkingDirectory=$ME_HOME
ExecStartPre=-/usr/bin/tigervncserver -kill :%i
ExecStart=/usr/bin/tigervncserver :%i -fg -geometry 1280x720 -depth 24 -localhost no
ExecStop=/usr/bin/tigervncserver -kill :%i
[Install]
WantedBy=multi-user.target
SEOF
systemctl daemon-reload
echo "[on-demand-gui] done. start: sudo systemctl start vncserver@1 ; VNC <IP>:5901 pass=$VNC_PASS"
GUISCRIPT
chmod +x /tmp/ondemand_gui.sh
setsid nohup bash /tmp/ondemand_gui.sh > "$GUI_LOG" 2>&1 < /dev/null &
chown "$ME":"$ME" "$GUI_LOG" 2>/dev/null || true
echo "  → デスクトップ/VNC はバックグラウンドで導入中（ログ: $GUI_LOG）"
echo "    完了後: sudo systemctl start vncserver@1  → VNC <PiのIP>:5901 (パス: $VNC_PASS)"
sudo systemctl daemon-reload
echo "  GUIが必要な時: sudo systemctl start vncserver@1  → VNCで <PiのIP>:5901 (パス: $VNC_PASS)"
echo "  終了:          sudo systemctl stop  vncserver@1"
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
