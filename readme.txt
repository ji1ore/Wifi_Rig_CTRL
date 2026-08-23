①はじめに
M5CoreHamCATは、Raspberry Pi Zero2WとM5CoreS3SEを用い、無線機に接続したRaspberry Pi Zero2Wを使って
無線機をCAT操作し、その情報の取得、及び操作をM5CoreS3SE上で行うシステムです。
技術的には、Raspberry Pi Zero2W上のHamlibをFastAPIでWrapし、M5CoreS3SEからFastAPIを叩いて無線機の操作や
無線機の情報を取得する、ということを行っています。
できることは、無線機の情報の表示、、音声の受信操作です。
Ver1.10でPTT信号の送付に対応しました。
ラジオマイク等でリグへ音声信号を送付したうえで、当機械よりPTTのON/OFFを発することができます。
Ver1.20にてAPRSの送信に対応しました(動作確認、IC-705/ボーレート1200bpsのみ)。
音声の受信とは排他の関係になりますが、APRS機能のついていない機種からラズパイ上のDireWolfを使い、APRS信号を
送付することができます。APRSモードでは、現在周波数から別に設定したAPRS周波数に遷移して(144.66など)APRSの発信を
行います。
なお、ボーレートについては送信機種に依存するようで、IC-705(USB接続)からでは送信することができませんでした。
--
2026/3/1
M5CoreHamCAT_SpeakerはModule Audio経由で音を出すことができるようになったので、廃止します。
--
2026/7/22
Ver2.20にて、M5Core2 / M5Core2 Tough / M5CoreS3SEの3機種それぞれのソース・ファームウェアを公開しました
（機種ごとに別フォルダ：M5CoreHamCAT_Core2 / M5CoreHamCAT_Core2Tough / M5CoreHamCAT_CoreS3SE）。
--
2026/7/25
readmeを最新化し、Ver2.20対応の全デバイス（M5Core2 / M5Core2 Tough / M5CoreS3SE / Android / iOS）を
網羅する内容に更新しました。M5の各機種紹介文（M5Burner掲載用）、およびiOS版（WifiRigCTRL for iOS）の
案内（⑦）を追加しています。
--
2026/8/12
Ver2.34にて、Android版・iOS版を更新しました。主な変更点は⑥⑦を参照ください。
--
2026/8/10
Ver2.33にて、Android版・iOS版を更新しました。主な変更点は⑥⑦を参照ください。
--
2026/7/31
Ver2.32にて、Android版・iOS版を更新しました。主な変更点は⑥⑦を参照ください。
--
2026/7/27
Ver2.31にて、Android版・iOS版を更新しました。主な変更点は⑥⑦を参照ください。
--
2026/7/26
Ver2.30にて、Android版・iOS版・Pi側APIを更新しました。主な変更点は⑥⑦を参照ください。
--

現在のところ、Yaesu FT-991A のみで動作確認を行っており、他の無線機での動作は未検証です。
また、 M5CoreS3やM5CoreS3Lite、他のM5Coreシリーズで動作するかは未検証です。

②必要なもの
当システムを動作させるために必要なものは以下のとおりです。
・M5CoreS3SE/M5Core2 ver1.1 (M5CoreS3SEのほうが快適に動作します)
・Module Audio(M5純正 SKU:M144) PortAに刺します。
・Raspberry Pi Zero2W
・Wifiルータ(上記２つの端末が同一Wifiネットワーク上に存在することを前提とします。)
・Unit Encoder(M5純正 SKU:U135)
 なくても動作しますが、操作性が向上します。
 ※M5Core2 Toughのみ、Unit Encoderの代わりに機械式2相ロータリーエンコーダをPort Aに直結する方式です（Unit Encoder不要）。
・M5Stack CoreS3用バッテリーボトム
 なくても動作しますが、利便性があがりますので。
・MicroSDカード(16G以上、信頼性の高いもの)
・その他無線機やM5CoreS3SE、Raspberry Pi Zero2Wへの電源取得やCATデータ取得に用いるUSBケーブル類
APRS動作のために
・スマートフォン(アプリ：Taskerが動作するもの)
・スマートフォンアプリ、Tasker(有償)
--
音声信号の送信に必要なもの
・メカニカルキー(M5純正 SK6812) PortC(M5Core2) PortB(M5CoreS3SE)に刺します。
・無線機に音声を飛ばせるマイク(ラジオマイク等)

③セットアップ手順(Raspberry Pi Zero2W)
https://github.com/ji1ore/M5CoreHamCAT/blob/main/v2.51/RaspberryPiSetup/readme.txt
を参照してセットアップを行ってください(verに応じたフォルダを参照ください)。
主な手順は以下の通りです。
・Raspberry Pi Imagerのインストール
・Raspberry Pi Imagerの作成(ここでWifi SSIDの指定やユーザパスワードを指定します)
・SSHログイン
・必要コマンドの実施(シェルファイルを用意してありますので簡単ですが時間がかかります)

④セットアップ手順(M5CoreS3SE/M5Core2/M5Core2 Tough/M5 Stopwatch)
M5CoreS3SE/M5Core2/M5Core2 Tough/M5Stopwatchでは、M5Burnerを用いてファームウェアの読み込みを行ってください。
Git上の以下フォルダにソースは機種別に公開します。
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.51/M5CoreHamCAT_Core2Tough
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.51/M5CoreHamCAT_Core2
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.51/M5CoreHamCAT_CoreS3SE
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.51/M5CoreHamCAT_M5StopWatch
ソースはVisual Studio Code上のPlatformI/O上でのコンパイルを前提にしています。

●各機種の紹介文（M5Burner掲載用）
M5Burner上でM5CoreHamCATを検索すると、機種ごとに以下のような紹介文で表示されます。

