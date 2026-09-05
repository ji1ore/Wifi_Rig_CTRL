import Foundation
import CoreBluetooth
import Darwin
import AVFoundation
@preconcurrency import Network

// Plain non-actor class: properties written from main thread AND from the BLE background
// queue (for immediate key-on sidetone response), and read on the audio render thread.
// Placed at file scope so its init() is not @MainActor-isolated, allowing
// nonisolated(unsafe) let rs to be initialised without a main-actor hop.
private final class SidetoneRenderState {
    var keyOn: Bool = false
    var volume: Double = 0.5
    var frequencyHz: Double = 700.0
    var sampleRate: Double = 44100.0
    var phase: Double = 0.0
    var envelope: Double = 0.0
}

/// CW relay over BLE Nordic UART Service (NUS) to DualKey-BLE (AtomS3 BLE keyer).
///
/// Protocol (matches Android `CwBleService.kt`):
/// - `0xE0 [4 bytes ts]`  (5 bytes, Atom→Phone) — SYNC request, forwarded over UDP to Pi
/// - `0xE1 [8 bytes ts]`  (9 bytes, Phone→Atom) — SYNC response from Pi
/// - `0xE2 [wpm]`         (2 bytes, both ways)  — WPM (5..99)
/// - `0xE3 [swap]`        (2 bytes, Phone→Atom) — paddle swap (0/1)
/// - `0x01/0x00 0x01 [8 bytes ts]` (10 bytes, Atom→Phone) — key state ON/OFF with timestamp
///
/// Phone acts as a transparent BLE↔UDP bridge plus a UI surface for key state / WPM.
@MainActor
@Observable
final class CwBleService: NSObject {

    // MARK: - Nordic UART Service UUIDs (same as Android impl)
    nonisolated static let nusService = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    nonisolated static let nusRxChar  = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")   // Phone → Atom (writeWithoutResponse)
    nonisolated static let nusTxChar  = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")   // Atom → Phone (notify)

    struct DiscoveredDevice: Identifiable, Hashable {
        let id: UUID
        let name: String
        let rssi: Int
    }

    // MARK: - Observable state
    private(set) var bluetoothState: CBManagerState = .unknown
    private(set) var scanning: Bool = false
    private(set) var discovered: [DiscoveredDevice] = []
    private(set) var connecting: Bool = false
    private(set) var connected: Bool = false
    private(set) var connectedName: String?
    private(set) var lastError: String?
    private(set) var wpm: Int = 20
    private(set) var keyOn: Bool = false
    private(set) var lastKeyAt: Date?
    private(set) var syncForwarded: Int = 0   // 0xE0 → Pi
    private(set) var syncReceived: Int = 0    // 0xE1 ← Pi → written to Atom
    private(set) var keyForwarded: Int = 0    // 10-byte key packet → Pi

    /// Pi UDP destination for SYNC tunneling. Set by MainViewModel to hostName (same as main rig host).
    /// Matches Android: cwBle.piSyncAddress uses prefs.hostName, CW_UDP_PORT=8889 is hardcoded.
    var piHost: String = ""
    let piPort: Int = AppConstants.atomBridgePort
    /// Desired keyer WPM to send to DualKey after 10 SYNCs. Matches Android keeyerWpm.
    var keeyerWpm: Int = 20
    /// Current rig mode (CW/USB/FM/etc.). Updated by MainViewModel. Used to decide whether
    /// to forward key packets to Pi (CW) or let the callback drive PTT (FM-CW).
    var currentMode: String = ""

    // MARK: - CoreBluetooth
    // Dedicated BLE queue: callbacks fire here instead of main thread so BLE I/O
    // doesn't compete with SwiftUI renders. Delegate methods still hop to @MainActor
    // via Task { @MainActor in … } for @Observable property updates.
    private let bleQueue = DispatchQueue(label: "ble.cw", qos: .userInteractive)
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var rxCharacteristic: CBCharacteristic?
    private var foundPeripherals: [UUID: CBPeripheral] = [:]

    // MARK: - Persistent UDP socket to Pi (BSD socket — same pattern as Android DatagramSocket)
    private var piSocket: Int32 = -1
    private var piSocketHost: String = ""
    private var piSocketPort: Int = 0
    private var piRecvTask: Task<Void, Never>?
    private var piPendingRequests: Int = 0

    // MARK: - Sidetone (local 700 Hz tone fed by keyOn)

