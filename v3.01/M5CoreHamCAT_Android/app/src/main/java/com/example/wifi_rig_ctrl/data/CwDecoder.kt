package com.ji1ore.wifi_rig_ctrl.data

import kotlin.math.*

/**
 * RX CW decoder (multi-channel support)
 *
 * Algorithm:
 *   1. Compute spectrum each frame with 256-point FFT (50% overlap)
 *   2. Detect local maxima in CW band (300-3000Hz), track top N_CHAN
 *   3. Each channel independently processes energy at its fixed frequency bin
 *   4. Determine key state per channel via percentile threshold + hysteresis
 *   5. Decode characters by judging dit/dah/space per frame
 */
class CwDecoder(private val sampleRate: Int = 8000) {

    /** (char, channelIdx 0..N_CHAN-1) */
    var onCharDecoded: ((Char, Int) -> Unit)? = null
    /** (channelIdx, freqHz) — freqHz=0 means channel deactivated */
    var onChannelFreq: ((Int, Int) -> Unit)? = null
    /** Slot swap notification (slotA, slotB) */
    var onChannelSwap: ((Int, Int) -> Unit)? = null

    // ── FFT settings ─────────────────────────────────────────────
    private val FFT_SIZE  = 512              // 256→512: 15.6Hz/bin で弱信号SNR向上
    private val HOP_SIZE  = 128             // time resolution kept at 16ms (75% overlap)
    private val msPerHop  = maxOf(1, HOP_SIZE * 1000 / sampleRate)
    private val binHz     = sampleRate.toFloat() / FFT_SIZE
    private val cwBinMin  = (300f  / binHz).toInt().coerceAtLeast(1)
    private val cwBinMax  = (3000f / binHz).toInt().coerceAtMost(FFT_SIZE / 2 - 1)
    private val FREQ_TOL      = 4     // Frequency match tolerance in bins (±62Hz)
    private val DRIFT_RADIUS  = 6     // Frequency drift range a channel can track (±94Hz)
    private val PEAK_SNR_MIN  = 5f   // 10→5: create channel even for weak signals (~7dB above noise)
    private val CAND_PROMOTE  = maxOf(2, 32 / msPerHop)  // promote candidate to channel after ≈32ms continuous signal

