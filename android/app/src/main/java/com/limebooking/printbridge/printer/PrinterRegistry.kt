package com.limebooking.printbridge.printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Enumerates paired Bluetooth printers and tracks the user-selected default.
 *
 * Filters bonded devices by Major Device Class = IMAGING (0x0600) or any
 * device whose name contains common thermal-printer markers. Most cheap
 * POS-58/POS-80 dongles report as Imaging+Printer minor class.
 */
class PrinterRegistry(private val context: Context) {

    companion object {
        private const val PREFS = "printer_registry"
        private const val KEY_DEFAULT_NAME = "default_name"
        private const val KEY_DEFAULT_ADDRESS = "default_address"
        private const val KEY_PAPER_WIDTH = "paper_width"

        // ESC/POS thermal printers report this Major Device Class
        // (BluetoothClass.Device.Major.IMAGING == 0x0600)
        private val PRINTER_NAME_HINTS = listOf(
            "pos-58", "pos-80", "pos58", "pos80",
            "printer", "print", "spp", "rpp", "bt-spp",
            "munbyn", "goojprt", "xprinter", "xp-58", "xp-80",
            "pt-210", "ph58", "mtp"
        )
    }

    data class Printer(val name: String, val address: String)

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasBluetoothPermission(): Boolean {
        val p = if (android.os.Build.VERSION.SDK_INT >= 31)
            Manifest.permission.BLUETOOTH_CONNECT
        else
            Manifest.permission.BLUETOOTH
        return ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun list(): List<Printer> {
        if (!hasBluetoothPermission()) return emptyList()
        val adapter = adapter() ?: return emptyList()
        val bonded = try { adapter.bondedDevices ?: emptySet() } catch (e: SecurityException) { emptySet() }

        return bonded.mapNotNull { dev ->
            val name = try { dev.name } catch (e: SecurityException) { null } ?: return@mapNotNull null
            val nameMatches = PRINTER_NAME_HINTS.any { name.lowercase().contains(it) }
            val classMatches = try {
                dev.bluetoothClass?.majorDeviceClass == 0x0600 // IMAGING
            } catch (e: SecurityException) { false }
            if (nameMatches || classMatches) Printer(name, dev.address) else null
        }.sortedBy { it.name }
    }

    fun adapter(): BluetoothAdapter? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter
    }

    fun setDefault(p: Printer) {
        prefs.edit()
            .putString(KEY_DEFAULT_NAME, p.name)
            .putString(KEY_DEFAULT_ADDRESS, p.address)
            .apply()
    }

    fun defaultPrinter(): Printer? {
        val name = prefs.getString(KEY_DEFAULT_NAME, null) ?: return null
        val addr = prefs.getString(KEY_DEFAULT_ADDRESS, null) ?: return null
        return Printer(name, addr)
    }

    fun defaultPrinterName(): String? = prefs.getString(KEY_DEFAULT_NAME, null)

    fun findByName(name: String): Printer? {
        if (name.isEmpty()) return defaultPrinter()
        // Try exact match first, then case-insensitive
        list().firstOrNull { it.name == name }?.let { return it }
        list().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        // Fall back to default if name doesn't match (e.g. PWA cached an old printer name)
        return defaultPrinter()
    }

    var paperColumns: Int
        get() = prefs.getInt(KEY_PAPER_WIDTH, 33)
        set(value) { prefs.edit().putInt(KEY_PAPER_WIDTH, value).apply() }

    /** Pixel width of the print head — 384 dots for 58mm, 576 for 80mm. */
    val paperWidthDots: Int
        get() = if (paperColumns >= 48) 576 else 384
}
