RaspberryPiのセットアップについて（Wifi_Rig_CTRL v2.02）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.02 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・cw_bridge.py に USB-NCM モード対応を追加
  ATOM S3 Lite サーバーを USB-C ケーブルで Pi に直結して運用可能になりました。

  シリアルモード (旧バージョン・ATOM Lite 互換):
    python3 cw_bridge.py /dev/ttyUSBx

  USB-NCMモード (ATOM S3 Lite):
    python3 cw_bridge.py --mode ncm [SERVER_NCM_IP]
    python3 cw_bridge.py --mode ncm               # デフォルト: 192.168.7.1

v2.01 からアップグレードする場合:
  cw_bridge.py を差し替えるだけです。既存の設定やスクリプトへの影響はありません。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
USB-NCM モードの仕組み
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ATOM S3 Lite サーバーが USB-NCM デバイスとして動作します。
Pi を USB ホストとして USB-C ケーブルで接続すると、Pi 側に
ネットワークインタフェース (usb0 など) が自動的に作成されます。

ネットワーク構成:
  ATOM S3 Lite (Server) : 192.168.7.1/30 (DHCP サーバー機能内蔵)
  Raspberry Pi          : 192.168.7.2/30 (DHCP で自動取得)

cw_bridge.py は UDP でサーバーと直接通信します:
  iOS/Android アプリ → Pi:8889 (UDP) → cw_bridge → Server:8888 (UDP/NCM)
  Server:8888 (SYNC応答 / PONG) → cw_bridge → アプリ

シリアル通信・pyserial は USB-NCM モードでは不要です。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Pi 側の USB-NCM インタフェース設定
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
多くの環境では DHCP が自動的に機能しますが、
インタフェースが認識されない場合は以下を試してください。

[dhcpcd を使用している場合 (Raspberry Pi OS Bullseye 以前)]
  /etc/dhcpcd.conf の末尾に追記:
    allowinterfaces usb0
    interface usb0

[NetworkManager を使用している場合 (Bookworm 以降)]
  sudo nmcli dev set usb0 managed yes

インタフェース名を確認:
  ip link show | grep -E "usb|eth"

接続確認:
  ping 192.168.7.1

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
cw_bridge.py の起動方法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
USB-NCM モード (ATOM S3 Lite):
  python3 ~/cw_bridge.py --mode ncm

シリアルモード (ATOM Lite, 従来通り):
  python3 ~/cw_bridge.py /dev/ttyUSB0

systemd サービスとして登録する場合の ExecStart 例 (NCM モード):
  ExecStart=/home/pi/venv/bin/python3 /home/pi/cw_bridge.py --mode ncm

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
(以下 v2.01 と同じ)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
スクリプトの役割
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
setup_fastapi_radio.sh  ：ラズパイ環境構築（初回のみ）
create_api.sh           ：api.py の生成・更新（初回 + バージョンアップ時）
set_api_key.sh          ：API Key の設定・変更・削除

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ポート構成
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
8000  ：メインAPI（CAT制御・APRS制御）
50000 ：音声ストリーミング専用（fastapi-audio）
8888  ：M5 Server UDP (NCM / WiFi)
8889  ：cw_bridge UDP クライアント受信ポート

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
①RaspberryPi Imager のインストール
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
https://www.raspberrypi.com/software/

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
②起動用 MicroSD カード作成
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・イメージ：Raspberry Pi OS Lite (64bit)
・Hostname：「raspizero」推奨
・ユーザ名：pi を想定
・SSID：イメージ作成時に設定
・SSH を有効にする

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
③SSH ログイン
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ssh pi@raspizero

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
④セットアップスクリプト実行
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
--- ネットワーク設定 ---
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.02/RaspberryPiSetup/setup_netwk.sh
chmod +x setup_netwk.sh
bash setup_netwk.sh

--- 環境構築 + api.py 生成 ---
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.02/RaspberryPiSetup/setup_fastapi_radio.sh
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.02/RaspberryPiSetup/create_api.sh
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.02/RaspberryPiSetup/set_api_key.sh
chmod +x setup_fastapi_radio.sh create_api.sh set_api_key.sh
bash setup_fastapi_radio.sh

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑤動作確認
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
sudo systemctl status fastapi
ログ確認: sudo journalctl -u fastapi -f

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑥アプリ接続設定
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
アプリの RIG CONNECT 画面:
  ホスト名  : raspizero（または IP アドレス）
  API Port  : 8000
  Audio Port: 50000

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑦API Key 認証の設定（任意）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
bash ~/set_api_key.sh あなたのシークレットキー
bash ~/set_api_key.sh ""  # 無効化

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑧api.py の更新方法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
bash ~/create_api.sh
