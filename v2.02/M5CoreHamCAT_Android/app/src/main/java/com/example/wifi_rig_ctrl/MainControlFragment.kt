package com.ji1ore.wifi_rig_ctrl

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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ji1ore.wifi_rig_ctrl.data.MenuItem
import com.ji1ore.wifi_rig_ctrl.data.SAMPLING_RATES
import com.ji1ore.wifi_rig_ctrl.data.SCREEN_TIMEOUT_OPTIONS
import com.ji1ore.wifi_rig_ctrl.data.STEP_LIST
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ji1ore.wifi_rig_ctrl.databinding.FragmentMainControlBinding
import com.ji1ore.wifi_rig_ctrl.viewmodel.MainViewModel
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

    private var cwTxSheet: BottomSheetDialog? = null
    private var cwSheetStopBtn: android.widget.Button? = null
    private var cwSheetMsgButtons: List<android.widget.Button> = emptyList()

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
        setupMicGainSlider()

        vm.fetchModeList()
        vm.startStatusPolling()
        updateCwUsbStatus()
        updateCwTxButton()
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
        vm.sharedBkIn.observe(viewLifecycleOwner) { updateInfoRow(); updateButtonHighlights() }
        vm.sharedWidth.observe(viewLifecycleOwner) { updateInfoRow() }
        vm.sharedSignal.observe(viewLifecycleOwner) { updateSMeter(it) }
        vm.sharedModel.observe(viewLifecycleOwner) { binding.tvModel.text = it }
        vm.selectedStep.observe(viewLifecycleOwner) { updateInfoRow() }
        vm.selectedMenuItem.observe(viewLifecycleOwner) { updateButtonHighlights() }
        vm.aprsEnabled.observe(viewLifecycleOwner) { updateButtonHighlights() }
        vm.aprsActive.observe(viewLifecycleOwner) { updateButtonHighlights() }
        vm.spkEnabled.observe(viewLifecycleOwner) { updateButtonHighlights() }
        vm.txEnabled.observe(viewLifecycleOwner) { _ ->
            updateButtonHighlights()
            updateTxIndicator()
        }
        vm.aprsTxInProgress.observe(viewLifecycleOwner) { _ ->
            updateTxIndicator()
        }
        vm.audioError.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                vm.audioError.value = null
                // On error, turn SPK back OFF
                vm.spkEnabled.value = false
            }
        }
        vm.errorMessage.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                vm.errorMessage.value = null
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

        vm.cwDecoding.observe(viewLifecycleOwner) { decoding ->
            binding.llCwDecoder.visibility = if (decoding) View.VISIBLE else View.GONE
            updateButtonHighlights()
        }
        vm.cwTxText.observe(viewLifecycleOwner) { text ->
            binding.tvCwTx.text = text
            binding.hsvCwTx.post { binding.hsvCwTx.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }
        }
        val cwRxTextViews = listOf(binding.tvCwRx0, binding.tvCwRx1, binding.tvCwRx2, binding.tvCwRx3, binding.tvCwRx4)
        val cwRxScrollViews = listOf(binding.hsvCwRx0, binding.hsvCwRx1, binding.hsvCwRx2, binding.hsvCwRx3, binding.hsvCwRx4)
        val cwFreqTextViews = listOf(binding.tvCwFreq0, binding.tvCwFreq1, binding.tvCwFreq2, binding.tvCwFreq3, binding.tvCwFreq4)
        for (i in cwRxTextViews.indices) {
            val hsv = cwRxScrollViews[i]
            vm.cwRxTexts[i].observe(viewLifecycleOwner) { text ->
                cwRxTextViews[i].text = text
                hsv.post { hsv.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }
            }
            vm.cwRxFreqLabels[i].observe(viewLifecycleOwner) { label ->
                cwFreqTextViews[i].text = label
            }
        }
        vm.cwMultiRx.observe(viewLifecycleOwner) { multi ->
            val vis = if (multi) View.VISIBLE else View.GONE
            for (i in 1 until cwRxScrollViews.size) {
                cwRxScrollViews[i].visibility = vis
                cwFreqTextViews[i].visibility = vis
            }
        }
        vm.cwTxBusy.observe(viewLifecycleOwner) { updateCwUsbStatus(); updateCwTxButton(); updateCwTxSheetState() }
        vm.sharedMode.observe(viewLifecycleOwner) { updateCwTxButton() }
    }

    private fun updateCwUsbStatus() {
        val connected = vm.cwUsbConnected.value ?: false
        val enabled = vm.cwUsbEnabled.value ?: false
        val mode = vm.sharedMode.value ?: ""
        val isCwMode = mode.contains("CW", ignoreCase = true)
        val svrConnected = vm.cwServerConnected.value ?: false
        val svrSynced = vm.cwServerSynced.value ?: false
        val cwTxBusy = vm.cwTxBusy.value ?: false

        val sidetone = vm.cwSidetoneEnabled.value ?: true
        val sidetoneTag = if (!sidetone && (enabled)) " [Muted]" else ""
        val (statusText, statusColor) = when {
            cwTxBusy   -> Pair("CW Sending... (tap to stop)", 0xFFFF6600.toInt())
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
        val bkOn = vm.sharedBkIn.value ?: false
        binding.tvBkIn.text = "BK-IN\n${if (bkOn) "ON" else "OFF"}"
        binding.tvBkIn.setTextColor(if (bkOn) 0xFF00E676.toInt() else 0xFF666666.toInt())
    }

    private fun updateTxIndicator() {
        val tx = vm.txEnabled.value == true
        val aprs = vm.aprsTxInProgress.value == true
        val bgColor = when {
            aprs -> 0xFFFF8800.toInt()  // orange: APRS TX
            tx   -> 0xFFFF0000.toInt()  // red: TX
            else -> 0xFFDDDDDD.toInt()  // grey: RX
        }
        val textColor = if (tx || aprs) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        binding.tvTxIndicator.setBackgroundColor(bgColor)
        binding.tvTxIndicator.setTextColor(textColor)
        binding.tvTxIndicator.text = if (aprs) "APRS" else "TX"
    }

    private fun updateButtonHighlights() {
        val sel = vm.selectedMenuItem.value ?: MenuItem.NONE

        fun tint(color: Int) = ColorStateList.valueOf(color)

        binding.btnFreq.backgroundTintList  = tint(if (sel == MenuItem.FREQ)  0xFF00BCD4.toInt() else 0xFF1565C0.toInt())
        binding.btnWidth.backgroundTintList = tint(if (sel == MenuItem.WIDTH) 0xFF00BCD4.toInt() else 0xFF1565C0.toInt())
        binding.btnPow.backgroundTintList   = tint(if (sel == MenuItem.POW)   0xFF00BCD4.toInt() else 0xFF1565C0.toInt())
        binding.btnSQL.backgroundTintList   = tint(if (sel == MenuItem.SQL)   0xFF00BCD4.toInt() else 0xFF1565C0.toInt())

        // Info row highlight: selected item → cyan text
        binding.tvStep.setTextColor(if (sel == MenuItem.STEP)  0xFF00FFFF.toInt() else 0xFFCCCCCC.toInt())
        binding.tvMode.setTextColor(if (sel == MenuItem.MODE)  0xFF00FFFF.toInt() else 0xFFCCCCCC.toInt())
        binding.tvWidth.setTextColor(if (sel == MenuItem.WIDTH) 0xFF00FFFF.toInt() else 0xFFCCCCCC.toInt())
        binding.tvPow.setTextColor(if (sel == MenuItem.POW)    0xFF00FFFF.toInt() else 0xFFCCCCCC.toInt())
        binding.tvSQL.setTextColor(if (sel == MenuItem.SQL)    0xFF00FFFF.toInt() else 0xFFCCCCCC.toInt())

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
        val cwDecoding = vm.cwDecoding.value ?: false
        val spkColor = when {
            sampRate == 0 -> 0xFF455A64.toInt()
            spkEnabled    -> 0xFFAEEA00.toInt()  // Yellow-green: SPK ON
            else          -> 0xFF1B5E20.toInt()  // Dark green: SPK OFF
        }
        binding.btnSpk.backgroundTintList = tint(spkColor)
        // Show DEC in lower caption when decoding
        binding.btnSpk.text = if (cwDecoding) "SPK\nDEC" else "SPK"

    }

    private fun setupButtons() {
        // Freq → frequency input screen
        binding.tvFreq.setOnClickListener {
            if (vm.txEnabled.value == true) return@setOnClickListener
            findNavController().navigate(R.id.action_MainControlFragment_to_FreqInputFragment)
        }

        // Select menu buttons
        listOf(
            binding.btnFreq  to MenuItem.FREQ,
            binding.btnWidth to MenuItem.WIDTH,
            binding.btnPow   to MenuItem.POW,
            binding.btnSQL   to MenuItem.SQL
        ).forEach { (btn, item) ->
            btn.setOnClickListener {
                if (vm.txEnabled.value == true && item != MenuItem.NONE) return@setOnClickListener
                vm.selectedMenuItem.value =
                    if (vm.selectedMenuItem.value == item) MenuItem.NONE else item
            }
        }

        // Info row taps: select parameter for UP/DOWN
        listOf(
            binding.tvStep  to MenuItem.STEP,
            binding.tvMode  to MenuItem.MODE,
            binding.tvWidth to MenuItem.WIDTH,
            binding.tvPow   to MenuItem.POW,
            binding.tvSQL   to MenuItem.SQL
        ).forEach { (tv, item) ->
            tv.setOnClickListener {
                if (vm.txEnabled.value == true) return@setOnClickListener
                vm.selectedMenuItem.value =
                    if (vm.selectedMenuItem.value == item) MenuItem.NONE else item
            }
        }

        // Info row long press: pick value from list
        binding.tvStep.setOnLongClickListener {
            val items = STEP_LIST.map { it.label }.toTypedArray()
            val cur = vm.selectedStep.value ?: 0
            AlertDialog.Builder(requireContext()).setTitle("Step")
                .setSingleChoiceItems(items, cur) { dlg, which ->
                    vm.selectedStep.value = which
                    vm.prefs.setModeStep(vm.sharedMode.value ?: "", which)
                    updateInfoRow(); dlg.dismiss()
                }.setNegativeButton("Cancel", null).show()
            true
        }

        binding.tvMode.setOnLongClickListener {
            val modes = vm.supportedModes.value?.takeIf { it.isNotEmpty() } ?: return@setOnLongClickListener true
            val cur = modes.indexOf(vm.sharedMode.value ?: "").coerceAtLeast(0)
            AlertDialog.Builder(requireContext()).setTitle("Mode")
                .setSingleChoiceItems(modes.toTypedArray(), cur) { dlg, which ->
                    val m = modes[which]
                    val w = if (m.contains("CW", ignoreCase = true)) 500 else (vm.sharedWidth.value ?: 0)
                    vm.sendMode(m, w)
                    if (m.contains("CW", ignoreCase = true)) vm.sharedWidth.value = w
                    vm.selectedStep.value = vm.prefs.getModeStep(m)
                    dlg.dismiss()
                }.setNegativeButton("Cancel", null).show()
            true
        }

        val widthOptions = intArrayOf(0, 100, 200, 500, 1000, 1500, 2400, 3000)
        binding.tvWidth.setOnLongClickListener {
            val labels = widthOptions.map { if (it == 0) "Auto" else "${it} Hz" }.toTypedArray()
            val cur = widthOptions.indexOfFirst { it == (vm.sharedWidth.value ?: 0) }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext()).setTitle("Filter Width")
                .setSingleChoiceItems(labels, cur) { dlg, which ->
                    val w = widthOptions[which]
                    vm.sharedWidth.value = w
                    vm.sendMode(vm.sharedMode.value ?: "", w)
                    updateInfoRow(); dlg.dismiss()
                }.setNegativeButton("Cancel", null).show()
            true
        }

        val pctSteps = (0..20).map { it * 5 }
        binding.tvPow.setOnLongClickListener {
            val cur = ((vm.sharedPower.value ?: 0f) * 100).toInt()
            val idx = pctSteps.indexOfFirst { it >= cur }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext()).setTitle("Power")
                .setSingleChoiceItems(pctSteps.map { "$it" }.toTypedArray(), idx) { dlg, which ->
                    vm.sendPower(pctSteps[which] / 100f); dlg.dismiss()
                }.setNegativeButton("Cancel", null).show()
            true
        }

        binding.tvSQL.setOnLongClickListener {
            val cur = ((vm.sharedSQL.value ?: 0f) * 100).toInt()
            val idx = pctSteps.indexOfFirst { it >= cur }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext()).setTitle("Squelch")
                .setSingleChoiceItems(pctSteps.map { "$it" }.toTypedArray(), idx) { dlg, which ->
                    vm.sendSQL(pctSteps[which] / 100f); dlg.dismiss()
                }.setNegativeButton("Cancel", null).show()
            true
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
            if (vm.txEnabled.value == true) return@setOnClickListener  // no operation allowed during TX
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

        // SPK long press: toggle CW/FM-CW decode display ON/OFF
        binding.btnSpk.setOnLongClickListener {
            vm.toggleCwDecoding()
            val decoding = vm.cwDecoding.value ?: false
            val msg = if (decoding) "CW Decode ON (yellow=RX  blue=TX)" else "CW Decode OFF"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            true
        }

        // Decode area long press: toggle RX multi-channel display ON/OFF
        binding.llCwDecoder.setOnLongClickListener {
            if (vm.cwDecoding.value != true) return@setOnLongClickListener false
            vm.toggleCwMultiRx()
            val multi = vm.cwMultiRx.value ?: false
            Toast.makeText(requireContext(),
                if (multi) "RX: 5ch multi-frequency" else "RX: single channel",
                Toast.LENGTH_SHORT).show()
            true
        }

        // FT8
        binding.btnFt8.setOnClickListener {
            if (vm.isDemoMode.value == true) {
                Toast.makeText(requireContext(), "FT8 is not available in Demo Mode", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            findNavController().navigate(R.id.action_MainControlFragment_to_Ft8Fragment)
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

        // CW TX button
        binding.btnCwTx.setOnClickListener {
            if (vm.cwTxBusy.value == true) {
                vm.stopCwText()
                cwTxSheet?.dismiss()
                Toast.makeText(requireContext(), "CW TX stopped", Toast.LENGTH_SHORT).show()
            } else {
                if (cwTxSheet?.isShowing != true) showCwTxPanel()
            }
        }

        // CW USB status bar
        // Short tap: toggle enabled/disabled if connected, scan USB if not connected; stop CW TX if busy
        binding.llCwUsb.setOnClickListener {
            if (vm.cwTxBusy.value == true) {
                vm.stopCwText()
                Toast.makeText(requireContext(), "CW TX stopped", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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
                // Manually scan USB devices
                (activity as? MainActivity)?.scanUsbDevices()
                Toast.makeText(requireContext(), "Scanning USB devices...", Toast.LENGTH_SHORT).show()
            }
        }

        // Long press: CW settings menu (sidetone toggle / Pi port select / VPN buffer)
        binding.llCwUsb.setOnLongClickListener {
            val sidetoneLabel = if (vm.cwSidetoneEnabled.value == true)
                "Sidetone: ON → turn OFF" else "Sidetone: OFF → turn ON"
            val bufMs = vm.prefs.cwDelayMs
            val vpnLabel = "CW VPN buffer: ${bufMs}ms"
            AlertDialog.Builder(requireContext())
                .setTitle("CW USB Settings")
                .setItems(arrayOf(sidetoneLabel, "Select Pi CW Port", vpnLabel)) { _, which ->
                    when (which) {
                        0 -> vm.toggleCwSidetone()
                        1 -> lifecycleScope.launch { showCwPortDialog() }
                        2 -> showCwBufferDialog()
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

    private fun updateCwTxButton() {
        val mode = vm.sharedMode.value ?: ""
        val isCwMode = mode.contains("CW", ignoreCase = true)
        val isFmMode = mode.contains("FM", ignoreCase = true)  // FM / FMN / PKTFM
        val busy = vm.cwTxBusy.value ?: false
        val color = when {
            busy     -> 0xFFFF6600.toInt()  // orange: sending
            isCwMode -> 0xFF004D40.toInt()  // teal: CW keyer
            isFmMode -> 0xFF1A237E.toInt()  // dark blue: FM-CW audio
            else     -> 0xFF1A1A1A.toInt()  // dark: inactive
        }
        val textColor = if (isCwMode || isFmMode || busy) 0xFFCCCCCC.toInt() else 0xFF555555.toInt()
        binding.btnCwTx.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        binding.btnCwTx.setTextColor(textColor)
        binding.btnCwTx.text = if (busy) "STOP\nCW" else "CW TX"
    }

    private fun showCwTxPanel() {
        val ctx = requireContext()
        val sheet = BottomSheetDialog(ctx)
        cwTxSheet = sheet

        val v = layoutInflater.inflate(R.layout.dialog_cw_tx, null)
        sheet.setContentView(v)

        val seekWpm  = v.findViewById<android.widget.SeekBar>(R.id.seekCwWpm)
        val tvWpmLbl = v.findViewById<android.widget.TextView>(R.id.tvCwWpmLabel)
        val btnCq    = v.findViewById<android.widget.Button>(R.id.btnCwCq)
        val btnCall  = v.findViewById<android.widget.Button>(R.id.btnCwCall)
        val btnUr    = v.findViewById<android.widget.Button>(R.id.btnCwUr)
        val btnAgn   = v.findViewById<android.widget.Button>(R.id.btnCwAgn)
        val btnTu    = v.findViewById<android.widget.Button>(R.id.btnCwTu)
        val etFree   = v.findViewById<EditText>(R.id.etCwFreeText)
        val btnSend  = v.findViewById<android.widget.Button>(R.id.btnCwSend)
        val btnStop  = v.findViewById<android.widget.Button>(R.id.btnCwSheetStop)

        cwSheetStopBtn   = btnStop
        cwSheetMsgButtons = listOf(btnCq, btnCall, btnUr, btnAgn, btnTu, btnSend)

        val initWpm = (vm.cwWpm.value ?: vm.prefs.cwWpm).coerceIn(5, 60)
        seekWpm.progress = initWpm - 5  // range 5..60 → progress 0..55 (max=55 in XML max=40 → fix: use 55)
        tvWpmLbl.text = "$initWpm WPM"
        seekWpm.max = 55
        seekWpm.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                tvWpmLbl.text = "${p + 5} WPM"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })
        etFree.setText("")

        // 第2画面（ConnectFragment）で設定したコールサインを使用
        val callsign = vm.prefs.ft8MyCall.ifEmpty { vm.prefs.aprsCallsign }
        btnCall.text = if (callsign.isNotEmpty()) "$callsign K" else "CALL K"

        fun getWpm() = (seekWpm.progress + 5).coerceIn(5, 60)

        fun send(text: String) {
            val w = getWpm()
            vm.prefs.cwWpm = w
            vm.cwWpm.value = w
            vm.sendCwText(text, w)
        }

        btnCq.setOnClickListener {
            val cqMsg = if (callsign.isNotEmpty()) "CQ CQ CQ DE $callsign K" else "CQ CQ CQ K"
            send(cqMsg)
        }
        btnUr.setOnClickListener   { send("UR 5NN5NN BK") }
        btnAgn.setOnClickListener  { send("AGN") }
        btnTu.setOnClickListener   { send("TU TU 73 E E") }
        btnCall.setOnClickListener {
            if (callsign.isNotEmpty()) send("$callsign K")
            else Toast.makeText(ctx, "Set callsign in Connect screen", Toast.LENGTH_SHORT).show()
        }
        btnSend.setOnClickListener {
            val text = etFree.text.toString().trim()
            if (text.isNotEmpty()) {
                vm.prefs.cwLastText = text
                send(text)
            } else {
                Toast.makeText(ctx, "Enter text", Toast.LENGTH_SHORT).show()
            }
        }
        btnStop.setOnClickListener {
            vm.stopCwText()
            Toast.makeText(ctx, "CW stopped", Toast.LENGTH_SHORT).show()
        }

        updateCwTxSheetState()

        sheet.setOnDismissListener {
            cwTxSheet = null
            cwSheetStopBtn = null
            cwSheetMsgButtons = emptyList()
        }

        sheet.show()
    }

    private fun updateCwTxSheetState() {
        val busy = vm.cwTxBusy.value == true
        cwSheetStopBtn?.visibility = if (busy) View.VISIBLE else View.GONE
        cwSheetMsgButtons.forEach { it.isEnabled = !busy }
    }

    private fun applyCwPort(port: String) {
        binding.tvCwUsbPort.text = port
        vm.prefs.cwPort = port
        if (vm.cwUsbConnected.value == true) {
            lifecycleScope.launch(Dispatchers.IO) { api.cwOpen(port, 0) }
        }
    }

    // Convenience property to access api directly within the Fragment
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
                val w = if (newMode.contains("CW", ignoreCase = true)) 500 else (vm.sharedWidth.value ?: 0)
                vm.sendMode(newMode, w)
                if (newMode.contains("CW", ignoreCase = true)) vm.sharedWidth.value = w
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
                vm.sendPower((cur + dir * 0.01f).coerceIn(0f, 1f))
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

    private fun setupMicGainSlider() {
        val gain = vm.sharedMicGain.value ?: 1.0f
        val progress = (gain * 10).toInt().coerceIn(0, 30)
        binding.seekMicGain.progress = progress
        binding.tvMicGain.text = "%.1f".format(gain)

        binding.seekMicGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val g = p / 10f
                binding.tvMicGain.text = "%.1f".format(g)
                if (fromUser) {
                    vm.audioTx.gainMultiplier = g
                    vm.sharedMicGain.value = g
                    vm.prefs.micGain = g
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        vm.sharedMicGain.observe(viewLifecycleOwner) { g ->
            val p = (g * 10).toInt().coerceIn(0, 30)
            if (binding.seekMicGain.progress != p) binding.seekMicGain.progress = p
            binding.tvMicGain.text = "%.1f".format(g)
        }
    }

    override fun onResume() {
        super.onResume()
        applyScreenTimeout()
        if (vm.spkEnabled.value == true) {
            if (!vm.audio.isPlaying && !vm.audio.isStreamActive) {
                vm.startAudio()
            } else if (vm.audio.isStreamActive && !vm.audio.isPlaying) {
                // ゾンビストリーム（接続済みだが再生なし）→ FT8帰還後などに発生 → 強制再起動
                vm.stopAudio()
                vm.startAudio()
            }
        }
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
        // timeoutMin == 0 → "Off" → keep screen on permanently
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}