[M5CoreHamCAT_Core2]
M5Stack Core2 を無線機（リグ）のリモートコントローラーにするファームウェアです。Raspberry Pi
（Wifi_Rig_CTRL FastAPIバックエンド）または ICOM WLAN Remote（CI-V over WiFi）経由でリグを操作し、
周波数・モード・Sメーター表示、受信音声再生、PTT送信、APRSビーコン送受信に対応します。
Port AにM5純正Unit Encoder（I2C）、Port CにPTTスイッチとステータスLEDを接続します。内蔵マイク/
スピーカーに加え、外部Module Audioへの切替も可能です。

[M5CoreHamCAT_Core2Tough]
M5Stack Core2 Tough を無線機（リグ）のリモートコントローラーにするファームウェアです。機能はCore2版
と共通（Raspberry Pi/CI-V経由のリグ操作、受信音声、PTT送信、APRS送受信）ですが、耐衝撃ボディに合わせ
てPort Aには機械式2相ロータリーエンコーダを直結（Unit Encoder不要）、起動時に画面を180度回転して表示
するなど、Core2 Tough専用の調整を行っています。Port CはPTTスイッチとステータスLED用です。

[M5CoreHamCAT_CoreS3SE]
M5Stack CoreS3 SE を無線機（リグ）のリモートコントローラーにするファームウェアです。Raspberry Pi
または CI-V直結でリグを操作し、周波数・モード・Sメーター表示、受信音声、PTT送信、APRSビーコン送受信
に対応します。3機種中もっとも快適に動作します。Port AにUnit Encoder（I2C）、Port BにPTTスイッチと
ステータスLEDを接続します。内蔵マイク/スピーカーと外部Module Audioを個別に切替可能です。

[M5CoreHamCAT_M5StopWatch]
M5Stack Stopwatch開発キット（466×466 円形AMOLED）を無線機（リグ）のリモートコントローラーにする
ファームウェアです。Raspberry Pi（Wifi_Rig_CTRL FastAPIバックエンド）または ICOM WLAN Remote
（CI-V over WiFi）経由でリグを操作し、周波数・モード・Sメーター表示、受信音声再生、PTT送信、
APRSビーコン送受信に対応します。外部ロータリーエンコーダー・Module Audioポートは非搭載のため、
内蔵マイク/スピーカーのみを使用します。円形ディスプレイに最適化したUIを採用しています。

ファームウェアの読み込み手順は以下のとおりです。
・M5Burnerをダウンロードし、インストールします。
・M5Burnerを起動します。ユーザー登録を行います。
・M5CoreHamCATをDownloadします。
・コンピュータにM5CoreS3SE/M5Core2をUSBケーブルで接続し、Burnします。
MBurner上では、M5CoreHamCATで検索できます。
APRSについては、メイン画面のグレーアウトしたAPRSボタンを長押しすることにより、設定を修正できます。
また、スマートフォンのGPSデータを、Taskerを使ってラズパイ上のFastAPIに送付することができます。
設定手順は「RaspberryPiSetup」フォルダ内のReadmeを参照願います。

⑤注意点
まだ結構不安定です。上手く動かないこともあると思いますので、何度か再起動するなど試してみてください。
無線機の情報が取れなくなったら、Raspberry Pi Zero2WにSSH接続をしてリブートを行ってください。
リブートのコマンドは以下になります。

sudo reboot now 

CATデバイスの選択を間違えると接続できませんのでご注意ください。
連打をすると再起動がかかることがあります。
音声は遅延防止のために10分ごとに再接続しています。そのタイミングで数秒聞こえなくなりますのでご了承ください。
M5Core2の場合、メイン画面上の操作を長押し気味にする必要があります。

⑥ Android版について（Wifi_RIG_CTRL_ForAndroid v2.51）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
M5CoreS3SE の代わりに Android スマートフォンでリグをリモート制御できる
アプリを v1.30 より公開しています（最新版：v2.51）。
M5Core/Module Audio/Unit Encoder 等のハードウェアは不要です。
Raspberry Pi のセットアップは M5Core 版と共通です。

●v2.51 の変更点（v2.50 との比較）
【SP2ALART 連携強化（Android）】
・POTA/SOTA 通知アプリ「SP2ALART」のスポットバナーをタップしてWifi_RIG_CTRLを開くと、
  そのスポットの周波数とモードが自動設定される（Sp2alertReceiver によるブロードキャスト受信）

【Pi側スクリプト更新（create_api.sh）】
・DireWolf ADEVICE を plughw:0,0 固定から .env の ALSA_CAPTURE 設定値に変更（不具合修正）
  USBオーディオデバイスが複数ある環境や CARD=CODEC 以外のデバイス名でも正しく動作
・mawk 互換性修正: awk の代わりに mawk を使う環境でも Pi セットアップが正常に完了
・API_VERSION を "2.51" に更新（Pi 側更新には「Update Pi」が必要）

●v2.50 の変更点（v2.34 との比較）
【Pi側スクリプト更新（api.py / create_api.sh）】
・PTT OFF ウォッチドッグ追加（FTX-1F 等、PTT 固着対策）
  - PTT OFF後、バックグラウンドで実際にOFFを確認できるまで最大20秒間 T 0 を送り続ける
  - rigctld 再起動を挟んだ場合は "t" 応答を2回連続で確認してから確定
  - CW/APRS/FT8 送信に追い越された場合は即座に打ち切り
・rigctld 再起動の排他制御追加（PTT 連打時の多重起動防止）
・WebFT8 バージョンを /radio/status に追加
・webft8 サーバー再起動をsystemd + 直接起動フォールバック方式に改善
・Hamlib モデル別ストップビット自動設定（FTX-1 などデフォルト設定のまま CAT PTT が動作）
・USB オーディオデバイス名の自動検出対応（ALSA_CAPTURE / ALSA_PLAYBACK 自動設定）
  - 初回セットアップ: 複数デバイス時は対話選択、1台のみの場合は自動決定
  - Update Pi: 非対話で自動検出（ヘッドレス環境対応）
・API_VERSION を "2.50" に更新

