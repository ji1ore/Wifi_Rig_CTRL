import SwiftUI
import UIKit

/// Disables the interactive swipe-back gesture on the enclosing NavigationStack page.
/// Without this, a left-edge swipe exits MainControlView even when the back button is hidden.
private struct SwipeBackDisabler: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { UIViewController() }
    func updateUIViewController(_ vc: UIViewController, context: Context) {
        DispatchQueue.main.async {
            vc.navigationController?.interactivePopGestureRecognizer?.isEnabled = false
        }
    }
}

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

    @State private var showBeaconOverlay = false
    @State private var beaconHideTask: Task<Void, Never>? = nil
    @State private var beaconOverlayDismissed = false
    @State private var showMemSheet = false
    @State private var showRepeaterSheet = false

    private var isCwMode: Bool {
        vm.sharedMode.uppercased().contains("CW")
    }

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
        .animation(.easeInOut(duration: 0.35), value: showBeaconOverlay)
        .onChange(of: vm.aprsReceivedStations) { old, new in
            guard !new.isEmpty, !beaconOverlayDismissed else { return }
            let oldCalls = Set(old.map(\.call))
            let newCalls = Set(new.map(\.call))
            guard !newCalls.isSubset(of: oldCalls) else { return }
            showBeaconOverlay = true
            scheduleBeaconHide()
        }
        .onChange(of: vm.aprsActive) { _, active in
            if active { beaconOverlayDismissed = false }
        }
        .navigationTitle(currentRigName)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        .background(SwipeBackDisabler())
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    screenTimeoutMenu
                    Button("FT8") { vm.path.append(.ft8) }
                        .disabled(
                            (vm.useCivMode && vm.civConnected) ||
                            vm.ft8MyCall.trimmingCharacters(in: .whitespaces).isEmpty ||
                            vm.ft8MyGrid.trimmingCharacters(in: .whitespaces).isEmpty
                        )
                    Button("CW") { vm.path.append(.cw) }
                    Button("PTT Settings") { vm.path.append(.pttSettings) }
                    Button("APRS Settings") { vm.path.append(.aprsSettings) }
                        .disabled(vm.useCivMode && vm.civConnected)
                    Button("Scan BLE Keyer") { vm.path.append(.bleKeyer) }
                    Button("Admin (Update Pi)") { vm.path.append(.admin) }
                        .disabled(vm.useCivMode && vm.civConnected)
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
        .sheet(isPresented: $showMemSheet) { MemoryPanelView(vm: vm) }
        .sheet(isPresented: $showRepeaterSheet) { RepeaterSettingsView(vm: vm) }
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
        .onAppear { vm.applyWakeLock() }
        .onDisappear { vm.stopWakeLock() }
    }

    private func applyWakeLock() { vm.applyWakeLock() }

    @ViewBuilder private var screenTimeoutMenu: some View {
        Menu {
            screenTimeoutButton(mins: -1, label: "Always On")
            screenTimeoutButton(mins:  5, label: "5 min")
            screenTimeoutButton(mins: 15, label: "15 min")
            screenTimeoutButton(mins: 30, label: "30 min")
            screenTimeoutButton(mins: 60, label: "60 min")
            screenTimeoutButton(mins:  0, label: "System Default")
        } label: {
            Label("Screen: \(screenTimeoutLabel)", systemImage: "sun.max")
        }
    }

    private func screenTimeoutButton(mins: Int, label: String) -> some View {
        Button {
            vm.screenTimeoutMinutes = mins
            vm.applyWakeLock()
            vm.persistConnectionSettings()
        } label: {
            if vm.screenTimeoutMinutes == mins {
                Label(label, systemImage: "checkmark")
            } else {
                Text(label)
            }
        }
    }

    private var screenTimeoutLabel: String {
        switch vm.screenTimeoutMinutes {
        case -1: return "Always On"
        case  0: return "System"
        default: return "\(vm.screenTimeoutMinutes) min"
        }
    }

    private var currentRigName: String {
        let base: String
        if vm.useCivMode && vm.civConnected {
            let model = vm.civ.getModelName()
            base = model.isEmpty ? "CI-V" : model
        } else if vm.rigList.indices.contains(vm.selectedRigIndex) {
            base = vm.rigList[vm.selectedRigIndex].name
        } else {
            base = "Rig"
        }
        let prefix = vm.useCivMode ? "[CI-V] " : "[WiFi] "
        let name = "\(prefix)\(base)"
        if vm.piVersionMismatch && !base.isEmpty { return "\(name) ⚠UPDATE" }
        return name
    }

    // MARK: - Layouts

    private var portraitLayout: some View {
        VStack(spacing: 6) {
            headerCard
            meterBar
            panelGrid
                .overlay(alignment: .top) {
                    if showBeaconOverlay && !vm.aprsReceivedStations.isEmpty {
                        LargeBeaconOverlay(
                            stations: vm.aprsReceivedStations,
                            selfPos: vm.selfPosition,
                            onRefresh: { Task { await vm.refreshAprsReceived() } },
                            onDismiss: { dismissBeaconOverlay() }
                        )
                        .transition(.move(edge: .top).combined(with: .opacity))
                    }
                }
                .zIndex(10)
            beaconCompassCard
            activeSlider
            nrAndCwDecodeRow
            if vm.cwDecodeActive {
                cwDecodeTextOnly
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
                Text(formatFreqDigits(vm.displayFreq))
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

    /// Single-row 8-cell panel for landscape (v2.31: APRS→MEM, BK-IN→CW/APRS conditional).
    private var panelGrid6Wide: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 8),
                  spacing: 4) {
            panelCell(title: "VFO", value: vfoLabel, accent: .mint, isSelected: false) {
                Task { await vm.toggleVfo() }
            }
            panelCell(title: "Mode",  value: vm.sharedMode.isEmpty ? "—" : vm.sharedMode, accent: .teal, isSelected: false) {
                showModePicker = true
            }
            panelCell(title: "Power", value: String(format: "%.0f%%", vm.sharedPower * 100), accent: .orange,
                      isSelected: selectedParam == .power) {
                selectedParam = .power
            }
            panelCell(title: "Step",  value: AppConstants.stepLabels[vm.selectedStepIndex], accent: .green,  isSelected: false) {
                showStepPicker = true
            }
            // Col5: MEM (v2.31 — APRSボタンから変更)
            panelCell(title: "MEM", value: "SET", accent: .secondary, isSelected: false) {
                showMemSheet = true
            }
            panelCell(title: "SQL",   value: String(format: "%.0f%%", vm.sharedSQL * 100), accent: .purple,
                      isSelected: selectedParam == .squelch) {
                selectedParam = .squelch
            }
            panelCell(title: "Width", value: formatWidth(vm.sharedWidth), accent: .blue,
                      isSelected: selectedParam == .width) {
                selectedParam = .width
            }
            // Col8: CWモード時BK-IN、それ以外はAPRS (v2.31)
            if isCwMode {
                panelCell(title: "BK-IN", value: vm.sharedBkIn ? "ON" : "OFF",
                          accent: vm.sharedBkIn ? .orange : .gray, isSelected: false) {
                    Task { await vm.setBkIn(on: !vm.sharedBkIn) }
                }
            } else {
                panelCell(title: "APRS", value: aprsLabel,
                          accent: aprsAccent, isSelected: false) {
                    Task {
                        if let err = await vm.toggleAprs() { vm.errorMessage = err }
                    }
                }
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
            .simultaneousGesture(LongPressGesture().onEnded { _ in showRepeaterSheet = true })
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
                    Text(formatFreqDigits(vm.displayFreq))
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

    // MARK: - APRS beacon card (v2.20)
    // Delegates all rendering to BeaconListCard struct to avoid @ViewBuilder complexity.

    @ViewBuilder
    private var beaconCompassCard: some View {
        if vm.aprsActive {
            BeaconListCard(stations: vm.aprsReceivedStations,
                           selfPos: vm.selfPosition,
                           aprsActive: vm.aprsActive,
                           useRigModem: vm.aprsUseRigModem,
                           onRefresh: { Task { await vm.refreshAprsReceived() } })
        }
    }

    // MARK: - 8-cell parameter panel 4×2 (v2.20: +VFO +APRS)

    private var aprsLabel: String {
        if vm.aprsUseRigModem { return vm.aprsAtPreset2 ? "AP12" : "AP96" }
        if vm.aprsActive { return "ON" }
        if !vm.aprsEnabled { return "OFF" }
        return "IDLE"
    }

    private var aprsAccent: Color {
        if vm.aprsActive { return .green }
        if !vm.aprsEnabled { return .gray }
        return .orange   // IDLE = enabled but not started
    }

    private var vfoLabel: String {
        vm.vfoCurrentSide.isEmpty ? "—" : vm.vfoCurrentSide
    }

    private var panelGrid: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 4),
                  spacing: 6) {
            panelCell(title: "VFO", value: vfoLabel, accent: .mint, isSelected: false) {
                Task { await vm.toggleVfo() }
            }
            panelCell(title: "Mode",  value: vm.sharedMode.isEmpty ? "—" : vm.sharedMode, accent: .teal, isSelected: false) {
                showModePicker = true
            }
            panelCell(title: "Power", value: String(format: "%.0f%%", vm.sharedPower * 100), accent: .orange,
                      isSelected: selectedParam == .power) {
                selectedParam = .power
            }
            panelCell(title: "Step",  value: AppConstants.stepLabels[vm.selectedStepIndex], accent: .green,  isSelected: false) {
                showStepPicker = true
            }
            // Row2 Col1: MEM (v2.31 — APRSボタンから変更)
            panelCell(title: "MEM", value: "SET", accent: .secondary, isSelected: false) {
                showMemSheet = true
            }
            panelCell(title: "SQL",   value: String(format: "%.0f%%", vm.sharedSQL * 100), accent: .purple,
                      isSelected: selectedParam == .squelch) {
                selectedParam = .squelch
            }
            panelCell(title: "Width", value: formatWidth(vm.sharedWidth), accent: .blue,
                      isSelected: selectedParam == .width) {
                selectedParam = .width
            }
            // Row2 Col4: CWモード時BK-IN、それ以外はAPRS (v2.31)
            if isCwMode {
                panelCell(title: "BK-IN", value: vm.sharedBkIn ? "ON" : "OFF",
                          accent: vm.sharedBkIn ? .orange : .gray, isSelected: false) {
                    Task { await vm.setBkIn(on: !vm.sharedBkIn) }
                }
            } else {
                panelCell(title: "APRS", value: aprsLabel,
                          accent: aprsAccent, isSelected: false) {
                    Task {
                        if let err = await vm.toggleAprs() { vm.errorMessage = err }
                    }
                }
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
        .simultaneousGesture(LongPressGesture().onEnded { _ in showRepeaterSheet = true })
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
            .simultaneousGesture(LongPressGesture().onEnded { _ in showRepeaterSheet = true })
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

    private func scheduleBeaconHide() {
        beaconHideTask?.cancel()
        beaconHideTask = Task {
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            if !Task.isCancelled {
                await MainActor.run { showBeaconOverlay = false }
            }
        }
    }

    private func dismissBeaconOverlay() {
        beaconHideTask?.cancel()
        showBeaconOverlay = false
        beaconOverlayDismissed = true
    }
}

// MARK: - APRS Compass view

struct AprsCompassView: View {
    let bearing: Double

    var body: some View {
        ZStack {
            // Outer ring
            Circle()
                .stroke(Color.green.opacity(0.25), lineWidth: 2)
            // 8-point ticks
            ForEach(0..<8) { i in
                Rectangle()
                    .fill(i == 0 ? Color.red.opacity(0.85) : Color.green.opacity(0.45))
                    .frame(width: i % 2 == 0 ? 2 : 1, height: i % 2 == 0 ? 8 : 5)
                    .offset(y: -28)
                    .rotationEffect(.degrees(Double(i) * 45))
            }
            // Cardinal labels
            Text("N").font(.system(size: 9, weight: .bold)).foregroundStyle(.red).offset(y: -18)
            Text("E").font(.system(size: 8)).foregroundStyle(.green.opacity(0.6)).offset(x: 18)
            Text("S").font(.system(size: 8)).foregroundStyle(.green.opacity(0.6)).offset(y: 18)
            Text("W").font(.system(size: 8)).foregroundStyle(.green.opacity(0.6)).offset(x: -18)
            // Needle
            ZStack {
                // Tail (gray)
                Capsule()
                    .fill(Color.gray.opacity(0.35))
                    .frame(width: 3, height: 14)
                    .offset(y: 8)
                // Tip (green)
                Capsule()
                    .fill(
                        LinearGradient(colors: [.green, .green.opacity(0.5)],
                                       startPoint: .top, endPoint: .bottom)
                    )
                    .frame(width: 3, height: 18)
                    .offset(y: -8)
                // Tip dot
                Circle()
                    .fill(Color.green)
                    .frame(width: 5, height: 5)
                    .offset(y: -16)
                // Center pivot
                Circle()
                    .fill(Color.white.opacity(0.9))
                    .frame(width: 5, height: 5)
            }
            .rotationEffect(.degrees(bearing))
        }
        .frame(width: 72, height: 72)
    }
}

// MARK: - Beacon list card (v2.20)

/// Compact single-row beacon status bar — fits in one screen line (~36pt).
struct BeaconListCard: View {
    let stations: [AprsStation]
    let selfPos: (lat: Double, lon: Double)?
    let aprsActive: Bool
    var useRigModem: Bool = false
    var onRefresh: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 6) {
            if stations.isEmpty {
                Image(systemName: "dot.radiowaves.left.and.right")
                    .font(.caption)
                    .foregroundStyle(.green.opacity(0.6))
                Text(useRigModem ? "Rig Modem ON" : "Listening...")
                    .font(.caption)
                    .foregroundStyle(.green.opacity(0.7))
            } else {
                let primary = sortedStations()[0]
                // Rotating arrow as compact compass
                if let p = selfPos, let lat = primary.lat, let lon = primary.lon {
                    let brg = MainViewModel.bearing(from: p, to: (lat, lon))
                    Image(systemName: "arrow.up")
                        .rotationEffect(.degrees(brg))
                        .font(.caption.bold())
                        .foregroundStyle(.green)
                }
                let emoji = AprsConstants.symbol(byCode: String((primary.symbol ?? "").prefix(1))).emoji
                if !emoji.isEmpty { Text(emoji).font(.caption) }
                Text(primary.call)
                    .font(.caption.bold().monospaced())
                    .foregroundStyle(.green)
                if let p = selfPos, let lat = primary.lat, let lon = primary.lon {
                    let brg = MainViewModel.bearing(from: p, to: (lat, lon))
                    let dst = MainViewModel.distanceKm(from: p, to: (lat, lon))
                    Text(String(format: "%.0f°", brg))
                        .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                    Text(dst < 1
                         ? String(format: "%.0fm", dst * 1000)
                         : String(format: "%.1fkm", dst))
                        .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                }
                if stations.count > 1 {
                    Text("+\(stations.count - 1)")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Text(ageString(primary.age_sec))
                    .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Button { onRefresh?() } label: {
                Image(systemName: "arrow.clockwise")
                    .font(.caption2)
                    .foregroundStyle(.green.opacity(0.6))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Color.green.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.green.opacity(0.25), lineWidth: 1))
    }

    private func sortedStations() -> [AprsStation] {
        guard let p = selfPos else { return stations }
        return stations.sorted {
            let a = ($0.lat != nil && $0.lon != nil)
                ? MainViewModel.distanceKm(from: p, to: ($0.lat!, $0.lon!)) : Double.infinity
            let b = ($1.lat != nil && $1.lon != nil)
                ? MainViewModel.distanceKm(from: p, to: ($1.lat!, $1.lon!)) : Double.infinity
            return a < b
        }
    }

    private func ageString(_ sec: Int) -> String {
        if sec < 60   { return "\(sec)s" }
        if sec < 3600 { return "\(sec / 60)m" }
        return "\(sec / 3600)h\((sec % 3600) / 60)m"
    }
}

// MARK: - Large Beacon Overlay

struct LargeBeaconOverlay: View {
    let stations: [AprsStation]
    let selfPos: (lat: Double, lon: Double)?
    var onRefresh: (() -> Void)? = nil
    var onDismiss: (() -> Void)? = nil

    private var primary: AprsStation? { stations.sorted { ($0.age_sec) < ($1.age_sec) }.first }

    var body: some View {
        if let station = primary {
            VStack(spacing: 10) {
                HStack {
                    Text("APRS")
                        .font(.caption.bold())
                        .foregroundStyle(.green.opacity(0.7))
                    Spacer()
                    if stations.count > 1 {
                        Text("+\(stations.count - 1) more")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Button { onDismiss?() } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                    }.buttonStyle(.plain)
                }

                HStack(spacing: 16) {
                    if let p = selfPos, let lat = station.lat, let lon = station.lon {
                        let brg = MainViewModel.bearing(from: p, to: (lat, lon))
                        AprsCompassView(bearing: brg)
                            .frame(width: 90, height: 90)
                        VStack(alignment: .leading, spacing: 6) {
                            let sym = AprsConstants.symbol(byCode: String((station.symbol ?? "").prefix(1)))
                            HStack(spacing: 4) {
                                if !sym.emoji.isEmpty { Text(sym.emoji).font(.title2) }
                                Text(station.call)
                                    .font(.title2.bold().monospaced())
                                    .foregroundStyle(.green)
                            }
                            let dst = MainViewModel.distanceKm(from: p, to: (lat, lon))
                            let dstStr = dst < 1 ? String(format: "%.0f m", dst*1000) : String(format: "%.1f km", dst)
                            HStack(spacing: 8) {
                                Label(String(format: "%.0f°", brg), systemImage: "location.north")
                                    .font(.headline.monospacedDigit())
                                    .foregroundStyle(.primary)
                                Text(dstStr)
                                    .font(.headline.monospacedDigit())
                                    .foregroundStyle(.primary)
                            }
                            if let comment = station.comment, !comment.isEmpty {
                                Text(comment)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                            }
                            Text(ageLabel(station.age_sec))
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 0)
                    } else {
                        let sym = AprsConstants.symbol(byCode: String((station.symbol ?? "").prefix(1)))
                        if !sym.emoji.isEmpty { Text(sym.emoji).font(.largeTitle) }
                        VStack(alignment: .leading, spacing: 6) {
                            Text(station.call)
                                .font(.title2.bold().monospaced())
                                .foregroundStyle(.green)
                            if let comment = station.comment, !comment.isEmpty {
                                Text(comment)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                }
            }
            .padding(14)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.green.opacity(0.4), lineWidth: 1))
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
            .onTapGesture { onDismiss?() }
        }
    }

    private func ageLabel(_ sec: Int) -> String {
        if sec < 60   { return "\(sec)s ago" }
        if sec < 3600 { return "\(sec / 60)m ago" }
        return "\(sec / 3600)h\((sec % 3600) / 60)m ago"
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

// MARK: - Memory Panel (v2.31)

struct MemoryPanelView: View {
    @Bindable var vm: MainViewModel
    @Environment(\.dismiss) private var dismiss
    @AppStorage("memLastTab") private var selectedTab: Int = 0
    @State private var editContext: MemoryEditContext? = nil
    @State private var showResetConfirm = false

    /// シートに渡す編集コンテキスト — item: バインディングで確実に渡す
    private struct MemoryEditContext: Identifiable {
        let id = UUID()
        let entry: MemoryEntry?
        let index: Int?
        let isPreset: Bool
    }

    private struct IndexedPreset: Identifiable {
        let id: Int          // vm.customPresets 内のオリジナルインデックス
        let entry: MemoryEntry
    }

    private var presetsByBand: [(band: String, entries: [IndexedPreset])] {
        var result: [(band: String, entries: [IndexedPreset])] = []
        var seen: [String: Int] = [:]
        for (idx, entry) in vm.customPresets.enumerated() {
            let band = entry.name.components(separatedBy: " ").first ?? entry.name
            let item = IndexedPreset(id: idx, entry: entry)
            if let groupIdx = seen[band] { result[groupIdx].entries.append(item) }
            else { seen[band] = result.count; result.append((band: band, entries: [item])) }
        }
        return result
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Tab", selection: $selectedTab) {
                    Text("Preset").tag(0)
                    Text("User").tag(1)
                    Text("POTA").tag(2)
                    Text("SOTA").tag(3)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                Divider()
                Group {
                    switch selectedTab {
                    case 1:  userTab
                    case 2:  MemoryPotaView(vm: vm)
                    case 3:  MemorySotaView(vm: vm)
                    default: presetTab
                    }
                }
            }
            .navigationTitle("SET")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                if selectedTab == 0 {
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            Button {
                                editContext = MemoryEditContext(entry: nil, index: nil, isPreset: true)
                            } label: { Label("Add Preset", systemImage: "plus") }
                            Button(role: .destructive) { showResetConfirm = true } label: {
                                Label("Reset All", systemImage: "arrow.counterclockwise")
                            }
                        } label: { Image(systemName: "ellipsis.circle") }
                    }
                }
                if selectedTab == 1 {
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            editContext = MemoryEditContext(entry: nil, index: nil, isPreset: false)
                        } label: { Label("Add", systemImage: "plus") }
                    }
                }
            }
            .sheet(item: $editContext) { ctx in
                MemoryEditView(vm: vm, entry: ctx.entry, index: ctx.index,
                               defaultFreqHz: vm.sharedFreq, defaultMode: vm.sharedMode,
                               isPreset: ctx.isPreset)
            }
            .confirmationDialog("Reset Presets", isPresented: $showResetConfirm, titleVisibility: .visible) {
                Button("Reset All", role: .destructive) { vm.resetCustomPresets() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("すべてのプリセットをデフォルトに戻します")
            }
        }
    }

    @ViewBuilder private var presetTab: some View {
        List {
            ForEach(presetsByBand, id: \.band) { group in
                Section(group.band) {
                    ForEach(group.entries) { item in
                        MemoryRowView(entry: item.entry, showLockIcon: false)
                            .contentShape(Rectangle())
                            .onTapGesture { Task { await vm.recallMemory(item.entry) }; dismiss() }
                            .swipeActions(edge: .trailing) {
                                Button("Delete", role: .destructive) {
                                    var list = vm.customPresets
                                    list.remove(at: item.id)
                                    vm.saveCustomPresets(list)
                                }
                                Button("Edit") {
                                    editContext = MemoryEditContext(entry: item.entry, index: item.id, isPreset: true)
                                }
                                .tint(.blue)
                            }
                    }
                }
            }
        }
        .listStyle(.plain)
    }

    @ViewBuilder private var userTab: some View {
        List {
            ForEach(Array(vm.userMemories.enumerated()), id: \.offset) { idx, entry in
                MemoryRowView(entry: entry)
                    .contentShape(Rectangle())
                    .onTapGesture { Task { await vm.recallMemory(entry) }; dismiss() }
                    .swipeActions(edge: .trailing) {
                        Button("Delete", role: .destructive) {
                            var list = vm.userMemories; list.remove(at: idx); vm.saveUserMemories(list)
                        }
                        Button("Edit") {
                            editContext = MemoryEditContext(entry: entry, index: idx, isPreset: false)
                        }
                        .tint(.blue)
                    }
            }
        }
        .listStyle(.plain)
    }
}

