import SwiftUI

struct AprsSettingsView: View {
    @Bindable var vm: MainViewModel

    @State private var callsignText: String = ""
    @State private var latText: String = ""
    @State private var lonText: String = ""
    @State private var txFreqText: String = ""
    @State private var localMessage: String?
    @State private var isSending: Bool = false

    var body: some View {
        Form {
            Section(header: Label("APRS", systemImage: "location.north.line.fill").foregroundStyle(.green)) {
                Toggle("APRS Enabled", isOn: $vm.aprsEnabled)
                Toggle(isOn: Binding(
                    get: { vm.aprsActive },
                    set: { newValue in
                        Task {
                            if newValue {
                                if let err = await vm.startAprs() {
                                    localMessage = err
                                }
                            } else {
                                await vm.stopAprs()
                            }
                        }
                    }
                )) {
                    Text(vm.aprsActive ? "APRS Active" : "APRS Inactive")
                }
            }

            Section(header: Label("Station", systemImage: "person.crop.circle").foregroundStyle(.indigo)) {
                LabeledContent("Callsign") {
                    TextField("NOCALL", text: $callsignText)
                        .multilineTextAlignment(.trailing)
                        .autocapitalization(.allCharacters)
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

            Section(header: Label("Transmit", systemImage: "antenna.radiowaves.left.and.right").foregroundStyle(.orange)) {
                Picker("TX Interval", selection: $vm.aprsIntervalSec) {
                    ForEach(AprsConstants.intervalSecs, id: \.self) { v in
                        Text("\(v) s").tag(v)
                    }
                }
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

            Section(header: Label("Radio / Audio", systemImage: "waveform").foregroundStyle(.cyan)) {
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
            if vm.aprsSoundDevice.isEmpty, let first = vm.soundDeviceList.first {
                vm.aprsSoundDevice = first.id
            }
        }
    }

    private func saveAndSend() async {
        localMessage = nil
        isSending = true
        defer { isSending = false }
        if let err = await vm.saveAndSendAprsConfig() {
            localMessage = "Failed to send APRS settings: " + err
        } else {
            vm.path.removeLast()
        }
    }
}
