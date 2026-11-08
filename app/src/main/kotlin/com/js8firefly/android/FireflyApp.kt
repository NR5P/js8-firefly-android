package com.js8firefly.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/** Application: owns the single [AppModel] and the notification channels. */
class FireflyApp : Application() {
    companion object {
        const val stationChannel = "station"
        const val messagesChannel = "messages"

        lateinit var instance: FireflyApp
            private set

        /** The one AppModel for the process (created on the main thread in onCreate). */
        val model: AppModel get() = instance.model

        /**
         * Asks the user for runtime permissions; set by MainActivity while it exists.
         * The callback runs on the main thread with true when all were granted.
         */
        var permissionRequester: ((Array<String>, (Boolean) -> Unit) -> Unit)? = null

        /** Set by MainActivity: closes the UI after the notification's Stop action. */
        var onStopRequested: (() -> Unit)? = null
    }

    lateinit var model: AppModel
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        model = AppModel(this)
        if (BuildConfig.DEBUG) registerDebugCAT()
    }

    /**
     * Debug builds only: send a raw CAT string to the connected radio from a computer, e.g.
     * `adb shell am broadcast -a com.js8firefly.app.DEBUG_CAT --es cmd "TQ;"`.
     * Replies show in logcat under the JS8Rig tag.
     */
    private fun registerDebugCAT() {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                i.getStringExtra("cmd")?.let { model.rig.debugSend(it) }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(this, r,
            android.content.IntentFilter("com.js8firefly.app.DEBUG_CAT"), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(stationChannel, "Station running",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while JS8 Firefly receives and transmits in the background"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(messagesChannel, "Messages",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Directed messages, inbox messages and watched stations"
        })
    }

    /** The notification's Stop action: stop the station and close the UI. */
    fun stopStation() {
        model.stop()
        onStopRequested?.invoke()
    }
}