struct MemoryRowView: View {
    let entry: MemoryEntry
    var showLockIcon: Bool = true

    private var freqLabel: String {
        let mhz = Double(entry.freqHz) / 1_000_000.0
        if mhz >= 1.0 {
            return String(format: "%.3f MHz", mhz)
        }
        return String(format: "%.0f Hz", Double(entry.freqHz))
    }

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name)
                    .font(.callout.bold())
                HStack(spacing: 6) {
                    Text(freqLabel)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                    if !entry.mode.isEmpty {
                        Text(entry.mode)
                            .font(.caption.bold())
                            .foregroundStyle(.teal)
                    }
                    if entry.stepIndex >= 0 {
                        Text(AppConstants.stepLabels[entry.stepIndex])
                            .font(.caption2)
                            .foregroundStyle(.green)
                    }
                }
            }
            Spacer()
            if showLockIcon && entry.isPreset {
                Image(systemName: "lock.fill")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

struct MemoryEditView: View {
    @Bindable var vm: MainViewModel
    let entry: MemoryEntry?
    let index: Int?
    let defaultFreqHz: Int64
    let defaultMode: String
    var isPreset: Bool = false
    @Environment(\.dismiss) private var dismiss

    @State private var name: String = ""
    @State private var freqText: String = ""
    @State private var selectedMode: String = ""
    @State private var stepIdx: Int = -1

    private static let modeList: [String] = ["LSB", "USB", "CW", "CWR", "AM", "FM", "C4FM", "DV", "RTTY", "PSK"]

    private var isValid: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty &&
        Int64(freqText.trimmingCharacters(in: .whitespaces)) != nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Memory name", text: $name)
                }
                Section("Frequency (Hz)") {
                    TextField("e.g. 14225000", text: $freqText)
                        .keyboardType(.numberPad)
                    if let hz = Int64(freqText.trimmingCharacters(in: .whitespaces)) {
                        Text(String(format: "= %.4f MHz", Double(hz) / 1_000_000.0))
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                Section("Mode") {
                    Picker("Mode", selection: $selectedMode) {
                        Text("Stay current").tag("")
                        ForEach(Self.modeList, id: \.self) { m in
                            Text(m).tag(m)
                        }
                    }
                    .pickerStyle(.menu)
                }
                Section("Step") {
                    Picker("Step", selection: $stepIdx) {
                        Text("Mode default").tag(-1)
                        ForEach(AppConstants.stepHz.indices, id: \.self) { i in
                            Text(AppConstants.stepLabels[i]).tag(i)
                        }
                    }
                }
            }
            .navigationTitle(entry == nil ? "Add Memory" : "Edit Memory")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(!isValid)
                }
            }
            .onAppear {
                if let e = entry {
                    name = e.name
                    freqText = "\(e.freqHz)"
                    selectedMode = e.mode
                    stepIdx = e.stepIndex
                } else {
                    freqText = "\(defaultFreqHz)"
                    selectedMode = Self.modeList.contains(defaultMode.uppercased())
                        ? defaultMode.uppercased() : ""
                }
            }
        }
    }

    private func save() {
        guard let hz = Int64(freqText.trimmingCharacters(in: .whitespaces)) else { return }
        let newEntry = MemoryEntry(
            name: name.trimmingCharacters(in: .whitespaces),
            freqHz: hz,
            mode: selectedMode,
            stepIndex: stepIdx
        )
        if isPreset {
            var list = vm.customPresets
            if let idx = index { list[idx] = newEntry } else { list.append(newEntry) }
            vm.saveCustomPresets(list)
        } else {
            var list = vm.userMemories
            if let idx = index { list[idx] = newEntry } else { list.append(newEntry) }
            vm.saveUserMemories(list)
        }
        dismiss()
    }
}

