# WifiRigCTRL — App Store 掲載情報 下書き

作成日: 2026-09-20 / 対象バージョン: 3.05 (build 34)
Android版 (Wifi_Rig_CTRL) を参考に、iOS版の実機能に合わせて作成。
> iOS版の対応: RX音声再生 / マイク送信(PTT) / 直接CI-V接続(IC-705・IC-9700) / BT CI-V Bluetooth接続 / BT音声 / CWデコード・送信 / BLE CWキーヤー / FT8・FT4 / APRSビーコン / 複数プロファイル / APIキー認証。
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
v3.05: Androidと同バージョンに統一。PTT TX音声の安定性向上・SOTAスポット重複表示を修正済み。Raspberry Pi や IC-705/IC-9700 をWi-Fiでリモート操作。CW・FT8・APRS対応。
```

### English
```
v3.05: Version aligned with Android. Includes PTT TX audio stability fix and duplicate SOTA spot fix. Remote rig control via Wi-Fi — CW, FT8 and APRS included.
```

---

## 3. 説明（Description・4000字以内）

### 日本語
```
WifiRigCTRL は、アマチュア無線機をiPhone/iPadからWi-Fi経由でリモート操作するためのコントローラーアプリです。

接続方法は3通り:
1. Raspberry Pi 経由（Hamlib/FastAPI）— 幅広い機種のCAT制御・受信音声・CW・FT8・APRSに対応
2. 直接CI-V接続 — IC-705 / IC-9700 と同じWi-Fiに接続し、Raspberry Pi なしで基本操作
3. BT CI-V Bluetooth接続 — IC-705 / IC-9700 にBluetooth直結（v2.60: RX音声もiPhoneスピーカーへ）

■ 主な機能

◆ BT CI-V Bluetooth音声（v2.60 新機能）
・IC-705 を Bluetooth で直接接続した状態で、RX 音声を iPhone スピーカーから聞けます
・Bluetooth SCO 経由でリグ音声をキャプチャし AVAudioEngine で再生
・Pi 不要の完全ワイヤレス RX 音声を実現

◆ SP2ALART 連携（v2.51）
・POTA/SOTA スポット通知をタップするとアプリが起動し周波数・モードが自動設定

◆ 基本リグ制御
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

Three ways to connect:
1. Via Raspberry Pi (Hamlib/FastAPI) — CAT control for a wide range of rigs, plus RX audio, CW, FT8 and APRS
2. Direct CI-V — connect to an IC-705 / IC-9700 on the same Wi-Fi, no Raspberry Pi required
3. BT CI-V Bluetooth — connect directly to IC-705 / IC-9700 over Bluetooth (v2.60: RX audio to iPhone speaker)

■ Features

BT CI-V Bluetooth Audio (New in v2.60)
- Stream IC-705 RX audio to your iPhone speaker over Bluetooth SCO
- Captures rig audio via BT SCO input and plays it through AVAudioEngine
- Fully wireless RX audio — no Raspberry Pi needed

SP2ALART Integration (v2.51)
- Tap a POTA/SOTA spot notification from SP2ALART and this app opens with
  the spot's frequency and mode pre-configured

Rig Control
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

## 11. リリースノート（What's New）— v3.05

App Store Connect の「バージョン情報」→「このバージョンの新機能」欄にそのまま貼れる文案です。

### 日本語

```
v3.05

【新機能】
・アプリ内ヘルプ画面を追加（About → Help / Manual）
  日本語・英語のマニュアルをアプリ内で確認できます

【修正】
・PTT 音声送信（TX）中に発生していた Broken Pipe エラーを修正
  （Pi 側 audio_tx ストリームの安定性向上）
・SOTA スポット画面で同一局・同一サミットの重複エントリが表示される問題を修正
  （最新スポットのみ表示するようになりました）

【Pi API 更新】
・Pi API を 3.04 に更新しました。
  「Admin」→「Update Pi」をタップして Pi 側を更新してください（約1分）。
```

### English

```
v3.05

[New Feature]
- Built-in help screen (About → Help / Manual)
  View the Japanese/English manual without leaving the app

[Fixes]
- Fixed a Broken Pipe error that occurred during PTT audio TX
  (improved stability of the Pi-side audio_tx stream)
- Fixed duplicate SOTA spots appearing for the same activator and summit
  (now shows only the most recent spot per activator/summit)

[Pi API Update]
- Pi API updated to 3.04.
  Go to Admin → Update Pi to update your Raspberry Pi (takes ~1 minute).
```

---

## 10. 未対応・要準備タスク（チェックリスト v3.05）

- [x] MARKETING_VERSION = 3.05 / CURRENT_PROJECT_VERSION = 35 設定済み
- [x] AppStore_Listing_Draft.md v3.05 内容に更新済み
- [x] BrokenPipe TX 修正（api.py OSError キャッチ）
- [x] SOTA スポット重複除去修正
- [x] アプリ内ヘルプ画面追加（HelpView.swift / help.html / help_ja.html）
- [ ] Xcode で Archive → App Store Connect へアップロード（GUI操作必要）
- [ ] App Store Connect で新バージョン 3.05 (build 34) を作成
- [ ] リリースノート（上記セクション11）を App Store Connect に貼り付け
- [ ] スクリーンショット iPhone 6.5型（1242×2688 など）最低1枚
- [ ] プライバシーポリシーを公開しURL取得（セクション6を貼る）
- [ ] デモ動画URL（審査通過率を上げるため強く推奨）
- [ ] 審査提出
