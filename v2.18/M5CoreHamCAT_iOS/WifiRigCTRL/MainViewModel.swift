import Foundation
import Observation
import SwiftUI
import AVFoundation
import CoreLocation

// International Morse code table used for local sidetone generation in CW TX screen.
private let cwMorseTable: [Character: String] = [
    "A": ".-",   "B": "-...", "C": "-.-.", "D": "-..",
    "E": ".",    "F": "..-.", "G": "--.",  "H": "....",
    "I": "..",   "J": ".---", "K": "-.-", "L": ".-..",
    "M": "--",   "N": "-.",   "O": "---", "P": ".--.",
    "Q": "--.-", "R": ".-.",  "S": "...", "T": "-",
    "U": "..-",  "V": "...-", "W": ".--", "X": "-..-",
    "Y": "-.--", "Z": "--..",
    "0": "-----", "1": ".----", "2": "..---", "3": "...--",
    "4": "....-", "5": ".....", "6": "-....", "7": "--...",
    "8": "---..", "9": "----.",
    ".": ".-.-.-", ",": "--..--", "?": "..--..",
    "/": "-..-.",  "+": ".-.-.",  "=": "-...-",
    "!": "-.-.--", "@": ".--.-.", "'": ".----.",
    "(": "-.--.",  ")": "-.--.-", "&": ".-...",
    ";": "-.-.-.", ":": "---...", "-": "-....-",
]

enum AppRoute: Hashable {
    case rigSelect
    case mainControl
    case pttSettings
    case aprsSettings
    case ft8
    case profiles
    case bleKeyer
    case about
    case cw
    case admin
}

@MainActor
@Observable
final class MainViewModel {
    // Navigation
    var path: [AppRoute] = []

    // CW BLE keyer service — owned here so it survives navigation. Connects to DualKey-BLE
    // (AtomS3 BLE keyer) via Nordic UART Service and bridges packets to Pi over WiFi UDP.
    let cwBle = CwBleService()

    // Connection settings (persisted)
    var hostName: String = UserDefaults.standard.string(forKey: "hostName") ?? "raspberrypi.local"
    var apiPort: Int = UserDefaults.standard.integer(forKey: "apiPort").nonZeroOr(AppConstants.defaultApiPort)
    var audioPort: Int = UserDefaults.standard.integer(forKey: "audioPort").nonZeroOr(AppConstants.defaultAudioPort)
    var apiKey: String = UserDefaults.standard.string(forKey: "apiKey") ?? ""
    /// First-launch: derive from hostName so the default `raspberrypi.local` toggles mDNS on.
    var useMDNS: Bool = {
        if UserDefaults.standard.object(forKey: "useMDNS") != nil {
            return UserDefaults.standard.bool(forKey: "useMDNS")
        }
        let host = UserDefaults.standard.string(forKey: "hostName") ?? "raspberrypi.local"
        return host.hasSuffix(".local")
    }()

    // PTT routing
    var useWifiPTT: Bool = UserDefaults.standard.bool(forKey: "useWifiPTT")
    var pttHost: String = UserDefaults.standard.string(forKey: "pttHost") ?? ""
    var pttPort: Int = UserDefaults.standard.integer(forKey: "pttPort").nonZeroOr(AppConstants.defaultPttPort)

    // CI-V: RS-BA1 direct IC-705/IC-7300/IC-9700 WiFi (bypasses Pi FastAPI)
    var useCivMode: Bool = UserDefaults.standard.bool(forKey: "useCivMode")
    var civHost: String = UserDefaults.standard.string(forKey: "civHost") ?? ""
    var civUsername: String = UserDefaults.standard.string(forKey: "civUsername") ?? "user"
    var civPassword: String = UserDefaults.standard.string(forKey: "civPassword") ?? ""
    var civPort1: Int = UserDefaults.standard.integer(forKey: "civPort1").nonZeroOr(50001)
    var civPort2: Int = UserDefaults.standard.integer(forKey: "civPort2").nonZeroOr(50002)
    var civPort3: Int = UserDefaults.standard.integer(forKey: "civPort3").nonZeroOr(50003)
    var civAddressHex: String = UserDefaults.standard.string(forKey: "civAddressHex") ?? "A4"
    var civError: String = ""
    var civConnected: Bool = false
    var civConnecting: Bool = false
    var civConnectStep: String = ""

    // Discovery
    var rigList: [RigInfo] = []
    var catList: [String] = []
    var soundDeviceList: [SoundDevice] = []
    var supportedModes: [String] = []

    // Selection (persisted — restored on launch + reconciled after server discovery)
    var selectedRigIndex: Int = 0
    var selectedCatIndex: Int = 0
    var selectedBaudIndex: Int = {
        let v = UserDefaults.standard.object(forKey: "selectedBaudIndex") as? Int
        let idx = v ?? AppConstants.defaultBaudIndex
        // Clamp: a stale/imported value beyond the current array would crash the
        // direct subscripts in RigSelectView / openSelectedRig (black-screen symptom).
        return AppConstants.baudRates.indices.contains(idx) ? idx : AppConstants.defaultBaudIndex
    }()
    var selectedSamplingIndex: Int = {
        let v = UserDefaults.standard.object(forKey: "selectedSamplingIndex") as? Int
        let idx = v ?? AppConstants.defaultSamplingIndex
        return AppConstants.samplingRates.indices.contains(idx) ? idx : AppConstants.defaultSamplingIndex
    }()
    var selectedPttDevice: String = UserDefaults.standard.string(forKey: "selectedPttDevice") ?? "NONE"
    var selectedPttType: PttType = PttType(rawValue: UserDefaults.standard.string(forKey: "selectedPttType") ?? "RTS") ?? .rts
    var selectedAudioCapture: String = UserDefaults.standard.string(forKey: "selectedAudioCapture") ?? ""
    var selectedAudioPlayback: String = UserDefaults.standard.string(forKey: "selectedAudioPlayback") ?? ""

    /// Last-used rig id (stable across restarts, used to restore `selectedRigIndex` after server discovery).
    private var savedRigId: Int = UserDefaults.standard.integer(forKey: "savedRigId")
    /// Last-used CAT device path.
    private var savedCatDevice: String = UserDefaults.standard.string(forKey: "savedCatDevice") ?? ""

    // Connection state
    var isConnectedToRig: Bool = false
    var isBusy: Bool = false
    var errorMessage: String?

    // Rig status (live)
    var sharedFreq: Int64 = 0
    var sharedMode: String = ""
    var sharedSignal: Double = 0       // S0..S15
    var sharedTx: Bool = false
    var sharedPower: Double = 0
    var sharedWidth: Int = 0
    var sharedSQL: Double = 0
    var sharedBkIn: Bool = false

    // PTT/Audio toggles
    var txEnabled: Bool = false        // PTT armed?
    var spkEnabled: Bool = false
    var micGain: Float = UserDefaults.standard.float(forKey: "micGain").nonZeroOr(1.0)
    var sharedVolume: Double = 0.5     // not server-side; UI hint only for now

    // Editing step
    var selectedStepIndex: Int = 2  // 1 kHz

    // Display preferences (persisted)
    var keepScreenAwake: Bool = UserDefaults.standard.object(forKey: "keepScreenAwake") as? Bool ?? true

    // APRS state (persisted where indicated)
    var aprsEnabled: Bool = UserDefaults.standard.bool(forKey: "aprsEnabled")
    var aprsCallsign: String = UserDefaults.standard.string(forKey: "aprsCallsign") ?? ""
    var aprsSSID: Int = UserDefaults.standard.integer(forKey: "aprsSSID")
    var aprsPath: String = UserDefaults.standard.string(forKey: "aprsPath") ?? "WIDE1-1"
    var aprsDestination: String = UserDefaults.standard.string(forKey: "aprsDestination") ?? "APRS00"
    var aprsSymbol: String = UserDefaults.standard.string(forKey: "aprsSymbol") ?? "/["
    var aprsIntervalSec: Int = UserDefaults.standard.integer(forKey: "aprsIntervalSec").nonZeroOr(60)
    var aprsBaud: Int = UserDefaults.standard.integer(forKey: "aprsBaud").nonZeroOr(1200)
    var aprsTxFreq: Double = (UserDefaults.standard.object(forKey: "aprsTxFreq") as? Double) ?? 144.660
    var aprsUseGPS: Bool = UserDefaults.standard.bool(forKey: "aprsUseGPS")
    var aprsManualLat: Double = (UserDefaults.standard.object(forKey: "aprsManualLat") as? Double) ?? 0.0
    var aprsManualLon: Double = (UserDefaults.standard.object(forKey: "aprsManualLon") as? Double) ?? 0.0
    var aprsSoundDevice: String = UserDefaults.standard.string(forKey: "aprsSoundDevice") ?? ""
    var aprsComment: String = UserDefaults.standard.string(forKey: "aprsComment") ?? ""
    var aprsActive: Bool = false
    var aprsCurrentLat: Double = 0
    var aprsCurrentLon: Double = 0

