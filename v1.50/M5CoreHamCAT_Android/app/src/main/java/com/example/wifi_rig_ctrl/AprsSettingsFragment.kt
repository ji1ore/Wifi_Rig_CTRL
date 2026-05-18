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

        // Load sound devices from server
        soundDevices = vm.soundDeviceList.value ?: emptyList()
        soundDeviceIdx = soundDevices.indexOfFirst { it.id == vm.aprsSoundDevice.value }.takeIf { it >= 0 } ?: 0

        loadValuesToUI()
        setupListeners()

        // 設定画面を開いた時点でGPS ONかつ権限あり → 即時受信開始
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

        updateGpsFieldState(useGps)
        updateCyclableFields()
    }

    private fun updateCyclableFields() {
        val baudIdx = APRS_BAUD_LIST.indexOf(vm.aprsBaud.value ?: 1200).takeIf { it >= 0 } ?: 0
        binding.tvBaud.text = "${APRS_BAUD_LIST.getOrElse(baudIdx) { 1200 }}"

        val intIdx = APRS_INTERVAL_LIST.indexOf(vm.aprsIntervalSec.value ?: 60).takeIf { it >= 0 } ?: 1
        binding.tvInterval.text = "${APRS_INTERVAL_LIST.getOrElse(intIdx) { 60 }} sec"

        binding.tvSSID.text = "-${vm.aprsSSID.value ?: 0}"
        binding.tvPath.text = vm.aprsPath.value ?: "WIDE1-1"
        binding.tvSymbol.text = vm.aprsSymbol.value ?: ">"
        binding.tvDestination.text = vm.aprsDestination.value ?: "APRS00"

        val sd = soundDevices.getOrNull(soundDeviceIdx)
        binding.tvSoundDevice.text = sd?.label ?: (vm.aprsSoundDevice.value ?: "---")
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

        // 使用可能なプロバイダーを列挙 (GPS + ネットワーク両方)
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

        // 最終既知位置で即時更新 (GPSまたはネットワーク、null でなければ)
        providers.firstNotNullOfOrNull { lm.getLastKnownLocation(it) }?.let { loc ->
            binding.editLat.setText("%.5f".format(loc.latitude))
            binding.editLon.setText("%.5f".format(loc.longitude))
        }

        // 全プロバイダーにリスナー登録 (5秒間隔、距離制限なし)
        // ネットワーク: 数秒で取得、GPS: より高精度だが時間がかかる
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

        // Baud rate list
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
            val cur = APRS_SYMBOL_LIST.indexOf(vm.aprsSymbol.value ?: ">").takeIf { it >= 0 } ?: 0
            showPicker("Symbol", APRS_SYMBOL_LIST.toTypedArray(), cur) { idx ->
                vm.aprsSymbol.value = APRS_SYMBOL_LIST[idx]
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