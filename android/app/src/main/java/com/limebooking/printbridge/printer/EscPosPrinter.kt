package com.limebooking.printbridge.printer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Top-level print orchestrator. Receives the same JSON shape that
 * `qz.print(config, data)` sends to the desktop QZ Tray daemon
 * (see js/qz-tray.js:1737-1744 for the exact envelope).
 *
 * Translates each `data[]` entry to ESC/POS bytes, opens a Bluetooth socket
 * to the targeted printer, writes the byte stream, and closes.
 */
class EscPosPrinter(private val registry: PrinterRegistry) {

    companion object {
        private const val TAG = "EscPosPrinter"
    }

    /** params shape: {printer:{name|host}, options:{...}, data:[{type,format,flavor,data,options?}, ...]} */
    fun print(params: JSONObject) {
        val printerName = params.optJSONObject("printer")?.optString("name") ?: ""
        val printer = registry.findByName(printerName)
            ?: throw IllegalStateException("Printer not selected — open Lime Print Bridge settings to choose one")

        val data = params.optJSONArray("data") ?: JSONArray()
        val options = params.optJSONObject("options") ?: JSONObject()
        val encoding = options.optString("encoding", "cp1250").lowercase()

        val job = PrintJob.build(data, encoding, registry.paperWidthDots)

        val adapter = registry.adapter() ?: throw IllegalStateException("Bluetooth not available")
        BluetoothTransport(adapter, printer.address).use { tx ->
            tx.connect()
            tx.write(job)
            // Trailing feed + cut so the receipt is presented for tearing
            tx.write(byteArrayOf(0x0A, 0x0A, 0x0A) + CUT_PARTIAL)
        }
        Log.i(TAG, "Printed ${job.size} bytes to ${printer.name}")
    }

    private val CUT_PARTIAL = byteArrayOf(0x1D, 0x56, 0x42, 0x00) // GS V B 0
}
