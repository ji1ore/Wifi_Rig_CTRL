Wifi RIG CTRL  Raspberry Pi セットアップガイド（v2.60）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
配布ファイル
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Wifi_RIG_CTRL_v2.60.apk   : Android アプリ
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
v2.34 での変更点（Pi 側 / api.py）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・/radio/status に webft8_version を追加
    webft8_static/web/server.py の _VERSION を読み取って返すようにし、
    アプリ側でwebft8サーバーとのバージョン不一致を検知できるようにした。
    /admin/update_webft8 実行時、systemdサービスがあればsystemctl経由、
    無ければ直接プロセスをkill&再起動するフォールバックにも対応。

・FTX-1F でハードPTT(Hamlib経由)を6秒未満で離すとTX固着する不具合を修正
    FTX-1F はPTT ON直後、出力が徐々に10Wへ立ち上がるランプアップ動作
    (約6秒)があり、その最中に送られた T 0（PTT OFF）はCI-V応答上は成功
    (RPRT 0)しつつ無線機側で無視されることがあった。従来は/radio/ptt
    (state=0)で1回失敗検知リトライするのみだったため、ランプアップ完了前
    にPTTを離すとTXが固着したままになっていた。

    v2.34 では、/radio/ptt(state=0)でT 0を送った後、バックグラウンドの
    _ptt_off_watchdogが"t"問い合わせで実際にOFFを確認できるまで1秒間隔で
    T 0を送り続けるよう変更（最大20秒）。HTTPレスポンス自体はT 0を1回
    送った直後に即返すため、ESP32/Android/iOS側のPTT操作レイテンシには
    影響しない。

    また、CW送信(内蔵キーヤー)・APRS送信(direwolf)はT 1/last_ptt_stateを
    経由せず独自にPTTを制御しているため、watchdog実行中にそれらが動き出す
    と無関係なT 0を送って割り込んでしまう恐れがあった。last_ptt_stateに
    加えradio_cache["tx"]・_morse_sending・_ft8_tx_activeのいずれかが
    立った時点でwatchdogを即座に打ち切るようにし、他の送信方式との衝突を
    防いでいる。

    反映には Pi 側 api.py の更新が必要（アプリの「Update Pi」、または
    create_api.sh の再実行）。

・rigctld再起動を挟んだPTT OFFに対する安全策を追加
    実機検証の結果、上記の不具合の実際の引き金は「ランプアップ中の無視」
    だけでなく、送信時のRF回り込み（430MHz帯等）でPi側のUSBオーディオ/
    rigctldがリセットされ再起動することが主因と判明（USBケーブルへの
    フェライトコア追加等、ハード側の対策も別途推奨）。

    rigctldはUSBリセット復旧時 release_ptt=False で再起動されるため、
    再起動直後は無線機の実際のTX状態を把握していない。この状態で
    _ptt_off_watchdogが"t"問い合わせの単発の"0"応答を信用すると、
    無線機がまだ実際には送信中でも「OFF確認済み」と誤判定する恐れが
    あった。

    v2.34ではrigctldの再起動(プロセス入れ替わり/_rigctld_restarting)を
    watchdogが検知した場合、"t"が2回連続で"0"を返すまで確認を継続し、
    確認後・最大20秒でのタイムアウト後のいずれでも念のため最後にもう
    一度無条件でT 0を送るようにした（既にOFFなら無害、まだONなら最後の
    保険になる）。

・PTT連打時にrigctld再起動が競合する不具合を修正
    音声USBリセット検知(audio_tx)・PTT ON失敗(/radio/ptt)・poll_rigの
    タイムアウト検知、の3箇所がそれぞれ独立に「_rigctld_restartingが
    Falseなら再起動をキックする」という check-then-act 方式だったため、
    PTTを短い間隔で連打すると、ほぼ同時に複数箇所が"restarting中ではない"
    と判定してしまい、start_rigctldが多重に起動してrigctldプロセス同士が
    競合する（お互いをkillし合う・ポート起動待ちが乱れる等）ことがあった。

    v2.34では_trigger_rigctld_restart()ヘルパーを追加し、threading.Lockで
    排他制御するようにした。ロックを取得できなかった場合(既に他の再起動が
    進行中)は何もせず即座に諦める(既存の再起動が終われば全経路とも回復する
    ため無視して問題ない)。3箇所の再起動トリガーをすべてこのヘルパー経由に
    統一。

・PTT ON→OFFが極端に短いとrigctldが復旧せず放置される不具合を修正
    音声USBリセット検知(audio_tx)は last_ptt_state==1 を条件に再起動をキック
    する設計だったが、PTT ON直後に極端に短い間隔でOFFされると、OFF処理の方が
    先に last_ptt_state を0にしてしまい、その後にUSBリセットを検知しても
    「last_ptt_state==1ではない」ため誰も再起動をキックしないまま放置される
    ことがあった。この場合 _ptt_off_watchdog は「"t"問い合わせが無応答
    (rigctld無応答)」を観測し続けるだけで、20秒間何も直さずに諦めていた。

    v2.34では _ptt_off_watchdog 自身が、"t"問い合わせが無応答でまだ誰も
    再起動を進行させていない場合、自らrigctldの再起動をキックするように
    した(release_ptt=True。last_ptt_state==0のためstart_rigctld自身の
    PTT解除ロジックにもT 0を送らせ、OFFをさらに確実にする)。

