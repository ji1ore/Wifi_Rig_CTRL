# Wifi RIG CTRL for Android — v3.16

Android スマートフォンからアマチュア無線機をWi-Fi経由でリモート操作するコントローラーアプリ。

> **English summary** — Wifi RIG CTRL is an Android controller app for amateur (ham) radio operators. It connects to a transceiver over Wi-Fi via a Raspberry Pi (Hamlib/FastAPI) or directly to an IC-705 / IC-9700 (CI-V). Features: RX audio, PTT/mic TX, CW decode & send, USB/BLE CW keyer, FT8/FT4 server-side decode via jt9, APRS beacon, RTTY AFSK TX/RX, QSO log, Raspberry Pi SSH one-tap setup, Picture-in-Picture, multiple profiles.

---

## 動作要件

本アプリ単体では動作しません。次のいずれかのハードウェアが必要です。

| 接続方式 | 必要なもの |
|---|---|
| **Raspberry Pi 経由** | Raspberry Pi Zero 2W 以上のスペック（Hamlib + FastAPI サーバー導入済み）＋ 対応無線機<br>※ FT8デコードには Raspberry Pi 4 以上推奨（Pi 5 1GB も可） |
| **直接CI-V接続** | Icom IC-705 または IC-9700（Android と同一Wi-Fiネットワーク） |

- Android 7.0 (API 24) 以上
- Wi-Fi接続必須

---

## 接続方式別 星取表

| 機能 | Raspberry Pi 経由 | CI-V Wi-Fi直接 | CI-V Bluetooth ※1 |
|---|:---:|:---:|:---:|
| 周波数表示・変更 | ○ | ○ | ○ |
| モード表示・変更 | ○ | ○ | ○ |
| フィルタ幅変更 | ○ | ○ | ○ |
| Sメーター表示 | ○ | ○ | ○ |
| 送信出力（RF Power）変更 | ○ | ○ | ○ |
| スケルチ変更 | ○ | ○ | ○ |
| ブレークイン（BK-IN）ON/OFF | ○ | ○ | ○ |
| PTT ON/OFF | ○ | ○ | ○ |
| マイク音声 TX（PTT中ストリーミング） | ○ | × | ○ |
| 受信音声 RX（スピーカー再生） | ○ | × | ○ |
| CW テキスト送信 | ○ | × | × |
| CW デコード（受信音声） | ○ | × | × |
| USB CWキーヤー（DualKey USB直結） | ○ | × | × |
| BLE CWキーヤー（DualKey-BLE 等） | ○ | × | × |
| CQ リピート | ○ | × | × |
| ノイズリダクション（NR） | ○ | × | × |
| Wi-Fi PTT（M5Atom 等外部デバイス） | ○ | × | × |
| FT8 / FT4（サーバーサイドデコード） | ○ | × | × |
| FT8 / FT4（ローカルデコード ※2） | × | ○ | ○ |
| APRS ビーコン送信 | ○ | × | × |

○: 対応　×: 非対応

**※1** CI-V Bluetooth：IC-705 等を Bluetooth RFCOMM（SPP）で接続。音声は Bluetooth SCO 経由。  
**※2** ローカル FT8 は端末上の ft8_lib（JNI）でデコード。Wi-Fi CI-V は RS-BA1 音声ストリームを入力、Bluetooth CI-V は BT SCO 音声を入力。

---

## 主な機能

### 基本制御
- 受信周波数・モード・Sメーターのリアルタイム表示（200ms ポーリング）
- 周波数変更（ステップ: 1Hz / 10Hz / 100Hz / 500Hz / 1kHz / 5kHz / 10kHz / 20kHz）
- モード・送信出力・スケルチ・フィルタ幅の変更（◀▶ ボタンで ±1% / ±100Hz 調整）
- ノイズリダクション（レベル 0〜5、SQL ボタン長押しで循環）

### 音声
- 受信音声のスピーカー再生（サンプリングレート選択可: 8k〜48kHz）
- PTT ON/OFF ＋ マイク音声のリアルタイム送信
- Wi-Fi PTT（M5Atom 等の外部デバイス経由）

