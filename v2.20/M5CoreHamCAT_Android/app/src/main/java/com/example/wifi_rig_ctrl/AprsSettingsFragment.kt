package com.ji1ore.wifi_rig_ctrl

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ji1ore.wifi_rig_ctrl.data.*
import com.ji1ore.wifi_rig_ctrl.databinding.FragmentAprsSettingsBinding
import com.ji1ore.wifi_rig_ctrl.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class AprsSettingsFragment : Fragment() {

    private var _binding: FragmentAprsSettingsBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    private var soundDevices: List<SoundDevice> = emptyList()
    private var soundDeviceIdx = 0
    private var settingsLocMgr: LocationManager? = null
    private var settingsLocListener: LocationListener? = null

    // Rig modem UI state (local copies, saved to vm on OK)
    private var useRigModem = false
    private var modemSelIdx = 1  // index into MODEM_SEL_LIST

    companion object {
        val MODEM_SEL_LIST = listOf(1 to "AUTO", 2 to "MAIN", 3 to "SUB")
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSettingsLocationUpdates()
        else {
            binding.switchUseGps.isChecked = false
            Toast.makeText(requireContext(), "Location permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAprsSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        soundDevices = vm.soundDeviceList.value ?: emptyList()
        soundDeviceIdx = soundDevices.indexOfFirst { it.id == vm.aprsSoundDevice.value }.takeIf { it >= 0 } ?: 0

        useRigModem = vm.aprsUseRigModem.value ?: false
        modemSelIdx = MODEM_SEL_LIST.indexOfFirst { it.first == (vm.aprsModemSel.value ?: 2) }.takeIf { it >= 0 } ?: 1

        loadValuesToUI()
        setupListeners()

        if (vm.aprsUseGPS.value == true &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startSettingsLocationUpdates()
        }
    }

    private fun loadValuesToUI() {
        binding.switchAprsEnabled.isChecked = vm.aprsEnabled.value ?: false
        val useGps = vm.aprsUseGPS.value ?: false
        binding.switchUseGps.isChecked = useGps
        binding.editLat.setText("%.5f".format(vm.aprsLat.value ?: 0f))
        binding.editLon.setText("%.5f".format(vm.aprsLon.value ?: 0f))
        binding.editTxFreq.setText("%.3f".format(vm.aprsTxFreq.value ?: 144.660f))
        binding.editCallsign.setText(vm.aprsCallsign.value ?: "")
        binding.editComment.setText(vm.aprsComment.value ?: "")
        binding.editComment.filters = arrayOf(
            android.text.InputFilter { source, start, end, _, _, _ ->
                val filtered = source.substring(start, end).filter { it.code in 32..126 }
                if (filtered.length == end - start) null else filtered
            }
        )

        // Rig modem presets
        binding.editAp96Freq.setText("%.3f".format(vm.aprsPreset1Freq.value ?: 144.660f))
        binding.editAp12Freq.setText("%.3f".format(vm.aprsPreset2Freq.value ?: 144.660f))

        updateGpsFieldState(useGps)
        updateTxMethodUI()
        updateCyclableFields()
    }

    private fun updateCyclableFields() {
        val baudIdx = APRS_BAUD_LIST.indexOf(vm.aprsBaud.value ?: 1200).takeIf { it >= 0 } ?: 0
        binding.tvBaud.text = "${APRS_BAUD_LIST.getOrElse(baudIdx) { 1200 }}"

        val intIdx = APRS_INTERVAL_LIST.indexOf(vm.aprsIntervalSec.value ?: 60).takeIf { it >= 0 } ?: 1
        binding.tvInterval.text = "${APRS_INTERVAL_LIST.getOrElse(intIdx) { 60 }} sec"

        binding.tvSSID.text = "-${vm.aprsSSID.value ?: 0}"
        binding.tvPath.text = vm.aprsPath.value ?: "WIDE1-1"
        binding.tvSymbol.text = aprsSymbolByCode(vm.aprsSymbol.value ?: ">").display
        binding.tvDestination.text = vm.aprsDestination.value ?: "APRS00"

        val sd = soundDevices.getOrNull(soundDeviceIdx)
        binding.tvSoundDevice.text = sd?.label ?: (vm.aprsSoundDevice.value ?: "---")

        // Rig modem baud displays
        val ap96Baud = vm.aprsPreset1Baud.value ?: 9600
        binding.tvAp96Baud.text = "$ap96Baud"
        val ap12Baud = vm.aprsPreset2Baud.value ?: 1200
        binding.tvAp12Baud.text = "$ap12Baud"

        // Modem select
        binding.tvModemSel.text = MODEM_SEL_LIST.getOrNull(modemSelIdx)?.second ?: "MAIN"

        // TX Method label
        binding.tvTxMethod.text = if (useRigModem) "Rig Modem" else "DireWolf"
    }

    private fun updateTxMethodUI() {
        binding.tvTxMethod.text = if (useRigModem) "Rig Modem" else "DireWolf"
        binding.sectionDireWolf.visibility = if (useRigModem) View.GONE else View.VISIBLE
        binding.sectionRigModem.visibility = if (useRigModem) View.VISIBLE else View.GONE
    }

    private fun updateGpsFieldState(useGps: Boolean) {
        binding.editLat.isEnabled = !useGps
        binding.editLon.isEnabled = !useGps
    }

    @SuppressLint("MissingPermission")
    private fun startSettingsLocationUpdates() {
        val ctx = requireContext()
        val hasFine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        settingsLocMgr = lm
        settingsLocListener?.let { lm.removeUpdates(it) }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.isProviderEnabled(it) }
        if (providers.isEmpty()) {
            Toast.makeText(ctx, "GPS unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        val listener = LocationListener { loc ->
            if (_binding != null) {
                binding.editLat.setText("%.5f".format(loc.latitude))
                binding.editLon.setText("%.5f".format(loc.longitude))
            }
        }
        settingsLocListener = listener

        providers.firstNotNullOfOrNull { lm.getLastKnownLocation(it) }?.let { loc ->
            binding.editLat.setText("%.5f".format(loc.latitude))
            binding.editLon.setText("%.5f".format(loc.longitude))
        }

        for (p in providers) {
            lm.requestLocationUpdates(p, 5000L, 0f, listener, Looper.getMainLooper())
        }
    }

    private fun stopSettingsLocationUpdates() {
        settingsLocListener?.let { settingsLocMgr?.removeUpdates(it) }
        settingsLocListener = null
        settingsLocMgr = null
    }

    private fun setupListeners() {
        // TX Method toggle
        binding.tvTxMethod.setOnClickListener {
            useRigModem = !useRigModem
            updateTxMethodUI()
        }

        // Use GPS switch
        binding.switchUseGps.setOnCheckedChangeListener { _, checked ->
            updateGpsFieldState(checked)
            if (checked) {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    startSettingsLocationUpdates()
                } else {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            } else {
                stopSettingsLocationUpdates()
            }
        }

        // Baud rate list (DireWolf)
        binding.tvBaud.setOnClickListener {
            val cur = APRS_BAUD_LIST.indexOf(vm.aprsBaud.value ?: 1200).takeIf { it >= 0 } ?: 0
            showPicker("Baud Rate", APRS_BAUD_LIST.map { it.toString() }.toTypedArray(), cur) { idx ->
                vm.aprsBaud.value = APRS_BAUD_LIST[idx]
                updateCyclableFields()
            }
        }

        // Interval list
        binding.tvInterval.setOnClickListener {
            val cur = APRS_INTERVAL_LIST.indexOf(vm.aprsIntervalSec.value ?: 60).takeIf { it >= 0 } ?: 0
            showPicker("TX Interval", APRS_INTERVAL_LIST.map { "${it}s" }.toTypedArray(), cur) { idx ->
                vm.aprsIntervalSec.value = APRS_INTERVAL_LIST[idx]
                updateCyclableFields()
            }
        }

        // SSID list
        binding.tvSSID.setOnClickListener {
            val cur = vm.aprsSSID.value ?: 0
            showPicker("SSID", (0..15).map { "-$it" }.toTypedArray(), cur) { idx ->
                vm.aprsSSID.value = idx
                updateCyclableFields()
            }
        }

        // Path list
        binding.tvPath.setOnClickListener {
            val cur = APRS_PATH_LIST.indexOf(vm.aprsPath.value ?: "WIDE1-1").takeIf { it >= 0 } ?: 0
            showPicker("Path", APRS_PATH_LIST.toTypedArray(), cur) { idx ->
                vm.aprsPath.value = APRS_PATH_LIST[idx]
                updateCyclableFields()
            }
        }

        // Symbol list
        binding.tvSymbol.setOnClickListener {
            val cur = APRS_SYMBOL_LIST.indexOfFirst { it.code == (vm.aprsSymbol.value ?: ">") }.takeIf { it >= 0 } ?: 0
            val labels = APRS_SYMBOL_LIST.map { it.display }.toTypedArray()
            showPicker("Symbol", labels, cur) { idx ->
                vm.aprsSymbol.value = APRS_SYMBOL_LIST[idx].code
                updateCyclableFields()
            }
        }

        // Destination list
        binding.tvDestination.setOnClickListener {
            val cur = APRS_DEST_LIST.indexOf(vm.aprsDestination.value ?: "APRS00").takeIf { it >= 0 } ?: 0
            showPicker("Destination", APRS_DEST_LIST.toTypedArray(), cur) { idx ->
                vm.aprsDestination.value = APRS_DEST_LIST[idx]
                updateCyclableFields()
            }
        }

        // Sound device list
        binding.tvSoundDevice.setOnClickListener {
            if (soundDevices.isEmpty()) {
                Toast.makeText(requireContext(), "No devices (connect to server first)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val labels = soundDevices.map { it.label }.toTypedArray()
            showPicker("Sound Device", labels, soundDeviceIdx) { idx ->
                soundDeviceIdx = idx
                vm.aprsSoundDevice.value = soundDevices[idx].id
                updateCyclableFields()
            }
        }

        // Modem Select
        binding.tvModemSel.setOnClickListener {
            val labels = MODEM_SEL_LIST.map { it.second }.toTypedArray()
            showPicker("Modem Select", labels, modemSelIdx) { idx ->
                modemSelIdx = idx
                updateCyclableFields()
            }
        }

        // AP96 Baud
        binding.tvAp96Baud.setOnClickListener {
            val cur = APRS_BAUD_LIST.indexOf(vm.aprsPreset1Baud.value ?: 9600).takeIf { it >= 0 } ?: 0
            showPicker("AP96 Baud", APRS_BAUD_LIST.map { it.toString() }.toTypedArray(), cur) { idx ->
                vm.aprsPreset1Baud.value = APRS_BAUD_LIST[idx]
                updateCyclableFields()
            }
        }

        // AP12 Baud
        binding.tvAp12Baud.setOnClickListener {
            val cur = APRS_BAUD_LIST.indexOf(vm.aprsPreset2Baud.value ?: 1200).takeIf { it >= 0 } ?: 0
            showPicker("AP12 Baud", APRS_BAUD_LIST.map { it.toString() }.toTypedArray(), cur) { idx ->
                vm.aprsPreset2Baud.value = APRS_BAUD_LIST[idx]
                updateCyclableFields()
            }
        }

        // Received Beacons link
        binding.rowReceivedBeacons.setOnClickListener {
            findNavController().navigate(R.id.action_AprsSettingsFragment_to_AprsReceivedFragment)
        }

        // OK button → save and send config to server
        binding.btnOk.setOnClickListener {
            saveFromUI()
            val cfg = vm.buildAprsConfig()

            lifecycleScope.launch {
                val ok = vm.saveAprsConfig(cfg)
                if (ok) {
                    findNavController().navigate(R.id.action_AprsSettingsFragment_to_MainControlFragment)
                } else {
                    Toast.makeText(requireContext(), "Failed to send settings", Toast.LENGTH_SHORT).show()
                    findNavController().navigate(R.id.action_AprsSettingsFragment_to_MainControlFragment)
                }
            }
        }
    }

    private fun saveFromUI() {
        vm.aprsEnabled.value = binding.switchAprsEnabled.isChecked
        vm.aprsUseGPS.value = binding.switchUseGps.isChecked
        vm.aprsLat.value = binding.editLat.text.toString().toFloatOrNull() ?: 0f
        vm.aprsLon.value = binding.editLon.text.toString().toFloatOrNull() ?: 0f
        vm.aprsTxFreq.value = binding.editTxFreq.text.toString().toFloatOrNull() ?: 144.660f
        vm.aprsCallsign.value = binding.editCallsign.text.toString().trim().uppercase()
        vm.aprsComment.value = binding.editComment.text.toString()

        // Rig modem settings
        vm.aprsUseRigModem.value = useRigModem
        vm.aprsModemSel.value = MODEM_SEL_LIST.getOrNull(modemSelIdx)?.first ?: 2
        vm.aprsPreset1Freq.value = binding.editAp96Freq.text.toString().toFloatOrNull() ?: 144.660f
        vm.aprsPreset2Freq.value = binding.editAp12Freq.text.toString().toFloatOrNull() ?: 144.660f
    }

    private fun showPicker(title: String, items: Array<String>, currentIdx: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(items, currentIdx) { dlg, idx ->
                onPick(idx)
                dlg.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        stopSettingsLocationUpdates()
        super.onDestroyView()
        _binding = null
    }
}
