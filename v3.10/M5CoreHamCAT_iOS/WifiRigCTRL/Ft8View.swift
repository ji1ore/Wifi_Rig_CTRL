import SwiftUI
import Foundation

// MARK: - Data model

struct Ft8Msg: Identifiable {
    let id = UUID()
    let freq: Int
    let snr: Int
    let dt: Double
    let msg: String
    let utcSec: Int
    let period: Int
    var isTx: Bool = false
    var epochMs: Double
    var seqNo: Int = 0   // arrival-order counter; sorted by seqNo (mirrors Android)

    static func utcSecToEpochMs(_ utcSec: Int) -> Double {
        let nowMs = Date().timeIntervalSince1970 * 1000
        let nowUtcSec = Int(nowMs / 1000) % 60
        let secAgo = ((nowUtcSec - utcSec) + 60) % 60
        return nowMs - Double(secAgo) * 1000
    }
}

// Shared UTC formatter — creating DateFormatter per-row is O(n) expensive
private let utcTimeFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss"
    f.timeZone = TimeZone(identifier: "UTC")
    return f
}()

enum Ft8TxState { case off, auto, cqAuto }

struct QsoStartData {
    var candidates: [String]
    var myCall: String
    var myGrid: String
    var defaultTxMode: String
    var snrFromMsg: String
    var dxFreqHz: Int
}

private struct WfMarker {
    var rowsFromBottom: Int
    let label: String
}

// MARK: - Waterfall Canvas

private struct WaterfallCanvas: View {
    let rows: [[UInt8]]
    let markers: [WfMarker]
    let txFreqHz: Int
    let maxAudioHz: Int
    let brightness: Float
    var onFreqTap: ((Int) -> Void)?

    private let rowH: CGFloat = 1.0

    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            let n = rows.count
            guard n > 0 else { return }

            // Draw the waterfall bitmap bottom-aligned in a fixed-height viewport.
            // The previous implementation grew a ScrollView and auto-scrolled to the
            // bottom, but the scroll only fired while `rows.count` changed — once the
            // buffer filled at 80 rows the count stopped changing, so auto-scroll
            // stopped and the feed appeared to freeze. A fixed Canvas redraws in place
            // on every new row, so the waterfall keeps scrolling after it fills.
            // Rows taller than the viewport are clipped at the top (oldest rows).
            if let img = makeWaterfallImage() {
                let fullH = CGFloat(n) * rowH
                ctx.draw(Image(decorative: img, scale: 1).interpolation(.none),
                         in: CGRect(x: 0, y: h - fullH, width: w, height: fullH))
            }

            // TX freq marker
            if maxAudioHz > 0 {
                let x = CGFloat(txFreqHz) / CGFloat(maxAudioHz) * w
                ctx.stroke(
                    Path { p in p.move(to: .init(x: x, y: 0)); p.addLine(to: .init(x: x, y: h)) },
                    with: .color(.orange), lineWidth: 2
                )
            }

            // Period markers
            for m in markers {
                let y = h - CGFloat(m.rowsFromBottom) * rowH
                guard y >= 0 && y <= h else { continue }
                ctx.stroke(
                    Path { p in p.move(to: .init(x: 0, y: y)); p.addLine(to: .init(x: w, y: y)) },
                    with: .color(.white.opacity(0.5)), lineWidth: 0.5
                )
                ctx.draw(
                    Text(m.label).font(.system(size: 7, design: .monospaced)).foregroundColor(.white.opacity(0.85)),
                    at: .init(x: 2, y: y + 2), anchor: .topLeading
                )
            }
        }
        .background(Color.black)
        .overlay(GeometryReader { geo in
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture { loc in
                    guard maxAudioHz > 0 else { return }
                    let hz = Int(loc.x / geo.size.width * CGFloat(maxAudioHz))
                    onFreqTap?(max(100, min(hz, 2900)))
                }
        })
    }

    /// Maps a normalized magnitude to raw RGB bytes for the pixel buffer.
    private func spectrumRGB(_ v: Float) -> (UInt8, UInt8, UInt8) {
        let r: Float, g: Float, b: Float
        switch v {
        case ..<0.2: r = 0; g = 0; b = v / 0.2
        case ..<0.4: let t = (v - 0.2) / 0.2; r = 0; g = t; b = 1
        case ..<0.6: let t = (v - 0.4) / 0.2; r = 0; g = 1; b = 1 - t
        case ..<0.8: let t = (v - 0.6) / 0.2; r = t; g = 1; b = 0
        default:     let t = (v - 0.8) / 0.2; r = 1; g = 1 - t; b = 0
        }
        return (UInt8(max(0, min(1, r)) * 255),
                UInt8(max(0, min(1, g)) * 255),
                UInt8(max(0, min(1, b)) * 255))
    }

    /// Builds the waterfall as a single CGImage (one pixel per bin/row). Row 0 is the
    /// top (oldest) row, matching the previous per-rectangle drawing order.
    private func makeWaterfallImage() -> CGImage? {
        let h = rows.count
        guard h > 0 else { return nil }
        let w = rows.reduce(0) { max($0, $1.count) }
        guard w > 0 else { return nil }

        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        for ri in 0..<h {
            let row = rows[ri]
            let nBins = row.count
            let base = ri * w * 4
            for bi in 0..<w {
                let raw = bi < nBins ? row[bi] : 0
                let v = min(Float(raw) / 255.0 * brightness, 1.0)
                let (r, g, b) = spectrumRGB(v)
                let off = base + bi * 4
                pixels[off] = r
                pixels[off + 1] = g
                pixels[off + 2] = b
                pixels[off + 3] = 255
            }
        }

        let cs = CGColorSpaceCreateDeviceRGB()
        let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
        // Build the image from a CGDataProvider that owns a copy of the pixels. Making
        // a CGImage from a CGContext over a caller-owned buffer can leave the image
        // referencing freed memory, which crashes when it is later drawn.
        guard let provider = CGDataProvider(data: Data(pixels) as CFData) else { return nil }
        return CGImage(width: w, height: h,
                       bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4,
                       space: cs, bitmapInfo: info,
                       provider: provider, decode: nil,
                       shouldInterpolate: false, intent: .defaultIntent)
    }
}

// MARK: - Waterfall store & isolated view

/// Holds the high-frequency waterfall data. Kept in a dedicated observable object so
/// that spectrum updates (several per second) only invalidate `WaterfallView` and not
/// the whole `Ft8View` body — recomputing the message list on every frame progressively
/// saturated the main thread and hung the UI as messages accumulated.
@Observable
private final class WaterfallStore {
    var rows: [[UInt8]] = []
    var markers: [WfMarker] = []
}

private struct WaterfallView: View {
    let store: WaterfallStore
    let txFreqHz: Int
    let maxAudioHz: Int
    let brightness: Float
    var onFreqTap: ((Int) -> Void)?

    var body: some View {
        WaterfallCanvas(rows: store.rows, markers: store.markers,
                        txFreqHz: txFreqHz, maxAudioHz: maxAudioHz,
                        brightness: brightness, onFreqTap: onFreqTap)
    }
}

// MARK: - Main view

struct Ft8View: View {
    @Bindable var vm: MainViewModel

    init(vm: MainViewModel) { self.vm = vm }

    // CI-V local engine (non-nil when in CI-V WiFi mode)
    @State private var localEngine: Ft8LocalEngine? = nil
    // Mirrors Android: guards cq_auto_restart from arriving after user pressed stop
    @State private var cqAutoUserEnabled = false

    // SSE tasks (Pi mode only)
    @State private var sseTask: Task<Void, Never>?
    @State private var spectrumTask: Task<Void, Never>?
    // CI-V local engine tasks
    @State private var civSseTask: Task<Void, Never>?
    @State private var civSpecTask: Task<Void, Never>?

    // Messages
    @State private var rxMsgs: [Ft8Msg] = []
    @State private var txMsgs: [Ft8Msg] = []
    @State private var pendingTxSeqNo: Int = -1  // seqNo of the pending (not-yet-sent) TX row
    @State private var nextSeqNo: Int = 0         // monotonically increasing; mirrors Android msgSeqNo
    @State private var curDecodePeriod: Int = -1  // period_n of the current decode batch
    @State private var curPeriodSeqNo: Int = 0    // seqNo shared by all msgs in current period
    @State private var lastMsgId: UUID? = nil

    // POTA/SOTA spot cache — local @State so view re-renders when spots arrive
    @State private var potaMap: [String: [PotaSpot]] = [:]
    @State private var sotaMap: [String: [SotaSpot]] = [:]

    // Waterfall
    @State private var wf = WaterfallStore()
    @State private var wfBrightness: Float = 1.0
    @State private var markedUtcSecs: Set<Int> = []

    // TX state
    @State private var txState: Ft8TxState = .off
    @State private var txMsg: String = ""
    @State private var cqAutoOrigMsg: String = ""   // original CQ message saved when CQAUTO starts
    @State private var txFreqHz: Int = 1500
    @State private var autoTxMode: String = "even"
    @State private var filterEnabled: Bool = false
    @State private var filterDxFreq: Int = 0
    @State private var decodeDepth: Int = 1
    @State private var isFt4: Bool = false
    @State private var qsoLogSaved: Bool = false
    @State private var qsoDxCall: String = ""
    @State private var qsoWaitFor: String = ""    // "snr" | "waiting" | "done" | ""
    @State private var spotSheetItem: Ft8Msg? = nil
    @State private var txLongPressTriggered = false

    // Status
    @State private var statusText: String = "---"

    // TX output gain (0.1 – 2.0, default 0.43)
    @State private var txGainLevel: Float = {
        let v = UserDefaults.standard.float(forKey: "ft8TxGain"); return v > 0 ? v : 0.43
    }()
    @State private var txGainStep: Float = 0.1   // long-press T± to toggle 0.1 ↔ 0.01
    @State private var txGainLongPress = false    // tracks long-press vs tap for T±
    // RX input gain (0.1 – 4.0, default 1.0)
    @State private var rxGainLevel: Float = {
        let v = UserDefaults.standard.float(forKey: "ft8RxGain"); return v > 0 ? v : 1.0
    }()

    // Sheet flags
    @State private var showCallsignSheet = false
    @State private var showQsoStartSheet = false
    @State private var showQsoLogSheet = false
    @State private var showTxFreqSheet = false
    @State private var showFreqPicker = false
    @State private var showMemSheet = false
    @State private var showPowerPicker = false
    @State private var qsoStartData: QsoStartData? = nil
    @State private var txFreqInput: String = ""
    @State private var showDebugAlert = false
    @State private var debugText = ""
    @AppStorage("ft8FontSize") private var ft8FontSize: Double = 16

    private let powerSteps: [Double] = [0.05,0.10,0.20,0.30,0.40,0.50,0.60,0.70,0.80,0.90,1.00]

    private func powerButton(_ v: Double) -> some View {
        Button("\(Int(v * 100))%") { Task { await vm.setPower(v) } }
    }

    // CI-V local mode: useCivMode + connected → local FT8 engine (no Pi server)
    private var isLocalMode: Bool { vm.useCivMode && vm.civConnected }

