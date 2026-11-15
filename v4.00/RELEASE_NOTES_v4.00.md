# Wifi_RIG_CTRL v4.00 リリースノート / Release Notes

**バージョン / Version**: 4.00（Android versionCode 163 / iOS build 80）
**リリース日 / Release date**: 2026-11-15

---

## 日本語

本バージョンは、**D-STAR（DV）のレピータ運用**に本格対応し、**FT8/FT4 デコーダ**を更新したメジャーアップデートです。

### D-STAR（DR）レピータ運用に対応
- IC-705 / IC-9700 などで、D-STAR の **DR モード運用**に対応しました。
  - 宛先 **UR** / アクセス（エリア）レピータ **R1** / ゲートウェイ（リンク先）レピータ **R2** の設定（CI-V `1F 01`）。
  - **シンプレックス**（UR=CQCQCQ）運用。
  - **MyCall**（CI-V `1F 00`）/ **TX メッセージ**（CI-V `1F 02`）の設定。
  - **レピータリスト（CS-705 / RepeaterBook 形式 CSV）**の取り込みと、現在地からの近傍レピータ選択。
- CI-V 直結（WiFi / BT）に加え、**Pi 経由の CI-V ブリッジ**でも D-STAR ルーティング設定が可能です（IC-705 / IC-9700）。
- D-STAR ルーティング設定中に一時的に「リグ切断」と誤表示される問題を修正しました（rigctld 再起動中の接続猶予を適用）。

### FT8/FT4 デコーダ（mfsk-core）を v0.16.0 に更新
- Pi 側の FT8/FT4 デコーダ **mfsk-core を v0.16.0** に更新し、デコード精度を改善しました。
- 端末内蔵のローカル FT8/FT4（CI-V 直結時）は ft8_lib（MIT）を継続使用します。

### Pi の更新（v4.00）
- Pi API を **v4.00** に更新しました。**Admin → Update Pi** で Pi を更新してください（更新後、メイン画面の「Update」バッジが消えます）。
- D-STAR の CI-V ルーティングはサーバー側（api.py）経由でも送信されるため、Pi の更新が必要です。

---

## English

This is a major release that adds full **D-STAR (DV) repeater operation** and updates the **FT8/FT4 decoder**.

### D-STAR (DR) repeater operation
- Added **DR-mode operation** for D-STAR on IC-705 / IC-9700 and similar rigs:
  - Destination **UR** / access (area) repeater **R1** / gateway (link) repeater **R2** (CI-V `1F 01`).
  - **Simplex** operation (UR = CQCQCQ).
  - **MyCall** (CI-V `1F 00`) and **TX message** (CI-V `1F 02`).
  - Import a **repeater list (CS-705 / RepeaterBook CSV)** and pick the nearest repeater from your location.
- Works over CI-V direct (Wi-Fi / BT) and over the **Pi CI-V bridge** (IC-705 / IC-9700).
- Fixed a transient "rig disconnected" message shown while configuring D-STAR routing (a connect grace period is now applied while rigctld restarts).

### FT8/FT4 decoder (mfsk-core) updated to v0.16.0
- The Pi-side FT8/FT4 decoder **mfsk-core is updated to v0.16.0** for improved decode accuracy.
- The on-device local FT8/FT4 engine (CI-V direct mode) continues to use ft8_lib (MIT).

### Pi update (v4.00)
- The Pi API is updated to **v4.00**. Update the Pi via **Admin → Update Pi** (the "Update" badge on the main screen disappears afterward).
- D-STAR CI-V routing can be sent through the server (api.py), so the Pi update is required.