    /// Shared AudioRxService whose AVAudioEngine is also used for sidetone.
    /// Set by MainViewModel after init. Sharing one engine eliminates hardware
    /// output contention between sidetone and radio RX on iOS.
    @ObservationIgnored weak var audioRx: AudioRxService?

    /// Enable/disable local sidetone playback. Survives across BLE connect/disconnect.
    var sidetoneEnabled: Bool = UserDefaults.standard.object(forKey: "ble.sidetoneEnabled") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(sidetoneEnabled, forKey: "ble.sidetoneEnabled")
            sidetoneEnabled ? startSidetone() : stopSidetone()
        }
    }
    /// Frequency in Hz. Conventional CW sidetone is 600–800 Hz; default 700 Hz like Android.
    var sidetoneFrequencyHz: Double = UserDefaults.standard.object(forKey: "ble.sidetoneFreq") as? Double ?? 700 {
        didSet {
            UserDefaults.standard.set(sidetoneFrequencyHz, forKey: "ble.sidetoneFreq")
            rs.frequencyHz = sidetoneFrequencyHz
        }
    }
    /// Linear volume (0…1). Multiplied with the envelope before writing samples.
    var sidetoneVolume: Double = UserDefaults.standard.object(forKey: "ble.sidetoneVolume") as? Double ?? 0.5 {
        didSet {
            UserDefaults.standard.set(sidetoneVolume, forKey: "ble.sidetoneVolume")
            rs.volume = sidetoneVolume
        }
    }
    /// Left/right paddle swap. Persisted; resent to keyer on BLE connect.
    var paddleSwapped: Bool = UserDefaults.standard.bool(forKey: "cwPaddleSwap") {
        didSet {
            UserDefaults.standard.set(paddleSwapped, forKey: "cwPaddleSwap")
            if connected { sendPaddleSwap(paddleSwapped) }
        }
    }
    // Fallback own engine, used only when audioRx is unavailable (should not happen).
    private let sidetoneEngine = AVAudioEngine()
    private var sidetoneSource: AVAudioSourceNode?
    private var sidetoneConfigObserver: NSObjectProtocol?
    // nonisolated(unsafe) allows BLE delegate to write rs.keyOn on BLE queue without
    // a main-actor hop — eliminates up to 16 ms sidetone jitter per SwiftUI frame.
    nonisolated(unsafe) private let rs = SidetoneRenderState()

    /// エンジンが動作中か（診断用）
    var sidetoneEngineRunning: Bool {
        if let rx = audioRx { return rx.isEngineRunning }
        return sidetoneEngine.isRunning
    }

    /// CW TXテキスト送信時など、外部からサイドトーンのキー状態を直接制御する。
    /// BLE物理キーイベント以外でサイドトーンを鳴らす場合に使用。
    func setSidetoneKey(_ isOn: Bool) {
        guard sidetoneEnabled else { return }
        // Lazy-start: BLEキーヤー未接続時もサイドトーンを鳴らせるようエンジンを初期化する。
        if isOn && sidetoneSource == nil { startSidetone() }
        rs.keyOn = isOn
    }

    /// Nonisolated variant for background CW timing tasks.
    /// sidetoneEnabled の確認は呼び出し側 (startLocalCwSidetone) で行う。
    /// nonisolated(unsafe) な rs に直接書くので MainActor ホップ不要 → ジッタゼロ。
    nonisolated func setSidetoneKeyRealtime(_ isOn: Bool) {
        rs.keyOn = isOn
    }

    /// sidetoneSourceがnilならstartSidetone()を呼ぶ。BLEキーヤー未接続時の遅延初期化用。
    func startSidetoneLazy() {
        if sidetoneSource == nil { startSidetone() }
    }

    /// 1秒間テストトーンを鳴らす（エンジンとrender blockの動作確認用）
    func testSidetone() {
        if sidetoneSource == nil { startSidetone() }
        rs.keyOn = true
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            self?.rs.keyOn = false
        }
    }

    // MARK: - Timing / scheduling
    /// Pi時刻とiOS時刻の差(ms)。Pi SYNC応答から計算。Pi未接続時は0。
    private(set) var piClockOffsetMs: Int64 = 0
    /// SYNC応答回数(成功+タイムアウト)。Android syncResponseCountと同様。10回でWPMを送信。
    private var syncResponseCount: Int = 0
    private var cwDelayMs: Int { UserDefaults.standard.integer(forKey: "cwDelayMs") }

    // MARK: - Packet accumulator (BLE notifications may not align with packet boundaries)
    private var accum = Data()

    // MARK: - Callbacks
    var onKeyStateChange: ((_ isOn: Bool, _ rawPacket: Data) -> Void)?
    var onWpmChange: ((_ wpm: Int) -> Void)?
    var onConnectionStateChange: ((_ connected: Bool) -> Void)?

    // MARK: - Lifecycle
    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: bleQueue)
        // Sidetone is NOT started here. AVAudioSession.setActive + AVAudioEngine.start
        // block the main thread for ~200 ms. Called from init (even inside a Task) this
        // races with SwiftUI's first-frame commit and leaves a black screen before the
        // splash. Sidetone is started lazily when the BLE keyer first connects, which
        // is the only moment audio is actually needed.
    }

    deinit {
        sidetoneEngine.stop()
    }

    // MARK: - Sidetone audio

    /// Start the sidetone sine-wave source node.
    /// Preferred: attach to AudioRxService's shared AVAudioEngine so both sidetone
    /// and radio RX coexist on one engine (eliminates hardware output contention).
    /// Fallback: own sidetoneEngine when audioRx is not wired.
    private func startSidetone() {
        stopSidetone()

        rs.volume = sidetoneVolume
        rs.frequencyHz = sidetoneFrequencyHz
        rs.keyOn = keyOn
        rs.envelope = 0
        rs.phase = 0

        let sr: Double = 44100
        rs.sampleRate = sr
        guard let sineFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                             sampleRate: sr, channels: 1,
                                             interleaved: false) else {
            lastError = "Failed to create sidetone audio format"
            return
        }

        // Render block runs on the real-time audio thread. Reads/writes rs (non-actor class)
        // which is visible from any thread without actor isolation.
        let rs = self.rs
        let source = AVAudioSourceNode(format: sineFormat) { _, _, frameCount, audioBufferList -> OSStatus in
            let abl = UnsafeMutableAudioBufferListPointer(audioBufferList)
            let target: Double = rs.keyOn ? rs.volume : 0.0
            let sampleRate = rs.sampleRate
            let phaseInc = 2.0 * .pi * rs.frequencyHz / sampleRate
            let rampStep = 1.0 / (sampleRate * 3.0 / 1000.0)  // 3 ms click-suppression ramp
            var phase = rs.phase
            var env = rs.envelope
            for frame in 0..<Int(frameCount) {
                if env < target { env = min(target, env + rampStep) }
                else if env > target { env = max(target, env - rampStep) }
                let s = Float(env * sin(phase))
                for buffer in abl {
                    if let mData = buffer.mData {
                        mData.assumingMemoryBound(to: Float.self)[frame] = s
                    }
                }
                phase += phaseInc
                if phase > 2.0 * .pi { phase -= 2.0 * .pi }
            }
            rs.phase = phase
            rs.envelope = env
            return noErr
        }

        sidetoneSource = source

        if let rx = audioRx {
            // Shared-engine path: AudioRxService manages the engine lifecycle.
            rx.startSidetoneIn(source, format: sineFormat)
            print("[CW] Sidetone attached to shared AudioRx engine @ \(Int(sr)) Hz")
        } else {
            // Fallback: own engine (should not happen after MainViewModel wires audioRx).
            let sess = AVAudioSession.sharedInstance()
            // Don't downgrade .playAndRecord — CI-V may own the mic session.
            if sess.category != .playAndRecord {
                try? sess.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            }
            try? sess.setActive(true, options: [])
            sidetoneEngine.attach(source)
            sidetoneEngine.connect(source, to: sidetoneEngine.mainMixerNode, format: sineFormat)
            do {
                try sidetoneEngine.start()
                print("[CW] Sidetone started on fallback engine @ \(Int(sr)) Hz")
            } catch {
                lastError = "Audio engine start: \(error.localizedDescription)"
                print("[CW] Sidetone fallback engine failed: \(error)")
            }
            sidetoneConfigObserver = NotificationCenter.default.addObserver(
                forName: .AVAudioEngineConfigurationChange,
                object: sidetoneEngine,
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor [weak self] in
                    guard let self, self.sidetoneEnabled, !self.sidetoneEngine.isRunning else { return }
                    try? await Task.sleep(nanoseconds: 200_000_000)
                    guard self.sidetoneEnabled, !self.sidetoneEngine.isRunning else { return }
                    self.startSidetone()
                }
            }
        }
    }

    private func stopSidetone() {
        if let obs = sidetoneConfigObserver {
            NotificationCenter.default.removeObserver(obs)
            sidetoneConfigObserver = nil
        }
        if let src = sidetoneSource {
            if let rx = audioRx {
                rx.stopSidetoneIn(src)
            } else {
                sidetoneEngine.stop()
                sidetoneEngine.detach(src)
            }
            sidetoneSource = nil
        }
        rs.envelope = 0
    }

    func startScan() {
        guard central.state == .poweredOn else {
            lastError = "Bluetooth not ready (state=\(central.state.rawValue))"
            return
        }
        if scanning { return }
        discovered.removeAll()
        foundPeripherals.removeAll()
        scanning = true

        // First add any already-connected NUS peripherals (e.g. a device connected by a
        // previous run of this app or by the system). They won't be re-discovered by scan
        // because they're not advertising while connected.
        let already = central.retrieveConnectedPeripherals(withServices: [Self.nusService])
        for p in already {
            let name = p.name ?? "(connected, no name)"
            print("[BLE] retrieveConnected: \(name) [\(p.identifier)]")
            foundPeripherals[p.identifier] = p
            discovered.append(.init(id: p.identifier, name: name, rssi: 0))
        }

        // Scan for ALL BLE peripherals. allowDuplicates=true so we keep refreshing RSSI
        // and re-detect a device that goes silent for a moment.
        central.scanForPeripherals(withServices: nil, options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: true
        ])
        print("[BLE] scanForPeripherals started (services=nil)")
    }

    func stopScan() {
        if scanning {
            central.stopScan()
            scanning = false
        }
    }

    func connect(_ device: DiscoveredDevice) {
        guard let p = foundPeripherals[device.id] else {
            lastError = "Peripheral not found in cache"
            return
        }
        stopScan()
        connecting = true
        connectedName = device.name
        peripheral = p
        p.delegate = self
        central.connect(p, options: nil)
    }

    func disconnect() {
        if let p = peripheral {
            central.cancelPeripheralConnection(p)
        }
        cleanup(reason: "disconnect called")
    }

    private func cleanup(reason: String) {
        accum.removeAll(keepingCapacity: false)
        rxCharacteristic = nil
        peripheral?.delegate = nil
        peripheral = nil
        connecting = false
        syncResponseCount = 0
        if connected {
            connected = false
            onConnectionStateChange?(false)
        }
        connectedName = nil
        closePiSocket()
    }

    // MARK: - Outbound writes (Phone → Atom)

    private func writeRaw(_ data: Data) {
        guard let p = peripheral, let ch = rxCharacteristic else { return }
        p.writeValue(data, for: ch, type: .withoutResponse)
    }

    func sendWpm(_ value: Int) {
        let v = max(5, min(99, value))
        wpm = v
        writeRaw(Data([0xE2, UInt8(v)]))
    }

    func sendPaddleSwap(_ swap: Bool) {
        writeRaw(Data([0xE3, swap ? 0x01 : 0x00]))
    }

    // MARK: - Incoming packet processing (Atom → Phone)

    private func processIncoming(_ chunk: Data) {
        accum.append(chunk)
        accum = parsePackets(accum)
        if accum.count > 64 {
            // Sanity reset on malformed stream
            accum.removeAll(keepingCapacity: false)
        }
    }

    /// Parses zero or more complete packets from the front of `buf`, returning the unconsumed tail.
    private func parsePackets(_ buf: Data) -> Data {
        var work = buf
        while let first = work.first {
            switch first {
            case 0xE0:
                if work.count < 5 { return work }
                let pkt = work.prefix(5)
                work = work.dropFirst(5)
                forwardSyncToPi(Data(pkt))
            case 0xE2:
                if work.count < 2 { return work }
                let w = Int(work[work.startIndex + 1])
                work = work.dropFirst(2)
                if (5...99).contains(w) {
                    wpm = w
                    onWpmChange?(w)
                }
            case 0x00, 0x01:
                if work.count >= 10, work[work.startIndex + 1] == 0x01 {
                    let pkt = work.prefix(10)
                    work = work.dropFirst(10)
                    handleKey(first == 0x01, raw: Data(pkt))
                } else if work.count >= 2, work[work.startIndex + 1] != 0x01 {
                    let synth = Data([first, 0x01, 0, 0, 0, 0, 0, 0, 0, 1])
                    work = work.dropFirst(1)
                    handleKey(first == 0x01, raw: synth)
                } else {
                    return work
                }
            default:
                work = work.dropFirst(1)
            }
        }
        return work
    }

    private func handleKey(_ isOn: Bool, raw: Data) {
        // サイドトーンは即時発火（Android式: Pi接続時 schedDelay=0）。
        // cwDelayMsはforwardKeyToPiのopTimeMs加算にのみ使用し、サイドトーンとは独立管理。
        keyOn = isOn
        rs.keyOn = isOn
        lastKeyAt = Date()

        let isFm = currentMode.uppercased().contains("FM")
        if !isFm {
            forwardKeyToPi(raw)
        }
        onKeyStateChange?(isOn, raw)
    }

    private func forwardKeyToPi(_ pkt: Data) {
        guard ensurePiSocket() else { return }
        let sock = piSocket
        var bytes = [UInt8](pkt)
        let delay = cwDelayMs
        if delay > 0 {
            // DualKeyはM5Stack時刻基準でopTimeMsを生成するのでpiClockOffsetMs変換不要。
            // delay分だけずらすだけ。
            var existingMs: Int64 = 0
            for i in 0..<8 { existingMs = (existingMs << 8) | Int64(bytes[2 + i]) }
            let fireMs = existingMs >= 2 ? existingMs + Int64(delay)
                                         : Int64(Date().timeIntervalSince1970 * 1000) + Int64(delay)
            for i in 0..<8 {
                bytes[2 + i] = UInt8((UInt64(bitPattern: fireMs) >> UInt64(56 - i * 8)) & 0xFF)
            }
        }
        // MSG_DONTWAIT: return EAGAIN immediately if socket send buffer is full
        // instead of blocking the main actor — prevents CW timing jitter under load
        let sent = bytes.withUnsafeBufferPointer { bp -> Int in
            send(sock, bp.baseAddress, bp.count, MSG_DONTWAIT)
        }
        if sent == bytes.count { keyForwarded += 1 }
    }

    // MARK: - SYNC tunnel: BLE 0xE0 → UDP → Pi → 0xE1 → BLE write back

    /// Send the 5-byte SYNC request to Pi:8889 and write the 9-byte response back to Atom.
    /// Uses a persistent BSD UDP socket (resolved once via getaddrinfo) to avoid mDNS
    /// rate-limiting and per-packet NWConnection setup overhead.
    private func forwardSyncToPi(_ pkt: Data) {
        syncForwarded += 1
        if !ensurePiSocket() {
            writeZeroSyncResponse()
            return
        }
        let sock = piSocket
        let pktBytes = [UInt8](pkt)

        let sent = pktBytes.withUnsafeBufferPointer { bp -> Int in
            send(sock, bp.baseAddress, bp.count, MSG_DONTWAIT)
        }
        if sent != pktBytes.count {
            writeZeroSyncResponse()
            return
        }
        piPendingRequests += 1
    }

    /// Ensure the persistent UDP socket to Pi is open. Returns true if usable.
    private func ensurePiSocket() -> Bool {
        if piSocket >= 0 && piSocketHost == piHost && piSocketPort == piPort {
            return true
        }
        closePiSocket()
        guard !piHost.isEmpty, piPort > 0 else { return false }

        // Resolve hostname via getaddrinfo (mDNS-aware) - one-shot, cached in the socket.
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_DGRAM
        hints.ai_protocol = IPPROTO_UDP
        var result: UnsafeMutablePointer<addrinfo>? = nil
        let rv = piHost.withCString { hostC in
            String(piPort).withCString { portC in
                getaddrinfo(hostC, portC, &hints, &result)
            }
        }
        guard rv == 0, let info = result else {
            lastError = "getaddrinfo(\(piHost)) failed: \(rv)"
            return false
        }
        defer { freeaddrinfo(info) }

        let sock = socket(info.pointee.ai_family, info.pointee.ai_socktype, info.pointee.ai_protocol)
        guard sock >= 0 else {
            lastError = "socket() failed: errno=\(errno)"
            return false
        }

        // Set recv timeout (300ms like Android)
        var tv = timeval(tv_sec: 0, tv_usec: 300_000)
        _ = setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        // connect() so we can use send/recv (kernel handles addr) — also faster
        if Darwin.connect(sock, info.pointee.ai_addr, info.pointee.ai_addrlen) != 0 {
            let err = errno
            Darwin.close(sock)
            lastError = "connect to \(piHost):\(piPort) failed: errno=\(err)"
            return false
        }

        piSocket = sock
        piSocketHost = piHost
        piSocketPort = piPort
        print("[BLE] Pi socket connected to \(piHost):\(piPort) (fd=\(sock))")

        // Spawn a single receive loop that demuxes responses to Atom.
        piRecvTask?.cancel()
        piRecvTask = Task.detached(priority: .userInitiated) { [weak self] in
            await self?.piRecvLoop(socket: sock)
        }
        return true
    }

    nonisolated private func piRecvLoop(socket sock: Int32) async {
        var buf = [UInt8](repeating: 0, count: 32)
        while !Task.isCancelled {
            let n = buf.withUnsafeMutableBufferPointer { bp -> Int in
                recv(sock, bp.baseAddress, bp.count, 0)
            }
            if n <= 0 {
                let e = errno
                if e == EBADF { break }
                // EAGAIN / EWOULDBLOCK from timeout — keep looping
                await MainActor.run { [weak self] in
                    // Drain a pending request; if many in flight, fall back to zero response.
                    self?.handlePiTimeout()
                }
                continue
            }
            let data = Data(buf[0..<n])
            await MainActor.run { [weak self] in
                self?.handlePiResponse(data)
            }
        }
    }

    private func handlePiResponse(_ data: Data) {
        if data.count == 9, data.first == 0xE1 {
            syncReceived += 1
            piPendingRequests = max(0, piPendingRequests - 1)
            // Android版と同様: Piから受け取った0xE1をそのままDualKeyに書き込む。
            // bytes[5-8] = M5Stack uptime ms → DualKeyがM5Stack時刻に同期する。
            writeRaw(data)
            // Android syncResponseCount==10 でsendWpmToM5()と同様
            syncResponseCount += 1
            if syncResponseCount == 10 { sendWpm(keeyerWpm) }
        }
    }

    private func handlePiTimeout() {
        if piPendingRequests > 0 {
            piPendingRequests -= 1
            writeZeroSyncResponse()
        }
    }

    private func closePiSocket() {
        piRecvTask?.cancel(); piRecvTask = nil
        if piSocket >= 0 {
            Darwin.close(piSocket)
            piSocket = -1
        }
        piSocketHost = ""
        piSocketPort = 0
        piPendingRequests = 0
        piClockOffsetMs = 0
    }

    private func writeZeroSyncResponse() {
        var resp = Data(count: 9)
        resp[0] = 0xE1
        // Pi未接続時はiOS時刻(low32bit)をbytes[5-8]に入れる。
        // DualKeyがiOS時刻に同期してopTimeMsを生成できるようにする。
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        resp[5] = UInt8((nowMs >> 24) & 0xFF)
        resp[6] = UInt8((nowMs >> 16) & 0xFF)
        resp[7] = UInt8((nowMs >>  8) & 0xFF)
        resp[8] = UInt8( nowMs        & 0xFF)
        writeRaw(resp)
        // Android版と同様: タイムアウト時もsyncResponseCountを増やし10回でWPMを送信
        syncResponseCount += 1
        if syncResponseCount == 10 { sendWpm(keeyerWpm) }
    }
}

