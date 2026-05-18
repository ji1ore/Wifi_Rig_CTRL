package com.example.wifi_rig_ctrl

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.wifi_rig_ctrl.data.ProfileConfig
import com.example.wifi_rig_ctrl.databinding.FragmentSplashBinding
import com.example.wifi_rig_ctrl.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashFragment : Fragment() {

    private var _binding: FragmentSplashBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()
    private var timerJob: Job? = null
    private var acted = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSplashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleGroup.animate().alpha(1f).setDuration(800).start()

        binding.btnNormal.setOnClickListener {
            if (acted) return@setOnClickListener
            acted = true
            timerJob?.cancel()
            navigateNormal()
        }

        binding.btnSkip.setOnClickListener {
            if (acted) return@setOnClickListener
            acted = true
            timerJob?.cancel()
            doSkip()
        }

        binding.btnAddProfile.setOnClickListener {
            showAddProfileDialog()
        }

        // 1秒後にボタン・プロファイル表示、さらに8秒後に自動でNormal
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(1000)
            binding.btnNormal.visibility = View.VISIBLE
            binding.btnSkip.visibility = View.VISIBLE
            binding.profileSection.visibility = View.VISIBLE
            refreshProfileList()
            delay(8000)
            if (acted) return@launch
            acted = true
            navigateNormal()
        }
    }

    private fun refreshProfileList() {
        if (_binding == null) return
        val profiles = vm.prefs.getProfiles()
        val activeIdx = vm.prefs.activeProfileIndex
        binding.llProfiles.removeAllViews()
        val dp = resources.displayMetrics.density

        profiles.forEachIndexed { idx, profile ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(if (idx == activeIdx) 0xFF1565C0.toInt() else 0xFF2A2A2A.toInt())
                val vPad = (10 * dp).toInt()
                val hPad = (12 * dp).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (3 * dp).toInt() }
            }

            val tv = TextView(requireContext()).apply {
                text = profile.name
                textSize = 17f
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tv)

            if (idx == activeIdx) {
                val check = TextView(requireContext()).apply {
                    text = "✓"
                    textSize = 17f
                    setTextColor(0xFFAEEA00.toInt())
                }
                row.addView(check)
            }

            row.setOnClickListener {
                vm.loadProfile(idx)
                refreshProfileList()
            }

            row.setOnLongClickListener {
                showProfileOptions(idx, profile.name)
                true
            }

            binding.llProfiles.addView(row)
        }
    }

    private fun showAddProfileDialog() {
        val edit = EditText(requireContext()).apply {
            hint = "プロファイル名"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("新規プロファイル")
            .setView(edit)
            .setPositiveButton("作成") { _, _ ->
                val profiles = vm.prefs.getProfiles()
                val name = edit.text.toString().trim().ifEmpty { "Profile ${profiles.size + 1}" }
                profiles.add(ProfileConfig(name = name))
                vm.prefs.saveProfiles(profiles)
                val newIdx = profiles.size - 1
                vm.loadProfile(newIdx)
                refreshProfileList()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showProfileOptions(idx: Int, name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(name)
            .setItems(arrayOf("名前を変更", "削除")) { _, which ->
                when (which) {
                    0 -> showRenameDialog(idx, name)
                    1 -> deleteProfile(idx)
                }
            }
            .show()
    }

    private fun showRenameDialog(idx: Int, currentName: String) {
        val edit = EditText(requireContext()).apply {
            setText(currentName)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("名前を変更")
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                val newName = edit.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val profiles = vm.prefs.getProfiles()
                    if (idx < profiles.size) {
                        profiles[idx] = profiles[idx].copy(name = newName)
                        vm.prefs.saveProfiles(profiles)
                        refreshProfileList()
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun deleteProfile(idx: Int) {
        val profiles = vm.prefs.getProfiles()
        if (profiles.size <= 1) {
            Toast.makeText(requireContext(), "プロファイルは1つ以上必要です", Toast.LENGTH_SHORT).show()
            return
        }
        profiles.removeAt(idx)
        vm.prefs.saveProfiles(profiles)
        val activeIdx = vm.prefs.activeProfileIndex
        val newActive = when {
            activeIdx >= profiles.size -> profiles.size - 1
            activeIdx > idx -> activeIdx - 1
            else -> activeIdx
        }.coerceAtLeast(0)
        vm.loadProfile(newActive)
        refreshProfileList()
    }

    private fun doSkip() {
        binding.btnNormal.isEnabled = false
        binding.btnSkip.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val error = vm.skipConnect()
            if (_binding == null) return@launch
            binding.progressBar.visibility = View.GONE
            if (error == null) {
                val navOptions = NavOptions.Builder()
                    .setPopUpTo(R.id.SplashFragment, true)
                    .build()
                findNavController().navigate(R.id.ConnectFragment, null, navOptions)
            } else {
                binding.tvStatus.text = error
                binding.tvStatus.visibility = View.VISIBLE
                delay(5000)
                if (_binding == null) return@launch
                navigateNormal()
            }
        }
    }

    private fun navigateNormal() {
        if (_binding == null) return
        val navOptions = NavOptions.Builder()
            .setPopUpTo(R.id.SplashFragment, true)
            .build()
        findNavController().navigate(R.id.ConnectFragment, null, navOptions)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timerJob?.cancel()
        _binding = null
    }
}
