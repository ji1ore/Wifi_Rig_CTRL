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
            val (apiVer, rigctld, webft8Ver) = withContext(Dispatchers.IO) {
                val api = try { vm.api.getApiVersion() } catch (e: Exception) { "?" to (e.message ?: "error") }
                val wv = try { vm.api.getWebft8Version() } catch (e: Exception) { "?" }
                Triple(api.first, api.second, wv)
            }
            binding.tvPiVersion.text = "Pi API: $apiVer"
            binding.tvHamlibVersion.text = "Hamlib: $rigctld"
            binding.tvWebft8Version.text = "WebFT8: $webft8Ver"
            if (webft8Ver != "?") vm.webft8Version.value = webft8Ver
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