// MARK: - CBCentralManagerDelegate

extension CwBleService: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor [weak self] in
            self?.bluetoothState = central.state
            if central.state != .poweredOn {
                self?.scanning = false
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let id = peripheral.identifier
        let advName = advertisementData[CBAdvertisementDataLocalNameKey] as? String

        // Primary filter: NUS service UUID in the advertising packet.
        // Some devices (e.g. RemoteKeyer-BLE) carry their name only in the scan response,
        // so peripheral.name / advName may be nil on the first didDiscover callback.
        // By checking the service UUID first we accept such devices immediately and let
        // the name resolve in a subsequent callback.
        let svcUUIDs = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID]) ?? []
        let advertisesNus = svcUUIDs.contains(Self.nusService)

        // Secondary filter: device name contains a keyer-related keyword.
        let rawName = peripheral.name ?? advName
        let nameLower = (rawName ?? "").lowercased()
        let keyerKeywords = ["dualkey", "remotekeyer", "keyer"]
        let nameMatches = keyerKeywords.contains { nameLower.contains($0) }

        guard advertisesNus || nameMatches else { return }

        // Use the resolved name, or a placeholder so NUS-advertising devices without a
        // name-yet (scan response pending) still appear in the list.
        let resolvedName = rawName ?? (advertisesNus ? "(NUS Device)" : nil)
        guard let name = resolvedName, !name.isEmpty else { return }

        let rssi = RSSI.intValue
        Task { @MainActor [weak self] in
            guard let self else { return }
            if self.foundPeripherals[id] == nil {
                let svcStrs = svcUUIDs.map { $0.uuidString }
                print("[BLE] discovered NEW: \(name) [\(id)] rssi=\(rssi) svc=\(svcStrs)")
            }
            self.foundPeripherals[id] = peripheral
            if let idx = self.discovered.firstIndex(where: { $0.id == id }) {
                self.discovered[idx] = .init(id: id, name: name, rssi: rssi)
            } else {
                self.discovered.append(.init(id: id, name: name, rssi: rssi))
            }
            self.discovered.sort { $0.rssi > $1.rssi }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.connecting = false
            peripheral.discoverServices([Self.nusService])
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                                    error: Error?) {
        Task { @MainActor [weak self] in
            self?.lastError = "BLE connect failed: \(error?.localizedDescription ?? "unknown")"
            self?.cleanup(reason: "didFailToConnect")
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                                    error: Error?) {
        Task { @MainActor [weak self] in
            if let err = error { self?.lastError = "BLE disconnected: \(err.localizedDescription)" }
            self?.cleanup(reason: "didDisconnect")
        }
    }
}

