import Foundation

/// Persists the user's chosen BLE printer (peripheral UUID + display name)
/// + paper width across launches.
final class PrinterRegistry {

    private static let kPeripheralUUID = "printer.peripheralUUID"
    private static let kPrinterName    = "printer.name"
    private static let kPaperColumns   = "printer.paperColumns"

    private let defaults = UserDefaults.standard

    struct SavedPrinter: Codable, Equatable {
        let id: UUID
        let name: String
    }

    func save(_ printer: SavedPrinter) {
        defaults.set(printer.id.uuidString, forKey: Self.kPeripheralUUID)
        defaults.set(printer.name, forKey: Self.kPrinterName)
    }

    func saved() -> SavedPrinter? {
        guard
            let s = defaults.string(forKey: Self.kPeripheralUUID),
            let id = UUID(uuidString: s)
        else { return nil }
        let name = defaults.string(forKey: Self.kPrinterName) ?? "Saved printer"
        return SavedPrinter(id: id, name: name)
    }

    func clear() {
        defaults.removeObject(forKey: Self.kPeripheralUUID)
        defaults.removeObject(forKey: Self.kPrinterName)
    }

    /// 33 (58mm) or 48 (80mm). Defaults to 33 to match POS-58.
    var paperColumns: Int {
        get { defaults.object(forKey: Self.kPaperColumns) as? Int ?? 33 }
        set { defaults.set(newValue, forKey: Self.kPaperColumns) }
    }

    /// Width in DOTS for raster bitmaps — 384 dots @ 58mm, 576 dots @ 80mm.
    var paperWidthDots: Int { paperColumns >= 48 ? 576 : 384 }
}
