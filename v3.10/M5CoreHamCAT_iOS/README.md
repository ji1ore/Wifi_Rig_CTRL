# WifiRigCTRL (iOS) — v3.05

iPhone / iPad からアマチュア無線機をWi-Fi経由でリモート操作するコントローラーアプリ。

> **English summary** — WifiRigCTRL is an iOS controller app for amateur (ham) radio operators. It connects to a transceiver over Wi-Fi via a Raspberry Pi (Hamlib/FastAPI) or directly to an IC-705 / IC-9700 (CI-V). Features: RX audio, PTT/mic TX, CW decode & send, BLE CW keyer, USB-NCM CW relay, FT8/FT4 local decode (CI-V mode), APRS beacon, POTA/SOTA spot recall, multiple profiles.

---

## 動作要件

本アプリ単体では動作しません。次のいずれかのハードウェアが必要です。

| 接続方式 | 必要なもの |
|---|---|
| **Raspberry Pi 経由** | Raspberry Pi Zero 2W 以上のスペック（Hamlib + FastAPI サーバー導入済み）＋ 対応無線機<br>※ FT8デコードには Raspberry Pi 4 以上推奨 |
| **CI-V 直接接続（Wi-Fi）** | Icom IC-705 または IC-9700（iPhone と同一Wi-Fiネットワーク） |
| **CI-V 直接接続（Bluetooth）** | Icom IC-705（Bluetooth接続対応）|

- iOS 17.0 以上
- iPhone / iPad（Wi-Fi接続必須）
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
| マイク音声 TX（PTT中ストリーミング） | ○ | ○ |
| 受信音声 RX（スピーカー再生） | ○ | ○ |
| CW テキスト送信 | ○ | △ ※1 |
| CW デコード（受信音声） | ○ | ○ |
| BLE CWキーヤー（DualKey-BLE 等） | ○ | △ ※2 |
| USB-NCM CWキーヤー（AtomS3 Lite） | ○ | × |
| CQ リピート | ○ | △ ※2 |
| ノイズリダクション（NR） | ○ | × |
| Wi-Fi PTT（M5Atom 等外部デバイス） | ○ | × |
| FT8 / FT4（サーバーサイドデコード） | ○ | × |
| FT8 / FT4（ローカルデコード ※3） | × | ○ |
| APRS ビーコン送信 | ○ | × |

○: 対応　△: 一部対応（制限あり）　×: 非対応

**※1** CI-V 接続時の CW 送信方式はリグの BK-IN 状態により自動選択：
- BK-IN ON → IC-705 内蔵キーヤーに文字列を直接送信
- BK-IN OFF → PTT per element（符号要素ごとに PTT 制御）

**※2** CI-V 接続時の BLE キーヤーも上記 ※1 と同じ方式で無線機を制御。FM モードの場合は PTT per element に自動切替。

**※3** CI-V モード時に iPhone 上の ft8lib（C ライブラリ）でオンデバイスデコード。Pi 不要。

---

## 主な機能

### 基本制御
- 受信周波数・モード・Sメーターのリアルタイム表示（200ms ポーリング）
- 周波数変更（ステップ: 1Hz / 10Hz / 100Hz / 500Hz / 1kHz / 5kHz / 10kHz / 20kHz）
- モード・送信出力・スケルチ・フィルタ幅の変更
- ノイズリダクション（レベル 0〜5）

### 音声
- 受信音声のスピーカー再生（サンプリングレート選択可）
- PTT ON/OFF ＋ マイク音声のリアルタイム送信
- Wi-Fi PTT（M5Atom 等の外部デバイス経由）
- CI-V 直接接続時も受信音声・マイク送信に対応（RS-BA1互換プロトコル）

### CW
- CW デコード（受信音声から最大5局同時・リアルタイム表示）
- CW 送信（テキスト入力、WPM 調整、プリセットメッセージ）
- CQ リピート（回数・インターバル設定）
- ローカルサイドトーン生成（Pi側送信と独立したタイミング管理）
- **BLE CWキーヤー対応**（DualKey-BLE / RemoteKeyer-BLE）
- **USB-NCM CWキーヤー中継**（AtomS3 Lite を iPhone に USB で直結し Pi ブリッジへ中継）
- FM-CW モード（FM時はPTT per element に自動切替）
- CI-V 接続時は IC-705 内蔵キーヤーまたは PTT-per-element で送信