// MARK: - CBPeripheralDelegate

extension CwBleService: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let services = peripheral.services else {
            Task { @MainActor [weak self] in
                self?.lastError = "discoverServices error: \(error?.localizedDescription ?? "no services")"
                self?.disconnect()
            }
            return
        }
        var foundNus = false
        for svc in services where svc.uuid == Self.nusService {
            foundNus = true
            peripheral.discoverCharacteristics([Self.nusRxChar, Self.nusTxChar], for: svc)
        }
        if !foundNus {
            Task { @MainActor [weak self] in
                self?.lastError = "NUS service not found — not a compatible keyer"
                self?.disconnect()
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                                error: Error?) {
        guard error == nil, let chars = service.characteristics else { return }
        var rx: CBCharacteristic? = nil
        var tx: CBCharacteristic? = nil
        for c in chars {
            if c.uuid == Self.nusRxChar { rx = c }
            if c.uuid == Self.nusTxChar { tx = c }
        }
        guard let rx, let tx else {
            Task { @MainActor [weak self] in
                self?.lastError = "NUS characteristics missing"
                self?.disconnect()
            }
            return
        }
        peripheral.setNotifyValue(true, for: tx)
        // Task 1: handle connection state immediately so BLE RX is open for 0xE0 SYNC packets.
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.rxCharacteristic = rx
            self.connected = true
            self.sendPaddleSwap(self.paddleSwapped)
            self.onConnectionStateChange?(true)
        }
        // Task 2: sidetone starts after a 250ms yield (Task.sleep suspends, not blocks) so
        // the initial DualKey-BLE 0xE0 SYNC notifications can be processed before
        // engine.start() occupies the main actor for ~200ms. Without this delay,
        // the timing races with DualKey-BLE's SYNC response window (~300ms).
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 250_000_000)
            guard let self, self.connected, self.sidetoneEnabled else { return }
            self.startSidetone()
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                                error: Error?) {
        guard error == nil, let data = characteristic.value, !data.isEmpty else { return }
        // Immediate sidetone update on BLE queue — no main-actor hop needed for a Bool write.
        // At 20 WPM, dit = 60 ms; the old main-actor dispatch could add up to 16 ms of
        // jitter per SwiftUI render frame. Writing rs.keyOn here cuts sidetone latency to ~0.
        if data.count >= 2, (data[0] == 0x00 || data[0] == 0x01), data[1] == 0x01 {
            rs.keyOn = data[0] == 0x01
        }
        Task { @MainActor [weak self] in
            self?.processIncoming(data)
        }
    }
}
