package com.ji1ore.wifi_rig_ctrl

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ji1ore.wifi_rig_ctrl.data.BAUD_RATES
import com.ji1ore.wifi_rig_ctrl.data.SAMPLING_RATES
import com.ji1ore.wifi_rig_ctrl.data.SCREEN_TIMEOUT_OPTIONS
import com.ji1ore.wifi_rig_ctrl.databinding.FragmentRigSelectBinding
import com.ji1ore.wifi_rig_ctrl.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class RigSelectFragment : Fragment() {

    private var _binding: FragmentRigSelectBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRigSelectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateUI()

        // Rig model tap →dialog
        binding.tvRigName.setOnClickListener {
            val rigs = vm.rigList.value ?: return@setOnClickListener
            if (rigs.isEmpty()) return@setOnClickListener
            val names = rigs.map { it.name }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("Rig Model")
                .setItems(names) { _, i ->
                    vm.selectedRigIndex.value = i
                    updateUI()
                }.show()
        }

        // CAT device tap →dialog with detected devices + manual input
        binding.tvCatDevice.setOnClickListener {
            val cats = vm.catList.value ?: return@setOnClickListener
            val options = (cats + listOf("Manual...")).toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("CAT Device")
                .setItems(options) { _, i ->
                    if (i == options.size - 1) {
                        showCatManualInput()
                    } else {
                        vm.selectedCatIndex.value = i
                        updateUI()
                    }
                }.show()
        }

        // PTT device tap →dialog
        binding.tvPttDevice.setOnClickListener {
            val cats = vm.catList.value ?: emptyList()
            val options = (listOf("NONE") + cats + listOf("Manual...")).toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("PTT Device (RTS)")
                .setItems(options) { _, i ->
                    when (i) {
                        options.size - 1 -> showPttManualInput()
                        else -> { vm.selectedPttDevice.value = options[i]; updateUI() }
                    }
                }.show()
        }

        // PTT type tap →cycle RTS/DTR (only meaningful when PTT device is set)
        binding.tvPttType.setOnClickListener {
            val cur = vm.selectedPttType.value ?: "RTS"
            vm.selectedPttType.value = if (cur == "RTS") "DTR" else "RTS"
            updateUI()
        }

        // Timeout tap →cycle
        binding.tvTimeout.setOnClickListener {
            val cur = vm.selectedTimeoutIndex.value ?: 0
            vm.selectedTimeoutIndex.value = (cur + 1) % SCREEN_TIMEOUT_OPTIONS.size
            updateUI()
        }

        // Baud rate tap →cycle
        binding.tvBaudRate.setOnClickListener {
            val cur = vm.selectedBaudIndex.value ?: 0
            vm.selectedBaudIndex.value = (cur + 1) % BAUD_RATES.size
            updateUI()
        }

        // Sampling rate tap → cycle
        binding.tvSampling.setOnClickListener {
            val cur = vm.selectedSamplingIndex.value ?: 0
            vm.selectedSamplingIndex.value = (cur + 1) % SAMPLING_RATES.size
            updateUI()
        }

        // Audio Device (SPK/TX) tap → dialog with detected ALSA devices + manual input
        binding.tvAudioDevice.setOnClickListener {
            val devices = vm.soundDeviceList.value ?: emptyList()
            val labels = (listOf("Default (plughw:CARD=CODEC,DEV=0)") +
                devices.map { it.label } + listOf("Manual...")).toTypedArray()
            val ids = (listOf("") + devices.map { it.id } + listOf("__manual__")).toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("Audio Device (SPK/TX)")
                .setItems(labels) { _, i ->
                    if (ids[i] == "__manual__") {
                        showAudioManualInput()
                    } else {
                        vm.selectedAudioDevice.value = ids[i]
                        updateUI()
                    }
                }.show()
        }

        // Audio Device (FT8) tap → dialog
        binding.tvAudioDeviceFt8.setOnClickListener {
            val devices = vm.soundDeviceList.value ?: emptyList()
            val labels = (listOf("Same as SPK/TX") +
                devices.map { it.label } + listOf("Manual...")).toTypedArray()
            val ids = (listOf("") + devices.map { it.id } + listOf("__manual__")).toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("Audio Device (FT8)")
                .setItems(labels) { _, i ->
                    if (ids[i] == "__manual__") {
                        showAudioFt8ManualInput()
                    } else {
                        vm.selectedAudioDeviceFt8.value = ids[i]
                        updateUI()
                    }
                }.show()
        }

        binding.btnUpdateApi.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Update Pi")
                .setMessage("Send api.py then run create_api.sh in background (includes re-fetching webft8 files). Proceed?")
                .setPositiveButton("Update") { _, _ ->
                    binding.btnUpdateApi.isEnabled = false
                    binding.tvStatus.text = "Uploading api.py..."
                    lifecycleScope.launch {
                        val result = vm.updatePiSoftware()
                        binding.btnUpdateApi.isEnabled = true
                        when {
                            result == null -> {
                                binding.tvStatus.text = "Setup running on Pi (/tmp/create_api.log)"
                                Toast.makeText(requireContext(), "Pi setup started", Toast.LENGTH_SHORT).show()
                            }
                            result.startsWith("api.py OK") -> {
                                binding.tvStatus.text = "api.py updated. Run UPDATE again to send setup script."
                                Toast.makeText(requireContext(), result, Toast.LENGTH_LONG).show()
                            }
                            else -> {
                                binding.tvStatus.text = "Update failed: $result"
                                Toast.makeText(requireContext(), "Update failed: $result", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnUpdateCwBridge.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Update CW Bridge")
                .setMessage("Send cw_bridge.py to Pi and restart it. Proceed?")
                .setPositiveButton("Update") { _, _ ->
                    binding.btnUpdateCwBridge.isEnabled = false
                    binding.tvStatus.text = "Uploading cw_bridge.py..."
                    lifecycleScope.launch {
                        val result = vm.updateCwBridge()
                        binding.btnUpdateCwBridge.isEnabled = true
                        if (result == null) {
                            binding.tvStatus.text = "cw_bridge.py updated and restarted"
                            Toast.makeText(requireContext(), "CW Bridge updated", Toast.LENGTH_SHORT).show()
                        } else {
                            binding.tvStatus.text = "CW Bridge update failed: $result"
                            Toast.makeText(requireContext(), "Failed: $result", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnSetupLog.setOnClickListener {
            lifecycleScope.launch {
                binding.btnSetupLog.isEnabled = false
                val (running, log) = vm.getSetupLog()
                binding.btnSetupLog.isEnabled = true
                val title = if (running) "Setup: Running..." else "Setup: Done"
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setMessage(log.takeLast(2000))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        binding.btnBack.setOnClickListener {
            findNavController().navigate(R.id.action_RigSelectFragment_to_ConnectFragment)
        }

        binding.btnPttSettings.setOnClickListener {
            findNavController().navigate(R.id.action_RigSelectFragment_to_PttSettingsFragment)
        }

        binding.btnConnect.setOnClickListener {
            val rigs = vm.rigList.value
            if (rigs.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "No rig found", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnConnect.isEnabled = false
            binding.progressBar.visibility = View.VISIBLE
            binding.tvStatus.text = "Connecting..."

            lifecycleScope.launch {
                val error = vm.connectRig()
                binding.btnConnect.isEnabled = true
                binding.progressBar.visibility = View.GONE
                if (error == null) {
                    vm.isConnectedToRig.value = true
                    findNavController().navigate(R.id.action_RigSelectFragment_to_MainControlFragment)
                } else {
                    binding.tvStatus.text = error
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateUI() {
        val rigs = vm.rigList.value ?: emptyList()
        val cats = vm.catList.value ?: emptyList()
        val rigIdx = vm.selectedRigIndex.value ?: 0
        val catIdx = vm.selectedCatIndex.value ?: 0
        val baudIdx = vm.selectedBaudIndex.value ?: 2
        val sampIdx = vm.selectedSamplingIndex.value ?: 1
        val toIdx = vm.selectedTimeoutIndex.value ?: 2

        binding.tvRigName.text = rigs.getOrNull(rigIdx)?.name ?: "---"
        binding.tvCatDevice.text = cats.getOrElse(catIdx) { "None" }
        binding.tvPttDevice.text = vm.selectedPttDevice.value ?: "NONE"
        val pttDev = vm.selectedPttDevice.value ?: "NONE"
        binding.tvPttType.text = if (pttDev == "NONE") "-" else vm.selectedPttType.value ?: "RTS"
        binding.tvBaudRate.text = "${BAUD_RATES.getOrElse(baudIdx) { 9600 }} bps"

        val rate = SAMPLING_RATES.getOrElse(sampIdx) { 0 }
        binding.tvSampling.text = if (rate == 0) "OFF" else "$rate Hz"

        val timeout = SCREEN_TIMEOUT_OPTIONS.getOrElse(toIdx) { 10 }
        binding.tvTimeout.text = if (timeout == 0) "Off" else "$timeout min"

        val audioDev = vm.selectedAudioDevice.value ?: ""
        binding.tvAudioDevice.text = if (audioDev.isEmpty()) "Default" else audioDev

        val audioFt8Dev = vm.selectedAudioDeviceFt8.value ?: ""
        binding.tvAudioDeviceFt8.text = if (audioFt8Dev.isEmpty()) "Same as SPK/TX" else audioFt8Dev
    }

    private fun showPttManualInput() {
        val edit = EditText(requireContext()).apply { hint = "e.g. ttyUSB1" }
        AlertDialog.Builder(requireContext())
            .setTitle("PTT Device (manual)")
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                val input = edit.text.toString().trim()
                if (input.isNotEmpty()) { vm.selectedPttDevice.value = input; updateUI() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAudioManualInput() {
        val edit = EditText(requireContext()).apply {
            hint = "e.g. plughw:CARD=CODEC,DEV=0"
            val cur = vm.selectedAudioDevice.value ?: ""
            if (cur.isNotEmpty()) setText(cur)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Audio Device (manual)")
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                val input = edit.text.toString().trim()
                vm.selectedAudioDevice.value = input
                updateUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAudioFt8ManualInput() {
        val edit = EditText(requireContext()).apply {
            hint = "e.g. plughw:CARD=CODEC,DEV=0"
            val cur = vm.selectedAudioDeviceFt8.value ?: ""
            if (cur.isNotEmpty()) setText(cur)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Audio Device FT8 (manual)")
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                val input = edit.text.toString().trim()
                vm.selectedAudioDeviceFt8.value = input
                updateUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCatManualInput() {
        val edit = EditText(requireContext()).apply {
            hint = "e.g. ttyUSB0"
            val cur = vm.catList.value?.getOrNull(vm.selectedCatIndex.value ?: 0)
            if (cur != null && cur != "None") setText(cur)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("CAT Device (manual)")
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                val input = edit.text.toString().trim()
                if (input.isNotEmpty()) {
                    vm.setCustomCatDevice(input)
                    updateUI()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}