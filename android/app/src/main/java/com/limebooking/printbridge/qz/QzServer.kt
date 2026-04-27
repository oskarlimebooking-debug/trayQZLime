package com.limebooking.printbridge.qz

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext

/**
 * Local WebSocket-Secure server that emulates the QZ Tray daemon protocol.
 *
 * Mirrors src/qz/ws/PrintSocketServer.java (port allocation + handshake).
 *
 * NOTE: Android WebView's `new WebSocket(...)` does NOT trigger
 * WebViewClient.onReceivedSslError, so a self-signed cert here is silently
 * rejected by the WebView itself. Inside the WebView we use [QzBridge]
 * (JavascriptInterface) instead. This server is kept around for two reasons:
 *
 *   1. External clients can connect to the phone over LAN (e.g. desktop test
 *      script with the cert pinned).
 *   2. If a future Android WebView gains SSL override hooks for WebSocket,
 *      this becomes the simpler path.
 */
class QzServer(
    private val sslContext: SSLContext,
    private val router: QzMessageRouter
) {

    companion object {
        private const val TAG = "QzServer"
        // Mirrors Constants.DEFAULT_WSS_PORTS in src/qz/common/Constants.java
        val DEFAULT_WSS_PORTS = intArrayOf(8181, 8282, 8383, 8484)
        private const val PROBE_REQUEST = "getProgramName"
        private const val PROBE_RESPONSE = "QZ Tray"
    }

    private var server: WebSocketServer? = null
    private var boundPort: Int = -1
    private val connections = ConcurrentHashMap<WebSocket, QzConnection>()

    fun start(): Int {
        for (port in DEFAULT_WSS_PORTS) {
            try {
                val s = createServer(port)
                s.isReuseAddr = true
                s.start()
                server = s
                boundPort = port
                Log.i(TAG, "Listening on wss://localhost:$port")
                return port
            } catch (e: Exception) {
                Log.w(TAG, "Port $port unavailable: ${e.message}")
            }
        }
        throw IllegalStateException("All WSS ports busy")
    }

    fun stop() {
        try { server?.stop(1000) } catch (e: Exception) { Log.w(TAG, "Error stopping", e) }
        server = null
        boundPort = -1
        connections.clear()
    }

    fun port(): Int = boundPort

    private fun createServer(port: Int): WebSocketServer {
        val s = object : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {

            override fun onStart() {
                connectionLostTimeout = 0
            }

            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                val wrapped = WsConnection(conn)
                connections[conn] = wrapped
                router.onConnectionOpened(wrapped)
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
                connections.remove(conn)?.let { router.onConnectionClosed(it) }
            }

            override fun onMessage(conn: WebSocket, message: String) {
                if (message == PROBE_REQUEST) { conn.send(PROBE_RESPONSE); return }
                if (message == "ping") return
                val wrapped = connections[conn] ?: WsConnection(conn).also { connections[conn] = it }
                try {
                    router.dispatch(wrapped, JSONObject(message))
                } catch (e: Exception) {
                    Log.e(TAG, "Bad message", e)
                }
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                Log.e(TAG, "WebSocket error", ex)
            }
        }
        s.setWebSocketFactory(DefaultSSLWebSocketServerFactory(sslContext))
        return s
    }

    private class WsConnection(private val ws: WebSocket) : QzConnection {
        override val id: String = "ws-${ws.remoteSocketAddress?.port ?: System.identityHashCode(ws)}"
        override fun send(text: String) { try { ws.send(text) } catch (_: Exception) {} }
        override fun close() { try { ws.close() } catch (_: Exception) {} }
    }
}
