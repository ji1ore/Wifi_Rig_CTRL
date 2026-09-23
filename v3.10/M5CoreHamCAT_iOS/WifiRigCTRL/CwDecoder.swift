import Foundation

/// RX CW decoder (multi-channel support). Port of Android CwDecoder.kt.
///
/// Algorithm:
///   1. Compute spectrum each frame with 512-point FFT (75% overlap)
///   2. Detect local maxima in CW band (300-3000Hz), track up to N_CHAN
///   3. Each channel independently processes energy at its fixed bin
///   4. Determine key state per channel via percentile threshold + hysteresis
///   5. Decode characters by dit/dah/space timing
final class CwDecoder {
    static let N_CHAN: Int = 5

    static let morseTable: [String: Character] = [
        ".-": "A", "-...": "B", "-.-.": "C", "-..": "D",
        ".": "E", "..-.": "F", "--.": "G", "....": "H",
        "..": "I", ".---": "J", "-.-": "K", ".-..": "L",
        "--": "M", "-.": "N", "---": "O", ".--.": "P",
        "--.-": "Q", ".-.": "R", "...": "S", "-": "T",
        "..-": "U", "...-": "V", ".--": "W", "-..-": "X",
        "-.--": "Y", "--..": "Z",
        "-----": "0", ".----": "1", "..---": "2", "...--": "3",
        "....-": "4", ".....": "5", "-....": "6", "--...": "7",
        "---..": "8", "----.": "9",
        ".-.-.-": ".", "--..--": ",", "..--..": "?",
        "-.--.": "(", "-.--.-": ")", "-..-.": "/",
        ".-.-.": "+", "-...-": "=", ".-...": "&",
        "---...": ":", "-.-.-": ";", ".--.-.": "@",
        ".----.": "'"
    ]

    var onCharDecoded: ((Character, Int) -> Void)?
    var onChannelFreq: ((Int, Int) -> Void)?
    var onChannelSwap: ((Int, Int) -> Void)?

    private let sampleRate: Int
    private let fftSize: Int = 512
    private let hopSize: Int = 128
    private let msPerHop: Int
    private let binHz: Float
    private let cwBinMin: Int
    private let cwBinMax: Int
    private let freqTol: Int = 4
    private let driftRadius: Int = 6
    private let peakSnrMin: Float = 5
    private let candPromote: Int
    private let deactThresh: Int
    private let noiseFlatnessThresh: Float = 0.35
    private let ditWinsInit: Float
    private let ditWinsMax: Float
    private let ditWinsMin: Float

    private let hannWin: [Float]
    private var ringBuf: [Float]
    private var ringHead: Int = 0
    private var hopCount: Int = 0
    private var fftRe: [Float]
    private var fftIm: [Float]
    private var specPow: [Float]
    private var cwBandBuf: [Float]
    private var peakCandidates: [(bin: Int, count: Int)] = []
    private var channels: [Channel?]
    private var promoteCountdown: Int = 0

    init(sampleRate: Int = 8000) {
        self.sampleRate = sampleRate
        self.msPerHop = max(1, hopSize * 1000 / sampleRate)
        self.binHz = Float(sampleRate) / Float(fftSize)
        self.cwBinMin = max(1, Int(300 / binHz))
        self.cwBinMax = min(fftSize / 2 - 1, Int(3000 / binHz))
        self.candPromote = max(2, 32 / msPerHop)
        self.deactThresh = max(10, 4000 / msPerHop)
        self.ditWinsInit = max(4.0, 64.0 / Float(msPerHop))   // 64ms ≈ 19WPM initial assumption
        self.ditWinsMax  = max(8.0, 500.0 / Float(msPerHop))  // 500ms ≈ slow CW upper bound
        self.ditWinsMin  = max(2.0, 24.0 / Float(msPerHop))
        let n = fftSize
        self.hannWin = (0..<n).map { i in
            Float(0.5 * (1.0 - cos(2.0 * .pi * Double(i) / Double(n - 1))))
        }
        self.ringBuf = .init(repeating: 0, count: n)
        self.fftRe = .init(repeating: 0, count: n)
        self.fftIm = .init(repeating: 0, count: n)
        self.specPow = .init(repeating: 0, count: n / 2)
        self.cwBandBuf = .init(repeating: 0, count: n / 2)
        self.channels = .init(repeating: nil, count: CwDecoder.N_CHAN)
    }

