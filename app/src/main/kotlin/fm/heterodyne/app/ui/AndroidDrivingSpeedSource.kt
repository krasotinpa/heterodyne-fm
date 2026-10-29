package fm.heterodyne.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock

/** Main Handler only. Independent of developer diagnostics, USB and audio workers. */
class AndroidDrivingSpeedSource(
    private val context: Context,
    private val handler: Handler,
    private val changed: () -> Unit,
) : DrivingSpeedSource {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var location: Location? = null
    private var listening = false
    private val listener = object : LocationListener {
        override fun onLocationChanged(value: Location) {
            location = Location(value)
            changed()
        }
        override fun onProviderDisabled(provider: String) {
            location = null
            changed()
        }
        override fun onProviderEnabled(provider: String) = Unit
        @Deprecated("Required on API 30")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override fun start() { read() }

    override fun read(): Float? {
        try {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) != true) {
                location = null
                return null
            }
            if (!listening) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, handler.looper)
                listening = true
            }
            val value = location ?: return null
            val age = SystemClock.elapsedRealtimeNanos() - value.elapsedRealtimeNanos
            return value.speed.takeIf { value.hasSpeed() && age in 0L..10_000_000_000L && it.isFinite() && it >= 0f }
        } catch (_: SecurityException) {
            location = null
            return null
        } catch (_: IllegalArgumentException) {
            location = null
            return null
        }
    }

    override fun stop() {
        if (listening) {
            try { manager?.removeUpdates(listener) } catch (_: SecurityException) { /* Permission revoked. */ }
        }
        listening = false
        location = null
    }
}
