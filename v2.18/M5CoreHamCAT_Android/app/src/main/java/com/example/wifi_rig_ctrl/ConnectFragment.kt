package com.ji1ore.wifi_rig_ctrl

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ji1ore.wifi_rig_ctrl.databinding.FragmentConnectBinding
import com.ji1ore.wifi_rig_ctrl.viewmodel.MainViewModel
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

        if (vm.isConnectedToRig.value == true) {
            findNavController().navigate(R.id.action_ConnectFragment_to_MainControlFragment)
            return
        }

        val isCiv = vm.useCIV.value ?: false

        // Restore saved values
        binding.editHost.setText(if (isCiv) vm.civHost.value else vm.hostName.value)
        binding.editApiPort.setText(vm.apiPort.value?.toString() ?: "8210")
        binding.editAudioPort.setText(vm.audioPort.value?.toString() ?: "8211")
        binding.editApiKey.setText(vm.apiKey.value)
        binding.editMyCall.setText(vm.prefs.ft8MyCall)
        binding.switchMdns.isChecked = vm.useMDNS.value ?: false
        binding.switchCiv.isChecked = isCiv
        binding.editCivPort.setText((vm.civPort.value ?: 50001).toString())
        binding.editCivPort2.setText((vm.civPort2.value ?: 50002).toString())
        binding.editCivPort3.setText((vm.civPort3.value ?: 50003).toString())
        binding.editCivUser.setText(vm.civUser.value ?: "")
        binding.editCivPassword.setText(vm.civPassword.value ?: "")
        binding.editCivAddress.setText((vm.civAddress.value ?: 0xA4).toString(16).uppercase())

        applyCivVisibility(isCiv)

        if (!isCiv && vm.useMDNS.value == true) {
            val h = binding.editHost.text.toString().trim()
            if (h.isNotEmpty() && !h.endsWith(".local")) binding.editHost.setText("$h.local")
        }

        binding.switchMdns.setOnCheckedChangeListener { _, isChecked ->
            if (binding.switchCiv.isChecked) return@setOnCheckedChangeListener
            val host = binding.editHost.text.toString().trim()
            if (isChecked && host.isNotEmpty() && !host.endsWith(".local")) {
                binding.editHost.setText("$host.local")
            } else if (!isChecked && host.endsWith(".local")) {
                binding.editHost.setText(host.removeSuffix(".local"))
            }
        }

        binding.switchCiv.setOnCheckedChangeListener { _, isChecked ->
            applyCivVisibility(isChecked)
            if (isChecked) binding.switchMdns.isChecked = false
        }

        binding.btnConnect.setOnClickListener {
            val host = binding.editHost.text.toString().trim()
            val civMode = binding.switchCiv.isChecked

            if (host.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter a host address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val myCall = binding.editMyCall.text.toString().trim().uppercase()
            vm.prefs.ft8MyCall = myCall

            if (civMode) {
                val civPort = binding.editCivPort.text.toString().toIntOrNull() ?: 50001
                val civPort2 = binding.editCivPort2.text.toString().toIntOrNull() ?: 50002
                val civPort3 = binding.editCivPort3.text.toString().toIntOrNull() ?: 50003
                val civUser = binding.editCivUser.text.toString().trim()
                val civPass = binding.editCivPassword.text.toString()
                val civAddrStr = binding.editCivAddress.text.toString().trim()
                val civAddr = civAddrStr.toIntOrNull(16) ?: 0xA4

                vm.updateCivSettings(host, civPort, civPort2, civPort3, civUser, civPass, civAddr)
            } else {
                val apiPort = binding.editApiPort.text.toString().toIntOrNull() ?: 8210
                val audioPort = binding.editAudioPort.text.toString().toIntOrNull() ?: 8211
                val apiKey = binding.editApiKey.text.toString().trim()
                val mdns = binding.switchMdns.isChecked

                vm.updateConnectionSettings(host, apiPort, audioPort, mdns, apiKey)
            }

            binding.btnConnect.isEnabled = false
            binding.progressBar.visibility = View.VISIBLE
            binding.tvStatus.text = if (civMode) "Connecting to rig via CI-V..." else "Connecting..."

            lifecycleScope.launch {
                val error = vm.connectToRasPi()
                binding.btnConnect.isEnabled = true
                binding.progressBar.visibility = View.GONE
                if (error == null) {
                    if (vm.isDemoMode.value == true) {
                        Toast.makeText(requireContext(), "DEMO MODE - No server connection required", Toast.LENGTH_LONG).show()
                    }
                    findNavController().navigate(R.id.action_ConnectFragment_to_RigSelectFragment)
                } else {
                    binding.tvStatus.text = error
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applyCivVisibility(isCiv: Boolean) {
        binding.groupPiOnly.visibility = if (isCiv) View.GONE else View.VISIBLE
        binding.rowMdns.visibility = if (isCiv) View.GONE else View.VISIBLE
        binding.groupCivOnly.visibility = if (isCiv) View.VISIBLE else View.GONE
        binding.tvConnectTitle.text = if (isCiv) "CI-V CONNECT" else "RasPi CONNECT"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
