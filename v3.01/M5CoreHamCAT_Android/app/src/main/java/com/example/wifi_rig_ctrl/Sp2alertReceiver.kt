package com.ji1ore.wifi_rig_ctrl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ji1ore.wifi_rig_ctrl.viewmodel.MainViewModel

/**
 * SP2ALERT(POTA/SOTAスポット通知アプリ)からの周波数・モード連携を受信する。
 * Intent action: com.ji1ore.ACTION_SET_FREQ_MODE
 * Extras:
 *   "freq_hz" : Long   — 周波数(Hz)
 *   "mode"    : String — モード文字列(POTA/SOTA APIの値: SSB/CW/FT8等)
 */
class Sp2alertReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.ji1ore.ACTION_SET_FREQ_MODE"
        const val EXTRA_FREQ_HZ = "freq_hz"
        const val EXTRA_MODE = "mode"

        // POTAスポットAPIのモード → (無線機モード, フィルター幅Hz)
        // SSBは周波数で USB(>=10MHz) / LSB(<10MHz) を自動判定
        fun mapMode(spotMode: String, freqHz: Long): Pair<String, Int> {
            return when (spotMode.uppercase().trim()) {
                "CW", "CW-R"                                         -> Pair("CW",  500)
                "FM", "C4FM", "D-STAR", "DSTAR", "DMR"              -> Pair("FM",  15000)
                "AM"                                                  -> Pair("AM",  6000)
                "FT8", "FT4", "JS8", "RTTY", "PSK31",
                "PSKR", "DIGI", "MSK144"                             -> Pair(usbOrLsb(freqHz), 3000)
                "USB"                                                 -> Pair("USB", 3000)
                "LSB"                                                 -> Pair("LSB", 3000)
                "SSB"                                                 -> Pair(usbOrLsb(freqHz), 3000)
                else                                                  -> Pair(usbOrLsb(freqHz), 3000)
            }
        }

        private fun usbOrLsb(freqHz: Long) = if (freqHz >= 10_000_000L) "USB" else "LSB"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val freqHz  = intent.getLongExtra(EXTRA_FREQ_HZ, 0L)
        val spotMode = intent.getStringExtra(EXTRA_MODE) ?: return
        if (freqHz <= 0L) return

        val vm = MainViewModel.instance ?: return
        if (vm.isConnectedToRig.value != true) return

        val (rigMode, width) = mapMode(spotMode, freqHz)
        vm.sendFreq(freqHz)
        vm.sendMode(rigMode, width)
    }
}
