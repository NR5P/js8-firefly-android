package com.js8firefly.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Turns the device location into a Maidenhead grid, either once on request
 * or continuously while automatic updates are on. Main thread only.
 */
@SuppressLint("MissingPermission")
class GridLocator(context: Context) {
    private val context = context.applicationContext

    var isLocating by mutableStateOf(false)
        private set
    var isTracking by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    /** Called with each new 6-character grid. */
    var onGrid: ((String) -> Unit)? = null

    private companion object {
        const val deniedMessage = "Location access is off. Turn it on in Settings › Apps › JS8 Firefly › Permissions › Location."
        const val failMessage = "Couldn't get a location fix. Try again, or enter your grid manually."
        val permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val hasPlayServices: Boolean by lazy {
        try {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this.context) == ConnectionResult.SUCCESS
        } catch (e: Exception) { false }
    }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this.context) }
    private val locationManager = this.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { got(it) }
        }
    }
    private val managerListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            got(location)
            if (!isTracking) locationManager.removeUpdates(this)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** One fix, then stop (unless tracking is on). */
    fun locateOnce() {
        lastError = null
        isLocating = true
        authorize { requestOnce() }
    }

    fun startTracking() {
        if (isTracking) return
        lastError = null
        isTracking = true
        authorize { startUpdates() }
    }

    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        if (hasPlayServices) fused.removeLocationUpdates(fusedCallback)
        locationManager.removeUpdates(managerListener)
    }

    private fun granted() = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Requests permission if needed, then runs [then]. */
    private fun authorize(then: () -> Unit) {
        if (granted()) return then()
        val req = FireflyApp.permissionRequester
        if (req == null) {
            fail(deniedMessage)
            return
        }
        req(permissions) {
            if (granted()) {
                if (isLocating || isTracking) then()
            } else {
                fail(deniedMessage)
            }
        }
    }

    private fun fail(message: String) {
        lastError = message
        isLocating = false
    }

    private fun requestOnce() {
        if (hasPlayServices) {
            fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc -> if (loc != null) got(loc) else fail(failMessage) }
                .addOnFailureListener { fail(failMessage) }
        } else {
            val provider = bestProvider() ?: return fail(failMessage)
            locationManager.requestLocationUpdates(provider, 0L, 0f, managerListener, Looper.getMainLooper())
        }
    }

    private fun startUpdates() {
        if (hasPlayServices) {
            // A 6-character subsquare is several km, so coarse fixes are plenty.
            val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L)
                .setMinUpdateDistanceMeters(500f)
                .build()
            fused.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper())
                .addOnFailureListener { fail(failMessage) }
        } else {
            val provider = bestProvider() ?: return fail(failMessage)
            locationManager.requestLocationUpdates(provider, 60_000L, 500f, managerListener, Looper.getMainLooper())
        }
    }

    private fun bestProvider(): String? = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { try { locationManager.isProviderEnabled(it) } catch (e: Exception) { false } }

    private fun got(loc: Location) {
        isLocating = false
        lastError = null
        onGrid?.invoke(GridMath.grid(loc.latitude, loc.longitude))
    }
}
