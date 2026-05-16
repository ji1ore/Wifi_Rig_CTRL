Wifi_RIG_CTRL for Android v1.40
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
・USB CW 中継（M5ATOM Lite を Android に直接 USB 接続してキー信号を中継）
・APRS ビーコン送信（DireWolf 経由、Android GPS 対応）
・複数プロファイル対応（接続先の切り替え）
・API Key 認証対応
・WireGuard VPN による外出先接続対応


【v1.40 の新機能】
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・USB 経由 CW 中継モード（M5ATOM Lite）
  - CW モード    : キー状態を Raspberry Pi の /cw/key に中継
  - 非 CW モード : CW 音声トーンを /radio/audio_tx にストリーミング（FM-CW など）
  - Android サイドトーン再生（低レイテンシ、ON/OFF 設定を記憶）
  - CW VPN バッファ設定（キー信号の遅延補正）
  - FM-CW PTT 遅延設定（VPN 使用時の頭切れ防止）
・IC-705 TX 音声レベル修正（PCM 100% 設定）
・FM-CW と SPK の音声競合修正


【ファイル内容】
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Wifi_RIG_CTRL_v1.40.apk        リリース版 APK（通常インストール用）
Wifi_RIG_CTRL_v1.40_debug.apk  デバッグ版 APK
app/                            Android ソースコード
build.gradle 等                 Gradle ビルドファイル
readme.txt                      本ファイル


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【手順 1】Android アプリのインストール
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. Wifi_RIG_CTRL_v1.40.apk をスマートフォンにコピー
2. Android の設定 →「提供元不明のアプリ」→ インストールを許可するアプリを追加
3. APK をタップしてインストール

必要環境：Android 5.0（API 21）以上


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
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v1.40/RaspberryPiSetup/setup_netwk.sh
  chmod +x setup_netwk.sh && bash setup_netwk.sh

  # ← ここで一度ログアウトして再ログイン
  logout
  ssh pi@raspizero

  # 環境構築 + api.py 生成（Hamlib ビルドで数十分かかります）
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v1.40/RaspberryPiSetup/setup_fastapi_radio.sh
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v1.40/RaspberryPiSetup/create_api.sh
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v1.40/RaspberryPiSetup/set_api_key.sh
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
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v1.40/WireGuard/setup_wireguard.sh
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
【手順 5（任意）】Wifi_Rig_CW との組み合わせ（USB CW 中継）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

M5ATOM Lite を Android に USB 接続し、物理キーから CW 信号を
Raspberry Pi 経由で無線機に送信します。

【必要なもの】
・M5ATOM Lite × 2台（クライアント用 + サーバー用）
・ATOMICプロキット × 2
・OTG 対応 USB ケーブル（クライアント M5ATOM ↔ Android）
・エレキー・縦振れ電鍵等（クライアント M5ATOM の GROVE 端子に接続）
・Raspberry Pi Zero 2W（手順 2 のセットアップ済み）
・無線機のキー端子またはマイク入力への配線
  ※配線は Wifi_Rig_CW/配線/ フォルダの JPG ファイルを参照

【ファームウェア書き込み】
Wifi_Rig_CW/ForM5StackLite/Ver1.40/ のバイナリを M5Burner で書き込む:

  クライアント用（Android に USB 接続する側）
    M5ATOM Lite → Clie_M5AtomLitev1.40.bin

  サーバー用（Raspberry Pi と同じ Wi-Fi に繋ぎ、無線機のキー端子に接続）
    M5ATOM Lite → Serv_M5AtomLitev1.40.bin

【Wi-Fi プロファイル設定】
書き込み後、MicroSD カードに Wifi プロファイルを作成して
M5ATOM の TF-CARD スロットに挿入することで Wi-Fi 設定を読み込みます。
プロファイルの書式はソースコードフォルダ内のサンプルを参照してください。

  クライアント: 自宅 Wi-Fi の SSID/パスワード
  サーバー    : 自宅 Wi-Fi の SSID/パスワード + Raspberry Pi の IP アドレス

