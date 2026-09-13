package fr.parisfogofwar.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class BackgroundLocationController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasPreciseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) start() else stop()
    }

    fun startIfEnabled() {
        if (isEnabled() && hasLocationPermission()) start()
    }

    private fun start() {
        if (!hasLocationPermission()) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, LocationTrackingService::class.java),
        )
    }

    private fun stop() {
        context.stopService(Intent(context, LocationTrackingService::class.java))
    }

    private companion object {
        const val PREFERENCES_NAME = "location_preferences"
        const val KEY_ENABLED = "background_tracking_enabled"
    }
}
