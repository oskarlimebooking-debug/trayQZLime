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
 * Tracks the user's chosen printer (Classic-paired or BLE-scanned) and the
 * paper width preference. Persists across launches via SharedPreferences.
 */
class PrinterRegistry(private val context: Context) {

    enum class TransportType { CLASSIC, BLE }

    data class Printer(
        val name: String,
        val address: String,
        val transport: TransportType
    )

    companion object {
        private const val PREFS = "printer_registry"
        private const val KEY_DEFAULT_NAME = "default_name"
        private const val KEY_DEFAULT_ADDRESS = "default_address"
        private const val KEY_DEFAULT_TRANSPORT = "default_transport"
        private const val KEY_PAPER_WIDTH = "paper_width"

        // Bluetooth Classic IMAGING major class
        private const val IMAGING_MAJOR = 0x0600

        private val PRINTER_NAME_HINTS = listOf(
            "pos-58", "pos-80", "pos58", "pos80",
            "printer", "print", "spp", "rpp", "bt-spp",
            "munbyn", "goojprt", "xprinter", "xp-58", "xp-80",
            "pt-210", "ph58", "mtp"
        )
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun adapter(): BluetoothAdapter? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter
    }

    fun hasBluetoothConnectPermission(): Boolean {
        val p = if (android.os.Build.VERSION.SDK_INT >= 31)
            Manifest.permission.BLUETOOTH_CONNECT
        else
            Manifest.permission.BLUETOOTH
        return ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Lists Bluetooth Classic devices the user has already paired. BLE devices
     * generally don't show up here even if previously connected — they're
     * discovered via [BleScanner] instead.
     */
    @SuppressLint("MissingPermission")
    fun listClassicPaired(): List<Printer> {
        if (!hasBluetoothConnectPermission()) return emptyList()
        val adapter = adapter() ?: return emptyList()
        val bonded = try {
            adapter.bondedDevices ?: emptySet()
        } catch (e: SecurityException) { emptySet() }

        return bonded.mapNotNull { dev ->
            val name = try { dev.name } catch (e: SecurityException) { null } ?: return@mapNotNull null
            val nameMatches = PRINTER_NAME_HINTS.any { name.lowercase().contains(it) }
            val classMatches = try {
                dev.bluetoothClass?.majorDeviceClass == IMAGING_MAJOR
            } catch (e: SecurityException) { false }
            if (nameMatches || classMatches) Printer(name, dev.address, TransportType.CLASSIC) else null
        }.sortedBy { it.name }
    }

    fun setDefault(p: Printer) {
        prefs.edit()
            .putString(KEY_DEFAULT_NAME, p.name)
            .putString(KEY_DEFAULT_ADDRESS, p.address)
            .putString(KEY_DEFAULT_TRANSPORT, p.transport.name)
            .apply()
    }

    fun defaultPrinter(): Printer? {
        val name = prefs.getString(KEY_DEFAULT_NAME, null) ?: return null
        val addr = prefs.getString(KEY_DEFAULT_ADDRESS, null) ?: return null
        val tx = prefs.getString(KEY_DEFAULT_TRANSPORT, null)?.let {
            runCatching { TransportType.valueOf(it) }.getOrNull()
        } ?: TransportType.CLASSIC // legacy installs default to Classic
        return Printer(name, addr, tx)
    }

    fun defaultPrinterName(): String? = prefs.getString(KEY_DEFAULT_NAME, null)

    /**
     * Best-effort enumeration for `printers.find` / `printers.details`. Returns
     * paired Classic devices plus the saved default (which might be BLE — those
     * we don't keep a system-side bond record for).
     */
    fun listAvailable(): List<Printer> {
        val classic = listClassicPaired()
        val def = defaultPrinter()
        return if (def != null && classic.none { it.address == def.address }) classic + def else classic
    }

    fun findByName(name: String): Printer? {
        if (name.isEmpty()) return defaultPrinter()
        // Try paired Classic first; otherwise default. We don't keep BLE scan
        // history past app launch, so a name-only lookup for BLE falls back
        // to whatever the user saved as default.
        listClassicPaired().firstOrNull { it.name == name }?.let { return it }
        listClassicPaired().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        return defaultPrinter()
    }

    var paperColumns: Int
        get() = prefs.getInt(KEY_PAPER_WIDTH, 33)
        set(value) { prefs.edit().putInt(KEY_PAPER_WIDTH, value).apply() }

    /** Pixel width of the print head — 384 dots @ 58mm, 576 dots @ 80mm. */
    val paperWidthDots: Int
        get() = if (paperColumns >= 48) 576 else 384
}
