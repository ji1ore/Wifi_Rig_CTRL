import Testing
import Foundation
@testable import WifiRigCTRL

// MARK: - Grid Locator

struct GridLocatorTests {
    @Test func tokyoCoordinates() {
        // Tokyo Tower: 35.6586 N, 139.7454 E
        let grid = MainViewModel.latLonToGrid(lat: 35.6586, lon: 139.7454)
        #expect(grid == "PM95UP")
    }

    @Test func londonCoordinates() {
        // Greenwich Observatory: 51.4769 N, 0.0005 W
        let grid = MainViewModel.latLonToGrid(lat: 51.4769, lon: -0.0005)
        #expect(grid.hasPrefix("IO91"))
    }

    @Test func nullIsland() {
        // 0,0 — Maidenhead starts at JJ00aa
        let grid = MainViewModel.latLonToGrid(lat: 0, lon: 0)
        #expect(grid.hasPrefix("JJ00"))
    }

    @Test func gridIsSixCharacters() {
        let grid = MainViewModel.latLonToGrid(lat: 40.7128, lon: -74.0060) // NYC
        #expect(grid.count == 6)
    }

    @Test func southernHemisphere() {
        // Sydney Opera House: -33.8568 S, 151.2153 E
        let grid = MainViewModel.latLonToGrid(lat: -33.8568, lon: 151.2153)
        #expect(grid.hasPrefix("QF56"))
    }
}

// MARK: - AprsConfig Encoding

struct AprsConfigEncodingTests {
    private func makeConfig() -> AprsConfig {
        AprsConfig(
            callsign: "JI1ORE",
            ssid: 9,
            path: "WIDE1-1",
            interval: 60,
            freq: 144.660,
            baud: 1200,
            use_gps: true,
            manual_lat: 35.0,
            manual_lon: 139.0,
            symbol: ">",
            comment: "Mobile",
            destination: "APRS00",
            sound_device: "default",
            rig_id: "1035",
            cat_device: "/dev/ttyUSB0"
        )
    }

    @Test func encodesAllRequiredKeys() throws {
        let cfg = makeConfig()
        let data = try JSONEncoder().encode(cfg)
        let json = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])

        #expect(json["callsign"] as? String == "JI1ORE")
        #expect(json["ssid"] as? Int == 9)
        #expect(json["path"] as? String == "WIDE1-1")
        #expect(json["interval"] as? Int == 60)
        #expect(json["baud"] as? Int == 1200)
        #expect(json["use_gps"] as? Bool == true)
        #expect(json["manual_lat"] as? Double == 35.0)
        #expect(json["manual_lon"] as? Double == 139.0)
        #expect(json["symbol"] as? String == ">")
        #expect(json["comment"] as? String == "Mobile")
        #expect(json["destination"] as? String == "APRS00")
        #expect(json["sound_device"] as? String == "default")
        #expect(json["rig_id"] as? String == "1035")
        #expect(json["cat_device"] as? String == "/dev/ttyUSB0")
    }

    @Test func freqIsEncodedAsDouble() throws {
        let cfg = makeConfig()
        let data = try JSONEncoder().encode(cfg)
        let json = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let freq = try #require(json["freq"] as? Double)
        #expect(abs(freq - 144.660) < 0.0001)
    }

    @Test func startPayloadOmitsRadioFields() {
        let cfg = makeConfig()
        let payload = cfg.startPayload()
        // Subset sent to /aprs_start mirrors the Android contract.
        #expect(payload["callsign"] != nil)
        #expect(payload["interval"] != nil)
        #expect(payload["manual_lat"] != nil)
        // These five must be excluded from the start-call body.
        #expect(payload["symbol"] == nil)
        #expect(payload["destination"] == nil)
        #expect(payload["sound_device"] == nil)
        #expect(payload["rig_id"] == nil)
        #expect(payload["cat_device"] == nil)
    }
}

// MARK: - Server Response Decoding

