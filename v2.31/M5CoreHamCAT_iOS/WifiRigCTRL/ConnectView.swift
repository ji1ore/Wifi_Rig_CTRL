import SwiftUI

struct ConnectView: View {
    @Bindable var vm: MainViewModel

    @State private var apiPortText: String = ""
    @State private var audioPortText: String = ""
    @State private var civPort1Text: String = ""
    @State private var civPort2Text: String = ""
    @State private var civPort3Text: String = ""
    @State private var gridText: String = ""
    @State private var localError: String?
    @State private var showSaveDialog: Bool = false
    @State private var newProfileName: String = ""
    @State private var isScanning: Bool = false
    @State private var discoveredHosts: [DiscoveredHost] = []
    @State private var showDiscoveryResults: Bool = false
    @State private var showNoHostsAlert: Bool = false

    @ViewBuilder
    private var demoSection: some View {
        Section(header: Label("Demo", systemImage: "play.circle.fill").foregroundStyle(.blue)) {
            Button {
                vm.loadMockData()
                vm.path.append(.rigSelect)
            } label: {
                Label("Skip with mock data", systemImage: "forward.fill")
            }
            .foregroundStyle(.blue)
        }
    }

    var body: some View {
        Form {
            // Profile selection
            if !vm.profiles.isEmpty {
                Section(header: Label("Saved Profiles", systemImage: "person.crop.circle.fill").foregroundStyle(.indigo)) {
                    ForEach(vm.profiles) { p in
                        Button {
                            vm.loadProfile(p)
                            apiPortText = String(vm.apiPort)
                            audioPortText = String(vm.audioPort)
                        } label: {
                            HStack {
                                Image(systemName: ProfileStore.activeId == p.id ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(ProfileStore.activeId == p.id ? .green : .secondary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(p.name).font(.body)
                                    Text("\(p.hostName):\(p.apiPort)")
                                        .font(.caption2.monospaced())
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    Button {
                        vm.path.append(.profiles)
                    } label: {
                        Label("Manage Profiles…", systemImage: "list.bullet.rectangle")
                    }
                }
            }

            // ─── 接続モード選択 ───────────────────────────────
            Section(header: Label("接続モード", systemImage: "antenna.radiowaves.left.and.right").foregroundStyle(.indigo)) {
                Picker("接続モード", selection: $vm.useCivMode) {
                    Text("ラズパイ経由").tag(false)
                    Text("CI-V 直接 (IC-705)").tag(true)
                }
                .pickerStyle(.segmented)
                .onChange(of: vm.useCivMode) { _, _ in vm.persistConnectionSettings() }
            }

            // ─── ステーション設定 (FT8 / CW TX 共通) ──────────
            Section(header: Label("Station", systemImage: "person.wave.2.fill").foregroundStyle(.indigo)) {
                LabeledContent("Callsign") {
                    TextField("JA0ABC", text: $vm.ft8MyCall)
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.asciiCapable)
                        .textInputAutocapitalization(.never)
                        .disableAutocorrection(true)
                        .onChange(of: vm.ft8MyCall) { _, _ in vm.persistFt8Settings() }
                }
                if !vm.useCivMode {
                    LabeledContent("Grid Locator") {
                        TextField("PM74", text: $gridText)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.asciiCapable)
                            .textInputAutocapitalization(.characters)
                            .disableAutocorrection(true)
                            .onChange(of: gridText) { _, v in
                                vm.ft8MyGrid = v.uppercased()
                                vm.persistFt8Settings()
                            }
                    }
                }
            }

            // ─── ラズパイ接続設定 ─────────────────────────────
            if !vm.useCivMode {
                Section(header: Label("Connection Settings", systemImage: "network").foregroundStyle(.indigo)) {
                    LabeledContent("Host") {
                        TextField("raspberrypi.local", text: $vm.hostName)
                            .multilineTextAlignment(.trailing)
                            .textContentType(.URL)
                            .keyboardType(.URL)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                    }

                    Button {
                        Task { await scanHosts() }
                    } label: {
                        HStack {
                            if isScanning {
                                ProgressView().padding(.trailing, 4)
                            }
                            Label(isScanning ? "検索中..." : "ネットワーク検索",
                                  systemImage: "magnifyingglass")
                        }
                    }
                    .disabled(isScanning)

                    Toggle("Use mDNS (.local)", isOn: $vm.useMDNS)
                        .onChange(of: vm.useMDNS) { _, _ in vm.applyMDNSSuffix() }

                    LabeledContent("API Port") {
                        TextField("8000", text: $apiPortText)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numberPad)
                            .onChange(of: apiPortText) { _, new in
                                if let p = Int(new) { vm.apiPort = p }
                            }
                    }
                    LabeledContent("Audio Port") {
                        TextField("50000", text: $audioPortText)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numberPad)
                            .onChange(of: audioPortText) { _, new in
                                if let p = Int(new) { vm.audioPort = p }
                            }
                    }
                    LabeledContent("API Key") {
                        SecureField("optional", text: $vm.apiKey)
                            .multilineTextAlignment(.trailing)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                    }
                }

                Section {
                    Button {
                        Task { await connect() }
                    } label: {
                        HStack {
                            if vm.isBusy { ProgressView().padding(.trailing, 4) }
                            Text(vm.isBusy ? "Connecting…" : "Connect")
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(vm.isBusy)

                    Button {
                        newProfileName = vm.hostName.isEmpty ? "Profile \(vm.profiles.count + 1)" : vm.hostName
                        showSaveDialog = true
                    } label: {
                        Label("Save as Profile…", systemImage: "square.and.arrow.down")
                    }
                }
            }

            // ─── CI-V 直接接続設定 ────────────────────────────
            if vm.useCivMode {
                Section(header: Label("CI-V 設定 (IC-705 直接WiFi)", systemImage: "antenna.radiowaves.left.and.right.circle")
                            .foregroundStyle(.teal)) {
                    LabeledContent("IC-705 IPアドレス") {
                        TextField("192.168.1.10", text: $vm.civHost)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.URL)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                    }
                    LabeledContent("ユーザー名") {
                        TextField("user", text: $vm.civUsername)
                            .multilineTextAlignment(.trailing)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                    }
                    LabeledContent("パスワード") {
                        SecureField("password", text: $vm.civPassword)
                            .multilineTextAlignment(.trailing)
                    }
                    LabeledContent("ポート (CTRL)") {
                        TextField("50001", text: $civPort1Text)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numberPad)
                            .onChange(of: civPort1Text) { _, v in if let p = Int(v) { vm.civPort1 = p } }
                    }
                    LabeledContent("ポート (CI-V)") {
                        TextField("50002", text: $civPort2Text)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numberPad)
                            .onChange(of: civPort2Text) { _, v in if let p = Int(v) { vm.civPort2 = p } }
                    }
                    LabeledContent("ポート (Audio)") {
                        TextField("50003", text: $civPort3Text)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numberPad)
                            .onChange(of: civPort3Text) { _, v in if let p = Int(v) { vm.civPort3 = p } }
                    }
                    LabeledContent("CI-V アドレス (HEX)") {
                        TextField("A4", text: $vm.civAddressHex)
                            .multilineTextAlignment(.trailing)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                            .frame(maxWidth: 70)
                    }
                    Text("IC-705: A4 / IC-7300: 94 / IC-9700: A2 / IC-7610: 98")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Section {
                    Button {
                        Task { await civConnect() }
                    } label: {
                        HStack {
                            Spacer()
                            if vm.civConnecting {
                                ProgressView()
                                    .tint(.white)
                                    .padding(.trailing, 6)
                            }
                            Text(vm.civConnecting ? "接続中…" : "CI-V 接続")
                                .fontWeight(.semibold)
                            Spacer()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.teal)
                    .disabled(vm.civConnecting || vm.civHost.trimmingCharacters(in: .whitespaces).isEmpty)

                    if vm.civConnecting {
                        HStack(spacing: 8) {
                            ProgressView()
                                .scaleEffect(0.75)
                            Text(vm.civConnectStep.isEmpty ? String(localized: "接続中…") : vm.civConnectStep)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }

            if let msg = localError ?? vm.civError.nilIfEmpty ?? vm.errorMessage {
                Section { Text(msg).foregroundStyle(.red).font(.callout) }
            }

            demoSection
        }
        .navigationTitle(Text("Wifi_RIG_CTRL"))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        vm.path.append(.profiles)
                    } label: {
                        Label("Profiles", systemImage: "person.crop.circle.fill")
                    }
                    Button {
                        vm.path.append(.bleKeyer)
                    } label: {
                        Label("BLE Keyer (DualKey-BLE)", systemImage: "antenna.radiowaves.left.and.right")
                    }
                    Button {
                        vm.path.append(.admin)
                    } label: {
                        Label("Admin", systemImage: "gearshape.2.fill")
                    }
                    Button {
                        vm.path.append(.about)
                    } label: {
                        Label("About", systemImage: "info.circle.fill")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .onAppear {
            apiPortText = String(vm.apiPort)
            audioPortText = String(vm.audioPort)
            civPort1Text = String(vm.civPort1)
            civPort2Text = String(vm.civPort2)
            civPort3Text = String(vm.civPort3)
            gridText = vm.ft8MyGrid
            if let activeId = ProfileStore.activeId,
               let p = vm.profiles.first(where: { $0.id == activeId }) {
                vm.loadProfile(p)
                apiPortText = String(vm.apiPort)
                audioPortText = String(vm.audioPort)
                gridText = vm.ft8MyGrid
            }
        }
        .confirmationDialog("デバイスを選択", isPresented: $showDiscoveryResults, titleVisibility: .visible) {
            ForEach(discoveredHosts) { host in
                Button(host.label) {
                    vm.hostName = host.ip
                    vm.useMDNS = false
                }
            }
            Button("キャンセル", role: .cancel) {}
        }
        .alert("デバイスが見つかりません", isPresented: $showNoHostsAlert) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("WIFI_RIG_CTRL サーバーが見つかりませんでした。\nPiが起動していてapi.pyが動作していることを確認してください。")
        }
        .alert("Save Profile", isPresented: $showSaveDialog) {
            TextField("Profile name", text: $newProfileName)
                .autocapitalization(.words)
            Button("Cancel", role: .cancel) {}
            Button("Save") {
                let trimmed = newProfileName.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { return }
                let p = vm.snapshotCurrentProfile(name: trimmed)
                vm.profiles.append(p)
                ProfileStore.saveAll(vm.profiles)
                ProfileStore.activeId = p.id
            }
        } message: {
            Text("Save the current connection settings as a reusable profile.")
        }
        // ─── CI-V 接続中オーバーレイ ─────────────────────────
        .overlay {
            if vm.civConnecting {
                ZStack {
                    Color.black.opacity(0.4).ignoresSafeArea()
                    VStack(spacing: 20) {
                        ProgressView()
                            .scaleEffect(1.6)
                            .tint(.white)
                        Text(vm.civConnectStep.isEmpty ? String(localized: "接続中…") : vm.civConnectStep)
                            .font(.callout.weight(.medium))
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: 240)
                    }
                    .padding(32)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
                }
                .allowsHitTesting(true)
            }
        }
    }

    private func scanHosts() async {
        isScanning = true
        let hosts = await discoverWifiRigCtrlHosts()
        isScanning = false
        if hosts.isEmpty {
            showNoHostsAlert = true
        } else {
            discoveredHosts = hosts
            showDiscoveryResults = true
        }
    }

    private func connect() async {
        localError = nil
        vm.applyMDNSSuffix()
        if let err = await vm.connectToRasPi() {
            localError = err
        } else {
            vm.path.append(.rigSelect)
        }
    }

    private func civConnect() async {
        localError = nil
        vm.civError = ""
        if let err = await vm.civConnect() {
            localError = err
        } else {
            vm.path.append(.mainControl)
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
