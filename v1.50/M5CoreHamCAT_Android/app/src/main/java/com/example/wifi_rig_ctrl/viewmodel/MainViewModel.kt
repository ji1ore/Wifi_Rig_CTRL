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
import kotlin.math.log10

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = AppPrefs(app)
    val api = RigApiService(prefs.hostName, prefs.apiPort, prefs.apiKey)
    val audio = AudioStreamService()
    val audioTx = AudioTxService(api.client)
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

    // CW USB
    val cwUsbConnected = MutableLiveData(false)
    val cwUsbEnabled = MutableLiveData(false)
    val cwSidetoneEnabled = MutableLiveData(prefs.cwSidetoneEnabled)
    val cwDelayMs = MutableLiveData(prefs.cwDelayMs)
    val cwFmDelayMs = MutableLiveData(prefs.cwFmDelayMs)
    val cwServerConnected = MutableLiveData(false)
    val cwServerSynced = MutableLiveData(false)
    val cwMeasuredLatencyMs = MutableLiveData(0)  // Piが計測したmax_late_ms (=クロック差+OWD-バッファ)
    private var cwUsbConnecting = false  // 二重接続防止フラグ（メインスレッドのみ）
    @Volatile private var cwFmTxActive = false  // FM CW TX中フラグ（外部PTT誤検出防止）
    @Volatile private var aprsTxing = false    // APRS TX中フラグ（外部PTT誤検出防止・SPK管理）
    @Volatile private var aprsStoppedAt = 0L  // APRS停止時刻: 停止後30秒はSPKエラーをサイレント
    private var lastAudioWatchdogMs = 0L      // SPKウォッチドッグ最終起動時刻
    private var cwFmTailJob: Job? = null         // VOXテール待機ジョブ

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
    // Handler経由でメインスレッドにtimeoutを投げる（USBスレッドからlaunchより信頼性が高い）
    private val cwTxTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cwTxTimeoutRunnable = Runnable { cwTxKeyDecoder.checkTimeout() }

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

    // Hamlib PTT ON命令のHTTP完了を追跡する: OFFはこのjobをjoin()してから送信する
    private var pttOnJob: Job? = null
    // Android操作でPTT OFFした時刻: 外部PTT誤検出クールダウン用
    private var lastAndroidPttOffTime = 0L

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
        selectedTimeoutIndex.value = profile.savedTimeoutIndex
        useWifiPTT.value = profile.useWifiPTT
        pttHost.value = profile.pttHost
        pttPort.value = profile.pttPort
        selectedPttDevice.value = profile.savedPttDevice
        selectedPttType.value = profile.savedPttType
        cwDelayMs.value = profile.cwDelayMs
        cwFmDelayMs.value = profile.cwFmDelayMs
        cwMeasuredLatencyMs.value = 0  // プロファイル切替時に計測値リセット

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
            savedTimeoutIndex = selectedTimeoutIndex.value ?: 2,
            useWifiPTT = useWifiPTT.value ?: false,
            pttHost = pttHost.value ?: "",
            pttPort = pttPort.value ?: 8888,
            cwDelayMs = prefs.cwDelayMs,
            cwFmDelayMs = prefs.cwFmDelayMs
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
            val port = apiPort.value ?: 8000
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
        if (prefs.hostName.isEmpty()) return "No host configured"
        if (prefs.savedRigId < 0) return "No rig settings saved"
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
                val pt = pttPort.value ?: 8888
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
            prefs.pttPort = pttPort.value ?: 8888

            saveCurrentToProfile()

            api.openRig(rig.id, cat, baud, ptt, pttType)
            delay(1000)

            repeat(50) {
                val st = api.getStatus()
                if (st != null) {
                    withContext(Dispatchers.Main) { sharedModel.value = rig.name }
                    // 前セッションのPTT/ポーリング状態をリセット
                    api.setPtt(false)
                    api.setPoll(true)
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
                updateCwAudioStreamForMode(newMode)
            }
        }
        if (now - lastUserPowerChange > 1000) sharedPower.value = st.power
        if (now - lastUserSQLChange > 1000) sharedSQL.value = st.sql
        if (now - lastUserWidthChange > 1000) sharedWidth.value = st.width
        sharedSignal.value = s
        sharedTx.value = st.tx
        aprsTxInProgress.value = st.txInProgress

        // APRS TX状態管理: ラジオがTX中かつAPRSが有効 → APRS TX中と判断
        // TX開始でSPK停止、TX終了でSPK再開（外部PTT誤検出も同時にブロック）
        val nowAprsTxing = aprsActive.value == true && st.tx
        if (nowAprsTxing && !aprsTxing) {
            aprsTxing = true
            audio.stop()  // APRS TX開始: RX音声停止
        } else if (!nowAprsTxing && aprsTxing) {
            aprsTxing = false
            if (spkEnabled.value == true && txEnabled.value != true) {
                startAudio()  // APRS TX終了: RX音声再開
            }
        }

        // SPKウォッチドッグ: SPK ONなのに音声が止まっていれば15秒ごとに自動再接続
        // APRS TX中(aprsTxing)以外はAPRS有効時も音声を維持する
        if (spkEnabled.value == true && !audio.isPlaying && !audio.isStreamActive
                && txEnabled.value != true && !aprsTxing
                && now - lastAudioWatchdogMs > 15_000L) {
            startAudio()
        }

        // 外部PTT検出: M5物理ボタン等でradiocacheがTX=trueになったら音声送信を追従
        // pttOffJob/audioTxStartJob が実行中 or Android PTT OFF直後のクールダウン中はスキップ
        // cwFmTxActive中はCW FM TX中のため外部PTT誤検出をブロック
        // aprsActive中はAPRS TXを外部PTTと誤検出しないためスキップ
        val pttOffCooldown = System.currentTimeMillis() - lastAndroidPttOffTime < 3000
        if (pttOffJob == null && audioTxStartJob == null && !pttOffCooldown && !cwFmTxActive && aprsActive.value != true) {
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
        updateCwAudioStreamForMode(mode)
        viewModelScope.launch(Dispatchers.IO) { api.setMode(mode, width) }
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
            // ポーリング停止: Piのポーリング中HTTP完了を待ち、CATバスを空ける
            stopStatusPolling()
            // 保留中のPTT OFFを取消し、強制クローズ
            pttOffJob?.cancel()
            pttOffJob = null
            audioTx.stop()
            audio.stop()
            audioTxStartJob?.cancel()
            audioTxStartJob = null  // setPtr成功後にpttOnJob内で起動
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
                            audioTx.start(api.getAudioTxUrl(), apiKey.value ?: "")
                        }
                    }
                } else {
                    android.util.Log.d("PTT", "Hamlib PTT ON")
                    // setPoll(false)を先行送信: Piバックグラウンドポーリングを停止
                    api.setPoll(false)
                    // CAT応答完了バッファ: in-flight poll_rig()コマンドがrigctldキューから
                    // 抜けるまで待機。FT-991 CAT最大応答~150ms + HTTP往復~100ms = 300ms余裕あり
                    delay(300L)
                    if (txEnabled.value == true) {
                        var sent = false
                        for (attempt in 1..5) {
                            if (api.setPtt(true)) { sent = true; break }
                            if (!isActive || txEnabled.value != true) break
                            delay(200)
                        }
                        // setPtr成功後にのみheartbeat・マイク音声を開始
                        if (sent && txEnabled.value == true) {
                            withContext(Dispatchers.Main) {
                                startPttHeartbeat()
                                audioTxStartJob = viewModelScope.launch {
                                    audioTx.start(api.getAudioTxUrl(), apiKey.value ?: "")
                                }
                            }
                        }
                    }
                }
            }
            // startPttHeartbeat()とaudioTxStartJobはpttOnJob内でsetPtt(true)成功後に開始
        } else {
            lastAndroidPttOffTime = System.currentTimeMillis()  // 外部PTT誤検出クールダウン開始
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
                    // ON命令のHTTP完了を待ってからOFFを送る（rigが立ち上がる前にOFFが届かないよう）
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
                                api.setPtt(false)   // 失敗時1回リトライ
                            }
                            api.setPoll(true)
                        }
                    }
                }
                audioTx.stop()
                delay(200)
                withContext(Dispatchers.IO) {
                    if (txEnabled.value != true) {
                        if (useWifiPTT.value == true) {
                            val h = pttHost.value
                            val pt = pttPort.value ?: 8888
                            if (!h.isNullOrEmpty()) udpPtt.sendPtt(h, pt, false)
                        } else {
                            api.setPtt(false)
                        }
                        Unit
                    }
                }
                // PTT OFF完了: ポーリング再開（停止はON時のstopStatusPolling()）
                if (txEnabled.value != true) startStatusPolling()
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
                        val pt = pttPort.value ?: 8888
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
        if (aprsTxing) return                // APRS TX中はSPK起動しない
        val aPort = audioPort.value ?: 50000
        val sIdx = selectedSamplingIndex.value ?: 1
        val rate = SAMPLING_RATES.getOrElse(sIdx) { 0 }
        if (rate == 0) return
        lastAudioWatchdogMs = System.currentTimeMillis()
        // onErrorはエラー発生時点でAPRS状態を確認する（startAudio()呼び出し時点で決めない）:
        // APRS停止後120秒間はPi Zeroの音声サービス再起動を待つためエラーをサイレントにする
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
        viewModelScope.launch {
            if (aprsActive.value != true) {
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
                        delay(500)
                        api.startAprs(cfg)
                    }
                }.isSuccess
                if (ok) {
                    aprsActive.value = true
                    startAprsLocationUpdates()
                    startAprsHeartbeat()
                } else {
                    // API失敗: ポーリング再開してSPK復旧
                    runCatching { withContext(Dispatchers.IO) { api.setPoll(true) } }
                    if (spkEnabled.value == true && txEnabled.value != true) startAudio()
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
        // GPS・ネットワーク両プロバイダーから最新の既知位置を取得
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
            aprsLat.postValue(loc.latitude.toFloat())
            aprsLon.postValue(loc.longitude.toFloat())
        }
        locationListener = listener
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.isProviderEnabled(it) }
        // GPS・ネットワーク両プロバイダーを登録 (30秒間隔)
        for (p in providers) {
            lm.requestLocationUpdates(p, 30000L, 10f, listener, Looper.getMainLooper())
        }
        // 最終既知位置で即時初期化
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
                delay(10000)  // ウォッチドッグ更新は10秒ごと
                gpsTick++
                val sendGpsNow = aprsUseGPS.value == true && gpsTick % 3 == 0  // GPS送信は30秒ごと
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

    // ───────── CW UDP キー送信（専用送信スレッド）─────────
    // USB読み取りスレッド上でDNS/ネットワーク待ちが発生すると最初の1回以降すべてブロックするため、
    // LinkedBlockingQueue + 専用送信スレッドでUSBスレッドを解放する。

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
            val sock = cwUdpSocket ?: return
            val addr = cwUdpAddr ?: return
            sock.send(java.net.DatagramPacket(packet, packet.size, addr, CW_UDP_PORT))
        } catch (e: Exception) {
            android.util.Log.w("CwUdp", "send error: ${e.message}")
            cwUdpSocket = null
        }
    }

    // ───────── FM CW VOX セッション管理 ─────────
    // USBキーまたはPTTボタンCWトーンモードから呼ばれる（USBスレッドから呼び出し可）

    private fun cwFmKeyOn() {
        cwFmTailJob?.cancel()  // テール待機をキャンセル（セッション継続）
        cwFmTailJob = null
        if (cwFmTxActive) return  // セッション既に開始中
        cwFmTxActive = true  // volatile: メインスレッドから可視
        txEnabled.postValue(true)
        cwFmPttJob?.cancel()
        cwFmPttJob = viewModelScope.launch(Dispatchers.IO) {
            audio.stop()  // SPKを停止
            api.setPoll(false)
            delay(80L + (cwFmDelayMs.value ?: 0).toLong())  // SPK切替 + FM-CW VPN伝搬遅延補償
            api.setPtt(true)
            withContext(Dispatchers.Main) { startPttHeartbeat() }  // watchdog延命
        }
    }

    private fun cwFmKeyOff() {
        if (!cwFmTxActive) return
        cwFmTailJob?.cancel()
        cwFmTailJob = viewModelScope.launch {
            delay(500)  // VOXテール: 最終dit/dah後に500ms待機
            stopPttHeartbeat()
            cwFmPttJob?.cancel()
            cwFmPttJob = null
            lastAndroidPttOffTime = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                delay(60)  // 最終トーンチャンクのドレイン待ち
                api.setPtt(false)
                api.setPoll(true)
            }
            cwFmTxActive = false
            txEnabled.value = false
            cwFmTailJob = null
            if (spkEnabled.value == true) startAudio()
        }
    }

    // ───────── CW USB中継 ─────────

    fun connectCwUsb(device: android.hardware.usb.UsbDevice) {
        // onResume()スキャンとACTION_USB_PERMISSIONが同時に呼ぶ二重接続を防ぐ
        if (cwUsb.isConnected || cwUsbConnecting) return
        cwUsbConnecting = true

        cwUsb.onConnectionStateChange = { connected ->
            cwUsbConnected.postValue(connected)
        }
        cwUsb.onKeyStateChange = { isOn, rawPacket ->
            cwUsb.currentMode = sharedMode.value ?: ""
            val mode = cwUsb.currentMode
            val isCwMode = mode.contains("CW", ignoreCase = true)
            val isFmMode = mode.contains("FM", ignoreCase = true)
            if (cwUsbEnabled.value == true) {
                when {
                    isCwMode -> {
                        val fireMs = System.currentTimeMillis() + prefs.cwDelayMs.toLong()
                        val pkt = rawPacket.copyOf()
                        for (i in 0..7) pkt[2 + i] = ((fireMs ushr (56 - i * 8)) and 0xFF).toByte()
                        cwSendQueue.offer(pkt)
                    }
                    isFmMode -> {
                        if (isOn) cwFmKeyOn() else cwFmKeyOff()
                    }
                }
            }
            // TX decode: USBスレッドで直接呼び出し (CwKeyDecoder は @Synchronized)
            // Handler経由でメインスレッドへ timeout を投げる（coroutine launch より確実）
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
                // 接続時にDNSを事前解決（初回打鍵時の2〜3秒ブロック防止）
                // このコルーチンは Dispatchers.IO 上で動作するので DNS ブロック可
                val resolveHost = prefs.hostName
                if (resolveHost.isNotEmpty()) {
                    try {
                        val addr = java.net.InetAddress.getByName(resolveHost)
                        val sock = java.net.DatagramSocket()
                        cwUdpAddr = addr
                        cwUdpSocket = sock
                        android.util.Log.i("CwUdp", "pre-resolved: $resolveHost -> ${addr.hostAddress}")
                    } catch (e: Exception) {
                        android.util.Log.w("CwUdp", "pre-resolve failed (fallback on first send): ${e.message}")
                    }
                }
                api.cwOpen(piSerialPort, 0)
                // USB接続成功後に自動有効化
                withContext(Dispatchers.Main) { setCwUsbEnabled(true) }
            } finally {
                withContext(Dispatchers.Main) { cwUsbConnecting = false }
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

    // ───────── CW デコード表示 ─────────

    fun toggleCwDecoding() {
        val next = !(cwDecoding.value ?: false)
        cwDecoding.value = next
        cwDecodingActive = next
        if (next) {
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
                // テキストバッファをスロットごと入れ替え
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

            audio.audioSampleListener = { samples -> decoder.processSamples(samples) }

            // SPKが未起動なら自動起動
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

    fun disconnectFromRig() {
        externalPttActive = false
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
