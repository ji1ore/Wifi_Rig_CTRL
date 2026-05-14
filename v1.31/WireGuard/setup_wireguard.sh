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

SERVER_ENDPOINT="\${DUCKDNS_DOMAIN}.duckdns.org:51820"

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
### 8. QR コード表示
### ================================
echo "=== Android QR Code ==="
qrencode -t ansiutf8 < ${CLIENT_NAME}.conf

echo ""
echo "=== Android Config File ==="
cat ${CLIENT_NAME}.conf

echo ""
echo "=== 完了：WireGuard サーバー構築完了 ==="
