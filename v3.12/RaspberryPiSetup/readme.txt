Wifi RIG CTRL  Raspberry Pi セットアップガイド（v3.12）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
配布ファイル
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
api.py                    : FastAPI サーバー本体（v3.12）
cw_bridge.py              : CW USB/NCM 中継スクリプト
setup_fastapi_radio.sh    : Pi 環境構築スクリプト（初回のみ）
create_api.sh             : api.py・mfsk-decode ビルド・更新スクリプト
set_api_key.sh            : API Key 設定スクリプト
setup_ft8_encode.sh       : ft8_encode バイナリビルドスクリプト（FT8 TX 用）
setup_netwk.sh            : ネットワーク設定スクリプト
install_hamlib.sh         : Hamlib 4.7.x ソースビルドスクリプト
update_pi.sh              : macOS から api.py / mfsk-decode を Pi へ転送する開発用スクリプト（Mac用）
mfsk-decode/              : FT8/FT4 デコーダ（Rust）ソース。create_api.sh / Update Pi が Pi 上でビルドします
ft8_encode_main.cpp       : ft8_encode のソース（setup_ft8_encode.sh / create_api.sh が使用）

※ Pi 側スクリプトはすべて ${SUDO_USER:-$(whoami)} で実行ユーザーを取得します。
   ユーザー名 "pi" である必要はありません（任意のユーザー名で動作します）。
※ update_pi.sh（Mac 用）だけは接続先ユーザー名が必要です。既定は Mac のユーザー名で、
   環境変数または引数で指定できます：  PI_USER=jiro PI_HOST=raspizero.local bash update_pi.sh

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v3.12 での変更点（Pi 側 / api.py）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・FastAPI 再起動後の自動復旧
    - リグ選択（/radio/open の内容）と音声デバイス選択をファイルに保存し、起動時に読み戻す
    - 以前は fastapi 再起動で選択が失われ、rigctld が落ちても誰も再起動できず
      「PTT が 500」「周波数が変わらない」「S メーター 0」のまま固まっていた
    - 音声デバイス選択は :8000（API）と :50000（音声）の 2 プロセス間でファイル経由で共有

・受信音声チェーンの改善
    - ffmpeg の volume を 10.0（+20 dB）→ 2.0（+6 dB）に変更。常時クリッピングしていたため
      RTTY のトーンが矩形波になりデコードできなかった問題を解消
    - 既に同じサンプルレートで配信中の場合、再接続時に FT8 キャプチャ（arecord）を止めない
      （再接続のたびに数秒音声が欠け、RTTY でシフト文字を失って以降が化けていた）

・RTTY FSK 送信エンドポイント /radio/rtty_fsk_tx を追加（実験的・アプリ UI からは未使用）
    - シリアルポートの DTR（Mark/Space）/ RTS（PTT）でキーイング（FT-991A の Data COM ポート向け）
    - rigctld が使用中の CAT ポートではなく、空いているシリアルポートが必要です
    - v3.12 のアプリは RTTY 送信を Pi 経由の AFSK 音声で行うため、このエンドポイントは呼びません

・診断用エンドポイント /admin/amixer を追加（サウンドカードのミキサー設定を読み取り専用で表示）

・mfsk-decode を mfsk-core v0.12.0 に更新（FT4 デコード --ft4、既知コールサインのハッシュ -k）

・API_VERSION を "3.12" に更新

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
スクリプトの役割
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
setup_fastapi_radio.sh  : Pi 環境構築（初回のみ）
                          ※ create_api.sh を内部で自動実行するため
                          ※ このスクリプト一発で FT8 を含む全機能が使える
create_api.sh           : api.py・mfsk-decode・ft8_encode の更新（バージョンアップ時）
                          ※ アプリの「Update Pi」ボタンはこのスクリプトを Pi に送って実行します
set_api_key.sh          : API Key の設定・変更・削除
setup_ft8_encode.sh     : FT8 TX 用バイナリビルド（FT8 TX を使う場合のみ）
install_hamlib.sh       : Hamlib 4.7.x ソースビルド（アプリの「Update Hamlib」ボタン相当）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ポート構成
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
8000  : メイン API（CAT 制御・APRS 制御・FT8 デコード結果 SSE）
50000 : 音声ストリーミング専用（fastapi-audio）
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
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v3.12/RaspberryPiSetup/setup_netwk.sh
chmod +x setup_netwk.sh
bash setup_netwk.sh

