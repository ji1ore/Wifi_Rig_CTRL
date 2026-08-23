import Foundation
@preconcurrency import AVFoundation

/// Microphone capture → 8 kHz PCM-16 LE mono → HTTP POST chunked upload.
/// URL: POST http://<host>:<apiPort>/radio/audio_tx?rate=8000
@MainActor
final class AudioTxService: NSObject {
    private let engine = AVAudioEngine()
    private var converter: AVAudioConverter?
    private var session: URLSession?
    private var uploadTask: URLSessionUploadTask?
    private var outputStream: OutputStream?
    private var inputStream: InputStream?
    private var apiKey: String = ""
    private(set) var isRunning = false

    /// Linear gain applied before encoding (1.0 = unity).
    var micGain: Float = 1.0 {
        didSet { gainBox?.value = micGain }
    }
    private var gainBox: MicGainBox?

    private let targetSampleRate: Double = 8000

    func start(host: String, port: Int, apiKey: String) async throws {
        await stop()
        self.apiKey = apiKey

        // Pair of bound streams: input -> URLSession, output -> we write encoded PCM to it.
        var inS: InputStream?
        var outS: OutputStream?
        Stream.getBoundStreams(withBufferSize: 32 * 1024, inputStream: &inS, outputStream: &outS)
        guard let inS, let outS else { throw RigApiError.transport(NSError(domain: "AudioTx", code: -1)) }
        self.inputStream = inS
        self.outputStream = outS
        outS.open()

        try configureAudioSession()
        try startCaptureTap()

        let urlStr = "http://\(host):\(port)/radio/audio_tx?rate=\(Int(targetSampleRate))"
        guard let url = URL(string: urlStr) else { throw RigApiError.invalidURL }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        req.setValue("chunked", forHTTPHeaderField: "Transfer-Encoding")
        if !apiKey.isEmpty { req.setValue(apiKey, forHTTPHeaderField: "X-API-Key") }
        req.httpBodyStream = inS

        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 0
        config.timeoutIntervalForResource = 0
        let s = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        self.session = s
        let task = s.uploadTask(withStreamedRequest: req)
        self.uploadTask = task
        task.resume()
        isRunning = true
    }

    func stop() async {
        isRunning = false
        if engine.isRunning {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        outputStream?.close()
        outputStream = nil
        inputStream = nil
        uploadTask?.cancel()
        uploadTask = nil
        session?.invalidateAndCancel()
        session = nil
        converter = nil
    }

    private func configureAudioSession() throws {
        let s = AVAudioSession.sharedInstance()
        // .mixWithOthers prevents iOS from interrupting the existing SPK audio session
        // (AudioRxService, .playback) when PTT is pressed, eliminating ~1-2 s hardware
        // reconfiguration delay, especially on Bluetooth devices.
        try s.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .mixWithOthers])
        try s.setActive(true, options: [])
    }

    private func startCaptureTap() throws {
        let input = engine.inputNode
        let inFormat = input.outputFormat(forBus: 0)
        guard let target = AVAudioFormat(commonFormat: .pcmFormatInt16,
                                         sampleRate: targetSampleRate,
                                         channels: 1,
                                         interleaved: true) else {
            throw RigApiError.transport(NSError(domain: "AudioTx", code: -2))
        }
        guard let conv = AVAudioConverter(from: inFormat, to: target) else {
            throw RigApiError.transport(NSError(domain: "AudioTx", code: -3))
        }
        self.converter = conv

        let gainBox = MicGainBox()
        gainBox.value = micGain
        self.gainBox = gainBox

        input.installTap(onBus: 0, bufferSize: 1024, format: inFormat) { [weak self] buffer, _ in
            // Convert + apply gain synchronously inside the audio thread, then ship a
            // Sendable Data payload to the main actor for stream writing.
            guard let data = AudioTxService.encode(buffer: buffer, converter: conv, target: target, gain: gainBox.value) else { return }
            Task { @MainActor [weak self] in self?.writeToStream(data) }
        }
        try engine.start()
    }

    nonisolated static func encode(buffer: AVAudioPCMBuffer, converter: AVAudioConverter, target: AVAudioFormat, gain: Float) -> Data? {
        let ratio = target.sampleRate / buffer.format.sampleRate
        let outFrameCapacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio + 16)
        guard let outBuffer = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: outFrameCapacity) else { return nil }
        var error: NSError?
        var supplied = false
        let status = converter.convert(to: outBuffer, error: &error) { _, status in
            if supplied { status.pointee = .noDataNow; return nil }
            supplied = true
            status.pointee = .haveData
            return buffer
        }
        guard status != .error, let ch = outBuffer.int16ChannelData else { return nil }
        let frames = Int(outBuffer.frameLength)
        guard frames > 0 else { return nil }

        if gain != 1.0 {
            for i in 0..<frames {
                let scaled = Float(ch[0][i]) * gain
                let clamped = max(Float(Int16.min), min(Float(Int16.max), scaled))
                ch[0][i] = Int16(clamped)
            }
        }

        var data = Data(count: frames * 2)
        data.withUnsafeMutableBytes { raw in
            if let dst = raw.baseAddress {
                memcpy(dst, ch[0], frames * 2)
            }
        }
        return data
    }

    private func writeToStream(_ data: Data) {
        guard let out = outputStream, out.hasSpaceAvailable else { return }
        data.withUnsafeBytes { raw in
            if let ptr = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) {
                _ = out.write(ptr, maxLength: data.count)
            }
        }
    }
}

extension AudioTxService: URLSessionTaskDelegate {
    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, needNewBodyStream completionHandler: @escaping (InputStream?) -> Void) {
        Task { @MainActor in completionHandler(self.inputStream) }
    }
}
/// Sendable mutable box for mic-gain read from the audio render thread.
final class MicGainBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _value: Float = 1.0
    var value: Float {
        get { lock.lock(); defer { lock.unlock() }; return _value }
        set { lock.lock(); _value = newValue; lock.unlock() }
    }
}

