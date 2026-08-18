package com.ji1ore.wifi_rig_ctrl.data

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
    val txInProgress: Boolean,
    val bkIn: Boolean = false,
    val apiVersion: String = "",
    val webft8Version: String = ""
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
    val comment: String,
    val destination: String,
    val soundDevice: String,
    val rigId: String,
    val catDevice: String,
    val useRigModem: Boolean = false,
    val modemSel: Int = 2,  // 1=AUTO 2=MAIN 3=SUB
    val enabled: Boolean = true
)

data class SoundDevice(val id: String, val label: String)

data class AprsHeardStation(
    val call: String,
    val lat: Double,
    val lon: Double,
    val comment: String,
    val symbol: String,
    val ageSec: Int
)

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
    StepSetting("500Hz",  500L),
    StepSetting("1kHz",   1000L),
    StepSetting("5kHz",   5000L),
    StepSetting("10kHz",  10000L),
    StepSetting("20kHz",  20000L)
)

val APRS_PATH_LIST = listOf("WIDE1-1", "WIDE1-1,WIDE2-1", "WIDE2-1", "DIRECT", "NONE")

data class AprsSymbol(val code: String, val label: String, val emoji: String) {
    val display get() = "$emoji $label"
}

val APRS_SYMBOL_LIST = listOf(
    AprsSymbol(">",  "Car",         "🚗"),
    AprsSymbol("v",  "Van",         "🚐"),
    AprsSymbol("k",  "Truck",       "🚚"),
    AprsSymbol("U",  "Bus",         "🚌"),
    AprsSymbol("<",  "Motorcycle",  "🏍"),
    AprsSymbol("j",  "Jeep",        "🚙"),
    AprsSymbol("[",  "Pedestrian",  "🚶"),
    AprsSymbol("b",  "Bike",        "🚲"),
    AprsSymbol("e",  "Horse",       "🐴"),
    AprsSymbol("-",  "House",       "🏠"),
    AprsSymbol("Y",  "Sailboat",    "⛵"),
    AprsSymbol("s",  "Motorboat",   "🚤"),
    AprsSymbol("X",  "Helicopter",  "🚁"),
    AprsSymbol("^",  "Aircraft",    "✈"),
    AprsSymbol("O",  "Balloon",     "🎈"),
    AprsSymbol("_",  "Wx Station",  "🌡"),
    AprsSymbol("r",  "Antenna",     "📡"),
    AprsSymbol("a",  "Ambulance",   "🚑"),
    AprsSymbol("f",  "Fire Truck",  "🚒"),
    AprsSymbol("h",  "Hospital",    "🏥"),
    AprsSymbol("n",  "Node",        "📦"),
    AprsSymbol("/",  "Red Dot",     "🔴"),
)

fun aprsSymbolByCode(code: String): AprsSymbol {
    // FastAPI returns sym_table+sym_code (2 chars, e.g. "/>"). Match on last char only.
    val lookup = if (code.length >= 2) code.last().toString() else code
    return APRS_SYMBOL_LIST.find { it.code == lookup } ?: AprsSymbol(lookup, lookup, "[$lookup]")
}

val APRS_DEST_LIST = listOf("APDW18", "APDW19", "APDW20", "APYA05", "APMI05", "APRS00")
val APRS_INTERVAL_LIST = listOf(30, 60, 120, 180, 300, 600)
val APRS_BAUD_LIST = listOf(1200, 9600)

data class MemoryEntry(
    val name: String,
    val freqHz: Long,
    val mode: String = "",       // 空文字 = 現在のモードのまま遷移
    val stepIndex: Int = -1,     // -1 = モードデフォルト
    val isPreset: Boolean = false,
    val toneMode: String? = null,   // null/"Tone"/"TSQL"/"DTCS"
    val toneHz: Double? = null,     // CTCSS/TSQL tone (Hz)
    val offsetDir: String? = null,  // null/"+"/ "-"
    val offsetHz: Long? = null      // repeater offset (Hz)
)

// STEP_LIST index: 0=1Hz 1=10Hz 2=100Hz 3=500Hz 4=1kHz 5=5kHz 6=10kHz 7=20kHz
val PRESET_MEMORIES = listOf(
    MemoryEntry("160m CW",    1_825_000L, "CW",  2, true),   // 100Hz
    MemoryEntry("160m SSB",   1_910_000L, "LSB", 4, true),   // 1kHz
    MemoryEntry("80m CW",     3_525_000L, "CW",  2, true),
    MemoryEntry("80m SSB",    3_740_000L, "LSB", 4, true),
    MemoryEntry("40m CW",     7_025_000L, "CW",  2, true),
    MemoryEntry("40m SSB",    7_100_000L, "LSB", 4, true),
    MemoryEntry("30m CW",    10_130_000L, "CW",  2, true),
    MemoryEntry("20m CW",    14_025_000L, "CW",  2, true),
    MemoryEntry("20m SSB",   14_225_000L, "USB", 4, true),
    MemoryEntry("17m CW",    18_080_000L, "CW",  2, true),
    MemoryEntry("17m SSB",   18_130_000L, "USB", 4, true),
    MemoryEntry("15m CW",    21_025_000L, "CW",  2, true),
    MemoryEntry("15m SSB",   21_225_000L, "USB", 4, true),
    MemoryEntry("12m CW",    24_895_000L, "CW",  2, true),
    MemoryEntry("12m SSB",   24_940_000L, "USB", 4, true),
    MemoryEntry("10m CW",    28_025_000L, "CW",  2, true),
    MemoryEntry("10m SSB",   28_400_000L, "USB", 4, true),
    MemoryEntry("6m SSB",    50_100_000L, "USB", 4, true),
    MemoryEntry("2m CW",    144_060_000L, "CW",  2, true),
    MemoryEntry("2m SSB",   144_200_000L, "USB", 4, true),
    MemoryEntry("2m FM",    145_000_000L, "FM",  7, true),   // 20kHz
    MemoryEntry("70cm CW",  430_050_000L, "CW",  2, true),
    MemoryEntry("70cm SSB", 430_100_000L, "USB", 4, true),
    MemoryEntry("70cm FM",  433_000_000L, "FM",  7, true),
)

// MARK: - POTA/SOTA spot data classes

data class PotaSpot(
    val spotId: Long = 0,
    val activator: String = "",
    val frequency: String = "",   // kHz (e.g. "14025" = 14.025 MHz)
    val mode: String = "",
    val reference: String = "",
    val name: String = "",
    val spotTime: String = "",
    val spotter: String = "",
    val comments: String = "",
    val invalid: Boolean = false,
    val locationDesc: String = ""
) {
    val freqMhz: Double get() = (frequency.toDoubleOrNull() ?: 0.0) / 1000.0
    val freqHz: Long   get() = ((frequency.toDoubleOrNull() ?: 0.0) * 1000).toLong()
}

data class SotaSpot(
    val id: Long = 0,
    val timeStamp: String? = null,
    val comments: String? = null,
    val callsign: String? = null,
    val activatorCallsign: String? = null,
    val activatorName: String? = null,
    val frequency: String? = null,   // MHz (e.g. "14.025")
    val mode: String? = null,
    val summitCode: String? = null,
    val associationCode: String? = null,
    val summitDetails: String? = null
) {
    val freqMhz: Double get() = frequency?.toDoubleOrNull() ?: 0.0
    val freqHz: Long   get() = ((frequency?.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong()
}