【アプリ側】
・Pi API バージョン照合を "2.50" に更新（旧 Pi は「Update Pi」で更新してください）

●v2.34 の変更点（v2.33 との比較）
【バグ修正】
・About 画面での WebFT8 バージョン取得を修正
  - iOS 側で「Update Pi」を実行後、Android/iOS ともに WebFT8 バージョンが
    取得できなくなる問題を修正
  - Android: HTTPS 接続で証明書ピンニングを削除、キャッシュ済み IP を直接使用
  - create_api.sh の embedded server.py に /server_version エンドポイントを追加
    （Update Pi 直後から即座にバージョン取得可能に）

【Pi側スクリプト更新】
・create_api.sh: embedded server.py に /server_version エンドポイントを追加
  （_VERSION = "2.34"）
・create_api.sh: embedded api.py の API_VERSION を "2.34" に更新

●v2.33 の変更点（v2.32 との比較）
【バグ修正】
・WebFT8「Loading WASM で止まる」問題を修正
  - jl1nie/webft8 のアップデート後に不足していた wav-save.js を create_api.sh のダウンロードリストに追加
  - server_webft8.py（Pi上のwebft8サーバー）が起動するたびに GitHub から最新 JS ファイルを自動取得するよう変更

・USB CW 電鍵使用時のサイドトーン遅延を修正（Android）
  - USB 読み取りスレッドが Pi との UDP SYNC 往復応答（最大 300ms ブロック）によって
    キー状態処理が遅延し、サイドトーン開始が打鍵より遅れる問題を修正
  - 専用の SYNC フォワーダースレッド（CwUsb-SyncFwd）を追加し、
    USB 読み取りスレッドと Pi 通信処理を分離（CwUsbService.kt）

【新機能】
・Update 画面に「Update WebFT8」ボタン追加（オレンジ色）
  - server_webft8.py のみを素早くデプロイ（Pi 再起動不要）
・「Update Pi」完了後に「Update WebFT8」を自動実行するよう変更

【Pi側スクリプト更新】
・create_api.sh: wav-save.js をダウンロードリストに追加
・server_webft8.py: 起動時に GitHub から最新 JS ファイルを自動更新

●v2.32 の変更点（v2.31 との比較）
【新機能（CI-V 直接接続）】
・レピータ設定機能追加
  - CTCSS トーンモード（None / Tone / TSQL）とトーン周波数を設定可能
  - オフセット方向（+/-）とオフセット周波数（プリセット or カスタム）を設定可能
    プリセット: 100kHz / 600kHz / 1MHz / 1.6MHz / 5MHz / 7.6MHz
  - 送信中（PTT ON）はオフセット適用後の実際の TX 周波数を周波数ディスプレイに表示
  - 設定はアプリ終了後も保持

【注記】
・Pi 側スクリプト変更なし（v2.30 と同一）

●v2.31 の変更点（v2.30 との比較）
【新機能】
・P/W/S ボタン追加（POW・WIDTH・SQL を1ボタンに統合）
  - タップするたびに Power → Width → SQL → 選択解除 とサイクル
  - 選択中の項目名をボタン下部に表示
・MEM バンドメモリー機能追加
  - プリセットメモリー（160m〜70cm の代表周波数、読み取り専用）
    ・70cm バンドに CW（430.050 MHz）・SSB（430.100 MHz）を追加
    ・プリセットをバンド別セクション（160m / 80m / … / 70cm）で表示
  - ユーザーメモリー（周波数・モード・ステップを自由に登録・編集・削除）
    ・ユーザーメモリーをリスト先頭に配置
    ・モード選択をドロップダウン方式に変更（LSB / USB / CW / CWR / AM / FM / C4FM / DV / RTTY / PSK）
    ・メモリ名を必須入力に変更（空欄では保存不可）
  - 全プロファイル共通で利用可能
  - MEM ボタン短押し → 呼び出し、長押し → 管理（追加・編集・削除）

【変更】
・PTT タイプのデフォルトを CAT（RIG）に変更

【注記】
・Pi 側スクリプト変更なし（v2.30 と同一）

●v2.30 の変更点（v2.20 との比較）
【Pi側API更新】
・モード一覧を機種ごとの動的検出に変更
  - 従来は固定リスト（LSB/USB/CW 等）のみ返していたため、FT-991 の C4FM や
    IC-705 の D-STAR など機種固有のデジタルモードを選択できなかった
  - v2.30 では接続時に dump_caps でサポートモードを自動検出し、Mode 選択に反映
  - 反映には Pi 側の更新が必要（アプリの「Update」→「Update Pi」ボタンで自動更新）

【新機能】
・カラーテーマセレクター追加
  - メインコントロール画面の TX インジケーター横に OCEAN/AMBER/MONO/AQUA の
    4テーマを切替えるボタンを追加（設定は再起動後も保持）
・FM ボタンのデジタルモード動的追加
  - dump_caps で検出した機種固有モードに C4FM・FMN・FM-D・D-STAR が含まれる
    場合、FM ボタンタップでそれらのデジタルモードも循環

【改善】
・プロファイル自動保存
  - 接続ボタン押下時・リグを開く時に、アクティブなプロファイルへ現在の設定を
    自動保存するよう変更（接続後に手動でプロファイル保存する手順が不要に）
・APRS 設定の安定化（リグモデム AP96/AP12 の信頼性向上）
  - サーバーとの設定不整合を防ぐため、30秒ごとに APRS 設定を自動再送するよう変更
  - AP96/AP12 稼働中のハートビート送信を追加し、Pi 側ウォッチドッグによる
    ビーコン途絶を防止（v2.20 では 15 秒後にビーコンが自動停止する問題があった）
  - APRS 設定画面の OK ボタンで「APRS Enabled OFF」または「TX Method 変更」時に
    稼働中のビーコンを自動停止するよう改善

【修正】
・D-STAR 選択時にリグへ USB が誤って送信されていた不具合を修正（CI-V 直結モード）
  - CivTcpService.kt の setMode() に D-STAR→0x17 の対応が抜けていた

