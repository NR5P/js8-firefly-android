package com.js8firefly.android

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps receive and transmit running with the screen off
 * or the app in the background (the iPhone app's background audio mode).
 * Shows a persistent notification with a Stop action and holds a partial wake lock.
 */
class StationService : Service() {
    companion object {
        private const val TAG = "JS8Service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.js8firefly.android.action.STOP"

        /** Starts the service; needs the microphone permission (Android 14 rejects it otherwise). */
        fun start(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, StationService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start foreground service", e)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            FireflyApp.instance.stopStation()
            shutdown()
            return START_NOT_STICKY
        }
        var type = 0
        if (Build.VERSION.SDK_INT >= 30) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            // connectedDevice needs a Bluetooth (or similar) permission on Android 14.
            if (Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JS8Firefly:station").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        // Not sticky: a microphone service can't be restarted from the background.
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, StationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, FireflyApp.stationChannel)
            .setSmallIcon(R.drawable.ic_stat_firefly)
            .setContentTitle("JS8 Firefly is on the air")
            .setContentText("Receiving and transmitting in the background")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    /** The app was swiped away from Recents: stop, as the iPhone app does when it is closed. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        FireflyApp.instance.model.stop()
        shutdown()
        super.onTaskRemoved(rootIntent)
    }

    private fun shutdown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
