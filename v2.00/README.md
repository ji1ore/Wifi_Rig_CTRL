# Wifi RIG CTRL for Android

Raspberry Pi 経由でアマチュア無線機を Android スマートフォンから遠隔操作するアプリです。

## 機能

| カテゴリ | 内容 |
|---|---|
| リグ制御 | 周波数・モード・出力・スケルチ・フィルター幅 (Hamlib REST API) |
| 音声受信 | PCM ストリーミング受信、サンプリングレート選択 (8k〜48kHz) |
| 音声送信 | マイク音声を HTTP で Pi へ送信、PTT 連動 |
| PTT | Hamlib (HTTP) / WiFi UDP PTT (M5Atom) / 外部 PTT 自動追従 |
| CW デコード | リアルタイム FFT、最大 5 チャンネル同時、自動速度追従 |
| CW キー中継 | USB 縦振り電鍵 → Pi 経由で送信 (CW/FM モード対応) |
| FT8 / FT4 | 受信デコード表示・送信（Pi 側 ft8wav デコーダ使用）※実験的機能 |
| APRS | ビーコン送信、GPS または手動座標 |
| プロファイル | 接続先・リグ設定を複数保存 |

> **FT8 / FT4 について**: この機能は実験的なものです。ft8wav バイナリを Raspberry Pi に配置する必要があります。受信状況や環境によってはすべての信号がデコードされない場合があります。

---

## 必要なもの

### サーバー側（Raspberry Pi）

| 役割 | ポート | 備考 |
|---|---|---|
| REST API サーバー | 8000 | Hamlib ラッパー (`api.py`) |
| 音声ストリームサーバー | 50000 | PCM HTTP ストリーム (`api.py` 別インスタンス) |
| WiFi PTT サーバー | 8888 | M5Atom 使用時 |
| CW キーサーバー | 8889 | USB 電鍵中継使用時 |

- Raspberry Pi (Zero W / 3 / 4 など)
- Hamlib 対応無線機
- USB オーディオコーデック (ALSA デバイス)
- （オプション）M5Atom — WiFi UDP PTT 用
- （オプション）CH340 / CDC-ACM USB シリアル CW キー
- （FT8/FT4 使用時）ft8wav バイナリ（create_api.sh が自動ビルドを試みます）

### Android 端末

- Android 8.0 (API 26) 以上
- WiFi 接続（同一 LAN またはリモート）

---

## インストール

### 1. Raspberry Pi 側セットアップ

#### 必要パッケージをインストール

```bash
sudo apt update
sudo apt install -y python3-pip ffmpeg hamlib rigctld
pip3 install fastapi uvicorn
```

#### API サーバーを配置・起動

`create_api.sh` を実行すると `api.py` の生成と ft8wav のビルドを自動で行います。

```bash
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v2.00/RaspberryPiSetup/create_api.sh
chmod +x create_api.sh && bash create_api.sh
```

または手動で `app/src/main/assets/api.py` を Pi にコピーします。

```bash
scp app/src/main/assets/api.py pi@<PI_IP>:~/api.py
```

**ポート 8000（REST API + リグ制御）を起動:**

```bash
uvicorn api:app --host 0.0.0.0 --port 8000
```

**ポート 50000（音声ストリームサービス）を起動:**

```bash
IS_AUDIO_SERVICE=1 uvicorn api:app --host 0.0.0.0 --port 50000
```

#### systemd サービス例

`/etc/systemd/system/fastapi.service`:
```ini
[Unit]
Description=Wifi RIG CTRL API (port 8000)
After=network.target

[Service]
ExecStart=/usr/local/bin/uvicorn api:app --host 0.0.0.0 --port 8000
WorkingDirectory=/home/pi
User=pi
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

`/etc/systemd/system/fastapi-audio.service`:
```ini
[Unit]
Description=Wifi RIG CTRL Audio Service (port 50000)
After=network.target