●v2.20 の変更点（v2.18 との比較）
【新機能】
・APRS リグ内蔵モデムモードを追加（FTX-1 等の内蔵 APRS モデムを CAT 経由で制御）
  - APRS 設定画面に「TX Method」切替を追加（DireWolf / Rig Modem）
  - Rig Modem 選択時: APRSボタンを押すたびに OFF → AP96（9600baud）→ AP12（1200baud）→ OFF とサイクル
  - モデム選択（AUTO / MAIN / SUB）・AP96/AP12 それぞれの周波数・ボーレート設定可能

【修正】
・APRS 受信: Mic-E パケットの東経が西経として表示される問題を修正
  - FTX-1 ファームウェアが Mic-E D6 に P-Y でなく通常数字を使うバグへの対処
・APRS 受信: シンボルが文字化けする問題を修正（バイトオフセット修正）

●v2.18 の変更点（v2.17 との比較）
・versionCode 更新（Google Play 公開バージョン整合）
  - Pi 側スクリプト変更なし（v2.17 と同一）

●v2.17 の変更点（v2.16 との比較）
【修正・改善】
・IC-705 Wifi CI-V 接続の信頼性向上
  - エフェメラルポート（0）を使用して接続のたびに異なる ctrlMyId を生成
  - IC-705 が古いセッションを再利用してしまう問題を解消（iOS 動作に合わせて修正）
  - IAH 未受信時でも ping からの civRemoteId 学習済みなら接続を継続

・PiP（縮小）モードから復帰した際にパネルボタンが上段 4 つしか表示されない問題を修正
  - onPictureInPictureModeChanged の requestLayout() を post{} 内に移動し、
    ウィンドウが完全に復元されてから GridLayout を再計算するよう変更
  - Pi 側スクリプト変更なし（v2.16 と同一）

●v2.16 の変更点（v2.15 との比較）
【修正】
・RIG CONNECT 画面の「USE CI-V」ラベルから [TEST] 表記を削除
  - CI-V 機能を正式機能として扱い
  - Pi 側スクリプト変更なし（v2.15 と同一）

●v2.15 の変更点（v2.14 との比較）
【修正】
・CI-V 接続時のコールサイン欄から「FT8」表記を削除
  - CI-V モードでは FT8 は使用不可のため「My Callsign (FT8)」→「My Callsign」に変更
  - Pi 側スクリプト変更なし（v2.14 と同一）

●v2.14 の変更点（v2.13 との比較）
【新機能】
・IC-705 / IC-9700 等への直接 Wifi CI-V 接続対応（Raspberry Pi 不要）
  - RIG CONNECT 画面の「USE CI-V」スイッチで Pi モード / CI-V 直接接続を切替
  - CI-V ポート（デフォルト 50001）・CI-V アドレス（IC-705: 0xA4）を設定可能
  - 対応機能: 周波数・モード・Sメーター・PTT・RF power・SQL・BK-IN
  - 非対応機能（Pi モードのみ）: 音声ストリーミング・CW テキスト送信・FT8・APRS
  - Pi 側スクリプト変更なし（v2.13 と同一）

●v2.13 の変更点（v2.12 との比較）
【改善】
・CW 打鍵時の SPK 音切れタイミング改善（iOS 版と同等のレスポンス）
  - AudioTrack バッファを ~2 秒 → ~200ms に削減

【新機能】
・ピクチャー・イン・ピクチャー（PiP）対応
  - TX 中・CW 打鍵中にホームボタンで自動的に小画面に移行
  - Android 12+ では setAutoEnterEnabled で自動移行

【修正・改善】
・エッジ・ツー・エッジ表示に正式対応（Google Play ポリシー準拠）
  - enableEdgeToEdge() + WindowInsetsCompat でシステムバー領域を自動回避
  - 非推奨 API setStatusBarColor / setNavigationBarColor を解消
・USB CW 接続時のレイアウト崩れを修正
  - パーミッションダイアログ中の誤 PiP 移行を防止
・スプラッシュ画面にアプリアイコンを追加
・「+ New」ボタンの見切れを修正
・Pi 側スクリプト変更なし（v2.12 と同一）

●v2.12 の変更点（v2.11 との比較）
【新機能】
・Hamlib 4.7.2 サポート追加
  - apt では Hamlib 4.7.x を取得できないため、ソースビルドに対応
  - インストール先: ~/.local/bin/rigctld（sudo 不要・RPATH 付きビルド）
  - アプリの「Update」→「Update Hamlib」ボタンから実行（Pi Zero で 30〜60 分）
  - rigctld は ~/.local/bin/rigctld（4.7.2）を優先使用し、なければシステム版にフォールバック

・Update 画面を新設（UI 整理）
  - Update Pi・Update Hamlib・Pi Log・Hamlib Log を 1 画面に統合
  - ログエリア（緑モノスペース）でビルド進捗をリアルタイム確認
  - Reload ボタンで手動更新
  - RIG CONNECT 画面のボタンを 6 個（2 行×3 列）に整理

・About 画面に Pi API バージョン・Hamlib バージョンを表示
  - 接続中の Pi の FastAPI バージョンと rigctld バージョンを確認可能

【修正】
・webFT8 画面で Rig→TX / RX フィールドを変更すると無線機の周波数が変わる問題を修正
  - 横向き（ランドスケープ）時のみ発生
  - 原因: DOM change イベントリスナーがオーディオオフセット等の値を周波数と誤検知していた
  - localStorage.setItem インターセプターによる周波数同期は引き続き正常動作

・WID（フィルター幅）・POW（送信出力）・SQL（スケルチ）を ◀▶ ボタンで増減可能に
  - WID ±100 Hz、POW ±1%、SQL ±1% の増減
  - WID / POW / SQL ボタンを押して選択状態にしてから ◀▶ で操作

