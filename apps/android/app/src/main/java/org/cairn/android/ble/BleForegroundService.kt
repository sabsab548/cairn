package org.cairn.android.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.cairn.android.MainActivity

/**
 * Foreground service that keeps the BLE connection alive while the app is in
 * the background. The actual GATT logic lives in a driver class; this service
 * is only responsible for the lifecycle and the notification.
 */
class BleForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Meshtastic radio connection alive"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cairn")
            .setContentText("Connected to Meshtastic radio")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(intent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "cairn_ble"
        private const val NOTIFICATION_ID = 1
    }
}
