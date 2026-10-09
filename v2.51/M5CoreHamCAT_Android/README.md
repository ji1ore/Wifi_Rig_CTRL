# Wifi RIG CTRL for Android — v2.51

Android スマートフォンからアマチュア無線機をWi-Fi経由でリモート操作するコントローラーアプリ。

> **English summary** — Wifi RIG CTRL is an Android controller app for amateur (ham) radio operators. It connects to a transceiver over Wi-Fi via a Raspberry Pi (Hamlib/FastAPI) or directly to an IC-705 / IC-9700 (CI-V). Features: RX audio, PTT/mic TX, CW decode & send, USB/BLE CW keyer, FT8/FT4, APRS beacon (DireWolf + Rig modem AP96/AP12), APRS receive with Mic-E decode, Picture-in-Picture, multiple profiles, band memory (MEM), repeater settings (tone, offset), SP2ALART spot integration.

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

## v2.51 の変更点（v2.50 との比較）

### SP2ALART 連携強化
- **SP2ALART スポット連携対応**: POTA/SOTA 通知アプリ「SP2ALART」(Android 版) のバナーをタップして
  Wifi RIG CTRL を起動すると、そのスポットの周波数とモードが自動設定される
  - SP2ALART の「連携モード」設定で有効化
  - `Sp2alertReceiver` を AndroidManifest に追加して `com.ji1ore.ACTION_SET_FREQ_MODE`
    ブロードキャストを受信（アプリが起動済みの場合も `onNewIntent` 経由で反映）

### Pi 側スクリプト更新（create_api.sh）
- **DireWolf ADEVICE 動的設定** (不具合修正):
  - `setup` / `Update Pi` 実行時の DireWolf 設定を `plughw:0,0` 固定から、
    `.env` の `ALSA_CAPTURE` 設定値を参照した動的設定に変更
  - USB オーディオデバイスが複数ある環境や `CARD=CODEC` 以外のデバイス名でも正しく動作するようになった
- **mawk 互換性修正**: `awk` の代わりに `mawk` を使う環境でも Pi セットアップが正常に完了するよう修正
- `API_VERSION` を `"2.51"` に更新、`server_webft8.py` の `_VERSION` を `"2.51"` に更新

> **Pi 側更新が必要です**: Admin 画面の「Update Pi」ボタンで更新してください。

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
| レピータ設定（トーン・オフセット） | × | ○ |
| PTT ON/OFF | ○ | ○ |
| マイク音声 TX（PTT中ストリーミング） | ○ | × |
| 受信音声 RX（スピーカー再生） | ○ | × |
| CW テキスト送信 | ○ | × |
| CW デコード（受信音声） | ○ | × |
| USB CW キーヤー（M5ATOM） | ○ | × |
| BLE CW キーヤー | ○ | × |
| FT8 / FT4 送受信（WebFT8） | ○ | × |
| APRS ビーコン送信 | ○ | × |
| APRS 受信・デコード | ○ | × |
| ピクチャー・イン・ピクチャー | ○ | ○ |
| SP2ALART スポット連携 | ○ | ○ |

---

## ファイル内容

| ファイル | 説明 |
|---|---|
| `Wifi_RIG_CTRL_v2.51.apk` | Android インストールパッケージ |
| `Wifi_RIG_CTRL_v2.51.aab` | Google Play 提出用 App Bundle |
| `app/` | Android ソースコード |

---

## ライセンス

MIT License — 詳細は `../M5CoreHamCAT_iOS/LICENSES.md` を参照
