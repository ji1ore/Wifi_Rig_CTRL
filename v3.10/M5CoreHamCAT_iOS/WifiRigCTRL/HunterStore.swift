import Foundation
import Observation

// Per-park hunt record. Mirrors Android's HunterPark (ref + locationDesc + qsos).
struct HunterPark: Codable {
    var reference: String
    var locationDesc: String   // "short" in POTA API  (e.g. "JP-TK")
    var qsos: Int
}

@MainActor
@Observable
final class HunterStore {
    private(set) var parkCount: Int = 0
    private var cache: Set<String> = []
    private let storeURL: URL

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        storeURL = docs.appendingPathComponent("pota_hunter_parks.json")
        load()
    }

    func isHunted(_ reference: String) -> Bool {
        cache.contains(reference.uppercased())
    }

    // Import from /hunter/{callsign} or /user/logbook JSON.
    // Each element should have "reference" (required), "short" (locationDesc), "qsos".
    // parkCount mirrors Android: number of input entries (not unique refs).
    @discardableResult
    func importFromApiJson(_ json: String) -> Int {
        guard let data = json.data(using: .utf8),
              let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return 0 }

        var parks: [HunterPark] = []
        var newCache = Set<String>()

        for entry in arr {
            guard let ref = (entry["reference"] as? String)?.uppercased(), !ref.isEmpty else { continue }
            let loc  = entry["short"] as? String ?? ""
            let qsos = (entry["qsos"] as? Int) ?? Int((entry["qsos"] as? Double) ?? 0)
            parks.append(HunterPark(reference: ref, locationDesc: loc, qsos: qsos))
            newCache.insert(ref)
            if !loc.isEmpty {
                // Store "JP-0014|JP-TK" so future location-specific checks can work
                newCache.insert("\(ref)|\(loc)")
                // Handle comma-separated multi-location ("JP-TK,JP-KN")
                if loc.contains(",") {
                    for part in loc.split(separator: ",").map({ $0.trimmingCharacters(in: .whitespaces) }) where !part.isEmpty {
                        newCache.insert("\(ref)|\(part)")
                    }
                }
            }
        }

        guard !parks.isEmpty else { return 0 }
        cache = newCache
        parkCount = parks.count     // matches Android: parks.size
        save(parks)
        return parkCount
    }

    private func save(_ parks: [HunterPark]) {
        if let data = try? JSONEncoder().encode(parks) {
            try? data.write(to: storeURL)
        }
    }

    private func load() {
        // Try new Codable format first
        if let data = try? Data(contentsOf: storeURL),
           let parks = try? JSONDecoder().decode([HunterPark].self, from: data) {
            cache = buildCache(parks)
            parkCount = parks.count
            return
        }
        // Legacy fallback: old format was [{"reference":"JP-0001"}]
        if let data = try? Data(contentsOf: storeURL),
           let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] {
            let parks = arr.compactMap { entry -> HunterPark? in
                guard let ref = (entry["reference"] as? String)?.uppercased(), !ref.isEmpty else { return nil }
                return HunterPark(reference: ref, locationDesc: entry["short"] as? String ?? "", qsos: 0)
            }
            cache = buildCache(parks)
            parkCount = parks.count
        }
    }

    private func buildCache(_ parks: [HunterPark]) -> Set<String> {
        var set = Set<String>()
        for p in parks {
            set.insert(p.reference)
            if !p.locationDesc.isEmpty {
                set.insert("\(p.reference)|\(p.locationDesc)")
                if p.locationDesc.contains(",") {
                    for part in p.locationDesc.split(separator: ",").map({ $0.trimmingCharacters(in: .whitespaces) }) where !part.isEmpty {
                        set.insert("\(p.reference)|\(part)")
                    }
                }
            }
        }
        return set
    }
}
