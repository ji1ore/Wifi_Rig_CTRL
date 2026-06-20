package com.ji1ore.wifi_rig_ctrl.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ji1ore.wifi_rig_ctrl.data.*
import kotlinx.coroutines.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.sin
import kotlin.math.PI

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = AppPrefs(app)
    val api = RigApiService(prefs.hostName, prefs.apiPort, prefs.apiKey)
    val audio = AudioStreamService()
    val audioTx = AudioTxService(api.client).also { it.gainMultiplier = prefs.micGain }
    val udpPtt = UdpPttService()
    val cwUsb = CwUsbService(app).also { it.sidetoneEnabled = prefs.cwSidetoneEnabled }

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
    val selectedTxSamplingIndex = MutableLiveData(prefs.savedTxSamplingIndex)
    val selectedTimeoutIndex = MutableLiveData(prefs.savedTimeoutIndex)
    val selectedAudioDevice = MutableLiveData(prefs.alsaDevice)
    val selectedAudioDeviceFt8 = MutableLiveData(prefs.alsaDeviceFt8)

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
    val sharedMicGain = MutableLiveData(prefs.micGain)
    val sharedBkIn = MutableLiveData(false)

    // ノイズリダクション (0=OFF, 1=Light, 2=Medium, 3=Strong)
    val noiseReductionLevel = MutableLiveData(prefs.nrLevel)

    fun cycleNoiseReduction() {
        val next = ((noiseReductionLevel.value ?: 0) + 1) % 6
        noiseReductionLevel.value = next
        prefs.nrLevel = next
        viewModelScope.launch(Dispatchers.IO) {
            api.setNrLevel(next)
            api.setNrLevelOnPort(prefs.audioPort, next)
        }
    }

    fun setNoiseReduction(level: Int) {
        noiseReductionLevel.value = level
        prefs.nrLevel = level
        viewModelScope.launch(Dispatchers.IO) {
            api.setNrLevel(level)
            api.setNrLevelOnPort(prefs.audioPort, level)
        }
    }

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
    val aprsComment = MutableLiveData(prefs.aprsComment)
    val aprsDestination = MutableLiveData(prefs.aprsDestination)
    val aprsSoundDevice = MutableLiveData(prefs.aprsSoundDevice)
    val aprsTxInProgress = MutableLiveData(false)

    // CW USB
    val cwUsbConnected = MutableLiveData(false)
    val cwUsbEnabled = MutableLiveData(false)
    val cwSidetoneEnabled = MutableLiveData(prefs.cwSidetoneEnabled)
    val cwDelayMs = MutableLiveData(prefs.cwDelayMs)
    val cwFmDelayMs = MutableLiveData(prefs.cwFmDelayMs)
    val cwServerConnected = MutableLiveData(false)
    val cwServerSynced = MutableLiveData(false)
    val cwMeasuredLatencyMs = MutableLiveData(0)  // max_late_ms measured by Pi (= clock diff + OWD - buffer)
    private var cwUsbConnecting = false  // flag to prevent double connection (main thread only)
    @Volatile var piClockOffsetMs: Long = 0L  // (Pi Unix ms) - (Android Unix ms)
    @Volatile var ft8FragmentActive = false      // FT8 WebView is visible (suppress SPK watchdog)
    @Volatile private var cwFmTxActive = false  // FM CW TX active flag (prevents false external PTT detection)
    @Volatile private var cwTextTxActive = false // CW text TX active flag (prevents false external PTT detection)
    @Volatile private var aprsTxing = false    // APRS TX active flag (prevents false external PTT detection, manages SPK)
    @Volatile private var aprsStoppedAt = 0L  // APRS stop time: silence SPK errors for 120 seconds after stop
    private var lastAudioWatchdogMs = 0L      // last SPK watchdog start time
    private var cwFmTailJob: Job? = null         // VOX tail wait job

    // CW text send (Hamlib)
    val cwWpm = MutableLiveData(prefs.cwWpm)
    val cwPaddleSwap = MutableLiveData(prefs.cwPaddleSwap)
    val cwPttPoll = MutableLiveData(prefs.cwPttPoll)

    fun updateCwPttPoll(enabled: Boolean) {
        prefs.cwPttPoll = enabled
        cwPttPoll.value = enabled
    }
    val cwTxBusy = MutableLiveData(false)
    private var cwTextJob: Job? = null

    val cwCqRepeating = MutableLiveData(false)
    val cwCqRepeatStatus = MutableLiveData("")
    private var cwCqRepeatJob: Job? = null

    fun startCqRepeat(message: String, wpm: Int, intervalSec: Int, loopCount: Int) {
        cwCqRepeatJob?.cancel()
        cwCqRepeating.value = true
        cwCqRepeatJob = viewModelScope.launch {
            var sent = 0
            val countStr = if (loopCount == 0) "∞" else "$loopCount"
            while (isActive && (loopCount == 0 || sent < loopCount)) {
                sent++
                cwCqRepeatStatus.postValue("Sending $sent/$countStr")
                sendCwText(message, wpm)
                delay(400)  // brief settle before busy check
                while (isActive && cwTxBusy.value == true) delay(200)
                if (loopCount > 0 && sent >= loopCount) break
                // Countdown interval
                val endMs = System.currentTimeMillis() + intervalSec * 1000L
                while (isActive && System.currentTimeMillis() < endMs) {
                    val rem = ((endMs - System.currentTimeMillis()) / 1000).toInt() + 1
                    cwCqRepeatStatus.postValue("$sent/$countStr — next in ${rem}s")
                    delay(300)
                }
            }
            cwCqRepeating.postValue(false)
            cwCqRepeatStatus.postValue("")
            cwCqRepeatJob = null
        }
    }

    fun stopCqRepeat() {
        cwCqRepeatJob?.cancel()
        cwCqRepeatJob = null
        cwCqRepeating.value = false
        cwCqRepeatStatus.value = ""
        stopCwText()
    }

    fun updateCwWpm(wpm: Int) {
        prefs.cwWpm = wpm
        cwWpm.value = wpm
        cwUsb.keeyerWpm = wpm
        if (cwUsb.isConnected) viewModelScope.launch(Dispatchers.IO) { cwUsb.sendWpmToM5(wpm) }
    }

    fun updatePaddleSwap(swapped: Boolean) {
        prefs.cwPaddleSwap = swapped
        cwPaddleSwap.value = swapped
        if (cwUsb.isConnected) cwUsb.sendPaddleSwapToM5(swapped)
    }

    // CW Decoder
    val cwDecoding = MutableLiveData(false)
    val cwTxText = MutableLiveData<String>("")
    val cwRxTexts = Array(CwDecoder.N_CHAN) { MutableLiveData<String>("") }
    val cwRxFreqLabels = Array(CwDecoder.N_CHAN) { i -> MutableLiveData(if (i == 0) "RX:" else "---") }
    val cwMultiRx = MutableLiveData(false)
    @Volatile private var cwDecodingActive = false
    private var cwRxDecoder: CwDecoder? = null
    private val cwTxKeyDecoder = CwKeyDecoder()
    private val cwTxBuffer = StringBuilder()
    private val cwRxBuffers = Array(CwDecoder.N_CHAN) { StringBuilder() }
    private val cwDecodedLock = Any()
    @Volatile private var cwTxTimeoutJob: Job? = null
    private val CW_MAX_CHARS = 200
    // Post timeout to main thread via Handler (more reliable than coroutine launch from USB thread)
    private val cwTxTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cwTxTimeoutRunnable = Runnable { cwTxKeyDecoder.checkTimeout() }

    // Status
    val errorMessage = MutableLiveData<String?>(null)
    val audioError = MutableLiveData<String?>(null)
    val isConnectedToRig = MutableLiveData(false)
    val isDemoMode = MutableLiveData(false)
    private var demoTick = 0

    // User interaction timestamps (protect UI updates from overwriting user input)
    private var lastUserFreqChange = 0L
    private var lastUserModeChange = 0L
    private var lastUserPowerChange = 0L
    private var lastUserSQLChange = 0L
    private var lastUserVolumeChange = 0L
    private var lastUserWidthChange = 0L

    // Track HTTP completion of Hamlib PTT ON command: OFF waits by join()ing this job before sending
    private var pttOnJob: Job? = null
    // Time when Android PTT OFF was pressed: cooldown for external PTT false detection
    private var lastAndroidPttOffTime = 0L
    // Time when connectRig() completed: suppress external PTT detection during rigctld init
    private var lastConnectTime = 0L

    private var statusPollingJob: Job? = null

    companion object {
        const val CW_UDP_PORT = 8889
    }
    private var cwServerPollingJob: Job? = null
    private var aprsHeartbeatJob: Job? = null
    private var pttHeartbeatJob: Job? = null
    private var audioTxStartJob: Job? = null
    private var pttOffJob: Job? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var externalPttActive = false  // M5物理ボタン等の外部PTT検出フラグ
    private var serverWatchdogJob: Job? = null  // WiFi PTT時のサーバーwatchdog更新（UDP heartbeatと独立）
    private var cwFmPttJob: Job? = null  // FMモードCWキー時のPTT制御

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
        selectedTxSamplingIndex.value = profile.savedTxSamplingIndex
        selectedTimeoutIndex.value = profile.savedTimeoutIndex
        useWifiPTT.value = profile.useWifiPTT
        pttHost.value = profile.pttHost
        pttPort.value = profile.pttPort
        selectedPttDevice.value = profile.savedPttDevice
        selectedPttType.value = profile.savedPttType
        cwDelayMs.value = profile.cwDelayMs
        cwFmDelayMs.value = profile.cwFmDelayMs
        cwMeasuredLatencyMs.value = 0
        selectedAudioDevice.value = profile.alsaDevice
        selectedAudioDeviceFt8.value = profile.alsaDeviceFt8

        api.updateConnection(profile.hostName, profile.apiPort, profile.apiKey)
    }

    fun saveCurrentToProfile() {
        val profiles = prefs.getProfiles()
        val idx = prefs.activeProfileIndex.coerceIn(0, maxOf(0, profiles.size - 1))
        val oldName = profiles.getOrElse(idx) { ProfileConfig() }.name
        val updated = ProfileConfig(
            name = oldName,
            hostName = hostName.value ?: "",
            apiPort = apiPort.value ?: 8000,
            audioPort = audioPort.value ?: 50000,
            useMDNS = useMDNS.value ?: false,
            apiKey = apiKey.value ?: "",
            savedRigId = prefs.savedRigId,
            savedCat = prefs.savedCat,
            savedPttDevice = selectedPttDevice.value ?: "NONE",
            savedPttType = selectedPttType.value ?: "RTS",
            savedBaudIndex = selectedBaudIndex.value ?: 2,
            savedSamplingIndex = selectedSamplingIndex.value ?: 1,
            savedTxSamplingIndex = selectedTxSamplingIndex.value ?: 1,
            savedTimeoutIndex = selectedTimeoutIndex.value ?: 2,
            useWifiPTT = useWifiPTT.value ?: false,
            pttHost = pttHost.value ?: "",
            pttPort = pttPort.value ?: 8888,
            cwDelayMs = prefs.cwDelayMs,
            cwFmDelayMs = prefs.cwFmDelayMs,
            alsaDevice = selectedAudioDevice.value ?: "",
            alsaDeviceFt8 = selectedAudioDeviceFt8.value ?: ""
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
        val rawHost = hostName.value ?: return@withContext "No host"
        if (rawHost.trim().lowercase() == "demo") {
            withContext(Dispatchers.Main) {
                isDemoMode.value = true
                rigList.value = listOf(RigInfo(3073, "IC-7300"))
                catList.value = listOf("None")
                soundDeviceList.value = emptyList()
                selectedRigIndex.value = 0
                selectedCatIndex.value = 0
            }
            return@withContext null
        }
        try {
            val port = apiPort.value ?: 8000
            val key = apiKey.value ?: ""
            val host = if (useMDNS.value == true) {
                val mdnsName = if (!rawHost.endsWith(".local")) "$rawHost.local" else rawHost
                try {
                    // Resolve to IP address (use .local hostname as-is if resolution fails)
                    java.net.InetAddress.getByName(mdnsName).hostAddress ?: mdnsName
                } catch (e: Exception) {
                    mdnsName  // attempt connection with hostname even on resolution failure
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
            val msg = e.message ?: ""
            when {
                msg.contains("Connection refused", ignoreCase = true) ->
                    "FastAPI service not running on Pi.\nRun create_api.sh to reinstall packages."
                msg.contains("timeout", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) ->
                    "Pi unreachable. Check WiFi/network. (${msg})"
                else -> "Connection failed: $msg"
            }
        }
    }

    suspend fun skipConnect(): String? {
        if (prefs.hostName.isEmpty()) return "No host configured"
        if (prefs.savedRigId < 0) return "No rig settings saved"
        val err = connectToRasPi()
        if (err != null) return err
        val result = connectRig()
        if (result == null) isConnectedToRig.value = true
        return result
    }

    suspend fun connectRig(): String? = withContext(Dispatchers.IO) {
        if (isDemoMode.value == true) {
            withContext(Dispatchers.Main) {
                sharedModel.value = "IC-7300 [DEMO]"
                sharedFreq.value = 7_074_000L
                sharedMode.value = "USB"
                sharedPower.value = 1.0f
                sharedWidth.value = 2400
                sharedSQL.value = 0f
                supportedModes.value = listOf("USB", "LSB", "CW", "CWR", "AM", "FM", "RTTY", "RTTYR", "PKTUSB", "PKTLSB")
            }
            return@withContext null
        }
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
                val pt = pttPort.value ?: 8888
                // M5Atom time sync: send currentTimeMillis() as base time
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
            prefs.savedTxSamplingIndex = selectedTxSamplingIndex.value ?: 1
            prefs.savedTimeoutIndex = selectedTimeoutIndex.value ?: 2
            prefs.useWifiPTT = useWifiPTT.value ?: false
            prefs.pttHost = pttHost.value ?: ""
            prefs.pttPort = pttPort.value ?: 8888
            val alsaDev = selectedAudioDevice.value ?: ""
            prefs.alsaDevice = alsaDev
            val alsaFt8Dev = selectedAudioDeviceFt8.value ?: ""
            prefs.alsaDeviceFt8 = alsaFt8Dev

            saveCurrentToProfile()

            api.openRig(rig.id, cat, baud, ptt, pttType)
            if (alsaDev.isNotEmpty()) api.setAudioDevice(alsaDev)
            if (alsaFt8Dev.isNotEmpty()) api.setAudioDeviceFt8(alsaFt8Dev)
            delay(1000)

            repeat(50) {
                val st = api.getStatus()
                if (st != null) {
                    withContext(Dispatchers.Main) {
                        sharedModel.value = rig.name
                        lastConnectTime = System.currentTimeMillis()  // suppress external PTT detection during rigctld init
                    }
                    // Reset PTT/polling state from previous session
                    api.setPtt(false)
                    api.setPoll(true)
                    // NRレベルをAndroid保存値でサーバーへ同期（サーバー再起動後にNRが0に戻ってもAndroid側の設定を復元）
                    val savedNr = prefs.nrLevel
                    if (savedNr > 0) {
                        api.setNrLevel(savedNr)
                        api.setNrLevelOnPort(prefs.audioPort, savedNr)
                    }
                    // Pi clock sync: correct clock difference for CW timestamps
                    val offset = api.getPiClockOffsetMs()
                    if (offset != null) {
                        piClockOffsetMs = offset
                        android.util.Log.i("PiClock", "Pi clock offset: ${offset}ms")
                    } else {
                        android.util.Log.w("PiClock", "Pi clock sync failed")
                    }
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

    private fun updateDemoStatus() {
        demoTick++
        val pattern = floatArrayOf(2f, 3f, 5f, 7f, 9f, 11f, 10f, 8f, 6f, 4f, 3f, 5f, 8f, 11f, 13f, 10f, 7f, 5f, 3f, 2f)
        sharedSignal.value = pattern[demoTick % pattern.size]
        sharedTx.value = txEnabled.value == true
    }

    private suspend fun updateStatus() {
        if (isDemoMode.value == true) { updateDemoStatus(); return }
        val st = withContext(Dispatchers.IO) { api.getStatus() } ?: return
        val now = System.currentTimeMillis()

        // Hamlib STRENGTH: dBm relative to S9=0dBm, 6dB per S-unit
        // S0 ≈ -54dBm, S1=-48, S2=-42, ..., S9=0, S9+6dB=+6, S9+36dB=+36
        var s = (st.signal + 54f) / 6f
        s = s.coerceIn(0f, 15f)

        if (now - lastUserFreqChange > 1000) sharedFreq.value = st.freq
        if (now - lastUserModeChange > 1000) {
            val newMode = st.mode
            if (newMode != sharedMode.value && newMode.isNotEmpty()) {
                sharedMode.value = newMode
                selectedStep.value = prefs.getModeStep(newMode)
                updateCwAudioStreamForMode(newMode)
            }
        }
        if (now - lastUserPowerChange > 1000) sharedPower.value = st.power
        if (now - lastUserSQLChange > 1000) sharedSQL.value = st.sql
        if (now - lastUserWidthChange > 1000) sharedWidth.value = st.width
        sharedBkIn.value = st.bkIn
        sharedSignal.value = s
        sharedTx.value = st.tx
        aprsTxInProgress.value = st.txInProgress

        // APRS TX state management: radio is TX and APRS is enabled → treat as APRS TX
        // Stop SPK on TX start, resume SPK on TX end (also blocks false external PTT detection)
        val nowAprsTxing = aprsActive.value == true && st.tx
        if (nowAprsTxing && !aprsTxing) {
            aprsTxing = true
            audio.stop()  // APRS TX start: stop RX audio
        } else if (!nowAprsTxing && aprsTxing) {
            aprsTxing = false
            if (spkEnabled.value == true && txEnabled.value != true) {
                startAudio()  // APRS TX end: resume RX audio
            }
        }

        // SPK watchdog: auto-reconnect every 15 seconds if SPK is ON but audio stopped or zombie
        // (zombie = AudioTrack.PLAYSTATE_PLAYING but Pi stopped sending data)
        if (spkEnabled.value == true && !audio.isDataFlowing && !audio.isStreamActive
                && txEnabled.value != true && !aprsTxing && !ft8FragmentActive
                && now - lastAudioWatchdogMs > 15_000L) {
            startAudio()
        }

        // External PTT detection: follow audio TX if radiocache becomes TX=true via M5 physical button etc.
        // Skip if pttOffJob/audioTxStartJob is running or during cooldown right after Android PTT OFF
        // Block false external PTT detection during cwFmTxActive (CW FM TX in progress)
        // Skip when aprsActive to avoid falsely detecting APRS TX as external PTT
        val pttOffCooldown = System.currentTimeMillis() - lastAndroidPttOffTime < 5000
        val connectCooldown = System.currentTimeMillis() - lastConnectTime < 8000
        // CW USBキーヤーがCWモードで有効な場合、無線機のTX状態はCWキーによるもの（外部PTTではない）
        val cwUsbCwActive = cwUsbEnabled.value == true && (sharedMode.value ?: "").contains("CW", ignoreCase = true)
        if (pttOffJob == null && audioTxStartJob == null && !pttOffCooldown && !connectCooldown && !cwFmTxActive && !cwTextTxActive && !cwUsbCwActive && aprsActive.value != true) {
            if (st.tx && txEnabled.value != true) {
                // External device started TX → start TX audio on Android too
                externalPttActive = true
                txEnabled.value = true
                audioTx.stop()
                viewModelScope.launch(Dispatchers.IO) { api.pttHeartbeat() }  // immediate watchdog reset (no rigctl)
                audioTxStartJob = viewModelScope.launch {
                    delay(300)
                    audioTx.start(api.getAudioTxUrl(txAudioRate()), apiKey.value ?: "")
                }
                startPttHeartbeat()  // 継続的なwatchdog更新
            } else if (!st.tx && txEnabled.value == true && externalPttActive) {
                // External device released PTT → stop TX on Android too
                externalPttActive = false
                txEnabled.value = false
                audioTx.stop()
                stopPttHeartbeat()
                viewModelScope.launch(Dispatchers.IO) { api.setPtt(false) }  // reset server last_ptt_state
                if (spkEnabled.value == true) startAudio()
            }
        }
    }

    fun fetchModeList() = viewModelScope.launch {
        if (isDemoMode.value == true) {
            supportedModes.value = listOf("USB", "LSB", "CW", "CWR", "AM", "FM", "RTTY", "RTTYR", "PKTUSB", "PKTLSB")
            return@launch
        }
        val modes = withContext(Dispatchers.IO) { api.getCaps() }
        supportedModes.value = modes
    }

    fun sendFreq(freqHz: Long) {
        lastUserFreqChange = System.currentTimeMillis()
        sharedFreq.value = freqHz
        if (isDemoMode.value != true) viewModelScope.launch(Dispatchers.IO) { api.setFreq(freqHz) }
    }

    fun sendMode(mode: String, width: Int) {
        lastUserModeChange = System.currentTimeMillis()
        sharedMode.value = mode
        updateCwAudioStreamForMode(mode)
        if (isDemoMode.value != true) viewModelScope.launch(Dispatchers.IO) { api.setMode(mode, width) }
    }

    private fun updateCwAudioStreamForMode(mode: String) {
        if (cwUsbEnabled.value != true) return
        val isCwMode = mode.contains("CW", ignoreCase = true)
        if (isCwMode) cwUsb.stopCwAudioStream()
        else cwUsb.startCwAudioStream(api, apiKey.value ?: "")
    }

    fun toggleCwSidetone() {
        val next = !(cwSidetoneEnabled.value ?: true)
        cwSidetoneEnabled.value = next
        cwUsb.sidetoneEnabled = next
        prefs.cwSidetoneEnabled = next
    }

    fun sendPower(value: Float) {
        lastUserPowerChange = System.currentTimeMillis()
        sharedPower.value = value
        if (isDemoMode.value != true) viewModelScope.launch(Dispatchers.IO) { api.setPower(value) }
    }

    fun sendBkIn(on: Boolean) {
        sharedBkIn.value = on
        if (isDemoMode.value != true) viewModelScope.launch(Dispatchers.IO) { api.setBkIn(on) }
    }

    fun sendSQL(value: Float) {
        lastUserSQLChange = System.currentTimeMillis()
        sharedSQL.value = value
        if (isDemoMode.value != true) viewModelScope.launch(Dispatchers.IO) { api.setLevel("SQL", value) }
    }

    fun sendVolume(value: Float) {
        lastUserVolumeChange = System.currentTimeMillis()
        sharedVolume.value = value
        prefs.volume = value
        audio.setVolume(value)
        if (isDemoMode.value != true) viewModelScope.launch(Dispatchers.IO) { api.setLevel("VOL", value) }
    }

    fun setPttEnabled(on: Boolean) {
        if (isDemoMode.value == true) {
            externalPttActive = false
            txEnabled.value = on
            return
        }
        externalPttActive = false  // Android takes over control
        txEnabled.value = on
        if (on) {
            // Stop polling: wait for in-flight Pi polling HTTP to complete, clear CAT bus
            stopStatusPolling()
            // Stop CW audio stream during PTT: prevents contention on audio_tx endpoint
            cwUsb.stopCwAudioStream()
            // Cancel pending PTT OFF and force close
            val jobToCancel = pttOffJob
            if (jobToCancel != null) {
                android.util.Log.w("PTT", "cancelling pttOffJob active=${jobToCancel.isActive}")
                jobToCancel.cancel()
            }
            pttOffJob = null
            audioTx.stop()
            audio.stop()
            audioTxStartJob?.cancel()
            audioTxStartJob = null  // started inside pttOnJob after setPtt succeeds
            pttOnJob?.cancel()
            pttOnJob = viewModelScope.launch(Dispatchers.IO) {
                if (useWifiPTT.value == true) {
                    val h = pttHost.value
                    val pt = pttPort.value ?: 8888
                    android.util.Log.d("PTT", "WiFi PTT ON → host=$h port=$pt")
                    if (h.isNullOrEmpty()) {
                        android.util.Log.e("PTT", "pttHost is empty, skipping UDP")
                        return@launch
                    }
                    udpPtt.sendPtt(h, pt, true)
                    api.pttHeartbeat()
                    api.setPoll(false)
                    withContext(Dispatchers.Main) {
                        startPttHeartbeat()
                        audioTxStartJob = viewModelScope.launch {
                            delay(300)
                            audioTx.start(api.getAudioTxUrl(txAudioRate()), apiKey.value ?: "")
                        }
                    }
                } else {
                    android.util.Log.d("PTT", "Hamlib PTT ON")
                    // Send setPoll(false) first: stop Pi background polling
                    api.setPoll(false)
                    // CAT response completion buffer: wait until in-flight poll_rig() command clears the rigctld queue
                    delay(100L)
                    if (txEnabled.value == true) {
                        var sent = false
                        for (attempt in 1..5) {
                            if (api.setPtt(true)) { sent = true; break }
                            if (!isActive || txEnabled.value != true) break
                            delay(200)
                        }
                        // Start heartbeat and mic audio only after setPtt succeeds
                        if (sent && txEnabled.value == true) {
                            withContext(Dispatchers.Main) {
                                startPttHeartbeat()
                                audioTxStartJob = viewModelScope.launch {
                                    audioTx.start(api.getAudioTxUrl(txAudioRate()), apiKey.value ?: "")
                                }
                            }
                        }
                    }
                }
            }
            // startPttHeartbeat() and audioTxStartJob are started inside pttOnJob after setPtt(true) succeeds
        } else {
            lastAndroidPttOffTime = System.currentTimeMillis()
            audioTxStartJob?.cancel()
            audioTxStartJob = null
            audioTx.gracefulStop()
            stopPttHeartbeat()
            pttOffJob = viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    audioTx.awaitDrain(500)
                    // drain margin for aplay -B 100000 (100ms) buffer
                    delay(100)
                    // Stop audio before PTT OFF: prevents aplay echo through RX speaker
                    audioTx.stop()
                    // Wait for ON command HTTP completion before sending OFF
                    pttOnJob?.join()
                    if (txEnabled.value != true) {
                        if (useWifiPTT.value == true) {
                            val h = pttHost.value
                            val pt = pttPort.value ?: 8888
                            if (!h.isNullOrEmpty()) udpPtt.sendPtt(h, pt, false)
                            api.setPtt(false)
                            api.setPoll(true)
                        } else {
                            if (!api.setPtt(false)) {
                                delay(200)
                                api.setPtt(false)
                            }
                            api.setPoll(true)
                        }
                    }
                }
                // Abort here if cancelled by re-pressing PTT ON
                android.util.Log.w("PTT", "pttOffJob after withContext: isActive=$isActive txEnabled=${txEnabled.value}")
                if (!isActive) return@launch
                // Start SPK after PTT OFF confirmed: aplay has ended and ffmpeg can start immediately
                if (txEnabled.value != true) {
                    if (spkEnabled.value == true) startAudio()
                    startStatusPolling()
                    // Restore CW audio stream after PTT (non-CW modes only)
                    updateCwAudioStreamForMode(sharedMode.value ?: "")
                }
                pttOffJob = null
            }
        }
    }

    fun suppressExternalPttDetection() {
        lastAndroidPttOffTime = System.currentTimeMillis()
    }

    /** Fragment のライフサイクルに依存せず PTT OFF を Pi に送信する（FT8 画面離脱時用）*/
    fun releasePttBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            try { api.setPtt(false) } catch (_: Exception) {}
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
                        // WiFi PTT mode: send UDP only (fast, non-blocking)
                        val h = pttHost.value
                        val pt = pttPort.value ?: 8888
                        if (!h.isNullOrEmpty()) udpPtt.sendPtt(h, pt, true)
                    } else {
                        // Hamlib mode: update last_heartbeat only
                        // Using setPtt(true) risks a race where in-flight response arrives after setPtt(false),
                        // restarting TX from last_ptt_state=0
                        api.pttHeartbeat()
                    }
                    Unit
                }
            }
        }
        // WiFi PTT mode: run server watchdog update in a separate job
        // Decouple HTTP from UDP heartbeat to eliminate delay impact
        if (useWifiPTT.value == true) {
            serverWatchdogJob = viewModelScope.launch {
                while (isActive) {
                    delay(2000)
                    withContext(Dispatchers.IO) { api.pttHeartbeat() }  // reset watchdog without rigctl
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

    fun txAudioRate(): Int {
        val idx = selectedTxSamplingIndex.value ?: 1
        return SAMPLING_RATES.getOrElse(idx) { 8000 }.let { if (it == 0) 8000 else it }
    }

    fun startAudio() {
        if (isDemoMode.value == true) return
        if (hostName.value.isNullOrEmpty()) return
        if (txEnabled.value == true) return  // don't start SPK during TX
        if (aprsTxing) return                // don't start SPK during APRS TX
        val aPort = audioPort.value ?: 50000
        val sIdx = selectedSamplingIndex.value ?: 1
        val rate = SAMPLING_RATES.getOrElse(sIdx) { 0 }
        if (rate == 0) return
        lastAudioWatchdogMs = System.currentTimeMillis()
        // onError checks APRS state at error time (not at startAudio() call time):
        // Silence errors for 120 seconds after APRS stop to wait for Pi Zero audio service restart
        audio.start(
            url = api.getAudioStreamUrl(aPort, rate),
            vol = sharedVolume.value ?: 0.5f,
            apiKey = apiKey.value ?: "",
            onError = { msg ->
                val inGrace = aprsStoppedAt > 0 && System.currentTimeMillis() - aprsStoppedAt < 120_000L
                if (aprsActive.value != true && !inGrace) {
                    audioError.postValue(msg)
                }
            }
        )
    }

    fun stopAudio() = audio.stop()

    fun toggleAprs() {
        if (isDemoMode.value == true) return
        viewModelScope.launch {
            if (aprsActive.value != true) {
                if (cwTxBusy.value == true) stopCwText()
                fetchLatestGpsForAprs()
                val cfg = buildAprsConfig()
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        api.setPoll(false)
                        api.sendAprsConfig(cfg)
                        if (cfg.useGps) {
                            val lat = aprsLat.value ?: 0f
                            val lon = aprsLon.value ?: 0f
                            if (lat != 0f || lon != 0f) api.sendGps(lat, lon)
                        }
                        delay(1500)  // wait for direwolf to restart
                        api.startAprs(cfg)
                    }
                }.isSuccess
                if (ok) {
                    aprsActive.value = true
                    startAprsLocationUpdates()
                    startAprsHeartbeat()
                    android.widget.Toast.makeText(getApplication(), "APRS started", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // API failed: resume polling and restore SPK
                    runCatching { withContext(Dispatchers.IO) { api.setPoll(true) } }
                    if (spkEnabled.value == true && txEnabled.value != true) startAudio()
                    android.widget.Toast.makeText(getApplication(), "APRS start failed (Pi unreachable?)", android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                runCatching {
                    withContext(Dispatchers.IO) {
                        api.stopAprs()
                        api.setPoll(true)
                    }
                }
                aprsTxing = false
                aprsStoppedAt = System.currentTimeMillis()
                aprsActive.value = false
                stopAprsLocationUpdates()
                stopAprsHeartbeat()
                if (spkEnabled.value == true && txEnabled.value != true) startAudio()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLatestGpsForAprs() {
        if (aprsUseGPS.value != true) return
        val ctx = getApplication<Application>()
        val hasFine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        // Get latest known location from both GPS and Network providers
        val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.isProviderEnabled(it) }
            .firstNotNullOfOrNull { lm.getLastKnownLocation(it) } ?: return
        aprsLat.value = loc.latitude.toFloat()
        aprsLon.value = loc.longitude.toFloat()
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
        val listener = LocationListener { loc ->
            val lat = loc.latitude.toFloat()
            val lon = loc.longitude.toFloat()
            aprsLat.postValue(lat)
            aprsLon.postValue(lon)
            viewModelScope.launch(Dispatchers.IO) { api.sendGps(lat, lon) }
        }
        locationListener = listener
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.isProviderEnabled(it) }
        // Register both GPS and Network providers (30 second interval)
        for (p in providers) {
            lm.requestLocationUpdates(p, 30000L, 10f, listener, Looper.getMainLooper())
        }
        // Immediately initialize with last known location
        providers.firstNotNullOfOrNull { lm.getLastKnownLocation(it) }?.let {
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
        var gpsTick = 0
        aprsHeartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(10000)  // watchdog update every 10 seconds
                gpsTick++
                val sendGpsNow = aprsUseGPS.value == true && gpsTick % 3 == 0  // GPS send every 30 seconds
                if (aprsUseGPS.value == true) {
                    val lat = aprsLat.value ?: 0f
                    val lon = aprsLon.value ?: 0f
                    withContext(Dispatchers.IO) {
                        if (sendGpsNow) api.sendGps(lat, lon)
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
            aprsComment.value = cfg.comment
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
            prefs.aprsComment = cfg.comment
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
            comment = aprsComment.value ?: "",
            destination = aprsDestination.value ?: "APRS00",
            soundDevice = aprsSoundDevice.value ?: "",
            rigId = rig?.id?.toString() ?: "",
            catDevice = cat
        )
    }

    suspend fun getSetupLog(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        api.getSetupLog()
    }

    suspend fun updatePiSoftware(): String? = withContext(Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            val apiBytes = app.assets.open("api.py").readBytes()
            val apiErr = api.sendApiUpdate(apiBytes)
            if (apiErr != null) return@withContext apiErr
            // api.py sent successfully. Wait for Pi restart then send create_api.sh
            // Pi Zero restart can take 20-30s; retry up to 8 times x 5s = 40s
            val scriptBytes = app.assets.open("create_api.sh").readBytes()
            var setupErr: String? = null
            for (attempt in 1..8) {
                Thread.sleep(5000)
                setupErr = api.sendSetupScript(scriptBytes)
                if (setupErr == null) break
            }
            // create_api.sh embeds an older api.py and overwrites the one we just sent.
            // Poll setup_log until create_api.sh finishes ("=== 完了 ==="), then re-send api.py.
            if (setupErr == null) {
                val deadline = System.currentTimeMillis() + 120_000L
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(5000)
                    val (_, log) = try { api.getSetupLog() } catch (e: Exception) { false to "" }
                    if (log.contains("=== 完了 ===")) break
                }
                // Re-send api.py with retry: systemd restart after create_api.sh takes a few seconds.
                var resendErr: String? = "not attempted"
                for (resend in 1..5) {
                    Thread.sleep(5000)
                    resendErr = api.sendApiUpdate(apiBytes)
                    if (resendErr == null) break
                }
                if (resendErr != null) return@withContext "setup OK / api.py resend failed: $resendErr"
            }
            setupErr?.let { "api.py OK / setup: $it" }
        } catch (e: Exception) { e.message }
    }

    suspend fun syncPiTime(): String = withContext(Dispatchers.IO) {
        // 同期前のPi時刻ずれを測定
        val preOffsetMs = try { api.getPiClockOffsetMs() } catch (_: Exception) { null }
        // Android側でも NTP に問い合わせて正確な時刻を取得する
        val ntpMs = com.ji1ore.wifi_rig_ctrl.data.queryNtpMs()
        val nowMs = ntpMs ?: System.currentTimeMillis()
        val androidSource = if (ntpMs != null) "NTP" else "SystemClock"
        val r = try { api.syncTime(nowMs) } catch (e: Exception) { return@withContext "同期失敗: ${e.message}" }
        if (!r.ok) return@withContext "同期失敗"
        val piSource = when {
            r.source.startsWith("ntp_chrony")      -> "NTP(chrony)"
            r.source.startsWith("ntp_ntpdate")     -> "NTP(ntpdate)"
            r.source.startsWith("ntp_timedatectl") -> "NTP(timedatectl)"
            r.source == "android"                  -> "Android[$androidSource]"
            else -> r.source
        }
        val preStr = preOffsetMs?.let {
            val s = if (it > 0) "+${it}" else "$it"
            " (同期前ずれ ${s}ms)"
        } ?: ""
        "同期完了[$piSource]$preStr"
    }

    suspend fun updateCwBridge(): String? = withContext(Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            val bytes = app.assets.open("cw_bridge.py").readBytes()
            api.sendCwBridgeUpdate(bytes)
        } catch (e: Exception) { e.message }
    }

    suspend fun updateWebFt8Server(): String? = withContext(Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            val bytes = app.assets.open("server_webft8.py").readBytes()
            api.sendWebFt8Update(bytes)
        } catch (e: Exception) { e.message }
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

    // ───────── CW UDP key send (dedicated send thread) ─────────
    // DNS/network waits on the USB read thread would block all sends after the first,
    // so a LinkedBlockingQueue + dedicated send thread frees the USB thread.

    @Volatile private var cwUdpSocket: java.net.DatagramSocket? = null
    @Volatile private var cwUdpAddr: java.net.InetAddress? = null
    private val cwSendQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(64)
    private var cwSendThread: Thread? = null
    private var cwNetworkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun startCwSendThread() {
        cwSendThread?.interrupt()
        cwSendThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val pkt = cwSendQueue.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                    doSendCwUdp(pkt)
                } catch (_: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
        registerCwNetworkCallback()
    }

    private fun stopCwSendThread() {
        unregisterCwNetworkCallback()
        cwSendThread?.interrupt()
        cwSendThread = null
        cwSendQueue.clear()
    }

    private fun resetCwUdpSocket() {
        val old = cwUdpSocket
        cwUdpSocket = null
        cwUdpAddr = null
        try { old?.close() } catch (_: Exception) {}
        android.util.Log.i("CwUdp", "UDP socket reset on network change")
    }

    private fun registerCwNetworkCallback() {
        val cm = getApplication<Application>()
            .getSystemService(android.net.ConnectivityManager::class.java) ?: return
        cwNetworkCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = resetCwUdpSocket()
            override fun onLost(network: android.net.Network) = resetCwUdpSocket()
        }
        try { cm.registerDefaultNetworkCallback(cb); cwNetworkCallback = cb } catch (_: Exception) {}
    }

    private fun unregisterCwNetworkCallback() {
        val cb = cwNetworkCallback ?: return
        try {
            getApplication<Application>()
                .getSystemService(android.net.ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        cwNetworkCallback = null
    }

    private fun doSendCwUdp(packet: ByteArray) {
        val host = prefs.hostName
        if (host.isEmpty()) return
        try {
            if (cwUdpSocket == null || cwUdpSocket!!.isClosed) {
                cwUdpSocket = java.net.DatagramSocket()
                cwUdpAddr = java.net.InetAddress.getByName(host)
            }
            val sock = cwUdpSocket ?: run { android.util.Log.w("CwRelay", "sock=null"); return }
            val addr = cwUdpAddr ?: run { android.util.Log.w("CwRelay", "addr=null"); return }
            android.util.Log.d("CwRelay", "UDP send key=${packet[0].toInt()} to ${addr.hostAddress}:$CW_UDP_PORT")
            sock.send(java.net.DatagramPacket(packet, packet.size, addr, CW_UDP_PORT))
        } catch (e: Exception) {
            android.util.Log.w("CwUdp", "send error: ${e.message}")
            cwUdpSocket = null
        }
    }

    // ───────── FM CW VOX session management ─────────
    // Called from USB key or PTT button CW tone mode (callable from USB thread)

    private fun cwFmKeyOn() {
        cwFmTailJob?.cancel()  // cancel tail wait (session continues)
        cwFmTailJob = null
        if (cwFmTxActive) {
            // Race guard: tail job may have stopped heartbeat before being cancelled
            if (pttHeartbeatJob?.isActive != true) startPttHeartbeat()
            return
        }
        cwFmTxActive = true  // volatile: visible from main thread
        txEnabled.postValue(true)
        cwFmPttJob?.cancel()
        cwFmPttJob = viewModelScope.launch(Dispatchers.IO) {
            audio.stop()  // stop SPK
            api.setPoll(false)
            delay(80L)
            api.setPtt(true)
            withContext(Dispatchers.Main) { startPttHeartbeat() }  // extend watchdog
        }
    }

    private fun cwFmKeyOff() {
        if (!cwFmTxActive) return
        cwFmTailJob?.cancel()
        cwFmTailJob = viewModelScope.launch {
            delay(500L)  // VOX tail
            stopPttHeartbeat()
            cwFmPttJob?.cancel()
            cwFmPttJob = null
            lastAndroidPttOffTime = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                delay(60)  // wait for last tone chunk to drain
                api.setPtt(false)
                api.setPoll(true)
            }
            cwFmTxActive = false
            txEnabled.value = false
            cwFmTailJob = null
            if (spkEnabled.value == true) startAudio()
        }
    }

    // ───────── CW USB relay ─────────

    fun connectCwUsb(device: android.hardware.usb.UsbDevice) {
        // Prevent double connection from onResume() scan and ACTION_USB_PERMISSION firing simultaneously
        if (cwUsb.isConnected || cwUsbConnecting) return
        cwUsbConnecting = true

        cwUsb.onConnectionStateChange = { connected ->
            cwUsbConnected.postValue(connected)
        }
        cwUsb.onWpmChange = { wpm ->
            // M5ボタンでWPMが変更された → Android側のWPM設定も同期
            prefs.cwWpm = wpm
            cwWpm.postValue(wpm)
        }
        cwUsb.onAudioStreamNeeded = {
            val mode = sharedMode.value ?: ""
            val pttActive = txEnabled.value == true
            if (!mode.contains("CW", ignoreCase = true) && cwUsbEnabled.value == true && !pttActive) {
                viewModelScope.launch(Dispatchers.Main) {
                    cwUsb.startCwAudioStream(api, apiKey.value ?: "")
                }
            }
        }
        cwUsb.onKeyStateChange = { isOn, rawPacket ->
            cwUsb.currentMode = sharedMode.value ?: ""
            val mode = cwUsb.currentMode
            val isCwMode = mode.contains("CW", ignoreCase = true)
            val isFmMode = mode.contains("FM", ignoreCase = true)
            if (cwUsbEnabled.value == true) {
                when {
                    isCwMode -> {
                        val pkt = rawPacket.copyOf()
                        if (prefs.cwDelayMs > 0) {
                            var existingMs = 0L
                            for (i in 0..7) existingMs = (existingMs shl 8) or (pkt[2 + i].toLong() and 0xFF)
                            val fireMs = if (existingMs in 2L..0xFFFFFFFFL) {
                                // M5 Client SYNC済み: Server-msタイムスタンプにdelayを加算（精度を維持）
                                existingMs + prefs.cwDelayMs.toLong()
                            } else {
                                // opTimeMs=0/1(即時) or 旧形式: AndroidタイムスタンプをVPN補正
                                System.currentTimeMillis() + piClockOffsetMs + prefs.cwDelayMs.toLong()
                            }
                            for (i in 0..7) pkt[2 + i] = ((fireMs ushr (56 - i * 8)) and 0xFF).toByte()
                        }
                        cwSendQueue.offer(pkt)
                    }
                    isFmMode -> {
                        if (isOn) cwFmKeyOn() else cwFmKeyOff()
                    }
                }
            }
            // TX decode: call directly on USB thread (CwKeyDecoder is @Synchronized)
            // Post timeout to main thread via Handler (more reliable than coroutine launch)
            if (cwDecodingActive) {
                cwTxKeyDecoder.keyEvent(isOn)
                cwTxTimeoutHandler.removeCallbacks(cwTxTimeoutRunnable)
                if (!isOn) {
                    val waitMs = (cwTxKeyDecoder.ditMs * 4).coerceAtLeast(200L)
                    cwTxTimeoutHandler.postDelayed(cwTxTimeoutRunnable, waitMs)
                }
            }
        }
        val piSerialPort = prefs.cwPort
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ok = cwUsb.connect(device)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(),
                        if (ok) "CW USB connected" else "CW USB connect failed (check logcat)",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                if (!ok) {
                    cwUsbConnected.postValue(false)
                    return@launch
                }
                startCwSendThread()
                // Send current WPM to M5 keyer (sync completion triggers 0xE2, but also set now)
                cwUsb.keeyerWpm = prefs.cwWpm
                // Enable immediately after USB connects — Pi connection failure must not block this
                withContext(Dispatchers.Main) { setCwUsbEnabled(true) }
                // Send paddle swap setting to M5
                cwUsb.sendPaddleSwapToM5(prefs.cwPaddleSwap)
                // Pre-resolve DNS on connection (prevents 2-3 second block on first keystroke)
                val resolveHost = prefs.hostName
                if (resolveHost.isNotEmpty()) {
                    try {
                        val addr = java.net.InetAddress.getByName(resolveHost)
                        val sock = java.net.DatagramSocket()
                        cwUdpAddr = addr
                        cwUdpSocket = sock
                        cwUsb.piSyncAddress = java.net.InetSocketAddress(addr, CW_UDP_PORT)
                        android.util.Log.i("CwUdp", "pre-resolved: $resolveHost -> ${addr.hostAddress}")
                    } catch (e: Exception) {
                        android.util.Log.w("CwUdp", "pre-resolve failed: ${e.message}")
                    }
                }
                try { api.cwOpen(piSerialPort, 0) } catch (_: Exception) {}
            } finally {
                withContext(Dispatchers.Main) { cwUsbConnecting = false }
            }
        }
    }

    fun refreshCwPiConnection() {
        if (!cwUsb.isConnected) return
        viewModelScope.launch(Dispatchers.IO) {
            val host = prefs.hostName
            if (host.isEmpty()) return@launch
            try {
                val addr = java.net.InetAddress.getByName(host)
                cwUdpSocket?.close()
                cwUdpSocket = java.net.DatagramSocket()
                cwUdpAddr = addr
                cwUsb.piSyncAddress = java.net.InetSocketAddress(addr, CW_UDP_PORT)
                android.util.Log.i("CwRefresh", "Pi address refreshed: $host -> ${addr.hostAddress}")
                api.cwOpen(prefs.cwPort, 0)
            } catch (e: Exception) {
                android.util.Log.w("CwRefresh", "CW Pi refresh failed: ${e.message}")
            }
        }
    }

    fun disconnectCwUsb() {
        cwUsbConnecting = false
        cwFmTailJob?.cancel(); cwFmTailJob = null
        cwFmPttJob?.cancel(); cwFmPttJob = null
        stopPttHeartbeat()
        if (cwFmTxActive) {
            cwFmTxActive = false
            txEnabled.value = false
            viewModelScope.launch(Dispatchers.IO) { api.setPtt(false) }
        }
        stopCwServerPolling()
        stopCwSendThread()
        viewModelScope.launch(Dispatchers.IO) { api.cwClose() }
        cwUsb.piSyncAddress = null
        cwUsb.disconnect()
        cwUdpSocket?.close(); cwUdpSocket = null; cwUdpAddr = null
        cwUsbConnected.value = false
    }

    fun setCwUsbEnabled(enabled: Boolean) {
        cwUsbEnabled.value = enabled
        if (!enabled) {
            cwUsb.stopCwAudioStream()
            stopCwServerPolling()
        } else {
            val isCwMode = (sharedMode.value ?: "").contains("CW", ignoreCase = true)
            if (!isCwMode) cwUsb.startCwAudioStream(api, apiKey.value ?: "")
            startCwServerPolling()
        }
    }

    private fun startCwServerPolling() {
        cwServerPollingJob?.cancel()
        cwServerPollingJob = viewModelScope.launch {
            while (isActive) {
                val st = withContext(Dispatchers.IO) { api.cwStatus() }
                cwServerConnected.value   = st?.connected  ?: false
                cwServerSynced.value      = st?.synced     ?: false
                if ((st?.maxLateMs ?: 0) > cwMeasuredLatencyMs.value ?: 0)
                    cwMeasuredLatencyMs.value = st!!.maxLateMs
                delay(3000)
            }
        }
    }

    private fun stopCwServerPolling() {
        cwServerPollingJob?.cancel()
        cwServerPollingJob = null
        cwServerConnected.value = false
        cwServerSynced.value    = false
    }

    // ───────── CW decode display ─────────

    fun toggleCwDecoding() {
        val next = !(cwDecoding.value ?: false)
        cwDecoding.value = next
        cwDecodingActive = next
        if (next) {
            // CWデコード中はafftdnのトランジェント遅延を避けるため
            // バンドパスフィルター(NR相当)に自動切替をPiに通知
            viewModelScope.launch(Dispatchers.IO) {
                api.setCwDecodeMode(true)
                api.setCwDecodeModeOnPort(prefs.audioPort, true)
            }

            synchronized(cwDecodedLock) {
                cwTxBuffer.clear()
                cwRxBuffers.forEach { it.clear() }
            }
            cwTxText.value = ""
            cwRxTexts.forEach { it.value = "" }
            cwRxFreqLabels.forEachIndexed { i, ld -> ld.value = if (i == 0) "RX:" else "---" }
            cwTxKeyDecoder.reset()

            val sampIdx = selectedSamplingIndex.value ?: 1
            val rate = SAMPLING_RATES.getOrElse(sampIdx) { 8000 }.let { if (it == 0) 8000 else it }
            val decoder = CwDecoder(rate)
            decoder.onCharDecoded = { c, ci -> appendCwRxChar(c, ci) }
            decoder.onChannelFreq = { ci, hz ->
                val label = if (hz == 0) (if (ci == 0) "RX:" else "---") else "${hz}Hz"
                cwRxFreqLabels[ci].postValue(label)
            }
            decoder.onChannelSwap = { a, b ->
                // Swap text buffers per slot
                synchronized(cwDecodedLock) {
                    val tmpBuf = StringBuilder(cwRxBuffers[a])
                    cwRxBuffers[a].clear(); cwRxBuffers[a].append(cwRxBuffers[b])
                    cwRxBuffers[b].clear(); cwRxBuffers[b].append(tmpBuf)
                }
                cwRxTexts[a].postValue(cwRxBuffers[a].toString())
                cwRxTexts[b].postValue(cwRxBuffers[b].toString())
            }
            cwRxDecoder = decoder
            cwTxKeyDecoder.onCharDecoded = { c, _ -> appendCwTxChar(c) }

            // VPNバースト保護: リアルタイムより速くサンプルが届く場合は蓄積超過を追跡し
            // 2秒を超えた分はデコードをスキップして団子化を防ぐ
            val cwBurstState = longArrayOf(0L, 0L)  // [0]=前回壁時計ms, [1]=超過ms
            audio.audioSampleListener = { samples ->
                val now = System.currentTimeMillis()
                val chunkAudioMs = samples.size * 1000L / rate.toLong()
                val elapsed = if (cwBurstState[0] == 0L) chunkAudioMs else now - cwBurstState[0]
                cwBurstState[0] = now
                cwBurstState[1] = maxOf(0L, cwBurstState[1] + chunkAudioMs - elapsed)
                if (cwBurstState[1] <= 2000L) {
                    decoder.processSamples(samples)
                }
            }

            // Auto-start SPK if not already running
            if (spkEnabled.value != true && txEnabled.value != true) {
                val sampRate = SAMPLING_RATES.getOrElse(sampIdx) { 0 }
                if (sampRate != 0) {
                    spkEnabled.value = true
                    startAudio()
                }
            }
        } else {
            audio.audioSampleListener = null
            cwRxDecoder?.onCharDecoded = null
            cwRxDecoder?.onChannelFreq = null
            cwRxDecoder?.onChannelSwap = null
            cwRxDecoder = null
            cwTxKeyDecoder.onCharDecoded = null
            cwTxTimeoutHandler.removeCallbacks(cwTxTimeoutRunnable)
            cwTxTimeoutJob?.cancel(); cwTxTimeoutJob = null
            cwRxFreqLabels.forEachIndexed { i, ld -> ld.value = if (i == 0) "RX:" else "---" }

            // CWデコード終了: afftdn NRに戻す
            viewModelScope.launch(Dispatchers.IO) {
                api.setCwDecodeMode(false)
                api.setCwDecodeModeOnPort(prefs.audioPort, false)
            }
        }
    }

    private fun appendCwTxChar(c: Char) {
        val text: String
        synchronized(cwDecodedLock) {
            cwTxBuffer.append(c)
            if (cwTxBuffer.length > CW_MAX_CHARS) cwTxBuffer.delete(0, cwTxBuffer.length - CW_MAX_CHARS)
            text = cwTxBuffer.toString()
        }
        cwTxText.postValue(text)
    }

    private fun appendCwRxChar(c: Char, ci: Int) {
        if (ci !in 0 until CwDecoder.N_CHAN) return
        val text: String
        synchronized(cwDecodedLock) {
            val buf = cwRxBuffers[ci]
            buf.append(c)
            if (buf.length > CW_MAX_CHARS) buf.delete(0, buf.length - CW_MAX_CHARS)
            text = buf.toString()
        }
        cwRxTexts[ci].postValue(text)
    }

    fun toggleCwMultiRx() {
        cwMultiRx.value = !(cwMultiRx.value ?: false)
    }

    // ───────── CW text send via Hamlib ─────────

    private fun buildCwPcm(text: String, wpm: Int, sampleRate: Int = 44100): ShortArray {
        val ditSamples = (sampleRate * 1200.0 / wpm.coerceIn(5, 60) / 1000.0).toInt().coerceAtLeast(1)
        val buf = ArrayList<Short>(sampleRate * 60)
        fun tone(dits: Int) = repeat(ditSamples * dits) { i ->
            buf.add((32767 * 0.7 * sin(2.0 * PI * 700.0 * i / sampleRate)).toInt().toShort())
        }
        fun silence(dits: Int) = repeat(ditSamples * dits) { buf.add(0) }
        val words = text.uppercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for ((wi, word) in words.withIndex()) {
            if (wi > 0) silence(7)
            for ((ci, ch) in word.withIndex()) {
                if (ci > 0) silence(3)
                val morse = CwDecoder.CHAR_TO_MORSE[ch] ?: continue
                for ((ei, el) in morse.withIndex()) {
                    if (ei > 0) silence(1)
                    when (el) { '.' -> tone(1); '-' -> tone(3) }
                }
            }
        }
        silence(7)
        return buf.toShortArray()
    }

    private suspend fun playCwSidetone(text: String, wpm: Int) {
        if (cwSidetoneEnabled.value != true) return
        val pcm = buildCwPcm(text, wpm)
        if (pcm.isEmpty()) return
        val sampleRate = 44100
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val playMs = pcm.size.toLong() * 1000 / sampleRate
        val startMs = System.currentTimeMillis()
        try {
            track.play()
            val chunk = 4410 // 100ms chunks → allows coroutine cancellation check
            var offset = 0
            while (offset < pcm.size && currentCoroutineContext().isActive) {
                val len = minOf(chunk, pcm.size - offset)
                track.write(pcm, offset, len)
                offset += len
            }
            val elapsed = System.currentTimeMillis() - startMs
            val remaining = playMs - elapsed + 100
            if (remaining > 0) delay(remaining)
        } finally {
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
    }

    private suspend fun sendCwTextFm(text: String, wpm: Int) {
        val sampleRate = 8000
        val pcm = buildCwPcm(text, wpm, sampleRate)
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[i * 2]     = (pcm[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (pcm[i].toInt() ushr 8).toByte()
        }
        cwFmTxActive = true
        val key = apiKey.value ?: ""
        try {
            if (!api.setPtt(true)) {
                withContext(Dispatchers.Main) { errorMessage.postValue("PTT failed — check rigctld") }
                return
            }
            startPttHeartbeat()
            val chunkBytes = (sampleRate / 100) * 2  // 160 bytes = 10ms at 8000 Hz
            val conn = (java.net.URL(api.getAudioTxUrl(sampleRate)).openConnection()
                    as java.net.HttpURLConnection).apply {
                doOutput = true
                requestMethod = "POST"
                if (key.isNotEmpty()) setRequestProperty("X-API-Key", key)
                setChunkedStreamingMode(0)
                connectTimeout = 3000
                readTimeout = 0
            }
            conn.connect()
            val out = conn.outputStream
            try {
                var offset = 0
                var nextMs = System.currentTimeMillis()
                while (offset < bytes.size) {
                    nextMs += 10L
                    val len = minOf(chunkBytes, bytes.size - offset)
                    out.write(bytes, offset, len)
                    out.flush()
                    offset += len
                    val sleepMs = nextMs - System.currentTimeMillis()
                    if (sleepMs > 0) delay(sleepMs)  // キャンセル確認ポイント
                }
            } finally {
                try { out.close() } catch (_: Exception) {}
                try { conn.disconnect() } catch (_: Exception) {}
            }
        } finally {
            withContext(NonCancellable) {
                delay(100)
                try { api.setPtt(false) } catch (_: Exception) {}
                stopPttHeartbeat()
                cwFmTxActive = false
            }
        }
    }

    fun sendCwText(text: String, wpm: Int) {
        // 全角ASCII → 半角に正規化 (日本語キーボード対策: ？→? など)
        val normalized = text.map { c ->
            when {
                c in '！'..'～' -> (c.code - 0xFEE0).toChar()
                c == '　' -> ' '
                else -> c
            }
        }.joinToString("")
        cwTextJob?.cancel()
        cwTextJob = viewModelScope.launch(Dispatchers.IO) {
            if (isDemoMode.value == true) return@launch
            // Stop APRS if active — prevents aprs_loop from changing frequency during CW TX
            if (aprsActive.value == true) {
                runCatching {
                    api.stopAprs()
                    api.setPoll(true)
                }
                withContext(Dispatchers.Main) {
                    aprsActive.value = false
                    stopAprsLocationUpdates()
                    stopAprsHeartbeat()
                }
                delay(300)  // let direwolf settle before PTT
            }
            val isCwMode = (sharedMode.value ?: "").contains("CW", ignoreCase = true)
            withContext(Dispatchers.Main) {
                cwTxBusy.value = true
                if (isCwMode) cwTextTxActive = true else txEnabled.value = true
                stopStatusPolling()
                if (!isCwMode) audio.stop()  // CWモード: SPK継続（TX後の復帰遅延を排除）
                if (!isCwMode) cwUsb.stopCwAudioStream()  // audio_tx 競合防止
            }
            try {
                api.setPoll(false)
                launch { playCwSidetone(normalized, wpm) }
                if (isCwMode) {
                    delay(300) // rig_lock 解放待ち (Pi Zero: setPoll後のrigctld残処理考慮)
                    val ok = api.cwSendMorse(normalized, wpm, cwPttPoll.value ?: false)
                    if (!ok) {
                        withContext(Dispatchers.Main) {
                            errorMessage.postValue("CW send failed")
                        }
                        return@launch
                    }
                    while (isActive && api.cwMorseStatus()) {
                        delay(500)
                    }
                } else {
                    // FM-CW: PTT + 700Hz トーンをリアルタイムストリーミング
                    sendCwTextFm(normalized, wpm)
                }
            } finally {
                withContext(NonCancellable) {
                    api.setPoll(true)
                }
                withContext(Dispatchers.Main) {
                    if (cwTxBusy.value == true) {
                        cwTxBusy.value = false
                        cwTextTxActive = false
                        txEnabled.value = false
                        startStatusPolling()
                        if (spkEnabled.value == true && (!isCwMode || !audio.isDataFlowing)) startAudio()  // CWモード: データが流れていなければ再起動
                        updateCwAudioStreamForMode(sharedMode.value ?: "")  // 電鍵ストリーム復元
                    }
                }
            }
        }
    }

    fun stopCwText() {
        cwTextJob?.cancel()
        cwTextJob = null
        viewModelScope.launch(Dispatchers.IO) {
            api.cwStopMorse()
            api.setPtt(false)  // FM-CW パス用
            api.setPoll(true)
        }
        stopPttHeartbeat()
        cwFmTxActive = false
        cwTextTxActive = false
        cwTxBusy.value = false
        txEnabled.value = false
        startStatusPolling()
        val isCwModeNow = (sharedMode.value ?: "").contains("CW", ignoreCase = true)
        if (spkEnabled.value == true && (!isCwModeNow || !audio.isDataFlowing)) startAudio()  // CWモード: データが流れていなければ再起動
    }

    fun disconnectFromRig() {
        isDemoMode.value = false
        externalPttActive = false
        cwCqRepeatJob?.cancel(); cwCqRepeatJob = null
        cwCqRepeating.value = false; cwCqRepeatStatus.value = ""
        cwTextJob?.cancel(); cwTextJob = null
        if (cwTxBusy.value == true) cwTxBusy.value = false
        serverWatchdogJob?.cancel()
        serverWatchdogJob = null
        pttOnJob?.cancel()
        pttOnJob = null
        pttOffJob?.cancel()
        pttOffJob = null
        stopStatusPolling()
        stopAudio()
        audioTx.stop()
        stopAprsHeartbeat()
        stopAprsLocationUpdates()
        stopPttHeartbeat()
        stopCwServerPolling()
        cwUsb.disconnect()
        audio.audioSampleListener = null
        cwRxDecoder = null
        cwTxTimeoutHandler.removeCallbacks(cwTxTimeoutRunnable)
        cwTxTimeoutJob?.cancel(); cwTxTimeoutJob = null
        cwDecodingActive = false
        aprsTxing = false
        aprsStoppedAt = 0L
        lastAudioWatchdogMs = 0L
        isConnectedToRig.value = false
        txEnabled.value = false
        spkEnabled.value = false
        aprsActive.value = false
        cwUsbConnected.value = false
        cwDecoding.value = false
    }

    override fun onCleared() {
        super.onCleared()
        cwTxTimeoutHandler.removeCallbacksAndMessages(null)
        disconnectFromRig()
        udpPtt.close()
    }
}
