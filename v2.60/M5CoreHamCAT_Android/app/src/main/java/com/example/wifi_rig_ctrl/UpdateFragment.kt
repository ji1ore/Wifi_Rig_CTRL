package com.ji1ore.wifi_rig_ctrl

import android.app.AlertDialog
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
                .setMessage("api.py を送信し create_api.sh を実行後、Pi を再起動します。続けますか？")
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
                                // After reboot: push new server_webft8.py so it downloads any
                                // missing JS files (e.g. wav-save.js) at webft8 startup.
                                binding.tvStatus.text = "$rebootResult\nUpdating WebFT8 server..."
                                val wErr = vm.updateWebFt8Server()
                                binding.tvStatus.text = if (wErr == null)
                                    "$rebootResult\nWebFT8 server updated."
                                else
                                    "$rebootResult\nWebFT8 update: $wErr"
                            }
                            result.startsWith("api.py OK") ->
                                binding.tvStatus.text = "api.py 送信済みだが setup 失敗。再度 Update Pi を実行してください。"
                            result.startsWith("setup OK") ->
                                binding.tvStatus.text = "Setup 完了だが api.py 再送失敗。再度 Update Pi を実行してください。"
                            else ->
                                binding.tvStatus.text = "Update 失敗: $result"
                        }
                        setButtonsEnabled(true)
                        reloadLog()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnUpdateWebFt8.setOnClickListener {
            setButtonsEnabled(false)
            binding.tvStatus.text = "Updating WebFT8 server.py..."
            lifecycleScope.launch {
                val result = vm.updateWebFt8Server()
                binding.tvStatus.text = if (result == null)
                    "WebFT8 server updated. JS files will refresh on next webft8 start (~30s)."
                else
                    "WebFT8 update failed: $result"
                setButtonsEnabled(true)
            }
        }

        binding.btnUpdateHamlib.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Update Hamlib")
                .setMessage("Hamlib 4.7.2 をビルド＆インストールします。\nPi Zero: 30〜60分。\nビルド完了まで再起動しないでください。\n続けますか？")
                .setPositiveButton("Build") { _, _ ->
                    currentLogType = "hamlib"
                    updateTabUI()
                    binding.btnUpdateHamlib.isEnabled = false
                    binding.tvStatus.text = "Starting Hamlib 4.7.2 build..."
                    lifecycleScope.launch {
                        val result = vm.installHamlib()
                        binding.tvStatus.text = if (result == null)
                            "Hamlib 4.7.2 インストール完了。"
                        else
                            "Hamlib ビルド失敗: $result"
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

        binding.btnReload.setOnClickListener {
            lifecycleScope.launch { reloadLog() }
        }

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        updateTabUI()
        lifecycleScope.launch { reloadLog() }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnUpdatePi.isEnabled = enabled
        binding.btnUpdateWebFt8.isEnabled = enabled
        binding.btnUpdateHamlib.isEnabled = enabled
    }

    private fun updateTabUI() {
        binding.btnTabPiLog.alpha = if (currentLogType == "pi") 1.0f else 0.4f
        binding.btnTabHamlibLog.alpha = if (currentLogType == "hamlib") 1.0f else 0.4f
    }

    private suspend fun reloadLog() {
        if (currentLogType == "pi") {
            val (running, log) = vm.getSetupLog()
            binding.tvLogHeader.text = if (running) "Pi Log (実行中...)" else "Pi Log"
            binding.tvLog.text = log.takeLast(3000).ifEmpty { "(ログなし)" }
        } else {
            val (running, log) = withContext(Dispatchers.IO) {
                try { vm.api.getHamlibLog() } catch (e: Exception) { false to (e.message ?: "error") }
            }
            binding.tvLogHeader.text = if (running) "Hamlib Log (ビルド中...)" else "Hamlib Log"
            binding.tvLog.text = log.takeLast(3000).ifEmpty { "(ログなし)" }
        }
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
