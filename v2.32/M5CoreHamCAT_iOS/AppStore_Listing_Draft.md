# WifiRigCTRL — App Store 掲載情報 下書き

作成日: 2026-07-26 / 更新日: 2026-07-31 / 対象バージョン: 2.32
Android版 (Wifi_Rig_CTRL) を参考に、iOS版の実機能に合わせて作成。
> iOS版の対応: RX音声再生 / マイク送信(PTT) / 直接CI-V接続(IC-705・IC-9700) / CWデコード・送信 / BLE CWキーヤー / FT8・FT4 / APRSビーコン / 複数プロファイル / APIキー認証。
> ※Android版にある「USB CWリレー」はiOS版では非対応のため記載していません。

---

## 1. 基本情報

| 項目 | 内容 | 文字数上限 |
|---|---|---|
| App名 | WifiRigCTRL | 30 |
| サブタイトル（日本語） | Wi-Fiでリグをリモート制御 | 30 |
| サブタイトル（英語） | Wi-Fi remote rig control | 30 |

---

## 2. プロモーション用テキスト（170字以内・後から差し替え可）

### 日本語
```
Raspberry Pi や IC-705/IC-9700 と同じWi-Fiにつなぐだけ。周波数・モード・Sメーター表示、受信音声、PTT送信、CW、FT8、APRSまで。アマチュア無線機をスマホからリモート操作。
```

### English
```
Connect over Wi-Fi to your Raspberry Pi or IC-705/IC-9700. View frequency, mode and S-meter, hear RX audio, transmit with PTT, send CW, FT8 and APRS — remote rig control from your iPhone.
```

---

## 3. 説明（Description・4000字以内）

### 日本語
```
WifiRigCTRL は、アマチュア無線機をiPhone/iPadからWi-Fi経由でリモート操作するためのコントローラーアプリです。

接続方法は2通り:
1. Raspberry Pi 経由（Hamlib/FastAPI）— 幅広い機種のCAT制御・受信音声・CW・FT8・APRSに対応
2. 直接CI-V接続 — IC-705 / IC-9700 と同じWi-Fiに接続し、Raspberry Pi なしで基本操作

■ 主な機能
・受信周波数・モード・信号強度(Sメーター)のリアルタイム表示
・周波数、モード、送信出力、スケルチ、フィルタ幅の変更
・受信音声のスピーカー再生（ノイズリダクション対応）
・PTTのON/OFFとマイク音声送信
・Wi-Fi PTT（M5Atom等の外部デバイス経由のPTT制御）
・CWデコード（複数局同時／最大5局）とCW送信（プリセット・WPM調整）
・BLE CWキーヤー対応（DualKey-BLE / RemoteKeyer-BLE をBluetoothで接続）
・FT8/FT4 のデコード・送信
・APRSビーコン送信（スマホGPS対応）
・複数プロファイル対応（接続先の切り替え）
・APIキー認証、WireGuard VPN 経由のリモートアクセスに対応
・バンドメモリー（Preset / User / POTA / SOTAの4タブ）
・レピータ設定（CI-V直接接続時：CTCSSトーン・オフセット方向/周波数）

■ 動作に必要なもの（重要）
本アプリ単体では動作しません。次のいずれかのユーザー所有ハードウェアが必要です。
・セットアップ済みの Raspberry Pi Zero 2W（無線機に接続、Hamlib/FastAPI導入済み）、または
・IC-705 / IC-9700（直接CI-V接続の場合）
いずれもiPhoneと同一のWi-Fiネットワークに接続してください。
Raspberry Pi のセットアップ手順・ファームウェアは下記GitHubで公開しています。
https://github.com/ji1ore/M5CoreHamCAT

■ 対象ユーザー
アマチュア無線の免許を持ち、対応する無線機環境をお持ちの方向けのアプリです。

※本アプリは広告・使用状況の追跡を一切含みません。取得した情報は端末内でのみ使用し、外部サーバーへ送信しません。
```