// MARK: - Spot helpers

private let spotBandOrder = ["160m","80m","60m","40m","30m","20m","17m","15m","12m","10m","6m","2m","70cm","Other"]

/// POTA/SOTA APIのモード文字列を無線機互換モードに変換する
/// SSB → 周波数に応じてUSB/LSB、FT8/FT4等 → USB
private func normalizeSpotMode(_ mode: String, freqHz: Int64) -> String {
    switch mode.uppercased() {
    case "SSB":
        return freqHz >= 10_000_000 ? "USB" : "LSB"
    case "FT8", "FT4", "JS8", "WSPR", "PSK31", "PSK63", "JT65", "JT9":
        return "USB"
    case "RTTY":
        return "RTTY"
    default:
        return mode.uppercased()
    }
}

private func freqToBand(_ mhz: Double) -> String {
    switch mhz {
    case 1.8..<2.0:        return "160m"
    case 3.5..<4.0:        return "80m"
    case 5.3..<5.406:      return "60m"
    case 7.0..<7.3:        return "40m"
    case 10.1..<10.15:     return "30m"
    case 14.0..<14.35:     return "20m"
    case 18.068..<18.168:  return "17m"
    case 21.0..<21.45:     return "15m"
    case 24.89..<24.99:    return "12m"
    case 28.0..<29.7:      return "10m"
    case 50.0..<54.0:      return "6m"
    case 144.0..<148.0:    return "2m"
    case 430.0..<440.0:    return "70cm"
    default:                return "Other"
    }
}

