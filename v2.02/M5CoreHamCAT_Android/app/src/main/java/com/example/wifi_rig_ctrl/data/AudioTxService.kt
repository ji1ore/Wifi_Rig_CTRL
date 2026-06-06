package com.ji1ore.wifi_rig_ctrl.data

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

class AudioTxService(baseClient: OkHttpClient) {

    var gainMultiplier: Float = 1.0f

    private var txJob: Job? = null
    private var activeCall: Call? = null
    @Volatile private var recording = false

    // Completed when the writeTo() mic loop ends
    // Unlike txJob.join(), does not include waiting for Pi's HTTP response
    private var micDrained: CompletableDeferred<Unit>? = null

    private val client = baseClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun start(url: String, apiKey: String = "") {
        stop()
        recording = true
        micDrained = null

        val rate = 8000
        val minBuf = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = minBuf

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize * 4
        )

        val agc = if (AutomaticGainControl.isAvailable())
            AutomaticGainControl.create(record.audioSessionId)?.also { it.enabled = false }
        else null

        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                val buf = ByteArray(bufSize)
                record.startRecording()
                try {
                    while (recording) {
                        val n = record.read(buf, 0, buf.size)
                        if (n > 0) {
                            val gain = gainMultiplier
                            if (gain != 1.0f) {
                                val shorts = n / 2
                                for (k in 0 until shorts) {
                                    val lo = buf[k * 2].toInt() and 0xFF
                                    val hi = buf[k * 2 + 1].toInt()
                                    val sample = (hi shl 8) or lo
                                    val amplified = (sample * gain).toInt().coerceIn(-32768, 32767)
                                    buf[k * 2] = (amplified and 0xFF).toByte()
                                    buf[k * 2 + 1] = (amplified shr 8).toByte()
                                }
                            }
                            sink.write(buf, 0, n)
                            sink.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.d("AudioTx", "writeTo ended: ${e.message}")
                } finally {
                    // Notify when mic loop ends = all data passed to OkHttp buffer
                    // awaitDrain() returns before Pi's HTTP response wait (up to 5 seconds)
                    micDrained?.complete(Unit)
                    record.stop()
                    record.release()
                    agc?.release()
                }
            }
        }

        txJob = CoroutineScope(Dispatchers.IO).launch {
            val request = Request.Builder().url(url)
                .apply { if (apiKey.isNotEmpty()) addHeader("X-API-Key", apiKey) }
                .post(body).build()
            val call = client.newCall(request)
            activeCall = call
            try {
                call.execute().close()
            } catch (e: Exception) {
                Log.e("AudioTx", "TX error: ${e.message}")
            }
        }
    }

    fun gracefulStop() {
        micDrained = CompletableDeferred()
        recording = false
        // Don't cancel activeCall/txJob — let writeTo() end naturally to flush remaining audio
    }

    // Wait until mic loop ends (= all audio data passed to OkHttp buffer)
    // Returns as fast as possible because it doesn't wait for Pi's HTTP response unlike txJob.join()
    suspend fun awaitDrain(timeoutMs: Long = 1000) {
        if (txJob?.isActive != true) return  // skip if TX not started
        val def = micDrained ?: return
        try {
            withTimeout(timeoutMs) { def.await() }
        } catch (_: Exception) {
            Log.d("AudioTx", "awaitDrain timeout(${timeoutMs}ms)")
        }
    }

    fun stop() {
        if (activeCall != null) {
            val caller = Thread.currentThread().stackTrace.drop(1).take(5)
                .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            Log.w("AudioTx", "stop() while active: $caller")
        }
        recording = false
        micDrained?.complete(Unit)
        micDrained = null
        activeCall?.cancel()
        activeCall = null
        txJob?.cancel()
        txJob = null
    }
}
