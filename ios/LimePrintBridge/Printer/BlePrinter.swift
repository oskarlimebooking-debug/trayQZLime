import CoreBluetooth
import Foundation

/// Streaming write API on top of a connected BLE peripheral + writable
/// characteristic.
///
/// The trickiest part of BLE printing is flow control: writes-without-response
/// queue at the BT controller and silently drop if you push faster than the
/// link can drain. `CBPeripheral.canSendWriteWithoutResponse` and the
/// `peripheralIsReady(...)` delegate callback let us back off until ready.
///
/// `CBPeripheral` methods are documented as safe to call from any thread, so
/// this class isn't actor-isolated. The `peripheralIsReady` callback that
/// resumes our continuations is forwarded from `BleManager`'s nonisolated
/// delegate stub, which means waiters can resume from any thread; the
/// continuation API handles cross-actor resume safely.
final class BlePrinter {
    let peripheral: CBPeripheral
    let characteristic: CBCharacteristic
    let preferredType: CBCharacteristicWriteType

    /// Largest payload the link supports. iOS reports this on a per-peripheral basis.
    var maxChunk: Int {
        let raw = peripheral.maximumWriteValueLength(for: preferredType)
        // Be defensive: some firmwares mis-report. Cap at 180 to avoid unstable links.
        return min(max(raw, 20), 180)
    }

    init(peripheral: CBPeripheral, characteristic: CBCharacteristic) {
        self.peripheral = peripheral
        self.characteristic = characteristic
        self.preferredType = characteristic.properties.contains(.writeWithoutResponse)
            ? .withoutResponse
            : .withResponse
    }

    /// Sends `data` to the printer in MTU-sized chunks with cooperative flow control.
    /// Throws on disconnect / write error.
    func write(_ data: Data) async throws {
        var offset = 0
        while offset < data.count {
            try await waitUntilReady()
            let len = min(maxChunk, data.count - offset)
            let chunk = data.subdata(in: offset..<(offset + len))
            peripheral.writeValue(chunk, for: characteristic, type: preferredType)
            offset += len
            // Tiny per-chunk gap helps cheap printers — many have ~256B internal RX buffer.
            try? await Task.sleep(nanoseconds: 6_000_000) // 6 ms
        }
    }

    private func waitUntilReady() async throws {
        if preferredType == .withResponse { return }
        if peripheral.canSendWriteWithoutResponse { return }
        // Subscribe to the next ready callback. BleManager fans these out via a static map.
        await withCheckedContinuation { cont in
            BlePrinter.queueReady(for: peripheral, cont: cont)
        }
    }

    // MARK: - Static fan-out for `peripheralIsReady` delegate callbacks
    private static var waiters: [UUID: [CheckedContinuation<Void, Never>]] = [:]
    private static let waitersLock = NSLock()

    static func queueReady(for peripheral: CBPeripheral, cont: CheckedContinuation<Void, Never>) {
        waitersLock.lock(); defer { waitersLock.unlock() }
        waiters[peripheral.identifier, default: []].append(cont)
    }

    static func notifyReady(for peripheral: CBPeripheral) {
        waitersLock.lock()
        let pending = waiters.removeValue(forKey: peripheral.identifier) ?? []
        waitersLock.unlock()
        for c in pending { c.resume() }
    }
}
