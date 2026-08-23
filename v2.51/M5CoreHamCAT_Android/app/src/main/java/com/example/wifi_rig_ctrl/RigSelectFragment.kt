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

        val isCiv = vm.useCIV.value == true
        applyModeVisibility(isCiv)
        updateUI()

        if (vm.isDemoMode.value == true) {
            binding.btnUpdate.isEnabled = false
        }

        vm.piVersionMismatch.observe(viewLifecycleOwner) { mismatch ->
            if (mismatch) binding.tvStatus.text = "Pi API version mismatch — update via UPDATE"
        }
        vm.webft8VersionMismatch.observe(viewLifecycleOwner) { mismatch ->
            if (mismatch) binding.tvStatus.text = "WebFT8 server is outdated — update via UPDATE"
        }

        // Start polling immediately so version-mismatch warnings are visible on first display.
        if (vm.isDemoMode.value != true && vm.useCIV.value != true) {
            vm.startStatusPolling()
        }

        // Rig model tap → dialog (Pi mode only; in CI-V mode it's read-only)
        binding.tvRigName.setOnClickListener {
            if (isCiv) return@setOnClickListener
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

        // CAT device tap (Pi mode only)
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

        // PTT device tap (Pi mode only)
        binding.tvPttDevice.setOnClickListener {
            val cats = vm.catList.value ?: emptyList()
            val options = (listOf("NONE") + cats + listOf("Manual...")).toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("PTT Device  (type: tap to cycle RTS/DTR/CAT)")
                .setItems(options) { _, i ->
                    when (i) {
                        options.size - 1 -> showPttManualInput()
                        else -> { vm.selectedPttDevice.value = options[i]; updateUI() }
                    }
                }.show()
        }

        // PTT type tap → cycle RTS/DTR/RIG
        binding.tvPttType.setOnClickListener {
            val cur = vm.selectedPttType.value ?: "RIG"
            vm.selectedPttType.value = when (cur) { "RTS" -> "DTR"; "DTR" -> "RIG"; else -> "RTS" }
            updateUI()
        }

        // Timeout tap → cycle (shared between Pi and CI-V modes)
        val timeoutClickListener = View.OnClickListener {
            val cur = vm.selectedTimeoutIndex.value ?: 0
            vm.selectedTimeoutIndex.value = (cur + 1) % SCREEN_TIMEOUT_OPTIONS.size
            updateUI()
        }
        binding.tvTimeout.setOnClickListener(timeoutClickListener)
        binding.tvTimeoutCiv.setOnClickListener(timeoutClickListener)

        // Baud rate tap (Pi mode only)
        binding.tvBaudRate.setOnClickListener {
            val cur = vm.selectedBaudIndex.value ?: 0
            vm.selectedBaudIndex.value = (cur + 1) % BAUD_RATES.size
            updateUI()
        }

        // Sampling rate tap (Pi mode only)
        binding.tvSampling.setOnClickListener {
            val cur = vm.selectedSamplingIndex.value ?: 0
            vm.selectedSamplingIndex.value = (cur + 1) % SAMPLING_RATES.size
            updateUI()
        }

        // TX rate tap (Pi mode only)
        binding.tvTxRate.setOnClickListener {
            val cur = vm.selectedTxSamplingIndex.value ?: 1
            vm.selectedTxSamplingIndex.value = (cur % (SAMPLING_RATES.size - 1)) + 1
            updateUI()
        }

        // Audio Device (SPK/TX) tap (Pi mode only)
        binding.tvAudioDevice.setOnClickListener {
            val devices = vm.soundDeviceList.value ?: emptyList()
            val labels = (listOf("Default (plughw:CARD=CODEC,DEV=0)") +
                devices.map { it.label } + listOf("Manual...")).toTypedArray()
            val ids = (listOf("") + devices.map { it.id } + listOf("__manual__")).toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("Audio Device (SPK/TX)")
                .setItems(labels) { _, i ->
                    if (ids[i] == "__manual__") showAudioManualInput()
                    else { vm.selectedAudioDevice.value = ids[i]; updateUI() }
                }.show()
        }

        // Audio Device (FT8) tap (Pi mode only)
        binding.tvAudioDeviceFt8.setOnClickListener {
            val devices = vm.soundDeviceList.value ?: emptyList()
            val labels = (listOf("Same as SPK/TX") +
                devices.map { it.label } + listOf("Manual...")).toTypedArray()
            val ids = (listOf("") + devices.map { it.id } + listOf("__manual__")).toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("Audio Device (FT8)")
                .setItems(labels) { _, i ->
                    if (ids[i] == "__manual__") showAudioFt8ManualInput()
                    else { vm.selectedAudioDeviceFt8.value = ids[i]; updateUI() }
                }.show()
        }

        binding.btnUpdate.setOnClickListener {
            findNavController().navigate(R.id.action_RigSelectFragment_to_UpdateFragment)
        }

        binding.btnSyncTime.setOnClickListener {
            binding.tvStatus.text = "Syncing Pi time..."
            lifecycleScope.launch {
                val result = vm.syncPiTime()
                binding.tvStatus.text = result
                Toast.makeText(requireContext(), result, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAbout.setOnClickListener {
            findNavController().navigate(R.id.action_RigSelectFragment_to_AboutFragment)
        }

        binding.btnBack.setOnClickListener {
            findNavController().navigate(R.id.action_RigSelectFragment_to_ConnectFragment)
        }

        binding.btnPttSettings.setOnClickListener {
            findNavController().navigate(R.id.action_RigSelectFragment_to_PttSettingsFragment)
        }

        binding.btnConnect.setOnClickListener {
            if (isCiv) {
                // CI-V mode: connect directly (CIV already connected from ConnectFragment)
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
            } else {
                // Pi mode: original flow
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
    }

    private fun applyModeVisibility(isCiv: Boolean) {
        binding.groupPiSettings.visibility = if (isCiv) View.GONE else View.VISIBLE
        binding.groupCivSettings.visibility = if (isCiv) View.VISIBLE else View.GONE
        binding.tvRigSelectTitle.text = if (isCiv) "RIG CONNECT (CI-V)" else "RIG CONNECT"
        // Hide Pi-specific buttons in CI-V mode
        binding.btnUpdate.visibility = if (isCiv) View.GONE else View.VISIBLE
        binding.btnSyncTime.visibility = if (isCiv) View.GONE else View.VISIBLE
        binding.btnPttSettings.visibility = if (isCiv) View.GONE else View.VISIBLE
    }

    private fun updateUI() {
        val rigs = vm.rigList.value ?: emptyList()
        val cats = vm.catList.value ?: emptyList()
        val rigIdx = vm.selectedRigIndex.value ?: 0
        val catIdx = vm.selectedCatIndex.value ?: 0
        val baudIdx = vm.selectedBaudIndex.value ?: 2
        val sampIdx = vm.selectedSamplingIndex.value ?: 1
        val toIdx = vm.selectedTimeoutIndex.value ?: 2
        val isCiv = vm.useCIV.value == true

        val rigName = rigs.getOrNull(rigIdx)?.name ?: "---"
        binding.tvRigName.text = rigName

        val timeout = SCREEN_TIMEOUT_OPTIONS.getOrElse(toIdx) { 10 }
        val timeoutText = if (timeout == 0) "Off" else "$timeout min"
        binding.tvTimeout.text = timeoutText
        binding.tvTimeoutCiv.text = timeoutText

        if (isCiv) {
            val addr = (vm.civAddress.value ?: 0xA4).toString(16).uppercase()
            binding.tvCivInfo.text = "CI-V: ${vm.civHost.value}\nPort1=${vm.civPort.value}  Port3=${vm.civPort3.value}  addr=0x$addr\nUser: ${vm.civUser.value?.ifEmpty { "(none)" }}"
            return
        }

        binding.tvCatDevice.text = cats.getOrElse(catIdx) { "None" }
        binding.tvPttDevice.text = vm.selectedPttDevice.value ?: "NONE"
        val pttDev = vm.selectedPttDevice.value ?: "NONE"
        val pttTypeDisplay = when (vm.selectedPttType.value ?: "RTS") { "RIG" -> "CAT"; else -> vm.selectedPttType.value ?: "RTS" }
        binding.tvPttType.text = if (pttDev == "NONE") "-" else pttTypeDisplay
        binding.tvBaudRate.text = "${BAUD_RATES.getOrElse(baudIdx) { 9600 }} bps"

        val rate = SAMPLING_RATES.getOrElse(sampIdx) { 0 }
        binding.tvSampling.text = if (rate == 0) "OFF" else "$rate Hz"

        val txIdx = vm.selectedTxSamplingIndex.value ?: 1
        val txRate = SAMPLING_RATES.getOrElse(txIdx) { 8000 }
        binding.tvTxRate.text = "$txRate Hz"

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
                vm.selectedAudioDevice.value = edit.text.toString().trim()
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
                vm.selectedAudioDeviceFt8.value = edit.text.toString().trim()
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
                if (input.isNotEmpty()) { vm.setCustomCatDevice(input); updateUI() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
