package com.example.wifi_rig_ctrl

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.wifi_rig_ctrl.databinding.FragmentFreqInputBinding
import com.example.wifi_rig_ctrl.viewmodel.MainViewModel

class FreqInputFragment : Fragment() {

    private var _binding: FragmentFreqInputBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFreqInputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初期値はブランク — OKのままで周波数を変えない
        binding.etFreqInput.setText("")

        binding.etFreqInput.requestFocus()
        binding.etFreqInput.post {
            requireContext().getSystemService<InputMethodManager>()
                ?.showSoftInput(binding.etFreqInput, InputMethodManager.SHOW_IMPLICIT)
        }

        binding.etFreqInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitFreq()
                true
            } else false
        }

        binding.btnOk.setOnClickListener { submitFreq() }
    }

    private fun submitFreq() {
        val input = binding.etFreqInput.text.toString().trim().replace(',', '.')
        if (input.isNotEmpty()) {
            val freqMHz = input.toDoubleOrNull()
            if (freqMHz != null && freqMHz > 0) {
                val freqHz = (freqMHz * 1e6 + 0.5).toLong()
                vm.sendFreq(freqHz)
            }
        }
        // ブランクの場合は何もせず戻る
        findNavController().navigate(R.id.action_FreqInputFragment_to_MainControlFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
