package com.limebooking.printbridge.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * RFCOMM SPP socket wrapper for ESC/POS thermal printers.
 *
 * Many cheap POS-58 printers have a tiny RX buffer (often 256 bytes) and
 * silently drop bytes if the firmware can't keep up. We chunk writes and
 * sleep briefly between chunks.
 */
class BluetoothTransport(
    private val adapter: BluetoothAdapter,
    private val deviceAddress: String
) : Transport {

    companion object {
        private const val TAG = "BluetoothTransport"
        // SPP UUID — universal for serial-over-Bluetooth
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CHUNK_SIZE = 256
        private const val INTER_CHUNK_DELAY_MS = 30L
    }

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null

    @SuppressLint("MissingPermission")
    override fun connect() {
        val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)
        // Cancel any in-progress discovery; it slows down RFCOMM connect
        try { adapter.cancelDiscovery() } catch (e: SecurityException) { /* ignore */ }

        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            s.connect()
        } catch (e: IOException) {
            Log.w(TAG, "Standard RFCOMM connect failed, trying reflection fallback", e)
            try { s.close() } catch (_: Exception) {}
            // Reflection fallback for buggy firmwares (channel 1)
            val fallback = device.javaClass
                .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket
            fallback.connect()
            socket = fallback
            output = fallback.outputStream
            return
        }
        socket = s
        output = s.outputStream
    }

    override fun write(bytes: ByteArray) {
        val out = output ?: throw IllegalStateException("Not connected")
        var offset = 0
        while (offset < bytes.size) {
            val len = minOf(CHUNK_SIZE, bytes.size - offset)
            out.write(bytes, offset, len)
            out.flush()
            offset += len
            if (offset < bytes.size) {
                try { Thread.sleep(INTER_CHUNK_DELAY_MS) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
    }

    override fun close() {
        try { output?.close() } catch (e: Exception) { /* ignore */ }
        try { socket?.close() } catch (e: Exception) { /* ignore */ }
        output = null
        socket = null
    }
}
