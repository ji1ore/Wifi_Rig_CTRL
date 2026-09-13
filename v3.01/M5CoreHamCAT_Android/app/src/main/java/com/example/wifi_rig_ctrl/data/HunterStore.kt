package com.ji1ore.wifi_rig_ctrl.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class HunterStore(context: Context) {

    private val gson = Gson()
    private val storeFile = File(context.filesDir, "pota_hunter_parks.json")
    private var cache: Set<String> = emptySet()

    var parkCount: Int = 0
        private set

    init { load() }

    fun isHunted(reference: String): Boolean = cache.contains(reference.uppercase())

    fun importFromApiJson(json: String): Int {
        return try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val list: List<Map<String, Any>> = gson.fromJson(json, type) ?: return 0
            val parks = list.mapNotNull { map ->
                val ref = (map["reference"] as? String)?.ifBlank { return@mapNotNull null }
                    ?: return@mapNotNull null
                val locDesc = map["short"] as? String
                val qsos = (map["qsos"] as? Double)?.toInt() ?: 0
                HunterPark(ref, locDesc, qsos)
            }
            val set = mutableSetOf<String>()
            for (p in parks) {
                set.add(p.reference.uppercase())
                if (!p.locationDesc.isNullOrEmpty()) {
                    set.add("${p.reference.uppercase()}|${p.locationDesc}")
                }
            }
            cache = set
            parkCount = parks.size
            save(parks)
            parks.size
        } catch (_: Exception) { 0 }
    }

    private fun save(parks: List<HunterPark>) {
        storeFile.writeText(gson.toJson(parks))
    }

    private fun load() {
        if (!storeFile.exists()) return
        try {
            val type = object : TypeToken<List<HunterPark>>() {}.type
            val parks: List<HunterPark> = gson.fromJson(storeFile.readText(), type) ?: emptyList()
            val set = mutableSetOf<String>()
            for (p in parks) {
                set.add(p.reference.uppercase())
                if (!p.locationDesc.isNullOrEmpty()) {
                    set.add("${p.reference.uppercase()}|${p.locationDesc}")
                }
            }
            cache = set
            parkCount = parks.size
        } catch (_: Exception) { cache = emptySet() }
    }
}