### English
```
WifiRigCTRL is a controller app that lets you remotely operate an amateur (ham) radio transceiver from your iPhone or iPad over Wi-Fi.

Two ways to connect:
1. Via Raspberry Pi (Hamlib/FastAPI) — CAT control for a wide range of rigs, plus RX audio, CW, FT8 and APRS
2. Direct CI-V — connect to an IC-705 / IC-9700 on the same Wi-Fi, no Raspberry Pi required for basic operation

■ Features
- Real-time display of RX frequency, mode and signal strength (S-meter)
- Change frequency, mode, TX power, squelch and filter width
- Play received audio through the speaker (with noise reduction)
- PTT ON/OFF and microphone audio transmission
- Wi-Fi PTT (PTT control via external devices such as M5Atom)
- CW decoding (multi-station, up to 5) and CW transmit (presets, WPM control)
- BLE CW keyer support (connect DualKey-BLE / RemoteKeyer-BLE over Bluetooth)
- FT8/FT4 decode and transmit
- APRS beacon transmission (with smartphone GPS)
- Multiple profiles (switch between connection targets)
- API key authentication and remote access over WireGuard VPN
- Band memory panel (Preset / User / POTA / SOTA — 4 tabs)
- Repeater settings (CI-V direct mode: CTCSS tone, offset direction & frequency)

■ What you need (important)
This app does not work on its own. You must have one of the following (your own hardware):
- A set-up Raspberry Pi Zero 2W connected to your radio (Hamlib/FastAPI installed), or
- An IC-705 / IC-9700 (for direct CI-V connection)
Connect it to the same Wi-Fi network as your iPhone.
Raspberry Pi setup instructions and firmware are published on GitHub:
https://github.com/ji1ore/M5CoreHamCAT

■ Who it's for
For licensed amateur radio operators who have a compatible radio setup.

This app contains no ads and no usage tracking. Any information is used only on your device and is never sent to external servers.
```

---

## 4. キーワード（100字以内・カンマ区切り・スペースなし推奨）

```
ham radio,amateur radio,rig control,CAT,Hamlib,APRS,CW,morse,FT8,CI-V,IC-705,transceiver
```
（89文字。必要なら "IC-705" や "transceiver" を削って調整）

---

## 5. サポートURL / マーケティングURL（案）

- サポートURL: `https://github.com/ji1ore/M5CoreHamCAT`（Issues でも可）
  - もしくは問い合わせ用に簡単なGitHub Pages / Notion公開ページ
- マーケティングURL（任意）: 同上

---

## 6. プライバシーポリシー（そのまま公開可・iOS向けに追記済み）

> Android版の privacy_policy をベースに、iOS特有の「ローカルネットワーク」「Bluetooth」を追記。
> このMarkdownを GitHub Pages / Gist / Notion 等に貼り、公開URLを App Store Connect の
> 「プライバシーポリシーURL」に登録してください。

```markdown
# Privacy Policy / プライバシーポリシー

**WifiRigCTRL (iOS)**
Last updated: 2026-07-07

---

## 日本語

### 収集する情報
WifiRigCTRL は、以下の情報のみを端末内でローカルに使用します。
外部サーバー（Google・広告ネットワーク等）への送信は一切行いません。

| 情報 | 用途 | 保存先 |
|---|---|---|
| 接続先ホスト名・ポート・APIキー | ユーザーが設定した Raspberry Pi / 無線機への接続情報 | 端末内 |
| APRS コールサイン・SSID・座標 | APRS ビーコン送信の設定 | 端末内 |
| GPS 位置情報（任意） | APRSビーコン／FT8グリッドロケーターへの座標付加（明示的に許可した場合のみ） | 送信されない・保存されない |
| マイク音声 | PTT送信中にのみ無線機へストリーミング（録音・保存なし） | 送信されない・保存されない |
| ローカルネットワーク通信 | 同一LAN内の Raspberry Pi / 無線機 / M5デバイスとの制御通信 | — |
| Bluetooth (BLE) | CWキーヤー（DualKey-BLE 等）への接続 | — |

### 第三者への情報提供
本アプリはいかなる情報も第三者に提供しません。

### 広告・アナリティクス
本アプリは広告および使用状況の追跡機能を含みません。

### 変更について
プライバシーポリシーを変更する場合は、このページを更新します。

### お問い合わせ
jiro.ueno0213@gmail.com

---

## English

### Information We Collect
WifiRigCTRL uses the following information locally on your device only.
No data is transmitted to external servers (Google, ad networks, etc.).

| Information | Purpose | Storage |
|---|---|---|
| Host, port, API key | Connection settings for your Raspberry Pi / radio | On device |
| APRS callsign, SSID, coordinates | APRS beacon configuration | On device |
| GPS location (optional) | Appended to APRS beacon / FT8 grid locator when explicitly permitted | Not transmitted or stored |
| Microphone audio | Streamed to the radio only while PTT is active (not recorded) | Not transmitted or stored |
| Local network access | Control communication with your Raspberry Pi / radio / M5 devices on the same LAN | — |
| Bluetooth (BLE) | Connection to a CW keyer (e.g. DualKey-BLE) | — |

### Third-Party Sharing
We do not share any information with third parties.

### Advertising & Analytics
This app contains no advertisements and no usage tracking.

### Changes
We will update this page if the privacy policy changes.

### Contact
jiro.ueno0213@gmail.com
```