    // Dispatch FT8 control to either the local engine or the Pi server.
    private func dispatchSetAutoTx(active: Bool, mode: String, msg: String, audioFreqHz: Int) {
        if let eng = localEngine {
            eng.setAutoTx(active: active, mode: mode, msg: msg, audioFreqHz: audioFreqHz)
        } else {
            Task { try? await vm.ft8SetAutoTx(active: active, mode: mode, msg: msg, audioFreqHz: audioFreqHz) }
        }
    }
    private func dispatchCqAuto(active: Bool, msg: String, mode: String,
                                audioFreqHz: Int, myCall: String, myGrid: String) {
        if let eng = localEngine {
            eng.startCqAuto(active: active, msg: msg, mode: mode, audioFreqHz: audioFreqHz,
                            call: myCall, grid: myGrid)
        } else {
            Task { try? await vm.ft8CqAuto(active: active, msg: msg, mode: mode,
                                           audioFreqHz: audioFreqHz, myCall: myCall, myGrid: myGrid) }
        }
    }
    private func dispatchStartQso(r: QsoStartResult) {
        if let eng = localEngine {
            eng.startQso(dxCall: r.dxCall, firstMsg: r.msg1, txMode: r.txMode,
                         audioFreqHz: txFreqHz, initialState: r.initialState)
        } else {
            Task { try? await vm.ft8StartQso(firstMsg: r.msg1, dxCall: r.dxCall, myCall: r.myCall,
                                              txMode: r.txMode, audioFreqHz: txFreqHz,
                                              initialState: r.initialState, stepSnrMsg: r.msgSnr,
                                              step2Msg: r.msg2, step3Msg: r.msg3, step4Msg: r.msg4) }
        }
    }
    private func dispatchCancelQso() {
        if let eng = localEngine { eng.cancelQso() }
        else { Task { try? await vm.ft8CancelQso() } }
    }

