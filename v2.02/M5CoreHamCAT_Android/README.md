# Wifi RIG CTRL for Android — v2.02

Android スマートフォンから Raspberry Pi 経由でアマチュア無線機を遠隔制御するアプリです。

## v2.02 変更点（v2.01 との比較）

### 新機能
- **CW TX パネル追加**
  - CQ / CALL K / AGN / UR 5NN BK 定型文ボタン
  - WPM スライダー（5〜60 WPM）
  - フリーテキスト入力（英語キーボード自動表示、全角→半角自動変換）
  - CW モード: Hamlib キーヤー経由で送信
  - FM-CW モード: PCM トーンをラズパイへストリーミング送信

### 改善・修正
- **CW/CWR モード自動幅設定**: モード選択時にフィルター幅を 500Hz に自動設定
- **POW UP/DOWN が 1% 刻みに** (旧: 5%)
- **PWR/SQL ダイアログの「%」を削除**
- **日本語メッセージをすべて英語に変更**

### Raspberry Pi FastAPI v2.02 対応（要 create_api.sh 再実行）
- CW モールス送信 API 追加
- BK-IN 設定 API 追加
- 時刻同期 API 追加
- APRS ビーコンのシンボル・コメント・宛先設定対応

## APK インストール手順

1. `Wifi_RIG_CTRL_v2.02_debug.apk` をダウンロード
2. Android の設定で「提供元不明のアプリ」のインストールを許可
3. APK をタップしてインストール

または GitHub からダウンロード:
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.02/M5CoreHamCAT_Android

## 必要なもの

- Android スマートフォン (Android 8.0 / API 26 以上)
- Raspberry Pi Zero 2W（FastAPI v2.02 セットアップ済み）
- Wi-Fi 環境

USB CW 電鍵を使う場合:
- M5ATOM Lite または M5ATOM S3 Lite（Wifi_Rig_CW ファームウェア書き込み済み）
- OTG 対応 USB ケーブル

## ソースコード

このフォルダの `app/`・`gradle/`・`build.gradle`・`settings.gradle` 等が Android Studio でビルド可能なソースコードです。

## ライセンス

Private use. All rights reserved.
