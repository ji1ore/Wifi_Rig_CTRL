#!/bin/bash

### ================================
### 0. 固定 IP 設定（ユーザーが変更）
### ================================
# Raspberry Pi に割り当てる固定 IP アドレス
# 例：192.168.0.50/24
# ※「XX」の部分を自分の環境に合わせて変更してください
STATIC_IP="192.168.0.XX/24"

# ルーターのゲートウェイアドレス
# 例：192.168.0.1
GATEWAY="192.168.0.1"

# DNS サーバー（任意）
DNS="1.1.1.1"

# 接続名を取得
CON_NAME=$(nmcli -t -f NAME,DEVICE connection show | grep wlan0 | cut -d: -f1)
echo "Using connection: $CON_NAME"

# 固定 IP 設定
sudo nmcli connection modify "$CON_NAME" ipv4.addresses "$STATIC_IP"
sudo nmcli connection modify "$CON_NAME" ipv4.gateway "$GATEWAY"
sudo nmcli connection modify "$CON_NAME" ipv4.dns "$DNS"
sudo nmcli connection modify "$CON_NAME" ipv4.method manual

echo "=== wlan0 固定 IP 設定完了 ==="


### ================================
### 1. WireGuard インストール
### ================================
sudo apt update -y
sudo apt install -y wireguard wireguard-tools qrencode


### ================================
### 2. サーバー鍵生成
### ================================
mkdir -p ~/wgkeys/server
cd ~/wgkeys/server

wg genkey | tee privatekey | wg pubkey > publickey
chmod 600 privatekey

SERVER_PRIVATE_KEY=$(cat privatekey)
SERVER_PUBLIC_KEY=$(cat publickey)

echo "=== Server Keys ==="
echo "PrivateKey: (省略)"
echo "PublicKey : $SERVER_PUBLIC_KEY"


### ================================
### 3. wg0.conf 生成
### ================================
sudo bash -c "cat << EOF > /etc/wireguard/wg0.conf
[Interface]
Address = 10.0.0.1/24
ListenPort = 51820
PrivateKey = ${SERVER_PRIVATE_KEY}

PostUp = nft add table inet nat 2>/dev/null
PostUp = nft add chain inet nat postrouting { type nat hook postrouting priority 100 \; } 2>/dev/null
PostUp = nft add rule inet nat postrouting oif wlan0 masquerade

PostDown = nft delete rule inet nat postrouting oif wlan0 masquerade
EOF"

sudo chmod 600 /etc/wireguard/wg0.conf


### ================================
### 4. Android クライアント鍵生成
### ================================
CLIENT_NAME="android1"
mkdir -p ~/wgkeys/$CLIENT_NAME
cd ~/wgkeys/$CLIENT_NAME

wg genkey | tee privatekey | wg pubkey > publickey
chmod 600 privatekey

CLIENT_PRIVATE_KEY=$(cat privatekey)
CLIENT_PUBLIC_KEY=$(cat publickey)

echo "=== Android Client Keys ==="
echo "PrivateKey: (省略)"
echo "PublicKey : $CLIENT_PUBLIC_KEY"


### ================================
### 5. サーバー wg0.conf に Peer を追加
### ================================
sudo bash -c "cat << EOF >> /etc/wireguard/wg0.conf

[Peer]
PublicKey = ${CLIENT_PUBLIC_KEY}
AllowedIPs = 10.0.0.2/32
EOF"


### ================================
### 6. Android 用設定ファイル生成（ユーザーが変更）
### ================================
# DuckDNS のドメイン名（例：myhome）
DUCKDNS_DOMAIN="yourdomain"

SERVER_ENDPOINT="${DUCKDNS_DOMAIN}.duckdns.org:51820"

cat << EOF > ${CLIENT_NAME}.conf
[Interface]
PrivateKey = ${CLIENT_PRIVATE_KEY}
Address = 10.0.0.2/32
DNS = 1.1.1.1

[Peer]
PublicKey = ${SERVER_PUBLIC_KEY}
Endpoint = ${SERVER_ENDPOINT}
AllowedIPs = 10.0.0.0/24
PersistentKeepalive = 25
EOF


### ================================
### 7. wg0 を安全に再起動
### ================================
echo "=== WireGuard wg0 を再起動します ==="
sudo wg-quick down wg0 2>/dev/null
sudo wg-quick up wg0
sudo systemctl enable wg-quick@wg0
echo "=== WireGuard 起動完了 ==="


### ================================
### 8. ufw ファイアウォール設定
### ================================
# ★ 旧版は「ufw が既に有効な場合のみルール追加」だったため、未設定の Pi では
#   何もせず FW が無効のままだった。本版では ufw を導入・有効化まで行う。
#
# 許可方針:
#   ・SSH(22)         : どこからでも許可(締め出し防止。最優先で先に追加)
#   ・WireGuard(51820): どこからでも許可(VPNトンネル確立に必須)
#   ・アプリ各ポート  : LANサブネット + WireGuardサブネット(10.0.0.0/24)のみ許可
#                        → LAN内の直アクセス(ローカル利用)と VPN 経由の両方で動作
#   ・それ以外の着信  : すべて拒否(default deny incoming)
#
#   アプリが待ち受けるポート:
#     8000/tcp   FastAPI(CAT制御)
#     50000/tcp  音声ストリーミング(fastapi-audio)
#     8443/tcp   webft8(HTTPS)
#     8889/udp   cw_bridge(CWキーイング)
#   ※ 8001(direwolf KISS)は localhost 内部通信のみのため開放しない
echo "=== ufw ファイアウォール設定 ==="

# ufw が未インストールなら導入
if ! command -v ufw > /dev/null 2>&1; then
    echo "ufw をインストール中..."
    sudo apt install -y ufw
fi

# LAN サブネットを GATEWAY から導出(例: 192.168.0.1 → 192.168.0.0/24)
LAN_SUBNET="$(echo "$GATEWAY" | sed -E 's/\.[0-9]+$/.0\/24/')"
echo "LAN サブネット: $LAN_SUBNET / WireGuard サブネット: 10.0.0.0/24"

# 既定ポリシー(先に SSH を許可してから deny にすることで締め出しを防ぐ)
sudo ufw allow 22/tcp comment "SSH"
sudo ufw allow 51820/udp comment "WireGuard"
sudo ufw default deny incoming
sudo ufw default allow outgoing

# アプリ各ポートを LAN + WireGuard サブネットのみに許可
for SUBNET in "$LAN_SUBNET" "10.0.0.0/24"; do
    sudo ufw allow from "$SUBNET" to any port 8000  proto tcp comment "FastAPI ($SUBNET)"
    sudo ufw allow from "$SUBNET" to any port 50000 proto tcp comment "Audio ($SUBNET)"
    sudo ufw allow from "$SUBNET" to any port 8443  proto tcp comment "webft8 ($SUBNET)"
    sudo ufw allow from "$SUBNET" to any port 8889  proto udp comment "cw_bridge ($SUBNET)"
done

# 有効化(--force で対話プロンプトを回避)
sudo ufw --force enable
sudo ufw reload
echo "=== ufw 設定完了 ==="
sudo ufw status verbose


### ================================
### 9. QR コード表示
### ================================
echo "=== Android QR Code ==="
qrencode -t ansiutf8 < ${CLIENT_NAME}.conf

echo ""
echo "=== Android Config File ==="
cat ${CLIENT_NAME}.conf

echo ""
echo "=== 完了：WireGuard サーバー構築完了 ==="
