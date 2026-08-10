# WifiRigCTRL (iOS) — v2.30

iPhone / iPad からアマチュア無線機をWi-Fi経由でリモート操作するコントローラーアプリ。

> **English summary** — WifiRigCTRL is an iOS controller app for amateur (ham) radio operators. It connects to a transceiver over Wi-Fi via a Raspberry Pi (Hamlib/FastAPI) or directly to an IC-705 / IC-9700 (CI-V). Features: RX audio, PTT/mic TX, CW decode & send, BLE CW keyer, FT8/FT4, APRS beacon, multiple profiles.

---

## v2.30 での変更点

**Pi側API更新**
Raspberry Pi 側 API（`api.py`）が接続中の機種が実際にサポートするモード一覧を
動的に検出するようになった（例: FT-991 の C4FM、IC-705 の D-STAR など、従来は
選べなかった機種固有のデジタルモードが Mode 選択に表示されるようになる）。
反映には Pi 側 API の更新が必要です。Admin 画面の「Update Pi」ボタンで自動更新
されます（Bundled api.py version は Admin 画面で確認可能）。

**修正（CI-V直結モード）**
D-STAR 選択時にリグへ USB (0x01) が誤って送信されていた不具合を修正しました
（`CivService.swift` の `setMode()` に D-STAR→0x17 の対応が抜けていました。
モード一覧には表示されるのに、実際に選択すると USB が送られていました）。

**新機能（アプリ）**
- ネットワーク検索ボタン追加（ラズパイ接続設定画面）
  - 「ネットワーク検索」ボタンをタップすると UDP ブロードキャスト（ポート 5001）で
    ネットワーク上の Pi を自動検索（Android 版と同一プロトコル）
  - 発見したデバイスをリスト表示し、タップするとホスト名欄に IP アドレスを自動入力
  - API Port / Audio Port は上書きしない（既存の設定を保持）
  - iOS 14 以降: 初回検索時にローカルネットワークへのアクセス許可を求めます

**修正（アプリ）**
- PTT 種別の表示を「RIG」→「CAT」に統一（Android 版・Pi 側の表記に合わせて修正）
- C4FM / D-STAR モード切替の安定性向上
  - C4FM 選択時のフィルタ幅を 0 に強制（rigctld がフィルタ幅非対応の機種でのエラーを回避）
  - 接続時のモード一覧取得（getCaps）でタイムアウト時に自動リトライ

---

## 動作要件

本アプリ単体では動作しません。次のいずれかのハードウェアが必要です。

| 接続方式 | 必要なもの |
|---|---|
| **Raspberry Pi 経由** | Raspberry Pi Zero 2W 以上のスペック（Hamlib + FastAPI サーバー導入済み）＋ 対応無線機<br>※ Raspberry Pi 4 での動作実績あり |
| **直接CI-V接続** | Icom IC-705 または IC-9700（iPhone と同一Wi-Fiネットワーク） |

- iOS 17.0 以上
- iPhone / iPad (Wi-Fi接続必須)
- Raspberry Pi セットアップ手順・サーバーファームウェア: https://github.com/ji1ore/M5CoreHamCAT

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

### CW
- CW デコード（受信音声から最大5局同時・リアルタイム表示）
- CW 送信（テキスト入力、WPM 調整、プリセットメッセージ）
- CQ リピート（回数・インターバル設定）
- ローカルサイドトーン生成（Pi側送信と独立したタイミング管理）
- BLE CWキーヤー対応（DualKey-BLE / RemoteKeyer-BLE）
- FM-CW モード（FM時はPTT per element に自動切替）
- CI-V 接続時は IC-705 内蔵キーヤーまたは PTT-per-element で送信

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
- 管理画面（Piサーバーファームウェア・CWブリッジ・セットアップスクリプトのアップロード）
- デモモード（ハードウェアなしで全画面を確認可能）
- 画面常時点灯オプション
- 日本語 / 英語 対応（ローカライズ済み）

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
| CQ リピート | ○ | △ ※2 |
| ノイズリダクション（NR） | ○ | × |
| Wi-Fi PTT（M5Atom 等外部デバイス） | ○ | × |
| FT8 / FT4 | ○ | × |
| APRS ビーコン送信 | ○ | × |

○: 対応　△: 一部対応（制限あり）　×: 非対応

**※1** CI-V 接続時の CW 送信方式はリグの BK-IN 状態により自動選択：
- BK-IN ON → IC-705 内蔵キーヤーに文字列を直接送信
- BK-IN OFF → PTT per element（符号要素ごとに PTT 制御）

**※2** CI-V 接続時の BLE キーヤーも上記 ※1 と同じ方式で無線機を制御。FM モードの場合は PTT per element に自動切替。

---

## プロジェクト構成

