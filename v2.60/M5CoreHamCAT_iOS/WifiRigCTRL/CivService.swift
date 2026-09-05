import Foundation
import AVFoundation
import Darwin

// MARK: - Thread-safe CI-V receive queue

private final class CivQueue {
    private var buf: [Data] = []
    private let lock = NSLock()
    private let sema = DispatchSemaphore(value: 0)

    func enqueue(_ d: Data) {
        lock.lock(); buf.append(d); lock.unlock()
        sema.signal()
    }

    /// Dequeue with timeout; returns nil on timeout.
    func dequeue(timeoutMs: Int) -> Data? {
        if timeoutMs <= 0 {
            lock.lock(); defer { lock.unlock() }
            return buf.isEmpty ? nil : buf.removeFirst()
        }
        let deadline = DispatchTime.now() + .milliseconds(timeoutMs)
        if sema.wait(timeout: deadline) == .timedOut { return nil }
        lock.lock(); defer { lock.unlock() }
        return buf.isEmpty ? nil : buf.removeFirst()
    }

    func clear() {
        lock.lock(); buf.removeAll(); lock.unlock()
    }
    var isEmpty: Bool { lock.lock(); defer { lock.unlock() }; return buf.isEmpty }
}

// MARK: - CivStatus

struct CivStatus {
    var freq: Int64
    var signal: Float?
    var tx: Bool
    var mode: CivMode?
    var power: Float?
    var sql: Float?
    var bkIn: Bool?
}

struct CivMode {
    var name: String
    var filterWidth: Int
}

// MARK: - CivService

/// RS-BA1 compatible UDP direct WiFi connection to ICOM IC-705/IC-7300/etc.
/// Swift port of Android CivTcpService.kt.
/// All public blocking methods (connect, pollStatus, getFrequency, …) must be
/// called from a background Task/thread — they never block the main actor.
final class CivService: @unchecked Sendable {

    // Explicit nonisolated init prevents @MainActor inference on the synthesized init.
    nonisolated init() {}

    // MARK: - Observable state (always updated via postMain)
    private(set) var isConnected: Bool = false
    private(set) var civStreamOpened: Bool = false
    var lastError: String = ""
    /// Called on background thread at each connection step. Dispatch to main if updating UI.
    var onConnectStep: ((String) -> Void)?
    var civAddress: Int = 0xA4
    var spkEnabled: Bool = false {
        didSet {
            if spkEnabled {
                #if os(iOS)
                // Re-apply speaker override each time SPK is enabled.
                // .measurement mode may reset the route override after session interruptions.
                try? AVAudioSession.sharedInstance().overrideOutputAudioPort(.speaker)
                #endif
                tryPlayAudio()
            } else {
                audioPause()
            }
        }
    }
    var micGain: Float = 1.0

    // MARK: - RS-BA1 constants
    private static let CONTROL_SIZE  = 16
    private static let PING_SIZE     = 21
    private static let TOKEN_SIZE    = 64
    private static let LOGIN_SIZE    = 128
    private static let LOGIN_RSP_SZ  = 96
    private static let STATUS_SIZE   = 80
    private static let CONNINFO_SIZE = 144
    private static let OPENCLOSE_SZ  = 22
    private static let AUDIO_HDR     = 24
    private static let TX_JITTER_MS: UInt32 = 200
    private static let TX_CACHE_SZ   = 128
    static let ctrlAddress           = 0xE0

    // MARK: - Network sockets (touch only from owner threads)
    private var ctrlFd: Int32 = -1
    private var civFd:  Int32 = -1
    private var audioFd: Int32 = -1
    private var remoteIP: String = ""
    private var civPort1: Int = 50001
    private var civPort2: Int = 50002
    private var civPort3: Int = 50003

    // MARK: - RS-BA1 session IDs / sequences
    private var ctrlMyId:     UInt32 = 0
    private var civMyId:      UInt32 = 0
    private var audioMyId:    UInt32 = 0
    private var ctrlRemoteId: UInt32 = 0
    private var civRemoteId:  UInt32 = 0
    private var audioRemoteId: UInt32 = 0
    private var token:        UInt32 = 0
    private var tokRequest:   Int    = 0
    private var authSeq:      Int    = 0
    private var ctrlSeq:      Int    = 0
    private var civPktSeq:    Int    = 0
    private var civSendSeqB:  Int    = 0
    private var audioPktSeq:  Int    = 0
    private var audioTxSeq:   Int    = 0

    // MARK: - Background threads
    private var ctrlRxActive = false
    private var civRxActive  = false
    private var audioRxActive = false
    private var ctrlRxThread: Thread?
    private var civRxThread:  Thread?
    private var audioRxThread: Thread?

    // MARK: - CI-V receive queue (civRxThread → exchange)
    private let civRxQueue = CivQueue()

    // MARK: - exchange() mutex (one CI-V query at a time)
    private let exchangeLock = NSLock()

    // MARK: - CI-V broadcast cache: key = (cmd<<8)|sub → (body, ts)
    private var civCache: [Int: (body: Data, ts: TimeInterval)] = [:]

    // MARK: - Poll / watchdog state
    private var pollModeCount  = 0
    private var pollFailCount  = 0
    private var lastKnownTx    = false
    private var transcieveEnabled = false
    private var pttOffPending  = false
    private var pttIntent      = false

    private var lastCtrlPingMs:  TimeInterval = 0
    private var lastAudioPingMs: TimeInterval = 0
    private var lastAudioDataMs: TimeInterval = 0
    private var lastTokenRenMs:  TimeInterval = 0
    private var lastOpenCloseMs: TimeInterval = 0
    private var maintenanceMs:   TimeInterval = 0
    private var mainRetryMs:     TimeInterval = 0
    private var lastRenewalMs:   TimeInterval = 0
    private var lastTxAudioMs:   TimeInterval = 0

    // MARK: - Pending CI-V commands (dead window queue)
    private var pendingFreqHz: Int64?        = nil
    private var pendingMode:   (String, Int)? = nil

    // MARK: - Saved for stream renewal
    private var savedUsername:  String = ""
    private var savedMacAddr:   Data?  = nil
    private var savedRadioName: Data?  = nil
    private var seamlessReconnect = false

    // MARK: - Stale session (persisted across app launches to enable clean close)

    private static let sk = "civ_stale_"  // UserDefaults key prefix

    /// Save current session IDs so the next app launch can send a proper disconnect
    /// before the new connection attempt — preventing IC-705 from rejecting AYT
    /// because it still considers the previous session active.
    func saveStaleSession() {
        let d = UserDefaults.standard
        d.set(Int(ctrlMyId),     forKey: Self.sk + "ctrlMyId")
        d.set(Int(ctrlRemoteId), forKey: Self.sk + "ctrlRemoteId")
        d.set(Int(token),        forKey: Self.sk + "token")
        d.set(tokRequest,        forKey: Self.sk + "tokRequest")
        d.set(authSeq,           forKey: Self.sk + "authSeq")
        d.set(ctrlSeq,           forKey: Self.sk + "ctrlSeq")
        d.set(Int(civMyId),      forKey: Self.sk + "civMyId")
        d.set(Int(civRemoteId),  forKey: Self.sk + "civRemoteId")
        d.set(civPktSeq,         forKey: Self.sk + "civPktSeq")
        d.set(civSendSeqB,       forKey: Self.sk + "civSendSeqB")
        d.set(remoteIP,          forKey: Self.sk + "remoteIP")
        d.set(civPort1,          forKey: Self.sk + "port1")
        d.set(civPort2,          forKey: Self.sk + "port2")
    }

    func clearStaleSession() {
        let d = UserDefaults.standard
        for k in ["ctrlMyId","ctrlRemoteId","token","tokRequest","authSeq","ctrlSeq",
                  "civMyId","civRemoteId","civPktSeq","civSendSeqB","remoteIP","port1","port2"] {
            d.removeObject(forKey: Self.sk + k)
        }
    }

    /// Send RS-BA1 disconnect packets using the saved stale session IDs so the IC-705
    /// releases its previous session before we attempt a new AYT handshake.
    private func sendStaleDisconnect() {
        let ud = UserDefaults.standard
        let sCtrlMyId    = UInt32(ud.integer(forKey: Self.sk + "ctrlMyId")    & 0xFFFFFFFF)
        let sCtrlRemId   = UInt32(ud.integer(forKey: Self.sk + "ctrlRemoteId") & 0xFFFFFFFF)
        let sToken       = UInt32(ud.integer(forKey: Self.sk + "token")        & 0xFFFFFFFF)
        let sTokReq      = ud.integer(forKey: Self.sk + "tokRequest")
        let sAuthSeq     = ud.integer(forKey: Self.sk + "authSeq")
        let sCtrlSeq     = ud.integer(forKey: Self.sk + "ctrlSeq")
        let sCivMyId     = UInt32(ud.integer(forKey: Self.sk + "civMyId")      & 0xFFFFFFFF)
        let sCivRemId    = UInt32(ud.integer(forKey: Self.sk + "civRemoteId")  & 0xFFFFFFFF)
        let sCivPktSeq   = ud.integer(forKey: Self.sk + "civPktSeq")
        let sCivSendSeqB = ud.integer(forKey: Self.sk + "civSendSeqB")
        let sIP          = ud.string(forKey: Self.sk + "remoteIP") ?? ""
        let sPort1       = ud.integer(forKey: Self.sk + "port1")
        let sPort2       = ud.integer(forKey: Self.sk + "port2")

        guard sCtrlMyId != 0, sCtrlRemId != 0, !sIP.isEmpty, sPort1 > 0 else { return }
        NSLog("[CivService] sendStaleDisconnect → \(sIP):\(sPort1) ctrlMyId=0x\(String(sCtrlMyId, radix: 16))")

        let fd = openUdpSocket(localPort: 0)
        guard fd >= 0 else { return }
        defer { Darwin.close(fd) }

        // 1. Token delete (type=0x00, magic=0x01)
        if sToken != 0 {
            var p = Data(count: Self.TOKEN_SIZE)
            putLE32(&p, 0,    UInt32(Self.TOKEN_SIZE))
            putLE16(&p, 4,    0x00)
            putLE16(&p, 6,    (sCtrlSeq + 1) & 0xFFFF)
            putLE32(&p, 8,    sCtrlMyId)
            putLE32(&p, 12,   sCtrlRemId)
            putBE32(&p, 16,   UInt32(Self.TOKEN_SIZE - 0x10))
            p[20] = 0x01; p[21] = 0x01                   // magic=delete
            putBE16(&p, 22,   (sAuthSeq + 1) & 0xFFFF)
            putLE16(&p, 26,   sTokReq)
            putLE32(&p, 28,   sToken)
            putBE16(&p, 0x24, 0x0798)
            _ = send2(fd, p, ip: sIP, port: sPort1)
        }

        // 2. CIV close (OpenClose open=false)
        if sCivMyId != 0, sCivRemId != 0, sPort2 > 0 {
            let fd2 = openUdpSocket(localPort: 0)
            if fd2 >= 0 {
                var p = Data(count: Self.OPENCLOSE_SZ)
                putLE32(&p, 0,  UInt32(Self.OPENCLOSE_SZ))
                putLE16(&p, 4,  0x00)
                putLE16(&p, 6,  (sCivPktSeq + 1) & 0xFFFF)
                putLE32(&p, 8,  sCivMyId)
                putLE32(&p, 12, sCivRemId)
                putLE16(&p, 16, 0x01c0)
                putBE16(&p, 19, (sCivSendSeqB + 1) & 0xFFFF)
                p[21] = 0x00    // close
                _ = send2(fd2, p, ip: sIP, port: sPort2)
                Darwin.close(fd2)
            }
        }

        Thread.sleep(forTimeInterval: 0.05)

        // 3. Ctrl disconnect (type=0x05)
        var p = Data(count: Self.CONTROL_SIZE)
        putLE32(&p, 0,  UInt32(Self.CONTROL_SIZE))
        putLE16(&p, 4,  0x05)
        putLE16(&p, 6,  (sCtrlSeq + 2) & 0xFFFF)
        putLE32(&p, 8,  sCtrlMyId)
        putLE32(&p, 12, sCtrlRemId)
        _ = send2(fd, p, ip: sIP, port: sPort1)

        Thread.sleep(forTimeInterval: 0.15)
        NSLog("[CivService] sendStaleDisconnect done")
    }

