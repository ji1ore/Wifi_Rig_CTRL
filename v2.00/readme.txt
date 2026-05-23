Wifi_RIG_CTRL for Android v2.00
Android スマートフォンによる無線機リモートコントロールシステム
JI1ORE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━


【概要】
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Android スマートフォンから Raspberry Pi Zero 2W 経由で
無線機をリモート制御するシステムです。

・受信周波数・モード・信号強度のリアルタイム表示
・周波数・モード・パワー・スケルチの変更
・受信音声のスマートフォンスピーカー再生（SPK）
・PTT ON/OFF と音声送信（マイクの音声を無線機に送出）
・WiFi PTT（外部デバイスと連動した PTT 制御）
・USB CW 中継（縦振り電鍵を Android に直接 USB 接続してキー信号を中継）
・多チャンネル CW デコーダー
・FT8 / FT4 受信デコード（実験的機能）
・APRS ビーコン送信（DireWolf 経由、Android GPS 対応）
・複数プロファイル対応（接続先の切り替え）
・API Key 認証対応
・WireGuard VPN による外出先接続対応


【v2.00 の新機能】
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・FT8 / FT4 受信デコード（実験的機能）
  Raspberry Pi 側の ft8wav デコーダを使用して FT8/FT4 信号をリアルタイムデコードします。
  ※ この機能は実験的なものです。受信状況・環境によってはデコードされない場合があります。
  ※ 使用するには ft8wav バイナリを Raspberry Pi に配置する必要があります。

  - 15 秒（FT8）/ 7.5 秒（FT4）サイクルに同期した自動デコード
  - デコード結果リスト表示（SNR・周波数・メッセージ）
  - コールサインを選択して TX 送信

・CW スパイクノイズ除去の強化
  受信音声の突発的なノイズ（クリック・スパイク）を EMA ベースのフィルターで除去。
  SPK 再生時のクリックノイズが大幅に軽減されました。

・FM-CW Auto Relay の安定性向上
  2回目以降のキー押下で音声が出なくなる問題を修正。

・Raspberry Pi 側の更新が必要
  create_api.sh を再実行してください。


【ファイル内容】
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Wifi_RIG_CTRL_v2.00.apk  リリース版 APK（通常インストール用）
readme.txt                本ファイル
README.md                 GitHub 掲載用 README


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【手順 1】Android アプリのインストール
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. Wifi_RIG_CTRL_v2.00.apk をスマートフォンにコピー
2. Android の設定 →「提供元不明のアプリ」→ インストールを許可するアプリを追加
3. APK をタップしてインストール

必要環境：Android 8.0（API 26）以上


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【手順 2】Raspberry Pi のセットアップ（初回のみ）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

詳細は RaspberryPiSetup/readme.txt を参照してください。

【必要なもの】
・Raspberry Pi Zero 2W
・MicroSD カード（16GB 以上）
・無線機との接続用 USB ケーブル（CAT + 音声）
・無線機（動作確認済み：Yaesu FT-991A、Icom IC-705）

【① SD カード作成】
Raspberry Pi Imager で作成:  https://www.raspberrypi.com/software/
  イメージ  : Raspberry Pi OS Lite (64bit)
  Hostname  : raspizero（推奨）
  ユーザー名: pi
  SSH       : 有効
  WiFi SSID : 自宅の SSID を設定

※ルーターの高速ローミング（802.11r）が ON だと接続できない場合があります。

【② SSH ログイン】
  ssh pi@raspizero

【③ セットアップスクリプト実行】
  # ネットワーク設定
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.00/RaspberryPiSetup/setup_netwk.sh
  chmod +x setup_netwk.sh && bash setup_netwk.sh

  # ← ここで一度ログアウトして再ログイン
  logout
  ssh pi@raspizero

  # 環境構築 + api.py 生成（Hamlib ビルドで数十分かかります）
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.00/RaspberryPiSetup/setup_fastapi_radio.sh
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.00/RaspberryPiSetup/create_api.sh
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.00/RaspberryPiSetup/set_api_key.sh
  chmod +x setup_fastapi_radio.sh create_api.sh set_api_key.sh
  bash setup_fastapi_radio.sh

