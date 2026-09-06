#!/usr/bin/env python3
import http.server
import os
import ssl
import urllib.request

_VERSION = "2.60"

def _load_api_key():
    try:
        env_path = os.path.join(os.path.expanduser("~"), "fastapi", ".env")
        with open(env_path) as f:
            for line in f:
                line = line.strip()
                if line.startswith('API_KEY=') and not line.startswith('#'):
                    return line.split('=', 1)[1].strip()
    except Exception:
        pass
    return ''

_API_KEY = _load_api_key()

class WebFt8Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Cross-Origin-Opener-Policy', 'same-origin')
        self.send_header('Cross-Origin-Embedder-Policy', 'credentialless')
        super().end_headers()

    def guess_type(self, path):
        if str(path).endswith('.wasm'):
            return 'application/wasm'
        return super().guess_type(path)

    def do_GET(self):
        if self.path == '/server_version':
            body = f'{{"version":"{_VERSION}"}}'.encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path.startswith('/audio_sub'):
            qs = self.path[len('/audio_sub'):]
            url = 'http://127.0.0.1:8000/radio/audio_sub?rate=12000'
            if qs.startswith('?'):
                url += '&' + qs[1:]
            req = None
            try:
                req_obj = urllib.request.Request(url)
                if _API_KEY:
                    req_obj.add_header('X-API-Key', _API_KEY)
                req = urllib.request.urlopen(req_obj, timeout=None)
                self.send_response(200)
                self.send_header('Content-Type', 'application/octet-stream')
                self.send_header('Cross-Origin-Opener-Policy', 'same-origin')
                self.send_header('Cross-Origin-Embedder-Policy', 'credentialless')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                while True:
                    chunk = req.read(4096)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
            except Exception as e:
                try:
                    self.send_error(503, str(e))
                except Exception:
                    pass
            finally:
                if req:
                    try:
                        req.close()
                    except Exception:
                        pass
        elif self.path in ('/', '/index.html') or self.path.startswith('/index.html?'):
            # Inject SW.ready timeout patch so WASM loading never hangs if the SW
            # has not yet activated (first install / waiting state after unregister).
            try:
                with open('./index.html', 'rb') as f:
                    html = f.read()
                patch = (
                    b'<script>(function(){try{'
                    b'var s=navigator.serviceWorker;if(!s)return;'
                    b'var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(s),"ready")'
                    b'||Object.getOwnPropertyDescriptor(s,"ready");'
                    b'if(!d||!d.get)return;var og=d.get;'
                    b'Object.defineProperty(s,"ready",{configurable:true,get:function(){'
                    b'return Promise.race([og.call(s),'
                    b'new Promise(function(r){setTimeout(function(){r(null);},5000);})'
                    b']);}});'
                    b'}catch(e){}})();</script>'
                )
                idx = html.find(b'<head>')
                if idx >= 0:
                    html = html[:idx+6] + patch + html[idx+6:]
                else:
                    html = patch + html
                self.send_response(200)
                self.send_header('Content-Type', 'text/html; charset=utf-8')
                self.send_header('Content-Length', str(len(html)))
                self.end_headers()
                self.wfile.write(html)
            except Exception:
                super().do_GET()
        elif self.path == '/sw.js' or self.path.startswith('/sw.js?'):
            # Append skipWaiting + clients.claim so the SW activates immediately
            # on install. Without this, first-install SW stays in "waiting" state
            # and navigator.serviceWorker.ready never resolves → WASM hangs.
            try:
                with open('./sw.js', 'rb') as f:
                    js = f.read()
                extra = (
                    b"\nself.addEventListener('install',()=>self.skipWaiting());"
                    b"\nself.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));\n"
                )
                js = js + extra
                self.send_response(200)
                self.send_header('Content-Type', 'application/javascript')
                self.send_header('Content-Length', str(len(js)))
                self.end_headers()
                self.wfile.write(js)
            except Exception:
                super().do_GET()
        else:
            super().do_GET()

    def do_POST(self):
        if self.path.startswith('/audio_tx'):
            qs = self.path[len('/audio_tx'):]
            target = 'http://127.0.0.1:8000/radio/audio_tx?rate=12000&ptt=1'
            if qs.startswith('?'):
                target += '&' + qs[1:]
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length) if length else b''
            try:
                req_obj = urllib.request.Request(target, data=body, method='POST')
                req_obj.add_header('Content-Type', 'application/octet-stream')
                if _API_KEY:
                    req_obj.add_header('X-API-Key', _API_KEY)
                resp = urllib.request.urlopen(req_obj, timeout=60)
                resp_body = resp.read()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(resp_body)))
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self.wfile.write(resp_body)
            except Exception as e:
                try:
                    self.send_error(503, str(e))
                except Exception:
                    pass
        else:
            self.send_response(405)
            self.send_header('Allow', 'GET, POST')
            self.end_headers()

    def log_message(self, format, *args):
        pass

# Refresh all JS files from GitHub on every startup to fix version mismatches after Pi updates.
# Large/binary files (WASM, images, JSON) are only downloaded if missing.
_HERE = os.path.dirname(os.path.abspath(__file__))
_BASE_URL = 'https://raw.githubusercontent.com/jl1nie/webft8/main/docs'
for _f in [
    'index.html', 'app.js', 'sw.js', 'ft8_web.js', 'decode-worker.js',
    'waterfall.js', 'audio-capture.js', 'audio-output.js', 'audio-processor.js',
    'ft8-period.js', 'qso.js', 'cat.js', 'gps-nmea.js',
    'qso-log.js', 'wav-save.js', 'ble-transport.js', 'rig-profiles.json',
]:
    try:
        with urllib.request.urlopen(_BASE_URL + '/' + _f, timeout=30) as _r:
            with open(os.path.join(_HERE, _f), 'wb') as _out:
                _out.write(_r.read())
    except Exception:
        pass
for _f in ['ft8_web_bg.wasm', 'manifest.json', 'icon-192.png', 'icon-512.png']:
    if not os.path.exists(os.path.join(_HERE, _f)):
        try:
            with urllib.request.urlopen(_BASE_URL + '/' + _f, timeout=60) as _r:
                with open(os.path.join(_HERE, _f), 'wb') as _out:
                    _out.write(_r.read())
        except Exception:
            pass

httpd = http.server.ThreadingHTTPServer(('0.0.0.0', 8443), WebFt8Handler)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain('./server.pem')
httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
httpd.serve_forever()
