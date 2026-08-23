import SwiftUI

struct AprsSettingsView: View {
    @Bindable var vm: MainViewModel

    @State private var callsignText: String = ""
    @State private var latText: String = ""
    @State private var lonText: String = ""
    @State private var txFreqText: String = ""
    @State private var ap96FreqText: String = ""
    @State private var ap12FreqText: String = ""
    @State private var localMessage: String?
    @State private var isSending: Bool = false

    private let modemSelOptions: [(Int, String)] = [(1, "AUTO"), (2, "MAIN"), (3, "SUB")]

    var body: some View {
        Form {
            Section(header: Label("APRS", systemImage: "location.north.line.fill").foregroundStyle(.green)) {
                Toggle("APRS Enabled", isOn: $vm.aprsEnabled)
                Toggle(isOn: Binding(
                    get: { vm.aprsActive },
                    set: { newValue in
                        Task {
                            if newValue {
                                if let err = await vm.startAprs() { localMessage = err }
                            } else {
                                await vm.stopAprs()
                            }
                        }
                    }
                )) {
                    Text(vm.aprsActive ? "APRS Active" : "APRS Inactive")
                }
            }

            // TX Method toggle
            Section(header: Label("TX Method", systemImage: "antenna.radiowaves.left.and.right").foregroundStyle(.orange)) {
                Button {
                    Task { await vm.switchAprsTxMethod() }
                } label: {
                    HStack {
                        Text("TX Method")
                            .foregroundStyle(.primary)
                        Spacer()
                        Text(vm.aprsUseRigModem ? "Rig Modem" : "DireWolf")
                            .bold()
                            .foregroundStyle(vm.aprsUseRigModem ? .mint : .blue)
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)

                Picker("TX Interval", selection: $vm.aprsIntervalSec) {
                    ForEach(AprsConstants.intervalSecs, id: \.self) { v in
                        Text("\(v) s").tag(v)
                    }
                }
            }

            if vm.aprsUseRigModem {
                // Rig Modem section
                Section(header: Label("Rig Modem", systemImage: "waveform").foregroundStyle(.mint)) {
                    Picker("Modem Select", selection: $vm.aprsModemSel) {
                        ForEach(modemSelOptions, id: \.0) { (val, label) in
                            Text(label).tag(val)
                        }
                    }
                    .pickerStyle(.segmented)

                    LabeledContent("AP96 Freq (MHz)") {
                        TextField("144.660", text: $ap96FreqText)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.decimalPad)
                            .onChange(of: ap96FreqText) { _, new in
                                if let d = Double(new.replacingOccurrences(of: ",", with: ".")) {
                                    vm.aprsPreset1Freq = d
                                }
                            }
                    }
                    LabeledContent("AP96 Baud") {
                        Text("9600 (fixed)")
                            .foregroundStyle(.secondary)
                    }

                    LabeledContent("AP12 Freq (MHz)") {
                        TextField("144.660", text: $ap12FreqText)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.decimalPad)
                            .onChange(of: ap12FreqText) { _, new in
                                if let d = Double(new.replacingOccurrences(of: ",", with: ".")) {
                                    vm.aprsPreset2Freq = d
                                }
                            }
                    }
                    LabeledContent("AP12 Baud") {
                        Text("1200 (fixed)")
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                // DireWolf mode: station identity (callsign etc.) is sent to DireWolf/Pi
                Section(header: Label("Station", systemImage: "person.crop.circle").foregroundStyle(.indigo)) {
                    LabeledContent("Callsign") {
                        TextField("NOCALL", text: $callsignText)
                            .multilineTextAlignment(.trailing)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                            .onChange(of: callsignText) { _, new in
                                vm.aprsCallsign = new.uppercased()
                            }
                    }
                    Picker("SSID", selection: $vm.aprsSSID) {
                        ForEach(0..<16, id: \.self) { i in
                            Text("-\(i)").tag(i)
                        }
                    }
                    Picker("Path", selection: $vm.aprsPath) {
                        ForEach(AprsConstants.pathList, id: \.self) { p in
                            Text(p).tag(p)
                        }
                    }
                    Picker("Destination", selection: $vm.aprsDestination) {
                        ForEach(AprsConstants.destinationList, id: \.self) { d in
                            Text(d).tag(d)
                        }
                    }
                    Picker("Symbol", selection: $vm.aprsSymbol) {
                        ForEach(AprsConstants.symbols) { sym in
                            Text(sym.display).tag(sym.code)
                        }
                    }
                    LabeledContent("Comment") {
                        TextField("optional", text: $vm.aprsComment)
                            .multilineTextAlignment(.trailing)
                            .autocapitalization(.sentences)
                    }
                }

                // DireWolf transmit settings
                Section(header: Label("Transmit", systemImage: "waveform").foregroundStyle(.cyan)) {
                    Picker("Baud", selection: $vm.aprsBaud) {
                        ForEach(AprsConstants.baudList, id: \.self) { v in
                            Text("\(v)").tag(v)
                        }
                    }
                    LabeledContent("TX Freq (MHz)") {
                        TextField("144.660", text: $txFreqText)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.decimalPad)
                            .onChange(of: txFreqText) { _, new in
                                if let d = Double(new.replacingOccurrences(of: ",", with: ".")) {
                                    vm.aprsTxFreq = d
                                }
                            }
                    }
                }

                Section(header: Label("Position", systemImage: "location.fill").foregroundStyle(.teal)) {
                    Toggle("Use GPS", isOn: $vm.aprsUseGPS)
                        .onChange(of: vm.aprsUseGPS) { _, on in
                            if on {
                                vm.requestLocationAuthorization()
                                if !vm.isLocationAuthorized {
                                    localMessage = "Location permission required"
                                }
                            }
                        }
                    LabeledContent("Latitude") {
                        TextField("0.00000", text: $latText)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numbersAndPunctuation)
                            .disabled(vm.aprsUseGPS)
                            .onChange(of: latText) { _, new in
                                if let d = Double(new.replacingOccurrences(of: ",", with: ".")) {
                                    vm.aprsManualLat = d
                                }
                            }
                    }
                    LabeledContent("Longitude") {
                        TextField("0.00000", text: $lonText)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numbersAndPunctuation)
                            .disabled(vm.aprsUseGPS)
                            .onChange(of: lonText) { _, new in
                                if let d = Double(new.replacingOccurrences(of: ",", with: ".")) {
                                    vm.aprsManualLon = d
                                }
                            }
                    }
                    if vm.aprsUseGPS {
                        LabeledContent("Current Lat") {
                            Text(String(format: "%.5f", vm.aprsCurrentLat))
                                .font(.caption.monospacedDigit())
                        }
                        LabeledContent("Current Lon") {
                            Text(String(format: "%.5f", vm.aprsCurrentLon))
                                .font(.caption.monospacedDigit())
                        }
                    }
                }

                Section(header: Label("Radio / Audio", systemImage: "speaker.wave.2").foregroundStyle(.cyan)) {
                    if vm.soundDeviceList.isEmpty {
                        Text("No sound devices (connect to server first)")
                            .foregroundStyle(.secondary).font(.callout)
                    } else {
                        Picker("Sound Device", selection: $vm.aprsSoundDevice) {
                            ForEach(vm.soundDeviceList) { d in
                                Text(d.label).tag(d.id)
                            }
                        }
                    }
                }
            }

