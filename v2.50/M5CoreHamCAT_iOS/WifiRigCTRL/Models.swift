import Foundation

nonisolated struct RigInfo: Identifiable, Hashable, Decodable {
    let id: Int
    let name: String
}

nonisolated struct RigsResponse: Decodable {
    let rigs: [RigInfo]
}

nonisolated struct SoundDevice: Identifiable, Hashable, Decodable {
    let id: String
    let label: String
}

nonisolated struct DevicesResponse: Decodable {
    let serial: [String]
    let audio: [SoundDevice]
}

nonisolated struct CapsResponse: Decodable {
    let modes: [String]
}

nonisolated struct RigStatus: Decodable {
    let freq: Int64
    let mode: String
    let signal: Double
    let tx: Bool
    let power: Double
    let width: Int
    let sql: Double
    let tx_in_progress: Bool?
    let bk_in: Bool?
    let api_version: String?
    let webft8_version: String?

    enum CodingKeys: String, CodingKey {
        case freq, mode, signal, tx, power, width, sql
        case tx_in_progress, bk_in, api_version, webft8_version
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // Numeric fields are lenient: accept Int / Int64 / Double / String forms
        // (matches Android v2.02 RigApiService.getStatus parsing).
        self.freq = Self.decodeInt64(c, .freq) ?? 0
        self.mode = (try? c.decode(String.self, forKey: .mode)) ?? ""
        self.signal = Self.decodeDouble(c, .signal) ?? 0
        self.tx = (try? c.decode(Bool.self, forKey: .tx)) ?? false
        self.power = Self.decodeDouble(c, .power) ?? 0
        self.width = Int(Self.decodeInt64(c, .width) ?? 0)
        self.sql = Self.decodeDouble(c, .sql) ?? 0
        self.tx_in_progress = try? c.decode(Bool.self, forKey: .tx_in_progress)
        if let b = try? c.decode(Bool.self, forKey: .bk_in) { self.bk_in = b }
        else if let i = try? c.decode(Int.self, forKey: .bk_in) { self.bk_in = (i == 1) }
        else { self.bk_in = nil }
        self.api_version = try? c.decode(String.self, forKey: .api_version)
        self.webft8_version = try? c.decode(String.self, forKey: .webft8_version)
    }

    nonisolated init(freq: Int64, mode: String, signal: Double, tx: Bool, power: Double, width: Int, sql: Double, tx_in_progress: Bool?, bk_in: Bool?) {
        self.freq = freq; self.mode = mode; self.signal = signal; self.tx = tx
        self.power = power; self.width = width; self.sql = sql
        self.tx_in_progress = tx_in_progress; self.bk_in = bk_in
        self.api_version = nil; self.webft8_version = nil
    }

    private static func decodeInt64(_ c: KeyedDecodingContainer<CodingKeys>, _ key: CodingKeys) -> Int64? {
        if let v = try? c.decode(Int64.self, forKey: key) { return v }
        if let v = try? c.decode(Double.self, forKey: key) { return Int64(v) }
        if let v = try? c.decode(String.self, forKey: key), let n = Int64(v) { return n }
        return nil
    }

    private static func decodeDouble(_ c: KeyedDecodingContainer<CodingKeys>, _ key: CodingKeys) -> Double? {
        if let v = try? c.decode(Double.self, forKey: key) { return v }
        if let v = try? c.decode(Int64.self, forKey: key) { return Double(v) }
        if let v = try? c.decode(String.self, forKey: key), let n = Double(v) { return n }
        return nil
    }
}

nonisolated struct PiTime: Decodable {
    let ms: Int64
}

nonisolated struct BkInResponse: Decodable {
    let bk_in: Bool
}

