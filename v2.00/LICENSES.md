# Licenses / ライセンス表記

## このアプリについて

**Wifi RIG CTRL for Android**  
Copyright (c) 2026 JI1ORE  
Private use. All rights reserved.

このアプリ本体（Kotlin/Java ソースコード）は独自ライセンスです。

---

## オープンソースコンポーネント

### Android アプリ内（APK に組み込まれているもの）

#### ft8_lib
- **用途**: FT8/FT4 デコード用 C ライブラリ（Android NDK でコンパイル）
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

#### OkHttp
- **用途**: HTTP ストリーミング・REST API 通信
- **作者**: Square, Inc.
- **ライセンス**: Apache License 2.0
- **ソース**: https://github.com/square/okhttp

#### Gson
- **用途**: JSON パース
- **作者**: Google
- **ライセンス**: Apache License 2.0
- **ソース**: https://github.com/google/gson

#### usb-serial-for-android
- **用途**: USB シリアル通信（CH340 / CDC-ACM）
- **作者**: mik3y
- **ライセンス**: GNU Lesser General Public License v2.1 (LGPL 2.1)
- **ソース**: https://github.com/mik3y/usb-serial-for-android

LGPL 2.1 に基づき、このライブラリは動的リンク相当の形式で使用しています。
ライブラリ本体のソースコードは上記リポジトリで入手できます。

#### Kotlin / AndroidX / Kotlin Coroutines
- **ライセンス**: Apache License 2.0
- **ソース**: https://kotlinlang.org / https://developer.android.com/jetpack

---

### Raspberry Pi 側（アプリに同梱されないサーバーサイドコンポーネント）

以下のソフトウェアは Raspberry Pi 上で動作します。Android APK には含まれません。
ユーザーが各自の Pi にインストール・ビルドして使用するものです。

#### ft8wav
- **用途**: FT8/FT4 音声ファイルのデコード（Pi 側で subprocess として実行）
- **ライセンス**: GNU General Public License v3.0 (GPL 3.0)
- **ソース**: https://github.com/ayoungblood/ft8wav

**GPL 準拠についての注記**:  
`ft8wav` は GPL v3 ライセンスのもとで配布されています。  
本アプリは `ft8wav` を別プロセス（subprocess）として呼び出すのみであり、  
`ft8wav` のソースコードをリンク・組み込みしていません。

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