    // Feed a local engine SSE event through the existing parseSseLine handler.
    private func parseSseEventLocal(_ type: String, _ data: [String: Any]) {
        var payload = data; payload["type"] = type
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload),
              let jsonStr  = String(data: jsonData, encoding: .utf8) else { return }
        parseSseLine(jsonStr)
    }
    private let ft8Bands: [(label: String, hz: Int64)] = [
        ("160m  1.840 MHz",        1_840_000),
        ("160m  1.908 MHz (JA)",   1_908_000),
        ("80m   3.573 MHz",        3_573_000),
        ("80m   3.531 MHz (JA)",   3_531_000),
        ("40m   7.074 MHz",        7_074_000),
        ("40m   7.041 MHz (JA)",   7_041_000),
        ("30m  10.136 MHz",       10_136_000),
        ("20m  14.074 MHz",       14_074_000),
        ("17m  18.100 MHz",       18_100_000),
        ("15m  21.074 MHz",       21_074_000),
        ("12m  24.915 MHz",       24_915_000),
        ("10m  28.074 MHz",       28_074_000),
        ("6m   50.313 MHz",       50_313_000),
        ("2m  144.174 MHz",      144_174_000),
        ("2m  144.460 MHz (JA)", 144_460_000),
        ("70cm 430.510 MHz (JA)", 430_510_000),
        ("70cm 432.174 MHz",      432_174_000),
    ]
    private let ft4Bands: [(label: String, hz: Int64)] = [
        ("160m  1.844 MHz",        1_844_000),
        ("160m  1.912 MHz (JP)",   1_912_000),
        ("80m   3.575 MHz",        3_575_000),
        ("80m   3.535 MHz (JP)",   3_535_000),
        ("40m   7.078 MHz",        7_078_000),
        ("40m   7.047 MHz (JP)",   7_047_000),
        ("30m  10.140 MHz",       10_140_000),
        ("20m  14.080 MHz",       14_080_000),
        ("17m  18.104 MHz",       18_104_000),
        ("15m  21.140 MHz",       21_140_000),
        ("12m  24.919 MHz",       24_919_000),
        ("10m  28.180 MHz",       28_180_000),
        ("6m   50.318 MHz",       50_318_000),
        ("2m  144.170 MHz",      144_170_000),
        ("2m  144.460 MHz (JP)", 144_460_000),
        ("70cm 430.510 MHz (JP)", 430_510_000),
        ("70cm 432.174 MHz",      432_174_000),
    ]

    // MARK: Body

    var body: some View {
        VStack(spacing: 0) {
            topBar
            WaterfallView(store: wf,
                          txFreqHz: txFreqHz, maxAudioHz: 3000,
                          brightness: wfBrightness) { hz in
                txFreqHz = hz
                vm.ft8AudioFreqHz = hz
                vm.persistFt8Settings()
            }
            .frame(height: 120)
            msgListView
            statusBarView
            txControlsView
        }
        .background(Color.black)
        .navigationBarHidden(true)
        .onAppear { onViewAppear() }
        .onDisappear { onViewDisappear() }
        // If CI-V connects/disconnects while the FT8 view is already open, recreate the engine.
        // vm.civConnected is @Observable (unlike civ.isConnected) so SwiftUI tracks it.
        .onChange(of: vm.civConnected) { _, nowConnected in
            guard vm.useCivMode else { return }
            if nowConnected, localEngine == nil {
                sseTask?.cancel()
                spectrumTask?.cancel()
                let engine = Ft8LocalEngine(civ: vm.civ)
                engine.txGain = txGainLevel
                engine.rxGain = rxGainLevel
                engine.isFt4 = isFt4
                localEngine = engine
                startCivTasks(engine: engine)
                statusText = "CI-V local: engine starting..."
                Task { await vm.enterFt8Mode() }
                engine.start(myCallSign: vm.ft8MyCall, myGrid: vm.ft8MyGrid)
                statusText = "CI-V local: engine running"
            } else if !nowConnected, let engine = localEngine {
                stopCivTasks()
                engine.stop(); localEngine = nil
                statusText = "CI-V disconnected"
            }
        }
        .onChange(of: vm.ft8IsFt4) { _, newVal in
            guard newVal != isFt4 else { return }
            isFt4 = newVal
            if let eng = localEngine {
                eng.isFt4 = newVal
            } else {
                Task { try? await vm.ft8SetFt4(newVal) }
            }
        }
        .sheet(isPresented: $showMemSheet) { MemoryPanelView(vm: vm) }
        .sheet(isPresented: $showCallsignSheet) { callsignSheetView }
        .sheet(isPresented: $showQsoStartSheet) {
            if let data = qsoStartData {
                QsoStartSheet(data: data,
                              onStart: handleQsoStart,
                              onLog:   handleQsoLog)
            }
        }
        .sheet(isPresented: $showQsoLogSheet) { qsoLogSheetView }
        .sheet(isPresented: $showTxFreqSheet) { txFreqSheetView }
        .sheet(item: $spotSheetItem) { item in
            SpotInfoSheet(item: item, potaMap: potaMap, sotaMap: sotaMap, hunterStore: vm.hunterStore)
        }
        .confirmationDialog(isFt4 ? "FT4 Band" : "FT8 Band", isPresented: $showFreqPicker, titleVisibility: .visible) {
            ForEach(isFt4 ? ft4Bands : ft8Bands, id: \.hz) { b in
                Button(b.label) { selectBand(b.hz) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Power", isPresented: $showPowerPicker, titleVisibility: .visible) {
            ForEach(powerSteps, id: \.self) { v in powerButton(v) }
            Button("Cancel", role: .cancel) {}
        }
        .alert("jt9 decode debug", isPresented: $showDebugAlert) {
            Button("OK", role: .cancel) {}
        } message: { Text(debugText) }
    }

    // MARK: - Top bar (2 rows)

    private var topBar: some View {
        VStack(spacing: 0) {
            // ── Row 1: navigation / mode / freq ──────────────────────────────
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    Button { vm.path.removeLast() } label: {
                        Image(systemName: "chevron.left").font(.caption.bold())
                    }
                    Button {
                        decodeDepth = decodeDepth % 3 + 1
                        vm.ft8DecodeDepth = decodeDepth
                        vm.persistFt8Settings()
                        Task { try? await vm.ft8SetDepth(decodeDepth) }
                    } label: {
                        Text("D:\(decodeDepth)").font(.caption.monospacedDigit())
                    }
                    Button { Task { await syncTime() } } label: {
                        Image(systemName: "clock.arrow.2.circlepath").font(.caption)
                    }
                    Divider().frame(height: 16)
                    Button {
                        isFt4.toggle()
                        vm.ft8IsFt4 = isFt4
                        vm.persistFt8Settings()
                        if let eng = localEngine {
                            eng.isFt4 = isFt4
                        } else {
                            Task { try? await vm.ft8SetFt4(isFt4) }
                        }
                    } label: {
                        Text(isFt4 ? "FT4" : "FT8")
                            .font(.caption.bold())
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6).padding(.vertical, 3)
                            .background(isFt4 ? Color.orange : Color.blue, in: Capsule())
                    }
                    Button {
                        let usbModes = vm.supportedModes.filter { $0.uppercased().contains("USB") }
                        let cycleOptions = usbModes.isEmpty ? ["USB"] : usbModes
                        let cur = vm.ft8TxMode == "USB-D" ? "PKTUSB" : vm.ft8TxMode
                        let idx = cycleOptions.firstIndex(of: cur) ?? 0
                        let next = cycleOptions[(idx + 1) % cycleOptions.count]
                        vm.ft8TxMode = next
                        Task { await vm.setMode(next, width: 3000) }
                    } label: {
                        Text(vm.ft8TxMode == "USB-D" ? "PKTUSB" : vm.ft8TxMode)
                            .font(.caption.monospacedDigit())
                    }
                    Button { showPowerPicker = true } label: {
                        Text("PWR \(Int(vm.sharedPower * 100))")
                            .font(.caption.monospacedDigit())
                    }
                    Button { showFreqPicker = true } label: {
                        Text(vm.sharedFreq <= 0 ? "--.-"
                             : String(format: "%.3f", Double(vm.sharedFreq) / 1_000_000.0))
                            .font(.caption.monospacedDigit())
                    }
                    .simultaneousGesture(LongPressGesture().onEnded { _ in showMemSheet = true })
                    Divider().frame(height: 16)
                    Button {
                        filterEnabled.toggle()
                        vm.ft8Filter = filterEnabled
                        vm.persistFt8Settings()
                        if !filterEnabled {
                            filterDxFreq = 0
                            Task { try? await vm.ft8SetDecodeFilter(freqs: [], bw: 150) }
                        } else if filterDxFreq > 0 {
                            let f = filterDxFreq
                            Task { try? await vm.ft8SetDecodeFilter(freqs: [f], bw: 150) }
                        }
                    } label: {
                        Text(filterDxFreq > 0 ? "FIL:\(filterDxFreq)" : "FILTER")
                            .font(.caption.bold())
                            .foregroundStyle((filterEnabled || filterDxFreq > 0) ? .white : .secondary)
                            .padding(.horizontal, 6).padding(.vertical, 3)
                            .background(filterDxFreq > 0 ? Color.orange.opacity(0.8) : filterEnabled ? Color.indigo : Color.gray.opacity(0.3), in: Capsule())
                    }
                    Divider().frame(height: 16)
                    Button { showPskReporter() } label: {
                        Text("GL").font(.caption.bold())
                    }
                    Button { showQsoLogSheet = true } label: {
                        Image(systemName: "list.bullet").font(.caption)
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
            }
            Divider()
            // ── Row 2: gain / brightness / font ──────────────────────────────
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    // V: RX volume/gain (mirrors Android V-/×1.0/V+)
                    Text("V").font(.system(size: 10)).foregroundStyle(.secondary)
                    Button {
                        rxGainLevel = max(0.1, Float(round(Double(rxGainLevel - 0.1) * 10)) / 10)
                        UserDefaults.standard.set(rxGainLevel, forKey: "ft8RxGain")
                        localEngine?.rxGain = rxGainLevel
                    } label: { Text("−").font(.caption) }
                    Text(String(format: "×%.1f", rxGainLevel))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(rxGainLevel > 2.0 ? Color.orange : Color.secondary)
                    Button {
                        rxGainLevel = min(4.0, Float(round(Double(rxGainLevel + 0.1) * 10)) / 10)
                        UserDefaults.standard.set(rxGainLevel, forKey: "ft8RxGain")
                        localEngine?.rxGain = rxGainLevel
                    } label: { Text("+").font(.caption) }
                    Divider().frame(height: 14)
                    // TX output gain — long-press ± to toggle step 0.1 ↔ 0.01
                    Text("T").font(.system(size: 10)).foregroundStyle(.secondary)
                    Text(txGainStep < 0.05 ? "−−" : "−")
                        .font(.caption)
                        .padding(.horizontal, 2)
                        .contentShape(Rectangle())
                        .onLongPressGesture(minimumDuration: 0.5,
                                            pressing: { isPressing in
                                                if !isPressing {
                                                    if txGainLongPress {
                                                        txGainLongPress = false
                                                    } else {
                                                        let s = Double(txGainStep)
                                                        let scale = 1.0 / s
                                                        txGainLevel = max(0.01, Float(round((Double(txGainLevel) - s) * scale) / scale))
                                                        UserDefaults.standard.set(txGainLevel, forKey: "ft8TxGain")
                                                        if let eng = localEngine { eng.txGain = txGainLevel }
                                                        else { let g = txGainLevel; Task { try? await vm.ft8SetTxGain(g) } }
                                                    }
                                                }
                                            },
                                            perform: {
                                                txGainLongPress = true
                                                txGainStep = txGainStep < 0.05 ? 0.1 : 0.01
                                            })
                    Text(txGainStep < 0.05
                         ? String(format: "×%.2f", txGainLevel)
                         : String(format: "×%.1f", txGainLevel))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(txGainLevel > 1.0 ? Color.orange : Color.secondary)
                    Text(txGainStep < 0.05 ? "++" : "+")
                        .font(.caption)
                        .padding(.horizontal, 2)
                        .contentShape(Rectangle())
                        .onLongPressGesture(minimumDuration: 0.5,
                                            pressing: { isPressing in
                                                if !isPressing {
                                                    if txGainLongPress {
                                                        txGainLongPress = false
                                                    } else {
                                                        let s = Double(txGainStep)
                                                        let scale = 1.0 / s
                                                        txGainLevel = min(2.0, Float(round((Double(txGainLevel) + s) * scale) / scale))
                                                        UserDefaults.standard.set(txGainLevel, forKey: "ft8TxGain")
                                                        if let eng = localEngine { eng.txGain = txGainLevel }
                                                        else { let g = txGainLevel; Task { try? await vm.ft8SetTxGain(g) } }
                                                    }
                                                }
                                            },
                                            perform: {
                                                txGainLongPress = true
                                                txGainStep = txGainStep < 0.05 ? 0.1 : 0.01
                                            })
                    Divider().frame(height: 14)
                    // Waterfall brightness (mirrors Android B-/B+)
                    Text("B").font(.system(size: 10)).foregroundStyle(.secondary)
                    Button { wfBrightness = max(0.2, wfBrightness - 0.2) } label: {
                        Text("−").font(.caption)
                    }
                    Button { wfBrightness = min(4.0, wfBrightness + 0.2) } label: {
                        Text("+").font(.caption)
                    }
                    Divider().frame(height: 14)
                    // Font size
                    Button { ft8FontSize = max(10, ft8FontSize - 1) } label: {
                        Text("F−").font(.caption)
                    }
                    Button { ft8FontSize = min(22, ft8FontSize + 1) } label: {
                        Text("F+").font(.caption)
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
            }
            // ── Row 3: ALC / Power meters (TX中のみ表示) ─────────────────────
            if vm.sharedTx {
                Divider()
                HStack(spacing: 6) {
                    Text("PO")
                        .font(.system(size: 8, weight: .bold))
                        .foregroundStyle(Color(red: 1, green: 0.43, blue: 0))
                        .frame(width: 18)
                    TxBarMeterView(
                        value: vm.sharedRfOut,
                        activeColors: [Color(red: 1, green: 0.43, blue: 0),
                                       Color(red: 0.99, green: 0.85, blue: 0.21),
                                       Color(red: 0.84, green: 0, blue: 0)],
                        inactiveColor: Color(red: 0.10, green: 0.03, blue: 0)
                    )
                    Text(String(format: "%3d%%", Int(vm.sharedRfOut * 100)))
                        .font(.system(size: 9).monospacedDigit())
                        .foregroundStyle(Color(red: 1, green: 0.43, blue: 0))
                        .frame(width: 32, alignment: .trailing)
                    Text("ALC")
                        .font(.system(size: 7, weight: .bold))
                        .foregroundStyle(Color(red: 0.84, green: 0, blue: 0))
                        .frame(width: 18)
                    TxBarMeterView(
                        value: vm.sharedAlc,
                        activeColors: [Color(red: 0.84, green: 0, blue: 0),
                                       Color(red: 0.84, green: 0, blue: 0)],
                        inactiveColor: Color(red: 0.10, green: 0, blue: 0)
                    )
                    Text(String(format: "%3d%%", Int(vm.sharedAlc * 100)))
                        .font(.system(size: 9).monospacedDigit())
                        .foregroundStyle(Color(red: 0.84, green: 0, blue: 0))
                        .frame(width: 32, alignment: .trailing)
                }
                .padding(.horizontal, 10).padding(.vertical, 4)
            }
        }
        .background(Color(.secondarySystemBackground))
    }

    // MARK: - Message list

    private struct FT8ListItem: Identifiable {
        let id: String
        let msg: Ft8Msg?
        let periodLabel: String?
        static func row(_ m: Ft8Msg) -> FT8ListItem { FT8ListItem(id: m.id.uuidString, msg: m, periodLabel: nil) }
        static func sep(key: String, label: String) -> FT8ListItem { FT8ListItem(id: key, msg: nil, periodLabel: label) }
    }

    private var listItems: [FT8ListItem] {
        var result: [FT8ListItem] = []
        var lastUtcSec: Int? = nil
        for item in combinedMsgs {
            if !item.isTx && item.utcSec != lastUtcSec {
                let secs = item.epochMs / 1000
                var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "UTC")!
                let c = cal.dateComponents([.hour, .minute], from: Date(timeIntervalSince1970: secs))
                let label = String(format: "%02d:%02d UTC", c.hour ?? 0, c.minute ?? 0)
                result.append(.sep(key: "sep_\(Int(item.epochMs))", label: label))
                lastUtcSec = item.utcSec
            }
            result.append(.row(item))
        }
        return result
    }

    private var msgListView: some View {
        ScrollViewReader { proxy in
            List(listItems) { listItem in
                if let item = listItem.msg {
                    msgRow(item)
                        .contentShape(Rectangle())
                        .listRowBackground(rowBackground(item))
                        .listRowInsets(EdgeInsets(top: 3, leading: 6, bottom: 3, trailing: 6))
                        .onTapGesture { if !item.isTx { onMsgTapped(item) } }
                } else if let label = listItem.periodLabel {
                    Text(label)
                        .font(.system(size: 8, design: .monospaced))
                        .foregroundStyle(Color.white.opacity(0.25))
                        .frame(maxWidth: .infinity, alignment: .center)
                        .listRowBackground(Color(hex: 0x060606))
                        .listRowInsets(EdgeInsets(top: 2, leading: 6, bottom: 2, trailing: 6))
                }
            }
            .listStyle(.plain)
            .environment(\.defaultMinListRowHeight, 30)
            .onChange(of: listItems.last?.id) { _, id in
                if let id { withAnimation { proxy.scrollTo(id, anchor: .bottom) } }
            }
        }
    }

    private var combinedMsgs: [Ft8Msg] {
        let rx = filterEnabled && filterDxFreq > 0
            ? rxMsgs.filter { abs($0.freq - filterDxFreq) <= 150 }
            : rxMsgs
        return (rx + txMsgs).sorted { $0.seqNo < $1.seqNo }
    }

    @ViewBuilder
    private func msgRow(_ item: Ft8Msg) -> some View {
        let timeStr = utcTimeFormatter.string(from: Date(timeIntervalSince1970: item.epochMs / 1000))
        let isEven = isFt4 ? item.utcSec % 15 == 0 : item.utcSec % 30 == 0
        let base = CGFloat(ft8FontSize)
        let meta = max(base - 4, 8)   // UTC / freq / SNR: smaller than message

        if item.isTx {
            let txParts = item.msg.trimmingCharacters(in: .whitespaces).uppercased()
                .components(separatedBy: .whitespaces)
            let txW3 = txParts.count > 2 ? txParts[2] : ""
            let isRStep = txW3.hasPrefix("R") && txW3.dropFirst().contains { $0.isNumber }
            let (txLabel, txLabelColor, txMsgColor): (String, Color, Color) = {
                if isRStep          { return ("TX R",  Color(hex: 0xFFBB00), Color(hex: 0xFFDD88)) }
                if txW3 == "RR73"   { return ("TX 3",  Color(hex: 0x44CC66), Color(hex: 0x88FFAA)) }
                if txW3 == "73"     { return ("TX 73", Color(hex: 0x44FF44), Color(hex: 0x88FF88)) }
                return ("TX▶", Color(hex: 0xFF6622), Color(hex: 0xFF9944))
            }()
            HStack(spacing: 1) {
                Text(timeStr).font(.system(size: meta, design: .monospaced))
                    .foregroundStyle(isEven ? Color(hex: 0x88AAFF) : Color(hex: 0x88FF44))
                Text(txLabel).font(.system(size: meta, design: .monospaced))
                    .foregroundStyle(txLabelColor)
                    .padding(.leading, 1)
                Text(item.msg).font(.system(size: base, design: .monospaced))
                    .foregroundStyle(txMsgColor)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 2)
            }
        } else {
            let myCall = vm.ft8MyCall.uppercased()
            let parts = item.msg.trimmingCharacters(in: .whitespaces).uppercased()
                .components(separatedBy: .whitespaces).filter { !$0.isEmpty }
                .map { ($0.hasPrefix("<") && $0.hasSuffix(">")) ? String($0.dropFirst().dropLast()) : $0 }
            let isToMe = !myCall.isEmpty && parts.first == myCall
            let isCq   = item.msg.hasPrefix("CQ ")
            let snrRaw = item.snr >= 0 ? "+\(item.snr)" : "\(item.snr)"
            // Right-justify to width 3 (sign + 2 digits covers typical FT8 SNR range).
            let snrStr = String(repeating: " ", count: max(0, 3 - snrRaw.count)) + snrRaw
            // POTA/SOTA badge: check the second token (activator position), strip /P suffix.
            // Mirrors Android: parts.drop(1).dropLast(1).firstOrNull()
            let keywords = Set(["CQ", "DX", "RR73", "RRR", "73", "DE", "QRZ", "TU", "TNX"])
            let middleParts = parts.count >= 3 ? Array(parts.dropFirst().dropLast()) : Array(parts.dropFirst())
            let call2base = middleParts.first(where: { t in
                t.count >= 3 && t.count <= 13 &&
                !keywords.contains(t) &&
                t.contains(where: { $0.isNumber }) &&
                t.contains(where: { $0.isLetter })
            }).map { $0.components(separatedBy: "/").first ?? $0 } ?? ""
            let potaSpots = call2base.isEmpty ? [] : (potaMap[call2base] ?? [])
            let sotaSpots = call2base.isEmpty ? [] : (sotaMap[call2base] ?? [])
            let isPota = !potaSpots.isEmpty
            let isSota = !sotaSpots.isEmpty
            let isHunted = isPota && potaSpots.contains { vm.hunterStore.isHunted($0.reference) }
            let badgeLabel: String = {
                let p = isPota ? (isHunted ? "P✓" : "P") : ""
                let s = isSota ? "S" : ""
                if !p.isEmpty && !s.isEmpty { return "\(p)/\(s)" }
                return p.isEmpty ? s : p
            }()
            // Android colors: 未ハントPOTA=緑、ハント済みPOTA=暗緑、SOTAのみ=青
            let badgeColor: Color = isPota
                ? (isHunted ? Color(hex: 0x1B5E20) : Color(hex: 0x00CC55))
                : Color(hex: 0x0088EE)
            let spotRef: String = isPota
                ? (potaSpots.first?.reference ?? "")
                : (sotaSpots.first?.summitCode ?? "")
            HStack(spacing: 1) {
                Text(timeStr).font(.system(size: meta, design: .monospaced))
                    .foregroundStyle(isEven ? Color(hex: 0x88AAFF) : Color(hex: 0x88FF44))
                Text(String(format: "%4d", item.freq)).font(.system(size: meta, design: .monospaced))
                    .foregroundStyle(isToMe ? Color(hex: 0xFFDD44) : Color(hex: 0xAAAAAA))
                    .padding(.leading, 1)
                Text(snrStr).font(.system(size: meta, design: .monospaced))
                    .foregroundStyle(Color(hex: 0x888888))
                    .padding(.leading, 1)
                Text(item.msg).font(.system(size: base, design: .monospaced))
                    .foregroundStyle(isToMe ? Color(hex: 0xFFFF00)
                                    : isCq   ? Color(hex: 0x88FF88)
                                    :           Color(hex: 0xCCCCCC))
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 2)
                    .layoutPriority(-1)
                if !badgeLabel.isEmpty {
                    Button { spotSheetItem = item } label: {
                        VStack(spacing: 1) {
                            Text(badgeLabel)
                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 3).padding(.vertical, 1)
                                .background(RoundedRectangle(cornerRadius: 3).fill(badgeColor))
                            if !spotRef.isEmpty {
                                Text(spotRef)
                                    .font(.system(size: 8, weight: .bold, design: .monospaced))
                                    .foregroundStyle(badgeColor)
                                    .lineLimit(1)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .layoutPriority(1)
                }
            }
        }
    }

    private func rowBackground(_ item: Ft8Msg) -> Color {
        if item.isTx {
            let parts = item.msg.trimmingCharacters(in: .whitespaces).uppercased()
                .components(separatedBy: .whitespaces)
            let w3 = parts.count > 2 ? parts[2] : ""
            if w3.hasPrefix("R") && w3.dropFirst().contains(where: { $0.isNumber }) { return Color(hex: 0x1E1200) }
            if w3 == "RR73" { return Color(hex: 0x001A0A) }
            if w3 == "73"   { return Color(hex: 0x001400) }
            return Color(hex: 0x2A1400)
        }
        let myCall = vm.ft8MyCall.uppercased()
        let parts = item.msg.trimmingCharacters(in: .whitespaces).uppercased()
            .components(separatedBy: .whitespaces).filter { !$0.isEmpty }
            .map { ($0.hasPrefix("<") && $0.hasSuffix(">")) ? String($0.dropFirst().dropLast()) : $0 }
        let isToMe = !myCall.isEmpty && parts.first == myCall
        let isFromDx = !qsoDxCall.isEmpty && parts.count > 1 && parts[1] == qsoDxCall
        let w3 = parts.count > 2 ? parts[2] : ""
        let isCq = item.msg.hasPrefix("CQ ")
        if isToMe && isFromDx && qsoWaitFor == "waiting" && (w3 == "RR73" || w3 == "73") {
            return Color(hex: 0x003300)  // 明緑: RR73/73受信→73へ進む
        }
        if isToMe && isFromDx && qsoWaitFor == "waiting" { return Color(hex: 0x001428) }  // 青: RR73待ち
        if isToMe && isFromDx && qsoWaitFor == "snr" && w3 != "RR73" && w3 != "73" {
            return Color(hex: 0x1F1200)  // 琥珀: SNR受信→R-SNRへ
        }
        if isToMe { return Color(hex: 0x1A1A00) }
        if isCq   { return Color(hex: 0x1A2C1A) }
        return Color(hex: 0x0A0A0A)
    }

    // MARK: - Status bar

    private var statusBarView: some View {
        HStack {
            Text(vm.sharedMode.isEmpty ? "---" : vm.sharedMode)
                .font(.system(size: 11, design: .monospaced)).foregroundStyle(.secondary)
            Spacer()
            Text(statusText)
                .font(.system(size: 11, design: .monospaced)).foregroundStyle(.primary)
                .lineLimit(1).truncationMode(.tail)
            Spacer()
            Text(vm.sharedFreq <= 0 ? "---.--- MHz"
                 : String(format: "%.3f MHz", Double(vm.sharedFreq) / 1_000_000.0))
                .font(.system(size: 11, design: .monospaced)).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 8).padding(.vertical, 4)
        .background(Color(.secondarySystemBackground))
    }

    // MARK: - TX controls

    private var txControlsView: some View {
        VStack(spacing: 4) {
            // TX message field
            HStack(spacing: 6) {
                // CQ quick-fill; long-press → callsign dialog
                Button { fillCqMsg() } label: {
                    Text("CQ").font(.caption.bold())
                        .frame(minWidth: 36).padding(.vertical, 5)
                        .background(Color(.systemGray5), in: RoundedRectangle(cornerRadius: 6))
                }
                .simultaneousGesture(LongPressGesture().onEnded { _ in showCallsignSheet = true })

                TextField("TX message", text: $txMsg)
                    .font(.system(size: 12, design: .monospaced))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.characters)
                    .padding(.horizontal, 6).padding(.vertical, 4)
                    .background(Color(.systemGray6), in: RoundedRectangle(cornerRadius: 6))

                // TX freq
                Button { showTxFreqSheet = true } label: {
                    Text("TX:\(txFreqHz)").font(.caption.monospacedDigit())
                        .frame(minWidth: 54).padding(.vertical, 5)
                        .background(Color(.systemGray5), in: RoundedRectangle(cornerRadius: 6))
                }
            }
            .padding(.horizontal, 8)

            // TX action row
            HStack(spacing: 6) {
                // EVEN/ODD toggle
                Button {
                    autoTxMode = autoTxMode == "even" ? "odd" : "even"
                    vm.ft8AutoTxMode = autoTxMode
                    vm.persistFt8Settings()
                    if txState == .auto {
                        let m = txMsg; let hz = txFreqHz; let mode = autoTxMode
                        dispatchSetAutoTx(active: true, mode: mode, msg: m, audioFreqHz: hz)
                    }
                } label: {
                    let isEven = autoTxMode == "even"
                    Text(isEven ? "EVEN" : "ODD ")
                        .font(.caption.bold().monospacedDigit())
                        .foregroundStyle(isEven ? Color(hex: 0x88AAFF) : Color(hex: 0x88FF44))
                        .frame(minWidth: 44).padding(.vertical, 5)
                        .background(isEven ? Color(hex: 0x1A237E) : Color(hex: 0x1A3700),
                                    in: RoundedRectangle(cornerRadius: 6))
                }

                Spacer()

                // Clear
                Button {
                    rxMsgs.removeAll(); txMsgs.removeAll()
                    pendingTxSeqNo = -1; nextSeqNo = 0
                    curDecodePeriod = -1; curPeriodSeqNo = 0
                    statusText = "---"
                } label: {
                    Text("CLR").font(.caption)
                        .frame(minWidth: 36).padding(.vertical, 5)
                        .background(Color(.systemGray5), in: RoundedRectangle(cornerRadius: 6))
                }
                .simultaneousGesture(LongPressGesture().onEnded { _ in
                    Task {
                        let info = (try? await vm.ft8DecodeDebug()) ?? "—"
                        await MainActor.run { debugText = info; showDebugAlert = true }
                    }
                })

                // TX / AUTO / CQ AUTO button
                // Short press: AUTO/CQ_AUTO → OFF
                // Long press:  OFF → CQ AUTO
                Button {
                    // LongPressGesture.onEnded fires mid-press (at threshold); Button fires on
                    // finger-up — always after the long press. Guard with flag so the two don't
                    // cancel each other.
                    if txLongPressTriggered {
                        txLongPressTriggered = false
                    } else {
                        handleTxButtonTap()
                    }
                } label: {
                    txButtonLabel
                }
                .simultaneousGesture(LongPressGesture(minimumDuration: 0.6).onEnded { _ in
                    txLongPressTriggered = true
                    handleTxButtonLongPress()
                })
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 6)
        }
        .background(Color(.secondarySystemBackground))
    }

    private var txButtonLabel: some View {
        let (label, bg, fg): (String, Color, Color) = {
            switch txState {
            case .off:    return ("TX",       Color(hex: 0x546E7A), Color(hex: 0xAAAAAA))
            case .auto:   return ("AUTO",     Color(hex: 0xB71C1C), .white)
            case .cqAuto: return ("CQ AUTO",  Color(hex: 0xE65100), .white)
            }
        }()
        return Text(label)
            .font(.caption.bold())
            .foregroundStyle(fg)
            .frame(minWidth: 68).padding(.vertical, 5)
            .background(bg, in: RoundedRectangle(cornerRadius: 6))
    }

    // MARK: - TX Freq sheet

    private var txFreqSheetView: some View {
        NavigationStack {
            Form {
                Section(header: Text("TX Audio Frequency (Hz)  100–2900")) {
                    TextField("1500", text: $txFreqInput)
                        .keyboardType(.numberPad)
                        .font(.title2.monospacedDigit())
                }
            }
            .navigationTitle("TX Freq")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { showTxFreqSheet = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("OK") {
                        if let hz = Int(txFreqInput) {
                            txFreqHz = max(100, min(hz, 2900))
                            vm.ft8AudioFreqHz = txFreqHz
                            vm.persistFt8Settings()
                        }
                        showTxFreqSheet = false
                    }
                }
            }
            .onAppear { txFreqInput = "\(txFreqHz)" }
        }
        .presentationDetents([.height(200)])
    }

    // MARK: - Callsign sheet

    private var callsignSheetView: some View {
        NavigationStack {
            Form {
                Section(header: Text("My Station")) {
                    LabeledContent("Callsign") {
                        TextField("JA1XXX", text: $vm.ft8MyCall)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.characters)
                            .multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Grid") {
                        TextField("PM85", text: $vm.ft8MyGrid)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.characters)
                            .multilineTextAlignment(.trailing)
                    }
                }
                Section {
                    Button("Get Grid from GPS") { applyGridFromGps(); showCallsignSheet = false }
                }
            }
            .navigationTitle("Callsign / Grid")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        vm.ft8MyCall = vm.ft8MyCall.uppercased()
                        vm.ft8MyGrid = vm.ft8MyGrid.uppercased()
                        vm.persistFt8Settings()
                        showCallsignSheet = false
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }

    // MARK: - QSO Log sheet

    private var qsoLogSheetView: some View {
        NavigationStack {
            let entries = loadQsoLog()
            Group {
                if entries.isEmpty {
                    Text("No QSOs logged yet.")
                        .foregroundStyle(.secondary).padding()
                } else {
                    List(entries.indices, id: \.self) { i in
                        let e = entries[i]
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text(e.call).font(.system(size: 13, design: .monospaced).bold())
                                Spacer()
                                Text(freqToBand(e.freq)).font(.caption).foregroundStyle(.secondary)
                                Text(e.mode).font(.caption).foregroundStyle(.secondary)
                            }
                            Text("\(formatDt(e.dt))  RST S:\(e.rstS) R:\(e.rstR)")
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("QSO Log (\(entries.count))")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { showQsoLogSheet = false }
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("Export ADIF") { exportAdif(entries) }
                        Button("Clear Log", role: .destructive) {
                            vm.ft8QsoLog = "[]"
                            vm.persistFt8Settings()
                        }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
        }
    }

    // MARK: - Actions

    private func onViewAppear() {
        vm.captureFt8RestorePoint()
        isFt4 = vm.ft8IsFt4
        txFreqHz = vm.ft8AudioFreqHz > 0 ? vm.ft8AudioFreqHz : 1500
        decodeDepth = vm.ft8DecodeDepth > 0 ? vm.ft8DecodeDepth : 1
        autoTxMode = vm.ft8AutoTxMode
        filterEnabled = vm.ft8Filter
        UIApplication.shared.isIdleTimerDisabled = true

        if isLocalMode {
            // CI-V WiFi mode: local engine handles decode/encode; no Pi server connection.
            let engine = Ft8LocalEngine(civ: vm.civ)
            engine.txGain = txGainLevel
            engine.rxGain = rxGainLevel
            engine.isFt4  = isFt4
            localEngine = engine
            startCivTasks(engine: engine)
            statusText = "CI-V local: starting engine..."
            Task { await vm.enterFt8Mode() }
            engine.start(myCallSign: vm.ft8MyCall, myGrid: vm.ft8MyGrid)
            statusText = "CI-V local: engine running"
        } else {
            let ft4 = isFt4
            Task {
                await vm.audioRxStopIfRunning()
                await vm.enterFt8Mode()
                try? await vm.ft8SetFt4(ft4)
                try? await vm.ft8Start()
            }
            startMsgSse()
            startSpectrumSse()
        }
        fetchSpots()
    }

    private func startCivTasks(engine: Ft8LocalEngine) {
        civSseTask?.cancel()
        civSpecTask?.cancel()
        civSseTask = Task { @MainActor in
            for await event in engine.sseStream {
                parseSseEventLocal(event.type, event.data)
            }
        }
        civSpecTask = Task { @MainActor in
            for await frame in engine.spectrumStream {
                applySpectrumFrame(SpectrumFrame(bins: frame.bins,
                                                 isNewPeriod: frame.newPeriod,
                                                 period: frame.period,
                                                 utcSec: frame.utcSec))
            }
        }
    }

    private func stopCivTasks() {
        civSseTask?.cancel(); civSseTask = nil
        civSpecTask?.cancel(); civSpecTask = nil
    }

    private func fetchSpots() {
        Task {
            guard let url = URL(string: "https://api.pota.app/spot/activator"),
                  let (data, _) = try? await URLSession.shared.data(from: url),
                  let spots = try? JSONDecoder().decode([PotaSpot].self, from: data)
            else { return }
            let valid = spots.filter { !$0.invalid }
            var map: [String: [PotaSpot]] = [:]
            for spot in valid {
                let base = spot.activator.uppercased().components(separatedBy: "/").first ?? spot.activator.uppercased()
                map[base, default: []].append(spot)
            }
            potaMap = map
            vm.updatePotaCache(valid)
        }
        Task {
            let rawSota = await SotaCluster.fetchSpots()
            let dedupedSota = Dictionary(grouping: rawSota, by: { "\($0.activatorCallsign ?? "")|\($0.summitCode ?? "")" })
                .values.compactMap { $0.max(by: { $0.id < $1.id }) }
            var map: [String: [SotaSpot]] = [:]
            for spot in dedupedSota {
                guard let base = spot.activatorCallsign?.uppercased().components(separatedBy: "/").first
                else { continue }
                map[base, default: []].append(spot)
            }
            sotaMap = map
            vm.updateSotaCache(dedupedSota)
        }
    }

    private func onViewDisappear() {
        if let engine = localEngine {
            stopCivTasks()
            engine.stop(); localEngine = nil
        } else {
            sseTask?.cancel()
            spectrumTask?.cancel()
        }
        if txState == .auto {
            let mode = autoTxMode; let hz = txFreqHz
            dispatchSetAutoTx(active: false, mode: mode, msg: "", audioFreqHz: hz)
        }
        if txState == .cqAuto {
            cqAutoUserEnabled = false
            let mode = autoTxMode; let hz = txFreqHz
            dispatchCqAuto(active: false, msg: "", mode: mode, audioFreqHz: hz, myCall: "", myGrid: "")
        }
        dispatchCancelQso()
        Task { await vm.exitFt8Mode() }
    }

    private func selectBand(_ hz: Int64) {
        vm.ft8LastFreq = hz
        Task { await vm.setFreq(hz) }
    }

    private func fillCqMsg() {
        let call = vm.ft8MyCall
        let grid = vm.ft8MyGrid
        if call.isEmpty { showCallsignSheet = true; return }
        txMsg = "CQ \(call) \(String(grid.prefix(4)))".trimmingCharacters(in: .whitespaces)
    }

    private func handleTxButtonTap() {
        switch txState {
        case .auto:
            txState = .off
            removePendingTx()
            let mode = autoTxMode; let hz = txFreqHz
            dispatchSetAutoTx(active: false, mode: mode, msg: "", audioFreqHz: hz)
            if filterDxFreq > 0 {
                filterDxFreq = 0
                Task { try? await vm.ft8SetDecodeFilter(freqs: [], bw: 150) }
            }
        case .cqAuto:
            cqAutoUserEnabled = false  // guard against stale cq_auto_restart arriving after stop
            txState = .off
            cqAutoOrigMsg = ""
            removePendingTx()
            let mode = autoTxMode; let hz = txFreqHz
            dispatchCqAuto(active: false, msg: "", mode: mode, audioFreqHz: hz, myCall: "", myGrid: "")
        case .off:
            break
        }
    }

    private func handleTxButtonLongPress() {
        guard txState == .off else { return }
        let msg = txMsg.trimmingCharacters(in: .whitespaces)
        if msg.isEmpty { statusText = "Enter a message in the TX field"; return }
        let myCall = vm.ft8MyCall; let myGrid = vm.ft8MyGrid
        if myCall.isEmpty { showCallsignSheet = true; return }
        cqAutoOrigMsg = msg
        cqAutoUserEnabled = true   // arm the guard
        txState = .cqAuto
        let mode = autoTxMode; let hz = txFreqHz
        dispatchCqAuto(active: true, msg: msg, mode: mode, audioFreqHz: hz, myCall: myCall, myGrid: myGrid)
    }

    private func removePendingTx() {
        guard pendingTxSeqNo >= 0 else { return }
        let sno = pendingTxSeqNo
        pendingTxSeqNo = -1
        txMsgs.removeAll { $0.isTx && $0.seqNo == sno }
    }

    private func syncTime() async {
        if let eng = localEngine {
            // Local mode: phone clock is NTP-synced; offset stays 0.
            statusText = "Local mode: using system clock (no Pi sync needed)"
            eng.ntpOffsetMs = 0
            return
        }
        statusText = "Syncing time..."
        guard let offset = try? await vm.syncFt8Clock() else {
            statusText = "Time sync error"; return
        }
        statusText = "Synced drift=\(offset)ms — restarting..."
        try? await Task.sleep(nanoseconds: 300_000_000)
        try? await vm.ft8Stop()
        try? await Task.sleep(nanoseconds: 500_000_000)
        try? await vm.ft8Start()
        statusText = "Synced drift=\(offset)ms — FT8 restarted"
    }

    private func applyGridFromGps() {
        vm.requestLocationAuthorization()
        guard let fix = vm.currentLocationFix() else { statusText = "No GPS fix"; return }
        let grid = MainViewModel.latLonToGrid(lat: fix.lat, lon: fix.lon)
        vm.ft8MyGrid = grid
        vm.persistFt8Settings()
    }

    private func showPskReporter() {
        let myCall = vm.ft8MyCall
        let freqHz = vm.sharedFreq
        let bandMhz = bandMhzFor(freqHz)
        var urlStr = "https://pskreporter.info/pskmap.html?"
        if !myCall.isEmpty { urlStr += "callsign=\(myCall)&" }
        if bandMhz > 0 { urlStr += "band=\(bandMhz)&" }
        urlStr += "mode=FT8"
        if let url = URL(string: urlStr) { UIApplication.shared.open(url) }
    }

    private func bandMhzFor(_ hz: Int64) -> Int {
        switch hz {
        case 1_800_000..<2_000_000:   return 1
        case 3_500_000..<4_000_000:   return 3
        case 7_000_000..<7_300_000:   return 7
        case 10_100_000..<10_150_000: return 10
        case 14_000_000..<14_350_000: return 14
        case 18_068_000..<18_168_000: return 18
        case 21_000_000..<21_450_000: return 21
        case 24_890_000..<24_990_000: return 24
        case 28_000_000..<29_700_000: return 28
        case 50_000_000..<54_000_000: return 50
        case 144_000_000..<148_000_000: return 144
        case 430_000_000..<440_000_000: return 430
        default: return 0
        }
    }

    // MARK: - QSO start handling

    private func onMsgTapped(_ item: Ft8Msg) {
        let myCall = vm.ft8MyCall.isEmpty ? "" : vm.ft8MyCall.uppercased()
        guard !myCall.isEmpty else { showCallsignSheet = true; return }
        let myGrid = vm.ft8MyGrid.uppercased()
        let parts = item.msg.trimmingCharacters(in: .whitespaces).uppercased()
            .components(separatedBy: .whitespaces).filter { !$0.isEmpty }
        let searchParts = parts.count >= 3 ? Array(parts.dropLast()) : parts
        let candidates = extractCallsigns(searchParts).filter { $0 != myCall }
        guard !candidates.isEmpty else { return }
        let snrStr = item.snr >= 0 ? "+\(item.snr)" : "\(item.snr)"
        let txMode = (isFt4 ? item.utcSec % 15 == 0 : item.utcSec % 30 == 0) ? "odd" : "even"
        qsoStartData = QsoStartData(candidates: candidates, myCall: myCall, myGrid: myGrid,
                                    defaultTxMode: txMode, snrFromMsg: snrStr, dxFreqHz: item.freq)
        showQsoStartSheet = true
    }

    private func extractCallsigns(_ parts: [String]) -> [String] {
        let keywords: Set<String> = ["CQ","DX","RR73","RRR","73","DE","QRZ","TU","TNX"]
        return parts.map { t in
            (t.hasPrefix("<") && t.hasSuffix(">")) ? String(t.dropFirst().dropLast()) : t
        }.filter { t in
            t.count >= 3 && t.count <= 13 &&
            t.first?.isLetter == true &&
            !keywords.contains(t) &&
            !t.matches(pattern: "^[+-]\\d{1,3}$") &&
            !t.matches(pattern: "^R[+-]\\d{1,3}$") &&
            !t.matches(pattern: "^[A-Z]{2}\\d{2}([A-Z]{2})?$") &&
            t.contains(where: \.isNumber) &&
            t.contains(where: \.isLetter)
        }
    }

    private func handleQsoStart(_ result: QsoStartResult) {
        autoTxMode = result.txMode
        vm.ft8AutoTxMode = result.txMode
        txState = .auto
        qsoLogSaved = false
        txMsg = result.firstMsg
        statusText = "QSO start: \(result.dxCall)"
        if filterEnabled && result.dxFreqHz > 0 {
            filterDxFreq = result.dxFreqHz
            let f = result.dxFreqHz
            Task { try? await vm.ft8SetDecodeFilter(freqs: [f], bw: 150) }
        }
        dispatchStartQso(r: result)
    }

    private func handleQsoLog(_ entry: QsoLogParams) {
        saveQsoLogEntry(dxCall: entry.dxCall, myCall: entry.myCall,
                        snrRcvd: entry.snrRcvd, snrSent: entry.snrSent)
    }

    // MARK: - SSE

    private func startMsgSse() {
        sseTask?.cancel()
        sseTask = Task { await connectMsgSse() }
    }

    private func connectMsgSse() async {
        await MainActor.run { statusText = "RX: connecting..." }
        let sseConfig = URLSessionConfiguration.default
        sseConfig.timeoutIntervalForRequest = 15
        sseConfig.timeoutIntervalForResource = 86400
        let session = URLSession(configuration: sseConfig)

        while !Task.isCancelled {
            guard let req = await vm.makeFt8SseRequest(path: "/ft8/rx_msgs") else {
                try? await Task.sleep(nanoseconds: 3_000_000_000); continue
            }
            do {
                let (bytes, resp) = try await session.bytes(for: req)
                guard let httpResp = resp as? HTTPURLResponse,
                      (200..<300).contains(httpResp.statusCode) else {
                    await MainActor.run { statusText = "RX: HTTP error" }
                    try? await Task.sleep(nanoseconds: 5_000_000_000); continue
                }
                await MainActor.run { statusText = "RX: connected" }
                try? await vm.ft8SetDepth(decodeDepth)
                let syncFreq = await MainActor.run { filterEnabled && filterDxFreq > 0 ? filterDxFreq : 0 }
                if syncFreq > 0 { try? await vm.ft8SetDecodeFilter(freqs: [syncFreq], bw: 150) }
                else            { try? await vm.ft8SetDecodeFilter(freqs: [], bw: 150) }
                for try await line in bytes.lines {
                    guard !Task.isCancelled else { break }
                    if line.hasPrefix("data: ") {
                        await parseSseLine(String(line.dropFirst(6)))
                    }
                }
            } catch {
                if Task.isCancelled { break }
            }
            if !Task.isCancelled {
                await MainActor.run { statusText = "RX: reconnecting..." }
                try? await Task.sleep(nanoseconds: 3_000_000_000)
            }
        }
    }

    private func startSpectrumSse() {
        spectrumTask?.cancel()
        spectrumTask = Task { await connectSpectrumSse() }
    }

    private func connectSpectrumSse() async {
        let sseConfig = URLSessionConfiguration.default
        sseConfig.timeoutIntervalForRequest = 15
        sseConfig.timeoutIntervalForResource = 86400
        let session = URLSession(configuration: sseConfig)

        while !Task.isCancelled {
            guard let req = await vm.makeFt8SseRequest(path: "/ft8/spectrum") else {
                try? await Task.sleep(nanoseconds: 3_000_000_000); continue
            }
            do {
                let (bytes, resp) = try await session.bytes(for: req)
                guard let httpResp = resp as? HTTPURLResponse,
                      (200..<300).contains(httpResp.statusCode) else {
                    try? await Task.sleep(nanoseconds: 5_000_000_000); continue
                }
                // Watchdog: the server sends ": keepalive" comments during stalls,
                // so a frozen feed never errors on its own. If no spectrum frame
                // arrives for a while, tear down and reconnect.
                let lastFrame = LastFrameClock()
                await lastFrame.mark()
                try await withThrowingTaskGroup(of: Void.self) { group in
                    group.addTask {
                        for try await line in bytes.lines {
                            guard !Task.isCancelled else { break }
                            if line.hasPrefix("data: ") {
                                await lastFrame.mark()
                                if let frame = Self.decodeSpectrumFrame(String(line.dropFirst(6))) {
                                    await applySpectrumFrame(frame)
                                }
                            }
                        }
                    }
                    group.addTask {
                        while !Task.isCancelled {
                            try await Task.sleep(nanoseconds: 3_000_000_000)
                            if await lastFrame.secondsSinceMark() > 12 {
                                throw CancellationError()
                            }
                        }
                    }
                    defer { group.cancelAll() }
                    try await group.next()
                }
            } catch {
                if Task.isCancelled { break }
            }
            if !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
            }
        }
    }

    /// Tracks the timestamp of the most recent spectrum frame for the stall watchdog.
    private actor LastFrameClock {
        private var lastMs = Date().timeIntervalSince1970 * 1000
        func mark() { lastMs = Date().timeIntervalSince1970 * 1000 }
        func secondsSinceMark() -> Double {
            (Date().timeIntervalSince1970 * 1000 - lastMs) / 1000
        }
    }

    @MainActor
    private func parseSseLine(_ json: String) {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        let type = obj["type"] as? String ?? ""
        switch type {
        case "decode_msg":
            let period  = obj["period"] as? Int ?? 0
            let utcSec  = obj["utc_sec"] as? Int ?? 0
            let rxEpochMs = Ft8Msg.utcSecToEpochMs(utcSec)
            // Assign seqNo: all msgs in the same period share one seqNo (mirrors Android)
            if period != curDecodePeriod {
                curDecodePeriod = period
                curPeriodSeqNo = nextSeqNo
                nextSeqNo += 1
            }
            let newItem = Ft8Msg(freq: obj["freq"] as? Int ?? 0,
                                 snr:  obj["snr"]  as? Int ?? 0,
                                 dt:   obj["dt"]   as? Double ?? 0,
                                 msg:  obj["msg"]  as? String ?? "",
                                 utcSec: utcSec, period: period,
                                 epochMs: rxEpochMs, seqNo: curPeriodSeqNo)
            rxMsgs.append(newItem)
            if rxMsgs.count > 200 { rxMsgs.removeFirst(rxMsgs.count - 200) }
            // If DX sends 73/RR73 to us while in AUTO mode, stop (defensive; Pi also sends qso_done)
            let rxParts = newItem.msg.uppercased().components(separatedBy: .whitespaces).filter { !$0.isEmpty }
            let myC = vm.ft8MyCall.uppercased()
            if !myC.isEmpty, !qsoDxCall.isEmpty,
               rxParts.count >= 3, rxParts[0] == myC, rxParts[1] == qsoDxCall {
                let last = rxParts.last ?? ""
                if last == "73" || last == "RR73" {
                    if txState == .cqAuto {
                        // CQAUTO: Pi will restart CQ; just clear QSO tracking
                        qsoDxCall = ""; qsoWaitFor = ""
                    } else if txState == .auto {
                        txState = .off; qsoDxCall = ""; qsoWaitFor = ""
                    }
                }
            }

        case "decode_done":
            let period  = obj["period"] as? Int ?? 0
            let utcSec  = obj["utc_sec"] as? Int ?? 0
            let count   = obj["count"]  as? Int ?? 0
            curDecodePeriod = -1  // mirrors Android: force new seqNo on next period even if same number
            statusText = "P\(period) | \(utcSec)s | \(count) decoded"
            if markedUtcSecs.insert(utcSec).inserted {
                if markedUtcSecs.count > 8 { markedUtcSecs.removeFirst() }
                var utcCal = Calendar(identifier: .gregorian)
                utcCal.timeZone = TimeZone(identifier: "UTC")!
                let comps = utcCal.dateComponents([.hour, .minute], from: Date())
                let hhmm = String(format: "%02d:%02d", comps.hour ?? 0, comps.minute ?? 0)
                wf.markers.append(WfMarker(rowsFromBottom: 1, label: "\(hhmm) P\(period)"))
                if wf.markers.count > 8 { wf.markers.removeFirst() }
            }

        case "decode":
            let period  = obj["period"] as? Int ?? 0
            let utcSec  = obj["utc_sec"] as? Int ?? 0
            guard let msgs = obj["msgs"] as? [[String: Any]] else { return }
            let rxEpochMs = Ft8Msg.utcSecToEpochMs(utcSec)
            // Always increment per batch, matching Android's msgSeqNo++ behavior
            let batchSeqNo = nextSeqNo
            nextSeqNo += 1
            curDecodePeriod = period; curPeriodSeqNo = batchSeqNo
            let newItems = msgs.map { m in
                Ft8Msg(freq: m["freq"] as? Int ?? 0,
                       snr:  m["snr"]  as? Int ?? 0,
                       dt:   m["dt"]   as? Double ?? 0,
                       msg:  m["msg"]  as? String ?? "",
                       utcSec: utcSec, period: period,
                       epochMs: rxEpochMs, seqNo: batchSeqNo)
            }
            rxMsgs.append(contentsOf: newItems)
            if rxMsgs.count > 200 { rxMsgs.removeFirst(rxMsgs.count - 200) }
            statusText = "P\(period) | \(utcSec)s | \(newItems.count) decoded"
            if markedUtcSecs.insert(utcSec).inserted {
                if markedUtcSecs.count > 8 { markedUtcSecs.removeFirst() }
                var utcCal = Calendar(identifier: .gregorian)
                utcCal.timeZone = TimeZone(identifier: "UTC")!
                let comps = utcCal.dateComponents([.hour, .minute], from: Date())
                let hhmm = String(format: "%02d:%02d", comps.hour ?? 0, comps.minute ?? 0)
                wf.markers.append(WfMarker(rowsFromBottom: 1, label: "\(hhmm) P\(period)"))
                if wf.markers.count > 8 { wf.markers.removeFirst() }
            }

        case "tx_pending":
            let txMsgStr  = obj["msg"] as? String ?? ""
            let utcAt     = obj["utc_at_tx"] as? Int ?? 0
            let txPeriodSec = isFt4 ? utcAt : ((utcAt + 7) / 15) * 15 % 60
            let txEpochMs = Ft8Msg.utcSecToEpochMs(txPeriodSec)
            if !txMsgStr.isEmpty {
                // Replace previously pending (not yet sent) TX to avoid accumulation
                if pendingTxSeqNo >= 0 {
                    let oldSno = pendingTxSeqNo
                    txMsgs.removeAll { $0.isTx && $0.seqNo == oldSno }
                }
                let sno = nextSeqNo; nextSeqNo += 1
                txMsgs.append(Ft8Msg(freq: 0, snr: 0, dt: 0, msg: txMsgStr,
                                     utcSec: txPeriodSec, period: -1, isTx: true,
                                     epochMs: txEpochMs, seqNo: sno))
                if txMsgs.count > 20 { txMsgs.removeFirst() }
                pendingTxSeqNo = sno
            }
            statusText = "TX▶ \(txMsgStr)"

        case "auto_tx_sent":
            pendingTxSeqNo = -1
            let msg = obj["msg"] as? String ?? ""
            statusText = "AutoTX done: \(msg)"
            // When 73 is confirmed sent, stop AUTO (Pi also sends qso_done; this is a safety net)
            let txParts = msg.uppercased().components(separatedBy: .whitespaces)
            if txParts.last == "73" {
                if txState == .cqAuto {
                    qsoDxCall = ""; qsoWaitFor = ""   // next CQ will resume via Pi
                } else if txState == .auto {
                    txState = .off; qsoDxCall = ""; qsoWaitFor = ""
                }
            }

        case "auto_tx_error":
            let err = obj["error"] as? String ?? ""
            removePendingTx()
            statusText = "AutoTX error: \(err)"

        case "tx_scheduled":
            let txMsgStr = obj["msg"] as? String ?? ""
            let waitSec  = obj["wait_sec"] as? Double ?? 0
            let utcAt    = obj["utc_at_tx"] as? Int ?? 0
            let periodSec = ((utcAt + 7) / 15) * 15 % 60
            statusText = "TX waiting \(txMsgStr)  (in \(Int(waitSec))s → UTC \(periodSec)s)"

        case "period_start":
            let utcSecPs = obj["utc_sec"] as? Int ?? 0
            markedUtcSecs.insert(utcSecPs)
            if markedUtcSecs.count > 8 { markedUtcSecs.removeFirst() }

        case "qso_state":
            let state   = obj["state"]    as? String ?? ""
            let msg     = obj["msg"]      as? String ?? ""
            let dx      = obj["dx_call"]  as? String ?? ""
            let snr     = obj["snr"]      as? String ?? ""
            let txMode  = obj["tx_mode"]  as? String ?? ""
            let snrRcvd = obj["snr_rcvd"] as? String ?? ""
            let snrSent = obj["snr_sent"] as? String ?? ""
            let myCall  = (obj["my_call"] as? String ?? "").isEmpty ? vm.ft8MyCall : (obj["my_call"] as? String ?? "")
            statusText = switch state {
                case "pending":    "QSO pending[\(dx)]: \(msg)"
                case "tx_start":   "TX: \(msg)"
                case "got_report": "Report \(snr) rcvd → \(msg)"
                case "snr_update": "SNR update → \(msg)"
                case "sending_73": "RR73 rcvd → sending 73: \(msg)"
                case "retry_rr73": "RR73 retry: \(msg)"
                default:           "QSO: \(msg)"
            }
            if !msg.isEmpty { txMsg = msg }
            if !txMode.isEmpty { autoTxMode = txMode }
            if txState != .auto && txState != .cqAuto { txState = .auto }
            if !dx.isEmpty { qsoDxCall = dx }
            // Track QSO wait state for row coloring (mirrors Android adapter.setQsoState)
            let wf: String = {
                switch state {
                case "pending":
                    let w3 = msg.trimmingCharacters(in: .whitespaces).uppercased()
                        .components(separatedBy: .whitespaces).dropFirst(2).first ?? ""
                    let isStep2 = w3.hasPrefix("R") && w3.dropFirst().contains { $0.isNumber }
                    return (isStep2 || w3 == "RR73" || w3 == "73") ? "waiting" : "snr"
                case "got_report":  return (obj["wait_for"] as? String) ?? "waiting"
                case "retry_rr73":  return "waiting"
                case "sending_73":  return "done"
                default:            return qsoWaitFor
                }
            }()
            qsoWaitFor = wf
            if state == "sending_73" && !dx.isEmpty && !qsoLogSaved {
                qsoLogSaved = true
                saveQsoLogEntry(dxCall: dx, myCall: myCall, snrRcvd: snrRcvd, snrSent: snrSent)
            }

        case "cq_auto_restart":
            guard cqAutoUserEnabled else { return }  // user stopped CQ AUTO — ignore stale event
            let cqMsg = obj["msg"] as? String ?? ""
            if !cqMsg.isEmpty { txMsg = cqMsg; cqAutoOrigMsg = cqMsg }
            txState = .cqAuto
            qsoLogSaved = false
            qsoDxCall = ""
            qsoWaitFor = ""
            statusText = "CQ AUTO: \(cqMsg)"

        case "qso_done":
            let dx      = obj["dx_call"]  as? String ?? ""
            let myCall  = (obj["my_call"] as? String ?? "").isEmpty ? vm.ft8MyCall : (obj["my_call"] as? String ?? "")
            let snrRcvd = obj["snr_rcvd"] as? String ?? ""
            let snrSent = obj["snr_sent"] as? String ?? ""
            if txState != .cqAuto { txState = .off }
            // Restore original CQ message after each QSO (in case Pi updated txMsg for the QSO)
            if txState == .cqAuto && !cqAutoOrigMsg.isEmpty { txMsg = cqAutoOrigMsg }
            qsoDxCall = ""
            qsoWaitFor = ""
            statusText = "QSO done! \(dx)"
            if !qsoLogSaved {
                qsoLogSaved = true
                saveQsoLogEntry(dxCall: dx, myCall: myCall, snrRcvd: snrRcvd, snrSent: snrSent)
            }
            if filterDxFreq > 0 {
                filterDxFreq = 0
                Task { try? await vm.ft8SetDecodeFilter(freqs: [], bw: 150) }
            }

        default: break
        }
    }

    private struct SpectrumFrame {
        let bins: [UInt8]
        let isNewPeriod: Bool
        let period: Int
        let utcSec: Int
    }

    /// Parses one spectrum SSE payload. Runs off the main actor so JSON decoding
    /// never blocks the byte-reading loop (a slow main thread was stalling the read
    /// loop, which caused the server to drop us and freeze the waterfall).
    private nonisolated static func decodeSpectrumFrame(_ json: String) -> SpectrumFrame? {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = obj["bins"] as? [Int] else { return nil }
        return SpectrumFrame(bins: arr.map { UInt8(clamping: $0) },
                             isNewPeriod: obj["new_period"] as? Bool ?? false,
                             period: obj["period"] as? Int ?? 0,
                             utcSec: obj["utc_sec"] as? Int ?? 0)
    }

    @State private var _applySpectrumCount = 0

    @MainActor
    private func applySpectrumFrame(_ frame: SpectrumFrame) {
        _applySpectrumCount += 1
        if _applySpectrumCount <= 5 || _applySpectrumCount % 100 == 0 {
            NSLog("[Ft8View] applySpectrum #\(_applySpectrumCount) bins=\(frame.bins.count) rows=\(wf.rows.count)")
        }

        // Each new row pushes all markers upward; drop any that scrolled off the top
        wf.markers = wf.markers.compactMap { m in
            let u = WfMarker(rowsFromBottom: m.rowsFromBottom + 1, label: m.label)
            return u.rowsFromBottom < 160 ? u : nil
        }

        if frame.isNewPeriod && markedUtcSecs.insert(frame.utcSec).inserted {
            if markedUtcSecs.count > 8 { markedUtcSecs.removeFirst() }
            var utcCal = Calendar(identifier: .gregorian)
            utcCal.timeZone = TimeZone(identifier: "UTC")!
            let comps = utcCal.dateComponents([.hour, .minute], from: Date())
            let hhmm = String(format: "%02d:%02d", comps.hour ?? 0, comps.minute ?? 0)
            wf.markers.append(WfMarker(rowsFromBottom: 0, label: "\(hhmm) P\(frame.period)"))
            if wf.markers.count > 8 { wf.markers.removeFirst() }
        }

        if !frame.bins.isEmpty {
            wf.rows.append(frame.bins)
            if wf.rows.count > 160 { wf.rows.removeFirst() }
        }
    }

    // MARK: - QSO log helpers

    private func saveQsoLogEntry(dxCall: String, myCall: String, snrRcvd: String, snrSent: String) {
        guard !dxCall.isEmpty, !myCall.isEmpty else { return }
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyyMMddHHmmss"
        fmt.timeZone = TimeZone(identifier: "UTC")
        let dt = fmt.string(from: Date())
        let mode = isFt4 ? "FT4" : "FT8"
        var arr = (try? JSONSerialization.jsonObject(with: Data(vm.ft8QsoLog.utf8)) as? [[String: Any]]) ?? []
        arr.append(["dt": dt, "call": dxCall, "freq": vm.sharedFreq, "mode": mode,
                    "rstS": snrSent.isEmpty ? "-00" : snrSent,
                    "rstR": snrRcvd.isEmpty ? "-00" : snrRcvd,
                    "grid": "", "myCall": myCall, "myGrid": vm.ft8MyGrid])
        if let data = try? JSONSerialization.data(withJSONObject: arr),
           let str = String(data: data, encoding: .utf8) {
            vm.ft8QsoLog = str
        }
    }

    private struct LogEntry {
        let dt: String; let call: String; let freq: Int64; let mode: String
        let rstS: String; let rstR: String; let myCall: String; let myGrid: String
    }

    private func loadQsoLog() -> [LogEntry] {
        guard let arr = try? JSONSerialization.jsonObject(with: Data(vm.ft8QsoLog.utf8)) as? [[String: Any]] else { return [] }
        return arr.map { o in
            LogEntry(dt: o["dt"] as? String ?? "", call: o["call"] as? String ?? "",
                     freq: Int64(o["freq"] as? Int ?? 0), mode: o["mode"] as? String ?? "FT8",
                     rstS: o["rstS"] as? String ?? "", rstR: o["rstR"] as? String ?? "",
                     myCall: o["myCall"] as? String ?? "", myGrid: o["myGrid"] as? String ?? "")
        }
    }

    private func formatDt(_ dt: String) -> String {
        guard dt.count >= 14 else { return dt }
        return "\(dt.prefix(4))-\(dt.dropFirst(4).prefix(2))-\(dt.dropFirst(6).prefix(2)) \(dt.dropFirst(8).prefix(2)):\(dt.dropFirst(10).prefix(2))Z"
    }

    private func freqToBand(_ hz: Int64) -> String {
        switch hz {
        case 1_800_000..<2_000_000:   return "160M"
        case 3_500_000..<4_000_000:   return "80M"
        case 7_000_000..<7_300_000:   return "40M"
        case 10_100_000..<10_150_000: return "30M"
        case 14_000_000..<14_350_000: return "20M"
        case 18_068_000..<18_168_000: return "17M"
        case 21_000_000..<21_450_000: return "15M"
        case 24_890_000..<24_990_000: return "12M"
        case 28_000_000..<29_700_000: return "10M"
        case 50_000_000..<54_000_000: return "6M"
        case 144_000_000..<148_000_000: return "2M"
        case 420_000_000..<450_000_000: return "70CM"
        default: return ""
        }
    }

    private func exportAdif(_ entries: [LogEntry]) {
        func f(_ tag: String, _ v: String) -> String {
            v.isEmpty ? "" : "<\(tag):\(v.count)>\(v) "
        }
        var adif = "ADIF Export from Wifi_RIG_CTRL\n<EOH>\n\n"
        for e in entries {
            let freq = e.freq > 0 ? String(format: "%.4f", Double(e.freq) / 1_000_000.0) : ""
            adif += f("CALL",e.call) + f("MODE",e.mode) + f("BAND",freqToBand(e.freq))
            adif += f("FREQ",freq) + f("QSO_DATE",String(e.dt.prefix(8))) + f("TIME_ON",String(e.dt.dropFirst(8).prefix(6)))
            adif += f("RST_SENT",e.rstS) + f("RST_RCVD",e.rstR)
            adif += f("OPERATOR",e.myCall) + f("MY_GRIDSQUARE",e.myGrid)
            adif += "<EOR>\n"
        }
        let fmt = DateFormatter(); fmt.dateFormat = "yyyyMMdd"; fmt.timeZone = TimeZone(identifier: "UTC")
        let fname = "qso_\(fmt.string(from: Date())).adi"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(fname)
        try? adif.write(to: url, atomically: true, encoding: .utf8)
        let av = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let vc = scene.windows.first?.rootViewController {
            av.popoverPresentationController?.sourceView = vc.view
            vc.present(av, animated: true)
        }
    }
}