    // FT8 settings (persisted)
    var ft8MyCall: String = UserDefaults.standard.string(forKey: "ft8MyCall") ?? ""
    var ft8MyGrid: String = UserDefaults.standard.string(forKey: "ft8MyGrid") ?? ""
    var ft8DxCall: String = UserDefaults.standard.string(forKey: "ft8DxCall") ?? ""
    var ft8LatencyMs: Int = UserDefaults.standard.integer(forKey: "ft8LatencyMs").nonZeroOr(4000)
    var ft8TxMode: String = UserDefaults.standard.string(forKey: "ft8TxMode") ?? "USB"
    var ft8LastFreq: Int64 = Int64(UserDefaults.standard.integer(forKey: "ft8LastFreq"))
    var ft8IsFt4: Bool = UserDefaults.standard.bool(forKey: "ft8IsFt4")
    var ft8QsoLog: String = UserDefaults.standard.string(forKey: "ft8QsoLog") ?? "[]"

    // CW settings (v2.02, persisted)
    var cwPort: String = UserDefaults.standard.string(forKey: "cwPort") ?? "ttyACM0"
    var cwDelayMs: Int = UserDefaults.standard.integer(forKey: "cwDelayMs")
    var cwFmDelayMs: Int = UserDefaults.standard.integer(forKey: "cwFmDelayMs")
    var cwSidetoneEnabled: Bool = (UserDefaults.standard.object(forKey: "cwSidetoneEnabled") as? Bool) ?? true
    var cwWpm: Int = UserDefaults.standard.integer(forKey: "cwWpm").nonZeroOr(20)
    var cwLastText: String = UserDefaults.standard.string(forKey: "cwLastText") ?? ""

    // CW TX operator screen state (persisted)
    var cwDxCall: String = UserDefaults.standard.string(forKey: "cwDxCall") ?? ""
    var cwRst: String = { let v = UserDefaults.standard.string(forKey: "cwRst") ?? ""; return v.isEmpty ? "599" : v }()
    var cwPota: String = UserDefaults.standard.string(forKey: "cwPota") ?? ""
    var cwJcc: String = UserDefaults.standard.string(forKey: "cwJcc") ?? ""
    var cwPttPoll: Bool = (UserDefaults.standard.object(forKey: "cwPttPoll") as? Bool) ?? false
    var cwCqRepeat: Int = max(1, min(3, UserDefaults.standard.integer(forKey: "cwCqRepeat").nonZeroOr(1)))
    var cwCqPota: Bool = (UserDefaults.standard.object(forKey: "cwCqPota") as? Bool) ?? false
    var cwCqJcc: Bool = (UserDefaults.standard.object(forKey: "cwCqJcc") as? Bool) ?? false
    var cwAnsGreeting: String = UserDefaults.standard.string(forKey: "cwAnsGreeting") ?? ""
    var cwAnsPota: Bool = (UserDefaults.standard.object(forKey: "cwAnsPota") as? Bool) ?? false
    var cwAnsJcc: Bool = (UserDefaults.standard.object(forKey: "cwAnsJcc") as? Bool) ?? false
    var cwQsl: String = UserDefaults.standard.string(forKey: "cwQsl") ?? ""
    var cwCqLoopCount: Int = UserDefaults.standard.integer(forKey: "cwCqLoopCount")  // 0=∞
    var cwCqLoopInterval: Int = UserDefaults.standard.integer(forKey: "cwCqLoopInterval").nonZeroOr(15)
    var cwRstRepeat: Int = max(1, min(3, UserDefaults.standard.integer(forKey: "cwRstRepeat").nonZeroOr(1)))
    var cwCqTabActive: Bool = true
    var cwCqRepeating: Bool = false
    var cwCqRepeatStatus: String = ""
    private var cwCqRepeatTask: Task<Void, Never>?
    /// Noise reduction level (0=Off, 1..5). Sent to rig via /radio/noise_reduction (Android-compatible).
    var noiseReductionLevel: Int = UserDefaults.standard.integer(forKey: "noiseReductionLevel")
    /// CW decode panel visibility on main control screen (mirrors Android `llCwDecoder`).
    var cwDecodeActive: Bool = false
    /// Live RX-decoded morse text buffer (last N chars).
    var cwRxDecodedText: String = ""
    /// Live TX-decoded morse text buffer (last N chars).
    var cwTxDecodedText: String = ""

    // Pre-FT8 rig state (restored on exit)
    var preFt8Freq: Int64 = 0
    var preFt8Mode: String = ""
    var preFt8Width: Int = 0

    private let api = RigApiService()
    private let ptt = PttService()
    private let audioRx = AudioRxService()
    private let audioTx = AudioTxService()
    nonisolated(unsafe) let civ = CivService()
    private let location = LocationService()
    private var cwDecoder = CwDecoder(sampleRate: 8000)
    private let cwDecodeMaxChars = 100
    private var pollTask: Task<Void, Never>?
    private var pttHeartbeatTask: Task<Void, Never>?
    private var aprsHeartbeatTask: Task<Void, Never>?
    private var aprsGpsTask: Task<Void, Never>?
    private var locationObserveTask: Task<Void, Never>?

    // FM-CW (Android-compatible): when rig is in FM mode, key state is converted to
    // PTT on/off instead of being forwarded to Pi cw_bridge. PTT-off is delayed for a
    // VOX tail so multi-character sentences don't drop the carrier.
    private var fmTxActive: Bool = false
    private var fmTailTask: Task<Void, Never>?
    private let fmVoxTailMs: UInt64 = 500_000_000   // 500 ms tail

    // TX CW decode (key state → characters)
    private var txKeyOnTime: Date?
    private var txLastOffTime: Date?
    private var txElements: String = ""
    private var txDitMs: Double = 60.0   // adaptive dit estimate, starts at ~20 WPM
    private var txCharFlushTask: Task<Void, Never>?
    /// Streams a 700 Hz sine wave to `POST /radio/audio_tx` so the rig has audio to
    /// modulate while PTT is keyed (matches Android's `cwBle.startCwAudioStream`).
    let cwAudioStream = CwAudioStream(toneHz: 700, sampleRate: 8000)

    init() {
        wireCwBleCallbacks()
    }

    /// Hook the BLE keyer's key-state callback. CW packets are forwarded inside
    /// `CwBleService.handleKey`; only the FM-CW (PTT) path needs MainViewModel.
    private func wireCwBleCallbacks() {
        // Share AudioRxService's engine with the sidetone so both run on one
        // AVAudioEngine, eliminating hardware output contention on iOS.
        cwBle.audioRx = audioRx
        // Surface AudioRx engine errors in the UI.
        audioRx.onError = { [weak self] msg in self?.errorMessage = msg }

        cwBle.onKeyStateChange = { [weak self] isOn, _ in
            guard let self else { return }
            Task { @MainActor in
                self.handleBleKeyForFmMode(isOn: isOn)
                self.decodeTxKey(isOn: isOn)
            }
        }

        // Android版connectCwBle()に合わせ: piHostはメインリグのhostNameを使用 (CW_UDP_PORT=8889は固定)
        cwBle.piHost = hostName
        cwBle.keeyerWpm = cwWpm

        // BLE接続時にpiHostとkeeyerWpmを最新値で上書きし、cw_bridge.pyを起動する (Android connectCwBle()と同様)
        cwBle.onConnectionStateChange = { [weak self] isConnected in
            guard let self, isConnected else { return }
            self.cwBle.piHost = self.hostName
            self.cwBle.keeyerWpm = self.cwWpm
            Task { await self.cwOpen() }
        }
    }

