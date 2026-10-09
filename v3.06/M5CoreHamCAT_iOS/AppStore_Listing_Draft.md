# WifiRigCTRL — App Store 掲載情報 下書き

作成日: 2026-09-21 / 対象バージョン: 3.06 (build 35)

---

## 1. 基本情報

| 項目 | 内容 | 文字数上限 |
|---|---|---|
| App名 | WifiRigCTRL | 30 |
| サブタイトル（日本語） | Wi-Fiでリグをリモート制御 | 30 |
| サブタイトル（英語） | Wi-Fi remote rig control | 30 |

---

## 2. プロモーション用テキスト（170字以内）

### 日本語
```
v3.06: FT8画面にALC・パワー数値を追加。Tゲインボタン・CI-Vウォーターフォール・ラズパイFT4 TX/RXを修正。Raspberry Pi や IC-705/IC-9700 をWi-Fiでリモート操作。
```

### English
```
v3.06: FT8 screen shows numeric ALC/power values. Fixes for T-gain button, CI-V waterfall, and Raspberry Pi FT4 TX/RX. Remote rig control via Wi-Fi — CW, FT8 and APRS.
```

---

## 3. リリースノート（What's New）— v3.06

App Store Connect の「バージョン情報」→「このバージョンの新機能」欄にそのまま貼れます。

### 日本語
```
v3.06

【新機能】
・FT8画面にALC・パワー出力の数値（%）を追加表示
  TX中にリアルタイムで更新、TX終了時に自動リセット

【修正】
・T+/T- ゲインボタンのタップが効かない問題を修正
  （長押しとの競合を解消）
・CI-V接続時のウォーターフォール表示が止まる問題を修正
  （タスク管理を明示的に制御し、接続・切断時に正しく再起動）
・ラズパイモードでTゲインボタンを押してもALCに反映されなかった問題を修正
・ラズパイFT4: TX/RXが動作しない問題を修正
  （FT4は48ms/シンボル・20.8Hzトーン間隔・105シンボルで正しく生成するよう修正）
・ラズパイFT4: FT8画面を開いたときにFT4設定がPiに同期されるよう修正

【Pi API 更新】
・Pi API を 3.06 に更新しました。
  「Admin」→「Update Pi」をタップして Pi 側を更新してください（約1分）。
  （FT4 TX/RX修正・Tゲイン反映に必要です）
```

### English
```
v3.06

[New Feature]
- FT8 screen now shows numeric ALC and power output values (%)
  Updates in real time during TX; resets to 0% when TX ends

[Fixes]
- Fixed T+/T- gain buttons not responding to taps
  (resolved conflict between tap and long-press gestures)
- Fixed CI-V waterfall freezing when connecting/disconnecting
  (explicit task management ensures proper restart on reconnect)
- Fixed T-gain button having no effect on ALC in Raspberry Pi mode
- Fixed Raspberry Pi FT4 TX/RX not working
  (FT4 now correctly uses 48 ms/symbol, 20.8 Hz tone spacing, 105 symbols)
- Fixed FT4 mode not being synced to Pi when entering the FT8 screen

[Pi API Update]
- Pi API updated to 3.06.
  Go to Admin → Update Pi to update your Raspberry Pi (takes ~1 minute).
  Required for FT4 TX/RX fix and T-gain to take effect.
```

---

## 4. 提出チェックリスト（v3.06）

- [ ] MARKETING_VERSION = 3.06 / CURRENT_PROJECT_VERSION 確認・更新
- [ ] Xcode で Archive → App Store Connect へアップロード
- [ ] App Store Connect で新バージョン 3.06 を作成
- [ ] リリースノート（上記セクション3）を App Store Connect に貼り付け
- [ ] プロモーション用テキスト更新
- [ ] 審査提出