### CW
- CW デコード（受信音声から最大5局同時・リアルタイム表示）
- CW 送信（テキスト入力、WPM 調整、プリセットメッセージ）
- CQ リピート（回数・インターバル設定）
- ローカルサイドトーン再生（低レイテンシ、200ms バッファ）
- **USB CWキーヤー対応**（M5ATOM Lite / M5ATOM S3 Lite を OTG USB で直結）
- **BLE CWキーヤー対応**（DualKey-BLE / RemoteKeyer-BLE）
- FM-CW モード（FM時はPTT per element + PCMトーンストリーミング）

### FT8 / FT4
- **サーバーサイドデコード（Raspberry Pi モード）**: Pi 上の `jt9`（WSJT-X）が15秒ごとに音声をデコード
- **SSE（Server-Sent Events）**: デコード結果をリアルタイムで Android に配信
- **ローカルデコード（CI-V モード）**: ft8_lib（JNI）による端末上のオンデバイスデコード（Pi 不要）
  - Wi-Fi CI-V: RS-BA1 音声ストリーム（8kHz）を入力
  - Bluetooth CI-V: Bluetooth SCO 音声（12kHz）を入力
- **ウォーターフォール表示**: FT8 画面にリアルタイムスペクトル表示
- **ネイティブ RecyclerView UI**: WebView 不使用、軽量・高速
- FT8 メッセージタップで TX 欄に自動入力（CQ→応答、+レポート→RR73、RR73→73）
- CQ のみ表示フィルター、QSO ログ（ADIF エクスポート対応）
- TX前後の周波数・モード自動保存・復元

### RTTY
- RTTY AFSK 送受信（Pi 経由。2125/2295 Hz Goertzel 復調・ITA2 デコード）
- スペクトラム表示・ヒステリシス付きオートチューン・FIX モード
- コマンドボタン（CQ/ANS タブ切替）・TX テキスト入力・ゲイン調整
- FT-991A バンド別キャリア周波数補正（HF / 50 / 144 / 430 MHz）
- CI-V 直接接続時は使用不可（Pi の音声経路に依存）

### APRS
- APRSビーコン送信（GPS対応・手動座標入力も可）
- コールサイン・SSID・パス・シンボル・コメント設定
- 送信間隔・ボーレート（1200 / 9600）設定
- **APRS受信局リスト**: Pi が受信した APRS ステーションの一覧・距離・方位コンパス表示

### 接続
- 複数プロファイル対応（接続先ごとに保存・切替）
- API キー認証
- mDNS（.local ホスト名）対応
- WireGuard VPN 経由のリモートアクセス対応
- **CI-V Bluetooth 接続**: IC-705 等へ Bluetooth RFCOMM（SPP）で直接接続
  - 音声は Bluetooth SCO（BT ヘッドセットプロファイル）経由
  - Wi-Fi が利用できない環境でもリグ制御が可能

### POTA / SOTA 連携
- **メモリ/スポットダイアログ**: プリセット周波数・ユーザーメモリ・POTAスポット・SOTAスポットをタブ切替で呼び出し
- **SP2ALERT 連携**: POTA/SOTA スポット通知アプリから周波数・モードを Intent で受け取り自動設定
- **POTA ハンターログ取得**: pota.app へのログインを仲介し、ハンターのアクティベーション履歴を取得

### その他
- ピクチャー・イン・ピクチャー（PiP）対応（TX中・CW打鍵中にホームボタンで小画面移行）
- **アプリ内ヘルプ**: 日本語・英語対応の操作ガイド画面
- 管理画面（Piサーバーファームウェア・CWブリッジ・Hamlib のアップデート、ビルドログ確認）
- About画面で Pi API バージョン・Hamlib バージョン・FT8デコード状態確認

---

## Wifi_Rig_CW との関係

**Wifi_Rig_CW** は、M5Stack デバイスに書き込む CW キーヤーのファームウェアプロジェクトです。  
本アプリはこのファームウェアが動作するデバイスと **USB（CDC）または Bluetooth LE** で接続し、CW キー信号を中継します。

### 対応デバイスとファームウェア

| デバイス名 | ハードウェア | 接続方式 | ファームウェア |
|---|---|---|---|
| **DualKey** | M5AtomS3 (AtomS3) | USB CDC（OTGケーブル） | Wifi_Rig_CW_DUALKEY v1.43 |
| **DualKey-BLE** | M5AtomS3 (AtomS3) | Bluetooth LE | Wifi_Rig_CW_DUALKEY v1.43 |
| **RemoteKeyer-BLE** | M5StackCore 等 | Bluetooth LE | Remotekeyer_M5Stack_Server v1.43 |