    private val hannWin = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }
    private val ringBuf   = FloatArray(FFT_SIZE)
    private var ringHead  = 0
    private var hopCount  = 0
    private val fftRe     = FloatArray(FFT_SIZE)
    private val fftIm     = FloatArray(FFT_SIZE)
    private val specPow   = FloatArray(FFT_SIZE / 2)
    private val cwBandBuf     = FloatArray(FFT_SIZE / 2)  // scratch buffer for noise floor estimation
    // Channel candidates: bin → consecutive frames exceeding SNR threshold
    private val peakCandidates = LinkedHashMap<Int, Int>()

    // ── dit length parameters ─────────────────────────────────────
    private val ditWinsInit = maxOf(2f, 32f / msPerHop)   // Float: ≈32ms initial value (all speeds)
    private val ditWinsMax  = maxOf(3f, 250f / msPerHop)  // Float: ≈250ms upper limit
    private val ditWinsMin  = maxOf(2f, 24f / msPerHop)   // Float: ≈24ms static lower limit

    // ── Channel deactivation threshold (≈4 seconds without key ON) ──
    private val DEACT_THRESH = maxOf(10, 4000 / msPerHop)

    // ── White noise detection: flat spectrum if noisePow/maxPow > threshold ──
    private val NOISE_FLATNESS_THRESH = 0.35f  // 0.5→0.35: avoid falsely detecting weak signals as noise

    // ── Public constants ──────────────────────────────────────────
    companion object {
        const val N_CHAN = 5
        val MORSE_TABLE: Map<String, Char> = mapOf(
            ".-"   to 'A', "-..."  to 'B', "-.-." to 'C', "-.."  to 'D',
            "."    to 'E', "..-."  to 'F', "--."  to 'G', "...." to 'H',
            ".."   to 'I', ".---"  to 'J', "-.-"  to 'K', ".-.." to 'L',
            "--"   to 'M', "-."    to 'N', "---"  to 'O', ".--." to 'P',
            "--.-" to 'Q', ".-."   to 'R', "..."  to 'S', "-"    to 'T',
            "..-"  to 'U', "...-"  to 'V', ".--"  to 'W', "-..-" to 'X',
            "-.--" to 'Y', "--.."  to 'Z',
            "-----" to '0', ".----" to '1', "..---" to '2', "...--" to '3',
            "....-" to '4', "....." to '5', "-...." to '6', "--..." to '7',
            "---.." to '8', "----." to '9',
            ".-.-.-"  to '.', "--..--" to ',', "..--.."  to '?',
            "-.--."   to '(', "-.--.-" to ')', "-..-."   to '/',
            ".-.-."   to '+', "-...-"  to '=', ".-..."   to '&',
            "---..."  to ':', "-.-.-"  to ';', ".--.-."  to '@',
            ".----."  to '\''
        )
        val CHAR_TO_MORSE: Map<Char, String> = MORSE_TABLE.entries.associate { (morse, char) -> char to morse }
    }

    // ── Internal channel ──────────────────────────────────────────
    private inner class Channel(var freqBin: Int, seedSigE: Float, seedNoiseE: Float) {
        val freqHz: Int get() = (freqBin * binHz).toInt()

        private val HIST_SZ         = maxOf(30, 1600 / msPerHop)
        private val SUSPEND_THRESH  = maxOf(5, 2000 / msPerHop)   // sleep after 2000ms of silence
        private val ELEM_FLUSH_THRESH = maxOf(3, 500 / msPerHop)  // 500ms: force clear pending elements after gap
        private val hist    = FloatArray(HIST_SZ)
        private var histIdx = 0

        private var threshOn  = Float.MAX_VALUE
        private var threshOff = Float.MAX_VALUE
        private var suspKeyOnFrames = 0  // consecutive key-ON confirmation frames during suspend

        init {
            // Set thresholds immediately if signal is stronger than noise.
            // Having an initial value without waiting for updateThresh ratio requirement prevents MAX_VALUE loop.
            if (seedSigE > seedNoiseE) {
                threshOn  = seedNoiseE + (seedSigE - seedNoiseE) * 0.50f  // 0.60→0.50: handle weak signals
                threshOff = seedNoiseE + (seedSigE - seedNoiseE) * 0.22f  // 0.28→0.22: maintain hysteresis
            }
            // Seed history to stabilize subsequent updateThresh calls
            val seedN = maxOf(CAND_PROMOTE, 8)
            repeat(seedN) { hist[histIdx++ % HIST_SZ] = seedSigE }
            repeat(seedN) { hist[histIdx++ % HIST_SZ] = seedNoiseE }
        }

        var keyOn      = false
        var keyOnWins  = 0
        var keyOffWins = 0
        var charFlushed  = false
        var wordEmitted  = false
        var ditWins      = ditWinsInit
        val elements     = StringBuilder()

        var framesNoRealElement = 0
        var hadSignal           = false
        var smoothE             = 0f   // Signal strength EMA (for slot promotion)

        fun process(energy: Float, ci: Int) {
            smoothE = smoothE * 0.97f + energy * 0.03f  // τ≈530ms
            hist[histIdx % HIST_SZ] = energy
            histIdx++
            val valid = minOf(histIdx, HIST_SZ)
            if (histIdx % 5 == 0 && valid >= 5) updateThresh(valid)

            if (threshOn == Float.MAX_VALUE) {
                if (keyOn) keyOnWins++ else { keyOffWins++; framesNoRealElement++ }
                return
            }

            // Force clear pending elements after 500ms+ gap — prevents previous buffer contaminating after recovery
            if (framesNoRealElement == ELEM_FLUSH_THRESH && elements.isNotEmpty() && !charFlushed) {
                flushChar(ci); charFlushed = true
                keyOn = false; keyOnWins = 0
            }

            // Sleep if no real elements for a long time (noise spikes below ditWinsMin don't reset sleep counter)
            if (framesNoRealElement > SUSPEND_THRESH) {
                if (energy > threshOn) {
                    if (++suspKeyOnFrames >= CAND_PROMOTE) {
                        // Real signal confirmed → wake up
                        framesNoRealElement = 0; keyOffWins = 0; keyOn = false; keyOnWins = 0
                        elements.clear(); charFlushed = false; wordEmitted = false
                        suspKeyOnFrames = 0
                        // Fall through to normal processing (key ON detection)
                    } else return
                } else {
                    suspKeyOnFrames = 0; framesNoRealElement++; return
                }
            }

            val isOn = if (keyOn) energy > threshOff else energy > threshOn
            if (isOn && !keyOn) {
                analyzeGap(keyOffWins, ci)
                keyOffWins = 0; charFlushed = false; wordEmitted = false
                keyOn = true; hadSignal = true
                // Don't reset framesNoRealElement here — reset after real element confirmed in analyzeElement
            } else if (!isOn && keyOn) {
                analyzeElement(keyOnWins); keyOnWins = 0; keyOn = false
            }
            if (keyOn) {
                keyOnWins++
            } else {
                keyOffWins++; framesNoRealElement++
                if (!charFlushed  && keyOffWins.toFloat() >= ditWins * 3f) { flushChar(ci); charFlushed = true }
                if (!wordEmitted && hadSignal && keyOffWins.toFloat() >= ditWins * 7f) {
                    onCharDecoded?.invoke(' ', ci); wordEmitted = true
                }
            }
        }

        private fun updateThresh(valid: Int) {
            // Don't update thresholds during gaps — history filled with noise drives thresholds near floor,
            // causing all noise to be falsely detected as key ON after recovery
            if (framesNoRealElement > HIST_SZ / 3) return
            val tmp = hist.copyOf(valid); tmp.sort()
            val lo = tmp[(valid * 0.15).toInt().coerceIn(0, valid - 1)]
            val hi = tmp[(valid * 0.85).toInt().coerceIn(0, valid - 1)]
            when {
                hi > lo * 3f -> { threshOn = lo + (hi-lo)*0.58f; threshOff = lo + (hi-lo)*0.26f }
                hi > lo * 2f -> { threshOn = lo + (hi-lo)*0.52f; threshOff = lo + (hi-lo)*0.24f }
                // Don't update for contrast below 2x (keep existing calibration values)
            }
        }

        private fun analyzeElement(wins: Int) {
            // After ditWins calibration, dynamic noise filter based on keying speed (below 0.6×dit is noise)
            val effectiveMin = maxOf(ditWinsMin, ditWins * 0.6f)
            if (wins.toFloat() < effectiveMin) return
            framesNoRealElement = 0
            if (wins.toFloat() < ditWins * 1.8f) {
                elements.append('.')
                ditWins = (ditWins * 0.95f + wins * 0.05f).coerceIn(ditWinsMin, ditWinsMax)
            } else {
                elements.append('-')
                ditWins = (ditWins * 0.95f + wins / 3f * 0.05f).coerceIn(ditWinsMin, ditWinsMax)
            }
        }

        private fun analyzeGap(wins: Int, ci: Int) {
            if (elements.isEmpty() || charFlushed) return
            when {
                wins.toFloat() >= ditWins * 6f -> {
                    flushChar(ci); charFlushed = true
                    if (!wordEmitted) { onCharDecoded?.invoke(' ', ci); wordEmitted = true }
                }
                wins.toFloat() >= ditWins * 2.5f -> { flushChar(ci); charFlushed = true }
            }
        }

        private fun flushChar(ci: Int) {
            if (elements.isEmpty()) return
            val c = MORSE_TABLE[elements.toString()] ?: '?'
            onCharDecoded?.invoke(c, ci)
            elements.clear()
        }

        // For white noise periods: advance only keyOffWins without polluting hist/thresholds/ditWins
        fun processQuiet(ci: Int) {
            if (keyOn) { analyzeElement(keyOnWins); keyOnWins = 0; keyOn = false }
            keyOffWins++; framesNoRealElement++
            if (!charFlushed  && keyOffWins.toFloat() >= ditWins * 3f) { flushChar(ci); charFlushed = true }
            if (!wordEmitted && hadSignal && keyOffWins.toFloat() >= ditWins * 7f) {
                onCharDecoded?.invoke(' ', ci); wordEmitted = true
            }
        }

        // Track frequency to nearest peak. Returns true if bin changed.
        fun trackPeak(spec: FloatArray): Boolean {
            var bestBin = freqBin
            var bestPow = spec[freqBin]
            val lo = (freqBin - DRIFT_RADIUS).coerceAtLeast(cwBinMin)
            val hi = (freqBin + DRIFT_RADIUS).coerceAtMost(cwBinMax)
            for (b in lo..hi) {
                if (spec[b] > bestPow) { bestPow = spec[b]; bestBin = b }
            }
            if (bestBin == freqBin) return false
            freqBin = bestBin
            return true
        }

        val shouldDeactivate: Boolean
            get() = framesNoRealElement > DEACT_THRESH
    }

    private val channels = arrayOfNulls<Channel>(N_CHAN)
    private var promoteCountdown = 0  // counter for promoting strongest channel to slot 0

    // ── Public API ────────────────────────────────────────────────

    fun reset() {
        ringBuf.fill(0f); ringHead = 0; hopCount = 0
        peakCandidates.clear()
        for (i in 0 until N_CHAN) {
            if (channels[i] != null) onChannelFreq?.invoke(i, 0)
            channels[i] = null
        }
    }

    fun processSamples(samples: ShortArray) {
        for (s in samples) {
            ringBuf[ringHead] = s.toFloat() / 32768f
            ringHead = (ringHead + 1) % FFT_SIZE
            if (++hopCount >= HOP_SIZE) { hopCount = 0; processFrame() }
        }
    }

    // ── Frame processing ──────────────────────────────────────────

    private fun processFrame() {
        for (i in 0 until FFT_SIZE) {
            fftRe[i] = ringBuf[(ringHead + i) % FFT_SIZE] * hannWin[i]
            fftIm[i] = 0f
        }
        fft(fftRe, fftIm)

        // Compute power spectrum
        for (bin in cwBinMin..cwBinMax) {
            specPow[bin] = fftRe[bin] * fftRe[bin] + fftIm[bin] * fftIm[bin]
        }

        // Noise floor estimation: treat bottom 30th percentile of CW band as noise
        val cwRange = cwBinMax - cwBinMin + 1
        for (i in 0 until cwRange) cwBandBuf[i] = specPow[cwBinMin + i]
        java.util.Arrays.sort(cwBandBuf, 0, cwRange)
        val noisePow = cwBandBuf[(cwRange * 0.20).toInt().coerceIn(0, cwRange - 1)]
        val peakMinPow = noisePow * PEAK_SNR_MIN

        // White noise detection: judge flatness by maximum value of sorted cwBandBuf
        val maxCwPow = cwBandBuf[cwRange - 1]
        val isNoise = maxCwPow <= 0f || noisePow / maxCwPow > NOISE_FLATNESS_THRESH

        // Update existing channels with energy at their respective frequency bins
        for (ci in 0 until N_CHAN) {
            val ch = channels[ci] ?: continue
            if (isNoise) {
                // During noise period, protect hist/thresholds/ditWins and advance only keyOffWins
                ch.processQuiet(ci)
            } else {
                if (ch.trackPeak(specPow)) onChannelFreq?.invoke(ci, ch.freqHz)
                val b = ch.freqBin
                val e = sqrt(
                    specPow[(b-2).coerceAtLeast(cwBinMin)] +
                    specPow[(b-1).coerceAtLeast(cwBinMin)] +
                    specPow[b] +
                    specPow[(b+1).coerceAtMost(cwBinMax)] +
                    specPow[(b+2).coerceAtMost(cwBinMax)]
                )
                ch.process(e, ci)
            }
            if (ch.shouldDeactivate) {
                channels[ci] = null
                onChannelFreq?.invoke(ci, 0)
            }
        }

        // Merge channels that converged to the same frequency after trackPeak (remove weaker one)
        for (i in 0 until N_CHAN - 1) {
            val ci = channels[i] ?: continue
            for (j in i + 1 until N_CHAN) {
                val cj = channels[j] ?: continue
                if (abs(ci.freqBin - cj.freqBin) <= FREQ_TOL) {
                    if (ci.smoothE >= cj.smoothE) {
                        channels[j] = null; onChannelFreq?.invoke(j, 0)
                    } else {
                        channels[i] = null; onChannelFreq?.invoke(i, 0); break
                    }
                }
            }
        }

        // Collect local maxima; find bins exceeding SNR threshold not covered by existing channels
        val strongBins = mutableSetOf<Int>()
        for (bin in (cwBinMin + 1) until cwBinMax) {
            if (specPow[bin] > specPow[bin-1] && specPow[bin] > specPow[bin+1]
                && specPow[bin] >= peakMinPow) {
                val covered = channels.any { it != null && abs(it.freqBin - bin) <= FREQ_TOL }
                if (!covered) strongBins.add(bin)
            }
        }

        // Update candidate map: increment bins still strong this frame, remove gone ones
        val candIter = peakCandidates.iterator()
        while (candIter.hasNext()) { if (candIter.next().key !in strongBins) candIter.remove() }
        for (bin in strongBins) peakCandidates[bin] = (peakCandidates[bin] ?: 0) + 1

        // Promote strongest channel to slot 0 (≈every 2 seconds, if >50% energy difference)
        if (++promoteCountdown >= maxOf(10, 2000 / msPerHop)) {
            promoteCountdown = 0
            var bestSlot = 0
            var bestE = channels[0]?.smoothE ?: -1f
            for (i in 1 until N_CHAN) {
                val e = channels[i]?.smoothE ?: -1f
                if (e > bestE * 1.5f) { bestE = e; bestSlot = i }
            }
            if (bestSlot != 0) {
                val tmp = channels[0]; channels[0] = channels[bestSlot]; channels[bestSlot] = tmp
                onChannelFreq?.invoke(0, channels[0]?.freqHz ?: 0)
                onChannelFreq?.invoke(bestSlot, channels[bestSlot]?.freqHz ?: 0)
                onChannelSwap?.invoke(0, bestSlot)
            }
        }

        // Promote candidates with signal continuing for CAND_PROMOTE+ frames at the same bin to channels
        val noiseE = sqrt(noisePow * 3f)
        for (bin in strongBins.sortedByDescending { specPow[it] }) {
            if ((peakCandidates[bin] ?: 0) < CAND_PROMOTE) continue
            val slot = channels.indexOfFirst { it == null }
            if (slot < 0) break
            val e = sqrt(
                specPow[(bin-2).coerceAtLeast(cwBinMin)] +
                specPow[(bin-1).coerceAtLeast(cwBinMin)] +
                specPow[bin] +
                specPow[(bin+1).coerceAtMost(cwBinMax)] +
                specPow[(bin+2).coerceAtMost(cwBinMax)]
            )
            val ch = Channel(bin, e, noiseE)
            channels[slot] = ch
            onChannelFreq?.invoke(slot, ch.freqHz)
            peakCandidates.remove(bin)
            ch.process(e, slot)
        }
    }

    // ── Cooley-Tukey FFT ─────────────────────────────────────────
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n ushr 1
            while (j and bit != 0) { j = j xor bit; bit = bit ushr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val half = len ushr 1
            val ang  = -PI / half
            val wRe  = cos(ang).toFloat()
            val wIm  = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cRe = 1f; var cIm = 0f
                for (k in 0 until half) {
                    val uRe = re[i+k];            val uIm = im[i+k]
                    val vRe = re[i+k+half]*cRe - im[i+k+half]*cIm
                    val vIm = re[i+k+half]*cIm + im[i+k+half]*cRe
                    re[i+k]      = uRe+vRe;       im[i+k]      = uIm+vIm
                    re[i+k+half] = uRe-vRe;       im[i+k+half] = uIm-vIm
                    val nc = cRe*wRe - cIm*wIm;   cIm = cRe*wIm + cIm*wRe;   cRe = nc
                }
                i += len
            }
            len = len shl 1
        }
    }
}
