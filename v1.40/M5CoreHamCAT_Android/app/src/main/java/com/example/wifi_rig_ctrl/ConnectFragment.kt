package com.example.wifi_rig_ctrl

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.wifi_rig_ctrl.databinding.FragmentConnectBinding
import com.example.wifi_rig_ctrl.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class ConnectFragment : Fragment() {

    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Skipモードで接続済みの場合は MainControlFragment へ直接遷移
        if (vm.isConnectedToRig.value == true) {
            findNavController().navigate(R.id.action_ConnectFragment_to_MainControlFragment)
            return
        }

        binding.editHost.setText(vm.hostName.value)
        binding.editApiPort.setText(vm.apiPort.value?.toString() ?: "8210")
        binding.editAudioPort.setText(vm.audioPort.value?.toString() ?: "8211")
        binding.editApiKey.setText(vm.apiKey.value)
        binding.switchMdns.isChecked = vm.useMDNS.value ?: false

        // 初期表示時: mDNSが既にONなら.localを自動付加
        if (vm.useMDNS.value == true) {
            val h = binding.editHost.text.toString().trim()
            if (h.isNotEmpty() && !h.endsWith(".local")) {
                binding.editHost.setText("$h.local")
            }
        }

        binding.switchMdns.setOnCheckedChangeListener { _, isChecked ->
            val host = binding.editHost.text.toString().trim()
            if (isChecked && host.isNotEmpty() && !host.endsWith(".local")) {
                binding.editHost.setText("$host.local")
            } else if (!isChecked && host.endsWith(".local")) {
                binding.editHost.setText(host.removeSuffix(".local"))
            }
        }

        binding.btnConnect.setOnClickListener {
            val host = binding.editHost.text.toString().trim()
            val apiPort = binding.editApiPort.text.toString().toIntOrNull() ?: 8210
            val audioPort = binding.editAudioPort.text.toString().toIntOrNull() ?: 8211
            val apiKey = binding.editApiKey.text.toString().trim()
            val mdns = binding.switchMdns.isChecked

            if (host.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter a host address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            vm.updateConnectionSettings(host, apiPort, audioPort, mdns, apiKey)
            binding.btnConnect.isEnabled = false
            binding.progressBar.visibility = View.VISIBLE
            binding.tvStatus.text = "Connecting..."

            lifecycleScope.launch {
                val error = vm.connectToRasPi()
                binding.btnConnect.isEnabled = true
                binding.progressBar.visibility = View.GONE
                if (error == null) {
                    findNavController().navigate(R.id.action_ConnectFragment_to_RigSelectFragment)
                } else {
                    binding.tvStatus.text = error
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