・Update Pi 後の「Pi API バージョン不一致」表示を修正
  - アプリ側の期待バージョン定数が v2.11 のままだったため、Update 後も不一致が出ていた問題を修正

●v2.11 の変更点（v2.10 との比較）
【新機能】
・BLE CW 電鍵サポートを追加（DualKey-BLE / RemoteKeyer-BLE）
  - DualKey-BLE（M5AtomS3）または RemoteKeyer-BLE を BLE（Bluetooth LE）で Android にワイヤレス接続可能
  - Nordic UART Service（NUS）プロトコルを使用
  - Android の Bluetooth 設定でペアリング後、BT ボタンをタップするだけで自動検出・接続
  - DualKey-BLE の USB CDC / BLE 自動切替機能
    ・電源投入後10秒間: 左パドル（DAH）→ USB CDC モード、
                         右パドル（DIT）→ BLE モード（デフォルト）
    ・BLE モード中: アプリ（Android）からの USB データ受信で
      自動的に USB CDC モードへ再起動
    ・USB CDC モード中: USB 切断で自動的に BLE モードへ再起動

【改善・修正】
・CW 接続状態表示を改善
  - BLE 接続時: 「BLE」緑表示
  - 未接続時: 「BLE」グレー表示

●v2.10 の変更点（v2.09 との比較）
【改善・修正】
・ノイズリダクション強化（afftdn ベースに統一）
  - NR レベルを 3 段階から 5 段階に拡張
    Level 1 (Light)   : afftdn=nf=-30:nr=15
    Level 2 (Medium)  : afftdn=nf=-25:nr=20
    Level 3 (Strong)  : afftdn=nf=-20:nr=25:tn=1
    Level 4 (Stronger): afftdn=nf=-20:nr=33:tn=1
    Level 5 (Max)     : afftdn=nf=-20:nr=40:tn=1
  - SQL ボタン長押しで 0→1→2→3→4→5→0 循環
  - NR をデュアルサーバー構成に対応（apiPort / audioPort 両方に同期送信）

・Update Pi の信頼性向上
  - sudoers 設定を実行ユーザー名で正しく生成（sudo 時の whoami バグを修正）
  - fallback 再起動の待機時間を 3s → 15s に延長（Pi Zero 対応）
  - fallback 時に fastapi-audio（port 50000）も確実に再起動

●v2.09 の変更点（v2.08 との比較）
【改善・修正】
・CW デコード精度向上（RX メイン画面・TX CW パネル）
  - dit/dah 境界判定を改善（×2 → ×1.8）: dah 誤判定削減
  - 字間ギャップ判定を緩和（×2 → ×2.5）: 字間誤切り込み削減
  - ditWins 収束を遅くして急激なスピード変動耐性向上
  - エネルギー計算を 3bin → 5bin 合計に拡大（弱信号 SNR 向上）
  - ノイズフロア推定を 30 → 20 パーセンタイルに変更（混信耐性向上）
  - TX 側: ditMs 下限を 15ms → 20ms に変更（誤カウント防止）

●v2.08 の変更点（v2.07 との比較）
【改善・修正】
・CW TX 開始ラグを修正（USB シリアル接続時）
  - open_radio で current_ptt_type に実効 PTT タイプ（RIG）を保存するよう修正
  - ttyACM/ttyUSB 使用時に CW TX 毎に rigctld 再起動が発生していた問題を解消
    （タイミングにより 0〜7 秒のラグが発生していた）

・CW TX パネルの UI 改善
  - ボタンを大型化し、1画面に収まるレイアウトに変更
  - 横向き（ランドスケープ）時は 2 カラムレイアウトで表示

●v2.07 の変更点（v2.06 との比較）
【改善・修正】
・USB シリアル接続時の PTT 自動最適化（IC-705 USB 対応強化）
  - ttyACM / ttyUSB デバイス使用時は PTT を自動的に RTS → CAT（CI-V）に切替
  - IC-705 USB オーディオリセット問題を回避

・rigctld 起動後の PTT 強制解除
  - rigctld 再起動時に誤って TX になることを防止

・TX 中の rigctld 再起動を禁止
  - CW/音声送信中に rigctld が再起動して DTR が切れる問題を修正

・PTT タイプ表示を「RIG」→「CAT」に変更（UI 表記統一）

・CW CQ リピート UI 改善

●v2.06 の変更点（v2.05 との比較）
【改善・修正】
・CW USB（DualKey）の Pi 未接続時の同期動作を修正
  - Pi 不在でもキーイングタイミングが正しく計算されるよう改善

●v2.05 の変更点（v2.04 との比較）
【修正・改善】
・CW 送信が途中で切れる問題を修正
  - set_morse_code_speed（K コマンド）を削除
    rigctld を 2 秒以上ブロックしていた原因を除去
  - IC-7300 / IC-705 内部キーヤーは PTT を自動管理するため CAT PTT 制御は不要と判明

・CW TX 終了方式の選択機能を追加
  - 時間予測モード（デフォルト）: IC-7300 / IC-705 内部キーヤー向け
  - PTT ポーリングモード: FT-991 等 CAT PTT 対応リグ向け
  - CW TX パネルの「TX end: PTT poll」スイッチで切替

・CW TX 開始遅延を短縮（600ms → 100ms）

・S メーター表示修正
  - IC-705 接続時に常時 S9 になっていた問題を修正

・Update Pi ボタン強化
  - v2.03 以降が動作中の Pi であればボタン一発で更新完了
  - Pi のユーザー名（pi / pizero 等）に依存しない実装に変更

【FastAPI 更新（Update Pi ボタンで更新）】
・CW TX 終了方式の選択をサポート（ptt_poll パラメータ）
・BK-IN 状態を 15 秒ごとに自動ポーリング
・パス設定の汎用化（ユーザー名不問）

