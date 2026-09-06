package com.ji1ore.wifi_rig_ctrl

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ji1ore.wifi_rig_ctrl.data.SCREEN_TIMEOUT_OPTIONS
import com.ji1ore.wifi_rig_ctrl.databinding.FragmentFt8Binding
import com.ji1ore.wifi_rig_ctrl.viewmodel.MainViewModel
import kotlin.math.*

class Ft8Fragment : Fragment() {

    private var _binding: FragmentFt8Binding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()
    private var isFt4 = false

    private var screenTimeoutJob: Job? = null
    private var piProxy: LocalPiProxy? = null
    private var certDialogShown = false
    private var preFt8Freq = 0L
    private var preFt8Mode = ""
    private var preFt8Width = 0
    private data class CqEntry(val freqHz: Int, val call: String, val timeStr: String, val period: Int)
    private val cqList = ArrayDeque<CqEntry>()

    // ── QSO ログ ──────────────────────────────────────────────────────────────
    private data class QsoLogEntry(
        val dt: String,       // UTC "20240603143200"
        val call: String,
        val freqHz: Long,
        val mode: String,
        val rstSent: String,
        val rstRcvd: String,
        val dxGrid: String,
        val myCall: String,
        val myGrid: String
    )

    private fun freqToBand(hz: Long) = when {
        hz in 1_800_000..2_000_000   -> "160M"
        hz in 3_500_000..4_000_000   -> "80M"
        hz in 7_000_000..7_300_000   -> "40M"
        hz in 10_100_000..10_150_000 -> "30M"
        hz in 14_000_000..14_350_000 -> "20M"
        hz in 18_068_000..18_168_000 -> "17M"
        hz in 21_000_000..21_450_000 -> "15M"
        hz in 24_890_000..24_990_000 -> "12M"
        hz in 28_000_000..29_700_000 -> "10M"
        hz in 50_000_000..54_000_000   -> "6M"
        hz in 430_000_000..440_000_000 -> "70cm"
        else -> ""
    }

    private fun loadQsoLog(): MutableList<QsoLogEntry> = try {
        val arr = org.json.JSONArray(vm.prefs.ft8QsoLog)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            QsoLogEntry(o.optString("dt"), o.optString("call"), o.optLong("freq"),
                o.optString("mode","FT8"), o.optString("rstS"), o.optString("rstR"),
                o.optString("grid"), o.optString("myCall"), o.optString("myGrid"))
        }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    private fun saveQsoLog(entries: List<QsoLogEntry>) {
        val arr = org.json.JSONArray()
        entries.forEach { e ->
            arr.put(org.json.JSONObject().apply {
                put("dt", e.dt); put("call", e.call); put("freq", e.freqHz)
                put("mode", e.mode); put("rstS", e.rstSent); put("rstR", e.rstRcvd)
                put("grid", e.dxGrid); put("myCall", e.myCall); put("myGrid", e.myGrid)
            })
        }
        vm.prefs.ft8QsoLog = arr.toString()
    }

