import Foundation
import WebKit

/// In-process bridge between the WKWebView's JavaScript and the native
/// `QzMessageRouter`.
///
/// Lifecycle:
///  1. WKWebView is configured with [QzShim.js] injected at `.atDocumentStart`.
///     The shim replaces `window.WebSocket` for `wss://localhost*` URLs with a
///     fake socket whose `send()` posts via `webkit.messageHandlers.qz`.
///  2. JS calls land in `userContentController(_:didReceive:)` as a dictionary
///     `{kind: "connect|send|close", id: <socketId>, ...}`.
///  3. We dispatch into the router, then push responses back into JS via
///     `webView.evaluateJavaScript("__qzBridge.recv('id', '<json>')")`.
@MainActor
final class QzBridge: NSObject {

    static let messageName = "qz"

    weak var webView: WKWebView?
    private let router: QzMessageRouter

    init(router: QzMessageRouter) {
        self.router = router
        super.init()
    }

    /// Constructs a `WKWebViewConfiguration` with our shim + message handler attached.
    static func makeConfiguration(handler: QzBridge) -> WKWebViewConfiguration {
        let config = WKWebViewConfiguration()
        let userContent = WKUserContentController()
        let userScript = WKUserScript(source: QzShim.js, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        userContent.addUserScript(userScript)
        userContent.add(handler, name: messageName)
        config.userContentController = userContent
        config.websiteDataStore = .default()
        if #available(iOS 14.0, *) {
            config.defaultWebpagePreferences.allowsContentJavaScript = true
        } else {
            config.preferences.javaScriptEnabled = true
        }
        return config
    }

    private func send(toSocket id: String, text: String) {
        guard let webView else { return }
        let escaped = jsEscape(text)
        webView.evaluateJavaScript("window.__qzBridge && window.__qzBridge.recv('\(id)', '\(escaped)');", completionHandler: nil)
    }

    private func close(socket id: String) {
        guard let webView else { return }
        webView.evaluateJavaScript("window.__qzBridge && window.__qzBridge.close('\(id)');", completionHandler: nil)
    }

    private func jsEscape(_ s: String) -> String {
        var out = ""
        out.reserveCapacity(s.count + 32)
        for scalar in s.unicodeScalars {
            switch scalar.value {
            case 0x5C: out += "\\\\"
            case 0x27: out += "\\'"
            case 0x0A: out += "\\n"
            case 0x0D: out += "\\r"
            case 0x09: out += "\\t"
            case 0x2028: out += "\\u2028"
            case 0x2029: out += "\\u2029"
            case 0x3C: out += "\\u003c" // </script>
            default:   out.unicodeScalars.append(scalar)
            }
        }
        return out
    }
}

// MARK: - WKScriptMessageHandler
extension QzBridge: WKScriptMessageHandler {

    nonisolated func userContentController(_ controller: WKUserContentController,
                                           didReceive message: WKScriptMessage) {
        // Hop to main actor to interact with the router (and the WebView)
        let body = message.body
        Task { @MainActor in
            await handle(body)
        }
    }

    @MainActor
    private func handle(_ body: Any) async {
        guard let dict = body as? [String: Any],
              let kind = dict["kind"] as? String,
              let id = dict["id"] as? String else {
            NSLog("QzBridge: malformed message body")
            return
        }

        switch kind {
        case "connect":
            router.onConnectionOpened(id: id)

        case "send":
            let text = dict["message"] as? String ?? ""
            // Plain-text probe handshake bypasses JSON parsing
            if router.handlePlainText(text, on: id, send: { [weak self] s in self?.send(toSocket: id, text: s) }) {
                return
            }
            guard let data = text.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else {
                NSLog("QzBridge: bad JSON: \(text.prefix(200))")
                return
            }
            await router.dispatch(json, on: id) { [weak self] s in self?.send(toSocket: id, text: s) }

        case "close":
            router.onConnectionClosed(id: id)
            close(socket: id)

        default:
            NSLog("QzBridge: unknown kind=\(kind)")
        }
    }
}
