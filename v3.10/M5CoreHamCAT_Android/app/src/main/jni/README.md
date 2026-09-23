# JNI Native Library (ft8lib)

## ビルド前の準備

ft8_libソースをクローンしてください:

```bash
cd app/src/main/jni
git clone --depth=1 https://github.com/kgoba/ft8_lib ft8_lib
```

その後、Android Studioでビルドすると `libft8jni.so` が自動的に
`arm64-v8a` および `armeabi-v7a` 向けにビルドされます。

## ファイル構成

```
jni/
├── CMakeLists.txt      # NDKビルド設定
├── ft8_jni.c           # JNIラッパー (Kotlin↔ft8lib)
├── ft8_lib/            # ← git clone で配置 (not tracked)
│   ├── ft8/
│   │   ├── message.h/c  FT8メッセージのエンコード/パック
│   │   ├── encode.h/c   LDPC符号化 + トーン生成
│   │   ├── decode.h/c   LDPC復号 + FFT検出
│   │   └── constants.h  FT8定数 (FT8_NN=79, FT8_SYMBOL_PERIOD等)
│   └── common/
│       └── kiss_fft/    FFTライブラリ (デコード用)
└── README.md
```

## Kotlin API

```kotlin
// エンコード: テキスト → PCM音声 (S16_LE, 12000Hz)
val pcm: ShortArray? = Ft8Jni.encode("CQ JF9KKE QN02", baseHz = 1500f)

// デコード: PCM音声 → メッセージリスト (JSON文字列)
val msgs: List<String> = Ft8Jni.decode(samples, myCall = "JF9KKE")
// 各要素: {"freq":1234.5,"snr":-5.0,"dt":0.15,"msg":"CQ JA1ABC PM95"}
```

## CI-Vモード動作

`useCIV = true` の場合、Ft8Fragmentが自動的に `Ft8LocalEngine` を起動:
- **RX**: AudioRecord (12kHz mono) → 15秒ウィンドウ → `Ft8Jni.decode()`
- **TX**: メッセージ → `Ft8Jni.encode()` → AudioTrack → CI-V PTT
- ウォーターフォール/SSEイベントはPi接続と同一形式で既存UIに流れる