### FT8 / FT4
- **サーバーサイドデコード（Raspberry Pi モード）**: Pi 上の `jt9`（WSJT-X）が15秒ごとにデコード、SSE でリアルタイム配信
- **ローカルデコード（CI-V モード）**: ft8lib（C ライブラリ）による iPhone 上でのオンデバイスデコード（Pi 不要）
- FT8 メッセージタップで TX 欄に自動入力（CQ→応答、レポート→RR73、RR73→73）
- QSO ログ（ADIF エクスポート対応）
- TX前後の周波数・モード自動保存・復元

### APRS
- APRSビーコン送信（GPS対応・手動座標入力も可）
- コールサイン・SSID・パス・シンボル・コメント設定
- 送信間隔・ボーレート（1200 / 9600）設定

### POTA / SOTA 連携
- **メモリ/スポットダイアログ**: POTAスポット・SOTAスポットをリスト表示し、タップで周波数・モードを自動設定
- **POTA ハンターログ取得**: pota.app へのログインを仲介し、ハンターの活性化履歴を取得
- **SP2ALART 連携**: POTA/SOTA スポット通知アプリから URL Scheme または Intent で周波数・モードを自動設定

### 接続
- 複数プロファイル対応（接続先ごとに保存・切替）
- API キー認証
- mDNS（.local ホスト名）対応
- WireGuard VPN 経由のリモートアクセス対応
- ネットワーク自動検索（UDP ブロードキャストで同一ネットワーク内の Pi を自動発見）

### その他
- 管理画面（Piサーバーファームウェア・CWブリッジ・セットアップスクリプトのアップロード）
- デモモード（ハードウェアなしで全画面を確認可能）
- 画面常時点灯オプション
- 日本語 / 英語 対応（ローカライズ済み）

---

## プロジェクト構成

```
WifiRigCTRL_iOS _3.05/
├── WifiRigCTRL/
│   ├── WifiRigCTRLApp.swift          # アプリエントリポイント
│   ├── ContentView.swift             # ルートナビゲーション
│   ├── SplashView.swift              # 起動画面
│   ├── ConnectView.swift             # 接続設定画面（ネットワーク自動検索付き）
│   ├── RigSelectView.swift           # リグ選択画面
│   ├── MainControlView.swift         # メインコントロール画面（POTAスポット呼び出し含む）
│   ├── CwView.swift                  # CW送信画面
│   ├── Ft8View.swift                 # FT8/FT4画面（SSE + ローカルエンジン切替）
│   ├── Ft8LocalEngine.swift          # FT8ローカルエンジン（CI-Vモード、ft8lib使用）
│   ├── AprsSettingsView.swift        # APRS設定画面
│   ├── BleKeyerView.swift            # BLE CWキーヤー設定
│   ├── UsbRelayView.swift            # USB-NCM CW中継設定画面
│   ├── PttSettingsView.swift         # PTT設定画面
│   ├── ProfileListView.swift         # プロファイル管理
│   ├── AdminView.swift               # 管理画面
│   ├── AboutView.swift               # バージョン情報
│   ├── PotaHuntLogView.swift         # POTAハンターログ取得画面
│   ├── MainViewModel.swift           # メインViewModel（全状態管理）
│   ├── RigConnection.swift           # Hamlib HTTP API クライアント
│   ├── CivService.swift              # CI-V (RS-BA1互換) 直接接続
│   ├── AudioRxService.swift          # 受信音声ストリーム
│   ├── AudioTxService.swift          # 送信音声ストリーム
│   ├── PttService.swift              # Wi-Fi UDP PTT
│   ├── CwBleService.swift            # BLE CWキーヤー (Nordic UART)
│   ├── UsbRelayService.swift         # USB-NCM CW中継（AtomS3 Lite Client⇔Pi）
│   ├── CwAudioStream.swift           # CW 700Hz サイドトーンストリーム
│   ├── CwDecoder.swift               # 受信音声→モールス符号デコーダ
│   ├── HunterStore.swift             # POTAハンターデータ永続化
│   ├── LocationService.swift         # GPS位置情報
│   ├── PiProxySchemeHandler.swift    # FT8 WebView プロキシ
│   ├── NetworkDiscovery.swift        # UDP ブロードキャストによる Pi 自動検索
│   ├── ProfileConfig.swift           # プロファイル定義・永続化
│   ├── Models.swift                  # データモデル・定数
│   ├── WifiRigCTRL-Bridging-Header.h # ft8lib Objective-C ブリッジヘッダ
│   ├── ft8lib/                       # FT8デコードCライブラリ（ft8_lib ベース）
│   └── Localizable.xcstrings         # 多言語リソース（日本語・英語）
├── AppStore_Listing_Draft.md         # App Store 掲載情報下書き
├── LICENSES.md                       # 使用ライブラリのライセンス
└── PRIVACY_POLICY_iOS.md             # プライバシーポリシー
```