●v2.04 の変更点（v2.03 との比較）
【修正・改善】
・CW モード時の BK-IN 表示が常に OFF になっていた問題を修正
  - FastAPI の poll_signal() に SBKIN / FBKIN ポーリングを追加（15 秒間隔）
  - TX 中はポーリングをスキップ（IC-7300 リレー音対策）
  - セミブレークイン（SBKIN）→ フルブレークイン（FBKIN）の順で自動判別

・Update Pi ボタンの信頼性を向上
  - create_api.sh 完了後の api.py 再送信にリトライを追加（最大 5 回 × 5 秒）
  - 再送信失敗時にエラーを表示（従来は無視されていた）
  - 成功時のステータスメッセージを修正

【FastAPI 更新（Update Pi ボタンまたは create_api.sh 再実行）】
・poll_signal() に CW モード BK-IN 自動ポーリング追加

●v2.03 の変更点（v2.02 との比較）
【新機能】
・FT8/FT4 デコード機能（webft8 ベース）
・マルチプロファイル対応

【改善・修正】
・Sync Time ボタン追加・SSL 証明書ピンニング・ALSA デバイス分離

【FastAPI 更新（要 create_api.sh 再実行）】
・webft8 HTTPS サーバー・時刻同期 API 改善・ホームディレクトリ汎用化
・API バージョン 2.03

●v2.02 の変更点（v2.01 との比較）
【新機能】
・CW TX パネルを追加
  - CQ / CALL K / AGN / UR 5NN BK の定型文ボタン
  - WPM スライダー（5〜60 WPM、SeekBar）
  - フリーテキスト入力（英語キーボード自動表示）
  - CW モード: Hamlib キーヤー経由でモールス送信
  - FM-CW モード: PCM トーンをラズパイへストリーミング送信
    ※ FM-CW は約 0.5〜1 秒の遅延があります

【改善・修正】
・CW/CWR モード選択時にフィルター幅を自動で 500Hz に設定
・POW UP/DOWN を 5% 刻みから 1% 刻みに変更
・PWR / SQL ダイアログのステップ表示から「%」を削除
・メッセージをすべて英語に統一

【FastAPI 更新（要 create_api.sh 再実行）】
・CW モールス送信 API 追加（/cw/send_morse / /cw/stop_morse / /cw/morse_status）
・Break-in API 追加（/radio/setbkin / /radio/getbkin）
・時刻同期 API 追加（/time）
・FT8 専用音声デバイス設定 API 追加（/radio/audio_device_ft8）
・cw_bridge.py リモートアップデート機能追加（/admin/update_cw_bridge）
・APRS ビーコンのシンボル・コメント・宛先に設定値を反映
・APRS PTT 制御を rigctld 経由（PTT RIG 2）に修正（送信できない問題を解消）
・APRS KISS ポート待機タイムアウト延長（Pi Zero の起動時間に対応）
・APRS TX 中の PTT ウォッチドッグ抑制（送信が途中で切れる問題を解消）
・/aprs_config・/aprs_start を非ブロッキング化（Android の HTTP タイムアウト対策）

●v2.01 の変更点（参考）
・FT8/FT4 機能を WebView ベースに刷新（Pi 側ポート 8443 が必要）
・Raspberry Pi 側の create_api.sh を再実行してください

●v2.00 の新機能（v1.50 からの追加）
・FT8/FT4 受信デコード・送信（実験的機能）
・音声ストリームのサンプリングレート選択（8k〜48kHz）
・フィルター幅の操作に対応

●v1.50 の新機能（変更なし）
・多チャンネル CW デコーダー（SPK ボタン長押しでパネル表示）
  - 最大 5 局を同時受信・デコード（TX 行 + RX 行 ×5）
  - 最も強く聞こえている局を常に RX0（黄色）に自動昇格
  - ±125 Hz 以内の周波数ドリフトに自動追従（チャンネル重複を防止）
  - 同一周波数のチャンネルを自動統合（重複表示なし）
  - 20 WPM 超でも正確にデコード
  - VPN 遅延対応：2 秒超の音声バーストをデコードをスキップ

●v1.40 の機能（変更なし）
・USB経由CW中継モード（M5ATOM Lite / M5ATOM S3 Lite をAndroidに直接USB接続）
  - CWモード: キー状態を Raspberry Pi の /cw/key に中継
  - 非CWモード（FM等）: CW音声トーンを /radio/audio_tx にストリーミング
  - Androidからのサイドトーン再生（低レイテンシ、ON/OFF設定の記憶対応）
  - CW VPN バッファ設定（キー信号の遅延補正）
  - FM-CW PTT遅延設定（VPN使用時の頭切れ防止、CW/FM-CW個別設定）

●できること
・受信周波数・モード・信号強度のリアルタイム表示
・周波数・モード・パワー・スケルチ・フィルター幅の変更
・受信音声をスマートフォンのスピーカーで再生（SPK）
・PTT ON/OFF と音声送信（マイクの音声を無線機に送出）
・WiFi PTT（M5Atom 等の外部デバイスと連動した PTT 制御）
・USB CW中継（M5ATOM / DualKey を直接 Android に接続して CW キー信号を中継）
・BLE CW中継（DualKey-BLE または RemoteKeyer-BLE を Android に BLE 接続して CW キー信号を中継）
・FT8/FT4 受信デコード・送信（WebView ベース）
・APRS ビーコン送信（DireWolf 経由、スマートフォン GPS 対応）
・APRS ビーコン送信（リグ内蔵モデム経由 AP96/AP12 切替）
・APRS 受信局一覧表示（Mic-E 形式対応）
・複数プロファイル対応（接続先の切り替え）
・API Key 認証対応
・WireGuard VPN 経由での外出先接続対応

●必要なもの
・Android スマートフォン（Android 8.0 以上）
・Raspberry Pi Zero 2W（セットアップ済み）
・WiFi 環境

USB CW中継を使う場合:
・M5ATOM Lite または M5ATOM S3 Lite（Wifi_Rig_CW Ver1.40 ファームウェア書き込み済み）
・OTG 対応 USB ケーブル

