package com.example.wifi_rig_ctrl

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.wifi_rig_ctrl.data.MenuItem
import com.example.wifi_rig_ctrl.data.SAMPLING_RATES
import com.example.wifi_rig_ctrl.data.SCREEN_TIMEOUT_OPTIONS
import com.example.wifi_rig_ctrl.data.STEP_LIST
import com.example.wifi_rig_ctrl.databinding.FragmentMainControlBinding
import com.example.wifi_rig_ctrl.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainControlFragment : Fragment() {

    private var _binding: FragmentMainControlBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()
    private var screenTimeoutJob: Job? = null

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.setPttEnabled(true)
        } else {
            Toast.makeText(requireContext(), "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMainControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSMeter()
        setupObservers()
        setupButtons()
        setupVolumeSlider()

        vm.fetchModeList()
        vm.startStatusPolling()
        updateCwUsbStatus()
        binding.tvCwUsbPort.text = vm.prefs.cwPort
    }

    private fun setupSMeter() {
        binding.llSmeter.removeAllViews()
        val dp = resources.displayMetrics.density
        repeat(22) { i ->
            val v = View(requireContext())
            val lp = LinearLayout.LayoutParams(0, (16 * dp).toInt(), 1f)
            lp.marginEnd = (2 * dp).toInt()
            v.layoutParams = lp
            v.tag = i
            binding.llSmeter.addView(v)
        }
    }

    private fun updateSMeter(signal: Float) {
        val bars = binding.llSmeter.childCount
        for (i in 0 until bars) {
            val v = binding.llSmeter.getChildAt(i)
            val color = if (i < signal * 2) {
                when {
                    i <= 16 -> 0xFF2196F3.toInt()  // Blue S1-S8
                    i <= 18 -> 0xFF00BCD4.toInt()  // Cyan S9
                    i <= 20 -> 0xFFFFEB3B.toInt()  // Yellow S9+
                    else    -> 0xFFF44336.toInt()  // Red S9++
                }
            } else 0xFF333333.toInt()
            v.setBackgroundColor(color)
        }
    }

    private fun setupObservers() {
        vm.sharedFreq.observe(viewLifecycleOwner) { freq ->
            val mhz = freq / 1_000_000.0
            binding.tvFreq.text = "%.5f".format(mhz)
        }
        vm.sharedMode.observe(viewLifecycleOwner) { updateInfoRow() }
        vm.sharedPower.observe(viewLifecycleOwner) { updateInfoRow() }
        vm.sharedSQL.observe(viewLifecycleOwner) { updateInfoRow() }
        vm.sharedWidth.observe(viewLifecycleOwner) { updateInfoRow() }
        vm.sharedSignal.observe(viewLifecycleOwner) { updateSMeter(it) }
        vm.sharedModel.observe(viewLifecycleOwner) { binding.tvModel.text = it }
        vm.selectedStep.observe(viewLifecycleOwner) { updateInfoRow() }
        vm.selectedMenuItem.observe(viewLifecycleOwner) { updateButtonHighlights() }
        vm.aprsEnabled.observe(viewLifecycleOwner) { updateButtonHighlights() }
        vm.aprsActive.observe(viewLifecycleOwner) { updateButtonHighlights() }
        vm.spkEnabled.observe(viewLifecycleOwner) { updateButtonHighlights() }
        vm.txEnabled.observe(viewLifecycleOwner) { on ->
            updateButtonHighlights()
            val color = if (on) 0xFFFF0000.toInt() else 0xFFDDDDDD.toInt()
            val textColor = if (on) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            binding.tvTxIndicator.setBackgroundColor(color)
            binding.tvTxIndicator.setTextColor(textColor)
        }
        vm.aprsTxInProgress.observe(viewLifecycleOwner) {
            // Visual feedback handled by txEnabled/tx indicator
        }
        vm.audioError.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                vm.audioError.value = null
                // エラー時はSPKをOFFに戻す
                vm.spkEnabled.value = false
            }
        }

        vm.cwUsbConnected.observe(viewLifecycleOwner) { updateCwUsbStatus() }
        vm.cwUsbEnabled.observe(viewLifecycleOwner) { updateCwUsbStatus() }
        vm.cwServerConnected.observe(viewLifecycleOwner) { updateCwUsbStatus() }
        vm.cwServerSynced.observe(viewLifecycleOwner) { updateCwUsbStatus() }
        vm.cwSidetoneEnabled.observe(viewLifecycleOwner) { updateCwUsbStatus() }
        vm.sharedMode.observe(viewLifecycleOwner) {
            vm.cwUsb.currentMode = it ?: ""
            updateCwUsbStatus()
        }
    }

    private fun updateCwUsbStatus() {
        val connected = vm.cwUsbConnected.value ?: false
        val enabled = vm.cwUsbEnabled.value ?: false
        val mode = vm.sharedMode.value ?: ""
        val isCwMode = mode.contains("CW", ignoreCase = true)
        val svrConnected = vm.cwServerConnected.value ?: false
        val svrSynced = vm.cwServerSynced.value ?: false

        val sidetone = vm.cwSidetoneEnabled.value ?: true
        val sidetoneTag = if (!sidetone && (enabled)) " [Muted]" else ""
        val (statusText, statusColor) = when {
            !connected -> Pair("USB: Not connected", 0xFF555555.toInt())
            !enabled   -> Pair("Connected (idle)", 0xFFFFEB3B.toInt())
            isCwMode   -> Pair("CW relay$sidetoneTag", 0xFF76FF03.toInt())
            else       -> Pair("Audio relay$sidetoneTag", 0xFF40C4FF.toInt())
        }
        binding.tvCwUsbStatus.text = statusText
        binding.tvCwUsbStatus.setTextColor(statusColor)

        val (svrText, svrColor) = when {
            !enabled       -> Pair("Svr:--",      0xFF555555.toInt())
            svrSynced      -> Pair("Svr:Synced",  0xFF76FF03.toInt())
            svrConnected   -> Pair("Svr:Online",  0xFFFFEB3B.toInt())
            else           -> Pair("Svr:Offline", 0xFFFF5252.toInt())
        }
        binding.tvCwServerStatus?.text = svrText
        binding.tvCwServerStatus?.setTextColor(svrColor)
    }

    private fun updateInfoRow() {
        val step = STEP_LIST.getOrElse(vm.selectedStep.value ?: 0) { STEP_LIST[0] }
        binding.tvStep.text = "Step\n${step.label}"
        binding.tvMode.text = "Mode\n${vm.sharedMode.value ?: "-"}"
        binding.tvWidth.text = "Wid\n${vm.sharedWidth.value ?: 0}"
        binding.tvPow.text = "Pow\n${((vm.sharedPower.value ?: 0f) * 100).toInt()}"
        binding.tvSQL.text = "SQL\n${((vm.sharedSQL.value ?: 0f) * 100).toInt()}"
    }

    private fun updateButtonHighlights() {
        val sel = vm.selectedMenuItem.value ?: MenuItem.NONE

        fun tint(color: Int) = ColorStateList.valueOf(color)

        binding.btnFreq.backgroundTintList  = tint(if (sel == MenuItem.FREQ)  0xFF00BCD4.toInt() else 0xFF1565C0.toInt())
        binding.btnStep.backgroundTintList  = tint(if (sel == MenuItem.STEP)  0xFF00BCD4.toInt() else 0xFF1565C0.toInt())
        binding.btnMode.backgroundTintList  = tint(if (sel == MenuItem.MODE)  0xFF00BCD4.toInt() else 0xFF1565C0.toInt())
        binding.btnWidth.backgroundTintList = tint(if (sel == MenuItem.WIDTH) 0xFF00BCD4.toInt() else 0xFF1565C0.toInt())
        binding.btnPow.backgroundTintList   = tint(if (sel == MenuItem.POW)   0xFF00BCD4.toInt() else 0xFF1565C0.toInt())
        binding.btnSQL.backgroundTintList   = tint(if (sel == MenuItem.SQL)   0xFF00BCD4.toInt() else 0xFF1565C0.toInt())

        // APRS button — same color scheme as SPK
        val aprsEnabled = vm.aprsEnabled.value ?: false
        val aprsActive = vm.aprsActive.value ?: false
        val aprsColor = when {
            !aprsEnabled -> 0xFF455A64.toInt()   // disabled: grey
            aprsActive   -> 0xFFAEEA00.toInt()   // active: bright yellow-green (= SPK ON)
            else         -> 0xFF1B5E20.toInt()   // ready: dark green (= SPK OFF)
        }
        binding.btnAprs.backgroundTintList = tint(aprsColor)

        // PTT button
        val txOn = vm.txEnabled.value ?: false
        val pttColor = if (txOn) 0xFFAD1457.toInt() else 0xFF6A1B9A.toInt()
        binding.btnPtt.backgroundTintList = tint(pttColor)

        // SPK button
        val sampIdx = vm.selectedSamplingIndex.value ?: 1
        val sampRate = SAMPLING_RATES.getOrElse(sampIdx) { 0 }
        val spkEnabled = vm.spkEnabled.value ?: false
        val spkColor = when {
            sampRate == 0 -> 0xFF455A64.toInt()
            spkEnabled    -> 0xFFAEEA00.toInt()  // 黄緑
            else          -> 0xFF1B5E20.toInt()
        }
        binding.btnSpk.backgroundTintList = tint(spkColor)

    }

    private fun setupButtons() {
        // Freq → frequency input screen
        binding.tvFreq.setOnClickListener {
            if (vm.txEnabled.value == true) return@setOnClickListener
            findNavController().navigate(R.id.action_MainControlFragment_to_FreqInputFragment)
        }

        // Select menu buttons
        listOf(
            binding.btnFreq to MenuItem.FREQ,
            binding.btnStep to MenuItem.STEP,
            binding.btnMode to MenuItem.MODE,
            binding.btnWidth to MenuItem.WIDTH,
            binding.btnPow to MenuItem.POW,
            binding.btnSQL to MenuItem.SQL
        ).forEach { (btn, item) ->
            btn.setOnClickListener {
                if (vm.txEnabled.value == true && item != MenuItem.NONE) return@setOnClickListener
                vm.selectedMenuItem.value =
                    if (vm.selectedMenuItem.value == item) MenuItem.NONE else item
            }
        }

        // UP / DOWN → adjust selected parameter
        binding.btnUp.setOnClickListener { handleUpDown(+1) }
        binding.btnDown.setOnClickListener { handleUpDown(-1) }

        // PTT toggle
        binding.btnPtt.setOnClickListener {
            val on = !(vm.txEnabled.value ?: false)
            if (on && ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                vm.setPttEnabled(on)
            }
        }

        // SPK toggle
        binding.btnSpk.setOnClickListener {
            if (vm.txEnabled.value == true) return@setOnClickListener  // TX中は操作不可
            val sampIdx = vm.selectedSamplingIndex.value ?: 1
            val sampRate = SAMPLING_RATES.getOrElse(sampIdx) { 0 }
            if (sampRate == 0) {
                Toast.makeText(requireContext(), "Sampling rate=OFF", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val on = !(vm.spkEnabled.value ?: false)
            vm.spkEnabled.value = on
            if (on) vm.startAudio() else vm.stopAudio()
        }

        // APRS: short tap = toggle active, long tap = settings
        binding.btnAprs.setOnClickListener {
            if (vm.aprsEnabled.value != true) {
                Toast.makeText(requireContext(), "APRS disabled (hold to configure)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.toggleAprs()
        }
        binding.btnAprs.setOnLongClickListener {
            findNavController().navigate(R.id.action_MainControlFragment_to_AprsSettingsFragment)
            true
        }

        // BACK → rig select
        binding.btnBack.setOnClickListener {
            vm.disconnectFromRig()
            findNavController().navigate(R.id.action_MainControlFragment_to_RigSelectFragment)
        }

        // CW USB ステータスバー
        // 短タップ: 接続済みなら有効/無効切替、未接続なら USB スキャン
        binding.llCwUsb.setOnClickListener {
            val connected = vm.cwUsbConnected.value ?: false
            if (connected) {
                val enabled = !(vm.cwUsbEnabled.value ?: false)
                vm.setCwUsbEnabled(enabled)
                if (enabled) {
                    val port = binding.tvCwUsbPort.text.toString()
                    vm.prefs.cwPort = port
                    lifecycleScope.launch(Dispatchers.IO) { api.cwOpen(port, 0) }
                } else {
                    lifecycleScope.launch(Dispatchers.IO) { api.cwClose() }
                }
            } else {
                // USB デバイスを手動スキャン
                (activity as? MainActivity)?.scanUsbDevices()
                Toast.makeText(requireContext(), "Scanning USB devices...", Toast.LENGTH_SHORT).show()
            }
        }

        // 長タップ: CW設定メニュー (サイドトーン切替 / Pi ポート選択 / VPN バッファ)
        binding.llCwUsb.setOnLongClickListener {
            val sidetoneLabel = if (vm.cwSidetoneEnabled.value == true)
                "Sidetone: ON → turn OFF" else "Sidetone: OFF → turn ON"
            val bufMs = vm.prefs.cwDelayMs
            val fmMs = vm.prefs.cwFmDelayMs
            val vpnLabel = "CW VPN buffer: ${bufMs}ms"
            val fmLabel = "FM-CW PTT delay: ${fmMs}ms"
            AlertDialog.Builder(requireContext())
                .setTitle("CW USB Settings")
                .setItems(arrayOf(sidetoneLabel, "Select Pi CW Port", vpnLabel, fmLabel)) { _, which ->
                    when (which) {
                        0 -> vm.toggleCwSidetone()
                        1 -> lifecycleScope.launch { showCwPortDialog() }
                        2 -> showCwBufferDialog()
                        3 -> showCwFmDelayDialog()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    private suspend fun showCwPortDialog() {
        val currentPort = binding.tvCwUsbPort.text.toString()
        val serials = withContext(Dispatchers.IO) {
            api.getDevices().first
        }.ifEmpty { listOf("ttyUSB0", "ttyUSB1", "ttyUSB2", "ttyACM0") }

        val items = (serials + "Manual...").toTypedArray()
        var selectedIdx = serials.indexOf(currentPort).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("Pi CW Port")
            .setSingleChoiceItems(items, selectedIdx) { _, which -> selectedIdx = which }
            .setPositiveButton("Set") { _, _ ->
                if (selectedIdx == items.size - 1) {
                    val edit = EditText(requireContext()).apply {
                        setText(currentPort)
                        hint = "e.g. ttyUSB2"
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle("Pi CW Port (manual)")
                        .setView(edit)
                        .setPositiveButton("Set") { _, _ ->
                            val port = edit.text.toString().trim().ifEmpty { currentPort }
                            applyCwPort(port)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    applyCwPort(serials[selectedIdx])
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCwBufferDialog() {
        val measuredMs = vm.cwMeasuredLatencyMs.value ?: 0
        val recommendedMs = if (measuredMs > 0) measuredMs + 200 else 0
        val edit = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(vm.prefs.cwDelayMs.toString())
            hint = "0 (no delay)"
        }
        val measuredNote = if (measuredMs > 0)
            "\n\nMeasured: ${measuredMs}ms late\n→ Recommended: ${recommendedMs}ms"
        else
            "\n\n(No data yet. Key CW to measure latency.)"
        val dlg = AlertDialog.Builder(requireContext())
            .setTitle("VPN Buffer (ms)")
            .setMessage("Sidetone is always instant.\nOnly the CW RF signal is delayed by this value.\n0=instant\nLAN: 50~100ms\nVPN: measured+200ms${measuredNote}")
            .setView(edit)
            .setPositiveButton("Set") { _, _ ->
                val ms = edit.text.toString().toIntOrNull()?.coerceIn(0, 5000) ?: 0
                vm.prefs.cwDelayMs = ms
                vm.cwDelayMs.value = ms
                Toast.makeText(requireContext(), "VPN buffer: ${ms}ms", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
        if (recommendedMs > 0) {
            dlg.setNeutralButton("Recommended ${recommendedMs}ms") { _, _ ->
                vm.prefs.cwDelayMs = recommendedMs
                vm.cwDelayMs.value = recommendedMs
                Toast.makeText(requireContext(), "VPN buffer: ${recommendedMs}ms (recommended)", Toast.LENGTH_SHORT).show()
            }
        }
        dlg.show()
    }

    private fun showCwFmDelayDialog() {
        val edit = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(vm.prefs.cwFmDelayMs.toString())
            hint = "0 (no delay)"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("FM-CW PTT Delay (ms)")
            .setMessage("VPN使用時: 音声がPiに届く前にPTT ONしないよう遅延を設定\n0=instant\nLAN: 0ms\nVPN: 150~300ms")
            .setView(edit)
            .setPositiveButton("Set") { _, _ ->
                val ms = edit.text.toString().toIntOrNull()?.coerceIn(0, 2000) ?: 0
                vm.prefs.cwFmDelayMs = ms
                vm.cwFmDelayMs.value = ms
                Toast.makeText(requireContext(), "FM-CW PTT delay: ${ms}ms", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyCwPort(port: String) {
        binding.tvCwUsbPort.text = port
        vm.prefs.cwPort = port
        if (vm.cwUsbConnected.value == true) {
            lifecycleScope.launch(Dispatchers.IO) { api.cwOpen(port, 0) }
        }
    }

    // Fragment 内で api に直接アクセスするための便利プロパティ
    private val api get() = vm.api

    private fun handleUpDown(dir: Int) {
        val modes = vm.supportedModes.value ?: emptyList()
        when (vm.selectedMenuItem.value ?: MenuItem.NONE) {
            MenuItem.FREQ -> {
                val step = STEP_LIST.getOrElse(vm.selectedStep.value ?: 0) { STEP_LIST[0] }
                val base = vm.sharedFreq.value ?: 0L
                vm.sendFreq(base + dir * step.stepHz)
            }
            MenuItem.STEP -> {
                val cur = vm.selectedStep.value ?: 0
                val next = (cur + dir).coerceIn(0, STEP_LIST.size - 1)
                vm.selectedStep.value = next
                vm.prefs.setModeStep(vm.sharedMode.value ?: "", next)
                updateInfoRow()
            }
            MenuItem.MODE -> {
                if (modes.isEmpty()) return
                val curMode = vm.sharedMode.value ?: ""
                var idx = modes.indexOf(curMode).takeIf { it >= 0 } ?: 0
                idx = ((idx + dir) + modes.size) % modes.size
                val newMode = modes[idx]
                vm.sendMode(newMode, vm.sharedWidth.value ?: 0)
                val savedStep = vm.prefs.getModeStep(newMode)
                vm.selectedStep.value = savedStep
            }
            MenuItem.WIDTH -> {
                // Width values from status; cycle ±100Hz steps if unknown
                val cur = vm.sharedWidth.value ?: 0
                val newW = (cur + dir * 100).coerceAtLeast(0)
                vm.sharedWidth.value = newW
                vm.sendMode(vm.sharedMode.value ?: "", newW)
            }
            MenuItem.POW -> {
                val cur = vm.sharedPower.value ?: 0f
                vm.sendPower((cur + dir * 0.05f).coerceIn(0f, 1f))
            }
            MenuItem.SQL -> {
                val cur = vm.sharedSQL.value ?: 0f
                vm.sendSQL((cur + dir * 0.01f).coerceIn(0f, 1f))
            }
            MenuItem.RVOL -> {
                val cur = vm.sharedVolume.value ?: 0.5f
                vm.sendVolume((cur + dir * 0.05f).coerceIn(0f, 1f))
            }
            else -> {}
        }
    }

    private fun setupVolumeSlider() {
        val vol = ((vm.sharedVolume.value ?: 0.5f) * 100).toInt()
        binding.seekVolume.progress = vol

        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) vm.sendVolume(progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        vm.sharedVolume.observe(viewLifecycleOwner) { v ->
            val progress = (v * 100).toInt()
            if (binding.seekVolume.progress != progress)
                binding.seekVolume.progress = progress
        }
    }

    override fun onResume() {
        super.onResume()
        applyScreenTimeout()
    }

    override fun onPause() {
        super.onPause()
        screenTimeoutJob?.cancel()
        screenTimeoutJob = null
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        // timeoutMin == 0 → "Off" → 画面を消さない
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}