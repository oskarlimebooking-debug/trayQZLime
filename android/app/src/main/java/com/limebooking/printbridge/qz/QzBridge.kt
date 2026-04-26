package com.limebooking.printbridge.qz

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process bridge between the WebView's JavaScript and our QzMessageRouter.
 *
 * The companion JS shim ([SHIM_JS]) injected into every page replaces
 * `window.WebSocket` for `wss://localhost*` URLs with a fake socket whose
 * send() forwards through this bridge. Responses come back via
 * `webView.evaluateJavascript("__qzBridge.recv(...)")`.
 *
 * This is the primary transport because WebView silently rejects self-signed
 * TLS certs on real WebSocket connections (no SslError callback fires for
 * `new WebSocket()` — only for HTTP resource loads).
 */
class QzBridge(
    private val webView: WebView,
    private val router: QzMessageRouter
) {

    companion object {
        private const val TAG = "QzBridge"
        const val INTERFACE_NAME = "AndroidQzBridge"

        /** Inject this JS into every page on `onPageStarted`. */
        val SHIM_JS = """
            (function() {
              if (window.__qzBridgeInstalled) return;
              window.__qzBridgeInstalled = true;
              var sockets = {};
              var nextId = 1;

              function FakeWS(url, protocols) {
                var self = this;
                self.url = url;
                self.readyState = 0; // CONNECTING
                self.protocol = '';
                self.extensions = '';
                self.bufferedAmount = 0;
                self.binaryType = 'blob';
                self.onopen = null;
                self.onclose = null;
                self.onerror = null;
                self.onmessage = null;
                var listeners = { open: [], close: [], error: [], message: [] };
                self.addEventListener = function(t, fn) {
                  if (listeners[t]) listeners[t].push(fn);
                };
                self.removeEventListener = function(t, fn) {
                  if (listeners[t]) {
                    var i = listeners[t].indexOf(fn);
                    if (i >= 0) listeners[t].splice(i, 1);
                  }
                };
                self.dispatchEvent = function(ev) {
                  if (self['on' + ev.type]) try { self['on' + ev.type](ev); } catch(e){}
                  (listeners[ev.type] || []).forEach(function(fn){ try { fn(ev); } catch(e){} });
                };
                self._id = String(nextId++);
                sockets[self._id] = self;

                self.send = function(data) {
                  var msg = (typeof data === 'string') ? data : new TextDecoder().decode(data);
                  AndroidQzBridge.send(self._id, msg);
                };
                self.close = function(code, reason) {
                  AndroidQzBridge.close(self._id);
                  delete sockets[self._id];
                  self.readyState = 3;
                  self.dispatchEvent({ type: 'close', code: code || 1000, reason: reason || '', wasClean: true });
                };

                setTimeout(function() {
                  AndroidQzBridge.connect(self._id, url);
                  self.readyState = 1;
                  self.dispatchEvent({ type: 'open' });
                }, 0);
              }
              FakeWS.CONNECTING = 0; FakeWS.OPEN = 1; FakeWS.CLOSING = 2; FakeWS.CLOSED = 3;
              FakeWS.prototype.CONNECTING = 0;
              FakeWS.prototype.OPEN = 1;
              FakeWS.prototype.CLOSING = 2;
              FakeWS.prototype.CLOSED = 3;

              window.__qzBridge = {
                recv: function(id, data) {
                  var s = sockets[id];
                  if (!s) return;
                  s.dispatchEvent({ type: 'message', data: data });
                },
                close: function(id, code, reason) {
                  var s = sockets[id];
                  if (!s) return;
                  s.readyState = 3;
                  delete sockets[id];
                  s.dispatchEvent({ type: 'close', code: code || 1000, reason: reason || '', wasClean: true });
                }
              };

              var NativeWS = window.WebSocket;
              window.WebSocket = function(url, protocols) {
                var lower = String(url).toLowerCase();
                if (lower.indexOf('wss://localhost') === 0 ||
                    lower.indexOf('ws://localhost') === 0 ||
                    lower.indexOf('wss://127.0.0.1') === 0 ||
                    lower.indexOf('ws://127.0.0.1') === 0 ||
                    lower.indexOf('wss://localhost.qz.io') === 0 ||
                    lower.indexOf('ws://localhost.qz.io') === 0) {
                  return new FakeWS(url, protocols);
                }
                return new NativeWS(url, protocols);
              };
              window.WebSocket.CONNECTING = 0;
              window.WebSocket.OPEN = 1;
              window.WebSocket.CLOSING = 2;
              window.WebSocket.CLOSED = 3;
              // Don't reassign window.WebSocket.prototype — FakeWS instances would lose
              // their methods (addEventListener etc.) since they're defined per-instance,
              // and `instanceof WebSocket` would still work for native instances.
            })();
        """.trimIndent()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connections = ConcurrentHashMap<String, BridgedConnection>()

    @JavascriptInterface
    fun connect(id: String, url: String) {
        Log.d(TAG, "JS connect id=$id url=$url")
        val conn = BridgedConnection(id)
        connections[id] = conn
        router.onConnectionOpened(conn)
    }

    @JavascriptInterface
    fun send(id: String, message: String) {
        val conn = connections[id] ?: run {
            Log.w(TAG, "send for unknown id=$id"); return
        }
        // Plain-text probe handshake (qz-tray.js sends "getProgramName" before any JSON)
        if (message == "getProgramName") {
            conn.send("QZ Tray"); return
        }
        if (message == "ping") return
        try {
            router.dispatch(conn, JSONObject(message))
        } catch (e: Exception) {
            Log.e(TAG, "Bad bridge message: ${message.take(200)}", e)
        }
    }

    @JavascriptInterface
    fun close(id: String) {
        connections.remove(id)?.let { router.onConnectionClosed(it) }
    }

    private inner class BridgedConnection(override val id: String) : QzConnection {
        override fun send(text: String) {
            // text could be either a JSON envelope (from sendResult/Error) or the
            // plain "QZ Tray" probe response. Forward as-is.
            val esc = jsEscape(text)
            mainHandler.post {
                webView.evaluateJavascript(
                    "window.__qzBridge && window.__qzBridge.recv('$id', '$esc');",
                    null
                )
            }
        }

        override fun close() {
            mainHandler.post {
                webView.evaluateJavascript(
                    "window.__qzBridge && window.__qzBridge.close('$id');",
                    null
                )
            }
        }
    }

    private fun jsEscape(s: String): String {
        val sb = StringBuilder(s.length + 32)
        for (c in s) {
            when (c.code) {
                0x5C -> sb.append("\\\\")
                0x27 -> sb.append("\\'")
                0x0A -> sb.append("\\n")
                0x0D -> sb.append("\\r")
                0x09 -> sb.append("\\t")
                0x2028 -> sb.append("\\u2028") // JS line separator (breaks string literals)
                0x2029 -> sb.append("\\u2029") // JS paragraph separator
                0x3C -> sb.append("\\u003c")   // avoid </script> sequences breaking out
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
