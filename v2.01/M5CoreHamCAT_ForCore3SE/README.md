# M5CoreHamCAT for CoreS3/CoreS3 SE — v2.01

M5Stack CoreS3 / CoreS3 SE 用の Wifi_Rig_CTRL ファームウェアです。  
Raspberry Pi FastAPI サーバー v2.01 と組み合わせて使用します。

## 必要なハードウェア

- M5Stack CoreS3 または CoreS3 SE
- （オプション）M5Stack Unit-Encoder — 周波数ステップ・パラメータ変更用
- （オプション）M5Stack Module-Audio — 外部スピーカー出力用

## 機能

- Raspberry Pi に WiFi 接続して無線機を遠隔制御
- 周波数・モード・出力・スケルチ・フィルター幅の操作
- PCM 音声ストリーミング受信（内蔵スピーカー / 外部スピーカー）
- マイク音声送信（PTT）
- Hamlib HTTP PTT / WiFi UDP PTT (M5Atom) 対応
- APRS ビーコン送信
- スキップモード（保存済み設定で即起動）

## 書き込み方法

### 方法 1 — バイナリを直接書き込む（推奨）

1. `firmware/M5CoreHamCAT_ForCore3SE_v2.01.bin` を使用
2. [esptool](https://docs.espressif.com/projects/esptool/en/latest/) または [M5Burner](https://docs.m5stack.com/en/download) でフラッシュ

**esptool を使う場合:**
```bash
esptool.py --chip esp32s3 --port <COMポート> write_flash 0x0 M5CoreHamCAT_ForCore3SE_v2.01.bin
```

### 方法 2 — PlatformIO でビルドして書き込む

1. [PlatformIO IDE](https://platformio.org/) をインストール
2. このフォルダをプロジェクトとして開く（`platformio.ini` があるフォルダ）
3. `pio run --target upload` を実行

## ポート構成（Raspberry Pi 側）

| 役割 | ポート |
|---|---|
| REST API | TCP 8000 |
| 音声ストリーム | TCP 50000 |
| WiFi PTT | UDP 8888 |
| CW キー | UDP 8889 |

## Raspberry Pi セットアップ

`../RaspberryPiSetup/` 内のスクリプトを使用してください。  
詳細は `readme.txt` を参照。

## ライセンス

Private use. All rights reserved.