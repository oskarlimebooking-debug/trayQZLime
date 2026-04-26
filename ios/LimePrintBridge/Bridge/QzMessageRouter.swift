import Foundation

/// Dispatches JSON envelopes from qz-tray.js to the appropriate handler.
///
/// Mirrors the call dispatch in `src/qz/ws/PrintSocketClient.processMessage()`
/// and the SocketMethod enum in `src/qz/ws/SocketMethod.java` (Java reference
/// in this same repository).
///
/// Transport-agnostic: the per-connection `send` closure is supplied by
/// `QzBridge` (which pushes back into the WKWebView via
/// `evaluateJavaScript("__qzBridge.recv(...)")`). MVP signature handling: we
/// accept the certificate on first connect (with user dialog) and skip
/// per-call signature verification.
@MainActor
final class QzMessageRouter {

    typealias ApprovalHandler = (_ pem: String, _ commonName: String?) async -> Bool

    private let printer: EscPosPrinter
    private let registry: PrinterRegistry
    private let allowedCerts: QzAllowedCerts
    private let approvalHandler: ApprovalHandler

    private struct ConnState { var certPem: String? = nil; var allowed: Bool = false }
    private var connections: [String: ConnState] = [:]

    private static let emulatedVersion = "2.2.6"

    init(printer: EscPosPrinter,
         registry: PrinterRegistry,
         allowedCerts: QzAllowedCerts,
         approvalHandler: @escaping ApprovalHandler) {
        self.printer = printer
        self.registry = registry
        self.allowedCerts = allowedCerts
        self.approvalHandler = approvalHandler
    }

    func onConnectionOpened(id: String) {
        connections[id] = ConnState()
    }

    func onConnectionClosed(id: String) {
        connections.removeValue(forKey: id)
    }

    /// Plain-text probe handshake (qz-tray.js sends "getProgramName" before any JSON).
    /// Mirrors `Constants.PROBE_REQUEST` / `PROBE_RESPONSE` in
    /// `src/qz/common/Constants.java:63-64`.
    func handlePlainText(_ message: String, on id: String, send: @escaping (String) -> Void) -> Bool {
        switch message {
        case "getProgramName":
            send("QZ Tray"); return true
        case "ping":
            return true
        default:
            return false
        }
    }

    func dispatch(_ json: [String: Any], on id: String, send: @escaping (String) -> Void) async {
        let uid = json["uid"] as? String ?? ""

        if let pem = json["certificate"] as? String, !pem.isEmpty {
            await handleCertHandshake(uid: uid, pem: pem, on: id, send: send)
            return
        }

        guard let call = json["call"] as? String, !call.isEmpty else {
            sendError(uid: uid, message: "Missing 'call' in message", send: send)
            return
        }

        // Unsigned calls don't require cert handshake
        switch call {
        case "getVersion":
            sendResult(uid: uid, result: Self.emulatedVersion, send: send); return
        case "websocket.getNetworkInfo", "networking.deviceLegacy", "networking.device":
            sendResult(uid: uid, result: ["ipAddress": "127.0.0.1", "macAddress": "00:00:00:00:00:00"], send: send); return
        case "websocket.getNetworkInterfaces", "networking.devices":
            sendResult(uid: uid, result: [Any](), send: send); return
        default: break
        }

        guard let state = connections[id], state.allowed else {
            sendError(uid: uid, message: "Connection not yet authorized", send: send)
            return
        }

        do {
            try await handleAuthorizedCall(call: call, uid: uid, json: json, send: send)
        } catch {
            sendError(uid: uid, message: error.localizedDescription, send: send)
        }
    }

    private func handleAuthorizedCall(call: String,
                                      uid: String,
                                      json: [String: Any],
                                      send: @escaping (String) -> Void) async throws {
        switch call {
        case "printers.getDefault":
            let name = registry.saved()?.name as Any? ?? NSNull()
            sendResult(uid: uid, result: name, send: send)

        case "printers.find":
            let query = (json["params"] as? [String: Any])?["query"] as? String
            let names: [String] = registry.saved().map { [$0.name] } ?? []
            let filtered = (query?.isEmpty ?? true)
                ? names
                : names.filter { $0.localizedCaseInsensitiveContains(query ?? "") }
            sendResult(uid: uid, result: filtered, send: send)

        case "printers.details", "printers.detail":
            if let s = registry.saved() {
                sendResult(uid: uid, result: [["name": s.name, "driver": "ESCPOS", "connection": "BLE"]], send: send)
            } else {
                sendResult(uid: uid, result: [Any](), send: send)
            }

        case "printers.getStatus":
            sendResult(uid: uid, result: NSNull(), send: send)

        case "print":
            guard let params = json["params"] as? [String: Any] else {
                throw NSError(domain: "QzMessageRouter", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: "Missing print params"])
            }
            try await printer.print(params: params)
            sendResult(uid: uid, result: NSNull(), send: send)

        case "websocket.stop":
            sendResult(uid: uid, result: NSNull(), send: send)

        default:
            NSLog("QzMessageRouter: unhandled call=\(call)")
            sendResult(uid: uid, result: NSNull(), send: send)
        }
    }

    private func handleCertHandshake(uid: String, pem: String, on id: String, send: @escaping (String) -> Void) async {
        var state = connections[id] ?? ConnState()
        state.certPem = pem
        connections[id] = state

        switch allowedCerts.decision(for: pem) {
        case .allow:
            connections[id]?.allowed = true
            sendResult(uid: uid, result: NSNull(), send: send)
        case .block:
            sendError(uid: uid, message: "Connection blocked by user", send: send)
        case .unknown:
            let cn = extractCommonName(from: pem)
            let approved = await approvalHandler(pem, cn)
            if approved {
                connections[id]?.allowed = true
                sendResult(uid: uid, result: NSNull(), send: send)
            } else {
                sendError(uid: uid, message: "Connection blocked by user", send: send)
            }
        }
    }

    // MARK: - Encoding helpers

    private func sendResult(uid: String, result: Any, send: (String) -> Void) {
        let env: [String: Any] = ["uid": uid, "result": result]
        if let data = try? JSONSerialization.data(withJSONObject: env, options: [.fragmentsAllowed]),
           let s = String(data: data, encoding: .utf8) {
            send(s)
        }
    }

    private func sendError(uid: String, message: String, send: (String) -> Void) {
        let env: [String: Any] = ["uid": uid, "error": message]
        if let data = try? JSONSerialization.data(withJSONObject: env, options: [.fragmentsAllowed]),
           let s = String(data: data, encoding: .utf8) {
            send(s)
        }
    }

    private func extractCommonName(from pem: String) -> String? {
        let cleaned = pem
            .replacingOccurrences(of: "-----BEGIN CERTIFICATE-----", with: "")
            .replacingOccurrences(of: "-----END CERTIFICATE-----", with: "")
            .components(separatedBy: .whitespacesAndNewlines).joined()
        guard
            let der = Data(base64Encoded: cleaned),
            let cert = SecCertificateCreateWithData(nil, der as CFData)
        else { return nil }
        return SecCertificateCopySubjectSummary(cert) as String?
    }
}