---

## 接続フロー

```
iPhone
  │
  ├─[Raspberry Pi モード]── HTTP/UDP ──► Raspberry Pi Zero 2W
  │                                          │
  │                                     Hamlib/rigctld
  │                                     FastAPI サーバー v3.04
  │                                          │
  │                                     jt9 (WSJT-X)  ← 15秒ごとにFT8デコード
  │                                          │
  │                                       無線機 (CAT)
  │    FT8デコード結果 ──SSE──► iPhone (Ft8View)
  │
  ├─[CI-V 直接モード]── TCP (RS-BA1互換) ──► IC-705 / IC-9700
  │                          Port 50001/50002/50003
  │                               │ RX音声/TX音声/制御
  │                               └─► FT8ローカルエンジン（ft8lib）
  │
  ├─[BLE CWキーヤー]── Bluetooth LE (Nordic UART) ──► DualKey-BLE / RemoteKeyer-BLE
  │                                                        │
  │                                                   UDP ──► Raspberry Pi CW Bridge
  │
  └─[USB-NCM CWキーヤー中継]── USB-NCM (UDP 8888) ──► AtomS3 Lite (Client FW)
                                     │
                               WiFi (UDP 8889) ──► Raspberry Pi cw_bridge_ncm.py
                                                        │
                                                   NCM (UDP 8888) ──► AtomS3 (Server FW)
```

---

## ビルド方法

1. Xcode 15 以上で `WifiRigCTRL_iOS.xcodeproj` を開く
2. Signing & Capabilities で開発者アカウントを設定
3. ターゲットデバイスを iPhone / iPad に設定してビルド

外部フレームワークへの依存なし（Swift Package Manager / CocoaPods 不使用）。  
ft8lib は C ライブラリとして `ft8lib/` に同梱、`WifiRigCTRL-Bridging-Header.h` 経由でブリッジ。

---

## 通信ポート一覧

| 用途 | プロトコル | デフォルトポート |
|---|---|---|
| Hamlib FastAPI | HTTP | 8000 |
| 受信音声ストリーム | HTTP (chunked) | 50000 |
| Wi-Fi PTT (M5Atom) | UDP | 8888 |
| CW Bridge (BLE中継) | UDP | 8889 |
| CI-V 制御 (IC-705) | TCP | 50001 |
| CI-V 音声 TX | TCP | 50002 |
| CI-V 音声 RX | TCP | 50003 |
| USB-NCM (AtomS3 Client) | UDP | 8888 (NCM NIF) |

> **v3.00 変更点**: WebFT8 HTTPS サーバー（ポート 8443）を廃止。FT8デコード結果は FastAPI（ポート 8000）の SSE エンドポイント `/ft8/rx_msgs` で配信。

---

## プライバシー

- 広告・使用状況のトラッキング: **なし**
- 外部サーバーへのデータ送信: **なし**（ユーザーが設定した Raspberry Pi / 無線機のみ通信）
- 位置情報: APRSビーコン送信時のみ使用（ユーザーが明示的に許可した場合）
- マイク: PTT送信中にのみ使用（録音・保存なし）

