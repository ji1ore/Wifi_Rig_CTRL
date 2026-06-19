# Wifi RIG CTRL for Android — v2.10

Android スマートフォンから Raspberry Pi 経由でアマチュア無線機を遠隔制御するアプリです。

## v2.10 変更点（v2.09 との比較）

### ノイズリダクション強化

- **NR レベルを 3 段階から 5 段階に拡張**
  - Level 1 (Light): `afftdn=nf=-30:nr=15`
  - Level 2 (Medium): `afftdn=nf=-25:nr=20`
  - Level 3 (Strong): `afftdn=nf=-20:nr=25:tn=1`（ノイズ追従）
  - Level 4 (Max): `afftdn=nf=-15:nr=33:tn=1` + `anlmdn=s=7`（二重フィルター）
  - Level 5 (Neural): `arnndn`（ニューラルネットワーク）+ `afftdn` 強
- **NR をデュアルサーバー構成に対応**（apiPort / audioPort 両方に同期送信）
- **NR モデルパスをユーザー名非依存に変更**（api.py と同ディレクトリの `model.rnnn` を参照）
- **SQL ボタン長押し**で 0→1→2→3→4→5→0 循環

## APK インストール手順

1. `Wifi_RIG_CTRL_v2.10.apk` をダウンロード
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