【動作モード別の仕組み】
  CW モード:
    キー ON/OFF → Android → Raspberry Pi /cw/key → サーバー M5ATOM →
    無線機のキー端子（フォトカプラ経由で接点出力）

  FM-CW・SSB 等の音声モード:
    キー ON/OFF → Android が CW 音声トーン（700Hz）を生成 →
    Raspberry Pi /radio/audio_tx → 無線機の音声入力（USB オーディオ経由）

【接続手順】
1. サーバー M5ATOM を Raspberry Pi と同じ Wi-Fi に接続して起動
2. クライアント M5ATOM に電鍵を接続
3. OTG ケーブルでクライアント M5ATOM と Android を接続
4. Android に「USB デバイスへのアクセスを許可しますか？」と表示 → OK
5. アプリのメイン画面に「CW relay」または「Audio relay」と表示される


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

  ▼ プロファイル
  右上アイコンから複数の接続先プロファイルを保存・切り替えできます。
  自宅 LAN 用・VPN 用など用途別に作成しておくと便利です。


■ 機種選択画面（RIG SELECT）

  無線機       ハムライブが対応する機種を選択（FT-991A、IC-705 など）
  CAT ポート   Raspberry Pi 上の USB デバイス名（例：ttyUSB0）
  ボーレート   機種に合わせて選択（FT-991A: 38400 など）
  サンプリング 受信音声のサンプリングレート（0=無効、8000/16000/44100 Hz）
  タイムアウト 画面消灯までの時間（0=常時点灯）

  「SET」→ メイン操作画面へ


■ メイン操作画面（Main Control）

  ┌──────────────────────────────────┐
  │  [モデル名]           [TX インジケーター]  │
  │                                          │
  │        14.250.00 MHz                     │  ← 周波数表示（タップで直接入力）
  │                                          │
  │  ██████████████░░░░░░  S-meter           │
  │                                          │
  │  Step  Mode   Wid   Pow   SQL            │  ← 情報行
  │  1kHz  USB    2400  100   0              │
  │                                          │
  │  [FREQ][STEP][MODE][WIDTH][POW][SQL]     │  ← 操作ボタン
  │                  [UP]                    │
  │                  [DOWN]                  │
  │  [PTT]  [SPK]  [APRS]  [BACK]           │
  │                                          │
  │  ─── 音量スライダー ───                  │
  │                                          │
  │  USB: Not connected       [ポート名]     │  ← CW USB ステータスバー
  └──────────────────────────────────┘

  【周波数の変更】
  方法①  周波数表示をタップ → キーボード入力画面で直接入力（MHz 単位）
  方法②  FREQ ボタンをタップして選択（ハイライト点灯）→ UP/DOWN で変更

  【各パラメータの変更】
  STEP  : ボタンタップで選択 → UP/DOWN でステップ幅を変更
          100Hz / 500Hz / 1kHz / 5kHz / 10kHz / 20kHz など
          ※ モード別に記憶されます（CW: 100Hz、SSB: 1kHz 等がデフォルト）
  MODE  : ボタンタップで選択 → UP/DOWN でモードを切り替え（USB/LSB/CW 等）
  WIDTH : ボタンタップで選択 → UP/DOWN でフィルタ幅を変更（Hz）
  POW   : ボタンタップで選択 → UP/DOWN で送信パワーを変更（%）
  SQL   : ボタンタップで選択 → UP/DOWN でスケルチを変更

  ※ 選択中のボタンは水色にハイライトされます
  ※ TX 中はパラメータ変更できません

  【音量スライダー】
  受信音声のボリュームをスライドで調整します（Raspberry Pi 側の出力レベル）

  【PTT ボタン】
  タップで PTT ON/OFF を切り替えます。
  ON 中は赤/TX インジケーターが赤く点灯します。
  初回タップ時にマイクのアクセス許可ダイアログが表示されます。

  【SPK ボタン（受信音声）】
  タップで受信音声の再生を ON/OFF します。
  ON 中は黄緑色に点灯します。
  ※ TX 中は操作できません
  ※ サンプリングレートが 0（無効）の場合は動作しません

  【APRS ボタン】
  短タップ  : APRS ビーコン送信の ON/OFF
             設定済みの場合: 送信中は黄緑色に点灯
             未設定の場合: 「長押しで設定」と表示
  長押し    : APRS 設定画面へ（コールサイン・周波数・GPS・送信間隔等）
  ※ APRS 送信中は受信音声（SPK）は停止します

  【BACK ボタン】
  メイン画面から RIG SELECT 画面に戻ります（無線機から切断）