// MARK: - QSO Start Sheet

struct QsoStartResult {
    let dxCall: String; let myCall: String; let txMode: String
    let firstMsg: String; let msg1: String; let msgSnr: String; let msg2: String; let msg3: String; let msg4: String
    let initialState: Int; let dxFreqHz: Int
}

struct QsoLogParams {
    let dxCall: String; let myCall: String; let snrRcvd: String; let snrSent: String
}

struct QsoStartSheet: View {
    let data: QsoStartData
    let onStart: (QsoStartResult) -> Void
    let onLog:   (QsoLogParams) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selectedDx: String
    @State private var msg1: String; @State private var msgSnr: String
    @State private var msg2: String; @State private var msg3: String; @State private var msg4: String
    @State private var startStep: Int = 0   // 0=step1, 1=step2snr, 2=step2, 3=step3, 4=step4
    @State private var txMode: String

    init(data: QsoStartData, onStart: @escaping (QsoStartResult) -> Void, onLog: @escaping (QsoLogParams) -> Void) {
        self.data = data; self.onStart = onStart; self.onLog = onLog
        let dx = data.candidates.last ?? ""
        _selectedDx = State(initialValue: dx)
        _txMode     = State(initialValue: data.defaultTxMode)
        _msg1   = State(initialValue: "\(dx) \(data.myCall) \(String(data.myGrid.prefix(4)))".trimmingCharacters(in: .whitespaces))
        _msgSnr = State(initialValue: "\(dx) \(data.myCall) \(data.snrFromMsg)".trimmingCharacters(in: .whitespaces))
        _msg2   = State(initialValue: "\(dx) \(data.myCall) R\(data.snrFromMsg)".trimmingCharacters(in: .whitespaces))
        _msg3   = State(initialValue: "\(dx) \(data.myCall) RR73")
        _msg4   = State(initialValue: "\(dx) \(data.myCall) 73")
    }

