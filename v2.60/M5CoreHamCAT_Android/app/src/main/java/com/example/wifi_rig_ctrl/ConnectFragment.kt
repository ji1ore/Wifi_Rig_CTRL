package com.ji1ore.wifi_rig_ctrl

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) showBtDevicePicker()
        else Toast.makeText(requireContext(), "Bluetooth権限が必要です", Toast.LENGTH_SHORT).show()
    }

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
        val isBt = (vm.civConnectionType.value ?: "WIFI") == "BT"

        // Restore saved values
        binding.editHost.setText(if (isCiv && !isBt) vm.civHost.value else if (!isCiv) vm.hostName.value else "")
        binding.editApiPort.setText(vm.apiPort.value?.toString() ?: "8210")
        binding.editAudioPort.setText(vm.audioPort.value?.toString() ?: "8211")
        binding.editApiKey.setText(vm.apiKey.value)
        binding.editMyCall.setText(vm.prefs.ft8MyCall)
        binding.editSsbWidth.setText(vm.prefs.defaultSsbWidth.toString())
        binding.editCwWidth.setText(vm.prefs.defaultCwWidth.toString())
        binding.switchMdns.isChecked = vm.useMDNS.value ?: false
        binding.switchCiv.isChecked = isCiv
        binding.editCivPort.setText((vm.civPort.value ?: 50001).toString())
        binding.editCivPort2.setText((vm.civPort2.value ?: 50002).toString())
        binding.editCivPort3.setText((vm.civPort3.value ?: 50003).toString())
        binding.editCivUser.setText(vm.civUser.value ?: "")
        binding.editCivPassword.setText(vm.civPassword.value ?: "")
        val addrHex = (vm.civAddress.value ?: 0xA4).toString(16).uppercase()
        binding.editCivAddress.setText(addrHex)
        binding.editCivAddressBt.setText(addrHex)

        // Restore RS-BA1 audio fields for BT mode
        binding.editBtAudioHost.setText(if (isBt) vm.civHost.value ?: "" else "")
        binding.editBtAudioUser.setText(if (isBt) vm.civUser.value ?: "" else "")
        binding.editBtAudioPass.setText(if (isBt) vm.civPassword.value ?: "" else "")

        // Restore BT connection type radio
        if (isBt) {
            binding.rbCivBt.isChecked = true
        } else {
            binding.rbCivWifi.isChecked = true
        }

        // Restore selected BT device name
        val btAddr = vm.civBtDeviceAddress.value ?: ""
        if (btAddr.isNotEmpty()) {
            val btName = getPairedDeviceName(btAddr) ?: btAddr
            binding.tvBtDeviceName.text = btName
        }

        applyCivVisibility(isCiv)
        if (isCiv) applyCivTypeVisibility(isBt)

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
            if (isChecked) {
                binding.switchMdns.isChecked = false
                applyCivTypeVisibility(binding.rbCivBt.isChecked)
            }
        }

        binding.rgCivConnectionType.setOnCheckedChangeListener { _, checkedId ->
            applyCivTypeVisibility(checkedId == R.id.rbCivBt)
        }

        binding.btnSelectBtDevice.setOnClickListener { requestBtPermissionAndPick() }

        binding.btnScan.setOnClickListener {
            if (binding.switchCiv.isChecked) startCivDiscovery { binding.editHost.setText(it) }
            else startDiscovery()
        }
        binding.btnScanBtAudio.setOnClickListener {
            startCivDiscovery { binding.editBtAudioHost.setText(it) }
        }

        binding.btnConnect.setOnClickListener {
            val civMode = binding.switchCiv.isChecked
            val btMode = civMode && binding.rbCivBt.isChecked

            val myCall = binding.editMyCall.text.toString().trim().uppercase()
            vm.prefs.ft8MyCall = myCall
            vm.prefs.defaultSsbWidth = binding.editSsbWidth.text.toString().toIntOrNull()?.coerceIn(100, 10000) ?: 3000
            vm.prefs.defaultCwWidth  = binding.editCwWidth.text.toString().toIntOrNull()?.coerceIn(50, 3000)   ?: 500

            if (civMode) {
                if (btMode) {
                    val btAddr = vm.civBtDeviceAddress.value ?: ""
                    if (btAddr.isEmpty()) {
                        Toast.makeText(requireContext(), "Bluetoothデバイスを選択してください", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val civAddrStr = binding.editCivAddressBt.text.toString().trim()
                    val civAddr = civAddrStr.toIntOrNull(16) ?: 0xA4
                    val audioHost = binding.editBtAudioHost.text.toString().trim()
                    val audioUser = binding.editBtAudioUser.text.toString().trim()
                    val audioPass = binding.editBtAudioPass.text.toString()
                    vm.updateCivBtSettings(btAddr, civAddr, audioHost, audioUser, audioPass)
                } else {
                    val host = binding.editHost.text.toString().trim()
                    if (host.isEmpty()) {
                        Toast.makeText(requireContext(), "Please enter a host address", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val civPort = binding.editCivPort.text.toString().toIntOrNull() ?: 50001
                    val civPort2 = binding.editCivPort2.text.toString().toIntOrNull() ?: 50002
                    val civPort3 = binding.editCivPort3.text.toString().toIntOrNull() ?: 50003
                    val civUser = binding.editCivUser.text.toString().trim()
                    val civPass = binding.editCivPassword.text.toString()
                    val civAddrStr = binding.editCivAddress.text.toString().trim()
                    val civAddr = civAddrStr.toIntOrNull(16) ?: 0xA4
                    vm.updateCivSettings(host, civPort, civPort2, civPort3, civUser, civPass, civAddr)
                }
            } else {
                val host = binding.editHost.text.toString().trim()
                if (host.isEmpty()) {
                    Toast.makeText(requireContext(), "Please enter a host address", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val apiPort = binding.editApiPort.text.toString().toIntOrNull() ?: 8210
                val audioPort = binding.editAudioPort.text.toString().toIntOrNull() ?: 8211
                val apiKey = binding.editApiKey.text.toString().trim()
                val mdns = binding.switchMdns.isChecked
                vm.updateConnectionSettings(host, apiPort, audioPort, mdns, apiKey)
            }

            binding.btnConnect.isEnabled = false
            binding.progressBar.visibility = View.VISIBLE
            binding.tvStatus.text = when {
                !civMode -> "Connecting..."
                btMode   -> "Connecting to rig via Bluetooth CI-V..."
                else     -> "Connecting to rig via CI-V (WiFi)..."
            }

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
        // For CI-V: btnScan visibility is set by applyCivTypeVisibility (WiFi=show, BT=hide)
        if (!isCiv) binding.btnScan.visibility = View.VISIBLE
        binding.tvConnectTitle.text = if (isCiv) "CI-V CONNECT" else "WiFi CONNECT"
    }

    private fun applyCivTypeVisibility(isBt: Boolean) {
        binding.groupCivWifi.visibility = if (isBt) View.GONE else View.VISIBLE
        binding.groupCivBt.visibility = if (isBt) View.VISIBLE else View.GONE
        binding.editHost.visibility = if (isBt) View.GONE else View.VISIBLE
        // Show scan button in WiFi CI-V mode for IC-705 IP discovery
        binding.btnScan.visibility = if (isBt) View.GONE else View.VISIBLE
    }

    private fun requestBtPermissionAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                .filter { ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED }
                .toTypedArray()
            if (needed.isNotEmpty()) { btPermissionLauncher.launch(needed); return }
        }
        showBtDevicePicker()
    }

    @SuppressLint("MissingPermission")
    private fun showBtDevicePicker() {
        val bm = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(requireContext(), "Bluetoothが無効です", Toast.LENGTH_SHORT).show()
            return
        }
        val paired = adapter.bondedDevices.toList()
        if (paired.isEmpty()) {
            Toast.makeText(requireContext(), "ペアリング済みデバイスがありません", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = paired.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Bluetoothデバイスを選択")
            .setItems(labels) { _, idx ->
                val device = paired[idx]
                vm.civBtDeviceAddress.value = device.address
                vm.prefs.civBtDeviceAddress = device.address
                binding.tvBtDeviceName.text = device.name ?: device.address
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun getPairedDeviceName(address: String): String? {
        return try {
            val bm = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter?.bondedDevices?.firstOrNull { it.address == address }?.name
        } catch (_: Exception) { null }
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
                        if (found.none { it.ip == ip }) found.add(DiscoveredHost(ip, hostname, apiPort, audioPort))
                    }
                } catch (_: SocketTimeoutException) {}
            }
            sock.close()
        } catch (_: Exception) {}
        return found
    }

    private fun startCivDiscovery(onSelected: (String) -> Unit) {
        val progress = AlertDialog.Builder(requireContext())
            .setTitle("IC-705を検索中...")
            .setMessage("RS-BA1サーバーをスキャンしています (3秒)")
            .setNegativeButton("キャンセル", null)
            .show()

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { discoverCivHosts() }
            if (!isAdded) return@launch
            progress.dismiss()
            if (results.isEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("IC-705が見つかりません")
                    .setMessage("同一WiFiネットワーク上でIC-705のRS-BA1が有効か確認してください。")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle("IC-705が見つかりました")
                    .setItems(results.toTypedArray()) { _, idx -> onSelected(results[idx]) }
                    .show()
            }
        }
    }

    private fun discoverCivHosts(): List<String> {
        val found = mutableListOf<String>()
        val port = 50001
        // AreYouThere packet: 16 bytes, type=0x03
        val myId = (System.currentTimeMillis() and 0xFFFFFFFFL)
        val ayt = ByteArray(16).also { p ->
            p[0] = 0x10; p[1] = 0; p[2] = 0; p[3] = 0   // length LE32 = 16
            p[4] = 0x03; p[5] = 0                          // type LE16 = AreYouThere
            p[6] = 0; p[7] = 0                             // seq = 0
            p[8]  = (myId and 0xFF).toByte()
            p[9]  = ((myId shr 8) and 0xFF).toByte()
            p[10] = ((myId shr 16) and 0xFF).toByte()
            p[11] = ((myId shr 24) and 0xFF).toByte()
            // remoteId = 0 (bytes 12-15 already zero)
        }
        try {
            val sock = DatagramSocket()
            sock.broadcast = true
            sock.soTimeout = 200
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                    if (!iface.isUp || iface.isLoopback) return@forEach
                    iface.interfaceAddresses.forEach { ia ->
                        val local = ia.address ?: return@forEach
                        if (local.address.size != 4) return@forEach  // IPv4 only
                        // Unicast to all /24 IPs
                        val base = local.address.clone()
                        for (i in 1..254) {
                            base[3] = i.toByte()
                            val addr = runCatching { InetAddress.getByAddress(base.clone()) }.getOrNull() ?: continue
                            try { sock.send(DatagramPacket(ayt, ayt.size, addr, port)) } catch (_: Exception) {}
                        }
                        // Broadcast
                        val bcast = ia.broadcast
                        if (bcast != null) {
                            try { sock.send(DatagramPacket(ayt, ayt.size, bcast, port)) } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}

            val buf = ByteArray(64)
            val resp = DatagramPacket(buf, buf.size)
            val deadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < deadline) {
                try {
                    resp.length = buf.size
                    sock.receive(resp)
                    // IAmHere: 16 bytes, type LE16 = 0x04
                    if (resp.length == 16 && buf[4] == 0x04.toByte() && buf[5] == 0x00.toByte()) {
                        val ip = resp.address.hostAddress ?: continue
                        if (ip !in found) found.add(ip)
                    }
                } catch (_: SocketTimeoutException) {}
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
