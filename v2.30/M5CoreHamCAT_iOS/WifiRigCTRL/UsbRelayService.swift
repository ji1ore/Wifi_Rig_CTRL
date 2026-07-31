import Foundation
import Darwin
@preconcurrency import Network

/// Bridges the AtomS3Lite Client (USB-NCM on iPhone) ⇔ Raspberry Pi `cw_bridge_ncm.py`.
///
/// Topology:
/// ```
/// [Atom Client] ─USB-NCM(UDP 8888)─ [iPhone] ─WiFi(UDP 8889)─ [Pi bridge] ─NCM(UDP 8888)─ [Atom Server]
/// ```
///
/// ESP32 (Atom) firmware constants (Clie_main.cpp v1.42):
/// - ESP32 IP: `192.168.7.1`
/// - iOS-side IP (DHCP): `192.168.7.2`
/// - Subnet: `255.255.255.252` (/30, 2-host subnet)
///
/// Per Mac-side hand-off notes:
/// 1. Use `getifaddrs()` to find the interface that actually carries 192.168.7.x — iOS
///    typed-path filtering (`.wiredEthernet`) doesn't always identify the USB-NCM link
///    when the link is up but DHCP hasn't completed. Active polling of getifaddrs is
///    the reliable check.
/// 2. Explicitly bind the UDP listener to the local 192.168.7.2:8888 endpoint so that
///    iOS routes through the USB-NCM netif (not the WiFi default route).
/// 3. Use `requiredInterface` (by NWInterface) for outbound to 192.168.7.1:8888 so the
///    Pi→Client reply leaves the USB-NCM interface.
@MainActor
@Observable
final class UsbRelayService {
    struct UsbStatus {
        var interfaceName: String?
        var localIPv4: String?      // The iPhone-side IP discovered via getifaddrs (e.g. 192.168.7.2)
        var available: Bool
        var rxFromClient: Int
        var txToClient: Int
        var lastClientPacketAt: Date?
    }

    struct PiStatus {
        var host: String
        var port: Int
        var connected: Bool
        var rxFromPi: Int
        var txToPi: Int
        var lastPiPacketAt: Date?
    }

    private(set) var usb: UsbStatus = .init(interfaceName: nil, localIPv4: nil, available: false, rxFromClient: 0, txToClient: 0, lastClientPacketAt: nil)
    private(set) var pi: PiStatus
    private(set) var isRunning: Bool = false
    private(set) var lastError: String?

    private static let ncmPort: NWEndpoint.Port = 8888
    private static let atomDeviceIP: String = "192.168.7.1"
    private static let expectedLocalPrefix = "192.168.7."   // Match anything in our /24-or-narrower scope

    private let pathQueue = DispatchQueue(label: "atom.bridge.path")

    private var monitor: NWPathMonitor?
    private var ncmInterface: NWInterface?
    private var ncmListener: NWListener?
    private var ncmOut: NWConnection?   // iPhone NCM → Client (192.168.7.1:8888)
    private var piOut: NWConnection?    // iPhone WiFi → Pi (piHost:piPort)
    private var ifaceScanTask: Task<Void, Never>?

    init(piHost: String = "", piPort: Int = AppConstants.atomBridgePort) {
        self.pi = .init(host: piHost, port: piPort, connected: false, rxFromPi: 0, txToPi: 0, lastPiPacketAt: nil)
    }

    func configure(piHost: String, piPort: Int) {
        pi.host = piHost
        pi.port = piPort
    }

    func start() {
        guard !isRunning else { return }
        isRunning = true
        lastError = nil

        // 1. NWPathMonitor — passive interface change notifications.
        let m = NWPathMonitor()
        monitor = m
        m.pathUpdateHandler = { [weak self] path in
            Task { @MainActor [weak self] in self?.applyPath(path) }
        }
        m.start(queue: pathQueue)

        // 2. Active getifaddrs() poll — primary detection mechanism.
        // iOS doesn't always report USB-NCM links as `.wiredEthernet` with the IP
        // attached; polling getifaddrs() catches the DHCP-assigned 192.168.7.2.
        ifaceScanTask?.cancel()
        ifaceScanTask = Task { [weak self] in
            while let self, !Task.isCancelled, self.isRunning {
                self.scanLocalInterfacesAndAttach()
                try? await Task.sleep(nanoseconds: 1_000_000_000)  // 1 s
            }
        }

        // 3. Outbound NWConnection to Pi (via WiFi).
        openPiConnection()
    }

