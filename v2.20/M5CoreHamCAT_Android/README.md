# Wifi RIG CTRL for Android — v2.20

Android スマートフォンからアマチュア無線機をWi-Fi経由でリモート操作するコントローラーアプリ。

> **English summary** — Wifi RIG CTRL is an Android controller app for amateur (ham) radio operators. It connects to a transceiver over Wi-Fi via a Raspberry Pi (Hamlib/FastAPI) or directly to an IC-705 / IC-9700 (CI-V). Features: RX audio, PTT/mic TX, CW decode & send, USB/BLE CW keyer, FT8/FT4, APRS beacon (DireWolf + Rig modem AP96/AP12), APRS receive with Mic-E decode, Picture-in-Picture, multiple profiles.

---

## 動作要件

本アプリ単体では動作しません。次のいずれかのハードウェアが必要です。

| 接続方式 | 必要なもの |
|---|---|
| **Raspberry Pi 経由** | Raspberry Pi Zero 2W 以上のスペック（Hamlib + FastAPI サーバー導入済み）＋ 対応無線機<br>※ Raspberry Pi 4 での動作実績あり |
| **直接CI-V接続** | Icom IC-705 または IC-9700（Android と同一Wi-Fiネットワーク） |

- Android 5.0 (API 21) 以上
- Wi-Fi接続必須
- Raspberry Pi セットアップ手順・サーバーファームウェア: https://github.com/ji1ore/M5CoreHamCAT

---

## 接続方式別 星取表

| 機能 | Raspberry Pi 経由 | CI-V 直接接続 |
|---|:---:|:---:|
| 周波数表示・変更 | ○ | ○ |
| モード表示・変更 | ○ | ○ |
| フィルタ幅変更 | ○ | ○ |
| Sメーター表示 | ○ | ○ |
| 送信出力（RF Power）変更 | ○ | ○ |
| スケルチ変更 | ○ | ○ |
| ブレークイン（BK-IN）ON/OFF | ○ | ○ |
| PTT ON/OFF | ○ | ○ |
| マイク音声 TX（PTT中ストリーミング） | ○ | × |
| 受信音声 RX（スピーカー再生） | ○ | × |
| CW テキスト送信 | ○ | × |
| CW デコード（受信音声） | ○ | × |
| USB CWキーヤー（DualKey USB直結） | ○ | × |
| BLE CWキーヤー（DualKey-BLE 等） | ○ | × |
| CQ リピート | ○ | × |
| ノイズリダクション（NR） | ○ | × |
| Wi-Fi PTT（M5Atom 等外部デバイス） | ○ | × |
| FT8 / FT4 | ○ | × |
| APRS ビーコン送信（DireWolf） | ○ | × |
| APRS ビーコン送信（リグ内蔵モデム AP96/AP12） | ○ | × |
| APRS 受信・局表示（Mic-E対応） | ○ | × |

○: 対応　×: 非対応

> iOS版と異なり、Android版のCI-V接続は周波数・モード・Sメーター・PTT・RF power・SQL・BK-INのみ対応。音声・CW送信・FT8・APRSはRaspberry Pi経由でのみ使用可能。

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
- FT8 / FT4 デコード・送信（Pi上のWSJT-XベースWebUIをWebViewで表示）
- TX前後の周波数・モード自動保存・復元

### APRS

#### 送信（DireWolf モード）
- APRSビーコン送信（GPS対応・手動座標入力も可）
- コールサイン・SSID・パス・シンボル・コメント設定
- 送信間隔・ボーレート（1200 / 9600）設定

#### 送信（リグ内蔵モデム モード）— v2.20 新機能
- FTX-1 等リグの内蔵APRSモデムを制御
- APRSボタンを押すたびに OFF → AP96（9600baud）→ AP12（1200baud）→ OFF とサイクル
- モデム選択（AUTO / MAIN / SUB）・AP96 / AP12 それぞれの周波数・ボーレート設定可能

