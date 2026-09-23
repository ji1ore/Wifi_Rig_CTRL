import SwiftUI
import WebKit
import UIKit

// WKWebView that loads pota.app, navigates to #/user/stats to trigger auth API calls,
// captures Authorization token + callsign via JS, then fetches hunt data via
// /hunter/{callsign} (primary) or /user/logbook (fallback) and passes the result
// to the caller as JSON for HunterStore.importFromApiJson.
struct PotaHuntLogView: View {
    @Environment(\.dismiss) private var dismiss
    var onImport: (String) -> Void

    @State private var capturedToken: String = ""
    @State private var capturedCallsign: String = ""
    @State private var isFetching = false
    @State private var statusMsg = "pota.app にログインしてください"
    @State private var importedCount = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack {
                    Image(systemName: importedCount > 0 ? "checkmark.circle.fill" : "info.circle")
                        .foregroundStyle(importedCount > 0 ? .green : isFetching ? .secondary : .orange)
                    Text(statusMsg)
                        .font(.caption)
                        .foregroundStyle(importedCount > 0 ? .green : isFetching ? .secondary : .orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(8)
                .background(Color(uiColor: .systemGroupedBackground))
                Divider()
                if isFetching {
                    ProgressView().padding(16)
                } else {
                    PotaWebViewRepresentable(
                        onToken: { token in
                            guard capturedToken.isEmpty else { return }
                            capturedToken = token
                            isFetching = true
                            Task { await fetchData(token: token, callsign: capturedCallsign) }
                        },
                        onCallsign: { cs in
                            guard capturedCallsign.isEmpty else { return }
                            capturedCallsign = cs
                        },
                        onPageReady: {
                            statusMsg = "認証トークン取得中…"
                        }
                    )
                }
            }
            .navigationTitle("POTA ハント履歴取り込み")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
    }

    // MARK: - Entry point

    private func fetchData(token: String, callsign: String) async {
        // Resolve callsign: JS-captured → JWT claim → /user/profile API
        let cs: String
        if !callsign.isEmpty {
            cs = callsign
        } else if let fromJWT = callsignFromJWT(token) {
            cs = fromJWT
        } else if let fromProfile = await callsignFromProfile(authToken: token) {
            cs = fromProfile
        } else {
            // No callsign — fall back to logbook
            await fetchLogbook(token: token)
            return
        }

        await fetchHunterStats(callsign: cs, authToken: token)
    }

    // MARK: - JWT callsign extraction

    private func callsignFromJWT(_ token: String) -> String? {
        let jwt = token.hasPrefix("Bearer ") ? String(token.dropFirst(7)) : token
        let parts = jwt.split(separator: ".").map(String.init)
        guard parts.count >= 2 else { return nil }
        var b64 = parts[1]
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return (json["callsign"] as? String) ?? (json["https://pota.app/callsign"] as? String)
    }

    private func callsignFromProfile(authToken: String) async -> String? {
        guard let url = URL(string: "https://api.pota.app/user/profile") else { return nil }
        var req = URLRequest(url: url)
        req.setValue(authToken, forHTTPHeaderField: "Authorization")
        req.timeoutInterval = 15
        let data = (try? await URLSession.shared.data(for: req))?.0 ?? Data()
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return (json["callsign"] as? String) ?? (json["ham_callsign"] as? String)
    }

    // MARK: - /hunter/{callsign} (primary)

