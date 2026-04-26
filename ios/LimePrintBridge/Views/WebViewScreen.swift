import SwiftUI
import WebKit

/// Hosts `https://app.lime-booking.com` in a WKWebView. The QzShim is
/// injected at `.atDocumentStart`, so the page's `qz-tray.js` library finds
/// our patched `window.WebSocket` from its very first call.
struct WebViewScreen: View {

    @EnvironmentObject var app: AppState

    var body: some View {
        WebViewContainer(app: app)
            .ignoresSafeArea(.container, edges: .bottom)
            .navigationTitle("Lime Booking")
            .navigationBarTitleDisplayMode(.inline)
            .alert(item: $app.pendingApproval) { approval in
                Alert(
                    title: Text("Dovoli Lime Booking, da tiska?"),
                    message: Text(approval.commonName ?? "Lime Booking"),
                    primaryButton: .default(Text("Vedno")) {
                        app.resolveApproval(approval, allow: true, remember: true)
                    },
                    secondaryButton: .destructive(Text("Blokiraj")) {
                        app.resolveApproval(approval, allow: false, remember: true)
                    }
                )
            }
    }
}

private struct WebViewContainer: UIViewRepresentable {
    let app: AppState

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> WKWebView {
        // The QzBridge is retained by the WKUserContentController via add(_:name:),
        // so we don't need a SwiftUI state ref to keep it alive.
        let qzBridge = QzBridge(router: app.router)
        let config = QzBridge.makeConfiguration(handler: qzBridge)

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.allowsBackForwardNavigationGestures = true
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.customUserAgent = (webView.value(forKey: "userAgent") as? String ?? "")
            + " LimePrintBridge/1.0"

        qzBridge.webView = webView

        if let url = URL(string: "https://app.lime-booking.com/") {
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        // Open new windows in the same WebView (Lime sometimes opens email links etc.)
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction,
                     windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let url = navigationAction.request.url {
                webView.load(URLRequest(url: url))
            }
            return nil
        }
    }
}