BLE CW中継を使う場合（DualKey-BLE / RemoteKeyer-BLE）:
・DualKey-BLE: M5AtomS3（AtomS3）（Wifi_Rig_CW_DUALKEY Ver1.43 ファームウェア書き込み済み）
・RemoteKeyer-BLE: M5StackCore 等（Remotekeyer_M5Stack_Server Ver1.43 ファームウェア書き込み済み）
・Android の Bluetooth 設定でペアリング（OTG ケーブル不要）

●インストール手順
GitHub の以下フォルダから APK をダウンロードしてインストールしてください。
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.51/M5CoreHamCAT_Android

  1. Wifi_RIG_CTRL_v2.51.apk をダウンロード
  2. Android の設定から「提供元不明のアプリ」を許可
  3. APK をタップしてインストール

ソースコードも同フォルダに公開しています（Android Studio でビルド可能）。

●Raspberry Pi セットアップ
M5Core 版と同じ手順でセットアップしてください。
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.51/RaspberryPiSetup

v2.03 以降からのアップグレード: アプリの「Update」→「Update Pi」ボタンで自動更新
v2.12 から Hamlib 4.7.2 対応: アプリの「Update」→「Update Hamlib」ボタンで追加インストール
v2.02 以前からのアップグレード: 初回のみ手動 scp が必要
  scp api.py <ユーザー名>@raspizero:~/fastapi/api.py
  ssh <ユーザー名>@raspizero "sudo systemctl restart fastapi"

●外出先からの接続（WireGuard VPN）
モバイル回線など自宅外から接続する場合は WireGuard のセットアップが必要です。
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.02/WireGuard
（v1.40 からの変更なし）

⑦ iOS版について（WifiRigCTRL for iOS v2.51）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
M5CoreS3SE の代わりに iPhone / iPad でリグをリモート制御できるアプリです。
v2.17 よりソースコードをGitHubで公開しています（最新版：v2.51）。
Android版と同様、M5Core/Module Audio/Unit Encoder 等のハードウェアは不要です。
Raspberry Pi のセットアップは M5Core版・Android版と共通です。

●App Storeでの配信について
2026/8/18 時点では App Store への提出準備中（審査未提出）です。App Store公開まではソースコードを
Xcodeでビルドしてご利用ください（下記「ビルド方法」参照）。公開でき次第、本readmeにリンクを追記します。

●v2.51 の変更点（v2.50 との比較）
【SP2ALART 連携（iOS）】
・POTA/SOTA 通知アプリ「SP2ALART」（v0.84 以降）でスポットをタップすると、URL スキーム経由で
  Wifi_RIG_CTRL が前面に表示され、スポットの周波数・モードが自動設定される
・設定: SP2ALART 設定画面 →「WifiRig CTRL 連携」を OFF 以外に設定
・iOS の制約上、常にアプリが前面に切り替わる（Android のようなバックグラウンド送信は不可）

【Pi側スクリプト更新（create_api.sh）】
・DireWolf ADEVICE を plughw:0,0 固定から .env の ALSA_CAPTURE 設定値に変更（不具合修正）
  USBオーディオデバイスが複数ある環境や CARD=CODEC 以外のデバイス名でも正しく動作
・mawk 互換性修正: awk の代わりに mawk を使う環境でも Pi セットアップが正常に完了
・API_VERSION を "2.51" に更新（Pi 側更新には「Update Pi」が必要）

●v2.50 の変更点（v2.34 との比較）
【Pi側スクリプト更新（api.py / create_api.sh）】
・PTT OFF ウォッチドッグ追加（FTX-1F 等、PTT 固着対策）
  - PTT OFF後、バックグラウンドで実際にOFFを確認できるまで最大20秒間 T 0 を送り続ける
  - rigctld 再起動を挟んだ場合は "t" 応答を2回連続で確認してから確定
  - CW/APRS/FT8 送信に追い越された場合は即座に打ち切り
・rigctld 再起動の排他制御追加（PTT 連打時の多重起動防止）
・WebFT8 バージョンを /radio/status に追加
・webft8 サーバー再起動をsystemd + 直接起動フォールバック方式に改善
・Hamlib モデル別ストップビット自動設定（FTX-1 などデフォルト設定のまま CAT PTT が動作）
・USB オーディオデバイス名の自動検出対応（ALSA_CAPTURE / ALSA_PLAYBACK 自動設定）
  - 初回セットアップ: 複数デバイス時は対話選択、1台のみの場合は自動決定
  - Update Pi: 非対話で自動検出（ヘッドレス環境対応）
・API_VERSION を "2.50" に更新

【アプリ側】
・Pi API バージョン照合を "2.50" に更新（旧 Pi は「Update Pi」で更新してください）

●v2.34 の変更点（v2.33 との比較）
【バグ修正】
・「Update Pi」後の WebFT8 バージョン取得を修正
  - iOS 側で「Update Pi」を実行後、WebFT8 バージョンが取得できなくなる問題を修正
  - URLSession の SSL チャレンジ応答を修正
    （async data(for:) から dataTask + withCheckedContinuation 方式に変更）
  - adminUpdatePi に api.py 再起動待機ステップを追加
    （新 api.py が起動してから WebFT8 更新を実行するよう改善）
  - Pi 側 create_api.sh の embedded server.py に /server_version エンドポイントを追加

【Pi側スクリプト更新】
・create_api.sh: embedded server.py に /server_version エンドポイントを追加
  （_VERSION = "2.34"）
・create_api.sh: embedded api.py の API_VERSION を "2.34" に更新

●v2.33 の変更点（v2.32 との比較）
【バグ修正】
・WebFT8「Loading WASM で止まる」問題を修正（Pi側スクリプト更新、Android版と共通）

【新機能】
・Admin 画面に「Update WebFT8 Server」ボタン追加
  - server_webft8.py のみを素早くデプロイ（Pi 再起動不要）
・「Update Pi API」完了後に「Update WebFT8」を自動実行するよう変更