/// Status of the Pi-side CW USB bridge (`cw_bridge_ncm.py`). v2.02.
nonisolated struct CwStatus: Decodable, Hashable {
    let connected: Bool
    let synced: Bool
    let offset_ms: Int64
    let max_late_ms: Int?

    var maxLateMs: Int { max_late_ms ?? 0 }

    enum CodingKeys: String, CodingKey {
        case connected, synced, offset_ms, max_late_ms
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.connected = Self.bool(c, .connected) ?? false
        self.synced = Self.bool(c, .synced) ?? false
        self.offset_ms = Self.int64(c, .offset_ms) ?? 0
        if let v = Self.int64(c, .max_late_ms) { self.max_late_ms = Int(v) }
        else { self.max_late_ms = nil }
    }

    nonisolated init(connected: Bool, synced: Bool, offset_ms: Int64, max_late_ms: Int?) {
        self.connected = connected; self.synced = synced
        self.offset_ms = offset_ms; self.max_late_ms = max_late_ms
    }

    private static func bool(_ c: KeyedDecodingContainer<CodingKeys>, _ k: CodingKeys) -> Bool? {
        if let v = try? c.decode(Bool.self, forKey: k) { return v }
        if let v = try? c.decode(Int.self, forKey: k) { return v != 0 }
        if let v = try? c.decode(String.self, forKey: k) { return ["1","true","TRUE","yes"].contains(v) }
        return nil
    }
    private static func int64(_ c: KeyedDecodingContainer<CodingKeys>, _ k: CodingKeys) -> Int64? {
        if let v = try? c.decode(Int64.self, forKey: k) { return v }
        if let v = try? c.decode(Double.self, forKey: k) { return Int64(v) }
        if let v = try? c.decode(String.self, forKey: k), let n = Int64(v) { return n }
        return nil
    }
}

nonisolated enum PttType: String, CaseIterable, Hashable {
    case none = "NONE"
    case rts = "RTS"
    case dtr = "DTR"
    case rig = "RIG"

    var displayName: String { self == .rig ? "CAT" : rawValue }
}

nonisolated enum AppConstants {
    static let baudRates: [Int] = [1200, 4800, 9600, 19200, 38400, 57600, 115200]
    static let defaultBaudIndex: Int = 2

    static let samplingRates: [Int] = [0, 8000, 16000, 22050, 32000, 44100, 48000]
    static let defaultSamplingIndex: Int = 1

    static let screenTimeoutMinutes: [Int] = [0, 5, 10, 30, 60]
    static let defaultScreenTimeoutIndex: Int = 2

    /// Step list: 1Hz / 10Hz / 100Hz / 500Hz / 1kHz / 5kHz / 10kHz / 20kHz.
    static let stepHz: [Int] = [1, 10, 100, 500, 1000, 5000, 10000, 20000]
    static let stepLabels: [String] = ["1Hz", "10Hz", "100Hz", "500Hz", "1kHz", "5kHz", "10kHz", "20kHz"]

    /// Default step index per rig mode.
    /// Returns an index into `stepHz`.
    static func defaultStepIndex(forMode mode: String) -> Int {
        let m = mode.uppercased()
        if m.contains("FM") || m.contains("AM") { return 7 } // 20 kHz
        if m == "CW" || m == "CWR" || m == "CW-R" { return 2 } // 100 Hz
        return 4 // 1 kHz default for SSB/PKT/RTTY etc
    }

    static let defaultApiPort: Int = 8000
    static let defaultAudioPort: Int = 50000
    static let defaultPttPort: Int = 8888
    static let cwUdpPort: Int = 8889
    static let webFt8HttpsPort: Int = 8443
    /// Port that the Raspberry Pi `cw_bridge_ncm.py` listens on for relay from iPhone.
    static let atomBridgePort: Int = 8889
}

nonisolated struct VfoResponse: Decodable {
    let mode: String   // "ab" | "mainsub"
    let side: String   // "A"/"B" or "Main"/"M/S"/"S/M"
}

