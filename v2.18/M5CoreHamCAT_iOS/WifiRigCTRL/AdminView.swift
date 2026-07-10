import SwiftUI
import UniformTypeIdentifiers

struct AdminView: View {
    @Bindable var vm: MainViewModel
    @State private var pickingTarget: UploadTarget?
    @State private var localMessage: String?
    @State private var isUploading: Bool = false

    enum UploadTarget: Identifiable {
        case api, cwBridge, setupScript
        var id: Int { hashValue }
        var label: String {
            switch self {
            case .api: return "API (api.py)"
            case .cwBridge: return "CW Bridge (cw_bridge_ncm.py)"
            case .setupScript: return "Setup Script (.sh)"
            }
        }
        var icon: String {
            switch self {
            case .api: return "doc.text.fill"
            case .cwBridge: return "cable.connector.horizontal"
            case .setupScript: return "terminal.fill"
            }
        }
        var color: Color {
            switch self {
            case .api: return .blue
            case .cwBridge: return .orange
            case .setupScript: return .purple
            }
        }
        var contentTypes: [UTType] {
            switch self {
            case .setupScript: return [.shellScript, .data]
            default: return [.pythonScript, .data, .plainText]
            }
        }
    }

    var body: some View {
        Form {
            Section(header: Label("Upload", systemImage: "square.and.arrow.up")
                        .foregroundStyle(.indigo)) {
                ForEach([UploadTarget.api, .cwBridge, .setupScript]) { tgt in
                    Button {
                        pickingTarget = tgt
                    } label: {
                        Label(tgt.label, systemImage: tgt.icon)
                            .foregroundStyle(tgt.color)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                if isUploading {
                    HStack {
                        ProgressView()
                        Text("Uploading…").foregroundStyle(.secondary)
                    }
                }
            }

            Section(header: Label("Setup Log", systemImage: "doc.text.magnifyingglass")
                        .foregroundStyle(.teal)) {
                HStack {
                    statusBadge(vm.adminSetupRunning, runningLabel: "Running", idleLabel: "Idle")
                    Spacer()
                    Button {
                        Task { await vm.adminPollSetupLog() }
                    } label: {
                        Label("Refresh", systemImage: "arrow.clockwise")
                    }
                    .tint(.indigo)
                }
                ScrollView {
                    Text(vm.adminSetupLog.isEmpty ? "—" : vm.adminSetupLog)
                        .font(.caption.monospaced())
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 240)
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
        .fileImporter(
            isPresented: Binding(
                get: { pickingTarget != nil },
                set: { if !$0 { pickingTarget = nil } }
            ),
            allowedContentTypes: pickingTarget?.contentTypes ?? [.data],
            allowsMultipleSelection: false
        ) { result in
            guard let target = pickingTarget else { return }
            pickingTarget = nil
            switch result {
            case .success(let urls):
                if let url = urls.first { Task { await upload(target: target, fileURL: url) } }
            case .failure(let err):
                localMessage = err.localizedDescription
            }
        }
        .onAppear { Task { await vm.adminPollSetupLog() } }
    }

    private func upload(target: UploadTarget, fileURL: URL) async {
        localMessage = nil
        isUploading = true
        defer { isUploading = false }
        let accessing = fileURL.startAccessingSecurityScopedResource()
        defer { if accessing { fileURL.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: fileURL) else {
            localMessage = "Failed to read file"
            return
        }
        let err: String?
        switch target {
        case .api: err = await vm.adminUploadApi(data)
        case .cwBridge: err = await vm.adminUploadCwBridge(data)
        case .setupScript: err = await vm.adminUploadSetupScript(data)
        }
        if let err {
            localMessage = err
        } else {
            localMessage = "OK: uploaded \(fileURL.lastPathComponent) (\(data.count) bytes)"
            // Refresh log after setup script
            if target == .setupScript {
                Task { await vm.adminPollSetupLog() }
            }
        }
    }

    @ViewBuilder
    private func statusBadge(_ on: Bool, runningLabel: String, idleLabel: String) -> some View {
        HStack(spacing: 4) {
            Image(systemName: on ? "bolt.circle.fill" : "moon.zzz.fill")
                .foregroundStyle(on ? .green : .secondary)
            Text(on ? runningLabel : idleLabel)
        }
    }
}
