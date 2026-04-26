package com.limebooking.printbridge.printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Discovers BLE peripherals via [android.bluetooth.le.BluetoothLeScanner].
 *
 * BLE thermal printers typically advertise a few well-known GATT service UUIDs
 * (FF00, 18F0, FFE0, FFF0, 49535343-...). Rather than scanning with a strict
 * service filter — which silently misses devices that advertise no service
 * UUIDs in their broadcast (very common on cheap firmware) — we scan for
 * everything and rely on the UI to surface likely printers via name heuristics
 * + the discovered service UUIDs at advertisement time. Auto-discovery of the
 * writable characteristic happens later in [BleTransport.connect].
 */
class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        private const val SCAN_TIMEOUT_MS = 12_000L

        // Hints for the UI to bold "likely" printers
        val PRINTER_NAME_HINTS = listOf(
            "pos", "print", "rpp", "spp", "mtp", "thermal",
            "munbyn", "goojprt", "xprinter", "xp-58", "xp-80",
            "pt-2", "ph58", "rp-", "bxl"
        )
        val PRINTER_SERVICE_HINTS: Set<String> = setOf(
            "0000ff00-0000-1000-8000-00805f9b34fb",
            "000018f0-0000-1000-8000-00805f9b34fb",
            "0000ffe0-0000-1000-8000-00805f9b34fb",
            "0000fff0-0000-1000-8000-00805f9b34fb",
            "49535343-fe7d-4ae5-8fa9-9fafd205e455"
        )
    }

    data class Discovered(
        val name: String,
        val address: String,
        val rssi: Int,
        val isLikelyPrinter: Boolean
    )

    interface Listener {
        fun onDiscovered(devices: List<Discovered>)
        fun onScanFinished()
        fun onError(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val results = LinkedHashMap<String, Discovered>()
    private var listener: Listener? = null
    private var scanning = false
    private var timeoutRunnable: Runnable? = null

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = try { device.name ?: result.scanRecord?.deviceName ?: "" } catch (e: SecurityException) { "" }
            if (name.isEmpty() && device.address == null) return
            val address = device.address ?: return

            val advertisedServices = result.scanRecord?.serviceUuids
                ?.map { it.uuid.toString().lowercase() }
                ?: emptyList()
            val nameMatches = name.isNotEmpty() &&
                PRINTER_NAME_HINTS.any { name.lowercase().contains(it) }
            val serviceMatches = advertisedServices.any { it in PRINTER_SERVICE_HINTS }
            val isLikelyPrinter = nameMatches || serviceMatches

            results[address] = Discovered(
                name = name.ifEmpty { "(neimenovan)" },
                address = address,
                rssi = result.rssi,
                isLikelyPrinter = isLikelyPrinter
            )
            listener?.onDiscovered(results.values.toList())
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener?.onError("BLE scan napaka: $errorCode")
        }
    }

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun start(adapter: BluetoothAdapter, listener: Listener) {
        if (scanning) return
        if (!hasScanPermission()) {
            listener.onError("Manjka dovoljenje za BLE scan")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            listener.onError("BLE scanner ni na voljo (Bluetooth ugasnjen?)"); return
        }
        this.listener = listener
        results.clear()
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            // Filters are intentionally null — many BLE printer advertisements
            // omit service UUIDs in the connectable broadcast.
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            scanning = false
            listener.onError("Manjka dovoljenje: ${e.message}")
            return
        }
        val timeout = Runnable {
            stop(adapter)
            this.listener?.onScanFinished()
        }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stop(adapter: BluetoothAdapter) {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        if (!scanning) return
        scanning = false
        try { adapter.bluetoothLeScanner?.stopScan(callback) } catch (e: Exception) {
            Log.w(TAG, "stopScan", e)
        }
    }

    fun discovered(): List<Discovered> = results.values.toList()
    fun isScanning(): Boolean = scanning
}
