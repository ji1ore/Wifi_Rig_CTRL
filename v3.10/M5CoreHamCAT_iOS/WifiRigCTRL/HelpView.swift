import SwiftUI
import WebKit
import UIKit

struct HelpView: View {
    private var htmlFileName: String {
        Locale.current.language.languageCode?.identifier == "ja" ? "help_ja" : "help"
    }

    var body: some View {
        _HelpWebView(fileName: htmlFileName)
            .navigationTitle(Text("Help"))
            .navigationBarTitleDisplayMode(.inline)
            .ignoresSafeArea(edges: .bottom)
    }
}

private struct _HelpWebView: UIViewRepresentable {
    let fileName: String

    func makeUIView(context: Context) -> WKWebView {
        WKWebView()
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        guard let url = Bundle.main.url(forResource: fileName, withExtension: "html") else { return }
        webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
    }
}
