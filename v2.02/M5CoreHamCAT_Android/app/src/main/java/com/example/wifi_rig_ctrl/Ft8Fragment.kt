package com.ji1ore.wifi_rig_ctrl

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.http.SslError
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
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
        val host = vm.prefs.hostName
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

        // Redirect getUserMedia to Pi's audio_sub (HTTPS same origin)
        val js = buildAudioOverrideJs(host, apiKey, myCall, myGrid, vm.prefs.ft8LatencyMs, vm.prefs.apiPort, vm.prefs.ft8LastFreq, vm.piClockOffsetMs)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(binding.webView, js, setOf("*"))
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                val m = msg?.message() ?: return false
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
                return true
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

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

        binding.webView.loadUrl("https://$host:$WEBFT8_HTTPS_PORT/")
    }

    private fun buildAudioOverrideJs(host: String, apiKey: String, myCall: String, myGrid: String, latencyMs: Int, apiPort: Int, ft8InitFreqHz: Long = 0L, clockOffsetMs: Long = 0L): String {
        val audioUrl = "https://$host:$WEBFT8_HTTPS_PORT/audio_sub?rate=12000"
        val apiUrl   = "http://$host:$apiPort"
        val safeApiKey = apiKey.replace("'", "\\'")
        val safeCall = myCall.replace("'", "\\'")
        val safeGrid = myGrid.replace("'", "\\'")

        // language=JavaScript
        return """
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.getRegistrations().then(function(regs) {
    regs.forEach(function(r) { r.unregister(); console.log('[ft8] unregistered sw:', r.scope); });
  });
}

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

    // webFT8 title is cut off in portrait mode — hide it after render
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
    // ID first
    var byId = document.getElementById('btn-start');
    if (byId) { byId.click(); _ft8log('clicked #btn-start'); return; }
    // Text search
    var btns = document.querySelectorAll('button');
    for (var i = 0; i < btns.length; i++) {
      if (btns[i].textContent.trim() === 'Start Audio') {
        btns[i].click();
        _ft8log('clicked "Start Audio"');
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
    if (n > 100000)  return Math.round(n);          // already Hz
    if (n >= 100)    return Math.round(n * 1000);   // kHz
    if (n >= 0.1)    return Math.round(n * 1e6);    // MHz
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
            if (hz > 1000000 && hz < 2000000000) _syncFreqToPi(hz);
          }
        }
      },
      configurable: true, writable: true
    });
  })();

  // Capture frequency input via DOM input/change events (also handles implementations without localStorage)
  window.addEventListener('load', function() {
    document.addEventListener('change', function(e) {
      var val = (e.target.value || '').replace(/[,\s]/g, '').trim();
      var hz = _parseFreqHz(val);
      if (hz > 1000000 && hz < 2000000000) {
        _ft8log('UI→Pi: ' + hz + 'Hz');
        _syncFreqToPi(hz);
      }
    }, true);
  });

  // Intercept AudioContext constructor to capture webft8's own 12 kHz context.
  var _OrigAudioCtx = window.AudioContext || window.webkitAudioContext;
  window._ft8CapturedCtx = null;
  window._ft8AllCtx = [];
  function _PatchedAudioCtx(opts) {
    var ctx = new _OrigAudioCtx(opts || {});
    var rate = opts && opts.sampleRate ? opts.sampleRate : 'default';
    _ft8log('AudioCtx created rate=' + rate);
    window._ft8AllCtx.push(ctx);
    if (opts && opts.sampleRate === 12000) {
      window._ft8CapturedCtx = ctx;
    }
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
    if (this.buffer && this.buffer.duration > 5.0 && !this._ft8IsRx) {
      var nowMs  = Date.now();  // patched: real + clockOffset - latency; delta is still correct
      var bufMs  = this.buffer.duration * 1000;
      var fdPeriod = bufMs > 10000 ? 15000 : 7500;  // FT8=15s, FT4=7.5s

      // Calc UTC period for logging (use real time)
      var nowReal = nowMs + window._ft8AudioLatencyMs - window._ft8ClockOffsetMs;
      var sec60   = Math.floor(nowReal / 1000) % 60;
      var utcPer  = Math.floor(sec60 / 15);

      // Window-level lock check (main guard — survives IIFE re-runs)
      if (nowMs < window._ft8TxLockUntilMs) {
        var remS = ((window._ft8TxLockUntilMs - nowMs) / 1000).toFixed(1);
        _ft8log('TX muted: locked ' + remS + 's utc=' + sec60 + 's period=' + utcPer);
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
      // curPeriod+2 の period 開始時刻の 2秒前にロック解除 → webft8 に準備時間を与える
      var _numPer = Math.round(60000 / fdPeriod);   // 4(FT8) or 8(FT4)
      var _msInMin = nowReal % 60000;
      var _curP = Math.floor(_msInMin / fdPeriod);
      var _nextTxP = (_curP + 2) % _numPer;
      var _nextTxRealMs = nowReal - _msInMin + _nextTxP * fdPeriod;
      if (_nextTxRealMs <= nowReal + bufMs) _nextTxRealMs += 60000;
      // nowMs と nowReal の差を保持してロックのドメインを合わせる
      window._ft8TxLockUntilMs = _nextTxRealMs + (nowMs - nowReal) - 2000;
      window._ft8TxSeqNo++;
      var rate = this.context ? this.context.sampleRate : 12000;
      _ft8log('TX#' + window._ft8TxSeqNo + ' ' + this.buffer.duration.toFixed(1) +
              's period=' + utcPer + ' utc=' + sec60 + 's nextTxP=' + _nextTxP);
      console.log('[ft8] TX#' + window._ft8TxSeqNo + ' buf=' + this.buffer.duration.toFixed(2) +
                  's rate=' + rate + ' utcSec=' + sec60 + ' period=' + utcPer +
                  ' lockUntil+' + Math.round((bufMs+fdPeriod)/1000) + 's');
      _ft8SendTxBuffer(this.buffer, rate);
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
    function _parseCqRow(text) {
      // FT8 decode row: "HHMMSS dt freq snr CQ [DX] callsign grid"
      var m = text.match(/\b(\d{6})\s+[+-]?\d+\.?\d*\s+(\d{3,4})\s+[+-]?\d+\s+CQ\s+(?:[A-Z]{1,2}\s+)?([A-Z0-9\/]+)\s+([A-R]{2}\d{2})\b/);
      if (!m) return null;
      var timeStr = m[1];                          // HHMMSS
      var freq = parseInt(m[2]);
      if (freq < 300 || freq > 3000) return null;
      var ss = parseInt(timeStr.slice(4, 6));       // seconds within minute
      var period = Math.floor(ss / 15);             // 0-3
      return { time: timeStr, freq: freq, call: m[3], grid: m[4], period: period };
    }
    function _onNewNodes(nodes) {
      for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        if (node.nodeType !== 1 && node.nodeType !== 3) continue;
        var text = (node.textContent || '').replace(/\s+/g, ' ').trim();
        if (text.indexOf('CQ') < 0) continue;
        // Check self and parent for full row context (table row may split across children)
        var sources = [text];
        if (node.parentElement) sources.push((node.parentElement.textContent || '').replace(/\s+/g, ' ').trim());
        for (var j = 0; j < sources.length; j++) {
          var cq = _parseCqRow(sources[j]);
          if (!cq) continue;
          var period15 = Math.floor(Date.now() / 15000);
          var key = cq.call + '_' + period15;
          if (_seenCqs[key]) break;
          _seenCqs[key] = true;
          // Prune old entries
          var cutoff = period15 - 8;
          Object.keys(_seenCqs).forEach(function(k) {
            if (parseInt(k.split('_').pop()) < cutoff) delete _seenCqs[k];
          });
          console.log('[ft8] cq_rx freq=' + cq.freq + ' call=' + cq.call + ' grid=' + cq.grid + ' time=' + cq.time + ' period=' + cq.period);
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
            // Re-sync Pi clock offset then reconnect audio
            lifecycleScope.launch {
                val offset = withContext(Dispatchers.IO) {
                    try { vm.api.getPiClockOffsetMs() ?: vm.piClockOffsetMs }
                    catch (_: Exception) { vm.piClockOffsetMs }
                }
                vm.piClockOffsetMs = offset
                binding.webView.evaluateJavascript(
                    "window._ft8ClockOffsetMs=$offset; window._ft8TxLockUntilMs=0; window._ft8TxSeqNo=0;", null)
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
                    android.util.Log.d("Ft8Fragment", "sync: offset=${offset}ms diag=$result")
                    Toast.makeText(requireContext(), "Clock synced: ${offset}ms", Toast.LENGTH_SHORT).show()
                }
                binding.webView.evaluateJavascript(
                    "window._ft8ReconnectAudio && window._ft8ReconnectAudio()", null)
            }
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
    }
}
