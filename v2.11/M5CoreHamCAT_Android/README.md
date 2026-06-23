# Wifi RIG CTRL for Android — v2.11

Android スマートフォンから Raspberry Pi 経由でアマチュア無線機を遠隔制御するアプリです。

## v2.11 変更点（v2.10 との比較）

### BLE CW 電鍵サポートを追加（DualKey-BLE / RemoteKeyer-BLE）

- **DualKey-BLE（M5AtomS3）または RemoteKeyer-BLE を BLE（Bluetooth LE）で Android にワイヤレス接続可能**
- Nordic UART Service（NUS）プロトコルを使用
- Android の Bluetooth 設定でペアリング後、BT ボタンをタップするだけで自動検出・接続
- **DualKey-BLE の USB CDC / BLE 自動切替機能**
  - 電源投入後10秒間: 左パドル（DAH）→ USB CDC モード、右パドル（DIT）→ BLE モード（デフォルト）
  - BLE モード中: アプリ（Android）からの USB データ受信で自動的に USB CDC モードへ再起動
  - USB CDC モード中: USB 切断で自動的に BLE モードへ再起動

### CW 接続状態表示を改善

- BLE 接続時: 「BLE」緑表示
- 未接続時: 「BLE」グレー表示

## APK インストール手順

1. `Wifi_RIG_CTRL_v2.11.apk` をダウンロード
2. Android の設定で「提供元不明のアプリ」のインストールを許可
3. APK をタップしてインストール

## Raspberry Pi セットアップ

`RaspberryPiSetup/readme.txt` を参照してください。

## 対応リグ

Hamlib 対応リグ全般（ICOM / YAESU / Kenwood 等）
- IC-7300、IC-705 での動作確認済み
- FT-991 での CW PTT ポーリングモード対応

## 必要環境

- Android 5.0 以上
- Raspberry Pi（Pi Zero 2W 推奨）
- Hamlib がインストールされた Pi OS
