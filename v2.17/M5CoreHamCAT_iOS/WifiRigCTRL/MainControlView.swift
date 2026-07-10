import SwiftUI

/// One of the rig parameters adjusted by tapping its panel and using the
/// shared slider below. Mirrors Android's `tvWidth / tvPow / tvSQL` cells
/// driven by a common UP/DOWN button pair.
enum AdjustableParam: String, CaseIterable, Identifiable {
    case width, power, squelch
    var id: String { rawValue }
}

struct MainControlView: View {
    @Bindable var vm: MainViewModel
    @State private var showFreqInput = false
    @State private var showModePicker = false
    @State private var showStepPicker = false
    /// Which numeric parameter the active slider is bound to.
    @State private var selectedParam: AdjustableParam = .width

    private let widthOptions: [Int] = [50, 100, 200, 500, 1000, 1500, 2400, 2700, 3000, 6000, 9000, 12000, 15000, 25000]

    var body: some View {
        GeometryReader { geo in
            if geo.size.width > geo.size.height {
                landscapeLayout
            } else {
                portraitLayout
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 12)
        .navigationTitle(currentRigName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Toggle("Keep Screen Awake", isOn: $vm.keepScreenAwake)
                    Button("FT8") { vm.path.append(.ft8) }
                        .disabled(vm.useCivMode && vm.civConnected)
                    Button("CW") { vm.path.append(.cw) }
                    Button("PTT Settings") { vm.path.append(.pttSettings) }
                    Button("APRS Settings") { vm.path.append(.aprsSettings) }
                        .disabled(vm.useCivMode && vm.civConnected)
                    Button("Scan BLE Keyer") { vm.path.append(.bleKeyer) }
                    Button("Disconnect", role: .destructive) {
                        vm.disconnect()
                        vm.path.removeAll()
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .sheet(isPresented: $showFreqInput) { FreqInputView(vm: vm) }
        .confirmationDialog("Mode", isPresented: $showModePicker) {
            ForEach(vm.supportedModes, id: \.self) { m in
                Button(m) { Task { await vm.setMode(m, width: vm.sharedWidth) } }
            }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Step", isPresented: $showStepPicker) {
            ForEach(AppConstants.stepHz.indices, id: \.self) { i in
                Button(AppConstants.stepLabels[i]) {
                    vm.selectedStepIndex = i
                    vm.rememberStepForCurrentMode()
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .onAppear { applyWakeLock() }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .onChange(of: vm.keepScreenAwake) { _, _ in
            applyWakeLock()
            vm.persistConnectionSettings()
        }
    }

    private func applyWakeLock() {
        UIApplication.shared.isIdleTimerDisabled = vm.keepScreenAwake
    }

    private var currentRigName: String {
        if vm.useCivMode && vm.civConnected {
            let model = vm.civ.getModelName()
            return model.isEmpty ? "CI-V" : model
        }
        if vm.rigList.indices.contains(vm.selectedRigIndex) {
            return vm.rigList[vm.selectedRigIndex].name
        }
        return "Rig"
    }

    // MARK: - Layouts

    private var portraitLayout: some View {
        VStack(spacing: 6) {
            headerCard
            meterBar
            panelGrid
            activeSlider
            nrAndCwDecodeRow                     // NR + DEC toggle in one row
            if vm.cwDecodeActive {
                cwDecodeTextOnly                 // RX/TX text shown only when active
            }
            volumeSlider
            micGainSlider
            keyerCard
            Spacer(minLength: 2)
            pttAndSpkBar
        }
    }

    private var landscapeLayout: some View {
        HStack(alignment: .top, spacing: 10) {
            // Left column — tuning controls, compact for landscape height.
            VStack(spacing: 4) {
                headerCardLandscape
                meterBar
                panelGrid6Wide                  // 1 row × 6 cells
                activeSlider
                nrAndCwDecodeRow                // NR + DEC toggle (button only)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity)

            // Right column — audio + keyer + PTT at bottom. DEC text (when active) goes
            // ABOVE the volume slider so it's actually visible in landscape.
            VStack(spacing: 4) {
                if vm.cwDecodeActive {
                    cwDecodeTextOnly
                }
                volumeSlider
                micGainSlider
                keyerCardLandscape
                Spacer(minLength: 0)
                pttBarCompact
            }
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Landscape-compact variants

    /// Smaller freq + inline TX badge (no separate row) so the header takes < ~60pt.
    private var headerCardLandscape: some View {
        HStack(spacing: 4) {
            Button { Task { await vm.stepFreq(-1) } } label: {
                Image(systemName: "minus.circle.fill")
                    .font(.system(size: 24))
                    .foregroundStyle(.pink)
                    .padding(4)
            }
            .buttonStyle(.plain)

            HStack(alignment: .lastTextBaseline, spacing: 3) {
                Text(formatFreqDigits(vm.sharedFreq))
                    .font(.system(size: 52, weight: .semibold, design: .monospaced))
                    .lineLimit(1)
                    .minimumScaleFactor(0.3)
                    .foregroundStyle(vm.sharedTx ? Color.red : Color.primary)
                Text("MHz")
                    .font(.system(size: 10, weight: .regular, design: .monospaced))
                    .foregroundStyle(.secondary)
                if vm.sharedTx {
                    Text("TX")
                        .font(.caption2.bold())
                        .padding(.horizontal, 6).padding(.vertical, 1)
                        .background(Color.red, in: Capsule())
                        .foregroundStyle(.white)
                }
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
            .onTapGesture { showFreqInput = true }

            Button { Task { await vm.stepFreq(+1) } } label: {
                Image(systemName: "plus.circle.fill")
                    .font(.system(size: 24))
                    .foregroundStyle(.teal)
                    .padding(4)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 4)
        .background(
            LinearGradient(
                colors: [Color.indigo.opacity(0.10), Color.teal.opacity(0.10)],
                startPoint: .topLeading, endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 8)
        )
    }

    /// Single-row 6-cell panel (matches Android `tvStep|tvMode|tvWidth|tvPow|tvSQL|tvBkIn`).
    private var panelGrid6Wide: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 6),
                  spacing: 4) {
            panelCell(title: "Step",  value: AppConstants.stepLabels[vm.selectedStepIndex], accent: .green,  isSelected: false) {
                showStepPicker = true
            }
            panelCell(title: "Mode",  value: vm.sharedMode.isEmpty ? "—" : vm.sharedMode, accent: .teal, isSelected: false) {
                showModePicker = true
            }
            panelCell(title: "Width", value: formatWidth(vm.sharedWidth), accent: .blue,
                      isSelected: selectedParam == .width) {
                selectedParam = .width
            }
            panelCell(title: "Power", value: String(format: "%.0f%%", vm.sharedPower * 100), accent: .orange,
                      isSelected: selectedParam == .power) {
                selectedParam = .power
            }
            panelCell(title: "SQL",   value: String(format: "%.0f%%", vm.sharedSQL * 100), accent: .purple,
                      isSelected: selectedParam == .squelch) {
                selectedParam = .squelch
            }
            panelCell(title: "BK-IN", value: vm.sharedBkIn ? "ON" : "OFF",
                      accent: vm.sharedBkIn ? .orange : .gray, isSelected: false) {
                Task { await vm.setBkIn(on: !vm.sharedBkIn) }
            }
        }
    }

    /// NR buttons + CW Decode toggle in one row to save vertical space.
    private var nrAndCwDecodeRow: some View {
        HStack(spacing: 4) {
            Text("NR").font(.caption2.bold()).foregroundStyle(.purple)
            ForEach(0...5, id: \.self) { lv in
                Button {
                    Task { await vm.setNoiseReduction(lv) }
                } label: {
                    Text(lv == 0 ? "Off" : "\(lv)")
                        .font(.caption2.bold())
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                        .background(
                            vm.noiseReductionLevel == lv ? Color.purple : Color.purple.opacity(0.12),
                            in: RoundedRectangle(cornerRadius: 5)
                        )
                        .foregroundStyle(vm.noiseReductionLevel == lv ? .white : .purple)
                }
                .buttonStyle(.plain)
            }
            Divider().frame(height: 16)
            Button {
                Task { await vm.toggleCwDecode() }
            } label: {
                HStack(spacing: 3) {
                    Image(systemName: "text.bubble.fill").font(.caption2)
                    Text(vm.cwDecodeActive ? "DEC ON" : "DEC")
                        .font(.caption2.bold())
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                }
                .padding(.horizontal, 6).padding(.vertical, 4)
                .background(
                    vm.cwDecodeActive ? Color.yellow.opacity(0.9) : Color.gray.opacity(0.25),
                    in: RoundedRectangle(cornerRadius: 5)
                )
                .foregroundStyle(vm.cwDecodeActive ? .black : .secondary)
            }
            .buttonStyle(.plain)
            .fixedSize(horizontal: true, vertical: false)
        }
    }

    /// Compact 2-row keyer card for landscape (vs. 4 rows in portrait keyerCard).
    private var keyerCardLandscape: some View {
        VStack(spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: vm.cwBle.connected ? "antenna.radiowaves.left.and.right.circle.fill" : "antenna.radiowaves.left.and.right.slash")
                    .foregroundStyle(vm.cwBle.connected ? .green : .secondary)
                    .font(.caption)
                Text(vm.cwBle.connected ? (vm.cwBle.connectedName ?? "BLE") : "BLE off")
                    .font(.caption2.bold())
                    .lineLimit(1)
                Spacer()
                Toggle("", isOn: Binding(
                    get: { vm.cwBle.sidetoneEnabled },
                    set: { vm.cwBle.sidetoneEnabled = $0 }
                ))
                .labelsHidden()
                .scaleEffect(0.75)
                .tint(.indigo)
                Text("ST")
                    .font(.caption2.bold())
                    .foregroundStyle(.indigo)
                Toggle("", isOn: Binding(
                    get: { vm.cwBle.paddleSwapped },
                    set: { vm.cwBle.paddleSwapped = $0 }
                ))
                .labelsHidden()
                .scaleEffect(0.75)
                .tint(.orange)
                Text("L↔R")
                    .font(.caption2.bold())
                    .foregroundStyle(.orange)
                Toggle("", isOn: Binding(
                    get: { vm.spkEnabled },
                    set: { newValue in Task { await vm.setSpk(on: newValue) } }
                ))
                .labelsHidden()
                .scaleEffect(0.75)
                .tint(.green)
                Text("SPK")
                    .font(.caption2.bold())
                    .foregroundStyle(vm.spkEnabled ? .green : .secondary)
            }
            HStack(spacing: 6) {
                Text("Tone").font(.caption2).foregroundStyle(.indigo)
                Slider(value: Binding(
                    get: { vm.cwBle.sidetoneFrequencyHz },
                    set: { vm.cwBle.sidetoneFrequencyHz = $0 }
                ), in: 400...1200, step: 50)
                .tint(.indigo)
                Text("\(Int(vm.cwBle.sidetoneFrequencyHz))")
                    .font(.caption2.monospacedDigit())
                    .frame(width: 36, alignment: .trailing)
                Text("WPM").font(.caption2).foregroundStyle(.indigo)
                Slider(value: Binding(
                    get: { Double(vm.cwWpm) },
                    set: {
                        let w = max(5, min(60, Int($0.rounded())))
                        vm.cwWpm = w
                        vm.cwBle.sendWpm(w)
                    }
                ), in: 5...60, step: 1)
                .tint(.indigo)
                Text("\(vm.cwWpm)")
                    .font(.caption2.monospacedDigit())
                    .frame(width: 22, alignment: .trailing)
            }
        }
        .padding(6)
        .background(Color.indigo.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
    }

    /// Compact SPK + PTT bar for landscape, equal width.
    private var pttBarCompact: some View {
        HStack(spacing: 6) {
            Button {
                Task { await vm.setSpk(on: !vm.spkEnabled) }
            } label: {
                VStack(spacing: 2) {
                    Image(systemName: vm.spkEnabled ? "speaker.wave.2.fill" : "speaker.fill")
                        .font(.body)
                    Text("SPK").font(.caption.bold())
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(
                    vm.spkEnabled ? Color.green : Color.gray.opacity(0.25),
                    in: RoundedRectangle(cornerRadius: 10)
                )
                .foregroundStyle(vm.spkEnabled ? .white : .secondary)
                .shadow(color: vm.spkEnabled ? Color.green.opacity(0.4) : .clear, radius: 3, x: 0, y: 2)
            }
            Button {
                Task { await vm.setPtt(on: !vm.txEnabled) }
            } label: {
                Text(vm.txEnabled ? "TX" : "PTT")
                    .font(.title3.bold())
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(
                        vm.txEnabled
                        ? LinearGradient(colors: [.red, Color(red: 1.0, green: 0.4, blue: 0.2)],
                                         startPoint: .topLeading, endPoint: .bottomTrailing)
                        : LinearGradient(colors: [Color.indigo, Color.purple, Color.pink],
                                         startPoint: .topLeading, endPoint: .bottomTrailing),
                        in: RoundedRectangle(cornerRadius: 10)
                    )
                    .foregroundStyle(.white)
                    .shadow(color: (vm.txEnabled ? Color.red : Color.purple).opacity(0.4), radius: 4, x: 0, y: 2)
            }
        }
    }

    // MARK: - Header (Frequency + Mode badge + TX + Step ±)

    private var headerCard: some View {
        HStack(spacing: 4) {
            Button { Task { await vm.stepFreq(-1) } } label: {
                Image(systemName: "minus.circle.fill")
                    .font(.system(size: 27))
                    .foregroundStyle(.pink)
                    .padding(6)
            }
            .buttonStyle(.plain)

            // Center: huge frequency digits + small "MHz" + TX badge
            VStack(spacing: 2) {
                HStack(alignment: .lastTextBaseline, spacing: 3) {
                    Text(formatFreqDigits(vm.sharedFreq))
                        .font(.system(size: 96, weight: .semibold, design: .monospaced))
                        .lineLimit(1)
                        .minimumScaleFactor(0.3)
                        .foregroundStyle(vm.sharedTx ? Color.red : Color.primary)
                    Text("MHz")
                        .font(.system(size: 14, weight: .regular, design: .monospaced))
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
                .onTapGesture { showFreqInput = true }
                if vm.sharedTx {
                    Text("TX")
                        .font(.caption.bold())
                        .padding(.horizontal, 10).padding(.vertical, 2)
                        .background(Color.red, in: Capsule())
                        .foregroundStyle(.white)
                }
            }
            .frame(maxWidth: .infinity)

            Button { Task { await vm.stepFreq(+1) } } label: {
                Image(systemName: "plus.circle.fill")
                    .font(.system(size: 27))
                    .foregroundStyle(.teal)
                    .padding(6)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 6)
        .background(
            LinearGradient(
                colors: [Color.indigo.opacity(0.10), Color.teal.opacity(0.10)],
                startPoint: .topLeading, endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 10)
        )
    }

    private var meterBar: some View {
        HStack(spacing: 6) {
            Image(systemName: "gauge.with.dots.needle.67percent")
                .foregroundStyle(.cyan)
                .font(.caption)
            SMeterView(value: vm.sharedSignal)
            if vm.civConnected {
                HStack(spacing: 3) {
                    Circle().fill(Color.teal).frame(width: 7, height: 7)
                    Text("CI-V").font(.caption2.bold()).foregroundStyle(.teal)
                }
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(Color.teal.opacity(0.15), in: Capsule())
            }
        }
        .padding(8)
        .background(Color.cyan.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
    }

    // MARK: - 6-cell parameter panel (Android tvStep/tvMode/tvWidth/tvPow/tvSQL/tvBkIn)

    private var panelGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())],
                  spacing: 6) {
            panelCell(title: "Step",  value: AppConstants.stepLabels[vm.selectedStepIndex], accent: .green,  isSelected: false) {
                showStepPicker = true
            }
            panelCell(title: "Mode",  value: vm.sharedMode.isEmpty ? "—" : vm.sharedMode, accent: .teal, isSelected: false) {
                showModePicker = true
            }
            panelCell(title: "Width", value: formatWidth(vm.sharedWidth), accent: .blue,
                      isSelected: selectedParam == .width) {
                selectedParam = .width
            }
            panelCell(title: "Power", value: String(format: "%.0f%%", vm.sharedPower * 100), accent: .orange,
                      isSelected: selectedParam == .power) {
                selectedParam = .power
            }
            panelCell(title: "SQL",   value: String(format: "%.0f%%", vm.sharedSQL * 100), accent: .purple,
                      isSelected: selectedParam == .squelch) {
                selectedParam = .squelch
            }
            panelCell(title: "BK-IN", value: vm.sharedBkIn ? "ON" : "OFF",
                      accent: vm.sharedBkIn ? .orange : .gray, isSelected: false) {
                Task { await vm.setBkIn(on: !vm.sharedBkIn) }
            }
        }
    }

    /// Compact 3x2 variant — same buttons as `panelGrid` but smaller cells so the
    /// frequency display above doesn't shrink when CW DEC text is shown below.
    private var panelGridCompact: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())],
                  spacing: 4) {
            compactPanelCell(title: "Step",  value: AppConstants.stepLabels[vm.selectedStepIndex], accent: .green,  isSelected: false) {
                showStepPicker = true
            }
            compactPanelCell(title: "Mode",  value: vm.sharedMode.isEmpty ? "—" : vm.sharedMode, accent: .teal, isSelected: false) {
                showModePicker = true
            }
            compactPanelCell(title: "Width", value: formatWidth(vm.sharedWidth), accent: .blue,
                             isSelected: selectedParam == .width) {
                selectedParam = .width
            }
            compactPanelCell(title: "Power", value: String(format: "%.0f%%", vm.sharedPower * 100), accent: .orange,
                             isSelected: selectedParam == .power) {
                selectedParam = .power
            }
            compactPanelCell(title: "SQL",   value: String(format: "%.0f%%", vm.sharedSQL * 100), accent: .purple,
                             isSelected: selectedParam == .squelch) {
                selectedParam = .squelch
            }
            compactPanelCell(title: "BK-IN", value: vm.sharedBkIn ? "ON" : "OFF",
                             accent: vm.sharedBkIn ? .orange : .gray, isSelected: false) {
                Task { await vm.setBkIn(on: !vm.sharedBkIn) }
            }
        }
    }

    private func compactPanelCell(title: String, value: String, accent: Color, isSelected: Bool,
                                  action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Text(title)
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.secondary)
                Text(value)
                    .font(.caption.monospacedDigit().bold())
                    .foregroundStyle(accent)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 3)
            .padding(.horizontal, 4)
            .background(
                (isSelected ? accent.opacity(0.25) : accent.opacity(0.10)),
                in: RoundedRectangle(cornerRadius: 6)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 6)
                    .stroke(isSelected ? accent : accent.opacity(0.35),
                            lineWidth: isSelected ? 2 : 1)
            )
        }
        .buttonStyle(.plain)
    }

    private func panelCell(title: String, value: String, accent: Color, isSelected: Bool,
                           action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Text(title)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Text(value)
                    .font(.callout.monospacedDigit().bold())
                    .foregroundStyle(accent)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .background(
                (isSelected ? accent.opacity(0.25) : accent.opacity(0.10)),
                in: RoundedRectangle(cornerRadius: 8)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(isSelected ? accent : accent.opacity(0.35),
                            lineWidth: isSelected ? 2 : 1)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Active slider (bound to whichever panel was last tapped)

    @ViewBuilder
    private var activeSlider: some View {
        switch selectedParam {
        case .width:
            widthSlider
        case .power:
            sliderRow(title: "Power", value: vm.sharedPower, accent: .orange) { v in
                Task { await vm.setPower(v) }
            }
        case .squelch:
            sliderRow(title: "Squelch", value: vm.sharedSQL, accent: .purple) { v in
                Task { await vm.setSquelch(v) }
            }
        }
    }

    private var widthSlider: some View {
        let count = widthOptions.count
        let currentIdx = widthOptions.firstIndex(of: vm.sharedWidth) ?? closestIndex(to: vm.sharedWidth)
        return VStack(spacing: 2) {
            HStack {
                Label("Width", systemImage: "waveform.path").font(.caption).foregroundStyle(.blue)
                Spacer()
                Text(formatWidth(vm.sharedWidth)).font(.caption.monospacedDigit())
            }
            Slider(value: Binding(
                get: { Double(currentIdx) },
                set: { newIdx in
                    let i = max(0, min(count - 1, Int(newIdx.rounded())))
                    let w = widthOptions[i]
                    Task { await vm.setMode(vm.sharedMode, width: w) }
                }
            ), in: 0...Double(count - 1), step: 1)
            .tint(.blue)
        }
    }

    private func closestIndex(to width: Int) -> Int {
        var best = 0
        var bestDiff = Int.max
        for (i, w) in widthOptions.enumerated() {
            let d = abs(w - width)
            if d < bestDiff { bestDiff = d; best = i }
        }
        return best
    }

    private func formatWidth(_ hz: Int) -> String {
        if hz >= 1000 { return String(format: "%.1fk", Double(hz) / 1000.0) }
        return "\(hz)"
    }

    // MARK: - Always-visible: Volume / Mic Gain (matches Android pattern)

    private var volumeSlider: some View {
        sliderRow(title: "Volume", value: vm.sharedVolume, accent: .green) { v in
            Task { await vm.setVolume(v) }
        }
    }

    private var micGainSlider: some View {
        VStack(spacing: 2) {
            HStack {
                Label("Mic Gain", systemImage: "mic.fill").font(.caption).foregroundStyle(.pink)
                Spacer()
                Text(String(format: "%.1fx", vm.micGain)).font(.caption.monospacedDigit())
            }
            Slider(value: Binding(
                get: { Double(vm.micGain) },
                set: { vm.setMicGain(Float($0)) }
            ), in: 0.1...4.0)
            .tint(.pink)
        }
    }

    private func sliderRow(title: LocalizedStringKey, value: Double, accent: Color,
                           onChange: @escaping (Double) -> Void) -> some View {
        VStack(spacing: 2) {
            HStack {
                Label(title, systemImage: sliderIcon(for: title))
                    .font(.caption).foregroundStyle(accent)
                Spacer()
                Text(String(format: "%.0f%%", value * 100)).font(.caption.monospacedDigit())
            }
            Slider(value: Binding(get: { value }, set: { onChange($0) }), in: 0...1)
                .tint(accent)
        }
    }

    private func sliderIcon(for title: LocalizedStringKey) -> String {
        let key = "\(title)"
        if key.contains("Power") { return "bolt.fill" }
        if key.contains("Squelch") { return "speaker.slash.fill" }
        if key.contains("Volume") { return "speaker.wave.2.fill" }
        return "slider.horizontal.3"
    }

    // MARK: - NR buttons (Android: 6-cell grid Off/NR1..NR5)

    private var nrButtonsRow: some View {
        HStack(spacing: 4) {
            Text("NR").font(.caption2.bold()).foregroundStyle(.purple)
            ForEach(0...5, id: \.self) { lv in
                Button {
                    Task { await vm.setNoiseReduction(lv) }
                } label: {
                    Text(lv == 0 ? "Off" : "\(lv)")
                        .font(.caption2.bold())
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 5)
                        .background(
                            vm.noiseReductionLevel == lv
                            ? Color.purple
                            : Color.purple.opacity(0.12),
                            in: RoundedRectangle(cornerRadius: 6)
                        )
                        .foregroundStyle(vm.noiseReductionLevel == lv ? .white : .purple)
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - CW decode text-only display (used when decode is active)
    // Toggle is in `nrAndCwDecodeRow`; this view shows just the live RX/TX text streams
    // so it can be placed flexibly (above volume slider in landscape, etc.).

    private var cwDecodeTextOnly: some View {
        VStack(spacing: 2) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    Text("RX")
                        .font(.caption2.bold()).foregroundStyle(.orange)
                    Text(vm.cwRxDecodedText.isEmpty ? "—" : vm.cwRxDecodedText)
                        .font(.caption.monospaced())
                        .foregroundStyle(.orange)
                        .lineLimit(1)
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    Text("TX")
                        .font(.caption2.bold()).foregroundStyle(.cyan)
                    Text(vm.cwTxDecodedText.isEmpty ? "—" : vm.cwTxDecodedText)
                        .font(.caption.monospaced())
                        .foregroundStyle(.cyan)
                        .lineLimit(1)
                }
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 4)
        .background(Color.yellow.opacity(0.08), in: RoundedRectangle(cornerRadius: 6))
    }

    // MARK: - CW decode panel (legacy combined toggle + text — kept for compatibility)

    private var cwDecodeRow: some View {
        VStack(spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: "text.bubble.fill")
                    .font(.caption2)
                    .foregroundStyle(vm.cwDecodeActive ? .yellow : .secondary)
                Text("CW Decode").font(.caption.bold())
                Spacer()
                Button {
                    Task { await vm.toggleCwDecode() }
                } label: {
                    Text(vm.cwDecodeActive ? "ON" : "OFF")
                        .font(.caption2.bold())
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(
                            vm.cwDecodeActive ? Color.yellow.opacity(0.9) : Color.gray.opacity(0.25),
                            in: Capsule()
                        )
                        .foregroundStyle(vm.cwDecodeActive ? .black : .secondary)
                }
                .buttonStyle(.plain)
            }
            if vm.cwDecodeActive {
                // RX (orange) and TX (cyan) decoded streams. Both scroll horizontally
                // since CW decode tends to produce long single-line text.
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 4) {
                        Text("RX")
                            .font(.caption2.bold()).foregroundStyle(.orange)
                        Text(vm.cwRxDecodedText.isEmpty ? "—" : vm.cwRxDecodedText)
                            .font(.caption.monospaced())
                            .foregroundStyle(.orange)
                            .lineLimit(1)
                    }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 4) {
                        Text("TX")
                            .font(.caption2.bold()).foregroundStyle(.cyan)
                        Text(vm.cwTxDecodedText.isEmpty ? "—" : vm.cwTxDecodedText)
                            .font(.caption.monospaced())
                            .foregroundStyle(.cyan)
                            .lineLimit(1)
                    }
                }
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 6)
        .background(Color.yellow.opacity(0.06), in: RoundedRectangle(cornerRadius: 8))
    }

    // MARK: - CW Keyer card (inline BLE keyer status + sidetone + WPM + SPK)

    private var keyerCard: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: vm.cwBle.connected ? "antenna.radiowaves.left.and.right.circle.fill" : "antenna.radiowaves.left.and.right.slash")
                    .foregroundStyle(vm.cwBle.connected ? .green : .secondary)
                Text(vm.cwBle.connected ? (vm.cwBle.connectedName ?? "CW Keyer") : "CW Keyer (offline)")
                    .font(.caption.bold())
                Spacer()
            }
            HStack(spacing: 8) {
                Toggle(isOn: Binding(
                    get: { vm.cwBle.sidetoneEnabled },
                    set: { vm.cwBle.sidetoneEnabled = $0 }
                )) {
                    Label("Sidetone", systemImage: "waveform")
                        .font(.caption)
                }
                .toggleStyle(.switch)
                .tint(.indigo)
                .labelsHidden()
                Label("ST", systemImage: "waveform").font(.caption2).foregroundStyle(.indigo)
                Spacer(minLength: 8)
                Toggle(isOn: Binding(
                    get: { vm.cwBle.paddleSwapped },
                    set: { vm.cwBle.paddleSwapped = $0 }
                )) { EmptyView() }
                .toggleStyle(.switch)
                .tint(.orange)
                .labelsHidden()
                Label("L↔R", systemImage: "arrow.left.arrow.right")
                    .font(.caption2).foregroundStyle(.orange)
                Spacer()
                Text("WPM \(vm.cwWpm)").font(.caption.monospacedDigit())
            }
            HStack(spacing: 8) {
                Text("Tone").font(.caption2).foregroundStyle(.indigo)
                Slider(value: Binding(
                    get: { vm.cwBle.sidetoneFrequencyHz },
                    set: { vm.cwBle.sidetoneFrequencyHz = $0 }
                ), in: 400...1200, step: 50)
                .tint(.indigo)
                Text("\(Int(vm.cwBle.sidetoneFrequencyHz))Hz")
                    .font(.caption2.monospacedDigit())
                    .frame(width: 56, alignment: .trailing)
            }
            HStack(spacing: 8) {
                Text("WPM").font(.caption2).foregroundStyle(.indigo)
                Slider(value: Binding(
                    get: { Double(vm.cwWpm) },
                    set: {
                        let w = max(5, min(60, Int($0.rounded())))
                        vm.cwWpm = w
                        // Push to Atom over BLE so paddle keyer chip speed follows.
                        vm.cwBle.sendWpm(w)
                    }
                ), in: 5...60, step: 1)
                .tint(.indigo)
                Text("\(vm.cwWpm)").font(.caption2.monospacedDigit()).frame(width: 36, alignment: .trailing)
            }
        }
        .padding(8)
        .background(Color.indigo.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - PTT bar

    private var pttBar: some View {
        Button {
            Task { await vm.setPtt(on: !vm.txEnabled) }
        } label: {
            Text(vm.txEnabled ? "TX" : "PTT")
                .font(.title.bold())
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(
                    vm.txEnabled
                    ? LinearGradient(colors: [.red, Color(red: 1.0, green: 0.4, blue: 0.2)],
                                     startPoint: .topLeading, endPoint: .bottomTrailing)
                    : LinearGradient(colors: [Color.indigo, Color.purple, Color.pink],
                                     startPoint: .topLeading, endPoint: .bottomTrailing),
                    in: RoundedRectangle(cornerRadius: 12)
                )
                .foregroundStyle(.white)
                .shadow(color: (vm.txEnabled ? Color.red : Color.purple).opacity(0.4), radius: 6, x: 0, y: 3)
        }
    }

    /// SPK + PTT side by side, equal width.
    private var pttAndSpkBar: some View {
        HStack(spacing: 8) {
            Button {
                Task { await vm.setSpk(on: !vm.spkEnabled) }
            } label: {
                VStack(spacing: 3) {
                    Image(systemName: vm.spkEnabled ? "speaker.wave.2.fill" : "speaker.fill")
                        .font(.title2)
                    Text("SPK")
                        .font(.caption.bold())
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(
                    vm.spkEnabled ? Color.green : Color.gray.opacity(0.25),
                    in: RoundedRectangle(cornerRadius: 12)
                )
                .foregroundStyle(vm.spkEnabled ? .white : .secondary)
                .shadow(color: vm.spkEnabled ? Color.green.opacity(0.4) : .clear, radius: 4, x: 0, y: 2)
            }

            Button {
                Task { await vm.setPtt(on: !vm.txEnabled) }
            } label: {
                Text(vm.txEnabled ? "TX" : "PTT")
                    .font(.title.bold())
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(
                        vm.txEnabled
                        ? LinearGradient(colors: [.red, Color(red: 1.0, green: 0.4, blue: 0.2)],
                                         startPoint: .topLeading, endPoint: .bottomTrailing)
                        : LinearGradient(colors: [Color.indigo, Color.purple, Color.pink],
                                         startPoint: .topLeading, endPoint: .bottomTrailing),
                        in: RoundedRectangle(cornerRadius: 12)
                    )
                    .foregroundStyle(.white)
                    .shadow(color: (vm.txEnabled ? Color.red : Color.purple).opacity(0.4), radius: 6, x: 0, y: 3)
            }
        }
    }

    private func formatFreq(_ hz: Int64) -> String {
        let mhz = Double(hz) / 1_000_000.0
        return String(format: "%.5f MHz", mhz)
    }

    /// Digits-only MHz string (without trailing " MHz"), so the unit can be rendered
    /// in a separate smaller `Text` while the digits get full prominence.
    private func formatFreqDigits(_ hz: Int64) -> String {
        let mhz = Double(hz) / 1_000_000.0
        return String(format: "%.5f", mhz)
    }
}

// MARK: - S-Meter view

struct SMeterView: View {
    /// 0…15 S-unit value (after `normalizedSignal` mapping in MainViewModel).
    let value: Double
    /// Android-equivalent: 30 bars (2 bars = 1 S-unit). threshold = signal * 2.
    private let totalBars = 30
    /// Bar index → S-scale label (matches Android `updateSMeter`).
    private let scaleMarks: [(barIdx: Int, label: String)] = [
        (2, "1"), (6, "3"), (10, "5"), (14, "7"), (18, "9"),
        (21, "+10"), (24, "+20"), (28, "+30")
    ]

    var body: some View {
        VStack(spacing: 1) {
            GeometryReader { geo in
                HStack(spacing: 2) {
                    ForEach(0..<totalBars, id: \.self) { i in
                        let active = isActive(i)
                        let c = barColor(forBar: i, active: active)
                        RoundedRectangle(cornerRadius: 2)
                            .fill(
                                LinearGradient(
                                    colors: [c, c.opacity(active ? 0.75 : 0.6)],
                                    startPoint: .top, endPoint: .bottom
                                )
                            )
                            .shadow(color: active ? c.opacity(0.8) : .clear,
                                    radius: active ? 3 : 0)
                            .animation(.easeOut(duration: 0.10), value: active)
                            .frame(maxWidth: .infinity)
                    }
                }
                .frame(height: geo.size.height)
            }
            .frame(height: 16)
            // Scale labels: S1/S3/S5/S7/S9/+10/+20/+30 — Android exact placements
            GeometryReader { geo in
                let barW = (geo.size.width - CGFloat(totalBars - 1) * 2) / CGFloat(totalBars)
                ZStack(alignment: .leading) {
                    ForEach(scaleMarks, id: \.barIdx) { mark in
                        Text(mark.label)
                            .font(.system(size: 7, weight: .medium, design: .monospaced))
                            .foregroundStyle(labelColor(for: mark.barIdx))
                            .offset(x: CGFloat(mark.barIdx) * (barW + 2) - 4)
                    }
                }
                .frame(height: 8)
            }
            .frame(height: 8)
        }
    }

    /// Android logic: `threshold = (signal * 2).toInt()`; bar lit if `i < threshold`.
    private func isActive(_ i: Int) -> Bool {
        guard value.isFinite else { return false }
        let threshold = Int(max(0.0, min(value, 15.0)) * 2.0)
        return i < threshold
    }

    /// Android color scheme:
    /// - 0…17 (S1..S9):  Green  (active) / dark green  (inactive)
    /// - 18…23 (S9+12dB): Yellow / dark yellow
    /// - 24…27 (S9+30dB): Orange / dark orange
    /// - 28+   (S9+30dB+): Red   / dark red
    private func barColor(forBar i: Int, active: Bool) -> Color {
        if active {
            switch i {
            case 0...17: return Color(red: 0x00/255.0, green: 0xC8/255.0, blue: 0x53/255.0)   // green
            case 18...23: return Color(red: 0xFD/255.0, green: 0xD8/255.0, blue: 0x35/255.0)  // yellow
            case 24...27: return Color(red: 0xFF/255.0, green: 0x6D/255.0, blue: 0x00/255.0)  // orange
            default:     return Color(red: 0xD5/255.0, green: 0x00/255.0, blue: 0x00/255.0)   // red
            }
        } else {
            // Inactive: very dark tinted by the same hue (matches Android 0x0A2A14 etc.)
            switch i {
            case 0...17: return Color(red: 0x0A/255.0, green: 0x2A/255.0, blue: 0x14/255.0)
            case 18...23: return Color(red: 0x2A/255.0, green: 0x28/255.0, blue: 0x00/255.0)
            case 24...27: return Color(red: 0x2A/255.0, green: 0x14/255.0, blue: 0x00/255.0)
            default:     return Color(red: 0x2A/255.0, green: 0x00/255.0, blue: 0x00/255.0)
            }
        }
    }

    /// Scale label color per Android's labelColors map.
    private func labelColor(for bar: Int) -> Color {
        switch bar {
        case 2, 6, 10, 14: return Color(red: 0x3A/255.0, green: 0x7A/255.0, blue: 0x4A/255.0)
        case 18:           return Color(red: 0x00/255.0, green: 0xC8/255.0, blue: 0x53/255.0)
        case 21:           return Color(red: 0xBD/255.0, green: 0xA0/255.0, blue: 0x00/255.0)
        case 24:           return Color(red: 0xFD/255.0, green: 0xD8/255.0, blue: 0x35/255.0)
        case 28:           return Color(red: 0xFF/255.0, green: 0x6D/255.0, blue: 0x00/255.0)
        default:           return .secondary
        }
    }

}

// MARK: - Freq input sheet

struct FreqInputView: View {
    @Bindable var vm: MainViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var text: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section(header: Text("Frequency (MHz)")) {
                    TextField("e.g. 14.250", text: $text)
                        .keyboardType(.decimalPad)
                        .font(.title2.monospaced())
                }
            }
            .navigationTitle(Text("Frequency Input"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("OK") {
                        let normalized = text.replacingOccurrences(of: ",", with: ".")
                        if let mhz = Double(normalized), mhz > 0 {
                            let hz = Int64((mhz * 1_000_000.0).rounded())
                            Task { await vm.setFreq(hz) }
                        }
                        dismiss()
                    }
                }
            }
        }
    }
}
/*
// MARK: - CW TX bottom sheet (removed — access CW TX via CW menu)

struct CwTxPanelView: View {
    @Bindable var vm: MainViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var freeText: String = ""
    @State private var dxCall: String = ""
    @State private var rst: String = "599"
    @State private var pota: String = ""
    @State private var jcc: String = ""

    private let nrLevels: [Int] = [0, 1, 2, 3, 4, 5]

    var body: some View {
        NavigationStack {
            Form {
                Section("Speed") {
                    HStack {
                        Text("WPM").font(.caption).foregroundStyle(.indigo)
                        Slider(value: Binding(
                            get: { Double(vm.cwWpm) },
                            set: { vm.cwWpm = Int($0.rounded()) }
                        ), in: 5...55, step: 1)
                        Text("\(vm.cwWpm)").font(.caption.monospacedDigit()).frame(width: 30)
                    }
                }

                Section("Noise Reduction") {
                    Picker("NR Level", selection: Binding(
                        get: { vm.noiseReductionLevel },
                        set: { newLevel in
                            vm.noiseReductionLevel = newLevel
                            Task { await vm.applyNoiseReduction() }
                        }
                    )) {
                        ForEach(nrLevels, id: \.self) { lv in
                            Text(lv == 0 ? "Off" : "NR\(lv)").tag(lv)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section("Operator") {
                    HStack {
                        Text("DX").frame(width: 50, alignment: .leading).font(.caption.bold())
                        TextField("Callsign", text: $dxCall)
                            .autocapitalization(.allCharacters)
                            .disableAutocorrection(true)
                    }
                    HStack {
                        Text("RST").frame(width: 50, alignment: .leading).font(.caption.bold())
                        TextField("599", text: $rst).keyboardType(.numberPad)
                        ForEach(["5NN", "R1", "R2", "R3"], id: \.self) { p in
                            Button(p) { rst = p }.buttonStyle(.bordered).controlSize(.mini)
                        }
                    }
                    HStack {
                        Text("POTA").frame(width: 50, alignment: .leading).font(.caption.bold())
                        TextField("e.g. JA-1234", text: $pota)
                            .autocapitalization(.allCharacters)
                            .disableAutocorrection(true)
                    }
                    HStack {
                        Text("JCC").frame(width: 50, alignment: .leading).font(.caption.bold())
                        TextField("JCC code", text: $jcc)
                            .keyboardType(.numbersAndPunctuation)
                    }
                }

                Section("Free Text") {
                    TextField("Type morse text", text: $freeText)
                        .autocapitalization(.allCharacters)
                        .disableAutocorrection(true)
                    HStack {
                        Button {
                            Task {
                                let combined = combineMessage()
                                if !combined.isEmpty { vm.cwLastText = combined }
                                _ = await vm.cwSendMorse()
                            }
                        } label: {
                            Label("Send", systemImage: "paperplane.fill")
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.indigo)
                        Spacer()
                        Button(role: .destructive) {
                            Task { await vm.cwStopMorse() }
                        } label: {
                            Label("Stop", systemImage: "stop.fill")
                        }
                        .buttonStyle(.bordered)
                    }
                }

                Section("CQ Presets") {
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                        ForEach(["CQ", "CQ DX K", "CQ TU", "CQ AGN", "CQ UR", "Call"], id: \.self) { p in
                            Button(p) { freeText = p }
                                .buttonStyle(.bordered)
                                .font(.caption)
                        }
                    }
                }

                Section("ANS Presets") {
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                        ForEach(["DE", "UR", "TU", "AGN", "PSE", "TNX"], id: \.self) { p in
                            Button(p) {
                                freeText = freeText.isEmpty ? p : "\(freeText) \(p)"
                            }
                            .buttonStyle(.bordered)
                            .font(.caption)
                        }
                    }
                }
            }
            .navigationTitle("CW TX")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
        }
    }

    private func combineMessage() -> String {
        var parts: [String] = []
        if !freeText.isEmpty { parts.append(freeText) }
        if !dxCall.isEmpty { parts.append(dxCall) }
        if !rst.isEmpty { parts.append("RST \(rst)") }
        if !pota.isEmpty { parts.append("POTA \(pota)") }
        if !jcc.isEmpty { parts.append("JCC \(jcc)") }
        return parts.joined(separator: " ")
    }
}
*/
