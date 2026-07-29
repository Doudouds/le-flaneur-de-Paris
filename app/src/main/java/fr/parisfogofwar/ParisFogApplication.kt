package fr.parisfogofwar

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre

@HiltAndroidApp
class ParisFogApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
