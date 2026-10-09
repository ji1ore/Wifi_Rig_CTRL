# Wifi RIG CTRL for Android — v3.05

Android スマートフォンからアマチュア無線機をWi-Fi経由でリモート操作するコントローラーアプリ。

> **English summary** — Wifi RIG CTRL is an Android controller app for amateur (ham) radio operators. It connects to a transceiver over Wi-Fi via a Raspberry Pi (Hamlib/FastAPI) or directly to an IC-705 / IC-9700 (CI-V). Features: RX audio, PTT/mic TX, CW decode & send, USB/BLE CW keyer, FT8/FT4 server-side decode via jt9, APRS beacon, Picture-in-Picture, multiple profiles.

---

## 動作要件

本アプリ単体では動作しません。次のいずれかのハードウェアが必要です。

| 接続方式 | 必要なもの |
|---|---|
| **Raspberry Pi 経由** | Raspberry Pi Zero 2W 以上のスペック（Hamlib + FastAPI サーバー導入済み）＋ 対応無線機<br>※ FT8デコードには Raspberry Pi 4 以上推奨（Pi 5 1GB も可） |
| **直接CI-V接続** | Icom IC-705 または IC-9700（Android と同一Wi-Fiネットワーク） |

- Android 8.0 (API 26) 以上
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
Wifi_RIG_CTRL_ForAndroid_3.05/
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

1. `Wifi_RIG_CTRL_v3.00.apk` を Android デバイスに転送
2. Android の設定から「提供元不明のアプリ」を許可
3. APK をタップしてインストール

### Raspberry Pi サーバー

#### 新規セットアップ

Pi に SSH 接続後:

```bash
# 1. jt9 (WSJT-X) のインストール
sudo apt-get update
sudo apt-get install -y wsjtx
which jt9   # → /usr/bin/jt9 であることを確認

# 2. FastAPI サーバーの初期セットアップ（既存の手順と同じ）
#    アプリの「Update」→「Update Pi」ボタンで自動実行される
```

#### v2.xx からのアップグレード

1. Android アプリを v3.00 にアップデート
2. アプリ内 「Update」→「Update Pi」ボタン をタップ
   - `api.py` v3.00 が Pi に送信される
   - `create_api.sh` が実行され `jt9`（wsjtx パッケージ）が自動インストールされる
   - Pi が再起動する

または手動で:

```bash
# Pi 上で実行
sudo apt-get install -y wsjtx
# api.py を更新後
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

| アップグレード元 | 方法 |
|---|---|
| v2.03 以降 | アプリの「Update」→「Update Pi」ボタンで自動更新（jt9 も自動インストール） |
| v2.02 以前 | 初回のみ手動 SSH が必要 |

```bash
# 手動更新の場合
scp api.py <username>@raspberrypi:~/fastapi/api.py
ssh <username>@raspberrypi "sudo apt-get install -y wsjtx && sudo systemctl restart fastapi"
```

---

## プライバシー

- 広告・使用状況のトラッキング: **なし**
- 外部サーバーへのデータ送信: **なし**（ユーザーが設定した Raspberry Pi / 無線機のみ通信）
- 位置情報: APRSビーコン送信時・FT8グリッドロケーター取得時のみ使用（ユーザーが明示的に許可した場合）
- マイク: PTT送信中にのみ使用（録音・保存なし）

---

## バージョン履歴

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
