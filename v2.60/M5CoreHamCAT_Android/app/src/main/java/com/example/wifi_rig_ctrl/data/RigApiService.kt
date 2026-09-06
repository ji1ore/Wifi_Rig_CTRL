package com.ji1ore.wifi_rig_ctrl.data

import com.google.gson.Gson
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

// DNS resolver that caches the last successful resolution so the WebView can use the same IP
// even after the DNS entry expires (e.g. mDNS cache timeout while OkHttp keeps its connection alive).
private class CachingDns : Dns {
    @Volatile private var cachedHostname: String = ""
    @Volatile private var cachedAddresses: List<InetAddress> = emptyList()

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            if (hostname == cachedHostname && cachedAddresses.isNotEmpty()) return cachedAddresses
            throw e
        }
        if (addresses.isNotEmpty()) { cachedHostname = hostname; cachedAddresses = addresses }
        return addresses
    }

    fun getResolvedIp(hostname: String): String? =
        if (cachedHostname == hostname) cachedAddresses.firstOrNull()?.hostAddress else null
}

class RigApiService(private var hostName: String, private var apiPort: Int, private var apiKey: String = "") {

    private val cachingDns = CachingDns()

    internal val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .dns(cachingDns)
        .build()

    fun getResolvedHostIp(): String? = cachingDns.getResolvedIp(hostName)

