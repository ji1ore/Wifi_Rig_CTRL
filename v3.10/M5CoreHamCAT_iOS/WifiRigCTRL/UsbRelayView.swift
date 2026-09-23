import SwiftUI

struct UsbRelayView: View {
    @Bindable var vm: MainViewModel
    @State private var service = UsbRelayService()
    @State private var piPortText: String = String(AppConstants.atomBridgePort)
    @State private var piHostText: String = ""

    var body: some View {
        Form {
            Section(header: Label("Bridge", systemImage: "cable.connector.horizontal")
                        .foregroundStyle(.indigo)) {
                HStack {
                    Image(systemName: service.isRunning ? "bolt.fill" : "bolt.slash.fill")
                        .foregroundStyle(service.isRunning ? .green : .secondary)
                    Text(service.isRunning ? "Running" : "Stopped")
                    Spacer()
                    Button(service.isRunning ? "Stop" : "Start") {
                        if service.isRunning {
                            service.stop()
                        } else {
                            service.configure(piHost: piHostText, piPort: Int(piPortText) ?? AppConstants.atomBridgePort)
                            service.start()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(service.isRunning ? .red : .green)
                }
                if let err = service.lastError {
                    Text(err).foregroundStyle(.red).font(.caption)
                }
            }

            Section(header: Label("Atom Client (USB-NCM)", systemImage: "keyboard.fill")
                        .foregroundStyle(.blue)) {
                LabeledContent("Status") {
                    HStack(spacing: 4) {
                        Image(systemName: service.usb.available ? "checkmark.circle.fill" : "xmark.circle.fill")
                            .foregroundStyle(service.usb.available ? .green : .secondary)
                        Text(service.usb.available ? "Detected" : "Not detected")
                    }
                }
                LabeledContent("Interface") {
                    Text(service.usb.interfaceName ?? "—").font(.caption.monospaced())
                }
                LabeledContent("Device IP") {
                    Text("192.168.7.1:8888").font(.caption.monospaced())
                }
                HStack(spacing: 16) {
                    Label("\(service.usb.rxFromClient)", systemImage: "arrow.down")
                        .foregroundStyle(.blue).font(.caption.monospacedDigit())
                    Label("\(service.usb.txToClient)", systemImage: "arrow.up")
                        .foregroundStyle(.orange).font(.caption.monospacedDigit())
                    if let t = service.usb.lastClientPacketAt {
                        Text(t.formatted(date: .omitted, time: .standard))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section(header: Label("Pi Bridge (WiFi)", systemImage: "wifi")
                        .foregroundStyle(.purple)) {
                LabeledContent("Pi Host") {
                    TextField("raspberrypi.local", text: $piHostText)
                        .multilineTextAlignment(.trailing)
                        .textContentType(.URL)
                        .keyboardType(.URL)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                }
                LabeledContent("Bridge Port") {
                    TextField("8889", text: $piPortText)
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.numberPad)
                }
                LabeledContent("Status") {
                    HStack(spacing: 4) {
                        Image(systemName: service.pi.connected ? "checkmark.circle.fill" : "xmark.circle.fill")
                            .foregroundStyle(service.pi.connected ? .green : .secondary)
                        Text(service.pi.connected ? "Connected" : "Disconnected")
                    }
                }
                HStack(spacing: 16) {
                    Label("\(service.pi.rxFromPi)", systemImage: "arrow.down")
                        .foregroundStyle(.blue).font(.caption.monospacedDigit())
                    Label("\(service.pi.txToPi)", systemImage: "arrow.up")
                        .foregroundStyle(.orange).font(.caption.monospacedDigit())
                    if let t = service.pi.lastPiPacketAt {
                        Text(t.formatted(date: .omitted, time: .standard))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section(header: Label("How to use", systemImage: "info.circle.fill")
                        .foregroundStyle(.cyan)) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("1. Atom Server is connected to Raspberry Pi via USB-NCM")
                    Text("2. Pi runs cw_bridge_ncm.py (UDP 8889)")
                    Text("3. Atom Client is connected to iPhone via USB-C")
                    Text("4. Both Atom devices in USB-NCM mode")
                    Text("5. Set Pi Host above and tap Start")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(Text("USB Relay"))
        .onAppear {
            if piHostText.isEmpty { piHostText = vm.hostName }
        }
    }
}
