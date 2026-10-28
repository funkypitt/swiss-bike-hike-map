package com.freedomfighter.swissbikehike

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Saved map areas: MapLibre offline packs of the whole style (vector base, paper, overlays) for a bounding box. */
object OfflineMaps {
    const val MIN_ZOOM = 8.0
    const val MAX_ZOOM = 15.0
    /** The downloader only takes http(s) style URLs: the style is seeded into its database under this one. */
    const val STYLE_URL = "https://swiss-bike-hike.invalid/style.json"
    val COUNTRY: LatLngBounds = LatLngBounds.from(47.81, 10.50, 45.81, 5.95)
    const val COUNTRY_NAME = "Switzerland"
    const val COUNTRY_MAX_ZOOM = 14.0

    fun manager(ctx: Context): OfflineManager = OfflineManager.getInstance(ctx).also {
        it.setOfflineMapboxTileCountLimit(2_000_000)
    }

    suspend fun list(ctx: Context): List<OfflineRegion> = suspendCoroutine { cont ->
        manager(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) { cont.resume(offlineRegions?.toList() ?: emptyList()) }
            override fun onError(error: String) { cont.resume(emptyList()) }
        })
    }

    suspend fun status(r: OfflineRegion): OfflineRegionStatus? = suspendCoroutine { cont ->
        r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) { cont.resume(status) }
            override fun onError(error: String?) { cont.resume(null) }
        })
    }

    fun name(r: OfflineRegion): String = r.metadata?.let { String(it, StandardCharsets.UTF_8) } ?: "area"

    /** Creates a pack for the bounds (nothing downloaded yet) and hands it to the pack job. */
    suspend fun create(ctx: Context, styleFile: File, bounds: LatLngBounds, name: String, maxZoom: Double = MAX_ZOOM): OfflineRegion? {
        val pixelRatio = ctx.resources.displayMetrics.density
        val now = System.currentTimeMillis() / 1000
        manager(ctx).putResourceWithUrl(STYLE_URL, styleFile.readBytes(), now, now + 3650L * 86400, null, false)
        val def = OfflineTilePyramidRegionDefinition(STYLE_URL, bounds, MIN_ZOOM, maxZoom, pixelRatio)
        val region = suspendCoroutine<OfflineRegion?> { cont ->
            manager(ctx).createOfflineRegion(def, name.toByteArray(StandardCharsets.UTF_8), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) { cont.resume(offlineRegion) }
                override fun onError(error: String) { cont.resume(null) }
            })
        }
        if (region != null) PackWorker.enqueue(ctx)
        return region
    }

    /** Packs that still miss tiles (interrupted, or marked stale by a refresh). */
    suspend fun incomplete(ctx: Context): List<OfflineRegion> = list(ctx).filter { status(it)?.isComplete == false }

    /** Called at every launch: whatever was left unfinished goes on, once Wi-Fi is there. */
    suspend fun resumeIfNeeded(ctx: Context) { if (incomplete(ctx).isNotEmpty()) PackWorker.enqueue(ctx) }

    fun start(r: OfflineRegion, onProgress: (Long, Long, Boolean) -> Unit, onError: (String) -> Unit) {
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                onProgress(status.completedResourceCount, status.requiredResourceCount, status.isComplete)
                if (status.isComplete) r.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
            override fun onError(error: OfflineRegionError) { onError(error.message) }
            override fun mapboxTileCountLimitExceeded(limit: Long) { onError("tile limit $limit") }
        })
        r.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    /** Marks every tile of the pack stale so the next download pass fetches fresh ones. */
    suspend fun refresh(r: OfflineRegion): Boolean = suspendCoroutine { cont ->
        r.invalidate(object : OfflineRegion.OfflineRegionInvalidateCallback {
            override fun onInvalidate() { cont.resume(true) }
            override fun onError(error: String) { cont.resume(false) }
        })
    }

    // ---- automatic refresh ----

    const val AUTO_DAYS = 7

    fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Once a week, on an unmetered connection, every saved area is marked stale and downloaded again
     * in the background; the current style is seeded again too, so the packs follow the app. The
     * menu shows how it went. Returns true when a refresh was started.
     */
    suspend fun autoRefresh(ctx: Context, styleFile: File): Boolean {
        val prefs = Prefs(ctx)
        if (System.currentTimeMillis() - prefs.offlineRefreshed < AUTO_DAYS * 86400_000L) return false
        if (!unmetered(ctx)) return false
        val regions = list(ctx)
        prefs.offlineRefreshed = System.currentTimeMillis()
        if (regions.isEmpty()) return false
        if (styleFile.exists()) {
            val now = System.currentTimeMillis() / 1000
            manager(ctx).putResourceWithUrl(STYLE_URL, styleFile.readBytes(), now, now + 3650L * 86400, null, false)
        }
        for (r in regions) refresh(r)
        prefs.offlineRefreshStatus = ctx.getString(R.string.auto_refresh_done, regions.size, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        PackWorker.enqueue(ctx)
        return true
    }

    suspend fun delete(r: OfflineRegion): Boolean = suspendCoroutine { cont ->
        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { cont.resume(true) }
            override fun onError(error: String) { cont.resume(false) }
        })
    }
}
