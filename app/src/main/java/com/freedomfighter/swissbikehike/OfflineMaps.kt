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
 * The offline map: one MapLibre pack of the whole country (zoom 8 to 14), of the map as the app
 * draws it — swisstopo base map and relief, ASTRA cycling and mountain-bike routes. The closures
 * are left out: they change every day and are only worth showing live.
 * Packs saved by older versions (areas of the screen) are dropped once the country is complete.
 */
object OfflineMaps {
    const val MIN_ZOOM = 8.0
    const val MAX_ZOOM = 14.0
    /** The downloader only takes http(s) style URLs: the style is seeded into its database under this one. */
    const val STYLE_URL = "https://swiss-bike-hike.invalid/style.json"
    val COUNTRY: LatLngBounds = LatLngBounds.from(47.81, 10.50, 45.81, 5.95)
    const val COUNTRY_NAME = "Switzerland"

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
    /** Areas saved by versions before 0.10. */
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
     * What the map's servers say about their current version: the swisstopo tilesets' tiles.json
     * validators, one tile of each ASTRA route layer over central Switzerland (any change of the
     * routes there renews it), and the list of sources the app draws. Null when one cannot be reached.
     */
    suspend fun signature(ctx: Context): String? {
        val style = JSONObject(MapStyle.baseStyle(ctx)).getJSONObject("sources")
        val urls = style.keys().asSequence().mapNotNull { style.getJSONObject(it).optString("url").ifBlank { null } }.sorted().toList()
        val probes = urls + listOf(
            "https://wmts.geo.admin.ch/1.0.0/ch.astra.veloland/default/current/3857/9/266/180.png",
            "https://wmts.geo.admin.ch/1.0.0/ch.astra.mountainbikeland/default/current/3857/9/266/180.png")
        val parts = probes.map { Offline.validator(it) ?: return null }
        return (urls + parts).joinToString("|")
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
