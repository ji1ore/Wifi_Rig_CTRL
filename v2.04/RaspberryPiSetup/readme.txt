RaspberryPiのセットアップについて（Wifi_Rig_CTRL v2.04）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.04 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・CW モード時の BK-IN 状態が常に OFF になっていた問題を修正
  poll_signal() に SBKIN / FBKIN ポーリングを追加（15 秒間隔）
  TX 中はポーリングをスキップ（IC-7300 PA リレー保護）

v2.03 からアップグレードする場合:
  Android アプリの「Update Pi」ボタンを押してください。
  または SSH で以下を実行:
    bash ~/create_api.sh

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
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.04/RaspberryPiSetup/setup_netwk.sh
chmod +x setup_netwk.sh
bash setup_netwk.sh

--- 環境構築 + api.py 生成 ---
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.04/RaspberryPiSetup/setup_fastapi_radio.sh
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.04/RaspberryPiSetup/create_api.sh
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.04/RaspberryPiSetup/set_api_key.sh
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
