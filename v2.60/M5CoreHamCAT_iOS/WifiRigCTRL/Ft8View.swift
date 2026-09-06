import SwiftUI
@preconcurrency import WebKit

struct Ft8View: View {
    @Bindable var vm: MainViewModel
    @State private var webViewRef: WKWebView?
    @State private var showLatencyEditor: Bool = false
    @State private var latencyText: String = ""
    @State private var showPowerPicker: Bool = false
    @State private var webLoadingMessage: String? = nil
    @State private var piClockOffsetMs: Int64 = 0
    @State private var isSyncing: Bool = false

    private let powerSteps: [Double] = [0.05, 0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 0.80, 0.90, 1.00]

    private let ft8Bands: [(label: String, hz: Int64)] = [
        ("160m  1.840 MHz",        1_840_000),
        ("80m   3.531 MHz (JA)",   3_531_000),
        ("80m   3.573 MHz",        3_573_000),
        ("60m   5.357 MHz",        5_357_000),
        ("40m   7.041 MHz (JA)",   7_041_000),
        ("40m   7.074 MHz",        7_074_000),
        ("30m  10.136 MHz",       10_136_000),
        ("20m  14.074 MHz",       14_074_000),
        ("17m  18.100 MHz",       18_100_000),
        ("15m  21.074 MHz",       21_074_000),
        ("12m  24.915 MHz",       24_915_000),
        ("10m  28.074 MHz",       28_074_000),
        ("6m   50.313 MHz",        50_313_000),
        ("2m  144.174 MHz",       144_174_000),
        ("2m  144.460 MHz (JA)",  144_460_000),
        ("70cm 430.510 MHz (JA)", 430_510_000),
        ("70cm 432.174 MHz",      432_174_000),
    ]

    var body: some View {
        VStack(spacing: 0) {
            toolbar
            if vm.webft8VersionMismatch {
                Text("⚠ WebFT8 server is outdated — update via UPDATE")
                    .font(.caption.bold())
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
                    .background(Color(red: 0.72, green: 0.11, blue: 0.11))
            }
            Ft8WebView(vm: vm, onLoadStateChange: { msg in webLoadingMessage = msg }) { webView in
                self.webViewRef = webView
            }
            .overlay(alignment: .center) {
                if let msg = webLoadingMessage {
                    ZStack {
                        Color.black.opacity(0.55)
                        VStack(spacing: 14) {
                            ProgressView().tint(.white).scaleEffect(1.4)
                            Text(msg)
                                .font(.callout.weight(.medium))
                                .foregroundStyle(.white)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal)
                        }
                        .padding(24)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
                    }
                }
            }
            statusOverlay
        }
        .navigationTitle(Text("ft8"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            vm.captureFt8RestorePoint()
            Task {
                await vm.audioRxStopIfRunning()
                await vm.enterFt8Mode()
            }
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            webViewRef?.evaluateJavaScript("window._ft8StopAudio && window._ft8StopAudio()")
            // Read callsign/grid back from WebFT8 localStorage before persisting
            if let wv = webViewRef {
                wv.evaluateJavaScript(
                    "JSON.stringify({c:localStorage.getItem('webft8-mycall')||'',g:localStorage.getItem('webft8-mygrid')||''})"
                ) { result, _ in
                    if let json = result as? String,
                       let data = json.data(using: .utf8),
                       let obj = try? JSONSerialization.jsonObject(with: data) as? [String: String] {
                        let c = obj["c"] ?? ""
                        let g = obj["g"] ?? ""
                        Task { @MainActor in
                            if !c.isEmpty { vm.ft8MyCall = c }
                            if !g.isEmpty { vm.ft8MyGrid = g }
                            await vm.exitFt8Mode()
                        }
                    } else {
                        Task { await vm.exitFt8Mode() }
                    }
                }
            } else {
                Task { await vm.exitFt8Mode() }
            }
            vm.applyWakeLock()
        }
        .sheet(isPresented: $showLatencyEditor) {
            latencyEditor
        }
        .confirmationDialog("ft8_power", isPresented: $showPowerPicker) {
            ForEach(powerSteps, id: \.self) { v in
                Button("\(Int(v * 100))%") {
                    Task { await vm.setPower(v) }
                }
            }
            Button("cancel", role: .cancel) {}
        }
    }