【④ 動作確認】
  sudo systemctl status fastapi
  →「Active: active (running)」と表示されれば正常
  ログ確認: sudo journalctl -u fastapi -f

【ポート構成】
  8000  : メイン API（CAT 制御・APRS 制御）    → アプリの「API Port」
  50000 : 音声ストリーミング専用                → アプリの「Audio Port」


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【手順 3（任意）】API Key 認証の設定
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

LAN 内のみで使用する場合は不要です。外部公開時に設定します。

  # Raspberry Pi 上で実行
  bash ~/set_api_key.sh あなたのシークレットキー

アプリ側は Connect 画面の「API Key」欄に同じ値を入力してください。

  # 認証を無効化する場合
  bash ~/set_api_key.sh ""


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【手順 4（任意）】WireGuard VPN 設定（外出先から接続する場合）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

詳細は WireGuard/readme.txt を参照してください。

【前提条件】
・ルーターで UDP ポート 51820 を Raspberry Pi に転送設定済み
・DDNS サービスへの登録（DuckDNS 推奨: https://www.duckdns.org/）

【スクリプト実行】
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.00/WireGuard/setup_wireguard.sh
  chmod +x setup_wireguard.sh
  nano setup_wireguard.sh
    ← 以下を自分の環境に合わせて変更:
      STATIC_IP="192.168.0.XX/24"  ← Raspberry Pi に割り当てる固定 IP
      GATEWAY="192.168.0.1"        ← ルーターの IP
      DUCKDNS_DOMAIN="yourdomain"  ← DuckDNS のサブドメイン名
  bash setup_wireguard.sh

【Android 側の設定】
1. Google Play で「WireGuard」をインストール
2. アプリ起動 → 「+」→「QR コードから作成」
3. スクリプト実行後にターミナルに表示された QR コードをスキャン
4. トンネルを ON にして接続

【VPN 接続時のアプリ設定】
  ホスト名  : 10.0.0.1（VPN 内の Raspberry Pi IP）
  API Port  : 8000
  Audio Port: 50000
  ※ mDNS（raspizero.local）は VPN 経由では使用不可


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【FT8 / FT4 機能（実験的）】
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

※ この機能は実験的なものです。受信状況・Pi の性能・ft8wav の設定によって
  デコード結果が変わります。すべての信号がデコードされるとは限りません。

【必要なもの】
・ft8wav バイナリを Raspberry Pi のホームディレクトリに配置
  （create_api.sh が自動でビルドを試みます）

【操作】
1. SPK をON にして受信音声が聞こえる状態にする
2. メイン画面から FT8 タブに切り替える
3. FT8/FT4 切り替えボタンで使用プロトコルを選択
4. 15秒サイクル（FT8）または 7.5秒サイクル（FT4）でデコード結果が表示される
5. コールサインをタップして TX 送信


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【CW デコーダー操作説明】
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SPK ボタン長押し → CW デコード表示パネルの表示/非表示を切り替え

表示パネル:
  TX:  （水色）自分の送信内容（USB CW 中継時）
  RX0  （黄色）最も強い受信信号
  RX1〜4  他の受信信号（複数局同時受信時）

  ・各行は独立してスクロールします
  ・行をタップでパネル全体の表示/非表示を切り替え

デコード動作:
  ・SPK ON 時に自動でデコードを開始します
  ・同じ周波数の局が複数検出された場合は自動的に1行にまとめます
  ・最も強く聞こえている局は自動的に黄色（RX0）に移動します
  ・VPN 経由で接続している場合、音声遅延が 2 秒を超えると
    その間のデコードを自動スキップします（古い音声をデコードしない）


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【アプリの操作説明】
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

