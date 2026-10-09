# Wifi RIG CTRL for Android — v3.14

Android スマートフォンからアマチュア無線機をWi-Fi経由でリモート操作するコントローラーアプリ。

> **English summary** — Wifi RIG CTRL is an Android controller app for amateur (ham) radio operators. It connects to a transceiver over Wi-Fi via a Raspberry Pi (Hamlib/FastAPI) or directly to an IC-705 / IC-9700 (CI-V). Features: RX audio, PTT/mic TX, CW decode & send, USB/BLE CW keyer, FT8/FT4 server-side decode via jt9, APRS beacon, RTTY AFSK TX/RX, QSO log, Picture-in-Picture, multiple profiles.

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
| RTTY AFSK 送受信 | ○ | × | × |
| APRS ビーコン送信 | ○ | × | × |

○: 対応　×: 非対応

**※1** CI-V Bluetooth：IC-705 等を Bluetooth RFCOMM（SPP）で接続。音声は Bluetooth SCO 経由。  
**※2** ローカル FT8 は端末上の ft8_lib（JNI）でデコード。Wi-Fi CI-V は RS-BA1 音声ストリームを入力、Bluetooth CI-V は BT SCO 音声を入力。

---

## インストール

### APK から直接インストール

1. `Wifi_RIG_CTRL_v3.14.apk` を Android デバイスに転送
2. Android の設定から「提供元不明のアプリ」を許可
3. APK をタップしてインストール

### Raspberry Pi サーバー

[RaspberryPiSetup フォルダ](../RaspberryPiSetup/) の `readme.txt` を参照してください。

---

## バージョン履歴

### v3.14（2026-10-06・versionCode 113）

**RTTY 操作性改善**
- コマンドボタンを CW TX 画面と同じ配色に統一（送信マクロ: 紫、CQ REPEAT/TX: 緑、STOP: 赤）
- コマンドボタン長押しで TX テキストフィールドに流し込み（修正・TX×2 に対応）
- TX ゲイン調整ボタンを追加（10〜100%、シークバーで設定）
- FT-991A バンド別キャリア補正機能を追加（HF / 50 / 144 / 430 MHz 各バンドで個別設定可）

### v3.13（2026-10-04・versionCode 110）

**Pi API 更新（v3.13）**
- FT8 デコード深さのデフォルトを 3（deep）→ 1（fast）に変更
- mfsk-decode を mfsk-core v0.13.0 に更新
- PTT 音声送信の BrokenPipeError を捕捉し aplay 早期終了時のクラッシュを防止
- setup_fastapi_radio.sh の改善（libncurses-dev、rpath 追加、venv 修復、Direwolf 自動検出）

**修正**
- Pi API バージョン照合を "3.13" に修正

### v3.12（2026-10-03・versionCode 105）
**RTTY 改善**
- 受信チューニングを刷新: スペクトラム表示・ヒステリシス付きオートチューン・FIX モード
- 送信経路を Pi 経由 AFSK に統一（ダイヤルオフセット自動補正）

**修正・改善**
- Pi の FastAPI 再起動後に周波数 0 のまま固まる状態を自動復旧
- RTTY 画面を離れるときに他クライアントの受信音声が途切れる問題を修正

### v3.11（2026-09-28・versionCode 96）
- iOS 版と合わせたバージョン統一リリース

### v3.10（2026-09-23〜28・versionCode 88〜95）
- RTTY 送受信機能を追加
- BLE/USB リモート CW 送信の安定性改善

---

## ライセンス

使用ライブラリのライセンスは各ライブラリのドキュメントを参照してください。

### オープンソースクレジット

- **WSJT-X / jt9**: FT8/FT4 デコーダー — GPL-3.0-or-later
- **Hamlib / rigctld**: 無線機制御ライブラリ — LGPL-2.1
- **Direwolf**: AX.25/APRS モデム — GPL-2.0
- **mfsk-core**: FT8/FT4 Rust デコーダー — MIT