    // MARK: - Public API

    func reset() {
        for i in 0..<fftSize { ringBuf[i] = 0 }
        ringHead = 0; hopCount = 0
        peakCandidates.removeAll()
        for i in 0..<CwDecoder.N_CHAN {
            if channels[i] != nil { onChannelFreq?(i, 0) }
            channels[i] = nil
        }
    }

    func processSamples(_ samples: [Int16]) {
        for s in samples {
            ringBuf[ringHead] = Float(s) / 32768.0
            ringHead = (ringHead + 1) % fftSize
            hopCount += 1
            if hopCount >= hopSize {
                hopCount = 0
                processFrame()
            }
        }
    }

    // MARK: - Frame processing

    private func processFrame() {
        for i in 0..<fftSize {
            fftRe[i] = ringBuf[(ringHead + i) % fftSize] * hannWin[i]
            fftIm[i] = 0
        }
        fft(&fftRe, &fftIm)
        for bin in cwBinMin...cwBinMax {
            specPow[bin] = fftRe[bin] * fftRe[bin] + fftIm[bin] * fftIm[bin]
        }

        let cwRange = cwBinMax - cwBinMin + 1
        for i in 0..<cwRange { cwBandBuf[i] = specPow[cwBinMin + i] }
        cwBandBuf[0..<cwRange].sort()
        let noiseIdx = min(max(0, Int(Double(cwRange) * 0.20)), cwRange - 1)
        let noisePow = cwBandBuf[noiseIdx]
        let peakMinPow = noisePow * peakSnrMin
        let maxCwPow = cwBandBuf[cwRange - 1]
        let isNoise = maxCwPow <= 0 || noisePow / maxCwPow > noiseFlatnessThresh

        for ci in 0..<CwDecoder.N_CHAN {
            guard let ch = channels[ci] else { continue }
            if isNoise {
                ch.processQuiet(ci: ci, onCharDecoded: onCharDecoded)
            } else {
                if ch.trackPeak(spec: specPow,
                                cwBinMin: cwBinMin, cwBinMax: cwBinMax,
                                driftRadius: driftRadius) {
                    onChannelFreq?(ci, ch.freqHz)
                }
                let b = ch.freqBin
                let e = sqrt(
                    specPow[max(cwBinMin, b - 2)] +
                    specPow[max(cwBinMin, b - 1)] +
                    specPow[b] +
                    specPow[min(cwBinMax, b + 1)] +
                    specPow[min(cwBinMax, b + 2)]
                )
                ch.process(energy: e, ci: ci, onCharDecoded: onCharDecoded)
            }
            if ch.shouldDeactivate {
                channels[ci] = nil
                onChannelFreq?(ci, 0)
            }
        }

        // Merge channels that converged onto same bin
        for i in 0..<(CwDecoder.N_CHAN - 1) {
            guard let ci = channels[i] else { continue }
            for j in (i + 1)..<CwDecoder.N_CHAN {
                guard let cj = channels[j] else { continue }
                if abs(ci.freqBin - cj.freqBin) <= freqTol {
                    if ci.smoothE >= cj.smoothE {
                        channels[j] = nil; onChannelFreq?(j, 0)
                    } else {
                        channels[i] = nil; onChannelFreq?(i, 0); break
                    }
                }
            }
        }

        // Strong-bin candidates
        var strongBins: Set<Int> = []
        if cwBinMax > cwBinMin + 1 {
            for bin in (cwBinMin + 1)..<cwBinMax {
                if specPow[bin] > specPow[bin - 1] && specPow[bin] > specPow[bin + 1]
                   && specPow[bin] >= peakMinPow {
                    let covered = channels.contains(where: { ch in
                        ch != nil && abs(ch!.freqBin - bin) <= freqTol
                    })
                    if !covered { strongBins.insert(bin) }
                }
            }
        }
        peakCandidates.removeAll(where: { !strongBins.contains($0.bin) })
        for bin in strongBins {
            if let idx = peakCandidates.firstIndex(where: { $0.bin == bin }) {
                peakCandidates[idx].count += 1
            } else {
                peakCandidates.append((bin: bin, count: 1))
            }
        }

        // Slot-0 promotion (~2s cadence)
        promoteCountdown += 1
        if promoteCountdown >= max(10, 2000 / msPerHop) {
            promoteCountdown = 0
            var bestSlot = 0
            var bestE: Float = channels[0]?.smoothE ?? -1
            for i in 1..<CwDecoder.N_CHAN {
                let e = channels[i]?.smoothE ?? -1
                if e > bestE * 1.5 { bestE = e; bestSlot = i }
            }
            if bestSlot != 0 {
                let tmp = channels[0]; channels[0] = channels[bestSlot]; channels[bestSlot] = tmp
                onChannelFreq?(0, channels[0]?.freqHz ?? 0)
                onChannelFreq?(bestSlot, channels[bestSlot]?.freqHz ?? 0)
                onChannelSwap?(0, bestSlot)
            }
        }

        // Promote sustained candidates
        let noiseE = sqrt(noisePow * 3)
        let sortedStrong = strongBins.sorted(by: { specPow[$0] > specPow[$1] })
        for bin in sortedStrong {
            guard let cand = peakCandidates.first(where: { $0.bin == bin }) else { continue }
            if cand.count < candPromote { continue }
            guard let slot = channels.firstIndex(where: { $0 == nil }) else { break }
            let e = sqrt(
                specPow[max(cwBinMin, bin - 2)] +
                specPow[max(cwBinMin, bin - 1)] +
                specPow[bin] +
                specPow[min(cwBinMax, bin + 1)] +
                specPow[min(cwBinMax, bin + 2)]
            )
            let ch = Channel(
                freqBin: bin, seedSigE: e, seedNoiseE: noiseE,
                msPerHop: msPerHop, binHz: binHz,
                ditWinsInit: ditWinsInit, ditWinsMax: ditWinsMax, ditWinsMin: ditWinsMin,
                candPromote: candPromote, deactThresh: deactThresh
            )
            channels[slot] = ch
            onChannelFreq?(slot, ch.freqHz)
            peakCandidates.removeAll(where: { $0.bin == bin })
            ch.process(energy: e, ci: slot, onCharDecoded: onCharDecoded)
        }
    }