--- 環境構築（FT8 含む全機能セットアップ・UpdatePi 相当）---
BASE=https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v3.12/RaspberryPiSetup
wget $BASE/setup_fastapi_radio.sh
wget $BASE/create_api.sh
wget $BASE/set_api_key.sh
wget $BASE/api.py
wget $BASE/cw_bridge.py
chmod +x setup_fastapi_radio.sh create_api.sh set_api_key.sh
sudo bash setup_fastapi_radio.sh

  ※ sudo で実行してください（systemd サービス登録に必要）
  ※ 完了後に sudo reboot で再起動してください
  ※ インターネット接続が必要（Hamlib, Direwolf, Rust/mfsk-core をダウンロード）
  ※ api.py を同じフォルダに置くことで「Update Pi」ボタンと同等の最新版が
    初回から適用されます

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
④ 動作確認
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
サービス状態確認:
  sudo systemctl status fastapi fastapi-audio direwolf

ログ確認:
  sudo journalctl -u fastapi -f
  sudo journalctl -u fastapi-audio -f

API バージョン確認:
  curl http://localhost:8000/version

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑤ アプリ接続設定
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
アプリの RIG CONNECT 画面:
  ホスト名  : raspizero（または IP アドレス）
  API Port  : 8000
  Audio Port: 50000

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑥ API Key 認証の設定（任意）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
bash ~/set_api_key.sh あなたのシークレットキー
bash ~/set_api_key.sh ""   # 無効化

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑦ FT8 TX 機能の有効化（任意・create_api.sh が自動ビルドに失敗した場合のみ）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
FT8/FT4 送信を使う場合、通常は create_api.sh が ft8_encode を自動ビルドします。
失敗した場合のみ手動で:
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v3.12/RaspberryPiSetup/setup_ft8_encode.sh
  chmod +x setup_ft8_encode.sh
  bash setup_ft8_encode.sh

  ※ /usr/local/bin/ft8_encode バイナリがビルドされます（g++, cmake が必要）
  ※ このバイナリがなくても FT8 受信・デコードは可能です

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑧ api.py の更新方法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【推奨】アプリの「Update Pi」ボタンで更新
  v2.03 以降が動作中であれば、ボタン一発で最新 api.py に更新されます。
  （api.py 送信 → create_api.sh 実行 → Pi 再起動 → mfsk-decode ビルド。Pi Zero では数分〜十数分）

【GitHub から再取得】
  Pi に SSH でログイン後:
  wget -O ~/create_api.sh https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v3.12/RaspberryPiSetup/create_api.sh
  chmod +x ~/create_api.sh
  sudo bash ~/create_api.sh

【手動】scp で直接転送
  scp api.py <ユーザー名>@raspizero:~/fastapi/api.py
  ssh <ユーザー名>@raspizero "sudo systemctl restart fastapi fastapi-audio"

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
RTTY 送信について（v3.12）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・アプリ（Android / iOS）の RTTY 送信は Pi 経由の AFSK 音声送信です。リグをデータモード
  （LSB/USB のデータ入力）に切り替え、PTT を ON にして音声を送出します
・送信時のダイヤルオフセット（Mark トーン分、FT-991A では DATA SHIFT メニューの 1000 Hz 分）は
  アプリ側で自動補正します。FT-991A の DATA SHIFT を 0 にしている場合は補正が合わなくなります
・/radio/rtty_fsk_tx（シリアル DTR/RTS キーイング）は実験的エンドポイントで、アプリからは使用しません。
  使用する場合は rigctld が占有している CAT ポートではなく、空いているシリアルポートが必要です
  （CAT ポートを使うとビット幅が乱れます）。IC-705 には FSK 入力がありません

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
cw_bridge.py の起動方法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
USB-NCM モード (ATOM S3 Lite):
  python3 ~/cw_bridge.py --mode ncm

シリアルモード (ATOM Lite, 従来通り):
  python3 ~/cw_bridge.py /dev/ttyUSB0
