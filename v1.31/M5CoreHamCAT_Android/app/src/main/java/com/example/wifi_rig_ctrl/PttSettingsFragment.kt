package com.example.wifi_rig_ctrl

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.wifi_rig_ctrl.databinding.FragmentPttSettingsBinding
import com.example.wifi_rig_ctrl.viewmodel.MainViewModel

class PttSettingsFragment : Fragment() {

    private var _binding: FragmentPttSettingsBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPttSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.editPttHost.setText(vm.pttHost.value)
        binding.editPttPort.setText(vm.pttPort.value?.toString() ?: "5198")
        updateButtonColors()

        binding.btnWifiPtt.setOnClickListener {
            vm.useWifiPTT.value = true
            updateButtonColors()
        }

        binding.btnHamlibPtt.setOnClickListener {
            vm.useWifiPTT.value = false
            updateButtonColors()
        }

        binding.btnOk.setOnClickListener {
            vm.pttHost.value = binding.editPttHost.text.toString().trim()
            vm.pttPort.value = binding.editPttPort.text.toString().toIntOrNull() ?: 5198
            vm.prefs.useWifiPTT = vm.useWifiPTT.value ?: false
            vm.prefs.pttHost = vm.pttHost.value ?: ""
            vm.prefs.pttPort = vm.pttPort.value ?: 5198
            vm.saveCurrentToProfile()
            findNavController().navigate(R.id.action_PttSettingsFragment_to_RigSelectFragment)
        }
    }

    private fun updateButtonColors() {
        val useWifi = vm.useWifiPTT.value ?: false
        binding.btnWifiPtt.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (useWifi) 0xFF1565C0.toInt() else 0xFF455A64.toInt()
        )
        binding.btnHamlibPtt.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (!useWifi) 0xFF2E7D32.toInt() else 0xFF455A64.toInt()
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
