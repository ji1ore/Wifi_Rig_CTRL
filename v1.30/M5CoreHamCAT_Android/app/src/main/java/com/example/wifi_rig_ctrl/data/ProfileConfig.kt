package com.example.wifi_rig_ctrl.data

data class ProfileConfig(
    val name: String = "Profile 1",
    val hostName: String = "",
    val apiPort: Int = 8210,
    val audioPort: Int = 8211,
    val useMDNS: Boolean = false,
    val apiKey: String = "",
    val savedRigId: Int = -1,
    val savedCat: String = "",
    val savedPttDevice: String = "NONE",
    val savedPttType: String = "RTS",
    val savedBaudIndex: Int = 2,
    val savedSamplingIndex: Int = 1,
    val savedTimeoutIndex: Int = 2,
    val useWifiPTT: Boolean = false,
    val pttHost: String = "",
    val pttPort: Int = 5198
)