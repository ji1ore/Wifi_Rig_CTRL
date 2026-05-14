#!/bin/bash
# Wifi_Rig_CTRL Raspberry Pi 環境セットアップ（初回のみ実行）
# api.py の生成は create_api.sh が担当
set -e

# ── パッケージインストール ─────────────────────────────────────
sudo apt update -y
sudo apt install -y \
    build-essential libtool libusb-1.0-0-dev libncurses5-dev \
    git autoconf automake pkg-config \
    ffmpeg alsa-utils \
    python3-pip python3-venv \
    cmake libasound2-dev

# ── Hamlib ビルド・インストール ───────────────────────────────
wget https://github.com/Hamlib/Hamlib/releases/download/4.7.1/hamlib-4.7.1.tar.gz
tar xvf hamlib-4.7.1.tar.gz
cd hamlib-4.7.1
./configure --prefix=/usr/local
make -j4
sudo make install
echo "/usr/local/lib" | sudo tee /etc/ld.so.conf.d/hamlib.conf
sudo ldconfig
cd ~
rigctl --version

# ── Python venv + FastAPI ─────────────────────────────────────
python3 -m venv ~/fastapi
source ~/fastapi/bin/activate
pip install fastapi uvicorn python-multipart
deactivate

# ── Direwolf ビルド・インストール ─────────────────────────────
cd ~
git clone https://www.github.com/wb2osz/direwolf
cd direwolf
mkdir build && cd build
cmake ..
make -j4
sudo make install
sudo make install-conf
cd ~

# ── Direwolf 初期設定ファイル（APRS設定前のプレースホルダー）─
cat << 'EOF' > /home/pi/direwolf.conf
ADEVICE null plughw:CARD=CODEC,DEV=0
CHANNEL 0
MYCALL NOCALL
MODEM 1200
KISSPORT 8001
AGWPORT 8050
EOF

# ── systemd サービスファイル ──────────────────────────────────
# fastapi: メインAPI (CAT制御・APRS制御) port 8000
cat << 'EOF' | sudo tee /etc/systemd/system/fastapi.service
[Unit]
Description=FastAPI Radio Control Service
After=network.target

[Service]
User=pi
Group=pi
WorkingDirectory=/home/pi/fastapi
EnvironmentFile=-/home/pi/fastapi/.env
ExecStart=/home/pi/fastapi/bin/uvicorn api:app --host 0.0.0.0 --port 8000
Restart=always

[Install]
WantedBy=multi-user.target
EOF

# fastapi-audio: 音声ストリーミング専用 port 50000
# APRSビーコン送信中は自動的にstop/startされ、USBオーディオを排他制御する
cat << 'EOF' | sudo tee /etc/systemd/system/fastapi-audio.service
[Unit]
Description=FastAPI Audio Streaming Service
After=network.target sound.target

[Service]
User=pi
WorkingDirectory=/home/pi/fastapi
EnvironmentFile=-/home/pi/fastapi/.env
ExecStart=/home/pi/fastapi/bin/uvicorn api:app --host 0.0.0.0 --port 50000
Restart=always
KillMode=control-group

[Install]
WantedBy=multi-user.target
EOF

# .env テンプレート生成
if [ ! -f /home/pi/fastapi/.env ]; then
    mkdir -p /home/pi/fastapi
    echo "# API Key 認証。キーを設定する場合は下の行を編集して有効にする" > /home/pi/fastapi/.env
    echo "# API_KEY=your_secret_key_here" >> /home/pi/fastapi/.env
fi

# direwolf: APRS用 KISS TNC
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

# ── sudoers (api.pyからsystemctlを実行するために必要) ─────────
cat << 'EOF' | sudo tee /etc/sudoers.d/fastapi
pi ALL=NOPASSWD: /usr/bin/systemctl stop fastapi-audio.service
pi ALL=NOPASSWD: /usr/bin/systemctl start fastapi-audio.service
pi ALL=NOPASSWD: /usr/bin/systemctl restart direwolf.service
EOF
sudo chmod 440 /etc/sudoers.d/fastapi

# ── api.py 生成（create_api.sh を実行）────────────────────────
echo ""
echo "環境セットアップ完了。api.py を生成します..."
bash ~/create_api.sh

echo ""
echo "セットアップ完了。サービス状態確認:"
echo "  sudo systemctl status fastapi"
