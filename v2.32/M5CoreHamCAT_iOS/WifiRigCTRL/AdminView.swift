import SwiftUI

struct AdminView: View {
    @Bindable var vm: MainViewModel
    @State private var localMessage: String?
    @State private var isUpdating: Bool = false
    @State private var isInstallingHamlib: Bool = false

    var body: some View {
        Form {
            // MARK: Update Pi API
            Section(header: Label("Update Pi", systemImage: "shippingbox.fill")
                        .foregroundStyle(.blue)) {
                Button {
                    Task { await runUpdate() }
                } label: {
                    HStack {
                        Label("Update Pi API", systemImage: "arrow.up.circle.fill")
                            .foregroundStyle(.blue)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if let ver = bundledApiVersion() {
                            Text(ver)
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .disabled(isUpdating || isInstallingHamlib)
                if isUpdating {
                    HStack {
                        ProgressView()
                        Text("Updating… (about 1 min)").foregroundStyle(.secondary)
                    }
                }
            }

            Section(header: Label("Setup Log", systemImage: "doc.text.magnifyingglass")
                        .foregroundStyle(.teal)) {
                HStack {
                    statusBadge(vm.adminSetupRunning)
                    Spacer()
                    Button {
                        Task { await vm.adminPollSetupLog() }
                    } label: {
                        Label("Refresh", systemImage: "arrow.clockwise")
                    }
                    .tint(.indigo)
                    .disabled(isUpdating)
                }
                logView(vm.adminSetupLog)
            }

            // MARK: Update Hamlib
            Section(header: Label("Update Hamlib", systemImage: "hammer.fill")
                        .foregroundStyle(.orange)) {
                Button {
                    Task { await runHamlibInstall() }
                } label: {
                    Label("Install Hamlib 4.7.2", systemImage: "arrow.down.circle.fill")
                        .foregroundStyle(.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .disabled(isUpdating || isInstallingHamlib)
                if isInstallingHamlib {
                    HStack {
                        ProgressView()
                        Text("Building… (Pi Zero: 30〜60 min)").foregroundStyle(.secondary)
                    }
                }
                Text("Pi Zero 2W では30〜60分かかります。完了まで電源を切らないでください。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section(header: Label("Hamlib Log", systemImage: "doc.text.magnifyingglass")
                        .foregroundStyle(.orange)) {
                HStack {
                    statusBadge(vm.adminHamlibRunning)
                    Spacer()
                    Button {
                        Task { await vm.adminPollHamlibLog() }
                    } label: {
                        Label("Refresh", systemImage: "arrow.clockwise")
                    }
                    .tint(.orange)
                    .disabled(isInstallingHamlib)
                }
                logView(vm.adminHamlibLog)
            }

            if let msg = localMessage {
                Section {
                    Text(msg)
                        .foregroundStyle(msg.hasPrefix("OK") ? .green : .red)
                        .font(.callout)
                }
            }
        }
        .navigationTitle(Text("Admin"))
        .onAppear {
            Task {
                await vm.adminPollSetupLog()
                await vm.adminPollHamlibLog()
            }
        }
    }

    // MARK: - Actions

    private func runUpdate() async {
        localMessage = nil
        isUpdating = true
        defer { isUpdating = false }
        guard let apiURL = Bundle.main.url(forResource: "api", withExtension: "py"),
              let apiData = try? Data(contentsOf: apiURL) else {
            localMessage = "Bundled api.py not found"
            return
        }
        if let shURL = Bundle.main.url(forResource: "create_api", withExtension: "sh"),
           let shData = try? Data(contentsOf: shURL) {
            if let err = await vm.adminUpdatePi(apiData: apiData, scriptData: shData) {
                localMessage = "Error: \(err)"
            } else {
                localMessage = "OK: Update complete"
            }
        } else {
            if let err = await vm.adminUploadApi(apiData) {
                localMessage = err
            } else {
                localMessage = "OK: api.py uploaded — Pi restarting (~15s)"
            }
        }
    }

    private func runHamlibInstall() async {
        localMessage = nil
        isInstallingHamlib = true
        defer { isInstallingHamlib = false }
        guard let shURL = Bundle.main.url(forResource: "install_hamlib", withExtension: "sh"),
              let shData = try? Data(contentsOf: shURL) else {
            localMessage = "Bundled install_hamlib.sh not found (add to Xcode target)"
            return
        }
        if let err = await vm.adminInstallHamlib(shData) {
            localMessage = "Error: \(err)"
        } else {
            localMessage = "OK: Hamlib 4.7.2 installed"
        }
    }

    // MARK: - Helpers

    private func bundledApiVersion() -> String? {
        guard let url = Bundle.main.url(forResource: "api", withExtension: "py"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        for line in text.split(separator: "\n").prefix(50) {
            let s = String(line)
            guard s.contains("API_VERSION") else { continue }
            if let r = s.range(of: #""([^"]+)""#, options: .regularExpression) {
                return "v" + s[r].replacingOccurrences(of: "\"", with: "")
            }
        }
        return nil
    }

    @ViewBuilder
    private func logView(_ log: String) -> some View {
        ScrollView {
            Text(log.isEmpty ? "—" : log)
                .font(.caption.monospaced())
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxHeight: 200)
    }

    @ViewBuilder
    private func statusBadge(_ running: Bool) -> some View {
        HStack(spacing: 4) {
            Image(systemName: running ? "bolt.circle.fill" : "moon.zzz.fill")
                .foregroundStyle(running ? .green : .secondary)
            Text(running ? "Running" : "Idle")
        }
    }
}
