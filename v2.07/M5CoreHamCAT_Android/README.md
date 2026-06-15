# Wifi RIG CTRL for Android — v2.07

Android スマートフォンから Raspberry Pi 経由でアマチュア無線機を遠隔制御するアプリです。

## v2.07 変更点（v2.06 との比較）

### CW TX タイミング修正

- **`_abort_morse()` の `\stop_morse` 実行タイミングを修正**
  - IC-7300 は `\stop_morse` CAT コマンド未対応のため、rigctld が 2 秒タイムアウトしていた
  - アクティブな送信セッションを中断する場合のみ `\stop_morse` を呼ぶよう変更
  - 初回送信時の 2 秒遅延を解消：サイドトーンと同時に送信開始

- **CW TX 開始遅延を調整**（100ms → 300ms）
  - Pi Zero 2W での rigctld ポーリング残処理をより確実に待機

### Pi 側音声 TX 修正

- **音声 TX 時に `_mgr_rx`（ffmpeg キャプチャ）を確実に停止**
  - ALSA デバイス競合を防止

### Update Pi 強化

- **root 実行時の fastapi ディレクトリ自動検出**
  - `SUDO_USER` 未設定・root 実行時も `/home/*/fastapi` を検索して正しいパスを使用
- **`/root` パスの自動修正**
  - 旧セットアップで `/root/fastapi` になったサービスファイルを自動修正
- **`fix_service.sh` を自動生成**
  - サービスファイルのパス修正と再起動をワンコマンドで実行
- **api.py の自己パス検出を改善**
  - `Path(__file__).resolve()` で常に正確なパスを取得（ユーザー名非依存）

### pi ユーザー名依存の排除

- `create_api.sh` のすべての `/home/pi` ハードコードを除去
- 任意のユーザー名（`pi`、`pizero`、その他）で動作

## APK インストール手順

1. `Wifi_RIG_CTRL_v2.06.apk` をダウンロード
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