private func formatRelTime(_ iso: String) -> String {
    guard !iso.isEmpty else { return "" }
    let fmts = ["yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS"]
    let df = DateFormatter(); df.timeZone = TimeZone(identifier: "UTC")
    let date: Date? = fmts.lazy.compactMap { f -> Date? in df.dateFormat = f; return df.date(from: iso) }.first
    guard let d = date else { return iso }
    let sec = max(0, Int(Date().timeIntervalSince(d)))
    if sec < 60   { return "\(sec)s ago" }
    if sec < 3600 { return "\(sec/60)m ago" }
    return "\(sec/3600)h \((sec % 3600)/60)m ago"
}

private func spotFilterPicker(
    _ label: String, options: [String], selection: Binding<String>
) -> some View {
    Picker(label, selection: selection) {
        Text("\(label): ALL").tag("")
        ForEach(options, id: \.self) { Text($0).tag($0) }
    }
    .pickerStyle(.menu)
    .frame(maxWidth: .infinity)
}

private func spotSearchField(text: Binding<String>) -> some View {
    HStack {
        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
        TextField("Search…", text: text)
            .autocorrectionDisabled().textInputAutocapitalization(.never)
        if !text.wrappedValue.isEmpty {
            Button { text.wrappedValue = "" } label: {
                Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
    }
    .padding(8)
    .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
    .padding(.horizontal, 8)
    .padding(.bottom, 4)
}

// MARK: - POTA Tab

struct MemoryPotaView: View {
    @Bindable var vm: MainViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var spots: [PotaSpot] = []
    @State private var isLoading = false
    @State private var errorMsg: String?

    @AppStorage("potaModeFilter")    private var modeFilter:    String = ""
    @AppStorage("potaBandFilter")    private var bandFilter:    String = ""
    @AppStorage("potaProgFilter")    private var programFilter: String = ""
    @AppStorage("potaSortOrder")     private var sortOrder:     Int    = 0
    @State private var textFilter = ""

    private var availableModes: [String] {
        Array(Set(spots.map { $0.mode.uppercased() }.filter { !$0.isEmpty })).sorted()
    }
    private var availableBands: [String] {
        let all = Set(spots.map { freqToBand($0.freqMhz) })
        return spotBandOrder.filter { all.contains($0) }
    }
    private var availablePrograms: [String] {
        Array(Set(spots.compactMap { s -> String? in
            let p = s.program; return p.isEmpty ? nil : p
        })).sorted()
    }
    private var filteredSpots: [PotaSpot] {
        let q = textFilter.lowercased().trimmingCharacters(in: .whitespaces)
        var list = spots.filter { s in
            (modeFilter.isEmpty    || s.mode.uppercased() == modeFilter) &&
            (bandFilter.isEmpty    || freqToBand(s.freqMhz) == bandFilter) &&
            (programFilter.isEmpty || s.program == programFilter) &&
            (q.isEmpty || s.activator.lowercased().contains(q) || s.name.lowercased().contains(q) ||
             s.reference.lowercased().contains(q) || s.comments.lowercased().contains(q) ||
             s.spotter.lowercased().contains(q))
        }
        switch sortOrder {
        case 1: list.sort { $0.activator < $1.activator }
        case 2: list.sort { $0.name < $1.name }
        default: list.sort { $0.freqMhz < $1.freqMhz }
        }
        return list
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                spotFilterPicker("Mode", options: availableModes, selection: $modeFilter)
                spotFilterPicker("Band", options: availableBands, selection: $bandFilter)
                Button { spots = []; Task { await fetch() } } label: { Image(systemName: "arrow.clockwise") }
                    .buttonStyle(.bordered)
            }
            .padding(.horizontal, 8).padding(.top, 4)
            HStack(spacing: 4) {
                spotFilterPicker("Prog", options: availablePrograms, selection: $programFilter)
                Picker("Sort", selection: $sortOrder) {
                    Text("Freq").tag(0); Text("Call").tag(1); Text("Park").tag(2)
                }
                .pickerStyle(.menu).frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 8).padding(.bottom, 4)
            spotSearchField(text: $textFilter)
            Divider()
            if isLoading {
                ProgressView("Loading POTA spots…").frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let err = errorMsg {
                VStack(spacing: 8) {
                    Text(err).foregroundStyle(.red).multilineTextAlignment(.center).padding()
                    Button("Retry") { spots = []; Task { await fetch() } }.buttonStyle(.bordered)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if filteredSpots.isEmpty {
                Text(spots.isEmpty ? "Press ↺ to load" : "No spots match the filter")
                    .foregroundStyle(.secondary).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(filteredSpots) { spot in
                    PotaSpotRow(spot: spot).contentShape(Rectangle())
                        .onTapGesture {
                            Task {
                                await vm.setFreq(spot.freqHz)
                                let m = normalizeSpotMode(spot.mode, freqHz: spot.freqHz)
                                if !m.isEmpty {
                                    let w = vm.modeDefaultWidth(m)
                                    await vm.setMode(m, width: w > 0 ? w : vm.sharedWidth)
                                }
                                dismiss()
                            }
                        }
                }
                .listStyle(.plain)
            }
        }
        .onAppear { if spots.isEmpty && !isLoading { Task { await fetch() } } }
    }

    private func fetch() async {
        isLoading = true; errorMsg = nil
        do {
            guard let url = URL(string: "https://api.pota.app/spot/activator") else { return }
            let (data, _) = try await URLSession.shared.data(from: url)
            spots = try JSONDecoder().decode([PotaSpot].self, from: data).filter { !$0.invalid }
            // Clear stale saved filters that no longer exist in fresh data
            if !modeFilter.isEmpty    && !availableModes.contains(modeFilter)       { modeFilter    = "" }
            if !bandFilter.isEmpty    && !availableBands.contains(bandFilter)       { bandFilter    = "" }
            if !programFilter.isEmpty && !availablePrograms.contains(programFilter) { programFilter = "" }
        } catch {
            errorMsg = "Fetch failed: \(error.localizedDescription)\nPress ↺ to retry"
        }
        isLoading = false
    }
}

struct PotaSpotRow: View {
    let spot: PotaSpot
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                Text(spot.activator).bold()
                Text(String(format: "%.3f MHz", spot.freqMhz)).font(.callout.monospacedDigit())
                Text(spot.mode).foregroundStyle(.teal)
            }
            .font(.callout)
            (Text(spot.name.isEmpty ? "" : spot.name + " (") + Text(spot.reference).bold() + Text(")"))
                .font(.caption).foregroundStyle(.cyan)
            HStack(spacing: 4) {
                Text(formatRelTime(spot.spotTime)).foregroundStyle(.secondary)
                let c = spot.comments.trimmingCharacters(in: .whitespaces)
                if !c.isEmpty { Text("💬 \(c)").foregroundStyle(.secondary) }
            }
            .font(.caption2)
        }
        .padding(.vertical, 2)
    }
}

