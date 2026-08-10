# リリースノート v2.33 — iOS

---

## App Store「このバージョンの新機能」（4000字以内）

### 日本語（ja-JP）

```
v2.33
■WebFT8 修正
・「Loading WASM で止まる」問題を修正
  - webft8 アップデート後に不足していた wav-save.js を自動取得するよう修正
  - Pi の webft8 サーバーが起動時に GitHub から最新 JS ファイルを自動更新
・ウォーターフォールが開始しない問題を修正
  - オーディオデバイス選択の初期化タイミング問題を修正
  - AudioWorklet が利用できない場合のフォールバック処理を追加
■Update 画面
・「Update WebFT8」ボタン追加（server_webft8.py のみ即時デプロイ）
・「Update Pi」完了後に自動で WebFT8 も更新
```

---

### English (en-US)

```
v2.33
[WebFT8 Fixes]
- Fixed "stuck at Loading WASM" after webft8 update
  - Auto-fetches missing wav-save.js and latest JS files from GitHub at startup
- Fixed waterfall not starting
  - Fixed audio device selection timing issue at initialization
  - Added AudioWorklet fallback for improved compatibility
[Update Screen]
- Added "Update WebFT8" button for quick server_webft8.py deployment
- "Update Pi" now automatically triggers WebFT8 update afterward
```

---

## バージョン履歴（iOS）

| バージョン | 内容 | ビルド日 |
|---|---|---|
| 2.30 | 初回 App Store リリース（スキャン・モード自動検出・プロファイル改善・APRS安定化） | 2026-07-26 |
| 2.33 | WebFT8修正（Loading WASM・ウォーターフォール）・Update WebFT8ボタン追加 | 2026-08-04 |