---

## 7. App Store Connect「Appのプライバシー」入力ガイド

審査画面の「Appのプライバシー」では、以下のように申告するのが実態に合っています。

- **データを収集していますか？** → **いいえ（No, we do not collect data from this app）**
  - 理由: 位置情報・マイク音声・接続設定はすべて端末内でのみ利用され、開発者や第三者のサーバーへ送信されないため、Appleの定義上「収集(collect)」に該当しません。
  - ※位置情報やマイクは「使用」しますが、外部送信・保存しないため「収集」ではありません。権限の使用目的は Info.plist の説明文（設定済み）でカバーされます。

もし審査で指摘された場合の代替申告（保守的な申告）:
- 位置情報: 「App機能」目的、ユーザーに紐付けない、トラッキングしない
- として申告し直す。

---

## 8. 年齢制限（レーティング）

- アンケートは基本すべて「なし/いいえ」で回答 → **4+** になる想定。
- 無制限Webアクセス等は「なし」（FT8のWebViewはローカルPi内のUIのみ）。

---

## 9. App Review 情報（審査メモ・重要）

> 審査担当者はユーザーのハードウェア（Raspberry Pi・無線機）を持っていないため、
> 「なぜ実機がないと動かないのか」を明確に説明することが承認の鍵です。
> 連絡先とデモ動画URLも入れてください。英語で記載します（日本語は補足）。

### Notes（英語・そのまま貼り付け可）
```
This app is a companion/controller app for licensed amateur (ham) radio operators.
It remotely controls a physical ham radio transceiver over Wi-Fi and therefore
REQUIRES the user's own hardware to function. It cannot be fully exercised without it.

Required hardware (one of):
1) A Raspberry Pi Zero 2W connected to a radio, running Hamlib + our FastAPI server
   (setup guide: https://github.com/ji1ore/M5CoreHamCAT), OR
2) An Icom IC-705 or IC-9700 on the same Wi-Fi network (direct CI-V mode).

The app connects to devices on the LOCAL network (UDP/HTTP) and to a BLE CW keyer.
This is why it requests Local Network and Bluetooth permissions. Microphone is used
only to stream voice to the radio while transmitting (PTT); nothing is recorded.
Location is optional and only used to fill an APRS position / FT8 grid locator.

No accounts, no ads, no analytics, no data leaves the device.

HOW TO REVIEW WITHOUT HARDWARE:
On the first screen ("Wifi_RIG_CTRL"), scroll to the bottom to the "Demo" section
and tap "Skip with mock data" (モックデータでスキップ). This loads sample data and
lets you explore all screens (main control, rig select, CW TX, FT8, APRS) without
any radio or Raspberry Pi connected.

If the app shows "not connected" in normal mode, that is expected without the
required hardware on the same network.

Contact: jiro.ueno0213@gmail.com
```

### Sign-in
- ログイン不要 → 「サインインが必要」は **オフ**。
- デモアカウント欄は空でOK（ハードウェア必須の旨をNotesに記載済み）。

---

## 11. リリースノート（What's New）— v2.32

App Store Connect「バージョン情報」→「このバージョンの新機能」欄にそのまま貼れる文案です。

### 日本語

```
■ v2.32 の主な変更点

【レピータ設定 — CI-V 直接接続】

周波数ディスプレイを長押しするとレピータ設定シートが開きます。

〔オフセット〕
・方向: None / + / - から選択
・プリセット: 100kHz / 600kHz / 1MHz / 1.6MHz / 5MHz / 7.6MHz
・カスタム入力にも対応

〔トーン〕
・モード: None / TONE（エンコードのみ）/ TSQL（エンコード+スケルチ）/ DTCS
・トーン周波数を Hz 単位で指定

〔TX 周波数表示〕
・送信中（PTT ON）はオフセットを加算/減算した実際の TX 周波数を表示

・設定はアプリ終了後も保持されます
```

