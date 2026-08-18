# リリースノート v2.50 — iOS

---

## App Store「このバージョンの新機能」（4000字以内）

### 日本語（ja-JP）

```
v2.50
■PTT固着対策（FTX-1F 等、ランプアップ中のPTT OFFが無視される機種向け）
・PTT OFF後、バックグラウンドで実際にOFFを確認できるまで最大20秒間 T 0 を送り続けるウォッチドッグを追加
・CW/APRS/FT8送信が割り込んだ場合は即座に打ち切り（他の送信方式と競合しない）
■PTT連打時のrigctld競合修正
・rigctld再起動の排他制御を追加（複数箇所からの同時再起動要求を排他して安定化）
■Pi側スクリプト更新（api.py/create_api.sh）
・WebFT8バージョンを /radio/status に追加（アプリ側でバージョン不一致を検知可能に）
・webft8サーバー再起動をsystemd対応 + 直接起動フォールバック方式に改善
・API_VERSION を 2.50 に更新
```

---

### English (en-US)

```
v2.50
[PTT-OFF Watchdog for TX-Stuck Prevention]
- For rigs like FTX-1F that ignore PTT-OFF during output ramp-up, a background watchdog
  now retries T 0 up to 20 seconds until the rig confirms OFF
- Immediately aborted if CW/APRS/FT8 transmission takes over (no interference)

[rigctld Restart Race Condition Fix]
- Added exclusive locking for rigctld restart to prevent multiple simultaneous restarts
  when PTT is toggled rapidly

[Pi-side Script Changes (api.py / create_api.sh)]
- WebFT8 version now included in /radio/status response
- webft8 server restart now supports systemd with direct-launch fallback
- API_VERSION updated to "2.50"
```

---

## バージョン履歴（iOS）

| バージョン | 内容 | ビルド日 |
|---|---|---|
| 2.30 | 初回 App Store リリース（スキャン・モード自動検出・プロファイル改善・APRS安定化） | 2026-07-26 |
| 2.33 | WebFT8修正（Loading WASM・ウォーターフォール）・Update WebFT8ボタン追加 | 2026-08-04 |
| 2.34 | WebFT8バージョン取得修正・URLSession SSL修正・adminUpdatePi改善 | 2026-08-12 |
| 2.50  | PTT OFFウォッチドッグ追加・rigctld排他制御・Pi API 2.50対応 | 2026-08-18 |
