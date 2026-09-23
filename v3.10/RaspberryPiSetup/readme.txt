Wifi RIG CTRL  Raspberry Pi セットアップガイド（v3.10）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
配布ファイル
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
api.py                    : FastAPI サーバー本体（v3.10）
cw_bridge.py              : CW USB/NCM 中継スクリプト
setup_fastapi_radio.sh    : Pi 環境構築スクリプト（初回のみ）
create_api.sh             : api.py・mfsk-decode ビルド・webft8 生成・更新スクリプト
set_api_key.sh            : API Key 設定スクリプト
setup_ft8_encode.sh       : ft8_encode バイナリビルドスクリプト（FT8 TX 用）
setup_netwk.sh            : ネットワーク設定スクリプト
install_hamlib.sh         : Hamlib 4.7.x ソースビルドスクリプト
update_pi.sh              : macOS から api.py を Pi へ転送するスクリプト（Mac用）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v3.10 での変更点（Pi 側 / api.py）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・FT4 TX 周期検出の改善
    - FT4 の TX 送信タイミングを正確に検出するよう改善

・Direwolf 安定性の改善
    - クラッシュ時に pkill direwolf で確実に終了するよう修正

・aplay クラッシュ検出の追加
    - 音声出力プロセスの異常終了を検出し自動再起動

・API_VERSION を "3.10" に更新

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
スクリプトの役割
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
setup_fastapi_radio.sh  : Pi 環境構築（初回のみ）
                          ※ create_api.sh を内部で自動実行するため
                          ※ このスクリプト一発で FT8 を含む全機能が使える
create_api.sh           : api.py・webft8 ファイル更新（バージョンアップ時）
set_api_key.sh          : API Key の設定・変更・削除
setup_ft8_encode.sh     : FT8 TX 用バイナリビルド（FT8 TX を使う場合のみ）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ポート構成
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
8000  : メイン API（CAT 制御・APRS 制御）
50000 : 音声ストリーミング専用（fastapi-audio）
8443  : webft8 HTTPS サーバー（FT8 デジタルモード）
8888  : M5 Server UDP (NCM / WiFi)
8889  : cw_bridge UDP クライアント受信ポート

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
① Raspberry Pi OS のインストール
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Raspberry Pi Imager でイメージを作成:
  https://www.raspberrypi.com/software/

  イメージ  : Raspberry Pi OS Lite (64bit)
  Hostname  : raspizero（推奨）
  ユーザー名: 任意（スクリプトはユーザー名に依存しません）
  SSH       : 有効にする
  SSID      : イメージ作成時に設定

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
② SSH ログイン
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ssh <ユーザー名>@raspizero

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
③ セットアップスクリプト実行
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
--- ネットワーク設定（任意）---
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v3.10/RaspberryPiSetup/setup_netwk.sh
chmod +x setup_netwk.sh
bash setup_netwk.sh

--- 環境構築（FT8 含む全機能セットアップ・UpdatePi 相当）---
BASE=https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v3.10/RaspberryPiSetup
wget $BASE/setup_fastapi_radio.sh
wget $BASE/create_api.sh
wget $BASE/set_api_key.sh
wget $BASE/api.py
wget $BASE/cw_bridge.py
chmod +x setup_fastapi_radio.sh create_api.sh set_api_key.sh
sudo bash setup_fastapi_radio.sh

  ※ sudo で実行してください（webft8.service 登録に必要）
  ※ 完了後に sudo reboot で再起動してください
  ※ インターネット接続が必要（Hamlib, Direwolf, webft8 ファイルをダウンロード）
  ※ api.py を同じフォルダに置くことで「Update Pi」ボタンと同等の最新版が
    初回から適用されます

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
④ 動作確認
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
サービス状態確認:
  sudo systemctl status fastapi fastapi-audio webft8 direwolf

ログ確認:
  sudo journalctl -u fastapi -f
  sudo journalctl -u webft8 -f
  cat /tmp/webft8.log

FT8 HTTPS サーバー接続テスト:
  curl -k https://localhost:8443

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑤ アプリ接続設定
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
アプリの RIG CONNECT 画面:
  ホスト名  : raspizero（または IP アドレス）
  API Port  : 8000
  Audio Port: 50000

FT8 ボタン → webft8 UI が自動的に port 8443 へ接続します
  初回接続時に SSL 証明書の確認ダイアログが表示されます →「常に信頼する」を選択

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑥ API Key 認証の設定（任意）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
bash ~/set_api_key.sh あなたのシークレットキー
bash ~/set_api_key.sh ""   # 無効化

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑦ FT8 TX 機能の有効化（任意・追加ビルド不要な場合はスキップ）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
FT8/FT4 送信（webft8 UI からの TX）を使う場合:
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v3.10/RaspberryPiSetup/setup_ft8_encode.sh
  chmod +x setup_ft8_encode.sh
  bash setup_ft8_encode.sh

  ※ /usr/local/bin/ft8_encode バイナリがビルドされます（g++, cmake が必要）
  ※ このバイナリがなくても FT8 受信・デコードは可能です

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑧ api.py の更新方法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【推奨】アプリの「Update Pi」ボタンで更新
  v2.03 以降が動作中であれば、ボタン一発で最新 api.py に更新されます。

【推奨】アプリの「Update webFT8」ボタンで webft8 サーバー更新

【GitHub から再取得】
  Pi に SSH でログイン後:
  wget -O ~/create_api.sh https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v3.10/RaspberryPiSetup/create_api.sh
  chmod +x ~/create_api.sh
  sudo bash ~/create_api.sh

【手動】scp で直接転送
  scp api.py <ユーザー名>@raspizero:~/fastapi/api.py
  ssh <ユーザー名>@raspizero "sudo systemctl restart fastapi"

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
cw_bridge.py の起動方法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
USB-NCM モード (ATOM S3 Lite):
  python3 ~/cw_bridge.py --mode ncm

シリアルモード (ATOM Lite, 従来通り):
  python3 ~/cw_bridge.py /dev/ttyUSB0