詳細は [PRIVACY_POLICY_iOS.md](PRIVACY_POLICY_iOS.md) を参照。

---

## 関連リポジトリ・リンク

- **Raspberry Pi サーバー / ファームウェア**: https://github.com/ji1ore/M5CoreHamCAT
- **Android版**: `Wifi_RIG_CTRL_ForAndroid_3.05`（本アプリのAndroid対応版）

---

## Wifi_Rig_CW との関係

**Wifi_Rig_CW** は、M5Stack デバイスに書き込む CW キーヤーのファームウェアプロジェクトです。  
本アプリはこのファームウェアが動作するデバイスと **USB-NCM または Bluetooth LE** で接続し、CW キー信号を中継します。

### 対応デバイスとファームウェア

| デバイス名 | ハードウェア | 接続方式 | ファームウェア |
|---|---|---|---|
| **DualKey-BLE** | M5AtomS3 (AtomS3) | Bluetooth LE | Wifi_Rig_CW_DUALKEY v1.43 |
| **RemoteKeyer-BLE** | M5StackCore 等 | Bluetooth LE | Remotekeyer_M5Stack_Server v1.43 |
| **AtomS3 Lite Client** | M5AtomS3 Lite | USB-NCM | Wifi_Rig_CW（Client FW） |

### 動作の流れ（BLE）

```
パドル / 電鍵
      │
      ▼
DualKey-BLE / RemoteKeyer-BLE (M5Stack)
      │  Bluetooth LE (Nordic UART Service)
      ▼
WifiRigCTRL (iPhone)   ←── 本アプリ
      │  UDP (ポート 8889)
      ▼
Raspberry Pi (cw_bridge.py)
      │  CAT / シリアル
      ▼
無線機
```

### 動作の流れ（USB-NCM）

```
パドル / 電鍵
      │
      ▼
AtomS3 Lite (Client FW)
      │  USB-NCM / UDP (ポート 8888)
      ▼
WifiRigCTRL (iPhone)   ←── 本アプリ (UsbRelayService)
      │  Wi-Fi UDP (ポート 8889)
      ▼
Raspberry Pi (cw_bridge_ncm.py)
      │  NCM / UDP
      ▼
AtomS3 (Server FW)
      │  CAT / シリアル
      ▼
無線機
```

iOS は USB CDC 非対応のため USB-NCM（Ethernet over USB）を使用。  
Android 版は USB CDC 直結（DualKey USB モード）も対応。

---

## バージョン履歴

### v3.05（2026-09-20）
- Android v3.05 に合わせたバージョン番号の統一
- api.py v3.04 同梱（PTT Broken Pipe 修正済み）

### v3.04
**修正**
- SOTA スポット画面で同一局・同一サミットの重複エントリが表示される問題を修正（最新スポットのみ表示）
- PTT 音声送信（TX）中に発生していた Broken Pipe エラーを修正（api.py OSError キャッチ）

**Pi API 更新（v3.02 → v3.04）**
- Admin →「Update Pi」で Raspberry Pi 側を更新してください

### v3.01
**新機能**
- FT8 / FT4 ローカルデコード対応（CI-V モード）
  - ft8lib（C ライブラリ）を Objective-C ブリッジ経由で使用
  - CI-V 接続時、Pi 不要で iPhone 上でオンデバイスデコード
- POTA ハンターログ取得画面（pota.app 連携）
- POTA / SOTA スポット呼び出し機能（メモリダイアログ）

### v3.00
**変更（破壊的）**
- FT8/FT4 をサーバーサイドデコード（Pi 上の `jt9`）に移行
  - WebFT8（WebView + WASM）を廃止
  - WebFT8 HTTPS サーバー（ポート 8443）を廃止

**新機能**
- USB-NCM CWキーヤー中継（`UsbRelayService.swift`）
  - AtomS3 Lite Client を iPhone に USB-NCM で接続し、CW 信号を Pi ブリッジへ中継
- FT8 デコード結果を SSE でリアルタイム配信（`/ft8/rx_msgs`）
- ネットワーク自動検索ボタン（接続設定画面）

---

## ライセンス

使用ライブラリのライセンスは [LICENSES.md](LICENSES.md) を参照。
