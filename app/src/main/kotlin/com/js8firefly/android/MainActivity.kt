package com.js8firefly.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.js8firefly.android.ui.FireflyRoot

class MainActivity : ComponentActivity() {
    private val model get() = FireflyApp.model

    private var pendingPermissionDone: ((Boolean) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val done = pendingPermissionDone
        pendingPermissionDone = null
        done?.invoke(result.isNotEmpty() && result.values.all { it })
    }
    private var startupPending = false
    private val startupLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startupPending = false
        startStation()
    }
    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // Firefly is always dark: light status and navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (DemoAir.handleLaunchIntent(this, intent)) return

        FireflyApp.permissionRequester = { perms, done ->
            if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                done(true)
            } else {
                pendingPermissionDone = done
                permissionLauncher.launch(perms)
            }
        }
        FireflyApp.onStopRequested = { finishAndRemoveTask() }
        model.notificationPermissionRequester = {
            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent { FireflyRoot(model) }

        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && model.settings.notificationsEnabled) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty() || savedInstanceState != null) startStation() else {
            startupPending = true
            startupLauncher.launch(wanted.toTypedArray())
        }

        handleUsbIntent(intent)
    }

    private fun startStation() {
        model.start()
        StationService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (startupPending) return
        if (!model.isStarted) {
            // Stopped from the notification earlier, or the microphone was granted in Settings.
            startStation()
        } else {
            model.onResume()
            StationService.start(this)
        }
    }

    override fun onDestroy() {
        if (FireflyApp.onStopRequested != null) FireflyApp.onStopRequested = null
        FireflyApp.permissionRequester = null
        model.notificationPermissionRequester = null
        super.onDestroy()
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        model.rig.usbDeviceAttached(device)
    }
}