struct ResponseDecodingTests {
    @Test func decodesRigsResponse() throws {
        let json = #"{"rigs":[{"id":1035,"name":"Yaesu FT-991"},{"id":2,"name":"Dummy"}]}"#
        let resp = try JSONDecoder().decode(RigsResponse.self, from: Data(json.utf8))
        #expect(resp.rigs.count == 2)
        #expect(resp.rigs[0].id == 1035)
        #expect(resp.rigs[0].name == "Yaesu FT-991")
        #expect(resp.rigs[1].id == 2)
    }

    @Test func decodesDevicesResponse() throws {
        let json = #"""
        {
          "serial":["/dev/ttyUSB0","/dev/ttyACM0"],
          "audio":[{"id":"hw:0,0","label":"Built-in"}]
        }
        """#
        let resp = try JSONDecoder().decode(DevicesResponse.self, from: Data(json.utf8))
        #expect(resp.serial.count == 2)
        #expect(resp.serial[0] == "/dev/ttyUSB0")
        #expect(resp.audio.count == 1)
        #expect(resp.audio[0].id == "hw:0,0")
        #expect(resp.audio[0].label == "Built-in")
    }

    @Test func decodesRigStatusWithOptionals() throws {
        let json = #"""
        {
          "freq":14250000,"mode":"USB","signal":3.5,
          "tx":false,"power":0.5,"width":2400,"sql":0.1
        }
        """#
        let s = try JSONDecoder().decode(RigStatus.self, from: Data(json.utf8))
        #expect(s.freq == 14_250_000)
        #expect(s.mode == "USB")
        #expect(s.tx == false)
        #expect(s.width == 2400)
        #expect(s.tx_in_progress == nil)
        #expect(s.bk_in == nil)
    }

    @Test func decodesRigStatusWithBkIn() throws {
        let json = #"""
        {"freq":7100000,"mode":"CW","signal":1.0,"tx":true,
         "power":0.3,"width":500,"sql":0.0,"bk_in":true}
        """#
        let s = try JSONDecoder().decode(RigStatus.self, from: Data(json.utf8))
        #expect(s.bk_in == true)
        #expect(s.mode == "CW")
    }

    @Test func decodesPiTime() throws {
        let s = try JSONDecoder().decode(PiTime.self, from: Data(#"{"ms":1717000000000}"#.utf8))
        #expect(s.ms == 1_717_000_000_000)
    }

    // v2.02
    @Test func decodesCwStatus() throws {
        let json = #"{"connected":true,"synced":true,"offset_ms":42,"max_late_ms":7}"#
        let s = try JSONDecoder().decode(CwStatus.self, from: Data(json.utf8))
        #expect(s.connected)
        #expect(s.synced)
        #expect(s.offset_ms == 42)
        #expect(s.maxLateMs == 7)
    }

    @Test func decodesCwStatusWithoutOptionalField() throws {
        let json = #"{"connected":false,"synced":false,"offset_ms":0}"#
        let s = try JSONDecoder().decode(CwStatus.self, from: Data(json.utf8))
        #expect(!s.connected)
        #expect(s.maxLateMs == 0)
    }
}

// MARK: - v2.02 Models

struct V2_02ModelsTests {
    @Test func aprsSymbolLookupByCode() {
        let car = AprsConstants.symbol(byCode: ">")
        #expect(car.label == "Car")
        #expect(car.emoji == "🚗")
    }

    @Test func aprsSymbolFallbackForUnknownCode() {
        let unk = AprsConstants.symbol(byCode: "?")
        #expect(unk.code == "?")
        #expect(unk.label == "?")
    }

    @Test func destinationListContainsV2_02Entries() {
        #expect(AprsConstants.destinationList.contains("APDW18"))
        #expect(AprsConstants.destinationList.contains("APYA05"))
        #expect(AprsConstants.destinationList.contains("APRS00"))
    }

    @Test func stepHzMatchesV2_02Layout() {
        #expect(AppConstants.stepHz == [1, 10, 100, 1000, 5000, 10000, 20000])
        #expect(AppConstants.stepLabels.count == AppConstants.stepHz.count)
    }

    @Test func defaultStepIndexPerMode() {
        #expect(AppConstants.defaultStepIndex(forMode: "FM") == 6)
        #expect(AppConstants.defaultStepIndex(forMode: "AM") == 6)
        #expect(AppConstants.defaultStepIndex(forMode: "CW") == 2)
        #expect(AppConstants.defaultStepIndex(forMode: "USB") == 3)
        #expect(AppConstants.defaultStepIndex(forMode: "PKTUSB") == 3)
    }
}

// MARK: - App Constants Sanity Checks

struct AppConstantsTests {
    @Test func defaultBaudIsValidIndex() {
        #expect(AppConstants.baudRates.indices.contains(AppConstants.defaultBaudIndex))
    }

    @Test func defaultSamplingIsValidIndex() {
        #expect(AppConstants.samplingRates.indices.contains(AppConstants.defaultSamplingIndex))
    }

    @Test func defaultScreenTimeoutIsValidIndex() {
        #expect(AppConstants.screenTimeoutMinutes.indices.contains(AppConstants.defaultScreenTimeoutIndex))
    }

    @Test func portsAreInValidRange() {
        let ports = [
            AppConstants.defaultApiPort,
            AppConstants.defaultAudioPort,
            AppConstants.defaultPttPort,
            AppConstants.cwUdpPort,
            AppConstants.webFt8HttpsPort
        ]
        for p in ports {
            #expect(p > 0 && p < 65536)
        }
    }

    @Test func stepHzIsAscending() {
        let steps = AppConstants.stepHz
        for i in 1..<steps.count {
            #expect(steps[i] > steps[i - 1])
        }
    }
}

// MARK: - CW Decoder

struct CwDecoderTests {
    @Test func morseTableHasCoreLetters() {
        // A few sanity checks against IARU standard
        #expect(CwDecoder.morseTable[".-"] == "A")
        #expect(CwDecoder.morseTable["..."] == "S")
        #expect(CwDecoder.morseTable["---"] == "O")
        #expect(CwDecoder.morseTable["-----"] == "0")
        #expect(CwDecoder.morseTable[".----"] == "1")
        #expect(CwDecoder.morseTable[".-.-.-"] == ".")
        #expect(CwDecoder.morseTable["-..-."] == "/")
    }

    @Test func morseTableLength() {
        // 26 letters + 10 digits + 13 punctuation = 49
        #expect(CwDecoder.morseTable.count == 49)
    }

    @Test func instantiatesWithoutCrash() {
        let dec = CwDecoder(sampleRate: 8000)
        dec.reset()
        // empty input
        dec.processSamples([])
        // a frame's worth of zeros (mostly noise, should not crash)
        dec.processSamples(.init(repeating: 0, count: 1024))
    }

    @Test func channelCallbacksFireOnSimulatedTone() async {
        let dec = CwDecoder(sampleRate: 8000)
        var freqEvents: [(Int, Int)] = []
        dec.onChannelFreq = { ci, hz in freqEvents.append((ci, hz)) }

        // Generate a 750 Hz CW-like burst (~500ms of tone) then silence.
        let sampleRate = 8000
        let toneFreq = 750.0
        var samples: [Int16] = []
        samples.reserveCapacity(sampleRate)
        for i in 0..<(sampleRate / 2) {
            let v = sin(2.0 * .pi * toneFreq * Double(i) / Double(sampleRate))
            samples.append(Int16(v * 16000))
        }
        samples.append(contentsOf: [Int16](repeating: 0, count: sampleRate / 2))
        dec.processSamples(samples)

        // The decoder should have activated at least one channel with a freq near 750 Hz.
        let activatedFreqs = freqEvents.map(\.1).filter { $0 > 0 }
        #expect(!activatedFreqs.isEmpty)
        if let closest = activatedFreqs.min(by: { abs($0 - 750) < abs($1 - 750) }) {
            // 15.6 Hz/bin with FREQ_TOL=4 ⇒ ±62Hz window
            #expect(abs(closest - 750) < 80)
        }
    }
}

// MARK: - PttType

struct PttTypeTests {
    @Test func rawValueRoundTrip() {
        for t in PttType.allCases {
            #expect(PttType(rawValue: t.rawValue) == t)
        }
    }

    @Test func rawValuesMatchServerContract() {
        #expect(PttType.none.rawValue == "NONE")
        #expect(PttType.rts.rawValue == "RTS")
        #expect(PttType.dtr.rawValue == "DTR")
    }
}
