package com.auralis.music.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.auralis.music.MainActivity
import com.auralis.music.R
import com.auralis.music.data.connect.AuralisConnect

/** Owns the account's live device connection while its UI task remains in Recents.
 * Separate from the media service: a paused/empty player must still accept handoffs.
 * Never sticky or boot-started: a closed task must not advertise a phantom phone.
 */
class ConnectPresenceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AuralisConnect.shouldRunPresenceService()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.connect_presence_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_auralis)
            .setContentTitle(getString(R.string.connect_presence_title))
            .setContentText(getString(R.string.connect_presence_description))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        AuralisConnect.presenceServiceChanged(true)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the service alive briefly for the bounded registration/lease cleanup.
        AuralisConnect.onTaskRemoved()
    }

    override fun onDestroy() {
        AuralisConnect.presenceServiceChanged(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "auralis_connect_presence"
        const val NOTIFICATION = 1004
    }
}
