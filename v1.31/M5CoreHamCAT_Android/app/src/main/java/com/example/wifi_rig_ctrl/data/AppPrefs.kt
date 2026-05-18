package com.example.wifi_rig_ctrl.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AppPrefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("wifi_rig_ctrl", Context.MODE_PRIVATE)
    private val gson = Gson()

    // --- Profile management ---

    fun getProfiles(): MutableList<ProfileConfig> {
        val json = p.getString("profiles_json", null)
        return if (json != null) {
            try {
                val type = object : TypeToken<MutableList<ProfileConfig>>() {}.type
                gson.fromJson(json, type) ?: mutableListOf(createDefaultProfile())
            } catch (_: Exception) { mutableListOf(createDefaultProfile()) }
        } else {
            val default = createDefaultProfile()
            saveProfiles(mutableListOf(default))
            mutableListOf(default)
        }
    }

    fun saveProfiles(profiles: List<ProfileConfig>) {
        p.edit().putString("profiles_json", gson.toJson(profiles)).apply()
    }

    var activeProfileIndex: Int
        get() = p.getInt("active_profile_index", 0)
        set(v) = p.edit().putInt("active_profile_index", v).apply()

    fun getActiveProfile(): ProfileConfig {
        val profiles = getProfiles()
        val idx = activeProfileIndex.coerceIn(0, maxOf(0, profiles.size - 1))
        return profiles.getOrElse(idx) { createDefaultProfile() }
    }

    fun applyProfile(profile: ProfileConfig) {
        p.edit()
            .putString("hostName", profile.hostName)
            .putInt("apiPort", profile.apiPort)
            .putInt("audioPort", profile.audioPort)
            .putBoolean("useMDNS", profile.useMDNS)
            .putString("apiKey", profile.apiKey)
            .putInt("rigId", profile.savedRigId)
            .putString("cat", profile.savedCat)
            .putString("pttDevice", profile.savedPttDevice)
            .putString("pttType", profile.savedPttType)
            .putInt("baudIndex", profile.savedBaudIndex)
            .putInt("samplingIndex", profile.savedSamplingIndex)
            .putInt("timeoutIndex", profile.savedTimeoutIndex)
            .putBoolean("useWifiPTT", profile.useWifiPTT)
            .putString("pttHost", profile.pttHost)
            .putInt("pttPort", profile.pttPort)
            .apply()
    }

    private fun createDefaultProfile(): ProfileConfig = ProfileConfig(
        name = "Default",
        hostName = p.getString("hostName", "") ?: "",
        apiPort = p.getInt("apiPort", 8000),
        audioPort = p.getInt("audioPort", 50000),
        useMDNS = p.getBoolean("useMDNS", false),
        apiKey = p.getString("apiKey", "") ?: "",
        savedRigId = p.getInt("rigId", -1),
        savedCat = p.getString("cat", "") ?: "",
        savedPttDevice = p.getString("pttDevice", "NONE") ?: "NONE",
        savedPttType = p.getString("pttType", "RTS") ?: "RTS",
        savedBaudIndex = p.getInt("baudIndex", 2),
        savedSamplingIndex = p.getInt("samplingIndex", 1),
        savedTimeoutIndex = p.getInt("timeoutIndex", 2),
        useWifiPTT = p.getBoolean("useWifiPTT", false),
        pttHost = p.getString("pttHost", "") ?: "",
        pttPort = p.getInt("pttPort", 8888)
    )

    // --- Flat prefs (kept in sync with active profile via applyProfile) ---

    var hostName: String get() = p.getString("hostName", "") ?: ""; set(v) = p.edit().putString("hostName", v).apply()
    var apiPort: Int get() = p.getInt("apiPort", 8000); set(v) = p.edit().putInt("apiPort", v).apply()
    var audioPort: Int get() = p.getInt("audioPort", 50000); set(v) = p.edit().putInt("audioPort", v).apply()
    var useMDNS: Boolean get() = p.getBoolean("useMDNS", false); set(v) = p.edit().putBoolean("useMDNS", v).apply()
    var apiKey: String get() = p.getString("apiKey", "") ?: ""; set(v) = p.edit().putString("apiKey", v).apply()

    var savedRigId: Int get() = p.getInt("rigId", -1); set(v) = p.edit().putInt("rigId", v).apply()
    var savedBaudIndex: Int get() = p.getInt("baudIndex", 2); set(v) = p.edit().putInt("baudIndex", v).apply()
    var savedSamplingIndex: Int get() = p.getInt("samplingIndex", 1); set(v) = p.edit().putInt("samplingIndex", v).apply()
    var savedTimeoutIndex: Int get() = p.getInt("timeoutIndex", 2); set(v) = p.edit().putInt("timeoutIndex", v).apply()
    var savedCat: String get() = p.getString("cat", "") ?: ""; set(v) = p.edit().putString("cat", v).apply()
    var savedPttDevice: String get() = p.getString("pttDevice", "NONE") ?: "NONE"; set(v) = p.edit().putString("pttDevice", v).apply()
    var savedPttType: String get() = p.getString("pttType", "RTS") ?: "RTS"; set(v) = p.edit().putString("pttType", v).apply()

    var useWifiPTT: Boolean get() = p.getBoolean("useWifiPTT", false); set(v) = p.edit().putBoolean("useWifiPTT", v).apply()
    var pttHost: String get() = p.getString("pttHost", "") ?: ""; set(v) = p.edit().putString("pttHost", v).apply()
    var pttPort: Int get() = p.getInt("pttPort", 8888); set(v) = p.edit().putInt("pttPort", v).apply()

    var aprsEnabled: Boolean get() = p.getBoolean("aprsEnabled", false); set(v) = p.edit().putBoolean("aprsEnabled", v).apply()
    var aprsUseGPS: Boolean get() = p.getBoolean("aprsUseGPS", false); set(v) = p.edit().putBoolean("aprsUseGPS", v).apply()
    var aprsLat: Float get() = p.getFloat("aprsLat", 0f); set(v) = p.edit().putFloat("aprsLat", v).apply()
    var aprsLon: Float get() = p.getFloat("aprsLon", 0f); set(v) = p.edit().putFloat("aprsLon", v).apply()
    var aprsTxFreq: Float get() = p.getFloat("aprsTxFreq", 144.660f); set(v) = p.edit().putFloat("aprsTxFreq", v).apply()
    var aprsBaud: Int get() = p.getInt("aprsBaud", 1200); set(v) = p.edit().putInt("aprsBaud", v).apply()
    var aprsCallsign: String get() = p.getString("aprsCallsign", "") ?: ""; set(v) = p.edit().putString("aprsCallsign", v).apply()
    var aprsSSID: Int get() = p.getInt("aprsSSID", 0); set(v) = p.edit().putInt("aprsSSID", v).apply()
    var aprsIntervalSec: Int get() = p.getInt("aprsInterval", 60); set(v) = p.edit().putInt("aprsInterval", v).apply()
    var aprsPath: String get() = p.getString("aprsPath", "WIDE1-1") ?: "WIDE1-1"; set(v) = p.edit().putString("aprsPath", v).apply()
    var aprsSymbol: String get() = p.getString("aprsSymbol", ">") ?: ">"; set(v) = p.edit().putString("aprsSymbol", v).apply()
    var aprsDestination: String get() = p.getString("aprsDestination", "APRS00") ?: "APRS00"; set(v) = p.edit().putString("aprsDestination", v).apply()
    var aprsSoundDevice: String get() = p.getString("aprsSoundDevice", "") ?: ""; set(v) = p.edit().putString("aprsSoundDevice", v).apply()

    var volume: Float get() = p.getFloat("volume", 0.5f); set(v) = p.edit().putFloat("volume", v).apply()

    fun getModeStep(mode: String): Int = p.getInt("step_$mode", defaultStepFor(mode))
    fun setModeStep(mode: String, step: Int) = p.edit().putInt("step_$mode", step).apply()

    companion object {
        fun defaultStepFor(mode: String): Int {
            val m = mode.uppercase()
            return when {
                m.contains("FM") || m.contains("AM") -> 6  // 20kHz
                m == "CW" || m == "CWR" || m == "CW-R" -> 2  // 100Hz
                else -> 3  // 1kHz (USB/LSB/RTTY/PKT等)
            }
        }
    }
}