・API_VERSION を 2.34 に更新

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.30 での変更点（Pi 側 / api.py）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・モード一覧を機種ごとの動的検出に変更
    従来、/radio/modes・/radio/caps は機種によらず固定の汎用モードリスト
    （LSB/USB/CW/CWR/AM/FM/DIGL/DIGU/PKTLSB/PKTUSB/PKTFM）のみを返しており、
    FT-991 の C4FM や IC-705 の D-STAR など、機種固有のデジタルモードを
    選択する手段がなかった。

    v2.30 では、/radio/open 時にバックグラウンドで rigctld へ \dump_caps を
    送り、"Mode list:" 行から接続中の機種が実際にサポートするモード一覧を
    検出して current_mode_list に保持するように変更（VFO A/B・MAIN/SUB
    自動判別と同じ \dump_caps 応答を再利用）。/radio/modes・/radio/caps は
    検出済みならその機種固有のリストを返す。検出前・失敗時は従来の固定
    リストにフォールバックするため後方互換。

    Hamlib 4.7.2 で確認: FT-991(model 1035)は C4FM、IC-705(model 3085)は
    D-STAR（Hamlib表記はハイフンあり "D-STAR"）がモード一覧に含まれる。
    アプリ側（Android/iOS/M5）は /radio/caps の返り値をそのまま選択肢と
    して表示するだけの実装のため、クライアント側の変更は不要。

    反映には Pi 側 api.py の更新が必要（アプリの「Update Pi」、または
    create_api.sh の再実行）。

・API_VERSION を 2.30 に更新

・C4FM モード切替時の BUSBUSY エラーを成功扱いに変更
    FT-991A など rigctld が BUSBUSY（RPRT -14）を返す機種では、C4FM への
    モード切替コマンドは受け付けているにもかかわらず -14 が返るため、
    従来はアプリ側でエラーとして処理されていた。
    v2.30 では set_mode 応答の RPRT -14 を成功とみなすよう修正（FT-991A での
    C4FM モード切替が正常に動作するようになる）。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.18 での変更点（Android アプリ側）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・versionCode 更新（Google Play 公開バージョン整合）
    Pi 側スクリプト変更なし（Pi 側は v2.17 と同一）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.17 での変更点（Android アプリ側）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・IC-705 Wifi CI-V 接続の信頼性向上
    エフェメラルポート（0）を使用して接続のたびに異なる ctrlMyId を生成
    IC-705 が古いセッションを再利用してしまう問題を解消（iOS 動作に合わせて修正）
    IAH 未受信時でも ping からの civRemoteId 学習済みなら接続を継続

・PiP 復帰時のレイアウト修正
    GridLayout の再計算を post() で遅延実行しウィンドウ展開完了後に行うよう変更

・Pi 側スクリプト変更なし（Pi 側は v2.16 と同一）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.16 での変更点（Android アプリ側）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・CI-V 接続の [TEST] 表記を削除（正式機能として扱い）
    RIG CONNECT 画面の「USE CI-V」表記から [TEST] ラベルを除去

・Pi 側スクリプト変更なし（Pi 側は v2.15 と同一）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.15 での変更点（Android アプリ側）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・CI-V 接続時のコールサイン欄から「FT8」表記を削除
    CI-V モードでは FT8 は使用不可のため「My Callsign (FT8)」→「My Callsign」に変更
    Pi 側スクリプト変更なし（v2.14 と同一）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.14 での変更点（Android アプリ側）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・IC-705 / IC-9700 等への直接 Wifi CI-V 接続対応（Raspberry Pi 不要）
    RIG CONNECT 画面の「USE CI-V」スイッチで接続先を切替
    CI-V ポート（デフォルト 50001）・CI-V アドレス（IC-705: 0xA4）を設定
    周波数・モード・Sメーター・PTT・RF power・SQL・BK-IN に対応
    音声ストリーミング・CW テキスト送信・FT8・APRS は Pi モードのみ

・Pi 側スクリプト変更なし（Pi 側は v2.13 と同一）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.13 での変更点（Android アプリ側）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・CW 打鍵時の SPK 音切れタイミング改善
    AudioTrack バッファを ~2 秒 → ~200ms に削減（iOS版と同等のレスポンス）

・Android エッジ・ツー・エッジ表示対応（Google Play ポリシー準拠）
    enableEdgeToEdge() + WindowInsetsCompat でシステムバー領域を自動回避
    非推奨 API setStatusBarColor / setNavigationBarColor を解消

・ピクチャー・イン・ピクチャー（PiP）実装
    TX 中・CW 打鍵中にホームボタンで自動的に小画面に移行
    Android 12+ では setAutoEnterEnabled で自動移行対応

・USB CW 接続時のレイアウト崩れ修正
    USB パーミッションダイアログ表示中の誤 PiP 移行を防止（suppressPip フラグ）
    PiP 復帰時に gridLayout を再計算

・UI 修正
    スプラッシュ画面にアプリアイコン追加
    「+ New」ボタンの高さ修正（見切れ解消）
    ボタン色の統一（AppCompat テーマに統一）
    FT8 画面ボタン行の高さ修正

・Pi 側スクリプト変更なし（Pi 側は v2.12 と同一）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
v2.12 での変更点
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・Android アプリ更新（Pi 側スクリプト変更なし）

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
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.60/RaspberryPiSetup/setup_netwk.sh
chmod +x setup_netwk.sh
bash setup_netwk.sh

--- 環境構築（FT8 含む全機能セットアップ・UpdatePi 相当）---
BASE=https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.60/RaspberryPiSetup
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
  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.60/RaspberryPiSetup/setup_ft8_encode.sh
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
  wget -O ~/create_api.sh https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.60/RaspberryPiSetup/create_api.sh
  wget -O ~/set_api_key.sh https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.60/RaspberryPiSetup/set_api_key.sh
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