    private func fetchHunterStats(callsign: String, authToken: String) async {
        statusMsg = "Hunt統計取得中… (\(callsign))"
        guard let url = URL(string: "https://api.pota.app/hunter/\(callsign)") else {
            await fetchLogbook(token: authToken); return
        }
        var req = URLRequest(url: url)
        req.setValue(authToken, forHTTPHeaderField: "Authorization")
        req.timeoutInterval = 30

        guard let (data, _) = try? await URLSession.shared.data(for: req),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]],
              !arr.isEmpty else {
            await fetchLogbook(token: authToken); return
        }
        processHunterStats(arr)
    }

    // Split multi-location entries (short = "JP-TK,JP-KN") into one entry per location.
    // If "hunts" field exists, map it to "qsos" (unique activations, not raw QSO contacts).
    private func processHunterStats(_ arr: [[String: Any]]) {
        var result: [[String: Any]] = []
        for park in arr {
            guard let ref = park["reference"] as? String, !ref.isEmpty else { continue }
            var entry = park
            if let hunts = park["hunts"] as? Int         { entry["qsos"] = hunts }
            else if let hunts = park["hunts"] as? Double { entry["qsos"] = Int(hunts) }

            let hasc = entry["short"] as? String ?? ""
            if hasc.contains(",") {
                let locs = hasc.split(separator: ",")
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                    .filter { !$0.isEmpty }
                for loc in locs {
                    var locEntry = entry; locEntry["short"] = loc
                    result.append(locEntry)
                }
            } else {
                result.append(entry)
            }
        }
        finishImport(result)
    }

    // MARK: - /user/logbook (fallback)

    private func fetchLogbook(token: String) async {
        var allRefs: [[String: Any]] = []
        var page = 1
        let size = 100
        var totalCount = Int.max

        while (page - 1) * size < totalCount {
            let totalPages = totalCount == Int.max ? "?" : "\((totalCount + size - 1) / size)"
            statusMsg = "ログブック取得中… (\(page)/\(totalPages)ページ)"

            guard let url = URL(string: "https://api.pota.app/user/logbook?hunterOnly=1&page=\(page)&size=\(size)") else { break }
            var req = URLRequest(url: url)
            req.setValue(token, forHTTPHeaderField: "Authorization")
            req.setValue("application/json", forHTTPHeaderField: "Accept")
            req.timeoutInterval = 30

            guard let (data, _) = try? await URLSession.shared.data(for: req) else { break }
            let parsed = try? JSONSerialization.jsonObject(with: data)

            if let obj = parsed as? [String: Any] {
                if let count = obj["count"] as? Int { totalCount = count }
                if let entries = obj["entries"] as? [[String: Any]] {
                    allRefs.append(contentsOf: entries)
                    if entries.isEmpty { break }
                } else { break }
            } else if let arr = parsed as? [[String: Any]] {
                allRefs.append(contentsOf: arr)
                if arr.count < size { break }
            } else {
                break
            }
            page += 1
        }

        guard !allRefs.isEmpty else {
            isFetching = false
            statusMsg = "データ取得失敗。ログインしてください。"
            capturedToken = ""
            return
        }

        // Deduplicate by ref|locDesc — each activation location counted separately
        var entries: [String: [String: Any]] = [:]
        for entry in allRefs {
            guard let ref = (entry["reference"] as? String)?.uppercased(), !ref.isEmpty else { continue }
            let loc = entry["locationDesc"] as? String ?? ""
            let key = loc.isEmpty ? ref : "\(ref)|\(loc)"
            if entries[key] == nil {
                var e: [String: Any] = ["reference": ref]
                if !loc.isEmpty { e["short"] = loc }
                entries[key] = e
            }
        }
        finishImport(Array(entries.values))
    }

    // MARK: - Common finish

    private func finishImport(_ result: [[String: Any]]) {
        isFetching = false
        guard !result.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: result),
              let jsonStr = String(data: data, encoding: .utf8) else {
            statusMsg = "データ取得失敗。ログインしてください。"
            capturedToken = ""
            return
        }
        onImport(jsonStr)
        importedCount = result.count
        statusMsg = "OK: \(result.count) パーク取り込み完了"
    }
}

// MARK: - WKWebView wrapper

private struct PotaWebViewRepresentable: UIViewRepresentable {
    var onToken: (String) -> Void
    var onCallsign: (String) -> Void
    var onPageReady: () -> Void

