# Licenses / ライセンス表記

## このアプリについて

**WifiRigCTRL for iOS**  
Copyright (c) 2026 JI1ORE  
Private use. All rights reserved.

このアプリ本体（Swift ソースコード）は独自ライセンスです。
ソースコードは閲覧のために公開していますが、著作権は作者が保持します。
無断での再利用・改変・再配布は許可しません。

---

## オープンソースコンポーネント

### iOS アプリ内（App に組み込まれているもの）

**なし。**

本 iOS アプリは、第三者のオープンソースライブラリを一切バンドルしていません。
UI・通信・CW デコード・音声処理・BLE・位置情報・FT8 表示（WebView）等は、すべて
独自の Swift コードと Apple 標準フレームワークのみで実装されています。

- **使用している Apple 標準フレームワーク**: SwiftUI, Foundation, AVFoundation,
  CoreBluetooth, CoreLocation, WebKit, Network
- FT8/FT4 は Raspberry Pi 上の WebFT8 を WebView で表示するのみで、デコード用
  ライブラリ（ft8_lib 等）はアプリに含まれません。

---

### Raspberry Pi 側（アプリに同梱されないサーバーサイドコンポーネント）

以下のソフトウェアは Raspberry Pi 上で動作します。iOS アプリには含まれません。
iOS アプリはネットワーク経由（HTTP/UDP）でこれらと通信するのみです。
ユーザーが各自の Pi にインストール・ビルドして使用するものです。

#### ft8wav
- **用途**: FT8/FT4 音声ファイルのデコード（Pi 側で subprocess として実行）
- **ライセンス**: GNU General Public License v3.0 (GPL 3.0)
- **ソース**: https://github.com/ayoungblood/ft8wav

**GPL 準拠についての注記**:  
`ft8wav` は GPL v3 ライセンスのもとで配布されています。  
本 iOS アプリは `ft8wav` を一切リンク・組み込みしておらず、Pi 側で別プロセスとして
実行されるものを利用するのみです。

- `ft8wav` のソースコードは上記 GitHub リポジトリで公開されています
- 本プロジェクトは `ft8wav` に対して一切の改変を行っていません
- `create_api.sh` は ft8wav を公式リポジトリからダウンロード・ビルドします
- ft8wav を使用するユーザーは GPL v3 の条件に従うものとします

#### Hamlib / rigctld
- **用途**: CAT 制御（リグ制御コマンド発行）
- **ライセンス**: GNU Lesser General Public License v2.1+ (LGPL 2.1+)
- **ソース**: https://github.com/Hamlib/Hamlib

#### Direwolf
- **用途**: APRS パケット送信（KISS TNC）
- **作者**: WB2OSZ
- **ライセンス**: GNU General Public License v2.0 (GPL 2.0)
- **ソース**: https://github.com/wb2osz/direwolf

#### FFmpeg
- **用途**: 音声キャプチャ・リサンプリング（ALSA → PCM ストリーム）
- **ライセンス**: GNU Lesser General Public License v2.1+ (LGPL 2.1+)
- **ソース**: https://ffmpeg.org

#### FastAPI / uvicorn
- **用途**: REST API サーバー・音声ストリームサーバー
- **ライセンス**: MIT License
- **ソース**: https://github.com/tiangolo/fastapi / https://github.com/encode/uvicorn

---

## GPL コンポーネントのライセンス全文

GPL 全文は各プロジェクトの公式リポジトリ・サイトで参照できます。

- **GPL v2**: https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
- **GPL v3**: https://www.gnu.org/licenses/gpl-3.0.html
- **LGPL v2.1**: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
