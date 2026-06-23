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
    private var cwPanelUpdateLabels: (() -> Unit)? = null
    private var cwRepeatUpdateFn: (() -> Unit)? = null
    private var cwSheetFreqView: android.widget.TextView? = null
    private var cwSheetRxView: android.widget.TextView? = null
    private var cwSheetRxScroll: android.widget.HorizontalScrollView? = null
    private var cwSheetTxView: android.widget.TextView? = null
    private var cwSheetTxScroll: android.widget.HorizontalScrollView? = null

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
        updateCwBtStatus()
        updateCwTxButton()
        binding.tvCwUsbPort.text = vm.prefs.cwPort
    }

    private fun setupSMeter() {
        binding.llSmeter.removeAllViews()
        binding.llSmeterLabels.removeAllViews()
        val dp = resources.displayMetrics.density

        // 30 bars: 2バー = 1 Sユニット
        repeat(30) {
            val v = View(requireContext())
            val lp = LinearLayout.LayoutParams(0, (22 * dp).toInt(), 1f)
            lp.marginEnd = (2 * dp).toInt()
            v.layoutParams = lp
            binding.llSmeter.addView(v)
        }

        // Scale labels: S1=bar2, S3=bar6, S5=bar10, S7=bar14, S9=bar18, +10=bar21, +20=bar24, +30=bar28
        val labels = mapOf(2 to "1", 6 to "3", 10 to "5", 14 to "7", 18 to "9",
                           21 to "+10", 24 to "+20", 28 to "+30")
        val labelColors = mapOf(
            2  to 0xFF3A7A4A.toInt(),
            6  to 0xFF3A7A4A.toInt(),
            10 to 0xFF3A7A4A.toInt(),
            14 to 0xFF3A7A4A.toInt(),
            18 to 0xFF00C853.toInt(),
            21 to 0xFFBDA000.toInt(),
            24 to 0xFFFDD835.toInt(),
            28 to 0xFFFF6D00.toInt()
        )
        // テキストが隣のスペーサー・カード境界へはみ出せるようにクリップを解除
        binding.llSmeterLabels.clipChildren = false
        binding.llSmeterLabels.clipToPadding = false
        // 親カード (paddingEnd=4dp) と祖父母 View までクリップ解除
        var p = binding.llSmeterLabels.parent
        repeat(3) {
            (p as? android.view.ViewGroup)?.let { vg ->
                vg.clipChildren = false
                vg.clipToPadding = false
                p = vg.parent
            }
        }
        repeat(30) { i ->
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = (2 * dp).toInt()  // バーと同じmarginで位置を揃える
            val text = labels[i]
            if (text != null) {
                val tv = android.widget.TextView(requireContext())
                tv.layoutParams = lp
                tv.text = text
                // +dBラベル(3文字)は6spで枠内に収める、S単位(1文字)は8sp
                tv.textSize = if (text.length > 2) 6f else 8f
                tv.gravity = android.view.Gravity.CENTER
                tv.maxLines = 1
                tv.isSingleLine = true
                tv.setTextColor(labelColors[i] ?: 0xFF3A7A4A.toInt())
                binding.llSmeterLabels.addView(tv)
            } else {
                val v = View(requireContext())
                v.layoutParams = lp
                binding.llSmeterLabels.addView(v)
            }
        }
    }

    private fun updateModelDisplay() {
        val name = vm.sharedModel.value ?: ""
        binding.tvModel.text = if (vm.piVersionMismatch.value == true && name.isNotEmpty()) "$name ⚠UPDATE" else name
    }

    private fun updateSMeter(signal: Float) {
        val bars = binding.llSmeter.childCount
        val threshold = (signal * 2f).toInt()
        for (i in 0 until bars) {
            val v = binding.llSmeter.getChildAt(i)
            val color = if (i < threshold) {
                when {
                    i <= 17 -> 0xFF00C853.toInt()  // Green  S1-S9
                    i <= 23 -> 0xFFFDD835.toInt()  // Yellow S9+
                    i <= 27 -> 0xFFFF6D00.toInt()  // Orange S9+18dB
                    else    -> 0xFFD50000.toInt()  // Red    S9+30dB+
                }
            } else {
                when {
                    i <= 17 -> 0xFF0A2A14.toInt()  // 暗緑
                    i <= 23 -> 0xFF2A2800.toInt()  // 暗黄
                    i <= 27 -> 0xFF2A1400.toInt()  // 暗橙
                    else    -> 0xFF2A0000.toInt()  // 暗赤
                }
            }
            v.setBackgroundColor(color)
        }
    }

    private fun setupObservers() {
        vm.sharedFreq.observe(viewLifecycleOwner) { freq ->
            val mhz = freq / 1_000_000.0
            binding.tvFreq.text = "%.5f".format(mhz)
            cwSheetFreqView?.text = "%.5f".format(mhz)
        }
        vm.sharedMode.observe(viewLifecycleOwner) { updateInfoRow(); updateButtonHighlights() }
        vm.sharedPower.observe(viewLifecycleOwner) { updateInfoRow() }
        vm.sharedSQL.observe(viewLifecycleOwner) { updateInfoRow() }
        vm.sharedBkIn.observe(viewLifecycleOwner) { updateInfoRow(); updateButtonHighlights() }
        vm.sharedWidth.observe(viewLifecycleOwner) { updateInfoRow() }
        vm.sharedSignal.observe(viewLifecycleOwner) { updateSMeter(it) }
        vm.sharedModel.observe(viewLifecycleOwner) { updateModelDisplay() }
        vm.piVersionMismatch.observe(viewLifecycleOwner) { updateModelDisplay() }
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
        vm.cwKeyOn.observe(viewLifecycleOwner) { _ ->
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

        vm.cwBleConnected.observe(viewLifecycleOwner) { updateCwBtStatus(); updateCwUsbStatus() }
        vm.cwBleEnabled.observe(viewLifecycleOwner) { updateCwBtStatus(); updateCwUsbStatus() }

        vm.cwDecoding.observe(viewLifecycleOwner) { decoding ->
            binding.llCwDecoder.visibility = if (decoding) View.VISIBLE else View.GONE
            updateButtonHighlights()
            val vis = if (decoding) View.VISIBLE else View.GONE
            cwSheetRxScroll?.visibility = vis
            cwSheetTxScroll?.visibility = vis
        }
        vm.cwTxText.observe(viewLifecycleOwner) { text ->
            binding.tvCwTx.text = text
            cwSheetTxView?.text = if (text.isEmpty()) "" else "TX: $text"
            if (text.isNotEmpty()) cwSheetTxScroll?.post { cwSheetTxScroll?.fullScroll(android.view.View.FOCUS_RIGHT) }
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
                if (i == 0) {
                    cwSheetRxView?.text = "RX: $text"
                    cwSheetRxScroll?.post { cwSheetRxScroll?.fullScroll(android.view.View.FOCUS_RIGHT) }
                }
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
        vm.noiseReductionLevel.observe(viewLifecycleOwner) { updateButtonHighlights() }
        vm.cwTxBusy.observe(viewLifecycleOwner) { updateCwUsbStatus(); updateCwTxButton(); updateCwTxSheetState() }
        vm.cwCqRepeating.observe(viewLifecycleOwner) { cwRepeatUpdateFn?.invoke() }
        vm.cwCqRepeatStatus.observe(viewLifecycleOwner) { cwRepeatUpdateFn?.invoke() }
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
        val sidetoneTag = if (!sidetone && enabled) " [Muted]" else ""

        // USB label: green when connected, gray when not
        val usbLabelColor = if (connected) 0xFF76FF03.toInt() else 0xFF444444.toInt()
        binding.tvCwUsbLabel?.setTextColor(usbLabelColor)

        val (statusText, statusColor) = when {
            cwTxBusy   -> Pair("CW Sending... (tap to stop)", 0xFFFF6600.toInt())
            !connected -> Pair("Not connected",               0xFF555555.toInt())
            !enabled   -> Pair("Idle",                        0xFFFFEB3B.toInt())
            isCwMode   -> Pair("CW relay$sidetoneTag",        0xFF76FF03.toInt())
            else       -> Pair("Audio relay$sidetoneTag",     0xFF40C4FF.toInt())
        }
        binding.tvCwUsbStatus.text = statusText
        binding.tvCwUsbStatus.setTextColor(statusColor)

        val bleEnabled = vm.cwBleEnabled.value ?: false
        val anyEnabled = enabled || bleEnabled
        val (svrText, svrColor) = when {
            !anyEnabled -> Pair("Svr:--",      0xFF555555.toInt())
            svrSynced   -> Pair("Svr:Synced",  0xFF76FF03.toInt())
            svrConnected-> Pair("Svr:Online",  0xFFFFEB3B.toInt())
            else        -> Pair("Svr:Offline", 0xFFFF5252.toInt())
        }
        binding.tvCwServerStatus?.text = svrText
        binding.tvCwServerStatus?.setTextColor(svrColor)
    }

    private fun updateCwBtStatus() {
        val bleConnected = vm.cwBleConnected.value ?: false
        val (text, color) = when {
            bleConnected -> Pair("BLE", 0xFF76FF03.toInt())
            else         -> Pair("BLE", 0xFF444444.toInt())
        }
        binding.tvCwBtStatus.text = text
        binding.tvCwBtStatus.setTextColor(color)
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
        val tx   = vm.txEnabled.value == true
        val aprs = vm.aprsTxInProgress.value == true
        val cwKey = vm.cwKeyOn.value == true
        val bgColor = when {
            aprs  -> 0xFFFF8800.toInt()  // orange: APRS TX
            tx    -> 0xFFFF0000.toInt()  // red: PTT TX
            cwKey -> 0xFFFF0000.toInt()  // red: CW keying TX
            else  -> 0xFFDDDDDD.toInt()  // grey: RX
        }
        val lit = tx || aprs || cwKey
        binding.tvTxIndicator.setBackgroundColor(bgColor)
        binding.tvTxIndicator.setTextColor(if (lit) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
        binding.tvTxIndicator.text = if (aprs) "APRS" else "TX"
    }

    private fun updateButtonHighlights() {
        val sel = vm.selectedMenuItem.value ?: MenuItem.NONE

        fun tint(color: Int) = ColorStateList.valueOf(color)

        binding.btnFreq.backgroundTintList  = tint(if (sel == MenuItem.FREQ)  0xFF00BCD4.toInt() else 0xFF1565C0.toInt())
        binding.btnWidth.backgroundTintList = tint(if (sel == MenuItem.WIDTH) 0xFF00BCD4.toInt() else 0xFF1565C0.toInt())
        binding.btnPow.backgroundTintList   = tint(if (sel == MenuItem.POW)   0xFF00BCD4.toInt() else 0xFF1565C0.toInt())
        val nrLevel = vm.noiseReductionLevel.value ?: 0
        binding.btnSQL.backgroundTintList = tint(if (sel == MenuItem.SQL) 0xFF00BCD4.toInt() else 0xFF1565C0.toInt())
        binding.btnSQL.text = "SQL"

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
        binding.btnSpk.text = "SPK"

        // NR button
        val nrActive = nrLevel > 0
        binding.btnNr.backgroundTintList = tint(if (nrActive) 0xFF6A1B9A.toInt() else 0xFF1565C0.toInt())
        binding.btnNr.text = if (nrActive) "NR$nrLevel" else "NR"

        // DEC button
        binding.btnDec.backgroundTintList = tint(if (cwDecoding) 0xFF00897B.toInt() else 0xFF1565C0.toInt())

        // Mode quick-select buttons (Row 1): base=cyan / PKT variant=teal / inactive=dark indigo
        val curMode = vm.sharedMode.value ?: ""
        val modeBase    = 0xFF00BCD4.toInt()  // cyan:  base mode active
        val modePkt     = 0xFF00897B.toInt()  // teal:  PKT/variant active
        val modeOff     = 0xFF1A237E.toInt()  // dark indigo: inactive

        val textActive   = 0xFF000000.toInt()  // 黒: 選択時（明るい背景に対してコントラスト確保）
        val textInactive = 0xFF6677CC.toInt()  // 淡青: 非選択時

        val lsbActive = curMode == "LSB" || curMode == "PKTLSB"
        binding.btnModeLsb.backgroundTintList = tint(when (curMode) { "LSB" -> modeBase; "PKTLSB" -> modePkt; else -> modeOff })
        binding.btnModeLsb.text = if (curMode == "PKTLSB") "PKT-L" else "LSB"
        binding.btnModeLsb.setTextColor(if (lsbActive) textActive else textInactive)

        val usbActive = curMode == "USB" || curMode == "PKTUSB"
        binding.btnModeUsb.backgroundTintList = tint(when (curMode) { "USB" -> modeBase; "PKTUSB" -> modePkt; else -> modeOff })
        binding.btnModeUsb.text = if (curMode == "PKTUSB") "PKT-U" else "USB"
        binding.btnModeUsb.setTextColor(if (usbActive) textActive else textInactive)

        val cwActive = curMode == "CW" || curMode == "CWR"
        binding.btnModeCw.backgroundTintList  = tint(when (curMode) { "CW" -> modeBase; "CWR" -> modePkt; else -> modeOff })
        binding.btnModeCw.text = if (curMode == "CWR") "CWR" else "CW"
        binding.btnModeCw.setTextColor(if (cwActive) textActive else textInactive)

        val fmActive = curMode == "FM" || curMode == "FM-D" || curMode == "PKTFM"
        binding.btnModeFm.backgroundTintList  = tint(when (curMode) {
            "FM"    -> modeBase
            "FM-D"  -> modePkt
            "PKTFM" -> modePkt
            else    -> modeOff
        })
        binding.btnModeFm.text = when (curMode) { "FM-D", "PKTFM" -> "PKT-F"; else -> "FM" }
        binding.btnModeFm.setTextColor(if (fmActive) textActive else textInactive)
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

        // Freq ◀/▶ → step by current step size
        binding.btnFreqMinus.setOnClickListener {
            val step = STEP_LIST.getOrElse(vm.selectedStep.value ?: 0) { STEP_LIST[0] }
            vm.sendFreq((vm.sharedFreq.value ?: 0L) - step.stepHz)
        }
        binding.btnFreqPlus.setOnClickListener {
            val step = STEP_LIST.getOrElse(vm.selectedStep.value ?: 0) { STEP_LIST[0] }
            vm.sendFreq((vm.sharedFreq.value ?: 0L) + step.stepHz)
        }

        // NR button: cycle noise reduction level
        binding.btnNr.setOnClickListener {
            if (vm.txEnabled.value == true) return@setOnClickListener
            vm.cycleNoiseReduction()
            val level = vm.noiseReductionLevel.value ?: 0
            val msg = when (level) {
                0    -> "Noise Reduction: OFF"
                1    -> "Noise Reduction: Level 1 (Light)"
                2    -> "Noise Reduction: Level 2 (Medium)"
                3    -> "Noise Reduction: Level 3 (Strong)"
                4    -> "Noise Reduction: Level 4 (Max)"
                5    -> "Noise Reduction: Level 5 (Neural)"
                else -> "Noise Reduction: Level $level"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // DEC button: toggle CW decode
        binding.btnDec.setOnClickListener {
            vm.toggleCwDecoding()
            val decoding = vm.cwDecoding.value ?: false
            val msg = if (decoding) "CW Decode ON (yellow=RX  blue=TX)" else "CW Decode OFF"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

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

        // CW BT status bar
        binding.llCwBt.setOnClickListener {
            (activity as? MainActivity)?.connectCwBt()
        }
        binding.llCwBt.setOnLongClickListener {
            val sidetoneLabel = if (vm.cwSidetoneEnabled.value == true)
                "Sidetone: ON → turn OFF" else "Sidetone: OFF → turn ON"
            AlertDialog.Builder(requireContext())
                .setTitle("CW BLE Settings")
                .setItems(arrayOf(sidetoneLabel, "Disconnect BLE")) { _, which ->
                    when (which) {
                        0 -> vm.toggleCwSidetone()
                        1 -> vm.disconnectCwBle()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        // Mode quick-select buttons (Row 4)
        binding.btnModeUsb.setOnClickListener { quickSetMode("USB") }
        binding.btnModeLsb.setOnClickListener { quickSetMode("LSB") }
        binding.btnModeCw.setOnClickListener  { quickSetMode("CW") }
        binding.btnModeFm.setOnClickListener  { quickSetMode("FM") }
    }

    private fun quickSetMode(key: String) {
        if (vm.txEnabled.value == true) return
        val current = vm.sharedMode.value ?: ""
        val target = when (key) {
            "LSB" -> if (current == "LSB") "PKTLSB" else "LSB"
            "USB" -> if (current == "USB") "PKTUSB" else "USB"
            "CW"  -> if (current == "CW")  "CWR"    else "CW"
            "FM"  -> if (current == "FM")  "PKTFM"  else "FM"
            else  -> key
        }
        val w = if (target.startsWith("CW")) 500 else 0
        vm.sendMode(target, w)
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

        fun <T : android.view.View> id(resId: Int): T = v.findViewById(resId)

        val seekWpm      = id<android.widget.SeekBar>(R.id.seekCwWpm)
        val tvWpmLbl     = id<android.widget.TextView>(R.id.tvCwWpmLabel)
        val swPaddleSwap = id<android.widget.Switch>(R.id.switchPaddleSwap)
        val swPttPoll    = id<android.widget.Switch>(R.id.switchPttPoll)
        val tvMyCall     = id<android.widget.TextView>(R.id.tvCwMyCall)
        val etDxCall     = id<EditText>(R.id.etCwDxCall)
        val btnDxClear   = id<android.widget.Button>(R.id.btnCwDxClear)
        val etRst        = id<EditText>(R.id.etCwRst)
        val btnRstClear  = id<android.widget.Button>(R.id.btnCwRstClear)
        val btnRst5NN    = id<android.widget.Button>(R.id.btnRst5NN)
        val btnRstR1     = id<android.widget.Button>(R.id.btnRstR1)
        val btnRstR2     = id<android.widget.Button>(R.id.btnRstR2)
        val btnRstR3     = id<android.widget.Button>(R.id.btnRstR3)
        val etPota       = id<EditText>(R.id.etCwPota)
        val btnPotaClear = id<android.widget.Button>(R.id.btnCwPotaClear)
        val etJcc        = id<EditText>(R.id.etCwJcc)
        val btnJccClear  = id<android.widget.Button>(R.id.btnCwJccClear)
        val btnTabCq     = id<android.widget.Button>(R.id.btnTabCq)
        val btnTabAns    = id<android.widget.Button>(R.id.btnTabAns)
        val indCq        = id<android.view.View>(R.id.viewTabIndCq)
        val indAns       = id<android.view.View>(R.id.viewTabIndAns)
        val panelCq      = id<android.widget.LinearLayout>(R.id.panelCq)
        val panelAns     = id<android.widget.LinearLayout>(R.id.panelAns)
        // CQ settings toggles
        val btnCqR1       = id<android.widget.Button>(R.id.btnCqR1)
        val btnCqR2       = id<android.widget.Button>(R.id.btnCqR2)
        val btnCqR3       = id<android.widget.Button>(R.id.btnCqR3)
        val btnCqTogPota  = id<android.widget.Button>(R.id.btnCqTogPota)
        val btnCqTogJcc   = id<android.widget.Button>(R.id.btnCqTogJcc)
        // CQ preset buttons
        val btnCwCq       = id<android.widget.Button>(R.id.btnCwCq)
        val btnCwCqDxK    = id<android.widget.Button>(R.id.btnCwCqDxK)
        val btnCwCall     = id<android.widget.Button>(R.id.btnCwCall)
        val btnCwCqTu     = id<android.widget.Button>(R.id.btnCwCqTu)
        val btnCwCqAgn    = id<android.widget.Button>(R.id.btnCwCqAgn)
        val btnCwCqUr     = id<android.widget.Button>(R.id.btnCwCqUr)
        // CQ greeting
        val btnCqGrNone   = id<android.widget.Button>(R.id.btnCqGrNone)
        val btnCqGrGm     = id<android.widget.Button>(R.id.btnCqGrGm)
        val btnCqGrGe     = id<android.widget.Button>(R.id.btnCqGrGe)
        val btnCqGrGa     = id<android.widget.Button>(R.id.btnCqGrGa)
        val btnCqQslNone  = id<android.widget.Button>(R.id.btnCqQslNone)
        val btnCqQslPse   = id<android.widget.Button>(R.id.btnCqQslPse)
        val btnCqQslNo    = id<android.widget.Button>(R.id.btnCqQslNo)
        // CQ repeat controls
        val btnCqLoop3    = id<android.widget.Button>(R.id.btnCqLoop3)
        val btnCqLoop5    = id<android.widget.Button>(R.id.btnCqLoop5)
        val btnCqLoop10   = id<android.widget.Button>(R.id.btnCqLoop10)
        val btnCqLoopInf  = id<android.widget.Button>(R.id.btnCqLoopInf)
        val btnCqInt10    = id<android.widget.Button>(R.id.btnCqInt10)
        val btnCqInt15    = id<android.widget.Button>(R.id.btnCqInt15)
        val btnCqInt30    = id<android.widget.Button>(R.id.btnCqInt30)
        val btnCqInt60    = id<android.widget.Button>(R.id.btnCqInt60)
        val tvCqStatus    = id<android.widget.TextView>(R.id.tvCqRepeatStatus)
        val btnCqRepeat   = id<android.widget.Button>(R.id.btnCqRepeat)
        // ANS settings toggles
        val btnAnsGrNone  = id<android.widget.Button>(R.id.btnAnsGrNone)
        val btnAnsGrGm    = id<android.widget.Button>(R.id.btnAnsGrGm)
        val btnAnsGrGe    = id<android.widget.Button>(R.id.btnAnsGrGe)
        val btnAnsGrGa    = id<android.widget.Button>(R.id.btnAnsGrGa)
        val btnAnsTogPota = id<android.widget.Button>(R.id.btnAnsTogPota)
        val btnAnsTogJcc  = id<android.widget.Button>(R.id.btnAnsTogJcc)
        // QSL toggles
        val btnQslNone    = id<android.widget.Button>(R.id.btnQslNone)
        val btnQslPse     = id<android.widget.Button>(R.id.btnQslPse)
        val btnQslTnx     = id<android.widget.Button>(R.id.btnQslTnx)
        // ANS preset buttons
        val btnCwAnsCall  = id<android.widget.Button>(R.id.btnCwAnsCall)
        val btnCwAnsDE    = id<android.widget.Button>(R.id.btnCwAnsDE)
        val btnCwUr       = id<android.widget.Button>(R.id.btnCwUr)
        val btnCwTu       = id<android.widget.Button>(R.id.btnCwTu)
        val btnCwAgn      = id<android.widget.Button>(R.id.btnCwAgn)
        // NR level
        val btnNrOff = id<android.widget.Button>(R.id.btnCwNrOff)
        val btnNr1   = id<android.widget.Button>(R.id.btnCwNr1)
        val btnNr2   = id<android.widget.Button>(R.id.btnCwNr2)
        val btnNr3   = id<android.widget.Button>(R.id.btnCwNr3)
        val btnNr4   = id<android.widget.Button>(R.id.btnCwNr4)
        val btnNr5   = id<android.widget.Button>(R.id.btnCwNr5)
        val nrButtons = listOf(btnNrOff, btnNr1, btnNr2, btnNr3, btnNr4, btnNr5)

        // Free text
        val etFree        = id<EditText>(R.id.etCwFreeText)
        val btnSend       = id<android.widget.Button>(R.id.btnCwSend)
        val btnStop       = id<android.widget.Button>(R.id.btnCwSheetStop)

        cwSheetStopBtn    = btnStop
        cwSheetMsgButtons = emptyList()

        val tvCwFreqDisplay  = id<android.widget.TextView>(R.id.tvCwFreqDisplay)
        val btnCwFreqDown    = id<android.widget.Button>(R.id.btnCwFreqDown)
        val btnCwFreqUp      = id<android.widget.Button>(R.id.btnCwFreqUp)
        val tvCwPanelRx      = id<android.widget.TextView>(R.id.tvCwPanelRx)
        val hsvCwPanelRx     = id<android.widget.HorizontalScrollView>(R.id.hsvCwPanelRx)
        val tvCwPanelTx      = id<android.widget.TextView>(R.id.tvCwPanelTx)
        val hsvCwPanelTx     = id<android.widget.HorizontalScrollView>(R.id.hsvCwPanelTx)
        cwSheetFreqView  = tvCwFreqDisplay
        cwSheetRxView    = tvCwPanelRx
        cwSheetRxScroll  = hsvCwPanelRx
        cwSheetTxView    = tvCwPanelTx
        cwSheetTxScroll  = hsvCwPanelTx

        // 初期値を反映
        tvCwFreqDisplay.text = "%.5f".format((vm.sharedFreq.value ?: 0L) / 1_000_000.0)
        tvCwPanelRx.text = "RX: ${vm.cwRxTexts[0].value ?: ""}"
        tvCwPanelTx.text = vm.cwTxText.value?.let { if (it.isEmpty()) "" else "TX: $it" } ?: ""
        val decodeVis = if (vm.cwDecoding.value == true) View.VISIBLE else View.GONE
        hsvCwPanelRx.visibility = decodeVis
        hsvCwPanelTx.visibility = decodeVis

        // 周波数タップ → 入力ダイアログ
        tvCwFreqDisplay.setOnClickListener {
            val et = android.widget.EditText(ctx).apply {
                setText("%.5f".format((vm.sharedFreq.value ?: 0L) / 1_000_000.0))
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                selectAll()
            }
            android.app.AlertDialog.Builder(ctx)
                .setTitle("周波数 (MHz)")
                .setView(et)
                .setPositiveButton("OK") { _, _ ->
                    val hz = (et.text.toString().toDoubleOrNull() ?: return@setPositiveButton) * 1_000_000
                    vm.sendFreq(hz.toLong())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        // ◀/▶ 短押し±500Hz、長押し±5kHz
        btnCwFreqDown.setOnClickListener     { vm.sendFreq((vm.sharedFreq.value ?: 0L) - 500L) }
        btnCwFreqDown.setOnLongClickListener { vm.sendFreq((vm.sharedFreq.value ?: 0L) - 5000L); true }
        btnCwFreqUp.setOnClickListener       { vm.sendFreq((vm.sharedFreq.value ?: 0L) + 500L) }
        btnCwFreqUp.setOnLongClickListener   { vm.sendFreq((vm.sharedFreq.value ?: 0L) + 5000L); true }

        // MY callsign
        val myCall = vm.prefs.ft8MyCall.ifEmpty { vm.prefs.aprsCallsign }
        tvMyCall.text = if (myCall.isNotEmpty()) myCall else "(not set)"

        // Input field values
        etDxCall.setText(vm.prefs.cwDxCall)
        btnDxClear.setOnClickListener   { etDxCall.setText("") }
        btnRstClear.setOnClickListener  { etRst.setText("") }
        btnRst5NN.setOnClickListener    { etRst.setText("5NN") }
        btnPotaClear.setOnClickListener { etPota.setText("") }
        btnJccClear.setOnClickListener  { etJcc.setText("") }
        etRst.setText(vm.prefs.cwRst.ifEmpty { "599" })
        etPota.setText(vm.prefs.cwPota)
        etJcc.setText(vm.prefs.cwJcc)

        // WPM
        val initWpm = (vm.cwWpm.value ?: vm.prefs.cwWpm).coerceIn(5, 60)
        seekWpm.progress = initWpm - 5
        tvWpmLbl.text = "$initWpm WPM"
        seekWpm.max = 55
        seekWpm.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                tvWpmLbl.text = "${p + 5} WPM"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) { vm.updateCwWpm(sb.progress + 5) }
        })
        swPaddleSwap.isChecked = vm.prefs.cwPaddleSwap
        swPaddleSwap.setOnCheckedChangeListener { _, c -> vm.updatePaddleSwap(c) }
        swPttPoll.isChecked = vm.cwPttPoll.value ?: false
        swPttPoll.setOnCheckedChangeListener { _, c -> vm.updateCwPttPoll(c) }

        // NR level buttons
        fun updateNrHighlights() {
            val level = vm.noiseReductionLevel.value ?: 0
            nrButtons.forEachIndexed { idx, btn ->
                val active = idx == level
                btn.backgroundTintList = ColorStateList.valueOf(
                    if (active) 0xFF6A1B9A.toInt() else 0xFF333333.toInt()
                )
                btn.setTextColor(if (active) 0xFFEEEEEE.toInt() else 0xFF888888.toInt())
            }
        }
        updateNrHighlights()
        nrButtons.forEachIndexed { idx, btn ->
            btn.setOnClickListener {
                vm.setNoiseReduction(idx)
                updateNrHighlights()
            }
        }

        // ── CQ/ANS pattern state ──
        var cqRepeat     = vm.prefs.cwCqRepeat
        var cqPota       = vm.prefs.cwCqPota
        var cqJcc        = vm.prefs.cwCqJcc
        var ansGreet     = vm.prefs.cwAnsGreeting
        var ansPota      = vm.prefs.cwAnsPota
        var ansJcc       = vm.prefs.cwAnsJcc
        var qsl          = vm.prefs.cwQsl
        var cqLoopCount  = vm.prefs.cwCqLoopCount    // 0=∞
        var cqLoopInterv = vm.prefs.cwCqLoopInterval
        var rstRepeat    = vm.prefs.cwRstRepeat

        // Input helpers
        fun getWpm()  = (seekWpm.progress + 5).coerceIn(5, 60)
        fun my()      = myCall
        fun dx()      = etDxCall.text.toString().trim().uppercase()
        fun rst()     = etRst.text.toString().trim().ifEmpty { "599" }
        fun pota()    = etPota.text.toString().trim().uppercase()
        fun jcc()     = etJcc.text.toString().trim()

        fun saveAll() {
            vm.prefs.cwDxCall        = dx()
            vm.prefs.cwRst           = rst()
            vm.prefs.cwPota          = pota()
            vm.prefs.cwJcc           = jcc()
            vm.prefs.cwCqRepeat      = cqRepeat
            vm.prefs.cwCqPota        = cqPota
            vm.prefs.cwCqJcc         = cqJcc
            vm.prefs.cwAnsGreeting   = ansGreet
            vm.prefs.cwAnsPota       = ansPota
            vm.prefs.cwAnsJcc        = ansJcc
            vm.prefs.cwQsl           = qsl
            vm.prefs.cwCqLoopCount   = cqLoopCount
            vm.prefs.cwCqLoopInterval= cqLoopInterv
            vm.prefs.cwRstRepeat     = rstRepeat
        }

        fun send(text: String) {
            saveAll()
            val w = getWpm(); vm.updateCwWpm(w); vm.sendCwText(text, w)
        }

        // ── Toggle highlight helpers ──
        val COL_ON_GREEN  = 0xFF1A5C30.toInt()
        val COL_ON_AMBER  = 0xFF5C3A10.toInt()
        val COL_ON_BLUE   = 0xFF1A3A5C.toInt()
        val COL_OFF       = 0xFF333333.toInt()
        val TXT_ON        = 0xFFEEEEEE.toInt()
        val TXT_OFF       = 0xFF888888.toInt()

        fun hilite(btn: android.widget.Button, on: Boolean, onColor: Int = COL_ON_GREEN) {
            btn.backgroundTintList = ColorStateList.valueOf(if (on) onColor else COL_OFF)
            btn.setTextColor(if (on) TXT_ON else TXT_OFF)
        }

        fun refreshCqToggles() {
            hilite(btnCqR1, cqRepeat == 1, COL_ON_GREEN)
            hilite(btnCqR2, cqRepeat == 2, COL_ON_GREEN)
            hilite(btnCqR3, cqRepeat == 3, COL_ON_GREEN)
            hilite(btnCqTogPota, cqPota, COL_ON_AMBER)
            hilite(btnCqTogJcc,  cqJcc,  COL_ON_BLUE)
        }
        fun refreshRstRepeat() {
            hilite(btnRstR1, rstRepeat == 1, COL_ON_GREEN)
            hilite(btnRstR2, rstRepeat == 2, COL_ON_GREEN)
            hilite(btnRstR3, rstRepeat == 3, COL_ON_GREEN)
        }
        fun refreshGreetToggles() {
            // ANS greeting
            hilite(btnAnsGrNone, ansGreet == "",   COL_ON_GREEN)
            hilite(btnAnsGrGm,   ansGreet == "GM", COL_ON_GREEN)
            hilite(btnAnsGrGe,   ansGreet == "GE", COL_ON_GREEN)
            hilite(btnAnsGrGa,   ansGreet == "GA", COL_ON_GREEN)
            // CQ greeting (same state)
            hilite(btnCqGrNone, ansGreet == "",   COL_ON_GREEN)
            hilite(btnCqGrGm,   ansGreet == "GM", COL_ON_GREEN)
            hilite(btnCqGrGe,   ansGreet == "GE", COL_ON_GREEN)
            hilite(btnCqGrGa,   ansGreet == "GA", COL_ON_GREEN)
        }
        fun refreshAnsToggles() {
            hilite(btnAnsTogPota, ansPota, COL_ON_AMBER)
            hilite(btnAnsTogJcc,  ansJcc,  COL_ON_BLUE)
            hilite(btnQslNone, qsl == "",        COL_ON_GREEN)
            hilite(btnQslPse,  qsl == "PSE QSL", COL_ON_AMBER)
            hilite(btnQslTnx,  qsl == "NO QSL",  COL_ON_GREEN)
            hilite(btnCqQslNone, qsl == "",        COL_ON_GREEN)
            hilite(btnCqQslPse,  qsl == "PSE QSL", COL_ON_AMBER)
            hilite(btnCqQslNo,   qsl == "NO QSL",  COL_ON_GREEN)
        }
        fun refreshLoopToggles() {
            hilite(btnCqLoop3,   cqLoopCount == 3,  COL_ON_GREEN)
            hilite(btnCqLoop5,   cqLoopCount == 5,  COL_ON_GREEN)
            hilite(btnCqLoop10,  cqLoopCount == 10, COL_ON_GREEN)
            hilite(btnCqLoopInf, cqLoopCount == 0,  COL_ON_GREEN)
            hilite(btnCqInt10, cqLoopInterv == 10, COL_ON_BLUE)
            hilite(btnCqInt15, cqLoopInterv == 15, COL_ON_BLUE)
            hilite(btnCqInt30, cqLoopInterv == 30, COL_ON_BLUE)
            hilite(btnCqInt60, cqLoopInterv == 60, COL_ON_BLUE)
        }
        fun updateRepeatButton() {
            val repeating = vm.cwCqRepeating.value == true
            val status    = vm.cwCqRepeatStatus.value ?: ""
            tvCqStatus.text = status
            if (repeating) {
                btnCqRepeat.text = "■ STOP REPEAT"
                btnCqRepeat.backgroundTintList = ColorStateList.valueOf(0xFF7A1A1A.toInt())
            } else {
                btnCqRepeat.text = "▶ CQ REPEAT"
                btnCqRepeat.backgroundTintList = ColorStateList.valueOf(0xFF1A5C2A.toInt())
            }
        }

        // ── Tab switching ──
        val TAB_ACTIVE_IND = 0xFF4499FF.toInt()
        val TAB_INACTIVE_IND = 0xFF333333.toInt()
        fun showCqTab() {
            panelCq.visibility  = android.view.View.VISIBLE
            panelAns.visibility = android.view.View.GONE
            btnTabCq.backgroundTintList  = ColorStateList.valueOf(0xFF222222.toInt())
            btnTabCq.setTextColor(0xFFFFFFFF.toInt())
            btnTabAns.backgroundTintList = ColorStateList.valueOf(0xFF222222.toInt())
            btnTabAns.setTextColor(0xFF666666.toInt())
            indCq.setBackgroundColor(TAB_ACTIVE_IND)
            indAns.setBackgroundColor(TAB_INACTIVE_IND)
        }
        fun showAnsTab() {
            panelCq.visibility  = android.view.View.GONE
            panelAns.visibility = android.view.View.VISIBLE
            btnTabCq.backgroundTintList  = ColorStateList.valueOf(0xFF222222.toInt())
            btnTabCq.setTextColor(0xFF666666.toInt())
            btnTabAns.backgroundTintList = ColorStateList.valueOf(0xFF222222.toInt())
            btnTabAns.setTextColor(0xFFFFFFFF.toInt())
            indCq.setBackgroundColor(TAB_INACTIVE_IND)
            indAns.setBackgroundColor(TAB_ACTIVE_IND)
        }
        showCqTab()
        btnTabCq.setOnClickListener  { showCqTab() }
        btnTabAns.setOnClickListener { showAnsTab() }

        // ── updateLabels: assembles messages + enables/disables ──
        fun updateLabels() {
            val m = my(); val d = dx(); val r = rst(); val p = pota(); val j = jcc()
            val myOk = m.isNotEmpty(); val dOk = d.isNotEmpty()
            val pOk  = p.isNotEmpty(); val jOk  = j.isNotEmpty()
            val busy = vm.cwTxBusy.value == true
            val mDisp = if (myOk) m else "○○"
            val dDisp = if (dOk) d else "△△"

            // CQ assembled: POTA and JCC both go AFTER callsign
            val callPart = (1..cqRepeat).joinToString(" ") { mDisp }
            val cqPostExtra = buildString {
                if (cqPota && pOk) append(" POTA $p")
                if (cqJcc  && jOk) append(" JCC $j")
            }
            btnCwCq.text = "CQ CQ CQ DE $callPart$cqPostExtra K"
            btnCwCqDxK.text = "$dDisp K"
            btnCwCall.text = "$dDisp DE $mDisp K"
            btnCwCqTu.text = "TU TU 73 E E"

            // ANS assembled messages — greeting goes into UR (not DE)
            val greetPfx = if (ansGreet.isNotEmpty()) "$ansGreet " else ""
            btnCwAnsCall.text = "$mDisp K"
            btnCwAnsDE.text   = "$dDisp DE $mDisp K"
            val urExtra = buildString {
                if (ansPota && pOk) append(" POTA $p")
                if (ansJcc  && jOk) append(" JCC $j")
            }
            val rstPart = (1..rstRepeat).joinToString(" ") { r }
            val qslInUr = if (qsl.isNotEmpty()) " $qsl" else ""
            btnCwUr.text   = "${greetPfx}UR $rstPart$urExtra$qslInUr BK"
            btnCwCqUr.text = "${greetPfx}UR $rstPart$urExtra$qslInUr BK"
            btnCwTu.text   = "TU TU 73 E E"

            // Enable/disable per button
            fun setBtn(btn: android.widget.Button, ok: Boolean) {
                val en = ok && !busy
                btn.isEnabled = en
                btn.alpha     = if (en) 1.0f else 0.35f
            }
            setBtn(btnCwCq,      myOk)
            setBtn(btnCwCqDxK,   dOk)
            setBtn(btnCwCall,    myOk && dOk)
            setBtn(btnCwCqTu,    true)
            setBtn(btnCwCqAgn,   true)
            setBtn(btnCwCqUr,    true)
            setBtn(btnCwAnsCall, myOk)
            setBtn(btnCwAnsDE,   myOk && dOk)
            setBtn(btnCwUr,      true)
            setBtn(btnCwTu,      true)
            setBtn(btnCwAgn,     true)
            setBtn(btnSend,      true)
        }
        cwPanelUpdateLabels = { updateLabels() }
        cwRepeatUpdateFn    = { updateRepeatButton() }
        refreshCqToggles(); refreshAnsToggles(); refreshLoopToggles(); refreshGreetToggles(); refreshRstRepeat()
        updateLabels(); updateRepeatButton()

        // ── Toggle button handlers ──
        btnCqR1.setOnClickListener { cqRepeat = 1; refreshCqToggles(); updateLabels() }
        btnCqR2.setOnClickListener { cqRepeat = 2; refreshCqToggles(); updateLabels() }
        btnCqR3.setOnClickListener { cqRepeat = 3; refreshCqToggles(); updateLabels() }
        btnCqTogPota.setOnClickListener { cqPota = !cqPota; refreshCqToggles(); updateLabels() }
        btnCqTogJcc.setOnClickListener  { cqJcc  = !cqJcc;  refreshCqToggles(); updateLabels() }

        fun setGreet(g: String) { ansGreet = g; refreshGreetToggles(); updateLabels() }
        btnAnsGrNone.setOnClickListener  { setGreet("") }
        btnAnsGrGm.setOnClickListener    { setGreet("GM") }
        btnAnsGrGe.setOnClickListener    { setGreet("GE") }
        btnAnsGrGa.setOnClickListener    { setGreet("GA") }
        btnCqGrNone.setOnClickListener   { setGreet("") }
        btnCqGrGm.setOnClickListener     { setGreet("GM") }
        btnCqGrGe.setOnClickListener     { setGreet("GE") }
        btnCqGrGa.setOnClickListener     { setGreet("GA") }
        btnAnsTogPota.setOnClickListener { ansPota = !ansPota; refreshAnsToggles(); updateLabels() }
        btnAnsTogJcc.setOnClickListener  { ansJcc  = !ansJcc;  refreshAnsToggles(); updateLabels() }
        btnQslNone.setOnClickListener    { qsl = "";         refreshAnsToggles(); updateLabels() }
        btnQslPse.setOnClickListener     { qsl = "PSE QSL"; refreshAnsToggles(); updateLabels() }
        btnQslTnx.setOnClickListener     { qsl = "NO QSL";  refreshAnsToggles(); updateLabels() }
        // CQ tab QSL (mirrors ANS tab)
        btnCqQslNone.setOnClickListener  { qsl = "";         refreshAnsToggles(); updateLabels() }
        btnCqQslPse.setOnClickListener   { qsl = "PSE QSL"; refreshAnsToggles(); updateLabels() }
        btnCqQslNo.setOnClickListener    { qsl = "NO QSL";  refreshAnsToggles(); updateLabels() }
        // CQ tab AGN + UR
        btnCwCqAgn.setOnClickListener    { send("AGN") }
        btnCwCqUr.setOnClickListener     { send(btnCwCqUr.text.toString()) }
        // RST repeat toggles
        btnRstR1.setOnClickListener      { rstRepeat = 1; refreshRstRepeat(); updateLabels() }
        btnRstR2.setOnClickListener      { rstRepeat = 2; refreshRstRepeat(); updateLabels() }
        btnRstR3.setOnClickListener      { rstRepeat = 3; refreshRstRepeat(); updateLabels() }
        // CQ loop toggles
        btnCqLoop3.setOnClickListener    { cqLoopCount = 3;  refreshLoopToggles() }
        btnCqLoop5.setOnClickListener    { cqLoopCount = 5;  refreshLoopToggles() }
        btnCqLoop10.setOnClickListener   { cqLoopCount = 10; refreshLoopToggles() }
        btnCqLoopInf.setOnClickListener  { cqLoopCount = 0;  refreshLoopToggles() }
        btnCqInt10.setOnClickListener    { cqLoopInterv = 10; refreshLoopToggles() }
        btnCqInt15.setOnClickListener    { cqLoopInterv = 15; refreshLoopToggles() }
        btnCqInt30.setOnClickListener    { cqLoopInterv = 30; refreshLoopToggles() }
        btnCqInt60.setOnClickListener    { cqLoopInterv = 60; refreshLoopToggles() }
        // CQ repeat start/stop
        btnCqRepeat.setOnClickListener {
            if (vm.cwCqRepeating.value == true) {
                vm.stopCqRepeat()
            } else {
                saveAll()
                vm.startCqRepeat(btnCwCq.text.toString(), getWpm(), cqLoopInterv, cqLoopCount)
            }
        }

        // Text field changes
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { updateLabels() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        etDxCall.addTextChangedListener(watcher)
        etRst.addTextChangedListener(watcher)
        etPota.addTextChangedListener(watcher)
        etJcc.addTextChangedListener(watcher)

        // ── Preset button send actions ──
        btnCwCq.setOnClickListener      { send(btnCwCq.text.toString()) }
        btnCwCqDxK.setOnClickListener   { send(btnCwCqDxK.text.toString()) }
        btnCwCall.setOnClickListener    { send(btnCwCall.text.toString()) }
        btnCwCqTu.setOnClickListener    { send("TU TU 73 E E") }
        btnCwAnsCall.setOnClickListener { send(btnCwAnsCall.text.toString()) }
        btnCwAnsDE.setOnClickListener   { send(btnCwAnsDE.text.toString()) }
        btnCwUr.setOnClickListener      { send(btnCwUr.text.toString()) }
        btnCwTu.setOnClickListener      { send(btnCwTu.text.toString()) }
        btnCwAgn.setOnClickListener     { send("AGN") }

        btnSend.setOnClickListener {
            val text = etFree.text.toString().trim()
            if (text.isNotEmpty()) { vm.prefs.cwLastText = text; send(text) }
            else Toast.makeText(ctx, "Enter text", Toast.LENGTH_SHORT).show()
        }
        btnStop.setOnClickListener {
            vm.stopCwText()
            Toast.makeText(ctx, "CW stopped", Toast.LENGTH_SHORT).show()
        }

        updateCwTxSheetState()

        sheet.setOnDismissListener {
            saveAll()
            cwTxSheet = null
            cwSheetStopBtn = null
            cwSheetMsgButtons = emptyList()
            cwPanelUpdateLabels = null
            cwRepeatUpdateFn = null
            cwSheetFreqView = null
            cwSheetRxView = null
            cwSheetRxScroll = null
            cwSheetTxView = null
            cwSheetTxScroll = null
        }

        sheet.setOnShowListener {
            val bs = sheet.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            bs?.let {
                it.layoutParams?.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(it).apply {
                    state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }

        sheet.show()
    }

    private fun updateCwTxSheetState() {
        val busy = vm.cwTxBusy.value == true
        cwSheetStopBtn?.visibility = if (busy) View.VISIBLE else View.GONE
        // cwPanelUpdateLabels applies per-button placeholder + busy logic
        cwPanelUpdateLabels?.invoke()
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