Wifi RIG CTRL  Raspberry Pi セットアップガイド（v2.12）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
配布ファイル
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Wifi_RIG_CTRL_v2.12.apk  : Android アプリ
api.py                    : FastAPI サーバー本体
cw_bridge.py              : CW USB/NCM 中継スクリプト
server_webft8.py          : webft8 HTTPS プロキシサーバー（UpdateWebFT8で転送）
setup_fastapi_radio.sh    : Pi 環境構築スクリプト（初回のみ）
create_api.sh             : api.py・webft8 生成・更新スクリプト
install_hamlib.sh         : Hamlib 4.7.2 ソースビルド・インストールスクリプト
set_api_key.sh            : API Key 設定スクリプト
setup_ft8_encode.sh       : ft8_encode バイナリビルドスクリプト（FT8 TX 用）
setup_netwk.sh            : ネットワーク設定スクリプト

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
スクリプトの役割
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
setup_fastapi_radio.sh  : Pi 環境構築（初回のみ）
                          ※ create_api.sh を内部で自動実行するため
                          ※ このスクリプト一発で FT8 を含む全機能が使える
create_api.sh           : api.py・webft8 ファイル更新（バージョンアップ時）
install_hamlib.sh       : Hamlib 4.7.2 をソースビルドして ~/.local にインストール
                          ※ アプリの「Update」→「Update Hamlib」ボタンから実行可能
                          ※ Pi Zero では 30〜60 分かかります
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
v2.12 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・Hamlib 4.7.2 サポート追加
    apt では Hamlib 4.7.x を取得できないため、ソースビルドに対応
    インストール先: ~/.local/bin/rigctld（sudo 不要）
    RPATH 付きビルドにより ldconfig 不要
    アプリの「Update」→「Update Hamlib」ボタンから実行（所要時間: Pi Zero で 30〜60 分）

・Update 画面を新設（UI 整理）
    Update Pi・Update Hamlib・Pi Log・Hamlib Log を 1 画面に統合
    ログエリア（緑モノスペース）でビルド進捗をリアルタイム確認
    Reload ボタンで手動更新
    RIG CONNECT 画面のボタンを 6 個（2 行×3 列）に整理

・About 画面に Pi API バージョン・Hamlib バージョンを表示
    接続中の Pi の FastAPI バージョンと rigctld バージョンを確認可能

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.11 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・ノイズリダクション強化（afftdn ベースに統一）
    NR レベルを 3 段階から 5 段階に拡張
    Level 1 (Light)   : afftdn=nf=-30:nr=15
    Level 2 (Medium)  : afftdn=nf=-25:nr=20
    Level 3 (Strong)  : afftdn=nf=-20:nr=25:tn=1
    Level 4 (Stronger): afftdn=nf=-20:nr=33:tn=1
    Level 5 (Max)     : afftdn=nf=-20:nr=40:tn=1
    NR をデュアルサーバー構成に対応（apiPort / audioPort 両方に同期送信）
    SQL ボタン長押しで 0→1→2→3→4→5→0 循環

・Update Pi の信頼性向上
    sudoers 設定を実行ユーザー名で正しく生成（sudo 時の whoami バグを修正）
    fallback 再起動の待機時間を 3s → 15s に延長（Pi Zero 対応）
    fallback 時に fastapi-audio（port 50000）も確実に再起動

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.09 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・CW デコード精度向上（RX メイン画面・TX CW パネル）
    dit/dah 境界判定を改善（×2 → ×1.8）: dah 誤判定削減
    字間ギャップ判定を緩和（×2 → ×2.5）: 字間誤切り込み削減
    ditWins 収束を遅くして急激なスピード変動耐性向上
    エネルギー計算を 3bin → 5bin 合計に拡大（弱信号 SNR 向上）
    ノイズフロア推定を 30 → 20 パーセンタイルに変更（混信耐性向上）
    TX 側: ditMs 下限を 15ms → 20ms に変更（誤カウント防止）

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
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.12/RaspberryPiSetup/setup_netwk.sh
chmod +x setup_netwk.sh
bash setup_netwk.sh

--- 環境構築（FT8 含む全機能セットアップ・UpdatePi 相当）---
BASE=https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.12/RaspberryPiSetup
wget $BASE/setup_fastapi_radio.sh
wget $BASE/create_api.sh
wget $BASE/install_hamlib.sh
wget $BASE/set_api_key.sh
wget $BASE/api.py
wget $BASE/cw_bridge.py
wget $BASE/server_webft8.py
chmod +x setup_fastapi_radio.sh create_api.sh install_hamlib.sh set_api_key.sh
sudo bash setup_fastapi_radio.sh

  ※ sudo で実行してください（webft8.service 登録に必要）
  ※ 完了後に sudo reboot で再起動してください
  ※ インターネット接続が必要（Hamlib, Direwolf, webft8 ファイルをダウンロード）
  ※ api.py / server_webft8.py / cw_bridge.py を同じフォルダに置くことで
    「Update Pi」ボタンと同等の最新版が初回から適用されます

--- Hamlib 4.7.2 のインストール（任意・アプリから Update Hamlib でも可）---
  bash install_hamlib.sh
  ※ Pi Zero では 30〜60 分かかります
  ※ インストール先: ~/.local/bin/rigctld（sudo 不要）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
④ 動作確認
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
サービス状態確認:
  sudo systemctl status fastapi fastapi-audio webft8 direwolf

ログ確認:
  sudo journalctl -u fastapi -f
  sudo journalctl -u webft8 -f
  cat /tmp/webft8.log

Hamlib バージョン確認:
  ~/.local/bin/rigctld --version   # Hamlib 4.7.2（Update Hamlib 実行後）
  rigctld --version                # システム版

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
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.12/RaspberryPiSetup/setup_ft8_encode.sh
  chmod +x setup_ft8_encode.sh
  bash setup_ft8_encode.sh

  ※ /usr/local/bin/ft8_encode バイナリがビルドされます（g++, cmake が必要）
  ※ このバイナリがなくても FT8 受信・デコードは可能です

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑧ api.py の更新方法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【推奨】アプリの「Update」→「Update Pi」ボタンで更新
  v2.03 以降が動作中であれば、ボタン一発で最新 api.py に更新されます。

【推奨】アプリの「Update」→「Update Hamlib」ボタンで Hamlib 4.7.2 インストール
  ビルドログは「Update」→「Hamlib Log」タブで確認できます。

【GitHub から再取得】スクリプトを最新版に更新してから再セットアップ
  Pi に SSH でログイン後:
  wget -O ~/create_api.sh https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.12/RaspberryPiSetup/create_api.sh
  wget -O ~/set_api_key.sh https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.12/RaspberryPiSetup/set_api_key.sh
  chmod +x ~/create_api.sh ~/set_api_key.sh
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
