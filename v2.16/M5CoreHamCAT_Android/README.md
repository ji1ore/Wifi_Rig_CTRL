# Wifi RIG CTRL for Android — v2.16

Android スマートフォンから Raspberry Pi 経由でアマチュア無線機を遠隔制御するアプリです。

## v2.16 変更点（v2.15 との比較）

### 修正

#### CI-V 接続の [TEST] 表記を削除

- RIG CONNECT 画面の「USE CI-V (IC-705 etc.)」ラベルから [TEST] を除去
- CI-V 機能を正式機能として扱い

### Pi 側スクリプト

v2.15 と同一のため変更なし。

---

## ビルド方法

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
```

署名済み APK: `app/build/outputs/apk/release/app-release.apk`

---

## 関連リポジトリ

- Pi セットアップ: `v2.16/RaspberryPiSetup/`
- GitHub: [ji1ore/M5CoreHamCAT](https://github.com/ji1ore/M5CoreHamCAT)
