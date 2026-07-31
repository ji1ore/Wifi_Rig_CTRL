import SwiftUI

struct ContentView: View {
    @State private var vm = MainViewModel()
    @State private var showSplash = true

    var body: some View {
        ZStack {
            NavigationStack(path: $vm.path) {
                ConnectView(vm: vm)
                    .navigationDestination(for: AppRoute.self) { route in
                        switch route {
                        case .rigSelect: RigSelectView(vm: vm)
                        case .mainControl: MainControlView(vm: vm)
                        case .pttSettings: PttSettingsView(vm: vm)
                        case .aprsSettings: AprsSettingsView(vm: vm)
                        case .ft8: Ft8View(vm: vm)
                        case .profiles: ProfileListView(vm: vm)
                        case .bleKeyer: BleKeyerView(vm: vm)
                        case .about: AboutView(vm: vm)
                        case .cw: CwView(vm: vm)
                        case .admin: AdminView(vm: vm)
                        }
                    }
            }
            .tint(.indigo)
            if showSplash {
                SplashView { withAnimation { showSplash = false } }
                    .transition(.opacity)
                    .zIndex(1)
            }
        }
    }
}

#Preview {
    ContentView()
}
