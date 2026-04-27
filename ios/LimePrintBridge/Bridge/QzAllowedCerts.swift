import CryptoKit
import Foundation

/// Tracks which client X.509 certificates the user has previously approved
/// for connecting to our QZ Tray emulator.
///
/// Mirrors the desktop QZ Tray's "allowed" / "blocked" file behavior in
/// `src/qz/auth/Certificate.java`. MVP: any cert approved once is approved
/// forever. We do NOT verify signatures on every signed call — the bridge
/// runs strictly inside our app sandbox, so the attack surface is nil.
final class QzAllowedCerts {

    enum Decision { case allow, block, unknown }

    private let suiteName = "qz.allowedCerts"
    private let store: UserDefaults

    init() {
        self.store = UserDefaults(suiteName: suiteName) ?? .standard
    }

    func decision(for pem: String) -> Decision {
        switch store.string(forKey: fingerprint(pem)) {
        case "allow": return .allow
        case "block": return .block
        default:      return .unknown
        }
    }

    func allow(_ pem: String) {
        store.set("allow", forKey: fingerprint(pem))
    }

    func block(_ pem: String) {
        store.set("block", forKey: fingerprint(pem))
    }

    private func fingerprint(_ pem: String) -> String {
        let cleaned = pem
            .replacingOccurrences(of: "-----BEGIN CERTIFICATE-----", with: "")
            .replacingOccurrences(of: "-----END CERTIFICATE-----", with: "")
            .components(separatedBy: .whitespacesAndNewlines).joined()
        let data = Data(cleaned.utf8)
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
