import Foundation
import AVFoundation

/// Streaming PCM-16 LE mono playback over HTTP. Mirrors Android AudioStreamService.
/// URL: GET http://<host>:<audioPort>/radio/audio?rate=<sampleRate>
///
/// Also owns the shared AVAudioEngine so that CwBleService's sidetone source node
/// can be attached here. One shared engine eliminates all hardware output contention
/// between radio RX audio and sidetone that occurs with two separate AVAudioEngine
/// instances.
@MainActor
final class AudioRxService: NSObject {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var format: AVAudioFormat?
    private var sampleRate: Int = 8000
    private var apiKey: String = ""
    private var url: URL?
    private var streamTask: Task<Void, Never>?
    private(set) var isRunning: Bool = false

    /// Called on the MainActor with each 1024-sample chunk (Int16 LE) as it arrives.
    var onSamples: (([Int16]) -> Void)?

    /// Called on the MainActor when the engine or stream encounters a non-retryable error.
    var onError: ((String) -> Void)?

    func start(host: String, port: Int, sampleRate: Int, apiKey: String) async {
        await stop()
        self.sampleRate = sampleRate
        self.apiKey = apiKey
        guard sampleRate > 0 else { return }
        let urlStr = "http://\(host):\(port)/radio/audio?rate=\(sampleRate)"
        guard let u = URL(string: urlStr) else { return }
        self.url = u
        isRunning = true
        configureEngine()

        let capturedURL = u
        let capturedKey = apiKey
        streamTask = Task.detached(priority: .userInitiated) { [weak self] in
            await self?.runStream(url: capturedURL, apiKey: capturedKey)
        }
    }

    func stop() async {
        isRunning = false
        streamTask?.cancel()
        streamTask = nil
        if let obs = engineConfigObserver {
            NotificationCenter.default.removeObserver(obs)
            engineConfigObserver = nil
        }
        if player.isPlaying { player.stop() }
        if sharedSidetoneNode == nil, engine.isRunning { engine.stop() }
    }

    // MARK: - Shared engine (sidetone integration)

    var isEngineRunning: Bool { engine.isRunning }

    private var sharedSidetoneNode: AVAudioSourceNode?

    /// Attach a sidetone AVAudioSourceNode to this engine's graph.
    /// Called by CwBleService.startSidetone() so both sidetone and radio RX share
    /// one AVAudioEngine, which eliminates hardware output contention on iOS.
    func startSidetoneIn(_ src: AVAudioSourceNode, format: AVAudioFormat) {
        if let old = sharedSidetoneNode { engine.detach(old) }
        sharedSidetoneNode = src

        activateSession()
        ensurePlayerAttached()
        engine.attach(src)
        engine.connect(src, to: engine.mainMixerNode, format: format)

        if !engine.isRunning {
            do { try engine.start() }
            catch { print("[AudioRx] engine.start (sidetone) failed: \(error)") }
        }
        registerConfigObserver()
    }

    func stopSidetoneIn(_ src: AVAudioSourceNode) {
        engine.detach(src)
        if sharedSidetoneNode === src { sharedSidetoneNode = nil }
        if !isRunning, engine.isRunning { engine.stop() }
    }

    // MARK: - Private helpers

    private var playerAttached: Bool = false
    private var engineConfigObserver: NSObjectProtocol?

    private func activateSession() {
        let sess = AVAudioSession.sharedInstance()
        // Don't downgrade .playAndRecord to .playback — CI-V or AudioTxService may own the mic.
        // .playAndRecord already allows speaker output, so sidetone still works in that mode.
        if sess.category != .playAndRecord {
            try? sess.setCategory(.playback, mode: .default, options: [.mixWithOthers])
        }
        try? sess.setActive(true, options: [])
    }

    private func ensurePlayerAttached() {
        guard !playerAttached else { return }
        engine.attach(player)
        playerAttached = true
    }

    private func configureEngine() {
        activateSession()
        ensurePlayerAttached()

        guard let fmt = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                     sampleRate: Double(sampleRate),
                                     channels: 1,
                                     interleaved: false) else { return }
        self.format = fmt
        engine.connect(player, to: engine.mainMixerNode, format: fmt)