    private var toolbar: some View {
        HStack(spacing: 8) {
            Button {
                vm.path.removeLast()
            } label: {
                Image(systemName: "chevron.left")
            }
            Button(action: { showLatencyEditor = true }) {
                Text(String(format: "%.1fs", Double(vm.ft8LatencyMs) / 1000.0))
                    .font(.caption.monospacedDigit())
            }
            Button {
                let next = vm.ft8TxMode == "USB" ? "PKTUSB" : "USB"
                webViewRef?.evaluateJavaScript("window._ft8AudioLatencyMs = \(vm.ft8LatencyMs)", completionHandler: nil)
                Task { await vm.setFt8TxMode(next) }
            } label: {
                Text(vm.ft8TxMode == "PKTUSB" ? "PKT" : vm.ft8TxMode)
                    .font(.caption.monospacedDigit())
            }
            Button {
                showPowerPicker = true
            } label: {
                Text("PWR \(Int(vm.sharedPower * 100))")
                    .font(.caption.monospacedDigit())
            }
            Menu {
                Menu("HF") {
                    ForEach(ft8Bands.filter { $0.hz < 70_000_000 }, id: \.hz) { band in
                        Button(band.label) { setFrequency(band.hz) }
                    }
                }
                Menu("VHF/UHF") {
                    ForEach(ft8Bands.filter { $0.hz >= 70_000_000 }, id: \.hz) { band in
                        Button(band.label) { setFrequency(band.hz) }
                    }
                }
            } label: {
                Text(vm.sharedFreq <= 0 ? "--.-"
                     : String(format: "%.3f", Double(vm.sharedFreq) / 1_000_000.0))
                    .font(.caption.monospacedDigit())
            }
            Spacer()
            // SYNC: fetch Pi clock offset and update Date.now() correction in webft8.
            // Critical for FT8 decode alignment — if iOS clock differs from Pi UTC even
            // by 1s, decode windows shift and fewer signals are decoded.
            Button {
                guard !isSyncing else { return }
                isSyncing = true
                Task {
                    let offset = (try? await vm.syncFt8Clock()) ?? 0
                    piClockOffsetMs = offset
                    let js = "window._ft8ClockOffsetMs=\(offset);window._ft8TxLockUntilMs=0;window._ft8TxSeqNo=0;window._ft8LastSentPeriodKey=-1;window._ft8CurrentDx=null;window._ft8CallPeriod={};"
                    webViewRef?.evaluateJavaScript(js, completionHandler: nil)
                    isSyncing = false
                }
            } label: {
                if isSyncing {
                    ProgressView().scaleEffect(0.7)
                } else {
                    Text(piClockOffsetMs == 0 ? "SYNC"
                         : "SYNC\(piClockOffsetMs > 0 ? "+" : "")\(piClockOffsetMs/1000)s")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(abs(piClockOffsetMs) > 2000 ? .red : .primary)
                }
            }
            // Native START button: reconnects stream when Pi audio drops mid-session.
            // AudioContext bootstrap must be done via the injected web-page button (real
            // user gesture inside WebKit). evaluateJavaScript is not a user gesture and
            // cannot resume a suspended AudioContext on iOS — it can only restart the fetch.
            Button("START") {
                webViewRef?.evaluateJavaScript("""
                (function() {
                    var ctxs = window._ft8AllCtx || [];
                    ctxs.forEach(function(c) {
                        if (c && c.state === 'suspended') { try { c.resume(); } catch(e) {} }
                    });
                    window._ft8ReconnectAudio && window._ft8ReconnectAudio();
                })()
                """)
            }
            .font(.caption.bold())
            Button(action: {
                webViewRef?.evaluateJavaScript("window._ft8ReconnectAudio && window._ft8ReconnectAudio()")
            }) {
                Image(systemName: "arrow.clockwise")
            }
            Menu {
                Button("ft8_grid_from_gps") { applyGridFromGps() }
                Toggle("FT4", isOn: $vm.ft8IsFt4)
                Button {
                    Task { _ = await vm.ft8ServerTx() }
                } label: {
                    Label("Server TX (test)", systemImage: "paperplane.fill")
                }
                Button("ft8_reload") { webViewRef?.reload() }
                Button("ft8_reconnect_audio") {
                    webViewRef?.evaluateJavaScript("window._ft8ReconnectAudio && window._ft8ReconnectAudio()")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color(.secondarySystemBackground))
    }

    private var statusOverlay: some View {
        HStack {
            Text(vm.sharedMode.isEmpty ? "---" : vm.sharedMode)
                .font(.caption.monospacedDigit())
            Spacer()
            Text(vm.sharedFreq <= 0 ? "---.--- MHz"
                 : String(format: "%.3f MHz", Double(vm.sharedFreq) / 1_000_000.0))
                .font(.caption.monospacedDigit())
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .background(Color(.secondarySystemBackground))
    }

    private var latencyEditor: some View {
        NavigationStack {
            Form {
                Section(header: Text("ft8_audio_latency_sec")) {
                    TextField("0.0", text: $latencyText)
                        .keyboardType(.numbersAndPunctuation)
                        .font(.title2.monospacedDigit())
                }
            }
            .navigationTitle(Text("ft8_audio_latency_sec"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel") { showLatencyEditor = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("ok") {
                        let normalized = latencyText.replacingOccurrences(of: ",", with: ".")
                        if let sec = Double(normalized) {
                            let ms = Int((sec * 1000).rounded())
                            vm.updateFt8LatencyMs(ms)
                            webViewRef?.evaluateJavaScript("window._ft8AudioLatencyMs=\(vm.ft8LatencyMs)")
                        }
                        showLatencyEditor = false
                    }
                }
            }
            .onAppear {
                latencyText = String(format: "%.1f", Double(vm.ft8LatencyMs) / 1000.0)
            }
        }
    }

    private func applyGridFromGps() {
        vm.requestLocationAuthorization()
        guard let fix = vm.currentLocationFix() else { return }
        let grid = MainViewModel.latLonToGrid(lat: fix.lat, lon: fix.lon)
        vm.ft8MyGrid = grid
        vm.persistFt8Settings()
        let safe = grid.replacingOccurrences(of: "'", with: "\\'")
        let js = """
        (function(){
          localStorage.setItem('webft8-mygrid','\(safe)');
          var inputs = document.querySelectorAll('input');
          for (var i = 0; i < inputs.length; i++) {
            var v = (inputs[i].value || '').trim().toUpperCase();
            var ph = (inputs[i].placeholder || '').toLowerCase();
            var id = (inputs[i].id || '').toLowerCase();
            if (/^[A-R]{2}[0-9]{2}/.test(v) || ph.indexOf('grid') >= 0 || id.indexOf('grid') >= 0) {
              inputs[i].value = '\(safe)';
              inputs[i].dispatchEvent(new Event('input', { bubbles: true }));
              inputs[i].dispatchEvent(new Event('change', { bubbles: true }));
            }
          }
        })();
        """
        webViewRef?.evaluateJavaScript(js)
    }

    private func setFrequency(_ hz: Int64) {
        vm.sharedFreq = hz
        // Notify radio via FastAPI (native URLSession avoids Mixed Content from https page)
        if let url = URL(string: "http://\(vm.ft8Host):\(vm.ft8ApiPort)/radio/setfreq") {
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.httpBody = "f=\(hz)".data(using: .utf8)
            req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            if !vm.ft8ApiKey.isEmpty { req.setValue(vm.ft8ApiKey, forHTTPHeaderField: "X-API-Key") }
            URLSession.shared.dataTask(with: req).resume()
        }
        // Update WebFT8's band selector so its internal state (waterfall, decoder) syncs.
        // webft8 uses #band-header <select> with option values in MHz (e.g. "7.041", "144.460").
        webViewRef?.evaluateJavaScript("""
        (function(){
          var targetHz = \(hz);
          var sel = document.getElementById('band-header');
          if (!sel) return;
          // Find the closest option value (in MHz) to the target frequency.
          var best = null, bestDiff = Infinity;
          for (var i = 0; i < sel.options.length; i++) {
            var optHz = parseFloat(sel.options[i].value) * 1e6;
            if (isNaN(optHz)) continue;
            var diff = Math.abs(optHz - targetHz);
            if (diff < bestDiff) { bestDiff = diff; best = sel.options[i]; }
          }
          if (best && sel.value !== best.value) {
            sel.value = best.value;
            localStorage.setItem('webft8-band', best.value);
            sel.dispatchEvent(new Event('change', { bubbles: true }));
          }
        })();
        """)
    }
}

// MARK: - WKWebView Wrapper

struct Ft8WebView: UIViewRepresentable {
    let vm: MainViewModel
    var onLoadStateChange: ((String?) -> Void)? = nil
    let onReady: (WKWebView) -> Void

    func makeCoordinator() -> Ft8WebViewCoordinator {
        Ft8WebViewCoordinator(host: vm.ft8Host, apiPort: vm.ft8ApiPort, apiKey: vm.ft8ApiKey)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.mediaTypesRequiringUserActionForPlayback = []
        config.allowsInlineMediaPlayback = true
        // pistream:// → https:// for the WebFT8 page itself (self-signed cert accepted)
        config.setURLSchemeHandler(PiProxySchemeHandler(), forURLScheme: PiProxySchemeHandler.scheme)
        // piaudio:// → http:// for the audio stream, bypassing the Python HTTPS proxy and
        // connecting directly to FastAPI. This eliminates SSL cert issues on the audio path.
        config.setURLSchemeHandler(PiProxySchemeHandler(targetScheme: "http"),
                                   forURLScheme: PiProxySchemeHandler.httpScheme)

        let userContent = WKUserContentController()
        // ft8api: receives setfreq and audio_tx messages from JS, proxies to FastAPI via
        // native URLSession (avoids Mixed Content block from https:// page → http:// API).
        userContent.add(context.coordinator, name: "ft8api")
        let js = Self.buildInjectionJS(
            host: vm.ft8Host,
            apiKey: vm.ft8ApiKey,
            apiPort: vm.ft8ApiPort,
            myCall: vm.ft8MyCall,
            myGrid: vm.ft8MyGrid,
            latencyMs: vm.ft8LatencyMs,
            ft8InitFreqHz: vm.ft8LastFreq
        )
        let script = WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: true)
        userContent.addUserScript(script)
        config.userContentController = userContent

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.isInspectable = true

        // Load via https:// for a secure context — AudioContext.resume(), AudioWorklet, and
        // getUserMedia all require secure origin on iOS WebKit.  pistream:// (custom scheme)
        // is treated as an insecure unique origin, silently blocking ctx.resume() even on
        // user-gesture.  The self-signed cert is accepted by Ft8WebViewCoordinator below.
        // PiProxySchemeHandler stays registered so JS fetch("pistream://…/audio_sub") still works.
        let urlStr = "https://\(vm.ft8Host):\(AppConstants.webFt8HttpsPort)/"
        if let url = URL(string: urlStr) {
            webView.load(URLRequest(url: url))
        }

        context.coordinator.onLoadStateChange = onLoadStateChange
        DispatchQueue.main.async { onReady(webView) }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        context.coordinator.onLoadStateChange = onLoadStateChange
    }

    static func buildInjectionJS(host: String, apiKey: String, apiPort: Int, myCall: String, myGrid: String, latencyMs: Int, ft8InitFreqHz: Int64, clockOffsetMs: Int64 = 0) -> String {
        // Same-origin HTTPS fetch: page loaded at https://host:8443/, audio also fetched from
        // https://host:8443/audio_sub — same origin, no cross-origin block.
        // SSL trust is already established when the page loaded (Ft8WebViewCoordinator accepted
        // the self-signed cert), so subsequent same-origin fetches reuse the same trust decision.
        // pistream:// custom scheme was blocked because https:// → pistream:// is cross-origin.
        let audioUrl = "https://\(host):\(AppConstants.webFt8HttpsPort)/audio_sub?rate=12000"
        let apiUrl = "https://\(host):\(AppConstants.webFt8HttpsPort)"
        let safeApiKey = apiKey.replacingOccurrences(of: "'", with: "\\'")
        let safeCall = myCall.replacingOccurrences(of: "'", with: "\\'")
        let safeGrid = myGrid.replacingOccurrences(of: "'", with: "\\'").uppercased()
        return #"""
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.getRegistrations().then(function(regs) {
    regs.forEach(function(r) { r.unregister(); });
  });
}

window._ft8AudioLatencyMs = __LAT_MS__;
window._ft8ClockOffsetMs  = __CLOCK_OFFSET_MS__;
(function() {
  var _origDateNow = Date.now.bind(Date);
  var _OrigDate = window.Date;
  function _ft8AdjustedNow() {
    return _origDateNow() + window._ft8ClockOffsetMs - window._ft8AudioLatencyMs;
  }
  function _PatchedDate() {
    if (arguments.length === 0) {
      return new _OrigDate(_ft8AdjustedNow());
    }
    return new (Function.prototype.bind.apply(_OrigDate, [null].concat(Array.prototype.slice.call(arguments))))();
  }
  _PatchedDate.prototype = _OrigDate.prototype;
  _PatchedDate.now = function() { return _ft8AdjustedNow(); };
  _PatchedDate.parse = _OrigDate.parse.bind(_OrigDate);
  _PatchedDate.UTC   = _OrigDate.UTC.bind(_OrigDate);
  window.Date = _PatchedDate;
})();

(function() {
  if (window._ft8AudioInjected) return;
  window._ft8AudioInjected = true;

  Object.keys(localStorage).forEach(function(k) {
    if (k.startsWith('webft8-') && (
        k.indexOf('offset') >= 0 || k.indexOf('sync') >= 0 ||
        k.indexOf('ntp') >= 0 || k.indexOf('time') >= 0)) {
      localStorage.removeItem(k);
    }
  });

  if ('__CALL__') localStorage.setItem('webft8-mycall', '__CALL__');
  if ('__GRID__') localStorage.setItem('webft8-mygrid', '__GRID__');
  localStorage.setItem('webft8-audio-in', 'pi-audio-stream');
  localStorage.setItem('webft8-wf-enable', '1');

  if (__INITHZ__ > 0) {
    var _initHz = __INITHZ__;
    localStorage.setItem('webft8-dial',      _initHz);
    localStorage.setItem('webft8-dialfreq',  _initHz);
    localStorage.setItem('webft8-freq',      _initHz);
  }

  // Fix Japanese IME (flick input) double character entry in WKWebView.
  // Same technique as Android Ft8Fragment: stop duplicate input events within 50ms.
  (function() {
    var _lastData = null;
    var _lastDataTime = 0;
    document.addEventListener('input', function(e) {
      if (e.isComposing) { e.stopImmediatePropagation(); return; }
      var data = e.data;
      if (!data) return;
      var now = Date.now();
      if (data === _lastData && now - _lastDataTime < 50) {
        e.stopImmediatePropagation();
        var el = e.target;
        if (el && typeof el.value === 'string' && el.value.endsWith(data)) {
          el.value = el.value.slice(0, -data.length);
        }
        return;
      }
      _lastData = data;
      _lastDataTime = now;
    }, true);
    document.addEventListener('keydown', function(e) {
      if (e.isComposing || e.keyCode === 229) { e.stopImmediatePropagation(); }
    }, true);
  })();

  window.addEventListener('DOMContentLoaded', function() {
    setTimeout(function() {
      var all = document.body.getElementsByTagName('*');
      for (var i = 0; i < all.length; i++) {
        if (all[i].childElementCount === 0 && /^webFT8$/i.test(all[i].textContent.trim())) {
          var hdr = all[i].closest('header,nav,[class*="bar"],[class*="header"]') || all[i].parentElement || all[i];
          hdr.style.display = 'none';
          break;
        }
      }
    }, 1500);

    // Fill callsign / grid inputs directly so WebFT8 validates them on "Start Audio" click.
    // localStorage alone is not enough — WebFT8 reads it at init time; direct value sets
    // guarantee the DOM reflects what the user configured here, even on re-renders.
    var _ci = document.getElementById('my-call');
    if (_ci) {
      if ('__CALL__') { _ci.value = '__CALL__'; _ci.dispatchEvent(new Event('change', { bubbles: true })); }
    }
    var _gi = document.getElementById('my-grid');
    if (_gi) {
      if ('__GRID__') { _gi.value = '__GRID__'; _gi.dispatchEvent(new Event('change', { bubbles: true })); }
      // Allow 4-char grids (e.g. PM95) by removing HTML5 validation constraints webft8 applies.
      _gi.removeAttribute('required');
      _gi.removeAttribute('minlength');
      _gi.removeAttribute('pattern');
      _gi.setCustomValidity('');
      _gi.addEventListener('blur', function() { this.setCustomValidity(''); });
      _gi.addEventListener('invalid', function(e) { e.preventDefault(); this.setCustomValidity(''); });
    }

    var sel = document.getElementById('audio-device');
    if (sel) {
      if (!sel.querySelector('option[value="pi-audio-stream"]')) {
        var opt = document.createElement('option');
        opt.value = 'pi-audio-stream';
        opt.textContent = 'Pi Radio Audio';
        sel.appendChild(opt);
      }
      sel.value = 'pi-audio-stream';
      sel.dispatchEvent(new Event('change'));
    }
    // Retry after WebFT8 populates the select from enumerateDevices (async Promise chain).
    setTimeout(function() {
      var sel2 = document.getElementById('audio-device');
      if (sel2 && sel2.value !== 'pi-audio-stream') {
        if (!sel2.querySelector('option[value="pi-audio-stream"]')) {
          var opt2 = document.createElement('option');
          opt2.value = 'pi-audio-stream';
          opt2.textContent = 'Pi Radio Audio';
          sel2.appendChild(opt2);
        }
        sel2.value = 'pi-audio-stream';
        sel2.dispatchEvent(new Event('change'));
      }
    }, 800);

    // Inject iOS START button directly into the WebFT8 page.
    // This is a real user gesture inside WebKit — AudioContext.resume() succeeds here,
    // unlike evaluateJavaScript calls from Swift which are not user gestures on iOS.
    var iosBtn = document.createElement('button');
    iosBtn.id = '_ios_ft8_start';
    iosBtn.textContent = '▶ START';
    iosBtn.style.cssText = 'position:fixed;top:6px;left:50%;transform:translateX(-50%);z-index:2147483647;padding:6px 22px;background:#1565C0;color:#fff;border:none;border-radius:16px;font-size:14px;font-weight:bold;cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,0.45);';
    iosBtn.addEventListener('click', function() {
      if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur();
      window._ft8LastError = null;
      iosBtn.textContent = '⏳ CONNECT';
      iosBtn.style.background = '#555';

      // webft8's toggleAudio() checks deviceSelect.value and returns early (openSettings)
      // if it is empty. Force-select 'pi-audio-stream' — or the first valid option —
      // every time the START button is tapped so the check always passes.
      var _dSel = document.getElementById('audio-device');
      if (_dSel) {
        var _tgt = '', _piFound = false;
        for (var _oi = 0; _oi < _dSel.options.length; _oi++) {
          var _ov = _dSel.options[_oi].value;
          if (_ov === 'pi-audio-stream') { _tgt = _ov; _piFound = true; break; }
          if (_ov && !_tgt) _tgt = _ov;
        }
        if (!_tgt) {
          var _nOpt = document.createElement('option');
          _nOpt.value = 'pi-audio-stream'; _nOpt.textContent = 'Pi Radio Audio';
          _dSel.appendChild(_nOpt); _tgt = 'pi-audio-stream';
        }
        if (_dSel.value !== _tgt) {
          _dSel.value = _tgt; _dSel.dispatchEvent(new Event('change'));
        }
      }

      // Bootstrap our PCM stream and audio destination first so that when WebFT8's
      // capture.start() calls getUserMedia, we return _audioDest.stream immediately.
      window._ft8BootstrapAudio();
      // Trigger WebFT8's own "Start Audio" button so capture.start() and
      // createMediaStreamSource run — which connects our PCM stream to the waterfall.
      // Try multiple IDs used across webft8 versions; also fall back to text search.
      (function() {
        var _ws = document.getElementById('btn-start') ||
                  document.getElementById('start-audio') ||
                  document.getElementById('startAudio') ||
                  document.getElementById('audio-start') ||
                  document.getElementById('btn-audio-start');
        if (!_ws) {
          var _btns = document.querySelectorAll('button');
          for (var _bi = 0; _bi < _btns.length; _bi++) {
            var _bt = (_btns[_bi].textContent || '').trim().toLowerCase();
            if (_bt === 'start audio' || _bt === 'start') { _ws = _btns[_bi]; break; }
          }
        }
        if (_ws && /^start/i.test((_ws.textContent || '').trim())) _ws.click();
      })();
    });
    document.body.appendChild(iosBtn);
    // Reflect stream state on the button every second
    setInterval(function() {
      if (!iosBtn) return;
      if (window._streamRunning) {
        iosBtn.textContent = '● LIVE';
        iosBtn.style.background = '#2E7D32';
      } else if (window._ft8LastError) {
        var short = String(window._ft8LastError).slice(0, 24);
        iosBtn.textContent = '⚠ ' + short;
        iosBtn.style.background = '#B71C1C';
      } else {
        iosBtn.textContent = '▶ START';
        iosBtn.style.background = '#1565C0';
      }
    }, 1000);

    // webft8 Snipe/Call mode sets waterfall.freqOffset = snipeBpf - FILTER_CENTER.
    // A positive freqOffset shifts the view right, making bins with binF < 0 render dark
    // (left portion goes blank). Fix: lock freqOffset at 0 so the full spectrum is always visible.
    (function() {
      function _patchWF() {
        var wf = window.waterfall;
        if (!wf) { setTimeout(_patchWF, 500); return; }
        if (wf._freqOffsetPatched) return;
        wf._freqOffsetPatched = true;
        Object.defineProperty(wf, 'freqOffset', {
          get: function() { return 0; },
          set: function() {},
          configurable: true
        });
      }
      setTimeout(_patchWF, 1000);
    })();
  });

  // iOS WKWebView: evaluateJavaScript() is NOT a user gesture inside WebKit.
  // AudioContext.resume() called from Swift-side JS evaluation silently fails if the
  // context is suspended. The fix: inject a visible "▶ START" button directly into the
  // WebFT8 page DOM. When the user taps it, WebKit treats it as a real user gesture,
  // AudioContext can be created/resumed, and the pistream:// fetch starts successfully.
  // _ft8BootstrapAudio() handles the full bootstrap: ctx creation → resume → stream start.

  window._ft8BootstrapAudio = function() {
    var ctx = _audioCtxRef;
    if (!ctx) {
      ctx = new _OrigAudioCtx({ sampleRate: 12000 });
      window._ft8AllCtx.push(ctx);
      window._ft8CapturedCtx = ctx;
      _patchAudioWorklet(ctx);
      _audioCtxRef = ctx;
    }
    if (!_audioDest) {
      _audioDest = ctx.createMediaStreamDestination();
      _allGumDests.push(_audioDest);
      _monitorGain = ctx.createGain();
      _monitorGain.gain.value = 1.0;
      _monitorGain.connect(ctx.destination);
    }
    ctx.resume().then(function() {
      if (!_streamRunning) _startStream();
    }).catch(function() {});
  };

  function _resumeAllContexts() {
    var ctxs = window._ft8AllCtx || [];
    for (var i = 0; i < ctxs.length; i++) {
      if (ctxs[i] && ctxs[i].state === 'suspended') {
        ctxs[i].resume().catch(function() {});
      }
    }
    if (_audioCtxRef && _audioCtxRef.state !== 'running') {
      _audioCtxRef.resume().catch(function() {});
    }
    if (_audioDest && !_streamRunning) {
      setTimeout(function() { if (!_streamRunning) _startStream(); }, 100);
    } else if (!_audioDest && !_streamRunning && window._ft8BootstrapAudio) {
      // No destination yet — full bootstrap (creates ctx + dest + fetch)
      setTimeout(function() { if (!_streamRunning) window._ft8BootstrapAudio(); }, 100);
    }
  }
  document.addEventListener('touchstart', _resumeAllContexts, { passive: true });
  document.addEventListener('click', _resumeAllContexts);

  var _freqSyncTimer = null;
  function _syncFreqToPi(freqHz) {
    if (_freqSyncTimer) clearTimeout(_freqSyncTimer);
    _freqSyncTimer = setTimeout(function() {
      try { window.webkit.messageHandlers.ft8api.postMessage({ action: 'setfreq', hz: freqHz }); } catch(e) {}
    }, 600);
  }
  function _parseFreqHz(v) {
    var n = parseFloat(v);
    if (isNaN(n) || n <= 0) return 0;
    if (n > 100000)  return Math.round(n);        // already Hz (e.g. 7074000)
    if (n > 2000)    return Math.round(n * 1000); // kHz (e.g. 7074)
    if (n >= 0.1)    return Math.round(n * 1e6);  // MHz (e.g. 7.074, 144.460)
    return 0;
  }
  // MHz string from webft8-band key (e.g. "144.460" → 144460000 Hz)
  function _parseBandMHz(v) {
    var n = parseFloat(v);
    if (isNaN(n) || n <= 0) return 0;
    return Math.round(n * 1e6);
  }
  (function() {
    var _origSI = Storage.prototype.setItem;
    Object.defineProperty(Storage.prototype, 'setItem', {
      value: function(key, value) {
        _origSI.call(this, key, value);
        if (this === window.localStorage) {
          var kl = key.toLowerCase();
          if (kl.indexOf('freq') >= 0 || kl.indexOf('center') >= 0 ||
              kl.indexOf('dial') >= 0 || kl.indexOf('band') >= 0) {
            var hz = (kl === 'webft8-band') ? _parseBandMHz(value) : _parseFreqHz(value);
            if (hz > 1000000 && hz < 2000000000) _syncFreqToPi(hz);
          }
        }
      },
      configurable: true, writable: true
    });
  })();

  window.addEventListener('load', function() {
    document.addEventListener('change', function(e) {
      var val = (e.target.value || '').replace(/[,\s]/g, '').trim();
      // band-header value is always in MHz (e.g. "144.460")
      var hz = (e.target.id === 'band-header') ? _parseBandMHz(val) : _parseFreqHz(val);
      if (hz > 1000000 && hz < 2000000000) {
        _syncFreqToPi(hz);
      }
    }, true);

  });

  var _OrigAudioCtx = window.AudioContext || window.webkitAudioContext;
  window._ft8CapturedCtx = null;
  window._ft8AllCtx = [];

  function _patchAudioWorklet(ctx) {
    var aw = ctx.audioWorklet;
    if (!aw) {
      try {
        Object.defineProperty(ctx, 'audioWorklet', {
          value: { addModule: function() { return Promise.resolve(); } },
          configurable: true
        });
      } catch(e) {}
      return;
    }
    // STEP 1 (sync): suppress addModule errors so webft8 never sees rejection.
    var _nativeAM = aw.addModule.bind(aw);
    try {
      Object.defineProperty(aw, 'addModule', {
        value: function(url, opts) {
          return _nativeAM(url, opts).catch(function(e) { return Promise.resolve(); });
        },
        configurable: true, writable: true
      });
    } catch(e) {}
    // STEP 2 removed: do NOT pre-register a blob processor under 'ft8-audio-processor'.
    // Pre-registering our blob was blocking the real audio-processor.js from registering
    // (AudioWorklet throws if the same name is registered twice), causing FT8 decoding to
    // fail entirely because the real processor's decode messages never reached the main thread.
    // The ScriptProcessorNode fallback below handles the rare case where audio-processor.js
    // fails to load — in that case AudioWorkletNode creation throws and we fall back.
  }

  // AudioWorkletNode intercept: if 'ft8-audio-processor' is not yet registered
  // (blob not resolved in time or audio-processor.js missing), fall back to a
  // ScriptProcessorNode that emits the same {type:'waterfall'}/{type:'peak'} messages.
  var _OrigAWN = window.AudioWorkletNode;
  if (_OrigAWN) {
    window.AudioWorkletNode = function(ctx, name, opts) {
      try {
        return new _OrigAWN(ctx, name, opts || {});
      } catch(e) {
        if (name !== 'ft8-audio-processor') throw e;
        var scp = ctx.createScriptProcessor(256, 1, 1);
        var _wfBuf = new Float32Array(512), _wi = 0;
        var _pa = 0, _pc = 0, _run = false;
        var _rate = 12000;
        var _decBufSize = _rate * 15;
        var _decBuf = new Float32Array(_decBufSize);
        var _decPos = 0, _decSent = false;
        scp.port = {
          onmessage: null,
          postMessage: function(msg) {
            if (!msg) return;
            if (msg.type === 'start') {
              _run = true; _decPos = 0; _decSent = false;
              scp.port.onmessage && scp.port.onmessage({data:{type:'info',nativeRate:_rate,outputRate:_rate,snapshotRate:_rate,waterfallRate:6000}});
            } else if (msg.type === 'stop') {
              _run = false;
            } else if (msg.type === 'setBufferSeconds') {
              var secs = Math.max(1, msg.seconds || 15);
              _decBufSize = Math.round(_rate * secs);
              _decBuf = new Float32Array(_decBufSize);
              _decPos = 0; _decSent = false;
            } else if (msg.type === 'snapshot') {
              var snap = _decBuf.slice(0, _decPos);
              scp.port.onmessage && scp.port.onmessage({data:{type:'snapshot',samples:snap,outputRate:_rate}});
              _decPos = 0; _decSent = false;
            }
          },
          addEventListener: function() {}, removeEventListener: function() {}
        };
        scp.onaudioprocess = function(ev) {
          if (!_run) return;
          var inp = ev.inputBuffer.getChannelData(0);
          for (var i = 0; i < inp.length; i++) { var v = Math.abs(inp[i]); if (v > _pa) _pa = v; }
          _pc += inp.length;
          if (_pc >= 1200) { scp.port.onmessage && scp.port.onmessage({data:{type:'peak',level:_pa}}); _pa = 0; _pc = 0; }
          for (var j = 0; j + 1 < inp.length; j += 2) {
            _wfBuf[_wi++] = (inp[j] + inp[j+1]) * 0.5;
            if (_wi >= 512) {
              scp.port.onmessage && scp.port.onmessage({data:{type:'waterfall',samples:_wfBuf.slice()}});
              _wi = 0;
            }
          }
          for (var k = 0; k < inp.length; k++) {
            if (_decPos < _decBufSize) _decBuf[_decPos++] = inp[k];
          }
          if (!_decSent && _decPos >= _decBufSize) {
            _decSent = true;
            scp.port.onmessage && scp.port.onmessage({data:{type:'buffer-full'}});
          }
          ev.outputBuffer.getChannelData(0).fill(0);
        };
        var _scpGain = ctx.createGain();
        _scpGain.gain.value = 0;
        scp.connect(_scpGain);
        _scpGain.connect(ctx.destination);
        return scp;
      }
    };
    window.AudioWorkletNode.prototype = _OrigAWN.prototype;
  }

  function _PatchedAudioCtx(opts) {
    // Reuse the bootstrap 12kHz context when webft8 requests one.
    // iOS Safari does NOT route MediaStream audio across AudioContext boundaries —
    // A's MediaStreamDestination stream is silent when read by B's MediaStreamSourceNode.
    // Sharing the same context eliminates the cross-context hop entirely.
    if (opts && opts.sampleRate === 12000 && _audioCtxRef) {
      // Stub close() so webft8 cannot destroy the shared context.
      try {
        Object.defineProperty(_audioCtxRef, 'close', {
          value: function() { return Promise.resolve(); },
          configurable: true, writable: true
        });
      } catch(e) {}
      _patchAudioWorklet(_audioCtxRef);
      _audioCtxRef.resume().catch(function() {});
      return _audioCtxRef;
    }
    var ctx = new _OrigAudioCtx(opts || {});
    window._ft8AllCtx.push(ctx);
    if (opts && opts.sampleRate === 12000) {
      window._ft8CapturedCtx = ctx;
    }
    _patchAudioWorklet(ctx);
    ctx.resume().catch(function() {});
    return ctx;
  }
  _PatchedAudioCtx.prototype = _OrigAudioCtx.prototype;
  window.AudioContext = _PatchedAudioCtx;
  if (window.webkitAudioContext) window.webkitAudioContext = _PatchedAudioCtx;

  // Track when the stream last stopped so the failsafe below doesn't trigger
  // immediately after an intentional abort (which sets _streamRunning false briefly).
  var _streamStoppedAt = 0;
  setInterval(function() {
    var ctxs = window._ft8AllCtx || [];
    for (var i = 0; i < ctxs.length; i++) {
      if (ctxs[i] && ctxs[i].state === 'suspended') {
        ctxs[i].resume().catch(function() {});
      }
    }
    // Failsafe: if the stream has been down for > 8 s (pump catch/done already scheduled a
    // 5 s retry; this catches the rare case where the fetch error path has no retry).
    if (_audioDest && !_streamRunning) {
      if (_streamStoppedAt === 0) { _streamStoppedAt = _origDateNow(); }
      else if (_origDateNow() - _streamStoppedAt > 8000) {
        _streamStoppedAt = 0;
        _startStream();
      }
    } else {
      _streamStoppedAt = 0;
    }
  }, 1000);

  var _audioDest = null;
  var _monitorGain = null;
  var _audioCtxRef = null;
  var _abortCtrl = null;
  var _allGumDests = [];
  var _gumTimer = null;
  var _streamRunning = false;
  window._streamRunning = false;

  var _origCMSS = _OrigAudioCtx.prototype.createMediaStreamSource;
  _OrigAudioCtx.prototype.createMediaStreamSource = function(stream) {
    var found = false;
    var prevCtx = _audioCtxRef;
    for (var i = 0; i < _allGumDests.length; i++) {
      if (_allGumDests[i].stream === stream) {
        _audioDest = _allGumDests[i];
        _audioCtxRef = _allGumDests[i].context;
        if (!_monitorGain || _monitorGain.context !== _audioCtxRef) {
          _monitorGain = _audioCtxRef.createGain();
          _monitorGain.gain.value = 1.0;
          _monitorGain.connect(_audioCtxRef.destination);
        }
        found = true;
        break;
      }
    }
    if (found) {
      var ctxChanged = prevCtx !== _audioCtxRef;
      if (_gumTimer) clearTimeout(_gumTimer);
      _gumTimer = setTimeout(function() {
        if (_streamRunning && !ctxChanged) {
          // keep running
        } else if (_audioCtxRef && _audioCtxRef.state === 'running') {
          // Only restart if context is actually running (has user gesture).
          // Avoids premature _streamRunning=true when context is still suspended.
          _startStream();
        }
      }, 400);
    }
    return _origCMSS.call(this, stream);
  };

  if (!navigator.mediaDevices) {
    try {
      Object.defineProperty(navigator, 'mediaDevices', { value: {}, writable: true, configurable: true });
    } catch(e) { navigator.mediaDevices = {}; }
  }

  try {
    Object.defineProperty(navigator.mediaDevices, 'enumerateDevices', {
      value: async function() {
        return [{ deviceId: 'pi-audio-stream', kind: 'audioinput', label: 'Pi Radio Audio', groupId: '' }];
      },
      writable: true, configurable: true
    });
  } catch(e) {
    navigator.mediaDevices.enumerateDevices = async function() {
      return [{ deviceId: 'pi-audio-stream', kind: 'audioinput', label: 'Pi Radio Audio', groupId: '' }];
    };
  }

  function _startStream() {
    var ctx = _audioCtxRef;
    var dest = _audioDest;
    if (!ctx || !dest) return;
    if (_abortCtrl) { try { _abortCtrl.abort(); } catch(e) {} }
    _abortCtrl = new AbortController();
    _streamRunning = true;
    window._streamRunning = true;
    var signal = _abortCtrl.signal;
    var nextTime = 0;
    var pending = new Uint8Array(0);
    // Fade-in: suppress the initial audio burst (accumulated TCP buffer on connect).
    // 1.5 s × 12000 Hz = 18000 samples ramp from 0 → 1.
    var _fadeIn = 0;
    var _fadeInTotal = 18000;

    function pump(reader) {
      reader.read().then(function(r) {
        // Do NOT touch _streamRunning when our own AbortController fired it —
        // a newer _startStream() call already set _streamRunning = true.
        if (signal.aborted) { return; }
        if (r.done) {
          _streamRunning = false; window._streamRunning = false;
          window._ft8LastError = 'stream ended';
          setTimeout(function() { if (!signal.aborted) _startStream(); }, 5000);
          return;
        }
        var combined = new Uint8Array(pending.length + r.value.length);
        combined.set(pending);
        combined.set(r.value, pending.length);
        var samples = Math.floor(combined.length / 2);
        var used = samples * 2;
        pending = combined.slice(used);
        if (samples > 0) {
          var buf = ctx.createBuffer(1, samples, 12000);
          var data = buf.getChannelData(0);
          var view = new DataView(combined.slice(0, used).buffer);
          for (var i = 0; i < samples; i++) {
            var scale = _fadeIn < _fadeInTotal ? (_fadeIn++ / _fadeInTotal) : 1.0;
            data[i] = (view.getInt16(i*2, true) / 32768.0) * scale;
          }
          var src = ctx.createBufferSource();
          src.buffer = buf;
          src._ft8IsRx = true;
          src.connect(_audioDest || dest);
          if (_monitorGain) src.connect(_monitorGain);
          if (nextTime > 0 && nextTime < ctx.currentTime - 0.5) { nextTime = 0; }
          if (nextTime > 0 && nextTime - ctx.currentTime > 1.0) { nextTime = ctx.currentTime + 0.15; }
          var t = Math.max(nextTime, ctx.currentTime + 0.15);
          src.start(t);
          nextTime = t + samples / 12000;
        }
        // Throttle reads when look-ahead > 0.5 s so audio stays within 0.5 s of
        // real-time. FT8 decode windows are UTC-based; resetting nextTime would cause
        // gaps (stripes in waterfall), so we delay the next read instead.
        var ahead = nextTime - ctx.currentTime;
        if (ahead > 0.5) {
          setTimeout(function() { if (!signal.aborted) pump(reader); }, (ahead - 0.3) * 1000);
        } else {
          pump(reader);
        }
      }).catch(function(e) {
        _streamRunning = false; window._streamRunning = false;
        if (!signal.aborted) {
          setTimeout(function() { if (!signal.aborted) _startStream(); }, 5000);
        }
      });
    }

    var headers = {};
    if ('__APIKEY__') headers['X-API-Key'] = '__APIKEY__';

    // Keep AudioContext running — iOS suspends it when the app goes to background or the
    // screen locks. Resume every 3 s so audio never silently stops.
    var _keepAlive = setInterval(function() {
      if (signal.aborted) { clearInterval(_keepAlive); return; }
      if (ctx.state === 'suspended') { ctx.resume().then(function() {}); }
    }, 3000);

    ctx.resume().then(function() {
      fetch('__AUDIO_URL__', { headers: headers, signal: signal }).then(function(resp) {
        if (!resp.ok) {
          _streamRunning = false; window._streamRunning = false;
          window._ft8LastError = 'HTTP ' + resp.status;
          // Retry after 5 s (same delay as stream-end / error cases below).
          setTimeout(function() { if (!_streamRunning) _startStream(); }, 5000);
          return;
        }
        window._ft8LastError = null;
        pump(resp.body.getReader());
      }).catch(function(e) {
        if (signal.aborted) { return; }
        _streamRunning = false; window._streamRunning = false;
        window._ft8LastError = e ? (e.name + ': ' + (e.message || '')) : 'fetch failed';
        setTimeout(function() { if (!_streamRunning) _startStream(); }, 5000);
      });
    });
  }

  window._ft8ReconnectAudio = function() {
    setTimeout(_startStream, 600);
  };

  window._ft8StopAudio = function() {
    _streamRunning = false; window._streamRunning = false;
    if (_abortCtrl) { try { _abortCtrl.abort(); } catch(e) {} _abortCtrl = null; }
  };

  // iOS Safari: simple assignment (obj.prop = fn) is silently ignored for built-in
  // MediaDevices methods because the property descriptor on the instance is not writable.
  // Use Object.defineProperty (same pattern as enumerateDevices above) to override it.
  // Fallback: also patch MediaDevices.prototype so all instances are covered.
  (function() {
    function _gumMock(constraints) {
      return new Promise(function(resolve, reject) {
        try {
          var ctx = window._ft8CapturedCtx;
          if (!ctx) {
            ctx = new _PatchedAudioCtx({ sampleRate: 12000 });
            window._ft8CapturedCtx = ctx;
          }
          if (_audioDest) {
            resolve(_audioDest.stream);
            return;
          }
          var returnDest = ctx.createMediaStreamDestination();
          _allGumDests.push(returnDest);
          resolve(returnDest.stream);
        } catch(e) { reject(e); }
      });
    }
    // Patch the instance (preferred — lowest risk of breaking other pages)
    try {
      Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {
        value: _gumMock, writable: true, configurable: true
      });
    } catch(e) {
      try { navigator.mediaDevices.getUserMedia = _gumMock; } catch(e2) {}
    }
    // Also patch the prototype so the instance lookup always hits our version
    try {
      if (typeof MediaDevices !== 'undefined') {
        MediaDevices.prototype.getUserMedia = _gumMock;
      }
    } catch(e) {}
  })();

  // TX period lock — window-scoped so it survives IIFE re-runs and context recreation.
  if (!window._ft8TxLockUntilMs)  window._ft8TxLockUntilMs = 0;
  if (!window._ft8TxSeqNo)        window._ft8TxSeqNo = 0;
  // Time-based dedup: tracks the last period key (Math.floor(schedReal/fdPeriod)) in which
  // we sent TX to Pi.  WeakSet-by-buffer-identity was wrong — webft8 auto mode reuses the
  // same AudioBuffer across multiple TX periods, causing 2nd+ transmissions to be skipped.
  if (!window._ft8LastSentPeriodKey) window._ft8LastSentPeriodKey = -1;

  // Per-callsign ODD/EVEN tracking.
  // _ft8CallPeriod: { callsign → true=even, false=odd }
  // _ft8CurrentDx:  callsign the user is currently working (set on row-click and on DX→us message)
  if (!window._ft8CallPeriod) window._ft8CallPeriod = {};
  if (window._ft8CurrentDx === undefined) window._ft8CurrentDx = null;

  (function() {
    var _myCall = (localStorage.getItem('webft8-mycall') || '').trim().toUpperCase();

    function _parseCall(node) {
      var words = ((node.textContent || '').match(/[A-Z0-9/]+/gi) || []);
      for (var i = 0; i < words.length; i++) words[i] = words[i].toUpperCase();
      // Transmitter: first word, or second word after 'CQ'
      return (words[0] === 'CQ') ? (words[1] || null) : (words[0] || null);
    }

    function _recordPeriod(node) {
      var txCall = _parseCall(node);
      if (!txCall || txCall.length < 3) return;
      if (_myCall && txCall === _myCall) return;  // skip self-decoded TX

      // Decoded messages appear at the start of the NEXT period; compute which period just ended.
      var realMs = Date.now() + (window._ft8AudioLatencyMs || 0) - (window._ft8ClockOffsetMs || 0);
      var ss60   = Math.floor(realMs / 1000) % 60;
      var endPS  = (Math.floor(ss60 / 15) * 15 - 15 + 60) % 60;
      window._ft8CallPeriod[txCall] = (Math.floor(endPS / 15) % 2 === 0);

      // If DX addressed us, update _ft8CurrentDx
      var words = ((node.textContent || '').match(/[A-Z0-9/]+/gi) || []);
      for (var i = 0; i < words.length; i++) words[i] = words[i].toUpperCase();
      if (_myCall && words[1] === _myCall) window._ft8CurrentDx = txCall;
    }

    // Clicking a decoded chat row → immediately set that station as current DX
    document.addEventListener('click', function(e) {
      var el = e.target;
      for (var i = 0; i < 6 && el && el !== document.body; i++, el = el.parentElement) {
        if (el.classList && el.classList.contains('chat-msg') && el.classList.contains('rx')) {
          var txCall = _parseCall(el);
          if (txCall && txCall.length >= 3 && (!_myCall || txCall !== _myCall)) {
            window._ft8CurrentDx = txCall;
          }
          break;
        }
      }
    }, true);

    var _obs = new MutationObserver(function(muts) {
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          var n = added[j];
          if (n.nodeType === 1 && n.classList &&
              n.classList.contains('chat-msg') && n.classList.contains('rx')) {
            _recordPeriod(n);
          }
        }
      }
    });
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function() {
        _obs.observe(document.body, { childList: true, subtree: true });
      });
    } else {
      _obs.observe(document.body, { childList: true, subtree: true });
    }
  })();

  // (WeakSet-by-buffer removed — replaced by time-based period key below)
  var _origABSStart = AudioBufferSourceNode.prototype.start;
  AudioBufferSourceNode.prototype.start = function(when, offset, duration) {
    if (this.buffer && this.buffer.duration > 5.0 && !this._ft8IsRx) {
      var nowMs    = Date.now();   // patched: real + clockOffset - latency
      // Recover real UTC: period boundaries must align with actual FT8 transmission windows.
      var nowReal  = nowMs + (window._ft8AudioLatencyMs || 0) - (window._ft8ClockOffsetMs || 0);
      var bufMs    = this.buffer.duration * 1000;
      var fdPeriod = bufMs > 10000 ? 15000 : 7500;  // FT8=15s, FT4=7.5s

      // ctx.currentTime is AudioContext's own monotonic clock — not affected by Date.now() patch.
      var whenDeltaMs = 0;
      if (when && this.context && when > this.context.currentTime + 0.1) {
        whenDeltaMs = Math.max(0, Math.round((when - this.context.currentTime) * 1000));
      }
      var schedMs   = nowMs   + whenDeltaMs;   // patched-time domain (for lock comparison)
      var schedReal = nowReal + whenDeltaMs;   // real UTC (for period boundary calculation)

      // ODD/EVEN guard: only TX in the period opposite to _ft8CurrentDx.
      // _ft8CurrentDx is set on decoded row click; _ft8CallPeriod records each call's TX parity.
      var _dxCall = window._ft8CurrentDx;
      if (_dxCall !== null && _dxCall !== undefined &&
          window._ft8CallPeriod[_dxCall] !== undefined) {
        var schedSec60 = Math.floor(schedReal / 1000) % 60;
        var txIsEven   = (Math.floor(schedSec60 / (fdPeriod / 1000)) % 2 === 0);
        if (txIsEven === window._ft8CallPeriod[_dxCall]) {
          try { this.disconnect(); } catch(e) {}
          try {
            var _wg = this.context.createGain();
            _wg.gain.value = 0; _wg.connect(this.context.destination); this.connect(_wg);
          } catch(e) {}
          return _origABSStart.call(this, arguments[0], 0, 0.05);
        }
      }

      // TX lock: mute if we already sent in this TX window.
      // Lock is stored in patched-time domain to match schedMs.
      if (schedMs < window._ft8TxLockUntilMs) {
        try { this.disconnect(); } catch(e) {}
        try {
          var _mg = this.context.createGain();
          _mg.gain.value = 0;
          _mg.connect(this.context.destination);
          this.connect(_mg);
        } catch(e) {}
        return _origABSStart.call(this, arguments[0], 0, 0.05);
      }

      // Dedup by period key: prevent double-sending within the same 15s window.
      // Uses schedReal so the same buffer can be legitimately reused across periods
      // (webft8 auto mode reuses AudioBuffer objects for identical messages).
      var _periodKey = Math.floor(schedReal / fdPeriod);
      if (_periodKey === window._ft8LastSentPeriodKey) {
        return _origABSStart.apply(this, arguments);  // play locally, skip Pi send
      }
      window._ft8LastSentPeriodKey = _periodKey;

      // Duck monitor gain around TX/RX transition to suppress antenna-relay click and
      // AGC recovery burst that cause audio saturation when RX resumes after TX.
      // Uses AudioContext time for precision — unaffected by Date.now() patch.
      (function(_ctx, _startDelta, _dur) {
        var mg = _monitorGain;
        if (!mg || !mg.context || mg.context !== _ctx) return;
        var now   = _ctx.currentTime;
        var txEnd = now + _startDelta / 1000 + _dur / 1000;
        try {
          mg.gain.cancelScheduledValues(now);
          mg.gain.setValueAtTime(mg.gain.value, now);
          // Start fading 150ms before TX ends so relay click is inaudible.
          mg.gain.setValueAtTime(1.0, Math.max(now + 0.01, txEnd - 0.15));
          mg.gain.linearRampToValueAtTime(0.0, txEnd + 0.05);
          // Hold mute for 350ms to let AGC and squelch stabilise.
          mg.gain.setValueAtTime(0.0, txEnd + 0.40);
          // Fade back to full gain over 500ms.
          mg.gain.linearRampToValueAtTime(1.0, txEnd + 0.90);
        } catch(e) {}
      })(this.context, whenDeltaMs, bufMs);

      // Set lock until the next TX opportunity (skip one period = DX's RX window).
      // Use real UTC for period boundaries, then convert to patched-time domain for storage.
      var numPer    = Math.round(60000 / fdPeriod);   // 4 for FT8
      var msInMin   = schedReal % 60000;
      var curP      = Math.floor(msInMin / fdPeriod);
      var nextTxP   = (curP + 2) % numPer;
      var nextTxMs  = schedReal - msInMin + nextTxP * fdPeriod;
      if (nextTxMs <= schedReal + bufMs) nextTxMs += 60000;
      window._ft8TxLockUntilMs = nextTxMs + (nowMs - nowReal) - 2000;
      window._ft8TxSeqNo++;

      var rate = this.context ? this.context.sampleRate : 12000;
      var _buf = this.buffer;
      // Send 500ms before the scheduled TX start so Pi is ready when the period begins.
      var sendDelayMs = Math.max(0, whenDeltaMs - 500);
      if (sendDelayMs > 100) {
        setTimeout(function() { _ft8SendTxBuffer(_buf, rate); }, sendDelayMs);
      } else {
        _ft8SendTxBuffer(_buf, rate);
      }
    }
    return _origABSStart.apply(this, arguments);
  };

  function _ft8SendTxBuffer(audioBuffer, sampleRate) {
    var chData = audioBuffer.getChannelData(0);
    var pcm = new Int16Array(chData.length);
    for (var i = 0; i < chData.length; i++) {
      var v = chData[i];
      pcm[i] = v > 1 ? 32767 : v < -1 ? -32768 : (v * 32768) | 0;
    }
    try {
      // Encode Int16 PCM as base64 in 3-byte-aligned chunks (no mid-string '=' padding).
      var bytes = new Uint8Array(pcm.buffer);
      var b64 = '';
      var chunk = 8190;
      for (var offset = 0; offset < bytes.length; offset += chunk) {
        var end = Math.min(offset + chunk, bytes.length);
        var binary = '';
        for (var j = offset; j < end; j++) { binary += String.fromCharCode(bytes[j]); }
        b64 += btoa(binary);
      }
      window.webkit.messageHandlers.ft8api.postMessage({ action: 'audio_tx', rate: sampleRate, data: b64 });
    } catch(e) {}
  }
})();
"""#
        .replacingOccurrences(of: "__LAT_MS__", with: String(latencyMs))
        .replacingOccurrences(of: "__CLOCK_OFFSET_MS__", with: String(clockOffsetMs))
        .replacingOccurrences(of: "__INITHZ__", with: String(ft8InitFreqHz))
        .replacingOccurrences(of: "__APIKEY__", with: safeApiKey)
        .replacingOccurrences(of: "__APIURL__", with: apiUrl)
        .replacingOccurrences(of: "__AUDIO_URL__", with: audioUrl)
        .replacingOccurrences(of: "__CALL__", with: safeCall)
        .replacingOccurrences(of: "__GRID__", with: safeGrid)
    }
}

final class Ft8WebViewCoordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {

    let host: String
    let apiPort: Int
    let apiKey: String
    var onLoadStateChange: ((String?) -> Void)?
    private var retryWork: DispatchWorkItem?
    private static let retryDelaySec: Int = 5

    init(host: String, apiPort: Int, apiKey: String) {
        self.host = host
        self.apiPort = apiPort
        self.apiKey = apiKey
    }

    deinit { retryWork?.cancel() }

    // MARK: - WKScriptMessageHandler

    func userContentController(_ userContentController: WKUserContentController,
                                didReceive message: WKScriptMessage) {
        guard message.name == "ft8api",
              let body = message.body as? [String: Any],
              let action = body["action"] as? String else { return }
        switch action {
        case "setfreq":
            if let hz = body["hz"] as? Int {
                apiPost(path: "/radio/setfreq",
                        body: "f=\(hz)".data(using: .utf8),
                        contentType: "application/x-www-form-urlencoded")
            }
        case "audio_tx":
            if let rate = body["rate"] as? Int,
               let b64  = body["data"] as? String,
               let data = Data(base64Encoded: b64) {
                apiPost(path: "/radio/audio_tx?rate=\(rate)&ptt=1",
                        body: data,
                        contentType: "application/octet-stream")
            }
        default:
            break
        }
    }

    private func apiPost(path: String, body: Data?, contentType: String) {
        guard let url = URL(string: "http://\(host):\(apiPort)\(path)") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = body
        req.setValue(contentType, forHTTPHeaderField: "Content-Type")
        if !apiKey.isEmpty { req.setValue(apiKey, forHTTPHeaderField: "X-API-Key") }
        URLSession.shared.dataTask(with: req).resume()
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        // Accept self-signed certificates for the WebFT8 HTTPS endpoint.
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
           let trust = challenge.protectionSpace.serverTrust {
            completionHandler(.useCredential, URLCredential(trust: trust))
            return
        }
        completionHandler(.performDefaultHandling, nil)
    }

    @available(iOS 15.0, *)
    func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin, initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType, decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        decisionHandler(.grant)
    }

    // MARK: - Load success / failure + auto-retry for Pi startup delay

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        retryWork?.cancel()
        retryWork = nil
        DispatchQueue.main.async { self.onLoadStateChange?(nil) }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        scheduleRetry(webView: webView)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        scheduleRetry(webView: webView)
    }

    private func scheduleRetry(webView: WKWebView) {
        retryWork?.cancel()
        let msg = "⏳ Pi サーバーに接続中… \(Self.retryDelaySec)秒後に再試行"
        DispatchQueue.main.async { self.onLoadStateChange?(msg) }
        let work = DispatchWorkItem { [weak webView] in webView?.reload() }
        retryWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(Self.retryDelaySec), execute: work)
    }
}