    func stop() {
        isRunning = false
        ifaceScanTask?.cancel(); ifaceScanTask = nil
        monitor?.cancel(); monitor = nil
        ncmListener?.cancel(); ncmListener = nil
        ncmOut?.cancel(); ncmOut = nil
        piOut?.cancel(); piOut = nil
        ncmInterface = nil
        usb.available = false
        usb.interfaceName = nil
        usb.localIPv4 = nil
        pi.connected = false
    }

    // MARK: - Interface discovery (getifaddrs)

    /// One-shot scan: find the network interface that has an IPv4 in 192.168.7.x.
    /// Returns (interfaceName, ipv4) or nil if not present.
    private func discoverNcmInterface() -> (name: String, ip: String)? {
        var ifap: UnsafeMutablePointer<ifaddrs>? = nil
        guard getifaddrs(&ifap) == 0, let head = ifap else { return nil }
        defer { freeifaddrs(head) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = head
        while let cur = ptr {
            defer { ptr = cur.pointee.ifa_next }
            guard let sa = cur.pointee.ifa_addr, sa.pointee.sa_family == sa_family_t(AF_INET) else { continue }
            let nameStr = String(cString: cur.pointee.ifa_name)
            // Skip loopback / inactive
            if nameStr == "lo0" { continue }
            let flags = Int32(cur.pointee.ifa_flags)
            if (flags & IFF_UP) == 0 { continue }

            var hostBuf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let r = getnameinfo(sa, socklen_t(MemoryLayout<sockaddr_in>.size),
                                 &hostBuf, socklen_t(hostBuf.count),
                                 nil, 0, NI_NUMERICHOST)
            guard r == 0 else { continue }
            let ip = String(cString: hostBuf)
            if ip.hasPrefix(Self.expectedLocalPrefix) {
                return (nameStr, ip)
            }
        }
        return nil
    }

    /// Active poll callback — keep our NCM listener / out-conn aligned with reality.
    private func scanLocalInterfacesAndAttach() {
        guard isRunning else { return }
        let found = discoverNcmInterface()

        if let (name, ip) = found {
            // Already attached to the same interface? nothing to do (except sync UI).
            if usb.available && usb.interfaceName == name && usb.localIPv4 == ip { return }
            // Look up the matching NWInterface via current path snapshot.
            if let nif = monitor?.currentPath.availableInterfaces.first(where: { $0.name == name }) {
                attachNCM(nif, localIP: ip)
            } else {
                // Interface name not in NWPathMonitor list — still attach with name-only fallback.
                usb.available = true
                usb.interfaceName = name
                usb.localIPv4 = ip
                attachNCMByName(name, localIP: ip)
            }
        } else {
            // No 192.168.7.x interface present.
            if usb.available {
                usb.available = false
                usb.interfaceName = nil
                usb.localIPv4 = nil
                ncmListener?.cancel(); ncmListener = nil
                ncmOut?.cancel(); ncmOut = nil
                ncmInterface = nil
            }
        }
    }

    /// NWPathMonitor passive update — informational only, the getifaddrs poll is authoritative.
    private func applyPath(_ path: NWPath) {
        // Trigger an immediate scan when topology changes.
        scanLocalInterfacesAndAttach()
    }

    // MARK: - NCM listener / outbound (USB side)

    private func attachNCM(_ nif: NWInterface, localIP: String) {
        ncmListener?.cancel()
        ncmOut?.cancel()
        ncmInterface = nif
        usb.available = true
        usb.interfaceName = nif.name
        usb.localIPv4 = localIP

        let listenParams = NWParameters.udp
        listenParams.requiredInterface = nif
        listenParams.allowLocalEndpointReuse = true
        listenParams.prohibitedInterfaceTypes = [.cellular, .wifi]
        // Bind to the explicit local IP:port so the listener accepts only NCM traffic.
        if let localHostPort = NWEndpoint.Port(rawValue: 8888) {
            listenParams.requiredLocalEndpoint = .hostPort(host: NWEndpoint.Host(localIP), port: localHostPort)
        }

        do {
            let listener = try NWListener(using: listenParams, on: Self.ncmPort)
            listener.newConnectionHandler = { [weak self] conn in
                Task { @MainActor [weak self] in
                    self?.handleNcmIncoming(conn)
                }
            }
            listener.stateUpdateHandler = { [weak self] state in
                if case .failed(let err) = state {
                    Task { @MainActor [weak self] in
                        self?.lastError = "NCM listener: \(err.localizedDescription)"
                    }
                }
            }
            listener.start(queue: pathQueue)
            self.ncmListener = listener
        } catch {
            lastError = "NCM listen failed on \(nif.name) (\(localIP)): \(error.localizedDescription)"
        }

        // Outbound to Atom Client.
        let outParams = NWParameters.udp
        outParams.requiredInterface = nif
        outParams.allowLocalEndpointReuse = true
        outParams.prohibitedInterfaceTypes = [.cellular, .wifi]
        let out = NWConnection(
            host: NWEndpoint.Host(Self.atomDeviceIP),
            port: Self.ncmPort,
            using: outParams
        )
        out.start(queue: pathQueue)
        self.ncmOut = out
    }

    /// Fallback path when the interface is visible via getifaddrs() but NWPathMonitor
    /// hasn't surfaced an NWInterface object yet. Uses `requiredInterfaceType` instead.
    private func attachNCMByName(_ name: String, localIP: String) {
        ncmListener?.cancel()
        ncmOut?.cancel()
        ncmInterface = nil

        let listenParams = NWParameters.udp
        listenParams.allowLocalEndpointReuse = true
        listenParams.prohibitedInterfaceTypes = [.cellular, .wifi]
        if let localHostPort = NWEndpoint.Port(rawValue: 8888) {
            listenParams.requiredLocalEndpoint = .hostPort(host: NWEndpoint.Host(localIP), port: localHostPort)
        }

        do {
            let listener = try NWListener(using: listenParams, on: Self.ncmPort)
            listener.newConnectionHandler = { [weak self] conn in
                Task { @MainActor [weak self] in self?.handleNcmIncoming(conn) }
            }
            listener.start(queue: pathQueue)
            self.ncmListener = listener
        } catch {
            lastError = "NCM listen failed by-name \(name) (\(localIP)): \(error.localizedDescription)"
        }

        let outParams = NWParameters.udp
        outParams.allowLocalEndpointReuse = true
        outParams.prohibitedInterfaceTypes = [.cellular, .wifi]
        // Bind source to our discovered local IP so the kernel uses the right netif.
        if let localHostPort = NWEndpoint.Port(rawValue: 0) {
            outParams.requiredLocalEndpoint = .hostPort(host: NWEndpoint.Host(localIP), port: localHostPort)
        }
        let out = NWConnection(
            host: NWEndpoint.Host(Self.atomDeviceIP),
            port: Self.ncmPort,
            using: outParams
        )
        out.start(queue: pathQueue)
        self.ncmOut = out
    }

    private func handleNcmIncoming(_ conn: NWConnection) {
        conn.start(queue: pathQueue)
        receiveLoopNcm(on: conn)
    }

    nonisolated private func receiveLoopNcm(on conn: NWConnection) {
        conn.receiveMessage { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                Task { @MainActor [weak self] in self?.forwardClientToPi(data) }
            }
            if error == nil, !isComplete { self.receiveLoopNcm(on: conn) }
        }
    }