// MARK: - SOTA Tab

struct MemorySotaView: View {
    @Bindable var vm: MainViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var spots: [SotaSpot] = []
    @State private var isLoading = false
    @State private var errorMsg: String?

    @AppStorage("sotaModeFilter") private var modeFilter: String = ""
    @AppStorage("sotaBandFilter") private var bandFilter: String = ""
    @AppStorage("sotaAreaFilter") private var areaFilter: String = ""
    @AppStorage("sotaSortOrder")  private var sortOrder:  Int    = 0
    @State private var textFilter = ""

    private var availableModes: [String] {
        Array(Set(spots.compactMap { s -> String? in
            let m = (s.mode ?? "").uppercased(); return m.isEmpty ? nil : m
        })).sorted()
    }
    private var availableBands: [String] {
        let all = Set(spots.map { freqToBand($0.freqMhz) })
        return spotBandOrder.filter { all.contains($0) }
    }
    private var availableAreas: [String] {
        Array(Set(spots.compactMap { s -> String? in
            let a = s.associationCode ?? ""; return a.isEmpty ? nil : a
        })).sorted()
    }
    private var filteredSpots: [SotaSpot] {
        let q = textFilter.lowercased().trimmingCharacters(in: .whitespaces)
        var list = spots.filter { s in
            (modeFilter.isEmpty || (s.mode ?? "").uppercased() == modeFilter) &&
            (bandFilter.isEmpty || freqToBand(s.freqMhz) == bandFilter) &&
            (areaFilter.isEmpty || (s.associationCode ?? "") == areaFilter) &&
            (q.isEmpty || (s.activatorCallsign ?? "").lowercased().contains(q) ||
             (s.summitDetails ?? "").lowercased().contains(q) ||
             (s.summitCode ?? "").lowercased().contains(q) ||
             (s.associationCode ?? "").lowercased().contains(q) ||
             (s.comments ?? "").lowercased().contains(q))
        }
        switch sortOrder {
        case 1: list.sort { ($0.activatorCallsign ?? "") < ($1.activatorCallsign ?? "") }
        case 2: list.sort { ($0.summitDetails ?? "") < ($1.summitDetails ?? "") }
        default: list.sort { $0.freqMhz < $1.freqMhz }
        }
        return list
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                spotFilterPicker("Mode", options: availableModes, selection: $modeFilter)
                spotFilterPicker("Band", options: availableBands, selection: $bandFilter)
                Button { spots = []; Task { await fetch() } } label: { Image(systemName: "arrow.clockwise") }
                    .buttonStyle(.bordered)
            }
            .padding(.horizontal, 8).padding(.top, 4)
            HStack(spacing: 4) {
                spotFilterPicker("Area", options: availableAreas, selection: $areaFilter)
                Picker("Sort", selection: $sortOrder) {
                    Text("Freq").tag(0); Text("Call").tag(1); Text("Summit").tag(2)
                }
                .pickerStyle(.menu).frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 8).padding(.bottom, 4)
            spotSearchField(text: $textFilter)
            Divider()
            if isLoading {
                ProgressView("Loading SOTA spots…").frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let err = errorMsg {
                VStack(spacing: 8) {
                    Text(err).foregroundStyle(.red).multilineTextAlignment(.center).padding()
                    Button("Retry") { spots = []; Task { await fetch() } }.buttonStyle(.bordered)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if filteredSpots.isEmpty {
                Text(spots.isEmpty ? "Press ↺ to load" : "No spots match the filter")
                    .foregroundStyle(.secondary).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(filteredSpots) { spot in
                    SotaSpotRow(spot: spot).contentShape(Rectangle())
                        .onTapGesture {
                            guard spot.freqHz > 0 else { return }
                            Task {
                                await vm.setFreq(spot.freqHz)
                                let raw = (spot.mode ?? "")
                                let m = normalizeSpotMode(raw, freqHz: spot.freqHz)
                                if !m.isEmpty {
                                    let w = vm.modeDefaultWidth(m)
                                    await vm.setMode(m, width: w > 0 ? w : vm.sharedWidth)
                                }
                                dismiss()
                            }
                        }
                }
                .listStyle(.plain)
            }
        }
        .onAppear { if spots.isEmpty && !isLoading { Task { await fetch() } } }
    }

    private func fetch() async {
        isLoading = true; errorMsg = nil
        do {
            guard let url = URL(string: "https://api2.sota.org.uk/api/spots/60/-1") else { return }
            var req = URLRequest(url: url)
            req.setValue("application/json", forHTTPHeaderField: "Accept")
            let (data, _) = try await URLSession.shared.data(for: req)
            spots = try JSONDecoder().decode([SotaSpot].self, from: data)
            // Clear stale saved filters that no longer exist in fresh data
            if !modeFilter.isEmpty && !availableModes.contains(modeFilter) { modeFilter = "" }
            if !bandFilter.isEmpty && !availableBands.contains(bandFilter) { bandFilter = "" }
            if !areaFilter.isEmpty && !availableAreas.contains(areaFilter) { areaFilter = "" }
        } catch {
            errorMsg = "Fetch failed: \(error.localizedDescription)\nPress ↺ to retry"
        }
        isLoading = false
    }
}

struct SotaSpotRow: View {
    let spot: SotaSpot
    private var codeStr: String { spot.codeStr }
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                Text(spot.activatorCallsign ?? "").bold()
                Text(String(format: "%.3f MHz", spot.freqMhz)).font(.callout.monospacedDigit())
                Text(spot.mode ?? "").foregroundStyle(.teal)
            }
            .font(.callout)
            let detail = spot.summitDetails ?? ""
            Group {
                Text(detail + (codeStr.isEmpty ? "" : " ("))
                + Text(codeStr).bold()
                + Text(codeStr.isEmpty ? "" : ")")
            }
            .font(.caption).foregroundStyle(.cyan)
            HStack(spacing: 4) {
                Text(formatRelTime(spot.timeStamp ?? "")).foregroundStyle(.secondary)
                let c = (spot.comments ?? "").trimmingCharacters(in: .whitespaces)
                if !c.isEmpty { Text("💬 \(c)").foregroundStyle(.secondary) }
            }
            .font(.caption2)
        }
        .padding(.vertical, 2)
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

