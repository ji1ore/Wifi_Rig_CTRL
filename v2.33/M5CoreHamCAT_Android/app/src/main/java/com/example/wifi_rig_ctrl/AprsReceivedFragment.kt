package com.ji1ore.wifi_rig_ctrl

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.ji1ore.wifi_rig_ctrl.data.AprsHeardStation
import com.ji1ore.wifi_rig_ctrl.data.aprsSymbolByCode
import com.ji1ore.wifi_rig_ctrl.databinding.FragmentAprsReceivedBinding
import com.ji1ore.wifi_rig_ctrl.viewmodel.MainViewModel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AprsReceivedFragment : Fragment() {

    private var _binding: FragmentAprsReceivedBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAprsReceivedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            findNavController().navigate(R.id.action_AprsReceivedFragment_to_AprsSettingsFragment)
        }

        binding.btnRefresh.setOnClickListener {
            binding.tvStatus.text = "Loading..."
            vm.fetchAprsReceived()
        }

        vm.aprsReceivedStations.observe(viewLifecycleOwner) { stations ->
            updateStationList(stations)
        }

        vm.fetchAprsReceived()
    }

    private fun updateStationList(stations: List<AprsHeardStation>) {
        val count = stations.size
        binding.tvStatus.text = if (count == 0) "No stations heard" else "$count station(s) heard"

        binding.llStations.removeAllViews()
        val myLat = vm.aprsLat.value ?: 0f
        val myLon = vm.aprsLon.value ?: 0f
        val hasPos = myLat != 0f || myLon != 0f

        for (station in stations.sortedBy { it.ageSec }) {
            val dp = resources.displayMetrics.density

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                setBackgroundColor(0xFF0D1117.toInt())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = (4 * dp).toInt()
                layoutParams = lp
            }

            // --- Left column: call / comment / coords ---
            val leftCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val sym = aprsSymbolByCode(station.symbol)
            val ageStr = formatAge(station.ageSec)

            val tvCall = TextView(requireContext()).apply {
                text = "${sym.emoji} ${station.call}  $ageStr"
                textSize = 14f
                setTextColor(0xFF00FFFF.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            leftCol.addView(tvCall)

            if (station.comment.isNotEmpty()) {
                val tvComment = TextView(requireContext()).apply {
                    text = station.comment
                    textSize = 12f
                    setTextColor(0xFFAAAAAA.toInt())
                }
                leftCol.addView(tvComment)
            }

            val tvCoords = TextView(requireContext()).apply {
                text = "%.5f, %.5f".format(station.lat, station.lon)
                textSize = 11f
                setTextColor(0xFF555555.toInt())
            }
            leftCol.addView(tvCoords)

            row.addView(leftCol)

            // --- Right column: compass arrow + bearing + distance ---
            val rightCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    (56 * dp).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = (8 * dp).toInt()
                layoutParams = lp
            }

            if (hasPos && (station.lat != 0.0 || station.lon != 0.0)) {
                val bearing = bearingDeg(myLat.toDouble(), myLon.toDouble(), station.lat, station.lon)
                val distKm  = haversineKm(myLat.toDouble(), myLon.toDouble(), station.lat, station.lon)

                // Rotating arrow needle
                val tvArrow = TextView(requireContext()).apply {
                    text = "▲"
                    textSize = 26f
                    setTextColor(0xFF00E5FF.toInt())
                    gravity = android.view.Gravity.CENTER
                    rotation = bearing.toFloat()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (40 * dp).toInt()
                    )
                }
                rightCol.addView(tvArrow)

                val tvBearing = TextView(requireContext()).apply {
                    text = "%03.0f°".format(bearing)
                    textSize = 11f
                    setTextColor(0xFF00E5FF.toInt())
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                rightCol.addView(tvBearing)

                val tvDist = TextView(requireContext()).apply {
                    text = if (distKm < 1.0) "%.0fm".format(distKm * 1000) else "%.1fkm".format(distKm)
                    textSize = 11f
                    setTextColor(0xFF888888.toInt())
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                rightCol.addView(tvDist)
            }

            row.addView(rightCol)
            binding.llStations.addView(row)
        }
    }

    private fun formatAge(sec: Int): String = when {
        sec < 60   -> "${sec}s ago"
        sec < 3600 -> "${sec / 60}m ago"
        else       -> "${sec / 3600}h${(sec % 3600) / 60}m ago"
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1R = Math.toRadians(lat1)
        val lat2R = Math.toRadians(lat2)
        val y = sin(dLon) * cos(lat2R)
        val x = cos(lat1R) * sin(lat2R) - sin(lat1R) * cos(lat2R) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
