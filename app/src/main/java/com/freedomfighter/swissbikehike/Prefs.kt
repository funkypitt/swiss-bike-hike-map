package com.freedomfighter.swissbikehike

import android.content.Context
import android.content.SharedPreferences
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("sbh", Context.MODE_PRIVATE)

    var mode: Mode
        get() = Mode.of(p.getString("mode", null))
        set(v) = p.edit().putString("mode", v.name).apply()

    var paper: Boolean
        get() = p.getBoolean("paper", false)
        set(v) = p.edit().putBoolean("paper", v).apply()

    /** Last camera: Lausanne by default. */
    var camera: Triple<LatLng, Double, Double>
        get() = Triple(
            LatLng(p.getFloat("lat", 46.52f).toDouble(), p.getFloat("lon", 6.63f).toDouble()),
            p.getFloat("zoom", 12f).toDouble(), p.getFloat("bearing", 0f).toDouble())
        set(v) = p.edit().putFloat("lat", v.first.latitude.toFloat()).putFloat("lon", v.first.longitude.toFloat())
            .putFloat("zoom", v.second.toFloat()).putFloat("bearing", v.third.toFloat()).apply()

    /** Bounds of the last map view, for "save the area last shown". */
    var lastBounds: LatLngBounds?
        get() = if (!p.contains("b_n")) null else LatLngBounds.from(
            p.getFloat("b_n", 0f).toDouble(), p.getFloat("b_e", 0f).toDouble(),
            p.getFloat("b_s", 0f).toDouble(), p.getFloat("b_w", 0f).toDouble())
        set(v) { v ?: return; p.edit().putFloat("b_n", v.latitudeNorth.toFloat()).putFloat("b_e", v.longitudeEast.toFloat())
            .putFloat("b_s", v.latitudeSouth.toFloat()).putFloat("b_w", v.longitudeWest.toFloat()).apply() }

    fun downloadId(name: String): Long = p.getLong("dl_$name", -1)
    fun setDownloadId(name: String, id: Long) = p.edit().putLong("dl_$name", id).apply()
}