    // MARK: - FFT (Cooley-Tukey, in-place, complex)

    private func fft(_ re: inout [Float], _ im: inout [Float]) {
        let n = re.count
        var j = 0
        for i in 1..<n {
            var bit = n >> 1
            while (j & bit) != 0 { j ^= bit; bit >>= 1 }
            j ^= bit
            if i < j {
                re.swapAt(i, j); im.swapAt(i, j)
            }
        }
        var len = 2
        while len <= n {
            let half = len >> 1
            let ang = -Double.pi / Double(half)
            let wRe = Float(cos(ang))
            let wIm = Float(sin(ang))
            var i = 0
            while i < n {
                var cRe: Float = 1
                var cIm: Float = 0
                for k in 0..<half {
                    let uRe = re[i + k]
                    let uIm = im[i + k]
                    let vRe = re[i + k + half] * cRe - im[i + k + half] * cIm
                    let vIm = re[i + k + half] * cIm + im[i + k + half] * cRe
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe; im[i + k + half] = uIm - vIm
                    let nc = cRe * wRe - cIm * wIm
                    cIm = cRe * wIm + cIm * wRe
                    cRe = nc
                }
                i += len
            }
            len <<= 1
        }
    }
}

// MARK: - Channel

private final class Channel {
    let freqBin0: Int
    var freqBin: Int
    var freqHz: Int { Int(Float(freqBin) * binHz) }

