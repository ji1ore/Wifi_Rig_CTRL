# App Store Connect — Wifi_RIG_CTRL iOS v3.03 リリースノート

**バージョン**: 3.03 (build 81)  
**リリース日**: 2026-09-17

---

## このバージョンの新機能（日本語）500文字以内

```
v3.03

【変更】
・SOTAスポット取得をREST APIに変更（telnetクラスター廃止）
  → スポットが確実に取得・表示されるようになりました
・SOTA表示を直近2時間のスポットに絞り込み（従来24時間）
  → 現在運用中のアクティベーターのスポットのみ表示
```

---

## What's New in This Version（English）500 chars max

```
v3.03

[Changes]
• SOTA spot fetching now uses the REST API (telnet cluster removed)
  — spots load reliably every time
• SOTA list now shows only the last 2 hours (was 24 hours)
  — only currently active summits are displayed
```

---

## App Store Connect 入力手順

1. App Store Connect → アプリ → iOS → 「+バージョンまたはプラットフォーム」
2. バージョン番号: `3.03`
3. Xcode から Archive → Distribute App → App Store Connect → アップロード
4. 「このバージョンの新機能」に上記テキストを貼り付け
5. 審査に提出
