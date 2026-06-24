# Wifi RIG CTRL for Android — v2.12

Android スマートフォンから Raspberry Pi 経由でアマチュア無線機を遠隔制御するアプリです。

## v2.12 変更点（v2.11 との比較）

### 新機能

#### Hamlib 4.7.2 サポート追加

- apt では Hamlib 4.7.x を取得できないため、ソースビルドに対応
- インストール先: `~/.local/bin/rigctld`（sudo 不要・RPATH 付きビルド）
- アプリの「Update」→「Update Hamlib」ボタンから実行（Pi Zero で 30〜60 分）
- `rigctld` は `~/.local/bin/rigctld`（4.7.2）を優先使用し、なければシステム版にフォールバック

#### Update 画面を新設（UI 整理）

- Update Pi・Update Hamlib・Pi Log・Hamlib Log を 1 画面に統合
- ログエリア（緑モノスペース）でビルド進捗をリアルタイム確認
- Reload ボタンで手動更新
- RIG CONNECT 画面のボタンを 6 個（2 行×3 列）に整理

#### About 画面に Pi API バージョン・Hamlib バージョンを表示

- 接続中の Pi の FastAPI バージョンと rigctld バージョンを確認可能

### バグ修正・改善

- **webFT8 画面の周波数変更バグを修正**
  - Rig→TX / RX フィールドを変更すると無線機の周波数が変わっていた問題（横向き時のみ発生）
  - 原因: DOM change イベントリスナーがオーディオオフセット等の値を周波数と誤検知していた

- **WID / POW / SQL を ◀▶ ボタンで増減可能に**
  - WID（フィルター幅）±100 Hz、POW（送信出力）±1%、SQL（スケルチ）±1%
  - WID / POW / SQL ボタンを押して選択状態にしてから ◀▶ で操作

- **Update Pi 後の「Pi API バージョン不一致」表示を修正**
  - アプリ側の期待バージョン定数が v2.11 のままだったため、Update 後も不一致が出ていた問題を修正

## APK インストール手順

1. `Wifi_RIG_CTRL_v2.12.apk` をダウンロード
2. Android の設定で「提供元不明のアプリ」のインストールを許可
3. APK をタップしてインストール

## Raspberry Pi セットアップ

`RaspberryPiSetup/readme.txt` を参照してください。

v2.03 以降からのアップグレード: アプリの「Update」→「Update Pi」ボタンで自動更新  
v2.12 から Hamlib 4.7.2 対応: アプリの「Update」→「Update Hamlib」ボタンで追加インストール  
v2.02 以前からのアップグレード: 初回のみ手動 scp が必要

## 対応リグ

Hamlib 対応リグ全般（ICOM / YAESU / Kenwood 等）
- IC-7300、IC-705 での動作確認済み
- FT-991A での動作確認済み

## 必要環境

- Android 5.0 以上
- Raspberry Pi（Pi Zero 2W 推奨）
- Hamlib がインストールされた Pi OS