    private let binHz: Float
    private let msPerHop: Int
    private let candPromote: Int
    private let deactThreshLocal: Int
    private let ditWinsMaxLocal: Float
    private let ditWinsMinLocal: Float

    private let histSize: Int
    private let suspendThresh: Int
    private let elemFlushThresh: Int
    private var hist: [Float]
    private var histIdx: Int = 0

    private var threshOn: Float = .greatestFiniteMagnitude
    private var threshOff: Float = .greatestFiniteMagnitude
    private var suspKeyOnFrames: Int = 0

    var keyOn: Bool = false
    var keyOnWins: Int = 0
    var keyOffWins: Int = 0
    var charFlushed: Bool = false
    var wordEmitted: Bool = false
    var ditWins: Float
    var elements: String = ""

    var framesNoRealElement: Int = 0
    var hadSignal: Bool = false
    var smoothE: Float = 0

    init(freqBin: Int, seedSigE: Float, seedNoiseE: Float,
         msPerHop: Int, binHz: Float,
         ditWinsInit: Float, ditWinsMax: Float, ditWinsMin: Float,
         candPromote: Int, deactThresh: Int) {
        self.freqBin0 = freqBin
        self.freqBin = freqBin
        self.binHz = binHz
        self.msPerHop = msPerHop
        self.candPromote = candPromote
        self.deactThreshLocal = deactThresh
        self.ditWinsMaxLocal = ditWinsMax
        self.ditWinsMinLocal = ditWinsMin
        self.ditWins = ditWinsInit
        self.histSize = max(30, 1600 / msPerHop)
        self.suspendThresh = max(5, 2000 / msPerHop)
        self.elemFlushThresh = max(3, 500 / msPerHop)
        self.hist = .init(repeating: 0, count: max(30, 1600 / msPerHop))
        if seedSigE > seedNoiseE {
            threshOn = seedNoiseE + (seedSigE - seedNoiseE) * 0.50
            threshOff = seedNoiseE + (seedSigE - seedNoiseE) * 0.22
        }
        let seedN = max(candPromote, 8)
        for _ in 0..<seedN { hist[histIdx % histSize] = seedSigE; histIdx += 1 }
        for _ in 0..<seedN { hist[histIdx % histSize] = seedNoiseE; histIdx += 1 }
    }

    func process(energy: Float, ci: Int, onCharDecoded: ((Character, Int) -> Void)?) {
        smoothE = smoothE * 0.97 + energy * 0.03
        hist[histIdx % histSize] = energy
        histIdx += 1
        let valid = min(histIdx, histSize)
        if histIdx % 5 == 0 && valid >= 5 { updateThresh(valid: valid) }

        if threshOn == .greatestFiniteMagnitude {
            if keyOn { keyOnWins += 1 } else { keyOffWins += 1; framesNoRealElement += 1 }
            return
        }

        if framesNoRealElement == elemFlushThresh && !elements.isEmpty && !charFlushed {
            flushChar(ci: ci, onCharDecoded: onCharDecoded); charFlushed = true
            keyOn = false; keyOnWins = 0
        }

        if framesNoRealElement > suspendThresh {
            if energy > threshOn {
                suspKeyOnFrames += 1
                if suspKeyOnFrames >= candPromote {
                    framesNoRealElement = 0; keyOffWins = 0; keyOn = false; keyOnWins = 0
                    elements = ""; charFlushed = false; wordEmitted = false
                    suspKeyOnFrames = 0
                    // fall through
                } else { return }
            } else {
                suspKeyOnFrames = 0; framesNoRealElement += 1; return
            }
        }

        let isOn = keyOn ? (energy > threshOff) : (energy > threshOn)
        if isOn && !keyOn {
            analyzeGap(wins: keyOffWins, ci: ci, onCharDecoded: onCharDecoded)
            keyOffWins = 0; charFlushed = false; wordEmitted = false
            keyOn = true; hadSignal = true
        } else if !isOn && keyOn {
            analyzeElement(wins: keyOnWins); keyOnWins = 0; keyOn = false
        }
        if keyOn {
            keyOnWins += 1
        } else {
            keyOffWins += 1; framesNoRealElement += 1
            if !charFlushed && Float(keyOffWins) >= ditWins * 3 {
                flushChar(ci: ci, onCharDecoded: onCharDecoded); charFlushed = true
            }
            if !wordEmitted && hadSignal && Float(keyOffWins) >= ditWins * 7 {
                onCharDecoded?(" ", ci); wordEmitted = true
            }
        }
    }

