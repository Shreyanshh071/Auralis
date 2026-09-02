package com.auralis.music.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.auralis.music.MainActivity
import com.auralis.music.data.network.UpdateChecker
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AuralisFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "AuralisFCM"
        const val TOPIC_UPDATES = "app_updates"
        const val TOPIC_ANNOUNCEMENTS = "announcements"

        fun subscribeToUpdateTopics() {
            try {
                FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_UPDATES)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d(TAG, "Subscribed to topic: $TOPIC_UPDATES")
                        } else {
                            Log.w(TAG, "Failed to subscribe to topic: $TOPIC_UPDATES", task.exception)
                        }
                    }
                FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_ANNOUNCEMENTS)
            } catch (e: Exception) {
                Log.e(TAG, "Error subscribing to Firebase topics", e)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM Device Token: $token")
        subscribeToUpdateTopics()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM Message received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        val notification = remoteMessage.notification

        val title = notification?.title ?: data["title"] ?: "Auralis Update Available"
        val body = notification?.body ?: data["body"] ?: "A new version of Auralis is ready to install."
        val navDestination = data["nav_destination"] ?: "updater"

        showSystemNotification(title, body, navDestination)
    }

    private fun showSystemNotification(title: String, message: String, navDestination: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UpdateChecker.UPDATE_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when new Auralis releases and features are available"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("NAV_DESTINATION", navDestination)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notificationBuilder = NotificationCompat.Builder(this, UpdateChecker.UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(9002, notificationBuilder.build())
    }
}
