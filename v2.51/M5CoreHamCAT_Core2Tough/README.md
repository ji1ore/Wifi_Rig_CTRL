# Wifi_RIG_CTRL for M5Stack Core2 Tough  Ver 2.50

M5Stack Core2 Tough を無線機（リグ）のリモートコントローラーにするファームウェアです。
Raspberry Pi（Wifi_Rig_CTRL FastAPI バックエンド）または ICOM WLAN Remote（CI-V over WiFi）経由でリグを操作し、
APRSビーコン送出や、Wifi_Rig_PTT（別売のリレー基板）/ Hamlib 経由でのPTT制御に対応します。

by JI1ORE

---

## 1. 同梱ファイル

| ファイル | 内容 |
|---|---|
| `src/` `include/` `platformio.ini` `merge_bin.py` | ソース一式（PlatformIO プロジェクト） |
| `M5CoreHamCAT_Core2Tough_v2.50.bin` | ビルド済みファームウェア（マージ済み、0x0番地に書き込み可） |

書き込み済み `.bin` を直接使う場合は、esptool等で 0x0 番地に書き込んでください。

```bash
esptool.py --chip esp32 write_flash 0x0 M5CoreHamCAT_Core2Tough_v2.50.bin
```

自分でビルドする場合は、PlatformIO（VSCode拡張 または CLI）でこのフォルダをプロジェクトとして開き、
`Build` / `Upload` を実行してください。

```bash
pio run             # ビルド
pio run -t upload    # ビルド＋書き込み
```

M5Stack Core2 / M5Stack CoreS3 SE 向けはそれぞれ別フォルダ（`M5CoreHamCAT_Core2` / `M5CoreHamCAT_CoreS3SE`）で公開しています。

---

## 2. ハードウェア接続（Port の役割）

| Port | ピン | 用途 |
|---|---|---|
| **Port A** | G32 / G33 (+GND/5V) | **ロータリーエンコーダ**（機械式2相ロータリーエンコーダのA相/B相を直結。M5純正I2Cエンコーダユニットではなく、GPIO割り込みで直接読み取る方式） |
| **Port B** | G26 / G36 (+GND/5V) | **未使用**（G36は入力専用ピンでプルアップ非対応のため、エンコーダ用途では誤動作する。本ファームウェアでは使用しない） |
| **Port C** | G13 / G14 (+GND/5V) | **PTTキー入力（G13）＋ステータスLED（G14）**。詳細は下記 |

### Port C の配線（重要）

- **G13 ⟷ GND**：外部トグルスイッチ／フットスイッチ／ハンドマイクのPTT接点をここに接続します。
  G13は内部プルアップ（`INPUT_PULLUP`）されているため、スイッチはG13とPort CのGND間に接続するだけでOKです（プルアップ抵抗など追加部品は不要）。
- **G14**：WS2812互換のNeoPixel（1灯）のデータ入力。送受信状態をLEDの色で表示します（後述）。

> **重要な仕様**：実際に送信（PTT ON）されるのは、**画面上の「PTT」ボタンがON（`txEnabled`）** かつ **Port CのG13が閉（LOW）** の**両方が揃ったとき**だけです。
> 画面の「PTT」ボタンだけをタップしても、Port Cに何も接続していなければ送信されません。
> 想定運用：画面の「PTT」ボタンで送信を許可（アーム）しておき、実際の送信タイミングは外部スイッチ（フットスイッチ等）で操作する、という2段構えの安全設計です。

### ステータスLED（Port C, G14）の色

| 色 | 状態 |
|---|---|
| 赤 | **実際に送信中**（Hamlib / Wifi_Rig_PTT / CI-V いずれの方式でも） |
| 青 | 送信スタンバイ中（画面のPTTボタンON＝アーム済み、PTT方式=Wifi_Rig_PTT、実送信はしていない） |
| 緑 | 送信スタンバイ中（画面のPTTボタンON＝アーム済み、PTT方式=Hamlib または CI-V） |
| 消灯 | 画面のPTTボタンOFF（送信アーム解除中） |

### 画面の向き

Core2 Tough は本体ケースの都合上、起動時に画面を180度回転（上下反転）して表示します（ソース内 `#ifdef M5TOUGH` の回転処理）。

### オーディオ

内部I2C（G21/G22）およびI2S（M5Stack Module Audio 用の内部配線）でマイク入出力を行います。外部配線は不要です。

---

## 3. 初回起動〜接続までの流れ