nonisolated struct AprsConfig: Encodable, Hashable {
    var callsign: String
    var ssid: Int
    var path: String
    var interval: Int
    var freq: Double          // MHz
    var baud: Int             // 1200 / 9600
    var use_gps: Bool
    var manual_lat: Double
    var manual_lon: Double
    var symbol: String
    var comment: String
    var destination: String
    var sound_device: String
    var rig_id: String
    var cat_device: String
    var use_rig_modem: Bool = false
    var modem_sel: Int = 2   // 1=AUTO 2=MAIN 3=SUB

    /// Subset sent to /aprs_start — last 6 fields omitted (matches Android v2.02).
    func startPayload() -> [String: any Encodable & Sendable] {
        [
            "callsign": callsign,
            "ssid": ssid,
            "path": path,
            "interval": interval,
            "freq": freq,
            "baud": baud,
            "use_gps": use_gps,
            "manual_lat": manual_lat,
            "manual_lon": manual_lon
        ]
    }
}

/// APRS symbol with emoji display (v2.02).
nonisolated struct AprsSymbol: Identifiable, Hashable {
    let code: String
    let label: String
    let emoji: String
    var id: String { code }
    var display: String { "\(emoji) \(label)" }
}

nonisolated enum AprsConstants {
    static let pathList: [String] = ["WIDE1-1", "WIDE1-1,WIDE2-1", "WIDE2-1", "DIRECT", "NONE"]

    /// v2.02 symbol palette with emojis.
    static let symbols: [AprsSymbol] = [
        AprsSymbol(code: ">", label: "Car",         emoji: "🚗"),
        AprsSymbol(code: "v", label: "Van",         emoji: "🚐"),
        AprsSymbol(code: "k", label: "Truck",       emoji: "🚚"),
        AprsSymbol(code: "U", label: "Bus",         emoji: "🚌"),
        AprsSymbol(code: "<", label: "Motorcycle",  emoji: "🏍"),
        AprsSymbol(code: "j", label: "Jeep",        emoji: "🚙"),
        AprsSymbol(code: "[", label: "Pedestrian",  emoji: "🚶"),
        AprsSymbol(code: "b", label: "Bike",        emoji: "🚲"),
        AprsSymbol(code: "e", label: "Horse",       emoji: "🐴"),
        AprsSymbol(code: "-", label: "House",       emoji: "🏠"),
        AprsSymbol(code: "Y", label: "Sailboat",    emoji: "⛵"),
        AprsSymbol(code: "s", label: "Motorboat",   emoji: "🚤"),
        AprsSymbol(code: "X", label: "Helicopter",  emoji: "🚁"),
        AprsSymbol(code: "^", label: "Aircraft",    emoji: "✈"),
        AprsSymbol(code: "O", label: "Balloon",     emoji: "🎈"),
        AprsSymbol(code: "_", label: "Wx Station",  emoji: "🌡"),
        AprsSymbol(code: "r", label: "Antenna",     emoji: "📡"),
        AprsSymbol(code: "a", label: "Ambulance",   emoji: "🚑"),
        AprsSymbol(code: "f", label: "Fire Truck",  emoji: "🚒"),
        AprsSymbol(code: "h", label: "Hospital",    emoji: "🏥"),
        AprsSymbol(code: "n", label: "Node",        emoji: "📦"),
        AprsSymbol(code: "/", label: "Red Dot",     emoji: "🔴"),
    ]

    static func symbol(byCode code: String) -> AprsSymbol {
        symbols.first(where: { $0.code == code })
            ?? AprsSymbol(code: code, label: code, emoji: "")
    }

    /// Legacy flat list kept for compatibility with older Picker callers.
    static var symbolList: [String] { symbols.map(\.code) }

    /// v2.02 destination list.
    static let destinationList: [String] = ["APDW18", "APDW19", "APDW20", "APYA05", "APMI05", "APRS00"]
    static let intervalSecs: [Int] = [30, 60, 120, 180, 300, 600]
    static let baudList: [Int] = [1200, 9600]
}

/// Received APRS station entry from /aprs_received (v2.20).
nonisolated struct AprsStation: Identifiable, Decodable, Hashable {
    let call: String
    let lat: Double?
    let lon: Double?
    let symbol: String?
    let comment: String?
    let age_sec: Int
    var id: String { call }
}