---

### English

```
What's New in Version 2.32

[Repeater Settings — CI-V Direct Mode]

Long-press the frequency display to open the Repeater Settings sheet.

Offset
- Direction: None / + / -
- Presets: 100 kHz / 600 kHz / 1 MHz / 1.6 MHz / 5 MHz / 7.6 MHz
- Custom offset input also supported

Tone
- Mode: None / TONE (encode only) / TSQL (encode + squelch) / DTCS
- Specify tone frequency in Hz

TX Frequency Display
- While transmitting (PTT ON), the display shows the actual TX frequency
  (RX frequency ± repeater offset)

Settings are saved across app restarts.
```

---

## 12. リリースノート（What's New）— v2.31（前バージョン参考）

App Store Connect「バージョン情報」→「このバージョンの新機能」欄にそのまま貼れる文案です。

### 日本語

```
■ v2.31 の主な変更点

【MEM パネル — 4タブ構成に刷新】

MEM SET ボタンを押すと、Preset / User / POTA / SOTA の4つのタブに整理された
メモリーパネルが表示されます。

〔Preset タブ〕
・プリセットの追加・編集・削除・リセットに対応
・各行を左スワイプして「編集」「削除」を選択
・ツールバーの ... ボタンから「プリセット追加」「全件リセット」

〔User タブ〕
・ユーザーメモリーをスワイプで編集・削除

〔POTA タブ〕（新機能）
・POTA（Parks on the Air）スポットをリアルタイム取得
・パーク番号（例: JA-0001）を太文字で表示
・モード・バンド・プログラム・任意文字で絞り込み
・絞り込み条件はアプリ終了後も保持
・スポットをタップすると周波数・モード・Width をリグに一括設定

〔SOTA タブ〕（新機能）
・SOTA（Summits on the Air）スポットをリアルタイム取得
・サミットコード（例: JA/KN-001）を太文字で表示
・モード・バンド・エリア・任意文字で絞り込み
・絞り込み条件はアプリ終了後も保持
・スポットをタップすると周波数・モード・Width をリグに一括設定

〔共通〕
・SSB スポットは周波数に応じて USB（10MHz以上）/ LSB（以下）に自動変換
・FT8・FT4・WSPR 等は USB に自動変換
・接続設定画面で SSB 幅・CW 幅を設定可能

【その他の変更】
・MEM ボタンの表示を「MEM / SET」2行に変更

【バグ修正】
・前回セッションの絞り込み条件が現在のスポットに存在しない場合、
　全件が非表示になる問題を修正（自動リセット）
```

---

### English

```
What's New in Version 2.31

[MEM Panel — Redesigned as 4-Tab Layout]

Tap "MEM SET" to open the memory panel, now organized into
four tabs: Preset, User, POTA, and SOTA.

Preset Tab
- Presets are now fully editable
- Swipe any row to Edit or Delete
- Tap ... (More) to add a new preset or Reset All to defaults

User Tab
- Swipe to edit or delete user memories

POTA Tab (New)
- Live POTA (Parks on the Air) spot fetch
- Park reference (e.g. JA-0001) shown in bold
- Filter by mode, band, program, or free text
- Filters saved between sessions
- Tap a spot → instantly sets freq, mode & width on your rig

SOTA Tab (New)
- Live SOTA (Summits on the Air) spot fetch
- Summit code (e.g. JA/KN-001) shown in bold
- Filter by mode, band, association, or free text
- Filters saved between sessions
- Tap a spot → instantly sets freq, mode & width on your rig

All Spot Tabs
- "SSB" spots auto-converted to USB (10 MHz and above) or LSB (below 10 MHz)
- Digital modes (FT8, FT4, WSPR, etc.) sent as USB
- Default SSB & CW filter widths configurable in Connection Settings

Other Changes
- MEM button now shows "MEM / SET" on two lines

Bug Fix
- Fixed spots disappearing when a saved filter value from a
  previous session no longer exists in the current spot data
```

---

## 13. リリースノート（What's New）— v2.30（参考）

App Store Connect の「バージョン情報」→「このバージョンの新機能」欄にそのまま貼れる文案です。

### 日本語