【Pi側スクリプト更新】
・create_api.sh: wav-save.js をダウンロードリストに追加
・server_webft8.py: 起動時に GitHub から最新 JS ファイルを自動更新

●v2.32 の変更点（v2.31 との比較）
【新機能（CI-V 直接接続）】
・レピータ設定機能追加
  - 周波数ディスプレイを長押しするとレピータ設定シートが開く
  - CTCSS トーンモード（None / Tone / TSQL / DTCS）とトーン周波数を設定可能
  - オフセット方向（+/-）とオフセット周波数（プリセット or カスタム）を設定可能
  - 送信中（PTT ON）はオフセット適用後の実際の TX 周波数を周波数ディスプレイに表示
  - 設定はアプリ終了後も保持

【注記】
・Pi 側スクリプト変更なし（v2.30 と同一）

●v2.31 の変更点（v2.30 との比較）
【新機能】
・MEM バンドメモリー機能追加（Android版と同等）
  - プリセットメモリー（160m〜70cm、バンド別セクション表示）
    ・70cm バンドに CW（430.050 MHz）・SSB（430.100 MHz）を追加
  - ユーザーメモリー（周波数・モード・ステップを自由に登録・編集・削除）
    ・ユーザーメモリーをリスト先頭に配置
    ・モード選択をドロップダウン方式に変更
    ・新規追加時の周波数・モード初期値を現在のリグ値に自動設定
  - MEMパネル: APRSパネルの位置に配置
・BK-IN / APRS パネルの条件表示
  - CW系モード（CW / CWR 等）時 → BK-IN パネルを表示
  - それ以外のモード時 → APRS パネルを表示
  （同一位置で自動切替、操作スペースを有効活用）

【注記】
・Pi 側スクリプト変更なし（v2.30 と同一）

●v2.30 の変更点（v2.20 との比較）
【Pi側API更新】
・Android版と共通（上記⑥参照）

【新機能】
・ネットワーク検索ボタン追加（ラズパイ接続設定画面）
  - 「ネットワーク検索」ボタンをタップすると UDP ブロードキャストで Pi を自動検索
    （Android版と同一プロトコル）
  - API Port / Audio Port は上書きしない（既存の設定を保持）

【改善】
・プロファイル自動保存
  - 接続ボタン押下時・リグを開く時に、アクティブなプロファイルへ現在の設定を
    自動保存するよう変更
・APRS 設定の安定化
  - 30秒ごとに設定を自動再送（Android版と共通）
  - リグモデム AP96/AP12 のハートビート追加（ビーコン途絶防止）
  - APRS 設定画面の OK ボタンで Enabled OFF / TX Method 変更時に自動停止

【修正】
・PTT 種別の表示を「RIG」→「CAT」に統一
・C4FM / D-STAR モード切替の安定性向上
  - C4FM 選択時のフィルタ幅を 0 に強制（rigctld 非対応機種でのエラーを回避）
  - 接続時のモード一覧取得（getCaps）でタイムアウト時に自動リトライ
・D-STAR 選択時にリグへ USB が誤って送信されていた不具合を修正（CI-V 直結モード）

●できること
・受信周波数・モード・信号強度のリアルタイム表示
・周波数・モード・パワー・スケルチ・フィルター幅の変更
・受信音声をスピーカーで再生（ノイズリダクション対応）
・PTT ON/OFF と音声送信（マイクの音声を無線機に送出）
・WiFi PTT（M5Atom 等の外部デバイスと連動した PTT 制御）
・BLE CW中継（DualKey-BLE または RemoteKeyer-BLE を BLE 接続して CW キー信号を中継。USB CW中継はAndroid版のみ対応）
・FT8/FT4 受信デコード・送信（WebView ベース）
・APRS ビーコン送信（DireWolf 経由、GPS対応）
・複数プロファイル対応（接続先の切り替え）
・API Key 認証対応
・WireGuard VPN 経由での外出先接続対応
・直接CI-V接続（IC-705 / IC-9700、Raspberry Pi 不要。周波数・モード・Sメーター・PTT・RF power・SQL・
  BK-IN・マイク音声TX・受信音声RXに対応。CW送信/BLEキーヤーは制限付き対応、FT8/APRS/NR/WiFi PTTは非対応）

●必要なもの
・iPhone / iPad（iOS 17.0 以上）
・Raspberry Pi Zero 2W（セットアップ済み）、または Icom IC-705 / IC-9700（直接CI-V接続の場合）
・WiFi 環境

BLE CW中継を使う場合（DualKey-BLE / RemoteKeyer-BLE）:
・DualKey-BLE: M5AtomS3（Wifi_Rig_CW_DUALKEY Ver1.43 ファームウェア書き込み済み）
・RemoteKeyer-BLE: M5StackCore 等（Remotekeyer_M5Stack_Server Ver1.43 ファームウェア書き込み済み）
・iPhone の Bluetooth 設定でペアリング

●ソースコード・ビルド方法
GitHub の以下フォルダにソースを公開しています。
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.51/M5CoreHamCAT_iOS

  1. Xcode 15 以上で WifiRigCTRL_iOS.xcodeproj を開く
  2. Signing & Capabilities で開発者アカウントを設定
  3. ターゲットデバイスを iPhone / iPad に設定してビルド

外部ライブラリへの依存はありません（Swift Package Manager / CocoaPods 不使用）。

●Raspberry Pi セットアップ
M5Core版・Android版と同じ手順でセットアップしてください。
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.51/RaspberryPiSetup

●外出先からの接続（WireGuard VPN）
Android版と同様、自宅外から接続する場合は WireGuard のセットアップが必要です。
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.02/WireGuard

2026/8/12
以上。
--
2026/8/18
Ver2.50にて、Android版・iOS版・Pi側APIを更新しました。主な変更点は⑥⑦を参照ください。
--
2026/8/23
Ver2.51にて、Android版・iOS版・Pi側スクリプトを更新しました。主な変更点は⑥⑦を参照ください。
--
