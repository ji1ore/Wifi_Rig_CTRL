# Wifi RIG CTRL for Android — v2.18

Android スマートフォンからアマチュア無線機をWi-Fi経由でリモート操作するコントローラーアプリ。

> **English summary** — Wifi RIG CTRL is an Android controller app for amateur (ham) radio operators. It connects to a transceiver over Wi-Fi via a Raspberry Pi (Hamlib/FastAPI) or directly to an IC-705 / IC-9700 (CI-V). Features: RX audio, PTT/mic TX, CW decode & send, USB/BLE CW keyer, FT8/FT4, APRS beacon, Picture-in-Picture, multiple profiles.

---

## 動作要件

本アプリ単体では動作しません。次のいずれかのハードウェアが必要です。

| 接続方式 | 必要なもの |
|---|---|
| **Raspberry Pi 経由** | Raspberry Pi Zero 2W 以上のスペック（Hamlib + FastAPI サーバー導入済み）＋ 対応無線機<br>※ Raspberry Pi 4 での動作実績あり |
| **直接CI-V接続** | Icom IC-705 または IC-9700（Android と同一Wi-Fiネットワーク） |

- Android 8.0 (API 26) 以上
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
| APRS ビーコン送信 | ○ | × |

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
- APRSビーコン送信（GPS対応・手動座標入力も可）
- コールサイン・SSID・パス・シンボル・コメント設定
- 送信間隔・ボーレート（1200 / 9600）設定

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

## Wifi_Rig_CW との関係

**Wifi_Rig_CW** は、M5Stack デバイスに書き込む CW キーヤーのファームウェアプロジェクトです。  
本アプリはこのファームウェアが動作するデバイスと **USB（CDC）または Bluetooth LE** で接続し、CW キー信号を中継します。

### 対応デバイスとファームウェア

| デバイス名 | ハードウェア | 接続方式 | ファームウェア |
|---|---|---|---|
| **DualKey** | M5AtomS3 (AtomS3) | USB CDC（OTGケーブル） | Wifi_Rig_CW_DUALKEY v1.43 |
| **DualKey-BLE** | M5AtomS3 (AtomS3) | Bluetooth LE | Wifi_Rig_CW_DUALKEY v1.43 |
| **RemoteKeyer-BLE** | M5StackCore 等 | Bluetooth LE | Remotekeyer_M5Stack_Server v1.43 |

> **DualKey は USB/BLE 自動切替に対応**  
> 電源投入後10秒間: 左パドル（DAH）→ USB CDCモード / 右パドル（DIT）→ BLEモード（デフォルト）  
> BLEモード中にUSBアプリデータを受信 → 自動でUSB CDCモードへ再起動  
> USB CDCモード中にUSB切断 → 自動でBLEモードへ再起動

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

### 機能分担

- **Wifi_Rig_CW ファームウェア**: パドル/電鍵のキー状態を検出し、USB または BLE で Android へ送信
- **本アプリ**:
  - USB/BLE で受信したキー状態を Raspberry Pi へ UDP 中継（CW モード）
  - FM モード時はキー状態を PTT on/off + PCMトーンストリーミングに変換（FM-CW）
  - ローカルサイドトーン再生（200ms バッファ、低レイテンシ）
- **Raspberry Pi (cw_bridge.py)**: UDP で受信したキー状態を無線機の CW キーイングに変換

### iOS版との違い

iOS版は **USB CDC 非対応**のため BLE 接続のみ。Android版は **USB CDC と BLE の両方**に対応しています。

---

## プロジェクト構成

```
M5CoreHamCAT_Android/
├── app/src/main/
│   ├── java/com/example/wifi_rig_ctrl/
│   │   ├── MainActivity.kt              # メインActivity・PiP制御
│   │   ├── SplashFragment.kt            # 起動画面
│   │   ├── ConnectFragment.kt           # 接続設定画面
│   │   ├── RigSelectFragment.kt         # リグ選択画面
│   │   ├── MainControlFragment.kt       # メインコントロール画面
│   │   ├── Ft8Fragment.kt               # FT8/FT4画面
│   │   ├── AprsSettingsFragment.kt      # APRS設定画面
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
├── Wifi_RIG_CTRL_v2.18.apk              # 署名済みAPK
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
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.18/M5CoreHamCAT_Android
```

1. `Wifi_RIG_CTRL_v2.18.apk` をダウンロード
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

M5Core 版と同じ手順でセットアップしてください。  
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.18/RaspberryPiSetup

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

## 関連リポジトリ・リンク