    func processQuiet(ci: Int, onCharDecoded: ((Character, Int) -> Void)?) {
        if keyOn { analyzeElement(wins: keyOnWins); keyOnWins = 0; keyOn = false }
        keyOffWins += 1; framesNoRealElement += 1
        if !charFlushed && Float(keyOffWins) >= ditWins * 3 {
            flushChar(ci: ci, onCharDecoded: onCharDecoded); charFlushed = true
        }
        if !wordEmitted && hadSignal && Float(keyOffWins) >= ditWins * 7 {
            onCharDecoded?(" ", ci); wordEmitted = true
        }
    }

    private func updateThresh(valid: Int) {
        if framesNoRealElement > histSize / 3 { return }
        var tmp = Array(hist.prefix(valid))
        tmp.sort()
        let loIdx = min(max(0, Int(Double(valid) * 0.15)), valid - 1)
        let hiIdx = min(max(0, Int(Double(valid) * 0.85)), valid - 1)
        let lo = tmp[loIdx]
        let hi = tmp[hiIdx]
        if hi > lo * 3 {
            threshOn = lo + (hi - lo) * 0.58
            threshOff = lo + (hi - lo) * 0.26
        } else if hi > lo * 2 {
            threshOn = lo + (hi - lo) * 0.52
            threshOff = lo + (hi - lo) * 0.24
        }
    }

    private func analyzeElement(wins: Int) {
        let effectiveMin = max(ditWinsMinLocal, ditWins * 0.6)
        if Float(wins) < effectiveMin { return }
        framesNoRealElement = 0
        if Float(wins) < ditWins * 1.8 {
            elements.append(".")
            ditWins = min(max(ditWins * 0.95 + Float(wins) * 0.05, ditWinsMinLocal), ditWinsMaxLocal)
        } else {
            elements.append("-")
            ditWins = min(max(ditWins * 0.95 + Float(wins) / 3 * 0.05, ditWinsMinLocal), ditWinsMaxLocal)
        }
    }

    private func analyzeGap(wins: Int, ci: Int, onCharDecoded: ((Character, Int) -> Void)?) {
        if elements.isEmpty || charFlushed { return }
        if Float(wins) >= ditWins * 6 {
            flushChar(ci: ci, onCharDecoded: onCharDecoded); charFlushed = true
            if !wordEmitted { onCharDecoded?(" ", ci); wordEmitted = true }
        } else if Float(wins) >= ditWins * 2.5 {
            flushChar(ci: ci, onCharDecoded: onCharDecoded); charFlushed = true
        }
    }

    private func flushChar(ci: Int, onCharDecoded: ((Character, Int) -> Void)?) {
        if elements.isEmpty { return }
        let c: Character = CwDecoder.morseTable[elements] ?? "?"
        onCharDecoded?(c, ci)
        elements = ""
    }

    func trackPeak(spec: [Float], cwBinMin: Int, cwBinMax: Int, driftRadius: Int) -> Bool {
        var bestBin = freqBin
        var bestPow = spec[freqBin]
        let lo = max(freqBin - driftRadius, cwBinMin)
        let hi = min(freqBin + driftRadius, cwBinMax)
        for b in lo...hi {
            if spec[b] > bestPow { bestPow = spec[b]; bestBin = b }
        }
        if bestBin == freqBin { return false }
        freqBin = bestBin
        return true
    }

    var shouldDeactivate: Bool {
        framesNoRealElement > deactThreshLocal
    }
}
