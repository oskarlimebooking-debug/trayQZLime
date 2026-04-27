package com.limebooking.printbridge.qz

/**
 * Transport-agnostic abstraction for a connection from a qz-tray.js client.
 *
 * Concrete implementations:
 *  - [QzServer] backs each connection with a real WebSocket (for external clients,
 *    e.g. another machine on the LAN talking to a phone running the bridge).
 *  - [QzBridge] (JS injection) backs each connection with a JavaScript callback
 *    inside the WebView itself. This is the primary path because Android WebView
 *    silently rejects self-signed TLS certs on WebSocket connections — there is
 *    no SslError callback for `new WebSocket()`.
 */
interface QzConnection {
    val id: String
    fun send(text: String)
    fun close()
}