        do {
            if !engine.isRunning { try engine.start() }
            // NOTE: player.play() is NOT called here.
            // It is deferred until the first audio buffer is queued, which eliminates
            // the 2-3 second window of silence the old approach had (play() was called
            // immediately, then URLSession's ~32 KB internal buffer filled before
            // delivering the first data chunk).
            print("[AudioRx] engine ready @ \(sampleRate) Hz (player not yet playing)")
        } catch {
            let msg = "Audio engine start failed: \(error.localizedDescription)"
            print("[AudioRx] \(msg)")
            onError?(msg)
        }

        registerConfigObserver()
    }

    private func registerConfigObserver() {
        if let obs = engineConfigObserver {
            NotificationCenter.default.removeObserver(obs)
        }
        engineConfigObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange,
            object: engine,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                try? await Task.sleep(nanoseconds: 150_000_000)
                guard !self.engine.isRunning else { return }
                if self.isRunning { self.configureEngine() }
                else if self.sharedSidetoneNode != nil {
                    self.activateSession()
                    do { try self.engine.start() }
                    catch { print("[AudioRx] engine restart (sidetone) failed: \(error)") }
                }
            }
        }
    }

    private func scheduleSamples(_ data: Data) {
        guard let fmt = format else { return }
        let frameCount = AVAudioFrameCount(data.count / 2)
        guard frameCount > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: frameCount),
              let ch = buffer.floatChannelData else { return }
        buffer.frameLength = frameCount
        data.withUnsafeBytes { raw in
            guard let src = raw.baseAddress?.assumingMemoryBound(to: Int16.self) else { return }
            let dst = ch[0]
            for i in 0..<Int(frameCount) {
                dst[i] = Float(src[i]) / 32768.0
            }
        }
        player.scheduleBuffer(buffer, completionHandler: nil)
    }
}

// MARK: - Async stream loop (runs on background thread via Task.detached)

extension AudioRxService {

    nonisolated private func runStream(url: URL, apiKey: String) async {
        let chunkBytes = 1024 * 2     // 2048 bytes = 1024 samples = 128 ms at 8 kHz
        let prebufTarget = chunkBytes // queue one full chunk before starting player

        for attempt in 0..<10 {
            guard !Task.isCancelled else { return }
            if attempt > 0 { try? await Task.sleep(nanoseconds: 500_000_000) }
            guard !Task.isCancelled else { return }

            do {
                let cfg = URLSessionConfiguration.ephemeral
                cfg.timeoutIntervalForRequest = 0
                cfg.timeoutIntervalForResource = 0
                let sess = URLSession(configuration: cfg)
                defer { sess.invalidateAndCancel() }

                var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData,
                                     timeoutInterval: 0)
                if !apiKey.isEmpty { req.setValue(apiKey, forHTTPHeaderField: "X-API-Key") }

                // URLSession.bytes() delivers data as it arrives from the network,
                // unlike the old data delegate which buffered ~32 KB (~2 s at 8 kHz)
                // before the first delivery.
                let (bytes, resp) = try await sess.bytes(for: req)
                guard !Task.isCancelled else { return }
                if let http = resp as? HTTPURLResponse, http.statusCode >= 400 { continue }

                var pending = Data()
                var playerStarted = false

                for try await byte in bytes {
                    guard !Task.isCancelled else { return }
                    pending.append(byte)

                    guard pending.count >= chunkBytes else { continue }

                    let slice = Data(pending.prefix(chunkBytes))
                    pending.removeFirst(chunkBytes)
                    let shouldStart = !playerStarted && pending.count < prebufTarget
                    playerStarted = true

                    await MainActor.run { [weak self] in
                        guard let self, self.isRunning else { return }
                        self.scheduleSamples(slice)
                        if let cb = self.onSamples {
                            let samples = slice.withUnsafeBytes { raw -> [Int16] in
                                guard let base = raw.baseAddress?
                                    .assumingMemoryBound(to: Int16.self) else { return [] }
                                return Array(UnsafeBufferPointer(start: base,
                                                                  count: slice.count / 2))
                            }
                            cb(samples)
                        }
                        if shouldStart, !self.player.isPlaying {
                            self.player.play()
                            print("[AudioRx] player.play() — first audio queued")
                        }
                    }
                }
                // Stream ended cleanly — retry
            } catch is CancellationError {
                return
            } catch {
                // Network/timeout error — retry after delay (already at top of loop)
            }
        }
        await MainActor.run { [weak self] in
            self?.onError?("Audio stream failed after 10 attempts")
        }
    }
}
