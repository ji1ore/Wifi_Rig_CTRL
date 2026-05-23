package com.ji1ore.wifi_rig_ctrl.data

import com.google.gson.Gson
import com.ji1ore.wifi_rig_ctrl.Ft8Decoder
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
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

    private val ft8TxClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val ft8DecodeClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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
                txInProgress = doc["tx_in_progress"] as? Boolean ?: false,
                apiVersion = doc["api_version"] as? String ?: ""
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

    fun setSimplex(): Boolean = post("/radio/simplex", "".toRequestBody("text/plain".toMediaType()))

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

    fun getAudioFt8Url(): String {
        val base = "$baseUrl/radio/audio_sub"
        return if (apiKey.isNotEmpty()) "$base?api_key=$apiKey" else base
    }

    fun getTunerState(): Pair<Boolean, Boolean>? {
        val body = get("/radio/tuner") ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            val supported = doc["supported"] as? Boolean ?: false
            val state = doc["state"] as? Boolean ?: false
            Pair(supported, state)
        } catch (_: Exception) { null }
    }

    fun setTuner(on: Boolean) = post("/radio/tuner",
        FormBody.Builder().add("state", if (on) "1" else "0").build())

    fun cwStatus(): CwStatus? {
        val body = get("/cw/status") ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            CwStatus(
                connected  = doc["connected"]   as? Boolean ?: false,
                synced     = doc["synced"]       as? Boolean ?: false,
                offsetMs   = (doc["offset_ms"]   as? Double)?.toLong() ?: 0L,
                maxLateMs  = (doc["max_late_ms"] as? Double)?.toInt()  ?: 0
            )
        } catch (_: Exception) { null }
    }

    fun cwOpen(port: String = "ttyACM0", delayMs: Int = 0): Boolean = try {
        val req = Request.Builder().url("$baseUrl/cw/open?port=$port&delay_ms=$delayMs").withApiKey().build()
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    fun cwClose(): Boolean = post("/cw/close", "".toRequestBody("text/plain".toMediaType()))

    fun cwKey(isOn: Boolean) = post("/cw/key",
        FormBody.Builder().add("is_on", if (isOn) "true" else "false").build())

    // Returns (piClockMs - androidClockMs): negative if Android is ahead of Pi.
    // Use: piTimeNow = System.currentTimeMillis() + piClockOffsetMs
    fun getPiClockOffsetMs(): Long? {
        val t1 = System.currentTimeMillis()
        val body = get("/time") ?: return null
        val t2 = System.currentTimeMillis()
        return try {
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            val piMs = (doc["ms"] as? Double)?.toLong() ?: return null
            // piMs was generated at ~(t1+t2)/2 android time; project to t2 and subtract
            piMs + (t2 - t1) / 2 - t2
        } catch (_: Exception) { null }
    }

    fun setAudioDevice(capture: String, playback: String): Boolean = try {
        val json = """{"capture":"$capture","playback":"$playback"}"""
        val req = Request.Builder().url("$baseUrl/radio/audio_device").withApiKey()
            .post(json.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    fun sendApiUpdate(content: ByteArray): Boolean = try {
        val updateClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val body = content.toRequestBody("text/plain; charset=utf-8".toMediaType())
        val req = Request.Builder().url("$baseUrl/admin/update").withApiKey().post(body).build()
        updateClient.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    // Pi-side TX: sends message text only; Pi encodes FT8 audio and plays (~12.64s then responds)
    fun ft8Stop() = post("/radio/ft8_stop", "".toRequestBody("text/plain".toMediaType()))

    fun ft8Tx(msg: String, audioFreqHz: Int, sampleRate: Int, isFt4: Boolean): Boolean = try {
        val escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"")
        val json = """{"msg":"$escaped","audio_freq":$audioFreqHz,"rate":$sampleRate,"is_ft4":$isFt4}"""
        val req = Request.Builder()
            .url("$baseUrl/radio/ft8_tx")
            .withApiKey()
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        ft8TxClient.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    fun ft8Decode(pcm: ByteArray, rate: Int, isFt4: Boolean): List<Ft8Decoder.Result> {
        return try {
            val req = Request.Builder()
                .url("$baseUrl/radio/ft8_decode?rate=$rate&is_ft4=$isFt4")
                .withApiKey()
                .post(pcm.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            val body = ft8DecodeClient.newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            } ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            val results = doc["results"] as? List<Map<String, Any>> ?: return emptyList()
            results.mapNotNull { r ->
                try {
                    Ft8Decoder.Result(
                        snr = (r["snr"]  as? Number)?.toFloat() ?: 0f,
                        dt  = (r["dt"]   as? Number)?.toFloat() ?: 0f,
                        hz  = ((r["freq"] ?: r["hz"]) as? Number)?.toInt() ?: 0,
                        msg = r["msg"] as? String ?: ""
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun ft8StartPiDecode(isFt4: Boolean): Boolean = try {
        val req = Request.Builder().url("$baseUrl/ft8/start?is_ft4=$isFt4").withApiKey().build()
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    fun ft8StopPiDecode(): Unit = try {
        val req = Request.Builder().url("$baseUrl/ft8/stop").withApiKey().build()
        client.newCall(req).execute().use { }
    } catch (_: Exception) { }

    fun ft8ResultsDrain(): List<Ft8Decoder.Result> {
        return try {
            val req = Request.Builder().url("$baseUrl/ft8/results?drain=true").withApiKey().build()
            val body = client.newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            } ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            val results = doc["results"] as? List<Map<String, Any>> ?: return emptyList()
            results.mapNotNull { r ->
                try {
                    Ft8Decoder.Result(
                        snr = (r["snr"]  as? Number)?.toFloat() ?: 0f,
                        dt  = (r["dt"]   as? Number)?.toFloat() ?: 0f,
                        hz  = ((r["freq"] ?: r["hz"]) as? Number)?.toInt() ?: 0,
                        msg = r["msg"] as? String ?: ""
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun buildAprsJson(cfg: AprsConfig) =
        """{"callsign":"${cfg.callsign}","ssid":${cfg.ssid},"path":"${cfg.path}","interval":${cfg.interval},"freq":${cfg.freq},"baud":${cfg.baud},"use_gps":${cfg.useGps},"manual_lat":${cfg.manualLat},"manual_lon":${cfg.manualLon},"symbol":"${cfg.symbol}","destination":"${cfg.destination}","sound_device":"${cfg.soundDevice}","rig_id":"${cfg.rigId}","cat_device":"${cfg.catDevice}"}"""
}
