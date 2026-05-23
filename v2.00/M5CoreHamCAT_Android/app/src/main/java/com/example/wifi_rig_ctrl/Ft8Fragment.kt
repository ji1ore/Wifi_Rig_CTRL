package com.ji1ore.wifi_rig_ctrl

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
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
import com.ji1ore.wifi_rig_ctrl.data.Ft8BandEntry
import com.ji1ore.wifi_rig_ctrl.data.Ft8Constants
import com.ji1ore.wifi_rig_ctrl.data.Ft8Message
import com.ji1ore.wifi_rig_ctrl.data.SCREEN_TIMEOUT_OPTIONS
import com.ji1ore.wifi_rig_ctrl.databinding.FragmentFt8Binding
import com.ji1ore.wifi_rig_ctrl.databinding.ItemFt8MessageBinding
import com.ji1ore.wifi_rig_ctrl.viewmodel.MainViewModel
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class QsoState { CQ, ANSWER, REPORT, RRR, SEVENTY3 }

class Ft8Fragment : Fragment() {

    private var _binding: FragmentFt8Binding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    // ── モード / バンド ───────────────────────────────────────────
    private var isFt4     = false
    private var useJa     = false
    private var bandIdx   = 5           // default: 20m
    private var currentFreqMhz = 14.074f
    private var txEnabled = false

    // ── RIG モード / パワー ───────────────────────────────────────
    private var ft8SelectedMode = "PKTUSB"

    // ── 音声周波数 ────────────────────────────────────────────────
    private var rxAudioHz = 1500
    private var txAudioHz = 1500
    private val audioHzStep = 50
    private val audioHzMin  = 200
    private val audioHzMax  = 2800

    // ── QSO ステップ ──────────────────────────────────────────────
    private var qsoState = QsoState.CQ
    private var txMsgIdx = 0

    // ── メッセージ一覧 ────────────────────────────────────────────
    private val messages = mutableListOf<Ft8Message>()
    private lateinit var msgAdapter: Ft8MessageAdapter
    private val maxMessages = 200

    // ── 音声 RX ───────────────────────────────────────────────────
    @Volatile private var decodeActive = false
    private var audioRx: Ft8AudioRx? = null
    private var decodeAttempts = 0
    private var lastDecodeCount = 0
    private var ft8TxInProgress = false
    private var ft8TxJob: Job? = null
    private var piDecodeJob: Job? = null
    private var lastTxCycleNum = -1L   // TX every other 15s cycle (RX one cycle between TXes)