### 動作の流れ

```
パドル / 電鍵
      │
      ▼
DualKey / DualKey-BLE / RemoteKeyer-BLE (M5Stack)
      │  USB CDC（OTGケーブル） or Bluetooth LE (Nordic UART Service)
      ▼
Wifi RIG CTRL (Android)   ←── 本アプリ
      │  UDP (ポート 8889)
      ▼
Raspberry Pi (cw_bridge.py)
      │  CAT / シリアル
      ▼
無線機
```

---

## プロジェクト構成

```
Wifi_RIG_CTRL_ForAndroid_3.13/
├── app/src/main/
│   ├── java/com/ji1ore/wifi_rig_ctrl/
│   │   ├── MainActivity.kt              # メインActivity・PiP制御
│   │   ├── SplashFragment.kt            # 起動画面
│   │   ├── ConnectFragment.kt           # 接続設定画面
│   │   ├── RigSelectFragment.kt         # リグ選択画面
│   │   ├── MainControlFragment.kt       # メインコントロール画面
│   │   ├── Ft8Fragment.kt               # FT8/FT4画面（ネイティブUI + ウォーターフォール）
│   │   ├── WaterfallView.kt             # FT8リアルタイムウォーターフォール
│   │   ├── AprsSettingsFragment.kt      # APRS設定画面
│   │   ├── AprsReceivedFragment.kt      # APRS受信局リスト（距離・方位表示）
│   │   ├── CompassView.kt               # 方位コンパスView（APRS受信画面用）
│   │   ├── PttSettingsFragment.kt       # PTT設定画面
│   │   ├── FreqInputFragment.kt         # 周波数入力ダイアログ
│   │   ├── MemoryDialogFragment.kt      # メモリ/POTAスポット/SOTAスポット呼び出しダイアログ
│   │   ├── HelpActivity.kt              # アプリ内ヘルプ（日英切替）
│   │   ├── PotaHuntLogActivity.kt       # POTAハンターログ取得（pota.app連携）
│   │   ├── Sp2alertReceiver.kt          # SP2ALERT連携 BroadcastReceiver
│   │   ├── UpdateFragment.kt            # 管理・アップデート画面
│   │   ├── AboutFragment.kt             # バージョン情報
│   │   ├── data/
│   │   │   ├── RigApiService.kt         # Hamlib HTTP API クライアント
│   │   │   ├── CivTcpService.kt         # CI-V Wi-Fi (RS-BA1互換) 直接接続
│   │   │   ├── CivBtService.kt          # CI-V Bluetooth (RFCOMM SPP)
│   │   │   ├── CivBtAudio.kt            # Bluetooth SCO 受信音声ループバック
│   │   │   ├── AudioStreamService.kt    # 受信音声ストリーム（Pi モード）
│   │   │   ├── AudioTxService.kt        # 送信音声ストリーム
│   │   │   ├── UdpPttService.kt         # Wi-Fi UDP PTT
│   │   │   ├── CwUsbService.kt          # USB CWキーヤー (CDC)
│   │   │   ├── CwBleService.kt          # BLE CWキーヤー (Nordic UART)
│   │   │   ├── CwDecoder.kt             # 受信音声→モールス符号デコーダ
│   │   │   ├── CwKeyDecoder.kt          # キー状態→文字デコーダ
│   │   │   ├── Ft8Jni.kt               # FT8 JNIブリッジ（libft8jni.so）
│   │   │   ├── Ft8LocalEngine.kt        # FT8ローカルエンジン（CI-Vモード用）
│   │   │   ├── HunterStore.kt           # POTAハンターデータ永続化
│   │   │   ├── NtpClient.kt             # NTP時刻同期
│   │   │   ├── ProfileConfig.kt         # プロファイル定義・永続化
│   │   │   ├── AppPrefs.kt              # SharedPreferences ラッパー
│   │   │   └── Models.kt                # データモデル・定数
│   │   └── viewmodel/
│   │       └── MainViewModel.kt         # メインViewModel（全状態管理）
│   ├── assets/
│   │   ├── api.py                       # FastAPI サーバー（Pi に送信）
│   │   ├── create_api.sh                # Pi 環境セットアップスクリプト
│   │   ├── cw_bridge.py                 # CW ブリッジスクリプト
│   │   ├── ft8_tx_endpoint.py           # FT8 TX エンドポイント
│   │   ├── help.html                    # ヘルプページ（英語）
│   │   ├── help_ja.html                 # ヘルプページ（日本語）
│   │   └── install_hamlib.sh            # Hamlib ビルドスクリプト
│   ├── jni/                             # ft8_lib NDK ビルド設定（ft8jni ネイティブライブラリ）
│   └── res/layout/
│       ├── fragment_ft8.xml             # FT8 ネイティブレイアウト
│       ├── item_ft8_msg.xml             # RecyclerView 行レイアウト
│       └── ...
└── README.md
```

