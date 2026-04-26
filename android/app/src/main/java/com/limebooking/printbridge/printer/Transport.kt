package com.limebooking.printbridge.printer

/**
 * Transport-agnostic write API for the print pipeline. Concrete impls:
 *  - [BluetoothTransport]: Bluetooth Classic RFCOMM SPP (paired devices)
 *  - [BleTransport]: BLE GATT writable characteristic (scanned + connected ad-hoc)
 *
 * `connect()` may block; `write()` may also block if the link is flow-controlled.
 * `close()` must be safe to call multiple times.
 */
interface Transport : AutoCloseable {
    fun connect()
    fun write(bytes: ByteArray)
}