    private func forwardClientToPi(_ data: Data) {
        usb.rxFromClient += 1
        usb.lastClientPacketAt = Date()
        guard let out = piOut else { return }
        out.send(content: data, completion: .contentProcessed { _ in })
        pi.txToPi += 1
    }

    // MARK: - Pi (WiFi) side

    private func openPiConnection() {
        piOut?.cancel()
        let host = NWEndpoint.Host(pi.host)
        guard let port = NWEndpoint.Port(rawValue: UInt16(pi.port)) else {
            lastError = "Invalid Pi port: \(pi.port)"
            return
        }
        let params = NWParameters.udp
        params.prohibitedInterfaceTypes = [.wiredEthernet, .cellular]
        let conn = NWConnection(host: host, port: port, using: params)
        conn.stateUpdateHandler = { [weak self] state in
            Task { @MainActor [weak self] in
                guard let self else { return }
                switch state {
                case .ready: self.pi.connected = true
                case .failed(let err):
                    self.pi.connected = false
                    self.lastError = "Pi conn: \(err.localizedDescription)"
                case .cancelled: self.pi.connected = false
                default: break
                }
            }
        }
        conn.start(queue: pathQueue)
        self.piOut = conn
        receiveLoopPi(on: conn)
    }

    nonisolated private func receiveLoopPi(on conn: NWConnection) {
        conn.receiveMessage { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                Task { @MainActor [weak self] in self?.forwardPiToClient(data) }
            }
            if error == nil, !isComplete { self.receiveLoopPi(on: conn) }
        }
    }

    private func forwardPiToClient(_ data: Data) {
        pi.rxFromPi += 1
        pi.lastPiPacketAt = Date()
        guard let out = ncmOut else { return }
        out.send(content: data, completion: .contentProcessed { _ in })
        usb.txToClient += 1
    }
}