            Section {
                Button {
                    Task { await saveAndSend() }
                } label: {
                    HStack {
                        if isSending { ProgressView().padding(.trailing, 4) }
                        Text("Save & Send Config")
                            .frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(isSending)
            }

            // Received beacons section
            Section(header:
                HStack {
                    Label("Received Beacons", systemImage: "dot.radiowaves.left.and.right").foregroundStyle(.green)
                    Spacer()
                    Button {
                        Task { await vm.refreshAprsReceived() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.caption)
                    }
                }
            ) {
                if vm.aprsReceivedStations.isEmpty {
                    Text("No stations received")
                        .foregroundStyle(.secondary).font(.callout)
                } else {
                    ForEach(vm.aprsReceivedStations) { s in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text(s.call)
                                    .font(.caption.bold().monospaced())
                                    .foregroundStyle(.green)
                                if let sym = s.symbol, !sym.isEmpty {
                                    Text(AprsConstants.symbol(byCode: sym).emoji)
                                        .font(.caption)
                                }
                                Spacer()
                                Text(formatAge(s.age_sec))
                                    .font(.caption2.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                            if let lat = s.lat, let lon = s.lon {
                                Text(String(format: "%.4f, %.4f", lat, lon))
                                    .font(.caption2.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                            if let comment = s.comment, !comment.isEmpty {
                                Text(comment)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
            }

            if let msg = localMessage {
                Section { Text(msg).foregroundStyle(.red).font(.callout) }
            }
        }
        .navigationTitle(Text("APRS Settings"))
        .onAppear {
            callsignText = vm.aprsCallsign
            latText = String(format: "%.5f", vm.aprsManualLat)
            lonText = String(format: "%.5f", vm.aprsManualLon)
            txFreqText = String(format: "%.3f", vm.aprsTxFreq)
            ap96FreqText = String(format: "%.3f", vm.aprsPreset1Freq)
            ap12FreqText = String(format: "%.3f", vm.aprsPreset2Freq)
            if vm.aprsSoundDevice.isEmpty, let first = vm.soundDeviceList.first {
                vm.aprsSoundDevice = first.id
            }
            Task { await vm.refreshAprsReceived() }
        }
    }

    private func formatAge(_ sec: Int) -> String {
        if sec < 60 { return "\(sec)s" }
        if sec < 3600 { return "\(sec / 60)m" }
        return "\(sec / 3600)h\((sec % 3600) / 60)m"
    }

    private func saveAndSend() async {
        localMessage = nil
        isSending = true
        defer { isSending = false }
        vm.aprsPreset1Baud = 9600
        vm.aprsPreset2Baud = 1200
        if let err = await vm.saveAndSendAprsConfig() {
            localMessage = "Failed to send APRS settings: " + err
        } else {
            vm.path.removeLast()
        }
    }
}
