import Foundation

// MARK: - Thread-safe WiFi RX ring buffer (mirrors Android Channel<ShortArray> DROP_OLDEST)

private final class WifiRxChannel: @unchecked Sendable {
    private let lock = NSLock()
    private var queue: [[Int16]] = []
    private let maxSize: Int

    init(maxSize: Int = 400) { self.maxSize = maxSize }

    func send(_ samples: [Int16]) {
        lock.lock()
        if queue.count >= maxSize { queue.removeFirst() }
        queue.append(samples)
        lock.unlock()
    }

    func tryReceive() -> [Int16]? {
        lock.lock(); defer { lock.unlock() }
        return queue.isEmpty ? nil : queue.removeFirst()
    }

    func drain() { lock.lock(); queue.removeAll(); lock.unlock() }

    // Poll every 20ms until data arrives or deadline (mirrors Android withTimeoutOrNull).
    func receive(timeoutMs: Int) async -> [Int16]? {
        let deadline = Date().addingTimeInterval(Double(timeoutMs) / 1000.0)
        while !Task.isCancelled && Date() < deadline {
            if let item = tryReceive() { return item }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        return nil
    }
}

// MARK: - Ft8LocalEngine

/// Local FT8 engine for CI-V WiFi mode (mirrors Android Ft8LocalEngine.kt).
/// Audio RX arrives via CivService.rxAudioCallback (RS-BA1 8 kHz S16LE PCM).
/// Events are emitted via `sseStream` and `spectrumStream` for Ft8View to consume.
final class Ft8LocalEngine: @unchecked Sendable {

    // MARK: Output streams (consumed by Ft8View)

    struct SpectrumFrame {
        let bins: [UInt8]; let newPeriod: Bool; let period: Int; let utcSec: Int
    }
    typealias SseEvent = (type: String, data: [String: Any])

    private(set) var sseStream: AsyncStream<SseEvent>
    private(set) var spectrumStream: AsyncStream<SpectrumFrame>
    private var sseCont: AsyncStream<SseEvent>.Continuation?
    private var specCont: AsyncStream<SpectrumFrame>.Continuation?

    // MARK: State (lock-protected)

    private enum TxState { case off, oneShot, auto, cqAuto }
    private let stateLock = NSLock()
    private var _txState:    TxState = .off
    private var _autoMsg:    String  = ""
    private var _autoMode:   String  = "even"
    private var _txFreqHz:   Float   = 1500
    private var _myCall:     String  = ""
    private var _myGrid:     String  = ""
    private var _qsoDxCall:  String  = ""
    private var _qsoWaitFor: String  = ""
    private var _periodNo:   Int     = 0

    private func withState<T>(_ block: () -> T) -> T {
        stateLock.lock(); defer { stateLock.unlock() }; return block()
    }
    private var txState:   TxState { withState { _txState } }
    private var autoMsg:   String  { withState { _autoMsg } }
    private var autoMode:  String  { withState { _autoMode } }
    private var txFreqHz:  Float   { withState { _txFreqHz } }
    private var myCall:    String  { withState { _myCall } }
    private var qsoDxCall: String  { withState { _qsoDxCall } }

    // MARK: Config

    let SAMPLE_RATE    = 8000
    let SPECTRUM_CHUNK = 1280                                           // ~160 ms at 8 kHz
    var isFt4: Bool    = false
    // Snapshot used within each rxLoop iteration — set at loop top to keep the
    // whole iteration (record/TX/decode) consistent even if isFt4 is toggled mid-period.
    private var _isFt4: Bool   = false
    private var PERIOD_S:       Double { _isFt4 ? 7.5 : 15.0 }
    private var PERIOD_SAMPLES: Int    { Int(PERIOD_S * Double(SAMPLE_RATE)) } // 60 000 (FT4) / 120 000 (FT8)

    var ntpOffsetMs: Int64 = 0
    var rxGain: Float = 1.0
    var txGain: Float = 0.43   // adjustable from Ft8View; default matches original TX_GAIN