### 3-1. 起動画面（スプラッシュ）

電源を入れると "Wifi_Rig_CTRL" のロゴ表示後、**Normal** / **Skip** の2つのボタンが表示されます。

- **Normal**：通常起動。Wi-Fi選択 → Raspberry Pi/CI-V接続 → リグ選択 → PTT方式選択、の順に毎回設定を確認しながら進みます。
- **Skip**：前回保存済みの設定（Wi-Fi・Pi接続先・リグ・PTT方式）を使って、確認画面を飛ばして一気にメイン画面まで自動接続します。

画面左上には **「既定:Normal」/「既定:Skip」** の小さいトグルがあります。これをタップして「既定:Skip」にしておくと、
**8秒間何もタップしなかった場合の自動遷移先がSkipになります**（Normal/Skipボタンをその場でタップした場合はそちらが優先されます）。
毎回Skipで起動したい場合は、一度この既定値をSkipにしておけば、以後はボタン操作なしで自動的にSkip起動されます。

### 3-2. Wi-Fi接続画面

周囲のWi-Fi（Androidテザリング等）をスキャンして一覧表示します。SSIDをタップしてパスワードを入力し接続します。

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
  - Timeout（無操作時の画面消灯までの時間）

入力後「Connect」をタップします。

### 3-4. リグ選択画面

接続先（Pi/CI-V）から取得したリグ一覧から操作対象を選択します。

### 3-5. PTT方式選択画面

- **Wifi_PTT**：別売の Wifi_Rig_PTT リレー（Remotekeyer）へWiFi/UDP経由でPTT信号を送る方式。ICOM機でHamlib経由PTTだと変調が乗らない問題を回避するために用意された方式です。
  - PTT Host（mDNS名 or IPアドレス）、PTT Port を設定
- **Hamlib**：rigctld 経由で通常のCAT PTT制御を行う方式。
  - PTT Device（シリアルデバイス）、PTT Type（RTS/DTR）を設定

設定後「OK」でメイン画面へ遷移します。

---

## 4. メイン画面の操作方法

### 上段（ステータス表示）

- 左：接続中の機種名
- 右上のチップ（左から）：
  1. **テーマチップ**（例:"OCN"）：タップでデザインテーマを **OCN → AMB → MONO** の順に切替
  2. **昼夜チップ**（"NGT"/"DAY"）：タップで夜間⇄日中表示に切替。日中モードでは画面輝度を最大にし、明るい場所でも見やすい高コントラスト配色になります
  3. **TXピル**：送信中は赤（Hamlib）またはオレンジ（APRS送信中）に点灯

  ※ テーマ・昼夜チップは**送信中（PTT ON）でも操作可能**です。

- 周波数表示（大きく表示）：タップで周波数の直接入力画面へ（送信中はタップ無効）
- Sメーター：受信信号強度をグラデーションで表示
- ステータスチップ：ST(ステップ幅) / PW(パワー) / MD(モード) / WD(フィルタ幅)

### ボタン（4×3グリッド）

| ボタン | 機能 |
|---|---|
| Freq | 周波数を選択（ロータリーエンコーダで増減） |
| Step | 周波数ステップ幅を選択・変更 |
| Mode | 運用モード（SSB/CW/FM等）を選択・変更 |
| Wid | フィルタ幅を選択・変更 |
| Pow | 送信パワーを選択・変更 |
| SQL | スケルチレベルを選択・変更 |
| APRS | **短押し**：APRS送信のON/OFF切替（要:APRS Enabled設定＋GPS位置情報取得済み）。<br>**長押し（0.7秒以上）**：APRS設定画面を開く |
| PTT | 画面上のPTTアーム状態をON/OFF切替（実際の送信にはPort Cの外部スイッチも必要。上記「Port Cの配線」参照） |
| Back | 接続を切ってリグ選択画面へ戻る |
| SPK | スピーカー（受信音声）のON/OFF |
| DOWN / UP | 選択中の項目（Freq/Step/Mode/Wid/Pow/SQL）の値をボタンでも増減可能 |

### ロータリーエンコーダ（Port A）

Freq/Step/Mode/Wid/Pow/SQL のいずれかのボタンで項目を選択した状態で回すと、その項目の値を増減できます。

---

## 5. APRS設定（APRSボタン長押しで表示）

