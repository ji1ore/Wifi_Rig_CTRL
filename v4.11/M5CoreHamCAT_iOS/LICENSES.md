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

端末内蔵の FT8/FT4（CI-V モードのローカルエンコード・デコード）のため、以下の
パーミッシブライセンスの C ライブラリをソースから組み込んでコンパイルしています。
いずれも著作権表示とライセンス全文の添付が必要なため、以下に全文を掲載します。

#### ft8_lib
- **用途**: 端末内蔵 FT8/FT4 エンコード・デコード（`WifiRigCTRL/ft8lib/`）
- **作者**: Kārlis Goba
- **ライセンス**: MIT License
- **ソース**: https://github.com/kgoba/ft8_lib

```
MIT License

Copyright (c) 2018 Kārlis Goba

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

#### kissfft
- **用途**: FFT（ft8_lib 内で使用、`WifiRigCTRL/ft8lib/fft/`）
- **作者**: Mark Borgerding
- **ライセンス**: BSD-3-Clause
- **ソース**: https://github.com/mborgerding/kissfft

```
Copyright (c) 2003-2010, Mark Borgerding. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.
* Neither the author nor the names of any contributors may be used to endorse
  or promote products derived from this software without specific prior written
  permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

#### Swift Package Manager 依存（アプリにリンク・同梱）

SSH（Pi 初期セットアップ）等のため、以下の SPM パッケージをアプリに同梱しています。
いずれもパーミッシブライセンス（MIT / Apache-2.0）で、GPL/コピーレフトはありません。

| パッケージ | 用途 | ライセンス | ソース |
|---|---|---|---|
| Citadel | SSH クライアント | MIT | https://github.com/orlandos-nl/Citadel |
| BigInt | 多倍長整数（暗号） | MIT | https://github.com/attaswift/BigInt |
| swift-nio | 非同期 I/O | Apache-2.0 | https://github.com/apple/swift-nio |
| swift-nio-ssh | SSH プロトコル（フォーク） | Apache-2.0 | https://github.com/Wellz26/swift-nio-ssh |
| swift-crypto | 暗号 | Apache-2.0 | https://github.com/apple/swift-crypto |
| swift-asn1 | ASN.1 | Apache-2.0 | https://github.com/apple/swift-asn1 |
| swift-collections | コレクション | Apache-2.0 | https://github.com/apple/swift-collections |
| swift-atomics | アトミック操作 | Apache-2.0 | https://github.com/apple/swift-atomics |
| swift-log | ロギング | Apache-2.0 | https://github.com/apple/swift-log |
| swift-system | システム API | Apache-2.0 | https://github.com/apple/swift-system |

**MIT（Citadel / BigInt）** — 本文は上記 ft8_lib の MIT License と同一。著作権表示:
`Copyright (c) Joannis Orlandos and the Citadel project authors`（Citadel）、
`Copyright (c) Károly Lőrentey`（BigInt）。

**Apache License 2.0（swift-* 各パッケージ）** — ライセンス全文:
https://www.apache.org/licenses/LICENSE-2.0 。各リポジトリの `NOTICE` 記載を保持します。
（厳密な Apache-2.0 準拠のため、配布物に Apache-2.0 全文と各 NOTICE を同梱することを推奨。
Xcode の設定で「Acknowledgements/Licenses」を自動生成する運用でも可。）

> **補足（供給元の注意）**: `swift-nio-ssh` は Apple 公式ではなく第三者フォーク
> （`Wellz26/swift-nio-ssh`）を参照しています。ライセンスは Apache-2.0 のままですが、
> 供給元の信頼性の観点から、可能なら公式 `apple/swift-nio-ssh` への切替を推奨します。

その他の機能（UI・通信・CW デコード・音声処理・BLE・位置情報等）は、独自の Swift
コードと Apple 標準フレームワーク（SwiftUI, Foundation, AVFoundation, CoreBluetooth,
CoreLocation, WebKit, Network）のみで実装しています。

> **v3.32 の変更**: 従来 CI-V ローカルデコードに同梱していた mfsk-core
> （`MfskCore.xcframework`, GPL-3.0）をアプリから除去し、端末内蔵デコーダを
> ft8_lib（MIT）に統一しました。App Store 配布のため、GPL バイナリの同梱と
> 実行コードの実行時ダウンロードの双方を回避するための変更です。アプリ本体
> （IPA）には GPL / コピーレフトのコードを一切含みません。

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

#### mfsk-core
- **用途**: FT8/FT4 デコード（Pi サーバーサイド、`mfsk-decode`）
- **作者**: JL1NIE
- **ライセンス**: GNU General Public License v3.0 (GPL 3.0)
- **ソース**: https://github.com/jl1nie/mfsk-core

**GPL 準拠についての注記**:
`mfsk-core` は GPL v3 です。**v3.32 で iOS アプリ本体からは完全に除去**しました
（従来の `MfskCore.xcframework` を削除）。現在は Raspberry Pi 上でのみ、別プロセス
（`mfsk-decode`）として動作し、iOS アプリとは HTTP/SSE で通信するのみです。アプリに
リンク・同梱していません。

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
