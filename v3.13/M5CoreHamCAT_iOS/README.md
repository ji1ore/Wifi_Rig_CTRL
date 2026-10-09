# WifiRigCTRL (iOS) — v3.13

iPhone / iPad からアマチュア無線機をWi-Fi経由でリモート操作するコントローラーアプリ。

> **English summary** — WifiRigCTRL is an iOS controller app for amateur (ham) radio operators. It connects to a transceiver over Wi-Fi via a Raspberry Pi (Hamlib/FastAPI) or directly to an IC-705 / IC-9700 (CI-V). Features: RX audio, PTT/mic TX, CW decode & send, BLE CW keyer, USB-NCM CW relay, FT8/FT4 local decode (CI-V mode), APRS beacon, POTA/SOTA spot recall, multiple profiles.

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

### v3.13（2026-10-04・build 45）

**修正**
- Pi API バージョン照合を "3.13" に修正（About 画面のバージョン不一致表示が "3.12 is required" のままだった問題）
- Pi API 更新（v3.13）: FT8 デコード深さ改善、mfsk-core v0.13.0、BrokenPipeError 対処、setup_fastapi_radio.sh 改善

### v3.12（2026-10-03・build 40）
**新機能**
- RTTY 送受信画面を追加（Pi 経由。CI-V 直接接続では使用不可）

**修正・改善**
- 接続の安定性: 8 秒間は「再接続中」表示のままセッションを維持、Pi 復帰後に自動再オープン
- Pi の FastAPI 再起動後の周波数 0 固まりを自動復旧
- AVAudioSession 操作をメインスレッド外で実行し、音声再開時のアプリ凍結を解消
- iOS 26 で `.local` 名が解決できず受信音声が無音になる問題を修正
- Admin「Update Pi」を Android と同じ 2 ステップ手順に統一

### v3.11（2026-09-28）
- Android v3.11 に合わせたバージョン統一リリース（Pi API 3.10）

---

## ライセンス

使用ライブラリのライセンスは [LICENSES.md](LICENSES.md) を参照。