#### 受信
- APRS受信局の一覧表示・距離・方位表示
- Mic-E 形式パケットのデコード対応（v2.20 改善）
  - 東経の正常表示（FTX-1 ファームウェアのエンコードバグ回避）
  - シンボルの正常表示

### 接続・認証
- 複数プロファイル対応（接続先ごとに保存・切替）
- API キー認証
- mDNS（.local ホスト名）対応
- WireGuard VPN 経由のリモートアクセス対応

### その他
- ピクチャー・イン・ピクチャー（PiP）対応（TX中・CW打鍵中にホームボタンで小画面移行）
- 管理画面（Piサーバーファームウェア・CWブリッジ・Hamlib のアップデート、ビルドログ確認）
- NTPによる時刻同期（FT8精度向上）
- About画面で Pi API バージョン・Hamlib バージョン確認

---

## プロジェクト構成

```
M5CoreHamCAT_Android/
├── app/src/main/
│   ├── java/com/ji1ore/wifi_rig_ctrl/
│   │   ├── MainActivity.kt              # メインActivity・PiP制御
│   │   ├── SplashFragment.kt            # 起動画面（バージョン表示）
│   │   ├── ConnectFragment.kt           # 接続設定画面
│   │   ├── RigSelectFragment.kt         # リグ選択画面
│   │   ├── MainControlFragment.kt       # メインコントロール画面
│   │   ├── Ft8Fragment.kt               # FT8/FT4画面
│   │   ├── AprsSettingsFragment.kt      # APRS設定画面（DireWolf/Rigモード切替）
│   │   ├── AprsReceivedFragment.kt      # APRS受信局一覧
│   │   ├── PttSettingsFragment.kt       # PTT設定画面
│   │   ├── FreqInputFragment.kt         # 周波数入力ダイアログ
│   │   ├── UpdateFragment.kt            # 管理・アップデート画面
│   │   ├── AboutFragment.kt             # バージョン情報
│   │   ├── LocalPiProxy.kt              # FT8 WebView プロキシ
│   │   ├── data/
│   │   │   ├── RigApiService.kt         # Hamlib HTTP API クライアント
│   │   │   ├── CivTcpService.kt         # CI-V (RS-BA1互換) 直接接続
│   │   │   ├── AudioStreamService.kt    # 受信音声ストリーム
│   │   │   ├── AudioTxService.kt        # 送信音声ストリーム
│   │   │   ├── UdpPttService.kt         # Wi-Fi UDP PTT
│   │   │   ├── CwUsbService.kt          # USB CWキーヤー (CDC)
│   │   │   ├── CwBleService.kt          # BLE CWキーヤー (Nordic UART)
│   │   │   ├── CwDecoder.kt             # 受信音声→モールス符号デコーダ
│   │   │   ├── CwKeyDecoder.kt          # キー状態→文字デコーダ
│   │   │   ├── NtpClient.kt             # NTP時刻同期
│   │   │   ├── ProfileConfig.kt         # プロファイル定義・永続化
│   │   │   ├── AppPrefs.kt              # SharedPreferences ラッパー
│   │   │   └── Models.kt                # データモデル・定数
│   │   └── viewmodel/
│   │       └── MainViewModel.kt         # メインViewModel（全状態管理）
│   └── res/layout/                      # XMLレイアウト
├── Wifi_RIG_CTRL_v2.20.apk              # 署名済みAPK
└── README.md
```

---

## 接続フロー

```
Android
  │
  ├─[Raspberry Pi モード]── HTTP/UDP ──► Raspberry Pi Zero 2W
  │                                          │
  │                                     Hamlib/rigctld
  │                                     FastAPI サーバー
  │                                          │
  │                                       無線機 (CAT)
  │
  ├─[CI-V 直接モード]── TCP (RS-BA1互換) ──► IC-705 / IC-9700
  │                          Port 50001
  │
  ├─[USB CWキーヤー]── USB CDC (OTGケーブル) ──► DualKey (M5AtomS3)
  │                                                    │
  │                                              UDP ──► Raspberry Pi CW Bridge
  │
  └─[BLE CWキーヤー]── Bluetooth LE (Nordic UART) ──► DualKey-BLE / RemoteKeyer-BLE
                                                            │
                                                      UDP ──► Raspberry Pi CW Bridge
```

