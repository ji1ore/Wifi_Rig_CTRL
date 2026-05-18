RaspberryPiのセットアップについて（Wifi_Rig_CTRL v1.50）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v1.50 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・APRS アイドル中（ビーコン送信待ち）も音声受信（SPK）を継続するよう変更
  （fastapi-audio.service を APRS 開始・停止時に stop/start しなくなった）

v1.40 からアップグレードする場合:
  create_api.sh を再実行してください（api.py が更新されます）。
  bash ~/create_api.sh

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
スクリプトの役割
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
setup_fastapi_radio.sh  ：ラズパイ環境構築（初回のみ）
  - パッケージインストール（Hamlib / ffmpeg / alsa / direwolf 等）
  - Python venv + FastAPI / uvicorn インストール
  - systemd サービス登録（fastapi / fastapi-audio / direwolf）
  - sudoers 設定
  - 最後に create_api.sh を呼び出して api.py を生成

create_api.sh           ：api.py の生成・更新（初回 + バージョンアップ時）
  - 既存 api.py をバックアップ（api.py.bak2）
  - direwolf の drop-in 設定（SIGKILL後に再起動しない）
  - systemd drop-in で EnvironmentFile を追加（API Key 用）
  - .env テンプレートを生成（未作成時）
  - 最新の api.py を上書き生成
  - fastapi サービス再起動

set_api_key.sh          ：API Key の設定・変更・削除（単独で何度でも実行可）
  - /home/pi/fastapi/.env に API_KEY を書き込む
  - systemd drop-in の自動追加（未設定時）
  - fastapi / fastapi-audio サービス自動再起動

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ポート構成
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
8000  ：メインAPI（CAT制御・APRS制御）          → アプリの「API Port」
50000 ：音声ストリーミング専用（fastapi-audio）  → アプリの「Audio Port」

※ APRSビーコン送信中も fastapi-audio は動作し続けます。
  direwolf（再生のみ）と fastapi-audio（録音のみ）は同時動作可能です。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
①RaspberryPi Imager のインストール
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
https://www.raspberrypi.com/software/

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
②起動用 MicroSD カード作成
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・イメージ：Raspberry Pi OS Lite (64bit)
・Hostname：「raspizero」推奨（以降の設定がそのまま使用可能）
・ユーザ名：pi を想定
・SSID：イメージ作成時に設定
・SSH を有効にする

なお、RaspberryPi Zero 2W の WiFi について：
ルータの高速ローミング (802.11r) が ON だと接続できない場合があります。
WiFi に繋がらない場合はルータ側でローミングを無効にしてください。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
③SSH ログイン
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ssh pi@raspizero

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
④セットアップスクリプト実行
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
以下を順に実行してください（Hamlib・direwolf のビルドに時間がかかります）。

--- ネットワーク設定 ---
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v1.50/RaspberryPiSetup/setup_netwk.sh
chmod +x setup_netwk.sh
bash setup_netwk.sh

※ここでログアウトして再度 SSH ログインしてください。
logout

※ログイン後、IPv4 アドレスが取得できていることを確認:
ifconfig

--- 環境構築 + api.py 生成 ---
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v1.50/RaspberryPiSetup/setup_fastapi_radio.sh
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v1.50/RaspberryPiSetup/create_api.sh
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v1.50/RaspberryPiSetup/set_api_key.sh
chmod +x setup_fastapi_radio.sh create_api.sh set_api_key.sh
bash setup_fastapi_radio.sh

--- 後片付け ---
sudo systemctl status fastapi
rm setup_netwk.sh setup_fastapi_radio.sh

※ create_api.sh / set_api_key.sh は再利用するため残しておいてください。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑤動作確認
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
以下のように表示されれば正常です:

  ● fastapi.service - FastAPI Radio Control Service
       Loaded: loaded (/etc/systemd/system/fastapi.service; enabled)
       Active: active (running) since ...

ログ確認:
  sudo journalctl -u fastapi -f

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑥アプリ接続設定
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
アプリの RIG CONNECT 画面で以下を設定:

  ホスト名  : raspizero（または IP アドレス）
  API Port  : 8000
  Audio Port: 50000

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑦API Key 認証の設定（任意）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
外部ネットワーク公開時など、アクセスを制限したい場合に設定します。
未設定の場合は認証なしで動作します（LAN内のみなら不要）。

set_api_key.sh を使って設定します:

  # キーを直接指定
  bash ~/set_api_key.sh あなたのシークレットキー

  # 認証を無効化（キーを空にする）
  bash ~/set_api_key.sh ""

アプリ側は Connect 画面の「API Key」欄に同じ値を入力してください。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑧api.py の更新方法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
アプリのバージョンアップ時は create_api.sh のみ実行してください:

  bash ~/create_api.sh

api.py の旧バージョンは api.py.bak2 にバックアップされます。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑨APRS について
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v1.20 より、Raspberry Pi 上の Direwolf を使って APRS ビーコン送信に対応しました。
APRS の設定はアプリの APRS ボタン長押しで行います。

GPS 座標はアプリ（Android）の GPS を使用します。
APRS 設定画面で「Use Android GPS」を ON にすると、
Android の位置情報が自動的に Raspberry Pi に送信されます。