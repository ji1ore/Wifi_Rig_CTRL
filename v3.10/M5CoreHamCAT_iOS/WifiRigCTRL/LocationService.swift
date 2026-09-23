import Foundation
import CoreLocation

/// Lightweight CLLocationManager wrapper that publishes the latest fix via async stream.
@MainActor
final class LocationService: NSObject {
    private let manager = CLLocationManager()
    private(set) var latest: CLLocation?
    private var continuation: AsyncStream<CLLocation>.Continuation?
    var updates: AsyncStream<CLLocation>!

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.distanceFilter = 10
        updates = AsyncStream { cont in self.continuation = cont }
    }

    func requestAuthorization() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        default: break
        }
    }

    func start() {
        requestAuthorization()
        manager.startUpdatingLocation()
    }

    func stop() {
        manager.stopUpdatingLocation()
    }

    var isAuthorized: Bool {
        let s = manager.authorizationStatus
        return s == .authorizedWhenInUse || s == .authorizedAlways
    }
}

extension LocationService: CLLocationManagerDelegate {
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        Task { @MainActor in
            self.latest = loc
            self.continuation?.yield(loc)
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            if self.isAuthorized {
                self.manager.startUpdatingLocation()
            }
        }
    }
}