---

## インストール

APK を GitHub からダウンロードしてインストールしてください。

```
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.20/M5CoreHamCAT_Android
```

1. `Wifi_RIG_CTRL_v2.20.apk` をダウンロード
2. Android の設定から「提供元不明のアプリ」を許可
3. APK をタップしてインストール

---

## ビルド方法

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
```

署名済み APK: `app/build/outputs/apk/release/app-release.apk`

---

## 通信ポート一覧

| 用途 | プロトコル | デフォルトポート |
|---|---|---|
| Hamlib FastAPI | HTTP | 8000 |
| 受信音声ストリーム | HTTP (chunked) | 50000 |
| Wi-Fi PTT (M5Atom) | UDP | 8888 |
| CW Bridge (USB/BLE中継) | UDP | 8889 |
| FT8 WebUI (HTTPS) | HTTPS | 8443 |
| CI-V 制御 (IC-705) | TCP | 50001 |

---

## Raspberry Pi セットアップ

https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.20/RaspberryPiSetup

| アップグレード元 | 方法 |
|---|---|
| v2.03 以降 | アプリの「Update」→「Update Pi」ボタンで自動更新 |
| v2.12 以降（Hamlib 4.7.2） | アプリの「Update」→「Update Hamlib」ボタン（Pi Zero で 30〜60 分） |
| v2.02 以前 | 初回のみ手動 scp が必要（下記参照） |

```bash
scp api.py <username>@raspizero:~/fastapi/api.py
ssh <username>@raspizero "sudo systemctl restart fastapi"
```

---

## プライバシー

- 広告・使用状況のトラッキング: **なし**
- 外部サーバーへのデータ送信: **なし**（ユーザーが設定した Raspberry Pi / 無線機のみ通信）
- 位置情報: APRSビーコン送信時のみ使用（ユーザーが明示的に許可した場合）
- マイク: PTT送信中にのみ使用（録音・保存なし）

---

## バージョン履歴

### v2.20（2026-07-24）
**新機能**
- APRS: リグ内蔵モデムモードを追加（FTX-1 等の内蔵APRSモデムをCAT制御）
  - APRSボタン: OFF → AP96（9600baud）→ AP12（1200baud）→ OFF とサイクル
  - TX Method 設定（DireWolf / Rig Modem）を APRS 設定画面に追加
  - モデム選択（AUTO / MAIN / SUB）・各プリセットの周波数・ボーレード設定

**修正**
- APRS 受信: Mic-E パケットの東経が西経として表示される問題を修正
  - FTX-1 ファームウェアが D6 に P-Y でなく通常数字を使うバグへの対処
- APRS 受信: シンボルが文字化けする問題を修正（バイトオフセット修正）
- M5Core2 / M5Core2 Tough / M5CoreS3SE 向けの M5 ファームウェアを v2.20 に更新

### v2.18（2026-07-10）
**その他**
- versionCode を 51 に更新（Google Play 公開バージョン整合）
- Pi 側スクリプト変更なし（v2.17 と同一）

### v2.17（2026-07-07）
**修正・改善**
- IC-705 Wifi CI-V 接続の信頼性向上
- PiP（縮小）モードから復帰した際のレイアウト修正

### v2.16〜v2.14
- CI-V 直接接続の正式対応・ラベル修正

### v2.13
- PiP 対応・エッジ・ツー・エッジ表示

### v2.12
- Hamlib 4.7.2 対応・Update 管理画面

### v2.11
- BLE CWキーヤー対応（DualKey-BLE / RemoteKeyer-BLE）

### v2.10 以前
- CW デコード・送信・FT8・APRS 送信（各バージョンで段階的に追加）

---

## ライセンス

使用ライブラリのライセンスは各ライブラリのドキュメントを参照してください。