    var body: some View {
        NavigationStack {
            Form {
                if data.candidates.count > 1 {
                    Section(header: Text("DX Station")) {
                        Picker("DX", selection: $selectedDx) {
                            ForEach(data.candidates, id: \.self) { Text($0).tag($0) }
                        }
                        .onChange(of: selectedDx) { _, dx in
                            msg1   = "\(dx) \(data.myCall) \(String(data.myGrid.prefix(4)))".trimmingCharacters(in: .whitespaces)
                            msgSnr = "\(dx) \(data.myCall) \(data.snrFromMsg)".trimmingCharacters(in: .whitespaces)
                            msg2   = "\(dx) \(data.myCall) R\(data.snrFromMsg)".trimmingCharacters(in: .whitespaces)
                            msg3   = "\(dx) \(data.myCall) RR73"
                            msg4   = "\(dx) \(data.myCall) 73"
                        }
                    }
                }
                Section(header: Text("Messages")) {
                    LabeledContent("Step1 (grid)") {
                        TextField("", text: $msg1).multilineTextAlignment(.trailing)
                            .autocorrectionDisabled().textInputAutocapitalization(.characters)
                            .font(.system(size: 12, design: .monospaced))
                    }
                    LabeledContent("Step2s (SNR)") {
                        TextField("", text: $msgSnr).multilineTextAlignment(.trailing)
                            .autocorrectionDisabled().textInputAutocapitalization(.characters)
                            .font(.system(size: 12, design: .monospaced))
                    }
                    LabeledContent("Step2 (R+SNR)") {
                        TextField("", text: $msg2).multilineTextAlignment(.trailing)
                            .autocorrectionDisabled().textInputAutocapitalization(.characters)
                            .font(.system(size: 12, design: .monospaced))
                    }
                    LabeledContent("Step3 (RR73)") {
                        TextField("", text: $msg3).multilineTextAlignment(.trailing)
                            .autocorrectionDisabled().textInputAutocapitalization(.characters)
                            .font(.system(size: 12, design: .monospaced))
                    }
                    LabeledContent("Step4 (73)") {
                        TextField("", text: $msg4).multilineTextAlignment(.trailing)
                            .autocorrectionDisabled().textInputAutocapitalization(.characters)
                            .font(.system(size: 12, design: .monospaced))
                    }
                }
                Section(header: Text("Start step")) {
                    Picker("Step", selection: $startStep) {
                        Text("Step1 (grid)").tag(0)
                        Text("Step2s (SNR only)").tag(1)
                        Text("Step2 (R+SNR)").tag(2)
                        Text("Step3 (RR73)").tag(3)
                        Text("Step4 (73)").tag(4)
                    }
                }
                Section(header: Text("TX Period")) {
                    Picker("Period", selection: $txMode) {
                        Text("Even (0/30s)").tag("even")
                        Text("Odd (15/45s)").tag("odd")
                    }.pickerStyle(.segmented)
                }
            }
            .navigationTitle("Start QSO")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    HStack {
                        Button("Log") {
                            let m2parts = msg2.uppercased().components(separatedBy: .whitespaces)
                            let snrSent = m2parts.count >= 3 ? m2parts[2] : ""
                            onLog(QsoLogParams(dxCall: selectedDx, myCall: data.myCall,
                                               snrRcvd: data.snrFromMsg, snrSent: snrSent))
                            dismiss()
                        }
                        Button("Start TX") {
                            let initState: Int
                            switch startStep {
                            case 1: initState = 1
                            case 2: initState = 2
                            case 3: initState = 4
                            case 4: initState = 6
                            default: initState = 0
                            }
                            let first: String
                            switch startStep {
                            case 4: first = msg4
                            case 3: first = msg3
                            case 2: first = msg2
                            case 1: first = msgSnr
                            default: first = msg1
                            }
                            onStart(QsoStartResult(
                                dxCall: selectedDx, myCall: data.myCall, txMode: txMode,
                                firstMsg: first, msg1: msg1, msgSnr: msgSnr, msg2: msg2, msg3: msg3, msg4: msg4,
                                initialState: initState, dxFreqHz: data.dxFreqHz))
                            dismiss()
                        }
                        .bold()
                    }
                }
            }
        }
        .presentationDetents([.large])
    }
}

