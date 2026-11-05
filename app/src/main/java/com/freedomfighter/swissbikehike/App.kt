package com.freedomfighter.swissbikehike

import android.app.Application
import org.maplibre.android.MapLibre

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // once, after the update from the downloads of 0.9: nothing is fetched here
        Offline.migrate(this)
    }
}