    // MARK: Private

    private weak var civ: CivService?
    private let wifiRx = WifiRxChannel(maxSize: 400)
    private var engineTask: Task<Void, Never>?
    private var isRunning  = false
    private var isTxing    = false

    // MARK: Init

    init(civ: CivService) {
        self.civ = civ

        var sc: AsyncStream<SseEvent>.Continuation?
        sseStream = AsyncStream<SseEvent>(bufferingPolicy: .bufferingNewest(200)) { sc = $0 }
        sseCont = sc

        var spec: AsyncStream<SpectrumFrame>.Continuation?
        spectrumStream = AsyncStream<SpectrumFrame>(bufferingPolicy: .bufferingNewest(50)) { spec = $0 }
        specCont = spec
    }

    // MARK: Public API

    func start(myCallSign: String, myGrid: String) {
        guard !isRunning else { return }
        stateLock.lock(); _myCall = myCallSign; _myGrid = myGrid; stateLock.unlock()
        isRunning = true
        civ?.rxAudioCallback = { [weak self] data in self?.onWifiRxAudio(data) }
        NSLog("[Ft8Engine] started myCall=\(myCallSign) sampleRate=\(SAMPLE_RATE)")
        engineTask = Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else { return }
            while self.isRunning && !Task.isCancelled {
                do    { try await self.rxLoop() }
                catch is CancellationError { break }
                catch {
                    NSLog("[Ft8Engine] rxLoop error: \(error)")
                    if self.isRunning { try? await Task.sleep(nanoseconds: 1_000_000_000) }
                }
            }
        }
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false
        civ?.rxAudioCallback = nil
        wifiRx.drain()
        engineTask?.cancel()
        sseCont?.finish()
        specCont?.finish()
        stateLock.lock(); _txState = .off; stateLock.unlock()
        NSLog("[Ft8Engine] stopped")
    }

    func sendOneShotTx(msg: String, audioFreqHz: Int, mode: String) {
        stateLock.lock()
        _autoMsg = msg; _txFreqHz = Float(audioFreqHz); _autoMode = mode; _txState = .oneShot
        stateLock.unlock()
    }

    func setAutoTx(active: Bool, mode: String, msg: String, audioFreqHz: Int) {
        stateLock.lock()
        _autoMode = mode; _txFreqHz = Float(audioFreqHz)
        if active { _autoMsg = msg; _txState = .auto } else { _txState = .off }
        stateLock.unlock()
    }

    func startCqAuto(active: Bool, msg: String, mode: String,
                     audioFreqHz: Int, call: String, grid: String) {
        stateLock.lock()
        if !call.isEmpty { _myCall = call }
        if !grid.isEmpty { _myGrid = grid }
        _txFreqHz = Float(audioFreqHz); _autoMode = mode
        if active { _autoMsg = msg; _txState = .cqAuto } else { _txState = .off }
        stateLock.unlock()
    }

    func startQso(dxCall: String, firstMsg: String, txMode: String,
                  audioFreqHz: Int, initialState: Int) {
        stateLock.lock()
        _qsoDxCall  = dxCall
        _qsoWaitFor = initialState == 0 ? "snr" : (initialState == 1 ? "r_snr" : "rr73")
        _autoMode   = txMode; _txFreqHz = Float(audioFreqHz)
        _autoMsg    = firstMsg; _txState = .auto
        stateLock.unlock()
    }

    func cancelQso() {
        stateLock.lock()
        _qsoDxCall = ""; _qsoWaitFor = ""
        if _txState == .auto { _txState = .off }
        stateLock.unlock()
    }

    // MARK: - RX loop