// MARK: - Spot Info Sheet

struct SpotInfoSheet: View {
    @Environment(\.dismiss) private var dismiss
    let item: Ft8Msg
    let potaMap: [String: [PotaSpot]]
    let sotaMap: [String: [SotaSpot]]
    let hunterStore: HunterStore

    private var call2base: String {
        let parts = item.msg.trimmingCharacters(in: .whitespaces).uppercased()
            .components(separatedBy: .whitespaces).filter { !$0.isEmpty }
            .map { ($0.hasPrefix("<") && $0.hasSuffix(">")) ? String($0.dropFirst().dropLast()) : $0 }
        let keywords = Set(["CQ", "DX", "RR73", "RRR", "73", "DE", "QRZ", "TU", "TNX"])
        let mid = parts.count >= 3 ? Array(parts.dropFirst().dropLast()) : Array(parts.dropFirst())
        return mid.first(where: { t in
            t.count >= 3 && t.count <= 13 && !keywords.contains(t) &&
            t.contains { $0.isNumber } && t.contains { $0.isLetter }
        }).map { $0.components(separatedBy: "/").first ?? $0 } ?? ""
    }

    private var potaSpots: [PotaSpot] { potaMap[call2base] ?? [] }
    private var sotaSpots: [SotaSpot] { sotaMap[call2base] ?? [] }

