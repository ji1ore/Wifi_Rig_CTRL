# v4.11 ストア掲載用「新機能」/ Store "What's New"

Google Play / App Store の「新機能」欄にそのまま貼れる短文です（日本語・英語）。

---

## 日本語（Google Play / App Store）

v4.11 — WireGuard（VPN）セットアップの信頼性を向上しました。

・WireGuard の導入前に DuckDNS のドメイン／トークンを検証し、失敗時は大きな警告を表示してセットアップを中止します（設定ミスにその場で気づけます）。
・DuckDNS のドメイン／トークンに「クリップボード貼り付け」ボタンを追加しました（Android / iOS）。カメラでの UUID 誤読を回避できます。
・ドメイン入力を正規化し二重ドメインを防止。iOS はアプリ内の約42項目に英語訳を追加しました。

※本バージョンは Pi API を 4.11 に更新します（Admin → Update Pi が必要）。

---

## English (Google Play / App Store)

v4.11 — Makes WireGuard (VPN) setup more reliable.

- Before the WireGuard install, the app validates your DuckDNS domain/token and, on failure, shows a prominent warning and aborts setup so you catch mistakes on the spot.
- Added clipboard-paste buttons for the DuckDNS domain and token (Android / iOS), avoiding UUID misreads from the camera.
- Added domain normalization to prevent the double-domain bug, and added English translations for ~42 in-app strings on iOS.

Note: this release updates the Pi API to 4.11 (Admin → Update Pi required).
