Wifi RIG CTRL  Raspberry Pi セットアップガイド（v2.10）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
配布ファイル
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Wifi_RIG_CTRL_v2.10.apk  : Android アプリ
api.py                    : FastAPI サーバー本体
cw_bridge.py              : CW USB/NCM 中継スクリプト
server_webft8.py          : webft8 HTTPS プロキシサーバー（UpdateWebFT8で転送）
setup_fastapi_radio.sh    : Pi 環境構築スクリプト（初回のみ）
create_api.sh             : api.py・webft8 生成・更新スクリプト
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
v2.10 での変更点
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
v2.08 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・CW TX 開始ラグを修正（USBシリアル接続時）
    open_radio で current_ptt_type に実効 PTT タイプ（RIG）を保存するよう修正
    ttyACM/ttyUSB 使用時に CW TX 毎に rigctld 再起動が発生していた問題を解消
    （タイミングにより 0〜7 秒のラグが発生していた）

・CW TX パネルの UI 改善
    ボタンを大型化し、1画面に収まるレイアウトに変更
    横向き（ランドスケープ）時は2カラムレイアウトで表示

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.07 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・USB シリアル接続時の PTT 自動最適化（IC-705 USB 対応強化）
    ttyACM / ttyUSB デバイス使用時は PTT を自動的に RTS → CAT（CI-V）に切替
    IC-705 USB オーディオリセット問題を回避

・rigctld 起動後の PTT 強制解除
    rigctld 再起動時に誤って TX になることを防止

・TX 中の rigctld 再起動を禁止
    CW/音声送信中に rigctld が再起動して DTR が切れる問題を修正

・PTT タイプ表示を「RIG」→「CAT」に変更（UI 表記統一）

・CW CQ リピート UI 改善

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.06 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・CW USB（DualKey）の Pi 未接続時の同期動作を修正
    Pi 不在でもキーイングタイミングが正しく計算されるよう改善

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.05 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・FT8 デジタルモード対応
    ポート 8443 に HTTPS サーバーを自動起動
    setup_fastapi_radio.sh 一発で FT8 まで完全セットアップ

・CW TX 終了方式の選択機能を追加
    - 時間予測モード（デフォルト）: IC-7300 / IC-705 内部キーヤー向け
    - PTT ポーリングモード: CAT で PTT 状態を返すリグ向け
      CW TX パネルの「TX end: PTT poll」スイッチで切替

・CW TX 開始遅延を短縮（600ms → 100ms）

・S メーター表示修正（IC-705 で常時 S9 になる問題を修正）

・BK-IN 状態をリグから自動取得（15 秒ごとにポーリング）

・パス設定の汎用化
    Pi のユーザー名が「pi」以外でも正常動作するよう修正
    （pizero, ubuntu 等）

・アプリの「Update Pi」ボタンで api.py を更新可能
    v2.03 以降からのアップグレードは「Update Pi」ボタン一発で完了

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.04 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・FT8 送信機能強化
・APRS 機能改善

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.03 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・CW USB 中継（ATOMlite Server 対応）
・WireGuard VPN 対応

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
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.10/RaspberryPiSetup/setup_netwk.sh
chmod +x setup_netwk.sh
bash setup_netwk.sh

--- 環境構築（FT8 含む全機能セットアップ・UpdatePi 相当）---
BASE=https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.10/RaspberryPiSetup
wget $BASE/setup_fastapi_radio.sh
wget $BASE/create_api.sh
wget $BASE/set_api_key.sh
wget $BASE/api.py
wget $BASE/cw_bridge.py
wget $BASE/server_webft8.py
chmod +x setup_fastapi_radio.sh create_api.sh set_api_key.sh
sudo bash setup_fastapi_radio.sh

  ※ sudo で実行してください（webft8.service 登録に必要）
  ※ 完了後に sudo reboot で再起動してください
  ※ インターネット接続が必要（Hamlib, Direwolf, webft8 ファイルをダウンロード）
  ※ api.py / server_webft8.py / cw_bridge.py を同じフォルダに置くことで
    「Update Pi」ボタンと同等の最新版が初回から適用されます

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
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.10/RaspberryPiSetup/setup_ft8_encode.sh
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
  server_webft8.py を Pi に送信して webft8 サービスを再起動します。

【GitHub から再取得】スクリプトを最新版に更新してから再セットアップ
  Pi に SSH でログイン後:
  wget -O ~/create_api.sh https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.10/RaspberryPiSetup/create_api.sh
  wget -O ~/set_api_key.sh https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.10/RaspberryPiSetup/set_api_key.sh
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
