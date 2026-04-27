import Foundation
import SwiftUI

/// Singleton wiring for the QZ Tray emulator + print stack, shared between
/// SettingsView and WebViewScreen.
@MainActor
final class AppState: ObservableObject {

    let registry = PrinterRegistry()
    let allowedCerts = QzAllowedCerts()
    let bleManager = BleManager()

    lazy var printer: EscPosPrinter = EscPosPrinter(bleManager: bleManager, registry: registry)
    lazy var router: QzMessageRouter = QzMessageRouter(
        printer: printer,
        registry: registry,
        allowedCerts: allowedCerts,
        approvalHandler: { [weak self] pem, cn in
            await self?.requestCertApproval(pem: pem, commonName: cn) ?? false
        }
    )

    /// Pending cert approval surfaced to the foreground SwiftUI scene.
    @Published var pendingApproval: PendingApproval?

    struct PendingApproval: Identifiable {
        let id = UUID()
        let pem: String
        let commonName: String?
        let response: AsyncResponse
    }

    /// Bridges the imperative router request → SwiftUI alert UI.
    final class AsyncResponse {
        private var continuation: CheckedContinuation<Bool, Never>?
        func bind(_ cont: CheckedContinuation<Bool, Never>) { continuation = cont }
        func answer(_ value: Bool) {
            continuation?.resume(returning: value)
            continuation = nil
        }
    }

    private func requestCertApproval(pem: String, commonName: String?) async -> Bool {
        let response = AsyncResponse()
        let approval = PendingApproval(pem: pem, commonName: commonName, response: response)
        return await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            response.bind(cont)
            self.pendingApproval = approval
        }
    }

    func resolveApproval(_ approval: PendingApproval, allow: Bool, remember: Bool) {
        if allow && remember {
            allowedCerts.allow(approval.pem)
        } else if !allow {
            allowedCerts.block(approval.pem)
        }
        approval.response.answer(allow)
        pendingApproval = nil
    }

    /// Try to reconnect to the saved printer when BLE is powered on.
    func autoReconnectSavedPrinter() {
        guard case .poweredOn = bleManager.state else { return }
        if let saved = registry.saved() {
            bleManager.reconnectSavedPrinter(saved.id)
        }
    }
}
