package com.ji1ore.wifi_rig_ctrl.data

data class Ft8BandEntry(
    val band: String,
    val freqMhz: Float,
    val region: String = ""   // "" = global, "JA" = Japan
)

object Ft8Constants {

    // ── FT8 国際周波数 ───────────────────────────────────────────
    val FT8_BANDS_GLOBAL = listOf(
        Ft8BandEntry("160m",   1.840f),
        Ft8BandEntry("80m",    3.573f),
        Ft8BandEntry("60m",    5.357f),
        Ft8BandEntry("40m",    7.074f),
        Ft8BandEntry("30m",   10.136f),
        Ft8BandEntry("20m",   14.074f),
        Ft8BandEntry("17m",   18.100f),
        Ft8BandEntry("15m",   21.074f),
        Ft8BandEntry("12m",   24.915f),
        Ft8BandEntry("10m",   28.074f),
        Ft8BandEntry("6m",    50.313f),
        Ft8BandEntry("2m",   144.174f),
    )

    // ── FT8 日本 (JARL バンドプラン) ────────────────────────────
    // 40m: 日本では 7.041 MHz を使用（7.100 MHz 上限のため）
    // 2m/70cm: JA国内運用実績値
    val FT8_BANDS_JA = listOf(
        Ft8BandEntry("160m",   1.840f, "JA"),
        Ft8BandEntry("80m",    3.530f, "JA"),   // JA 80m FT8 common freq
        Ft8BandEntry("60m",    5.357f, "JA"),
        Ft8BandEntry("40m",    7.041f, "JA"),   // JA 40m (vs global 7.074)
        Ft8BandEntry("30m",   10.136f, "JA"),
        Ft8BandEntry("20m",   14.074f, "JA"),
        Ft8BandEntry("17m",   18.100f, "JA"),
        Ft8BandEntry("15m",   21.074f, "JA"),
        Ft8BandEntry("12m",   24.915f, "JA"),
        Ft8BandEntry("10m",   28.074f, "JA"),
        Ft8BandEntry("6m",    50.313f, "JA"),
        Ft8BandEntry("2m",   144.174f, "JA"),   // 国際標準
        Ft8BandEntry("2m JA", 144.460f, "JA"),  // JA国内 2m
        Ft8BandEntry("70cm", 430.510f, "JA"),   // JA 70cm
    )

    // ── FT4 国際周波数 ───────────────────────────────────────────
    val FT4_BANDS_GLOBAL = listOf(
        Ft8BandEntry("80m",    3.575f),
        Ft8BandEntry("40m",    7.047f),
        Ft8BandEntry("20m",   14.080f),
        Ft8BandEntry("15m",   21.140f),
        Ft8BandEntry("10m",   28.180f),
        Ft8BandEntry("6m",    50.318f),
    )

    val FT4_BANDS_JA = listOf(
        Ft8BandEntry("80m",    3.575f, "JA"),
        Ft8BandEntry("40m",    7.047f, "JA"),   // FT4 40m は同じ
        Ft8BandEntry("20m",   14.080f, "JA"),
        Ft8BandEntry("15m",   21.140f, "JA"),
        Ft8BandEntry("10m",   28.180f, "JA"),
        Ft8BandEntry("6m",    50.318f, "JA"),
    )

    // 後方互換
    val FT8_BANDS get() = FT8_BANDS_GLOBAL
    val FT4_BANDS get() = FT4_BANDS_GLOBAL

    const val FT8_CYCLE_MS = 15000
    const val FT4_CYCLE_MS = 7500
}

data class Ft8Message(
    val utcTime: String,
    val snr: Int,
    val dt: Float,
    val freqHz: Int,
    val message: String
)
