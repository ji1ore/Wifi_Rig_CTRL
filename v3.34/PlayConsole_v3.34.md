# Google Play Console — v3.34 (versionCode 161)

**リリース日**: 2026-11-06  
**ファイル**: `Wifi_RIG_CTRL_v3.34.aab`

---

## 日本語リリースノート（Google Play 掲載用・500字以内）

・CW（モールス）受信デコードにノイズリダクション（NR）を追加しました。受信トーンを中心とした狭帯域フィルタで近接混信やノイズを抑え、聞きやすさと復号率を改善します（音切れなし）。RX周波数のタップでON/OFF
・CW/RTTY画面のGA・GEボタンの左右配置を入れ替えました
・Pi APIを3.34に更新します（アプリのUpdate Piで適用）

---

## English Release Notes (Google Play listing, under 500 chars)

- Added noise reduction (NR) for RX CW (Morse) decoding: an in-app narrow bandpass centred on the received tone suppresses nearby QRM and band noise, improving both listenability and decode accuracy with no audio dropouts. Tap the RX frequency to toggle
- Swapped the GA / GE buttons on the CW / RTTY screens
- Updates the Pi API to 3.34 (apply with Update Pi)

---

## メモ

- 本バージョンの主眼は **CW デコード用 NR（ローカル狭帯域 BPF ＋ 再生 AGC）**。混雑バンドで受信トーン（CW ピッチ）中心に biquad バンドパス 3 段（実効 ~55Hz）をアプリ内で適用。デコーダ入力・再生の両方に掛け、近接局（数十 Hz 差）とバンドノイズを除去して復号率を改善。中心は NR ON 時のピッチに固定（追従による音の乱れを回避）。再生のみ滑らかゲインの AGC で信号強調（クリック無し）。
- Pi 側 afftdn は CW デコード中は使わない（従来どおり）。NR は **アプリ内処理**なので Pi CPU 由来の音切れが無い。
- **Pi API は 3.34 に更新（EXPECTED_PI_API_VERSION=3.34 / api_version 3.34）。Update Pi が必要**（アプリ同梱 api.py を /admin/update でアップロード）。api.py は FastAPI / iOS / Android の全同梱コピーで一致（create_api.sh 埋め込みも一致）。
- 配布 AAB/APK に GPL/コピーレフトのコードを含まない（libmfsk_jni.so を同梱しない。v3.32 のライセンス整理を継承）。実行コードの実行時ダウンロードも行わない＝Google Play 規約に適合。
- 署名: wifi_rig_ctrl.jks（CN=JI1ORE）。versionCode 161 / versionName 3.34。