- **Raspberry Pi サーバー / ファームウェア**: https://github.com/ji1ore/M5CoreHamCAT
- **iOS版**: `M5CoreHamCAT_iOS`（本アプリのiOS対応版）

---

## バージョン履歴

### v2.18（2026-07-10）
**その他**
- versionCode を 51 に更新（Google Play 公開バージョン整合）
- Pi 側スクリプト変更なし（v2.17 と同一）

### v2.17（2026-07-07）
**修正・改善**
- IC-705 Wifi CI-V 接続の信頼性向上
  - エフェメラルポート（0）を使用して接続のたびに異なる ctrlMyId を生成
  - IC-705 が古いセッションを再利用してしまう問題を解消（iOS 動作に合わせて修正）
  - IAH 未受信時でも ping からの civRemoteId 学習済みなら接続を継続
- PiP（縮小）モードから復帰した際にパネルボタンが上段4つしか表示されない問題を修正
  - `onPictureInPictureModeChanged` の `requestLayout()` を `post{}` 内に移動し、ウィンドウ復元後に GridLayout を再計算
- Pi 側スクリプト変更なし（v2.16 と同一）

### v2.16
**修正**
- RIG CONNECT 画面の「USE CI-V」ラベルから `[TEST]` 表記を削除（CI-V を正式機能として扱い）

### v2.15
**修正**
- CI-V 接続時のコールサイン欄から「FT8」表記を削除（CI-V モードでは FT8 不可）

### v2.14
**新機能**
- IC-705 / IC-9700 への直接 Wi-Fi CI-V 接続対応（Raspberry Pi 不要）
  - 対応機能: 周波数・モード・Sメーター・PTT・RF power・SQL・BK-IN
  - 非対応（Pi モードのみ）: 音声ストリーミング・CW 送信・FT8・APRS

### v2.13
**改善**
- CW 打鍵時の SPK 音切れタイミング改善（AudioTrack バッファ ~2秒 → ~200ms）

**新機能**
- ピクチャー・イン・ピクチャー（PiP）対応（TX中・CW打鍵中にホームで小画面移行）
- エッジ・ツー・エッジ表示に正式対応（Google Play ポリシー準拠）

### v2.12
**新機能**
- Hamlib 4.7.2 ソースビルド対応（`~/.local/bin/rigctld` にインストール）
- Update 画面を新設（Update Pi / Update Hamlib / ログ確認を統合）
- About 画面に Pi API バージョン・Hamlib バージョンを表示

**修正**
- webFT8 画面でフィールド編集時に無線機の周波数が変わる問題を修正
- WID / POW / SQL を ◀▶ ボタンで増減可能に

### v2.11
**新機能**
- BLE CWキーヤー対応（DualKey-BLE / RemoteKeyer-BLE、Nordic UART Service）
  - DualKey-BLE の USB CDC / BLE 自動切替機能

### v2.10
**改善**
- ノイズリダクション強化（afftdn ベース、5段階に拡張）
- Update Pi ボタンの信頼性向上（sudoers 生成バグ修正、待機時間延長）

### v2.09
**改善**
- CW デコード精度向上（dit/dah 境界・字間ギャップ判定改善、5bin エネルギー計算）

### v2.08
**修正**
- CW TX 開始ラグを修正（USB シリアル接続時の rigctld 再起動問題を解消）

### v2.07
**改善**
- USB シリアル接続時の PTT 自動最適化（ttyACM/ttyUSB で RTS → CAT 自動切替）

### v2.06
**修正**
- CW USB（DualKey）の Pi 未接続時の同期動作を修正

### v2.05
**修正**
- CW 送信が途中で切れる問題を修正（`set_morse_code_speed` を削除）
- CW TX 終了方式の選択機能を追加（時間予測モード / PTT ポーリングモード）
- CW TX 開始遅延を短縮（600ms → 100ms）

### v2.04
**修正**
- CW モード時の BK-IN 表示が常に OFF になっていた問題を修正

### v2.03
**新機能**
- FT8/FT4 デコード（webft8 ベース）・マルチプロファイル対応

### v2.02
**新機能**
- CW TX パネルを追加（プリセットボタン・WPM スライダー・フリーテキスト・FM-CW モード）

### v2.01
- FT8/FT4 機能を WebView ベースに刷新

### v2.00
- FT8/FT4 受信デコード・送信（実験的）・サンプリングレート選択・フィルター幅操作

### v1.50
- 多チャンネル CW デコーダー（最大5局同時、自動周波数追従）

### v1.40
- USB CW 中継モード（M5ATOM を Android に直結、CW / FM-CW サイドトーン対応）

---

## ライセンス

使用ライブラリのライセンスは各ライブラリのドキュメントを参照してください。
