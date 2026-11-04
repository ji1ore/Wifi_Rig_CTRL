# Wifi Rig CTRL v3.33 — Release Notes / 新着情報

App v3.33 (Android versionCode 160 / iOS build 77). Pi API stays 3.32 — **no Pi update required.**
アプリ v3.33（Android versionCode 160 / iOS build 77）。Pi API は 3.32 のまま＝**Pi の更新は不要**です。

---

## English (store "What's New")

v3.33 — CW & band-scope improvements (app-only; no Pi update needed)
- RX CW decode: finer FFT window copies 20–25 WPM reliably.
- Fixed endless "T" misdecode after a station signs ("K") — speed-lock collapse guard.
- Run-together "UR" now decodes.
- Band Scope: new filter-width readout (long-press to change) and a real-time audio spectrum (WF button) — tap a signal to tune it to your pitch; auto-zooms to the RX filter width.
- Fixed RTTY waterfall sometimes not appearing when SPK was on.

## 日本語（ストア「新着情報」）

v3.33 — CW とバンドスコープの改善（アプリのみ。Pi 更新不要）
- CW 受信デコード：FFT 解析窓の最適化で 20〜25WPM を安定コピー。
- 交信終了（K）後に「T」が延々と続く誤判定を修正（速度ロックの崩壊ガード）。
- 連結送出の「UR」を解読対応。
- バンドスコープ：受信フィルタ幅の表示（長押しで切替）と受信音のリアルタイム・スペクトラム（WF ボタン）を追加。信号をタップすると基準ピッチへ同調、受信幅へ自動ズーム。
- RTTY のウォーターフォールが SPK オン時に出ないことがある不具合を修正。
