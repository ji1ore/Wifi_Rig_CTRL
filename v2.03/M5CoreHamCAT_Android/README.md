# Wifi RIG CTRL for Android — v2.03

Android スマートフォンから Raspberry Pi 経由でアマチュア無線機を遠隔制御するアプリです。

## v2.03 変更点（v2.02 との比較）

### 新機能
- **FT8/FT4 デコード機能**（webft8 ベース）
  - WebView 上で webft8 を表示し、Pi の音声ストリームをリアルタイムにリダイレクト
  - TX: Android で生成した PCM を Pi へストリーミング送信し、PTT を自動制御
  - **Sync ボタン**: Pi クロックオフセットを自動測定し、デコードタイミングを補正
  - audioLatencyMs を clockOffset から自動較正（デコードウィンドウを正確に整合）
  - CQ オーバーレイ: デコードされた CQ コールサインを画面上部にリアルタイム表示
  - QSO ログ機能（ADIF 形式エクスポート対応）
  - FT4 / FT8 モード切替ボタン
  - FT8 画面から電力・周波数・モード（USB/PKTUSB）を直接制御
  - GPS グリッドロケーター自動取得（GL ボタン）

- **マルチプロファイル対応**
  - 複数の接続先（自宅・別の局など）をプロファイルとして保存・切替

### 改善・修正
- **RIG CONNECT 画面ツールバー改善**
  - About ボタンを下段から上段へ移動（Back / Connect / PTT / About）
  - Sync Time ボタン追加（Pi の NTP 時刻同期をアプリから実行）

- **SSL 証明書ピンニング**（webft8 HTTPS 接続の自己署名証明書を信頼登録）

- **webft8 リモートアップデート**
  - アプリの webFT8 ボタンから server_webft8.py を Pi に送信して自動再起動

- **ALSA デバイス分離**
  - FT8 用録音デバイスをメイン音声と別個に設定可能

### Raspberry Pi FastAPI v2.03 対応（要 create_api.sh 再実行）
- webft8 HTTPS サーバー（server_webft8.py）追加
- FT8 TX 専用エンドポイント改善（/radio/audio_sub・/radio/audio_tx の同時動作対応）
- 時刻同期 API 改善: chronyc → ntpdate → timedatectl → Android 時刻 の順で試行
- **ホームディレクトリ汎用化**: /home/pi ハードコードを廃止し、pi 以外のユーザーでも動作
- API バージョン 2.03

## APK インストール手順

1. `Wifi_RIG_CTRL_v2.03_debug.apk` をダウンロード
2. Android の設定で「提供元不明のアプリ」のインストールを許可
3. APK をタップしてインストール

または GitHub からダウンロード:
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.03/M5CoreHamCAT_Android

## 必要なもの

- Android スマートフォン (Android 8.0 / API 26 以上)
- Raspberry Pi Zero 2W（FastAPI v2.03 セットアップ済み）
- Wi-Fi 環境

FT8/FT4 デコードを使用する場合:
- Raspberry Pi の CPU が webft8 の WASM 実行に対応していること（Pi Zero 2W 以上推奨）
- アプリの FT8 ボタンからアクセス

USB CW 電鍵を使う場合:
- M5ATOM Lite または M5ATOM S3 Lite（Wifi_Rig_CW ファームウェア書き込み済み）
- OTG 対応 USB ケーブル

## ソースコード

このフォルダの `app/`・`gradle/`・`build.gradle`・`settings.gradle` 等が Android Studio でビルド可能なソースコードです。

## ライセンス

Private use. All rights reserved.
