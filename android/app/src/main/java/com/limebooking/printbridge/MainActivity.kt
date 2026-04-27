package com.limebooking.printbridge

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import com.limebooking.printbridge.qz.QzBridge
import com.limebooking.printbridge.qz.QzService

/**
 * Hosts the Lime Booking PWA in a full-screen WebView.
 *
 * The WebView's `window.WebSocket` is monkey-patched (via [QzBridge.SHIM_JS]
 * injected on every page load) so that any `wss://localhost*` connection is
 * routed through a [QzBridge] JavascriptInterface to our native
 * [QzMessageRouter] instead of going to the network. This sidesteps the
 * problem that WebView silently rejects self-signed TLS certs on real
 * WebSocket connections.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: QzBridge
    private val app: PrintBridgeApp get() = application as PrintBridgeApp

    private val approvalPoller = Handler(Looper.getMainLooper())
    private val approvalPollTask = object : Runnable {
        override fun run() {
            app.pendingCertApproval()?.let { showApprovalDialog(it) }
            approvalPoller.postDelayed(this, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Make sure the daemon is up (Settings normally starts it; bootstrap as fallback)
        QzService.start(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = settings.userAgentString + " LimePrintBridge/1.0"
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }

        webView.webViewClient = LimeWebViewClient()
        webView.webChromeClient = WebChromeClient()

        val container = FrameLayout(this).apply {
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(container)

        // addJavascriptInterface only takes effect on the *next* page load, so we must
        // wait for the QzMessageRouter to exist (started by QzService) before loadUrl.
        wireBridgeThenLoad()
    }

    private fun wireBridgeThenLoad() {
        val r = app.router()
        if (r != null) {
            bridge = QzBridge(webView, r)
            webView.addJavascriptInterface(bridge, QzBridge.INTERFACE_NAME)
            webView.loadUrl("https://app.lime-booking.com/")
            return
        }
        approvalPoller.postDelayed({ wireBridgeThenLoad() }, 200)
    }

    override fun onResume() {
        super.onResume()
        approvalPoller.post(approvalPollTask)
    }

    override fun onPause() {
        super.onPause()
        approvalPoller.removeCallbacks(approvalPollTask)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.removeJavascriptInterface(QzBridge.INTERFACE_NAME)
        webView.destroy()
        super.onDestroy()
    }

    private fun showApprovalDialog(req: QzService.CertApprovalRequest) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.allow_lime_to_print))
            .setMessage(req.commonName ?: "Lime Booking")
            .setPositiveButton(R.string.allow_always) { _, _ ->
                app.allowedCerts.allow(req.pem); req.response.offer(true)
            }
            .setNeutralButton(R.string.allow_once) { _, _ -> req.response.offer(true) }
            .setNegativeButton(R.string.block) { _, _ ->
                app.allowedCerts.block(req.pem); req.response.offer(false)
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Injects [QzBridge.SHIM_JS] before page scripts run so that the qz-tray.js
     * library finds our patched `window.WebSocket` from its very first call.
     *
     * Also: trust self-signed certs *only* for localhost (defensive — main bridge
     * doesn't use HTTPS, but this protects any future WSS resource loads inside
     * the page).
     */
    private class LimeWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            view.evaluateJavascript(QzBridge.SHIM_JS, null)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val url = error.url ?: ""
            if (isLocalhost(url)) handler.proceed() else handler.cancel()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return false
        }

        private fun isLocalhost(url: String): Boolean {
            val u = url.lowercase()
            return u.startsWith("https://localhost") ||
                u.startsWith("wss://localhost") ||
                u.startsWith("https://127.0.0.1") ||
                u.startsWith("wss://127.0.0.1") ||
                u.startsWith("https://localhost.qz.io") ||
                u.startsWith("wss://localhost.qz.io")
        }
    }
}
