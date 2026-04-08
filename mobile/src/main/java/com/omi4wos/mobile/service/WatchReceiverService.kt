package com.omi4wos.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.omi4wos.shared.Constants

/**
 * Persistent foreground service that holds a MessageClient listener so messages
 * from the watch are received even when the app UI is in the background.
 *
 * Samsung's FreecessHandler freezes background processes, preventing
 * WearableListenerService from being started by GMS. Running as a foreground
 * service keeps the process alive and exempt from that freezing.
 */
class WatchReceiverService : Service() {

    companion object {
        private const val TAG = "WatchReceiverService"
        private const val NOTIFICATION_ID = 1003
    }

    private lateinit var messageClient: MessageClient

    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        Log.d(TAG, "Message received: ${event.path} size=${event.data.size}")
        AudioReceiverService.processMessage(applicationContext, event.path, event.data)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        messageClient = Wearable.getMessageClient(this)
        messageClient.addListener(messageListener)
        Log.i(TAG, "Watch message listener registered")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ requires the service type to be passed to startForeground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        return START_STICKY
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, Constants.MOBILE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("omi4wOS")
            .setContentText("Listening for watch audio…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.MOBILE_NOTIFICATION_CHANNEL_ID,
            "Watch Receiver",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        messageClient.removeListener(messageListener)
        Log.i(TAG, "Watch message listener unregistered")
        super.onDestroy()
    }
}
