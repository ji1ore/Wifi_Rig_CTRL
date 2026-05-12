package com.example.wifi_rig_ctrl

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
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
import com.example.wifi_rig_ctrl.data.*
import com.example.wifi_rig_ctrl.databinding.FragmentAprsSettingsBinding
import com.example.wifi_rig_ctrl.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class AprsSettingsFragment : Fragment() {

    private var _binding: FragmentAprsSettingsBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    private var soundDevices: List<SoundDevice> = emptyList()
    private var soundDeviceIdx = 0

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) fetchAndFillLocation()
        else {
            binding.switchUseGps.isChecked = false
            Toast.makeText(requireContext(), "位置情報の権限が必要です", Toast.LENGTH_SHORT).show()
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
    private fun fetchAndFillLocation() {
        val ctx = requireContext()
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> { Toast.makeText(ctx, "GPS無効", Toast.LENGTH_SHORT).show(); return }
        }
        val loc = lm.getLastKnownLocation(provider)
        if (loc != null) {
            binding.editLat.setText("%.5f".format(loc.latitude))
            binding.editLon.setText("%.5f".format(loc.longitude))
        } else {
            Toast.makeText(ctx, "GPS取得中...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        // Use GPS switch
        binding.switchUseGps.setOnCheckedChangeListener { _, checked ->
            updateGpsFieldState(checked)
            if (checked) {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    fetchAndFillLocation()
                } else {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }

        // Baud rate cycle
        binding.tvBaud.setOnClickListener {
            val cur = APRS_BAUD_LIST.indexOf(vm.aprsBaud.value ?: 1200).takeIf { it >= 0 } ?: 0
            val next = (cur + 1) % APRS_BAUD_LIST.size
            vm.aprsBaud.value = APRS_BAUD_LIST[next]
            updateCyclableFields()
        }

        // Interval cycle
        binding.tvInterval.setOnClickListener {
            val cur = APRS_INTERVAL_LIST.indexOf(vm.aprsIntervalSec.value ?: 60).takeIf { it >= 0 } ?: 0
            val next = (cur + 1) % APRS_INTERVAL_LIST.size
            vm.aprsIntervalSec.value = APRS_INTERVAL_LIST[next]
            updateCyclableFields()
        }

        // SSID cycle
        binding.tvSSID.setOnClickListener {
            val cur = vm.aprsSSID.value ?: 0
            vm.aprsSSID.value = (cur + 1) % 16
            updateCyclableFields()
        }

        // Path cycle
        binding.tvPath.setOnClickListener {
            val cur = APRS_PATH_LIST.indexOf(vm.aprsPath.value ?: "WIDE1-1").takeIf { it >= 0 } ?: 0
            val next = (cur + 1) % APRS_PATH_LIST.size
            vm.aprsPath.value = APRS_PATH_LIST[next]
            updateCyclableFields()
        }

        // Symbol cycle
        binding.tvSymbol.setOnClickListener {
            val cur = APRS_SYMBOL_LIST.indexOf(vm.aprsSymbol.value ?: ">").takeIf { it >= 0 } ?: 0
            val next = (cur + 1) % APRS_SYMBOL_LIST.size
            vm.aprsSymbol.value = APRS_SYMBOL_LIST[next]
            updateCyclableFields()
        }

        // Destination cycle
        binding.tvDestination.setOnClickListener {
            val cur = APRS_DEST_LIST.indexOf(vm.aprsDestination.value ?: "APRS00").takeIf { it >= 0 } ?: 0
            val next = (cur + 1) % APRS_DEST_LIST.size
            vm.aprsDestination.value = APRS_DEST_LIST[next]
            updateCyclableFields()
        }

        // Sound device cycle
        binding.tvSoundDevice.setOnClickListener {
            if (soundDevices.isEmpty()) return@setOnClickListener
            soundDeviceIdx = (soundDeviceIdx + 1) % soundDevices.size
            vm.aprsSoundDevice.value = soundDevices[soundDeviceIdx].id
            updateCyclableFields()
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
                    Toast.makeText(requireContext(), "設定送信失敗", Toast.LENGTH_SHORT).show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}