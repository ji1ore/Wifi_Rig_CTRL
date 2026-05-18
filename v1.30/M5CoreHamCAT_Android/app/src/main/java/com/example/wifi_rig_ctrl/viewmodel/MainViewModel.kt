package com.example.wifi_rig_ctrl.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.wifi_rig_ctrl.data.*
import kotlinx.coroutines.*
import kotlin.math.log10

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = AppPrefs(app)
    val api = RigApiService(prefs.hostName, prefs.apiPort, prefs.apiKey)
    val audio = AudioStreamService()
    val audioTx = AudioTxService(api.client)
    val udpPtt = UdpPttService()

    // Connection
    val hostName = MutableLiveData(prefs.hostName)
    val apiPort = MutableLiveData(prefs.apiPort)
    val audioPort = MutableLiveData(prefs.audioPort)
    val useMDNS = MutableLiveData(prefs.useMDNS)
    val apiKey = MutableLiveData(prefs.apiKey)

    // Rig lists
    val rigList = MutableLiveData<List<RigInfo>>(emptyList())
    val catList = MutableLiveData<List<String>>(emptyList())
    val soundDeviceList = MutableLiveData<List<SoundDevice>>(emptyList())

    // Selected rig settings
    val selectedRigIndex = MutableLiveData(0)
    val selectedCatIndex = MutableLiveData(0)
    val selectedPttDevice = MutableLiveData(prefs.savedPttDevice)
    val selectedPttType = MutableLiveData(prefs.savedPttType)
    val selectedBaudIndex = MutableLiveData(prefs.savedBaudIndex)
    val selectedSamplingIndex = MutableLiveData(prefs.savedSamplingIndex)
    val selectedTimeoutIndex = MutableLiveData(prefs.savedTimeoutIndex)

    // PTT settings
    val useWifiPTT = MutableLiveData(prefs.useWifiPTT)
    val pttHost = MutableLiveData(prefs.pttHost)
    val pttPort = MutableLiveData(prefs.pttPort)

    // Rig status
    val sharedFreq = MutableLiveData(0L)
    val sharedMode = MutableLiveData("")
    val sharedModel = MutableLiveData("")
    val sharedSignal = MutableLiveData(0f)
    val sharedTx = MutableLiveData(false)
    val sharedPower = MutableLiveData(0f)
    val sharedSQL = MutableLiveData(0f)
    val sharedWidth = MutableLiveData(0)
    val sharedVolume = MutableLiveData(prefs.volume)

    // UI control state
    val txEnabled = MutableLiveData(false)
    val spkEnabled = MutableLiveData(false)
    val selectedMenuItem = MutableLiveData(MenuItem.NONE)
    val selectedStep = MutableLiveData(0)
    val supportedModes = MutableLiveData<List<String>>(emptyList())

    // APRS
    val aprsEnabled = MutableLiveData(prefs.aprsEnabled)
    val aprsActive = MutableLiveData(false)
    val aprsUseGPS = MutableLiveData(prefs.aprsUseGPS)
    val aprsLat = MutableLiveData(prefs.aprsLat)
    val aprsLon = MutableLiveData(prefs.aprsLon)
    val aprsTxFreq = MutableLiveData(prefs.aprsTxFreq)
    val aprsBaud = MutableLiveData(prefs.aprsBaud)
    val aprsCallsign = MutableLiveData(prefs.aprsCallsign)
    val aprsSSID = MutableLiveData(prefs.aprsSSID)
    val aprsIntervalSec = MutableLiveData(prefs.aprsIntervalSec)
    val aprsPath = MutableLiveData(prefs.aprsPath)
    val aprsSymbol = MutableLiveData(prefs.aprsSymbol)
    val aprsDestination = MutableLiveData(prefs.aprsDestination)
    val aprsSoundDevice = MutableLiveData(prefs.aprsSoundDevice)
    val aprsTxInProgress = MutableLiveData(false)

    // Status
    val errorMessage = MutableLiveData<String?>(null)
    val audioError = MutableLiveData<String?>(null)
    val isConnectedToRig = MutableLiveData(false)

    // User interaction timestamps (protect UI updates from overwriting user input)
    private var lastUserFreqChange = 0L
    private var lastUserModeChange = 0L
    private var lastUserPowerChange = 0L
    private var lastUserSQLChange = 0L
    private var lastUserVolumeChange = 0L
    private var lastUserWidthChange = 0L

    private var statusPollingJob: Job? = null
    private var aprsHeartbeatJob: Job? = null
    private var pttHeartbeatJob: Job? = null
    private var audioTxStartJob: Job? = null
    private var pttOffJob: Job? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var externalPttActive = false  // M5物理ボタン等の外部PTT検出フラグ
    private var serverWatchdogJob: Job? = null  // WiFi PTT時のサーバーwatchdog更新（UDP heartbeatと独立）

    // --- Profile management ---

    fun loadProfile(index: Int) {
        prefs.activeProfileIndex = index
        val profiles = prefs.getProfiles()
        val profile = profiles.getOrElse(index) { ProfileConfig() }
        prefs.applyProfile(profile)

        hostName.value = profile.hostName
        apiPort.value = profile.apiPort
        audioPort.value = profile.audioPort
        useMDNS.value = profile.useMDNS
        apiKey.value = profile.apiKey
        selectedBaudIndex.value = profile.savedBaudIndex
        selectedSamplingIndex.value = profile.savedSamplingIndex
        selectedTimeoutIndex.value = profile.savedTimeoutIndex
        useWifiPTT.value = profile.useWifiPTT
        pttHost.value = profile.pttHost
        pttPort.value = profile.pttPort
        selectedPttDevice.value = profile.savedPttDevice
        selectedPttType.value = profile.savedPttType

        api.updateConnection(profile.hostName, profile.apiPort, profile.apiKey)
    }

    fun saveCurrentToProfile() {
        val profiles = prefs.getProfiles()
        val idx = prefs.activeProfileIndex.coerceIn(0, maxOf(0, profiles.size - 1))
        val oldName = profiles.getOrElse(idx) { ProfileConfig() }.name
        val updated = ProfileConfig(
            name = oldName,
            hostName = hostName.value ?: "",
            apiPort = apiPort.value ?: 8210,
            audioPort = audioPort.value ?: 8211,
            useMDNS = useMDNS.value ?: false,
            apiKey = apiKey.value ?: "",
            savedRigId = prefs.savedRigId,
            savedCat = prefs.savedCat,
            savedPttDevice = selectedPttDevice.value ?: "NONE",
            savedPttType = selectedPttType.value ?: "RTS",
            savedBaudIndex = selectedBaudIndex.value ?: 2,
            savedSamplingIndex = selectedSamplingIndex.value ?: 1,
            savedTimeoutIndex = selectedTimeoutIndex.value ?: 2,
            useWifiPTT = useWifiPTT.value ?: false,
            pttHost = pttHost.value ?: "",
            pttPort = pttPort.value ?: 5198
        )
        if (idx < profiles.size) profiles[idx] = updated else profiles.add(updated)
        prefs.saveProfiles(profiles)
    }

    // --- Connection ---

    fun updateConnectionSettings(host: String, port: Int, aPort: Int, mdns: Boolean, key: String = "") {
        hostName.value = host; apiPort.value = port; audioPort.value = aPort
        useMDNS.value = mdns; apiKey.value = key
        prefs.hostName = host; prefs.apiPort = port; prefs.audioPort = aPort
        prefs.useMDNS = mdns; prefs.apiKey = key
        api.updateConnection(host, port, key)
        saveCurrentToProfile()
    }

    suspend fun connectToRasPi(): String? = withContext(Dispatchers.IO) {
        try {
            val rawHost = hostName.value ?: return@withContext "No host"
            val port = apiPort.value ?: 8210
            val key = apiKey.value ?: ""
            val host = if (useMDNS.value == true) {
                // .local が付いていなければ自動付加
                val mdnsName = if (!rawHost.endsWith(".local")) "$rawHost.local" else rawHost
                try {
                    // IPアドレスに解決（失敗時は .local ホスト名のまま使用）
                    java.net.InetAddress.getByName(mdnsName).hostAddress ?: mdnsName
                } catch (e: Exception) {
                    mdnsName  // 解決失敗でもホスト名のまま接続チャレンジ
                }
            } else rawHost
            val tempApi = RigApiService(host, port, key)
            val rigs = tempApi.getRigs()
            if (rigs.isEmpty()) return@withContext "No rigs found on server"

            api.updateConnection(host, port, key)
            val (cats, sounds) = tempApi.getDevices()
            val savedCat = prefs.savedCat
            val catDevices = when {
                cats.isNotEmpty() -> cats
                savedCat.isNotEmpty() -> listOf(savedCat)
                else -> listOf("None")
            }

            withContext(Dispatchers.Main) {
                rigList.value = rigs
                catList.value = catDevices
                soundDeviceList.value = sounds
                val savedId = prefs.savedRigId
                val idx = rigs.indexOfFirst { it.id == savedId }.takeIf { it >= 0 } ?: 0
                selectedRigIndex.value = idx
                val catIdx = catDevices.indexOfFirst { it == savedCat }.takeIf { it >= 0 } ?: 0
                selectedCatIndex.value = catIdx
            }
            null
        } catch (e: Exception) {
            "Connection failed: ${e.message}"
        }
    }

    suspend fun skipConnect(): String? {
        if (prefs.hostName.isEmpty()) return "ホストが未設定です"
        if (prefs.savedRigId < 0) return "リグ設定が保存されていません"
        val err = connectToRasPi()
        if (err != null) return err
        val result = connectRig()
        if (result == null) isConnectedToRig.value = true
        return result
    }

    suspend fun connectRig(): String? = withContext(Dispatchers.IO) {
        try {
            val rigs = rigList.value ?: return@withContext "No rigs"
            val cats = catList.value ?: return@withContext "No devices"
            if (rigs.isEmpty()) return@withContext "No rigs found"

            val rig = rigs[selectedRigIndex.value ?: 0]
            val cat = cats.getOrElse(selectedCatIndex.value ?: 0) { "None" }
            val baud = BAUD_RATES.getOrElse(selectedBaudIndex.value ?: 2) { 9600 }

            if (useWifiPTT.value == true) {
                val h = pttHost.value
                if (h.isNullOrEmpty()) return@withContext "PTT host not set"
                val pt = pttPort.value ?: 5198
                // M5Atom時刻同期: currentTimeMillis()をベース時刻として送信
                udpPtt.syncTime(h, pt)
            }

            val ptt = selectedPttDevice.value ?: "NONE"
            val pttType = selectedPttType.value ?: "RTS"
            prefs.savedRigId = rig.id
            prefs.savedCat = cat
            prefs.savedPttDevice = ptt
            prefs.savedPttType = pttType
            prefs.savedBaudIndex = selectedBaudIndex.value ?: 2
            prefs.savedSamplingIndex = selectedSamplingIndex.value ?: 1
            prefs.savedTimeoutIndex = selectedTimeoutIndex.value ?: 2
            prefs.useWifiPTT = useWifiPTT.value ?: false
            prefs.pttHost = pttHost.value ?: ""
            prefs.pttPort = pttPort.value ?: 5198

            saveCurrentToProfile()

            api.openRig(rig.id, cat, baud, ptt, pttType)
            delay(1000)

            repeat(50) {
                val st = api.getStatus()
                if (st != null) {
                    withContext(Dispatchers.Main) { sharedModel.value = rig.name }
                    return@withContext null
                }
                delay(200)
            }
            "Connect timeout"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun startStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = viewModelScope.launch {
            while (isActive) {
                updateStatus()
                delay(200)
            }
        }
    }

    fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    private suspend fun updateStatus() {
        val st = withContext(Dispatchers.IO) { api.getStatus() } ?: return
        val now = System.currentTimeMillis()

        val raw = maxOf(0f, st.signal)
        var s = 9f * (log10(raw + 1f) / log10(21f))
        if (s > 9f) s = 9f + (s - 9f) * 2f
        s = s.coerceIn(0f, 15f)

        if (now - lastUserFreqChange > 1000) sharedFreq.value = st.freq
        if (now - lastUserModeChange > 1000) {
            val newMode = st.mode
            if (newMode != sharedMode.value && newMode.isNotEmpty()) {
                sharedMode.value = newMode
                selectedStep.value = prefs.getModeStep(newMode)
            }
        }
        if (now - lastUserPowerChange > 1000) sharedPower.value = st.power
        if (now - lastUserSQLChange > 1000) sharedSQL.value = st.sql
        if (now - lastUserWidthChange > 1000) sharedWidth.value = st.width
        sharedSignal.value = s
        sharedTx.value = st.tx
        aprsTxInProgress.value = st.txInProgress

        // 外部PTT検出: M5物理ボタン等でradiocacheがTX=trueになったら音声送信を追従
        // pttOffJob/audioTxStartJob が実行中の場合はAndroidの操作途中なのでスキップ
        if (pttOffJob == null && audioTxStartJob == null) {
            if (st.tx && txEnabled.value != true) {
                // 外部デバイスが送信開始 → AndroidもTX音声を開始
                externalPttActive = true
                txEnabled.value = true
                audioTx.stop()
                viewModelScope.launch(Dispatchers.IO) { api.pttHeartbeat() }  // watchdog即時リセット（rigctlなし）
                audioTxStartJob = viewModelScope.launch {
                    delay(300)
                    audioTx.start(api.getAudioTxUrl(), apiKey.value ?: "")
                }
                startPttHeartbeat()  // 継続的なwatchdog更新
            } else if (!st.tx && txEnabled.value == true && externalPttActive) {
                // 外部デバイスがPTT解放 → AndroidもTX停止
                externalPttActive = false
                txEnabled.value = false
                audioTx.stop()
                stopPttHeartbeat()
                viewModelScope.launch(Dispatchers.IO) { api.setPtt(false) }  // サーバーlast_ptt_stateをリセット
                if (spkEnabled.value == true) startAudio()
            }
        }
    }

    fun fetchModeList() = viewModelScope.launch(Dispatchers.IO) {
        val modes = api.getCaps()
        withContext(Dispatchers.Main) { supportedModes.value = modes }
    }

    fun sendFreq(freqHz: Long) {
        lastUserFreqChange = System.currentTimeMillis()
        sharedFreq.value = freqHz
        viewModelScope.launch(Dispatchers.IO) { api.setFreq(freqHz) }
    }

    fun sendMode(mode: String, width: Int) {
        lastUserModeChange = System.currentTimeMillis()
        sharedMode.value = mode
        viewModelScope.launch(Dispatchers.IO) { api.setMode(mode, width) }
    }

    fun sendPower(value: Float) {
        lastUserPowerChange = System.currentTimeMillis()
        sharedPower.value = value
        viewModelScope.launch(Dispatchers.IO) { api.setPower(value) }
    }

    fun sendSQL(value: Float) {
        lastUserSQLChange = System.currentTimeMillis()
        sharedSQL.value = value
        viewModelScope.launch(Dispatchers.IO) { api.setLevel("SQL", value) }
    }

    fun sendVolume(value: Float) {
        lastUserVolumeChange = System.currentTimeMillis()
        sharedVolume.value = value
        prefs.volume = value
        audio.setVolume(value)
        viewModelScope.launch(Dispatchers.IO) { api.setLevel("VOL", value) }
    }

    fun setPttEnabled(on: Boolean) {
        externalPttActive = false  // Androidが操作を引き継ぐ
        txEnabled.value = on
        if (on) {
            // 保留中のPTT OFFを取消し、強制クローズ
            pttOffJob?.cancel()
            pttOffJob = null
            audioTx.stop()
            audio.stop()
            audioTxStartJob?.cancel()
            audioTxStartJob = viewModelScope.launch {
                delay(300)
                audioTx.start(api.getAudioTxUrl(), apiKey.value ?: "")
            }
            viewModelScope.launch(Dispatchers.IO) {
                if (useWifiPTT.value == true) {
                    val h = pttHost.value
                    val pt = pttPort.value ?: 5198
                    android.util.Log.d("PTT", "WiFi PTT ON → host=$h port=$pt")
                    if (h.isNullOrEmpty()) {
                        android.util.Log.e("PTT", "pttHost is empty, skipping UDP")
                        return@launch
                    }
                    udpPtt.sendPtt(h, pt, true)
                    api.pttHeartbeat()  // watchdog用: rigctlは呼ばない
                    api.setPoll(false)
                } else {
                    android.util.Log.d("PTT", "Hamlib PTT ON")
                    api.setPtt(true)
                    api.setPoll(false)
                }
            }
            startPttHeartbeat()
        } else {
            audioTxStartJob?.cancel()
            audioTxStartJob = null
            audioTx.gracefulStop()  // マイク停止、残音送出開始（txJobは継続）
            // ハートビートをt=0で即停止: 後続のsetPtt(false)とin-flight setPtt(true)の競合を防ぐ
            stopPttHeartbeat()
            pttOffJob = viewModelScope.launch {
                val drainJob = launch(Dispatchers.IO) { audioTx.awaitDrain() }
                if (spkEnabled.value == true) startAudio()  // drain待ちと並行で起動
                drainJob.join()
                delay(100)  // Piのaplayバッファ内の最終音声が無線に乗るまでの余裕
                withContext(Dispatchers.IO) {
                    if (useWifiPTT.value == true) {
                        val h = pttHost.value
                        val pt = pttPort.value ?: 5198
                        if (!h.isNullOrEmpty()) udpPtt.sendPtt(h, pt, false)
                        api.setPtt(false)
                        api.setPoll(true)
                    } else {
                        api.setPtt(false)
                        api.setPoll(true)
                    }
                }
                audioTx.stop()
                delay(200)
                withContext(Dispatchers.IO) {
                    if (useWifiPTT.value == true) {
                        val h = pttHost.value
                        val pt = pttPort.value ?: 5198
                        if (!h.isNullOrEmpty()) udpPtt.sendPtt(h, pt, false)
                    } else {
                        api.setPtt(false)
                    }
                    Unit
                }
                pttOffJob = null
            }
        }
    }

    private fun startPttHeartbeat() {
        pttHeartbeatJob?.cancel()
        serverWatchdogJob?.cancel()
        pttHeartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                withContext(Dispatchers.IO) {
                    if (useWifiPTT.value == true) {
                        // WiFi PTTモード: UDPのみ送信（高速・ブロックなし）
                        val h = pttHost.value
                        val pt = pttPort.value ?: 5198
                        if (!h.isNullOrEmpty()) udpPtt.sendPtt(h, pt, true)
                    } else {
                        // Hamlibモード: last_heartbeatのみ更新
                        // setPtt(true)を使うとin-flight応答がsetPtt(false)より後に届いた場合に
                        // last_ptt_state=0からTX再起動するレース条件が発生する
                        api.pttHeartbeat()
                    }
                    Unit
                }
            }
        }
        // WiFi PTTモード: サーバーwatchdog更新を独立したジョブで実行
        // HTTPをUDP heartbeatとは切り離して遅延の影響をなくす
        if (useWifiPTT.value == true) {
            serverWatchdogJob = viewModelScope.launch {
                while (isActive) {
                    delay(2000)
                    withContext(Dispatchers.IO) { api.pttHeartbeat() }  // rigctlなしでwatchdogリセット
                }
            }
        }
    }

    private fun stopPttHeartbeat() {
        pttHeartbeatJob?.cancel()
        pttHeartbeatJob = null
        serverWatchdogJob?.cancel()
        serverWatchdogJob = null
    }

    fun startAudio() {
        if (hostName.value.isNullOrEmpty()) return
        if (txEnabled.value == true) return  // TX中はSPK起動しない
        val aPort = audioPort.value ?: 8211
        val sIdx = selectedSamplingIndex.value ?: 1
        val rate = SAMPLING_RATES.getOrElse(sIdx) { 0 }
        if (rate == 0) return
        audio.start(
            url = api.getAudioStreamUrl(aPort, rate),
            vol = sharedVolume.value ?: 0.5f,
            apiKey = apiKey.value ?: "",
            onError = { msg -> audioError.postValue(msg) }
        )
    }

    fun stopAudio() = audio.stop()

    fun toggleAprs() {
        val cfg = buildAprsConfig()
        viewModelScope.launch {
            if (aprsActive.value != true) {
                withContext(Dispatchers.IO) {
                    api.sendAprsConfig(cfg)
                    delay(500)
                    api.startAprs(cfg)
                }
                aprsActive.value = true
                startAprsLocationUpdates()
                startAprsHeartbeat()
            } else {
                withContext(Dispatchers.IO) { api.stopAprs() }
                aprsActive.value = false
                stopAprsLocationUpdates()
                stopAprsHeartbeat()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startAprsLocationUpdates() {
        if (aprsUseGPS.value != true) return
        val ctx = getApplication<Application>()
        val hasFine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        locationManager = lm
        locationListener = LocationListener { loc ->
            aprsLat.postValue(loc.latitude.toFloat())
            aprsLon.postValue(loc.longitude.toFloat())
        }
        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        lm.requestLocationUpdates(provider, 30000L, 10f, locationListener!!, Looper.getMainLooper())
        lm.getLastKnownLocation(provider)?.let {
            aprsLat.postValue(it.latitude.toFloat())
            aprsLon.postValue(it.longitude.toFloat())
        }
    }

    fun stopAprsLocationUpdates() {
        locationListener?.let { locationManager?.removeUpdates(it) }
        locationListener = null
        locationManager = null
    }

    private fun startAprsHeartbeat() {
        aprsHeartbeatJob?.cancel()
        aprsHeartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(10000)
                if (aprsUseGPS.value == true) {
                    val lat = aprsLat.value ?: 0f
                    val lon = aprsLon.value ?: 0f
                    withContext(Dispatchers.IO) {
                        api.sendGps(lat, lon)
                        api.sendAprsHeartbeat()
                    }
                } else {
                    withContext(Dispatchers.IO) { api.sendAprsHeartbeat() }
                }
            }
        }
    }

    private fun stopAprsHeartbeat() {
        aprsHeartbeatJob?.cancel()
        aprsHeartbeatJob = null
    }

    suspend fun saveAprsConfig(cfg: AprsConfig): Boolean {
        val ok = withContext(Dispatchers.IO) { api.sendAprsConfig(cfg) }
        if (ok) {
            aprsEnabled.value = cfg.callsign.isNotEmpty()
            aprsCallsign.value = cfg.callsign
            aprsSSID.value = cfg.ssid
            aprsPath.value = cfg.path
            aprsIntervalSec.value = cfg.interval
            aprsTxFreq.value = cfg.freq
            aprsBaud.value = cfg.baud
            aprsLat.value = cfg.manualLat
            aprsLon.value = cfg.manualLon
            aprsSymbol.value = cfg.symbol
            aprsDestination.value = cfg.destination
            aprsSoundDevice.value = cfg.soundDevice

            prefs.aprsEnabled = aprsEnabled.value ?: false
            prefs.aprsCallsign = cfg.callsign
            prefs.aprsSSID = cfg.ssid
            prefs.aprsPath = cfg.path
            prefs.aprsIntervalSec = cfg.interval
            prefs.aprsTxFreq = cfg.freq
            prefs.aprsBaud = cfg.baud
            prefs.aprsUseGPS = aprsUseGPS.value ?: false
            prefs.aprsLat = cfg.manualLat
            prefs.aprsLon = cfg.manualLon
            prefs.aprsSymbol = cfg.symbol
            prefs.aprsDestination = cfg.destination
            prefs.aprsSoundDevice = cfg.soundDevice
        }
        return ok
    }

    fun buildAprsConfig(): AprsConfig {
        val rig = rigList.value?.getOrNull(selectedRigIndex.value ?: 0)
        val cat = catList.value?.getOrElse(selectedCatIndex.value ?: 0) { "" } ?: ""
        return AprsConfig(
            callsign = aprsCallsign.value ?: "",
            ssid = aprsSSID.value ?: 0,
            path = aprsPath.value ?: "WIDE1-1",
            interval = aprsIntervalSec.value ?: 60,
            freq = aprsTxFreq.value ?: 144.660f,
            baud = aprsBaud.value ?: 1200,
            useGps = aprsUseGPS.value ?: false,
            manualLat = aprsLat.value ?: 0f,
            manualLon = aprsLon.value ?: 0f,
            symbol = aprsSymbol.value ?: ">",
            destination = aprsDestination.value ?: "APRS00",
            soundDevice = aprsSoundDevice.value ?: "",
            rigId = rig?.id?.toString() ?: "",
            catDevice = cat
        )
    }

    fun setCustomCatDevice(device: String) {
        prefs.savedCat = device
        val current = catList.value?.toMutableList() ?: mutableListOf()
        val noneIdx = current.indexOf("None")
        if (noneIdx >= 0) {
            current[noneIdx] = device
        } else {
            current.add(0, device)
        }
        catList.value = current
        selectedCatIndex.value = current.indexOf(device).coerceAtLeast(0)
    }

    fun disconnectFromRig() {
        externalPttActive = false
        serverWatchdogJob?.cancel()
        serverWatchdogJob = null
        pttOffJob?.cancel()
        pttOffJob = null
        stopStatusPolling()
        stopAudio()
        audioTx.stop()
        stopAprsHeartbeat()
        stopAprsLocationUpdates()
        stopPttHeartbeat()
        isConnectedToRig.value = false
        txEnabled.value = false
        spkEnabled.value = false
        aprsActive.value = false
    }

    override fun onCleared() {
        super.onCleared()
        disconnectFromRig()
        udpPtt.close()
    }
}