[Service]
Environment=IS_AUDIO_SERVICE=1
ExecStart=/usr/local/bin/uvicorn api:app --host 0.0.0.0 --port 50000
WorkingDirectory=/home/pi
User=pi
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable fastapi fastapi-audio
sudo systemctl start  fastapi fastapi-audio
```

---

### 2. Android アプリのインストール

#### APK を直接インストールする場合

1. `Wifi_RIG_CTRL_v2.00.apk` をスマートフォンにコピー
2. Android の設定 →「提供元不明のアプリ」→ インストールを許可
3. APK をタップしてインストール

#### APK をビルドする場合

1. [Android Studio](https://developer.android.com/studio) をインストール
2. このリポジトリをクローン
3. Android Studio でプロジェクトを開く
4. **Build → Generate Signed Bundle/APK → APK** を選択
5. 生成された APK を端末にインストール

---

## 初期設定

### 1. 接続設定

アプリ起動後、接続画面で以下を入力します。

| 項目 | 説明 | デフォルト |
|---|---|---|
| Host | Raspberry Pi の IP または ホスト名 | — |
| API Port | REST API ポート | 8000 |
| Audio Port | 音声ストリームポート | 50000 |
| mDNS | `.local` ホスト名で解決する | OFF |
| API Key | 認証キー（サーバーで設定した場合） | 空 |

### 2. リグ選択

接続後に表示されるリグ一覧からリグを選択し、CAT ポート・ボーレート・PTT 設定を行います。

### 3. サンプリングレート

音声ストリームのサンプリングレートを選択します。通常は **8000 Hz** (CW/SSB) または **16000 Hz** で十分です。VPN 環境では低いレートの方が安定します。

---

## 主要機能の使い方

### 音声受信（SPK）

メイン画面の **SPK** ボタンでストリーミング受信を開始/停止します。

### PTT / 音声送信

**PTT** ボタンを押している間、マイク音声を無線機から送信します。  
WiFi PTT モード（M5Atom）を使用する場合は、PTT 設定画面でホスト/ポートを設定してください。

### CW デコード

**CW** ボタンを有効にすると、受信音声から CW 符号をリアルタイムでデコードします。

- 最大 5 チャンネル同時追跡（300〜3000 Hz）
- 速度自動追従（適応 dit 長推定）
- マルチ RX 表示でチャンネルごとのデコード文字を確認可能

### FT8 / FT4（実験的機能）

> **注意**: この機能は実験的です。ft8wav バイナリが Raspberry Pi に配置されていない場合はデコードは動作しません。すべての信号がデコードされるとは限りません。

メイン画面の **FT8** タブに切り替えると、Pi 側の `ft8wav` デコーダによるリアルタイムデコードが始まります。

- 受信メッセージは 15 秒サイクルで表示（FT4 は 7.5 秒）
- 送信する場合はリストからコールサインを選択して TX

### APRS

APRS 設定画面でコールサイン・SSID・パス・間隔・周波数を設定後、**APRS** ボタンで送信開始します。GPS 座標または手動座標を選択できます。

---

## ポート一覧

```
Pi REST API      : TCP 8000
音声ストリーム    : TCP 50000 (設定変更可)
WiFi PTT (M5Atom): UDP 8888
CW キー中継      : UDP 8889
```

---

## ビルド情報

- **言語**: Kotlin
- **minSdk**: 26 (Android 8.0)
- **targetSdk**: 35 (Android 15)
- **バージョン**: 2.00 (versionCode 200)

主な依存ライブラリ:

| ライブラリ | 用途 |
|---|---|
| OkHttp 4.12 | HTTP ストリーミング・REST API |
| Gson 2.11 | JSON パース |
| usb-serial-for-android 3.8 | USB シリアル (CH340 / CDC-ACM) |
| Kotlin Coroutines 1.8 | 非同期処理 |
| AndroidX Navigation | フラグメント遷移 |

---

## ライセンス

Copyright (c) 2026 JI1ORE — Private use. All rights reserved.

### オープンソースコンポーネント

| コンポーネント | ライセンス | 用途 |
|---|---|---|
| ft8_lib (kgoba) | MIT | FT8/FT4 デコード (Android NDK) |
| OkHttp | Apache 2.0 | HTTP 通信 |
| Gson | Apache 2.0 | JSON パース |
| usb-serial-for-android | LGPL 2.1 | USB シリアル |
| Kotlin / AndroidX | Apache 2.0 | フレームワーク |
| ft8wav (Pi側のみ) | **GPL 3.0** | FT8/FT4 デコーダ (外部プロセス) |
| Hamlib (Pi側のみ) | LGPL 2.1+ | CAT 制御 |
| Direwolf (Pi側のみ) | **GPL 2.0** | APRS TNC |
| FFmpeg (Pi側のみ) | LGPL 2.1+ | 音声キャプチャ |

詳細は [LICENSES.md](LICENSES.md) を参照してください。

**ft8wav (GPL 3.0) について**: Pi 上で別プロセスとして実行するため、Android アプリ本体への GPL 伝播はありません。ft8wav のソースは https://github.com/ayoungblood/ft8wav で公開されています。
