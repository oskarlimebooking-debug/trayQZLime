import Foundation

/// Top-level orchestrator that takes a `qz.print(config, data)` JSON payload
/// (the same shape that desktop QZ Tray's daemon receives — see
/// `js/qz-tray.js:1737-1744`), translates it to ESC/POS bytes, and streams
/// to the connected BLE printer.
@MainActor
final class EscPosPrinter {

    enum PrintError: LocalizedError {
        case noPrinterConnected
        case writeFailed(String)
        var errorDescription: String? {
            switch self {
            case .noPrinterConnected: return "Ni povezanega tiskalnika — odpri Settings."
            case .writeFailed(let m): return "Napaka tiskanja: \(m)"
            }
        }
    }

    private let bleManager: BleManager
    private let registry: PrinterRegistry

    init(bleManager: BleManager, registry: PrinterRegistry) {
        self.bleManager = bleManager
        self.registry = registry
    }

    /// `params` shape: `{printer:{name|host}, options:{...}, data:[{type,format,flavor,data,options?}, ...]}`
    func print(params: [String: Any]) async throws {
        guard case .connected(let printer) = bleManager.state else {
            throw PrintError.noPrinterConnected
        }

        let options = params["options"] as? [String: Any] ?? [:]
        let encoding = (options["encoding"] as? String ?? "cp1250").lowercased()
        let dataArray = params["data"] as? [[String: Any]] ?? []

        let job = PrintJob.build(
            data: dataArray,
            encoding: encoding,
            paperWidthDots: registry.paperWidthDots
        )

        // Trailing feed + partial cut so the receipt is presented for tearing
        var stream = job
        stream.append(contentsOf: [0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x42, 0x00])

        do {
            try await printer.write(stream)
        } catch {
            throw PrintError.writeFailed(error.localizedDescription)
        }
    }

    /// Sends the built-in test page directly (bypasses the QZ pipeline).
    func testPrint() async throws {
        guard case .connected(let printer) = bleManager.state else {
            throw PrintError.noPrinterConnected
        }
        var data = PrintJob.testPage(paperColumns: registry.paperColumns)
        data.append(contentsOf: [0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x42, 0x00])
        try await printer.write(data)
    }
}
