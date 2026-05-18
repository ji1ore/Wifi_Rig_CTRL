package com.ji1ore.wifi_rig_ctrl.data

import kotlin.math.*

/**
 * RX用 CWデコーダ（多チャンネル対応）
 *
 * アルゴリズム:
 *   1. 256点FFT (50%オーバーラップ) で毎フレームスペクトルを計算
 *   2. CW帯域(300-3000Hz)内の局所極大を検出、上位N_CHAN個を追跡
 *   3. 各チャンネルは固定周波数ビンのエネルギーを独立に処理
 *   4. パーセンタイル閾値 + ヒステリシスで各チャンネルのキー状態を判定
 *   5. フレーム単位で dit/dah/スペースを判定し文字をデコード
 */
class CwDecoder(private val sampleRate: Int = 8000) {

    /** (char, channelIdx 0..N_CHAN-1) */
    var onCharDecoded: ((Char, Int) -> Unit)? = null
    /** (channelIdx, freqHz) — freqHz=0 はチャンネル消滅 */
    var onChannelFreq: ((Int, Int) -> Unit)? = null
    /** スロット入れ替え通知 (slotA, slotB) */
    var onChannelSwap: ((Int, Int) -> Unit)? = null

    // ── FFT設定 ──────────────────────────────────────────────────
    private val FFT_SIZE  = 256
    private val HOP_SIZE  = FFT_SIZE / 2
    private val msPerHop  = maxOf(1, HOP_SIZE * 1000 / sampleRate)
    private val binHz     = sampleRate.toFloat() / FFT_SIZE
    private val cwBinMin  = (300f  / binHz).toInt().coerceAtLeast(1)
    private val cwBinMax  = (3000f / binHz).toInt().coerceAtMost(FFT_SIZE / 2 - 1)
    private val FREQ_TOL      = 3     // ビン単位の周波数マッチング許容幅
    private val DRIFT_RADIUS  = 4     // チャンネルが追跡できる周波数ドリフト幅 (±4 bins = ±125 Hz)
    private val PEAK_SNR_MIN  = 10f  // 新チャンネル作成に必要な (ピーク / ノイズフロア) 比
    private val CAND_PROMOTE  = maxOf(2, 32 / msPerHop)  // ≈32ms 連続でチャンネル昇格

