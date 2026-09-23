import Foundation
import Network

/// WiFi UDP PTT for M5Atom — matches Android UdpPttService protocol.
/// Time-sync request: [0xE0][clientMs:UInt32 BE] (5 bytes)
/// Time-sync reply:   [0xE1][clientMs:UInt32 BE][serverMs:UInt32 BE] (9 bytes)
/// PTT packet:        [state:1=ON|0=OFF][trxSel=0x01][opTimeMs:Int64 BE] (10 bytes)
actor PttService {
    private var connection: NWConnection?
    private var host: String = ""
    private var port: UInt16 = UInt16(AppConstants.defaultPttPort)
    private var m5TimeOffset: Int64 = 0
    private var synced: Bool = false

    /// Anchor for client time; monotonic seconds since service start, *1000 → ms.
    private let startTime = Date()
    private var clientMs: Int64 { Int64(Date().timeIntervalSince(startTime) * 1000.0) }

    func connect(host: String, port: Int) async {
        await disconnect()
        self.host = host
        self.port = UInt16(port)
        self.synced = false
        self.m5TimeOffset = 0

        let nwHost = NWEndpoint.Host(host)
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else { return }
        let params = NWParameters.udp
        let conn = NWConnection(host: nwHost, port: nwPort, using: params)
        connection = conn
        conn.start(queue: .global())
        // NWConnection queues sends until ready; no need to await readiness.
        receiveLoop()
        await sendTimeSync()
    }

    func disconnect() async {
        connection?.cancel()
        connection = nil
        synced = false
    }

    private func receiveLoop() {
        guard let conn = connection else { return }
        conn.receiveMessage { [weak self] data, _, _, error in
            guard let self else { return }
            Task { await self.handleReceive(data: data, error: error) }
        }
    }

    private func handleReceive(data: Data?, error: NWError?) {
        if let data, data.count == 9, data[0] == 0xE1 {
            let clientEcho = Int64(bigEndianUInt32(data, offset: 1))
            let serverMs = Int64(bigEndianUInt32(data, offset: 5))
            let now = clientMs
            // Use round-trip-corrected offset
            let rtt = max(0, now - clientEcho)
            m5TimeOffset = serverMs - now + rtt / 2
            synced = true
        }
        receiveLoop()
    }

    private func bigEndianUInt32(_ data: Data, offset: Int) -> UInt32 {
        var v: UInt32 = 0
        for i in 0..<4 { v = (v << 8) | UInt32(data[offset + i]) }
        return v
    }

    private func sendTimeSync() async {
        guard let conn = connection else { return }
        let now = UInt32(truncatingIfNeeded: clientMs)
        var pkt = Data(count: 5)
        pkt[0] = 0xE0
        pkt[1] = UInt8((now >> 24) & 0xFF)
        pkt[2] = UInt8((now >> 16) & 0xFF)
        pkt[3] = UInt8((now >> 8) & 0xFF)
        pkt[4] = UInt8(now & 0xFF)
        conn.send(content: pkt, completion: .contentProcessed { _ in })
    }

    /// Send PTT to M5Atom. ON sent once; OFF sent 5x at 60 ms intervals.
    func sendPtt(on: Bool, trxSel: UInt8 = 0x01) async {
        if on {
            await sendKeyPacket(on: true, trxSel: trxSel)
        } else {
            for i in 0..<5 {
                await sendKeyPacket(on: false, trxSel: trxSel)
                if i < 4 { try? await Task.sleep(nanoseconds: 60_000_000) }
            }
        }
    }

    private func sendKeyPacket(on: Bool, trxSel: UInt8) async {
        guard let conn = connection else { return }
        let opTimeMs: Int64 = synced ? (clientMs + m5TimeOffset) : 0
        var pkt = Data(count: 10)
        pkt[0] = on ? 0x01 : 0x00
        pkt[1] = trxSel
        let ts = UInt64(bitPattern: opTimeMs)
        for i in 0..<8 {
            pkt[2 + i] = UInt8((ts >> (UInt64(8 * (7 - i)))) & 0xFF)
        }
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            conn.send(content: pkt, completion: .contentProcessed { _ in cont.resume() })
        }
    }

    var isSynced: Bool { synced }
}
