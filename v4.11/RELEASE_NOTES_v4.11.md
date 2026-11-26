# Wifi_RIG_CTRL v4.11 リリースノート / Release Notes

**バージョン / Version**: 4.11（Android versionCode 169 / iOS 4.11）
**リリース日 / Release date**: 2026-11-26

---

## 日本語

本バージョンは **WireGuard（VPN）セットアップの信頼性向上** が目玉です。DuckDNS のドメイン／トークンの取り込みと検証を強化し、設定ミスによる「設定は完了したのに後から接続できない」事故を防ぎます。

### DuckDNS の事前検証（WireGuard セットアップ時）
- WireGuard の導入を始める**前**に、入力されたドメイン／トークンで DuckDNS へ実際に更新を試し、正しいか確認します。
- 失敗（`OK` 以外）した場合は、**画面に大きな警告を表示してセットアップを中止**します。読み取りミスやトークン誤りにその場で気づけます。

### DuckDNS ドメイン／トークンのクリップボード貼り付け（Android / iOS）
- ドメイン欄・トークン欄に **クリップボード貼り付けボタン（📋）** を追加。カメラ読み取りでの UUID 誤読を回避できます。
- トークンは貼り付け内容から **UUID を自動抽出**。ドメインは `xxx.duckdns.org` を貼っても **`xxx` のみ**を取り込みます。

### その他の改善・修正
- ドメイン入力の正規化を追加（`http(s)://`・末尾 `/`・`.duckdns.org` を除去）し、**二重ドメイン（`xxx.duckdns.org.duckdns.org`）** を防止。
- iOS：アプリ内の約 42 項目の文言に **英語訳を追加**（英語環境での表示を改善）。日本語ローカライズの登録（`knownRegions` の `ja`）も修正。

### Pi の更新について
- 本バージョンは **Pi API を 4.11 に更新**します。**Pi の更新（Admin → Update Pi）が必要**です（更新しないとアプリに Pi API 不一致の警告が表示されます）。

---

## English

This release focuses on **making WireGuard (VPN) setup more reliable**. It strengthens how the DuckDNS domain/token are entered and validated, preventing the "setup looked fine but won't connect later" problem.

### DuckDNS pre-validation (during WireGuard setup)
- **Before** starting the WireGuard install, the app tries a real DuckDNS update with the entered domain/token to confirm they are correct.
- If it fails (any response other than `OK`), a **prominent warning is shown and setup is aborted** — so you catch a misread or wrong token on the spot.

### Clipboard paste for the DuckDNS domain/token (Android / iOS)
- Added **clipboard-paste buttons (📋)** to the domain and token fields, avoiding UUID misreads from the camera.
- The token field **extracts the UUID automatically**; the domain field accepts `xxx.duckdns.org` and keeps **only `xxx`**.

### Other improvements & fixes
- Added domain input normalization (strips `http(s)://`, a trailing `/`, and a `.duckdns.org` suffix), preventing the **double-domain bug (`xxx.duckdns.org.duckdns.org`)**.
- iOS: added **English translations for ~42 in-app strings** (better experience in the English locale) and fixed Japanese localization registration (`ja` in `knownRegions`).

### Pi update
- This release **updates the Pi API to 4.11**. A **Pi update (Admin → Update Pi) is required** (otherwise the app shows a Pi API version-mismatch warning).
