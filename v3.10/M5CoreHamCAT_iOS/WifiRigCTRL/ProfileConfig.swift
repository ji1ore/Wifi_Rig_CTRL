import Foundation

/// Single saved connection profile. Mirrors the Android `ProfileConfig` data class.
struct ProfileConfig: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var name: String = "Profile 1"
    // ── ラズパイ接続 ──
    var hostName: String = ""
    var apiPort: Int = AppConstants.defaultApiPort
    var audioPort: Int = AppConstants.defaultAudioPort
    var useMDNS: Bool = false
    var apiKey: String = ""
    var savedRigId: Int = -1
    var savedCat: String = ""
    var savedPttDevice: String = "NONE"
    var savedPttType: String = "RTS"
    var savedBaudIndex: Int = AppConstants.defaultBaudIndex
    var savedSamplingIndex: Int = AppConstants.defaultSamplingIndex
    var savedTimeoutIndex: Int = AppConstants.defaultScreenTimeoutIndex
    var useWifiPTT: Bool = false
    var pttHost: String = ""
    var pttPort: Int = AppConstants.defaultPttPort
    var cwDelayMs: Int = 0
    var cwFmDelayMs: Int = 0
    var alsaDevice: String = ""
    var alsaDeviceFt8: String = ""
    // ── CI-V 直接接続 ──
    var useCivMode: Bool = false
    var civHost: String = ""
    var civUsername: String = "user"
    var civPassword: String = ""
    var civPort1: Int = 50001
    var civPort2: Int = 50002
    var civPort3: Int = 50003
    var civAddressHex: String = "A4"

    /// プロファイル一覧に表示するサブタイトル
    var subtitle: String {
        useCivMode ? "CI-V: \(civHost)" : "\(hostName):\(apiPort)"
    }
}

/// Persists the profile list in UserDefaults as a single JSON blob.
enum ProfileStore {
    private static let listKey = "profiles.list"
    private static let activeKey = "profiles.active"

    static func loadAll() -> [ProfileConfig] {
        guard let data = UserDefaults.standard.data(forKey: listKey),
              let arr = try? JSONDecoder().decode([ProfileConfig].self, from: data)
        else { return [] }
        return arr
    }

    static func saveAll(_ list: [ProfileConfig]) {
        guard let data = try? JSONEncoder().encode(list) else { return }
        UserDefaults.standard.set(data, forKey: listKey)
    }

    static var activeId: UUID? {
        get {
            guard let s = UserDefaults.standard.string(forKey: activeKey),
                  let id = UUID(uuidString: s) else { return nil }
            return id
        }
        set {
            if let v = newValue {
                UserDefaults.standard.set(v.uuidString, forKey: activeKey)
            } else {
                UserDefaults.standard.removeObject(forKey: activeKey)
            }
        }
    }
}
