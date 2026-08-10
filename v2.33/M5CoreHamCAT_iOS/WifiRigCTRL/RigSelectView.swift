import SwiftUI

struct RigSelectView: View {
    @Bindable var vm: MainViewModel
    @State private var localError: String?

    private var audioCaptureName: String {
        guard !vm.selectedAudioCapture.isEmpty else { return "Default" }
        return vm.selectedAudioCapture
    }
    private var audioPlaybackName: String {
        guard !vm.selectedAudioPlayback.isEmpty else { return "Same as SPK/TX" }
        return vm.selectedAudioPlayback
    }
    private var sampleRateLabel: String {
        // Guard against an out-of-range index (stale profile) — avoids a crash that
        // would leave this pushed screen blank/black.
        guard AppConstants.samplingRates.indices.contains(vm.selectedSamplingIndex) else { return "Off" }
        let r = AppConstants.samplingRates[vm.selectedSamplingIndex]
        return r == 0 ? "Off" : "\(r) Hz"
    }

    var body: some View {
        Form {
            Section(header: Label("Rig Model", systemImage: "antenna.radiowaves.left.and.right").foregroundStyle(.indigo)) {
                if vm.rigList.isEmpty {
                    Text("No rigs returned by server").foregroundStyle(.secondary)
                } else {
                    Picker("Rig Model", selection: $vm.selectedRigIndex) {
                        ForEach(vm.rigList.indices, id: \.self) { i in
                            Text(vm.rigList[i].name).tag(i)
                        }
                    }
                }
            }

            Section(header: Label("CAT Device", systemImage: "cable.connector").foregroundStyle(.teal)) {
                if vm.catList.isEmpty {
                    Text("No serial devices").foregroundStyle(.secondary)
                } else {
                    Picker("CAT Device", selection: $vm.selectedCatIndex) {
                        ForEach(vm.catList.indices, id: \.self) { i in
                            Text(vm.catList[i]).tag(i)
                        }
                    }
                }
                Picker("Baud Rate", selection: $vm.selectedBaudIndex) {
                    ForEach(AppConstants.baudRates.indices, id: \.self) { i in
                        Text(String(AppConstants.baudRates[i])).tag(i)
                    }
                }
            }

            Section(header: Label("PTT", systemImage: "dot.radiowaves.left.and.right").foregroundStyle(.red)) {
                Picker("PTT Device", selection: $vm.selectedPttDevice) {
                    Text("NONE").tag("NONE")
                    ForEach(vm.catList, id: \.self) { name in
                        Text(name).tag(name)
                    }
                }
                Picker("PTT Type", selection: $vm.selectedPttType) {
                    ForEach(PttType.allCases, id: \.self) { t in
                        Text(t.displayName).tag(t)
                    }
                }
            }

            Section {
                if vm.soundDeviceList.isEmpty {
                    Text("No audio devices").foregroundStyle(.secondary)
                } else {
                    Picker("SPK / TX", selection: $vm.selectedAudioCapture) {
                        Text("Default").tag("")
                        ForEach(vm.soundDeviceList) { d in
                            VStack(alignment: .leading, spacing: 1) {
                                Text(d.id)
                                Text(d.label).font(.caption2).foregroundStyle(.secondary)
                            }.tag(d.id)
                        }
                    }
                    Picker("FT8", selection: $vm.selectedAudioPlayback) {
                        Text("Same as SPK/TX").tag("")
                        ForEach(vm.soundDeviceList) { d in
                            VStack(alignment: .leading, spacing: 1) {
                                Text(d.id)
                                Text(d.label).font(.caption2).foregroundStyle(.secondary)
                            }.tag(d.id)
                        }
                    }
                }
                Picker("Sample Rate", selection: $vm.selectedSamplingIndex) {
                    ForEach(AppConstants.samplingRates.indices, id: \.self) { i in
                        let r = AppConstants.samplingRates[i]
                        Text(r == 0 ? "Off" : "\(r) Hz").tag(i)
                    }
                }
            } header: {
                Label("Audio", systemImage: "waveform").foregroundStyle(.cyan)
            } footer: {
                VStack(alignment: .leading, spacing: 2) {
                    if !vm.soundDeviceList.isEmpty {
                        Label("SPK/TX: \(audioCaptureName)", systemImage: "speaker.wave.2")
                        Label("FT8: \(audioPlaybackName)", systemImage: "waveform.badge.mic")
                    }
                    Label("Rate: \(sampleRateLabel)", systemImage: "metronome")
                }
                .font(.caption)
            }

            Section {
                Button {
                    Task { await open() }
                } label: {
                    HStack {
                        if vm.isBusy { ProgressView().padding(.trailing, 4) }
                        Text(vm.isBusy ? "Connecting…" : "Open Rig")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(vm.isBusy || vm.rigList.isEmpty)
            }

            if let msg = localError {
                Section { Text(msg).foregroundStyle(.red).font(.callout) }
            }

            #if DEBUG
            Section(header: Label("Debug", systemImage: "ladybug.fill").foregroundStyle(.orange)) {
                Button {
                    vm.isConnectedToRig = true
                    vm.path.append(.mainControl)
                } label: {
                    Label("Skip to Main Control", systemImage: "forward.fill")
                }
                .foregroundStyle(.orange)
            }
            #endif
        }
        .navigationTitle(Text("Select Rig"))
        .onChange(of: vm.selectedRigIndex) { _, _ in vm.persistSelections() }
        .onChange(of: vm.selectedCatIndex) { _, _ in vm.persistSelections() }
        .onChange(of: vm.selectedBaudIndex) { _, _ in vm.persistSelections() }
        .onChange(of: vm.selectedSamplingIndex) { _, _ in vm.persistSelections() }
        .onChange(of: vm.selectedPttDevice) { _, _ in vm.persistSelections() }
        .onChange(of: vm.selectedPttType) { _, _ in vm.persistSelections() }
        .onChange(of: vm.selectedAudioCapture) { _, _ in vm.persistSelections() }
        .onChange(of: vm.selectedAudioPlayback) { _, _ in vm.persistSelections() }
    }

    private func open() async {
        localError = nil
        if let err = await vm.openSelectedRig() {
            localError = err
        } else {
            vm.path.append(.mainControl)
        }
    }
}
