# Wifi RIG CTRL for Android — v2.13

Android スマートフォンから Raspberry Pi 経由でアマチュア無線機を遠隔制御するアプリです。

## v2.13 変更点（v2.12 との比較）

### 新機能

#### ピクチャー・イン・ピクチャー（PiP）対応

- TX 中・CW 打鍵中にホームボタンを押すと自動的に小画面（PiP）に移行
- Android 12（API 31）以上では `setAutoEnterEnabled` による自動移行に対応
- Android 8.0（API 26）以上でサポート

#### エッジ・ツー・エッジ表示に正式対応（Google Play ポリシー準拠）

- `enableEdgeToEdge()` + `WindowInsetsCompat` でシステムバー領域を自動回避
- 非推奨 API `setStatusBarColor` / `setNavigationBarColor` を解消
- Google Play Console の警告を解消

### バグ修正・改善

- **CW 打鍵時の SPK 音切れタイミング改善**
  - `AudioTrack` バッファを ~2 秒 → ~200ms に削減（iOS 版と同等のレスポンス）

- **USB CW 接続時のレイアウト崩れを修正**
  - USB パーミッションダイアログ表示中に誤って PiP に入る問題を修正（`suppressPip` フラグ）
  - PiP 復帰時にレイアウトを正しく再計算

- **スプラッシュ画面の UI 改善**
  - タイトル上にアプリアイコンを追加
  - 「+ New」ボタンの高さを修正（見切れ解消）
  - ボタン色を統一（AppCompat テーマに統一）

- **FT8 画面のボタン行高さを修正**

### Pi 側スクリプト

v2.12 と同一のため変更なし。

---

## ビルド方法

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
```

署名済み APK: `app/build/outputs/apk/release/app-release.apk`

---

## 関連リポジトリ

- Pi セットアップ: `v2.13/RaspberryPiSetup/`
- GitHub: [ji1ore/M5CoreHamCAT](https://github.com/ji1ore/M5CoreHamCAT)
