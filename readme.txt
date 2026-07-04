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
https://github.com/ji1ore/M5CoreHamCAT/blob/main/v1.11/RaspberryPiSetup/readme.txt
を参照してセットアップを行ってください(verに応じたフォルダを参照ください)。
主な手順は以下の通りです。
・Raspberry Pi Imagerのインストール
・Raspberry Pi Imagerの作成(ここでWifi SSIDの指定やユーザパスワードを指定します)
・SSHログイン
・必要コマンドの実施(シェルファイルを用意してありますので簡単ですが時間がかかります)

④セットアップ手順(M5CoreS3SE/M5Core2)
M5CoreS3SE/M5Core2では、M5Burnerを用いてファームウェアの読み込みを行ってください。
Git上の以下フォルダにソースは公開します。
https://github.com/ji1ore/M5CoreHamCAT/main/M5CoreHamCAT
ソースはVisual Studio Code上のPlatformI/O上でのコンパイルを前提にしています。
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

⑥ Android版について（Wifi_RIG_CTRL_ForAndroid v2.14）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
M5CoreS3SE の代わりに Android スマートフォンでリグをリモート制御できる
アプリを v1.30 より公開しています（最新版：v2.14）。
M5Core/Module Audio/Unit Encoder 等のハードウェアは不要です。
Raspberry Pi のセットアップは M5Core 版と共通です。

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
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.14/M5CoreHamCAT_Android

  1. Wifi_RIG_CTRL_v2.14.apk をダウンロード
  2. Android の設定から「提供元不明のアプリ」を許可
  3. APK をタップしてインストール

ソースコードも同フォルダに公開しています（Android Studio でビルド可能）。

●Raspberry Pi セットアップ
M5Core 版と同じ手順でセットアップしてください。
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.14/RaspberryPiSetup

v2.03 以降からのアップグレード: アプリの「Update」→「Update Pi」ボタンで自動更新
v2.12 から Hamlib 4.7.2 対応: アプリの「Update」→「Update Hamlib」ボタンで追加インストール
v2.02 以前からのアップグレード: 初回のみ手動 scp が必要
  scp api.py <ユーザー名>@raspizero:~/fastapi/api.py
  ssh <ユーザー名>@raspizero "sudo systemctl restart fastapi"

●外出先からの接続（WireGuard VPN）
モバイル回線など自宅外から接続する場合は WireGuard のセットアップが必要です。
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.02/WireGuard
（v1.40 からの変更なし）

2026/7/4
以上。
