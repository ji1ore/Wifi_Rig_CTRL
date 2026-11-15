# v4.00 ストア掲載用「新機能」/ Store "What's New"

Google Play / App Store の「新機能」欄にそのまま貼れる短文です（日本語・英語）。

---

## 日本語（Google Play / App Store）

v4.00 — D-STAR レピータ運用に対応し、FT8/FT4 デコーダを更新しました。

・D-STAR（DR）運用に対応：UR / R1 / R2 のルーティング、シンプレックス、MyCall・TX メッセージ設定（IC-705 / IC-9700、CI-V 1F）。
・レピータリスト（CS-705 / RepeaterBook CSV）取り込みと近傍レピータ選択に対応。
・CI-V 直結・Pi 経由ブリッジの両方で D-STAR ルーティングが可能。
・FT8/FT4 デコーダ（mfsk-core）を v0.16.0 に更新し、デコード精度を改善。
・D-STAR 設定中の「リグ切断」誤表示を修正。

※本バージョンは Pi API v4.00 です。Admin → Update Pi で Pi を更新してください。

---

## English (Google Play / App Store)

v4.00 — Adds D-STAR repeater operation and updates the FT8/FT4 decoder.

- D-STAR (DR) operation: UR / R1 / R2 routing, simplex, MyCall and TX message (IC-705 / IC-9700, CI-V 1F).
- Import a repeater list (CS-705 / RepeaterBook CSV) and pick the nearest repeater.
- D-STAR routing works over both direct CI-V and the Pi bridge.
- FT8/FT4 decoder (mfsk-core) updated to v0.16.0 for better decode accuracy.
- Fixed a false "rig disconnected" message while configuring D-STAR.

Note: this version uses Pi API v4.00 — update the Pi via Admin → Update Pi.
