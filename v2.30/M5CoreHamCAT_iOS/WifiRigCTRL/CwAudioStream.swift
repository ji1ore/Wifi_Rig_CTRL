import Foundation
import Darwin

/// Streams 16-bit PCM mono audio to `POST /radio/audio_tx?rate=N` so the Pi can
/// modulate phone-mode (FM/SSB) transmissions while a BLE/USB CW key is held.
///
/// Implementation notes:
/// - `URLSession.uploadTask(withStreamedRequest:)` is the only API path that
///   actually honors `httpBodyStream` for a long-lived chunked upload.
/// - Writing to `OutputStream` can block when the upstream buffer is full;
///   we poll `hasSpaceAvailable` with a small sleep instead of blocking forever.
/// - The producer runs on a dedicated `DispatchQueue`, NOT a Swift `Task`. Tasks
///   share a small pool of cooperative threads, so a blocked write would freeze
///   unrelated app work (the exact symptom we hit).
final class CwAudioStream: NSObject, @unchecked Sendable {

    private let toneHz: Double
    private let sampleRate: Int

    private var task: URLSessionUploadTask?
    private var session: URLSession?
    private var outputStream: OutputStream?

    private let producerQueue = DispatchQueue(label: "cw.audio.stream.producer",
                                              qos: .userInitiated)
    private let stateLock = NSLock()
    private var _running = false
    private var _keyIsOn = false
    private var _host: String = ""
    private var _apiPort: Int = 8000
    private var _apiKey: String = ""

    private var running: Bool {
        get { stateLock.lock(); defer { stateLock.unlock() }; return _running }
        set { stateLock.lock(); _running = newValue; stateLock.unlock() }
    }
    private var keyIsOn: Bool {
        get { stateLock.lock(); defer { stateLock.unlock() }; return _keyIsOn }
        set { stateLock.lock(); _keyIsOn = newValue; stateLock.unlock() }
    }

    init(toneHz: Double = 700, sampleRate: Int = 8000) {
        self.toneHz = toneHz
        self.sampleRate = sampleRate
        super.init()
    }

    func keyOn()  { keyIsOn = true }
    func keyOff() { keyIsOn = false }
    var isRunning: Bool { running }

    func start(host: String, apiPort: Int, apiKey: String) {
        stop()
        guard !host.isEmpty else { return }
        running = true
        stateLock.lock()
        _host = host
        _apiPort = apiPort
        _apiKey = apiKey
        stateLock.unlock()

        producerQueue.async { [weak self] in
            self?.connectAndStreamForever()
        }
    }

    func stop() {
        running = false
        // Tear down on the producer queue so we never race with an in-flight write.
        let task = self.task
        let session = self.session
        let outputStream = self.outputStream
        self.task = nil
        self.session = nil
        self.outputStream = nil
        producerQueue.async {
            outputStream?.close()
            task?.cancel()
            session?.invalidateAndCancel()
        }
    }

    // MARK: - Producer (runs on `producerQueue`)

    private func connectAndStreamForever() {
        while running {
            do {
                try openStream()
                try streamLoop()
            } catch {
                if !running { break }
                print("[CwAudioStream] reconnecting after error: \(error)")
            }
            // brief reconnect delay
            for _ in 0..<5 {
                if !running { return }
                Thread.sleep(forTimeInterval: 0.1)
            }
        }
    }

    private func openStream() throws {
        stateLock.lock()
        let host = _host
        let port = _apiPort
        let apiKey = _apiKey
        stateLock.unlock()

        let resolved = Self.resolveIPv4(host) ?? host
        guard let url = URL(string: "http://\(resolved):\(port)/radio/audio_tx?rate=\(sampleRate)") else {
            throw URLError(.badURL)
        }

        var inS: InputStream?
        var outS: OutputStream?
        Stream.getBoundStreams(withBufferSize: 16384, inputStream: &inS, outputStream: &outS)
        guard let inputStream = inS, let outputStream = outS else {
            throw URLError(.cannotCreateFile)
        }
        // Schedule streams on this thread's run loop so they can drain/refill.
        outputStream.open()
        self.outputStream = outputStream

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBodyStream = inputStream
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        // Omit Content-Length so URLSession uses chunked transfer encoding.
        if !apiKey.isEmpty { req.setValue(apiKey, forHTTPHeaderField: "X-API-Key") }

        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = TimeInterval.infinity
        config.timeoutIntervalForResource = TimeInterval.infinity
        let session = URLSession(configuration: config)
        self.session = session

        // CRITICAL: `withStreamedRequest:` honors the request's httpBodyStream;
        // `uploadTask(with:from:)` would override it with `from:` data and the
        // OutputStream writes would block forever (the original freeze symptom).
        let task = session.uploadTask(withStreamedRequest: req)
        self.task = task
        task.resume()
    }

    private func streamLoop() throws {
        let chunkSamples = sampleRate / 100      // 10 ms per chunk
        let byteCount = chunkSamples * MemoryLayout<Int16>.size
        var pcm = [Int16](repeating: 0, count: chunkSamples)
        var phase = 0.0
        let phaseInc = 2.0 * Double.pi * toneHz / Double(sampleRate)
        var nextWrite = Date().timeIntervalSince1970 * 1000

        while running {
            nextWrite += 10
            if keyIsOn {
                for i in 0..<chunkSamples {
                    pcm[i] = Int16(Double(Int16.max) * 0.8 * sin(phase))
                    phase += phaseInc
                    if phase > 2.0 * Double.pi { phase -= 2.0 * Double.pi }
                }
            } else {
                for i in 0..<chunkSamples { pcm[i] = 0 }
                phase = 0
            }

            // Wait (with bounded backoff) until the stream actually has space.
            // This avoids the indefinite block we hit before.
            var waited = 0
            while running {
                if let os = outputStream, os.hasSpaceAvailable { break }
                if waited > 100 { throw NSError(domain: "CwAudioStream", code: 2) }
                Thread.sleep(forTimeInterval: 0.01)
                waited += 1
            }
            if !running { return }

            let written = pcm.withUnsafeBufferPointer { bp -> Int in
                guard let base = bp.baseAddress else { return -1 }
                return base.withMemoryRebound(to: UInt8.self, capacity: byteCount) { p in
                    self.outputStream?.write(p, maxLength: byteCount) ?? -1
                }
            }
            if written < 0 {
                throw NSError(domain: "CwAudioStream", code: 1)
            }

            let now = Date().timeIntervalSince1970 * 1000
            let sleepMs = nextWrite - now
            if sleepMs > 0 {
                Thread.sleep(forTimeInterval: sleepMs / 1000.0)
            }
        }
    }

    /// Resolve hostname → IPv4 string via getaddrinfo (mDNS-aware on iOS).
    private static func resolveIPv4(_ host: String) -> String? {
        if host.isEmpty { return nil }
        var test = in_addr()
        if host.withCString({ inet_pton(AF_INET, $0, &test) }) == 1 { return host }
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_STREAM
        hints.ai_protocol = IPPROTO_TCP
        var result: UnsafeMutablePointer<addrinfo>? = nil
        let rv = host.withCString { getaddrinfo($0, nil, &hints, &result) }
        guard rv == 0, let info = result else { return nil }
        defer { freeaddrinfo(info) }
        let sa = info.pointee.ai_addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
        var addr = sa.sin_addr
        var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &addr, &buf, socklen_t(INET_ADDRSTRLEN)) != nil else { return nil }
        return String(cString: buf)
    }
}