    private val hannWin = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }
    private val ringBuf   = FloatArray(FFT_SIZE)
    private var ringHead  = 0
    private var hopCount  = 0
    private val fftRe     = FloatArray(FFT_SIZE)
    private val fftIm     = FloatArray(FFT_SIZE)
    private val specPow   = FloatArray(FFT_SIZE / 2)
    private val cwBandBuf     = FloatArray(FFT_SIZE / 2)  // ノイズフロア推定用スクラッチ
    // チャンネル候補: bin → SNR閾値超えの連続フレーム数
    private val peakCandidates = LinkedHashMap<Int, Int>()

    // ── dit長パラメータ ──────────────────────────────────────────
    private val ditWinsInit = maxOf(2f, 32f / msPerHop)   // Float: ≒32ms 初期値 (全速度対応)
    private val ditWinsMax  = maxOf(3f, 250f / msPerHop)  // Float: ≒250ms 上限
    private val ditWinsMin  = maxOf(2f, 24f / msPerHop)   // Float: ≒24ms 静的下限

    // ── チャンネル消滅閾値 (≒4秒間キーONなし) ────────────────────
    private val DEACT_THRESH = maxOf(10, 4000 / msPerHop)

    // ── ホワイトノイズ検出: noisePow/maxPow > 閾値なら平坦スペクトル ──
    private val NOISE_FLATNESS_THRESH = 0.5f

    // ── 公開定数 ─────────────────────────────────────────────────
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
    }

    // ── 内部チャンネル ────────────────────────────────────────────
    private inner class Channel(var freqBin: Int, seedSigE: Float, seedNoiseE: Float) {
        val freqHz: Int get() = (freqBin * binHz).toInt()

        private val HIST_SZ       = maxOf(30, 1600 / msPerHop)
        private val SUSPEND_THRESH = maxOf(5, 2000 / msPerHop)  // 2000ms無音で休眠
        private val hist    = FloatArray(HIST_SZ)
        private var histIdx = 0

        private var threshOn  = Float.MAX_VALUE
        private var threshOff = Float.MAX_VALUE
        private var suspKeyOnFrames = 0  // 休眠中の連続キーON確認フレーム数

        init {
            // 信号がノイズより大きければ即座に閾値を設定する。
            // updateThresh の比率要求を待たずに初期値を持つことで MAX_VALUE ループを防ぐ。
            if (seedSigE > seedNoiseE) {
                threshOn  = seedNoiseE + (seedSigE - seedNoiseE) * 0.60f
                threshOff = seedNoiseE + (seedSigE - seedNoiseE) * 0.28f
            }
            // 履歴をシードしてその後の updateThresh を安定させる
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
        var smoothE             = 0f   // 信号強度EMA（スロット昇格判定用）

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

            // 実要素がない状態が長く続いたら休眠（ノイズスパイクはditWinsMin未満のため休眠カウンタをリセットしない）
            if (framesNoRealElement > SUSPEND_THRESH) {
                if (energy > threshOn) {
                    if (++suspKeyOnFrames >= CAND_PROMOTE) {
                        // 本物の信号を確認 → 休眠解除
                        framesNoRealElement = 0; keyOffWins = 0; keyOn = false; keyOnWins = 0
                        elements.clear(); charFlushed = false; wordEmitted = false
                        suspKeyOnFrames = 0
                        // 以降の通常処理へ落ちる（キーON検出）
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
                // framesNoRealElement はリセットしない — analyzeElement で実要素確認後にリセット
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
            val tmp = hist.copyOf(valid); tmp.sort()
            val lo = tmp[(valid * 0.15).toInt().coerceIn(0, valid - 1)]
            val hi = tmp[(valid * 0.85).toInt().coerceIn(0, valid - 1)]
            // 通常 CW の sigE/noiseE は 2〜3 程度。8x 要求では自己修復できないため 3x/2x に緩める。
            when {
                hi > lo * 3f -> { threshOn = lo + (hi-lo)*0.60f; threshOff = lo + (hi-lo)*0.28f }
                hi > lo * 2f -> { threshOn = lo + (hi-lo)*0.55f; threshOff = lo + (hi-lo)*0.28f }
                // コントラスト不足時は閾値を更新しない（既存値を維持）
            }
        }

        private fun analyzeElement(wins: Int) {
            // ditWins較正後は打鍵速度に基づく動的ノイズフィルタ (0.6×dit未満はノイズとみなす)
            val effectiveMin = maxOf(ditWinsMin, ditWins * 0.6f)
            if (wins.toFloat() < effectiveMin) return
            framesNoRealElement = 0
            if (wins.toFloat() < ditWins * 2f) {
                elements.append('.')
                ditWins = (ditWins * 0.85f + wins * 0.15f).coerceIn(ditWinsMin, ditWinsMax)
            } else {
                elements.append('-')
                ditWins = (ditWins * 0.85f + wins / 3f * 0.15f).coerceIn(ditWinsMin, ditWinsMax)
            }
        }

        private fun analyzeGap(wins: Int, ci: Int) {
            if (elements.isEmpty() || charFlushed) return
            when {
                wins.toFloat() >= ditWins * 6f -> {
                    flushChar(ci); charFlushed = true
                    if (!wordEmitted) { onCharDecoded?.invoke(' ', ci); wordEmitted = true }
                }
                wins.toFloat() >= ditWins * 2f -> { flushChar(ci); charFlushed = true }
            }
        }

        private fun flushChar(ci: Int) {
            if (elements.isEmpty()) return
            val c = MORSE_TABLE[elements.toString()] ?: '?'
            onCharDecoded?.invoke(c, ci)
            elements.clear()
        }

        // ホワイトノイズ期間用: hist/閾値/ditWins を汚染せず keyOffWins だけ進める
        fun processQuiet(ci: Int) {
            if (keyOn) { analyzeElement(keyOnWins); keyOnWins = 0; keyOn = false }
            keyOffWins++; framesNoRealElement++
            if (!charFlushed  && keyOffWins.toFloat() >= ditWins * 3f) { flushChar(ci); charFlushed = true }
            if (!wordEmitted && hadSignal && keyOffWins.toFloat() >= ditWins * 7f) {
                onCharDecoded?.invoke(' ', ci); wordEmitted = true
            }
        }

        // 近傍ピークに周波数追従。ビンが変化した場合 true を返す
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
    private var promoteCountdown = 0  // 最強チャンネルをスロット0に昇格するカウンタ

    // ── 公開 API ─────────────────────────────────────────────────

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

    // ── フレーム処理 ─────────────────────────────────────────────

    private fun processFrame() {
        for (i in 0 until FFT_SIZE) {
            fftRe[i] = ringBuf[(ringHead + i) % FFT_SIZE] * hannWin[i]
            fftIm[i] = 0f
        }
        fft(fftRe, fftIm)

        // パワースペクトル計算
        for (bin in cwBinMin..cwBinMax) {
            specPow[bin] = fftRe[bin] * fftRe[bin] + fftIm[bin] * fftIm[bin]
        }

        // ノイズフロア推定: CW帯域の下位30パーセンタイルをノイズとみなす
        val cwRange = cwBinMax - cwBinMin + 1
        for (i in 0 until cwRange) cwBandBuf[i] = specPow[cwBinMin + i]
        java.util.Arrays.sort(cwBandBuf, 0, cwRange)
        val noisePow = cwBandBuf[(cwRange * 0.30).toInt().coerceIn(0, cwRange - 1)]
        val peakMinPow = noisePow * PEAK_SNR_MIN

        // ホワイトノイズ検出: ソート済み cwBandBuf の最大値で平坦度を判定
        val maxCwPow = cwBandBuf[cwRange - 1]
        val isNoise = maxCwPow <= 0f || noisePow / maxCwPow > NOISE_FLATNESS_THRESH

        // 既存チャンネルを各自の周波数ビンのエネルギーで更新
        for (ci in 0 until N_CHAN) {
            val ch = channels[ci] ?: continue
            if (isNoise) {
                // ノイズ期間中は hist/閾値/ditWins を保護し keyOffWins だけ進める
                ch.processQuiet(ci)
            } else {
                if (ch.trackPeak(specPow)) onChannelFreq?.invoke(ci, ch.freqHz)
                val b = ch.freqBin
                val e = sqrt(
                    specPow[(b-1).coerceAtLeast(cwBinMin)] +
                    specPow[b] +
                    specPow[(b+1).coerceAtMost(cwBinMax)]
                )
                ch.process(e, ci)
            }
            if (ch.shouldDeactivate) {
                channels[ci] = null
                onChannelFreq?.invoke(ci, 0)
            }
        }

        // trackPeak後に同じ周波数に収束したチャンネルを統合（弱い方を消す）
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

        // 局所極大を収集し SNR閾値超えかつ既存チャンネル未カバーのビン集合を求める
        val strongBins = mutableSetOf<Int>()
        for (bin in (cwBinMin + 1) until cwBinMax) {
            if (specPow[bin] > specPow[bin-1] && specPow[bin] > specPow[bin+1]
                && specPow[bin] >= peakMinPow) {
                val covered = channels.any { it != null && abs(it.freqBin - bin) <= FREQ_TOL }
                if (!covered) strongBins.add(bin)
            }
        }

        // 候補マップを更新: 今フレームも強いビンはインクリメント、消えたものは除去
        val candIter = peakCandidates.iterator()
        while (candIter.hasNext()) { if (candIter.next().key !in strongBins) candIter.remove() }
        for (bin in strongBins) peakCandidates[bin] = (peakCandidates[bin] ?: 0) + 1

        // 最強チャンネルをスロット0に昇格 (≈2秒ごと、50%以上の強度差がある場合)
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

        // CAND_PROMOTE フレーム以上同じ bin に信号が継続した候補をチャンネルへ昇格
        val noiseE = sqrt(noisePow * 3f)
        for (bin in strongBins.sortedByDescending { specPow[it] }) {
            if ((peakCandidates[bin] ?: 0) < CAND_PROMOTE) continue
            val slot = channels.indexOfFirst { it == null }
            if (slot < 0) break
            val e = sqrt(
                specPow[(bin-1).coerceAtLeast(cwBinMin)] +
                specPow[bin] +
                specPow[(bin+1).coerceAtMost(cwBinMax)]
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
