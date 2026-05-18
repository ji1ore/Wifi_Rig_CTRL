package com.example.wifi_rig_ctrl.data

import com.google.gson.Gson
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class RigApiService(private var hostName: String, private var apiPort: Int, private var apiKey: String = "") {

    internal val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val openClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val baseUrl get() = "http://$hostName:$apiPort"

    fun updateConnection(host: String, port: Int, key: String = "") {
        hostName = host
        apiPort = port
        apiKey = key
    }

    private fun Request.Builder.withApiKey(): Request.Builder {
        if (apiKey.isNotEmpty()) addHeader("X-API-Key", apiKey)
        return this
    }

    private fun get(path: String): String? = try {
        val req = Request.Builder().url("$baseUrl$path").withApiKey().build()
        client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (_: Exception) { null }

    private fun post(path: String, body: okhttp3.RequestBody): Boolean = try {
        val req = Request.Builder().url("$baseUrl$path").withApiKey().post(body).build()
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    fun getRigs(): List<RigInfo> {
        val body = get("/rigs") ?: return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            val rigs = doc["rigs"] as? List<Map<String, Any>> ?: return emptyList()
            rigs.map { RigInfo((it["id"] as Double).toInt(), it["name"] as String) }
        } catch (_: Exception) { emptyList() }
    }

    fun getDevices(): Pair<List<String>, List<SoundDevice>> {
        val body = get("/devices") ?: return Pair(emptyList(), emptyList())
        return try {
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            val serial = (doc["serial"] as? List<String>) ?: emptyList()
            val audioList = (doc["audio"] as? List<Map<String, Any>>) ?: emptyList()
            val sounds = audioList.map { SoundDevice(it["id"] as String, it["label"] as String) }
            Pair(serial.sorted(), sounds)
        } catch (_: Exception) { Pair(emptyList(), emptyList()) }
    }

    fun openRig(rigId: Int, cat: String, baud: Int, ptt: String = "NONE", pttType: String = "RTS"): Boolean {
        val catParam = if (cat == "None") "" else cat
        val pttParam = if (ptt == "NONE") "" else ptt
        return try {
            val req = Request.Builder()
                .url("$baseUrl/radio/open?model=$rigId&cat=$catParam&baud=$baud&ptt=$pttParam&ptt_type=$pttType")
                .withApiKey()
                .build()
            openClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    fun getStatus(): RigStatus? {
        val body = get("/radio/status") ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            val freq = when (val f = doc["freq"]) {
                is Double -> f.toLong()
                is String -> f.toLongOrNull() ?: 0L
                else -> 0L
            }
            val power = when (val pv = doc["power"]) {
                is Double -> pv.toFloat()
                is String -> pv.toFloatOrNull() ?: 0f
                else -> 0f
            }
            val width = (doc["width"] as? Double)?.toInt() ?: 0
            val sql = when (val sv = doc["sql"]) {
                is String -> sv.toFloatOrNull() ?: 0f
                is Double -> sv.toFloat()
                else -> 0f
            }
            RigStatus(
                freq = freq,
                mode = doc["mode"] as? String ?: "",
                signal = (doc["signal"] as? Double)?.toFloat() ?: 0f,
                tx = doc["tx"] as? Boolean ?: false,
                power = power,
                width = width,
                sql = sql,
                txInProgress = doc["tx_in_progress"] as? Boolean ?: false
            )
        } catch (_: Exception) { null }
    }

    fun getCaps(): List<String> {
        val body = get("/radio/caps") ?: return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            (doc["modes"] as? List<String>) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun setFreq(freqHz: Long) = post("/radio/setfreq",
        FormBody.Builder().add("f", freqHz.toString()).build())

    fun setMode(mode: String, width: Int) = post("/radio/setmode",
        FormBody.Builder().add("mode", mode).add("width", width.toString()).build())

    fun setLevel(name: String, value: Float) = post("/radio/setlevel",
        FormBody.Builder().add("name", name).add("value", "%.3f".format(value)).build())

    fun setPower(value: Float) = post("/radio/setpower",
        FormBody.Builder().add("value", "%.3f".format(value)).build())

    fun setPtt(on: Boolean) = post("/radio/ptt",
        FormBody.Builder().add("state", if (on) "1" else "0").build())

    fun setPoll(on: Boolean) = post("/radio/poll",
        FormBody.Builder().add("state", if (on) "1" else "0").build())

    fun pttHeartbeat() = post("/radio/ptt_heartbeat", FormBody.Builder().build())

    fun sendAprsConfig(cfg: AprsConfig): Boolean {
        val json = buildAprsJson(cfg)
        return post("/aprs_config", json.toRequestBody("application/json".toMediaType()))
    }

    fun startAprs(cfg: AprsConfig): Boolean {
        val json = """{"callsign":"${cfg.callsign}","ssid":${cfg.ssid},"path":"${cfg.path}","interval":${cfg.interval},"freq":${cfg.freq},"baud":${cfg.baud},"use_gps":${cfg.useGps},"manual_lat":${cfg.manualLat},"manual_lon":${cfg.manualLon}}"""
        return post("/aprs_start", json.toRequestBody("application/json".toMediaType()))
    }

    fun stopAprs() = post("/aprs_stop", "".toRequestBody("text/plain".toMediaType()))

    fun sendAprsHeartbeat() = post("/aprs_heartbeat", "".toRequestBody("text/plain".toMediaType()))

    fun sendGps(lat: Float, lon: Float) = post("/gps",
        """{"lat":$lat,"lon":$lon}""".toRequestBody("application/json".toMediaType()))

    fun getAudioStreamUrl(audioPort: Int, samplingRate: Int) =
        "http://$hostName:$audioPort/radio/audio?rate=$samplingRate"

    fun getAudioTxUrl(rate: Int = 8000) = "$baseUrl/radio/audio_tx?rate=$rate"

    private fun buildAprsJson(cfg: AprsConfig) =
        """{"callsign":"${cfg.callsign}","ssid":${cfg.ssid},"path":"${cfg.path}","interval":${cfg.interval},"freq":${cfg.freq},"baud":${cfg.baud},"use_gps":${cfg.useGps},"manual_lat":${cfg.manualLat},"manual_lon":${cfg.manualLon},"symbol":"${cfg.symbol}","destination":"${cfg.destination}","sound_device":"${cfg.soundDevice}","rig_id":"${cfg.rigId}","cat_device":"${cfg.catDevice}"}"""
}
