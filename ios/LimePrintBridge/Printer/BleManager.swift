import CoreBluetooth
import Foundation

/// Discovers BLE thermal printers, connects to a chosen one, and finds a
/// writable characteristic.
///
/// BLE thermal printers in the wild use a small set of conventional GATT
/// service/characteristic UUIDs. Rather than hard-coding them, we scan for any
/// peripheral and on connect probe every characteristic of every service for
/// `.write` or `.writeWithoutResponse`. The first writable characteristic is
/// the one we use. This works for all common Chinese POS-58/POS-80 BLE
/// modules (FF00/FF02, 18F0/2AF1, FFE0/FFE5, Microchip 49535343-...) and
/// Nordic UART Service implementations.
///
/// MTU on iOS BLE defaults to 23 bytes (20 bytes payload after ATT header);
/// modern peripherals negotiate up to ~512. We chunk to whatever
/// `maximumWriteValueLength(for: .withoutResponse)` reports, with a safe fallback.
@MainActor
final class BleManager: NSObject, ObservableObject {

    enum State {
        case unknown
        case poweredOff
        case unauthorized
        case unsupported
        case poweredOn
        case scanning
        case connecting(Discovered)
        case connected(BlePrinter)
        case error(String)
    }

    struct Discovered: Identifiable, Hashable {
        let id: UUID            // peripheral.identifier
        let name: String
        let rssi: Int
        let isLikelyPrinter: Bool
    }

    @Published private(set) var state: State = .unknown
    @Published private(set) var discovered: [Discovered] = []

    private var central: CBCentralManager!
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var connectedPeripheral: CBPeripheral?
    private var writableChar: CBCharacteristic?
    private var pendingConnect: Discovered?

    /// Optional callback the BlePrinter wraps so its writes flow through us.
    private var pendingWriteCompletion: ((Result<Void, Error>) -> Void)?

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main, options: [
            CBCentralManagerOptionShowPowerAlertKey: true
        ])
    }

    func startScan() {
        guard central.state == .poweredOn else { return }
        discovered.removeAll()
        peripherals.removeAll()
        state = .scanning
        // withServices: nil → all peripherals; we filter by name heuristics in the UI.
        central.scanForPeripherals(withServices: nil, options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: false
        ])
    }

    func stopScan() {
        if central.isScanning { central.stopScan() }
        if case .scanning = state { state = .poweredOn }
    }

    func connect(_ d: Discovered) {
        guard let p = peripherals[d.id] else { return }
        stopScan()
        pendingConnect = d
        state = .connecting(d)
        central.connect(p, options: nil)
    }

    func disconnect() {
        if let p = connectedPeripheral { central.cancelPeripheralConnection(p) }
        connectedPeripheral = nil
        writableChar = nil
        if central.state == .poweredOn { state = .poweredOn } else { state = .unknown }
    }

    /// Reconnect to a previously-paired printer by stored peripheral UUID.
    func reconnectSavedPrinter(_ identifier: UUID) {
        guard central.state == .poweredOn else { return }
        if let p = central.retrievePeripherals(withIdentifiers: [identifier]).first {
            peripherals[identifier] = p
            let d = Discovered(id: identifier, name: p.name ?? "Saved printer", rssi: -100, isLikelyPrinter: true)
            connect(d)
        }
    }
}

// MARK: - CBCentralManagerDelegate
//
// CBCentralManagerDelegate protocol requirements are nonisolated. Since
// `BleManager` is `@MainActor`, each implementation must be marked
// `nonisolated` and hop back to the main actor before touching `@Published`
// state. The central manager itself is initialised with the `.main` queue,
// so the hop is effectively zero-latency.
extension BleManager: CBCentralManagerDelegate {

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let st = central.state
        Task { @MainActor in
            switch st {
            case .poweredOn:    self.state = .poweredOn
            case .poweredOff:   self.state = .poweredOff
            case .unauthorized: self.state = .unauthorized
            case .unsupported:  self.state = .unsupported
            default:            self.state = .unknown
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager,
                                    didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any],
                                    rssi RSSI: NSNumber) {
        let id = peripheral.identifier
        let advertisedName = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
        let peripheralName = peripheral.name
        let nameForDisplay = peripheralName ?? advertisedName ?? "(neimenovan)"
        let advertisedServices = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID]
        let rssiValue = RSSI.intValue