| 項目 | 内容 |
|---|---|
| APRS Enabled | APRS機能自体のON/OFF |
| Use GPS | ONの場合、Android(Tasker)から取得した位置情報を自動使用（手動入力欄はロックされる） |
| Latitude / Longitude | Use GPS=OFF時の手動位置情報 |
| APRS TXFreq | APRSビーコン送信周波数 |
| Baudrate | 1200 / 9600 |
| TX Interval | ビーコン送信間隔（30/60/120/180/300/600秒） |
| Callsign / SSID | 自局コールサイン・SSID |
| Path | WIDE1-1 / WIDE1-1,WIDE2-1 / WIDE2-1 / DIRECT / NONE |
| Symbol | APRSシンボル（アイコン表示） |
| Destination | APRS宛先コード（TNC種別） |
| Sound Device | Pi側で使用するサウンドデバイス |

**GPS位置情報について**：Use GPS=ON時は、Android側でTasker HTTPサーバー（設定画面下部に表示されるポート・パスを参照）を起動しておく必要があります。
M5はWiFi接続後、定期的にAndroidへ位置情報を取りに行き、取得できていない状態でAPRS送信を開始しようとすると「GPS位置情報未取得」の警告が出て開始できません（その場合はその場で自動的に一度再取得を試みます）。

---

## 6. 配色テーマ

| テーマ | 特徴 |
|---|---|
| OCN (Ocean) | 標準配色（ティール/ブルー系） |
| AMB (Amber) | 真空管・VFD風の琥珀色。夜間運用向けの落ち着いた配色 |
| MONO | 無彩色・最大コントラスト。視認性重視 |

各テーマとも昼間モード（明るい背景・高コントラスト）／夜間モード（暗い背景）を持ち、画面右上のチップで独立して切替できます。設定は本体に保存され、再起動後も保持されます。

---

## 9. v2.50 での変更点（v2.30 との比較）

- バージョン表記を 2.33 に更新
- APRSで無線機内蔵APRSモデム（FTX-1等、TX Method=Rig）を使用している場合、
  M5側（Android中継）のGPS位置情報が未取得・古くてもAPRSを開始・継続できるように
  修正。この方式ではリグ自身のGPSで位置情報が付加されるため、M5側のGPS取得状況を
  条件にするのは誤りだった（従来はGPS未取得時に開始を拒否、または位置情報が
  途絶えると自動停止してしまっていた）

## 8. v2.30 での変更点（v2.20 との比較）

- バージョン表記を 2.30 に更新
- Pi 経由モードは **Raspberry Pi 側 API**（`api.py`）の更新で、接続中の機種が
  実際にサポートするモード一覧を動的に検出するようになった（例: FT-991 の C4FM、
  IC-705 の D-STAR など、従来は選べなかった機種固有のデジタルモードが Mode 選択に
  表示されるようになる）。M5本体にはPi更新機能がないため、反映するには
  **Android/iOSアプリの「Update Pi」ボタンを一度使うか、Raspberry Piのセットアップ
  をやり直す**必要がある（詳細は `RaspberryPiSetup/readme.txt` を参照）
- CI-V直結モードのMode選択に D-STAR（IC-705/IC-9700）と WFM を追加
  （従来はAndroid/iOS版にはあったがM5版のみ選択肢になかった）
- RasPi接続画面で「Scan」から検出した機器のIPをコピーした際、自動的に
  「Use IP」モードへ切り替わるようにした（API Port / Audio Portはコピーせず、
  既存の設定を維持する）
- RasPi接続画面のSCAN／Connectボタンの横幅を調整し、「Connect」ラベルが
  省略されず表示されるようにした
- CI-Vモードのフィルタ選択を修正。D-STAR時はIC-705の仕様上フィルタ0x01のみ
  受け付けるため、幅指定に関わらずフィルタ0x01を送信するようにした
- APRS設定画面で Enabled / TX Method（リグモデム使用有無）を変更してOKを
  押した際、画面遷移や再接続なしにその場でAPRS送受信を停止・再起動するように改善

## 7. v2.20 での変更点（v2.18 との比較）

- バージョン表記を 2.20 に更新
- CI-Vモード時のポーリング間隔を緩和（200ms→500ms）し、無線機側の負荷によるWiFi切断を回避
- CI-Vモード時のPTT OFFにリトライ処理を追加（送信固着防止）
- VFO A/B・MAIN/SUBの自動判別・表示に対応
- APRS受信ビーコン一覧画面（距離・方位表示）を追加
- streamTask強制終了処理・ENOMEM対策など安定性改善