---

## 接続フロー

```
Android
  │
  ├─[Raspberry Pi モード]── HTTP ──► Raspberry Pi
  │                                      │
  │                                 Hamlib/rigctld
  │                                 FastAPI v3.00
  │                                      │
  │                                 jt9 (WSJT-X)  ← 15秒ごとにデコード
  │                                      │
  │                                   無線機 (CAT)
  │
  │    FT8デコード結果 ──SSE──►  Android (RecyclerView)
  │
  ├─[CI-V 直接モード]── TCP (RS-BA1互換) ──► IC-705 / IC-9700
  │                          Port 50001
  │
  ├─[USB CWキーヤー]── USB CDC (OTGケーブル) ──► DualKey (M5AtomS3)
  │
  └─[BLE CWキーヤー]── Bluetooth LE (Nordic UART) ──► DualKey-BLE / RemoteKeyer-BLE
```

---

## インストール

### Android アプリ

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
```

署名済み APK: `app/build/outputs/apk/release/app-release.apk`

1. `Wifi_RIG_CTRL_v3.14.apk` を Android デバイスに転送
2. Android の設定から「提供元不明のアプリ」を許可
3. APK をタップしてインストール

### Raspberry Pi サーバー

#### 新規セットアップ（SETUP 画面を使用）

> **前提**: Raspberry Pi に Raspberry Pi OS がクリーンインストールされていること。Pi がスマートフォンと同じ Wi-Fi ネットワークに参加しており、SSH が有効であること。

1. アプリを起動し、接続設定画面（CONNECT 画面）を開く
2. **「SETUP Pi」ボタン**をタップ
3. 以下の項目を入力する:

   | 項目 | 説明 | 例 |
   |---|---|---|
   | IP / Host | Pi の IP アドレスまたはホスト名 | `192.168.1.100` / `raspberrypi.local` |
   | SSH User | SSH ユーザー名 | `pi`（デフォルト） |
   | SSH Password | SSH パスワード | `raspberry`（デフォルト） |

4. **「Run Setup」をタップ**
   - GitHub から最新サーバーファイルを自動ダウンロード
   - Hamlib・FastAPI・Direwolf 等を自動インストール
   - systemd サービス（fastapi）を設定・起動
   - ⚠ **実行中は画面 OFF・他の画面への移動をしないでください**（Pi Zero 2W は **5〜10 分**かかります）
5. 完了後、mfsk-decode バックグラウンドビルドが自動開始される（**10〜20 分**）
   - Admin 画面の「mfsk Build Log」ボタンで進捗を確認できます
6. Pi が再起動したら利用開始できます

#### 既存環境のアップグレード

アプリ内の Admin 画面（接続設定画面のメニュー → Admin）→「Update Pi」ボタンをタップ。
最新の `api.py` と `create_api.sh` が Pi に送信され、FastAPI が再起動します。

```bash
# 手動で更新する場合（Pi 上で実行）
sudo systemctl restart fastapi
```

---

## 通信ポート一覧

| 用途 | プロトコル | デフォルトポート |
|---|---|---|
| Hamlib FastAPI | HTTP | 8000 |
| 受信音声ストリーム | HTTP (chunked) | 50000 |
| Wi-Fi PTT (M5Atom) | UDP | 8888 |
| CW Bridge (USB/BLE中継) | UDP | 8889 |
| CI-V 制御 (IC-705) | TCP | 50001 |

> **v3.00 変更点**: WebFT8 HTTPS サーバー（ポート 8443）を廃止。FT8デコード結果は FastAPI（ポート 8000）の SSE エンドポイント `/ft8/rx_msgs` で配信。

---

## FastAPI v3.00 FT8 エンドポイント

| エンドポイント | メソッド | 説明 |
|---|---|---|
| `/ft8/start` | POST | FT8 デコードループ開始 |
| `/ft8/stop` | POST | FT8 デコードループ停止 |
| `/ft8/rx_msgs` | GET (SSE) | デコード結果ストリーム（keepalive 20秒） |
| `/radio/status` | GET | `ft8_decode_running` フラグを含むステータス |

SSE メッセージ形式:
```json
{
  "type": "decode",
  "period": 42,
  "utc_sec": 630,
  "msgs": [
    {"freq": 1234, "snr": -10, "dt": 0.3, "msg": "CQ JA1ABC PM85"}
  ]
}
```

---

## Raspberry Pi セットアップ

### クリーンインストール後の初回セットアップ

> Raspberry Pi OS を新規インストールした直後の状態を前提とします。

**接続設定画面 →「SETUP Pi」ボタン** から SETUP 画面を開き、Pi の IP アドレス・SSH ユーザー名・パスワードを入力して「Run Setup」を実行してください。詳細な手順は [インストール → 新規セットアップ](#新規セットアップsetup-画面を使用) を参照。

### 既存環境のアップグレード

| アップグレード元 | 方法 |
|---|---|
| v3.00 以降 | Admin 画面の「Update Pi」ボタンで自動更新 |
| v2.02 以前 | SETUP 画面から再セットアップを推奨 |

---

## プライバシー

- 広告・使用状況のトラッキング: **なし**
- 外部サーバーへのデータ送信: **なし**（ユーザーが設定した Raspberry Pi / 無線機のみ通信）
- 位置情報: APRSビーコン送信時・FT8グリッドロケーター取得時のみ使用（ユーザーが明示的に許可した場合）
- マイク: PTT送信中にのみ使用（録音・保存なし）

---

## バージョン履歴

### v3.16（2026-10-07・versionCode 116）

**新機能: Raspberry Pi ワンタップセットアップ（SETUP 画面）**
- 接続設定画面に「SETUP Pi」ボタンを追加
- Pi の IP アドレス・SSH ユーザー名・パスワードを入力してタップするだけで、クリーンインストール済みの Pi に FastAPI / Hamlib / Direwolf / mfsk-decode を一括インストール
- セットアップは SSH 経由で GitHub から最新スクリプトをダウンロードして実行（5〜10 分）
- セットアップ完了後に mfsk-decode のバックグラウンドビルドを自動トリガー

**Pi API**
- v3.15 から変更なし

### v3.15（2026-10-06・versionCode 114）

**FT8 改善**
- SSE ストリーム切断中に qso_done イベントを受信できなかった場合の QSO 状態リセットを自動化（ウォッチドッグ 60 秒タイムアウト）
- SSE 再接続ウェイトを 3 秒→1.5 秒に短縮
- 全デコードを ~/ALL.TXT に自動保存（WSJT-X 互換）
- LOG ボタン長押し → 最新 500 行の表示・共有・クリア

**RTTY 改善**
- PO（送信出力）・ALC メーター表示を追加
- 送信出力調整ボタンを追加（+5% / -5%、現在値表示）
- TX ゲイン（音量）スライダー範囲を 10〜100% → 1〜100% に拡大
- RST ボタンを拡張（519/529/539/549）
- CQ "73 DE [CALLSIGN] K" ボタンを追加
- 全送信テキストの先頭に "RTRT " を自動付加（RTTY 識別）
- CQ/ANS パネルにコール×1/×2/×3 リピートセレクターを追加

**About 画面**
- mfsk-core バージョン表示を追加（v0.13.1）

**Pi API 更新（v3.15）**
- mfsk-core v0.13.0 → v0.13.1 に更新
- /ft8/all_txt エンドポイント追加（GET で取得、DELETE でクリア）
- /admin/version に mfsk_version フィールドを追加

### v3.14（2026-10-06・versionCode 113）
**RTTY 操作性改善**
- コマンドボタンを CW TX 画面と同じ配色に統一（送信マクロ: 紫、CQ REPEAT/TX: 緑、STOP: 赤）
- コマンドボタン長押しで TX テキストフィールドに流し込み（修正後に TX / TX×2 で送信可能）
- TX ゲイン調整ボタンを追加（10〜100%、シークバーで設定）
- FT-991A バンド別キャリア周波数補正機能を追加（HF / 50 / 144 / 430 MHz 各バンドで個別設定可）

**Pi API**
- v3.13 から Pi API に変更なし。「Update Pi」は不要です。

### v3.13（2026-10-04・versionCode 110）
**新機能: QSO ログ**
- 交信記録をアプリ内に保存（コールサイン・RST 送受信・バンド・モード・周波数・日時）
- POTA / SOTA 参照符号フィールド（相手局・自局）対応
- ADIF 形式でエクスポート（選択した QSO のみ、または全件）
- 登録済み QSO の後から修正にも対応

**修正**
- Pi API バージョン照合を "3.13" に修正（About 画面で "3.12 is required" と表示されていた問題を修正）

**Pi API 更新（v3.13）**
- FT8 デコード深さのデフォルトを 3（deep）→ 1（fast）に変更（速度と精度のバランス改善）
- mfsk-decode を mfsk-core v0.13.0 に更新（FT8/FT4 デコード精度向上）
- PTT 音声送信の BrokenPipeError を捕捉しクラッシュを防止
- setup_fastapi_radio.sh の改善（libncurses-dev、rpath 追加、venv 修復強化、Direwolf 自動検出）

### v3.12（2026-10-03・versionCode 105）
**RTTY 改善**
- 受信チューニングを刷新: スクロール式ウォーターフォールに代えて現在フレームのスペクトラム
  表示（`RttySpectrumView.kt`）を採用。ヒステリシス付きオートチューンで Mark/Space を追従
- Tune ボタン長押しで FIX モード: オートチューンを止め、スペクトラムのタップでデコード位置を移動
- 受信レベル表示、標準トーン（2125/2295 Hz）への自動フォールバックを追加
- 送信経路を自動選択に統一（Pi 経由の AFSK 音声送信）。シリアル FSK の選択肢は CAT ポートと競合して
  制御を失うため UI から削除。ダイヤルオフセット（Mark トーン分、FT-991A の DATA SHIFT 分）を自動補正
- 接続断などで周波数が不明な間は送信しない
- CI-V 直接接続時は RTTY を使用不可に変更（送受信とも Pi の音声経路に依存するため）
- RTTY 画面（SPK OFF）を離れるときに Pi の受信音声ストリームを止めないよう修正
  （同じ Pi に接続中の他クライアントの音声が途切れていた）

**修正・改善**
- Pi の FastAPI 再起動後に周波数 0 のまま固まる状態を検出し、30 秒間隔の制限付きで自動的に
  `/radio/open` を再発行（rigctld の復旧）
- FT8 画面の入退出で FT8 用 ALSA デバイス設定を適用・解除
- 「Update Hamlib」: 完了マーカーを `install_hamlib.sh` の出力（`=== 完了 ===`）に合わせ、
  ビルド成功後も 60 分待ってタイムアウトになっていた問題を修正

**Pi API 更新（v3.12）**
- 「Update」→「Update Pi」で Raspberry Pi 側を更新してください
- リグ選択・音声デバイス選択をファイルに保存し、FastAPI 再起動後も自動復旧経路を維持
  （音声デバイス選択は :8000 / :50000 の 2 プロセス間で共有）
- 受信音声チェーンのゲインを +20 dB → +6 dB に変更（クリッピング解消・RTTY デコード改善）
- 既に同じレートで配信中の場合は再接続時に FT8 キャプチャを止めない（音切れ解消）
- 診断用エンドポイント `/admin/amixer` を追加（`/radio/rtty_fsk_tx` は実験的エンドポイントで、アプリからは未使用）
- mfsk-decode を mfsk-core v0.12.0 に更新（FT4 デコード・既知コールサインのハッシュ対応）

### v3.11（2026-09-28・versionCode 96）
- iOS 版と合わせたバージョン統一リリース
- CW TX シフト使用時の周波数ドリフトをさらに改善

### v3.10（2026-09-23〜28・versionCode 88〜95）
- RTTY 送受信機能を追加（FT8 ボタン長押し）
- BLE/USB リモート CW 送信の安定性改善、CW TX 周波数シフトのドリフト修正
- STEP エンコーダ操作後 2 秒で FREQ に自動復帰
- FT4 ウォーターフォール速度バグ修正（WiFi CI-V モード）、コンパウンドコールサイン対応
- minSdkVersion 21 → 24、R8 有効化、`enableEdgeToEdge()` 適用
- Pi API 3.10（FT4 TX 周期検出改善、Direwolf / aplay クラッシュ対応）

### v3.05（2026-09-20）
**新機能**
- FT8 / FT4 ローカルデコード対応（CI-V モード）
  - ft8_lib（NDK JNI）による端末上オンデバイスデコード
  - Wi-Fi CI-V: RS-BA1 音声ストリーム（8kHz）入力
  - Bluetooth CI-V: BT SCO 音声（12kHz）入力
- FT8 画面にリアルタイムウォーターフォール表示追加（`WaterfallView.kt`）
- CI-V Bluetooth 接続対応（`CivBtService.kt`）
  - IC-705 等へ Bluetooth RFCOMM（SPP）で直接接続
  - `CivBtAudio.kt` で BT SCO 受信音声をスピーカー再生
- メモリ/スポット呼び出しダイアログ（`MemoryDialogFragment.kt`）
  - プリセット周波数・ユーザーメモリ・POTAスポット・SOTAスポット（4タブ）
- APRS 受信局リスト画面（`AprsReceivedFragment.kt`）
  - 受信ステーション一覧、距離・方位コンパス表示（`CompassView.kt`）
- SP2ALERT 連携（`Sp2alertReceiver.kt`）
  - POTA/SOTA スポット通知アプリから Intent で周波数・モードを自動設定
- POTA ハンターログ取得（`PotaHuntLogActivity.kt`）
  - pota.app ログイン経由でハンター履歴を取得・表示
- アプリ内ヘルプ画面（`HelpActivity.kt`、`help.html` / `help_ja.html`）

### v3.04
**修正**
- PTT 音声送信（TX）中に発生していた Broken Pipe エラーを修正（Pi 側 audio_tx ストリームの安定性向上）
- SOTA スポット画面で同一局・同一サミットの重複エントリが表示される問題を修正（最新スポットのみ表示）

**Pi API 更新**
- Admin →「Update Pi」で Raspberry Pi 側を更新してください

### v3.00（2026-09-11）
**変更（破壊的）**
- FT8/FT4 デコードをサーバーサイド（Pi 上の `jt9`）に移行
  - WebFT8（WebView + WASM）を廃止
  - WebFT8 HTTPS サーバー（ポート 8443）を廃止
  - `LocalPiProxy.kt`（SSL プロキシ）を削除
  - `androidx.webkit` 依存を削除

**新機能**
- FT8 デコード結果を SSE でリアルタイム配信（`/ft8/rx_msgs`）
- ネイティブ RecyclerView UI（暗色テーマ、モノスペースフォント）
- メッセージタップで TX 欄に自動入力（CQ→応答、レポート→RR73、RR73→73）
- CQ のみ表示フィルター（SwitchCompat）
- QSO ログ（ADIF エクスポート）
- `create_api.sh` で jt9（wsjtx パッケージ）を自動インストール
- 旧 webft8 systemd サービスをアップグレード時に自動停止・無効化

### v2.17（2026-07-07）
**修正**
- PiP（縮小）モードから復帰した際にパネルボタンが上段4つしか表示されない問題を修正

### v2.16
**修正**
- RIG CONNECT 画面の「USE CI-V」ラベルから `[TEST]` 表記を削除

### v2.15
**修正**
- CI-V 接続時のコールサイン欄から「FT8」表記を削除

### v2.14
**新機能**
- IC-705 / IC-9700 への直接 Wi-Fi CI-V 接続対応

### v2.13
**改善**
- CW 打鍵時の SPK 音切れタイミング改善

**新機能**
- PiP（ピクチャー・イン・ピクチャー）対応
- エッジ・ツー・エッジ表示に正式対応

### v2.12
**新機能**
- Hamlib 4.7.2 ソースビルド対応
- Update 画面新設

### v2.03
**新機能**
- FT8/FT4 デコード（webft8 ベース）・マルチプロファイル対応

---

## ライセンス

使用ライブラリのライセンスは各ライブラリのドキュメントを参照してください。

### オープンソースクレジット

- **WSJT-X / jt9**: FT8/FT4 デコーダー — GPL-3.0-or-later
- **Hamlib / rigctld**: 無線機制御ライブラリ — LGPL-2.1
- **Direwolf**: AX.25/APRS モデム — GPL-2.0
