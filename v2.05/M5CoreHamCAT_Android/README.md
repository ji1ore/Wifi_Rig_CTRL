# Wifi RIG CTRL for Android — v2.05

Android スマートフォンから Raspberry Pi 経由でアマチュア無線機を遠隔制御するアプリです。

## v2.05 変更点（v2.04 との比較）

### CW TX 機能強化

- **TX 終了方式を選択可能に**
  - 時間予測モード（デフォルト）: IC-7300 / IC-705 内部キーヤー向け
    `b` コマンドで送信後、推定時間だけ待機して PTT OFF
  - PTT ポーリングモード: CAT で PTT 状態を返すリグ（FT-991 等）向け
  - CW TX パネルの「TX end: PTT poll」スイッチで切替

- **CW TX 開始遅延を短縮**（600ms → 100ms）

- **送信途中で切れる問題を修正**
  - `K`（set_morse_code_speed）コマンドを削除
    → rigctld を 2 秒以上ブロックしていた原因を除去
  - IC-7300/IC-705 では WPM をリグのフロントパネルで設定

### S メーター修正

- IC-705 接続時に常時 S9 になっていた問題を修正
  - FastAPI の `l STRENGTH` 値の変換式を修正（`max(sig, 0.0)` → `sig`）

### BK-IN 自動取得

- リグの BK-IN 状態を 15 秒ごとに自動ポーリング
  - `u SBKIN` → `u FBKIN` の順で取得
  - アプリから変更せずともリグ側の設定を反映

### パス設定の汎用化

- Pi のユーザー名に依存しない実装に変更（`pi`、`pizero`、その他すべて対応）
- `Path(__file__).resolve()` でスクリプト自身のパスを動的取得

### Update Pi ボタン強化

- v2.03 以降が動作中の Pi であれば「Update Pi」ボタン一発で更新完了
- 複数ユーザー名へのフォールバック書き込みに対応

## APK インストール手順

1. `Wifi_RIG_CTRL_v2.05.apk` をダウンロード
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