```
WifiRigCTRL_iOS _2.30/
├── WifiRigCTRL/
│   ├── WifiRigCTRLApp.swift      # アプリエントリポイント
│   ├── ContentView.swift         # ルートナビゲーション
│   ├── SplashView.swift          # 起動画面
│   ├── ConnectView.swift         # 接続設定画面
│   ├── RigSelectView.swift       # リグ選択画面
│   ├── MainControlView.swift     # メインコントロール画面
│   ├── CwView.swift              # CW送信画面
│   ├── Ft8View.swift             # FT8/FT4画面
│   ├── AprsSettingsView.swift    # APRS設定画面
│   ├── BleKeyerView.swift        # BLE CWキーヤー設定
│   ├── PttSettingsView.swift     # PTT設定画面
│   ├── ProfileListView.swift     # プロファイル管理
│   ├── AdminView.swift           # 管理画面
│   ├── AboutView.swift           # バージョン情報
│   ├── MainViewModel.swift       # メインViewModel（全状態管理）
│   ├── RigConnection.swift       # Hamlib HTTP API クライアント
│   ├── CivService.swift          # CI-V (RS-BA1互換) 直接接続
│   ├── AudioRxService.swift      # 受信音声ストリーム
│   ├── AudioTxService.swift      # 送信音声ストリーム
│   ├── PttService.swift          # Wi-Fi UDP PTT
│   ├── CwBleService.swift        # BLE CWキーヤー (Nordic UART)
│   ├── CwAudioStream.swift       # CW 700Hz サイドトーンストリーム
│   ├── CwDecoder.swift           # 受信音声→モールス符号デコーダ
│   ├── LocationService.swift     # GPS位置情報
│   ├── PiProxySchemeHandler.swift# FT8 WebView プロキシ
│   ├── NetworkDiscovery.swift    # UDP ブロードキャストによる Pi 自動検索
│   ├── ProfileConfig.swift       # プロファイル定義・永続化
│   ├── Models.swift              # データモデル・定数
│   └── Localizable.xcstrings     # 多言語リソース
├── AppStore_Listing_Draft.md     # App Store 掲載情報下書き
├── LICENSES.md                   # 使用ライブラリのライセンス
└── PRIVACY_POLICY_iOS.md         # プライバシーポリシー
```

---

## 接続フロー

```
iPhone
  │
  ├─[Raspberry Pi モード]── HTTP/UDP ──► Raspberry Pi Zero 2W
  │                                          │
  │                                     Hamlib/rigctld
  │                                     FastAPI サーバー
  │                                          │
  │                                       無線機 (CAT)
  │
  ├─[CI-V 直接モード]── TCP (RS-BA1互換) ──► IC-705 / IC-9700
  │                          Port 50001/50002/50003
  │
  └─[BLE CWキーヤー]── Bluetooth (Nordic UART) ──► DualKey-BLE / RemoteKeyer-BLE
                                                        │
                                                   UDP ──► Raspberry Pi CW Bridge
```

---

## ビルド方法

1. Xcode 15 以上で `WifiRigCTRL_iOS.xcodeproj` を開く
2. Signing & Capabilities で開発者アカウントを設定
3. ターゲットデバイスを iPhone / iPad に設定してビルド

外部ライブラリへの依存なし（Swift Package Manager / CocoaPods 不使用）。

---

## 通信ポート一覧

| 用途 | プロトコル | デフォルトポート |
|---|---|---|
| Hamlib FastAPI | HTTP | 8000 |
| 受信音声ストリーム | HTTP (chunked) | 50000 |
| Wi-Fi PTT (M5Atom) | UDP | 8888 |
| CW Bridge (BLE中継) | UDP | 8889 |
| FT8 WebUI (HTTPS) | HTTPS | 8443 |
| CI-V 制御 (IC-705) | TCP | 50001 |
| CI-V 音声 TX | TCP | 50002 |
| CI-V 音声 RX | TCP | 50003 |

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
- **Android版**: `Wifi_RIG_CTRL_ForAndroid_2.30`（本アプリのAndroid対応版）

---

## Wifi_Rig_CW との関係

**Wifi_Rig_CW** は、M5Stack デバイスに書き込む BLE CW キーヤーのファームウェアプロジェクトです。  
本アプリはこのファームウェアが動作するデバイスと Bluetooth (Nordic UART Service) で接続し、CW キー信号を中継します。

### 対応デバイスとファームウェア

| デバイス名 | ハードウェア | ファームウェア |
|---|---|---|
| **DualKey-BLE** | M5AtomS3 (AtomS3) | Wifi_Rig_CW_DUALKEY v1.43 |
| **RemoteKeyer-BLE** | M5StackCore 等 | Remotekeyer_M5Stack_Server v1.43 |

### 動作の流れ

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

### 機能分担

- **Wifi_Rig_CW ファームウェア**: パドル/電鍵のキー状態を検出し、BLE で iPhone へ送信
- **WifiRigCTRL (本アプリ)**:
  - BLE で受信したキー状態を Raspberry Pi へ UDP 中継（CW モード）
  - FM モード時はキー状態を PTT on/off に変換してトーン送信（FM-CW）
  - キー状態から送信文字をデコードして画面表示（TX CW デコード）
  - ローカルサイドトーン生成（700Hz、Pi 側送信とは独立したタイミング管理）
- **Raspberry Pi (cw_bridge.py)**: UDP で受信したキー状態を無線機の CW キーイングに変換

### Android 版との違い

Android 版（`Wifi_RIG_CTRL_ForAndroid`）は USB CDC 経由の直接接続（DualKey の USB モード）もサポートしていますが、iOS 版は USB CDC 非対応のため **BLE 接続のみ** 対応しています。

---

## ライセンス

使用ライブラリのライセンスは [LICENSES.md](LICENSES.md) を参照。
