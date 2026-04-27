package com.limebooking.printbridge

import android.app.Application
import android.util.Log
import com.limebooking.printbridge.printer.EscPosPrinter
import com.limebooking.printbridge.printer.PrinterRegistry
import com.limebooking.printbridge.qz.QzAllowedCerts
import com.limebooking.printbridge.qz.QzCertManager
import com.limebooking.printbridge.qz.QzMessageRouter
import com.limebooking.printbridge.qz.QzServer
import com.limebooking.printbridge.qz.QzService

/**
 * Singleton wiring for the QZ Tray emulator + print stack.
 *
 * Two transports share the same [QzMessageRouter]:
 *  - [QzServer]: real WSS on localhost:8181 (for external clients)
 *  - QzBridge (constructed per-WebView in MainActivity): primary path,
 *    bypasses TLS entirely via JavascriptInterface
 */
class PrintBridgeApp : Application() {

    companion object {
        private const val TAG = "PrintBridgeApp"
    }

    lateinit var printerRegistry: PrinterRegistry
        private set
    lateinit var allowedCerts: QzAllowedCerts
        private set
    lateinit var printer: EscPosPrinter
        private set

    private var router: QzMessageRouter? = null
    private var server: QzServer? = null
    private var service: QzService? = null

    override fun onCreate() {
        super.onCreate()
        printerRegistry = PrinterRegistry(this)
        allowedCerts = QzAllowedCerts(this)
        printer = EscPosPrinter(this, printerRegistry)
    }

    @Synchronized
    fun startQzServer(approval: QzMessageRouter.CertApprovalHandler) {
        if (router != null) return
        val r = QzMessageRouter(printerRegistry, printer, allowedCerts, approval)
        router = r
        try {
            val ssl = QzCertManager(this).loadOrCreate()
            val s = QzServer(ssl, r)
            val port = s.start()
            server = s
            Log.i(TAG, "QzServer started on port $port (router shared with WebView bridge)")
        } catch (e: Exception) {
            Log.w(TAG, "WSS server failed to start (WebView bridge still works)", e)
        }
    }

    @Synchronized
    fun stopQzServer() {
        try { server?.stop() } catch (e: Exception) { Log.w(TAG, "Error stopping server", e) }
        server = null
        router = null
    }

    /** Used by MainActivity to attach the in-WebView JS bridge to the same router. */
    fun router(): QzMessageRouter? = router

    fun isServerRunning(): Boolean = router != null
    fun serverPort(): Int = server?.port() ?: -1

    fun attachService(s: QzService) { service = s }
    fun detachService(s: QzService) { if (service === s) service = null }

    fun pendingCertApproval(): QzService.CertApprovalRequest? = service?.nextPendingApproval()
}
