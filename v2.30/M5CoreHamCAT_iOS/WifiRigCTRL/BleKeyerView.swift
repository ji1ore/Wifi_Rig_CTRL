import SwiftUI
import CoreBluetooth

struct BleKeyerView: View {
    @Bindable var vm: MainViewModel

    private var service: CwBleService { vm.cwBle }

    var body: some View {
        Form {
            Section(header: Label("Status", systemImage: "bolt.fill")
                        .foregroundStyle(.indigo)) {
                LabeledContent("Bluetooth") {
                    HStack(spacing: 4) {
                        Image(systemName: bluetoothIconName)
                            .foregroundStyle(bluetoothIconColor)
                        Text(bluetoothStateLabel)
                    }
                }
                LabeledContent("CW Keyer") {
                    HStack(spacing: 4) {
                        Image(systemName: service.connected ? "checkmark.circle.fill" : "xmark.circle.fill")
                            .foregroundStyle(service.connected ? .green : .secondary)
                        Text(service.connected ? "Connected" : "Disconnected")
                    }
                }
                if let name = service.connectedName, service.connected {
                    LabeledContent("Device") {
                        Text(name).font(.caption.monospaced())
                    }
                }
                if let err = service.lastError {
                    Text(err).foregroundStyle(.red).font(.caption)
                }
            }

            Section(header: Label("Pi UDP Tunnel", systemImage: "wifi")
                        .foregroundStyle(.purple)) {
                LabeledContent("Pi Host") {
                    Text(service.piHost.isEmpty ? vm.hostName : service.piHost)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.trailing)
                }
                Picker("Pi USB Port", selection: $vm.cwPort) {
                    ForEach(["ttyACM0", "ttyUSB0", "ttyUSB1", "ttyUSB2"], id: \.self) { port in
                        Text(port).tag(port)
                    }
                }
                .onChange(of: vm.cwPort) { _, _ in vm.persistCwSettings() }
                Stepper("BLE Delay: \(vm.cwDelayMs) ms", value: $vm.cwDelayMs, in: 0...500, step: 10)
                    .onChange(of: vm.cwDelayMs) { _, _ in vm.persistCwSettings() }
                HStack(spacing: 16) {
                    Label("SYNC fwd \(service.syncForwarded)", systemImage: "arrow.up")
                        .foregroundStyle(.orange).font(.caption.monospacedDigit())
                    Label("SYNC rx \(service.syncReceived)", systemImage: "arrow.down")
                        .foregroundStyle(.blue).font(.caption.monospacedDigit())
                    Label("KEY fwd \(service.keyForwarded)", systemImage: "key.fill")
                        .foregroundStyle(.red).font(.caption.monospacedDigit())
                }
            }

            Section(header: Label("Keyer", systemImage: "key.horizontal")
                        .foregroundStyle(.cyan)) {
                LabeledContent("Key State") {
                    HStack(spacing: 4) {
                        Image(systemName: service.keyOn ? "bolt.fill" : "bolt.slash.fill")
                            .foregroundStyle(service.keyOn ? .red : .secondary)
                        Text(service.keyOn ? "DOWN" : "UP")
                    }
                }
                LabeledContent("WPM") {
                    Text("\(service.wpm)")
                        .font(.caption.monospacedDigit())
                }
                if let t = service.lastKeyAt {
                    LabeledContent("Last") {
                        Text(t.formatted(date: .omitted, time: .standard))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section(header: Label("Sidetone", systemImage: "speaker.wave.2.fill")
                        .foregroundStyle(.green)) {
                Toggle("Enable", isOn: Binding(
                    get: { service.sidetoneEnabled },
                    set: { service.sidetoneEnabled = $0 }
                ))
                LabeledContent("Engine") {
                    HStack(spacing: 4) {
                        Image(systemName: service.sidetoneEngineRunning ? "checkmark.circle.fill" : "xmark.circle.fill")
                            .foregroundStyle(service.sidetoneEngineRunning ? .green : .red)
                        Text(service.sidetoneEngineRunning ? "Running" : "Stopped")
                            .foregroundStyle(service.sidetoneEngineRunning ? AnyShapeStyle(.primary) : AnyShapeStyle(.red))
                    }
                }
                Button("Test Tone (1 sec)") {
                    service.testSidetone()
                }
                .buttonStyle(.bordered)
                .tint(service.sidetoneEngineRunning ? .green : .orange)
                LabeledContent("Frequency") {
                    HStack(spacing: 8) {
                        Slider(value: Binding(
                            get: { service.sidetoneFrequencyHz },
                            set: { service.sidetoneFrequencyHz = $0 }
                        ), in: 400...1200, step: 50)
                        Text("\(Int(service.sidetoneFrequencyHz)) Hz")
                            .font(.caption.monospacedDigit())
                            .frame(width: 64, alignment: .trailing)
                    }
                }
                LabeledContent("Volume") {
                    HStack(spacing: 8) {
                        Slider(value: Binding(
                            get: { service.sidetoneVolume },
                            set: { service.sidetoneVolume = $0 }
                        ), in: 0...1)
                        Text("\(Int(service.sidetoneVolume * 100))%")
                            .font(.caption.monospacedDigit())
                            .frame(width: 48, alignment: .trailing)
                    }
                }
            }

            Section(header: Label("Devices", systemImage: "antenna.radiowaves.left.and.right")
                        .foregroundStyle(.blue)) {
                if service.scanning {
                    HStack {
                        ProgressView().scaleEffect(0.8)
                        Text("Scanning...").font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button("Stop") { service.stopScan() }
                            .buttonStyle(.bordered)
                            .tint(.red)
                    }
                } else {
                    HStack {
                        Spacer()
                        Button {
                            service.startScan()
                        } label: {
                            Label("Scan", systemImage: "magnifyingglass")
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.indigo)
                        .disabled(service.bluetoothState != .poweredOn)
                        Spacer()
                    }
                }

                if service.discovered.isEmpty {
                    Text("(no devices yet)")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    ForEach(service.discovered) { d in
                        Button {
                            service.connect(d)
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(d.name).font(.body)
                                    Text(d.id.uuidString).font(.caption2.monospaced())
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text("\(d.rssi) dBm")
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .disabled(service.connecting || service.connected)
                    }
                }
            }

            if service.connected {
                Section {
                    Button(role: .destructive) {
                        service.disconnect()
                    } label: {
                        Label("Disconnect", systemImage: "xmark.circle.fill")
                    }
                }
            }

            Section(header: Label("How to use", systemImage: "info.circle.fill")
                        .foregroundStyle(.cyan)) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("1. Atom S3 needs DualKey-BLE firmware (Nordic UART Service)")
                    Text("2. Pi Host is set automatically from main rig connection")
                    Text("3. Tap Scan → select DualKey-BLE → Connect")
                    Text("4. SYNC packets are tunneled to Pi via WiFi (UDP 8889)")
                    Text("5. BLE Delay: set to BLE+WiFi one-way latency (~60-100 ms) to prevent immediate firing on rapid dots")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(Text("BLE Keyer"))
    }

    private var bluetoothStateLabel: String {
        switch service.bluetoothState {
        case .poweredOn: return "Powered On"
        case .poweredOff: return "Powered Off"
        case .unauthorized: return "Unauthorized"
        case .unsupported: return "Unsupported"
        case .resetting: return "Resetting"
        case .unknown: return "Unknown"
        @unknown default: return "Unknown"
        }
    }

    private var bluetoothIconName: String {
        service.bluetoothState == .poweredOn ? "checkmark.circle.fill" : "exclamationmark.triangle.fill"
    }

    private var bluetoothIconColor: Color {
        service.bluetoothState == .poweredOn ? .green : .orange
    }
}
