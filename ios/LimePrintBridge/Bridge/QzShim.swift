import Foundation

/// JavaScript that we inject into every page in the WKWebView at
/// `WKUserScriptInjectionTime.atDocumentStart`. It monkey-patches
/// `window.WebSocket` so any `wss://localhost*` connection from
/// `qz-tray.js` (the library Lime Booking ships) is routed through the
/// `webkit.messageHandlers.qz` bridge into our native QzMessageRouter
/// instead of going to the network.
///
/// This sidesteps the fact that WKWebView silently rejects self-signed TLS
/// certs on real WebSocket connections — there is no
/// `webView(_:didReceive:completionHandler:)` callback for `new WebSocket()`.
enum QzShim {
    static let js: String = #"""
    (function() {
      if (window.__qzBridgeInstalled) return;
      window.__qzBridgeInstalled = true;
      var sockets = {};
      var nextId = 1;

      function send(payload) {
        try {
          window.webkit.messageHandlers.qz.postMessage(payload);
        } catch (e) { /* bridge not ready */ }
      }

      function FakeWS(url, protocols) {
        var self = this;
        self.url = url;
        self.readyState = 0;
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
          if (!listeners[t]) return;
          var i = listeners[t].indexOf(fn);
          if (i >= 0) listeners[t].splice(i, 1);
        };
        self.dispatchEvent = function(ev) {
          if (self['on' + ev.type]) try { self['on' + ev.type](ev); } catch(e){}
          (listeners[ev.type] || []).forEach(function(fn){ try { fn(ev); } catch(e){} });
        };
        self._id = String(nextId++);
        sockets[self._id] = self;

        self.send = function(data) {
          var msg = (typeof data === 'string') ? data : new TextDecoder().decode(data);
          send({ kind: 'send', id: self._id, message: msg });
        };
        self.close = function(code, reason) {
          send({ kind: 'close', id: self._id });
          delete sockets[self._id];
          self.readyState = 3;
          self.dispatchEvent({ type: 'close', code: code || 1000, reason: reason || '', wasClean: true });
        };

        setTimeout(function() {
          send({ kind: 'connect', id: self._id, url: url });
          self.readyState = 1;
          self.dispatchEvent({ type: 'open' });
        }, 0);
      }
      FakeWS.CONNECTING = 0; FakeWS.OPEN = 1; FakeWS.CLOSING = 2; FakeWS.CLOSED = 3;

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
    })();
    """#
}
