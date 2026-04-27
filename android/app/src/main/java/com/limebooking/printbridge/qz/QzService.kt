package com.limebooking.printbridge.qz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.limebooking.printbridge.PrintBridgeApp
import com.limebooking.printbridge.R
import com.limebooking.printbridge.ui.SettingsActivity
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Foreground service that hosts the QzServer for the lifetime of the app.
 *
 * We use a foreground service (not a plain background thread) because:
 *  - The WebView in MainActivity must be able to reach localhost:8181 even
 *    if the user backgrounds the app briefly.
 *  - Bluetooth output requires `connectedDevice` foreground service type
 *    on Android 14+.
 */
class QzService : Service() {

    companion object {
        private const val TAG = "QzService"
        private const val CHANNEL_ID = "qz_server"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, QzService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QzService::class.java))
        }
    }

    /**
     * Cert-approval requests are delivered through this queue. SettingsActivity /
     * MainActivity polls it (via [PrintBridgeApp.pendingCertApproval]) to show
     * the user a dialog. This keeps the service decoupled from any UI.
     */
    data class CertApprovalRequest(
        val pem: String,
        val commonName: String?,
        val response: java.util.concurrent.SynchronousQueue<Boolean> = java.util.concurrent.SynchronousQueue()
    )

    private val pendingApprovals = LinkedBlockingQueue<CertApprovalRequest>()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat()

        val app = applicationContext as PrintBridgeApp
        app.attachService(this)
        app.startQzServer { pem, cn ->
            // Block worker thread until the UI answers
            val req = CertApprovalRequest(pem, cn)
            pendingApprovals.put(req)
            try {
                req.response.poll(60, TimeUnit.SECONDS) ?: false
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); false
            }
        }
    }

    fun nextPendingApproval(): CertApprovalRequest? = pendingApprovals.poll()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        val app = applicationContext as PrintBridgeApp
        app.stopQzServer()
        app.detachService(this)
    }

    private fun startForegroundCompat() {
        val tapIntent = Intent(this, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.server_running))
            .setContentText(getString(R.string.server_running_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "Started foreground service")
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Print bridge",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
    }
}
