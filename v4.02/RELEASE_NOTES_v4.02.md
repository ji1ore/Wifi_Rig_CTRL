# Wifi_RIG_CTRL v4.02 リリースノート / Release Notes

**バージョン / Version**: 4.02（Android versionCode 165 / iOS 4.02）
**リリース日 / Release date**: 2026-11-17

---

## 日本語

本バージョンは、v4.01 で対応した**アプリUIの英語化の仕上げ**です。英語（その他の言語）表示のときに、一部のボタンのキャプション・設定項目・ダイアログに**日本語が残っていた問題**を修正し、英語UIを完全化しました。

### 英語UIの残存日本語を修正（Android / iOS）
- 以下のような、まだハードコードで日本語のままだった画面文字列を文字列リソース化しました。英語端末では英語、日本語端末では従来どおり日本語で表示されます。
  - SSH / VNC セットアップ、WireGuard セットアップの各項目・ボタン
  - リグ選択（MEMSET）画面の外部連携・SOTA スポット・バックアップ関連の説明と項目
  - 接続画面（Log取得 / QSO LOG 一覧）、バンドスコープ／RTTY／FT8 の一部ラベル、CW送信ダイアログ、運用地ラベル、トークン読取ダイアログ など
  - Dropbox 連携の一部エラーメッセージ
- Android は文字列リソース（`values` = 英語 / `values-ja` = 日本語）、iOS は String Catalog により切り替わります。
- 地名データ（JCC/JGC 等）や、Pi 側の出力と照合する内部文字列、「→」「D★」等の記号は仕様上そのままです。

### Pi の更新について
- 本バージョンは**アプリ側のみの変更**で、Pi（api.py）に機能的な変更はありません。**Pi の更新（Admin → Update Pi）は不要**です（Pi API は **4.00** のまま）。

---

## English

This release **completes the English‑UI localization** started in v4.01. It fixes **Japanese text that still remained** in some button captions, settings rows and dialogs when the app ran in English (or other non‑Japanese locales).

### Fixed remaining Japanese in the English UI (Android / iOS)
- The following still‑hardcoded screen strings were moved to proper string resources, so they show English on English devices and Japanese on Japanese devices:
  - SSH / VNC setup and WireGuard setup rows and buttons
  - Rig‑select (MEMSET) screen: external‑integration, SOTA‑spot and backup descriptions/rows
  - Connect screen (Fetch Log / QSO LOG list), some Band‑scope / RTTY / FT8 labels, the CW‑TX dialog, the operating‑location label, the token‑scan dialog, and more
  - A Dropbox‑integration error message
- Android uses string resources (`values` = English / `values-ja` = Japanese); iOS uses the String Catalog.
- Place‑name data (JCC/JGC, etc.), internal strings matched against the Pi's output, and symbols such as "→" / "D★" are intentionally left unchanged.

### Pi update
- This is an **app-only** release; there is no functional change to the Pi (api.py). **No Pi update is required** (the Pi API stays at **4.00**).