    private fun buildAdif(entries: List<QsoLogEntry>): String {
        fun f(tag: String, v: String) = if (v.isNotEmpty()) "<$tag:${v.length}>$v " else ""
        val sb = StringBuilder("ADIF Export from Wifi_RIG_CTRL\n<EOH>\n\n")
        entries.forEach { e ->
            val freq = if (e.freqHz > 0) "%.4f".format(e.freqHz / 1_000_000.0) else ""
            sb.append(f("CALL", e.call))
            sb.append(f("MODE", e.mode))
            sb.append(f("BAND", freqToBand(e.freqHz)))
            sb.append(f("FREQ", freq))
            sb.append(f("QSO_DATE", e.dt.take(8)))
            sb.append(f("TIME_ON", e.dt.drop(8).take(6)))
            sb.append(f("RST_SENT", e.rstSent))
            sb.append(f("RST_RCVD", e.rstRcvd))
            sb.append(f("GRIDSQUARE", e.dxGrid))
            sb.append(f("OPERATOR", e.myCall))
            sb.append(f("MY_GRIDSQUARE", e.myGrid))
            sb.append("<EOR>\n")
        }
        return sb.toString()
    }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) applyGlFromGps()
        else Toast.makeText(requireContext(), "Location permission denied", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val WEBFT8_HTTPS_PORT = 8443

        fun latLonToGrid(lat: Double, lon: Double): String {
            val adjLon = lon + 180.0
            val adjLat = lat + 90.0
            val field = charArrayOf(
                'A' + (adjLon / 20).toInt(),
                'A' + (adjLat / 10).toInt()
            )
            val square = charArrayOf(
                '0' + ((adjLon % 20) / 2).toInt(),
                '0' + (adjLat % 10).toInt()
            )
            return String(field) + String(square)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFt8Binding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Save pre-FT8 freq and mode so we can restore on Back
        preFt8Freq = vm.sharedFreq.value ?: 0L
        preFt8Mode = vm.sharedMode.value ?: ""
        preFt8Width = vm.sharedWidth.value ?: 0

        cqList.clear()
        binding.llCqOverlay.visibility = android.view.View.GONE
        setupWebView()
        setupButtons()
        updatePowerDisplay(vm.sharedPower.value ?: 0f)
        vm.sharedPower.observe(viewLifecycleOwner) { updatePowerDisplay(it) }
        updateFreqOverlay(vm.sharedFreq.value ?: 0L)
        vm.sharedFreq.observe(viewLifecycleOwner) { updateFreqOverlay(it) }
        binding.tvModeOverlay.text = vm.sharedMode.value?.takeIf { it.isNotEmpty() } ?: "---"
        vm.sharedMode.observe(viewLifecycleOwner) { binding.tvModeOverlay.text = it.ifEmpty { "---" } }
        vm.webft8VersionMismatch.observe(viewLifecycleOwner) { mismatch ->
            binding.tvWebft8Warning.visibility = if (mismatch) android.view.View.VISIBLE else android.view.View.GONE
        }

        // Set mode and tune to last FT8 frequency
        val ft8LastFreq = vm.prefs.ft8LastFreq
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                vm.api.setMode(vm.prefs.ft8TxMode, 3000)
                if (ft8LastFreq > 0L) vm.api.setFreq(ft8LastFreq)
            } catch (_: Exception) {}
        }
        if (ft8LastFreq > 0L) vm.sharedFreq.value = ft8LastFreq

        // FT4/FT8 モードを前回の状態から復元
        isFt4 = vm.prefs.ft8IsFt4
        val ft4Tint = if (isFt4) 0xFF1565C0.toInt() else 0xFF37474F.toInt()
        val ft4Text = if (isFt4) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
        binding.btnFt4Toggle.backgroundTintList = android.content.res.ColorStateList.valueOf(ft4Tint)
        binding.btnFt4Toggle.setTextColor(ft4Text)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val hostName = vm.prefs.hostName
        val apiKey = vm.prefs.apiKey
        val myCall = vm.prefs.ft8MyCall
        val myGrid = vm.prefs.ft8MyGrid

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Resolve hostname to IP so Android WebView can connect (WebView cannot resolve mDNS names).
        // Prefer the IP already cached by OkHttp (vm.api) over a fresh InetAddress lookup,
        // because OkHttp may have resolved the hostname earlier when DNS was still available.
        lifecycleScope.launch {
            val host = withContext(Dispatchers.IO) {
                val cached = vm.api.getResolvedHostIp()
                if (cached != null) {
                    android.util.Log.i("Ft8WebView", "cached IP: $hostName -> $cached")
                    return@withContext cached
                }
                try {
                    val resolved = java.net.InetAddress.getByName(hostName).hostAddress ?: hostName
                    android.util.Log.i("Ft8WebView", "resolved: $hostName -> $resolved")
                    resolved
                } catch (e: Exception) {
                    android.util.Log.w("Ft8WebView", "resolve failed: $hostName / ${e.message}")
                    hostName
                }
            }
            setupWebViewWithHost(host, apiKey, myCall, myGrid)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewWithHost(host: String, apiKey: String, myCall: String, myGrid: String) {
        // Start a local HTTP proxy (localhost) that forwards to Pi's HTTPS.
        // WebView loads http://localhost:PORT/ so no SSL is visible to WebView,
        // eliminating any need for SslErrorHandler.proceed().
        piProxy?.stop()
        val proxy = LocalPiProxy(
            piBaseUrl = "https://$host:$WEBFT8_HTTPS_PORT",
            initialPinnedFp = vm.prefs.pinnedCertFingerprint,
            onCertError = { fp, onAccept, onCancel ->
                lifecycleScope.launch(Dispatchers.Main) {
                    if (certDialogShown || !isAdded || activity == null) return@launch
                    certDialogShown = true
                    val configuredHost = vm.prefs.hostName
                    val fpDisplay = fp.chunked(2).joinToString(":").uppercase().take(47) + "…"
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("SSL証明書の確認")
                        .setMessage("${configuredHost} の自己署名証明書\n\n$fpDisplay\n\nこの証明書を信頼しますか？")
                        .setPositiveButton("常に信頼する") { _, _ ->
                            vm.prefs.pinnedCertFingerprint = fp
                            vm.prefs.sslTrustedHost = configuredHost
                            onAccept(fp)
                            certDialogShown = false
                            _binding?.webView?.reload()
                        }
                        .setNeutralButton("今回のみ") { _, _ ->
                            onAccept(fp)
                            certDialogShown = false
                            _binding?.webView?.reload()
                        }
                        .setNegativeButton("キャンセル") { _, _ ->
                            onCancel()
                            certDialogShown = false
                        }
                        .setCancelable(false).show()
                }
            }
        )
        proxy.start()
        piProxy = proxy
        val proxyPort = proxy.port

        val js = buildAudioOverrideJs(host, apiKey, myCall, myGrid, vm.prefs.ft8LatencyMs, vm.prefs.apiPort, vm.prefs.ft8LastFreq, vm.piClockOffsetMs, proxyPort)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(binding.webView, js, setOf("*"))
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                val m = msg?.message() ?: return false
                val lvl = msg.messageLevel()
                if (lvl == android.webkit.ConsoleMessage.MessageLevel.ERROR ||
                    lvl == android.webkit.ConsoleMessage.MessageLevel.WARNING ||
                    m.contains("Audio") || m.contains("error") || m.contains("Error")) {
                    android.util.Log.w("FT8console", "${msg.sourceId()}:${msg.lineNumber()} $m")
                }
                if (m.contains("[ft8]")) android.util.Log.d("FT8js", m)
                if (m.contains("[ft8] cq_rx ")) {
                    val match = Regex("freq=(\\d+) call=(\\S+).*?time=(\\d{6}) period=(\\d)").find(m)
                    if (match != null) {
                        val freq = match.groupValues[1].toIntOrNull() ?: return true
                        val call = match.groupValues[2]
                        val time = match.groupValues[3]
                        val period = match.groupValues[4].toIntOrNull() ?: 0
                        updateCqOverlay(freq, call, time, period)
                    } else {
                        val m2 = Regex("freq=(\\d+) call=(\\S+)").find(m)
                        if (m2 != null) {
                            val freq = m2.groupValues[1].toIntOrNull() ?: return true
                            val call = m2.groupValues[2]
                            updateCqOverlay(freq, call, "", -1)
                        }
                    }
                }
                if (m.contains("[ft8] save_grid: ")) {
                    val grid = m.substringAfter("[ft8] save_grid: ").trim()
                    if (grid.matches(Regex("[A-R]{2}[0-9]{2}([A-X]{2})?"))) {
                        vm.prefs.ft8MyGrid = grid
                        android.util.Log.i("FT8js", "MyGrid saved: $grid")
                    }
                }
                if (m.contains("[ft8] save_call: ")) {
                    val call = m.substringAfter("[ft8] save_call: ").trim()
                    if (call.matches(Regex("[A-Z0-9]{3,13}(/[A-Z0-9]*)?"))) {
                        vm.prefs.ft8MyCall = call
                        android.util.Log.i("FT8js", "MyCall saved: $call")
                    }
                }
                return true
            }
        }

        android.util.Log.i("Ft8WebView", "loadUrl: http://localhost:$proxyPort/")

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view?.evaluateJavascript(js, null)
                }
                // ページ読み込み後に周波数・Sync を自動適用
                lifecycleScope.launch {
                    delay(2500)
                    val b = _binding ?: return@launch
                    // webft8 の localStorage に周波数を再書き込み（UI反映のため）
                    val freq = vm.prefs.ft8LastFreq
                    if (freq > 0L) {
                        b.webView.evaluateJavascript("""
                            (function(){
                              var hz=$freq;
                              localStorage.setItem('webft8-dial',hz);
                              localStorage.setItem('webft8-dialfreq',hz);
                              localStorage.setItem('webft8-freq',hz);
                              window.dispatchEvent(new StorageEvent('storage',{key:'webft8-dial',newValue:''+hz,storageArea:localStorage}));
                            })()
                        """.trimIndent(), null)
                    }
                    // Clock Sync を自動実行（初回1回のみ）
                    b.btnSync.performClick()
                }
            }
        }

        binding.webView.clearCache(true)
        binding.webView.loadUrl("http://localhost:$proxyPort/")
    }

    private fun buildAudioOverrideJs(host: String, apiKey: String, myCall: String, myGrid: String, latencyMs: Int, apiPort: Int, ft8InitFreqHz: Long = 0L, clockOffsetMs: Long = 0L, proxyPort: Int = 0): String {
        val audioUrl = if (proxyPort > 0) "http://localhost:$proxyPort/audio_sub?rate=12000"
                       else "https://$host:$WEBFT8_HTTPS_PORT/audio_sub?rate=12000"
        val apiUrl   = "http://$host:$apiPort"
        val safeApiKey = apiKey.replace("'", "\\'")
        val safeCall = myCall.replace("'", "\\'")
        val safeGrid = myGrid.replace("'", "\\'")

        // language=JavaScript
        return """
// Debug: cross-origin isolation and SharedArrayBuffer availability.
// crossOriginIsolated must be true for SharedArrayBuffer (needed by threaded WASM).
console.log('[ft8] crossOriginIsolated=' + (typeof crossOriginIsolated !== 'undefined' ? crossOriginIsolated : 'N/A')
  + ' SAB=' + (typeof SharedArrayBuffer !== 'undefined'));

// Intercept ALL non-audio fetch calls for full visibility into module/WASM loading.
(function() {
  var _orig = window.fetch;
  window.fetch = function(input, init) {
    var url = typeof input === 'string' ? input
            : (input instanceof Request) ? input.url
            : (input instanceof URL) ? input.href : String(input);
    var skip = url.indexOf('audio') >= 0 || url.indexOf('/ws') >= 0;
    if (!skip) console.log('[ft8] fetch→ ' + url.replace(/.*\//, ''));
    return _orig.apply(this, arguments).then(function(r) {
      if (!skip) console.log('[ft8] fetch← ' + r.status + ' ' + url.replace(/.*\//, ''));
      return r;
    }, function(e) {
      if (!skip) console.log('[ft8] fetch✗ ' + url.replace(/.*\//, '') + ' ' + e);
      throw e;
    });
  };
})();

// Patch Worker() constructor to log worker creation (diagnose module-worker failures).
(function() {
  var _OW = window.Worker;
  if (!_OW) return;
  window.Worker = function(url, opts) {
    var u = (url instanceof URL) ? url.href : String(url);
    console.log('[ft8] Worker(' + u.replace(/.*\//, '') + (opts && opts.type ? ',type=' + opts.type : '') + ')');
    return new _OW(url, opts);
  };
  window.Worker.prototype = _OW.prototype;
})();

// Unregister stale SW and clear its caches; block re-registration this load.
// After a Pi webft8 update the SW often serves old cached app.js/ft8_web.js/WASM,
// causing init() to silently fail. Forcing a fresh fetch from Pi fixes this.
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.getRegistrations().then(function(regs) {
    console.log('[ft8] SW regs=' + regs.length);
    regs.forEach(function(r) { r.unregister(); console.log('[ft8] SW unreg: ' + r.scope); });
  });
  var _origReg = navigator.serviceWorker.register.bind(navigator.serviceWorker);
  navigator.serviceWorker.register = function(url, opts) {
    console.log('[ft8] SW register blocked: ' + url);
    return Promise.resolve({ scope: url });
  };
}
if (window.caches) {
  caches.keys().then(function(names) {
    console.log('[ft8] caches=' + names.length);
    names.forEach(function(n) { caches.delete(n); console.log('[ft8] cache del: ' + n); });
  });
}

// Patch navigator.serviceWorker.ready with a 5s timeout fallback.
// New WebFT8 awaits serviceWorker.ready before loading WASM. On first install
// the SW is in "waiting" state and ready may never resolve without skipWaiting.
// server.py appends skipWaiting to sw.js, but this races as defense-in-depth.
(function() {
  try {
    var swc = navigator.serviceWorker;
    if (!swc) return;
    var desc = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(swc), 'ready')
               || Object.getOwnPropertyDescriptor(swc, 'ready');
    if (!desc || !desc.get) return;
    var origGet = desc.get;
    Object.defineProperty(swc, 'ready', {
      configurable: true,
      get: function() {
        return Promise.race([
          origGet.call(swc),
          new Promise(function(resolve) { setTimeout(function() { resolve(null); }, 5000); })
        ]);
      }
    });
    console.log('[ft8] SW.ready patched with 5s timeout');
  } catch (e) {
    console.log('[ft8] SW.ready patch failed: ' + e);
  }
})();

// Add 430.510 JA to band-header select (not in upstream webft8 index.html).
// Runs after DOM is parsed; idempotent — skips if option already present.
document.addEventListener('DOMContentLoaded', function() {
  var sel = document.getElementById('band-header');
  if (!sel) return;
  for (var i = 0; i < sel.options.length; i++) {
    if (sel.options[i].value === '430.510') return;
  }
  var opt = document.createElement('option');
  opt.value = '430.510';
  opt.text  = '430.510 JA';
  // Insert in frequency order (before the first option whose value > 430.510)
  var ref = null;
  for (var i = 0; i < sel.options.length; i++) {
    if (parseFloat(sel.options[i].value) > 430.510) { ref = sel.options[i]; break; }
  }
  sel.insertBefore(opt, ref);  // ref=null appends at end if no larger value found
});

// Patch all WebAssembly entry points: log + fallback for MIME/COEP proxy issues.
(function() {
  var _origIS = WebAssembly.instantiateStreaming;
  var _origI  = WebAssembly.instantiate;
  var _origCS = WebAssembly.compileStreaming;
  var _origC  = WebAssembly.compile;
  function _hint(src) {
    if (typeof src === 'string') return src;
    if (src && typeof src.url === 'string') return src.url;
    return null;
  }
  if (_origIS) {
    WebAssembly.instantiateStreaming = function(source, importObj) {
      var h = _hint(source);
      return Promise.resolve(source).then(function(resp) {
        if (!h && resp && resp.url) h = resp.url;
        console.log('[ft8] WASM instStream: ' + (h ? h.replace(/.*\//, '') : '?'));
        return _origIS.call(WebAssembly, resp, importObj).then(function(r) {
          var nExp = r && r.instance ? Object.keys(r.instance.exports).length : '?';
          console.log('[ft8] WASM instStream OK exports=' + nExp);
          return r;
        });
      }).catch(function(e) {
        console.log('[ft8] WASM instStream FAIL (' + e + '), fallback');
        if (!h) throw e;
        return fetch(h).then(function(r) { return r.arrayBuffer(); })
          .then(function(buf) {
            console.log('[ft8] WASM instFallback OK');
            return _origI.call(WebAssembly, buf, importObj);
          });
      });
    };
  }
  if (_origCS) {
    WebAssembly.compileStreaming = function(source) {
      var h = _hint(source);
      return Promise.resolve(source).then(function(resp) {
        if (!h && resp && resp.url) h = resp.url;
        console.log('[ft8] WASM compStream: ' + (h ? h.replace(/.*\//, '') : '?'));
        return _origCS.call(WebAssembly, resp);
      }).catch(function(e) {
        console.log('[ft8] WASM compStream FAIL (' + e + '), fallback');
        if (!h) throw e;
        return fetch(h).then(function(r) { return r.arrayBuffer(); })
          .then(function(buf) { return _origC.call(WebAssembly, buf); });
      });
    };
  }
  if (_origI) {
    WebAssembly.instantiate = function(src, importObj) {
      if (src instanceof ArrayBuffer || ArrayBuffer.isView(src)) {
        console.log('[ft8] WASM inst(buf) len=' + (src.byteLength || '?'));
      }
      return _origI.call(WebAssembly, src, importObj);
    };
  }
})();

// Fix Japanese IME (フリック入力) double input in WebView.
// フリック入力は composing を通らないため e.isComposing では捕まらない。
// 50ms以内に同じ e.data が2回来たら2回目をブロック＋DOMを修正（二重入力除去）。
// composing 経由の変換系キーボードは e.isComposing=true のものをブロック。
// Backspace 等の削除は keydown を composing 中だけブロックして保護。
(function() {
  var _lastData = null;
  var _lastDataTime = 0;

  document.addEventListener('input', function(e) {
    if (e.isComposing) {
      e.stopImmediatePropagation();
      return;
    }
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
    if (e.isComposing || e.keyCode === 229) {
      e.stopImmediatePropagation();
    }
  }, true);
})();

// Capture all uncaught JS errors for debugging
window.addEventListener('error', function(e) {
  console.log('[ft8] JS-ERROR: ' + e.message + ' @ ' + e.filename + ':' + e.lineno);
});
window.addEventListener('unhandledrejection', function(e) {
  console.log('[ft8] JS-PROMISE-REJECT: ' + (e.reason && e.reason.message ? e.reason.message : String(e.reason)));
});

// Clock correction: align webft8's Date.now() to Pi UTC time.
// _ft8ClockOffsetMs = Pi_time - Android_time (e.g. -15000 if Android is 15s fast).
// _ft8AudioLatencyMs = audio pipeline delay (shift timing line back to match audio).
// Combined: Date.now() = real_now + clockOffset - audioLatency
window._ft8AudioLatencyMs = $latencyMs;
window._ft8ClockOffsetMs  = $clockOffsetMs;
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
  console.log('[ft8] Date.now: clockOffset=' + window._ft8ClockOffsetMs + 'ms audioLatency=' + window._ft8AudioLatencyMs + 'ms');
})();

(function() {
  if (window._ft8AudioInjected) return;
  window._ft8AudioInjected = true;

  // Clear accumulated time drift from previous session (prevents cumulative drift from multiple Sync presses)
  Object.keys(localStorage).forEach(function(k) {
    if (k.startsWith('webft8-') && (
        k.indexOf('offset') >= 0 || k.indexOf('sync') >= 0 ||
        k.indexOf('ntp') >= 0 || k.indexOf('time') >= 0)) {
      console.log('[ft8] clearing stale localStorage:', k, '=', localStorage.getItem(k));
      localStorage.removeItem(k);
    }
  });

  localStorage.setItem('webft8-mycall', '$safeCall' || 'NOCALL');
  localStorage.setItem('webft8-mygrid', '$safeGrid' || 'QQ00AA');
  localStorage.setItem('webft8-audio-in', 'pi-audio-stream');
  localStorage.setItem('webft8-wf-enable', '1');

  // Set initial frequency from last FT8 session
  if ($ft8InitFreqHz > 0) {
    var _initHz = $ft8InitFreqHz;
    localStorage.setItem('webft8-dial',      _initHz);
    localStorage.setItem('webft8-dialfreq',  _initHz);
    localStorage.setItem('webft8-freq',      _initHz);
    console.log('[ft8] init freq: ' + _initHz + ' Hz');
  }

  // --- Debug overlay: display latest status in upper-right corner ---
  var _dbgDiv = null;
  var _rxBytes = 0;
  function _ft8log(msg) {
    console.log('[ft8] ' + msg);
    if (_dbgDiv) _dbgDiv.textContent = msg;
  }
  window.addEventListener('DOMContentLoaded', function() {
    _dbgDiv = document.createElement('div');
    _dbgDiv.style.cssText = 'position:fixed;bottom:50px;right:2px;background:rgba(0,0,0,0.75);color:#0f0;font:9px monospace;padding:2px 4px;z-index:99999;max-width:180px;border-radius:2px;pointer-events:none;';
    document.body.appendChild(_dbgDiv);
    _ft8log('JS ready');


    // Directly add and select pi-audio-stream option in #audio-device select
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
      _ft8log('audio-device set');
    }
  });

  // --- Find and click the "Start Audio" button ---
  function _tryAutoStart(n) {
    if (n <= 0) { _ft8log('btn-start not found'); return; }
    // ID first (various naming conventions across webft8 versions)
    var byId = document.getElementById('btn-start') ||
               document.getElementById('start-audio') ||
               document.getElementById('startAudio') ||
               document.getElementById('audio-start') ||
               document.getElementById('btn-audio-start');
    if (byId) { byId.click(); _ft8log('clicked #' + byId.id); return; }
    // Text search (case insensitive, exact or "Start Audio" prefix)
    var btns = document.querySelectorAll('button');
    for (var i = 0; i < btns.length; i++) {
      var t = btns[i].textContent.trim().toLowerCase();
      if (t === 'start audio' || t === 'start') {
        btns[i].click();
        _ft8log('clicked btn: ' + btns[i].textContent.trim());
        return;
      }
    }
    setTimeout(function() { _tryAutoStart(n - 1); }, 600);
  }

  window.addEventListener('load', function() {
    setTimeout(function() { _tryAutoStart(30); }, 1200);
  });

  // --- Frequency sync: intercept webft8's localStorage writes → forward to Pi radio ---
  var _freqSyncTimer = null;
  function _parseFreqHz(v) {
    var n = parseFloat(v);
    if (isNaN(n) || n <= 0) return 0;
    if (n >= 1800000) return Math.round(n);          // already Hz (>= 1.8 MHz as Hz)
    if (n >= 1800)    return Math.round(n * 1000);   // kHz (covers VHF: 144460 kHz = 144.460 MHz)
    if (n >= 1.8)     return Math.round(n * 1e6);    // MHz
    return 0;
  }
  function _syncFreqToPi(freqHz) {
    if (_freqSyncTimer) clearTimeout(_freqSyncTimer);
    _freqSyncTimer = setTimeout(function() {
      _ft8log('→Pi freq: ' + freqHz);
      var h = { 'Content-Type': 'application/x-www-form-urlencoded' };
      if ('$safeApiKey') h['X-API-Key'] = '$safeApiKey';
      fetch('$apiUrl/radio/setfreq', { method: 'POST', headers: h, body: 'f=' + freqHz })
        .then(function(r) { _ft8log('freq ' + r.status); })
        .catch(function(e) { _ft8log('freq err: ' + e); });
    }, 600);
  }
  (function() {
    var _origSI = Storage.prototype.setItem;
    Object.defineProperty(Storage.prototype, 'setItem', {
      value: function(key, value) {
        _origSI.call(this, key, value);
        if (this === window.localStorage) {
          var kl = key.toLowerCase();
          // log all short values; skip large JSON blobs
          if (value.length < 80) console.log('[ft8] ls.set key=' + key + ' val=' + value);
          // Sync when numeric frequency written to freq, center, dial, band keys
          if (kl.indexOf('freq') >= 0 || kl.indexOf('center') >= 0 ||
              kl.indexOf('dial') >= 0 || kl.indexOf('band') >= 0) {
            var hz = _parseFreqHz(value);
            if (hz >= 1800000 && hz < 2000000000) _syncFreqToPi(hz);
          }
        }
      },
      configurable: true, writable: true
    });
  })();

  // Intercept AudioContext constructor to capture webft8's own 12 kHz context.
  var _OrigAudioCtx = window.AudioContext || window.webkitAudioContext;
  window._ft8CapturedCtx = null;
  window._ft8AllCtx = [];

  // AudioWorklet.addModule() fails on HTTP because worklet scripts are fetched
  // as module scripts requiring a secure context. Intercept addModule() on ALL
  // AudioContext instances so webft8 never sees a rejection and skips the error UI.
  function _patchAudioWorklet(ctx) {
    var aw = ctx.audioWorklet;
    if (!aw) {
      try {
        Object.defineProperty(ctx, 'audioWorklet', {
          value: { addModule: function() { return Promise.resolve(); } },
          configurable: true
        });
      } catch(e) {}
    } else if (typeof aw.addModule === 'function') {
      try {
        var _origAddModule = aw.addModule.bind(aw);
        Object.defineProperty(aw, 'addModule', {
          value: function(url, opts) {
            return _origAddModule(url, opts).catch(function(e) {
              _ft8log('audioWorklet.addModule suppressed: ' + (url || '') + ' err=' + e);
              return Promise.resolve();
            });
          },
          configurable: true, writable: true
        });
      } catch(e) {}
    }
  }

  function _PatchedAudioCtx(opts) {
    var ctx = new _OrigAudioCtx(opts || {});
    var rate = opts && opts.sampleRate ? opts.sampleRate : 'default';
    _ft8log('AudioCtx created rate=' + rate);
    window._ft8AllCtx.push(ctx);
    if (opts && opts.sampleRate === 12000) {
      window._ft8CapturedCtx = ctx;
    }
    _patchAudioWorklet(ctx);
    return ctx;
  }
  _PatchedAudioCtx.prototype = _OrigAudioCtx.prototype;
  window.AudioContext = _PatchedAudioCtx;
  if (window.webkitAudioContext) window.webkitAudioContext = _PatchedAudioCtx;

  // Resume all contexts including webft8's AudioContext from suspend every second
  // Waterfall goes black when Android WebView throttles after detecting background state
  setInterval(function() {
    var ctxs = window._ft8AllCtx || [];
    for (var i = 0; i < ctxs.length; i++) {
      if (ctxs[i] && ctxs[i].state === 'suspended') {
        ctxs[i].resume().catch(function() {});
      }
    }
  }, 1000);

  // createMediaStreamSource intercept: detect which dest webft8 connects to AudioWorklet,
  // redirect _audioDest/ctx to that dest and start stream. Handles both LAN and VPN
  // call orders (LAN: createMSS after gUM#2; VPN: createMSS after gUM#1).
  var _origCMSS = _OrigAudioCtx.prototype.createMediaStreamSource;
  _OrigAudioCtx.prototype.createMediaStreamSource = function(stream) {
    var active = stream ? stream.active : false;
    var streamId = stream ? stream.id.slice(0,6) : '?';
    _ft8log('createMSS active=' + active + ' id=' + streamId);
    var found = false;
    var prevCtx = _audioCtxRef;  // for detecting context change
    for (var i = 0; i < _allGumDests.length; i++) {
      if (_allGumDests[i].stream === stream) {
        _ft8log('createMSS→feeding gUM#' + (i+1));
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
          // Same context re-initialized: no restart needed (dest follows via dynamic reference)
          _ft8log('createMSS: same ctx, stream alive, skip restart');
        } else {
          // AudioContext changed (VPN reconnect etc.) or first time: always restart
          _ft8log('createMSS: restart (ctxChanged=' + ctxChanged + ')');
          _startStream();
        }
      }, 400);
    }
    return _origCMSS.call(this, stream);
  };

  // Intercept MediaStreamTrack.stop(): detect when webft8 stops tracks
  var _origTrkStop = MediaStreamTrack.prototype.stop;
  MediaStreamTrack.prototype.stop = function() {
    _ft8log('track.stop kind=' + this.kind + ' id=' + this.id.slice(0,6));
    return _origTrkStop.call(this);
  };

  if (!navigator.mediaDevices) {
    try {
      Object.defineProperty(navigator, 'mediaDevices', { value: {}, writable: true, configurable: true });
    } catch(e) { navigator.mediaDevices = {}; }
  }

  // enumerateDevices: return pi-audio-stream device
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

  var _audioDest = null;
  var _monitorGain = null;
  var _audioCtxRef = null;
  var _abortCtrl = null;
  var _allGumDests = [];
  var _gumTimer = null;
  var _streamRunning = false;  // flag indicating whether the stream pump loop is running

  function _startStream() {
    var ctx = _audioCtxRef;
    var dest = _audioDest;
    if (!ctx || !dest) return;
    if (_abortCtrl) { try { _abortCtrl.abort(); } catch(e) {} }
    _abortCtrl = new AbortController();
    _streamRunning = true;
    // Counter phase shift after VPN reconnect: clear webft8's time sync cache to trigger auto-resync
    try {
      Object.keys(localStorage).forEach(function(k) {
        if (k.startsWith('webft8-') && (
            k.indexOf('offset') >= 0 || k.indexOf('sync') >= 0 ||
            k.indexOf('ntp') >= 0 || k.indexOf('time') >= 0)) {
          localStorage.removeItem(k);
        }
      });
    } catch(e) {}
    var signal = _abortCtrl.signal;
    var nextTime = 0;
    var pending = new Uint8Array(0);

    var _pumpCount = 0;
    var _stallTimer = null;
    function _resetStall() {
      if (_stallTimer) clearTimeout(_stallTimer);
      _stallTimer = setTimeout(function() {
        if (!signal.aborted) { _ft8log('stall 30s→restart'); _startStream(); }
      }, 30000);
    }

    // Display received amount and buffer state in overlay every 5 seconds
    var _heartbeat = setInterval(function() {
      if (signal.aborted) { clearInterval(_heartbeat); return; }
      var aheadMs = nextTime > 0 ? Math.round((nextTime - ctx.currentTime) * 1000) : 0;
      _ft8log('alive rx=' + (_rxBytes >> 10) + 'KB buf=' + aheadMs + 'ms');
    }, 5000);

    function pump(reader) {
      _resetStall();
      reader.read().then(function(r) {
        if (signal.aborted) { _streamRunning = false; clearTimeout(_stallTimer); clearInterval(_heartbeat); return; }
        if (r.done) {
          _streamRunning = false;
          clearTimeout(_stallTimer); clearInterval(_heartbeat);
          // TX直後は素早くリトライして最初のRXピリオドの損失を最小化
          var _recentTx = window._ft8TxDoneAt && (performance.now() - window._ft8TxDoneAt) < 8000;
          var _endRetryMs = _recentTx ? 300 : (Date.now() < (window._ft8TxLockUntilMs || 0) ? 1500 : 5000);
          _ft8log('stream ended, retry ' + _endRetryMs + 'ms...');
          setTimeout(function() { if (!signal.aborted) _startStream(); }, _endRetryMs);
          return;
        }
        var combined = new Uint8Array(pending.length + r.value.length);
        combined.set(pending);
        combined.set(r.value, pending.length);
        var samples = Math.floor(combined.length / 2);
        var used = samples * 2;
        pending = combined.slice(used);
        if (samples > 0) {
          _rxBytes += used;
          _pumpCount++;
          if (_pumpCount === 1 && window._ft8TxDoneAt) {
            var firstMs = Math.round(performance.now() - window._ft8TxDoneAt);
            _ft8log('rx#1 first audio ' + firstMs + 'ms after TX done');
            console.log('[ft8] first audio after TX done: ' + firstMs + 'ms');
          } else if (_pumpCount <= 5 || _pumpCount % 10 === 0) {
            _ft8log('rx ' + (_rxBytes >> 10) + 'KB #' + _pumpCount);
          }
          var buf = ctx.createBuffer(1, samples, 12000);
          var data = buf.getChannelData(0);
          var view = new DataView(combined.slice(0, used).buffer);
          for (var i = 0; i < samples; i++) data[i] = view.getInt16(i*2, true) / 32768.0;
          var src = ctx.createBufferSource();
          src.buffer = buf;
          src._ft8IsRx = true;
          src.connect(_audioDest || dest);  // createMSS更新後の新destにも対応
          if (_monitorGain) src.connect(_monitorGain);
          if (nextTime > 0 && nextTime < ctx.currentTime - 0.5) { nextTime = 0; }
          // Cap buffer at 1000ms to prevent sox startup over-accumulation
          if (nextTime > 0 && nextTime - ctx.currentTime > 1.0) { nextTime = ctx.currentTime + 0.15; }
          var t = Math.max(nextTime, ctx.currentTime + 0.15);
          src.start(t);
          nextTime = t + samples / 12000;
        }
        pump(reader);
      }).catch(function(e) {
        _streamRunning = false;
        clearTimeout(_stallTimer); clearInterval(_heartbeat);
        if (!signal.aborted) {
          _ft8log('stream err, retry 5s...');
          setTimeout(function() { if (!signal.aborted) _startStream(); }, 5000);
        }
      });
    }

    var headers = {};
    if ('$safeApiKey') headers['X-API-Key'] = '$safeApiKey';
    var _fetchStartMs = performance.now();
    var _lagMs = window._ft8TxDoneAt ? Math.round(_fetchStartMs - window._ft8TxDoneAt) : 0;
    _ft8log('fetching audio_sub... lag=' + _lagMs + 'ms after TX done');
    console.log('[ft8] fetch start lag=' + _lagMs + 'ms utcSec=' + (Math.floor(Date.now()/1000)%60));

    // Periodically resume to prevent AudioContext from suspending
    var _keepAlive = setInterval(function() {
      if (signal.aborted) { clearInterval(_keepAlive); return; }
      if (ctx.state === 'suspended') {
        ctx.resume().then(function() { _ft8log('ctx resumed'); });
      }
    }, 3000);

    ctx.resume().then(function() {
      fetch('$audioUrl', { headers: headers, signal: signal }).then(function(resp) {
        if (!resp.ok) {
          _streamRunning = false;
          var ms = Math.round(performance.now() - _fetchStartMs);
          _ft8log('audio_sub err ' + resp.status + ' after ' + ms + 'ms, retry 2s');
          clearInterval(_keepAlive); clearInterval(_heartbeat);
          if (!signal.aborted) setTimeout(function() { if (!signal.aborted) _startStream(); }, 2000);
          return;
        }
        var connMs = Math.round(performance.now() - _fetchStartMs);
        var totalMs = window._ft8TxDoneAt ? Math.round(performance.now() - window._ft8TxDoneAt) : 0;
        _ft8log('audio_sub connected conn=' + connMs + 'ms total=' + totalMs + 'ms after TX done');
        console.log('[ft8] connected conn=' + connMs + 'ms total=' + totalMs + 'ms');
        pump(resp.body.getReader());
      }).catch(function(e) {
        _streamRunning = false;
        var ms = Math.round(performance.now() - _fetchStartMs);
        clearInterval(_keepAlive); clearInterval(_heartbeat);
        if (!signal.aborted) {
          var _recentTx2 = window._ft8TxDoneAt && (performance.now() - window._ft8TxDoneAt) < 8000;
          var _retryMs = _recentTx2 ? 300 : 2000;
          _ft8log('fetch err after ' + ms + 'ms: ' + e + ', retry ' + _retryMs + 'ms');
          setTimeout(function() { if (!signal.aborted) _startStream(); }, _retryMs);
        }
      });
    });
  }

  window._ft8ReconnectAudio = function() {
    // TX lock が有効な間はwebft8の再接続要求を無視する。
    // TX送信中に audio_sub が再接続するとダブルTXと webft8 状態機械の乱れが起きる。
    // TX done 後に _startStream() が直接呼ばれるため、ここでの遅延は問題ない。
    var lockRemain = (window._ft8TxLockUntilMs || 0) - Date.now();
    if (lockRemain > 0) {
      _ft8log('reconnect blocked: TX lock ' + Math.round(lockRemain/1000) + 's remain');
      return;
    }
    _ft8log('reconnecting...');
    setTimeout(_startStream, 600);
  };

  window._ft8StopAudio = function() {
    _streamRunning = false;
    if (_abortCtrl) { try { _abortCtrl.abort(); } catch(e) {} _abortCtrl = null; }
    _ft8log('stream stopped');
  };

  window.addEventListener('ft8-audio-reconnect', window._ft8ReconnectAudio);

  var _gumCount = 0;
  navigator.mediaDevices.getUserMedia = function(constraints) {
    return new Promise(function(resolve, reject) {
      try {
        _gumCount++;
        _ft8log('getUserMedia #' + _gumCount);
        var ctx = window._ft8CapturedCtx;
        if (!ctx) {
          ctx = new _OrigAudioCtx({ sampleRate: 12000 });
          window._ft8CapturedCtx = ctx;
          _ft8log('created fallback 12k ctx');
        }
        var returnDest = ctx.createMediaStreamDestination();
        _allGumDests.push(returnDest);
        _ft8log('gUM#' + _gumCount + ' dest=' + returnDest.stream.id.slice(0,6));
        // Pre-arm dest so _startStream fires even if createMediaStreamSource is never called
        // (newer webft8 may use AudioWorklet instead of createMSS).
        // createMSS intercept will update _audioDest/ctx later if it fires.
        if (!_audioDest) {
          _audioDest = returnDest;
          _audioCtxRef = ctx;
          if (_gumTimer) clearTimeout(_gumTimer);
          _gumTimer = setTimeout(function() {
            if (!_streamRunning) { _ft8log('gUM fallback→start'); _startStream(); }
          }, 600);
        }
        resolve(returnDest.stream);
      } catch(e) { _ft8log('gUM err: ' + e); reject(e); }
    });
  };

  // ---- FT8 TX: intercept webft8's large AudioBuffer playback and stream to Pi ----
  // Lock stored in window scope so it survives IIFE re-execution and AudioContext recreation.
  // webFT8 in CQ-auto mode generates TX on EVERY 15s period; we enforce TX/RX alternation here.
  if (!window._ft8TxLockUntilMs)      window._ft8TxLockUntilMs = 0;
  if (!window._ft8TxSeqNo)            window._ft8TxSeqNo = 0;

  var _ft8SentBuffers = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
  var _origABSStart = AudioBufferSourceNode.prototype.start;
  AudioBufferSourceNode.prototype.start = function(when, offset, duration) {
    // Diagnostic: log every AudioBufferSourceNode.start() call
    if (this.buffer && this.buffer.duration > 0.1) {
      console.log('[ft8] abs.start dur=' + this.buffer.duration.toFixed(2) + 's rx=' + (!!this._ft8IsRx) +
                  ' when=' + (when !== undefined ? (+when).toFixed(3) : 'undef') +
                  ' lock=' + Math.round((window._ft8TxLockUntilMs - Date.now())/1000) + 's');
    }
    if (this.buffer && this.buffer.duration > 5.0 && !this._ft8IsRx) {
      var nowMs  = Date.now();  // patched: real + clockOffset - latency; delta is still correct
      var bufMs  = this.buffer.duration * 1000;
      var fdPeriod = bufMs > 10000 ? 15000 : 7500;  // FT8=15s, FT4=7.5s

      // Calc UTC period for logging (use real time)
      var nowReal = nowMs + window._ft8AudioLatencyMs - window._ft8ClockOffsetMs;
      var sec60   = Math.floor(nowReal / 1000) % 60;
      var utcPer  = Math.floor(sec60 / 15);

      // webft8 schedules reply TX via .start(futureWhen) — compute delta so lock and
      // Pi-send timing are judged against the SCHEDULED start, not the call time.
      // Cap at one period to ignore bogus far-future values.
      var whenDeltaMs = 0;
      if (when && this.context && when > this.context.currentTime + 0.1) {
        whenDeltaMs = Math.min(Math.round((when - this.context.currentTime) * 1000), fdPeriod);
      }
      var schedMs   = nowMs   + whenDeltaMs;  // scheduled TX start in patched-time domain
      var schedReal = nowReal + whenDeltaMs;  // scheduled TX start in real-time domain
      var schedSec60 = Math.floor(schedReal / 1000) % 60;
      var schedPer   = Math.floor(schedSec60 / (fdPeriod / 1000));

      // Same-period-as-DX guard: mute TX if it would fire in DX's own period.
      // _ft8DxIsEven is set from decoded rx message timing (MutationObserver).
      // Fires BEFORE the lock so a wrong-period attempt doesn't poison the lock.
      if (window._ft8DxIsEven !== undefined) {
        var _txIsEven = (schedPer % 2 === 0);
        if (_txIsEven === window._ft8DxIsEven) {
          _ft8log('TX WRONG PERIOD: tx=' + (_txIsEven?'even':'odd') +
                  ' dxEven=' + window._ft8DxIsEven + ' per=' + schedPer + ' utc=' + schedSec60 + 's');
          try { this.disconnect(); } catch(e) {}
          try {
            var _wg2 = this.context.createGain();
            _wg2.gain.value = 0; _wg2.connect(this.context.destination);
            this.connect(_wg2);
          } catch(e) {}
          return _origABSStart.call(this, arguments[0], 0, 0.05);
        }
      }

      // Window-level lock check — use SCHEDULED start so pre-queued replies aren't muted
      if (schedMs < window._ft8TxLockUntilMs) {
        var remS = ((window._ft8TxLockUntilMs - schedMs) / 1000).toFixed(1);
        _ft8log('TX muted: locked ' + remS + 's sched+' + Math.round(whenDeltaMs/1000) + 's utc=' + sec60 + 's');
        // 50ms だけ再生: onended を即座に発火させて webFT8 の TX 状態を解除する
        // フル再生(12.64s)すると次の period 開始をまたいで webFT8 のスケジュールが狂う
        try { this.disconnect(); } catch(e) {}
        try {
          var _mg = this.context.createGain();
          _mg.gain.value = 0;
          _mg.connect(this.context.destination);
          this.connect(_mg);
        } catch(e) {}
        return _origABSStart.call(this, arguments[0], 0, 0.05);
      }
      // Same-buffer dedup (catches same AudioBuffer re-started)
      if (_ft8SentBuffers && _ft8SentBuffers.has(this.buffer)) {
        _ft8log('TX skip: dup buf');
        return _origABSStart.apply(this, arguments);
      }
      if (_ft8SentBuffers) _ft8SentBuffers.add(this.buffer);

      // ロックを次のTX period 境界に精密アライン（サイクルスキップ防止）
      // スケジュール時刻ベースで計算することで、reply の pre-queue を正確に扱う
      var _numPer = Math.round(60000 / fdPeriod);   // 4(FT8) or 8(FT4)
      var _msInMin = schedReal % 60000;
      var _curP = Math.floor(_msInMin / fdPeriod);
      var _nextTxP = (_curP + 2) % _numPer;
      var _nextTxRealMs = schedReal - _msInMin + _nextTxP * fdPeriod;
      if (_nextTxRealMs <= schedReal + bufMs) _nextTxRealMs += 60000;
      // nowMs と nowReal の差を保持してロックのドメインを合わせる
      window._ft8TxLockUntilMs = _nextTxRealMs + (nowMs - nowReal) - 2000;
      window._ft8TxSeqNo++;
      var rate = this.context ? this.context.sampleRate : 12000;
      _ft8log('TX#' + window._ft8TxSeqNo + ' ' + this.buffer.duration.toFixed(1) +
              's sched+' + Math.round(whenDeltaMs/1000) + 's period=' + schedPer + ' utc=' + schedSec60 + 's nextTxP=' + _nextTxP);
      console.log('[ft8] TX#' + window._ft8TxSeqNo + ' buf=' + this.buffer.duration.toFixed(2) +
                  's rate=' + rate + ' schedDelta=' + whenDeltaMs + 'ms utcSec=' + schedSec60 + ' period=' + schedPer +
                  ' lockUntil+' + Math.round((bufMs+fdPeriod)/1000) + 's');
      // Pi への送信はTXピリオド境界の500ms前に行う（即時送信すると早着して誤タイミングTX）
      var _buf = this.buffer;
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
    // TX中も audio_sub を維持する（_ft8StopAudio は呼ばない）。
    // Pi 側が _mgr_sub.mute(13.0) でサイレンスを送るため:
    //   - ウォーターフォールはサイレンスで暗くなる（= 停止相当）
    //   - デコーダーはサイレンスで継続動作
    // ft8-audio-reconnect は TX ロック中にブロック済み → ダブルTX不要
    var chData = audioBuffer.getChannelData(0);
    var pcm = new Int16Array(chData.length);
    for (var i = 0; i < chData.length; i++) {
      var v = chData[i];
      pcm[i] = v > 1 ? 32767 : v < -1 ? -32768 : (v * 32768) | 0;
    }
    var url = '$apiUrl/radio/audio_tx?rate=' + sampleRate + '&ptt=1';
    var headers = { 'Content-Type': 'application/octet-stream' };
    if ('$safeApiKey') headers['X-API-Key'] = '$safeApiKey';
    _ft8log('TX send ' + (pcm.byteLength >> 10) + 'KB');
    console.log('[ft8] TX: sending ' + pcm.byteLength + ' bytes at ' + sampleRate + 'Hz');
    var _txAbort = new AbortController();
    window._ft8TxAbortCtrl = _txAbort;
    fetch(url, { method: 'POST', body: pcm.buffer, headers: headers, signal: _txAbort.signal })
      .then(function(r) {
        window._ft8TxAbortCtrl = null;
        window._ft8TxDoneAt = performance.now();
        var utcSec = Math.floor(Date.now()/1000) % 60;
        _ft8log('TX done:' + r.status + ' utcSec=' + utcSec);
        console.log('[ft8] TX done:', r.status, 'utcSec=' + utcSec);
        if (r.status === 200) {
          // ストリームが生きていれば再起動不要（Pi の unmute で音声が自動復旧する）
          if (!_streamRunning) {
            _startStream();
          } else {
            // 生きていても 500ms 後に再チェック: TX中に切れていた場合を早期検出
            setTimeout(function() { if (!_streamRunning) _startStream(); }, 500);
          }
        }
      })
      .catch(function(e) {
        window._ft8TxAbortCtrl = null;
        if (e && e.name === 'AbortError') { _ft8log('TX aborted'); return; }
        _ft8log('TX err:' + e); console.error('[ft8] TX error:', e);
        if (!_streamRunning) setTimeout(function() { _startStream(); }, 500);
      });
  }

  window._ft8AbortTx = function() {
    var ctrl = window._ft8TxAbortCtrl;
    if (ctrl) { try { ctrl.abort(); } catch(e) {} window._ft8TxAbortCtrl = null; }
  };


  // ---- CQ frequency display: watch webFT8 DOM for decoded CQ messages ----
  (function() {
    var _seenCqs = {};
    // DOM text format from webft8: "{freq_hz} {dt} {snr} {message}"
    // e.g. "1234 +0.2 -5 CQ JF1AWC/P PM84"  (no HHMMSS in DOM)
    function _parseCqRow(text) {
      var m = text.match(/\b(\d{3,4})\s+[+-]?\d+\.?\d*\s+[+-]?\d+\s+CQ\s+(?:[A-Z]{1,2}\s+)?([A-Z0-9\/]+)\s+([A-R]{2}\d{2})\b/i);
      if (!m) return null;
      var freq = parseInt(m[1]);
      if (freq < 300 || freq > 3000) return null;
      // Derive period from current time: decode arrives within first ~3s of new period
      var _ss60 = Math.floor(Date.now() / 1000) % 60;
      var _curPS = Math.floor(_ss60 / 15) * 15;       // current period start (sec)
      var _endPS = (_curPS - 15 + 60) % 60;           // ended period start (sec)
      var period = Math.floor(_endPS / 15);            // 0-3
      var timeStr = ('0' + Math.floor(_ss60 / 60)).slice(-2) + ('0' + (_ss60 % 60)).slice(-2) + '00';
      return { time: timeStr, freq: freq, call: m[2], grid: m[3], period: period };
    }
    // Helper: update _ft8DxIsEven from current time when a decode batch arrives
    function _updateDxPeriod() {
      var _ss60 = Math.floor(Date.now() / 1000) % 60;
      var _curPS = Math.floor(_ss60 / 15) * 15;
      var _endPS = (_curPS - 15 + 60) % 60;
      var _endPer = Math.floor(_endPS / 15);
      window._ft8DxIsEven = (_endPer % 2 === 0);
    }
    function _onNewNodes(nodes) {
      for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        if (node.nodeType !== 1) continue;
        // Any new rx decoded message → update DX period tracking
        if (node.classList && node.classList.contains('chat-msg') && node.classList.contains('rx')) {
          _updateDxPeriod();
        }
        if (node.nodeType !== 1 && node.nodeType !== 3) continue;
        var text = (node.textContent || '').replace(/\s+/g, ' ').trim();
        if (text.indexOf('CQ') < 0) continue;
        var sources = [text];
        if (node.parentElement) sources.push((node.parentElement.textContent || '').replace(/\s+/g, ' ').trim());
        for (var j = 0; j < sources.length; j++) {
          var cq = _parseCqRow(sources[j]);
          if (!cq) continue;
          var period15 = Math.floor(Date.now() / 15000);
          var key = cq.call + '_' + period15;
          if (_seenCqs[key]) break;
          _seenCqs[key] = true;
          var cutoff = period15 - 8;
          Object.keys(_seenCqs).forEach(function(k) {
            if (parseInt(k.split('_').pop()) < cutoff) delete _seenCqs[k];
          });
          console.log('[ft8] cq_rx freq=' + cq.freq + ' call=' + cq.call + ' grid=' + cq.grid + ' time=' + cq.time + ' period=' + cq.period + ' dxEven=' + window._ft8DxIsEven);
          break;
        }
      }
    }
    var _cqObserver = new MutationObserver(function(mutations) {
      for (var i = 0; i < mutations.length; i++) {
        _onNewNodes(mutations[i].addedNodes);
      }
    });
    window.addEventListener('load', function() {
      setTimeout(function() {
        _cqObserver.observe(document.body, { childList: true, subtree: true });
        _ft8log('CQ observer active');
        // Mirror webft8 DOM status updates to logcat (setStatus() only updates DOM, not console)
        function _observeStatus(id, label) {
          var el = document.getElementById(id);
          if (!el) { console.log('[ft8] status-obs: no #' + id); return; }
          new MutationObserver(function() {
            var t = (el.textContent || '').trim();
            if (t) console.log('[ft8] ' + label + ': ' + t);
          }).observe(el, { characterData: true, childList: true, subtree: true });
          console.log('[ft8] status-obs: watching #' + id);
        }
        _observeStatus('scout-tx-queue',   'tx');
        _observeStatus('scout-decode-info','dec');

        // Block clicks on RR73 / 73 decoded rows — these end the QSO and need no reply.
        // Uses capture phase so we intercept before webft8's bubble listener on the div.
        document.addEventListener('click', function(e) {
          var el = e.target;
          while (el && el !== document.body) {
            if (el.classList && el.classList.contains('chat-msg') && el.classList.contains('rx')) {
              var textEl = el.querySelector('.text');
              var txt = (textEl ? textEl.textContent : el.textContent) || '';
              if (/\bRR73\b|\s73\s*$/.test(txt)) {
                e.stopImmediatePropagation();
                console.log('[ft8] click blocked (end-of-QSO): ' + txt.trim().slice(0, 40));
              }
              break;
            }
            el = el.parentElement;
          }
        }, true);
        console.log('[ft8] RR73 click-block active');

        // Save manually-entered My Grid / My Call back to Android SharedPreferences.
        // webft8 saves these to its own localStorage but Android prefs are not updated.
        // We emit console messages that onConsoleMessage parses and persists.
        function _watchInput(id, tag) {
          var el = document.getElementById(id);
          if (!el) return;
          var _last = el.value;
          el.addEventListener('change', function() {
            var v = (el.value || '').trim().toUpperCase();
            if (v && v !== _last) { _last = v; console.log('[ft8] ' + tag + ': ' + v); }
          });
        }
        _watchInput('my-grid', 'save_grid');
        _watchInput('my-call', 'save_call');
      }, 3000);
    });
  })();

  console.log('[ft8] audio override ready → $audioUrl');
})();
        """.trimIndent()
    }

    private fun updateCqOverlay(freqHz: Int, call: String, timeStr: String, period: Int) {
        _binding ?: return
        // 同一コールサイン or 同一(freq,period)スロットの既存エントリを置換
        cqList.removeAll { it.call == call || (it.freqHz == freqHz && it.period == period) }
        cqList.addFirst(CqEntry(freqHz, call, timeStr, period))
        while (cqList.size > 5) cqList.removeLast()
        binding.tvCqOverlay.text = cqList.joinToString("\n") {
            val t = if (it.timeStr.length >= 4) "${it.timeStr.substring(0,2)}:${it.timeStr.substring(2,4)} " else ""
            "${t}%4dHz %s".format(it.freqHz, it.call)
        }
        binding.llCqOverlay.visibility = android.view.View.VISIBLE
    }

    private fun shareAdif(entries: List<QsoLogEntry>) {
        val adif = buildAdif(entries)
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val fname = "qso_%04d%02d%02d.adi".format(
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH)+1,
            cal.get(java.util.Calendar.DAY_OF_MONTH))
        val file = java.io.File(requireContext().cacheDir, fname)
        file.writeText(adif)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(), "${requireContext().packageName}.provider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, fname)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "ADIF を共有"))
    }

    private fun updatePowerDisplay(power: Float) {
        val pct = (power * 100).toInt()
        binding.btnPower.text = "PWR ${pct}"
    }

    private fun updateFreqOverlay(freqHz: Long) {
        binding.tvFreqOverlay.text = if (freqHz <= 0L) "---.--- MHz"
        else "%.3f MHz".format(freqHz / 1_000_000.0)
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener {
            // Restore pre-FT8 frequency and mode
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        if (preFt8Mode.isNotEmpty()) vm.api.setMode(preFt8Mode, preFt8Width)
                        if (preFt8Freq > 0L) vm.api.setFreq(preFt8Freq)
                    } catch (_: Exception) {}
                }
                if (preFt8Freq > 0L) vm.sharedFreq.value = preFt8Freq
                if (preFt8Mode.isNotEmpty()) vm.sharedMode.value = preFt8Mode
                findNavController().popBackStack()
            }
        }

        binding.btnReload.setOnClickListener {
            binding.webView.reload()
        }

        binding.btnLog.setOnClickListener {
            val entries = loadQsoLog()
            if (entries.isEmpty()) {
                Toast.makeText(requireContext(), "QSO log is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // サマリー表示: 日時 コール バンド RST
            val summary = entries.joinToString("\n") { e ->
                val d = e.dt.take(8).let { "${it.take(4)}-${it.drop(4).take(2)}-${it.drop(6)}" }
                val t = e.dt.drop(8).take(6).let { "${it.take(2)}:${it.drop(2).take(2)}Z" }
                val b = freqToBand(e.freqHz).ifEmpty { "${e.freqHz/1000}kHz" }
                "%-8s %-6s %-5s %-4s %-4s %s".format(e.call, b, e.mode, e.rstSent, e.rstRcvd, "$d $t")
            }
            val scroll = android.widget.ScrollView(requireContext()).apply {
                addView(android.widget.TextView(requireContext()).apply {
                    text = summary
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(16, 16, 16, 16)
                })
            }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("QSO Log (${entries.size} QSOs)")
                .setView(scroll)
                .setPositiveButton("Export ADIF") { _, _ -> shareAdif(entries) }
                .setNeutralButton("Clear") { _, _ ->
                    android.app.AlertDialog.Builder(requireContext())
                        .setMessage("Delete all QSO logs?")
                        .setPositiveButton("Delete") { _, _ ->
                            vm.prefs.ft8QsoLog = "[]"
                            Toast.makeText(requireContext(), "QSO log cleared", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
                .setNegativeButton("Close", null)
                .show()
        }

        // Mode toggle: USB <-> PKTUSB
        binding.btnMode.text = vm.prefs.ft8TxMode
        binding.btnMode.setOnClickListener {
            val next = if (vm.prefs.ft8TxMode == "USB") "PKTUSB" else "USB"
            vm.prefs.ft8TxMode = next
            binding.btnMode.text = next
            lifecycleScope.launch(Dispatchers.IO) {
                try { vm.api.setMode(next, 3000) } catch (_: Exception) {}
            }
        }

        // Power: tap = list picker, long press = exact value dialog
        val powerSteps = listOf(0.05f, 0.10f, 0.20f, 0.30f, 0.40f, 0.50f, 0.60f, 0.70f, 0.80f, 0.90f, 1.00f)
        val powerLabels = powerSteps.map { "${(it * 100).toInt()}%" }.toTypedArray()
        binding.btnPower.setOnClickListener {
            val current = vm.sharedPower.value ?: 0f
            val checkedIdx = powerSteps.indexOfFirst { kotlin.math.abs(it - current) < 0.03f }.coerceAtLeast(0)
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Power")
                .setSingleChoiceItems(powerLabels, checkedIdx) { dialog, which ->
                    val v = powerSteps[which]
                    vm.sharedPower.value = v
                    lifecycleScope.launch(Dispatchers.IO) {
                        try { vm.api.setPower(v) } catch (_: Exception) {}
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnPower.setOnLongClickListener {
            val edit = android.widget.EditText(requireContext()).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                val pct = ((vm.sharedPower.value ?: 0f) * 100).toInt()
                setText(pct.toString())
                selectAll()
            }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Power (%)")
                .setView(edit)
                .setPositiveButton("OK") { _, _ ->
                    val pct = edit.text.toString().toFloatOrNull() ?: return@setPositiveButton
                    val v = (pct / 100f).coerceIn(0f, 1f)
                    vm.sharedPower.value = v
                    lifecycleScope.launch(Dispatchers.IO) {
                        try { vm.api.setPower(v) } catch (_: Exception) {}
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        binding.btnGl.setOnClickListener {
            val hasCoarse = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasFine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasCoarse || hasFine) applyGlFromGps()
            else locationPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }

        binding.btnFt4Toggle.setOnClickListener {
            isFt4 = !isFt4
            val tintColor = if (isFt4) 0xFF1565C0.toInt() else 0xFF37474F.toInt()
            val textColor = if (isFt4) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
            binding.btnFt4Toggle.backgroundTintList =
                android.content.res.ColorStateList.valueOf(tintColor)
            binding.btnFt4Toggle.setTextColor(textColor)
        }

        binding.btnSync.setOnClickListener {
            // Re-sync Pi clock offset then reconnect audio.
            // audioLatencyMs = max(0, clockOffset + TARGET_PIPELINE_MS) keeps the
            // Date.now() shift equal to the actual audio pipeline delay regardless
            // of how far the Pi clock drifts from Android/UTC.
            // TARGET_PIPELINE_MS = 2000ms → total Date.now() shift ≈ 2 seconds
            val TARGET_PIPELINE_MS = 2000L
            lifecycleScope.launch {
                val offset = withContext(Dispatchers.IO) {
                    try { vm.api.getPiClockOffsetMs() ?: vm.piClockOffsetMs }
                    catch (_: Exception) { vm.piClockOffsetMs }
                }
                vm.piClockOffsetMs = offset
                val newLatencyMs = maxOf(0L, offset + TARGET_PIPELINE_MS).toInt()
                vm.prefs.ft8LatencyMs = newLatencyMs
                binding.webView.evaluateJavascript(
                    "window._ft8ClockOffsetMs=$offset; window._ft8AudioLatencyMs=$newLatencyMs; window._ft8TxLockUntilMs=0; window._ft8TxSeqNo=0;", null)
                val dumpJs = """
(function(){
  return JSON.stringify({
    clockOffsetMs: window._ft8ClockOffsetMs,
    latencyMs: window._ft8AudioLatencyMs,
    ft8Injected: !!window._ft8AudioInjected,
    ctxState: window._ft8CapturedCtx ? window._ft8CapturedCtx.state : 'none'
  });
})()""".trimIndent()
                binding.webView.evaluateJavascript(dumpJs) { result ->
                    android.util.Log.d("Ft8Fragment", "sync: offset=${offset}ms latency=${newLatencyMs}ms diag=$result")
                    Toast.makeText(requireContext(),
                        "Clock synced: ${offset}ms / latency: ${newLatencyMs}ms",
                        Toast.LENGTH_SHORT).show()
                }
                binding.webView.evaluateJavascript(
                    "window._ft8ReconnectAudio && window._ft8ReconnectAudio()", null)
            }
        }

        // Sync長押し: audioLatencyMsを手動設定
        binding.btnSync.setOnLongClickListener {
            val current = vm.prefs.ft8LatencyMs
            val edit = android.widget.EditText(requireContext()).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(current.toString())
                selectAll()
            }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Audio Latency (ms)")
                .setMessage("デコードタイミング補正値を直接設定\n現在: ${current}ms\n(Sync短押しで自動再計算)")
                .setView(edit)
                .setPositiveButton("OK") { _, _ ->
                    val newMs = edit.text.toString().toIntOrNull()?.coerceIn(0, 10000)
                        ?: return@setPositiveButton
                    vm.prefs.ft8LatencyMs = newMs
                    binding.webView.evaluateJavascript(
                        "window._ft8AudioLatencyMs=$newMs; console.log('[ft8] latency manual=' + $newMs);", null)
                    Toast.makeText(requireContext(), "Latency: ${newMs}ms", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyGlFromGps() {
        val lm = requireContext().getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val loc = providers.firstNotNullOfOrNull { p ->
            try { lm?.getLastKnownLocation(p) } catch (_: Exception) { null }
        }
        if (loc == null) {
            Toast.makeText(requireContext(), "No GPS fix yet", Toast.LENGTH_SHORT).show()
            return
        }
        val grid = latLonToGrid(loc.latitude, loc.longitude)
        vm.prefs.ft8MyGrid = grid
        val safeGrid = grid.replace("'", "\\'")
        // Directly update localStorage and webft8's MyGrid input field
        val js = """
(function(){
  localStorage.setItem('webft8-mygrid','$safeGrid');
  var inputs = document.querySelectorAll('input');
  for(var i=0;i<inputs.length;i++){
    var v=(inputs[i].value||'').trim().toUpperCase();
    if(/^[A-R]{2}[0-9]{2}/.test(v)||(inputs[i].placeholder||'').toLowerCase().indexOf('grid')>=0||(inputs[i].id||'').toLowerCase().indexOf('grid')>=0){
      inputs[i].value='$safeGrid';
      inputs[i].dispatchEvent(new Event('input',{bubbles:true}));
      inputs[i].dispatchEvent(new Event('change',{bubbles:true}));
    }
  }
})()""".trimIndent()
        binding.webView.evaluateJavascript(js, null)
        Toast.makeText(requireContext(), "MyGrid: $grid", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        vm.ft8FragmentActive = true
        vm.stopAudio()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val toIdx = vm.selectedTimeoutIndex.value ?: vm.prefs.savedTimeoutIndex
        val timeoutMin = SCREEN_TIMEOUT_OPTIONS.getOrElse(toIdx) { 0 }
        screenTimeoutJob?.cancel()
        if (timeoutMin > 0) {
            screenTimeoutJob = lifecycleScope.launch {
                delay(timeoutMin * 60_000L)
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        // Don't reconnect if stream is running (prevents unnecessary disconnect on temporary pause from notifications etc.)
        _binding?.webView?.evaluateJavascript(
            "if(window._streamRunning){console.log('[ft8] onResume: running, skip')}else{window._ft8ReconnectAudio&&window._ft8ReconnectAudio()}",
            null)
    }

    override fun onPause() {
        super.onPause()
        vm.ft8FragmentActive = false
        screenTimeoutJob?.cancel()
        screenTimeoutJob = null
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val freq = vm.sharedFreq.value ?: 0L
        if (freq > 0L) vm.prefs.ft8LastFreq = freq
        vm.prefs.ft8IsFt4 = isFt4  // FT4/FT8 モード保存
        // Stop audio in onPause on screen transitions such as back navigation
        // (onStop is called after MainControlFragment.onResume, causing ALSA conflicts)
        // Temporary pause from notifications has isRemoving=false so audio continues
        if (isRemoving) {
            _binding?.webView?.evaluateJavascript(
                "window._ft8StopAudio && window._ft8StopAudio(); window._ft8AbortTx && window._ft8AbortTx()", null)
            // PTT誤表示防止: Main画面遷移前に即座にクリアし、外部PTT誤検出も抑制
            vm.txEnabled.value = false
            vm.suppressExternalPttDetection()
            // ViewModel スコープで PTT OFF（lifecycleScope はフラグメント破棄でキャンセルされる）
            vm.releasePttBackground()
        }
    }

    override fun onStop() {
        super.onStop()
        // Stop when app goes to background (pause with isRemoving=false)
        // When isRemoving=true, already stopped in onPause, but double-stop is safe
        _binding?.webView?.evaluateJavascript(
            "window._ft8StopAudio && window._ft8StopAudio(); window._ft8AbortTx && window._ft8AbortTx()", null)
        vm.releasePttBackground()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.webView.destroy()
        _binding = null
        piProxy?.stop()
        piProxy = null
    }
}
