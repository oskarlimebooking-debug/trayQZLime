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
extension BleManager: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:    state = .poweredOn
        case .poweredOff:   state = .poweredOff
        case .unauthorized: state = .unauthorized
        case .unsupported:  state = .unsupported
        default:            state = .unknown
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        let id = peripheral.identifier
        let name = peripheral.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? "(neimenovan)"
        peripherals[id] = peripheral

        let likely = isLikelyPrinter(name: name, advertisement: advertisementData)
        let d = Discovered(id: id, name: name, rssi: RSSI.intValue, isLikelyPrinter: likely)
        if let idx = discovered.firstIndex(where: { $0.id == id }) {
            discovered[idx] = d
        } else {
            discovered.append(d)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectedPeripheral = peripheral
        peripheral.delegate = self
        peripheral.discoverServices(nil)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        state = .error("Povezava ni uspela: \(error?.localizedDescription ?? "unknown")")
        connectedPeripheral = nil
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectedPeripheral = nil
        writableChar = nil
        if let err = error {
            state = .error("Prekinjeno: \(err.localizedDescription)")
        } else if central.state == .poweredOn {
            state = .poweredOn
        }
    }

    private func isLikelyPrinter(name: String, advertisement: [String: Any]) -> Bool {
        let n = name.lowercased()
        let hints = ["pos", "print", "rpp", "spp", "mtp", "thermal",
                     "munbyn", "goojprt", "xprinter", "xp-58", "xp-80",
                     "pt-2", "ph58", "rp-", "bxl"]
        if hints.contains(where: { n.contains($0) }) { return true }
        // Some printers advertise specific service UUIDs
        if let uuids = advertisement[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] {
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
extension BleManager: CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let services = peripheral.services else {
            state = .error("Discovery failed: \(error?.localizedDescription ?? "no services")")
            return
        }
        for s in services {
            peripheral.discoverCharacteristics(nil, for: s)
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard error == nil, let chars = service.characteristics else { return }
        // Prefer .writeWithoutResponse (faster for streaming ESC/POS), fall back to .write
        if writableChar == nil,
           let c = chars.first(where: { $0.properties.contains(.writeWithoutResponse) }) {
            writableChar = c
        } else if writableChar == nil,
                  let c = chars.first(where: { $0.properties.contains(.write) }) {
            writableChar = c
        }
        // Once any service finishes discovering and we have a char, declare connected.
        if let char = writableChar {
            let printer = BlePrinter(peripheral: peripheral, characteristic: char)
            state = .connected(printer)
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        // BlePrinter polls this; nothing to do here, we forward via Combine in BlePrinter.
        BlePrinter.notifyReady(for: peripheral)
    }
}
