package com.ji1ore.wifi_rig_ctrl.data

data class ProfileConfig(
    val name: String = "Profile 1",
    val hostName: String = "",
    val apiPort: Int = 8000,
    val audioPort: Int = 50000,
    val useMDNS: Boolean = false,
    val apiKey: String = "",
    val savedRigId: Int = -1,
    val savedCat: String = "",
    val savedPttDevice: String = "NONE",
    val savedPttType: String = "RIG",
    val savedBaudIndex: Int = 2,
    val savedSamplingIndex: Int = 1,
    val savedTxSamplingIndex: Int = 1,
    val savedTimeoutIndex: Int = 2,
    val useWifiPTT: Boolean = false,
    val pttHost: String = "",
    val pttPort: Int = 8888,
    val cwDelayMs: Int = 0,
    val cwFmDelayMs: Int = 0,
    val alsaDevice: String = "",
    val alsaDeviceFt8: String = "",
    // CI-V direct connection (IC-705 etc.)
    val useCIV: Boolean = false,
    val civHost: String = "",
    val civPort: Int = 50001,
    val civPort2: Int = 50002,
    val civPort3: Int = 50003,
    val civUser: String = "",
    val civPassword: String = "",
    val civAddress: Int = 0xA4,
    val civConnectionType: String = "WIFI",   // "WIFI" or "BT"
    val civBtDeviceAddress: String = ""
)