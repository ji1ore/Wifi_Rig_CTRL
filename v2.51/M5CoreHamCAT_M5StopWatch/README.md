# Wifi_RIG_CTRL for M5Stack StopWatch  Ver 2.50

M5Stack Stopwatch開発キット（466×466 円形AMOLED）を無線機（リグ）のリモートコントローラーにするファームウェアです。
Raspberry Pi（Wifi_Rig_CTRL FastAPI バックエンド）または ICOM WLAN Remote（CI-V over WiFi）経由でリグを操作し、
APRSビーコン送出や Hamlib / CI-V 経由でのPTT制御に対応します。

by JI1ORE

---

## 1. 同梱ファイル

| ファイル | 内容 |
|---|---|
| `src/` `include/` `platformio.ini` `merge_bin.py` | ソース一式（PlatformIO プロジェクト） |
| `M5CoreHamCAT_M5StopWatch_v2.50.bin` | ビルド済みファームウェア（マージ済み、0x0番地に書き込み可） |

書き込み済み `.bin` を直接使う場合は、esptool等で 0x0 番地に書き込んでください。

```bash
esptool.py --chip esp32s3 write_flash 0x0 M5CoreHamCAT_M5StopWatch_v2.50.bin
```

自分でビルドする場合は、PlatformIO（VSCode拡張 または CLI）でこのフォルダをプロジェクトとして開き、
`Build` / `Upload` を実行してください。

```bash
pio run             # ビルド
pio run -t upload    # ビルド＋書き込み
```

M5Stack Core2 / M5Stack Core2 Tough / M5Stack CoreS3 SE 向けはそれぞれ別フォルダで公開しています。

---

## 2. ハードウェア（外部接続なし）

M5Stack Stopwatch開発キットは、他機種（Core2 / CoreS3 SE）が使用する以下のポートを**持っていません**。
外部ハードウェアは一切不要です。

| 他機種の周辺機器 | M5StopWatchでの扱い |
|---|---|
| Unit Encoder（Port A ロータリーエンコーダ） | 本体の **BtnA / BtnB** で代替（増減操作） |
| Module Audio / ES8388（外部オーディオコーデック） | **非搭載**。内蔵マイク / スピーカーのみ使用 |
| NeoPixel LED（Port B, G9） | **非搭載**（送信状態は画面上のTXピルで表示） |
| 外部PTTスイッチ（Port B, G8） | **非搭載**。画面のPTTボタン＋本体ボタンで操作 |

### 本体物理ボタン（側面）の役割

M5Stack Stopwatchには側面に2つのボタンがあります（青=BtnA / 黄=BtnB）。

| ボタン | 通常操作 | PTTアーム中 | CWモード中 |
|---|---|---|---|
| **BtnA（青）** | 選択項目を**増加**（UP） | 押下中のみ**送信ON** | **短点（ドット）**送出 |
| **BtnB（黄）** | 選択項目を**減少**（DOWN） | 押下中のみ**送信ON** | **長点（ダー）**送出 |

### PTT操作の流れ

1. 画面の「**PTT**」ボタンをタップ → **アーム状態**（"PTT?"と表示）
2. **BtnA または BtnB を押している間のみ**送信（PTT ON）
3. ボタンを離す → 送信OFF

> **誤送信防止設計**：画面タップ（アーム）と物理ボタン押下の2アクションが必要です。
> 画面タップだけでは送信されません。

### CWモード

Modeボタンを長押しすると、PTTボタンがCWキーヤーモードに切り替わります。

- **BtnA（青）**：短点（ドット）
- **BtnB（黄）**：長点（ダー）

再度Modeボタン長押しで通常PTTモードに戻ります。

---

## 3. 初回起動〜接続までの流れ

### 3-1. 起動画面（スプラッシュ）

電源を入れると "Wifi_Rig_CTRL" のロゴ表示後、**Normal** / **Skip** の2つのボタンが表示されます。

- **Normal**：通常起動。Wi-Fi選択 → Raspberry Pi/CI-V接続 → リグ選択 → PTT方式選択、の順に進みます。
- **Skip**：前回保存済みの設定を使って、確認画面を飛ばして一気にメイン画面まで自動接続します。

画面左上の **「既定:Normal」/「既定:Skip」** トグルで、8秒無操作時の自動遷移先を設定できます。

### 3-2. Wi-Fi接続画面

周囲のWi-Fiをスキャンして一覧表示します。SSIDをタップしてパスワードを入力し接続します。

### 3-3. Raspberry Pi / CI-V 接続画面

画面右上の **「Pi Mode」/「CI-V」** で接続方式を切り替えます。

