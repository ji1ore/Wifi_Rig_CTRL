package com.example.wifi_rig_ctrl.data

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

    private var txJob: Job? = null
    private var activeCall: Call? = null
    @Volatile private var recording = false

    // writeTo()のマイクループが終了した時点でcompleteされる
    // txJob.join()と異なりPiのHTTPレスポンス待ちは含まない
    private var micDrained: CompletableDeferred<Unit>? = null

    private val client = baseClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
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
                            sink.write(buf, 0, n)
                            sink.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.d("AudioTx", "writeTo ended: ${e.message}")
                } finally {
                    // マイクループ終了 = 全データをOkHttpバッファに渡した時点で通知
                    // PiのHTTPレスポンス待ち(最大5秒)より先にawaitDrain()が返れる
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
        // activeCall/txJob はキャンセルしない — writeTo()が自然に終わり残音を送出する
    }

    // マイクループ終了（= 全音声データをOkHttpバッファに渡した）まで待機
    // txJob.join()と異なりPiのHTTPレスポンスを待たないため最速で返る
    suspend fun awaitDrain(timeoutMs: Long = 1000) {
        if (txJob?.isActive != true) return  // TX未起動ならスキップ
        val def = micDrained ?: return
        try {
            withTimeout(timeoutMs) { def.await() }
        } catch (_: Exception) {
            Log.d("AudioTx", "awaitDrain timeout(${timeoutMs}ms)")
        }
    }

    fun stop() {
        recording = false
        micDrained?.complete(Unit)  // タイムアウト待ちがあれば解除
        micDrained = null
        activeCall?.cancel()
        activeCall = null
        txJob?.cancel()
        txJob = null
    }
}