    /// Engaged only when `sharedMode` contains "FM". CW mode is handled entirely by
    /// CwBleService → Pi UDP forwarding.
    private func handleBleKeyForFmMode(isOn: Bool) {
        guard cwBle.currentMode.uppercased().contains("FM") else {
            // Mode changed mid-key: drop any pending tail so we don't leave PTT stuck on.
            fmTailTask?.cancel(); fmTailTask = nil
            cwAudioStream.keyOff()
            return
        }
        if isOn {
            fmTailTask?.cancel(); fmTailTask = nil
            // Ensure the audio TX stream is live, then start the tone immediately.
            if !cwAudioStream.isRunning {
                cwAudioStream.start(host: hostName, apiPort: apiPort, apiKey: apiKey)
            }
            cwAudioStream.keyOn()
            if !fmTxActive {
                fmTxActive = true
                txEnabled = true
                Task { try? await self.api.setPtt(state: true) }
            }
        } else {
            // Stop tone immediately; PTT stays up for VOX tail.
            cwAudioStream.keyOff()
            // VOX tail before releasing PTT.
            fmTailTask?.cancel()
            fmTailTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard let self else { return }
                if Task.isCancelled { return }
                try? await self.api.setPtt(state: false)
                self.fmTxActive = false
                self.txEnabled = false
                self.fmTailTask = nil
            }
        }
    }

    // MARK: - TX CW decode (key state → cwTxDecodedText)

    /// Called each time the BLE keyer reports a key state change.
    /// Measures element durations and gap durations to decode dit/dah sequences.
    private func decodeTxKey(isOn: Bool) {
        guard cwDecodeActive else { return }
        let now = Date()
        if isOn {
            txCharFlushTask?.cancel(); txCharFlushTask = nil
            if let offTime = txLastOffTime {
                let gapMs = now.timeIntervalSince(offTime) * 1000
                if gapMs >= txDitMs * 2.5 {
                    flushTxChar()
                    if gapMs >= txDitMs * 7 { appendTxText(" ") }
                }
            }
            txKeyOnTime = now
        } else {
            if let onTime = txKeyOnTime {
                let durMs = now.timeIntervalSince(onTime) * 1000
                if durMs < txDitMs * 1.8 {
                    txElements += "."
                    txDitMs = txDitMs * 0.95 + durMs * 0.05
                } else {
                    txElements += "-"
                    txDitMs = txDitMs * 0.95 + (durMs / 3.0) * 0.05
                }
                txKeyOnTime = nil
            }
            txLastOffTime = now
            let ditMs = txDitMs
            txCharFlushTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(ditMs * 3.0 * 1_000_000))
                guard !Task.isCancelled, let self else { return }
                self.flushTxChar()
                try? await Task.sleep(nanoseconds: UInt64(ditMs * 4.0 * 1_000_000))
                guard !Task.isCancelled else { return }
                self.appendTxText(" ")
            }
        }
    }

    private func flushTxChar() {
        guard !txElements.isEmpty else { return }
        let elems = txElements; txElements = ""
        if let char = CwDecoder.morseTable[elems] { appendTxText(String(char)) }
    }

    private func appendTxText(_ s: String) {
        cwTxDecodedText += s
        if cwTxDecodedText.count > cwDecodeMaxChars {
            cwTxDecodedText = String(cwTxDecodedText.suffix(cwDecodeMaxChars))
        }
    }

    private func resetTxDecoder() {
        txCharFlushTask?.cancel(); txCharFlushTask = nil
        txKeyOnTime = nil; txLastOffTime = nil
        txElements = ""; txDitMs = 60.0
    }

    // MARK: - Persistence

    func persistConnectionSettings() {
        let d = UserDefaults.standard
        d.set(hostName, forKey: "hostName")
        d.set(apiPort, forKey: "apiPort")
        d.set(audioPort, forKey: "audioPort")
        d.set(apiKey, forKey: "apiKey")
        d.set(useMDNS, forKey: "useMDNS")
        d.set(useWifiPTT, forKey: "useWifiPTT")
        d.set(pttHost, forKey: "pttHost")
        d.set(pttPort, forKey: "pttPort")
        d.set(micGain, forKey: "micGain")
        d.set(keepScreenAwake, forKey: "keepScreenAwake")
        d.set(useCivMode, forKey: "useCivMode")
        d.set(civHost, forKey: "civHost")
        d.set(civUsername, forKey: "civUsername")
        d.set(civPassword, forKey: "civPassword")
        d.set(civPort1, forKey: "civPort1")
        d.set(civPort2, forKey: "civPort2")
        d.set(civPort3, forKey: "civPort3")
        d.set(civAddressHex, forKey: "civAddressHex")
    }

    func persistAprsSettings() {
        let d = UserDefaults.standard
        d.set(aprsEnabled, forKey: "aprsEnabled")
        d.set(aprsCallsign, forKey: "aprsCallsign")
        d.set(aprsSSID, forKey: "aprsSSID")
        d.set(aprsPath, forKey: "aprsPath")
        d.set(aprsDestination, forKey: "aprsDestination")
        d.set(aprsSymbol, forKey: "aprsSymbol")
        d.set(aprsIntervalSec, forKey: "aprsIntervalSec")
        d.set(aprsBaud, forKey: "aprsBaud")
        d.set(aprsTxFreq, forKey: "aprsTxFreq")
        d.set(aprsUseGPS, forKey: "aprsUseGPS")
        d.set(aprsManualLat, forKey: "aprsManualLat")
        d.set(aprsManualLon, forKey: "aprsManualLon")
        d.set(aprsSoundDevice, forKey: "aprsSoundDevice")
        d.set(aprsComment, forKey: "aprsComment")
    }

    /// Persist CW settings (v2.02).
    func persistCwSettings() {
        let d = UserDefaults.standard
        d.set(cwPort, forKey: "cwPort")
        d.set(cwDelayMs, forKey: "cwDelayMs")
        d.set(cwFmDelayMs, forKey: "cwFmDelayMs")
        d.set(cwSidetoneEnabled, forKey: "cwSidetoneEnabled")
        d.set(cwWpm, forKey: "cwWpm")
        d.set(cwLastText, forKey: "cwLastText")
        d.set(cwDxCall, forKey: "cwDxCall")
        d.set(cwRst, forKey: "cwRst")
        d.set(cwPota, forKey: "cwPota")
        d.set(cwJcc, forKey: "cwJcc")
        d.set(cwPttPoll, forKey: "cwPttPoll")
        d.set(cwCqRepeat, forKey: "cwCqRepeat")
        d.set(cwCqPota, forKey: "cwCqPota")
        d.set(cwCqJcc, forKey: "cwCqJcc")
        d.set(cwAnsGreeting, forKey: "cwAnsGreeting")
        d.set(cwAnsPota, forKey: "cwAnsPota")
        d.set(cwAnsJcc, forKey: "cwAnsJcc")
        d.set(cwQsl, forKey: "cwQsl")
        d.set(cwCqLoopCount, forKey: "cwCqLoopCount")
        d.set(cwCqLoopInterval, forKey: "cwCqLoopInterval")
        d.set(cwRstRepeat, forKey: "cwRstRepeat")
    }

    func applyMDNSSuffix() {
        let h = hostName.trimmingCharacters(in: .whitespaces)
        if useMDNS, !h.isEmpty, !h.hasSuffix(".local") {
            hostName = h + ".local"
        } else if !useMDNS, h.hasSuffix(".local") {
            hostName = String(h.dropLast(".local".count))
        }
    }

    // MARK: - Connect / discover

    func connectToRasPi() async -> String? {
        let h = hostName.trimmingCharacters(in: .whitespaces)
        guard !h.isEmpty else { return NSLocalizedString("enter_host", comment: "") }
        isBusy = true
        defer { isBusy = false }
        await api.configure(host: h, apiPort: apiPort, apiKey: apiKey)
        do {
            let rigs = try await api.getRigs()
            let devices = try await api.getDevices()
            self.rigList = rigs
            self.catList = devices.serial
            self.soundDeviceList = devices.audio
            restoreSelectionsFromSaved()
            persistConnectionSettings()
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    /// Reconcile saved (id/name) selections against the freshly discovered lists.
    /// Falls back to index 0 when the previously-saved item is no longer present.
    private func restoreSelectionsFromSaved() {
        // Rig: match by stable id
        if let idx = rigList.firstIndex(where: { $0.id == savedRigId }) {
            selectedRigIndex = idx
        } else if selectedRigIndex >= rigList.count {
            selectedRigIndex = 0
        }
        // CAT device: match by path string
        if !savedCatDevice.isEmpty,
           let idx = catList.firstIndex(of: savedCatDevice) {
            selectedCatIndex = idx
        } else if selectedCatIndex >= catList.count {
            selectedCatIndex = 0
        }
        // Audio capture/playback: only clear if the server returned devices and the saved id is absent.
        // An empty soundDeviceList means the fetch hasn't completed or the Pi has no devices —
        // in that case keep the saved selection so it isn't accidentally erased.
        if !selectedAudioCapture.isEmpty,
           !soundDeviceList.isEmpty,
           !soundDeviceList.contains(where: { $0.id == selectedAudioCapture }) {
            selectedAudioCapture = ""
        }
        if !selectedAudioPlayback.isEmpty,
           !soundDeviceList.isEmpty,
           !soundDeviceList.contains(where: { $0.id == selectedAudioPlayback }) {
            selectedAudioPlayback = ""
        }
        // PTT device: only clear if catList is non-empty and the saved device is absent.
        if selectedPttDevice != "NONE",
           !catList.isEmpty,
           !catList.contains(selectedPttDevice) {
            selectedPttDevice = "NONE"
        }
    }

    /// Persist the rig/CAT/PTT/audio/sampling selections for next launch.
    func persistSelections() {
        let d = UserDefaults.standard
        if rigList.indices.contains(selectedRigIndex) {
            savedRigId = rigList[selectedRigIndex].id
            d.set(savedRigId, forKey: "savedRigId")
        }
        if catList.indices.contains(selectedCatIndex) {
            savedCatDevice = catList[selectedCatIndex]
            d.set(savedCatDevice, forKey: "savedCatDevice")
        }
        d.set(selectedBaudIndex, forKey: "selectedBaudIndex")
        d.set(selectedSamplingIndex, forKey: "selectedSamplingIndex")
        d.set(selectedPttDevice, forKey: "selectedPttDevice")
        d.set(selectedPttType.rawValue, forKey: "selectedPttType")
        d.set(selectedAudioCapture, forKey: "selectedAudioCapture")
        d.set(selectedAudioPlayback, forKey: "selectedAudioPlayback")
    }

    func openSelectedRig() async -> String? {
        guard !rigList.isEmpty else { return NSLocalizedString("no_rigs", comment: "") }
        if isDemoMode {
            // Demo mode: skip the real server handshake and go straight to Main Control.
            isConnectedToRig = true
            return nil
        }
        isBusy = true
        defer { isBusy = false }
        let rig = rigList[selectedRigIndex]
        let cat = catList.indices.contains(selectedCatIndex) ? catList[selectedCatIndex] : ""
        let baud = AppConstants.baudRates[selectedBaudIndex]
        let pttDev = selectedPttDevice == "NONE" ? "" : selectedPttDevice
        do {
            // Pi may need to close the existing CAT port first; retry once on failure.
            do {
                try await api.openRig(model: rig.id, cat: cat, baud: baud, ptt: pttDev, pttType: selectedPttType.rawValue)
            } catch {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                try await api.openRig(model: rig.id, cat: cat, baud: baud, ptt: pttDev, pttType: selectedPttType.rawValue)
            }
            let caps = (try? await api.getCaps()) ?? CapsResponse(modes: [])
            self.supportedModes = caps.modes
            self.isConnectedToRig = true
            if !selectedAudioCapture.isEmpty {
                // Use the same device for both capture (radio→PC) and playback (PC→radio).
                try? await api.setAudioDevice(capture: selectedAudioCapture, playback: selectedAudioCapture)
            }
            // FT8 uses its own capture-device endpoint so the audio_sub stream uses the
            // correct ALSA device independently of the main SPK/TX device.
            if !selectedAudioPlayback.isEmpty {
                try? await api.setAudioDeviceFt8(capture: selectedAudioPlayback)
            }
            persistSelections()
            startStatusPolling()
            if useWifiPTT, !pttHost.isEmpty {
                await ptt.connect(host: pttHost, port: pttPort)
            }
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    /// Convenience wrapper around `api.getServerVersion()` used by AboutView.
    func fetchServerVersion() async -> (apiVersion: String, rigctld: String) {
        if isDemoMode { return ("DEMO", "DEMO") }
        return await api.getServerVersion()
    }

    func disconnect() {
        stopStatusPolling()
        stopPttHeartbeat()
        cwAudioStream.stop()
        fmTailTask?.cancel(); fmTailTask = nil
        fmTxActive = false
        Task {
            await audioRx.stop()
            await audioTx.stop()
            await ptt.disconnect()
        }
        if civ.isConnected { Task.detached { [civ = self.civ] in civ.disconnect() } }
        civConnected = false
        isConnectedToRig = false
        spkEnabled = false
        txEnabled = false
    }

    // MARK: - CI-V (RS-BA1 direct WiFi to IC-705)

    /// Blocking CI-V connect. Call from a background Task.
    /// Returns nil on success, or a localized error string on failure.
    func civConnect() async -> String? {
        civConnecting = true
        civConnectStep = ""
        defer { civConnecting = false; civConnectStep = "" }
        // Yield to let SwiftUI render the connecting state before blocking work starts.
        await Task.yield()
        civError = ""
        persistConnectionSettings()
        let host = civHost.trimmingCharacters(in: .whitespaces)
        guard !host.isEmpty else { civError = String(localized: "CI-V ホスト未入力"); return civError }
        // Request mic permission before connecting so initAudioPlayer() can activate the
        // microphone hardware (and the iOS privacy indicator) when the engine starts.
        let micGranted = await ensureMicPermission()
        if !micGranted {
            civError = String(localized: "マイク権限が必要です — 設定 → WifiRigCTRL でマイクを許可してください")
            return civError
        }
        // Apply manual CI-V address if set (auto-detection during connect may override)
        let addrHex = civAddressHex.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if !addrHex.isEmpty, let addr = Int(addrHex, radix: 16), addr > 0 {
            civ.civAddress = addr
        }
        civ.onConnectStep = { [weak self] step in
            DispatchQueue.main.async { self?.civConnectStep = step }
        }
        let ok = await Task.detached(priority: .userInitiated) { [civ = self.civ, host,
            p1 = self.civPort1, p2 = self.civPort2, p3 = self.civPort3,
            u = self.civUsername, pw = self.civPassword] in
            civ.connect(host: host, port1: p1, port2: p2, port3: p3, username: u, password: pw)
        }.value
        civ.onConnectStep = nil
        if !ok {
            civError = civ.lastError.isEmpty ? String(localized: "CI-V 接続失敗") : civ.lastError
            return civError
        }
        // Sync back auto-detected CI-V address
        let detectedHex = String(civ.civAddress, radix: 16).uppercased()
        civAddressHex = detectedHex
        UserDefaults.standard.set(detectedHex, forKey: "civAddressHex")
        supportedModes = civ.getSupportedModes()
        civConnected = true
        isConnectedToRig = true
        startStatusPolling()
        // Keep the overlay visible until the first successful frequency poll.
        civConnectStep = String(localized: "周波数確認中…")
        let freqDeadline = Date().addingTimeInterval(30)
        while Date() < freqDeadline {
            if sharedFreq != 0 { break }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        return nil
    }

    /// Disconnect CI-V and stop polling.
    func civDisconnect() {
        stopStatusPolling()
        stopPttHeartbeat()
        civConnected = false
        isConnectedToRig = false
        spkEnabled = false
        txEnabled = false
        Task.detached { [civ = self.civ] in civ.disconnect() }
    }

    // MARK: - Status polling (200 ms)

    func startStatusPolling() {
        stopStatusPolling()
        pollTask = Task { [weak self] in
            while let self, !Task.isCancelled {
                await self.pollOnce()
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
    }

    func stopStatusPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    private var loggedFirstPollError: Bool = false

    private func pollOnce() async {
        // CI-V direct path (bypasses Pi FastAPI entirely)
        if useCivMode, civ.isConnected {
            let s = await Task.detached(priority: .userInitiated) { [civ = self.civ] in
                civ.pollStatus()
            }.value
            guard let s else {
                if !civ.isConnected { isConnectedToRig = false; stopStatusPolling() }
                return
            }
            self.sharedFreq = s.freq
            if let m = s.mode {
                if m.name != self.sharedMode { self.applyStepForMode(m.name) }
                self.sharedMode = m.name; self.sharedWidth = m.filterWidth; self.cwBle.currentMode = m.name
            }
            self.sharedSignal = normalizedSignal(Double(s.signal ?? 0))
            self.sharedTx = s.tx
            if let p = s.power { self.sharedPower = Double(p) }
            if let sq = s.sql { self.sharedSQL = Double(sq) }
            if let bk = s.bkIn { self.sharedBkIn = bk }
            loggedFirstPollError = false
            return
        }

        do {
            let s = try await api.getStatus()
            self.sharedFreq = s.freq
            if s.mode != self.sharedMode { self.applyStepForMode(s.mode) }
            self.sharedMode = s.mode
            self.cwBle.currentMode = s.mode  // keep FM-CW PTT path in sync with rig polling
            self.sharedSignal = normalizedSignal(s.signal)
            self.sharedTx = s.tx
            self.sharedPower = s.power
            self.sharedWidth = s.width
            self.sharedSQL = s.sql
            self.sharedBkIn = s.bk_in ?? false
            loggedFirstPollError = false
        } catch {
            // Log the very first poll error so the user can diagnose why freq doesn't update.
            if !loggedFirstPollError {
                loggedFirstPollError = true
                let msg = "/radio/status poll failed: \(error.localizedDescription)"
                print("[Poll] \(msg)")
                errorMessage = msg
            }
        }
    }

    /// Hamlib STRENGTH (dBm) → 0…15 S-unit scale.
    /// - S0 ≈ −54 dBm, S9 = 0 dBm, +6 dB per S-unit.
    /// - S9+30 dB ≈ 5 over → maps to 14, S9+60 dB → 15 (capped).
    /// Matches Android `(signal + 54) / 6` formula in MainViewModel.kt.
    private func normalizedSignal(_ raw: Double) -> Double {
        let s = (raw + 54.0) / 6.0
        return min(max(s, 0.0), 15.0)
    }

    // MARK: - Control

    private var isDemoMode: Bool {
        return demoMode
    }

    func setFreq(_ hz: Int64) async {
        sharedFreq = hz
        if isDemoMode { return }
        if useCivMode, civ.isConnected {
            _ = await Task.detached(priority: .userInitiated) { [civ = self.civ, hz] in civ.setFrequency(hz) }.value
            return
        }
        do { try await api.setFreq(hz: hz) } catch { errorMessage = error.localizedDescription }
    }

    func setMode(_ mode: String, width: Int) async {
        sharedMode = mode; sharedWidth = width
        cwBle.currentMode = mode  // keeps FM-CW PTT path live as soon as user switches mode
        applyStepForMode(mode)
        if isDemoMode { return }
        if useCivMode, civ.isConnected {
            _ = await Task.detached(priority: .userInitiated) { [civ = self.civ, mode, width] in civ.setMode(mode, width: width) }.value
            return
        }
        do { try await api.setMode(mode: mode, width: width) }
        catch { errorMessage = error.localizedDescription }
    }

    /// Apply the step preset for a given mode (saved user preference, or default).
    /// Call this whenever sharedMode changes — whether from the app UI or from polling.
    func applyStepForMode(_ mode: String) {
        let key = "step_\(mode.uppercased())"
        if let stored = UserDefaults.standard.object(forKey: key) as? Int,
           AppConstants.stepHz.indices.contains(stored) {
            selectedStepIndex = stored
        } else {
            selectedStepIndex = AppConstants.defaultStepIndex(forMode: mode)
        }
    }

    /// Remember the user's chosen step for the current mode (v2.02 mode-specific step memory).
    func rememberStepForCurrentMode() {
        if sharedMode.isEmpty { return }
        UserDefaults.standard.set(selectedStepIndex, forKey: "step_\(sharedMode.uppercased())")
    }

    func setSquelch(_ value: Double) async {
        sharedSQL = value
        if isDemoMode { return }
        if useCivMode, civ.isConnected {
            _ = await Task.detached(priority: .userInitiated) { [civ = self.civ, value] in civ.setSql(Float(value)) }.value
            return
        }
        do { try await api.setLevel(name: "SQL", value: value) }
        catch { errorMessage = error.localizedDescription }
    }

    func setVolume(_ value: Double) async {
        sharedVolume = value
        if isDemoMode { return }
        do { try await api.setLevel(name: "VOL", value: value) }
        catch { errorMessage = error.localizedDescription }
    }

    func setPower(_ value: Double) async {
        sharedPower = value
        if isDemoMode { return }
        if useCivMode, civ.isConnected {
            _ = await Task.detached(priority: .userInitiated) { [civ = self.civ, value] in civ.setRfPower(Float(value)) }.value
            return
        }
        do { try await api.setPower(value: value) }
        catch { errorMessage = error.localizedDescription }
    }

    func stepFreq(_ direction: Int) async {
        let step = AppConstants.stepHz[selectedStepIndex]
        let newFreq = max(0, sharedFreq + Int64(direction * step))
        await setFreq(newFreq)
    }

    // MARK: - PTT

    /// Requests microphone permission. Returns true if granted (or already granted).
    /// Must be called BEFORE touching `AVAudioEngine.inputNode` to avoid the synchronous
    /// permission dialog blocking the audio engine setup.
    private func ensureMicPermission() async -> Bool {
        if #available(iOS 17.0, *) {
            switch AVAudioApplication.shared.recordPermission {
            case .granted: return true
            case .denied: return false
            case .undetermined:
                return await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
                    AVAudioApplication.requestRecordPermission { granted in
                        cont.resume(returning: granted)
                    }
                }
            @unknown default: return false
            }
        } else {
            let session = AVAudioSession.sharedInstance()
            switch session.recordPermission {
            case .granted: return true
            case .denied: return false
            case .undetermined:
                return await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
                    session.requestRecordPermission { granted in cont.resume(returning: granted) }
                }
            @unknown default: return false
            }
        }
    }

    func setPtt(on: Bool) async {
        txEnabled = on
        sharedTx = on
        if isDemoMode { return }

        // CI-V direct path — TX audio is handled internally by CivService (UDP to IC-705)
        if useCivMode, civ.isConnected {
            if on {
                let granted = await ensureMicPermission()
                guard granted else {
                    errorMessage = "Microphone permission denied — enable it in Settings → WifiRigCTRL."
                    txEnabled = false; sharedTx = false; return
                }
            }
            _ = await Task.detached(priority: .userInitiated) { [civ = self.civ, on] in civ.setPtt(on) }.value
            return
        }

        // 1) Hamlib HTTP path
        do { try await api.setPtt(state: on) } catch { errorMessage = error.localizedDescription }
        // 2) WiFi UDP path to M5Atom (if enabled)
        if useWifiPTT {
            await ptt.sendPtt(on: on)
        }
        if on {
            startPttHeartbeat()
            // Mic permission pre-flight (avoids freeze when AVAudioEngine.inputNode
            // synchronously triggers the iOS permission dialog).
            let granted = await ensureMicPermission()
            guard granted else {
                errorMessage = "Microphone permission denied — enable it in Settings → WifiRigCTRL."
                return
            }
            // Start TX audio upload
            do { try await audioTx.start(host: hostName, port: apiPort, apiKey: apiKey) }
            catch { errorMessage = error.localizedDescription }
        } else {
            stopPttHeartbeat()
            await audioTx.stop()
        }
    }

    private func startPttHeartbeat() {
        stopPttHeartbeat()
        pttHeartbeatTask = Task { [weak self] in
            while let self, !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                try? await self.api.pttHeartbeat()
                if self.useWifiPTT { await self.ptt.sendPtt(on: true) }
            }
        }
    }

    private func stopPttHeartbeat() {
        pttHeartbeatTask?.cancel()
        pttHeartbeatTask = nil
    }

    // MARK: - SPK (audio RX)

    func setSpk(on: Bool) async {
        spkEnabled = on
        if useCivMode, civ.isConnected {
            civ.spkEnabled = on
            return
        }
        if on {
            let rate = AppConstants.samplingRates[selectedSamplingIndex]
            if rate > 0 {
                await audioRx.start(host: hostName, port: audioPort, sampleRate: rate, apiKey: apiKey)
            }
        } else {
            await audioRx.stop()
        }
    }

    func setMicGain(_ g: Float) {
        micGain = g
        audioTx.micGain = g
        civ.micGain = g
        UserDefaults.standard.set(g, forKey: "micGain")
    }

    // MARK: - APRS

    func buildAprsConfig() -> AprsConfig {
        let rigId: String
        if rigList.indices.contains(selectedRigIndex) {
            rigId = String(rigList[selectedRigIndex].id)
        } else {
            rigId = ""
        }
        let catDevice = catList.indices.contains(selectedCatIndex) ? catList[selectedCatIndex] : ""
        return AprsConfig(
            callsign: aprsCallsign,
            ssid: aprsSSID,
            path: aprsPath,
            interval: aprsIntervalSec,
            freq: aprsTxFreq,
            baud: aprsBaud,
            use_gps: aprsUseGPS,
            manual_lat: aprsManualLat,
            manual_lon: aprsManualLon,
            symbol: aprsSymbol,
            comment: aprsComment,
            destination: aprsDestination,
            sound_device: aprsSoundDevice,
            rig_id: rigId,
            cat_device: catDevice
        )
    }

    /// Saves APRS settings locally and POSTs full config to /aprs_config.
    /// Returns nil on success, or a localized error message on failure.
    func saveAndSendAprsConfig() async -> String? {
        persistAprsSettings()
        if isDemoMode { return nil }
        let cfg = buildAprsConfig()
        do {
            try await api.sendAprsConfig(cfg)
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    /// POSTs subset config to /aprs_start, then begins heartbeat (and GPS feed if requested).
    func startAprs() async -> String? {
        if isDemoMode {
            aprsActive = true
            // Simulate position drifting slowly for demo
            aprsCurrentLat = aprsManualLat == 0 ? 35.6586 : aprsManualLat
            aprsCurrentLon = aprsManualLon == 0 ? 139.7454 : aprsManualLon
            return nil
        }
        let cfg = buildAprsConfig()
        do {
            try await api.startAprs(cfg)
            aprsActive = true
            startAprsHeartbeat()
            if aprsUseGPS {
                startAprsGpsStreaming()
            }
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    func stopAprs() async {
        if isDemoMode {
            aprsActive = false
            return
        }
        stopAprsHeartbeat()
        stopAprsGpsStreaming()
        do { try await api.stopAprs() } catch { /* ignore */ }
        aprsActive = false
    }

    /// In demo mode, skip the actual API call but persist locally.
    /// Note: saveAndSendAprsConfig uses this internally via isDemoMode check.

    private func startAprsHeartbeat() {
        stopAprsHeartbeat()
        aprsHeartbeatTask = Task { [weak self] in
            while let self, !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                try? await self.api.aprsHeartbeat()
            }
        }
    }

    private func stopAprsHeartbeat() {
        aprsHeartbeatTask?.cancel()
        aprsHeartbeatTask = nil
    }

    private func startAprsGpsStreaming() {
        stopAprsGpsStreaming()
        location.start()
        locationObserveTask = Task { [weak self] in
            guard let self else { return }
            for await loc in self.location.updates {
                if Task.isCancelled { return }
                self.aprsCurrentLat = loc.coordinate.latitude
                self.aprsCurrentLon = loc.coordinate.longitude
            }
        }
        aprsGpsTask = Task { [weak self] in
            while let self, !Task.isCancelled {
                if self.aprsCurrentLat != 0 || self.aprsCurrentLon != 0 {
                    try? await self.api.sendGps(lat: self.aprsCurrentLat, lon: self.aprsCurrentLon)
                }
                try? await Task.sleep(nanoseconds: 10_000_000_000)
            }
        }
    }

    private func stopAprsGpsStreaming() {
        aprsGpsTask?.cancel()
        aprsGpsTask = nil
        locationObserveTask?.cancel()
        locationObserveTask = nil
        location.stop()
    }

    func requestLocationAuthorization() {
        location.requestAuthorization()
    }

    var isLocationAuthorized: Bool { location.isAuthorized }

    // MARK: - FT8

    func persistFt8Settings() {
        let d = UserDefaults.standard
        d.set(ft8MyCall, forKey: "ft8MyCall")
        d.set(ft8MyGrid, forKey: "ft8MyGrid")
        d.set(ft8LatencyMs, forKey: "ft8LatencyMs")
        d.set(ft8TxMode, forKey: "ft8TxMode")
        d.set(Int(ft8LastFreq), forKey: "ft8LastFreq")
    }

    /// Stash current rig freq/mode/width so we can restore them on exiting FT8.
    func captureFt8RestorePoint() {
        preFt8Freq = sharedFreq
        preFt8Mode = sharedMode
        preFt8Width = sharedWidth
    }

    /// Switch radio to FT8 TX mode (USB or PKTUSB) and prior FT8 freq if any.
    func enterFt8Mode() async {
        do {
            try await api.setMode(mode: ft8TxMode, width: 3000)
            if ft8LastFreq > 0 { try await api.setFreq(hz: ft8LastFreq) }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Restore previous mode/freq when leaving FT8.
    func exitFt8Mode() async {
        if sharedFreq > 0 { ft8LastFreq = sharedFreq }
        persistFt8Settings()
        do {
            if !preFt8Mode.isEmpty { try await api.setMode(mode: preFt8Mode, width: preFt8Width) }
            if preFt8Freq > 0 { try await api.setFreq(hz: preFt8Freq) }
        } catch {
            errorMessage = error.localizedDescription
        }
        if preFt8Freq > 0 { sharedFreq = preFt8Freq }
        if !preFt8Mode.isEmpty { sharedMode = preFt8Mode }
    }

    func setFt8TxMode(_ m: String) async {
        ft8TxMode = m
        persistFt8Settings()
        do { try await api.setMode(mode: m, width: 3000) }
        catch { errorMessage = error.localizedDescription }
    }

    func updateFt8LatencyMs(_ ms: Int) {
        ft8LatencyMs = max(0, min(10000, ms))
        persistFt8Settings()
    }

    /// Server-side FT8/FT4 TX (v2.02). Composes a basic CQ message if dxCall is empty.
    func ft8ServerTx() async -> String? {
        let call = ft8MyCall.uppercased()
        let grid = ft8MyGrid.uppercased()
        let dx = ft8DxCall.uppercased()
        let msg: String
        if dx.isEmpty {
            msg = "CQ \(call) \(String(grid.prefix(4)))"
        } else {
            msg = "\(dx) \(call) \(String(grid.prefix(4)))"
        }
        if isDemoMode { return nil }
        do {
            try await api.ft8Tx(msg: msg, audioFreqHz: 1500, rate: 12000, isFt4: ft8IsFt4)
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    /// Convert lat/lon to 6-character Maidenhead grid locator.
    nonisolated static func latLonToGrid(lat: Double, lon: Double) -> String {
        guard lat.isFinite, lon.isFinite else { return "" }
        let adjLon = max(0.0, min(lon + 180.0, 360.0))
        let adjLat = max(0.0, min(lat + 90.0, 180.0))
        let scalar = UnicodeScalar("A").value
        let zeroAscii = UnicodeScalar("0").value
        func chr(_ n: Int, base: UInt32) -> Character { Character(UnicodeScalar(base + UInt32(n))!) }
        let f1 = Int(adjLon / 20.0)
        let f2 = Int(adjLat / 10.0)
        let s1 = Int(adjLon.truncatingRemainder(dividingBy: 20) / 2)
        let s2 = Int(adjLat.truncatingRemainder(dividingBy: 10))
        let sub1 = Int(adjLon.truncatingRemainder(dividingBy: 2) / 2.0 * 24.0)
        let sub2 = Int(adjLat.truncatingRemainder(dividingBy: 1) * 24.0)
        return String([
            chr(f1, base: scalar),
            chr(f2, base: scalar),
            chr(s1, base: zeroAscii),
            chr(s2, base: zeroAscii),
            chr(sub1, base: scalar),
            chr(sub2, base: scalar)
        ])
    }

    /// Get a one-shot location fix for grid locator. Returns nil if no fix.
    func currentLocationFix() -> (lat: Double, lon: Double)? {
        guard let l = location.latest else { return nil }
        return (l.coordinate.latitude, l.coordinate.longitude)
    }

    /// Read-only access for FT8 view to compose its WebView URL/headers and JS.
    var ft8Host: String { hostName }
    var ft8ApiKey: String { apiKey }
    var ft8ApiPort: Int { apiPort }

    // MARK: - Profiles

    var profiles: [ProfileConfig] = ProfileStore.loadAll()

    /// Capture current connection-related state into a ProfileConfig.
    func snapshotCurrentProfile(name: String) -> ProfileConfig {
        ProfileConfig(
            name: name,
            hostName: hostName,
            apiPort: apiPort,
            audioPort: audioPort,
            useMDNS: useMDNS,
            apiKey: apiKey,
            savedRigId: rigList.indices.contains(selectedRigIndex) ? rigList[selectedRigIndex].id : -1,
            savedCat: catList.indices.contains(selectedCatIndex) ? catList[selectedCatIndex] : "",
            savedPttDevice: selectedPttDevice,
            savedPttType: selectedPttType.rawValue,
            savedBaudIndex: selectedBaudIndex,
            savedSamplingIndex: selectedSamplingIndex,
            useWifiPTT: useWifiPTT,
            pttHost: pttHost,
            pttPort: pttPort,
            alsaDevice: selectedAudioCapture,
            alsaDeviceFt8: selectedAudioPlayback
        )
    }

    func saveProfile(name: String) {
        var p = snapshotCurrentProfile(name: name)
        if let idx = profiles.firstIndex(where: { $0.name == name }) {
            p.id = profiles[idx].id
            profiles[idx] = p
        } else {
            profiles.append(p)
        }
        ProfileStore.saveAll(profiles)
        ProfileStore.activeId = p.id
    }

    func deleteProfile(_ id: UUID) {
        profiles.removeAll(where: { $0.id == id })
        ProfileStore.saveAll(profiles)
        if ProfileStore.activeId == id { ProfileStore.activeId = nil }
    }

    func loadProfile(_ p: ProfileConfig) {
        hostName = p.hostName
        apiPort = p.apiPort
        audioPort = p.audioPort
        useMDNS = p.useMDNS
        apiKey = p.apiKey
        selectedPttDevice = p.savedPttDevice
        selectedPttType = PttType(rawValue: p.savedPttType) ?? .rts
        // Clamp profile-saved indices to the current arrays — an out-of-range value
        // (older app build / imported profile) otherwise crashes the direct subscripts
        // in RigSelectView / openSelectedRig, which surfaces as a stuck black screen.
        selectedBaudIndex = AppConstants.baudRates.indices.contains(p.savedBaudIndex)
            ? p.savedBaudIndex : AppConstants.defaultBaudIndex
        selectedSamplingIndex = AppConstants.samplingRates.indices.contains(p.savedSamplingIndex)
            ? p.savedSamplingIndex : AppConstants.defaultSamplingIndex
        useWifiPTT = p.useWifiPTT
        pttHost = p.pttHost
        pttPort = p.pttPort
        selectedAudioCapture = p.alsaDevice
        selectedAudioPlayback = p.alsaDeviceFt8
        ProfileStore.activeId = p.id
        persistConnectionSettings()
    }

    // MARK: - CW (v2.02 server-side bridge)

    var cwBridgeStatus: CwStatus?
    var cwMorseSending: Bool = false
    private var cwSidetoneTask: Task<Void, Never>?
    /// Single in-flight CW send/poll task. Cancelled before starting a new one
    /// (prevents the "endless send" symptom from a stuck or duplicated polling loop).
    private var cwTextTask: Task<Void, Never>?

    func cwRefreshStatus() async {
        if isDemoMode {
            cwBridgeStatus = CwStatus(connected: true, synced: true, offset_ms: 12, max_late_ms: 3)
            return
        }
        cwBridgeStatus = try? await api.cwStatus()
    }

    func cwOpen() async -> String? {
        persistCwSettings()
        if isDemoMode { return nil }
        do { try await api.cwOpen(port: cwPort, delayMs: cwDelayMs); return nil }
        catch { return error.localizedDescription }
    }

    func cwClose() async {
        if isDemoMode { return }
        try? await api.cwClose()
    }

    func cwKey(_ on: Bool) async {
        if isDemoMode { return }
        try? await api.cwKey(on)
    }

    /// Normalize full-width ASCII (`！..～`) and ideographic space (`　`) into half-width.
    /// Mirrors the v2.02 Android `sendCwText` normalization step.
    private func normalizeCwText(_ s: String) -> String {
        String(s.map { c -> Character in
            let scalar = c.unicodeScalars.first!.value
            if scalar >= 0xFF01 && scalar <= 0xFF5E {  // ！..～ → !..~
                return Character(UnicodeScalar(scalar - 0xFEE0)!)
            }
            if scalar == 0x3000 { return " " }  // U+3000 ideographic space
            return c
        })
    }

    func cwSendMorse() async -> String? {
        let raw = cwLastText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return "Empty text" }
        let text = normalizeCwText(raw)
        persistCwSettings()

        // Cancel any previous in-flight CW send job to guarantee a single active poll loop.
        cwTextTask?.cancel()
        cwTextTask = nil

        if isDemoMode {
            cwMorseSending = true
            startLocalCwSidetone(text: text, wpm: cwWpm)
            cwTextTask = Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                self?.cwMorseSending = false
                self?.stopLocalCwSidetone()
            }
            return nil
        }

        // CI-V direct path: IC-705内蔵キーヤーまたはPTT-per-elementで送信。
        // mode チェックは省略: sharedModeが未取得でもCI-V接続中ならCI-Vパスを使う。
        if useCivMode, civ.isConnected {
            return await civCwSendMorse(text: text)
        }

        // Pause status polling while CW transmits — matches Android v2.02 setPoll(false) pattern.
        try? await api.setPoll(state: false)

        do {
            // Start local sidetone in parallel with sending text to Pi.
            // Android方式: サイドトーンはWifi_Rig_CWとは独立してiOS側タイミングで生成。
            startLocalCwSidetone(text: text, wpm: cwWpm)
            try await api.cwSendMorse(text: text, wpm: cwWpm)
            // CQ repeatでSTOPが押されると呼び元タスクがキャンセルされる。
            // awaitから戻った後にキャンセル済みなら cwTextTask を作らず即終了。
            if Task.isCancelled {
                stopLocalCwSidetone()
                try? await api.cwStopMorse()
                try? await api.setPoll(state: true)
                return nil
            }
            cwMorseSending = true
        } catch {
            stopLocalCwSidetone()
            try? await api.setPoll(state: true)
            return error.localizedDescription
        }

        cwTextTask = Task { @MainActor [weak self] in
            guard let self else { return }
            while !Task.isCancelled, self.cwMorseSending {
                try? await Task.sleep(nanoseconds: 500_000_000)
                let stillSending = (try? await self.api.cwMorseStatus()) ?? false
                if !stillSending { break }
            }
            // Always run cleanup (v2.02: stop morse + PTT off + re-enable polling).
            self.cwMorseSending = false
            // Do NOT stop sidetone here — let it self-terminate so the last character
            // is not cut off. stopLocalCwSidetone() is called by cwStopMorse() on
            // manual stop, and by startLocalCwSidetone() when the next send begins.
            try? await self.api.cwStopMorse()
            try? await self.api.setPtt(state: false)
            try? await self.api.setPoll(state: true)
            self.cwTextTask = nil
        }
        return nil
    }

    // CI-V CW送信: BK-IN ONなら内蔵キーヤー、OFFならPTT-per-element (Android 2.15同等)
    // getBkIn() のみ blocking socket I/O のため Task.detached; UDP送信メソッドは直接呼び出し。
    private func civCwSendMorse(text: String) async -> String? {
        cwMorseSending = true
        startLocalCwSidetone(text: text, wpm: cwWpm)

        let ditMs = 1200.0 / Double(max(5, min(60, cwWpm)))
        let isBkIn = await Task.detached(priority: .userInitiated) { [civ = self.civ] in
            civ.getBkIn() ?? false
        }.value

        if isBkIn {
            // IC-705内蔵キーヤーでCW送信 (BK-IN ON)
            civ.setKeySpeed(cwWpm)
            try? await Task.sleep(nanoseconds: 50_000_000)

            // テキストを30文字以下のチャンクに分割して順番に送信
            var chunks: [String] = []
            var buf = ""
            for ch in text.uppercased() {
                buf.append(ch)
                if buf.count >= 30 { chunks.append(buf); buf = "" }
            }
            if !buf.isEmpty { chunks.append(buf) }

            for chunk in chunks {
                if Task.isCancelled { break }
                civ.sendCwMessage(chunk)
                let units = civMorseDurationUnits(chunk)
                let durNs = UInt64(Double(units) * ditMs * 1_000_000) + 300_000_000
                try? await Task.sleep(nanoseconds: durNs)
            }
            civ.stopCwMessage()
        } else {
            // PTT-per-element送信 (BK-IN OFF): 各符号要素ごとにPTT制御
            let seq = cwMorseSequence(for: text.uppercased(), unitMs: ditMs)
            for (isOn, durMs) in seq {
                if Task.isCancelled { break }
                if isOn { civ.civPttDown() } else { civ.civPttUp() }
                try? await Task.sleep(nanoseconds: UInt64(max(1, durMs) * 1_000_000))
            }
            civ.civPttUp()
        }

        cwMorseSending = false
        stopLocalCwSidetone()
        return nil
    }

    // テキスト中の全符号要素のdit単位数合計を返す (チャンク待機時間計算用)
    private func civMorseDurationUnits(_ text: String) -> Int {
        var units = 0
        let words = text.uppercased().split(separator: " ", omittingEmptySubsequences: true)
        for (wi, word) in words.enumerated() {
            if wi > 0 { units += 7 }  // inter-word gap (7 dits)
            var charsDone = 0
            for ch in word {
                guard let code = cwMorseTable[ch] else { continue }
                if charsDone > 0 { units += 3 }  // inter-char gap
                for (ei, e) in code.enumerated() {
                    if ei > 0 { units += 1 }
                    units += (e == ".") ? 1 : 3
                }
                charsDone += 1
            }
        }
        return units
    }

    /// Toggle CW break-in on the rig.
    func setBkIn(on: Bool) async {
        sharedBkIn = on
        if isDemoMode { return }
        if useCivMode, civ.isConnected {
            _ = await Task.detached(priority: .userInitiated) { [civ = self.civ, on] in civ.setBkIn(on) }.value
            return
        }
        do { try await api.setBkIn(on: on) }
        catch { errorMessage = "BK-IN: \(error.localizedDescription)" }
    }

    /// Persist current NR level and apply to rig via Hamlib FastAPI endpoint.
    /// `0` = Off, `1..5` = NR strength.
    func applyNoiseReduction() async {
        UserDefaults.standard.set(noiseReductionLevel, forKey: "noiseReductionLevel")
        if isDemoMode { return }
        do { try await api.setNrLevel(noiseReductionLevel) }
        catch { errorMessage = "NR: \(error.localizedDescription)" }
    }

    /// Cycle NR Off → 1 → 2 → 3 → 4 → 5 → Off. Mirrors Android `cycleNoiseReduction()`.
    func cycleNoiseReduction() async {
        noiseReductionLevel = (noiseReductionLevel + 1) % 6
        await applyNoiseReduction()
    }

    /// Set NR level directly from button grid.
    func setNoiseReduction(_ level: Int) async {
        noiseReductionLevel = max(0, min(5, level))
        await applyNoiseReduction()
    }

    /// Toggle CW decode panel + wire/unwire the audio→decoder pipeline.
    func toggleCwDecode() async {
        cwDecodeActive.toggle()
        if cwDecodeActive {
            // Use the actual streaming sample rate so timing parameters are correct.
            // If SPK is off (rate=0), fall back to 8000 Hz.
            let rate = AppConstants.samplingRates[selectedSamplingIndex]
            cwDecoder = CwDecoder(sampleRate: rate > 0 ? rate : 8000)
            cwRxDecodedText = ""
            cwTxDecodedText = ""
            resetTxDecoder()
            cwDecoder.onCharDecoded = { [weak self] char, channel in
                guard channel == 0 else { return }
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    self.cwRxDecodedText += String(char)
                    if self.cwRxDecodedText.count > self.cwDecodeMaxChars {
                        self.cwRxDecodedText = String(self.cwRxDecodedText.suffix(self.cwDecodeMaxChars))
                    }
                }
            }
            audioRx.onSamples = { [weak self] samples in
                self?.cwDecoder.processSamples(samples)
            }
        } else {
            audioRx.onSamples = nil
            cwDecoder.onCharDecoded = nil
            cwDecoder.reset()
            resetTxDecoder()
        }
    }

    func cwStopMorse() async {
        // Cancel local polling first so the cleanup branch doesn't double-fire.
        cwTextTask?.cancel()
        cwTextTask = nil
        cwMorseSending = false
        stopLocalCwSidetone()
        if isDemoMode { return }
        if useCivMode, civ.isConnected {
            civ.stopCwMessage()
            civ.civPttUp()
            return
        }
        // v2.02 cleanup triplet — guarantees Pi-side stops keying and resumes polling.
        try? await api.cwStopMorse()
        try? await api.setPtt(state: false)
        try? await api.setPoll(state: true)
    }

    // MARK: - Local sidetone for CW TX text (Android方式: Pi CWとは独立したタイミング管理)

    private func startLocalCwSidetone(text: String, wpm: Int) {
        cwSidetoneTask?.cancel()
        guard cwBle.sidetoneEnabled else { return }
        // Lazy-start sidetone engine if not yet initialized (BLE keyer not connected)
        cwBle.startSidetoneLazy()
        let unitMs = 1200.0 / Double(max(1, wpm))
        let seq = cwMorseSequence(for: text.uppercased(), unitMs: unitMs)
        guard !seq.isEmpty else { return }
        let cwBleRef = cwBle
        // Task.detached: バックグラウンドスレッドで絶対時刻ベースのタイミング管理。
        // @MainActor + 相対sleep は UIレンダリングに競合してジッタが生じるため、
        // nonisolated の setSidetoneKeyRealtime() を直接呼んで符号精度を確保する。
        // 20ms先読みバッファにより処理落ち時も正しく発音できる。
        cwSidetoneTask = Task.detached(priority: .userInteractive) {
            let lookaheadNs: UInt64 = 20_000_000  // 20ms lookahead buffer
            var targetNs = DispatchTime.now().uptimeNanoseconds + lookaheadNs
            for (isOn, durMs) in seq {
                if Task.isCancelled { break }
                let now = DispatchTime.now().uptimeNanoseconds
                if targetNs > now {
                    try? await Task.sleep(nanoseconds: targetNs - now)
                }
                // Re-check after sleep: cancellation swallowed by try? must not
                // let a key-ON write slip through after stopLocalCwSidetone() ran.
                if Task.isCancelled { break }
                cwBleRef.setSidetoneKeyRealtime(isOn)
                targetNs += UInt64(max(1, durMs) * 1_000_000)
            }
            // Wait until the scheduled end of the last element before turning key off.
            // Without this sleep the final dash/dot key-ON is immediately followed by key-OFF
            // (loop exits without sleeping), causing the last character to produce no sound.
            if !Task.isCancelled {
                let now = DispatchTime.now().uptimeNanoseconds
                if targetNs > now {
                    try? await Task.sleep(nanoseconds: targetNs - now)
                }
            }
            // Always turn key off — covers both normal completion and cancellation.
            cwBleRef.setSidetoneKeyRealtime(false)
        }
    }

    private func stopLocalCwSidetone() {
        cwSidetoneTask?.cancel()
        cwSidetoneTask = nil
        cwBle.setSidetoneKey(false)
    }

    private func cwMorseSequence(for text: String, unitMs: Double) -> [(Bool, Double)] {
        var result: [(Bool, Double)] = []
        let words = text.split(separator: " ", omittingEmptySubsequences: true)
        for (wi, word) in words.enumerated() {
            if wi > 0 { result.append((false, unitMs * 7)) }
            var charsDone = 0
            for ch in word {
                guard let code = cwMorseTable[ch] else { continue }
                if charsDone > 0 { result.append((false, unitMs * 3)) }
                for (ei, e) in code.enumerated() {
                    if ei > 0 { result.append((false, unitMs)) }
                    result.append((true, e == "." ? unitMs : unitMs * 3))
                }
                charsDone += 1
            }
        }
        return result
    }

    // MARK: - CW TX helper (direct text send from CW screen)

    func cwSendText(_ text: String) async {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !cwMorseSending else { return }
        cwLastText = t
        _ = await cwSendMorse()
    }

    func cwStartCqRepeat(_ text: String) {
        guard !cwCqRepeating else { cwStopCqRepeat(); return }
        cwCqRepeating = true
        let count = cwCqLoopCount
        let intervalSec = cwCqLoopInterval
        cwCqRepeatTask = Task { @MainActor [weak self] in
            guard let self else { return }
            var i = 0
            while self.cwCqRepeating && (count == 0 || i < count) {
                let n = i + 1
                let cntStr = count == 0 ? "∞" : String(count)
                self.cwCqRepeatStatus = "\(n)/\(cntStr)"
                await self.cwSendText(text)
                guard self.cwCqRepeating else { break }
                for sec in stride(from: intervalSec, through: 1, by: -1) {
                    guard self.cwCqRepeating else { break }
                    self.cwCqRepeatStatus = String(localized: "待機 \(sec)s…")
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
                i += 1
            }
            self.cwCqRepeating = false
            self.cwCqRepeatStatus = ""
        }
    }

    func cwStopCqRepeat() {
        cwCqRepeating = false
        cwCqRepeatTask?.cancel(); cwCqRepeatTask = nil
        cwCqRepeatStatus = ""
        Task { await self.cwStopMorse() }
    }

    // MARK: - Admin (v2.02 firmware/setup script upload)

    var adminSetupLog: String = ""
    var adminSetupRunning: Bool = false

    func adminUploadApi(_ data: Data) async -> String? {
        if isDemoMode { return nil }
        do { try await api.sendApiUpdate(data); return nil }
        catch { return error.localizedDescription }
    }

    func adminUploadCwBridge(_ data: Data) async -> String? {
        if isDemoMode { return nil }
        do { try await api.sendCwBridgeUpdate(data); return nil }
        catch { return error.localizedDescription }
    }

    func adminUploadSetupScript(_ data: Data) async -> String? {
        if isDemoMode { return nil }
        do { try await api.sendSetupScript(data); return nil }
        catch { return error.localizedDescription }
    }

    func adminPollSetupLog() async {
        if isDemoMode {
            adminSetupRunning = true
            adminSetupLog = "[DEMO] sample setup log\n  step 1: ok\n  step 2: ok\n"
            return
        }
        if let (running, log) = try? await api.getSetupLog() {
            adminSetupRunning = running
            adminSetupLog = log
        }
    }

    /// Stop local audio RX (used when entering FT8 so the WebView gets exclusive access).
    func audioRxStopIfRunning() async {
        if spkEnabled {
            spkEnabled = false
            await audioRx.stop()
        }
    }

    /// Demo mode flag — when true, server calls are skipped and signal value is simulated.
    var demoMode: Bool = false
    private var demoTickTask: Task<Void, Never>?

    /// Populate UI state with mock data so the next screens render without a real server.
    func loadMockData() {
        demoMode = true
        rigList = [
            RigInfo(id: 1035, name: "Yaesu FT-991"),
            RigInfo(id: 122, name: "Icom IC-7300"),
            RigInfo(id: 2, name: "Dummy (no rig)")
        ]
        catList = ["/dev/ttyUSB0", "/dev/ttyACM0"]
        soundDeviceList = [
            SoundDevice(id: "hw:0,0", label: "USB Audio CODEC"),
            SoundDevice(id: "default", label: "Default Output")
        ]
        supportedModes = ["USB", "LSB", "CW", "AM", "FM", "PKTUSB", "PKTLSB"]
        selectedRigIndex = 0
        selectedCatIndex = 0
        sharedFreq = 14_250_000
        sharedMode = "USB"
        sharedSignal = 6.5
        sharedTx = false
        sharedPower = 0.5
        sharedWidth = 2400
        sharedSQL = 0.2
        sharedBkIn = false
        isConnectedToRig = true
        aprsCallsign = "JI1ORE"
        aprsSSID = 9
        startDemoTicker()
    }

    private func startDemoTicker() {
        demoTickTask?.cancel()
        demoTickTask = Task { [weak self] in
            while let self, !Task.isCancelled, self.demoMode {
                try? await Task.sleep(nanoseconds: 800_000_000)
                // Wave-shaped S-meter wandering 2..10
                let phase = Date().timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 8.0)
                let s = 6.0 + 4.0 * sin(phase / 8.0 * 2.0 * .pi)
                self.sharedSignal = s
            }
        }
    }
}

private extension Int {
    func nonZeroOr(_ fallback: Int) -> Int { self == 0 ? fallback : self }
}

private extension Float {
    func nonZeroOr(_ fallback: Float) -> Float { self == 0 ? fallback : self }
}