    private var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) + ntpOffsetMs }

    private var _specFrameCount = 0

    private func rxLoop() async throws {
        while isRunning && !Task.isCancelled {
            _isFt4 = isFt4  // snapshot once per iteration for consistency
            let now       = nowMs
            let periodMs  = Int64(PERIOD_S * 1000)
            let utcMs     = now % periodMs
            // If rawWait >= 90% of period we're right at a boundary — act immediately.
            let rawWait   = periodMs - utcMs
            let waitMs    = rawWait > periodMs * 9 / 10 ? 0 : rawWait
            let periodNoInMinute = Int(Double(now % 60000) / (PERIOD_S * 1000))
            let alignedSec = Int(Double(periodNoInMinute) * PERIOD_S) % 60

            stateLock.lock(); _periodNo += 1; let pNo = _periodNo; stateLock.unlock()
            NSLog("[Ft8Engine] period start pNo=\(pNo) alignedSec=\(alignedSec) waitMs=\(waitMs)")

            specCont?.yield(SpectrumFrame(bins: [], newPeriod: true, period: pNo, utcSec: alignedSec))

            // TX/RX decisions must use the period we'll ACTUALLY be in after the wait.
            // When waitMs > 0 we act at the NEXT period boundary, so alignedSec (current
            // period) would produce an off-by-one even/odd error.
            let actingAlignedSec: Int
            if waitMs == 0 {
                actingAlignedSec = alignedSec
            } else {
                let afterNoInMinute = Int(Double((now + waitMs) % 60000) / (PERIOD_S * 1000))
                actingAlignedSec = Int(Double(afterNoInMinute) * PERIOD_S) % 60
            }

            let shouldTx = shouldTransmitThisPeriod(actingAlignedSec)
            let txMsg    = shouldTx ? buildNextTxMsg() : nil

            if let msg = txMsg {
                sseCont?.yield(("tx_pending", ["msg": msg, "utc_at_tx": actingAlignedSec]))
                if waitMs > 50 {
                    await dispatchSpectrumDuringWait(waitMs: waitMs, pNo: pNo, utcSec: actingAlignedSec)
                } else if waitMs > 0 {
                    try? await Task.sleep(nanoseconds: UInt64(waitMs) * 1_000_000)
                }
                wifiRx.drain()
                do    { try await doTx(msg) }
                catch is CancellationError { throw CancellationError() }
                catch { NSLog("[Ft8Engine] doTx error: \(error)") }
            } else {
                if waitMs > 50 {
                    await dispatchSpectrumDuringWait(waitMs: waitMs, pNo: pNo, utcSec: actingAlignedSec)
                } else if waitMs > 0 {
                    try? await Task.sleep(nanoseconds: UInt64(waitMs) * 1_000_000)
                }
                wifiRx.drain()

                var buf = [Int16](repeating: 0, count: PERIOD_SAMPLES)
                let recorded = await recordPeriodWifiCiv(into: &buf, pNo: pNo, utcSec: actingAlignedSec)

                NSLog("[Ft8Engine] recorded pNo=\(pNo) samples=\(recorded)/\(PERIOD_SAMPLES) isFt4=\(_isFt4) need=\(PERIOD_SAMPLES/2)")
                if recorded >= PERIOD_SAMPLES / 2 {
                    let g = rxGain
                    let snapshot: [Int16] = g == 1.0 ? Array(buf.prefix(PERIOD_SAMPLES)) :
                        buf.prefix(PERIOD_SAMPLES).map { s in
                            Int16(clamping: Int(Float(s) * g))
                        }
                    let pDecoded = pNo
                    let isFt4Snap = _isFt4  // capture snapshot for detached task
                    Task.detached { [weak self] in
                        self?.decodeAndDispatch(snapshot, pNo: pDecoded, utcSec: actingAlignedSec,
                                                isFt4: isFt4Snap)
                    }
                }
            }
        }
    }

    private func shouldTransmitThisPeriod(_ alignedSec: Int) -> Bool {
        if txState == .off { return false }
        // FT8: even at 0,30s; odd at 15,45s → check % 30
        // FT4: even at 0,15,30,45s; odd at 7,22,37,52s → check % 15
        let isEven = _isFt4 ? (alignedSec % 15 == 0) : (alignedSec % 30 == 0)
        return autoMode == "odd" ? !isEven : isEven
    }

    private func buildNextTxMsg() -> String? {
        stateLock.lock(); defer { stateLock.unlock() }
        switch _txState {
        case .off: return nil
        case .oneShot:
            let m = _autoMsg; _txState = .off; return m.isEmpty ? nil : m
        case .auto, .cqAuto:
            return _autoMsg.isEmpty ? nil : _autoMsg
        }
    }

    // MARK: - TX

    private func doTx(_ msg: String) async throws {
        guard isRunning else { return }
        isTxing = true
        defer { isTxing = false }
        NSLog("[Ft8Engine] TX start: msg=\(msg) freqHz=\(txFreqHz) isFt4=\(_isFt4)")

        let hz = txFreqHz
        var encoded = ft8lib_encode(msg, hz, Int32(SAMPLE_RATE), _isFt4 ? 1 : 0)
        defer { ft8lib_free_encode_result(&encoded) }
        guard encoded.count > 0, let rawPtr = encoded.samples else {
            NSLog("[Ft8Engine] encode failed for: \(msg)"); return
        }

        let total = Int(encoded.count)
        let gain = txGain
        let pcm: [Int16] = (0..<total).map { i in
            Int16(clamping: Int(Float(rawPtr[i]) * gain))
        }
        NSLog("[Ft8Engine] TX encode OK: samples=\(total)")

        pttOn()
        try? await Task.sleep(nanoseconds: 50_000_000)
        await streamViaCiv(pcm)
        pttOff()

        sseCont?.yield(("auto_tx_sent", ["msg": msg]))

        let upper = msg.uppercased().trimmingCharacters(in: .whitespaces)
        if upper.hasSuffix("73") || upper.hasSuffix("RR73") {
            handleSentFinalMsg()
        }
    }

    private func pttOn()  { civ?.civPttDown() }
    private func pttOff() { civ?.civPttUp() }

    // Stream FT8 PCM to IC-705 via RS-BA1 in 160-sample (20ms) chunks.
    private func streamViaCiv(_ pcm: [Int16]) async {
        let chunkSamples = 160          // 20 ms at 8 kHz
        let intervalMs   = 20
        var offset       = 0
        var pktCount     = 0
        var nextSendMs   = Int64(Date().timeIntervalSince1970 * 1000)
        NSLog("[Ft8Engine] streamViaCiv start totalSamples=\(pcm.count)")
        while offset < pcm.count && isRunning {
            guard civ?.isConnected == true else {
                NSLog("[Ft8Engine] streamViaCiv: connection lost at pkt=\(pktCount)"); break
            }
            let len = min(chunkSamples, pcm.count - offset)
            var data = Data(count: len * 2)
            data.withUnsafeMutableBytes { (ptr: UnsafeMutableRawBufferPointer) in
                for i in 0..<len {
                    let s = pcm[offset + i]
                    ptr[i * 2]     = UInt8(bitPattern: Int8(truncatingIfNeeded: s))
                    ptr[i * 2 + 1] = UInt8(bitPattern: Int8(truncatingIfNeeded: s >> 8))
                }
            }
            civ?.sendTxAudioDirect(data)
            offset += len; pktCount += 1

            nextSendMs += Int64(intervalMs)
            let waitMs = nextSendMs - Int64(Date().timeIntervalSince1970 * 1000)
            if waitMs > 1 {
                try? await Task.sleep(nanoseconds: UInt64(waitMs) * 1_000_000)
            }
        }
        NSLog("[Ft8Engine] streamViaCiv done pktSent=\(pktCount)")
    }

    private func handleSentFinalMsg() {
        stateLock.lock()
        let st = _txState; let origMsg = _autoMsg
        stateLock.unlock()
        switch st {
        case .cqAuto:
            stateLock.lock(); _qsoDxCall = ""; _qsoWaitFor = ""; stateLock.unlock()
            sseCont?.yield(("cq_auto_restart", ["msg": origMsg]))
        case .auto:
            let mc = myCall
            stateLock.lock(); _txState = .off; _qsoDxCall = ""; _qsoWaitFor = ""; stateLock.unlock()
            sseCont?.yield(("qso_done", ["dx_call": "", "my_call": mc, "snr_rcvd": "", "snr_sent": ""]))
        default: break
        }
    }

    // MARK: - Audio RX

    private var _audioPacketCount = 0

    private func onWifiRxAudio(_ data: Data) {
        guard isRunning, !isTxing, data.count >= 2 else { return }
        _audioPacketCount += 1
        if _audioPacketCount <= 3 || _audioPacketCount % 500 == 0 {
            NSLog("[Ft8Engine] rxAudio pkt=\(_audioPacketCount) bytes=\(data.count)")
        }
        let count   = data.count / 2
        let samples: [Int16] = data.withUnsafeBytes { (ptr: UnsafeRawBufferPointer) in
            (0..<count).map { i -> Int16 in
                let lo = UInt16(ptr.load(fromByteOffset: i * 2,     as: UInt8.self))
                let hi = UInt16(ptr.load(fromByteOffset: i * 2 + 1, as: UInt8.self))
                return Int16(bitPattern: lo | (hi << 8))
            }
        }
        wifiRx.send(samples)
    }

    // MARK: - Spectrum dispatch during wait period

    private func dispatchSpectrumDuringWait(waitMs: Int64, pNo: Int, utcSec: Int) async {
        let deadline = Date().addingTimeInterval(Double(waitMs) / 1000.0)
        // Use the same SPECTRUM_CHUNK accumulator as recordPeriodWifiCiv so the waterfall
        // scrolls at the same rate whether we're in the wait phase or the record phase.
        var accum = [Int16](repeating: 0, count: SPECTRUM_CHUNK)
        var accumPos = 0
        var frameCount = 0
        while Date() < deadline && isRunning && !Task.isCancelled {
            let remaining = Int(deadline.timeIntervalSinceNow * 1000.0)
            guard remaining > 0 else { break }
            if let samples = await wifiRx.receive(timeoutMs: min(300, remaining)) {
                var si = 0
                while si < samples.count {
                    let toCopy = min(samples.count - si, SPECTRUM_CHUNK - accumPos)
                    accum.replaceSubrange(accumPos..<(accumPos + toCopy),
                                         with: samples[si..<(si + toCopy)])
                    si       += toCopy
                    accumPos += toCopy
                    if accumPos >= SPECTRUM_CHUNK {
                        let bins = computeSpectrum(Array(accum.prefix(SPECTRUM_CHUNK)))
                        _specFrameCount += 1; frameCount += 1
                        specCont?.yield(SpectrumFrame(bins: bins, newPeriod: false, period: pNo, utcSec: utcSec))
                        accumPos = 0
                    }
                }
            } else {
                _specFrameCount += 1; frameCount += 1
                specCont?.yield(SpectrumFrame(bins: [UInt8](repeating: 0, count: 256),
                                              newPeriod: false, period: pNo, utcSec: utcSec))
            }
        }
        NSLog("[Ft8Engine] dispatchSpectrumDuringWait done pNo=\(pNo) frameCount=\(frameCount)")
    }

    // MARK: - Record 15-second period

    private func recordPeriodWifiCiv(into buf: inout [Int16], pNo: Int, utcSec: Int) async -> Int {
        var pos      = 0
        var accum    = [Int16](repeating: 0, count: SPECTRUM_CHUNK)
        var accumPos = 0

        while pos < buf.count && isRunning && !isTxing {
            guard let samples = await wifiRx.receive(timeoutMs: 300) else {
                specCont?.yield(SpectrumFrame(bins: [UInt8](repeating: 0, count: 256),
                                              newPeriod: false, period: pNo, utcSec: utcSec))
                continue
            }

            var si = 0
            while si < samples.count {
                let toCopy = min(samples.count - si, SPECTRUM_CHUNK - accumPos)
                accum.replaceSubrange(accumPos..<(accumPos + toCopy),
                                      with: samples[si..<(si + toCopy)])
                si       += toCopy
                accumPos += toCopy

                if accumPos >= SPECTRUM_CHUNK {
                    let copyToBuf = min(SPECTRUM_CHUNK, buf.count - pos)
                    if copyToBuf > 0 {
                        buf.replaceSubrange(pos..<(pos + copyToBuf),
                                             with: accum[0..<copyToBuf])
                        pos += copyToBuf
                    }
                    specCont?.yield(SpectrumFrame(bins: computeSpectrum(Array(accum.prefix(SPECTRUM_CHUNK))),
                                                  newPeriod: false, period: pNo, utcSec: utcSec))
                    accumPos = 0
                    if pos >= buf.count { break }
                }
            }
            if pos >= buf.count { break }
        }
        return pos
    }

    // MARK: - Decode

    private func decodeAndDispatch(_ samples: [Int16], pNo: Int, utcSec: Int, isFt4: Bool) {
        let myC: String; let dxC: String
        stateLock.lock(); myC = _myCall; dxC = _qsoDxCall; stateLock.unlock()

        NSLog("[Ft8Engine] decode pNo=\(pNo) utcSec=\(utcSec) samples=\(samples.count) isFt4=\(isFt4)")
        var decoded = ft8lib_decode(samples, Int32(samples.count), Int32(SAMPLE_RATE),
                                    100.0, 3000.0, myC, dxC, 50, isFt4 ? 1 : 0)
        NSLog("[Ft8Engine] decode done pNo=\(pNo) count=\(decoded.count) isFt4=\(isFt4)")
        defer { ft8lib_free_decode_result(&decoded) }
        guard decoded.count > 0 else { return }

        for i in 0..<Int(decoded.count) {
            guard let cPtr = decoded.messages?[i] else { continue }
            let jsonStr = String(cString: cPtr)
            guard let data = jsonStr.data(using: String.Encoding.utf8),
                  let obj  = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else { continue }

            let msg  = obj["msg"]  as? String ?? ""
            let freq = (obj["freq"] as? Double ?? 0.0)
            let snr  = (obj["snr"]  as? Double ?? 0.0)
            let dt   = obj["dt"]   as? Double ?? 0.0

            sseCont?.yield(("decode_msg",
                            ["freq": Int(freq), "snr": Int(snr), "dt": dt,
                             "msg": msg, "period": pNo, "utc_sec": utcSec]))
            handleIncomingMsg(msg: msg, freq: Int(freq), snr: Int(snr), pNo: pNo)
        }
    }

    // MARK: - QSO state machine

    private func handleIncomingMsg(msg: String, freq: Int, snr: Int, pNo: Int) {
        let parts = msg.uppercased().trimmingCharacters(in: .whitespaces)
            .components(separatedBy: .whitespaces).filter { !$0.isEmpty }
        guard parts.count >= 2 else { return }

        stateLock.lock()
        let st  = _txState
        let myC = _myCall.uppercased()
        let dxC = _qsoDxCall.uppercased()
        stateLock.unlock()

        switch st {
        case .cqAuto:
            guard parts.count >= 3 else { return }
            let dx: String
            if parts[1] == myC {                                            // standard: DX MYCALL REPORT
                dx = parts[0]
            } else if parts[0] == myC && parts[1].hasPrefix("<") {          // compound: MYCALL <DX> REPORT
                dx = stripBrackets(parts[1])
            } else { return }
            let report = parts[2]
            let rsnr   = report.hasPrefix("R") ? report : buildSnrReport(snr, rPrefix: true)
            let reply  = "\(myC) \(dx.uppercased()) \(rsnr)"
            stateLock.lock()
            _qsoDxCall = dx; _qsoWaitFor = "r_snr"; _autoMsg = reply; _txState = .auto
            stateLock.unlock()
            sseCont?.yield(("qso_state", ["state": "calling_back", "msg": reply,
                                          "dx_call": dx, "my_call": myC,
                                          "snr_rcvd": report, "snr_sent": ""]))

        case .auto:
            guard !dxC.isEmpty, parts.count >= 3 else { return }
            let word: String
            if parts[0] == dxC && parts[1] == myC {                         // standard: DX MYCALL word
                word = parts[2]
            } else if parts[0] == myC && stripBrackets(parts[1]) == dxC {   // compound: MYCALL <DX> word
                word = parts[2]
            } else { return }
            if word == "RR73" || word == "73" {
                stateLock.lock(); _txState = .off; _qsoDxCall = ""; _qsoWaitFor = ""; stateLock.unlock()
                sseCont?.yield(("qso_done", ["dx_call": dxC, "my_call": myC,
                                             "snr_rcvd": word, "snr_sent": ""]))
            } else if word.hasPrefix("R"), word.dropFirst().first?.isNumber == true {
                let rr73 = "\(myC) \(dxC) RR73"
                stateLock.lock(); _qsoWaitFor = "rr73"; _autoMsg = rr73; stateLock.unlock()
                sseCont?.yield(("qso_state", ["state": "sending_rr73", "msg": rr73,
                                              "dx_call": dxC, "my_call": myC,
                                              "snr_rcvd": word, "snr_sent": ""]))
            } else if word.first?.isNumber == true || word.hasPrefix("-") || word.hasPrefix("+") {
                let rsnr  = buildSnrReport(snr, rPrefix: true)
                let reply = "\(myC) \(dxC) \(rsnr)"
                stateLock.lock(); _qsoWaitFor = "r_snr"; _autoMsg = reply; stateLock.unlock()
                sseCont?.yield(("qso_state", ["state": "sending_r_snr", "msg": reply,
                                              "dx_call": dxC, "my_call": myC,
                                              "snr_rcvd": word, "snr_sent": ""]))
            }
        default: break
        }
    }

    private func stripBrackets(_ s: String) -> String {
        var r = s
        if r.hasPrefix("<") { r = String(r.dropFirst()) }
        if r.hasSuffix(">") { r = String(r.dropLast()) }
        return r
    }

    private func buildSnrReport(_ snr: Int, rPrefix: Bool) -> String {
        let c = max(-30, min(30, snr))
        let s = c >= 0 ? String(format: "+%02d", c) : String(format: "%+03d", c)
        return rPrefix ? "R\(s)" : s
    }

    // MARK: - Spectrum (simple DFT, mirrors Android computeSpectrum)

    private func computeSpectrum(_ samples: [Int16]) -> [UInt8] {
        let fftSize = min(SPECTRUM_CHUNK, samples.count)
        var out     = [UInt8](repeating: 0, count: 256)
        guard fftSize >= 64 else { return out }

        let maxBin = min(
            Int(Double(3000) / Double(SAMPLE_RATE / 2) * Double(fftSize / 2)),
            fftSize / 2 - 1)
        let binsPerOut = Double(maxBin) / Double(out.count)
        let floats     = (0..<fftSize).map { Double(samples[$0]) / 32768.0 }
        let twoPi      = 2.0 * Double.pi

        for k in 0..<out.count {
            let binIdx = min(maxBin, Int(Double(k) * binsPerOut))
            let freq   = Double(binIdx) / Double(fftSize)
            var re = 0.0, im = 0.0
            for n in 0..<fftSize {
                let angle = twoPi * freq * Double(n)
                re += floats[n] * cos(angle)
                im += floats[n] * sin(angle)
            }
            let mag = (re * re + im * im).squareRoot()
            let db  = 20.0 * log10(mag + 1e-10) + 80.0
            out[k]  = UInt8(max(0, min(120, Int(db))))
        }
        return out
    }
}
