# v3.35 ストア掲載用「新機能」/ Store "What's New"

Google Play / App Store の「新機能」欄にそのまま貼れる短文です（日本語・英語）。

---

## 日本語（Google Play / App Store）

v3.35 — CW（モールス）の送受信を改善しました。

・CW送信のWPM修正：Pi経由でスライダーを動かしても速度が変わらない不具合を修正。CI-V直結ではスライダーで本体のキーヤー速度に即反映。
・CW受信デコード改善：文頭・短い文字（TU/GA/UR等）・15〜20WPMの取りこぼしを改善。
・CI-V接続でもCW受信デコードに対応。
・CW送信中の送信文字をTX欄に表示（メイン/CW TX画面）。
・CW既定フィルタ幅を200Hzに。

※本バージョンはPi API v3.35です。Admin→Update PiでPiを更新してください。

---

## English (Google Play / App Store)

v3.35 — CW (Morse) transmit & receive improvements.

- CW TX speed (WPM): fixed the slider not changing TX speed over the Pi path; in CI-V direct the slider now sets the radio's keyer speed instantly.
- Better CW receive copy of opening characters, short letters (TU/GA/UR, etc.), and slower 15–20 WPM signals.
- CW receive decoding now works over a CI-V (RS-BA1) connection.
- Sent CW text is shown in a TX field (main & CW TX screens).
- Default CW filter width is now 200 Hz.

Note: this version uses Pi API v3.35 — update the Pi via Admin → Update Pi.
