package com.ji1ore.wifi_rig_ctrl.data

import android.util.Log

/**
 * JNI bridge to libft8jni.so (built from ft8_lib via NDK).
 *
 * Setup (one-time, before building):
 *   git clone --depth=1 https://github.com/kgoba/ft8_lib \
 *       app/src/main/jni/ft8_lib
 *
 * The library exposes FT8 encode (text → PCM) and decode (PCM → messages).
 */
object Ft8Jni {

    private const val TAG = "Ft8Jni"

    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("ft8jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libft8jni.so not found — CI-V FT8 encode/decode unavailable: ${e.message}")
            false
        }
    }

    /**
     * Encode an FT8 or FT4 message to mono PCM audio (S16_LE).
     * @param msg      message text (e.g. "CQ JF9KKE QN02")
     * @param baseHz   audio base frequency in Hz (e.g. 1500)
     * @param rate     sample rate in Hz (12000)
     * @param isFt4    true for FT4 (7.5-second slot), false for FT8 (15-second slot)
     * @return PCM samples as ShortArray, or null on error
     */
    fun encode(msg: String, baseHz: Float = 1500f, rate: Int = 12000,
               isFt4: Boolean = false): ShortArray? {
        if (!isAvailable) return null
        return try {
            nativeEncode(msg, baseHz, rate, if (isFt4) 1 else 0)
        } catch (e: Exception) {
            Log.e(TAG, "encode failed: ${e.message}")
            null
        }
    }

    /**
     * Decode FT8/FT4 messages from one audio window.
     * @param samples  PCM audio (mono S16_LE, 12000 Hz)
     * @param rate     sample rate (must be 12000)
     * @param freqMin  lowest audio frequency to search (Hz)
     * @param freqMax  highest audio frequency to search (Hz)
     * @param myCall   own callsign (helps hash decode)
     * @param dxCall   known DX callsign (helps hash decode)
     * @param isFt4    true for FT4 (pass 7.5s of samples), false for FT8 (15s)
     * @return list of decode results; each is a JSON string:
     *         {"freq":1234.5,"snr":-5.0,"dt":0.15,"msg":"CQ JF9KKE QN02"}
     */
    fun decode(
        samples: ShortArray,
        rate: Int = 12000,
        freqMin: Float = 100f,
        freqMax: Float = 3000f,
        myCall: String = "",
        dxCall: String = "",
        maxResults: Int = 50,
        isFt4: Boolean = false
    ): List<String> {
        if (!isAvailable) return emptyList()
        return try {
            nativeDecode(samples, rate, freqMin, freqMax, myCall, dxCall,
                         maxResults, if (isFt4) 1 else 0).toList()
        } catch (e: Exception) {
            Log.e(TAG, "decode failed: ${e.message}")
            emptyList()
        }
    }

    // ── native declarations ────────────────────────────────────────────────

    @JvmStatic
    private external fun nativeEncode(msg: String, baseHz: Float, sampleRate: Int,
                                      isFt4: Int): ShortArray?

    @JvmStatic
    private external fun nativeDecode(
        samples: ShortArray, sampleRate: Int,
        freqMin: Float, freqMax: Float,
        myCall: String, dxCall: String,
        maxResults: Int, isFt4: Int
    ): Array<String>
}
