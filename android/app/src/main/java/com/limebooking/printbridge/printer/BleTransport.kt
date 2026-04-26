package com.limebooking.printbridge.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BLE GATT transport for thermal printers. Mirrors the iOS implementation in
 * `ios/LimePrintBridge/Printer/BlePrinter.swift`.
 *
 * Behaviour:
 *  - Connect, request a large MTU (517 → typically negotiates ~180-185 effective
 *    payload after BLE/ATT overhead), discover services.
 *  - Probe every characteristic of every service for `WRITE_NO_RESPONSE` first
 *    (faster streaming) then `WRITE` (slower, ack'd). The first writable
 *    characteristic wins. This works for all common Chinese POS-58/POS-80 BLE
 *    modules without hard-coding vendor UUIDs.
 *  - Chunked writes capped at the negotiated MTU minus 3 bytes ATT overhead.
 *    Cap at 180 bytes regardless to dodge unstable links on cheap firmware.
 *  - With WRITE_NO_RESPONSE we throttle via `onCharacteristicWrite` callback
 *    + a small inter-chunk sleep, since cheap firmware silently drops bytes
 *    when their RX buffer overflows.
 *
 * NOTE: methods must be called from a non-main thread; many BLE callbacks are
 * delivered synchronously and the connect dance involves CountDownLatch waits.
 */
class BleTransport(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val deviceAddress: String
) : Transport {

    companion object {
        private const val TAG = "BleTransport"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val DISCOVER_TIMEOUT_MS = 10_000L
        private const val MTU_TIMEOUT_MS = 5_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val INTER_CHUNK_DELAY_MS = 8L
        private const val DESIRED_MTU = 517
        private const val MAX_CHUNK_BYTES = 180
        private const val ATT_OVERHEAD = 3
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = 23 // BLE default

    private val connectLatch = CountDownLatch(1)
    private val discoverLatch = CountDownLatch(1)
    private val mtuLatch = CountDownLatch(1)
    private var writeAckLatch: CountDownLatch? = null

    private val connected = AtomicBoolean(false)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.set(true)
                connectLatch.countDown()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected.set(false)
                // Make any waiters bail
                connectLatch.countDown()
                discoverLatch.countDown()
                mtuLatch.countDown()
                writeAckLatch?.countDown()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Log.i(TAG, "MTU negotiated: $mtu")
            }
            mtuLatch.countDown()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeChar = pickWritableCharacteristic(g)
            }
            discoverLatch.countDown()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeAckLatch?.countDown()
        }
    }

    private fun pickWritableCharacteristic(g: BluetoothGatt): BluetoothGattCharacteristic? {
        val services = g.services ?: return null
        // Prefer WRITE_NO_RESPONSE for streaming throughput, fall back to plain WRITE
        for (s in services) {
            for (c in s.characteristics) {
                val props = c.properties
                if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    return c
                }
            }
        }
        for (s in services) {
            for (c in s.characteristics) {
                val props = c.properties
                if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    return c
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    override fun connect() {
        val device = adapter.getRemoteDevice(deviceAddress)
        // autoConnect=false → faster initial connect; iOS-style. Some devices
        // need autoConnect=true for re-pair; user can re-trigger via Settings.
        gatt = device.connectGatt(context, false, callback)
            ?: throw IOException("connectGatt returned null")

        if (!connectLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            close()
            throw IOException("BLE connect timeout")
        }
        if (!connected.get()) {
            close()
            throw IOException("BLE connect failed")
        }

        gatt?.requestMtu(DESIRED_MTU)
        if (!mtuLatch.await(MTU_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "MTU negotiation timed out — staying at 23")
        }

        if (gatt?.discoverServices() != true) {
            close()
            throw IOException("discoverServices failed to start")
        }
        if (!discoverLatch.await(DISCOVER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            close()
            throw IOException("Service discovery timeout")
        }
        if (writeChar == null) {
            close()
            throw IOException("Tiskalnik nima writable characteristic-a (ni BLE printer ali nepodprt firmware)")
        }
    }

    @SuppressLint("MissingPermission")
    override fun write(bytes: ByteArray) {
        val g = gatt ?: throw IOException("Not connected")
        val ch = writeChar ?: throw IOException("No writable characteristic")
        val payloadCap = minOf(MAX_CHUNK_BYTES, negotiatedMtu - ATT_OVERHEAD).coerceAtLeast(20)
        val isWriteNoResp = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val writeType = if (isWriteNoResp)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        var offset = 0
        while (offset < bytes.size) {
            if (!connected.get()) throw IOException("Disconnected mid-write")
            val len = minOf(payloadCap, bytes.size - offset)
            val chunk = bytes.copyOfRange(offset, offset + len)

            writeAckLatch = CountDownLatch(1)
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                // New API: returns BluetoothStatusCodes.SUCCESS (0) on success.
                g.writeCharacteristic(ch, chunk, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = writeType
                    @Suppress("DEPRECATION") ch.value = chunk
                    @Suppress("DEPRECATION") g.writeCharacteristic(ch)
                }
            }
            if (!ok) throw IOException("BLE writeCharacteristic returned failure")

            // For WRITE_NO_RESPONSE Android still calls onCharacteristicWrite to
            // signal "ready for next chunk". Use a generous timeout.
            if (!writeAckLatch!!.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IOException("BLE write ack timeout at offset $offset")
            }
            offset += len
            if (offset < bytes.size) {
                try { Thread.sleep(INTER_CHUNK_DELAY_MS) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt(); break
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeChar = null
        connected.set(false)
    }
}
