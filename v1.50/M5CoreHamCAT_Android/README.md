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
| APRS | ビーコン送信、GPS または手動座標 |
| プロファイル | 接続先・リグ設定を複数保存 |

---

## 必要なもの

### サーバー側（Raspberry Pi）

| 役割 | ポート | 備考 |
|---|---|---|
| REST API サーバー | 8000 | Hamlib ラッパー |
| 音声ストリームサーバー | 50000 | PCM HTTP ストリーム |
| WiFi PTT サーバー | 8888 | M5Atom 使用時 |
| CW キーサーバー | 8889 | USB 電鍵中継使用時 |

- Raspberry Pi (任意モデル)
- Hamlib 対応無線機
- （オプション）M5Atom — WiFi UDP PTT 用
- （オプション）CH340 / CDC-ACM USB シリアル CW キー

### Android 端末

- Android 5.0 (API 21) 以上
- WiFi 接続

---

## インストール

### APK をビルドする場合

1. [Android Studio](https://developer.android.com/studio) をインストール
2. このリポジトリをクローン
   ```
   git clone <repo-url>
   ```
3. Android Studio でプロジェクトを開く
4. **Build → Generate Signed Bundle/APK → APK** を選択
   - キーストア: `wifi_rig_ctrl.jks`（プロジェクトルートに配置）
   - エイリアス: `wifi-rig-ctrl`
5. 生成された APK を端末にインストール

### デバッグビルドを端末に直接書き込む場合

1. Android Studio で端末を USB 接続し、開発者オプション→USB デバッグを有効化
2. **Run → Run 'app'** を実行

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

### CW USB 電鍵

CH340 または CDC-ACM 互換の USB シリアル CW キーを接続すると自動認識されます。  
モードに応じて動作が切り替わります。

| モード | 動作 |
|---|---|
| CW / CWR | UDP パケットで Pi に打鍵タイミングを送信 |
| FM | VOX 的に PTT を制御しトーンを送信 |

CW キー設定画面で遅延補正 (delay_ms) を調整できます。

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
- **minSdk**: 21 (Android 5.0)
- **targetSdk**: 34 (Android 14)
- **バージョン**: 1.50 (versionCode 4)

主な依存ライブラリ:

| ライブラリ | 用途 |
|---|---|
| OkHttp 4.11 | HTTP ストリーミング・REST API |
| Gson 2.10 | JSON パース |
| usb-serial-for-android 3.8 | USB シリアル (CH340 / CDC-ACM) |
| Kotlin Coroutines 1.7 | 非同期処理 |
| AndroidX Navigation | フラグメント遷移 |

---

## ライセンス

Private use. All rights reserved.
