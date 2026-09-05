import SwiftUI
@preconcurrency import WebKit

struct Ft8View: View {
    @Bindable var vm: MainViewModel
    @State private var webViewRef: WKWebView?
    @State private var showLatencyEditor: Bool = false
    @State private var latencyText: String = ""
    @State private var showPowerPicker: Bool = false
    @State private var showFreqPicker: Bool = false

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
            Ft8WebView(vm: vm) { webView in
                self.webViewRef = webView
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
        .confirmationDialog("Band / Freq", isPresented: $showFreqPicker) {
            ForEach(ft8Bands, id: \.hz) { band in
                Button(band.label) { setFrequency(band.hz) }
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
            Button(vm.ft8TxMode) {
                let next = vm.ft8TxMode == "USB" ? "PKTUSB" : "USB"
                webViewRef?.evaluateJavaScript("window._ft8AudioLatencyMs = \(vm.ft8LatencyMs)")
                Task { await vm.setFt8TxMode(next) }
            }
            Button {
                showPowerPicker = true
            } label: {
                Text("PWR \(Int(vm.sharedPower * 100))")
                    .font(.caption.monospacedDigit())
            }
            Button {
                showFreqPicker = true
            } label: {
                Text(vm.sharedFreq <= 0 ? "--.-"
                     : String(format: "%.3f", Double(vm.sharedFreq) / 1_000_000.0))
                    .font(.caption.monospacedDigit())
            }
            Spacer()
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
                        .keyboardType(.decimalPad)
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
        // Update WebFT8's dial frequency so its own display and decoder stay in sync
        webViewRef?.evaluateJavaScript("""
        (function(){
          var hz = \(hz);
          localStorage.setItem('webft8-dial', hz);
          localStorage.setItem('webft8-dialfreq', hz);
          localStorage.setItem('webft8-freq', hz);
          var inp = document.querySelector('input[id*="freq"],input[id*="dial"]');
          if (inp) {
            inp.value = (hz/1e6).toFixed(6);
            inp.dispatchEvent(new Event('change', { bubbles: true }));
          }
        })();
        """)
    }
}

// MARK: - WKWebView Wrapper

struct Ft8WebView: UIViewRepresentable {
    let vm: MainViewModel
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

        DispatchQueue.main.async { onReady(webView) }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    static func buildInjectionJS(host: String, apiKey: String, apiPort: Int, myCall: String, myGrid: String, latencyMs: Int, ft8InitFreqHz: Int64) -> String {
        // Same-origin HTTPS fetch: page loaded at https://host:8443/, audio also fetched from
        // https://host:8443/audio_sub — same origin, no cross-origin block.
        // SSL trust is already established when the page loaded (Ft8WebViewCoordinator accepted
        // the self-signed cert), so subsequent same-origin fetches reuse the same trust decision.
        // pistream:// custom scheme was blocked because https:// → pistream:// is cross-origin.
        let audioUrl = "https://\(host):\(AppConstants.webFt8HttpsPort)/audio_sub?rate=12000"
        let apiUrl = "https://\(host):\(AppConstants.webFt8HttpsPort)"
        let safeApiKey = apiKey.replacingOccurrences(of: "'", with: "\\'")
        let safeCall = myCall.replacingOccurrences(of: "'", with: "\\'")
        let safeGrid = myGrid.replacingOccurrences(of: "'", with: "\\'")
        return #"""
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.getRegistrations().then(function(regs) {
    regs.forEach(function(r) { r.unregister(); });
  });
}

window._ft8AudioLatencyMs = __LAT_MS__;
(function() {
  var _origDateNow = Date.now.bind(Date);
  var _OrigDate = window.Date;
  function _PatchedDate() {
    if (arguments.length === 0) {
      return new _OrigDate(_origDateNow() - window._ft8AudioLatencyMs);
    }
    return new (Function.prototype.bind.apply(_OrigDate, [null].concat(Array.prototype.slice.call(arguments))))();
  }
  _PatchedDate.prototype = _OrigDate.prototype;
  _PatchedDate.now = function() { return _origDateNow() - window._ft8AudioLatencyMs; };
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
      _ci.inputMode = 'url';
      if ('__CALL__') { _ci.value = '__CALL__'; _ci.dispatchEvent(new Event('input', { bubbles: true })); }
    }
    var _gi = document.getElementById('my-grid');
    if (_gi) {
      _gi.inputMode = 'url';
      if ('__GRID__') { _gi.value = '__GRID__'; _gi.dispatchEvent(new Event('input', { bubbles: true })); }
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
      // Guard: only click when showing "Start *" (not "Stop *").
      var _webStart = document.getElementById('btn-start');
      if (_webStart && /^start/i.test((_webStart.textContent || '').trim())) {
        _webStart.click();
      }
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
    if (n > 100000)  return Math.round(n);
    if (n >= 100)    return Math.round(n * 1000);
    if (n >= 0.1)    return Math.round(n * 1e6);
    return 0;
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
            var hz = _parseFreqHz(value);
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
      var hz = _parseFreqHz(val);
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
    // STEP 2 (async): pre-register 'ft8-audio-processor' blob so AudioWorkletNode
    // creation succeeds even if audio-processor.js is missing or fails to load.
    try {
      var _code =
        'class _FP extends AudioWorkletProcessor{' +
        'constructor(){super();this._r=false;this._wb=new Float32Array(512);' +
        'this._wi=0;this._pa=0;this._pc=0;' +
        'this.port.onmessage=e=>{const t=e.data&&e.data.type;' +
        'if(t==="start"){this._r=true;this.port.postMessage({type:"info",nativeRate:12000,snapshotRate:12000,waterfallRate:6000});}' +
        'else if(t==="stop")this._r=false;};' +
        '}' +
        'process(ins){if(!this._r)return true;' +
        'const inp=ins[0]&&ins[0][0];if(!inp||!inp.length)return true;' +
        'for(let i=0;i<inp.length;i++){const v=Math.abs(inp[i]);if(v>this._pa)this._pa=v;}' +
        'this._pc+=inp.length;' +
        'if(this._pc>=1200){this.port.postMessage({type:"peak",level:this._pa});this._pa=0;this._pc=0;}' +
        'for(let j=0;j+1<inp.length;j+=2){this._wb[this._wi++]=(inp[j]+inp[j+1])*0.5;' +
        'if(this._wi>=512){const c=this._wb.slice();this.port.postMessage({type:"waterfall",samples:c},[c.buffer]);this._wi=0;}}' +
        'return true;}}' +
        'registerProcessor("ft8-audio-processor",_FP);';
      var _blob = new Blob([_code], {type:'application/javascript'});
      var _blobUrl = URL.createObjectURL(_blob);
      _nativeAM(_blobUrl).then(function() {
        URL.revokeObjectURL(_blobUrl);
      }).catch(function(e) {
        URL.revokeObjectURL(_blobUrl);
      });
    } catch(e) {}
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
        var _wfBuf = new Float32Array(512), _wi = 0, _pa = 0, _pc = 0, _run = false;
        scp.port = {
          onmessage: null,
          postMessage: function(msg) {
            if (!msg) return;
            if (msg.type === 'start') {
              _run = true;
              scp.port.onmessage && scp.port.onmessage({data:{type:'info',nativeRate:12000,snapshotRate:12000,waterfallRate:6000}});
            } else if (msg.type === 'stop') { _run = false; }
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
              var chunk = _wfBuf.slice();
              scp.port.onmessage && scp.port.onmessage({data:{type:'waterfall',samples:chunk}});
              _wi = 0;
            }
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
    var ctx = new _OrigAudioCtx(opts || {});
    window._ft8AllCtx.push(ctx);
    if (opts && opts.sampleRate === 12000) {
      window._ft8CapturedCtx = ctx;
    }
    _patchAudioWorklet(ctx);
    // Resume immediately while still on the user-gesture call stack (if applicable).
    // WebFT8 often creates AudioContext inside the tap/click handler; resuming here
    // succeeds because iOS considers this the same gesture activation.
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
        _monitorGain = null;
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
          for (var i = 0; i < samples; i++) data[i] = view.getInt16(i*2, true) / 32768.0;
          var src = ctx.createBufferSource();
          src.buffer = buf;
          src._ft8IsRx = true;
          src.connect(_audioDest || dest);
          if (_monitorGain) src.connect(_monitorGain);
          if (nextTime > 0 && nextTime < ctx.currentTime - 0.5) { nextTime = 0; }
          var t = Math.max(nextTime, ctx.currentTime + 0.05);
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

  var _origABSStart = AudioBufferSourceNode.prototype.start;
  AudioBufferSourceNode.prototype.start = function(when, offset, duration) {
    if (this.buffer && this.buffer.duration > 5.0 && !this._ft8IsRx) {
      _ft8SendTxBuffer(this.buffer, this.context ? this.context.sampleRate : 12000);
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
      // Encode Int16 PCM as base64 in 3-byte-aligned chunks to avoid mid-stream padding.
      var bytes = new Uint8Array(pcm.buffer);
      var b64 = '';
      var chunk = 8190; // 8190 = 3 × 2730, divisible by 3 → no mid-string '=' padding
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

    init(host: String, apiPort: Int, apiKey: String) {
        self.host = host
        self.apiPort = apiPort
        self.apiKey = apiKey
    }

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
}