    var body: some View {
        NavigationStack {
            List {
                if !potaSpots.isEmpty {
                    Section("[POTA]") {
                        ForEach(potaSpots) { spot in
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 8) {
                                    Text(spot.activator).bold().font(.system(.body, design: .monospaced))
                                    Text(spot.reference).foregroundStyle(.secondary).font(.system(.body, design: .monospaced))
                                    if hunterStore.isHunted(spot.reference) {
                                        Text("P✓").foregroundStyle(.green).font(.caption.bold())
                                    }
                                }
                                if !spot.name.isEmpty {
                                    Text(spot.name).font(.caption).foregroundStyle(.secondary)
                                }
                                Text(String(format: "%.3f MHz  %@", spot.freqMhz, spot.mode))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 2)
                        }
                    }
                }
                if !sotaSpots.isEmpty {
                    Section("[SOTA]") {
                        ForEach(sotaSpots) { spot in
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 8) {
                                    if let cs = spot.activatorCallsign {
                                        Text(cs).bold().font(.system(.body, design: .monospaced))
                                    }
                                    if let code = spot.summitCode {
                                        Text(code).foregroundStyle(.secondary).font(.system(.body, design: .monospaced))
                                    }
                                }
                                if let details = spot.displayName, !details.isEmpty {
                                    Text(details).font(.caption).foregroundStyle(.secondary)
                                }
                                if let freq = spot.frequency, let mode = spot.mode {
                                    Text("\(freq) MHz  \(mode)").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .padding(.vertical, 2)
                        }
                    }
                }
                if potaSpots.isEmpty && sotaSpots.isEmpty {
                    Text("スポット情報なし").foregroundStyle(.secondary)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(item.msg)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("閉じる") { dismiss() } }
            }
        }
    }
}

// MARK: - Helpers

extension Color {
    init(hex: UInt32) {
        let r = Double((hex >> 16) & 0xFF) / 255
        let g = Double((hex >>  8) & 0xFF) / 255
        let b = Double( hex        & 0xFF) / 255
        self.init(red: r, green: g, blue: b)
    }
}

extension String {
    func matches(pattern: String) -> Bool {
        (try? NSRegularExpression(pattern: pattern))
            .map { $0.firstMatch(in: self, range: NSRange(self.startIndex..., in: self)) != nil } ?? false
    }
}