    func makeUIView(context: Context) -> WKWebView {
        let contentController = WKUserContentController()
        contentController.add(context.coordinator, name: "potaAuth")
        contentController.add(context.coordinator, name: "potaCallsign")

        // Intercept Authorization header + callsign from /hunter/ or /activator/ URL
        let js = """
        (function() {
            if (window._wrcPotaInjected) return;
            window._wrcPotaInjected = true;
            function reportAuth(v) {
                if (!v || typeof v !== 'string') return;
                try { window.webkit.messageHandlers.potaAuth.postMessage(v); } catch(e) {}
            }
            function reportCallsign(url) {
                var m = url.match(/\\/(hunter|activator)\\/([A-Z0-9/]+)/i);
                if (!m || !m[2]) return;
                var cs = m[2].toUpperCase().split('/')[0];
                try { window.webkit.messageHandlers.potaCallsign.postMessage(cs); } catch(e) {}
            }
            var origOpen = XMLHttpRequest.prototype.open;
            var origSet  = XMLHttpRequest.prototype.setRequestHeader;
            var origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(m, url) {
                this._wrcUrl = typeof url === 'string' ? url : '';
                return origOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                if (name.toLowerCase() === 'authorization' && this._wrcUrl.includes('api.pota.app')) {
                    reportAuth(value);
                }
                return origSet.call(this, name, value);
            };
            XMLHttpRequest.prototype.send = function() {
                if (this._wrcUrl) reportCallsign(this._wrcUrl);
                return origSend.apply(this, arguments);
            };
            var origFetch = window.fetch;
            window.fetch = function(resource, init) {
                var url = typeof resource === 'string' ? resource : (resource && resource.url) || '';
                if (url.includes('api.pota.app') && init && init.headers) {
                    var h = init.headers;
                    var auth = typeof h.get === 'function'
                        ? (h.get('Authorization') || h.get('authorization'))
                        : (h['Authorization'] || h['authorization']);
                    reportAuth(auth);
                }
                reportCallsign(url);
                return origFetch.apply(this, arguments);
            };
        })();
        """
        let script = WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        contentController.addUserScript(script)

        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.userContentController = contentController

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        context.coordinator.webView = webView
        if let url = URL(string: "https://pota.app") {
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onToken: onToken, onCallsign: onCallsign, onPageReady: onPageReady) }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var onToken: (String) -> Void
        var onCallsign: (String) -> Void
        var onPageReady: () -> Void
        weak var webView: WKWebView?
        private var tokenFired = false
        private var navigatedToStats = false

        init(onToken: @escaping (String) -> Void,
             onCallsign: @escaping (String) -> Void,
             onPageReady: @escaping () -> Void) {
            self.onToken = onToken
            self.onCallsign = onCallsign
            self.onPageReady = onPageReady
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard let url = webView.url?.absoluteString,
                  url.hasPrefix("https://pota.app"),
                  !url.contains("auth0"),
                  !url.contains("/login"),
                  !navigatedToStats, !tokenFired
            else { return }

            navigatedToStats = true
            DispatchQueue.main.async { self.onPageReady() }

            let js = """
            (async function() {
                try {
                    if ('serviceWorker' in navigator) {
                        const regs = await navigator.serviceWorker.getRegistrations();
                        await Promise.all(regs.map(function(r){ return r.unregister(); }));
                    }
                } catch(e) {}
                window.location.href = 'https://pota.app/#/user/stats';
            })();
            """
            webView.evaluateJavaScript(js)
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            switch message.name {
            case "potaAuth":
                guard let token = message.body as? String, !token.isEmpty, !tokenFired else { return }
                tokenFired = true
                let t = token
                DispatchQueue.main.async { self.onToken(t) }
            case "potaCallsign":
                guard let cs = message.body as? String, !cs.isEmpty else { return }
                DispatchQueue.main.async { self.onCallsign(cs) }
            default:
                break
            }
        }
    }
}
