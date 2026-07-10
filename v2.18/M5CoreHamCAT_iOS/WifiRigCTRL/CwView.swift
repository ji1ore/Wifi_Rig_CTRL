import SwiftUI

struct CwView: View {
    @Bindable var vm: MainViewModel
    @State private var isEditingFreq = false
    @State private var freqInputText = ""
    @FocusState private var freqFieldFocused: Bool
    @State private var dxCallText = ""
    @State private var rstText = ""
    @State private var potaText = ""
    @State private var jccText = ""
    @State private var freeText = ""
    @FocusState private var freeTextFocused: Bool

    var body: some View {
        GeometryReader { geo in
            if geo.size.width > geo.size.height {
                landscapeView
            } else {
                portraitView
            }
        }
        .navigationTitle("CW TX")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            dxCallText = vm.cwDxCall
            rstText = vm.cwRst
            potaText = vm.cwPota
            jccText = vm.cwJcc
            freeText = vm.cwLastText
        }
        .onDisappear { vm.persistCwSettings() }
    }

    private var portraitView: some View {
        ScrollView {
            VStack(spacing: 6) {
                wpmRow
                switchesRow
                nrRow
                freqRow
                if vm.cwDecodeActive { decodeRows }
                contactSection
                tabBar
                if vm.cwCqTabActive { cqPanel() } else { ansPanel() }
                freeTextRow
                if vm.cwMorseSending || vm.cwCqRepeating { stopRow }
            }
            .padding(8)
        }
    }

    private var landscapeView: some View {
        HStack(alignment: .top, spacing: 0) {
            ScrollView {
                VStack(spacing: 6) {
                    wpmRow
                    switchesRow
                    nrRow
                    freqRow
                    if vm.cwDecodeActive { decodeRows }
                    contactSection
                }
                .padding(8)
            }
            .frame(maxWidth: .infinity)
            Divider()
            ScrollView {
                VStack(spacing: 6) {
                    tabBar
                    if vm.cwCqTabActive { cqPanel(compact: true) } else { ansPanel(compact: true) }
                    freeTextRow
                    if vm.cwMorseSending || vm.cwCqRepeating { stopRow }
                }
                .padding(8)
            }
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - WPM

    @ViewBuilder private var wpmRow: some View {
        HStack(spacing: 8) {
            Text("CW TX").font(.headline).foregroundStyle(.white)
            Spacer()
            Text("\(vm.cwWpm) WPM")
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 4)
        .padding(.bottom, 2)
        Slider(value: Binding(
            get: { Double(vm.cwWpm) },
            set: { v in
                let w = max(5, min(60, Int(v.rounded())))
                vm.cwWpm = w
                vm.cwBle.sendWpm(w)
            }
        ), in: 5...60, step: 1)
        .tint(.indigo)
        Text("* CWモード時はリグ側のWPM設定が優先されます")
            .font(.caption2)
            .foregroundStyle(.secondary)
    }

    // MARK: - Switches

    private var switchesRow: some View {
        HStack(spacing: 8) {
            Toggle(isOn: Binding(
                get: { vm.cwBle.paddleSwapped },
                set: { vm.cwBle.paddleSwapped = $0 }
            )) {
                Text("Swap L↔R").font(.caption).foregroundStyle(.secondary)
            }
            .tint(.orange)
            Toggle(isOn: $vm.cwPttPoll) {
                Text("PTT poll").font(.caption).foregroundStyle(.secondary)
            }
            .tint(.indigo)
            Spacer()
            Button {
                Task { await vm.setSpk(on: !vm.spkEnabled) }
            } label: {
                HStack(spacing: 3) {
                    Image(systemName: vm.spkEnabled ? "speaker.wave.2.fill" : "speaker.fill").font(.caption2)
                    Text(vm.spkEnabled ? "MONI" : "MONI")
                        .font(.caption2.bold())
                }
                .padding(.horizontal, 6).padding(.vertical, 4)
                .background(
                    vm.spkEnabled ? Color.blue.opacity(0.7) : Color.gray.opacity(0.25),
                    in: RoundedRectangle(cornerRadius: 5)
                )
                .foregroundStyle(vm.spkEnabled ? .white : .secondary)
            }
            .buttonStyle(.plain)
            Button {
                Task { await vm.toggleCwDecode() }
            } label: {
                HStack(spacing: 3) {
                    Image(systemName: "text.bubble.fill").font(.caption2)
                    Text(vm.cwDecodeActive ? "DEC ON" : "DEC")
                        .font(.caption2.bold())
                }
                .padding(.horizontal, 6).padding(.vertical, 4)
                .background(
                    vm.cwDecodeActive ? Color.yellow.opacity(0.9) : Color.gray.opacity(0.25),
                    in: RoundedRectangle(cornerRadius: 5)
                )
                .foregroundStyle(vm.cwDecodeActive ? .black : .secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 4)
    }

    // MARK: - NR

    private var nrRow: some View {
        HStack(spacing: 0) {
            Text("NR:").font(.caption).foregroundStyle(.secondary).frame(width: 28, alignment: .leading)
            ForEach(["OFF","1","2","3","4","5"].indices, id: \.self) { i in
                let active = vm.noiseReductionLevel == i
                Button(["OFF","1","2","3","4","5"][i]) {
                    Task { await vm.setNoiseReduction(i) }
                }
                .font(.caption.bold())
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
                .background(active ? Color.purple : Color(white: 0.2), in: RoundedRectangle(cornerRadius: 4))
                .foregroundStyle(active ? .white : .secondary)
                .padding(.leading, i == 0 ? 0 : 2)
            }
        }
    }

    // MARK: - Frequency

    private var freqRow: some View {
        HStack(spacing: 4) {
            Button("◀") { Task { await vm.setFreq(vm.sharedFreq - 500) } }
                .buttonStyle(CwCompactButtonStyle(color: Color(white: 0.2)))
                .simultaneousGesture(LongPressGesture(minimumDuration: 0.4).onEnded { _ in
                    Task { await vm.setFreq(vm.sharedFreq - 5000) }
                })
            if isEditingFreq {
                TextField("MHz", text: $freqInputText)
                    .font(.system(.body, design: .monospaced).bold())
                    .foregroundStyle(Color(red: 0, green: 0.9, blue: 1))
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 4))
                    .focused($freqFieldFocused)
                    .toolbar {
                        ToolbarItemGroup(placement: .keyboard) {
                            Spacer()
                            Button("Cancel") { isEditingFreq = false; freqFieldFocused = false }
                            Button("OK") { applyFreqInput() }
                        }
                    }
            } else {
                Text(vm.sharedFreq > 0 ? String(format: "%.5f", Double(vm.sharedFreq) / 1_000_000.0) : "-.---00")
                    .font(.system(.body, design: .monospaced).bold())
                    .foregroundStyle(Color(red: 0, green: 0.9, blue: 1))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .background(Color(white: 0.13), in: RoundedRectangle(cornerRadius: 4))
                    .onTapGesture {
                        freqInputText = vm.sharedFreq > 0
                            ? String(format: "%.5f", Double(vm.sharedFreq) / 1_000_000.0) : ""
                        isEditingFreq = true
                        freqFieldFocused = true
                    }
            }
            Button("▶") { Task { await vm.setFreq(vm.sharedFreq + 500) } }
                .buttonStyle(CwCompactButtonStyle(color: Color(white: 0.2)))
                .simultaneousGesture(LongPressGesture(minimumDuration: 0.4).onEnded { _ in
                    Task { await vm.setFreq(vm.sharedFreq + 5000) }
                })
        }
    }

    private func applyFreqInput() {
        let normalized = freqInputText.replacingOccurrences(of: ",", with: ".")
        if let mhz = Double(normalized), mhz > 0.1, mhz < 3000 {
            let hz = Int64((mhz * 1_000_000).rounded())
            Task { await vm.setFreq(hz) }
        }
        isEditingFreq = false
        freqFieldFocused = false
    }

    // MARK: - Decode RX/TX

    @ViewBuilder private var decodeRows: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            Text("RX: \(vm.cwRxDecodedText)")
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(Color(red: 0.46, green: 1.0, blue: 0.01))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(height: 20)
        ScrollView(.horizontal, showsIndicators: false) {
            Text(vm.cwTxDecodedText.isEmpty ? "" : "TX: \(vm.cwTxDecodedText)")
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(Color(red: 0.31, green: 0.76, blue: 0.97))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(height: 20)
    }

    // MARK: - Contact fields

    private var contactSection: some View {
        VStack(spacing: 4) {
            // MY callsign
            let myCall = vm.ft8MyCall.isEmpty ? vm.aprsCallsign : vm.ft8MyCall
            HStack {
                Text("MY:").font(.caption).foregroundStyle(.secondary)
                Text(myCall.isEmpty ? String(localized: "(未設定)") : myCall)
                    .font(.system(.subheadline, design: .monospaced).bold())
                    .foregroundStyle(Color(red: 0, green: 0.9, blue: 1))
                if myCall.isEmpty {
                    Text("← Connect画面で設定").font(.caption2).foregroundStyle(.secondary)
                }
                Spacer()
            }

            // DX row
            HStack(spacing: 4) {
                Text("DX:").font(.caption).foregroundStyle(.secondary).frame(width: 24)
                TextField("JA2ABC", text: $dxCallText)
                    .font(.system(.body, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)
                    .onChange(of: dxCallText) { _, v in vm.cwDxCall = v }
                    .padding(.horizontal, 6).padding(.vertical, 4)
                    .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 4))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                Button("×") { dxCallText = ""; vm.cwDxCall = "" }
                    .buttonStyle(CwCompactButtonStyle(color: Color(white: 0.27)))
            }
            // RST row
            HStack(spacing: 4) {
                Text("RST:").font(.caption).foregroundStyle(.secondary)
                TextField("599", text: $rstText)
                    .font(.system(.body, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)
                    .keyboardType(.asciiCapable)
                    .onChange(of: rstText) { _, v in vm.cwRst = v }
                    .padding(.horizontal, 6).padding(.vertical, 4)
                    .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 4))
                    .foregroundStyle(.white)
                    .frame(width: 52)
                Button("×") { rstText = ""; vm.cwRst = "" }.buttonStyle(CwCompactButtonStyle(color: Color(white: 0.27)))
                Button("5NN") { rstText = "5NN"; vm.cwRst = "5NN" }.buttonStyle(CwCompactButtonStyle(color: Color(white: 0.2)))
                ForEach(1...3, id: \.self) { n in
                    Button("\(n)×") { vm.cwRstRepeat = n }
                        .buttonStyle(CwCompactButtonStyle(
                            color: vm.cwRstRepeat == n ? Color(red: 0.1, green: 0.36, blue: 0.19) : Color(white: 0.2)
                        ))
                }
                Spacer()
            }

            // POTA + JCC
            HStack(spacing: 4) {
                Text("POTA:").font(.caption).foregroundStyle(.secondary)
                TextField("JP-XXXX", text: $potaText)
                    .font(.system(.body, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)
                    .onChange(of: potaText) { _, v in vm.cwPota = v }
                    .padding(.horizontal, 6).padding(.vertical, 4)
                    .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 4))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                Button("×") { potaText = ""; vm.cwPota = "" }.buttonStyle(CwCompactButtonStyle(color: Color(white: 0.27)))

                Text("JCC:").font(.caption).foregroundStyle(.secondary)
                TextField("1234", text: $jccText)
                    .font(.system(.body, design: .monospaced))
                    .keyboardType(.numberPad)
                    .onChange(of: jccText) { _, v in vm.cwJcc = v }
                    .padding(.horizontal, 6).padding(.vertical, 4)
                    .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 4))
                    .foregroundStyle(.white)
                    .frame(width: 64)
                Button("×") { jccText = ""; vm.cwJcc = "" }.buttonStyle(CwCompactButtonStyle(color: Color(white: 0.27)))
            }
        }
        .padding(6)
        .background(Color(white: 0.11), in: RoundedRectangle(cornerRadius: 8))
    }

    // MARK: - Tab bar

    private var tabBar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                Button("CQ") { vm.cwCqTabActive = true }
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Color(white: 0.13))
                    .foregroundStyle(vm.cwCqTabActive ? .white : .secondary)
                Button("ANS") { vm.cwCqTabActive = false }
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Color(white: 0.13))
                    .foregroundStyle(!vm.cwCqTabActive ? .white : .secondary)
            }
            HStack(spacing: 0) {
                Rectangle()
                    .fill(vm.cwCqTabActive ? Color.blue : Color(white: 0.2))
                    .frame(maxWidth: .infinity, maxHeight: 3)
                Rectangle()
                    .fill(!vm.cwCqTabActive ? Color.blue : Color(white: 0.2))
                    .frame(maxWidth: .infinity, maxHeight: 3)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    // MARK: - CQ Panel

    private func cqPanel(compact: Bool = false) -> some View {
        let myCall = (vm.ft8MyCall.isEmpty ? vm.aprsCallsign : vm.ft8MyCall).uppercased()
        let dx = vm.cwDxCall.trimmingCharacters(in: .whitespaces).uppercased()
        let rst = vm.cwRst.trimmingCharacters(in: .whitespaces).ifEmpty("599")
        let pota = vm.cwPota.trimmingCharacters(in: .whitespaces).uppercased()
        let jcc  = vm.cwJcc.trimmingCharacters(in: .whitespaces)
        let myOk = !myCall.isEmpty; let dOk = !dx.isEmpty
        let pOk  = !pota.isEmpty;   let jOk  = !jcc.isEmpty
        let mDisp = myOk ? myCall : "○○"
        let dDisp = dOk  ? dx     : "△△"
        let callPart = (1...vm.cwCqRepeat).map { _ in mDisp }.joined(separator: " ")
        let cqExtra  = (vm.cwCqPota && pOk ? " POTA \(pota)" : "") + (vm.cwCqJcc && jOk ? " JCC \(jcc)" : "")
        let cqText   = "CQ CQ CQ DE \(callPart)\(cqExtra) K"
        let grPfx    = vm.cwAnsGreeting.isEmpty ? "" : "\(vm.cwAnsGreeting) "
        let rstPart  = (1...vm.cwRstRepeat).map { _ in rst }.joined(separator: " ")
        let urExtra  = (vm.cwCqPota && pOk ? " POTA \(pota)" : "") + (vm.cwCqJcc && jOk ? " JCC \(jcc)" : "")
        let qslSufx  = vm.cwQsl.isEmpty ? "" : " \(vm.cwQsl)"
        let urText   = "\(grPfx)UR \(rstPart)\(urExtra)\(qslSufx) BK"

        return VStack(spacing: compact ? 2 : 4) {
            // Call× 1/2/3 + POTA/JCC toggles
            HStack(spacing: 4) {
                Text("Call×").font(.caption2).foregroundStyle(.secondary)
                ForEach(1...3, id: \.self) { n in
                    Button("\(n)") { vm.cwCqRepeat = n }
                        .buttonStyle(CwCompactButtonStyle(
                            color: vm.cwCqRepeat == n ? Color(red: 0.1, green: 0.36, blue: 0.19) : Color(white: 0.2)
                        ))
                }
                Spacer()
                Button("POTA") { vm.cwCqPota.toggle() }
                    .buttonStyle(CwCompactButtonStyle(
                        color: vm.cwCqPota ? Color(red: 0.36, green: 0.23, blue: 0.06) : Color(white: 0.2)
                    ))
                Button("JCC") { vm.cwCqJcc.toggle() }
                    .buttonStyle(CwCompactButtonStyle(
                        color: vm.cwCqJcc ? Color(red: 0.1, green: 0.23, blue: 0.36) : Color(white: 0.2)
                    ))
            }

            // CQ button
            Button(cqText) { Task { await vm.cwSendText(cqText) } }
                .buttonStyle(CwBigButtonStyle(color: Color(red: 0.1, green: 0.23, blue: 0.36),
                                               enabled: myOk && !vm.cwMorseSending, compact: compact))

            // DX K | DX DE MY K
            HStack(spacing: 4) {
                Button("\(dDisp) K") { Task { await vm.cwSendText("\(dDisp) K") } }
                    .buttonStyle(CwBigButtonStyle(color: Color(red: 0.1, green: 0.23, blue: 0.36),
                                                   enabled: dOk && !vm.cwMorseSending, compact: compact))
                Button("\(dDisp) DE \(mDisp) K") { Task { await vm.cwSendText("\(dDisp) DE \(mDisp) K") } }
                    .buttonStyle(CwBigButtonStyle(color: Color(red: 0.1, green: 0.23, blue: 0.36),
                                                   enabled: myOk && dOk && !vm.cwMorseSending, compact: compact))
                    .frame(maxWidth: .infinity)
            }

            // Greeting toggles
            HStack(spacing: 4) {
                ForEach(["--", "GM", "GE", "GA"], id: \.self) { g in
                    let val = g == "--" ? "" : g
                    Button(g) { vm.cwAnsGreeting = val }
                        .buttonStyle(CwCompactButtonStyle(
                            color: vm.cwAnsGreeting == val ? Color(red: 0.1, green: 0.36, blue: 0.19) : Color(white: 0.2)
                        ))
                }
                Spacer()
            }

            // QSL toggles
            HStack(spacing: 4) {
                Text("QSL").font(.caption2).foregroundStyle(.secondary)
                ForEach(["--", "PSE QSL", "NO QSL"], id: \.self) { q in
                    let val = q == "--" ? "" : q
                    Button(q) { vm.cwQsl = val }
                        .buttonStyle(CwCompactButtonStyle(
                            color: vm.cwQsl == val ? Color(red: 0.36, green: 0.23, blue: 0.06) : Color(white: 0.2)
                        ))
                }
            }

            // UR RST BK
            Button(urText) { Task { await vm.cwSendText(urText) } }
                .buttonStyle(CwBigButtonStyle(color: Color(red: 0.1, green: 0.23, blue: 0.36),
                                               enabled: !vm.cwMorseSending, compact: compact))

            // TU 73 EE | AGN
            HStack(spacing: 4) {
                Button("TU TU 73 E E") { Task { await vm.cwSendText("TU TU 73 E E") } }
                    .buttonStyle(CwBigButtonStyle(color: Color(red: 0.1, green: 0.23, blue: 0.36),
                                                   enabled: !vm.cwMorseSending, compact: compact))
                    .frame(maxWidth: .infinity)
                Button("AGN") { Task { await vm.cwSendText("AGN") } }
                    .buttonStyle(CwBigButtonStyle(color: Color(red: 0.1, green: 0.23, blue: 0.36),
                                                   enabled: !vm.cwMorseSending, compact: compact))
            }

            Divider().background(Color(white: 0.2))

            // Loop count + interval
            HStack(spacing: 4) {
                Text("Cnt").font(.caption2).foregroundStyle(.secondary)
                ForEach([3, 5, 10, 0], id: \.self) { n in
                    Button(n == 0 ? "∞" : "\(n)×") { vm.cwCqLoopCount = n }
                        .buttonStyle(CwCompactButtonStyle(
                            color: vm.cwCqLoopCount == n ? Color(red: 0.1, green: 0.36, blue: 0.19) : Color(white: 0.2),
                            minWidth: 28, hPad: 5
                        ))
                }
                Rectangle().fill(Color(white: 0.27)).frame(width: 1, height: 20).padding(.horizontal, 2)
                Text("Int").font(.caption2).foregroundStyle(.secondary)
                ForEach([10, 15, 30, 60], id: \.self) { s in
                    Button("\(s)s") { vm.cwCqLoopInterval = s }
                        .buttonStyle(CwCompactButtonStyle(
                            color: vm.cwCqLoopInterval == s ? Color(red: 0.1, green: 0.23, blue: 0.36) : Color(white: 0.2),
                            minWidth: 28, hPad: 5
                        ))
                }
            }

            // CQ REPEAT button + status
            HStack {
                Text(vm.cwCqRepeatStatus).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button(vm.cwCqRepeating ? "■ STOP REPEAT" : "▶ CQ REPEAT") {
                    if vm.cwCqRepeating { vm.cwStopCqRepeat() }
                    else { vm.cwStartCqRepeat(cqText) }
                }
                .font(compact ? .caption.bold() : .callout.bold())
                .padding(.horizontal, compact ? 8 : 12).padding(.vertical, compact ? 6 : 8)
                .background(vm.cwCqRepeating ? Color(red: 0.48, green: 0.1, blue: 0.1)
                                             : Color(red: 0.1, green: 0.36, blue: 0.17),
                            in: RoundedRectangle(cornerRadius: 6))
                .foregroundStyle(.white)
            }
        }
        .padding(6)
        .background(Color(white: 0.11), in: RoundedRectangle(cornerRadius: 8))
    }

    // MARK: - ANS Panel

    private func ansPanel(compact: Bool = false) -> some View {
        let myCall = (vm.ft8MyCall.isEmpty ? vm.aprsCallsign : vm.ft8MyCall).uppercased()
        let dx = vm.cwDxCall.trimmingCharacters(in: .whitespaces).uppercased()
        let rst = vm.cwRst.trimmingCharacters(in: .whitespaces).ifEmpty("599")
        let pota = vm.cwPota.trimmingCharacters(in: .whitespaces).uppercased()
        let jcc  = vm.cwJcc.trimmingCharacters(in: .whitespaces)
        let myOk = !myCall.isEmpty; let dOk = !dx.isEmpty
        let pOk  = !pota.isEmpty;   let jOk  = !jcc.isEmpty
        let mDisp = myOk ? myCall : "○○"
        let dDisp = dOk  ? dx     : "△△"
        let grPfx   = vm.cwAnsGreeting.isEmpty ? "" : "\(vm.cwAnsGreeting) "
        let rstPart = (1...vm.cwRstRepeat).map { _ in rst }.joined(separator: " ")
        let urExtra = (vm.cwAnsPota && pOk ? " POTA \(pota)" : "") + (vm.cwAnsJcc && jOk ? " JCC \(jcc)" : "")
        let qslSufx = vm.cwQsl.isEmpty ? "" : " \(vm.cwQsl)"
        let urText  = "\(grPfx)UR \(rstPart)\(urExtra)\(qslSufx) BK"

        return VStack(spacing: compact ? 2 : 4) {
            // POTA/JCC toggles
            HStack(spacing: 4) {
                Spacer()
                Button("POTA") { vm.cwAnsPota.toggle() }
                    .buttonStyle(CwCompactButtonStyle(
                        color: vm.cwAnsPota ? Color(red: 0.36, green: 0.23, blue: 0.06) : Color(white: 0.2)
                    ))
                Button("JCC") { vm.cwAnsJcc.toggle() }
                    .buttonStyle(CwCompactButtonStyle(
                        color: vm.cwAnsJcc ? Color(red: 0.1, green: 0.23, blue: 0.36) : Color(white: 0.2)
                    ))
            }

            // MY K | DX DE MY K
            HStack(spacing: 4) {
                Button("\(mDisp) K") { Task { await vm.cwSendText("\(mDisp) K") } }
                    .buttonStyle(CwBigButtonStyle(color: Color(red: 0.17, green: 0.1, blue: 0.36),
                                                   enabled: myOk && !vm.cwMorseSending, compact: compact))
                Button("\(dDisp) DE \(mDisp) K") { Task { await vm.cwSendText("\(dDisp) DE \(mDisp) K") } }
                    .buttonStyle(CwBigButtonStyle(color: Color(red: 0.17, green: 0.1, blue: 0.36),
                                                   enabled: myOk && dOk && !vm.cwMorseSending, compact: compact))
                    .frame(maxWidth: .infinity)
            }

            // Greeting toggles
            HStack(spacing: 4) {
                ForEach(["--", "GM", "GE", "GA"], id: \.self) { g in
                    let val = g == "--" ? "" : g
                    Button(g) { vm.cwAnsGreeting = val }
                        .buttonStyle(CwCompactButtonStyle(
                            color: vm.cwAnsGreeting == val ? Color(red: 0.1, green: 0.36, blue: 0.19) : Color(white: 0.2)
                        ))
                }
                Spacer()
            }

            // QSL toggles
            HStack(spacing: 4) {
                Text("QSL").font(.caption2).foregroundStyle(.secondary)
                ForEach(["--", "PSE QSL", "NO QSL"], id: \.self) { q in
                    let val = q == "--" ? "" : q
                    Button(q) { vm.cwQsl = val }
                        .buttonStyle(CwCompactButtonStyle(
                            color: vm.cwQsl == val ? Color(red: 0.36, green: 0.23, blue: 0.06) : Color(white: 0.2)
                        ))
                }
            }

            // UR RST BK
            Button(urText) { Task { await vm.cwSendText(urText) } }
                .buttonStyle(CwBigButtonStyle(color: Color(red: 0.17, green: 0.1, blue: 0.36),
                                               enabled: !vm.cwMorseSending, compact: compact))

            // TU TU 73 E E | AGN
            HStack(spacing: 4) {
                Button("TU TU 73 E E") { Task { await vm.cwSendText("TU TU 73 E E") } }
                    .buttonStyle(CwBigButtonStyle(color: Color(red: 0.17, green: 0.1, blue: 0.36),
                                                   enabled: !vm.cwMorseSending, compact: compact))
                    .frame(maxWidth: .infinity)
                Button("AGN") { Task { await vm.cwSendText("AGN") } }
                    .buttonStyle(CwBigButtonStyle(color: Color(red: 0.17, green: 0.1, blue: 0.36),
                                                   enabled: !vm.cwMorseSending, compact: compact))
            }
        }
        .padding(6)
        .background(Color(white: 0.11), in: RoundedRectangle(cornerRadius: 8))
    }

    // MARK: - Free text + SEND

    private var freeTextRow: some View {
        HStack(spacing: 6) {
            TextField("Free text", text: $freeText)
                .font(.system(.body, design: .monospaced))
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
                .focused($freeTextFocused)
                .onChange(of: freeText) { _, v in vm.cwLastText = v }
                .padding(.horizontal, 8).padding(.vertical, 10)
                .background(Color(white: 0.27), in: RoundedRectangle(cornerRadius: 6))
                .foregroundStyle(.white)
            Button("SEND") { Task { await vm.cwSendText(freeText) } }
                .font(.callout.bold())
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Color(red: 0.1, green: 0.36, blue: 0.17), in: RoundedRectangle(cornerRadius: 6))
                .foregroundStyle(.white)
                .disabled(vm.cwMorseSending)
        }
    }

    // MARK: - STOP

    private var stopRow: some View {
        Button("■  STOP") {
            if vm.cwCqRepeating { vm.cwStopCqRepeat() }
            else { Task { await vm.cwStopMorse() } }
        }
        .font(.headline)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .background(Color(red: 0.48, green: 0.1, blue: 0.1), in: RoundedRectangle(cornerRadius: 8))
        .foregroundStyle(.white)
    }
}

// MARK: - Button styles

private struct CwCompactButtonStyle: ButtonStyle {
    var color: Color
    var minWidth: CGFloat = 36
    var hPad: CGFloat = 8
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.caption.bold())
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .padding(.horizontal, hPad)
            .padding(.vertical, 6)
            .frame(minWidth: minWidth)
            .background(color, in: RoundedRectangle(cornerRadius: 4))
            .foregroundStyle(Color.white.opacity(configuration.isPressed ? 0.6 : 1))
    }
}

private struct CwBigButtonStyle: ButtonStyle {
    var color: Color
    var enabled: Bool = true
    var compact: Bool = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(compact ? .caption.bold() : .callout.bold())
            .lineLimit(1)
            .minimumScaleFactor(compact ? 0.65 : 0.8)
            .frame(maxWidth: .infinity)
            .padding(.vertical, compact ? 6 : 12)
            .background(color, in: RoundedRectangle(cornerRadius: 6))
            .foregroundStyle(.white.opacity(enabled ? 1 : 0.35))
            .opacity(enabled ? 1 : 0.5)
    }
}

// MARK: - Helpers

private extension String {
    func ifEmpty(_ fallback: String) -> String { isEmpty ? fallback : self }
}
