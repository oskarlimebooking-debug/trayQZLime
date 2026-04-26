package com.limebooking.printbridge.printer

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Top-level print orchestrator. Receives the same JSON shape that
 * `qz.print(config, data)` sends to the desktop QZ Tray daemon
 * (see js/qz-tray.js:1737-1744 for the exact envelope).
 *
 * Translates each `data[]` entry to ESC/POS bytes, opens the appropriate
 * transport (Classic SPP or BLE GATT — based on `PrinterRegistry`), writes
 * the byte stream, and closes.
 */
class EscPosPrinter(
    private val context: Context,
    private val registry: PrinterRegistry
) {

    companion object {
        private const val TAG = "EscPosPrinter"
        private val CUT_PARTIAL = byteArrayOf(0x1D, 0x56, 0x42, 0x00) // GS V B 0
        private val TRAILING = byteArrayOf(0x0A, 0x0A, 0x0A) + CUT_PARTIAL
    }

    /** params shape: {printer:{name|host}, options:{...}, data:[{type,format,flavor,data,options?}, ...]} */
    fun print(params: JSONObject) {
        val printerName = params.optJSONObject("printer")?.optString("name") ?: ""
        val printer = registry.findByName(printerName)
            ?: throw IllegalStateException("Tiskalnik ni izbran — odpri Settings")

        val data = params.optJSONArray("data") ?: JSONArray()
        val options = params.optJSONObject("options") ?: JSONObject()
        val encoding = options.optString("encoding", "cp1250").lowercase()

        val job = PrintJob.build(data, encoding, registry.paperWidthDots)

        write(printer, job + TRAILING)
        Log.i(TAG, "Printed ${job.size} bytes to ${printer.name} (${printer.transport})")
    }

    /** Used by the Settings "Test print" button. */
    fun testPrint() {
        val printer = registry.defaultPrinter()
            ?: throw IllegalStateException("Ni izbranega tiskalnika")
        val data = PrintJob.testPage(registry.paperColumns) + TRAILING
        write(printer, data)
    }

    private fun write(printer: PrinterRegistry.Printer, data: ByteArray) {
        val adapter = registry.adapter() ?: throw IllegalStateException("Bluetooth ni na voljo")
        val transport: Transport = when (printer.transport) {
            PrinterRegistry.TransportType.CLASSIC -> BluetoothTransport(adapter, printer.address)
            PrinterRegistry.TransportType.BLE -> BleTransport(context, adapter, printer.address)
        }
        transport.use { tx ->
            tx.connect()
            tx.write(data)
        }
    }
}
