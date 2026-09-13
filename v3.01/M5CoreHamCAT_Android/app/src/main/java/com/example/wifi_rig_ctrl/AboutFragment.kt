package com.ji1ore.wifi_rig_ctrl

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ji1ore.wifi_rig_ctrl.databinding.FragmentAboutBinding
import com.ji1ore.wifi_rig_ctrl.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val versionName = requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0).versionName
        binding.tvVersion.text = "Version $versionName"
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        lifecycleScope.launch {
            val (apiVer, rigctld, ft8Running) = withContext(Dispatchers.IO) {
                val api = try { vm.api.getApiVersion() } catch (e: Exception) { null to null }
                val ft8 = try { vm.api.getStatus()?.ft8DecodeRunning } catch (e: Exception) { null }
                Triple(api.first, api.second, ft8)
            }
            binding.tvPiVersion.text = if (apiVer != null) "Pi API: $apiVer" else "Pi API: (not connected)"
            binding.tvHamlibVersion.text = if (rigctld != null) "Hamlib: $rigctld" else "Hamlib: (not connected)"
            binding.tvFt8Status.text = when (ft8Running) {
                true  -> "FT8 Decode: running (mfsk-decode)"
                false -> "FT8 Decode: stopped"
                null  -> "FT8 Decode: (not connected)"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
