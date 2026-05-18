package com.example.wifi_rig_ctrl.data

data class RigInfo(val id: Int, val name: String)

data class CwStatus(val connected: Boolean, val synced: Boolean, val offsetMs: Long, val maxLateMs: Int = 0)

data class RigStatus(
    val freq: Long,
    val mode: String,
    val signal: Float,
    val tx: Boolean,
    val power: Float,
    val width: Int,
    val sql: Float,
    val txInProgress: Boolean
)

data class AprsConfig(
    val callsign: String,
    val ssid: Int,
    val path: String,
    val interval: Int,
    val freq: Float,
    val baud: Int,
    val useGps: Boolean,
    val manualLat: Float,
    val manualLon: Float,
    val symbol: String,
    val destination: String,
    val soundDevice: String,
    val rigId: String,
    val catDevice: String
)

data class SoundDevice(val id: String, val label: String)

enum class MenuItem {
    FREQ, STEP, MODE, WIDTH, POW, SQL, APRS, PTT, BACK, SPK, DOWN, UP, RVOL, NONE
}

val BAUD_RATES = listOf(1200, 4800, 9600, 19200, 38400, 57600, 115200)
val SAMPLING_RATES = listOf(0, 8000, 16000, 22050, 32000, 44100, 48000)
val SCREEN_TIMEOUT_OPTIONS = listOf(0, 5, 10, 30, 60)

data class StepSetting(val label: String, val stepHz: Long)
val STEP_LIST = listOf(
    StepSetting("1Hz",    1L),
    StepSetting("10Hz",   10L),
    StepSetting("100Hz",  100L),
    StepSetting("1kHz",   1000L),
    StepSetting("5kHz",   5000L),
    StepSetting("10kHz",  10000L),
    StepSetting("20kHz",  20000L)
)

val APRS_PATH_LIST = listOf("WIDE1-1", "WIDE1-1,WIDE2-1", "WIDE2-1", "DIRECT", "NONE")
val APRS_SYMBOL_LIST = listOf(">", "v", "[", "/", "-")
val APRS_DEST_LIST = listOf("APDW18", "APDW19", "APDW20", "APYA05", "APMI05", "APRS00")
val APRS_INTERVAL_LIST = listOf(30, 60, 120, 180, 300, 600)
val APRS_BAUD_LIST = listOf(1200, 9600)