package com.ji1ore.wifi_rig_ctrl

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray

/**
 * JNI wrapper for ft8_lib decoding.
 * Native library: libft8rx.so  (built from app/src/main/cpp/)
 *
 * ft8_lib setup:
 *   cd app/src/main/cpp
 *   git clone https://github.com/kgoba/ft8_lib ft8_lib
 */
object Ft8Decoder {

    private var loaded = false

    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("ft8rx")
            loaded = true
            Log.i("Ft8Decoder", nativeVersion())
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e("Ft8Decoder", "libft8rx.so not found: ${e.message}")
            false
        }
    }

    /** ft8_lib decode results */
    data class Result(
        val snr: Float,
        val dt: Float,
        val hz: Int,
        val msg: String
    )

    /**
     * Decode FT8/FT4 from a float PCM audio array.
     * @param samples  normalized float PCM (-1..+1), [sampleRate * slotTime] samples
     * @param sampleRate  e.g. 12000 or 44100 Hz
     * @param isFt4  true for FT4 (7.5s), false for FT8 (15s)
     * @return list of decoded messages
     */
    /**
     * Encode a FT8/FT4 message to float PCM audio samples [-1..1].
     * @param message  e.g. "CQ JI1ORE PM95" (max 13 chars, standard FT8 format)
     * @param isFt4    true = FT4 (105 tones, 5s), false = FT8 (79 tones, 12.6s)
     * @param sampleRate  output sample rate (e.g. 12000)
     * @param baseFreqHz  audio base frequency in Hz (e.g. 1500.0f)
     * @return float PCM array, empty on encode failure
     */
    fun encode(message: String, isFt4: Boolean, sampleRate: Int, baseFreqHz: Float): FloatArray {
        if (!loaded) return FloatArray(0)
        return try {
            nativeEncode(message, isFt4, sampleRate, baseFreqHz)
        } catch (e: Throwable) {
            Log.e("Ft8Decoder", "encode error: ${e.message}")
            FloatArray(0)
        }
    }

    fun decode(samples: FloatArray, sampleRate: Int, isFt4: Boolean): List<Result> {
        if (!loaded) {
            Log.w("Ft8Decoder", "decode called but library not loaded")
            return emptyList()
        }
        Log.d("Ft8Decoder", "decode start: ${samples.size}smp @${sampleRate}Hz ft4=$isFt4")
        return try {
            val json = nativeDecode(samples, sampleRate, isFt4)
            Log.d("Ft8Decoder", "decode raw: $json")
            val results = parseResults(json)
            Log.d("Ft8Decoder", "decode done: ${results.size} messages")
            results
        } catch (e: Throwable) {   // Throwable catches UnsatisfiedLinkError too
            Log.e("Ft8Decoder", "decode error: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    private fun parseResults(json: String): List<Result> {
        val arr = Gson().fromJson(json, JsonArray::class.java) ?: return emptyList()
        return arr.mapNotNull { el ->
            try {
                val o = el.asJsonObject
                Result(
                    snr = o.get("snr").asFloat,
                    dt  = o.get("dt").asFloat,
                    hz  = o.get("hz").asInt,
                    msg = o.get("msg").asString
                )
            } catch (e: Exception) { null }
        }
    }

    // ── Native functions ──────────────────────────────────────────

    @JvmStatic
    private external fun nativeDecode(
        samples: FloatArray, sampleRate: Int, isFt4: Boolean
    ): String

    @JvmStatic
    private external fun nativeEncode(
        message: String, isFt4: Boolean, sampleRate: Int, baseFreqHz: Float
    ): FloatArray

    @JvmStatic
    private external fun nativeVersion(): String
}
