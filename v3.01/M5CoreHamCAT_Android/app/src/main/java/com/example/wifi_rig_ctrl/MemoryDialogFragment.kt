package com.ji1ore.wifi_rig_ctrl

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ji1ore.wifi_rig_ctrl.data.*
import com.ji1ore.wifi_rig_ctrl.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MemoryDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_START_TAB = "start_tab"
        private val BAND_ORDER = listOf(
            "160m","80m","60m","40m","30m","20m","17m","15m","12m","10m","6m","2m","70cm","Other"
        )
        fun newInstance(startTab: Int = -1) = MemoryDialogFragment().apply {
            arguments = Bundle().apply { putInt(ARG_START_TAB, startTab) }
        }
    }

    private val vm: MainViewModel by activityViewModels()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // Page views
    private lateinit var presetPage: View
    private lateinit var userPage:   View
    private lateinit var potaPage:   View
    private lateinit var sotaPage:   View

    // Adapters
    private lateinit var presetAdapter: PresetAdapter
    private lateinit var userAdapter:   MemoryAdapter
    private lateinit var potaAdapter:   SpotAdapter
    private lateinit var sotaAdapter:   SpotAdapter

    // Raw spot data
    private var allPotaSpots: List<PotaSpot> = emptyList()
    private var allSotaSpots: List<SotaSpot> = emptyList()

    // POTA filter/sort
    private var potaModeFilter    = ""
    private var potaBandFilter    = ""
    private var potaProgramFilter = ""
    private var potaTextFilter    = ""
    private var potaSort          = 0

    // SOTA filter/sort
    private var sotaModeFilter    = ""
    private var sotaBandFilter    = ""
    private var sotaAreaFilter    = ""
    private var sotaTextFilter    = ""
    private var sotaSort          = 0

    // Spinner refs — replaced after data loads
    private lateinit var spnPotaMode:    Spinner
    private lateinit var spnPotaBand:    Spinner
    private lateinit var spnPotaProgram: Spinner
    private lateinit var spnSotaMode:    Spinner
    private lateinit var spnSotaBand:    Spinner
    private lateinit var spnSotaArea:    Spinner

    // Progress / empty-state
    private lateinit var pbPota:      ProgressBar
    private lateinit var tvPotaEmpty: TextView
    private lateinit var pbSota:      ProgressBar
    private lateinit var tvSotaEmpty: TextView

    // ======================== LIFECYCLE ========================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        potaModeFilter    = vm.prefs.memPotaModeFilter
        potaBandFilter    = vm.prefs.memPotaBandFilter
        potaProgramFilter = vm.prefs.memPotaProgramFilter
        potaSort          = vm.prefs.memPotaSort
        sotaModeFilter    = vm.prefs.memSotaModeFilter
        sotaBandFilter    = vm.prefs.memSotaBandFilter
        sotaAreaFilter    = vm.prefs.memSotaAreaFilter
        sotaSort          = vm.prefs.memSotaSort

        presetPage = buildPresetPage()
        userPage   = buildUserPage()
        potaPage   = buildPotaPage()
        sotaPage   = buildSotaPage()

        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val titleBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(32, 20, 16, 8); gravity = Gravity.CENTER_VERTICAL
        }
        TextView(ctx).apply {
            text = "SET"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }.also { titleBar.addView(it) }
        Button(ctx).apply { text = "Close"; setOnClickListener { dismiss() } }.also { titleBar.addView(it) }
        root.addView(titleBar)

        val tabLayout = TabLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            tabMode = TabLayout.MODE_FIXED
            setSelectedTabIndicatorColor(0xFF4DD0E1.toInt())
            setTabTextColors(0xFF888888.toInt(), 0xFF4DD0E1.toInt())
        }
        root.addView(tabLayout)

        val viewPager = ViewPager2(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            offscreenPageLimit = 3
        }
        viewPager.adapter = PageAdapter()
        root.addView(viewPager)

        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = when (pos) { 0 -> "Preset"; 1 -> "User"; 2 -> "POTA"; 3 -> "SOTA"; else -> "" }
        }.attach()

        val argTab  = arguments?.getInt(ARG_START_TAB, -1) ?: -1
        val initTab = if (argTab >= 0) argTab else vm.prefs.memLastTab
        viewPager.post { viewPager.currentItem = initTab.coerceIn(0, 3) }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                vm.prefs.memLastTab = position
                if (position == 2 && allPotaSpots.isEmpty()) fetchPota()
                if (position == 3 && allSotaSpots.isEmpty()) fetchSota()
            }
        })
        if (initTab == 2) fetchPota()
        if (initTab == 3) fetchSota()

        return root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val h = (resources.displayMetrics.heightPixels * 0.88).toInt()
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
            setGravity(Gravity.BOTTOM)
        }
    }

    // ======================== PAGE BUILDERS ========================

    private fun buildPresetPage(): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val hdr = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(16, 8, 16, 4); gravity = Gravity.CENTER_VERTICAL
        }
        TextView(ctx).apply {
            text = "Presets (long-press to edit/delete)"; textSize = 12f; setTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }.also { hdr.addView(it) }
        Button(ctx).apply {
            text = "+ Add"; textSize = 11f
            setOnClickListener { openEditDialog(null, -1, isPresetTab = true) }
        }.also { hdr.addView(it) }
        Button(ctx).apply {
            text = "Reset"; textSize = 11f
            setOnClickListener { confirmResetPresets() }
        }.also { hdr.addView(it) }
        root.addView(hdr)

        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        presetAdapter = PresetAdapter(
            buildPresetRows(vm.prefs.getCustomPresets()),
            onTap    = { mem -> recallAndDismiss(mem) },
            onEdit   = { mem, origIdx -> openEditDialog(mem, origIdx, isPresetTab = true) },
            onDelete = { origIdx ->
                val list = vm.prefs.getCustomPresets(); list.removeAt(origIdx)
                vm.prefs.saveCustomPresets(list)
                presetAdapter.rows = buildPresetRows(list); presetAdapter.notifyDataSetChanged()
            }
        )
        rv.adapter = presetAdapter; root.addView(rv)
        return root
    }

    private fun buildPresetRows(presets: List<MemoryEntry>): List<PresetRow> {
        val result = mutableListOf<PresetRow>()
        var lastBand = ""
        presets.forEachIndexed { idx, mem ->
            val band = mem.name.substringBefore(" ")
            if (band != lastBand) { result.add(PresetRow.Header(band)); lastBand = band }
            result.add(PresetRow.Entry(mem, idx))
        }
        return result
    }

    private fun buildUserPage(): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val hdr = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(16, 8, 16, 4); gravity = Gravity.CENTER_VERTICAL
        }
        TextView(ctx).apply {
            text = "User Memories"; textSize = 12f; setTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }.also { hdr.addView(it) }
        Button(ctx).apply {
            text = "+ Add"; textSize = 11f
            setOnClickListener { openEditDialog(null, -1, isPresetTab = false) }
        }.also { hdr.addView(it) }
        root.addView(hdr)

        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            addItemDecoration(DividerItemDecoration(ctx, DividerItemDecoration.VERTICAL))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        userAdapter = MemoryAdapter(
            entries  = vm.prefs.getUserMemories(),
            onTap    = { mem -> recallAndDismiss(mem) },
            onEdit   = { mem, idx -> openEditDialog(mem, idx, isPresetTab = false) },
            onDelete = { idx ->
                val list = vm.prefs.getUserMemories(); list.removeAt(idx)
                vm.prefs.saveUserMemories(list)
                userAdapter.entries = list; userAdapter.notifyItemRemoved(idx)
            }
        )
        rv.adapter = userAdapter; root.addView(rv)
        return root
    }

    private fun buildPotaPage(): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(8, 6, 8, 2); gravity = Gravity.CENTER_VERTICAL
        }
        spnPotaMode    = placeholderSpinner(ctx, "Mode: ALL")
        spnPotaBand    = placeholderSpinner(ctx, "Band: ALL")
        spnPotaMode.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        spnPotaBand.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row1.addView(spnPotaMode); row1.addView(spnPotaBand)
        row1.addView(Button(ctx).apply {
            text = "🔄"; setOnClickListener { allPotaSpots = emptyList(); fetchPota() }
        })
        root.addView(row1)

        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(8, 2, 8, 4); gravity = Gravity.CENTER_VERTICAL
        }
        spnPotaProgram = placeholderSpinner(ctx, "Prog: ALL")
        spnPotaProgram.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val spnSort = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("By Freq", "By Call", "By Park"))
            setSelection(potaSort)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row2.addView(spnPotaProgram); row2.addView(spnSort)
        root.addView(row2)

        root.addView(EditText(ctx).apply {
            hint = "Search…"; textSize = 13f; setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(0xFF666666.toInt())
            setPadding(24, 4, 24, 4)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { potaTextFilter = s?.toString().orEmpty(); applyPotaFilter() }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        })

        pbPota = ProgressBar(ctx).apply { visibility = View.GONE }
        root.addView(pbPota)

        tvPotaEmpty = TextView(ctx).apply {
            text = "Press 🔄 to load"
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 14f; setPadding(16, 48, 16, 16)
            visibility = View.GONE
        }
        root.addView(tvPotaEmpty)

        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            addItemDecoration(DividerItemDecoration(ctx, DividerItemDecoration.VERTICAL))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        potaAdapter = SpotAdapter(emptyList()) { freqHz, mode -> recallAndDismiss(freqHz, mode) }
        rv.adapter = potaAdapter; root.addView(rv)

        root.post {
            spnSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    potaSort = pos; vm.prefs.memPotaSort = pos; applyPotaFilter()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        return root
    }

    private fun buildSotaPage(): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(8, 6, 8, 2); gravity = Gravity.CENTER_VERTICAL
        }
        spnSotaMode = placeholderSpinner(ctx, "Mode: ALL")
        spnSotaBand = placeholderSpinner(ctx, "Band: ALL")
        spnSotaMode.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        spnSotaBand.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row1.addView(spnSotaMode); row1.addView(spnSotaBand)
        row1.addView(Button(ctx).apply {
            text = "🔄"; setOnClickListener { allSotaSpots = emptyList(); fetchSota() }
        })
        root.addView(row1)

        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(8, 2, 8, 4); gravity = Gravity.CENTER_VERTICAL
        }
        spnSotaArea = placeholderSpinner(ctx, "Area: ALL")
        spnSotaArea.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val spnSort = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("By Freq", "By Call", "By Summit"))
            setSelection(sotaSort)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row2.addView(spnSotaArea); row2.addView(spnSort)
        root.addView(row2)

        root.addView(EditText(ctx).apply {
            hint = "Search…"; textSize = 13f; setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(0xFF666666.toInt())
            setPadding(24, 4, 24, 4)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { sotaTextFilter = s?.toString().orEmpty(); applySotaFilter() }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        })

        pbSota = ProgressBar(ctx).apply { visibility = View.GONE }
        root.addView(pbSota)

        tvSotaEmpty = TextView(ctx).apply {
            text = "Press 🔄 to load"
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 14f; setPadding(16, 48, 16, 16)
            visibility = View.GONE
        }
        root.addView(tvSotaEmpty)

        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            addItemDecoration(DividerItemDecoration(ctx, DividerItemDecoration.VERTICAL))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        sotaAdapter = SpotAdapter(emptyList()) { freqHz, mode -> recallAndDismiss(freqHz, mode) }
        rv.adapter = sotaAdapter; root.addView(rv)

        root.post {
            spnSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    sotaSort = pos; vm.prefs.memSotaSort = pos; applySotaFilter()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        return root
    }

    // ======================== HELPERS ========================

    private fun placeholderSpinner(ctx: android.content.Context, label: String): Spinner =
        Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, arrayOf(label))
        }

    private fun reloadSpinner(
        spn: Spinner,
        allLabel: String,
        values: List<String>,
        savedValue: String,
        onSelect: (String) -> Unit
    ) {
        if (!isAdded) return
        val ctx = requireContext()
        spn.onItemSelectedListener = null
        val options = arrayOf(allLabel) + values.toTypedArray()
        spn.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, options)
        val idx = if (savedValue.isEmpty()) 0
                  else values.indexOfFirst { it == savedValue }.let { if (it < 0) 0 else it + 1 }
        spn.setSelection(idx)
        spn.post {
            spn.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    onSelect(if (pos == 0) "" else values.getOrNull(pos - 1) ?: "")
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
    }

    private fun freqToBand(mhz: Double): String = when {
        mhz in 1.8..2.0       -> "160m"
        mhz in 3.5..4.0       -> "80m"
        mhz in 5.3..5.405     -> "60m"
        mhz in 7.0..7.3       -> "40m"
        mhz in 10.1..10.15    -> "30m"
        mhz in 14.0..14.35    -> "20m"
        mhz in 18.068..18.168 -> "17m"
        mhz in 21.0..21.45    -> "15m"
        mhz in 24.89..24.99   -> "12m"
        mhz in 28.0..29.7     -> "10m"
        mhz in 50.0..54.0     -> "6m"
        mhz in 144.0..148.0   -> "2m"
        mhz in 430.0..440.0   -> "70cm"
        else                   -> "Other"
    }

    private fun updatePotaSpinners() {
        val modes    = allPotaSpots.map { it.mode.uppercase() }.filter { it.isNotEmpty() }.toSortedSet().toList()
        val bands    = allPotaSpots.map { freqToBand(it.freqMhz) }.toSet()
            .sortedBy { b -> BAND_ORDER.indexOf(b).let { if (it < 0) 99 else it } }
        val programs = allPotaSpots.map { it.reference.substringBefore("-") }.filter { it.isNotEmpty() }.toSortedSet().toList()

        // 取得データに存在しない保存フィルターはリセット（全件フィルターアウト防止）
        if (potaModeFilter.isNotEmpty()    && potaModeFilter    !in modes)    { potaModeFilter    = ""; vm.prefs.memPotaModeFilter    = "" }
        if (potaBandFilter.isNotEmpty()    && potaBandFilter    !in bands)    { potaBandFilter    = ""; vm.prefs.memPotaBandFilter    = "" }
        if (potaProgramFilter.isNotEmpty() && potaProgramFilter !in programs) { potaProgramFilter = ""; vm.prefs.memPotaProgramFilter = "" }

        reloadSpinner(spnPotaMode,    "Mode: ALL", modes,    potaModeFilter)    { v -> potaModeFilter    = v; vm.prefs.memPotaModeFilter    = v; applyPotaFilter() }
        reloadSpinner(spnPotaBand,    "Band: ALL", bands,    potaBandFilter)    { v -> potaBandFilter    = v; vm.prefs.memPotaBandFilter    = v; applyPotaFilter() }
        reloadSpinner(spnPotaProgram, "Prog: ALL", programs, potaProgramFilter) { v -> potaProgramFilter = v; vm.prefs.memPotaProgramFilter = v; applyPotaFilter() }
    }

    private fun updateSotaSpinners() {
        val modes = allSotaSpots.map { it.mode?.uppercase().orEmpty() }.filter { it.isNotEmpty() }.toSortedSet().toList()
        val bands = allSotaSpots.map { freqToBand(it.freqMhz) }.toSet()
            .sortedBy { b -> BAND_ORDER.indexOf(b).let { if (it < 0) 99 else it } }
        val areas = allSotaSpots.map { it.associationCode.orEmpty() }.filter { it.isNotEmpty() }.toSortedSet().toList()

        // 取得データに存在しない保存フィルターはリセット（全件フィルターアウト防止）
        if (sotaModeFilter.isNotEmpty() && sotaModeFilter !in modes) { sotaModeFilter = ""; vm.prefs.memSotaModeFilter = "" }
        if (sotaBandFilter.isNotEmpty() && sotaBandFilter !in bands) { sotaBandFilter = ""; vm.prefs.memSotaBandFilter = "" }
        if (sotaAreaFilter.isNotEmpty() && sotaAreaFilter !in areas) { sotaAreaFilter = ""; vm.prefs.memSotaAreaFilter = "" }

        reloadSpinner(spnSotaMode, "Mode: ALL",  modes, sotaModeFilter) { v -> sotaModeFilter = v; vm.prefs.memSotaModeFilter = v; applySotaFilter() }
        reloadSpinner(spnSotaBand, "Band: ALL",  bands, sotaBandFilter) { v -> sotaBandFilter = v; vm.prefs.memSotaBandFilter = v; applySotaFilter() }
        reloadSpinner(spnSotaArea, "Area: ALL",  areas, sotaAreaFilter) { v -> sotaAreaFilter = v; vm.prefs.memSotaAreaFilter = v; applySotaFilter() }
    }

    // ======================== ACTIONS ========================

    private fun modeDefaultWidth(mode: String): Int = when {
        mode == "FM" || mode == "PKTFM" -> 12000
        mode == "FMN"                   -> 9000
        mode.startsWith("CW")           -> vm.prefs.defaultCwWidth
        mode == "USB" || mode == "LSB"  -> vm.prefs.defaultSsbWidth
        else                            -> 0
    }

    /** POTA/SOTA APIのモード文字列を無線機互換モードに変換する */
    private fun normalizeSpotMode(mode: String, freqHz: Long): String = when (mode.uppercase()) {
        "SSB"                                        -> if (freqHz >= 10_000_000L) "USB" else "LSB"
        "FT8", "FT4", "JS8", "WSPR", "PSK31",
        "PSK63", "JT65", "JT9"                      -> "USB"
        else                                         -> mode.uppercase()
    }

    private fun recallAndDismiss(mem: MemoryEntry) {
        vm.sendFreq(mem.freqHz)
        if (mem.mode.isNotEmpty()) {
            val w = modeDefaultWidth(mem.mode)
            vm.sendMode(mem.mode, w)
        }
        val step = if (mem.stepIndex >= 0) mem.stepIndex
                   else vm.prefs.getModeStep(mem.mode.ifEmpty { vm.sharedMode.value ?: "" })
        vm.selectedStep.value = step
        // リピーター設定を適用（何も設定されていなければクリア）
        vm.setRepeater(mem.toneMode.orEmpty(), mem.toneHz ?: 0.0, mem.offsetDir.orEmpty(), mem.offsetHz ?: 0L)
        dismiss()
    }

    private fun recallAndDismiss(freqHz: Long, mode: String) {
        if (freqHz <= 0) return
        vm.sendFreq(freqHz)
        val raw = mode.uppercase()
        if (raw.isNotEmpty() && raw != "UNKNOWN" && raw != "OTHER") {
            val m = normalizeSpotMode(raw, freqHz)
            vm.sendMode(m, modeDefaultWidth(m))
        }
        dismiss()
    }

    private fun confirmResetPresets() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset Presets")
            .setMessage("Restore all presets to defaults?\n(User memories are unchanged)")
            .setPositiveButton("Reset") { _, _ ->
                vm.prefs.resetCustomPresets()
                presetAdapter.rows = buildPresetRows(vm.prefs.getCustomPresets())
                presetAdapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun openEditDialog(editEntry: MemoryEntry?, editIndex: Int, isPresetTab: Boolean) {
        val ctx = requireContext()
        val initFreq = editEntry?.freqHz ?: (vm.sharedFreq.value ?: 0L)
        val initMode = editEntry?.mode ?: (vm.sharedMode.value ?: "")
        val initStep = if (editEntry != null && editEntry.stepIndex >= 0) editEntry.stepIndex
                       else (vm.selectedStep.value ?: vm.prefs.getModeStep(initMode))
                           .coerceIn(0, STEP_LIST.size - 1)

        val ctcssTones = doubleArrayOf(
            67.0, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5, 94.8,
            97.4, 100.0, 103.5, 107.2, 110.9, 114.8, 118.8, 123.0, 127.3, 131.8,
            136.5, 141.3, 146.2, 151.4, 156.7, 162.2, 167.9, 173.8, 179.9, 186.2,
            192.8, 203.5, 210.7, 218.1, 225.7, 233.6, 241.8, 250.3, 254.1
        )
        val toneModes   = arrayOf("None", "Tone", "TSQL", "DTCS")
        val toneLabels  = ctcssTones.map { "%.1f Hz".format(it) }.toTypedArray()
        val offsetDirs  = arrayOf("None", "+", "-")

        // 編集時はメモリの値、新規時は現在のリピーター設定を初期値に
        val initToneMode  = editEntry?.toneMode.orEmpty().ifEmpty { vm.repeaterToneMode.value.orEmpty() }
        val initToneHz    = (editEntry?.toneHz ?: vm.repeaterToneHz.value) ?: 0.0
        val initOffsetDir = editEntry?.offsetDir.orEmpty().ifEmpty { vm.repeaterOffsetDir.value.orEmpty() }
        val initOffsetHz  = (editEntry?.offsetHz ?: vm.repeaterOffsetHz.value) ?: 0L

        val sv = android.widget.ScrollView(ctx)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(56, 16, 56, 8) }
        sv.addView(root)

        fun label(t: String) = TextView(ctx).apply {
            text = t; setTextColor(0xFF888888.toInt()); textSize = 11f; setPadding(0, 8, 0, 2)
        }
        val etFreq = EditText(ctx).apply {
            hint = "e.g. 14.025000"
            setText("%.5f".format(initFreq / 1_000_000.0))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val etName = EditText(ctx).apply { hint = "Name (required)"; setText(editEntry?.name ?: "") }
        val modeOptions = arrayOf("(Keep current)", "LSB", "USB", "CW", "CWR", "AM", "FM", "C4FM", "DV", "RTTY", "PSK")
        val initModeIdx = modeOptions.indexOfFirst { it == initMode }.let { if (it < 0) 0 else it }
        val spinMode = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, modeOptions)
            setSelection(initModeIdx)
        }
        val spinStep = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                STEP_LIST.map { it.label }.toTypedArray())
            setSelection(initStep)
        }
        val cbStep = CheckBox(ctx).apply {
            text = "Save Step"; isChecked = editEntry?.stepIndex != -1
            setOnCheckedChangeListener { _, c -> spinStep.isEnabled = c }
        }
        spinStep.isEnabled = cbStep.isChecked

        // Repeater fields
        val spinToneMode = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, toneModes)
            setSelection(when (initToneMode.orEmpty()) { "Tone" -> 1; "TSQL" -> 2; "DTCS" -> 3; else -> 0 })
        }
        val nearestToneIdx = ctcssTones.indices.minByOrNull { Math.abs(ctcssTones[it] - initToneHz) } ?: 7
        val spinToneHz = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, toneLabels)
            setSelection(nearestToneIdx)
            isEnabled = initToneMode.isNotEmpty()
        }
        spinToneMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                spinToneHz.isEnabled = pos > 0
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        val spinOffsetDir = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, offsetDirs)
            setSelection(when (initOffsetDir.orEmpty()) { "+" -> 1; "-" -> 2; else -> 0 })
        }
        val etOffsetKhz = EditText(ctx).apply {
            hint = "Offset (kHz) e.g. 600"
            setText(if ((initOffsetHz) > 0) (initOffsetHz / 1000).toString() else "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        root.addView(label("Freq (MHz)")); root.addView(etFreq)
        root.addView(label("Name"));      root.addView(etName)
        root.addView(label("Mode"));      root.addView(spinMode)
        root.addView(cbStep);             root.addView(spinStep)
        root.addView(label("── Repeater ──────────────"))
        root.addView(label("Tone Mode")); root.addView(spinToneMode)
        root.addView(label("CTCSS/Tone (Hz)")); root.addView(spinToneHz)
        root.addView(label("Offset Direction")); root.addView(spinOffsetDir)
        root.addView(label("Offset (kHz)")); root.addView(etOffsetKhz)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (editEntry != null) "Edit Memory" else "Add Memory")
            .setView(sv).setPositiveButton("Save", null).setNegativeButton("Cancel", null).show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(ctx, "Name is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val hz       = ((etFreq.text.toString().toDoubleOrNull() ?: (initFreq / 1_000_000.0)) * 1_000_000).toLong()
            val mode     = if (spinMode.selectedItemPosition == 0) "" else modeOptions[spinMode.selectedItemPosition]
            val step     = if (cbStep.isChecked) spinStep.selectedItemPosition else -1
            val toneMode = when (spinToneMode.selectedItemPosition) { 1 -> "Tone"; 2 -> "TSQL"; 3 -> "DTCS"; else -> "" }
            val toneHz   = if (toneMode.isNotEmpty()) ctcssTones[spinToneHz.selectedItemPosition] else 0.0
            val offsetDir = when (spinOffsetDir.selectedItemPosition) { 1 -> "+"; 2 -> "-"; else -> "" }
            val offsetHz  = (etOffsetKhz.text.toString().toLongOrNull() ?: 0L) * 1000L
            val entry = MemoryEntry(name, hz, mode, step, isPresetTab, toneMode, toneHz, offsetDir, offsetHz)
            if (isPresetTab) {
                val list = vm.prefs.getCustomPresets()
                if (editIndex >= 0) list[editIndex] = entry else list.add(entry)
                vm.prefs.saveCustomPresets(list)
                presetAdapter.rows = buildPresetRows(list); presetAdapter.notifyDataSetChanged()
            } else {
                val list = vm.prefs.getUserMemories()
                if (editIndex >= 0) list[editIndex] = entry else list.add(entry)
                vm.prefs.saveUserMemories(list)
                userAdapter.entries = list; userAdapter.notifyDataSetChanged()
            }
            Toast.makeText(ctx, if (editEntry != null) "Updated: $name" else "Saved: $name", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    // ======================== POTA / SOTA FETCH ========================

    private fun fetchPota() {
        if (!isAdded) return
        pbPota.visibility = View.VISIBLE; tvPotaEmpty.visibility = View.GONE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = http.newCall(
                    Request.Builder().url("https://api.pota.app/spot/activator").build()
                ).execute().body?.string() ?: "[]"
                val type  = object : TypeToken<List<PotaSpot>>() {}.type
                val spots: List<PotaSpot> = gson.fromJson(body, type) ?: emptyList()
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    allPotaSpots = spots.filter { !it.invalid }
                    pbPota.visibility = View.GONE
                    updatePotaSpinners()
                    applyPotaFilter()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    pbPota.visibility = View.GONE
                    tvPotaEmpty.text = "Fetch failed: ${e.message}\nPress 🔄 to retry"
                    tvPotaEmpty.setTextColor(0xFFFF6B6B.toInt())
                    tvPotaEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun fetchSota() {
        if (!isAdded) return
        pbSota.visibility = View.VISIBLE; tvSotaEmpty.visibility = View.GONE
        lifecycleScope.launch(Dispatchers.IO) {
            var rawBody = ""
            try {
                val resp = http.newCall(
                    Request.Builder()
                        .url("https://api2.sota.org.uk/api/spots/60/-1")
                        .header("Accept", "application/json")
                        .build()
                ).execute()
                rawBody = resp.body?.string() ?: "[]"
                val type  = object : TypeToken<List<SotaSpot>>() {}.type
                val spots: List<SotaSpot> = gson.fromJson(rawBody, type) ?: emptyList()
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    allSotaSpots = spots
                    pbSota.visibility = View.GONE
                    updateSotaSpinners()
                    applySotaFilter()
                }
            } catch (e: Exception) {
                val preview = if (rawBody.length > 120) rawBody.take(120) + "…" else rawBody
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    pbSota.visibility = View.GONE
                    tvSotaEmpty.text = "Fetch failed: ${e.message}\n$preview\nPress 🔄 to retry"
                    tvSotaEmpty.setTextColor(0xFFFF6B6B.toInt())
                    tvSotaEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun applyPotaFilter() {
        val q = potaTextFilter.lowercase().trim()
        val filtered = allPotaSpots.filter { spot ->
            (potaModeFilter.isEmpty()    || spot.mode.uppercase() == potaModeFilter) &&
            (potaBandFilter.isEmpty()    || freqToBand(spot.freqMhz) == potaBandFilter) &&
            (potaProgramFilter.isEmpty() || spot.reference.substringBefore("-") == potaProgramFilter) &&
            (q.isEmpty() || listOf(spot.activator, spot.name, spot.reference, spot.comments, spot.spotter)
                .any { it.lowercase().contains(q) })
        }.let { list ->
            when (potaSort) {
                1    -> list.sortedBy { it.activator }
                2    -> list.sortedBy { it.name }
                else -> list.sortedBy { it.freqMhz }
            }
        }
        potaAdapter.update(filtered.map { spot ->
            val ref = spot.reference
            val l2  = "${spot.name} ($ref)"
            val ss2 = SpannableString(l2)
            val refStart = l2.length - ref.length - 1  // position just after '('
            if (refStart >= 0 && ref.isNotEmpty())
                ss2.setSpan(StyleSpan(Typeface.BOLD), refStart, refStart + ref.length, 0)
            SpotItem(
                line1  = "${spot.activator}   ${"%.3f".format(spot.freqMhz)} MHz   ${spot.mode}",
                line2  = ss2,
                line3  = buildString {
                    append(formatRelTime(spot.spotTime))
                    val c = spot.comments.trim()
                    if (c.isNotEmpty()) append("  💬 $c")
                },
                freqHz = spot.freqHz,
                mode   = spot.mode
            )
        })
        tvPotaEmpty.setTextColor(Color.WHITE)
        updateEmptyLabel(tvPotaEmpty, allPotaSpots.size, filtered.size)
    }

    private fun applySotaFilter() {
        val q = sotaTextFilter.lowercase().trim()
        val filtered = allSotaSpots.filter { spot ->
            (sotaModeFilter.isEmpty() || spot.mode?.uppercase().orEmpty() == sotaModeFilter) &&
            (sotaBandFilter.isEmpty() || freqToBand(spot.freqMhz) == sotaBandFilter) &&
            (sotaAreaFilter.isEmpty() || spot.associationCode.orEmpty() == sotaAreaFilter) &&
            (q.isEmpty() || listOf(
                spot.activatorCallsign.orEmpty(), spot.summitDetails.orEmpty(),
                spot.summitCode.orEmpty(), spot.associationCode.orEmpty(), spot.comments.orEmpty()
            ).any { it.lowercase().contains(q) })
        }.let { list ->
            when (sotaSort) {
                1    -> list.sortedBy { it.activatorCallsign.orEmpty() }
                2    -> list.sortedBy { it.summitDetails.orEmpty() }
                else -> list.sortedBy { it.freqMhz }
            }
        }
        sotaAdapter.update(filtered.map { spot ->
            val assoc   = spot.associationCode.orEmpty()
            val summit  = spot.summitCode.orEmpty()
            val codeStr = listOf(assoc, summit).filter { it.isNotEmpty() }.joinToString("/")
            val l2      = "${spot.summitDetails.orEmpty()} ($codeStr)"
            val ss2 = SpannableString(l2)
            val refStart = l2.length - codeStr.length - 1  // position just after '('
            if (refStart >= 0 && codeStr.isNotEmpty())
                ss2.setSpan(StyleSpan(Typeface.BOLD), refStart, refStart + codeStr.length, 0)
            SpotItem(
                line1  = "${spot.activatorCallsign.orEmpty()}   ${"%.3f".format(spot.freqMhz)} MHz   ${spot.mode.orEmpty()}",
                line2  = ss2,
                line3  = buildString {
                    append(formatRelTime(spot.timeStamp.orEmpty()))
                    val c = spot.comments?.trim().orEmpty()
                    if (c.isNotEmpty()) append("  💬 $c")
                },
                freqHz = spot.freqHz,
                mode   = spot.mode.orEmpty()
            )
        })
        tvSotaEmpty.setTextColor(Color.WHITE)
        updateEmptyLabel(tvSotaEmpty, allSotaSpots.size, filtered.size)
    }

    private fun updateEmptyLabel(tv: TextView, total: Int, filtered: Int) {
        when {
            total == 0    -> { tv.text = "No spots — press 🔄 to reload"; tv.visibility = View.VISIBLE }
            filtered == 0 -> { tv.text = "No spots match the selected filter"; tv.visibility = View.VISIBLE }
            else          -> tv.visibility = View.GONE
        }
    }

    private fun formatRelTime(iso: String): String {
        return try {
            val fmts = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS")
            val date = fmts.mapNotNull { f ->
                try { SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso) }
                catch (_: Exception) { null }
            }.firstOrNull() ?: return iso
            val sec = (System.currentTimeMillis() - date.time) / 1000
            when {
                sec < 60   -> "${sec}s ago"
                sec < 3600 -> "${sec / 60}m ago"
                else       -> "${sec / 3600}h ${(sec % 3600) / 60}m ago"
            }
        } catch (_: Exception) { iso }
    }

    // ======================== PAGE ADAPTER ========================

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v)
        private val pages get() = listOf(presetPage, userPage, potaPage, sotaPage)
        override fun getItemCount() = 4
        override fun getItemViewType(pos: Int) = pos
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = pages[viewType]
            v.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {}
    }

    // ======================== PRESET (BAND-GROUPED) ADAPTER ========================

    sealed class PresetRow {
        class Header(val band: String) : PresetRow()
        class Entry(val mem: MemoryEntry, val originalIndex: Int) : PresetRow()
    }

    inner class PresetAdapter(
        var rows:             List<PresetRow>,
        private val onTap:    (MemoryEntry) -> Unit,
        private val onEdit:   (MemoryEntry, Int) -> Unit,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private inner class HeaderVH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        private inner class EntryVH(root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val tvName:   TextView = root.getChildAt(0) as TextView
            val tvDetail: TextView = root.getChildAt(1) as TextView
        }

        override fun getItemCount() = rows.size
        override fun getItemViewType(pos: Int) = if (rows[pos] is PresetRow.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            return if (viewType == 0) {
                HeaderVH(TextView(ctx).apply {
                    setPadding(32, 18, 32, 6); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                    setTextColor(0xFF4DD0E1.toInt())
                })
            } else {
                val root = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(48, 16, 32, 14)
                    isClickable = true; isFocusable = true
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }
                root.addView(TextView(ctx).apply { textSize = 16f; setTextColor(Color.WHITE) })
                root.addView(TextView(ctx).apply { textSize = 12f; setTextColor(0xFFAAAAAA.toInt()) })
                EntryVH(root)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is PresetRow.Header -> (holder as HeaderVH).tv.text = "── ${row.band} ──"
                is PresetRow.Entry  -> {
                    val vh   = holder as EntryVH
                    val mem  = row.mem
                    val freq = "%.5f MHz".format(mem.freqHz / 1_000_000.0)
                    val mode = if (mem.mode.isEmpty()) "(current mode)" else mem.mode
                    val step = if (mem.stepIndex >= 0) "  St:${STEP_LIST.getOrNull(mem.stepIndex)?.label ?: ""}" else ""
                    val rpt  = buildString {
                        if (!mem.toneMode.isNullOrEmpty()) append("  ${mem.toneMode}:${"%.1f".format(mem.toneHz ?: 0.0)}Hz")
                        if (!mem.offsetDir.isNullOrEmpty()) append("  ${mem.offsetDir}${(mem.offsetHz ?: 0L)/1000}kHz")
                    }
                    vh.tvName.text   = mem.name
                    vh.tvDetail.text = "$freq  $mode$step$rpt"
                    holder.itemView.setOnClickListener { onTap(mem) }
                    holder.itemView.setOnLongClickListener {
                        AlertDialog.Builder(it.context).setTitle(mem.name)
                            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                                if (which == 0) onEdit(mem, row.originalIndex)
                                else AlertDialog.Builder(it.context)
                                    .setMessage("Delete \"${mem.name}\"?")
                                    .setPositiveButton("Delete") { _, _ -> onDelete(row.originalIndex) }
                                    .setNegativeButton("Cancel", null).show()
                            }.setNegativeButton("Cancel", null).show()
                        true
                    }
                }
            }
        }
    }

    // ======================== MEMORY ADAPTER (User tab) ========================

    inner class MemoryAdapter(
        var entries:          MutableList<MemoryEntry>,
        private val onTap:    (MemoryEntry) -> Unit,
        private val onEdit:   (MemoryEntry, Int) -> Unit,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<MemoryAdapter.VH>() {

        inner class VH(root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val tvName:   TextView = root.getChildAt(0) as TextView
            val tvDetail: TextView = root.getChildAt(1) as TextView
        }

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(32, 20, 32, 16)
                isClickable = true; isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            root.addView(TextView(ctx).apply {
                textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            })
            root.addView(TextView(ctx).apply { textSize = 12f; setTextColor(0xFFAAAAAA.toInt()) })
            return VH(root)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val mem = entries[position]
            holder.tvName.text = mem.name
            val freq = "%.5f MHz".format(mem.freqHz / 1_000_000.0)
            val mode = if (mem.mode.isEmpty()) "(current mode)" else mem.mode
            val step = if (mem.stepIndex >= 0) "  St:${STEP_LIST.getOrNull(mem.stepIndex)?.label ?: ""}" else ""
            val rpt  = buildString {
                if (!mem.toneMode.isNullOrEmpty()) append("  ${mem.toneMode}:${"%.1f".format(mem.toneHz ?: 0.0)}Hz")
                if (!mem.offsetDir.isNullOrEmpty()) append("  ${mem.offsetDir}${(mem.offsetHz ?: 0L)/1000}kHz")
            }
            holder.tvDetail.text = "$freq  $mode$step$rpt"
            holder.itemView.setOnClickListener { onTap(mem) }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(it.context).setTitle(mem.name)
                    .setItems(arrayOf("Edit", "Delete")) { _, which ->
                        if (which == 0) onEdit(mem, position)
                        else AlertDialog.Builder(it.context)
                            .setMessage("Delete \"${mem.name}\"?")
                            .setPositiveButton("Delete") { _, _ -> onDelete(position) }
                            .setNegativeButton("Cancel", null).show()
                    }.setNegativeButton("Cancel", null).show()
                true
            }
        }
    }

    // ======================== SPOT ADAPTER ========================

    data class SpotItem(
        val line1: String, val line2: CharSequence, val line3: String,
        val freqHz: Long, val mode: String
    )

    inner class SpotAdapter(
        private var items: List<SpotItem>,
        private val onTap: (freqHz: Long, mode: String) -> Unit
    ) : RecyclerView.Adapter<SpotAdapter.VH>() {

        inner class VH(root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val tv1: TextView = root.getChildAt(0) as TextView
            val tv2: TextView = root.getChildAt(1) as TextView
            val tv3: TextView = root.getChildAt(2) as TextView
        }

        fun update(newItems: List<SpotItem>) { items = newItems; notifyDataSetChanged() }
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(32, 14, 32, 10)
                isClickable = true; isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            root.addView(TextView(ctx).apply {
                textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            })
            root.addView(TextView(ctx).apply { textSize = 13f; setTextColor(0xFF4DD0E1.toInt()) })
            // Comment line — lighter gray so it's readable on dark background
            root.addView(TextView(ctx).apply { textSize = 12f; setTextColor(0xFFCCCCCC.toInt()) })
            return VH(root)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tv1.text = item.line1
            holder.tv2.text = item.line2
            holder.tv3.text = item.line3
            holder.itemView.setOnClickListener { onTap(item.freqHz, item.mode) }
        }
    }
}