■ 起動直後：接続画面（RIG CONNECT）

  ホスト名     Raspberry Pi のホスト名または IP アドレス
               LAN内: raspizero / VPN: 10.0.0.1
  mDNS スイッチ ON にすると「raspizero.local」を自動付与
               ※ VPN 経由の場合は OFF にして IP アドレスで指定
  API Port     8000（変更不要）
  Audio Port   50000（変更不要）
  API Key      設定した場合のみ入力

  「CONNECT」→ 接続成功すると RIG SELECT 画面へ


■ 機種選択画面（RIG SELECT）

  無線機       ハムライブが対応する機種を選択（FT-991A、IC-705 など）
  CAT ポート   Raspberry Pi 上の USB デバイス名（例：ttyUSB0）
  ボーレート   機種に合わせて選択（FT-991A: 38400 など）
  サンプリング 受信音声のサンプリングレート（0=無効、8000/16000/44100 Hz）
  タイムアウト 画面消灯までの時間（0=常時点灯）


■ メイン操作画面（Main Control）

  【周波数の変更】
  方法①  周波数表示をタップ → キーボード入力画面で直接入力（MHz 単位）
  方法②  FREQ ボタンをタップして選択 → UP/DOWN で変更

  【各パラメータの変更】
  STEP  : ボタンタップで選択 → UP/DOWN でステップ幅を変更
  MODE  : ボタンタップで選択 → UP/DOWN でモードを切り替え（USB/LSB/CW 等）
  WIDTH : ボタンタップで選択 → UP/DOWN でフィルタ幅を変更（Hz）
  POW   : ボタンタップで選択 → UP/DOWN で送信パワーを変更（%）
  SQL   : ボタンタップで選択 → UP/DOWN でスケルチを変更

  【SPK ボタン（受信音声 + CW デコード）】
  タップ    : 受信音声の再生を ON/OFF
  長押し    : CW デコード表示パネルの表示/非表示

  【PTT ボタン】
  タップで PTT ON/OFF を切り替えます。

  【APRS ボタン】
  短タップ  : APRS ビーコン送信の ON/OFF
  長押し    : APRS 設定画面へ

  【FT8 タブ】
  メイン画面下のタブから FT8 画面に切り替えます。


■ CW USB ステータスバー（画面下部）

  短タップ:
    M5ATOM 接続済みの場合 → CW 中継の有効/無効を切り替え
    M5ATOM 未接続の場合  → USB デバイスをスキャン

  長押し → CW 設定メニュー（Sidetone / Port / VPN buffer / FM-CW delay）


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【バージョンアップ（v1.50 → v2.00）】
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Raspberry Pi 側の更新が必要です。
  bash ~/create_api.sh
  sudo systemctl restart fastapi fastapi-audio

APK を新しいものにインストールしてください（設定は引き継がれます）。
※ Android 8.0（API 26）未満の端末には v2.00 はインストールできません。


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【トラブルシューティング】
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

接続できない
  → ホスト名を IP アドレスに変えて試す
  → sudo systemctl status fastapi でサービス状態を確認
  → sudo reboot now でラズパイを再起動

音声が出ない（受信）
  → SPK ボタンを ON にする
  → Audio Port が 50000 になっているか確認

CW デコードが始まらない
  → SPK が ON になっているか確認
  → サンプリングレートが 0（無効）になっていないか確認

FT8 がデコードされない
  → ft8wav が Raspberry Pi にインストールされているか確認
     sudo journalctl -u fastapi-audio | grep ft8
  → 受信音声レベルが適切か確認（強すぎるとデコード失敗することがあります）
  → FT8 周波数に合わせて SPK を ON にしてから FT8 画面に切り替える
  ※ FT8 デコードは実験的機能のため、すべての信号がデコードされるとは限りません

VPN 経由でデコードが途切れる
  → 正常な動作です（2秒超の遅延音声を自動スキップしています）

USB CW が認識されない
  → OTG ケーブルを使用しているか確認
  → デバイスの電源が入っているか確認

FM-CW で送信の頭が切れる（VPN 使用時）
  → CW USB バー長押し → FM-CW PTT delay を 150〜300ms に設定

ラズパイのログ確認
  → sudo journalctl -u fastapi -f
  → sudo journalctl -u fastapi-audio -f


2026/5/23