- **Pi Mode**（Raspberry Pi + rigctld/FastAPI バックエンド経由）
  - Hostname（またはIPアドレス。mDNS使用/不使用を切替可）
  - API Port / Audio Port
  - API Key（任意。バックエンド側で認証を要求する場合のみ）
- **CI-V**（ICOM WLAN Remote互換、無線機のWiFiに直結。Pi不要）
  - Host（無線機のIPアドレス）
  - Ctrl Port / CIV Port / Addr(hex)（CI-Vアドレス）
  - Username / Password（無線機側で設定した認証情報）

入力後「Connect」をタップします。

### 3-4. リグ選択画面

接続先（Pi/CI-V）から取得したリグ一覧から操作対象を選択します。

### 3-5. PTT方式選択画面

- **Wifi_PTT**：別売の Wifi_Rig_PTT リレー（Remotekeyer）へWiFi/UDP経由でPTT信号を送る方式。
  - PTT Host（mDNS名 or IPアドレス）、PTT Port を設定
- **Hamlib**：rigctld 経由で通常のCAT PTT制御を行う方式。
  - PTT Device（シリアルデバイス）、PTT Type（RTS/DTR）を設定

設定後「OK」でメイン画面へ遷移します。

---

## 4. メイン画面の操作方法

円形ディスプレイ（466×466）に最適化したUIを採用しています。

### 上段（ステータス表示）

- 左：接続中の機種名
- 右上のチップ（左から）：
  1. **テーマチップ**（例:"OCN"）：タップでデザインテーマを切替
  2. **昼夜チップ**（"NGT"/"DAY"）：タップで夜間⇄日中表示に切替
  3. **TXピル**：送信中は赤（Hamlib）またはオレンジ（APRS送信中）に点灯
- 周波数表示（大きく表示）：タップで周波数の直接入力画面へ
- Sメーター：受信信号強度をグラデーションで表示

### ボタン

| ボタン | 機能 |
|---|---|
| Freq | 周波数を選択（BtnA/BtnBで増減） |
| Step | 周波数ステップ幅を選択・変更 |
| Mode | 運用モード（SSB/CW/FM等）を選択・変更。**長押しでCWキーヤーモードに切替** |
| Wid | フィルタ幅を選択・変更 |
| Pow | 送信パワーを選択・変更 |
| SQL | スケルチレベルを選択・変更 |
| APRS | **短押し**：APRS送信のON/OFF切替。**長押し**：APRS設定画面を開く |
| PTT | 画面上のPTTアーム状態をON/OFF切替（実際の送信にはBtnA/BtnBの押下が必要） |
| Back | 接続を切ってリグ選択画面へ戻る |
| SPK | スピーカー（受信音声）のON/OFF |
| DOWN / UP | 選択中の項目の値を増減（BtnA/BtnBと同等） |

### BtnA / BtnB（本体側面ボタン）

Freq/Step/Mode/Wid/Pow/SQL のいずれかのボタンで項目を選択した状態で押すと、その項目の値を増減できます。
他機種のロータリーエンコーダに相当する操作です。

---

## 5. APRS設定（APRSボタン長押しで表示）

| 項目 | 内容 |
|---|---|
| APRS Enabled | APRS機能自体のON/OFF |
| Use GPS | ONの場合、Android(Tasker)から取得した位置情報を自動使用 |
| Latitude / Longitude | Use GPS=OFF時の手動位置情報 |
| APRS TXFreq | APRSビーコン送信周波数 |
| Baudrate | 1200 / 9600 |
| TX Interval | ビーコン送信間隔（30/60/120/180/300/600秒） |
| Callsign / SSID | 自局コールサイン・SSID |
| Path | WIDE1-1 / WIDE1-1,WIDE2-1 / WIDE2-1 / DIRECT / NONE |
| Symbol | APRSシンボル（アイコン表示） |
| Destination | APRS宛先コード（TNC種別） |
| Sound Device | Pi側で使用するサウンドデバイス |

---

## 6. 配色テーマ

| テーマ | 特徴 |
|---|---|
| OCN (Ocean) | 標準配色（ティール/ブルー系） |
| AMB (Amber) | 真空管・VFD風の琥珀色。夜間運用向けの落ち着いた配色 |
| MONO | 無彩色・最大コントラスト。視認性重視 |

各テーマとも昼間モード／夜間モードを持ち、画面右上のチップで独立して切替できます。設定は再起動後も保持されます。

---

## 9. v2.50 での変更点

- **新規追加**：M5Stack Stopwatch開発キット（466×466 円形AMOLED、ESP32-S3R8）対応
