# v4.10 ストア掲載用「新機能」/ Store "What's New"

Google Play / App Store の「新機能」欄にそのまま貼れる短文です（日本語・英語）。

---

## 日本語（Google Play / App Store）

v4.10 — D-STAR のテキストメッセージに対応し、SSTV 画像の送受信（試作）を追加しました。

・D-STAR のテキストメッセージに対応しました。受信した相手局のメッセージとコールサインを表示し、受信履歴（受信のみ）を一覧できます。D★表示をタップして送信メッセージを編集でき、無信号が続くと受信表示を自動で消します。
・SSTV 画像の送受信（試作）を追加しました。FM/SSB で写真を送信し、受信画像をギャラリー表示します（今後改善予定の試作機能です）。
・D-STAR のモード遷移を安定化し、FT8/FT4 デコーダを更新。各種不具合を修正しました。

※本バージョンは Pi API を 4.10 に更新します（Admin → Update Pi が必要）。

---

## English (Google Play / App Store)

v4.10 — Adds D-STAR text messaging and SSTV image send/receive (prototype).

- D-STAR text messaging is now supported. The app shows the received station's message and callsign, and lists your receive-only message history. Tap the D★ display to edit the TX message; the RX display auto-clears after a period of no signal.
- Added SSTV image send/receive (a prototype feature we will keep improving): transmit a photo over FM/SSB and view received images in a gallery.
- D-STAR mode switching is more reliable, the FT8/FT4 decoder is updated, and various bugs are fixed.

Note: this release updates the Pi API to 4.10 (Admin → Update Pi required).
