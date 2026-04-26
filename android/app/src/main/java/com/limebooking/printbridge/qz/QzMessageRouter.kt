package com.limebooking.printbridge.qz

import android.util.Log
import com.limebooking.printbridge.printer.EscPosPrinter
import com.limebooking.printbridge.printer.PrinterRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Dispatches JSON envelopes from qz-tray.js to the appropriate handler.
 *
 * Mirrors the call dispatch in src/qz/ws/PrintSocketClient.processMessage()
 * and the SocketMethod enum in src/qz/ws/SocketMethod.java.
 *
 * Transport-agnostic: works with both [QzServer] (real WebSocket) and the
 * in-WebView [QzBridge] (JavascriptInterface).
 *
 * MVP signature handling: we accept the certificate on first connect (with
 * user dialog) and skip per-call signature verification. Localhost-only
 * attack surface — only our own WebView can reach this dispatcher.
 */
class QzMessageRouter(
    private val printerRegistry: PrinterRegistry,
    private val printer: EscPosPrinter,
    private val allowedCerts: QzAllowedCerts,
    private val certApprovalHandler: CertApprovalHandler
) {

    companion object {
        private const val TAG = "QzMessageRouter"
        private const val EMULATED_VERSION = "2.2.6"
    }

    /** Asks the user to approve an unknown PWA cert. */
    fun interface CertApprovalHandler {
        /** Blocks until the user decides; returns true if allowed. */
        fun requestApproval(certPem: String, commonName: String?): Boolean
    }

    private data class ConnState(var certPem: String? = null, var allowed: Boolean = false)

    private val connections = ConcurrentHashMap<String, ConnState>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "qz-worker").apply { isDaemon = true }
    }

    fun onConnectionOpened(conn: QzConnection) {
        connections[conn.id] = ConnState()
    }

    fun onConnectionClosed(conn: QzConnection) {
        connections.remove(conn.id)
    }

    fun dispatch(conn: QzConnection, json: JSONObject) {
        val uid = json.optString("uid", "")

        if (json.has("certificate")) {
            handleCertHandshake(conn, uid, json.optString("certificate"))
            return
        }

        val call = json.optString("call")
        if (call.isEmpty()) {
            sendError(conn, uid, "Missing 'call' in message")
            return
        }

        pool.execute {
            try {
                handleCall(conn, uid, call, json)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing call=$call", e)
                sendError(conn, uid, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun handleCertHandshake(conn: QzConnection, uid: String, pem: String) {
        if (pem.isEmpty()) {
            connections[conn.id]?.allowed = true
            sendResult(conn, uid, JSONObject.NULL)
            return
        }

        val state = connections[conn.id] ?: ConnState().also { connections[conn.id] = it }
        state.certPem = pem

        when (allowedCerts.decisionFor(pem)) {
            QzAllowedCerts.Decision.ALLOW -> {
                state.allowed = true
                sendResult(conn, uid, JSONObject.NULL)
            }
            QzAllowedCerts.Decision.BLOCK -> {
                sendError(conn, uid, "Connection blocked by user")
                conn.close()
            }
            QzAllowedCerts.Decision.UNKNOWN -> pool.execute {
                val cn = extractCommonName(pem)
                val approved = try {
                    certApprovalHandler.requestApproval(pem, cn)
                } catch (e: Exception) {
                    Log.w(TAG, "Approval handler failed", e); false
                }
                if (approved) {
                    state.allowed = true
                    sendResult(conn, uid, JSONObject.NULL)
                } else {
                    sendError(conn, uid, "Connection blocked by user")
                    conn.close()
                }
            }
        }
    }

    private fun handleCall(conn: QzConnection, uid: String, call: String, json: JSONObject) {
        // Unsigned calls don't require cert handshake
        when (call) {
            "getVersion" -> {
                sendResult(conn, uid, EMULATED_VERSION); return
            }
            "websocket.getNetworkInfo", "networking.deviceLegacy", "networking.device" -> {
                sendResult(
                    conn, uid,
                    JSONObject().put("ipAddress", "127.0.0.1").put("macAddress", "00:00:00:00:00:00")
                ); return
            }
            "websocket.getNetworkInterfaces", "networking.devices" -> {
                sendResult(conn, uid, JSONArray()); return
            }
        }

        val state = connections[conn.id]
        if (state?.allowed != true) {
            sendError(conn, uid, "Connection not yet authorized")
            return
        }

        when (call) {
            "printers.getDefault" -> {
                sendResult(conn, uid, printerRegistry.defaultPrinterName() ?: JSONObject.NULL)
            }
            "printers.find" -> {
                val query = json.optJSONObject("params")?.optString("query")
                val matches = printerRegistry.listAvailable().map { it.name }.filter {
                    query.isNullOrEmpty() || it.contains(query, ignoreCase = true)
                }
                sendResult(conn, uid, JSONArray(matches))
            }
            "printers.details", "printers.detail" -> {
                val arr = JSONArray()
                printerRegistry.listAvailable().forEach { p ->
                    arr.put(JSONObject().put("name", p.name).put("driver", "ESCPOS").put("connection", "BLUETOOTH"))
                }
                sendResult(conn, uid, arr)
            }
            "printers.getStatus" -> sendResult(conn, uid, JSONObject.NULL)
            "print" -> {
                val params = json.getJSONObject("params")
                printer.print(params)
                sendResult(conn, uid, JSONObject.NULL)
            }
            "websocket.stop" -> sendResult(conn, uid, JSONObject.NULL)
            else -> {
                Log.w(TAG, "Unhandled call: $call (responding null)")
                sendResult(conn, uid, JSONObject.NULL)
            }
        }
    }

    private fun sendResult(conn: QzConnection, uid: String, result: Any) {
        try {
            conn.send(JSONObject().put("uid", uid).put("result", result).toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send result", e)
        }
    }

    private fun sendError(conn: QzConnection, uid: String?, message: String) {
        try {
            conn.send(JSONObject().put("uid", uid ?: "").put("error", message).toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send error", e)
        }
    }

    private fun extractCommonName(pem: String): String? {
        return try {
            val cleaned = pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\\s".toRegex(), "")
            val der = android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
            val factory = java.security.cert.CertificateFactory.getInstance("X.509")
            val cert = factory.generateCertificate(java.io.ByteArrayInputStream(der))
                    as java.security.cert.X509Certificate
            val dn = cert.subjectX500Principal.name
            Regex("CN=([^,]+)").find(dn)?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract CN from cert", e); null
        }
    }
}
