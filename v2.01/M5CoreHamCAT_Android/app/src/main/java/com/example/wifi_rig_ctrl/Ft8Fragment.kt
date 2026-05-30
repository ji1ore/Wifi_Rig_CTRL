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
            val subSqLon = (adjLon % 2) / 2.0 * 24
            val subSqLat = (adjLat % 1) * 24
            val subsq = charArrayOf(
                'A' + subSqLon.toInt(),
                'A' + subSqLat.toInt()
            )
            return String(field) + String(square) + String(subsq)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFt8Binding.inflate(inflater, container, false)
        // Clear stale storage such as accumulated time drift every time
        android.webkit.WebStorage.getInstance().deleteAllData()
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Save pre-FT8 freq and mode so we can restore on Back
        preFt8Freq = vm.sharedFreq.value ?: 0L
        preFt8Mode = vm.sharedMode.value ?: ""
        preFt8Width = vm.sharedWidth.value ?: 0

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
        val js = buildAudioOverrideJs(host, apiKey, myCall, myGrid, vm.prefs.ft8LatencyMs, vm.prefs.apiPort, vm.prefs.ft8LastFreq)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(binding.webView, js, setOf("*"))
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
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
            }
        }

        binding.webView.loadUrl("https://$host:$WEBFT8_HTTPS_PORT/")
    }

    private fun buildAudioOverrideJs(host: String, apiKey: String, myCall: String, myGrid: String, latencyMs: Int, apiPort: Int, ft8InitFreqHz: Long = 0L): String {
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

// Audio pipeline latency compensation: shift webft8's Date.now() so the
// timing line aligns with the delayed audio arriving from the Pi.
window._ft8AudioLatencyMs = $latencyMs;
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
  console.log('[ft8] Date.now shifted by -' + window._ft8AudioLatencyMs + 'ms');
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
          _ft8log('stream ended, retry 5s...');
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
          _rxBytes += used;
          _pumpCount++;
          if (_pumpCount <= 5 || _pumpCount % 10 === 0) {
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
          var t = Math.max(nextTime, ctx.currentTime + 0.30);
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
    _ft8log('fetching audio_sub...');

    // Periodically resume to prevent AudioContext from suspending
    var _keepAlive = setInterval(function() {
      if (signal.aborted) { clearInterval(_keepAlive); return; }
      if (ctx.state === 'suspended') {
        ctx.resume().then(function() { _ft8log('ctx resumed'); });
      }
    }, 3000);

    ctx.resume().then(function() {
      fetch('$audioUrl', { headers: headers, signal: signal }).then(function(resp) {
        if (!resp.ok) { _streamRunning = false; _ft8log('audio_sub err ' + resp.status); clearInterval(_keepAlive); clearInterval(_heartbeat); return; }
        _ft8log('audio_sub connected');
        pump(resp.body.getReader());
      }).catch(function(e) {
        _streamRunning = false;
        if (!signal.aborted) { _ft8log('fetch err: ' + e); clearInterval(_keepAlive); clearInterval(_heartbeat); }
      });
    });
  }

  window._ft8ReconnectAudio = function() {
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
  // webft8 pre-computes the full TX waveform (~12.6s) as a single AudioBuffer,
  // then plays it via AudioBufferSourceNode. We intercept start() on large buffers
  // (>1s) to distinguish TX from the small RX chunks we enqueue ourselves.
  var _origABSStart = AudioBufferSourceNode.prototype.start;
  AudioBufferSourceNode.prototype.start = function(when, offset, duration) {
    if (this.context === window._ft8CapturedCtx &&
        this.buffer && this.buffer.duration > 5.0 &&
        !this._ft8IsRx) {
      _ft8log('TX start ' + this.buffer.duration.toFixed(1) + 's');
      _ft8SendTxBuffer(this.buffer, this.context.sampleRate);
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
    var url = '$apiUrl/radio/audio_tx?rate=' + sampleRate + '&ptt=1';
    var headers = { 'Content-Type': 'application/octet-stream' };
    if ('$safeApiKey') headers['X-API-Key'] = '$safeApiKey';
    _ft8log('TX send ' + (pcm.byteLength >> 10) + 'KB');
    console.log('[ft8] TX: sending ' + pcm.byteLength + ' bytes at ' + sampleRate + 'Hz');
    fetch(url, { method: 'POST', body: pcm.buffer, headers: headers })
      .then(function(r) { _ft8log('TX done:' + r.status); console.log('[ft8] TX done:', r.status); })
      .catch(function(e) { _ft8log('TX err:' + e); console.error('[ft8] TX error:', e); });
  }

  console.log('[ft8] audio override ready → $audioUrl');
})();
        """.trimIndent()
    }

    private fun latencyLabel(ms: Int) = "%.1fs".format(ms / 1000.0)

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

        // Latency offset: short tap = +500ms, long press = enter exact value
        binding.btnLatency.text = latencyLabel(vm.prefs.ft8LatencyMs)
        binding.btnLatency.setOnClickListener {
            val next = (vm.prefs.ft8LatencyMs + 500) % 10500
            vm.prefs.ft8LatencyMs = next
            binding.btnLatency.text = latencyLabel(next)
            binding.webView.evaluateJavascript("window._ft8AudioLatencyMs=$next", null)
        }
        binding.btnLatency.setOnLongClickListener {
            val edit = android.widget.EditText(requireContext()).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText("%.1f".format(vm.prefs.ft8LatencyMs / 1000.0))
                selectAll()
            }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Audio latency (seconds)")
                .setMessage("Tap + to adjust +0.5s. Enter exact value here.\nIncrease if line is early; decrease if line is late.")
                .setView(edit)
                .setPositiveButton("OK") { _, _ ->
                    val sec = edit.text.toString().toFloatOrNull() ?: return@setPositiveButton
                    val ms = (sec * 1000).toInt().coerceIn(0, 10000)
                    vm.prefs.ft8LatencyMs = ms
                    binding.btnLatency.text = latencyLabel(ms)
                    binding.webView.evaluateJavascript("window._ft8AudioLatencyMs=$ms", null)
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
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
            val dumpJs = """
(function(){
  return JSON.stringify({
    crossOriginIsolated: window.crossOriginIsolated,
    SharedArrayBuffer: typeof SharedArrayBuffer,
    WebAssembly: typeof WebAssembly,
    AudioContext: typeof AudioContext,
    ft8Injected: !!window._ft8AudioInjected,
    ft8Ctx: !!window._ft8CapturedCtx,
    ctxState: window._ft8CapturedCtx ? window._ft8CapturedCtx.state : 'none',
    ctxRate: window._ft8CapturedCtx ? window._ft8CapturedCtx.sampleRate : 0
  });
})()""".trimIndent()
            binding.webView.evaluateJavascript(dumpJs) { result ->
                android.util.Log.d("Ft8Fragment", "diag: $result")
            }
            binding.webView.evaluateJavascript(
                "window._ft8ReconnectAudio && window._ft8ReconnectAudio()", null)
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
        // Stop audio in onPause on screen transitions such as back navigation
        // (onStop is called after MainControlFragment.onResume, causing ALSA conflicts)
        // Temporary pause from notifications has isRemoving=false so audio continues
        if (isRemoving) {
            _binding?.webView?.evaluateJavascript(
                "window._ft8StopAudio && window._ft8StopAudio()", null)
            lifecycleScope.launch(Dispatchers.IO) {
                try { vm.api.setPtt(false) } catch (_: Exception) {}
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Stop when app goes to background (pause with isRemoving=false)
        // When isRemoving=true, already stopped in onPause, but double-stop is safe
        _binding?.webView?.evaluateJavascript(
            "window._ft8StopAudio && window._ft8StopAudio()", null)
        lifecycleScope.launch(Dispatchers.IO) {
            try { vm.api.setPtt(false) } catch (_: Exception) {}
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.webView.destroy()
        _binding = null
    }
}