nonisolated struct AprsReceivedResponse: Decodable {
    let stations: [AprsStation]
}

// MARK: - Band Memory (v2.31)

nonisolated struct MemoryEntry: Identifiable, Codable, Hashable {
    let name: String
    let freqHz: Int64
    let mode: String      // "" = 現在のモードのまま
    let stepIndex: Int    // -1 = モードデフォルト
    let isPreset: Bool

    var id: String { "\(isPreset ? "p" : "u")_\(name)_\(freqHz)" }

    init(name: String, freqHz: Int64, mode: String = "", stepIndex: Int = -1, isPreset: Bool = false) {
        self.name = name; self.freqHz = freqHz; self.mode = mode
        self.stepIndex = stepIndex; self.isPreset = isPreset
    }
}

// STEP_LIST index: 0=1Hz 1=10Hz 2=100Hz 3=500Hz 4=1kHz 5=5kHz 6=10kHz 7=20kHz
let PRESET_MEMORIES: [MemoryEntry] = [
    MemoryEntry(name: "160m CW",   freqHz:   1_825_000, mode: "CW",  stepIndex: 2, isPreset: true),
    MemoryEntry(name: "160m SSB",  freqHz:   1_910_000, mode: "LSB", stepIndex: 4, isPreset: true),
    MemoryEntry(name: "80m CW",    freqHz:   3_525_000, mode: "CW",  stepIndex: 2, isPreset: true),
    MemoryEntry(name: "80m SSB",   freqHz:   3_740_000, mode: "LSB", stepIndex: 4, isPreset: true),
    MemoryEntry(name: "40m CW",    freqHz:   7_025_000, mode: "CW",  stepIndex: 2, isPreset: true),
    MemoryEntry(name: "40m SSB",   freqHz:   7_100_000, mode: "LSB", stepIndex: 4, isPreset: true),
    MemoryEntry(name: "30m CW",    freqHz:  10_130_000, mode: "CW",  stepIndex: 2, isPreset: true),
    MemoryEntry(name: "20m CW",    freqHz:  14_025_000, mode: "CW",  stepIndex: 2, isPreset: true),
    MemoryEntry(name: "20m SSB",   freqHz:  14_225_000, mode: "USB", stepIndex: 4, isPreset: true),
    MemoryEntry(name: "17m CW",    freqHz:  18_080_000, mode: "CW",  stepIndex: 2, isPreset: true),
    MemoryEntry(name: "17m SSB",   freqHz:  18_130_000, mode: "USB", stepIndex: 4, isPreset: true),
    MemoryEntry(name: "15m CW",    freqHz:  21_025_000, mode: "CW",  stepIndex: 2, isPreset: true),
    MemoryEntry(name: "15m SSB",   freqHz:  21_225_000, mode: "USB", stepIndex: 4, isPreset: true),
    MemoryEntry(name: "12m CW",    freqHz:  24_895_000, mode: "CW",  stepIndex: 2, isPreset: true),
    MemoryEntry(name: "12m SSB",   freqHz:  24_940_000, mode: "USB", stepIndex: 4, isPreset: true),
    MemoryEntry(name: "10m CW",    freqHz:  28_025_000, mode: "CW",  stepIndex: 2, isPreset: true),
    MemoryEntry(name: "10m SSB",   freqHz:  28_400_000, mode: "USB", stepIndex: 4, isPreset: true),
    MemoryEntry(name: "6m SSB",    freqHz:  50_100_000, mode: "USB", stepIndex: 4, isPreset: true),
    MemoryEntry(name: "2m CW",     freqHz: 144_060_000, mode: "CW",  stepIndex: 2, isPreset: true),
    MemoryEntry(name: "2m SSB",    freqHz: 144_200_000, mode: "USB", stepIndex: 4, isPreset: true),
    MemoryEntry(name: "2m FM",     freqHz: 145_000_000, mode: "FM",  stepIndex: 7, isPreset: true),
    MemoryEntry(name: "70cm CW",   freqHz: 430_050_000, mode: "CW",  stepIndex: 2, isPreset: true),
    MemoryEntry(name: "70cm SSB",  freqHz: 430_100_000, mode: "USB", stepIndex: 4, isPreset: true),
    MemoryEntry(name: "70cm FM",   freqHz: 433_000_000, mode: "FM",  stepIndex: 7, isPreset: true),
]