        Task { @MainActor in
            self.peripherals[id] = peripheral
            let likely = self.isLikelyPrinter(name: nameForDisplay, advertisedServices: advertisedServices)
            let d = Discovered(id: id, name: nameForDisplay, rssi: rssiValue, isLikelyPrinter: likely)
            if let idx = self.discovered.firstIndex(where: { $0.id == id }) {
                self.discovered[idx] = d
            } else {
                self.discovered.append(d)
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            self.connectedPeripheral = peripheral
            peripheral.delegate = self
            peripheral.discoverServices(nil)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let msg = error?.localizedDescription ?? "unknown"
        Task { @MainActor in
            self.state = .error("Povezava ni uspela: \(msg)")
            self.connectedPeripheral = nil
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let errMessage = error?.localizedDescription
        let centralState = central.state
        Task { @MainActor in
            self.connectedPeripheral = nil
            self.writableChar = nil
            if let m = errMessage {
                self.state = .error("Prekinjeno: \(m)")
            } else if centralState == .poweredOn {
                self.state = .poweredOn
            }
        }
    }

    private func isLikelyPrinter(name: String, advertisedServices: [CBUUID]?) -> Bool {
        let n = name.lowercased()
        let hints = ["pos", "print", "rpp", "spp", "mtp", "thermal",
                     "munbyn", "goojprt", "xprinter", "xp-58", "xp-80",
                     "pt-2", "ph58", "rp-", "bxl"]
        if hints.contains(where: { n.contains($0) }) { return true }
        if let uuids = advertisedServices {
            let printerUUIDs: Set<CBUUID> = [
                CBUUID(string: "FF00"), CBUUID(string: "18F0"),
                CBUUID(string: "FFE0"), CBUUID(string: "FFF0"),
                CBUUID(string: "49535343-FE7D-4AE5-8FA9-9FAFD205E455")
            ]
            if uuids.contains(where: { printerUUIDs.contains($0) }) { return true }
        }
        return false
    }
}

// MARK: - CBPeripheralDelegate
//
// Same pattern as the central delegate above: nonisolated stubs hop to
// MainActor before mutating any `@Published` state.
extension BleManager: CBPeripheralDelegate {

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        let errDesc = error?.localizedDescription
        Task { @MainActor in
            guard errDesc == nil, let services = peripheral.services else {
                self.state = .error("Discovery failed: \(errDesc ?? "no services")")
                return
            }
            for s in services {
                peripheral.discoverCharacteristics(nil, for: s)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral,
                                didDiscoverCharacteristicsFor service: CBService,
                                error: Error?) {
        let errIsNil = error == nil
        Task { @MainActor in
            guard errIsNil, let chars = service.characteristics else { return }
            // Prefer .writeWithoutResponse for streaming throughput, fall back to .write
            if self.writableChar == nil,
               let c = chars.first(where: { $0.properties.contains(.writeWithoutResponse) }) {
                self.writableChar = c
            } else if self.writableChar == nil,
                      let c = chars.first(where: { $0.properties.contains(.write) }) {
                self.writableChar = c
            }
            if let char = self.writableChar {
                let printer = BlePrinter(peripheral: peripheral, characteristic: char)
                self.state = .connected(printer)
            }
        }
    }

    nonisolated func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        // No actor hop needed — `notifyReady` only touches a thread-safe lock-protected map.
        BlePrinter.notifyReady(for: peripheral)
    }
}
