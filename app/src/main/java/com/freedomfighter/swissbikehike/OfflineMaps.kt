package com.freedomfighter.swissbikehike

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * What the offline map needs besides its tiles ([MapFiles]): the letters and the symbols of the
 * style, kept by MapLibre in one small pack. The tiles themselves are no longer fetched one by
 * one: the packs of older versions (the whole country tile by tile, or areas of the screen) are
 * dropped once the files are on the phone.
 */
object OfflineMaps {
    // one tile of each set at the scale of the world: the pack is there for the letters and the symbols
    const val MIN_ZOOM = 0.0
    const val MAX_ZOOM = 0.0
    /** The downloader only takes http(s) style URLs: the style is seeded into its database under this one. */
    const val STYLE_URL = "https://swiss-bike-hike.invalid/style.json"
    val COUNTRY: LatLngBounds = LatLngBounds.from(47.81, 10.50, 45.81, 5.95)
    const val COUNTRY_NAME = "Letters and symbols"

    fun manager(ctx: Context): OfflineManager = OfflineManager.getInstance(ctx).also {
        it.setOfflineMapboxTileCountLimit(2_000_000)
    }

    suspend fun list(ctx: Context): List<OfflineRegion> = withContext(Dispatchers.Main) {
        suspendCoroutine { cont ->
            manager(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) { cont.resume(offlineRegions?.toList() ?: emptyList()) }
                override fun onError(error: String) { cont.resume(emptyList()) }
            })
        }
    }

    suspend fun status(r: OfflineRegion): OfflineRegionStatus? = withContext(Dispatchers.Main) {
        suspendCoroutine { cont ->
            r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) { cont.resume(status) }
                override fun onError(error: String?) { cont.resume(null) }
            })
        }
    }

    fun name(r: OfflineRegion): String = r.metadata?.let { String(it, StandardCharsets.UTF_8) } ?: "area"

    suspend fun country(ctx: Context): OfflineRegion? = list(ctx).firstOrNull { name(it) == COUNTRY_NAME }
    /** The tile packs of older versions: the country tile by tile (before 0.20), areas of the screen (before 0.10). */
    suspend fun legacy(ctx: Context): List<OfflineRegion> = list(ctx).filter { name(it) != COUNTRY_NAME }

    /** The style the pack is made from: the app's map without the live-only closures. */
    suspend fun packStyle(ctx: Context): String {
        val s = JSONObject(MapStyle.build(MapStyle.baseStyle(ctx), false))
        val sources = s.getJSONObject("sources")
        val live = sources.keys().asSequence().filter { it.startsWith("closures") }.toList()
        live.forEach { sources.remove(it) }
        val old = s.getJSONArray("layers"); val layers = JSONArray()
        for (i in 0 until old.length()) { val l = old.getJSONObject(i); if (l.optString("source") !in live) layers.put(l) }
        s.put("layers", layers)
        return s.toString()
    }

    /** Seeds the style and returns the country pack, made if missing (nothing downloaded here). */
    suspend fun ensureCountry(ctx: Context, style: String): OfflineRegion? = withContext(Dispatchers.Main) {
        val now = System.currentTimeMillis() / 1000
        manager(ctx).putResourceWithUrl(STYLE_URL, style.toByteArray(), now, now + 3650L * 86400, null, false)
        country(ctx) ?: run {
            val def = OfflineTilePyramidRegionDefinition(STYLE_URL, COUNTRY, MIN_ZOOM, MAX_ZOOM, ctx.resources.displayMetrics.density)
            suspendCoroutine<OfflineRegion?> { cont ->
                manager(ctx).createOfflineRegion(def, COUNTRY_NAME.toByteArray(StandardCharsets.UTF_8), object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) { cont.resume(offlineRegion) }
                    override fun onError(error: String) { cont.resume(null) }
                })
            }
        }
    }

    /**
     * What swisstopo's server says about the current version of the map: the files of its tile
     * sets and their validators. Null when it cannot be reached.
     */
    suspend fun signature(ctx: Context): String? {
        val tiles = MapFiles.of(MapStyle.baseStyle(ctx))
        if (tiles.isEmpty()) return null
        val parts = tiles.map { Offline.validator(it.remote) ?: return null }
        return (tiles.map { it.remote } + parts).joinToString("|")
    }

    /** Marks every tile of the pack stale: the next download pass asks the server about each (304 when unchanged). */
    suspend fun invalidate(r: OfflineRegion): Boolean = withContext(Dispatchers.Main) {
        suspendCoroutine { cont ->
            r.invalidate(object : OfflineRegion.OfflineRegionInvalidateCallback {
                override fun onInvalidate() { cont.resume(true) }
                override fun onError(error: String) { cont.resume(false) }
            })
        }
    }

    /** Any network with internet; `roaming = false` refuses a roaming one. */
    fun connected(ctx: Context, roaming: Boolean = true): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (roaming || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING))
    }

    fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    suspend fun delete(r: OfflineRegion): Boolean = withContext(Dispatchers.Main) {
        suspendCoroutine { cont ->
            r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() { cont.resume(true) }
                override fun onError(error: String) { cont.resume(false) }
            })
        }
    }
}
