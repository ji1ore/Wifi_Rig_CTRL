Wifi RIG CTRL  Raspberry Pi セットアップガイド（v2.05）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
配布ファイル
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
WifiRigCtrl_v2.05.apk    : Android アプリ
api.py                    : FastAPI サーバー本体
cw_bridge.py              : CW USB/NCM 中継スクリプト
setup_fastapi_radio.sh    : Pi 環境構築スクリプト（初回のみ）
create_api.sh             : api.py 生成・更新スクリプト
set_api_key.sh            : API Key 設定スクリプト
setup_netwk.sh            : ネットワーク設定スクリプト

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.05 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
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
    ※ v2.02 以前からのアップグレードは初回のみ手動 scp が必要

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
v2.02 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・cw_bridge.py に USB-NCM モード対応を追加
  ATOM S3 Lite サーバーを USB-C ケーブルで Pi に直結して運用可能

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
スクリプトの役割
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
setup_fastapi_radio.sh  : Pi 環境構築（初回のみ）
create_api.sh           : api.py の生成・更新（初回 + バージョンアップ時）
set_api_key.sh          : API Key の設定・変更・削除

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ポート構成
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
8000  : メイン API（CAT 制御・APRS 制御）
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
--- ネットワーク設定 ---
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.05/RaspberryPiSetup/setup_netwk.sh
chmod +x setup_netwk.sh
bash setup_netwk.sh

--- 環境構築 + api.py 生成 ---
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.05/RaspberryPiSetup/setup_fastapi_radio.sh
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.05/RaspberryPiSetup/create_api.sh
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.05/RaspberryPiSetup/set_api_key.sh
chmod +x setup_fastapi_radio.sh create_api.sh set_api_key.sh
bash setup_fastapi_radio.sh

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
④ 動作確認
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
sudo systemctl status fastapi
ログ確認: sudo journalctl -u fastapi -f

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
⑦ api.py の更新方法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【推奨】アプリの「Update Pi」ボタンで更新
  v2.03 以降が動作中であれば、ボタン一発で最新 api.py に更新されます。

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
