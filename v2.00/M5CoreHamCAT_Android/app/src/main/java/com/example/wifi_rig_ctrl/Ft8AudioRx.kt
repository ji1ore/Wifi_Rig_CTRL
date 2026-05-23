package com.ji1ore.wifi_rig_ctrl

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.math.*
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Audio receiver for FT8/FT4.
 * - If [streamUrl] is non-null, reads S16_LE mono PCM from the Pi's /radio/audio_sub endpoint
 *   (12000 Hz, subscriber copy — does not compete with the main audio player queue).
 * - If [streamUrl] is null, records from the device microphone via AudioRecord.
 * - Every 16 hops fires [onRow] with FFT magnitudes (dB-normalized, 0..1) for the waterfall.
 * - At each UTC cycle boundary fires [onCycle] with accumulated float PCM for decoding.
 */
class Ft8AudioRx(
    private val isFt4: Boolean,
    private val streamUrl: String? = null,
    private val getClockOffsetMs: () -> Long = { 0L },
    private val onRow:       (FloatArray) -> Unit,
    private val onCycle:     (FloatArray, Int) -> Unit,
    private val onCycleMark: () -> Unit = {},
    private val onError:     (String) -> Unit = {}
) {
    val sampleRate: Int
    private val record: AudioRecord?
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var activeCall: okhttp3.Call? = null
    @Volatile var gainMultiplier: Float = 1.0f

    private val cycleMs get() = if (isFt4) 7500L else 15000L

    // Waterfall FFT: 1024-point, 256-sample hop
    private val fftSize  = 1024
    private val hopSize  = 256
    private val primHops = fftSize / hopSize

    private val dispBinsVal: Int get() = (3000f * fftSize / sampleRate).toInt().coerceAtMost(fftSize / 2)
    val displayedFreqMinHz: Int get() = 0
    val displayedFreqMaxHz: Int get() = (dispBinsVal.toFloat() * sampleRate / fftSize).toInt()

    private val hannWindow = FloatArray(fftSize) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))).toFloat()
    }

    init {
        if (streamUrl != null) {
            sampleRate = 12000
            record = null
            Log.i("Ft8AudioRx", "stream mode url=$streamUrl")
        } else {
            val audioSource = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    MediaRecorder.AudioSource.UNPROCESSED
                else ->
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
            var chosen = 0
            var chosenRecord: AudioRecord? = null
            // Limit to 16000 Hz max: ft8_lib's monitor_process uses stack-allocated VLAs sized to
            // nfft = block_size * freq_osr. At 44100 Hz nfft = 14112 → ~110 KB VLA, which can
            // overflow the decode thread's stack. 12000 Hz (nfft = 3840 → ~30 KB) is the standard.
            for (rate in listOf(12000, 11025, 16000, 8000)) {
                val min = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                val ar = try {
                    AudioRecord(audioSource, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(min, fftSize * 4))
                } catch (e: Exception) {
                    // UNPROCESSED not supported on this device — fall back to MIC
                    AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(min, fftSize * 4))
                }
                if (ar.state == AudioRecord.STATE_INITIALIZED) {
                    chosen = rate; chosenRecord = ar; break
                }
                ar.release()
            }
            if (chosenRecord == null) throw IllegalStateException("AudioRecord 初期化失敗")
            sampleRate = chosen
            record = chosenRecord
            Log.i("Ft8AudioRx", "mic mode sampleRate=$sampleRate source=$audioSource")
        }
    }

    fun start() {
        if (running) return
        running = true
        if (streamUrl != null) {
            thread = Thread({ loopStream() }, "ft8-rx-stream")
        } else {
            record!!.startRecording()
            thread = Thread({ loopMic() }, "ft8-rx-mic")
        }
        thread!!.start()
    }

    fun stop() {
        running = false
        activeCall?.cancel()
        record?.stop()
        record?.release()
        thread?.join(2000)
        thread = null
    }

    // ── HTTP stream loop ───────────────────────────────────────────

    private fun loopStream() {
        var retryDelay = 0L
        while (running) {
            if (retryDelay > 0) Thread.sleep(retryDelay)
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url(streamUrl!!).build()
            val call = client.newCall(req)
            activeCall = call
            var shouldReconnect = false
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        // 503/502: Pi側でffmpeg起動失敗 (TX直後など) → リトライ
                        // 403: 認証失敗 → 永久停止
                        if (resp.code == 403) {
                            if (running) onError("音声ストリーム接続失敗: ${resp.code}")
                            running = false
                            return
                        }
                        retryDelay = 2000L
                        Log.w("Ft8AudioRx", "audio_sub ${resp.code}, retry in ${retryDelay}ms")
                        return@use
                    }
                    val inputStream = resp.body?.byteStream() ?: run {
                        if (running) onError("音声ストリームが空です")
                        running = false
                        return
                    }
                    retryDelay = 1500L
                    val shortBuf = ShortArray(hopSize)
                    val byteHop  = ByteArray(hopSize * 2)
                    loopCore(shortBuf) { buf ->
                        var off = 0
                        while (off < byteHop.size) {
                            val n = try {
                                inputStream.read(byteHop, off, byteHop.size - off)
                            } catch (e: java.net.SocketTimeoutException) {
                                if (running) onError("音声データが届きません。PiのALSAデバイス(${streamUrl})を確認してください")
                                running = false
                                return@loopCore -1
                            } catch (_: Exception) { -1 }
                            if (n <= 0) {
                                // stop() sets running=false before cancel(), so if running is
                                // still true here this is an unexpected Pi-side close → reconnect
                                if (running) shouldReconnect = true
                                running = false
                                return@loopCore -1
                            }
                            off += n
                        }
                        // S16_LE → ShortArray
                        for (i in 0 until hopSize) {
                            val lo = byteHop[i * 2].toInt() and 0xFF
                            val hi = byteHop[i * 2 + 1].toInt()
                            buf[i] = ((hi shl 8) or lo).toShort()
                        }
                        hopSize
                    }
                    // loopCore exited; if reconnect requested, reset running before use{} closes resp
                    if (shouldReconnect) {
                        running = true
                        shouldReconnect = false
                        Log.i("Ft8AudioRx", "stream closed by Pi, will reconnect in ${retryDelay}ms")
                    }
                }
            } catch (e: Exception) {
                if (!running) return
            } finally {
                activeCall = null
            }
            if (!running) return
        }
    }

    // ── Microphone loop ───────────────────────────────────────────

    private fun loopMic() {
        val shortBuf = ShortArray(hopSize)
        loopCore(shortBuf) { buf -> record!!.read(buf, 0, hopSize) }
    }

    // ── Shared processing core ────────────────────────────────────

    private fun loopCore(shortBuf: ShortArray, readHop: (ShortArray) -> Int) {
        val overlap    = FloatArray(fftSize)
        val reFft      = FloatArray(fftSize)
        val imFft      = FloatArray(fftSize)
        var refLevelDb = -60f

        val cycleCapacity = (sampleRate * cycleMs / 1000L).toInt()
        val cycleBuf      = FloatArray(cycleCapacity)
        var cycleFill     = 0
        var lastCycleId   = -1L
        var hopCount = 0
        var wfHop    = 0
        val dispBins = dispBinsVal

        while (running) {
            val rd = readHop(shortBuf)
            if (rd <= 0) continue

            // Accumulate for cycle decoder
            val g = gainMultiplier
            for (i in 0 until rd) {
                val f = (shortBuf[i] / 32768f * g).coerceIn(-1f, 1f)
                if (cycleFill < cycleBuf.size) cycleBuf[cycleFill++] = f
            }

            // Slide overlap buffer for FFT
            System.arraycopy(overlap, hopSize, overlap, 0, fftSize - hopSize)
            for (i in 0 until rd) overlap[fftSize - hopSize + i] = (shortBuf[i] / 32768f * g).coerceIn(-1f, 1f)

            if (++hopCount < primHops) continue

            // Apply Hann window and compute FFT
            for (i in 0 until fftSize) { reFft[i] = overlap[i] * hannWindow[i]; imFft[i] = 0f }
            fft(reFft, imFft)

            // Magnitude in dB for positive frequencies
            val halfLen = fftSize / 2
            val dbMags  = FloatArray(halfLen) { i ->
                val mag = sqrt(reFft[i] * reFft[i] + imFft[i] * imFft[i])
                20f * log10(mag.coerceAtLeast(1e-10f))
            }

            // Noise floor estimate (every 8th bin, skip DC)
            var noiseEstimate = 0f; var noiseSamples = 0; var j = 4
            while (j < halfLen) { noiseEstimate += dbMags[j]; noiseSamples++; j += 8 }
            if (noiseSamples > 0) noiseEstimate /= noiseSamples.toFloat()

            refLevelDb = if (noiseEstimate < refLevelDb)
                refLevelDb * 0.90f + noiseEstimate * 0.10f   // fast fall
            else
                refLevelDb * 0.99f + noiseEstimate * 0.01f   // slow rise

            // Waterfall: one row per 16 hops ≈ 340 ms
            val dynRange = 40f
            val result = FloatArray(dispBins) { i ->
                ((dbMags[i] - refLevelDb) / dynRange).coerceIn(0f, 1f)
            }
            if (++wfHop % 8 == 0) onRow(result)

            // UTC cycle boundary check (Pi clock offset + FT8 dt-based correction)
            val utcMs   = (System.currentTimeMillis() + getClockOffsetMs()) % 60_000L
            val cycleId = utcMs / cycleMs

            if (cycleId != lastCycleId) {
                if (cycleFill >= cycleCapacity / 4) {
                    onCycleMark()
                    val copy = cycleBuf.copyOf(cycleFill)
                    val rate = sampleRate
                    Log.i("Ft8AudioRx", "cycle fired: $cycleFill samples @${rate}Hz")
                    Thread(null, {
                        try { onCycle(copy, rate) }
                        catch (t: Throwable) { Log.e("Ft8AudioRx", "onCycle error: ${t.javaClass.simpleName}: ${t.message}") }
                    }, "ft8-decode", 8 * 1024 * 1024).start()
                } else {
                    Log.i("Ft8AudioRx", "cycle skip: only $cycleFill samples (startup partial)")
                }
                lastCycleId = cycleId
                cycleFill = 0
            }
        }
    }

    // ── Cooley-Tukey in-place FFT ─────────────────────────────────

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i+k+len/2]*curRe - im[i+k+len/2]*curIm
                    val vIm = re[i+k+len/2]*curIm + im[i+k+len/2]*curRe
                    re[i+k]       = uRe+vRe; im[i+k]       = uIm+vIm
                    re[i+k+len/2] = uRe-vRe; im[i+k+len/2] = uIm-vIm
                    val nr = curRe*wRe - curIm*wIm
                    curIm = curRe*wIm + curIm*wRe; curRe = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
