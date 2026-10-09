# App Store 掲載情報下書き — WifiRigCTRL v2.51

---

## 基本情報

| 項目 | 内容 |
|---|---|
| アプリ名 | WifiRigCTRL |
| サブタイトル（30文字以内） | Ham Radio Remote Controller |
| カテゴリ | ユーティリティ |
| コンテンツレーティング | 4+ |
| 対応OS | iOS 17.0 以上 |
| 対応デバイス | iPhone / iPad |
| 広告 | なし |
| アプリ内課金 | なし |

---

## 日本語（ja）

### アプリ名
```
WifiRigCTRL
```

### サブタイトル
```
アマチュア無線機 Wi-Fiリモコン
```

### 説明（4000文字以内）

```
Raspberry Pi を中継サーバーとして、アマチュア無線機（リグ）を iPhone / iPad から
Wi-Fi 経由でリモート操作するアプリです。

【v2.51 の変更点】
・DireWolf設定修正: USBオーディオデバイスを.envから自動取得（固定値の不具合を修正）
・Pi APIを2.51に更新（Admin画面の「Update Pi」で更新してください）

【主な機能】

■ リグ制御
・周波数・モード・出力・スケルチ・フィルター幅をリアルタイムで変更
・Hamlib 対応リグ（YAESU、ICOM、Kenwood 等）をサポート
・複数接続プロファイルの保存・切替

■ IC-705 / IC-9700 Wifi CI-V 直接接続
・Raspberry Pi 不要で ICOM 無線機に直接接続
・「CI-V Direct」モードで周波数・モード・Sメーター・PTT・RF power・SQL・BK-IN に対応

■ 音声受信（SPK）
・無線機の受信音声をストリーミング再生
・ノイズリダクション（5段階）
・サンプリングレート選択（8k〜48kHz）

■ 音声送信・PTT
・マイク音声をリアルタイムで無線機から送信
・Hamlib HTTP PTT / WiFi UDP PTT（M5Atom）に対応

■ CW（モールス符号）
・受信音声からモールス符号をリアルタイムデコード（最大5チャンネル同時）
・CW テキスト送信（WPM 調整・プリセットメッセージ・フリーテキスト入力）
・BLE CW キーヤー対応（DualKey-BLE / RemoteKeyer-BLE）
・ローカルサイドトーン生成

■ FT8 / FT4
・webft8 を使ったリアルタイム受信デコード・送信

■ APRS
・DireWolf 経由でビーコン送信（GPS 対応・手動座標入力も可）
・受信局一覧表示（Mic-E 形式対応）

■ バンドメモリー MEM パネル
・Preset / User / POTA / SOTA の 4 タブ
・POTA・SOTA スポットをリアルタイム取得、タップで周波数・モードを一括設定

■ ネットワーク自動スキャン
・UDP ブロードキャストでネットワーク上の Pi を自動検出

■ その他
・WireGuard VPN 経由での外出先接続対応
・API Key 認証
・デモモード（ハードウェアなしで画面確認可能）
・日本語 / 英語 対応

【必要なもの（サーバー側）】
・Raspberry Pi（任意モデル）＋ Hamlib REST API サーバー
・Hamlib 対応アマチュア無線機
・セットアップスクリプトは GitHub（ji1ore/M5CoreHamCAT）で公開中
```

### キーワード（100文字以内）
```
ham radio,アマチュア無線,リモート,CW,FT8,APRS,Hamlib,Raspberry Pi,モールス,無線機
```

### プライバシーポリシーURL
```
（GitHubリポジトリのPRIVACY_POLICY_iOS.mdを参照）
```

---

## English (en-US)

### App Name
```
WifiRigCTRL
```

### Subtitle
```
Ham Radio Wi-Fi Remote Controller
```

### Description

```
Control your amateur radio transceiver remotely over Wi-Fi using a Raspberry Pi
as a relay server. Also supports direct Wi-Fi CI-V connection for IC-705 / IC-9700.

[What's New in v2.51]
- DireWolf fix: ADEVICE now read from .env (was hardcoded to plughw:0,0)
- Pi API updated to 2.51 (tap "Update Pi" in Admin)

[Key Features]

Remote Rig Control
- Adjust frequency, mode, power, squelch, and filter width in real time
- Supports Hamlib-compatible rigs (YAESU, ICOM, Kenwood, and more)
- Multiple connection profiles

Direct CI-V Connection (IC-705 / IC-9700)
- No Raspberry Pi required for ICOM transceivers
- Supports frequency, mode, S-meter, PTT, RF power, squelch, BK-IN

Audio RX Streaming
- Stream received audio to the iPhone speaker
- 5-level noise reduction; selectable sample rate (8k–48kHz)

PTT & Audio TX
- Transmit microphone audio through the rig in real time

CW (Morse Code)
- Real-time CW decoding (up to 5 simultaneous channels)
- CW text transmit with WPM slider, preset messages, and free text
- BLE CW keyer support (DualKey-BLE / RemoteKeyer-BLE)
- Local sidetone generation

FT8 / FT4
- Real-time decode and transmit via webft8

APRS
- DireWolf beacon with GPS or manual coordinates
- Received station list with distance and bearing

Band Memory — MEM Panel
- 4-tab panel: Preset / User / POTA / SOTA
- Live POTA & SOTA spot fetch; tap to auto-set frequency and mode

Network Auto-Scan
- Discover Raspberry Pi servers on the local network via UDP broadcast

Also Includes
- WireGuard VPN support for remote operation
- API Key authentication
- Demo mode (explore the UI without hardware)
- Japanese / English localization

[Requirements]
- Raspberry Pi with Hamlib REST API server + Hamlib-compatible transceiver
- Setup scripts on GitHub: ji1ore/M5CoreHamCAT
```

### Keywords
```
ham radio,amateur radio,remote,CW,morse,FT8,APRS,Hamlib,Raspberry Pi,transceiver
```

---

## プライバシー情報（App Store Connect）

| データ種別 | 収集 | 使用目的 |
|---|---|---|
| 位置情報 | 任意（APRSビーコン送信時のみ） | アプリ機能 |
| マイク | PTT送信中のみ使用 | アプリ機能 |
| その他データ | 収集なし | — |
| トラッキング | なし | — |

---

## App Store 審査メモ

- ハードウェア（Raspberry Pi / アマチュア無線機）が必要なアプリのため、
  Review ガイドに「テスト用アカウント不要、ハードウェア依存アプリ」と記載する
- デモモードで一部の画面は確認可能
- アマチュア無線用途であり、対象ユーザーは免許保持者
