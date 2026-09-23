package com.ji1ore.wifi_rig_ctrl

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ji1ore.wifi_rig_ctrl.data.Ft8LocalEngine
import com.ji1ore.wifi_rig_ctrl.data.PotaSpot
import com.ji1ore.wifi_rig_ctrl.data.SCREEN_TIMEOUT_OPTIONS
import com.ji1ore.wifi_rig_ctrl.data.SotaCluster
import com.ji1ore.wifi_rig_ctrl.data.SotaSpot
import com.ji1ore.wifi_rig_ctrl.databinding.FragmentFt8Binding
import com.ji1ore.wifi_rig_ctrl.databinding.ItemFt8MsgBinding
import com.ji1ore.wifi_rig_ctrl.viewmodel.MainViewModel
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class Ft8Fragment : Fragment() {

    private var _binding: FragmentFt8Binding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()
    private var isFt4 = false

    private var screenTimeoutJob: Job? = null
    private var preFt8Freq = 0L
    private var preFt8Mode = ""
    private var preFt8Width = 0

    private var sseCall: Call? = null
    private var sseJob: Job? = null
    private var spectrumCall: Call? = null
    private var spectrumJob: Job? = null
    private var spotFetchJob: Job? = null

    // CI-V local FT8 engine (active when useCIV=true — Raspi接続はそのまま、CI-Vの時だけ内蔵MFSKを使用)
    private var localEngine: Ft8LocalEngine? = null
    private val isLocalMode get() = localEngine != null

    private val spotHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val spotGson = Gson()
    private var potaSpotMap: Map<String, List<PotaSpot>> = emptyMap()
    private var sotaSpotMap: Map<String, List<SotaSpot>> = emptyMap()

    // 描画済み utcSec のセット（Period 0/15/30/45s を個別管理して decode フォールバックの重複防止）
    private val markedUtcSecs = mutableSetOf<Int>()
    private var pendingTxEpochMs: Long = -1L  // tx_pending で追加済みで未確定のメッセージの epochMs

    private var txFreqHz: Int = 1500   // TX音声周波数 (Hz)

    private var wfBrightness = 1.0f
    private var decodeDepth = 1  // prefs から上書きされる
    private var ft8FontSp: Float = 16f

    private enum class TxState { OFF, ONE_SHOT, AUTO, CQ_AUTO }
    private var txState = TxState.OFF
    private var cqAutoUserEnabled = false
    private var autoTxMode = "even"
    private var filterEnabled = false
    private var filterDxFreq = 0
    private var qsoLogSaved = false

    data class Ft8Msg(
        val freq: Int, val snr: Int, val dt: Double, val msg: String,
        val utcSec: Int, val period: Int, val isTx: Boolean = false,
        val epochMs: Long = System.currentTimeMillis(),
        val seqNo: Long = 0L
    )

    private fun utcSecToEpochMs(utcSec: Int): Long {
        val nowMs = System.currentTimeMillis()
        val nowUtcSec = (nowMs / 1000 % 60).toInt()
        val secAgo = ((nowUtcSec - utcSec) + 60) % 60
        return nowMs - secAgo * 1000L
    }
    private val rxMsgs = mutableListOf<Ft8Msg>()
    private val txMsgs = mutableListOf<Ft8Msg>()
    private var msgSeqNo = 0L        // メッセージ追加順序管理
    private var currentDecodePeriod = -1   // ストリーミング用: 現在デコード中のピリオド番号
    private var currentPeriodSeqNo = 0L    // 同一ピリオド内の全 decode_msg に共通の seqNo
    private lateinit var adapter: Ft8MsgAdapter

    private data class QsoLogEntry(
        val dt: String, val call: String, val freqHz: Long, val mode: String,
        val rstSent: String, val rstRcvd: String, val dxGrid: String,
        val myCall: String, val myGrid: String
    )

    companion object {
        fun latLonToGrid(lat: Double, lon: Double): String {
            val adjLon = lon + 180.0; val adjLat = lat + 90.0
            val field = charArrayOf('A' + (adjLon / 20).toInt(), 'A' + (adjLat / 10).toInt())
            val square = charArrayOf('0' + ((adjLon % 20) / 2).toInt(), '0' + (adjLat % 10).toInt())
            return String(field) + String(square)
        }
    }

    // --- RecyclerView Adapter ---

    inner class Ft8MsgAdapter : RecyclerView.Adapter<Ft8MsgAdapter.VH>() {
        private var filter = false
        private var dxFreq = 0
        private var potaMap: Map<String, List<PotaSpot>> = emptyMap()
        private var sotaMap: Map<String, List<SotaSpot>> = emptyMap()
        fun setSpotMaps(pota: Map<String, List<PotaSpot>>, sota: Map<String, List<SotaSpot>>) {
            potaMap = pota; sotaMap = sota; notifyDataSetChanged()
        }
        var qsoDxCall    = ""  // QSO相手コールサイン
        var qsoWaitingFor = "" // "snr" | "waiting" | "done" | ""
        fun setFilter(v: Boolean) { filter = v; notifyDataSetChanged() }
        fun setFilterDxFreq(freq: Int) { dxFreq = freq; notifyDataSetChanged() }
        fun setQsoState(dx: String, waitingFor: String) {
            if (dx.isNotEmpty()) qsoDxCall = dx
            qsoWaitingFor = waitingFor
            notifyDataSetChanged()
        }
        fun clearQsoState() {
            qsoDxCall = ""; qsoWaitingFor = ""; notifyDataSetChanged()
        }
        private fun filtered(): List<Ft8Msg> {
            // Filter は相手局が決まった時のみ有効（dxFreq > 0）
            val rx = if (filter && dxFreq > 0) {
                rxMsgs.filter { m -> kotlin.math.abs(m.freq - dxFreq) <= 150 }
            } else {
                rxMsgs
            }
            return (rx + txMsgs).sortedBy { it.seqNo }
        }
        override fun getItemCount() = filtered().size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemFt8MsgBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(filtered()[position])
        private val utcFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        inner class VH(private val b: ItemFt8MsgBinding) : RecyclerView.ViewHolder(b.root) {
            init {
                b.root.setOnClickListener {
                    filtered().getOrNull(adapterPosition)?.let { if (!it.isTx) onMsgShortTap(it) }
                }
                b.root.setOnLongClickListener {
                    filtered().getOrNull(adapterPosition)?.let { if (!it.isTx) onMsgTapped(it) }
                    true
                }
            }
            fun bind(item: Ft8Msg) {
                val timeStr = utcFmt.format(java.util.Date(item.epochMs))
                val isEven = if (isFt4) item.utcSec % 15 == 0 else item.utcSec % 30 == 0
                val timeColor = if (isEven) 0xFF88AAFF.toInt() else 0xFF88FF44.toInt()
                b.tvTime.text = timeStr
                b.tvTime.setTextColor(timeColor)
                if (item.isTx) {
                    b.tvSnr.text = ""
                    b.tvMsg.text = item.msg
                    // TX行をQSOステップ別に色分け
                    val txParts = item.msg.trim().uppercase().split(Regex("\\s+"))
                    val txW3 = txParts.getOrNull(2) ?: ""
                    when {
                        txW3.matches(Regex("R[+-]?\\d+")) -> {
                            // Step2: R-SNR送信中 (自分→相手 R-SNR→待RR73)
                            b.tvFreq.text = "TX R"
                            b.root.setBackgroundColor(0xFF1E1200.toInt())
                            b.tvFreq.setTextColor(0xFFFFAA22.toInt())
                            b.tvMsg.setTextColor(0xFFFFCC66.toInt())
                        }
                        txW3 == "RR73" -> {
                            // Step3: RR73送信中
                            b.tvFreq.text = "TX 3"
                            b.root.setBackgroundColor(0xFF001A0A.toInt())
                            b.tvFreq.setTextColor(0xFF44CC66.toInt())
                            b.tvMsg.setTextColor(0xFF88FFAA.toInt())
                        }
                        txW3 == "73" -> {
                            // Step4: 73送信中
                            b.tvFreq.text = "TX 73"
                            b.root.setBackgroundColor(0xFF001400.toInt())
                            b.tvFreq.setTextColor(0xFF44FF44.toInt())
                            b.tvMsg.setTextColor(0xFF88FF88.toInt())
                        }
                        else -> {
                            // Step1 or CQ
                            b.tvFreq.text = "TX▶"
                            b.root.setBackgroundColor(0xFF2A1400.toInt())
                            b.tvFreq.setTextColor(0xFFFF6622.toInt())
                            b.tvMsg.setTextColor(0xFFFF9944.toInt())
                        }
                    }
                } else {
                    val snrStr = if (item.snr >= 0) "+${item.snr}" else "${item.snr}"
                    b.tvFreq.text = "%4d".format(item.freq)
                    b.tvSnr.text = "%3s".format(snrStr)
                    b.tvMsg.text = item.msg
                    val myCall = vm.prefs.ft8MyCall.uppercase()
                    val parts = item.msg.trim().uppercase().split(Regex("\\s+"))
                        .map { if (it.startsWith("<") && it.endsWith(">")) it.drop(1).dropLast(1) else it }
                    val isCq = item.msg.startsWith("CQ ")
                    val isToMe = myCall.isNotEmpty() && parts.firstOrNull() == myCall
                    // QSOの相手局からの自分宛メッセージか
                    val isFromQsoDx = qsoDxCall.isNotEmpty() && parts.getOrNull(1) == qsoDxCall
                    val w3 = parts.getOrNull(2) ?: ""
                    val isRR73orFinal = w3 in setOf("RR73", "73")
                    when {
                        isToMe && isFromQsoDx && qsoWaitingFor == "waiting" && isRR73orFinal -> {
                            // RR73/73受信 → 73へ進む (明緑)
                            b.root.setBackgroundColor(0xFF003300.toInt())
                            b.tvFreq.setTextColor(0xFF44FF88.toInt())
                            b.tvMsg.setTextColor(0xFF88FFB8.toInt())
                        }
                        isToMe && isFromQsoDx && qsoWaitingFor == "waiting" -> {
                            // R-SNR送信中に相手信号あり・RR73待ち → 進まない (青)
                            b.root.setBackgroundColor(0xFF001428.toInt())
                            b.tvFreq.setTextColor(0xFF4499FF.toInt())
                            b.tvMsg.setTextColor(0xFF88BBFF.toInt())
                        }
                        isToMe && isFromQsoDx && qsoWaitingFor == "snr" && !isRR73orFinal -> {
                            // SNR受信 → R-SNRへ進む (琥珀)
                            b.root.setBackgroundColor(0xFF1F1200.toInt())
                            b.tvFreq.setTextColor(0xFFFFBB00.toInt())
                            b.tvMsg.setTextColor(0xFFFFDD88.toInt())
                        }
                        isToMe -> {
                            b.root.setBackgroundColor(0xFF1A1A00.toInt())
                            b.tvFreq.setTextColor(0xFFFFDD44.toInt())
                            b.tvMsg.setTextColor(0xFFFFFF00.toInt())
                        }
                        isCq -> {
                            b.root.setBackgroundColor(0xFF1A2C1A.toInt())
                            b.tvFreq.setTextColor(0xFFAAAAAA.toInt())
                            b.tvMsg.setTextColor(0xFF88FF88.toInt())
                        }
                        else -> {
                            b.root.setBackgroundColor(0xFF0A0A0A.toInt())
                            b.tvFreq.setTextColor(0xFFAAAAAA.toInt())
                            b.tvMsg.setTextColor(0xFFCCCCCC.toInt())
                        }
                    }
                    // POTA/SOTA スポットバッジ（コールサイン2番目=送信局のみ対象）
                    val call2 = extractCallsigns(
                        if (parts.size >= 3) parts.drop(1).dropLast(1) else parts.drop(1)
                    ).firstOrNull() ?: ""
                    val hasPota = call2.isNotEmpty() && potaMap.containsKey(call2)
                    val hasSota = call2.isNotEmpty() && sotaMap.containsKey(call2)
                    if (hasPota || hasSota) {
                        // POTA ハント済み判定（POTAのみ: SOTAは判定データなし）
                        val potaSpots = potaMap[call2] ?: emptyList()
                        val potaHunted = hasPota && potaSpots.any {
                            vm.hunterStore.isHunted(it.reference)
                        }
                        // ラベル: P✓/S, P/S, P✓, P, S (短縮表記)
                        val pLabel = if (hasPota) if (potaHunted) "P✓" else "P" else ""
                        val sLabel = if (hasSota) "S" else ""
                        val label = when {
                            pLabel.isNotEmpty() && sLabel.isNotEmpty() -> "$pLabel/$sLabel"
                            pLabel.isNotEmpty() -> pLabel
                            else -> sLabel
                        }
                        // 未ハント POTA が最優先（最も行動が必要）、ハント済みは抑制
                        val bgColor = when {
                            hasPota && !potaHunted -> 0xFF00CC55.toInt()  // 緑(POTA未ハント)
                            hasPota && potaHunted  -> 0xFF1B5E20.toInt()  // 暗緑(POTA済)
                            else                   -> 0xFF0088EE.toInt()  // 青(SOTAのみ)
                        }
                        val d = b.root.context.resources.displayMetrics.density
                        val ph = (4 * d).toInt(); val pv = (2 * d).toInt()
                        b.tvSpot.text = label
                        b.tvSpot.textSize = 11f
                        b.tvSpot.setBackgroundColor(bgColor)
                        b.tvSpot.setTextColor(0xFFFFFFFF.toInt())
                        b.tvSpot.setPadding(ph, pv, ph, pv)
                        val sotaSpots = sotaMap[call2] ?: emptyList()
                        val spotId = when {
                            hasPota -> potaSpots.firstOrNull()?.reference ?: ""
                            else    -> sotaSpots.firstOrNull()?.summitCode ?: ""
                        }
                        b.tvSpotId.text = spotId
                        b.tvSpotId.setTextColor(0xFF88FFBB.toInt())
                        b.tvSpotId.visibility = if (spotId.isNotEmpty()) View.VISIBLE else View.GONE
                        b.llSpot.visibility = View.VISIBLE
                    } else {
                        b.llSpot.visibility = View.GONE
                    }
                }
            }
        }
    }

    // --- Lifecycle ---

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFt8Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preFt8Freq = vm.sharedFreq.value ?: 0L
        preFt8Mode = vm.sharedMode.value ?: ""
        preFt8Width = vm.sharedWidth.value ?: 0

        setupRecyclerView()
        setupButtons()
        parentFragmentManager.setFragmentResultListener("memory_back_to_main", viewLifecycleOwner) { _, _ ->
            findNavController().popBackStack()
        }
        updatePowerDisplay(vm.sharedPower.value ?: 0f)
        vm.sharedPower.observe(viewLifecycleOwner) { updatePowerDisplay(it) }
        updateFreqDisplay(vm.sharedFreq.value ?: 0L)
        vm.sharedFreq.observe(viewLifecycleOwner) { updateFreqDisplay(it) }
        binding.tvMode.text = vm.sharedMode.value?.takeIf { it.isNotEmpty() } ?: "---"
        vm.sharedMode.observe(viewLifecycleOwner) { binding.tvMode.text = it.ifEmpty { "---" } }

        setupFt8TxMeters()
        vm.sharedTx.observe(viewLifecycleOwner) { updateFt8TxMeterVisibility(it) }
        vm.sharedRfOut.observe(viewLifecycleOwner) { updateFt8PoMeter(it) }
        vm.sharedAlc.observe(viewLifecycleOwner) { updateFt8AlcMeter(it) }

        isFt4 = vm.prefs.ft8IsFt4
        applyFt4Tint()

        vm.navigateToFt8.observe(viewLifecycleOwner) { newIsFt4 ->
            if (newIsFt4 == null || newIsFt4 == isFt4) return@observe
            vm.navigateToFt8.value = null
            isFt4 = newIsFt4; vm.prefs.ft8IsFt4 = newIsFt4; applyFt4Tint()
            if (localEngine != null) {
                localEngine?.setFt4Mode(newIsFt4)
            } else {
                val ft4 = newIsFt4
                lifecycleScope.launch(Dispatchers.IO) {
                    try { vm.api.ft8SetFt4(ft4) } catch (_: Exception) {}
                }
            }
        }

        val ft8LastFreq = vm.prefs.ft8LastFreq
        vm.sendMode(vm.prefs.ft8TxMode, 3000)
        if (ft8LastFreq > 0L) vm.sendFreq(ft8LastFreq)

        // CI-V mode: use local MFSK engine. Raspi接続の場合はPi側SSEを使用。
        if (vm.useCIV.value == true) {
            val isBt = vm.civConnectionType.value == "BT"
            // BT hybrid: BT for CI-V control, RS-BA1 WiFi for audio (civ.audioOnly=true).
            // Pass vm.civ so Ft8LocalEngine uses RS-BA1 audio (8 kHz) instead of phone mic.
            val hasBtHybridAudio = isBt && vm.civ.audioOnly && vm.civ.isConnected
            val engine = Ft8LocalEngine(
                context = requireContext(),
                civTcp  = if (!isBt || hasBtHybridAudio) vm.civ else null,
                civBt   = if (isBt) vm.civBt else null,
                isBt    = isBt
            )
            engine.rxGain = vm.prefs.ft8RxGain
            engine.txGain = vm.prefs.ft8TxGain
            engine.isFt4  = isFt4
            engine.listener = object : Ft8LocalEngine.Listener {
                override fun onSseEvent(eventType: String, data: org.json.JSONObject) {
                    data.put("type", eventType)
                    parseSseLine(data.toString())
                }
                override fun onSpectrumBins(bins: IntArray, newPeriod: Boolean, periodNo: Int, utcSec: Int) {
                    if (newPeriod && bins.isEmpty()) {
                        // Period boundary: add waterfall marker
                        if (markedUtcSecs.add(utcSec)) {
                            if (markedUtcSecs.size > 8) markedUtcSecs.clear()
                            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                            val hhmm = "%02d:%02d".format(
                                cal.get(java.util.Calendar.HOUR_OF_DAY),
                                cal.get(java.util.Calendar.MINUTE))
                            _binding?.waterfallView?.addPeriodMarker("$hhmm P$periodNo")
                        }
                    } else if (bins.isNotEmpty()) {
                        _binding?.waterfallView?.addLine(bins)
                    }
                }
            }
            localEngine = engine
            // RECORD_AUDIO is a runtime permission — check before starting AudioRecord
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                engine.start(vm.prefs.ft8MyCall, vm.prefs.ft8MyGrid)
                _binding?.tvStatus?.text = "CI-V Local FT8"
            } else {
                recordAudioPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                try { vm.api.ft8Start() } catch (_: Exception) {}
                try { vm.api.ft8SetTxGain(vm.prefs.ft8TxGain) } catch (_: Exception) {}
                try { vm.api.ft8SetFt4(isFt4) } catch (_: Exception) {}
            }
            startSse()
            startSpectrum()
        }
        startSpotFetch()
        if (vm.prefs.ft8MyCall.isEmpty()) {
            Toast.makeText(requireContext(),
                "Callsign not set. Long-press CQ to configure.",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun setupRecyclerView() {
        adapter = Ft8MsgAdapter()
        binding.rvMsgs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMsgs.adapter = adapter
        filterEnabled = vm.prefs.ft8Filter
        adapter.setFilter(filterEnabled)
        binding.btnCqOnly.isChecked = filterEnabled
    }

    // --- Spectrum SSE (Waterfall) ---

    private fun startSpectrum() {
        spectrumJob?.cancel()
        spectrumJob = lifecycleScope.launch(Dispatchers.IO) { connectSpectrum() }
    }

    private suspend fun connectSpectrum() {
        withContext(Dispatchers.Main) {
            _binding?.tvStatus?.text = "Spectrum: connecting..."
        }
        while (currentCoroutineContext().isActive && _binding != null) {
            try {
                val req = Request.Builder().url(vm.api.ft8SpectrumUrl())
                    .addHeader("Accept", "text/event-stream").build()
                val call = vm.api.sseClient.newCall(req)
                spectrumCall = call
                val resp = withContext(Dispatchers.IO) { call.execute() }
                if (!resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        _binding?.tvStatus?.text = "Spectrum: HTTP ${resp.code} (Pi update required?)"
                    }
                    resp.close()
                    delay(5000); continue
                }
                withContext(Dispatchers.Main) {
                    _binding?.tvStatus?.text = "Spectrum: connected"
                }
                val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream()))
                withContext(Dispatchers.IO) {
                    reader.forEachLine { line ->
                        if (line.startsWith("data: ")) parseSpectrumLine(line.removePrefix("data: "))
                    }
                }
                resp.close()
            } catch (e: CancellationException) { break }
              catch (_: Exception) {}
            if (currentCoroutineContext().isActive) {
                withContext(Dispatchers.Main) {
                    _binding?.tvStatus?.text = "Spectrum: reconnecting..."
                }
                delay(3000)
            }
        }
    }

    private fun parseSpectrumLine(json: String) {
        try {
            val obj  = JSONObject(json)
            val arr  = obj.optJSONArray("bins") ?: return
            val bins = IntArray(arr.length()) { arr.getInt(it) }
            // スペクトラムフレームに埋め込まれた周期境界フラグ（横線をaddLineと同期）
            val isNewPeriod = obj.optBoolean("new_period", false)
            val period      = obj.optInt("period")
            val utcSec      = obj.optInt("utc_sec")
            lifecycleScope.launch(Dispatchers.Main) {
                if (isNewPeriod && markedUtcSecs.add(utcSec)) {
                    if (markedUtcSecs.size > 8) markedUtcSecs.clear()
                    val cal  = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                    val hhmm = "%02d:%02d".format(
                        cal.get(java.util.Calendar.HOUR_OF_DAY),
                        cal.get(java.util.Calendar.MINUTE))
                    _binding?.waterfallView?.addPeriodMarker("$hhmm P$period")
                }
                _binding?.waterfallView?.addLine(bins)
            }
        } catch (_: Exception) {}
    }

    // --- SSE ---

    private fun startSse() {
        sseJob?.cancel()
        sseJob = lifecycleScope.launch(Dispatchers.IO) { connectSse() }
    }

    private suspend fun connectSse() {
        withContext(Dispatchers.Main) { _binding?.tvStatus?.text = "RX: connecting..." }
        while (currentCoroutineContext().isActive && _binding != null) {
            try {
                val req = Request.Builder().url(vm.api.ft8RxMsgsUrl())
                    .addHeader("Accept", "text/event-stream").build()
                val call = vm.api.sseClient.newCall(req)
                sseCall = call
                val resp = withContext(Dispatchers.IO) { call.execute() }
                if (!resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        _binding?.tvStatus?.text = "RX: HTTP ${resp.code}"
                    }
                    resp.close(); delay(5000); continue
                }
                withContext(Dispatchers.Main) { _binding?.tvStatus?.text = "RX: connected" }
                try { vm.api.ft8SetDepth(decodeDepth) } catch (_: Exception) {}
                val syncFreq = withContext(Dispatchers.Main) {
                    if (filterEnabled && filterDxFreq > 0) filterDxFreq else 0
                }
                try {
                    if (syncFreq > 0) vm.api.ft8SetDecodeFilter(listOf(syncFreq), 150)
                    else vm.api.ft8SetDecodeFilter(emptyList())
                } catch (_: Exception) {}
                val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream()))
                withContext(Dispatchers.IO) {
                    reader.forEachLine { line ->
                        if (line.startsWith("data: ")) parseSseLine(line.removePrefix("data: "))
                    }
                }
                resp.close()
            } catch (e: CancellationException) { break }
              catch (_: Exception) {}
            if (currentCoroutineContext().isActive) {
                withContext(Dispatchers.Main) { _binding?.tvStatus?.text = "RX: reconnecting..." }
                delay(3000)
            }
        }
    }

    private fun parseSseLine(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("type")) {
                // ─── mfsk-core ストリーミング: デコード完了局から逐次受信 ───
                "decode_msg" -> {
                    val period  = obj.optInt("period", -1)
                    val utcSec  = obj.optInt("utc_sec", 0)
                    val rxEpochMs = utcSecToEpochMs(utcSec)
                    val item = Ft8Msg(
                        freq    = obj.optInt("freq"),
                        snr     = obj.optInt("snr"),
                        dt      = obj.optDouble("dt"),
                        msg     = obj.optString("msg"),
                        utcSec  = utcSec,
                        period  = period,
                        epochMs = rxEpochMs,
                    )
                    lifecycleScope.launch(Dispatchers.Main) {
                        // 同一ピリオド内のメッセージはすべて同じ seqNo → 正しい順序でソートされる
                        if (period != currentDecodePeriod) {
                            currentDecodePeriod = period
                            currentPeriodSeqNo  = msgSeqNo++
                        }
                        rxMsgs.add(item.copy(seqNo = currentPeriodSeqNo))
                        if (rxMsgs.size > 200) rxMsgs.subList(0, rxMsgs.size - 200).clear()
                        adapter.notifyDataSetChanged()
                        _binding?.rvMsgs?.scrollToPosition(adapter.itemCount - 1)
                    }
                }
                "decode_done" -> {
                    // ピリオド終了: ステータス更新 + ウォーターフォール横線
                    val period  = obj.optInt("period", 0)
                    val utcSec  = obj.optInt("utc_sec", 0)
                    val count   = obj.optInt("count", 0)
                    lifecycleScope.launch(Dispatchers.Main) {
                        // period番号は0-3でサイクルするため、decode_done後にリセットして
                        // 次ピリオドの最初のdecode_msgが必ず新seqNoを取得するようにする
                        currentDecodePeriod = -1
                        _binding?.tvStatus?.text = "P$period | ${utcSec}s | $count decoded"
                        if (markedUtcSecs.add(utcSec)) {
                            if (markedUtcSecs.size > 8) markedUtcSecs.clear()
                            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                            val hhmm = "%02d:%02d".format(
                                cal.get(java.util.Calendar.HOUR_OF_DAY),
                                cal.get(java.util.Calendar.MINUTE))
                            _binding?.waterfallView?.addPeriodMarker("$hhmm P$period")
                        }
                    }
                }
                // ─── 旧バッチ形式 (v3.00 以前の Pi との互換) ────────────────
                "decode" -> {
                    val period  = obj.optInt("period")
                    val utcSec  = obj.optInt("utc_sec")
                    val msgs    = obj.optJSONArray("msgs") ?: return
                    val rxEpochMs = utcSecToEpochMs(utcSec)
                    val rawItems = (0 until msgs.length()).map { i ->
                        val m = msgs.getJSONObject(i)
                        Ft8Msg(m.optInt("freq"), m.optInt("snr"), m.optDouble("dt"),
                               m.optString("msg"), utcSec, period,
                               epochMs = rxEpochMs)
                    }
                    lifecycleScope.launch(Dispatchers.Main) {
                        val batchSeq = msgSeqNo++
                        val newItems = rawItems.map { it.copy(seqNo = batchSeq) }
                        rxMsgs.addAll(newItems)
                        if (rxMsgs.size > 200) rxMsgs.subList(0, rxMsgs.size - 200).clear()
                        adapter.notifyDataSetChanged()
                        _binding?.rvMsgs?.scrollToPosition(adapter.itemCount - 1)
                        _binding?.tvStatus?.text = "P$period | ${utcSec}s | ${newItems.size} decoded"
                        if (markedUtcSecs.add(utcSec)) {
                            if (markedUtcSecs.size > 8) markedUtcSecs.clear()
                            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                            val hhmm = "%02d:%02d".format(
                                cal.get(java.util.Calendar.HOUR_OF_DAY),
                                cal.get(java.util.Calendar.MINUTE))
                            _binding?.waterfallView?.addPeriodMarker("$hhmm P$period")
                        }
                    }
                }
                "tx_pending" -> {
                    val txMsg   = obj.optString("msg")
                    val utcAt   = obj.optInt("utc_at_tx")
                    val txPeriodSec = if (isFt4) utcAt else ((utcAt + 7) / 15) * 15 % 60
                    val txEpochMs = utcSecToEpochMs(txPeriodSec)
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (txMsg.isNotEmpty()) {
                            txMsgs.add(Ft8Msg(0, 0, 0.0, txMsg, txPeriodSec, -1, isTx = true,
                                              epochMs = txEpochMs, seqNo = msgSeqNo++))
                            if (txMsgs.size > 20) txMsgs.subList(0, txMsgs.size - 20).clear()
                            adapter.notifyDataSetChanged()
                            _binding?.rvMsgs?.scrollToPosition(adapter.itemCount - 1)
                            pendingTxEpochMs = txEpochMs
                        }
                        _binding?.tvStatus?.text = "TX▶ $txMsg"
                    }
                }
                "auto_tx_sent" -> {
                    val msg = obj.optString("msg")
                    lifecycleScope.launch(Dispatchers.Main) {
                        pendingTxEpochMs = -1L  // 送信確定
                        _binding?.tvStatus?.text = "AutoTX done: $msg"
                    }
                }
                "auto_tx_error" -> {
                    val err = obj.optString("error")
                    lifecycleScope.launch(Dispatchers.Main) {
                        removePendingTx()
                        _binding?.tvStatus?.text = "AutoTX error: $err"
                        Toast.makeText(requireContext(), "AutoTX Error: $err", Toast.LENGTH_LONG).show()
                    }
                }
                "tx_scheduled" -> {
                    val txMsg   = obj.optString("msg")
                    val waitSec = obj.optDouble("wait_sec")
                    val utcAt   = obj.optInt("utc_at_tx")
                    val txPeriodSec = if (isFt4) utcAt else ((utcAt + 7) / 15) * 15 % 60
                    lifecycleScope.launch(Dispatchers.Main) {
                        _binding?.tvStatus?.text =
                            "TX waiting $txMsg  (in ${waitSec.toInt()}s → UTC ${txPeriodSec}s)"
                    }
                }
                "period_start" -> {
                    val utcSecPs = obj.optInt("utc_sec")
                    lifecycleScope.launch(Dispatchers.Main) {
                        markedUtcSecs.add(utcSecPs)
                        if (markedUtcSecs.size > 8) markedUtcSecs.clear()
                    }
                }
                "qso_state" -> {
                    val state   = obj.optString("state")
                    val msg     = obj.optString("msg")
                    val dx      = obj.optString("dx_call")
                    val snr     = obj.optString("snr")
                    val txMode  = obj.optString("tx_mode")
                    val snrRcvd = obj.optString("snr_rcvd")
                    val snrSent = obj.optString("snr_sent")
                    val myCall  = obj.optString("my_call").ifEmpty { vm.prefs.ft8MyCall }
                    lifecycleScope.launch(Dispatchers.Main) {
                        _binding?.tvStatus?.text = when (state) {
                            "pending"     -> "QSO pending[$dx]: $msg"
                            "tx_start"    -> "TX: $msg"
                            "got_report"  -> "Report $snr rcvd → $msg"
                            "snr_update"  -> "SNR update → $msg"
                            "sending_73"  -> "RR73 rcvd → sending 73: $msg"
                            "retry_rr73"  -> "RR73 retry: $msg"
                            else          -> "QSO: $msg"
                        }
                        if (msg.isNotEmpty()) _binding?.etTxMsg?.setText(msg)
                        // QSO進行中は常にAUTOモード（CQ AUTO中はCQ_AUTOを維持）
                        if (txMode.isNotEmpty()) autoTxMode = txMode
                        if (txState != TxState.AUTO && txState != TxState.CQ_AUTO) {
                            txState = TxState.AUTO
                            updateTxButton()
                            updateEvenOddButton()
                        }
                        // RR73受信時にログ保存
                        if (state == "sending_73" && dx.isNotEmpty() && !qsoLogSaved) {
                            qsoLogSaved = true
                            saveQsoLogEntry(dx, myCall, snrRcvd, snrSent)
                            Toast.makeText(requireContext(), "QSO logged: $dx", Toast.LENGTH_SHORT).show()
                        }
                        // QSOステップ状態をアダプタに反映（自分↔相手の交互進行を表示に反映）
                        val waitFor = when (state) {
                            "pending" -> {
                                // 開始メッセージの内容からどのステップ待ちかを判定
                                val w3 = msg.trim().uppercase().split(Regex("\\s+")).getOrNull(2) ?: ""
                                if (w3.matches(Regex("R[+-]?\\d+")) || w3 == "RR73" || w3 == "73") "waiting" else "snr"
                            }
                            // wait_for フィールドがあればそれを使う（step_snr→state=2 は"snr"を維持）
                            "got_report"  -> obj.optString("wait_for").ifEmpty { "waiting" }
                            "retry_rr73"  -> "waiting"  // RR73再送中、まだRR73待ち
                            "sending_73"  -> "done"     // RR73受信→73送信中
                            else          -> adapter.qsoWaitingFor
                        }
                        adapter.setQsoState(dx, waitFor)
                    }
                }
                "cq_auto_restart" -> {
                    val cqMsg = obj.optString("msg")
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (!cqAutoUserEnabled) return@launch  // user stopped CQ AUTO
                        // CQ AUTO継続: TX欄をCQメッセージに戻してボタン状態維持
                        if (cqMsg.isNotEmpty()) _binding?.etTxMsg?.setText(cqMsg)
                        txState = TxState.CQ_AUTO
                        updateTxButton()
                        qsoLogSaved = false
                        adapter.clearQsoState()
                        _binding?.tvStatus?.text = "CQ AUTO: $cqMsg"
                    }
                }
                "qso_done" -> {
                    val dx      = obj.optString("dx_call")
                    val myCall  = obj.optString("my_call").ifEmpty { vm.prefs.ft8MyCall }
                    val snrRcvd = obj.optString("snr_rcvd")
                    val snrSent = obj.optString("snr_sent")
                    lifecycleScope.launch(Dispatchers.Main) {
                        // CQ AUTO中はcq_auto_restartが来るまでOFFにしない
                        if (txState != TxState.CQ_AUTO) {
                            txState = TxState.OFF
                            updateTxButton()
                        }
                        _binding?.tvStatus?.text = "QSO done! $dx"
                        Toast.makeText(requireContext(), "QSO done: $dx", Toast.LENGTH_SHORT).show()
                        // sending_73 で未保存の場合のみ保存（重複防止）
                        if (!qsoLogSaved) {
                            qsoLogSaved = true
                            saveQsoLogEntry(dx, myCall, snrRcvd, snrSent)
                        }
                        adapter.clearQsoState()
                        // QSO 完了でフィルター解除（Filter ONの時のみ Pi フィルタークリア）
                        if (filterDxFreq > 0) {
                            filterDxFreq = 0
                            adapter.setFilterDxFreq(0)
                            _binding?.waterfallView?.clearFilterBand()
                            lifecycleScope.launch(Dispatchers.IO) {
                                try { vm.api.ft8SetDecodeFilter(emptyList()) } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // --- 未確定TX削除 ---

    private fun removePendingTx() {
        if (pendingTxEpochMs < 0) return
        val ms = pendingTxEpochMs
        pendingTxEpochMs = -1L
        txMsgs.removeAll { it.isTx && it.epochMs == ms }
        adapter.notifyDataSetChanged()
    }

    // --- TX周波数ダイアログ ---

    private fun showTxFreqDialog() {
        val ctx = requireContext()
        val et = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(txFreqHz.toString())
            selectAll()
            setPadding(64, 32, 64, 32)
            textSize = 18f
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("TX Freq (Hz)  100–2900")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                val hz = et.text.toString().toIntOrNull()?.coerceIn(100, 2900) ?: return@setPositiveButton
                txFreqHz = hz
                vm.prefs.ft8AudioFreqHz = hz
                _binding?.btnTxFreq?.text = "TX:$hz"
                _binding?.waterfallView?.setTxFrequency(hz)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- メッセージタップ → QSO開始ダイアログ ---

    // ── POTA/SOTA Spot ────────────────────────────────────────────────────────

    private fun startSpotFetch() {
        spotFetchJob?.cancel()
        spotFetchJob = lifecycleScope.launch {
            while (isActive) {
                try { fetchSpots() } catch (_: Exception) {}
                delay(60_000L)
            }
        }
    }

    private suspend fun fetchSpots() = withContext(Dispatchers.IO) {
        try {
            val body = spotHttp.newCall(
                Request.Builder().url("https://api.pota.app/spot/activator").build()
            ).execute().body?.string() ?: "[]"
            val type = object : TypeToken<List<PotaSpot>>() {}.type
            val spots: List<PotaSpot> = spotGson.fromJson(body, type) ?: emptyList()
            val map = spots.filter { !it.invalid }.groupBy { it.activator.uppercase() }
            withContext(Dispatchers.Main) {
                potaSpotMap = map
                adapter.setSpotMaps(potaSpotMap, sotaSpotMap)
            }
        } catch (_: Exception) {}
        try {
            val rawSotaSpots = SotaCluster.fetchSpots()
                .groupBy { "${it.activatorCallsign ?: ""}|${it.summitCode ?: ""}" }
                .values.map { it.maxByOrNull { s -> s.id }!! }
            val map = rawSotaSpots.groupBy { (it.activatorCallsign ?: "").uppercase() }
                .filterKeys { it.isNotEmpty() }
            withContext(Dispatchers.Main) {
                sotaSpotMap = map
                adapter.setSpotMaps(potaSpotMap, sotaSpotMap)
            }
        } catch (_: Exception) {}
    }

    private fun onMsgShortTap(item: Ft8Msg) {
        val parts = item.msg.trim().uppercase().split(Regex("\\s+"))
            .map { if (it.startsWith("<") && it.endsWith(">")) it.drop(1).dropLast(1) else it }
        // コールサイン2番目（送信局）のみチェック
        val call2 = extractCallsigns(
            if (parts.size >= 3) parts.drop(1).dropLast(1) else parts.drop(1)
        ).firstOrNull() ?: ""
        val callsigns = if (call2.isNotEmpty()) listOf(call2) else emptyList()
        val potaSpots = callsigns.flatMap { potaSpotMap[it] ?: emptyList() }
        val sotaSpots = callsigns.flatMap { sotaSpotMap[it] ?: emptyList() }
        if (potaSpots.isEmpty() && sotaSpots.isEmpty()) {
            onMsgTapped(item)  // スポットなし → 従来のQSOダイアログ
            return
        }
        showSpotInfoDialog(callsigns, potaSpots, sotaSpots, item)
    }

    private fun showSpotInfoDialog(
        callsigns: List<String>,
        potaSpots: List<PotaSpot>,
        sotaSpots: List<SotaSpot>,
        item: Ft8Msg
    ) {
        val ctx = requireContext()
        val sb = StringBuilder()
        if (potaSpots.isNotEmpty()) {
            sb.append("[POTA]\n")
            potaSpots.forEach { s ->
                sb.append("  ${s.activator}  ${s.reference}\n")
                if (s.name.isNotEmpty()) sb.append("  ${s.name}\n")
                sb.append("  ${"%.3f".format(s.freqMhz)} MHz  ${s.mode}")
                if (s.comments.isNotEmpty()) sb.append("  ${s.comments}")
                sb.append("\n  ${s.spotTime.take(16)}\n\n")
            }
        }
        if (sotaSpots.isNotEmpty()) {
            sb.append("[SOTA]\n")
            sotaSpots.forEach { s ->
                sb.append("  ${s.activatorCallsign}  ${s.summitCode.orEmpty()}\n")
                if (!s.displayName.isNullOrEmpty()) sb.append("  ${s.displayName}\n")
                sb.append("  ${"%.3f".format(s.freqMhz)} MHz  ${s.mode.orEmpty()}")
                if (!s.comments.isNullOrEmpty()) sb.append("  ${s.comments}")
                sb.append("\n  ${s.timeStamp?.take(16).orEmpty()}\n\n")
            }
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle(callsigns.firstOrNull() ?: "Spot Info")
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("Start QSO") { _, _ -> onMsgTapped(item) }
            .setNeutralButton("Close", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun extractCallsigns(parts: List<String>): List<String> {
        val keywords = setOf("CQ", "DX", "RR73", "RRR", "73", "DE", "QRZ", "TU", "TNX")
        return parts.map { t ->
            // <CALL> 形式（ハッシュ複合コールサイン）のアングルブラケットを除去
            if (t.startsWith("<") && t.endsWith(">")) t.drop(1).dropLast(1) else t
        }.filter { t ->
            t.length in 3..13 &&
            t[0].isLetterOrDigit() &&
            !keywords.contains(t) &&
            !t.matches(Regex("[+-]\\d{1,3}")) &&               // SNR: -05, +03
            !t.matches(Regex("R[+-]\\d{1,3}")) &&              // レポート: R-05, R+03
            !t.matches(Regex("[A-Z]{2}\\d{2}([A-Z]{2})?")) && // グリッド: PM96
            t.any { c -> c.isDigit() } &&                     // 数字を含む（必須）
            t.any { c -> c.isLetter() }                       // 文字を含む（必須）
        }
    }

    private fun onMsgTapped(item: Ft8Msg) {
        val myCall = vm.prefs.ft8MyCall.ifEmpty { return }
        val myGrid = vm.prefs.ft8MyGrid
        val parts  = item.msg.trim().uppercase().split(Regex("\\s+"))

        // 最後のトークン（レポート/グリッド）を除いてコールサインを抽出（自分のコールは除外）
        val searchParts = if (parts.size >= 3) parts.dropLast(1) else parts
        val candidates = extractCallsigns(searchParts).filter { it != myCall.uppercase() }
        if (candidates.isEmpty()) return

        // Step2に使うSNR = 自分がDX局を測定した値 (item.snr)
        val mySnrOfDx = if (item.snr >= 0) "+${item.snr}" else "${item.snr}"

        val dxIsEven      = if (isFt4) item.utcSec % 15 == 0 else (item.utcSec % 30) == 0
        val defaultTxMode = if (dxIsEven) "odd" else "even"

        showQsoStartDialog(candidates, myCall, myGrid, defaultTxMode, mySnrOfDx, item.freq)
    }

    private fun showQsoStartDialog(
        candidates: List<String>,
        myCall: String,
        myGrid: String,
        defaultTxMode: String,
        snrFromMsg: String,
        dxFreqHz: Int = 0
    ) {
        val ctx = requireContext()
        var selectedDx = candidates.last()

        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
        }
        fun lbl(text: String) = android.widget.TextView(ctx).apply {
            this.text = text; textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 14, 0, 2)
        }
        fun makeEt() = android.widget.EditText(ctx).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            filters = arrayOf(android.text.InputFilter.AllCaps())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }

        val etMsg1    = makeEt()  // Step1: DX MY GRID
        val etMsgSnr  = makeEt()  // Step2: DX MY SNR (Rなし)
        val etMsg2    = makeEt()  // Step3: DX MY R-SNR
        val etMsg3    = makeEt()  // Step4: DX MY RR73
        val etMsg4    = makeEt()  // Step5: DX MY 73

        fun updateMsgs(dx: String) {
            etMsg1.setText("$dx $myCall $myGrid")
            etMsgSnr.setText("$dx $myCall $snrFromMsg".trim())
            etMsg2.setText("$dx $myCall R$snrFromMsg".trim())
            etMsg3.setText("$dx $myCall RR73")
            etMsg4.setText("$dx $myCall 73")
        }
        updateMsgs(selectedDx)

        // コールサインが複数ある場合のみ選択ラジオを表示
        if (candidates.size > 1) {
            layout.addView(lbl("DX Station"))
            val rgDx = android.widget.RadioGroup(ctx)
            candidates.forEachIndexed { idx, call ->
                val rb = android.widget.RadioButton(ctx).apply { text = call; id = idx + 100 }
                rgDx.addView(rb)
                if (idx == candidates.size - 1) rb.isChecked = true
            }
            rgDx.setOnCheckedChangeListener { _, checkedId ->
                selectedDx = candidates[checkedId - 100]
                updateMsgs(selectedDx)
            }
            layout.addView(rgDx)
        }

        layout.addView(lbl("Step1: First call (grid)"))
        layout.addView(etMsg1)
        layout.addView(lbl("Step2: SNR only (no R — auto-filled from DX signal)"))
        layout.addView(etMsgSnr)
        layout.addView(lbl("Step3: Signal report (R+SNR)"))
        layout.addView(etMsg2)
        layout.addView(lbl("Step4: RR73"))
        layout.addView(etMsg3)
        layout.addView(lbl("Step5: 73 (final)"))
        layout.addView(etMsg4)

        val rbStep1    = android.widget.RadioButton(ctx).apply { id = 201; text = "From Step1 (grid)" }
        val rbStep2snr = android.widget.RadioButton(ctx).apply { id = 205; text = "From Step2 (SNR)" }
        val rbStep2    = android.widget.RadioButton(ctx).apply { id = 202; text = "From Step3 (R-SNR)" }
        val rbStep3    = android.widget.RadioButton(ctx).apply { id = 203; text = "From Step4 (RR73)" }
        val rbStep4    = android.widget.RadioButton(ctx).apply { id = 204; text = "From Step5 (73)" }
        val rgStep     = android.widget.RadioGroup(ctx).apply {
            addView(rbStep1); addView(rbStep2snr); addView(rbStep2); addView(rbStep3); addView(rbStep4)
        }
        rbStep1.isChecked = true

        layout.addView(lbl("Start step"))
        layout.addView(rgStep)

        layout.addView(lbl("TX Period"))
        val rbEven = android.widget.RadioButton(ctx).apply {
            id = 301; text = "Even (0/30s)"
        }
        val rbOdd  = android.widget.RadioButton(ctx).apply {
            id = 302; text = "Odd (15/45s)"
        }
        val rgPeriod = android.widget.RadioGroup(ctx).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            addView(rbEven); addView(rbOdd)
        }
        if (defaultTxMode == "odd") rbOdd.isChecked = true else rbEven.isChecked = true
        layout.addView(rgPeriod)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("Start QSO")
            .setView(layout)
            .setNeutralButton("Log QSO") { _, _ ->
                // 手動でQSOログを残す（TX不要）
                saveQsoLogEntry(
                    dxCall  = selectedDx,
                    myCall  = myCall,
                    snrRcvd = snrFromMsg,   // 自局のDX受信SNR
                    snrSent = etMsg2.text.toString().trim().uppercase()
                        .split(Regex("\\s+")).getOrNull(2) ?: ""
                )
                Toast.makeText(requireContext(), "Logged: $selectedDx", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Start TX") { _, _ ->
                val m1   = etMsg1.text.toString().trim().uppercase()
                val mSnr = etMsgSnr.text.toString().trim().uppercase()
                val m2   = etMsg2.text.toString().trim().uppercase()
                val m3   = etMsg3.text.toString().trim().uppercase()
                val m4   = etMsg4.text.toString().trim().uppercase()
                val initState = when {
                    rbStep4.isChecked    -> 6
                    rbStep3.isChecked    -> 4
                    rbStep2.isChecked    -> 2
                    rbStep2snr.isChecked -> 1
                    else -> 0
                }
                val firstMsg = when (initState) {
                    6    -> m4
                    4    -> m3
                    2    -> m2
                    1    -> mSnr
                    else -> m1
                }
                val selectedTxMode = if (rbOdd.isChecked) "odd" else "even"

                autoTxMode = selectedTxMode
                txState = TxState.AUTO
                qsoLogSaved = false
                updateTxButton()
                updateEvenOddButton()
                binding.tvStatus.text = "QSO start: $selectedDx"
                binding.etTxMsg.setText(firstMsg)

                // Pi デコードフィルター: Filter スイッチONの時のみ有効
                // 3000 Hz 超は音声帯域外でプリフィルターが機能しないため設定しない
                if (filterEnabled && dxFreqHz in 100..3000) {
                    filterDxFreq = dxFreqHz
                    adapter.setFilterDxFreq(dxFreqHz)
                    _binding?.waterfallView?.setFilterBand(dxFreqHz, 150)
                    lifecycleScope.launch(Dispatchers.IO) {
                        try { vm.api.ft8SetDecodeFilter(listOf(dxFreqHz), 150) } catch (_: Exception) {}
                    }
                }

                if (isLocalMode) {
                    localEngine?.startQso(
                        dxCall = selectedDx, firstMsg = firstMsg,
                        txMode = selectedTxMode, audioFreqHz = txFreqHz,
                        initialState = initState
                    )
                } else {
                    lifecycleScope.launch(Dispatchers.IO) {
                        vm.api.ft8StartQso(
                            firstMsg = m1, dxCall = selectedDx, myCall = myCall,
                            txMode = selectedTxMode, audioFreqHz = txFreqHz,
                            initialState = initState, stepSnrMsg = mSnr,
                            step2Msg = m2, step3Msg = m3, step4Msg = m4
                        )
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- ボタン ---

    @SuppressLint("MissingPermission")
    private fun setupButtons() {
        // prefs から前回の設定を復元
        decodeDepth = vm.prefs.ft8DecodeDepth
        autoTxMode  = vm.prefs.ft8AutoTxMode
        ft8FontSp   = vm.prefs.ft8FontSize.toFloat()
        localEngine?.rxGain = vm.prefs.ft8RxGain
        txState = TxState.OFF
        updateTxButton()
        updateEvenOddButton()

        binding.btnBack.setOnClickListener {
            if (preFt8Mode.isNotEmpty()) vm.sendMode(preFt8Mode, preFt8Width)
            if (preFt8Freq > 0L) vm.sendFreq(preFt8Freq)
            if (preFt8Freq > 0L) vm.sharedFreq.value = preFt8Freq
            if (preFt8Mode.isNotEmpty()) vm.sharedMode.value = preFt8Mode
            findNavController().popBackStack()
        }

        binding.btnFt4Toggle.setOnClickListener {
            isFt4 = !isFt4; vm.prefs.ft8IsFt4 = isFt4; applyFt4Tint()
            if (localEngine != null) {
                localEngine?.setFt4Mode(isFt4)
            } else {
                // WiFi/Pi mode: notify Pi to switch decode period
                val ft4 = isFt4
                lifecycleScope.launch(Dispatchers.IO) {
                    try { vm.api.ft8SetFt4(ft4) } catch (_: Exception) {}
                }
            }
        }

        // Normalize legacy "USB-D" stored value to internal "PKTUSB"
        if (vm.prefs.ft8TxMode == "USB-D") vm.prefs.ft8TxMode = "PKTUSB"
        binding.btnMode.text = vm.prefs.ft8TxMode
        binding.btnMode.setOnClickListener {
            val allModes = vm.supportedModes.value ?: emptyList()
            val cycleOptions = allModes.filter { "usb" in it.lowercase() }
                .takeIf { it.isNotEmpty() } ?: listOf("USB")
            val cur = vm.prefs.ft8TxMode
            val idx = cycleOptions.indexOfFirst { it == cur }.takeIf { it >= 0 } ?: 0
            val next = cycleOptions[(idx + 1) % cycleOptions.size]
            vm.prefs.ft8TxMode = next; binding.btnMode.text = next
            vm.sendMode(next, 3000)
        }

        val powerSteps = listOf(0.05f,0.10f,0.20f,0.30f,0.40f,0.50f,0.60f,0.70f,0.80f,0.90f,1.00f)
        val powerLabels = powerSteps.map { "${(it * 100).toInt()}%" }.toTypedArray()
        binding.btnPower.setOnClickListener {
            val current = vm.sharedPower.value ?: 0f
            val idx = powerSteps.indexOfFirst { kotlin.math.abs(it - current) < 0.03f }.coerceAtLeast(0)
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Power")
                .setSingleChoiceItems(powerLabels, idx) { dialog, which ->
                    vm.sendPower(powerSteps[which])
                    dialog.dismiss()
                }.setNegativeButton("Cancel", null).show()
        }

        binding.btnGl.setOnClickListener { showPskReporter() }
        binding.btnGl.setOnLongClickListener {
            val ok = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .any { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }
            if (ok) applyGlFromGps()
            else locationPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            true
        }

        binding.btnLog.setOnClickListener { showQsoLog() }

        binding.btnCqOnly.setOnCheckedChangeListener { _, checked ->
            filterEnabled = checked
            vm.prefs.ft8Filter = checked
            adapter.setFilter(checked)
            if (!checked) {
                adapter.setFilterDxFreq(0)
                binding.waterfallView.clearFilterBand()
                lifecycleScope.launch(Dispatchers.IO) {
                    try { vm.api.ft8SetDecodeFilter(emptyList()) } catch (_: Exception) {}
                }
            } else if (filterDxFreq > 0) {
                val freq = filterDxFreq
                adapter.setFilterDxFreq(freq)
                binding.waterfallView.setFilterBand(freq, 150)
                lifecycleScope.launch(Dispatchers.IO) {
                    try { vm.api.ft8SetDecodeFilter(listOf(freq), 150) } catch (_: Exception) {}
                }
            }
        }

        // TX ボタン: グレー=OFF, 赤=AUTO(QSO自動), オレンジ=CQ AUTO(CQ→自動QSO→CQ)
        // 短押し: AUTO/CQ_AUTO → OFF
        // 長押し: OFF → CQ AUTO
        binding.btnTx.setOnClickListener {
            when (txState) {
                TxState.AUTO -> {
                    txState = TxState.OFF
                    updateTxButton()
                    removePendingTx()
                    if (isLocalMode) {
                        localEngine?.setAutoTx(false, autoTxMode, "", txFreqHz)
                    } else {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try { vm.api.ft8SetAutoTx(false, autoTxMode, "", txFreqHz) }
                            catch (_: Exception) {}
                        }
                        if (filterDxFreq > 0) {
                            filterDxFreq = 0
                            adapter.setFilterDxFreq(0)
                            binding.waterfallView.clearFilterBand()
                            lifecycleScope.launch(Dispatchers.IO) {
                                try { vm.api.ft8SetDecodeFilter(emptyList()) } catch (_: Exception) {}
                            }
                        }
                    }
                }
                TxState.CQ_AUTO -> {
                    cqAutoUserEnabled = false
                    txState = TxState.OFF
                    updateTxButton()
                    removePendingTx()
                    if (isLocalMode) {
                        localEngine?.startCqAuto(false, "", autoTxMode, txFreqHz, "", "")
                    } else {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try { vm.api.ft8CqAuto(false, "", autoTxMode, txFreqHz, "", "") }
                            catch (_: Exception) {}
                        }
                    }
                }
                else -> {}
            }
        }
        binding.btnTx.setOnLongClickListener {
            if (txState != TxState.OFF) return@setOnLongClickListener true
            val msg = binding.etTxMsg.text.toString().trim()
            if (msg.isEmpty()) { Toast.makeText(requireContext(), "Enter a message in the TX field", Toast.LENGTH_SHORT).show(); return@setOnLongClickListener true }
            val myCall = vm.prefs.ft8MyCall
            val myGrid = vm.prefs.ft8MyGrid
            if (myCall.isEmpty()) { Toast.makeText(requireContext(), "Callsign not set. Long-press CQ to configure.", Toast.LENGTH_SHORT).show(); return@setOnLongClickListener true }
            cqAutoUserEnabled = true
            txState = TxState.CQ_AUTO
            updateTxButton()
            if (isLocalMode) {
                localEngine?.startCqAuto(true, msg, autoTxMode, txFreqHz, myCall, myGrid)
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    try { vm.api.ft8CqAuto(true, msg, autoTxMode, txFreqHz, myCall, myGrid) }
                    catch (_: Exception) {}
                }
            }
            true
        }

        binding.btnClear.setOnClickListener {
            rxMsgs.clear(); txMsgs.clear(); adapter.notifyDataSetChanged(); binding.tvStatus.text = "---"
        }
        binding.btnClear.setOnLongClickListener {
            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { vm.api.ft8DecodeDebug() }
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("jt9 decode debug")
                    .setMessage(info)
                    .setPositiveButton("OK", null).show()
            }
            true
        }

        // TX周波数ボタン
        txFreqHz = vm.prefs.ft8AudioFreqHz.takeIf { it > 0 } ?: 1500
        binding.btnTxFreq.text = "TX:$txFreqHz"
        binding.waterfallView.setTxFrequency(txFreqHz)
        binding.btnTxFreq.setOnClickListener { showTxFreqDialog() }
        binding.waterfallView.onFrequencySelected = { hz ->
            txFreqHz = hz
            vm.prefs.ft8AudioFreqHz = hz
            _binding?.btnTxFreq?.text = "TX:$hz"
        }

        // Time sync
        binding.btnSync.setOnClickListener { syncPiTime() }

        // CQ quick-fill (long-press = set callsign/grid)
        binding.btnCq.setOnClickListener {
            val myCall = vm.prefs.ft8MyCall
            val myGrid = vm.prefs.ft8MyGrid
            if (myCall.isEmpty()) { showCallsignDialog(); return@setOnClickListener }
            if (myGrid.isEmpty()) {
                Toast.makeText(requireContext(), "グリッドが未設定です（長押しで設定）", Toast.LENGTH_SHORT).show()
                showCallsignDialog()
                return@setOnClickListener
            }
            binding.etTxMsg.setText("CQ $myCall $myGrid")
        }
        binding.btnCq.setOnLongClickListener { showCallsignDialog(); true }

        // Waterfall brightness
        binding.btnWfBrightMinus.setOnClickListener {
            wfBrightness = (wfBrightness - 0.2f).coerceAtLeast(0.2f)
            binding.waterfallView.brightness = wfBrightness
        }
        binding.btnWfBrightPlus.setOnClickListener {
            wfBrightness = (wfBrightness + 0.2f).coerceAtMost(4.0f)
            binding.waterfallView.brightness = wfBrightness
        }
        fun updateRxGainLabel() {
            binding.tvRxGain.text = "×%.1f".format(vm.prefs.ft8RxGain)
        }
        updateRxGainLabel()
        binding.btnRxGainMinus.setOnClickListener {
            val g = (vm.prefs.ft8RxGain - 0.5f).coerceAtLeast(0.5f)
            vm.prefs.ft8RxGain = g
            localEngine?.rxGain = g
            updateRxGainLabel()
        }
        binding.btnRxGainPlus.setOnClickListener {
            val g = (vm.prefs.ft8RxGain + 0.5f).coerceAtMost(8.0f)
            vm.prefs.ft8RxGain = g
            localEngine?.rxGain = g
            updateRxGainLabel()
        }
        var txGainCoarse = false
        fun updateTxGainLabel() {
            binding.tvTxGain.text = "T×%.2f".format(vm.prefs.ft8TxGain)
            binding.btnTxGainMinus.text = if (txGainCoarse) "T--" else "T-"
            binding.btnTxGainPlus.text  = if (txGainCoarse) "T++" else "T+"
        }
        updateTxGainLabel()
        fun applyTxGain(g: Float) {
            vm.prefs.ft8TxGain = g
            if (localEngine != null) {
                localEngine?.txGain = g
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    try { vm.api.ft8SetTxGain(g) } catch (_: Exception) {}
                }
            }
            updateTxGainLabel()
        }
        binding.btnTxGainMinus.setOnClickListener {
            val step = if (txGainCoarse) 0.1f else 0.01f
            val scale = if (txGainCoarse) 10 else 100
            val g = (kotlin.math.round((vm.prefs.ft8TxGain - step) * scale) / scale.toFloat()).coerceAtLeast(0.01f)
            applyTxGain(g)
        }
        binding.btnTxGainMinus.setOnLongClickListener {
            txGainCoarse = !txGainCoarse
            updateTxGainLabel()
            true
        }
        binding.btnTxGainPlus.setOnClickListener {
            val step = if (txGainCoarse) 0.1f else 0.01f
            val scale = if (txGainCoarse) 10 else 100
            val g = (kotlin.math.round((vm.prefs.ft8TxGain + step) * scale) / scale.toFloat()).coerceAtMost(2.0f)
            applyTxGain(g)
        }
        binding.btnTxGainPlus.setOnLongClickListener {
            txGainCoarse = !txGainCoarse
            updateTxGainLabel()
            true
        }

        // Decode depth cycle: 1 -> 2 -> 3 -> 1
        binding.btnDepth.text = "D:$decodeDepth"
        binding.btnDepth.setOnClickListener {
            decodeDepth = when (decodeDepth) { 1 -> 2; 2 -> 3; else -> 1 }
            binding.btnDepth.text = "D:$decodeDepth"
            vm.prefs.ft8DecodeDepth = decodeDepth
            lifecycleScope.launch(Dispatchers.IO) {
                try { vm.api.ft8SetDepth(decodeDepth) } catch (_: Exception) {}
            }
        }

        // Frequency tap → FT8 band selector; long press → memory panel
        binding.tvFreq.setOnClickListener { showFreqDialog() }
        binding.tvFreq.setOnLongClickListener { showFt8MemoryPanel(); true }

        // EVEN/ODD: TX ピリオド選択トグル
        binding.btnAutoTx.setOnClickListener {
            autoTxMode = if (autoTxMode == "even") "odd" else "even"
            vm.prefs.ft8AutoTxMode = autoTxMode
            updateEvenOddButton()
            // Auto TX 実行中はモードを即反映
            if (txState == TxState.AUTO) {
                val msg = binding.etTxMsg.text.toString().trim()
                if (isLocalMode) {
                    localEngine?.setAutoTx(true, autoTxMode, msg, txFreqHz)
                } else {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try { vm.api.ft8SetAutoTx(true, autoTxMode, msg, txFreqHz) }
                        catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun showFt8MemoryPanel() {
        MemoryDialogFragment.newInstance(fromFt8 = true).show(parentFragmentManager, "memory_dialog")
    }

    private fun showFreqDialog() {
        val ft8Labels = arrayOf(
            "160m   1.840 MHz",    "160m   1.908 MHz (JP)",
            " 80m   3.573 MHz",    " 80m   3.531 MHz (JP)",
            " 40m   7.074 MHz",    " 40m   7.041 MHz (JP)",
            " 30m  10.136 MHz",
            " 20m  14.074 MHz",
            " 17m  18.100 MHz",
            " 15m  21.074 MHz",
            " 12m  24.915 MHz",
            " 10m  28.074 MHz",
            "  6m  50.313 MHz",
            "  2m 144.174 MHz",    "  2m 144.460 MHz (JP)",
            " 70c 430.510 MHz (JP)","70c  432.174 MHz"
        )
        val ft8Freqs = longArrayOf(
            1_840_000L,   1_908_000L,
            3_573_000L,   3_531_000L,
            7_074_000L,   7_041_000L,
            10_136_000L,
            14_074_000L,
            18_100_000L,
            21_074_000L,
            24_915_000L,
            28_074_000L,
            50_313_000L,
            144_174_000L, 144_460_000L,
            430_510_000L, 432_174_000L
        )
        val ft4Labels = arrayOf(
            "160m   1.844 MHz",    "160m   1.912 MHz (JP)",
            " 80m   3.575 MHz",    " 80m   3.535 MHz (JP)",
            " 40m   7.078 MHz",    " 40m   7.047 MHz (JP)",
            " 30m  10.140 MHz",
            " 20m  14.080 MHz",
            " 17m  18.104 MHz",
            " 15m  21.140 MHz",
            " 12m  24.919 MHz",
            " 10m  28.180 MHz",
            "  6m  50.318 MHz",
            "  2m 144.170 MHz",    "  2m 144.460 MHz (JP)",
            " 70c 430.510 MHz (JP)","70c  432.174 MHz"
        )
        val ft4Freqs = longArrayOf(
            1_844_000L,   1_912_000L,
            3_575_000L,   3_535_000L,
            7_078_000L,   7_047_000L,
            10_140_000L,
            14_080_000L,
            18_104_000L,
            21_140_000L,
            24_919_000L,
            28_180_000L,
            50_318_000L,
            144_170_000L, 144_460_000L,
            430_510_000L, 432_174_000L
        )
        val labels = if (isFt4) ft4Labels else ft8Labels
        val freqs  = if (isFt4) ft4Freqs  else ft8Freqs
        val current = vm.sharedFreq.value ?: 0L
        val idx = freqs.indexOfFirst { it == current }.coerceAtLeast(-1)
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(if (isFt4) "FT4 Band" else "FT8 Band")
            .setSingleChoiceItems(labels, idx) { dialog, which ->
                val freq = freqs[which]
                vm.prefs.ft8LastFreq = freq
                vm.sendFreq(freq)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun syncPiTime() {
        lifecycleScope.launch {
            _binding?.tvStatus?.text = "Syncing time..."
            val result = withContext(Dispatchers.IO) {
                try { vm.api.syncTime(System.currentTimeMillis()) }
                catch (_: Exception) { null }
            }
            val syncMsg = if (result == null) "Time sync: error"
                          else if (result.ok) "Synced (${result.source} drift=${result.driftMs}ms)"
                          else "Time sync failed"
            _binding?.tvStatus?.text = "$syncMsg — restarting FT8..."
            // Restart decode loop so it recomputes period boundaries with correct clock
            delay(300)
            withContext(Dispatchers.IO) { try { vm.api.ft8Stop() } catch (_: Exception) {} }
            delay(500)
            withContext(Dispatchers.IO) { try { vm.api.ft8Start() } catch (_: Exception) {} }
            _binding?.tvStatus?.text = "$syncMsg — FT8 restarted"
        }
    }

    private fun showCallsignDialog() {
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etCall = android.widget.EditText(requireContext()).apply {
            hint = "Callsign (e.g. JA1XXX)"
            setText(vm.prefs.ft8MyCall)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.AllCaps())
        }
        val etGrid = android.widget.EditText(requireContext()).apply {
            hint = "Grid (e.g. PM85)"
            setText(vm.prefs.ft8MyGrid)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.AllCaps())
        }
        layout.addView(android.widget.TextView(requireContext()).apply {
            text = "Callsign"; textSize = 12f; setPadding(0, 0, 0, 4) })
        layout.addView(etCall)
        layout.addView(android.widget.TextView(requireContext()).apply {
            text = "Grid locator"; textSize = 12f; setPadding(0, 16, 0, 4) })
        layout.addView(etGrid)
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Callsign / Grid Settings")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val call = etCall.text.toString().trim().uppercase()
                val grid = etGrid.text.toString().trim().uppercase()
                vm.prefs.ft8MyCall = call
                vm.prefs.ft8MyGrid = grid
                if (call.isNotEmpty()) {
                    binding.etTxMsg.setText("CQ $call $grid".trim())
                    Toast.makeText(requireContext(), "Saved: $call $grid", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateTxButton() {
        val b = _binding?.btnTx ?: return
        when (txState) {
            TxState.OFF -> {
                b.text = "TX"
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF546E7A.toInt())
                b.setTextColor(0xFFAAAAAA.toInt())
            }
            TxState.ONE_SHOT -> {
                b.text = "TX"
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF546E7A.toInt())
                b.setTextColor(0xFFAAAAAA.toInt())
            }
            TxState.AUTO -> {
                b.text = "AUTO"
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFB71C1C.toInt())
                b.setTextColor(0xFFFFFFFF.toInt())
            }
            TxState.CQ_AUTO -> {
                b.text = "CQ AUTO"
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE65100.toInt())
                b.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    private fun updateEvenOddButton() {
        val b = _binding?.btnAutoTx ?: return
        if (autoTxMode == "even") {
            b.text = "EVEN"
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1A237E.toInt())
            b.setTextColor(0xFF88AAFF.toInt())
        } else {
            b.text = "ODD"
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1A3700.toInt())
            b.setTextColor(0xFF88FF44.toInt())
        }
    }

    private fun setupFt8TxMeters() {
        val dp = resources.displayMetrics.density
        val barHeight = (12 * dp).toInt()
        val margin = (2 * dp).toInt()
        listOf(binding.llFt8PoMeter!!, binding.llFt8AlcMeter!!).forEach { ll ->
            ll.removeAllViews()
            repeat(30) {
                val v = View(requireContext())
                val lp = LinearLayout.LayoutParams(0, barHeight, 1f)
                lp.marginEnd = margin
                v.layoutParams = lp
                ll.addView(v)
            }
        }
    }

    private fun updateFt8TxMeter(ll: LinearLayout, value: Float, activeColor: Int, darkColor: Int) {
        val bars = ll.childCount
        val threshold = (value * bars).toInt()
        for (i in 0 until bars) {
            ll.getChildAt(i).setBackgroundColor(if (i < threshold) activeColor else darkColor)
        }
    }

    private fun updateFt8PoMeter(value: Float) {
        val ll = binding.llFt8PoMeter ?: return
        val color = when {
            value < 0.6f -> 0xFFFF6D00.toInt()
            value < 0.85f -> 0xFFFDD835.toInt()
            else -> 0xFFD50000.toInt()
        }
        updateFt8TxMeter(ll, value, color, 0xFF1A0800.toInt())
        binding.tvFt8PoValue?.text = "${(value * 100).toInt()}%"
    }

    private fun updateFt8AlcMeter(value: Float) {
        val ll = binding.llFt8AlcMeter ?: return
        val color = when {
            value < 0.15f -> 0xFF00C853.toInt()
            value < 0.35f -> 0xFFFDD835.toInt()
            else -> 0xFFD50000.toInt()
        }
        updateFt8TxMeter(ll, value, color, 0xFF001A00.toInt())
        binding.tvFt8AlcValue?.text = "${(value * 100).toInt()}%"
    }

    private fun updateFt8TxMeterVisibility(tx: Boolean) {
        binding.llFt8TxMeters?.visibility = if (tx) View.VISIBLE else View.GONE
        if (!tx) {
            binding.llFt8PoMeter?.let { updateFt8TxMeter(it, 0f, 0, 0xFF1A0800.toInt()) }
            binding.llFt8AlcMeter?.let { updateFt8TxMeter(it, 0f, 0, 0xFF001A00.toInt()) }
            binding.tvFt8PoValue?.text = "0%"
            binding.tvFt8AlcValue?.text = "0%"
        }
    }

    private fun applyFt4Tint() {
        val tint = if (isFt4) 0xFF1565C0.toInt() else 0xFF2E7D32.toInt()
        binding.btnFt4Toggle.backgroundTintList = android.content.res.ColorStateList.valueOf(tint)
        binding.btnFt4Toggle.setTextColor(0xFFFFFFFF.toInt())
        binding.btnFt4Toggle.text = if (isFt4) "FT4" else "FT8"
    }

    private fun updatePowerDisplay(power: Float) { binding.btnPower.text = "PWR ${(power * 100).toInt()}" }
    private fun updateFreqDisplay(freqHz: Long) {
        binding.tvFreq.text = if (freqHz <= 0L) "---.--- MHz" else "%.3f MHz".format(freqHz / 1_000_000.0)
    }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) applyGlFromGps()
        else Toast.makeText(requireContext(), "Location permission denied", Toast.LENGTH_SHORT).show()
    }

    private val recordAudioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            localEngine?.start(vm.prefs.ft8MyCall, vm.prefs.ft8MyGrid)
            _binding?.tvStatus?.text = "CI-V Local FT8"
        } else {
            Toast.makeText(requireContext(), "マイク権限が必要です", Toast.LENGTH_LONG).show()
            findNavController().popBackStack()
        }
    }

    private fun showPskReporter() {
        val myCall = vm.prefs.ft8MyCall
        val freqHz = vm.sharedFreq.value ?: 0L
        val bandMhz = when {
            freqHz in 1_800_000L..2_000_000L   -> 1
            freqHz in 3_500_000L..4_000_000L   -> 3
            freqHz in 7_000_000L..7_300_000L   -> 7
            freqHz in 10_100_000L..10_150_000L -> 10
            freqHz in 14_000_000L..14_350_000L -> 14
            freqHz in 18_068_000L..18_168_000L -> 18
            freqHz in 21_000_000L..21_450_000L -> 21
            freqHz in 24_890_000L..24_990_000L -> 24
            freqHz in 28_000_000L..29_700_000L -> 28
            freqHz in 50_000_000L..54_000_000L -> 50
            freqHz in 144_000_000L..148_000_000L -> 144
            freqHz in 430_000_000L..440_000_000L -> 430
            else -> 0
        }
        val sb = StringBuilder("https://pskreporter.info/pskmap.html?")
        if (myCall.isNotEmpty()) sb.append("callsign=").append(myCall).append("&")
        if (bandMhz > 0) sb.append("band=").append(bandMhz).append("&")
        sb.append("mode=FT8")
        val uri = android.net.Uri.parse(sb.toString())
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
    }

    @SuppressLint("MissingPermission")
    private fun applyGlFromGps() {
        val lm = requireContext().getSystemService(LocationManager::class.java)
        val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .firstNotNullOfOrNull { p -> try { lm?.getLastKnownLocation(p) } catch (_: Exception) { null } }
        if (loc == null) { Toast.makeText(requireContext(), "No GPS fix yet", Toast.LENGTH_SHORT).show(); return }
        val grid = latLonToGrid(loc.latitude, loc.longitude)
        vm.prefs.ft8MyGrid = grid
        Toast.makeText(requireContext(), "MyGrid: $grid", Toast.LENGTH_SHORT).show()
    }

    // --- QSO Log ---

    private fun saveQsoLogEntry(dxCall: String, myCall: String, snrRcvd: String, snrSent: String) {
        if (dxCall.isEmpty() || myCall.isEmpty()) return
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val dt = "%04d%02d%02d%02d%02d%02d".format(
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
        val freqHz = vm.sharedFreq.value ?: 0L
        val mode = if (isFt4) "FT4" else "FT8"
        try {
            val arr = org.json.JSONArray(vm.prefs.ft8QsoLog)
            arr.put(org.json.JSONObject().apply {
                put("dt", dt)
                put("call", dxCall)
                put("freq", freqHz)
                put("mode", mode)
                put("rstS", snrSent.ifEmpty { "-00" })
                put("rstR", snrRcvd.ifEmpty { "-00" })
                put("grid", "")
                put("myCall", myCall)
                put("myGrid", vm.prefs.ft8MyGrid)
            })
            vm.prefs.ft8QsoLog = arr.toString()
        } catch (_: Exception) {}
    }

    private fun freqToBand(hz: Long) = when {
        hz in 1_800_000..2_000_000     -> "160M"; hz in 3_500_000..4_000_000   -> "80M"
        hz in 7_000_000..7_300_000     -> "40M";  hz in 10_100_000..10_150_000 -> "30M"
        hz in 14_000_000..14_350_000   -> "20M";  hz in 18_068_000..18_168_000 -> "17M"
        hz in 21_000_000..21_450_000   -> "15M";  hz in 24_890_000..24_990_000 -> "12M"
        hz in 28_000_000..29_700_000   -> "10M";  hz in 50_000_000..54_000_000 -> "6M"
        hz in 144_000_000..148_000_000 -> "2M";   hz in 420_000_000..450_000_000 -> "70CM"
        else -> ""
    }

    private fun loadQsoLog(): MutableList<QsoLogEntry> = try {
        val arr = org.json.JSONArray(vm.prefs.ft8QsoLog)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            QsoLogEntry(o.optString("dt"), o.optString("call"), o.optLong("freq"),
                o.optString("mode","FT8"), o.optString("rstS"), o.optString("rstR"),
                o.optString("grid"), o.optString("myCall"), o.optString("myGrid"))
        }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    private fun buildAdif(entries: List<QsoLogEntry>): String {
        fun f(tag: String, v: String) = if (v.isNotEmpty()) "<$tag:${v.length}>$v " else ""
        val sb = StringBuilder("ADIF Export from Wifi_RIG_CTRL\n<EOH>\n\n")
        entries.forEach { e ->
            val freq = if (e.freqHz > 0) "%.4f".format(e.freqHz / 1_000_000.0) else ""
            sb.append(f("CALL",e.call)).append(f("MODE",e.mode)).append(f("BAND",freqToBand(e.freqHz)))
            sb.append(f("FREQ",freq)).append(f("QSO_DATE",e.dt.take(8))).append(f("TIME_ON",e.dt.drop(8).take(6)))
            sb.append(f("RST_SENT",e.rstSent)).append(f("RST_RCVD",e.rstRcvd))
            sb.append(f("GRIDSQUARE",e.dxGrid)).append(f("OPERATOR",e.myCall)).append(f("MY_GRIDSQUARE",e.myGrid))
            sb.append("<EOR>\n")
        }
        return sb.toString()
    }

    private fun showQsoLog() {
        val entries = loadQsoLog()
        if (entries.isEmpty()) { Toast.makeText(requireContext(), "QSO log is empty", Toast.LENGTH_SHORT).show(); return }
        val summary = entries.joinToString("\n") { e ->
            val d = e.dt.take(8).let { "${it.take(4)}-${it.drop(4).take(2)}-${it.drop(6)}" }
            val t = e.dt.drop(8).take(6).let { "${it.take(2)}:${it.drop(2).take(2)}Z" }
            "%-8s %-6s %-5s %-4s %-4s %s".format(e.call, freqToBand(e.freqHz).ifEmpty { "${e.freqHz/1000}kHz" },
                e.mode, e.rstSent, e.rstRcvd, "$d $t")
        }
        val scroll = android.widget.ScrollView(requireContext()).apply {
            addView(android.widget.TextView(requireContext()).apply {
                text = summary; textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE; setPadding(16,16,16,16)
            })
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("QSO Log (${entries.size} QSOs)").setView(scroll)
            .setPositiveButton("Export ADIF") { _, _ ->
                val adif = buildAdif(entries)
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                val fname = "qso_%04d%02d%02d.adi".format(
                    cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH)+1,
                    cal.get(java.util.Calendar.DAY_OF_MONTH))
                val file = java.io.File(requireContext().cacheDir, fname); file.writeText(adif)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.provider", file)
                startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        putExtra(android.content.Intent.EXTRA_SUBJECT, fname)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share ADIF"))
            }
            .setNeutralButton("Clear") { _, _ ->
                android.app.AlertDialog.Builder(requireContext()).setMessage("Delete all QSO logs?")
                    .setPositiveButton("Delete") { _, _ ->
                        vm.prefs.ft8QsoLog = "[]"
                        Toast.makeText(requireContext(), "QSO log cleared", Toast.LENGTH_SHORT).show()
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }

    // --- Lifecycle ---

    override fun onResume() {
        super.onResume()
        vm.ft8FragmentActive = true; vm.stopAudio()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val toIdx = vm.selectedTimeoutIndex.value ?: vm.prefs.savedTimeoutIndex
        val timeoutMin = SCREEN_TIMEOUT_OPTIONS.getOrElse(toIdx) { 0 }
        screenTimeoutJob?.cancel()
        if (timeoutMin > 0) {
            screenTimeoutJob = lifecycleScope.launch {
                delay(timeoutMin * 60_000L)
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        vm.ft8FragmentActive = false; screenTimeoutJob?.cancel(); screenTimeoutJob = null
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val freq = vm.sharedFreq.value ?: 0L
        if (freq > 0L) vm.prefs.ft8LastFreq = freq
        vm.prefs.ft8IsFt4 = isFt4
        if (isRemoving) {
            vm.txEnabled.value = false; vm.suppressExternalPttDetection(); vm.releasePttBackground()
        }
    }

    override fun onStop() { super.onStop(); vm.releasePttBackground() }

    override fun onDestroyView() {
        super.onDestroyView()
        sseJob?.cancel(); sseCall?.cancel()
        spectrumJob?.cancel(); spectrumCall?.cancel()
        spotFetchJob?.cancel()

        val wasAutoOrCqAuto = txState == TxState.AUTO || txState == TxState.CQ_AUTO
        txState = TxState.OFF

        if (isLocalMode) {
            localEngine?.listener = null  // prevent in-flight withContext(Main) callbacks from reaching parseSseLine
            localEngine?.stop()
            localEngine = null
        } else {
            if (wasAutoOrCqAuto) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try { vm.api.ft8SetAutoTx(false, autoTxMode, "", vm.prefs.ft8AudioFreqHz) }
                    catch (_: Exception) {}
                }
            }
            lifecycleScope.launch(Dispatchers.IO) {
                try { vm.api.ft8CancelQso() } catch (_: Exception) {}
            }
        }
        _binding = null
    }
}