    // MARK: - Audio RX debug counters
    private var _audioDbgCount = 0
    private var _audioDbgUnknown = 0

    // MARK: - TX retransmit cache (audioRxThread reads, txAudio writes)
    private let txCacheLock = NSLock()
    private var txCache: [(seq: Int, pkt: Data)] = []  // ordered ring

    // MARK: - Audio RX (SPK)
    private var audioEngine: AVAudioEngine?
    private var audioPlayer: AVAudioPlayerNode?
    private var audioFormat: AVAudioFormat?

    // MARK: - Audio TX (mic → IC-705)
    private var txAudioActive = false
    private var txAudioThread: Thread?
    private var txAudioRunning = false
    // Mic tap state — shared between initAudioPlayer()'s tap closure and runTxAudio()'s send loop
    private var txAccum  = [Float]()
    private let txAccLock = NSLock()
    // FM-CW tone generation: when fmCwToneHz > 0, runTxAudio() sends a sine wave instead of mic input
    var fmCwToneHz: Double = 0.0
    private var fmCwSinePhase: Double = 0.0  // maintained exclusively by runTxAudio() thread
    private let txSema   = DispatchSemaphore(value: 0)
    private var txConverter: AVAudioConverter?
    private static let txTargetFmt = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                                    sampleRate: 8000, channels: 1, interleaved: false)!

    // MARK: - Helpers
    private func nowMs() -> TimeInterval { Date().timeIntervalSince1970 * 1000 }
    private func postMain(_ f: @escaping () -> Void) { DispatchQueue.main.async(execute: f) }

    // ─────────────────────────────────────────────
    // MARK: - Passcode cipher (RS-BA1 auth)
    // ─────────────────────────────────────────────

    private static let PASSCODE: [Int] = [
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0x47,0x5d,0x4c,0x42,0x66,0x20,0x23,0x46,0x4e,0x57,0x45,0x3d,0x67,0x76,0x60,0x41,
        0x62,0x39,0x59,0x2d,0x68,0x7e,0x7c,0x65,0x7d,0x49,0x29,0x72,0x73,0x78,0x21,0x6e,
        0x5a,0x5e,0x4a,0x3e,0x71,0x2c,0x2a,0x54,0x3c,0x3a,0x63,0x4f,0x43,0x75,0x27,0x79,
        0x5b,0x35,0x70,0x48,0x6b,0x56,0x6f,0x34,0x32,0x6c,0x30,0x61,0x6d,0x7b,0x2f,0x4b,
        0x64,0x38,0x2b,0x2e,0x50,0x40,0x3f,0x55,0x33,0x37,0x25,0x77,0x24,0x26,0x74,0x6a,
        0x28,0x53,0x4d,0x69,0x22,0x5c,0x44,0x31,0x36,0x58,0x3b,0x7a,0x51,0x5f,0x52,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0
    ]

    private func passcode(_ input: String) -> Data {
        let bytes = Array(input.utf8)
        var out = Data(count: bytes.count)
        for (i, c) in bytes.enumerated() {
            var p = Int(c) + i
            if p > 126 { p = 32 + p % 127 }
            out[i] = UInt8(Self.PASSCODE[max(0, min(127, p))])
        }
        return out
    }

    // ─────────────────────────────────────────────
    // MARK: - Endian helpers
    // ─────────────────────────────────────────────

    private func putLE32(_ p: inout Data, _ off: Int, _ v: UInt32) {
        p[off]   = UInt8(v         & 0xFF)
        p[off+1] = UInt8((v >>  8) & 0xFF)
        p[off+2] = UInt8((v >> 16) & 0xFF)
        p[off+3] = UInt8((v >> 24) & 0xFF)
    }
    private func putLE16(_ p: inout Data, _ off: Int, _ v: Int) {
        p[off]   = UInt8(v        & 0xFF)
        p[off+1] = UInt8((v >> 8) & 0xFF)
    }
    private func putBE16(_ p: inout Data, _ off: Int, _ v: Int) {
        p[off]   = UInt8((v >> 8) & 0xFF)
        p[off+1] = UInt8(v        & 0xFF)
    }
    private func putBE32(_ p: inout Data, _ off: Int, _ v: UInt32) {
        p[off]   = UInt8((v >> 24) & 0xFF)
        p[off+1] = UInt8((v >> 16) & 0xFF)
        p[off+2] = UInt8((v >>  8) & 0xFF)
        p[off+3] = UInt8(v         & 0xFF)
    }
    private func getLE32(_ p: Data, _ off: Int) -> UInt32 {
        UInt32(p[off]) | (UInt32(p[off+1]) << 8) |
        (UInt32(p[off+2]) << 16) | (UInt32(p[off+3]) << 24)
    }
    private func getLE16(_ p: Data, _ off: Int) -> Int {
        Int(p[off]) | (Int(p[off+1]) << 8)
    }
    private func getBE16(_ p: Data, _ off: Int) -> Int {
        (Int(p[off]) << 8) | Int(p[off+1])
    }

    // ─────────────────────────────────────────────
    // MARK: - Packet builders
    // ─────────────────────────────────────────────

    private func fillHeader(_ p: inout Data, len: Int, type: Int, seq: Int,
                             sentId: UInt32, rcvdId: UInt32) {
        putLE32(&p, 0, UInt32(len))
        putLE16(&p, 4, type)
        putLE16(&p, 6, seq & 0xFFFF)
        putLE32(&p, 8, sentId)
        putLE32(&p, 12, rcvdId)
    }

    private func makeCtrl(type: Int, seq: Int, sentId: UInt32, rcvdId: UInt32) -> Data {
        var p = Data(count: Self.CONTROL_SIZE)
        fillHeader(&p, len: Self.CONTROL_SIZE, type: type, seq: seq, sentId: sentId, rcvdId: rcvdId)
        return p
    }

    private func makeLogin(seq: Int, innerSeq: Int, tokReq: Int,
                            username: String, password: String) -> Data {
        var p = Data(count: Self.LOGIN_SIZE)
        fillHeader(&p, len: Self.LOGIN_SIZE, type: 0x00, seq: seq, sentId: ctrlMyId, rcvdId: ctrlRemoteId)
        putBE32(&p, 16, UInt32(Self.LOGIN_SIZE - 0x10))
        p[20] = 0x01; p[21] = 0x00
        putBE16(&p, 22, innerSeq)
        putLE16(&p, 26, tokReq)
        let un = passcode(username); un.withUnsafeBytes { src in
            p.withUnsafeMutableBytes { dst in
                let n = min(src.count, 16)
                dst.baseAddress!.advanced(by: 0x40).copyMemory(from: src.baseAddress!, byteCount: n)
            }
        }
        let pw = passcode(password); pw.withUnsafeBytes { src in
            p.withUnsafeMutableBytes { dst in
                let n = min(src.count, 16)
                dst.baseAddress!.advanced(by: 0x50).copyMemory(from: src.baseAddress!, byteCount: n)
            }
        }
        let os = "iOS".data(using: .ascii)!; os.withUnsafeBytes { src in
            p.withUnsafeMutableBytes { dst in
                let n = min(src.count, 16)
                dst.baseAddress!.advanced(by: 0x60).copyMemory(from: src.baseAddress!, byteCount: n)
            }
        }
        return p
    }

    private func makeToken(seq: Int, innerSeq: Int, tokReq: Int,
                            tokenVal: UInt32, magic: Int) -> Data {
        var p = Data(count: Self.TOKEN_SIZE)
        fillHeader(&p, len: Self.TOKEN_SIZE, type: 0x00, seq: seq, sentId: ctrlMyId, rcvdId: ctrlRemoteId)
        putBE32(&p, 16, UInt32(Self.TOKEN_SIZE - 0x10))
        p[20] = 0x01; p[21] = UInt8(magic)
        putBE16(&p, 22, innerSeq)
        putLE16(&p, 26, tokReq)
        putLE32(&p, 28, tokenVal)
        putBE16(&p, 0x24, 0x0798)
        return p
    }

    private func makeRequestStream(seq: Int, innerSeq: Int, tokReq: Int, tokenVal: UInt32,
                                    civLocalPort: Int, audioLocalPort: Int,
                                    username: String, macAddr: Data?, radioName: Data?) -> Data {
        var p = Data(count: Self.CONNINFO_SIZE)
        fillHeader(&p, len: Self.CONNINFO_SIZE, type: 0x00, seq: seq, sentId: ctrlMyId, rcvdId: ctrlRemoteId)
        putBE32(&p, 16, UInt32(Self.CONNINFO_SIZE - 0x10))
        p[20] = 0x01; p[21] = 0x03
        putBE16(&p, 22, innerSeq)
        putLE16(&p, 26, tokReq)
        putLE32(&p, 28, tokenVal)
        putLE16(&p, 0x27, 0x8010)
        if let mac = macAddr, mac.count >= 6 {
            mac.withUnsafeBytes { src in p.withUnsafeMutableBytes { dst in
                dst.baseAddress!.advanced(by: 0x2a).copyMemory(from: src.baseAddress!, byteCount: 6) }}
        }
        if let rn = radioName {
            rn.withUnsafeBytes { src in p.withUnsafeMutableBytes { dst in
                let n = min(src.count, 32)
                dst.baseAddress!.advanced(by: 0x40).copyMemory(from: src.baseAddress!, byteCount: n) }}
        }
        let un = passcode(username); un.withUnsafeBytes { src in p.withUnsafeMutableBytes { dst in
            let n = min(src.count, 16)
            dst.baseAddress!.advanced(by: 0x60).copyMemory(from: src.baseAddress!, byteCount: n) }}
        p[0x70] = 0x01; p[0x71] = 0x01; p[0x72] = 0x04; p[0x73] = 0x04
        putBE32(&p, 0x74, 8000)   // RX sample rate 8kHz
        putBE32(&p, 0x78, 8000)   // TX sample rate 8kHz
        putBE32(&p, 0x7c, UInt32(civLocalPort))
        putBE32(&p, 0x80, UInt32(audioLocalPort))
        putBE32(&p, 0x84, Self.TX_JITTER_MS)
        p[0x88] = 0x01
        return p
    }

    private func makeOpenClose(seq: Int, sendSeq: Int, open: Bool, sentId: UInt32, rcvdId: UInt32) -> Data {
        var p = Data(count: Self.OPENCLOSE_SZ)
        fillHeader(&p, len: Self.OPENCLOSE_SZ, type: 0x00, seq: seq, sentId: sentId, rcvdId: rcvdId)
        putLE16(&p, 16, 0x01c0)
        putBE16(&p, 19, sendSeq)
        p[21] = open ? 0x04 : 0x00
        return p
    }

    private func makeCivData(seq: Int, sendSeq: Int, civData: Data) -> Data {
        let total = Self.PING_SIZE + civData.count
        var p = Data(count: total)
        fillHeader(&p, len: total, type: 0x00, seq: seq, sentId: civMyId, rcvdId: civRemoteId)
        p[16] = 0xc1
        putLE16(&p, 17, civData.count)
        putBE16(&p, 19, sendSeq)
        civData.withUnsafeBytes { src in p.withUnsafeMutableBytes { dst in
            dst.baseAddress!.advanced(by: Self.PING_SIZE).copyMemory(from: src.baseAddress!, byteCount: civData.count) }}
        return p
    }

    private func makeTxAudioPacket(_ audio: Data) -> Data {
        audioPktSeq = (audioPktSeq + 1) & 0xFFFF
        audioTxSeq  = (audioTxSeq  + 1) & 0xFFFF
        let total = Self.AUDIO_HDR + audio.count
        var p = Data(count: total)
        fillHeader(&p, len: total, type: 0x00, seq: audioPktSeq, sentId: audioMyId, rcvdId: audioRemoteId)
        p[16] = 0x80; p[17] = 0x00
        putBE16(&p, 18, audioTxSeq)
        putBE16(&p, 22, audio.count)
        audio.withUnsafeBytes { src in p.withUnsafeMutableBytes { dst in
            dst.baseAddress!.advanced(by: Self.AUDIO_HDR).copyMemory(from: src.baseAddress!, byteCount: audio.count) }}
        // Cache for retransmit
        txCacheLock.lock()
        txCache.append((seq: audioPktSeq, pkt: p))
        if txCache.count > Self.TX_CACHE_SZ { txCache.removeFirst() }
        txCacheLock.unlock()
        return p
    }

    // ─────────────────────────────────────────────
    // MARK: - Socket helpers
    // ─────────────────────────────────────────────

    private func makeSockaddr(_ ip: String, _ port: Int) -> sockaddr_in {
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port   = CFSwapInt16HostToBig(UInt16(port))
        inet_pton(AF_INET, ip, &addr.sin_addr)
        return addr
    }

    /// Open a UDP socket, try to bind to `localPort` (SO_REUSEADDR), fall back to random port.
    private func openUdpSocket(localPort: Int) -> Int32 {
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return -1 }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port   = CFSwapInt16HostToBig(UInt16(localPort))
        addr.sin_addr.s_addr = INADDR_ANY
        let r = withUnsafeBytes(of: &addr) { ptr in
            Darwin.bind(fd, ptr.baseAddress!.assumingMemoryBound(to: sockaddr.self),
                        socklen_t(MemoryLayout<sockaddr_in>.size))
        }
        if r != 0 && localPort != 0 {
            // Bind to random port
            addr.sin_port = 0
            withUnsafeBytes(of: &addr) { ptr in
                _ = Darwin.bind(fd, ptr.baseAddress!.assumingMemoryBound(to: sockaddr.self),
                                socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        return fd
    }

    private func localPort(of fd: Int32) -> Int {
        var addr = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        withUnsafeMutableBytes(of: &addr) { ptr in
            _ = getsockname(fd, ptr.baseAddress!.assumingMemoryBound(to: sockaddr.self), &len)
        }
        return Int(CFSwapInt16BigToHost(addr.sin_port))
    }

    private func computeMyId(localIpStr: String, port: Int) -> UInt32 {
        var addr = in_addr()
        inet_pton(AF_INET, localIpStr, &addr)
        let ip = addr.s_addr.bigEndian
        let ip3 = (ip >> 8) & 0xFF
        let ip4 =  ip       & 0xFF
        return (ip3 << 24) | (ip4 << 16) | UInt32(port & 0xFFFF)
    }

    private func getLocalIP(to host: String, port: Int) -> String? {
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return nil }
        defer { Darwin.close(fd) }
        var dst = makeSockaddr(host, port)
        let r = withUnsafeBytes(of: &dst) { ptr in
            Darwin.connect(fd, ptr.baseAddress!.assumingMemoryBound(to: sockaddr.self),
                           socklen_t(MemoryLayout<sockaddr_in>.size))
        }
        guard r == 0 else { return nil }
        var local = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        withUnsafeMutableBytes(of: &local) { ptr in
            _ = getsockname(fd, ptr.baseAddress!.assumingMemoryBound(to: sockaddr.self), &len)
        }
        var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        withUnsafeBytes(of: &local.sin_addr) { ptr in
            _ = inet_ntop(AF_INET, ptr.baseAddress!, &buf, socklen_t(buf.count))
        }
        return String(cString: buf)
    }

    @discardableResult
    private func send(_ fd: Int32, _ data: Data, ip: String, port: Int) -> Int {
        var dst = makeSockaddr(ip, port)
        return data.withUnsafeBytes { raw in
            withUnsafeBytes(of: &dst) { dstPtr in
                Int(sendto(fd, raw.baseAddress!, data.count, 0,
                           dstPtr.baseAddress!.assumingMemoryBound(to: sockaddr.self),
                           socklen_t(MemoryLayout<sockaddr_in>.size)))
            }
        }
    }

    /// Returns errno string if sendto fails, or "ok" on success.
    private func send2(_ fd: Int32, _ data: Data, ip: String, port: Int) -> String {
        let n = send(fd, data, ip: ip, port: port)
        return n >= 0 ? "ok" : "err\(errno)"
    }

    private func sendCtrl(_ data: Data) { if ctrlFd >= 0  { send(ctrlFd,  data, ip: remoteIP, port: civPort1) } }
    private func sendCiv(_ data: Data)  { if civFd  >= 0  { send(civFd,   data, ip: remoteIP, port: civPort2) } }
    private func sendAudio(_ data: Data) { if audioFd >= 0 { send(audioFd, data, ip: remoteIP, port: civPort3) } }

    private func recvFrom(_ fd: Int32, timeoutMs: Int) -> Data? {
        guard fd >= 0 else { return nil }
        // poll() is more reliable than SO_RCVTIMEO for UDP on iOS (SO_RCVTIMEO may be ignored)
        var pfd = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
        guard poll(&pfd, 1, Int32(timeoutMs)) > 0 else { return nil }
        var buf = Data(count: 2048)
        let n = buf.withUnsafeMutableBytes { Darwin.recv(fd, $0.baseAddress!, $0.count, 0) }
        guard n > 0 else { return nil }
        return buf.prefix(n)
    }

    private func recvCtrl(_ ms: Int) -> Data? { recvFrom(ctrlFd, timeoutMs: ms) }
    private func recvCiv(_ ms: Int) -> Data? {
        civRxActive ? civRxQueue.dequeue(timeoutMs: ms) : recvFrom(civFd, timeoutMs: ms)
    }

    // ─────────────────────────────────────────────
    // MARK: - Ping/pong handlers
    // ─────────────────────────────────────────────

    private func isPing(_ r: Data) -> Bool {
        r.count == Self.PING_SIZE && getLE16(r, 4) == 0x07 && r[16] == 0x00
    }

    private func handleCtrlPing(_ r: Data) {
        guard r.count == Self.PING_SIZE, getLE16(r, 4) == 0x07, r[16] == 0x00 else { return }
        lastCtrlPingMs = nowMs()
        let sid = getLE32(r, 8)
        if ctrlRemoteId != 0 && sid != ctrlRemoteId { ctrlRemoteId = sid }
        var pong = Data(count: Self.PING_SIZE)
        putLE32(&pong, 0, UInt32(Self.PING_SIZE))
        putLE16(&pong, 4, 0x07); putLE16(&pong, 6, getLE16(r, 6))
        putLE32(&pong, 8, ctrlMyId); putLE32(&pong, 12, ctrlRemoteId)
        pong[16] = 0x01
        let tail = r[17..<21]
        pong.replaceSubrange(17..<21, with: tail)
        sendCtrl(pong)
    }

    @discardableResult
    private func handleCivPing(_ r: Data) -> Bool {
        guard r.count == Self.PING_SIZE, getLE16(r, 4) == 0x07, r[16] == 0x00 else { return false }
        let sid = getLE32(r, 8)
        if civRemoteId == 0 {
            civRemoteId = sid
            NSLog("[CivService] civRx: civRemoteId learned from ping=0x\(String(civRemoteId, radix: 16))")
        } else if sid != civRemoteId {
            NSLog("[CivService] civRx: session rotate 0x\(String(civRemoteId, radix: 16)) → 0x\(String(sid, radix: 16))")
            civRemoteId = sid
        }
        var pong = Data(count: Self.PING_SIZE)
        putLE32(&pong, 0, UInt32(Self.PING_SIZE))
        putLE16(&pong, 4, 0x07); putLE16(&pong, 6, getLE16(r, 6))
        putLE32(&pong, 8, civMyId); putLE32(&pong, 12, civRemoteId)
        pong[16] = 0x01
        let tail = r[17..<21]; pong.replaceSubrange(17..<21, with: tail)
        sendCiv(pong); return true
    }

    @discardableResult
    private func handleAudioPing(_ r: Data) -> Bool {
        guard r.count == Self.PING_SIZE, getLE16(r, 4) == 0x07, r[16] == 0x00 else { return false }
        lastAudioPingMs = nowMs()
        let sid = getLE32(r, 8)
        if audioRemoteId == 0 || sid != audioRemoteId { audioRemoteId = sid }
        var pong = Data(count: Self.PING_SIZE)
        putLE32(&pong, 0, UInt32(Self.PING_SIZE))
        putLE16(&pong, 4, 0x07); putLE16(&pong, 6, getLE16(r, 6))
        putLE32(&pong, 8, audioMyId); putLE32(&pong, 12, audioRemoteId)
        pong[16] = 0x01
        let tail = r[17..<21]; pong.replaceSubrange(17..<21, with: tail)
        sendAudio(pong); return true
    }

    private func drainCtrlPings() {
        for _ in 0..<30 {
            guard let r = recvCtrl(5), r.count == Self.PING_SIZE, getLE16(r, 4) == 0x07 else { continue }
            handleCtrlPing(r)
        }
    }

    // ─────────────────────────────────────────────
    // MARK: - Background receive threads
    // ─────────────────────────────────────────────

    private func startCtrlRxThread() {
        ctrlRxActive = true
        let t = Thread { [weak self] in self?.runCtrlRx() }
        t.name = "civCtrlRx"; t.qualityOfService = .userInitiated; t.start()
        ctrlRxThread = t
    }

    private func runCtrlRx() {
        var lastKeepalive = nowMs()
        while ctrlRxActive {
            let r = recvCtrl(200)
            let now = nowMs()
            if now - lastKeepalive > 2000, ctrlRemoteId != 0 {
                ctrlSeq += 1
                sendCtrl(makeCtrl(type: 0x00, seq: ctrlSeq, sentId: ctrlMyId, rcvdId: ctrlRemoteId))
                lastKeepalive = now
            }
            // Token renewal every 60s
            let ltr = lastTokenRenMs
            if ltr > 0, now - ltr > 60_000, token != 0, ctrlRemoteId != 0 {
                ctrlSeq += 1; authSeq += 1
                sendCtrl(makeToken(seq: ctrlSeq, innerSeq: authSeq, tokReq: tokRequest, tokenVal: token, magic: 0x05))
                lastTokenRenMs = now
            }
            guard let r else { continue }
            if r.count == Self.PING_SIZE, getLE16(r, 4) == 0x07, r[16] == 0x00 {
                handleCtrlPing(r)
            }
            // Token renewal response
            if r.count == Self.TOKEN_SIZE, getLE16(r, 4) != 0x01, r[20] == 0x02, r[21] == 0x05 {
                handleTokenRenewalResponse(r)
            }
        }
    }

    private func handleTokenRenewalResponse(_ r: Data) {
        lastTokenRenMs = nowMs()
        let err = getLE32(r, 0x30)
        if err == 0 { return }
        let newTokReq = getLE16(r, 0x1a)
        let newToken  = getLE32(r, 0x1c)
        if newToken != 0 { tokRequest = newTokReq; token = newToken }
        let civPt   = localPort(of: civFd)
        let audioPt = localPort(of: audioFd)
        guard civPt > 0, audioPt > 0, !savedUsername.isEmpty else { return }
        ctrlSeq += 1; authSeq += 1
        sendCtrl(makeRequestStream(seq: ctrlSeq, innerSeq: authSeq, tokReq: tokRequest,
                                    tokenVal: token, civLocalPort: civPt, audioLocalPort: audioPt,
                                    username: savedUsername, macAddr: savedMacAddr, radioName: savedRadioName))
    }

    private func startCivRxThread() {
        civRxActive = false
        Thread.sleep(forTimeInterval: 0.3)
        civRxQueue.clear()
        civRxActive = true
        let t = Thread { [weak self] in self?.runCivRx() }
        t.name = "civCivRx"; t.qualityOfService = .userInitiated; t.start()
        civRxThread = t
    }

    private func runCivRx() {
        var lastKeepalive = nowMs()
        while civRxActive {
            guard let r = recvFrom(civFd, timeoutMs: 200) else {
                let now = nowMs()
                if now - lastKeepalive > 2000, civRemoteId != 0 {
                    civPktSeq = (civPktSeq + 1) & 0xFFFF
                    sendCiv(makeCtrl(type: 0x00, seq: civPktSeq, sentId: civMyId, rcvdId: civRemoteId))
                    lastKeepalive = now
                }
                continue
            }
            if handleCivPing(r) { continue }
            civRxQueue.enqueue(r)
        }
    }

    private func startAudioRxThread() {
        audioRxActive = true
        let t = Thread { [weak self] in self?.runAudioRx() }
        t.name = "civAudioRx"; t.qualityOfService = .userInitiated; t.start()
        audioRxThread = t
    }

    private func runAudioRx() {
        var lastKeepalive = nowMs()
        while audioRxActive {
            guard let r = recvFrom(audioFd, timeoutMs: 100) else {
                let now = nowMs()
                if now - lastKeepalive > 2000, audioRemoteId != 0, now - lastTxAudioMs > 2000 {
                    audioPktSeq = (audioPktSeq + 1) & 0xFFFF
                    sendAudio(makeCtrl(type: 0x00, seq: audioPktSeq, sentId: audioMyId, rcvdId: audioRemoteId))
                    lastKeepalive = now
                }
                continue
            }
            let rType = r.count >= 6 ? getLE16(r, 4) : -1
            // Audio ping
            if r.count == Self.PING_SIZE, rType == 0x07, r[16] == 0x00 {
                handleAudioPing(r); continue
            }
            // Audio PCM data (byte[16]=0x81)
            if r.count > Self.AUDIO_HDR, r[16] == 0x81 {
                let audioLen = r.count - Self.AUDIO_HDR
                if audioLen > 0 {
                    _audioDbgCount += 1
                    if _audioDbgCount <= 5 || _audioDbgCount % 200 == 0 {
                        NSLog("[CIV] audioRX pkt#\(_audioDbgCount) len=\(audioLen) spk=\(spkEnabled) tx=\(txAudioActive) engineRunning=\(audioEngine?.isRunning ?? false)")
                    }
                    lastAudioDataMs = nowMs()
                    let pcm = r.subdata(in: Self.AUDIO_HDR..<r.count)
                    let sp = spkEnabled
                    if sp && !txAudioActive {
                        scheduleAudioPCM(pcm)
                    }
                }
                continue
            }
            // byte[16] != 0x81 — log unknown types briefly for diagnosis
            if r.count > Self.AUDIO_HDR {
                _audioDbgUnknown += 1
                if _audioDbgUnknown <= 3 {
                    NSLog("[CIV] audioRX unknown b16=0x\(String(r[16], radix: 16)) len=\(r.count)")
                }
            }
            // Single retransmit (16B, type=0x0001)
            if r.count == Self.CONTROL_SIZE, rType == 0x0001 {
                let missingSeq = getLE16(r, 6)
                retransmit(seq: missingSeq, single: true)
                continue
            }
            // Range retransmit (20B, type=0x0001)
            if r.count == 20, rType == 0x0001 {
                let start = getLE16(r, 16); let end = getLE16(r, 18)
                var s = start
                while s != (end + 1) & 0xFFFF {
                    retransmit(seq: s, single: false)
                    s = (s + 1) & 0xFFFF
                }
            }
        }
    }

    private func retransmit(seq: Int, single: Bool) {
        txCacheLock.lock()
        let cached = txCache.first(where: { $0.seq == seq })?.pkt
        txCacheLock.unlock()
        if let p = cached {
            sendAudio(p); if single { sendAudio(p) }
        } else {
            sendAudio(makeCtrl(type: 0x00, seq: seq, sentId: audioMyId, rcvdId: audioRemoteId))
        }
    }

    // ─────────────────────────────────────────────
    // MARK: - Audio RX (SPK) — AVAudioEngine
    // ─────────────────────────────────────────────

    private func initAudioPlayer() {
        releaseAudioPlayer()
        let eng = AVAudioEngine()
        let player = AVAudioPlayerNode()
        guard let fmt = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                       sampleRate: 8000, channels: 1, interleaved: false) else { return }
        eng.attach(player)
        eng.connect(player, to: eng.mainMixerNode, format: fmt)

        // AVAudioSession must be in .playAndRecord and active BEFORE installTap/start so that
        // iOS activates the microphone hardware and the privacy indicator (orange dot) appears.
        // installTap on an already-running engine does NOT trigger the indicator.
        // The tap closure is dormant (returns immediately) until txAudioRunning becomes true.
        #if os(iOS)
        let sess = AVAudioSession.sharedInstance()
        do {
            // .measurement disables iOS AEC/AGC/noise-reduction on mic input.
            // This prevents iOS from silencing the mic when IC-705 speaker is nearby (5 cm
            // scenario) — the default mode interprets near-field speaker output as echo and
            // aggressively suppresses it, causing audible TX cuts.
            try sess.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker, .mixWithOthers, .allowBluetoothHFP])
            try sess.setActive(true)
            // .measurement mode ignores .defaultToSpeaker on some iOS versions — force loudspeaker explicitly
            try sess.overrideOutputAudioPort(.speaker)
        } catch { NSLog("[CIV] AVAudioSession setup failed: \(error)") }
        #endif

        txConverter = nil
        eng.inputNode.installTap(onBus: 0, bufferSize: 1024, format: nil) { [weak self] buf, _ in
            guard let self, self.txAudioRunning else { return }
            if self.txConverter == nil {
                self.txConverter = AVAudioConverter(from: buf.format, to: Self.txTargetFmt)
            }
            guard let conv = self.txConverter else { return }
            let srcSR = buf.format.sampleRate
            let frameCount = AVAudioFrameCount(Double(buf.frameLength) * 8000.0 / srcSR + 1)
            guard let convBuf = AVAudioPCMBuffer(pcmFormat: Self.txTargetFmt,
                                                  frameCapacity: frameCount) else { return }
            var error: NSError?
            conv.convert(to: convBuf, error: &error) { _, status in
                status.pointee = .haveData; return buf
            }
            if let ch = convBuf.floatChannelData?[0] {
                self.txAccLock.lock()
                self.txAccum.append(contentsOf: UnsafeBufferPointer(start: ch,
                                                                      count: Int(convBuf.frameLength)))
                let ready = self.txAccum.count >= 160
                self.txAccLock.unlock()
                if ready { self.txSema.signal() }
            }
        }

        do {
            try eng.start()
            NSLog("[CIV] audioEngine started")
        } catch {
            NSLog("[CIV] audioEngine start FAILED: \(error)")
            return  // don't set audioPlayer if engine didn't start
        }
        if spkEnabled { player.play() }
        audioEngine = eng; audioPlayer = player; audioFormat = fmt
    }

    private func releaseAudioPlayer() {
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioPlayer?.stop()
        audioEngine?.stop()
        audioPlayer = nil; audioEngine = nil; audioFormat = nil
        txConverter = nil
        #if os(iOS)
        // Restore mode so AudioTxService (hamlib PTT) and sidetone work after CI-V disconnects.
        // initAudioPlayer() sets .measurement to disable AEC; release resets it to .default.
        try? AVAudioSession.sharedInstance().setMode(.default)
        #endif
    }

    private func tryPlayAudio() {
        guard let player = audioPlayer, !player.isPlaying else { return }
        player.play()
    }
    private func audioPause() {
        audioPlayer?.pause()
    }

    private func scheduleAudioPCM(_ pcm: Data) {
        guard let player = audioPlayer, let fmt = audioFormat,
              audioEngine?.isRunning == true else {
            if _audioDbgCount <= 5 {
                NSLog("[CIV] scheduleAudioPCM SKIP: player=\(audioPlayer != nil) fmt=\(audioFormat != nil) running=\(audioEngine?.isRunning ?? false)")
            }
            return
        }
        let frameCount = AVAudioFrameCount(pcm.count / 2)
        guard frameCount > 0,
              let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: frameCount),
              let ch = buf.floatChannelData else { return }
        buf.frameLength = frameCount
        pcm.withUnsafeBytes { raw in
            guard let src = raw.baseAddress?.assumingMemoryBound(to: Int16.self) else { return }
            for i in 0..<Int(frameCount) { ch[0][i] = Float(src[i]) / 32768.0 }
        }
        player.scheduleBuffer(buf)
        if !player.isPlaying { player.play() }
    }

    // ─────────────────────────────────────────────
    // MARK: - TX audio (mic → IC-705 UDP)
    // ─────────────────────────────────────────────

    private func startTxAudio() {
        guard !txAudioRunning else { txAudioActive = true; return }
        txAudioRunning = true; txAudioActive = true
        let t = Thread { [weak self] in self?.runTxAudio() }
        t.name = "civTxAudio"; t.qualityOfService = .userInteractive; t.start()
        txAudioThread = t
    }

    private func stopTxAudio() {
        txAudioActive = false
        lastTxAudioMs = nowMs()
    }

    private func stopTxAudioFully() {
        txAudioActive = false
        txAudioRunning = false
        txAudioThread = nil
        txCacheLock.lock(); txCache.removeAll(); txCacheLock.unlock()
    }

    // MARK: - FM-CW tone API (BLE paddle real-time keying in FM mode)

    /// Arm 700Hz tone TX thread. Call on first key-down (non-blocking).
    func prepareFmCwTone(hz: Double = 700.0) {
        fmCwToneHz = hz
        fmCwSinePhase = 0.0
        if !txAudioRunning {
            txAudioRunning = true; txAudioActive = false
            let t = Thread { [weak self] in self?.runTxAudio() }
            t.name = "civTxAudio"; t.qualityOfService = .userInteractive; t.start()
            txAudioThread = t
        }
    }

    func fmCwKeyOn()  { txAudioActive = true }
    func fmCwKeyOff() { txAudioActive = false }

    func endFmCwTone() {
        txAudioActive = false
        fmCwToneHz = 0.0
    }

    // MARK: - FM-CW direct PCM streaming (text-to-CW in FM mode)

    /// Stop mic capture so CW PCM can be sent without interference.
    func stopTxAudioCapture() { txAudioActive = false }

    /// Send one chunk of pre-built CW PCM directly to IC-705 RS-BA1 TX audio.
    func sendTxAudioDirect(_ pcm: Data) {
        guard isConnected else { return }
        sendAudio(makeTxAudioPacket(pcm))
    }

    private func runTxAudio() {
        // Mic tap is pre-installed in initAudioPlayer() before eng.start(), so the green
        // mic indicator appears when the engine starts (not only when PTT is pressed).
        // This function only runs the PCM encoding and UDP send loop.
        guard audioEngine != nil else { txAudioRunning = false; return }
        txAccLock.lock(); txAccum.removeAll(); txAccLock.unlock()

        let samplesPerPkt = 160
        let HARD_LIMIT = Float(29491)
        let PKT_NS = UInt64(20_000_000)
        let silence = Data(count: 320)
        var nextNs = clock_ns()
        var prevActive = false

        repeat {
            let active = txAudioActive
            if active && !prevActive {
                for _ in 0..<6 { sendAudio(makeTxAudioPacket(silence)) }
                nextNs = clock_ns()
            }
            prevActive = active

            // Clock-first: sleep to next 20-ms boundary BEFORE checking the accumulator.
            // Previously the 25ms sema-wait gated each iteration, driving the send rate to
            // the tap period (~21.3 ms at 48 kHz / 1024 frames) instead of the RS-BA1
            // protocol period (20 ms).  Over ~4 s the IC-705 audio buffer drifted empty
            // → periodic micro-cuts.  With clock-first pacing the IC-705 receives evenly-
            // spaced packets; the semaphore is only drained non-blockingly to keep its
            // counter from growing unbounded.
            let waitNs = Int64(nextNs) - Int64(clock_ns())
            if waitNs > 2_000_000 { Thread.sleep(forTimeInterval: Double(waitNs) / 1e9) }
            nextNs += PKT_NS
            while txSema.wait(timeout: .now()) == .success {}  // drain buffered tap signals

            txAccLock.lock()
            let hasData = txAccum.count >= samplesPerPkt
            let useTone = fmCwToneHz > 0
            let chunk: [Float]
            if useTone {
                // FM-CW: generate sine wave instead of mic input
                if hasData { txAccum.removeFirst(min(samplesPerPkt, txAccum.count)) }
                let hz = fmCwToneHz
                var s = [Float](repeating: 0, count: samplesPerPkt)
                for i in 0..<samplesPerPkt {
                    fmCwSinePhase += 2.0 * .pi * hz / 8000.0
                    s[i] = sin(Float(fmCwSinePhase))
                }
                fmCwSinePhase = fmCwSinePhase.truncatingRemainder(dividingBy: 2.0 * .pi)
                chunk = s
            } else {
                if hasData {
                    chunk = Array(txAccum.prefix(samplesPerPkt))
                    txAccum.removeFirst(samplesPerPkt)
                } else {
                    chunk = []
                }
            }
            txAccLock.unlock()

            guard txAudioActive else { continue }

            if !chunk.isEmpty {
                let gain = 8.0 * Double(micGain) * 32767.0
                var pcm = Data(count: samplesPerPkt * 2)
                let hl = Double(HARD_LIMIT)
                pcm.withUnsafeMutableBytes { raw in
                    let dst = raw.baseAddress!.assumingMemoryBound(to: Int16.self)
                    for i in 0..<samplesPerPkt {
                        let s = Double(chunk[i]) * gain
                        let lim = tanh(s / hl) * hl
                        dst[i] = Int16(max(-32768, min(32767, Int(lim))))
                    }
                }
                sendAudio(makeTxAudioPacket(pcm))
            } else {
                sendAudio(makeTxAudioPacket(silence))
            }
            lastTxAudioMs = nowMs()
        } while txAudioRunning

        // Tap stays installed until releaseAudioPlayer() — do NOT remove here.
        txAudioRunning = false
    }

    private func clock_ns() -> UInt64 {
        var ts = timespec()
        clock_gettime(CLOCK_MONOTONIC_RAW, &ts)
        return UInt64(ts.tv_sec) * 1_000_000_000 + UInt64(ts.tv_nsec)
    }

    // ─────────────────────────────────────────────
    // MARK: - CI-V cache
    // ─────────────────────────────────────────────

    private func cacheCivFrames(_ data: Data) {
        var i = 0
        while i < data.count - 1 {
            guard data[i] == 0xFE, data[i+1] == 0xFE else { i += 1; continue }
            var body = [UInt8](); var j = i + 2
            while j < data.count { let b = data[j]; if b == 0xFD { break }; body.append(b); j += 1 }
            if body.count >= 3, body[0] == UInt8(Self.ctrlAddress) {
                let cmd = Int(body[2])
                let sub = body.count >= 4 ? Int(body[3]) : 0xFF
                civCache[(cmd << 8) | sub] = (Data(body), nowMs())
            }
            i += 1
        }
    }

    private func getCached(_ cmd: Int, _ sub: Int, maxAgeMs: TimeInterval = 3000) -> Data? {
        let now = nowMs()
        if sub < 0 {
            return civCache.first(where: { ($0.key >> 8) == cmd && now - $0.value.ts < maxAgeMs })?.value.body
        }
        let key = (cmd << 8) | (sub & 0xFF)
        guard let e = civCache[key], now - e.ts < maxAgeMs else { return nil }
        return e.body
    }

    // ─────────────────────────────────────────────
    // MARK: - Connect / Disconnect
    // ─────────────────────────────────────────────

    /// Blocking. Must be called from a background thread/Task.
    func connect(host: String, port1: Int, port2: Int, port3: Int,
                 username: String, password: String) -> Bool {
        // Close any session the IC-705 still has open from a previous app run.
        // Without this, the IC-705 ignores AYT because it considers a session active.
        sendStaleDisconnect()
        clearStaleSession()
        disconnect()
        postMain { [weak self] in
            self?.lastError = ""; self?.isConnected = false; self?.civStreamOpened = false
        }
        ctrlSeq = 0; civPktSeq = 0; civSendSeqB = 0; authSeq = 0; audioTxSeq = 0
        pollFailCount = 0; token = 0; tokRequest = 0; lastCtrlPingMs = 0; lastAudioPingMs = 0
        lastAudioDataMs = 0; ctrlMyId = 0; audioMyId = 0; civMyId = 0; civRemoteId = 0
        ctrlRemoteId = 0; audioRemoteId = 0; transcieveEnabled = false
        _audioDbgCount = 0; _audioDbgUnknown = 0
        savedUsername = username; savedMacAddr = nil; savedRadioName = nil
        lastRenewalMs = 0; maintenanceMs = 0; mainRetryMs = 0; lastTokenRenMs = 0
        civCache.removeAll()
        remoteIP = host; civPort1 = port1; civPort2 = port2; civPort3 = port3

        let localIP = getLocalIP(to: host, port: port1) ?? "0.0.0.0"
        NSLog("[CivService] connect localIP=\(localIP) host=\(host) port1=\(port1)")
        if localIP == "0.0.0.0" {
            setErr(String(localized: "ルート不明 (\(host)) — iPhoneがIC-705と同じWiFiに接続されていることを確認してください。"))
            return false
        }

        // Open sockets on ephemeral ports so ctrlMyId/civMyId differ each connect.
        // IC-705 keys stale sessions on ctrlMyId (derived from localIP:localPort). Using a
        // fixed port (50001) means the same ctrlMyId every connect, so IC-705 ignores Login
        // until the stale session ages out. Ephemeral ports give a fresh identity each time.
        ctrlFd  = openUdpSocket(localPort: 0)
        civFd   = openUdpSocket(localPort: 0)
        audioFd = openUdpSocket(localPort: 0)
        guard ctrlFd >= 0, civFd >= 0, audioFd >= 0 else {
            let fds = "fd=\(ctrlFd),\(civFd),\(audioFd)"
            setErr(String(localized: "ソケット作成失敗 (\(fds))")); closeSockets(); return false
        }

        let ctrlPt  = localPort(of: ctrlFd)
        let civPt   = localPort(of: civFd)
        let audioPt = localPort(of: audioFd)
        NSLog("[CivService] sockets ctrlPt=\(ctrlPt) civPt=\(civPt) audioPt=\(audioPt)")
        ctrlMyId  = computeMyId(localIpStr: localIP, port: ctrlPt)
        civMyId   = computeMyId(localIpStr: localIP, port: civPt)
        audioMyId = computeMyId(localIpStr: localIP, port: audioPt)

        startCivRxThread()

        // Pre-wake AYT burst: unicast + broadcast to wake IC-705 from WiFi PSM (子機モード)
        var bcastOn: Int32 = 1
        setsockopt(ctrlFd, SOL_SOCKET, SO_BROADCAST, &bcastOn, socklen_t(MemoryLayout<Int32>.size))
        let bcastIP: String = {
            let parts = localIP.split(separator: ".")
            guard parts.count == 4, localIP != "0.0.0.0" else { return "255.255.255.255" }
            return "\(parts[0]).\(parts[1]).\(parts[2]).255"
        }()
        let ayt = makeCtrl(type: 0x03, seq: 0, sentId: ctrlMyId, rcvdId: 0)
        NSLog("[CivService] pre-wake bcastIP=\(bcastIP) ctrlMyId=0x\(String(ctrlMyId, radix: 16))")
        for i in 0..<3 {
            let s1 = send2(ctrlFd, ayt, ip: host, port: port1)
            let s2 = send2(ctrlFd, ayt, ip: bcastIP, port: port1)
            NSLog("[CivService] pre-wake[\(i)] unicast=\(s1) bcast=\(s2)")
            Thread.sleep(forTimeInterval: 0.10)
        }
        Thread.sleep(forTimeInterval: 0.15)

        // Step 1: AreYouThere → IAmHere (up to 90s; matches Android — IC-705 PSM wakeup can take >20s)
        // Main loop sends unicast only (like Android). Broadcast was already sent in the pre-wake burst above.
        onConnectStep?(String(localized: "IC-705 を検索中…"))
        let aytEnd = nowMs() + 90_000
        var gotIAH = false
        var aytCount = 0
        while nowMs() < aytEnd {
            aytCount += 1
            if aytCount <= 1 || aytCount % 5 == 0 {
                onConnectStep?(String(localized: "IC-705 を検索中… (\(aytCount)s / 90s)"))
            }
            let aytPkt = makeCtrl(type: 0x03, seq: 0, sentId: ctrlMyId, rcvdId: 0)
            let r1 = send2(ctrlFd, aytPkt, ip: host, port: port1)  // unicast only — matches Android
            NSLog("[CivService] AYT#\(aytCount) → \(host):\(port1) sendErr=\(r1)")
            guard let r = recvCtrl(1000) else { continue }
            NSLog("[CivService] recv \(r.count)B type=0x\(String(getLE16(r, 4), radix: 16))")
            if r.count == Self.CONTROL_SIZE, getLE16(r, 4) == 0x04 {
                ctrlRemoteId = getLE32(r, 8); gotIAH = true; break
            }
        }
        if !gotIAH { setErr(String(localized: "IC-705応答なし (\(host):\(port1)) — IPアドレス・RS-BA1設定を確認してください。")); disconnect(); return false }

        // Drain queued ctrl packets accumulated during AYT burst BEFORE sending AYR.
        // When IC-705 is already running, the 5-packet pre-wake burst generates 5 IAmHere (0x04)
        // responses that queue in the socket buffer and would interfere with the AYR step.
        Thread.sleep(forTimeInterval: 0.05)
        var drainBeforeAyr = 0
        while recvFrom(ctrlFd, timeoutMs: 10) != nil { drainBeforeAyr += 1 }
        NSLog("[CivService] drained \(drainBeforeAyr) queued packets before AYR")

        // Step 2: AreYouReady → IAmReady (retry once, 5s per attempt)
        NSLog("[CivService] step2 AYR remoteId=0x\(String(ctrlRemoteId, radix: 16))")
        onConnectStep?(String(localized: "ハンドシェイク中…"))
        var gotIAR = false
        for ayrAttempt in 0..<2 {
            if ayrAttempt > 0 {
                NSLog("[CivService] step2 retry AYR attempt=\(ayrAttempt)")
                Thread.sleep(forTimeInterval: 0.5)
            }
            // AYR uses seq=1 hardcoded (matches Android) WITHOUT touching ctrlSeq, so that
            // the first Login packet gets seq=1 (ctrlSeq 0→1). IC-705 expects Login at seq=1.
            sendCtrl(makeCtrl(type: 0x06, seq: 1, sentId: ctrlMyId, rcvdId: ctrlRemoteId))
            let ayrEnd = nowMs() + 5000
            while nowMs() < ayrEnd {
                guard let r = recvCtrl(500) else { continue }
                let rtype = getLE16(r, 4)
                NSLog("[CivService] step2[\(ayrAttempt)] recv \(r.count)B type=0x\(String(rtype, radix: 16))")
                if r.count == Self.CONTROL_SIZE {
                    let sid = getLE32(r, 8)
                    if rtype == 0x04 || rtype == 0x06 {
                        ctrlRemoteId = sid
                        NSLog("[CivService] step2 updated ctrlRemoteId=0x\(String(ctrlRemoteId, radix: 16)) from type=0x\(String(rtype, radix: 16))")
                    }
                    if rtype == 0x06 { gotIAR = true; break }
                }
            }
            if gotIAR { break }
        }
        if !gotIAR { setErr(String(localized: "IC-705応答なし (IAmReady)")); disconnect(); return false }
        NSLog("[CivService] step2 OK ctrlRemoteId=0x\(String(ctrlRemoteId, radix: 16))")

        // Drain any residual packets that arrived during the AYR exchange before proceeding to Login.
        var drainAfterAyr = 0
        while recvFrom(ctrlFd, timeoutMs: 10) != nil { drainAfterAyr += 1 }
        NSLog("[CivService] drained \(drainAfterAyr) residual packets after AYR")

        // Step 3: Login (retry up to 3x in case IC-705 needs time)
        onConnectStep?(String(localized: "ログイン中…"))
        NSLog("[CivService] step3 Login user=\(username) ctrlRemoteId=0x\(String(ctrlRemoteId, radix: 16))")
        tokRequest = Int.random(in: 1...0xFFFE)
        var gotLoginResp = false
        let loginEnd = nowMs() + 8000
        var loginSentAt = TimeInterval(0)
        while nowMs() < loginEnd {
            // Send (or re-send) Login every 2s
            if nowMs() - loginSentAt > 2000 {
                ctrlSeq += 1
                sendCtrl(makeLogin(seq: ctrlSeq, innerSeq: authSeq, tokReq: tokRequest,
                                    username: username, password: password))
                loginSentAt = nowMs()
                NSLog("[CivService] step3 Login sent ctrlSeq=\(ctrlSeq) authSeq=\(authSeq)")
            }
            guard let r = recvCtrl(500) else { continue }
            let rtype3 = r.count >= 6 ? getLE16(r, 4) : 0xFFFF
            NSLog("[CivService] step3 recv \(r.count)B type=0x\(String(rtype3, radix: 16))")
            // Pong ctrl pings during login wait (ctrlRxThread not yet started)
            if r.count == Self.PING_SIZE, rtype3 == 0x07, r[16] == 0x00 {
                handleCtrlPing(r); continue
            }
            if r.count != Self.LOGIN_RSP_SZ { continue }
            let err = getLE32(r, 0x30)
            NSLog("[CivService] step3 loginErr=0x\(String(err, radix: 16))")
            if err == 0xFEFFFFFF { setErr(String(localized: "認証失敗 (ユーザー名/パスワードが違う)")); disconnect(); return false }
            token = getLE32(r, 0x1c); gotLoginResp = true; break
        }
        if !gotLoginResp { setErr(String(localized: "IC-705応答なし (Login)")); disconnect(); return false }
        NSLog("[CivService] step3 OK token=0x\(String(token, radix: 16))")

        // Step 4: Token → capabilities
        onConnectStep?(String(localized: "認証中…"))
        NSLog("[CivService] step4 Token")
        ctrlSeq += 1; authSeq += 1
        sendCtrl(makeToken(seq: ctrlSeq, innerSeq: authSeq, tokReq: tokRequest, tokenVal: token, magic: 0x02))
        var gotTokenResp = false
        var macFromCaps: Data? = nil; var radioNameFromCaps: Data? = nil
        let CAPS_HDR = 0x42
        let tokEnd = nowMs() + 5000
        while nowMs() < tokEnd {
            guard let r = recvCtrl(500) else { continue }
            let rtype = getLE16(r, 4)
            NSLog("[CivService] step4 recv \(r.count)B type=0x\(String(rtype, radix: 16))")
            if r.count == Self.TOKEN_SIZE, rtype != 0x01 {
                gotTokenResp = true; break
            } else if r.count >= Self.CONNINFO_SIZE, rtype != 0x01 {
                if macFromCaps == nil, r.count >= 0x30 { macFromCaps = r.subdata(in: 0x2a..<0x30) }
                if r.count > Self.CONNINFO_SIZE {
                    if r.count >= CAPS_HDR + 0x10 { macFromCaps = r.subdata(in: (CAPS_HDR+0x0a)..<(CAPS_HDR+0x10)) }
                    if r.count >= CAPS_HDR + 0x30 { radioNameFromCaps = r.subdata(in: (CAPS_HDR+0x10)..<(CAPS_HDR+0x30)) }
                    if r.count >= CAPS_HDR + 0x53 {
                        let addr = Int(r[CAPS_HDR + 0x52])
                        if addr != 0 { let a = addr; postMain { [weak self] in self?.civAddress = a } }
                    }
                }
                gotTokenResp = true; break
            }
        }
        if !gotTokenResp { setErr(String(localized: "IC-705応答なし (Token)")); disconnect(); return false }
        NSLog("[CivService] step4 OK mac=\(macFromCaps?.map { String($0, radix: 16) }.joined(separator: ":") ?? "nil")")
        savedMacAddr = macFromCaps; savedRadioName = radioNameFromCaps
        lastTokenRenMs = nowMs()

        // Step 5: RequestStream → STATUS
        onConnectStep?(String(localized: "ストリーム設定中…"))
        NSLog("[CivService] step5 RequestStream civPt=\(civPt) audioPt=\(audioPt)")
        ctrlSeq += 1; authSeq += 1
        sendCtrl(makeRequestStream(seq: ctrlSeq, innerSeq: authSeq, tokReq: tokRequest, tokenVal: token,
                                    civLocalPort: civPt, audioLocalPort: audioPt,
                                    username: username, macAddr: macFromCaps, radioName: radioNameFromCaps))
        var gotStatus = false
        var statusFfRetried = false
        let rsEnd = nowMs() + 8000
        while nowMs() < rsEnd, !gotStatus {
            guard let r = recvCtrl(500) else { continue }
            NSLog("[CivService] step5 recv \(r.count)B")
            switch r.count {
            case Self.STATUS_SIZE:
                let err = getLE32(r, 0x30)
                NSLog("[CivService] step5 STATUS err=0x\(String(err, radix: 16))")
                if err == 0xFFFFFFFF {
                    // IC-705 is still releasing the previous session — wait and retry once.
                    if !statusFfRetried {
                        statusFfRetried = true
                        NSLog("[CivService] step5 Status FF — waiting 2s then retrying RequestStream")
                        Thread.sleep(forTimeInterval: 2.0)
                        ctrlSeq += 1; authSeq += 1
                        sendCtrl(makeRequestStream(seq: ctrlSeq, innerSeq: authSeq, tokReq: tokRequest, tokenVal: token,
                                                   civLocalPort: civPt, audioLocalPort: audioPt, username: username,
                                                   macAddr: macFromCaps, radioName: radioNameFromCaps))
                        continue
                    }
                    setErr(String(localized: "接続失敗 (Status FF)")); disconnect(); return false
                }
                gotStatus = true
            case Self.CONNINFO_SIZE:
                let busy = getLE32(r, 0x60)
                NSLog("[CivService] step5 CONNINFO busy=\(busy)")
                if busy <= 1 {
                    ctrlSeq += 1; authSeq += 1
                    sendCtrl(makeRequestStream(seq: ctrlSeq, innerSeq: authSeq, tokReq: tokRequest, tokenVal: token,
                                               civLocalPort: civPt, audioLocalPort: audioPt, username: username,
                                               macAddr: r.subdata(in: 0x2a..<0x30), radioName: radioNameFromCaps))
                }
            default: break
            }
        }
        if !gotStatus { setErr(String(localized: "IC-705応答なし (RequestStream)")); disconnect(); return false }
        NSLog("[CivService] step5 OK")

        // Step 5.5: Audio AYT handshake
        NSLog("[CivService] step5.5 Audio AYT")
        var audioReady = false
        for attempt in 1...3 {
            sendAudio(makeCtrl(type: 0x03, seq: 0, sentId: audioMyId, rcvdId: 0))
            let waitEnd = nowMs() + 1000
            while nowMs() < waitEnd {
                guard let ar = recvFrom(audioFd, timeoutMs: 100) else { continue }
                let rtype = ar.count >= 6 ? getLE16(ar, 4) : -1
                NSLog("[CivService] step5.5[\(attempt)] recv \(ar.count)B type=0x\(String(rtype, radix: 16))")
                if ar.count == Self.CONTROL_SIZE, rtype == 0x04 {
                    audioRemoteId = getLE32(ar, 8)
                    sendAudio(makeCtrl(type: 0x06, seq: 1, sentId: audioMyId, rcvdId: audioRemoteId))
                    let iarEnd = nowMs() + 1000
                    while nowMs() < iarEnd {
                        guard let iar = recvFrom(audioFd, timeoutMs: 100) else { continue }
                        let it = iar.count >= 6 ? getLE16(iar, 4) : -1
                        if it == 0x06 { audioReady = true }
                        else if it == 0x07 { handleAudioPing(iar) }
                        if audioReady { break }
                    }
                    break
                } else if rtype == 0x07 { handleAudioPing(ar) }
                if audioReady { break }
            }
            if audioReady { break }
            Thread.sleep(forTimeInterval: 0.2)
        }
        NSLog("[CivService] step5.5 audioReady=\(audioReady)")

        // Step 6: CI-V handshake
        onConnectStep?(String(localized: "CI-V 接続中…"))
        NSLog("[CivService] step6 CIV civMyId=0x\(String(civMyId, radix: 16))")
        civRxQueue.clear()
        var gotCivIAH = false; var gotCivReady = false
        let civEnd = nowMs() + 8000
        while nowMs() < civEnd {
            if !gotCivIAH {
                civPktSeq = (civPktSeq + 1) & 0xFFFF
                sendCiv(makeCtrl(type: 0x03, seq: civPktSeq, sentId: civMyId, rcvdId: 0))
            }
            guard let r = recvCiv(500) else { continue }
            NSLog("[CivService] step6 recv \(r.count)B type=0x\(r.count >= 6 ? String(getLE16(r, 4), radix: 16) : "?")")
            handleCivPing(r)
            if r.count != Self.CONTROL_SIZE { continue }
            switch getLE16(r, 4) {
            case 0x04:
                civRemoteId = getLE32(r, 8)
                NSLog("[CivService] step6 CIV IAH civRemoteId=0x\(String(civRemoteId, radix: 16))")
                if !gotCivIAH {
                    gotCivIAH = true
                    civPktSeq = (civPktSeq + 1) & 0xFFFF
                    sendCiv(makeCtrl(type: 0x06, seq: 1, sentId: civMyId, rcvdId: civRemoteId))
                }
            case 0x06:
                civRemoteId = getLE32(r, 8)
                NSLog("[CivService] step6 CIV IAmReady → OpenClose")
                civPktSeq = (civPktSeq + 1) & 0xFFFF
                civSendSeqB = (civSendSeqB + 1) & 0xFFFF
                sendCiv(makeOpenClose(seq: civPktSeq, sendSeq: civSendSeqB, open: true,
                                       sentId: civMyId, rcvdId: civRemoteId))
                gotCivReady = true
            default: break
            }
            if gotCivReady { break }
        }
        if gotCivIAH && !gotCivReady {
            NSLog("[CivService] step6 no IAmReady, sending OpenClose anyway")
            civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
            sendCiv(makeOpenClose(seq: civPktSeq, sendSeq: civSendSeqB, open: true, sentId: civMyId, rcvdId: civRemoteId))
        }
        if !gotCivIAH {
            // IC-705 may have established the CI-V session via ping/pong before step 6 ran
            // (civRxThread learned civRemoteId from pings), so it skips sending IAH.
            // If civRemoteId is known, send OpenClose directly and continue rather than failing.
            if civRemoteId != 0 {
                NSLog("[CivService] step6 no IAH but civRemoteId=0x\(String(civRemoteId, radix: 16)) (from pings) — sending OpenClose")
                civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
                sendCiv(makeOpenClose(seq: civPktSeq, sendSeq: civSendSeqB, open: true, sentId: civMyId, rcvdId: civRemoteId))
            } else {
                setErr(String(localized: "IC-705 CIVソケット応答なし")); disconnect(); return false
            }
        }
        NSLog("[CivService] step6 OK gotCivReady=\(gotCivReady)")

        // Enable transceive
        civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
        sendCiv(makeCivData(seq: civPktSeq, sendSeq: civSendSeqB, civData: buildCivFrame(0x16, 0x02, 0x01)))
        transcieveEnabled = true
        lastOpenCloseMs = nowMs()

        startCtrlRxThread()
        initAudioPlayer()
        startAudioRxThread()

        let now = nowMs()
        lastRenewalMs = now; lastAudioDataMs = now
        civStreamOpened = false
        saveStaleSession()   // persist session IDs so next launch can close this session
        postMain { [weak self] in self?.isConnected = true }
        return true
    }

    /// Blocking. Safe to call from any thread.
    func disconnect() {
        clearStaleSession()   // proper disconnect — no stale close needed on next launch
        postMain { [weak self] in self?.isConnected = false; self?.civStreamOpened = false }
        stopTxAudioFully()
        ctrlRxActive = false; audioRxActive = false

        // Token delete + CIV close + ctrl disconnect
        if ctrlFd >= 0, ctrlMyId != 0, ctrlRemoteId != 0, token != 0 {
            ctrlSeq += 1; authSeq += 1
            sendCtrl(makeToken(seq: ctrlSeq, innerSeq: authSeq, tokReq: tokRequest, tokenVal: token, magic: 0x01))
        }
        if civFd >= 0, civMyId != 0, civRemoteId != 0 {
            civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
            sendCiv(makeOpenClose(seq: civPktSeq, sendSeq: civSendSeqB, open: false, sentId: civMyId, rcvdId: civRemoteId))
        }
        if ctrlFd >= 0, ctrlRemoteId != 0 {
            ctrlSeq += 1
            sendCtrl(makeCtrl(type: 0x05, seq: ctrlSeq, sentId: ctrlMyId, rcvdId: ctrlRemoteId))
        }
        Thread.sleep(forTimeInterval: 0.1)

        releaseAudioPlayer()
        pendingFreqHz = nil; pendingMode = nil
        closeSockets()
        ctrlRxThread?.cancel(); ctrlRxThread = nil
        audioRxThread?.cancel(); audioRxThread = nil
        civRxActive = false
        civRxThread?.cancel(); civRxThread = nil
        civRxQueue.clear()
    }

    private func closeSockets() {
        if audioFd >= 0 { Darwin.close(audioFd); audioFd = -1 }
        if ctrlFd  >= 0 { Darwin.close(ctrlFd);  ctrlFd  = -1 }
        if civFd   >= 0 { Darwin.close(civFd);   civFd   = -1 }
    }

    private func setErr(_ msg: String) {
        lastError = msg
        postMain { [weak self] in self?.lastError = msg }
    }

    // ─────────────────────────────────────────────
    // MARK: - CI-V exchange (synchronized blocking)
    // ─────────────────────────────────────────────

    private func buildCivFrame(_ bytes: Int...) -> Data {
        var b = Data(count: 4 + bytes.count + 1)
        b[0] = 0xFE; b[1] = 0xFE
        b[2] = UInt8(civAddress); b[3] = UInt8(Self.ctrlAddress)
        for (i, v) in bytes.enumerated() { b[4 + i] = UInt8(v & 0xFF) }
        b[b.count - 1] = 0xFD
        return b
    }

    @discardableResult
    private func exchange(_ civFrame: Data, cmd: Int, subCmd: Int = -1, timeoutMs: Int = 1500) -> Data? {
        guard ctrlFd >= 0, civFd >= 0 else { return nil }
        exchangeLock.lock(); defer { exchangeLock.unlock() }
        let effective = timeoutMs
        let opened = civStreamOpened
        if !opened {
            let now = nowMs()
            if now - lastOpenCloseMs > 100 {
                civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
                sendCiv(makeOpenClose(seq: civPktSeq, sendSeq: civSendSeqB, open: true,
                                       sentId: civMyId, rcvdId: civRemoteId))
                lastOpenCloseMs = now
            }
        }
        civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
        sendCiv(makeCivData(seq: civPktSeq, sendSeq: civSendSeqB, civData: civFrame))
        let deadline = nowMs() + Double(effective)
        while nowMs() < deadline {
            if civFd < 0 { isConnected = false; return nil }
            if !civStreamOpened {
                let now = nowMs()
                if now - lastOpenCloseMs > 200 {
                    civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
                    sendCiv(makeOpenClose(seq: civPktSeq, sendSeq: civSendSeqB, open: true,
                                           sentId: civMyId, rcvdId: civRemoteId))
                    lastOpenCloseMs = now
                }
            }
            // Maintenance audio silence bail-out
            let ad = lastAudioDataMs; let ap = lastAudioPingMs
            if civStreamOpened, ad > 0, nowMs() - ad > 400, ap > 0, !lastKnownTx { return nil }

            guard let r = recvCiv(50) else { continue }
            handleCivPing(r)
            // When stream is not yet open, complete the IAH→IAmHere→IAmReady handshake.
            // Without this, IC-705 holds the CI-V command unprocessed until the handshake
            // times out on its side (~25 s).  Sending IAmHere lets IC-705 finish the
            // handshake quickly, after which it processes the CI-V cmd we resend here.
            if !civStreamOpened, r.count == Self.CONTROL_SIZE {
                let rtype = getLE16(r, 4)
                if rtype == 0x04 {                               // IAH — respond with IAmHere
                    let sid = getLE32(r, 8)
                    if civRemoteId == 0 || sid != civRemoteId { civRemoteId = sid }
                    civPktSeq = (civPktSeq + 1) & 0xFFFF
                    sendCiv(makeCtrl(type: 0x06, seq: 1, sentId: civMyId, rcvdId: civRemoteId))
                    continue
                }
                if rtype == 0x06 {                               // IAmReady — channel open, resend cmd
                    let sid = getLE32(r, 8)
                    if civRemoteId == 0 || sid != civRemoteId { civRemoteId = sid }
                    civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
                    sendCiv(makeCivData(seq: civPktSeq, sendSeq: civSendSeqB, civData: civFrame))
                    continue
                }
            }
            guard r.count > Self.PING_SIZE, getLE16(r, 4) != 0x01 else { continue }
            let dataLen = getLE16(r, 17)
            guard dataLen > 0, r.count >= Self.PING_SIZE + dataLen else { continue }
            let civData = r.subdata(in: Self.PING_SIZE..<(Self.PING_SIZE + dataLen))
            cacheCivFrames(civData)
            if let body = extractCivBody(civData, cmd: cmd, subCmd: subCmd) {
                civStreamOpened = true
                postMain { [weak self] in self?.civStreamOpened = true }
                return body
            }
            if cmd == 0x03 {
                if let body = extractCivBody(civData, cmd: 0x00, subCmd: -1) {
                    civStreamOpened = true
                    postMain { [weak self] in self?.civStreamOpened = true }
                    return body
                }
            }
        }
        return nil
    }

    private func extractCivBody(_ data: Data, cmd: Int, subCmd: Int) -> Data? {
        var i = 0
        while i < data.count - 1 {
            guard data[i] == 0xFE, data[i+1] == 0xFE else { i += 1; continue }
            var body = [UInt8](); var j = i + 2
            while j < data.count { let b = data[j]; if b == 0xFD { break }; body.append(b); j += 1 }
            if body.count >= 3, body[0] == UInt8(Self.ctrlAddress), body[2] == UInt8(cmd & 0xFF),
               (subCmd < 0 || (body.count >= 4 && body[3] == UInt8(subCmd & 0xFF))) {
                return Data(body)
            }
            i += 1
        }
        return nil
    }

    // ─────────────────────────────────────────────
    // MARK: - CI-V drain (dead window queue flush)
    // ─────────────────────────────────────────────

    private func drainCivBroadcasts() {
        while true {
            guard let r = civRxQueue.dequeue(timeoutMs: 0) else { break }
            handleCivPing(r)
            if r.count > Self.PING_SIZE, getLE16(r, 4) != 0x01 {
                let dl = getLE16(r, 17)
                if dl > 0, r.count >= Self.PING_SIZE + dl {
                    let cd = r.subdata(in: Self.PING_SIZE..<(Self.PING_SIZE + dl))
                    if cd.count >= 2, cd[0] == 0xFE, cd[1] == 0xFE { cacheCivFrames(cd) }
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // MARK: - Seamless maintenance reconnect
    // ─────────────────────────────────────────────

    private func tryAudioOnlyReconnect() -> Bool {
        guard ctrlFd >= 0, token != 0, ctrlRemoteId != 0, !savedUsername.isEmpty else { return false }
        let civPt   = localPort(of: civFd)
        let audioPt = localPort(of: audioFd)
        guard civPt > 0, audioPt > 0 else { return false }
        ctrlSeq += 1; authSeq += 1
        sendCtrl(makeToken(seq: ctrlSeq, innerSeq: authSeq, tokReq: tokRequest, tokenVal: token, magic: 0x02))
        var tokenOk = false
        let tokEnd = nowMs() + 1000
        while nowMs() < tokEnd {
            guard let r = recvCtrl(100) else { continue }
            if r.count == Self.PING_SIZE, getLE16(r, 4) == 0x07 { handleCtrlPing(r); continue }
            if r.count >= Self.TOKEN_SIZE { tokenOk = true; break }
        }
        if !tokenOk { return false }
        ctrlSeq += 1; authSeq += 1
        sendCtrl(makeRequestStream(seq: ctrlSeq, innerSeq: authSeq, tokReq: tokRequest, tokenVal: token,
                                    civLocalPort: civPt, audioLocalPort: audioPt,
                                    username: savedUsername, macAddr: savedMacAddr, radioName: savedRadioName))
        let statEnd = nowMs() + 1500
        while nowMs() < statEnd {
            guard let r = recvCtrl(100) else { continue }
            if r.count == Self.PING_SIZE, getLE16(r, 4) == 0x07 { handleCtrlPing(r); continue }
            if r.count == Self.STATUS_SIZE {
                let err = getLE32(r, 0x30)
                if err == 0xFFFFFFFF { return false }
                civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
                sendCiv(makeOpenClose(seq: civPktSeq, sendSeq: civSendSeqB, open: true, sentId: civMyId, rcvdId: civRemoteId))
                civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
                sendCiv(makeCivData(seq: civPktSeq, sendSeq: civSendSeqB, civData: buildCivFrame(0x16, 0x02, 0x01)))
                let n = nowMs()
                lastOpenCloseMs = n; lastAudioDataMs = n; lastRenewalMs = n
                if spkEnabled { DispatchQueue.main.async { self.audioPlayer?.play() } }
                return true
            }
        }
        return false
    }

    // ─────────────────────────────────────────────
    // MARK: - BCD helpers
    // ─────────────────────────────────────────────

    private func bcdLevel(_ b0: UInt8, _ b1: UInt8) -> Int {
        (Int(b0 >> 4) * 1000) + (Int(b0 & 0xF) * 100) + (Int(b1 >> 4) * 10) + Int(b1 & 0xF)
    }
    private func bcdLevelBytes(_ v: Int) -> (UInt8, UInt8) {
        let c = max(0, min(v, 9999))
        return (UInt8((c / 1000 << 4) | (c / 100 % 10)),
                UInt8((c / 10 % 10 << 4) | (c % 10)))
    }

    // ─────────────────────────────────────────────
    // MARK: - Public CI-V API (blocking, call from background)
    // ─────────────────────────────────────────────

    func getFrequency() -> Int64? {
        for cmd in [0x03, 0x00] {
            if let body = getCached(cmd, -1), body.count >= 8 {
                var freq: Int64 = 0; var mult: Int64 = 1
                for i in 3...7 { let b = Int64(body[i]); freq += (b & 0xF) * mult; mult *= 10; freq += (b >> 4) * mult; mult *= 10 }
                return freq
            }
        }
        guard let r = exchange(buildCivFrame(0x03), cmd: 0x03), r.count >= 8 else { return nil }
        var freq: Int64 = 0; var mult: Int64 = 1
        for i in 3...7 { let b = Int64(r[i]); freq += (b & 0xF) * mult; mult *= 10; freq += (b >> 4) * mult; mult *= 10 }
        return freq
    }

    func setFrequency(_ hz: Int64) -> Bool {
        var f = hz
        let bcd = (0..<5).map { _ -> UInt8 in let lo = UInt8(f % 10); f /= 10; let hi = UInt8(f % 10); f /= 10; return (hi << 4) | lo }
        if !civStreamOpened { pendingFreqHz = hz; return false }
        let ok = exchange(buildCivFrame(0x05, Int(bcd[0]), Int(bcd[1]), Int(bcd[2]), Int(bcd[3]), Int(bcd[4])), cmd: 0xFB) != nil
        if ok { pendingFreqHz = nil }
        return ok
    }

    func getMode() -> CivMode? {
        if let body = getCached(0x04, -1), let m = parseMode(body) { return m }
        for _ in 1...3 {
            if let r = exchange(buildCivFrame(0x04), cmd: 0x04, subCmd: -1, timeoutMs: 800),
               let m = parseMode(r) { return m }
        }
        return nil
    }

    private func parseMode(_ body: Data) -> CivMode? {
        guard body.count >= 5 else { return nil }
        let mc = Int(body[3]); let fc = Int(body[4])
        let name: String = switch mc {
            case 0x00: "LSB"; case 0x01: "USB"; case 0x02: "AM";  case 0x03: "CW"
            case 0x04: "RTTY"; case 0x05: "FM"; case 0x06: "WFM"; case 0x07: "CWR"
            case 0x08: "RTTYR"; case 0x17: "DSTAR"; default: "USB"
        }
        let w: Int = switch mc {
            case 0x03, 0x07: fc == 0x03 ? 100 : fc == 0x02 ? 250 : 500
            case 0x02: 6000; case 0x05: 15000; case 0x06: 200000
            default: fc == 0x03 ? 500 : fc == 0x02 ? 1800 : 2400
        }
        return CivMode(name: name, filterWidth: w)
    }

    func setMode(_ mode: String, width: Int) -> Bool {
        if !civStreamOpened { pendingMode = (mode, width); return false }
        let mc: Int = switch mode.uppercased() {
            case "LSB": 0x00; case "USB": 0x01; case "AM": 0x02; case "CW": 0x03
            case "RTTY": 0x04; case "FM": 0x05; case "WFM": 0x06
            case "CWR","CW-R": 0x07; case "RTTYR": 0x08
            case "DSTAR","D-STAR": 0x17; default: 0x01
        }
        let isCw = mc == 0x03 || mc == 0x07
        let fc: Int = isCw ? (width <= 150 ? 0x03 : width <= 350 ? 0x02 : 0x01)
                           : (width <= 600 ? 0x03 : width <= 2000 ? 0x02 : 0x01)
        let ok = exchange(buildCivFrame(0x06, mc, fc), cmd: 0xFB) != nil
        if ok { pendingMode = nil }
        return ok
    }

    func getSmeterSignal() -> Float? {
        if let body = getCached(0x15, 0x02), body.count >= 6 {
            let raw = Float(bcdLevel(body[4], body[5]))
            return (raw - 120) * 54 / 120
        }
        guard let r = exchange(buildCivFrame(0x15, 0x02), cmd: 0x15, subCmd: 0x02, timeoutMs: 400),
              r.count >= 6 else { return nil }
        let raw = Float(bcdLevel(r[4], r[5]))
        return (raw - 120) * 54 / 120
    }

    func getRfPower() -> Float? {
        guard let r = exchange(buildCivFrame(0x14, 0x0A), cmd: 0x14, subCmd: 0x0A, timeoutMs: 400),
              r.count >= 6 else { return nil }
        return Float(bcdLevel(r[4], r[5])) / 255
    }

    func setRfPower(_ value: Float) -> Bool {
        let raw = Int(value * 255).clamped(to: 0...255)
        let (b0, b1) = bcdLevelBytes(raw)
        return exchange(buildCivFrame(0x14, 0x0A, Int(b0), Int(b1)), cmd: 0xFB) != nil
    }

    func getSql() -> Float? {
        guard let r = exchange(buildCivFrame(0x14, 0x03), cmd: 0x14, subCmd: 0x03, timeoutMs: 400),
              r.count >= 6 else { return nil }
        return Float(bcdLevel(r[4], r[5])) / 255
    }

    func setSql(_ value: Float) -> Bool {
        let raw = Int(value * 255).clamped(to: 0...255)
        let (b0, b1) = bcdLevelBytes(raw)
        return exchange(buildCivFrame(0x14, 0x03, Int(b0), Int(b1)), cmd: 0xFB) != nil
    }

    func getBkIn() -> Bool? {
        guard let r = exchange(buildCivFrame(0x16, 0x47), cmd: 0x16, subCmd: 0x47, timeoutMs: 400),
              r.count >= 5 else { return nil }
        return r[4] != 0x00
    }

    func setBkIn(_ on: Bool) -> Bool {
        exchange(buildCivFrame(0x16, 0x47, on ? 0x01 : 0x00), cmd: 0xFB) != nil
    }

    func setPtt(_ on: Bool) -> Bool {
        if on {
            pttOffPending = false; pttIntent = true
            // Safety: if the engine isn't running (e.g., started before mic permission was granted),
            // reinitialize now so the mic tap gets proper hardware access.
            if audioEngine == nil || !(audioEngine?.isRunning ?? false) {
                NSLog("[CIV] setPtt(on): engine not running — reinitializing audio")
                initAudioPlayer()
            }
            // Fire-and-forget PTT when stream is already open: bypass exchangeLock to
            // avoid waiting up to ~1.5 s for pollStatus() to release the lock.
            // Falls back to exchange() during initial handshake (before stream opens).
            let ok: Bool
            if civStreamOpened {
                civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
                sendCiv(makeCivData(seq: civPktSeq, sendSeq: civSendSeqB,
                                    civData: buildCivFrame(0x1C, 0x00, 0x01)))
                ok = true
            } else {
                ok = exchange(buildCivFrame(0x1C, 0x00, 0x01), cmd: 0xFB) != nil
            }
            if ok || isConnected {
                lastKnownTx = true
                postMain { [weak self] in self?.audioPlayer?.pause() }
                startTxAudio()
            }
            return ok
        } else {
            pttIntent = false
            stopTxAudio()
            let ok = exchange(buildCivFrame(0x1C, 0x00, 0x00), cmd: 0xFB, timeoutMs: 3000) != nil
            if ok { lastKnownTx = false; pttOffPending = false }
            else { pttOffPending = true }
            if spkEnabled { postMain { [weak self] in self?.audioPlayer?.play() } }
            return ok
        }
    }

    func civPttDown() {
        guard isConnected else { return }
        lastKnownTx = true
        civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
        sendCiv(makeCivData(seq: civPktSeq, sendSeq: civSendSeqB, civData: buildCivFrame(0x1C, 0x00, 0x01)))
    }

    func civPttUp() {
        guard isConnected else { return }
        lastKnownTx = false
        civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
        sendCiv(makeCivData(seq: civPktSeq, sendSeq: civSendSeqB, civData: buildCivFrame(0x1C, 0x00, 0x00)))
    }

    func sendCwMessage(_ text: String) {
        guard isConnected else { return }
        let filtered = String(text.uppercased().filter { "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ /?,.@".contains($0) }.prefix(30))
        guard !filtered.isEmpty else { return }
        let bytes = [0x17] + filtered.unicodeScalars.map { Int($0.value) }
        civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
        sendCiv(makeCivData(seq: civPktSeq, sendSeq: civSendSeqB, civData: buildCivFrame(bytes)))
    }
    private func buildCivFrame(_ bytes: [Int]) -> Data { buildCivFrame(bytes[0], bytes.dropFirst().map{$0}) }
    private func buildCivFrame(_ first: Int, _ rest: [Int]) -> Data {
        var b = Data(count: 4 + 1 + rest.count + 1)
        b[0] = 0xFE; b[1] = 0xFE; b[2] = UInt8(civAddress); b[3] = UInt8(Self.ctrlAddress)
        b[4] = UInt8(first)
        for (i, v) in rest.enumerated() { b[5 + i] = UInt8(v & 0xFF) }
        b[b.count - 1] = 0xFD
        return b
    }

    func stopCwMessage() {
        guard isConnected else { return }
        civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
        sendCiv(makeCivData(seq: civPktSeq, sendSeq: civSendSeqB, civData: buildCivFrame(0x17, 0xFF)))
    }

    func setKeySpeed(_ wpm: Int) {
        guard isConnected else { return }
        let raw = ((wpm.clamped(to: 6...48) - 6) * 255 / 42).clamped(to: 0...255)
        let (b0, b1) = bcdLevelBytes(raw)
        civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
        sendCiv(makeCivData(seq: civPktSeq, sendSeq: civSendSeqB, civData: buildCivFrame(0x14, 0x0C, Int(b0), Int(b1))))
    }

    func getModelName() -> String {
        switch civAddress {
        case 0xA4: "IC-705"; case 0x90: "IC-7300"; case 0xA2: "IC-9700"
        case 0x98: "IC-7610"; case 0x88: "IC-7100"; default: "ICOM (0x\(String(civAddress, radix: 16).uppercased()))"
        }
    }

    // civAddress → supported modes lookup (consulted at connect time)
    private static let modeTable: [Int: [String]] = [
        0xA4: ["LSB","USB","AM","CW","CWR","RTTY","RTTYR","FM","WFM","DSTAR"], // IC-705
        0xA2: ["LSB","USB","AM","CW","CWR","RTTY","RTTYR","FM","WFM","DSTAR"], // IC-9700
        0x88: ["LSB","USB","AM","CW","CWR","RTTY","RTTYR","FM","DSTAR"],        // IC-7100
        0x90: ["LSB","USB","AM","CW","CWR","RTTY","RTTYR","FM"],                // IC-7300
        0x98: ["LSB","USB","AM","CW","CWR","RTTY","RTTYR","FM"],                // IC-7610
    ]
    private static let defaultModes = ["LSB","USB","AM","CW","CWR","RTTY","RTTYR","FM"]

    func getSupportedModes() -> [String] {
        Self.modeTable[civAddress] ?? Self.defaultModes
    }

    // ─────────────────────────────────────────────
    // MARK: - pollStatus (blocking, call from background)
    // ─────────────────────────────────────────────

    func pollStatus() -> CivStatus? {
        guard isConnected else { return nil }
        if !civStreamOpened { drainCivBroadcasts() }
        let now = nowMs()
        let ad = lastAudioDataMs; let ap = lastAudioPingMs

        // Maintenance detection
        if maintenanceMs != 0 || (ad > 0 && now - ad > 3000 && ap > 0 && now - ap < 20_000 && !lastKnownTx) {
            if maintenanceMs == 0 { maintenanceMs = now; mainRetryMs = 0 }
            let elapsed = now - maintenanceMs
            // Ctrl ping watchdog
            if lastCtrlPingMs > 0, now - lastCtrlPingMs > 5000 {
                maintenanceMs = 0; mainRetryMs = 0
                isConnected = false
                postMain { [weak self] in self?.isConnected = false }
                return nil
            }
            if elapsed > 90_000 {
                maintenanceMs = 0; mainRetryMs = 0
                isConnected = false
                postMain { [weak self] in self?.isConnected = false }
                return nil
            }
            if mainRetryMs == 0 || now - mainRetryMs > 3_000 {
                mainRetryMs = now
                if tryAudioOnlyReconnect() { maintenanceMs = 0; mainRetryMs = 0; lastAudioDataMs = now }
            }
            return nil
        }
        maintenanceMs = 0; mainRetryMs = 0

        let inTxOrGrace = txAudioActive || (lastTxAudioMs > 0 && now - lastTxAudioMs < 5_000)
        if lastCtrlPingMs > 0, now - lastCtrlPingMs > 5000, !inTxOrGrace {
            isConnected = false; postMain { [weak self] in self?.isConnected = false }; return nil
        }
        if ap > 0, now - ap > 5000, !inTxOrGrace {
            isConnected = false; postMain { [weak self] in self?.isConnected = false }; return nil
        }

        guard let freq = getFrequency() else {
            if !lastKnownTx {
                pollFailCount += 1
                if pollFailCount == 3 {
                    civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
                    sendCiv(makeOpenClose(seq: civPktSeq, sendSeq: civSendSeqB, open: true, sentId: civMyId, rcvdId: civRemoteId))
                    lastOpenCloseMs = now; civStreamOpened = false; transcieveEnabled = false
                    postMain { [weak self] in self?.civStreamOpened = false }
                }
            }
            return nil
        }
        pollFailCount = 0

        if !transcieveEnabled {
            civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
            sendCiv(makeCivData(seq: civPktSeq, sendSeq: civSendSeqB, civData: buildCivFrame(0x16, 0x02, 0x01)))
            transcieveEnabled = true
        }
        if let hz = pendingFreqHz { _ = setFrequency(hz) }
        if let (m, w) = pendingMode { _ = setMode(m, width: w) }

        let signal = getSmeterSignal()
        let txState = getTxState() ?? lastKnownTx
        lastKnownTx = txState || txAudioActive
        if !lastKnownTx { pttOffPending = false }
        else if pttOffPending, isConnected {
            civPktSeq = (civPktSeq + 1) & 0xFFFF; civSendSeqB = (civSendSeqB + 1) & 0xFFFF
            sendCiv(makeCivData(seq: civPktSeq, sendSeq: civSendSeqB, civData: buildCivFrame(0x1C, 0x00, 0x00)))
        }
        pollModeCount += 1
        let mode  = pollModeCount % 5  == 0 ? getMode()    : nil
        let power = pollModeCount % 10 == 1 ? getRfPower()  : nil
        let sql   = pollModeCount % 10 == 6 ? getSql()      : nil
        let bkIn  = pollModeCount % 10 == 8 ? getBkIn()     : nil
        return CivStatus(freq: freq, signal: signal, tx: lastKnownTx,
                         mode: mode, power: power, sql: sql, bkIn: bkIn)
    }

    private func getTxState() -> Bool? {
        if let body = getCached(0x1C, 0x00), body.count >= 5 { return body[4] == 0x01 }
        guard let r = exchange(buildCivFrame(0x1C, 0x00), cmd: 0x1C, subCmd: 0x00, timeoutMs: 400),
              r.count >= 5 else { return nil }
        return r[4] == 0x01
    }
}

// MARK: - Comparable clamp helper
private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