```
■ v2.30 の主な変更点

【新機能】
・接続画面に🔍ボタンを追加。同一ネットワーク内の Raspberry Pi を自動スキャンして
  接続先を一覧表示します（手動でIPアドレスを調べる手間がなくなります）。

【改善】
・接続中のリグが実際にサポートするモードを自動検出し、Mode選択に反映。
  FT-991 の C4FM、IC-705 の D-STAR など機種固有のデジタルモードを選択できます。
  Hamlib が対応していない理論上のモード（ECSSUSB・FAX・SAM 等）はリストから除外
  され、スッキリ表示されます。
・FM ボタンのサイクルが接続リグに自動適応。
  IC-705: FM → D-STAR → FM
  FT-991A: FM → C4FM → DATA-FM → FM
・プロファイルの信頼性向上。
  - 「接続」ボタン押下時・「リグを開く」押下時に、アクティブプロファイルへ
    現在の設定が自動保存されます。
  - プロファイル読み込み時にリグ・CAT 設定が正しく保存されない問題を修正。
・APRS の安定性向上。
  - TX Method を「リグモデム」から「DireWolf」に切り替えた際、リグ内蔵モデムの
    ビーコン送信を確実に停止するようになりました。
  - APRS 設定（有効/無効・ボーレート等）を 30 秒ごとにサーバーへ再送信し、
    サーバー再起動後も設定が自動復元されます。

【修正】
・直接CI-V接続時、D-STARを選択すると実際にはUSBモードが送信されてしまう不具合を
  修正しました。

⚠️ v2.20 以前からのアップグレード手順（重要）

本バージョンは Pi 側の api.py の更新が必要です。

1. アプリを起動し、接続先（IPアドレス・ポート・APIキー）を設定する
2. 「Admin」画面を開き「Update Pi API」をタップ
   （完了まで約1分。ログに「=== 完了 ===」が出るまで待つ）
3. 「About」画面で「Refresh Pi Version」をタップし、Pi API バージョンが
   2.30 になっていることを確認する
4. 通常通り接続して使用する

※ Pi に SSH 接続は不要です。アプリ内の操作だけで完結します。
```

### English

```
What's New in v2.30

[New Features]
- Auto-scan button (🔍) on the connect screen. Discovers Raspberry Pi servers on
  your local network automatically — no more manually looking up IP addresses.

[Improvements]
- The app now auto-detects the modes supported by the connected rig and shows only
  those in the Mode picker. Rig-specific digital modes such as C4FM on the FT-991 or
  D-STAR on the IC-705 are now selectable. Hamlib "noise" modes (ECSSUSB, FAX, SAM,
  etc.) are filtered out, keeping the list clean.
- FM button cycle adapts automatically to the connected radio:
    IC-705: FM → D-STAR → FM
    FT-991A: FM → C4FM → DATA-FM → FM
- Profile reliability improvements.
  - Active profile is now automatically updated when you tap Connect or Open Rig,
    so your latest settings are always saved.
  - Fixed a bug where rig and CAT settings were not saved when loading a profile.
- APRS stability improvements.
  - Switching TX Method from Rig Modem to DireWolf now reliably stops the rig's
    built-in beacon transmitter.
  - APRS configuration (enabled/disabled, baud rate, etc.) is re-sent to the server
    every 30 seconds, so settings are automatically restored after a server restart.

[Bug Fixes]
- Fixed an issue in direct CI-V mode where selecting D-STAR would send USB mode
  to the rig instead.

[!] Upgrade instructions for users on v2.20 or earlier (important)

This version requires updating api.py on your Raspberry Pi.

1. Launch the app and enter your Pi's connection settings (host, port, API key)
2. Open the Admin screen and tap "Update Pi API"
   (takes ~1 minute — wait for "=== Done ===" in the log)
3. In the About screen, tap "Refresh Pi Version" and confirm the Pi API version
   shows 2.30
4. Connect and use as normal

No SSH access to the Pi is needed — everything is done from within the app.
```

---

## 10. 未対応・要準備タスク（チェックリスト）

- [ ] スクリーンショット iPhone 6.5型（1242×2688 など）最低1枚
- [ ] プライバシーポリシーを公開しURL取得（上記セクション6を貼る）
- [ ] デモ動画URL（審査通過率を上げるため強く推奨）
- [ ] バージョン番号のズレ確認（ページ1.0 ↔ ビルド2.30）
- [ ] ビルドの処理完了後「ビルド」欄で選択
```
