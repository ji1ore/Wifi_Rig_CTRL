#!/usr/bin/env python3
import http.server
import ssl
import urllib.request

def _load_api_key():
    try:
        with open('/home/pi/fastapi/.env') as f:
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
        self.send_header('Cross-Origin-Embedder-Policy', 'require-corp')
        super().end_headers()

    def guess_type(self, path):
        if str(path).endswith('.wasm'):
            return 'application/wasm'
        return super().guess_type(path)

    def do_GET(self):
        if self.path.startswith('/audio_sub'):
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
                self.send_header('Cross-Origin-Embedder-Policy', 'require-corp')
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

httpd = http.server.ThreadingHTTPServer(('0.0.0.0', 8443), WebFt8Handler)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain('./server.pem')
httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
httpd.serve_forever()
