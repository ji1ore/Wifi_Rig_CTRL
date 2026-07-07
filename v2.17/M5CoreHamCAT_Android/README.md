# Wifi RIG CTRL for Android — v2.17

Android スマートフォンから Raspberry Pi 経由でアマチュア無線機を遠隔制御するアプリです。

## v2.17 変更点（v2.16 との比較）

### 修正

#### PiP（縮小）モードから復帰した際のパネルボタン表示バグを修正

- PiP モードから通常表示に戻った際、パネルボタンが上段 4 つしか表示されない問題を修正
- `onPictureInPictureModeChanged` 内の `requestLayout()` を `post {}` に移動し、
  ウィンドウが完全に復元されてから GridLayout を再計算するよう変更

### Pi 側スクリプト

v2.16 と同一のため変更なし。

---

## ビルド方法

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
```

署名済み APK: `app/build/outputs/apk/release/app-release.apk`

---

## 関連リポジトリ

- Pi セットアップ: `v2.17/RaspberryPiSetup/`
- GitHub: [ji1ore/M5CoreHamCAT](https://github.com/ji1ore/M5CoreHamCAT)
