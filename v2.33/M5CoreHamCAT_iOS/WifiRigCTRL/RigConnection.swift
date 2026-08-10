import Foundation
import Darwin

actor RigApiService {
    private(set) var host: String = ""
    private(set) var apiPort: Int = AppConstants.defaultApiPort
    private(set) var apiKey: String = ""

    /// Resolved IP (used in URLs to avoid iOS 26 URLSession mDNS issues with `.local`).
    /// Falls back to `host` if resolution fails.
    private var resolvedHost: String = ""

    private let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 3.0
        config.timeoutIntervalForResource = 10.0
        config.waitsForConnectivity = false
        config.httpAdditionalHeaders = ["Accept": "application/json"]
        return URLSession(configuration: config)
    }()

    func configure(host: String, apiPort: Int, apiKey: String) {
        self.host = host
        self.apiPort = apiPort
        self.apiKey = apiKey
        // Resolve once via getaddrinfo (mDNS-aware on iOS) and cache the IP.
        // iOS 26 URLSession sometimes can't resolve `.local` hostnames reliably,
        // so we pre-resolve and use a literal IP in the URL.
        self.resolvedHost = resolveIPv4(host) ?? host
    }

    var baseURL: String { "http://\(resolvedHost.isEmpty ? host : resolvedHost):\(apiPort)" }

    /// Synchronously resolve a hostname to its first IPv4 address via getaddrinfo.
    /// Returns nil if not resolvable. Numeric IPs pass through unchanged.
    private nonisolated func resolveIPv4(_ host: String) -> String? {
        if host.isEmpty { return nil }
        // If already a numeric IP, return as-is.
        var test = in_addr()
        if host.withCString({ inet_pton(AF_INET, $0, &test) }) == 1 {
            return host
        }
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_STREAM
        hints.ai_protocol = IPPROTO_TCP
        var result: UnsafeMutablePointer<addrinfo>? = nil
        let rv = host.withCString { getaddrinfo($0, nil, &hints, &result) }
        guard rv == 0, let info = result else { return nil }
        defer { freeaddrinfo(info) }
        let sa = info.pointee.ai_addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
        var addrCopy = sa.sin_addr
        var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &addrCopy, &buf, socklen_t(INET_ADDRSTRLEN)) != nil else {
            return nil
        }
        return String(cString: buf)
    }

    private func makeRequest(path: String, query: [URLQueryItem] = [], method: String = "GET", body: Data? = nil, contentType: String? = nil, timeout: TimeInterval? = nil) throws -> URLRequest {
        var comps = URLComponents(string: baseURL + path)
        if !query.isEmpty { comps?.queryItems = query }
        guard let url = comps?.url else { throw RigApiError.invalidURL }
        var req = URLRequest(url: url)
        req.httpMethod = method
        if !apiKey.isEmpty { req.setValue(apiKey, forHTTPHeaderField: "X-API-Key") }
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        if let body { req.httpBody = body }
        if let timeout { req.timeoutInterval = timeout }
        return req
    }

    private func perform(_ req: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await session.data(for: req)
        } catch {
            throw RigApiError.transport(error)
        }
        guard let http = response as? HTTPURLResponse else { throw RigApiError.httpStatus(-1) }
        guard (200..<300).contains(http.statusCode) else { throw RigApiError.httpStatus(http.statusCode) }
        return (data, http)
    }

    private func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw RigApiError.decoding(String(describing: error)) }
    }

    private func form(_ pairs: [String: String]) -> Data {
        let s = pairs.map { (k, v) in
            "\(percentEncode(k))=\(percentEncode(v))"
        }.joined(separator: "&")
        return Data(s.utf8)
    }

    private func percentEncode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? s
    }

    // MARK: - Endpoints

    func getRigs() async throws -> [RigInfo] {
        let req = try makeRequest(path: "/rigs", timeout: 10.0)
        let (data, _) = try await perform(req)
        return try decode(RigsResponse.self, from: data).rigs
    }

    func getDevices() async throws -> DevicesResponse {
        let req = try makeRequest(path: "/devices", timeout: 10.0)
        let (data, _) = try await perform(req)
        return try decode(DevicesResponse.self, from: data)
    }

    func openRig(model: Int, cat: String, baud: Int, ptt: String, pttType: String) async throws {
        let q = [
            URLQueryItem(name: "model", value: String(model)),
            URLQueryItem(name: "cat", value: cat),
            URLQueryItem(name: "baud", value: String(baud)),
            URLQueryItem(name: "ptt", value: ptt),
            URLQueryItem(name: "ptt_type", value: pttType)
        ]
        let req = try makeRequest(path: "/radio/open", query: q, timeout: 5.0)
        _ = try await perform(req)
    }

    func getStatus() async throws -> RigStatus {
        let req = try makeRequest(path: "/radio/status")
        let (data, _) = try await perform(req)
        return try decode(RigStatus.self, from: data)
    }

    func getCaps() async throws -> CapsResponse {
        let req = try makeRequest(path: "/radio/caps")
        let (data, _) = try await perform(req)
        return try decode(CapsResponse.self, from: data)
    }

    func setFreq(hz: Int64) async throws {
        let body = form(["f": String(hz)])
        let req = try makeRequest(path: "/radio/setfreq", method: "POST", body: body, contentType: "application/x-www-form-urlencoded")
        _ = try await perform(req)
    }

    func setMode(mode: String, width: Int) async throws {
        let body = form(["mode": mode, "width": String(width)])
        let req = try makeRequest(path: "/radio/setmode", method: "POST", body: body, contentType: "application/x-www-form-urlencoded")
        _ = try await perform(req)
    }

    func setLevel(name: String, value: Double) async throws {
        let body = form(["name": name, "value": String(format: "%.3f", value)])
        let req = try makeRequest(path: "/radio/setlevel", method: "POST", body: body, contentType: "application/x-www-form-urlencoded")
        _ = try await perform(req)
    }

    func setPower(value: Double) async throws {
        let body = form(["value": String(format: "%.3f", value)])
        let req = try makeRequest(path: "/radio/setpower", method: "POST", body: body, contentType: "application/x-www-form-urlencoded")
        _ = try await perform(req)
    }

    func setPtt(state: Bool) async throws {
        let body = form(["state": state ? "1" : "0"])
        let req = try makeRequest(path: "/radio/ptt", method: "POST", body: body, contentType: "application/x-www-form-urlencoded")
        _ = try await perform(req)
    }

    /// Pi-side API + rigctld version (used by About view to warn on mismatch with the app).
    /// Returns ("?", reason) on failure so the caller can still display something useful.
    func getServerVersion() async -> (apiVersion: String, rigctld: String) {
        do {
            let req = try makeRequest(path: "/admin/version", timeout: 5.0)
            let (data, _) = try await perform(req)
            struct Resp: Decodable { let api_version: String?; let rigctld: String? }
            let r = try? JSONDecoder().decode(Resp.self, from: data)
            return (r?.api_version ?? "?", r?.rigctld ?? "?")
        } catch {
            return ("?", error.localizedDescription)
        }
    }

    /// Set Noise Reduction level (0=Off, 1..5). Endpoint mirrors Android's `/radio/noise_reduction`.
    func setNrLevel(_ level: Int) async throws {
        let body = form(["level": String(level)])
        let req = try makeRequest(path: "/radio/noise_reduction", method: "POST", body: body, contentType: "application/x-www-form-urlencoded")
        _ = try await perform(req)
    }

    func setPoll(state: Bool) async throws {
        let body = form(["state": state ? "1" : "0"])
        let req = try makeRequest(path: "/radio/poll", method: "POST", body: body, contentType: "application/x-www-form-urlencoded")
        _ = try await perform(req)
    }

    func setAudioDevice(capture: String, playback: String) async throws {
        let json: [String: String] = ["capture": capture, "playback": playback]
        let body = try JSONSerialization.data(withJSONObject: json)
        let req = try makeRequest(path: "/radio/audio_device", method: "POST", body: body, contentType: "application/json")
        _ = try await perform(req)
    }

    func getPiTimeMs() async throws -> Int64 {
        let req = try makeRequest(path: "/time")
        let (data, _) = try await perform(req)
        return try decode(PiTime.self, from: data).ms
    }

    func pttHeartbeat() async throws {
        let req = try makeRequest(path: "/radio/ptt_heartbeat", method: "POST", body: Data(), contentType: "application/x-www-form-urlencoded", timeout: 1.2)
        _ = try await perform(req)
    }

    func setBkIn(on: Bool) async throws {
        let body = form(["state": on ? "1" : "0"])
        let req = try makeRequest(path: "/radio/setbkin", method: "POST", body: body, contentType: "application/x-www-form-urlencoded")
        _ = try await perform(req)
    }

    func getBkIn() async throws -> Bool {
        let req = try makeRequest(path: "/radio/getbkin")
        let (data, _) = try await perform(req)
        return try decode(BkInResponse.self, from: data).bk_in
    }

    // MARK: - APRS

    func sendAprsConfig(_ config: AprsConfig) async throws {
        let body = try JSONEncoder().encode(config)
        let req = try makeRequest(path: "/aprs_config", method: "POST", body: body, contentType: "application/json")
        _ = try await perform(req)
    }

    func startAprs(_ config: AprsConfig) async throws {
        // Build JSON for the subset (Android-compatible).
        let payload: [String: Any] = [
            "callsign": config.callsign,
            "ssid": config.ssid,
            "path": config.path,
            "interval": config.interval,
            "freq": config.freq,
            "baud": config.baud,
            "use_gps": config.use_gps,
            "manual_lat": config.manual_lat,
            "manual_lon": config.manual_lon
        ]
        let body = try JSONSerialization.data(withJSONObject: payload)
        let req = try makeRequest(path: "/aprs_start", method: "POST", body: body, contentType: "application/json")
        _ = try await perform(req)
    }

    func stopAprs() async throws {
        let req = try makeRequest(path: "/aprs_stop", method: "POST", body: Data(), contentType: "text/plain")
        _ = try await perform(req)
    }

    func getAprsReceived() async throws -> [AprsStation] {
        let req = try makeRequest(path: "/aprs_received")
        let (data, _) = try await perform(req)
        return try decode(AprsReceivedResponse.self, from: data).stations
    }

    func aprsHeartbeat() async throws {
        let req = try makeRequest(path: "/aprs_heartbeat", method: "POST", body: Data(), contentType: "text/plain", timeout: 2.0)
        _ = try await perform(req)
    }

    func sendGps(lat: Double, lon: Double) async throws {
        let payload: [String: Any] = ["lat": lat, "lon": lon]
        let body = try JSONSerialization.data(withJSONObject: payload)
        let req = try makeRequest(path: "/gps", method: "POST", body: body, contentType: "application/json")
        _ = try await perform(req)
    }

    // MARK: - VFO (v2.20)

    func getVfoMode() async throws -> VfoResponse {
        let req = try makeRequest(path: "/radio/vfo_current")
        let (data, _) = try await perform(req)
        return try decode(VfoResponse.self, from: data)
    }

    func toggleVfo() async throws -> VfoResponse {
        let req = try makeRequest(path: "/radio/vfo_toggle", method: "POST", body: Data(), contentType: "text/plain")
        let (data, _) = try await perform(req)
        return try decode(VfoResponse.self, from: data)
    }

    // MARK: - v2.02 additions

    /// Improved Pi clock offset: RTT-corrected `(piMs + rtt/2 - t2)`.
    func getPiClockOffsetMs() async throws -> Int64 {
        let t1 = Int64(Date().timeIntervalSince1970 * 1000)
        let req = try makeRequest(path: "/time")
        let (data, _) = try await perform(req)
        let t2 = Int64(Date().timeIntervalSince1970 * 1000)
        let piMs = try decode(PiTime.self, from: data).ms
        return piMs + (t2 - t1) / 2 - t2
    }

    /// Audio device for FT8 only (independent of main audio device).
    func setAudioDeviceFt8(capture: String) async throws {
        let json: [String: String] = ["capture": capture]
        let body = try JSONSerialization.data(withJSONObject: json)
        let req = try makeRequest(path: "/radio/audio_device_ft8", method: "POST", body: body, contentType: "application/json")
        _ = try await perform(req)
    }

    /// Server-side FT8/FT4 TX.
    func ft8Tx(msg: String, audioFreqHz: Int = 1500, rate: Int = 12000, isFt4: Bool = false) async throws {
        let payload: [String: Any] = [
            "msg": msg,
            "audio_freq": audioFreqHz,
            "rate": rate,
            "is_ft4": isFt4
        ]
        let body = try JSONSerialization.data(withJSONObject: payload)
        let req = try makeRequest(path: "/radio/ft8_tx", method: "POST", body: body, contentType: "application/json", timeout: 20.0)
        _ = try await perform(req)
    }

    // MARK: - v2.02 CW bridge

    func cwStatus() async throws -> CwStatus {
        let req = try makeRequest(path: "/cw/status")
        let (data, _) = try await perform(req)
        return try decode(CwStatus.self, from: data)
    }

    func cwOpen(port: String = "ttyACM0", delayMs: Int = 0) async throws {
        let q = [URLQueryItem(name: "port", value: port), URLQueryItem(name: "delay_ms", value: String(delayMs))]
        let req = try makeRequest(path: "/cw/open", query: q)
        _ = try await perform(req)
    }

    func cwClose() async throws {
        let req = try makeRequest(path: "/cw/close", method: "POST", body: Data(), contentType: "text/plain")
        _ = try await perform(req)
    }

    func cwKey(_ on: Bool) async throws {
        let body = form(["is_on": on ? "true" : "false"])
        let req = try makeRequest(path: "/cw/key", method: "POST", body: body, contentType: "application/x-www-form-urlencoded")
        _ = try await perform(req)
    }

    func cwSendMorse(text: String, wpm: Int) async throws {
        let body = form(["text": text, "wpm": String(wpm)])
        let req = try makeRequest(path: "/cw/send_morse", method: "POST", body: body, contentType: "application/x-www-form-urlencoded")
        _ = try await perform(req)
    }

    func cwStopMorse() async throws {
        let req = try makeRequest(path: "/cw/stop_morse", method: "POST", body: Data(), contentType: "text/plain")
        _ = try await perform(req)
    }

    func cwMorseStatus() async throws -> Bool {
        let req = try makeRequest(path: "/cw/morse_status")
        let (data, _) = try await perform(req)
        struct Resp: Decodable { let sending: Bool? }
        return (try? decode(Resp.self, from: data).sending) ?? false
    }

    // MARK: - v2.02 Admin (firmware/script upload)

    /// Returns nil on success, or an error message string on failure.
    func sendCwBridgeUpdate(_ content: Data) async throws {
        let req = try makeRequest(path: "/admin/update_cw_bridge", method: "POST", body: content, contentType: "text/plain", timeout: 30.0)
        _ = try await perform(req)
    }

    func sendApiUpdate(_ content: Data) async throws {
        let req = try makeRequest(path: "/admin/update", method: "POST", body: content, contentType: "text/plain", timeout: 30.0)
        _ = try await perform(req)
    }

    func sendSetupScript(_ content: Data) async throws {
        let req = try makeRequest(path: "/admin/setup", method: "POST", body: content, contentType: "application/x-sh", timeout: 30.0)
        _ = try await perform(req)
    }

    /// Returns (running, log).
    func getSetupLog() async throws -> (running: Bool, log: String) {
        let req = try makeRequest(path: "/admin/setup_log", timeout: 10.0)
        let (data, _) = try await perform(req)
        struct Resp: Decodable { let running: Bool?; let log: String? }
        let r = try decode(Resp.self, from: data)
        return (r.running ?? false, r.log ?? "")
    }

    func sendHamlibInstall(_ content: Data) async throws {
        let req = try makeRequest(path: "/admin/install_hamlib", method: "POST", body: content, contentType: "application/x-sh", timeout: 30.0)
        _ = try await perform(req)
    }

    func sendWebFt8Update(_ content: Data) async throws {
        let req = try makeRequest(path: "/admin/update_webft8", method: "POST", body: content, contentType: "text/plain", timeout: 30.0)
        _ = try await perform(req)
    }

    /// Returns (running, log).
    func getHamlibLog() async throws -> (running: Bool, log: String) {
        let req = try makeRequest(path: "/admin/hamlib_log", timeout: 10.0)
        let (data, _) = try await perform(req)
        struct Resp: Decodable { let running: Bool?; let log: String? }
        let r = try decode(Resp.self, from: data)
        return (r.running ?? false, r.log ?? "")
    }
}
