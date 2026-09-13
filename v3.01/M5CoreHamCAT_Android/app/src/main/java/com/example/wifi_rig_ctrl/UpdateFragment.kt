package com.ji1ore.wifi_rig_ctrl

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ji1ore.wifi_rig_ctrl.databinding.FragmentUpdateBinding
import com.ji1ore.wifi_rig_ctrl.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateFragment : Fragment() {

    private var _binding: FragmentUpdateBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    private var currentLogType = "pi"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUpdateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnUpdatePi.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Update Pi")
                .setMessage("Send api.py, run create_api.sh, then restart Pi. Continue?")
                .setPositiveButton("Update") { _, _ ->
                    currentLogType = "pi"
                    updateTabUI()
                    setButtonsEnabled(false)
                    binding.tvStatus.text = "Uploading api.py..."
                    lifecycleScope.launch {
                        val result = vm.updatePiSoftware()
                        when {
                            result == null -> {
                                binding.tvStatus.text = "Update complete. Rebooting Pi..."
                                val rebootResult = vm.rebootPiAndWait()
                                binding.tvStatus.text = "$rebootResult — Starting mfsk build..."
                                currentLogType = "mfsk"
                                updateTabUI()
                                val mfskResult = vm.triggerMfskBuildAfterUpdate()
                                binding.tvStatus.text = "$rebootResult — $mfskResult"
                                reloadLog()
                            }
                            result.startsWith("api.py OK") ->
                                binding.tvStatus.text = "api.py sent but setup failed. Please run Update Pi again."
                            result.startsWith("setup timeout") ->
                                binding.tvStatus.text = "Setup timed out. Please run Update Pi again."
                            else ->
                                binding.tvStatus.text = "Update failed: $result"
                        }
                        setButtonsEnabled(true)
                        reloadLog()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnUpdateHamlib.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Update Hamlib")
                .setMessage("Build & install Hamlib 4.7.2.\nPi Zero: 30–60 min.\nDo not restart until build completes.\nContinue?")
                .setPositiveButton("Build") { _, _ ->
                    currentLogType = "hamlib"
                    updateTabUI()
                    binding.btnUpdateHamlib.isEnabled = false
                    binding.tvStatus.text = "Starting Hamlib 4.7.2 build..."
                    lifecycleScope.launch {
                        val result = vm.installHamlib()
                        binding.tvStatus.text = if (result == null)
                            "Hamlib 4.7.2 install complete."
                        else
                            "Hamlib build failed: $result"
                        binding.btnUpdateHamlib.isEnabled = true
                        reloadLog()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnTabPiLog.setOnClickListener {
            currentLogType = "pi"
            updateTabUI()
            lifecycleScope.launch { reloadLog() }
        }

        binding.btnTabHamlibLog.setOnClickListener {
            currentLogType = "hamlib"
            updateTabUI()
            lifecycleScope.launch { reloadLog() }
        }

        binding.btnTabMfskLog.setOnClickListener {
            currentLogType = "mfsk"
            updateTabUI()
            lifecycleScope.launch { reloadLog() }
        }

        binding.btnReload.setOnClickListener {
            lifecycleScope.launch { reloadLog() }
        }

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnHelp?.setOnClickListener {
            startActivity(Intent(requireContext(), HelpActivity::class.java))
        }

        updateTabUI()
        lifecycleScope.launch { reloadLog() }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnUpdatePi.isEnabled = enabled
        binding.btnUpdateHamlib.isEnabled = enabled
    }

    private fun updateTabUI() {
        binding.btnTabPiLog.alpha = if (currentLogType == "pi") 1.0f else 0.4f
        binding.btnTabHamlibLog.alpha = if (currentLogType == "hamlib") 1.0f else 0.4f
        binding.btnTabMfskLog.alpha = if (currentLogType == "mfsk") 1.0f else 0.4f
    }

    private suspend fun reloadLog() {
        when (currentLogType) {
            "pi" -> {
                val (running, log) = vm.getSetupLog()
                binding.tvLogHeader.text = if (running) "Pi Log (running...)" else "Pi Log"
                binding.tvLog.text = log.takeLast(3000).ifEmpty { "(no log)" }
            }
            "hamlib" -> {
                val (running, log) = withContext(Dispatchers.IO) {
                    try { vm.api.getHamlibLog() } catch (e: Exception) { false to (e.message ?: "error") }
                }
                binding.tvLogHeader.text = if (running) "Hamlib Log (building...)" else "Hamlib Log"
                binding.tvLog.text = log.takeLast(3000).ifEmpty { "(no log)" }
            }
            "mfsk" -> {
                val (running, log) = withContext(Dispatchers.IO) {
                    try { vm.api.getMfskBuildLog() } catch (e: Exception) { false to (e.message ?: "error") }
                }
                binding.tvLogHeader.text = if (running) "mfsk Build (building...)" else "mfsk Build Log"
                binding.tvLog.text = log.takeLast(3000).ifEmpty { "(no log)" }
            }
        }
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