    // Short timeout for PTT heartbeat: 1.2s timeout + 500ms interval < 3s watchdog
    private val heartbeatClient = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1200, TimeUnit.MILLISECONDS)
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
            val bkIn = when (val b = doc["bk_in"]) {
                is Boolean -> b
                is Double  -> b.toInt() == 1
                else       -> false
            }
            val bkInSupported = when (val s = doc["bk_in_supported"]) {
                is Boolean -> s
                null       -> null
                else       -> null
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
                bkIn = bkIn,
                bkInSupported = bkInSupported,
                apiVersion = doc["api_version"] as? String ?: "",
                webft8Version = doc["webft8_version"] as? String ?: ""
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

    fun setBkIn(on: Boolean): Boolean {
        val body = try {
            val rb = FormBody.Builder().add("state", if (on) "1" else "0").build()
            val req = Request.Builder().url("$baseUrl/radio/setbkin").withApiKey().post(rb).build()
            client.newCall(req).execute().use { r -> r.body?.string() }
        } catch (e: Exception) { android.util.Log.e("BkIn", "setBkIn exception: ${e.message}"); null }
        android.util.Log.d("BkIn", "setBkIn(${if (on) 1 else 0}) → $body")
        return body != null
    }

    fun getBkIn(): Boolean? {
        val body = get("/radio/getbkin") ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            when (val b = doc["bk_in"]) {
                is Boolean -> b
                is Double  -> b.toInt() == 1
                else       -> null
            }
        } catch (_: Exception) { null }
    }

    fun getNrLevel(): Int? {
        val body = get("/radio/noise_reduction") ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(body, Map::class.java)["level"] as? Double)?.toInt()
        } catch (_: Exception) { null }
    }

    fun setNrLevel(level: Int) = post("/radio/noise_reduction",
        FormBody.Builder().add("level", level.toString()).build())

    fun setNrLevelOnPort(port: Int, level: Int) {
        if (port == apiPort) return
        try {
            val url = "http://$hostName:$port/radio/noise_reduction"
            val body = FormBody.Builder().add("level", level.toString()).build()
            val req = Request.Builder().url(url).withApiKey().post(body).build()
            client.newCall(req).execute().use { }
        } catch (_: Exception) { }
    }

    fun setCwDecodeMode(active: Boolean) = post("/radio/cw_decode",
        FormBody.Builder().add("active", if (active) "1" else "0").build())

    fun setCwDecodeModeOnPort(port: Int, active: Boolean) {
        if (port == apiPort) return
        try {
            val url = "http://$hostName:$port/radio/cw_decode"
            val body = FormBody.Builder().add("active", if (active) "1" else "0").build()
            val req = Request.Builder().url(url).withApiKey().post(body).build()
            client.newCall(req).execute().use { }
        } catch (_: Exception) { }
    }

    fun setPtt(on: Boolean) = post("/radio/ptt",
        FormBody.Builder().add("state", if (on) "1" else "0").build())

    fun setPoll(on: Boolean) = post("/radio/poll",
        FormBody.Builder().add("state", if (on) "1" else "0").build())

    fun pttHeartbeat(): Boolean = try {
        val req = Request.Builder().url("$baseUrl/radio/ptt_heartbeat").withApiKey()
            .post(FormBody.Builder().build()).build()
        heartbeatClient.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

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

    fun getAudioSubUrl(rate: Int = 12000, apiKey: String = ""): String {
        val keyParam = if (apiKey.isNotEmpty()) "&api_key=$apiKey" else ""
        return "$baseUrl/radio/audio_sub?rate=$rate$keyParam"
    }

    fun ft8Tx(msg: String, audioFreqHz: Int = 1500, rate: Int = 12000, isFt4: Boolean = false): Boolean = try {
        val json = """{"msg":${com.google.gson.JsonPrimitive(msg)},"audio_freq":$audioFreqHz,"rate":$rate,"is_ft4":$isFt4}"""
        val req = Request.Builder().url("$baseUrl/radio/ft8_tx").withApiKey()
            .post(json.toRequestBody("application/json".toMediaType())).build()
        val ft8Client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        ft8Client.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    fun setAudioDevice(device: String): Boolean = try {
        val json = """{"capture":"$device","playback":"$device"}"""
        val req = Request.Builder().url("$baseUrl/radio/audio_device").withApiKey()
            .post(json.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    fun setAudioDeviceFt8(device: String): Boolean = try {
        val json = """{"capture":"$device"}"""
        val req = Request.Builder().url("$baseUrl/radio/audio_device_ft8").withApiKey()
            .post(json.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

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

    fun getPiClockOffsetMs(): Long? {
        val t1 = System.currentTimeMillis()
        val body = get("/time") ?: return null
        val t2 = System.currentTimeMillis()
        return try {
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            val piMs = (doc["ms"] as? Double)?.toLong() ?: return null
            piMs + (t2 - t1) / 2 - t2
        } catch (_: Exception) { null }
    }

    fun cwSendMorse(text: String, wpm: Int, pttPoll: Boolean = false): Boolean = try {
        val body = FormBody.Builder()
            .add("text", text)
            .add("wpm", wpm.toString())
            .add("ptt_poll", pttPoll.toString())
            .build()
        val req = Request.Builder().url("$baseUrl/cw/send_morse").withApiKey().post(body).build()
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    fun cwStopMorse(): Boolean = post("/cw/stop_morse", "".toRequestBody("text/plain".toMediaType()))

    fun cwMorseStatus(): Boolean {
        return try {
            val body = get("/cw/morse_status") ?: return false
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            doc["sending"] as? Boolean ?: false
        } catch (_: Exception) { false }
    }

    fun cwOpen(port: String = "ttyACM0", delayMs: Int = 0): Boolean = try {
        val req = Request.Builder().url("$baseUrl/cw/open?port=$port&delay_ms=$delayMs").withApiKey().build()
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    fun cwClose(): Boolean = post("/cw/close", "".toRequestBody("text/plain".toMediaType()))

    fun cwKey(isOn: Boolean) = post("/cw/key",
        FormBody.Builder().add("is_on", if (isOn) "true" else "false").build())

    fun rebootPi(): Boolean = try {
        val req = Request.Builder().url("$baseUrl/admin/reboot").withApiKey()
            .post("".toRequestBody("text/plain".toMediaType())).build()
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    fun sendWebFt8Update(content: ByteArray): String? = try {
        val body = content.toRequestBody("text/plain".toMediaType())
        val req = Request.Builder().url("$baseUrl/admin/update_webft8").withApiKey().post(body).build()
        val updateClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        updateClient.newCall(req).execute().use { r ->
            if (r.isSuccessful) null else "HTTP ${r.code}: ${r.body?.string()}"
        }
    } catch (e: Exception) { e.message }

    fun sendCwBridgeUpdate(content: ByteArray): String? = try {
        val body = content.toRequestBody("text/plain".toMediaType())
        val req = Request.Builder().url("$baseUrl/admin/update_cw_bridge").withApiKey().post(body).build()
        val updateClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        updateClient.newCall(req).execute().use { r ->
            if (r.isSuccessful) null else "HTTP ${r.code}: ${r.body?.string()}"
        }
    } catch (e: Exception) { e.message }

    fun sendApiUpdate(content: ByteArray): String? = try {
        val body = content.toRequestBody("text/plain".toMediaType())
        val req = Request.Builder().url("$baseUrl/admin/update").withApiKey().post(body).build()
        val updateClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        updateClient.newCall(req).execute().use { r ->
            if (r.isSuccessful) null else "HTTP ${r.code}: ${r.body?.string()}"
        }
    } catch (e: Exception) { e.message }

    data class SyncTimeResult(val ok: Boolean, val source: String, val driftMs: Int)
    fun syncTime(nowMs: Long): SyncTimeResult = try {
        val json = "{\"ms\":$nowMs}"
        val body = json.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$baseUrl/admin/set_time").withApiKey().post(body).build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            val drift = Regex("\"drift_ms\":(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val source = Regex("\"source\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: "unknown"
            SyncTimeResult(r.isSuccessful, source, drift)
        }
    } catch (e: Exception) { SyncTimeResult(false, "error", 0) }

    fun getSetupLog(): Pair<Boolean, String> = try {
        val req = Request.Builder().url("$baseUrl/admin/setup_log").withApiKey().get().build()
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return Pair(false, "HTTP ${r.code}")
            val body = r.body?.string() ?: "{}"
            val running = body.contains("\"running\":true")
            val log = Regex("\"log\":\"(.*?)\"", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
                ?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: body
            Pair(running, log)
        }
    } catch (e: Exception) { Pair(false, e.message ?: "error") }

    fun sendHamlibInstall(content: ByteArray): String? = try {
        val body = content.toRequestBody("application/x-sh".toMediaType())
        val req = Request.Builder().url("$baseUrl/admin/install_hamlib").withApiKey().post(body).build()
        val installClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        installClient.newCall(req).execute().use { r ->
            if (r.isSuccessful) null else "HTTP ${r.code}: ${r.body?.string()}"
        }
    } catch (e: Exception) { e.message }

    fun getHamlibLog(): Pair<Boolean, String> = try {
        val req = Request.Builder().url("$baseUrl/admin/hamlib_log").withApiKey().get().build()
        val logClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        logClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return Pair(false, "HTTP ${r.code}")
            val body = r.body?.string() ?: "{}"
            val running = body.contains("\"running\":true")
            val log = Regex("\"log\":\"(.*?)\"", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
                ?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: body
            Pair(running, log)
        }
    } catch (e: Exception) { Pair(false, e.message ?: "error") }

    fun getWebft8Version(): String {
        return try {
            val tm = object : X509TrustManager {
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            val ip = cachingDns.getResolvedIp(hostName) ?: hostName
            val sslClient = OkHttpClient.Builder()
                .sslSocketFactory(ctx.socketFactory, tm)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url("https://$ip:8443/server_version").get().build()
            val body = sslClient.newCall(req).execute().use { it.body?.string() } ?: return "?"
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(body, Map::class.java) as? Map<String, Any>)?.get("version") as? String ?: "?"
        } catch (e: Exception) { "?" }
    }

    fun getApiVersion(): Pair<String, String> = try {
        val req = Request.Builder().url("$baseUrl/admin/version").withApiKey().get().build()
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return Pair("?", "HTTP ${r.code}")
            val body = r.body?.string() ?: "{}"
            val apiVer = Regex("\"api_version\":\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "?"
            val rigctld = Regex("\"rigctld\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?.replace("\\n", "\n") ?: "?"
            Pair(apiVer, rigctld)
        }
    } catch (e: Exception) { Pair("?", e.message ?: "error") }

    fun sendSetupScript(content: ByteArray): String? = try {
        val body = content.toRequestBody("application/x-sh".toMediaType())
        val req = Request.Builder().url("$baseUrl/admin/setup").withApiKey().post(body).build()
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { r ->
            if (r.isSuccessful) null else "HTTP ${r.code}: ${r.body?.string()}"
        }
    } catch (e: Exception) { e.message }

    fun getVfoMode(): String? {
        val body = get("/radio/vfo_mode") ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(body, Map::class.java)["mode"] as? String)
        } catch (_: Exception) { null }
    }

    fun getVfoCurrent(): String? {
        val body = get("/radio/vfo_current") ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(body, Map::class.java)["side"] as? String)
        } catch (_: Exception) { null }
    }

    fun vfoToggle(): String? {
        val body = try {
            val req = Request.Builder().url("$baseUrl/radio/vfo_toggle").withApiKey()
                .post("".toRequestBody("text/plain".toMediaType())).build()
            client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        } catch (_: Exception) { null } ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(body, Map::class.java)["side"] as? String)
        } catch (_: Exception) { null }
    }

    fun getAprsReceived(): List<AprsHeardStation> {
        val body = get("/aprs_received") ?: return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            val stations = doc["stations"] as? List<Map<String, Any>> ?: return emptyList()
            stations.map {
                AprsHeardStation(
                    call = it["call"] as? String ?: "",
                    lat = (it["lat"] as? Double) ?: 0.0,
                    lon = (it["lon"] as? Double) ?: 0.0,
                    comment = it["comment"] as? String ?: "",
                    symbol = it["symbol"] as? String ?: ">",
                    ageSec = (it["age_sec"] as? Double)?.toInt() ?: 0
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun getAprsNotifyAndroid(): List<AprsHeardStation> {
        val body = get("/aprs_notify_android") ?: return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            val doc = gson.fromJson(body, Map::class.java)
            val events = doc["events"] as? List<Map<String, Any>> ?: return emptyList()
            events.mapNotNull {
                val call = it["call"] as? String ?: return@mapNotNull null
                AprsHeardStation(
                    call = call,
                    lat = (it["lat"] as? Double) ?: 0.0,
                    lon = (it["lon"] as? Double) ?: 0.0,
                    comment = it["comment"] as? String ?: "",
                    symbol = it["symbol"] as? String ?: ">",
                    ageSec = 0
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun buildAprsJson(cfg: AprsConfig): String {
        val safeComment = cfg.comment.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"callsign":"${cfg.callsign}","ssid":${cfg.ssid},"path":"${cfg.path}","interval":${cfg.interval},"freq":${cfg.freq},"baud":${cfg.baud},"use_gps":${cfg.useGps},"manual_lat":${cfg.manualLat},"manual_lon":${cfg.manualLon},"symbol":"${cfg.symbol}","comment":"$safeComment","destination":"${cfg.destination}","sound_device":"${cfg.soundDevice}","rig_id":"${cfg.rigId}","cat_device":"${cfg.catDevice}","use_rig_modem":${cfg.useRigModem},"modem_sel":${cfg.modemSel},"enabled":${cfg.enabled}}"""
    }
}
