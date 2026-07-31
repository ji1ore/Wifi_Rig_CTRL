package com.ji1ore.wifi_rig_ctrl

import android.app.AlertDialog
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

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

        binding.btnScan.setOnClickListener { startDiscovery() }

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
        binding.btnScan.visibility = if (isCiv) View.GONE else View.VISIBLE
        binding.tvConnectTitle.text = if (isCiv) "CI-V CONNECT" else "RasPi CONNECT"
    }

    private fun startDiscovery() {
        val progress = AlertDialog.Builder(requireContext())
            .setTitle("デバイスを検索中...")
            .setMessage("ネットワークをスキャンしています (2秒)")
            .setNegativeButton("キャンセル", null)
            .show()

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { discoverHosts() }
            if (!isAdded) return@launch
            progress.dismiss()
            if (results.isEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("デバイスが見つかりません")
                    .setMessage("WIFI_RIG_CTRL サーバーが見つかりませんでした。\nPi が起動していてapi.pyが動作していることを確認してください。")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                val labels = results.map { "${it.hostname}  (${it.ip})" }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle("デバイスが見つかりました")
                    .setItems(labels) { _, idx ->
                        binding.editHost.setText(results[idx].ip)
                        // ポートは既存の設定を維持する
                    }
                    .show()
            }
        }
    }

    private data class DiscoveredHost(
        val ip: String,
        val hostname: String,
        val apiPort: Int,
        val audioPort: Int
    )

    private fun discoverHosts(): List<DiscoveredHost> {
        val found = mutableListOf<DiscoveredHost>()
        val magic = "WIFI_RIG_CTRL_DISCOVER".toByteArray()
        val port = 5001
        try {
            val sock = DatagramSocket()
            sock.broadcast = true
            sock.soTimeout = 200

            // 全 NIC のサブネットブロードキャストに送信（テザリング含む）
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                    if (!iface.isUp || iface.isLoopback) return@forEach
                    iface.interfaceAddresses.forEach { ia ->
                        val bcast = ia.broadcast ?: return@forEach
                        try { sock.send(DatagramPacket(magic, magic.size, bcast, port)) }
                        catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            // フォールバック: 限定ブロードキャスト
            try { sock.send(DatagramPacket(magic, magic.size, InetAddress.getByName("255.255.255.255"), port)) }
            catch (_: Exception) {}

            val buf = ByteArray(256)
            val resp = DatagramPacket(buf, buf.size)
            val deadline = System.currentTimeMillis() + 2500L
            while (System.currentTimeMillis() < deadline) {
                try {
                    resp.length = buf.size
                    sock.receive(resp)
                    val msg = String(resp.data, 0, resp.length)
                    if (msg.startsWith("WIFI_RIG_CTRL_HERE:")) {
                        val parts = msg.removePrefix("WIFI_RIG_CTRL_HERE:").split(":")
                        val hostname  = parts.getOrElse(0) { "unknown" }
                        val apiPort   = parts.getOrElse(1) { "8210" }.toIntOrNull() ?: 8210
                        val audioPort = parts.getOrElse(2) { "8211" }.toIntOrNull() ?: 8211
                        val ip = resp.address.hostAddress ?: continue
                        if (found.none { it.ip == ip }) {
                            found.add(DiscoveredHost(ip, hostname, apiPort, audioPort))
                        }
                    }
                } catch (_: SocketTimeoutException) { /* 正常、締め切りまでループ */ }
            }
            sock.close()
        } catch (_: Exception) {}
        return found
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
