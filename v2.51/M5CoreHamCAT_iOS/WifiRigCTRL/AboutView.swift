import SwiftUI

struct AboutView: View {
    @Bindable var vm: MainViewModel
    @State private var piApiVersion: String = "—"
    @State private var piRigctld: String = "—"
    @State private var fetchingVersion: Bool = false
    @State private var versionError: String?
    @State private var lastRefreshed: Date?

    /// App short version like "2.12.0" — used to compare with Pi side.
    private var appShortVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }
    private var appVersion: String {
        let bundle = Bundle.main
        let short = appShortVersion
        let build = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(short) (\(build))"
    }

    /// True iff Pi API version doesn't match the expected version (from ViewModel's polling result).
    private var versionMismatch: Bool { vm.piVersionMismatch }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                ZStack {
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [.indigo, .purple, .pink],
                                startPoint: .topLeading, endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 120, height: 120)
                        .shadow(color: .purple.opacity(0.4), radius: 10)
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 64, height: 64)
                        .foregroundStyle(.white)
                }
                .padding(.top, 20)

                VStack(spacing: 4) {
                    Text("Wifi_RIG_CTRL").font(.title.bold())
                    Text("Version \(appVersion)")
                        .font(.callout.monospacedDigit())
                        .foregroundStyle(.secondary)
                    Text("WiFi Ham Radio Controller").font(.subheadline).foregroundStyle(.secondary)
                }

                VStack(alignment: .leading, spacing: 12) {
                    aboutRow(icon: "iphone", color: .indigo,
                             title: "App Version", value: appShortVersion)
                    aboutRow(icon: "server.rack", color: .teal,
                             title: "Pi API Version",
                             value: fetchingVersion ? "Loading…" : piApiVersion)
                    aboutRow(icon: "waveform", color: .green,
                             title: "WebFT8 Version",
                             value: vm.webft8Version.isEmpty ? "—" : vm.webft8Version)
                    aboutRow(icon: "radio", color: .purple,
                             title: "rigctld",
                             value: fetchingVersion ? "Loading…" : piRigctld)
                    aboutRow(icon: "person.fill", color: .orange,
                             title: "Author", value: "JI1ORE")
                }
                .padding()
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                .padding(.horizontal)

                if versionMismatch {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(.orange)
                            Text("Version Mismatch")
                                .font(.headline)
                                .foregroundStyle(.orange)
                        }
                        Text("The Pi API server is on version **\(piApiVersion)** but version **2.51** is required. Update the Pi via the UPDATE screen.")
                            .font(.callout)
                            .foregroundStyle(.primary)
                    }
                    .padding()
                    .background(
                        Color.orange.opacity(0.15),
                        in: RoundedRectangle(cornerRadius: 12)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.orange.opacity(0.4), lineWidth: 1)
                    )
                    .padding(.horizontal)
                }

                Button {
                    Task { await refreshVersion() }
                } label: {
                    Label("Refresh Pi Version", systemImage: "arrow.clockwise")
                }
                .buttonStyle(.bordered)
                .tint(.indigo)
                .disabled(fetchingVersion)

                if fetchingVersion {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("Checking Pi version…")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } else if let t = lastRefreshed {
                    Text("Last checked: \(t.formatted(date: .omitted, time: .shortened))")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                if let err = versionError {
                    Text(err)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                Spacer(minLength: 24)

                Text("© 2025– JI1ORE")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding(.bottom, 24)
        }
        .navigationTitle(Text("About"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if piApiVersion == "—" {
                await refreshVersion()
            }
        }
    }

    private func refreshVersion() async {
        fetchingVersion = true
        versionError = nil
        defer { fetchingVersion = false }
        async let serverFetch = vm.fetchServerVersion()
        async let webft8Fetch = vm.fetchWebft8Version()
        let v = await serverFetch
        let w = await webft8Fetch
        if v.apiVersion == "?" {
            versionError = v.rigctld
        } else {
            piApiVersion = v.apiVersion
            piRigctld = v.rigctld
            versionError = nil
            lastRefreshed = Date()
        }
        if w != "?" { vm.webft8Version = w }
    }

    private func aboutRow(icon: String, color: Color, title: LocalizedStringKey, value: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(color)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.caption).foregroundStyle(.secondary)
                Text(value).font(.body)
            }
            Spacer()
        }
    }
}
