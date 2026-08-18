# Wifi RIG CTRL for Android — v2.50

Android スマートフォンからアマチュア無線機をWi-Fi経由でリモート操作するコントローラーアプリ。

> **English summary** — Wifi RIG CTRL is an Android controller app for amateur (ham) radio operators. It connects to a transceiver over Wi-Fi via a Raspberry Pi (Hamlib/FastAPI) or directly to an IC-705 / IC-9700 (CI-V). Features: RX audio, PTT/mic TX, CW decode & send, USB/BLE CW keyer, FT8/FT4, APRS beacon (DireWolf + Rig modem AP96/AP12), APRS receive with Mic-E decode, Picture-in-Picture, multiple profiles, band memory (MEM), repeater settings (tone, offset).

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

## v2.50 の変更点（v2.34 との比較）

### Pi側スクリプト更新（api.py / create_api.sh）
- **PTT OFF ウォッチドッグ追加**（FTX-1F 等、PTT 固着対策）
  - PTT OFF 後、バックグラウンドで実際に OFF を確認できるまで最大20秒間 T 0 を送り続ける
  - rigctld 再起動を挟んだ場合は "t" 応答を2回連続で確認してから確定
  - CW / APRS / FT8 送信に追い越された場合は即座に打ち切り（他の送信方式と競合しない）
- **rigctld 再起動の排他制御追加**（PTT 連打時の多重起動防止）
  - `_trigger_rigctld_restart()` ヘルパーと threading.Lock で競合を防止
- **WebFT8 バージョンを `/radio/status` に追加**
  - `webft8_static/web/server.py` の `_VERSION` を読み取って返すようにし、アプリ側でバージョン不一致を検知できるようにした
- **webft8 サーバー再起動の改善**
  - systemd サービスがあれば `systemctl restart webft8`、なければプロセス kill & 直接起動のフォールバック対応
- `API_VERSION` を `"2.50"` に更新、`server_webft8.py` の `_VERSION` を `"2.50"` に更新

### アプリ側
- Pi API バージョン照合を `"2.50"` に更新（旧 Pi は「Update Pi」で更新してください）

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
| USB CWキーヤー（DualKey USB直結） | ○ | × |
| BLE CWキーヤー（DualKey-BLE 等） | ○ | × |
| CQ リピート | ○ | × |
| ノイズリダクション（NR） | ○ | × |
| Wi-Fi PTT（M5Atom 等外部デバイス） | ○ | × |
| FT8 / FT4 | ○ | × |
| APRS ビーコン送信（DireWolf） | ○ | × |
| APRS ビーコン送信（リグ内蔵モデム AP96/AP12） | ○ | × |
| APRS 受信・局表示（Mic-E対応） | ○ | × |
| バンドメモリー（MEM） | ○ | ○ |

○: 対応　×: 非対応

---

## インストール

APK ダウンロード:
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.50/M5CoreHamCAT_Android

1. `Wifi_RIG_CTRL_v2.50.apk` をダウンロード
2. Android の設定から「提供元不明のアプリ」を許可
3. APK をタップしてインストール

ソースコードも同フォルダに公開しています（Android Studio でビルド可能）。

---

## Raspberry Pi セットアップ

https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.50/RaspberryPiSetup

- v2.03 以降からのアップグレード: アプリの「Update」→「Update Pi」ボタンで自動更新
- v2.12 から Hamlib 4.7.2 対応: アプリの「Update」→「Update Hamlib」ボタンで追加インストール
- v2.02 以前からのアップグレード: 初回のみ手動 scp が必要

---

## ライセンス

[LICENSES.md](https://github.com/ji1ore/M5CoreHamCAT/blob/main/v2.50/M5CoreHamCAT_Android/LICENSES.md) を参照