    // ── タイマー / 画面管理 ───────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var screenTimeoutJob: Job? = null
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (_binding != null) updateCycleTimer()
            handler.postDelayed(this, 100)
        }
    }

    // ── パーミッション ────────────────────────────────────────────
    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAudioRx()
        else Toast.makeText(requireContext(), "マイクの許可が必要です", Toast.LENGTH_SHORT).show()
    }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) fetchGpsLocation()
        else Toast.makeText(requireContext(), "位置情報の許可が必要です", Toast.LENGTH_SHORT).show()
    }

    // ── ゲイン ────────────────────────────────────────────────────
    private var rxGain = 1.0f
    private val gainSteps = floatArrayOf(0.1f, 0.2f, 0.3f, 0.5f, 0.7f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 6.0f)
    private var gainIdx = 5  // 1.0x

    // ── SharedPreferences キー ────────────────────────────────────
    private val PREFS_NAME   = "ft8_prefs"
    private val KEY_CUSTOM   = "custom_bands_v1"
    private val KEY_MY_CALL  = "my_call"
    private val KEY_MY_GL    = "my_gl"
    private val KEY_BAND_IDX = "band_idx"
    private val KEY_IS_FT4   = "is_ft4"
    private val KEY_USE_JA   = "use_ja"
    private val KEY_GAIN_IDX = "gain_idx"

    private var customBands  = mutableListOf<Ft8BandEntry>()

    // ── ライフサイクル ────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFt8Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadPrefs()

        msgAdapter = Ft8MessageAdapter(messages) { msg -> onMessageTapped(msg) }
        binding.rvFt8Messages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFt8Messages.adapter = msgAdapter

        setupModeButtons()
        setupBandSpinner()
        setupRigControls()
        setupAudioHz()
        setupCallsignGl()
        setupQsoControls()
        setupTxButton()
        updateQsoStepUI()
        updateTxMsgPreview()

        binding.btnFt8Back.setOnClickListener {
            findNavController().navigate(R.id.action_Ft8Fragment_to_MainControlFragment)
        }

        binding.waterfallFt8.onFreqTapped = { hz ->
            rxAudioHz = hz.coerceIn(audioHzMin, audioHzMax)
            updateAudioHzDisplay()
        }
    }

    override fun onResume() {
        super.onResume()
        decodeActive = true
        handler.post(timerRunnable)
        requestAndStartAudio()
        sendRigFreqAndMode()
        applyScreenTimeout()
    }

    override fun onPause() {
        super.onPause()
        decodeActive = false
        handler.removeCallbacks(timerRunnable)
        stopAudioRx()
        savePrefs()
        screenTimeoutJob?.cancel()
        screenTimeoutJob = null
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── SharedPreferences ─────────────────────────────────────────

    private fun loadPrefs() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json  = prefs.getString(KEY_CUSTOM, "[]")!!
        val type  = object : TypeToken<MutableList<Ft8BandEntry>>() {}.type
        customBands = Gson().fromJson(json, type) ?: mutableListOf()
        binding.editMyCall.setText(prefs.getString(KEY_MY_CALL, ""))
        binding.editMyGl.setText(prefs.getString(KEY_MY_GL, ""))
        isFt4   = prefs.getBoolean(KEY_IS_FT4, false)
        useJa   = prefs.getBoolean(KEY_USE_JA, false)
        bandIdx = prefs.getInt(KEY_BAND_IDX, 5)
        gainIdx = prefs.getInt(KEY_GAIN_IDX, 5).coerceIn(0, gainSteps.size - 1)
        rxGain  = gainSteps[gainIdx]
    }

    private fun savePrefs() {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_CUSTOM, Gson().toJson(customBands))
            putString(KEY_MY_CALL, binding.editMyCall.text.toString().trim().uppercase())
            putString(KEY_MY_GL, binding.editMyGl.text.toString().trim().uppercase())
            putBoolean(KEY_IS_FT4, isFt4)
            putBoolean(KEY_USE_JA, useJa)
            putInt(KEY_BAND_IDX, bandIdx)
            putInt(KEY_GAIN_IDX, gainIdx)
            apply()
        }
    }

    // ── 音声 RX ──────────────────────────────────────────────────

    private fun requestAndStartAudio() {
        // Stream mode (Pi connected) does not use AudioRecord — no mic permission needed
        if (vm.isConnectedToRig.value == true) {
            startAudioRx()
            return
        }
        when {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> startAudioRx()
            else -> audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAudioRx() {
        if (audioRx != null) return
        vm.ft8FragmentActive = true   // CWストリームの二重起動・audio_tx競合を防止
        vm.stopAudio()  // FT8使用中はSPKを停止: Android←→Piの二重音声ストリームによるノイズ防止
        vm.stopCwAudioStream()  // CWトーン(aplay)を停止してFFmpegキャプチャとの競合回避
        Ft8Decoder.load()
        try {
            audioRx = Ft8AudioRx(
                isFt4            = isFt4,
                streamUrl        = vm.api.getAudioFt8Url(),
                getClockOffsetMs = { vm.piClockOffsetMs + vm.ft8ClockAdjMs },
                onRow  = { mags ->
                    // mags は Ft8AudioRx 側で既に 0-3000 Hz にクロップ済み
                    _binding?.waterfallFt8?.addRow(mags)
                },
                onCycleMark = {
                    _binding?.waterfallFt8?.markCycleBoundary()
                },
                onCycle = { _, _ -> },  // Pi側がffmpegから直接キャプチャ・デコード; ポーリングで結果取得
                onError = { msg ->
                    handler.post {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            )
            audioRx!!.gainMultiplier = rxGain
            binding.waterfallFt8.freqMinHz = audioRx!!.displayedFreqMinHz
            binding.waterfallFt8.freqMaxHz = audioRx!!.displayedFreqMaxHz
            audioRx!!.start()
            if (vm.isConnectedToRig.value == true) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { vm.api.ft8StartPiDecode(isFt4) }
                startPiDecodePolling()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "音声RXエラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAudioRx() {
        cancelFt8Tx()
        piDecodeJob?.cancel()
        piDecodeJob = null
        audioRx?.stop()
        audioRx = null
        vm.ft8FragmentActive = false  // CWストリーム管理を再開
        if (vm.isConnectedToRig.value == true) {
            Thread { vm.api.ft8StopPiDecode(); vm.api.ft8Stop() }.start()
        }
        vm.restartCwAudioStreamForCurrentMode()  // FM-D等の場合はCWトーンストリームを再開
        // main画面復帰: FT8が占有していたオーディオを解放し、SPKを復元
        if (vm.spkEnabled.value == true && vm.txEnabled.value != true) {
            vm.startAudio()
        }
    }

    private fun startPiDecodePolling() {
        piDecodeJob?.cancel()
        val pollMs = 3000L
        piDecodeJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(pollMs)
                if (!decodeActive) continue
                val results = vm.api.ft8ResultsDrain()
                if (results.isNotEmpty()) handler.post { appendDecoded(results) }
            }
        }
    }

    // ── FT8 TX ───────────────────────────────────────────────────

    private fun cancelFt8Tx() {
        txEnabled = false
        lastTxCycleNum = -1L
        ft8TxJob?.cancel()
        ft8TxJob = null
        ft8TxInProgress = false
        if (_binding != null) updateTxButtonUI()
    }

    private fun updateTxButtonUI() {
        if (txEnabled) {
            if (ft8TxInProgress) {
                binding.btnFt8TxEnable.text = "TX ING"
                binding.btnFt8TxEnable.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.RED)
                binding.btnFt8TxEnable.setTextColor(Color.WHITE)
            } else {
                binding.btnFt8TxEnable.text = "TX WAIT"
                binding.btnFt8TxEnable.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6600"))
                binding.btnFt8TxEnable.setTextColor(Color.WHITE)
            }
        } else {
            binding.btnFt8TxEnable.text = "TX OFF"
            binding.btnFt8TxEnable.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF333333.toInt())
            binding.btnFt8TxEnable.setTextColor(Color.parseColor("#AAAAAA"))
        }
    }

    private fun performFt8Tx(message: String) {
        if (ft8TxInProgress) return
        ft8TxInProgress = true
        updateTxButtonUI()

        ft8TxJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.tvDecodeStatus.text = "TX: $message"

            var heartbeatJob: Job? = null
            try {
                vm.ft8TxActive = true  // ステータス監視がaudioTxを誤起動しないようにブロック
                // PTT ON
                withContext(Dispatchers.IO) { vm.api.setPtt(true) }
                delay(150L)

                // Heartbeat every 2s keeps Pi's watchdog satisfied during ~12.64s playback
                heartbeatJob = launch(Dispatchers.IO) {
                    while (isActive) {
                        delay(2000L)
                        vm.api.pttHeartbeat()
                    }
                }

                // Pi encodes FT8 audio and plays it (~12.64s), then responds
                withContext(Dispatchers.IO) { vm.api.ft8Tx(message, txAudioHz, 12000, isFt4) }

            } finally {
                heartbeatJob?.cancel()
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    vm.api.setPtt(false)
                }
                vm.ft8TxActive = false
                ft8TxInProgress = false
                if (_binding != null) {
                    binding.tvDecodeStatus.text = "TX完了: $message"
                    if (!txEnabled) updateTxButtonUI()
                }
            }
        }
    }

    private fun appendDecoded(results: List<Ft8Decoder.Result>) {
        if (_binding == null) return
        decodeAttempts++
        lastDecodeCount = results.size
        if (!ft8TxInProgress)
            binding.tvDecodeStatus.text = "#$decodeAttempts:${lastDecodeCount}msg"

        // dt値からタイミング自動補正: dt>0 → ウィンドウが早すぎ → クロックを遅らせる
        if (results.isNotEmpty()) {
            val sortedDt = results.map { it.dt }.sorted()
            val medianDt = sortedDt[sortedDt.size / 2]
            val correction = -(medianDt * 500).toLong()  // 50%ずつ収束させる
            vm.ft8ClockAdjMs = (vm.ft8ClockAdjMs + correction).coerceIn(-8_000L, 8_000L)
            android.util.Log.i("Ft8Timing", "dt_median=${medianDt}s corr=${correction}ms total=${vm.ft8ClockAdjMs}ms")
        }

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = System.currentTimeMillis() + vm.piClockOffsetMs
        val utc = "%02d:%02d:%02d".format(
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND))
        val deduped = results
            .groupBy { it.msg }
            .map { (_, g) -> g.maxByOrNull { it.snr }!! }
        for (r in deduped) {
            val idx = messages.indexOfFirst { it.utcTime == utc && it.message == r.msg }
            if (idx >= 0) {
                if (r.snr.toInt() > messages[idx].snr)
                    messages[idx] = Ft8Message(utc, r.snr.toInt(), r.dt, r.hz, r.msg)
            } else {
                if (messages.size >= maxMessages) messages.removeAt(0)
                messages.add(Ft8Message(utc, r.snr.toInt(), r.dt, r.hz, r.msg))
            }
        }
        msgAdapter.notifyDataSetChanged()
        binding.rvFt8Messages.scrollToPosition(messages.size - 1)
    }

    // ── モード切替 ────────────────────────────────────────────────

    private fun setupModeButtons() {
        binding.btnFt8Mode.setOnClickListener { setMode(false) }
        binding.btnFt4Mode.setOnClickListener { setMode(true) }
        binding.btnRegionJa.setOnClickListener { toggleJa() }
        updateModeUI()
    }

    private fun setMode(ft4: Boolean) {
        isFt4 = ft4
        bandIdx = 0
        stopAudioRx()
        updateModeUI()
        setupBandSpinner()
        startAudioRx()
        sendRigFreqAndMode()
    }

    private fun toggleJa() {
        useJa = !useJa
        bandIdx = 0
        updateModeUI()
        setupBandSpinner()
    }

    private fun updateModeUI() {
        val active   = 0xFF1565C0.toInt()
        val inactive = 0xFF333333.toInt()
        fun btn(b: android.widget.Button, on: Boolean) {
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(if (on) active else inactive)
            b.setTextColor(if (on) Color.WHITE else Color.parseColor("#AAAAAA"))
        }
        btn(binding.btnFt8Mode, !isFt4)
        btn(binding.btnFt4Mode, isFt4)

        val jaActive = 0xFF6A1B9A.toInt()
        binding.btnRegionJa.backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (useJa) jaActive else inactive)
        binding.btnRegionJa.setTextColor(
            if (useJa) Color.parseColor("#CE93D8") else Color.parseColor("#AAAAAA"))
    }

    // ── バンドスピナー + カスタム周波数 ──────────────────────────

    private fun currentBandList(): List<Ft8BandEntry> {
        val base = if (isFt4) {
            if (useJa) Ft8Constants.FT4_BANDS_JA else Ft8Constants.FT4_BANDS_GLOBAL
        } else {
            if (useJa) Ft8Constants.FT8_BANDS_JA else Ft8Constants.FT8_BANDS_GLOBAL
        }
        return base + customBands
    }

    private fun setupBandSpinner() {
        val bands  = currentBandList()
        val labels = bands.map { e ->
            val region = if (e.region.isNotEmpty()) " [${e.region}]" else ""
            "${e.band}$region  ${"%.6f".format(e.freqMhz)}"
        }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBand.adapter = adapter
        binding.spinnerBand.setSelection(bandIdx.coerceIn(0, bands.size - 1))
        binding.spinnerBand.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long
                ) {
                    bandIdx = pos
                    currentFreqMhz = bands[pos].freqMhz
                    updateFreqDisplay()
                    sendRigFreqAndMode()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        currentFreqMhz = bands[bandIdx.coerceIn(0, bands.size - 1)].freqMhz
        updateFreqDisplay()

        // ＋ ボタン: カスタム周波数追加
        binding.btnFreqAdd.setOnClickListener { showAddFreqDialog() }
    }

    private fun showAddFreqDialog() {
        val ctx   = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val dp = resources.displayMetrics.density.toInt()
        val etBand = EditText(ctx).apply {
            hint = "バンド名 (例: 40m JA2)"
            textSize = 14f
        }
        val etFreq = EditText(ctx).apply {
            hint = "周波数 MHz (例: 7.041000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 14f
        }
        layout.addView(etBand, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = 8 * dp })
        layout.addView(etFreq, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        AlertDialog.Builder(ctx)
            .setTitle("カスタム周波数を追加")
            .setView(layout)
            .setPositiveButton("追加") { _, _ ->
                val band = etBand.text.toString().trim()
                val freq = etFreq.text.toString().toFloatOrNull()
                if (band.isEmpty() || freq == null || freq <= 0f) {
                    Toast.makeText(ctx, "入力が無効です", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                customBands.add(Ft8BandEntry(band, freq, "USER"))
                savePrefs()
                bandIdx = currentBandList().size - 1   // 追加したものを選択
                setupBandSpinner()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun updateFreqDisplay() {
        binding.tvFt8Freq.text = "%.6f".format(currentFreqMhz)
    }

    private fun sendRigFreqAndMode() {
        val freqHz = (currentFreqMhz * 1_000_000.0).toLong()
        vm.sendFreq(freqHz)
        vm.sendMode(ft8SelectedMode, 3000)
    }

    // ── RIG モード / パワー ───────────────────────────────────────

    private fun setupRigControls() {
        // FT8に適切なモードセット (USB/PKTUSB/DIGU等)。FM/AM/CWはFT8不可なのでPKTUSBに戻す
        val ft8SuitableModes = setOf("USB", "PKTUSB", "DIGU", "USB-D", "LSB", "PKTLSB", "DIGL")
        val currentMode = vm.sharedMode.value?.takeIf { it.isNotEmpty() } ?: ft8SelectedMode
        ft8SelectedMode = if (currentMode in ft8SuitableModes) currentMode else "PKTUSB"
        binding.btnFt8RigMode.text = ft8SelectedMode

        // Observe radio mode changes — only update if the new mode is FT8-suitable
        vm.sharedMode.observe(viewLifecycleOwner) { mode ->
            if (!mode.isNullOrEmpty() && mode in ft8SuitableModes) {
                ft8SelectedMode = mode
                _binding?.btnFt8RigMode?.text = mode
            }
        }

        // Mode button tap → pick from available modes
        binding.btnFt8RigMode.setOnClickListener {
            val modes = vm.supportedModes.value?.takeIf { it.isNotEmpty() }
                ?: listOf("USB", "PKTUSB", "DIGU", "LSB", "PKTLSB", "FM", "AM", "CW")
            val cur = modes.indexOf(ft8SelectedMode).coerceAtLeast(0)
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("RIG Mode")
                .setSingleChoiceItems(modes.toTypedArray(), cur) { dlg, which ->
                    ft8SelectedMode = modes[which]
                    _binding?.btnFt8RigMode?.text = ft8SelectedMode
                    vm.sendMode(ft8SelectedMode, 3000)
                    dlg.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // POW display: observe radio power
        vm.sharedPower.observe(viewLifecycleOwner) { pow ->
            _binding?.tvFt8Pow?.text = "${(pow * 100).toInt()}%"
        }

        // POW buttons: adjust by 5%
        binding.btnFt8PowDown.setOnClickListener {
            val cur = vm.sharedPower.value ?: 0f
            vm.sendPower((cur - 0.05f).coerceIn(0f, 1f))
        }
        binding.btnFt8PowUp.setOnClickListener {
            val cur = vm.sharedPower.value ?: 0f
            vm.sendPower((cur + 0.05f).coerceIn(0f, 1f))
        }
    }

    private fun applyScreenTimeout() {
        screenTimeoutJob?.cancel()
        val toIdx = vm.selectedTimeoutIndex.value ?: 2
        val timeoutMin = SCREEN_TIMEOUT_OPTIONS.getOrElse(toIdx) { 10 }
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (timeoutMin > 0) {
            screenTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(timeoutMin * 60 * 1000L)
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // ── RX / TX 音声周波数 ────────────────────────────────────────

    private fun setupAudioHz() {
        binding.btnRxHzDown.setOnClickListener {
            rxAudioHz = (rxAudioHz - audioHzStep).coerceAtLeast(audioHzMin); updateAudioHzDisplay()
        }
        binding.btnRxHzUp.setOnClickListener {
            rxAudioHz = (rxAudioHz + audioHzStep).coerceAtMost(audioHzMax); updateAudioHzDisplay()
        }
        binding.btnTxHzDown.setOnClickListener {
            txAudioHz = (txAudioHz - audioHzStep).coerceAtLeast(audioHzMin); updateAudioHzDisplay()
        }
        binding.btnTxHzUp.setOnClickListener {
            txAudioHz = (txAudioHz + audioHzStep).coerceAtMost(audioHzMax); updateAudioHzDisplay()
        }
        binding.btnGainDown?.setOnClickListener {
            gainIdx = (gainIdx - 1).coerceAtLeast(0)
            rxGain = gainSteps[gainIdx]
            audioRx?.gainMultiplier = rxGain
            updateGainDisplay()
        }
        binding.btnGainUp?.setOnClickListener {
            gainIdx = (gainIdx + 1).coerceAtMost(gainSteps.size - 1)
            rxGain = gainSteps[gainIdx]
            audioRx?.gainMultiplier = rxGain
            updateGainDisplay()
        }
        updateAudioHzDisplay()
        updateGainDisplay()
    }

    private fun updateGainDisplay() {
        val g = gainSteps[gainIdx]
        binding.tvGain?.text = if (g < 1f) "%.1fx".format(g) else "%.0fx".format(g)
    }

    private fun updateAudioHzDisplay() {
        binding.tvRxHz.text = rxAudioHz.toString()
        binding.tvTxHz.text = txAudioHz.toString()
        binding.waterfallFt8.rxFreqHz = rxAudioHz
        binding.waterfallFt8.txFreqHz = txAudioHz
    }

    // ── コールサイン / GL / GPS ───────────────────────────────────

    private fun setupCallsignGl() {
        binding.btnGpsGl.setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) fetchGpsLocation()
            else locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchGpsLocation() {
        val lm  = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc != null) {
            binding.editMyGl.setText(latLonToMaidenhead(loc.latitude, loc.longitude))
        } else {
            Toast.makeText(requireContext(), "位置情報を取得できません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun latLonToMaidenhead(lat: Double, lon: Double): String {
        val al = lon + 180.0; val ar = lat + 90.0
        val c1 = 'A' + (al / 20).toInt()
        val c2 = 'A' + (ar / 10).toInt()
        val n1 = ((al % 20) / 2).toInt()
        val n2 = (ar % 10).toInt()
        return "$c1$c2$n1$n2"
    }

    // ── QSO ステップ ──────────────────────────────────────────────

    private fun setupQsoControls() {
        val qsoValues = QsoState.values()
        binding.btnTxMsgPrev.setOnClickListener {
            if (qsoState.ordinal > 0) {
                qsoState = qsoValues[qsoState.ordinal - 1]; txMsgIdx = 0
                updateQsoStepUI()
            }
        }
        binding.btnTxMsgNext.setOnClickListener {
            if (qsoState.ordinal < qsoValues.size - 1) {
                qsoState = qsoValues[qsoState.ordinal + 1]; txMsgIdx = 0
                updateQsoStepUI()
            }
        }
    }

    private fun updateQsoStepUI() {
        data class Step(val tv: android.widget.TextView, val s: QsoState)
        listOf(
            Step(binding.tvQsoStepCq,  QsoState.CQ),
            Step(binding.tvQsoStepAns, QsoState.ANSWER),
            Step(binding.tvQsoStepRpt, QsoState.REPORT),
            Step(binding.tvQsoStepRrr, QsoState.RRR),
            Step(binding.tvQsoStep73,  QsoState.SEVENTY3)
        ).forEach { (tv, s) ->
            when {
                s == qsoState            -> { tv.setBackgroundColor(0xFF1B5E20.toInt()); tv.setTextColor(0xFFAAFFAA.toInt()) }
                s.ordinal < qsoState.ordinal -> { tv.setBackgroundColor(0xFF0D2E0D.toInt()); tv.setTextColor(0xFF448844.toInt()) }
                else                     -> { tv.setBackgroundColor(0xFF222222.toInt()); tv.setTextColor(0xFF555555.toInt()) }
            }
        }
        updateTxMsgPreview()
    }

    private fun updateTxMsgPreview() {
        binding.tvTxMsgPreview.text = buildTxMessage()
    }

    private fun buildTxMessage(): String {
        val my = binding.editMyCall.text.toString().trim().uppercase().ifEmpty { "MYCALL" }
        val dx = binding.editDxCall.text.toString().trim().uppercase().ifEmpty { "???" }
        val gl = binding.editMyGl.text.toString().trim().uppercase().ifEmpty { "GL00" }
        return when (qsoState) {
            QsoState.CQ       -> "CQ $my $gl"
            QsoState.ANSWER   -> "$dx $my +00"
            QsoState.REPORT   -> "$dx $my R-05"
            QsoState.RRR      -> "$dx $my RRR"
            QsoState.SEVENTY3 -> "$dx $my 73"
        }
    }

    private fun onMessageTapped(msg: Ft8Message) {
        val parts = msg.message.trim().split("\\s+".toRegex())
        if (parts.size >= 2 && parts[0] == "CQ" && parts[1] != "DX") {
            binding.editDxCall.setText(parts[1])
        } else if (parts.size >= 2 && !parts[0].startsWith("CQ")) {
            binding.editDxCall.setText(parts[0])
        }
        txAudioHz = msg.freqHz.coerceIn(audioHzMin, audioHzMax)
        updateAudioHzDisplay()
        updateTxMsgPreview()
    }

    // ── TX ボタン ─────────────────────────────────────────────────

    private fun setupTxButton() {
        binding.btnFt8TxEnable.setOnClickListener {
            if (txEnabled) {
                cancelFt8Tx()
            } else {
                txEnabled = true
                updateTxButtonUI()
            }
        }
    }

    // ── UTC サイクルタイマー ──────────────────────────────────────

    private fun updateCycleTimer() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = System.currentTimeMillis() + vm.piClockOffsetMs
        val h   = cal.get(Calendar.HOUR_OF_DAY)
        val m   = cal.get(Calendar.MINUTE)
        val s   = cal.get(Calendar.SECOND)
        val ms  = cal.get(Calendar.MILLISECOND)
        binding.tvFt8Utc.text = "UTC %02d:%02d:%02d".format(h, m, s)

        val cycleMs  = if (isFt4) Ft8Constants.FT4_CYCLE_MS else Ft8Constants.FT8_CYCLE_MS
        val cycleSec = cycleMs / 1000
        val elapsed  = ((s % cycleSec) * 1000 + ms).coerceIn(0, cycleMs)

        binding.progressFt8Cycle.max      = cycleMs
        binding.progressFt8Cycle.progress = elapsed
        binding.tvFt8CycleTime.text = "${elapsed / 1000}/${cycleSec}s"

        // TX: サイクル先頭かつ1サイクルおきに送信（RXサイクルで相手の返答を聴く）
        val totalSec = h * 3600L + m * 60 + s
        val cycleNum = totalSec / cycleSec
        if (txEnabled && !ft8TxInProgress && elapsed < 300 && cycleNum > lastTxCycleNum + 1) {
            lastTxCycleNum = cycleNum
            performFt8Tx(buildTxMessage())
        }

        val showTx = ft8TxInProgress
        binding.tvFt8CycleState.text     = if (showTx) "TX" else "RX"
        binding.tvFt8CycleState.setTextColor(if (showTx) Color.RED else Color.GREEN)
    }
}

// ── RecyclerView Adapter ──────────────────────────────────────────

class Ft8MessageAdapter(
    private val items: List<Ft8Message>,
    private val onTap: (Ft8Message) -> Unit
) : RecyclerView.Adapter<Ft8MessageAdapter.VH>() {

    class VH(val b: ItemFt8MessageBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFt8MessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val msg = items[pos]
        h.b.tvMsgUtc.text  = msg.utcTime
        h.b.tvMsgSnr.text  = "%+3d".format(msg.snr)
        h.b.tvMsgDt.text   = "%+.1f".format(msg.dt)
        h.b.tvMsgHz.text   = "${msg.freqHz}"
        h.b.tvMsgText.text = msg.message
        h.b.tvMsgText.setTextColor(if (msg.message.startsWith("CQ")) Color.YELLOW else Color.WHITE)
        h.b.root.setOnClickListener { onTap(msg) }
    }
}
