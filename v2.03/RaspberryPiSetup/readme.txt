RaspberryPiのセットアップについて（Wifi_Rig_CTRL v2.03）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.03 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・FT8/FT4 デコード機能追加（webft8 ベース）
  - webft8 HTTPS サーバー（server_webft8.py）を新規追加
  - Pi の音声をブラウザ経由でリアルタイムデコード
  - TX: Android から生成した PCM を Pi へストリーミング送信
  - 時刻同期: Pi クロックオフセットを自動計算し Date.now() を補正
  - レイテンシ自動較正: clockOffset から audioLatencyMs を自動算出
  - CQ オーバーレイ表示（デコードされた CQ コールを画面上部に表示）
  - QSO ログ機能（ADIF 形式エクスポート対応）
  - FT4 / FT8 モード切替

・ホームディレクトリの汎用化
  - /home/pi ハードコードを廃止
  - bash スクリプト: $HOME / $(whoami) で実行ユーザーに自動対応
  - Python (api.py): Path.home() で動的取得
  - pi 以外のユーザー名（例: hamcat）でも動作

・時刻同期 API 改善（/admin/set_time）
  - chronyc makestep → ntpdate → timedatectl → Android 時刻 の順で試行
  - NTP 不可環境では Android 端末の時刻をフォールバックとして使用

・webft8 リモートアップデート機能追加（/admin/update_webft8）
  - アプリから server_webft8.py を Pi に送信して再起動

・API バージョンを 2.03 に更新

v2.02 からのアップグレード:
  create_api.sh のみ再実行してください（api.py が更新されます）。
  bash ~/create_api.sh

  webft8 を使用する場合は追加でセットアップが必要です（setup_ft8_encode.sh 参照）。

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
  - webft8 静的ファイルをダウンロード・配置
  - server_webft8.py（webft8 HTTPS サーバー）を生成
  - fastapi サービス再起動

server_webft8.py        ：webft8 HTTPS プロキシサーバー（port 8443）
  - webft8 静的ファイルを HTTPS で配信
  - /audio_sub を FastAPI へプロキシ（12kHz PCM ストリーム）
  - /audio_tx を FastAPI へプロキシ（FT8 TX 送信）
  ※ create_api.sh 実行後、systemd サービス webft8 として自動起動

set_api_key.sh          ：API Key の設定・変更・削除（単独で何度でも実行可）
  - ~/fastapi/.env に API_KEY を書き込む
  - systemd drop-in の自動追加（未設定時）
  - fastapi / fastapi-audio サービス自動再起動

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ポート構成
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
8000  ：メインAPI（CAT制御・APRS制御・CW送信）    → アプリの「API Port」
50000 ：音声ストリーミング専用（fastapi-audio）    → アプリの「Audio Port」
8443  ：webft8 HTTPS サーバー（FT8/FT4デコード）

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
・ユーザ名：任意（pi / hamcat 等、スクリプトが自動検出）
・SSID：イメージ作成時に設定
・SSH を有効にする

なお、RaspberryPi Zero 2W の WiFi について：
ルータの高速ローミング (802.11r) が ON だと接続できない場合があります。
WiFi に繋がらない場合はルータ側でローミングを無効にしてください。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
③SSH ログイン
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ssh <ユーザ名>@raspizero

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
④セットアップスクリプト実行
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
以下を順に実行してください（Hamlib・direwolf のビルドに時間がかかります）。

--- ネットワーク設定 ---
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.03/RaspberryPiSetup/setup_netwk.sh
chmod +x setup_netwk.sh
bash setup_netwk.sh

※ここでログアウトして再度 SSH ログインしてください。
logout

※ログイン後、IPv4 アドレスが取得できていることを確認:
ifconfig

--- 環境構築 + api.py 生成 ---
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.03/RaspberryPiSetup/setup_fastapi_radio.sh
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.03/RaspberryPiSetup/create_api.sh
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.03/RaspberryPiSetup/set_api_key.sh
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
アプリの Connect 画面で以下を設定:

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
⑨FT8/FT4 デコードについて
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
FT8/FT4 デコードには webft8 を使用します。
create_api.sh 実行時に自動でセットアップされます。

アプリの FT8 ボタンをタップすると webft8 画面が開きます。
・Sync ボタン：Pi クロックオフセットを測定し、デコードタイミングを自動補正
・TimeSync ボタン：Pi の時刻を NTP（またはAndroid時刻）で同期

FT8 TX には webft8 の TX ボタンを使用してください。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑩APRS について
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Raspberry Pi 上の Direwolf を使って APRS ビーコン送信に対応しています。
APRS の設定はアプリの APRS ボタン長押しで行います。

GPS 座標はアプリ（Android）の GPS を使用します。
APRS 設定画面で「Use Android GPS」を ON にすると、
Android の位置情報が自動的に Raspberry Pi に送信されます。
