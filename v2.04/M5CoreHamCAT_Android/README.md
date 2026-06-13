# Wifi RIG CTRL for Android — v2.04

Android スマートフォンから Raspberry Pi 経由でアマチュア無線機を遠隔制御するアプリです。

## v2.04 変更点（v2.03 との比較）

### 修正・改善

- **CW モード時の BK-IN 表示修正**
  - CW / CWR モードで BK-IN ステータスが常に OFF になっていた問題を修正
  - FastAPI `poll_signal()` に SBKIN → FBKIN ポーリングを追加（15 秒間隔）
  - TX 中はポーリングをスキップ（IC-7300 PA リレー保護）

- **Update Pi ボタンの信頼性向上**
  - `create_api.sh` 完了後の api.py 再送信にリトライを追加（最大 5 回 × 5 秒）
  - 再送信失敗時にエラーを画面表示するよう修正（従来は無視されていた）
  - 成功時のステータスメッセージを「Update complete. api.py deployed to Pi.」に修正

### Raspberry Pi FastAPI v2.04 対応（Update Pi ボタン または create_api.sh 再実行で更新）

- `poll_signal()` に CW モード時の BK-IN 自動取得を追加
  - SBKIN（セミブレークイン）→ FBKIN（フルブレークイン）の順で試行
  - 15 秒間隔、TX 中スキップ（IC-7300 リレー音対策）
- `create_api.sh` 埋め込み api.py にも同様の修正を反映

## APK インストール手順

1. `Wifi_RIG_CTRL_v2.04_debug.apk` をダウンロード
2. Android の設定で「提供元不明のアプリ」のインストールを許可
3. APK をタップしてインストール

または GitHub からダウンロード:
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.04/M5CoreHamCAT_Android

## 必要なもの

- Android スマートフォン (Android 8.0 / API 26 以上)
- Raspberry Pi Zero 2W（FastAPI v2.04 セットアップ済み）
- Wi-Fi 環境

FT8/FT4 デコードを使用する場合:
- Raspberry Pi の CPU が webft8 の WASM 実行に対応していること（Pi Zero 2W 以上推奨）

USB CW 電鍵を使う場合:
- M5ATOM Lite または M5ATOM S3 Lite（Wifi_Rig_CW ファームウェア書き込み済み）
- OTG 対応 USB ケーブル

## ソースコード

このフォルダの `app/`・`gradle/`・`build.gradle`・`settings.gradle` 等が Android Studio でビルド可能なソースコードです。

## ライセンス

Private use. All rights reserved.
