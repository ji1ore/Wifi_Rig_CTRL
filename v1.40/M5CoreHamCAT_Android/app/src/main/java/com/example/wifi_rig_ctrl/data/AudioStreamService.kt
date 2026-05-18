package com.example.wifi_rig_ctrl.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class AudioStreamService {

    private var streamJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var activeCall: Call? = null
    @Volatile private var currentVolume: Float = 0.5f
    @Volatile private var stopping = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    // onError: UIスレッドではないので呼び出し元でpostする
    fun start(url: String, vol: Float, apiKey: String = "", onError: ((String) -> Unit)? = null) {
        stop()
        stopping = false
        currentVolume = vol
        val sampleRate = extractSampleRate(url)

        streamJob = CoroutineScope(Dispatchers.IO).launch {
            var lastError: String? = null
            for (attempt in 1..MAX_RETRY) {
                if (!isActive || stopping) break
                if (attempt > 1) {
                    Log.d("AudioStream", "retry $attempt/$MAX_RETRY after ${RETRY_DELAY_MS}ms")
                    delay(RETRY_DELAY_MS)
                    if (!isActive || stopping) break
                }
                val t0 = System.currentTimeMillis()
                var streamed = false
                try {
                    val reqBuilder = Request.Builder().url(url)
                    if (apiKey.isNotEmpty()) reqBuilder.addHeader("X-API-Key", apiKey)
                    val call = client.newCall(reqBuilder.build())
                    activeCall = call
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            lastError = "Audio stream connect failed (${response.code})"
                            Log.e("AudioStream", "$lastError attempt=$attempt ${System.currentTimeMillis()-t0}ms")
                            return@use  // retry
                        }
                        Log.d("AudioStream", "connected ${System.currentTimeMillis()-t0}ms attempt=$attempt")

                        val minBuf = AudioTrack.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        val bufSize = maxOf(minBuf, 4096) * 8

                        val track = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(sampleRate)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build()
                            )
                            .setBufferSizeInBytes(bufSize)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()

                        track.setVolume(currentVolume)
                        audioTrack = track

                        val body = response.body ?: run {
                            lastError = "Audio stream: no response body"
                            track.release()
                            audioTrack = null
                            return@use
                        }
                        val stream = body.byteStream()
                        val readBuf = ByteArray(8192)

                        var prebuffered = 0
                        val prebufferTarget = minOf(bufSize / 2, 2048)
                        while (prebuffered < prebufferTarget && isActive) {
                            val n = stream.read(readBuf, 0, minOf(readBuf.size, prebufferTarget - prebuffered))
                            if (n <= 0) break
                            if (prebuffered == 0) Log.d("AudioStream", "first data ${System.currentTimeMillis()-t0}ms")
                            track.write(readBuf, 0, n)
                            prebuffered += n
                        }

                        if (prebuffered == 0) {
                            lastError = "No audio data (attempt=$attempt, ${System.currentTimeMillis()-t0}ms)"
                            Log.e("AudioStream", lastError!!)
                            track.release()
                            audioTrack = null
                            return@use  // retry
                        }

                        Log.d("AudioStream", "play start ${System.currentTimeMillis()-t0}ms prebuf=${prebuffered}B")
                        track.play()
                        streamed = true
                        lastError = null

                        while (isActive) {
                            val n = stream.read(readBuf)
                            if (n <= 0) break
                            track.write(readBuf, 0, n)
                        }

                        track.stop()
                        track.release()
                        audioTrack = null
                    }
                } catch (e: Exception) {
                    if (!stopping) {
                        lastError = "Audio connect error: ${e.message}"
                        Log.e("AudioStream", "attempt $attempt: ${e.message}")
                    } else {
                        break
                    }
                }
                if (stopping) break
                if (streamed) {
                    // Stream dropped unexpectedly — wait then reconnect
                    lastError = "Audio stream lost"
                    Log.w("AudioStream", "stream dropped attempt=$attempt, reconnecting")
                    delay(RECONNECT_DELAY_MS)
                    if (!isActive || stopping) break
                }
            }
            if (!stopping && lastError != null) {
                onError?.invoke(lastError!!)
            }
        }
    }

    fun stop() {
        stopping = true
        streamJob?.cancel()
        streamJob = null
        activeCall?.cancel()
        activeCall = null
        val track = audioTrack
        audioTrack = null
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
    }

    fun setVolume(vol: Float) {
        currentVolume = vol
        audioTrack?.setVolume(vol)
    }

    val isPlaying: Boolean get() = audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING

    private fun extractSampleRate(url: String): Int =
        Regex("rate=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 8000

    companion object {
        private const val MAX_RETRY = 10
        private const val RETRY_DELAY_MS = 500L
        private const val RECONNECT_DELAY_MS = 2000L
    }
}
