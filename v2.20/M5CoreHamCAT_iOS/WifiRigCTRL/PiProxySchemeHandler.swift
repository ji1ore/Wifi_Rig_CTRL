import WebKit
import Foundation

/// WKURLSchemeHandler that proxies custom-scheme requests to Pi endpoints.
///
/// Two schemes are registered:
///  • `pistream://` → `https://`  for the WebFT8 page + static assets (self-signed cert accepted)
///  • `piaudio://`  → `http://`   for the FastAPI audio-sub stream (no SSL, reliable)
///
/// Using `piaudio://` for audio bypasses the Python HTTPS proxy (server_webft8.py) entirely,
/// connecting straight to FastAPI on port 8000, which is always running when rig control works.
final class PiProxySchemeHandler: NSObject, WKURLSchemeHandler {

    static let scheme     = "pistream"   // → https://  (WebFT8 page)
    static let httpScheme = "piaudio"    // → http://   (FastAPI audio stream)

    private let targetScheme: String

    init(targetScheme: String = "https") {
        self.targetScheme = targetScheme
    }

    // Accessed only from the main thread (WKURLSchemeHandler is always called on main)
    private var active: [ObjectIdentifier: HandlerSession] = [:]

    func webView(_ webView: WKWebView, start urlSchemeTask: any WKURLSchemeTask) {
        guard let schemeURL = urlSchemeTask.request.url,
              var comps = URLComponents(url: schemeURL, resolvingAgainstBaseURL: false) else {
            print("[PiProxy] ❌ Bad URL")
            urlSchemeTask.didFailWithError(makeErr(-1, "Bad URL"))
            return
        }
        comps.scheme = targetScheme
        guard let targetURL = comps.url else {
            print("[PiProxy] ❌ URL conversion failed for \(schemeURL)")
            urlSchemeTask.didFailWithError(makeErr(-2, "URL conversion failed"))
            return
        }

        print("[PiProxy] ▶ start \(schemeURL) → \(targetURL)")

        var req = URLRequest(url: targetURL)
        req.httpMethod = urlSchemeTask.request.httpMethod ?? "GET"
        req.timeoutInterval = 0
        if let hdrs = urlSchemeTask.request.allHTTPHeaderFields {
            for (k, v) in hdrs { req.setValue(v, forHTTPHeaderField: k) }
        }

        let hs = HandlerSession(schemeTask: urlSchemeTask, originalURL: schemeURL)
        active[ObjectIdentifier(urlSchemeTask as AnyObject)] = hs
        hs.resume(with: req)
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: any WKURLSchemeTask) {
        print("[PiProxy] ■ stop \(urlSchemeTask.request.url?.absoluteString ?? "?")")
        active.removeValue(forKey: ObjectIdentifier(urlSchemeTask as AnyObject))?.cancel()
    }

    private func makeErr(_ code: Int, _ msg: String) -> NSError {
        NSError(domain: "PiProxySchemeHandler", code: code,
                userInfo: [NSLocalizedDescriptionKey: msg])
    }
}

// MARK: - Per-request streaming handler

private final class HandlerSession: NSObject, URLSessionDataDelegate {

    private let schemeTask: any WKURLSchemeTask
    private let originalURL: URL
    private var session: URLSession?
    // Protects _stopped which is written on main thread and read on URLSession background queue
    private let lock = NSLock()
    private var _stopped = false

    init(schemeTask: any WKURLSchemeTask, originalURL: URL) {
        self.schemeTask = schemeTask
        self.originalURL = originalURL
        super.init()
    }

    func resume(with request: URLRequest) {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 0
        cfg.timeoutIntervalForResource = 0
        let sess = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
        session = sess
        sess.dataTask(with: request).resume()
    }

    func cancel() {
        lock.lock(); _stopped = true; lock.unlock()
        session?.invalidateAndCancel()
        session = nil
    }

    // MARK: - URLSessionDelegate — accept self-signed certificates

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        handleChallenge(challenge, completionHandler: completionHandler)
    }

    // Task-level challenges (more common for per-connection SSL) also need handling.
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        handleChallenge(challenge, completionHandler: completionHandler)
    }

    private func handleChallenge(_ challenge: URLAuthenticationChallenge,
                                 completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let method = challenge.protectionSpace.authenticationMethod
        print("[PiProxy] 🔒 challenge method=\(method) host=\(challenge.protectionSpace.host):\(challenge.protectionSpace.port)")
        if method == NSURLAuthenticationMethodServerTrust,
           let trust = challenge.protectionSpace.serverTrust {
            print("[PiProxy] 🔒 → useCredential (self-signed accepted)")
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            print("[PiProxy] 🔒 → performDefaultHandling")
            completionHandler(.performDefaultHandling, nil)
        }
    }

    // MARK: - URLSessionDataDelegate — streaming bridge to WKURLSchemeTask

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if let http = response as? HTTPURLResponse {
            print("[PiProxy] ✅ response HTTP \(http.statusCode) for \(originalURL)")
        } else {
            print("[PiProxy] ✅ response (non-HTTP) for \(originalURL)")
        }
        lock.lock(); let stopped = _stopped; lock.unlock()
        guard !stopped else { completionHandler(.cancel); return }

        // Rebuild response with the pistream:// URL so WebKit same-origin checks pass
        let wrapped: URLResponse
        if let http = response as? HTTPURLResponse {
            wrapped = HTTPURLResponse(url: originalURL,
                                      statusCode: http.statusCode,
                                      httpVersion: "HTTP/1.1",
                                      headerFields: http.allHeaderFields as? [String: String]) ?? response
        } else {
            wrapped = response
        }
        // WKURLSchemeTask requires didReceive(response) BEFORE any didReceive(data).
        // Deliver response header synchronously on the main thread first, then allow
        // URLSession to start delivering data chunks. Using main.sync blocks this
        // background delegate queue until the header is safely handed to WebKit.
        DispatchQueue.main.sync { [weak self] in
            guard let self else { return }
            self.lock.lock(); let s = self._stopped; self.lock.unlock()
            guard !s else { return }
            self.schemeTask.didReceive(wrapped)
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock(); let stopped = _stopped; lock.unlock()
        guard !stopped else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lock.lock(); let s = self._stopped; self.lock.unlock()
            guard !s else { return }
            self.schemeTask.didReceive(data)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error {
            let ns = error as NSError
            print("[PiProxy] ❌ complete ERROR domain=\(ns.domain) code=\(ns.code) msg=\(ns.localizedDescription) url=\(originalURL)")
        } else {
            print("[PiProxy] ✅ complete (no error) url=\(originalURL)")
        }
        lock.lock(); let stopped = _stopped; lock.unlock()
        guard !stopped else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lock.lock(); let s = self._stopped; self.lock.unlock()
            guard !s else { return }
            if let error = error {
                if (error as NSError).code != NSURLErrorCancelled {
                    self.schemeTask.didFailWithError(error)
                }
            } else {
                self.schemeTask.didFinish()
            }
        }
    }
}