■ CW USB ステータスバー（画面下部）

  表示色と意味:
    灰色  USB: Not connected  M5ATOM が未接続
    黄色  Connected (idle)    接続済み・CW 中継は無効
    緑色  CW relay            CW モード中継中（キー ON/OFF を Pi に送信）
    青色  Audio relay         音声トーン中継中（FM-CW 等）
    [Muted] 表示             サイドトーンが OFF の状態

  短タップ:
    M5ATOM 接続済みの場合 → CW 中継の有効/無効を切り替え
    M5ATOM 未接続の場合  → USB デバイスをスキャン

  長押し → CW 設定メニュー:

    ① Sidetone ON/OFF
       キーイング中に Android から 700Hz のサイドトーンを再生します。
       ヘッドホン接続時に使用してください（スピーカーでは回り込みに注意）。
       設定は次回起動後も記憶されます。

    ② Select Pi CW Port
       Raspberry Pi 側で M5ATOM サーバーが接続されているシリアルポートを選択します。
       （例: ttyUSB0、ttyACM0）
       Raspberry Pi に SSH ログインして ls /dev/tty* で確認できます。

    ③ CW VPN buffer（CW モード用）
       VPN 経由でキー信号を中継する場合の遅延補正です。
       サイドトーンは常に即時再生されます。RF 信号の送信タイミングのみ遅延します。
         LAN 内 : 50〜100ms
         VPN    : 測定値 + 200ms を目安に設定
       ダイアログにはキー操作中に計測した推奨値も表示されます。

    ④ FM-CW PTT delay（非 CW モード用）
       音声トーン送信時、PTT ON から送信開始までの遅延です。
       VPN 使用時に音声が Pi に届く前に PTT が入ると送信の頭が切れるため設定します。
         LAN 内 : 0ms
         VPN    : 150〜300ms を目安に設定


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【バージョンアップ（v1.31 → v1.40）】
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Raspberry Pi 側の api.py を更新してください（IC-705 音声修正を含む）:
  bash ~/create_api.sh

APK は新しいものをインストールするだけです（設定は引き継がれます）。


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
  → sudo systemctl status fastapi-audio で確認

IC-705 で TX 音声が小さい / 出ない
  → create_api.sh を再実行（PCM 100% 設定を適用）:  bash ~/create_api.sh

USB CW が認識されない
  → OTG ケーブルを使用しているか確認
  → M5ATOM の電源が入っているか確認
  → アプリのパーミッション設定で USB アクセスを許可

FM-CW で送信の頭が切れる（VPN 使用時）
  → CW USB バー長押し → FM-CW PTT delay を 150〜300ms に設定

CW で RF の頭が切れる（VPN 使用時）
  → CW USB バー長押し → CW VPN buffer を増やす

Svr:Offline と表示される
  → サーバー M5ATOM の Wi-Fi 接続を確認
  → CW USB バー長押し → Select Pi CW Port でポートを確認

ラズパイのログ確認
  → sudo journalctl -u fastapi -f
  → sudo journalctl -u fastapi-audio -f


2026/5/17
