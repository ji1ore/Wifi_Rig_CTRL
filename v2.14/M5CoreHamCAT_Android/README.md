# Wifi RIG CTRL for Android — v2.14

Android スマートフォンから Raspberry Pi 経由でアマチュア無線機を遠隔制御するアプリです。

## v2.14 変更点（v2.13 との比較）

### 新機能

#### IC-705 / IC-9700 等への直接 Wifi CI-V 接続対応（Raspberry Pi 不要）

- RIG CONNECT 画面の「USE CI-V」スイッチで Pi モード / CI-V 直接接続を切替
- CI-V ポート（デフォルト 50001）・CI-V アドレス（IC-705: 0xA4）を設定可能
- 対応機能: 周波数・モード・Sメーター・PTT・RF power・SQL・BK-IN
- 非対応機能（Pi モードのみ）: 音声ストリーミング・CW テキスト送信・FT8・APRS

### Pi 側スクリプト

v2.13 と同一のため変更なし。

---

## ビルド方法

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
```

署名済み APK: `app/build/outputs/apk/release/app-release.apk`

---

## 関連リポジトリ

- Pi セットアップ: `v2.14/RaspberryPiSetup/`
- GitHub: [ji1ore/M5CoreHamCAT](https://github.com/ji1ore/M5CoreHamCAT)
