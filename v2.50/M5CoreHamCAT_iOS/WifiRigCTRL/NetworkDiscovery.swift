import Foundation

struct DiscoveredHost: Identifiable {
    let id = UUID()
    let ip: String
    let hostname: String
    let apiPort: Int
    let audioPort: Int
    var label: String { "\(hostname)  (\(ip))" }
}

/// Androidクライアントと同じUDPブロードキャストプロトコルでPiを検索する。
/// "WIFI_RIG_CTRL_DISCOVER" をUDP 5001番ポートにブロードキャストし、
/// "WIFI_RIG_CTRL_HERE:hostname:apiPort:audioPort" レスポンスを収集する。
func discoverWifiRigCtrlHosts() async -> [DiscoveredHost] {
    await withCheckedContinuation { continuation in
        DispatchQueue.global(qos: .userInitiated).async {
            continuation.resume(returning: _runDiscovery())
        }
    }
}

private func _runDiscovery(timeoutSec: Double = 2.5) -> [DiscoveredHost] {
    let udpPort: UInt16 = 5001
    let magic = "WIFI_RIG_CTRL_DISCOVER"
    var found: [DiscoveredHost] = []

    let sockfd = socket(AF_INET, SOCK_DGRAM, 0)
    guard sockfd >= 0 else { return [] }
    defer { close(sockfd) }

    // ブロードキャスト有効化
    var on: Int32 = 1
    setsockopt(sockfd, SOL_SOCKET, SO_BROADCAST, &on, socklen_t(MemoryLayout<Int32>.size))
    setsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR,  &on, socklen_t(MemoryLayout<Int32>.size))

    // INADDR_ANY:0 に明示 bind → 受信用のローカルポートを確定させる
    var local = sockaddr_in()
    local.sin_len    = UInt8(MemoryLayout<sockaddr_in>.size)
    local.sin_family = sa_family_t(AF_INET)
    local.sin_port   = 0   // OS が空きポートを割り当て
    local.sin_addr.s_addr = 0  // INADDR_ANY
    _ = withUnsafePointer(to: &local) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            bind(sockfd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }

    // ノンブロッキングモードに設定（SO_RCVTIMEO が iOS で効かない場合の対策）
    let fl = fcntl(sockfd, F_GETFL, 0)
    if fl >= 0 { _ = fcntl(sockfd, F_SETFL, fl | O_NONBLOCK) }

    // ブロードキャスト送信先リスト
    // 1. 255.255.255.255（限定ブロードキャスト）
    // 2. 各 NIC のサブネットブロードキャスト（Android と同様）
    var targets: [sockaddr_in] = [_makeSockAddrIn(ip: UInt32.max, port: udpPort)]
    _collectIfBroadcasts(port: udpPort, into: &targets)

    // 全ターゲットへ送信
    magic.withCString { cstr in
        let len = strlen(cstr)
        for var dest in targets {
            _ = withUnsafePointer(to: &dest) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(sockfd, cstr, len, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }

    // 受信ループ（デッドラインまで 20ms ポーリング）
    var buf    = [UInt8](repeating: 0, count: 512)
    var src    = sockaddr_in()
    var srcLen = socklen_t(MemoryLayout<sockaddr_in>.size)
    let deadline = Date().addingTimeInterval(timeoutSec)

    while Date() < deadline {
        srcLen = socklen_t(MemoryLayout<sockaddr_in>.size)
        let n = buf.withUnsafeMutableBufferPointer { bp in
            withUnsafeMutablePointer(to: &src) { sp in
                sp.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    recvfrom(sockfd, bp.baseAddress, bp.count - 1, 0, $0, &srcLen)
                }
            }
        }
        if n <= 0 {
            // ノンブロッキングなので EAGAIN は正常（データなし）
            usleep(20_000)  // 20ms 待って再試行
            continue
        }

        let msg = String(bytes: buf[0..<n], encoding: .utf8) ?? ""
        guard msg.hasPrefix("WIFI_RIG_CTRL_HERE:") else { continue }

        let payload = String(msg.dropFirst("WIFI_RIG_CTRL_HERE:".count))
        let parts   = payload.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        let hostname  = parts.first ?? "unknown"
        let apiPort   = parts.count > 1 ? Int(parts[1]) ?? 8000 : 8000
        let audioPort = parts.count > 2 ? Int(parts[2]) ?? 8000 : 8000

        var ipBuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        let ip: String = withUnsafePointer(to: src.sin_addr) {
            guard inet_ntop(AF_INET, $0, &ipBuf, socklen_t(INET_ADDRSTRLEN)) != nil else { return "" }
            return String(cString: ipBuf)
        }
        guard !ip.isEmpty, !found.contains(where: { $0.ip == ip }) else { continue }
        found.append(DiscoveredHost(ip: ip, hostname: hostname,
                                    apiPort: apiPort, audioPort: audioPort))
    }

    return found
}

// MARK: - Helpers

private func _makeSockAddrIn(ip: UInt32, port: UInt16) -> sockaddr_in {
    var addr = sockaddr_in()
    addr.sin_len    = UInt8(MemoryLayout<sockaddr_in>.size)
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_port   = port.bigEndian
    addr.sin_addr.s_addr = ip  // 255.255.255.255 はバイトオーダー不変
    return addr
}

/// getifaddrs で各 NIC のブロードキャストアドレスを収集する（Android の実装と同等）。
/// ifa_broadaddr は Swift から直接アクセスできない場合があるため、
/// IP アドレスとネットマスクからブロードキャストを計算する。
private func _collectIfBroadcasts(port: UInt16, into targets: inout [sockaddr_in]) {
    var ifap: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifap) == 0, let head = ifap else { return }
    defer { freeifaddrs(head) }

    var cur: UnsafeMutablePointer<ifaddrs>? = head
    while let iface = cur {
        defer { cur = iface.pointee.ifa_next }
        let flags = iface.pointee.ifa_flags
        // UP かつ ループバックでなく ブロードキャスト対応のインターフェースのみ
        guard flags & UInt32(IFF_UP)        != 0,
              flags & UInt32(IFF_LOOPBACK)  == 0,
              flags & UInt32(IFF_BROADCAST) != 0,
              let addrPtr = iface.pointee.ifa_addr,
              addrPtr.pointee.sa_family == sa_family_t(AF_INET),
              let maskPtr = iface.pointee.ifa_netmask,
              maskPtr.pointee.sa_family == sa_family_t(AF_INET)
        else { continue }

        // sin_addr.s_addr はネットワークバイトオーダーのまま演算して OK
        let ifIP: UInt32 = addrPtr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
            $0.pointee.sin_addr.s_addr
        }
        let mask: UInt32 = maskPtr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
            $0.pointee.sin_addr.s_addr
        }
        // broadcast = (ip & mask) | ~mask
        let bcastIP = (ifIP & mask) | (~mask)

        var addr = sockaddr_in()
        addr.sin_len    = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port   = port.bigEndian
        addr.sin_addr.s_addr = bcastIP

        if !targets.contains(where: { $0.sin_addr.s_addr == bcastIP }) {
            targets.append(addr)
        }
    }
}