// MARK: - POTA Spot (v2.31)

nonisolated struct PotaSpot: Identifiable, Decodable {
    let spotId:   Int64
    let activator: String
    let frequency: String   // kHz (e.g. "14025" = 14.025 MHz)
    let mode:      String
    let reference: String
    let name:      String
    let spotTime:  String
    let spotter:   String
    let comments:  String
    let invalid:   Bool

    var id: Int64  { spotId }
    var freqMhz: Double { (Double(frequency) ?? 0.0) / 1000.0 }
    var freqHz:  Int64  { Int64((Double(frequency) ?? 0.0) * 1000.0) }
    var program: String { reference.components(separatedBy: "-").first ?? "" }

    enum CodingKeys: String, CodingKey {
        case spotId, activator, frequency, mode, reference, name
        case spotTime, spotter, comments, invalid
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        spotId    = (try? c.decode(Int64.self,  forKey: .spotId))    ?? 0
        activator = (try? c.decode(String.self, forKey: .activator)) ?? ""
        frequency = (try? c.decode(String.self, forKey: .frequency)) ?? ""
        mode      = (try? c.decode(String.self, forKey: .mode))      ?? ""
        reference = (try? c.decode(String.self, forKey: .reference)) ?? ""
        name      = (try? c.decode(String.self, forKey: .name))      ?? ""
        spotTime  = (try? c.decode(String.self, forKey: .spotTime))  ?? ""
        spotter   = (try? c.decode(String.self, forKey: .spotter))   ?? ""
        comments  = (try? c.decode(String.self, forKey: .comments))  ?? ""
        invalid   = (try? c.decode(Bool.self,   forKey: .invalid))   ?? false
    }
}

// MARK: - SOTA Spot (v2.31)

nonisolated struct SotaSpot: Identifiable, Decodable {
    let id:                Int64
    let timeStamp:         String?
    let comments:          String?
    let activatorCallsign: String?
    let frequency:         String?   // MHz (e.g. "14.025")
    let mode:              String?
    let summitCode:        String?
    let associationCode:   String?
    let summitDetails:     String?

    var freqMhz: Double { Double(frequency ?? "") ?? 0.0 }
    var freqHz:  Int64  { Int64((Double(frequency ?? "") ?? 0.0) * 1_000_000) }
    var codeStr: String {
        [associationCode ?? "", summitCode ?? ""].filter { !$0.isEmpty }.joined(separator: "/")
    }

    enum CodingKeys: String, CodingKey {
        case id, timeStamp, comments, activatorCallsign, frequency, mode
        case summitCode, associationCode, summitDetails
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id                = (try? c.decode(Int64.self,  forKey: .id))                ?? 0
        timeStamp         = try? c.decode(String.self, forKey: .timeStamp)
        comments          = try? c.decode(String.self, forKey: .comments)
        activatorCallsign = try? c.decode(String.self, forKey: .activatorCallsign)
        frequency         = try? c.decode(String.self, forKey: .frequency)
        mode              = try? c.decode(String.self, forKey: .mode)
        summitCode        = try? c.decode(String.self, forKey: .summitCode)
        associationCode   = try? c.decode(String.self, forKey: .associationCode)
        summitDetails     = try? c.decode(String.self, forKey: .summitDetails)
    }
}

nonisolated enum RigApiError: LocalizedError {
    case invalidURL
    case httpStatus(Int)
    case decoding(String)
    case transport(Error)

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid URL"
        case .httpStatus(let c): return "HTTP \(c)"
        case .decoding(let m): return "Decode error: \(m)"
        case .transport(let e): return e.localizedDescription
        }
    }
}
