# WifiRigCTRL iOS — App Store Listing v3.34

---

## アプリ名

WifiRigCTRL

---

## サブタイトル（30文字以内）

Wi-Fi ハムラジオ リモートコントローラー

---

## 説明（日本語）

WifiRigCTRL は、Raspberry Pi または ICOM CI-V（Wi-Fi / Bluetooth）経由でアマチュア無線機をリモート操作する iPhone / iPad アプリです。

【主な機能】
・受信周波数・モード・Sメーターのリアルタイム表示
・周波数・モード・パワー・スケルチ・フィルタ幅の変更
・受信音声のスピーカー再生（ノイズリダクション対応）
・PTT ON/OFF とマイク音声の送信
・CW モールス送信・受信デコード（BLE / USB-NCM 電鍵対応・デコード用 NR 対応）
・RTTY AFSK 送受信（端末内蔵ソフトモデム／Raspberry Pi minimodem 経由の両対応・スペクトル表示）
・FT8 / FT4 受信デコード・送信
・FT8 全デコード履歴を ~/ALL.TXT に自動保存（WSJT-X 互換）
・バンドスコープ（CAT ワンショットスイープ／ICOM CI-V ネイティブスコープ・POTA/SOTA スポット重畳）
・APRS ビーコン送信（GPS 対応）
・QSO ログ（ADIF エクスポート・POTA/SOTA 参照符号対応）
・運用地（JCC/JGC・グリッド）登録と運用セッション
・eQSL 自動アップロード・Smart Ham Log 連携・PSKReporter 送信
・バンドメモリ・POTA/SOTA スポット連携（SP2ALART 対応）
・複数プロファイル対応・API Key 認証・WireGuard VPN 対応
・Icom IC-705 / IC-9700 への直接 Wi-Fi / Bluetooth CI-V 接続
・Raspberry Pi SSH ワンタップセットアップ

【v3.34 の変更点】
・CW（モールス）受信デコードにノイズリダクション（NR）を追加しました。受信トーンを中心とした狭帯域フィルタで近接混信やノイズを抑え、聞きやすさと復号率を改善します（音切れなし）。RX 周波数のタップで ON/OFF
・CW / RTTY 画面の GA・GE ボタンの左右配置を入れ替えました
※本バージョンは Pi API を 3.34 に更新します（アプリの Update Pi で適用してください）

---

## Description (English)

WifiRigCTRL is an iPhone / iPad app for remotely operating your amateur-radio transceiver via a Raspberry Pi or ICOM CI-V (Wi-Fi / Bluetooth).

Features:
- Real-time RX frequency, mode and S-meter display
- Change frequency, mode, power, squelch and filter width
- RX audio playback through the speaker (with noise reduction)
- PTT on/off and microphone transmit
- CW Morse send and receive-decode (BLE / USB-NCM keyers, with decode-time NR)
- RTTY AFSK transmit/receive (on-device software modem or via the Raspberry Pi's minimodem, with spectrum display)
- FT8 / FT4 receive-decode and transmit
- Automatic FT8 all-decode log to ~/ALL.TXT (WSJT-X compatible)
- Band Scope (CAT single-sweep / native ICOM CI-V scope, with POTA/SOTA spots overlaid)
- APRS beacon transmit (GPS)
- QSO log (ADIF export, POTA/SOTA references)
- Operating-location (JCC/JGC, grid) registration and operating sessions
- eQSL auto-upload, Smart Ham Log integration, PSKReporter spotting
- Band memory, POTA/SOTA spot integration (SP2ALART)
- Multiple profiles, API-key auth, WireGuard VPN
- Direct Wi-Fi / Bluetooth CI-V to Icom IC-705 / IC-9700
- One-tap Raspberry Pi SSH setup

What's new in v3.34:
- Added noise reduction (NR) for RX CW (Morse) decoding: an in-app narrow bandpass centred on the received tone suppresses nearby QRM and band noise, improving both listenability and decode accuracy with no audio dropouts. Tap the RX frequency to toggle
- Swapped the GA / GE buttons on the CW / RTTY screens
- Note: this version updates the Pi API to 3.34 (apply with the app's Update Pi)