// MARK: - Repeater Settings Sheet

struct RepeaterSettingsView: View {
    @Bindable var vm: MainViewModel
    @Environment(\.dismiss) private var dismiss

    private let offsetDirs   = ["None", "+", "-"]
    private let offsetPresets: [Int64] = [0, 100_000, 600_000, 1_000_000, 1_600_000, 5_000_000, 7_600_000]
    private let offsetLabels  = ["Custom", "100 kHz", "600 kHz", "1 MHz", "1.6 MHz", "5 MHz", "7.6 MHz"]
    private let toneModes     = ["", "TONE", "TSQL", "DTCS"]
    private let toneModeLabels = ["None", "TONE", "TSQL", "DTCS"]

    @State private var dirIdx: Int = 0
    @State private var presetIdx: Int = 0
    @State private var offsetKhzText: String = ""
    @State private var toneModeIdx: Int = 0
    @State private var toneHzText: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Offset Direction") {
                    Picker("Direction", selection: $dirIdx) {
                        ForEach(offsetDirs.indices, id: \.self) { i in
                            Text(offsetDirs[i]).tag(i)
                        }
                    }
                    .pickerStyle(.segmented)
                }
                Section("Offset") {
                    Picker("Preset", selection: $presetIdx) {
                        ForEach(offsetLabels.indices, id: \.self) { i in
                            Text(offsetLabels[i]).tag(i)
                        }
                    }
                    .onChange(of: presetIdx) { _, idx in
                        if idx > 0 { offsetKhzText = String(offsetPresets[idx] / 1000) }
                    }
                    TextField("Offset (kHz)", text: $offsetKhzText)
                        .keyboardType(.numberPad)
                }
                Section("Tone") {
                    Picker("Tone Mode", selection: $toneModeIdx) {
                        ForEach(toneModeLabels.indices, id: \.self) { i in
                            Text(toneModeLabels[i]).tag(i)
                        }
                    }
                    .pickerStyle(.segmented)
                    if toneModeIdx > 0 {
                        TextField("Tone (Hz)", text: $toneHzText)
                            .keyboardType(.decimalPad)
                    }
                }
            }
            .navigationTitle("Repeater Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .destructiveAction) {
                    Button("Clear", role: .destructive) {
                        vm.clearRepeater(); dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("OK") {
                        let dir   = dirIdx == 1 ? "+" : dirIdx == 2 ? "-" : ""
                        let offHz = (Int64(offsetKhzText) ?? 0) * 1000
                        let tMode = toneModes[toneModeIdx]
                        let tHz   = Double(toneHzText) ?? 0.0
                        vm.setRepeater(toneMode: tMode, toneHz: tHz, offsetDir: dir, offsetHz: offHz)
                        dismiss()
                    }
                }
            }
        }
        .onAppear {
            dirIdx        = vm.repeaterOffsetDir == "+" ? 1 : vm.repeaterOffsetDir == "-" ? 2 : 0
            let curOff    = vm.repeaterOffsetHz
            presetIdx     = offsetPresets.firstIndex(of: curOff).flatMap { $0 > 0 ? $0 : nil } ?? 0
            offsetKhzText = curOff > 0 ? String(curOff / 1000) : ""
            toneModeIdx   = toneModes.firstIndex(of: vm.repeaterToneMode) ?? 0
            toneHzText    = vm.repeaterToneHz > 0 ? String(vm.repeaterToneHz) : ""
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
