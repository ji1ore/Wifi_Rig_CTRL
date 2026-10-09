# WifiRigCTRL (iOS) — v3.14

iPhone / iPad からアマチュア無線機をWi-Fi経由でリモート操作するコントローラーアプリ。

> **English summary** — WifiRigCTRL is an iOS controller app for amateur (ham) radio operators. It connects to a transceiver over Wi-Fi via a Raspberry Pi (Hamlib/FastAPI) or directly to an IC-705 / IC-9700 (CI-V). Features: RX audio, PTT/mic TX, CW decode & send, BLE CW keyer, USB-NCM CW relay, FT8/FT4 local decode (CI-V mode), RTTY AFSK TX/RX, APRS beacon, QSO log, POTA/SOTA spot recall, multiple profiles.

---

## 動作要件

| 接続方式 | 必要なもの |
|---|---|
| **Raspberry Pi 経由** | Raspberry Pi Zero 2W 以上のスペック（Hamlib + FastAPI サーバー導入済み）＋ 対応無線機<br>※ FT8デコードには Raspberry Pi 4 以上推奨 |
| **CI-V 直接接続（Wi-Fi）** | Icom IC-705 または IC-9700（iPhone と同一Wi-Fiネットワーク） |
| **CI-V 直接接続（Bluetooth）** | Icom IC-705（Bluetooth接続対応）|

- iOS 17.0 以上
- iPhone / iPad（Wi-Fi接続必須）

---

## ビルド方法

1. Xcode 15 以上で `WifiRigCTRL_iOS.xcodeproj` を開く
2. Signing & Capabilities で開発者アカウントを設定
3. ターゲットデバイスを iPhone / iPad に設定してビルド

外部フレームワークへの依存なし（Swift Package Manager / CocoaPods 不使用）。

---

## バージョン履歴

### v3.14（2026-10-06・build 46）

**RTTY 操作性改善**
- コマンドパネルを CQ / ANS タブ構成に刷新（Android と同一レイアウト）
- コマンドボタンを CW TX 画面と同じ配色に統一（送信マクロ: 紫、TX: 緑）
- 周波数表示タップ → 周波数直接入力、長押し → メモリ呼び出し（MEM ボタン削除）
- RTTY 画面から QSO LOG 登録・一覧への遷移を追加
- TX ゲイン（音量）調整ボタンを追加（10–100%、5% 単位）
- FT-991A バンド別キャリア補正機能を追加（HF / 50 / 144 / 430 MHz 各バンドで個別設定可）

### v3.13（2026-10-04・build 45）

**修正**
- Pi API バージョン照合を "3.13" に修正
- Pi API 更新（v3.13）: FT8 デコード深さ改善、mfsk-core v0.13.0、BrokenPipeError 対処

### v3.12（2026-10-03・build 40）
**新機能**
- RTTY 送受信画面を追加（Pi 経由。CI-V 直接接続では使用不可）

**修正・改善**
- 接続の安定性向上、AVAudioSession 操作改善
- iOS 26 で `.local` 名が解決できず無音になる問題を修正

### v3.11（2026-09-28）
- Android v3.11 に合わせたバージョン統一リリース（Pi API 3.10）

---

## ライセンス

使用ライブラリのライセンスは [LICENSES.md](LICENSES.md) を参照。
