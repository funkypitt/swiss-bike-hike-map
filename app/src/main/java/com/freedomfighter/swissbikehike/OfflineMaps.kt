package com.freedomfighter.swissbikehike

import android.content.Context
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

    /** Creates a pack for the bounds and starts it; progress is reported as completed/required resources. */
    fun download(ctx: Context, styleFile: File, bounds: LatLngBounds, name: String,
                 onProgress: (done: Long, total: Long, finished: Boolean) -> Unit, onError: (String) -> Unit) {
        val pixelRatio = ctx.resources.displayMetrics.density
        val def = OfflineTilePyramidRegionDefinition("file://${styleFile.absolutePath}", bounds, MIN_ZOOM, MAX_ZOOM, pixelRatio)
        manager(ctx).createOfflineRegion(def, name.toByteArray(StandardCharsets.UTF_8), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) { start(offlineRegion, onProgress, onError) }
            override fun onError(error: String) { onError(error) }
        })
    }

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

    suspend fun delete(r: OfflineRegion): Boolean = suspendCoroutine { cont ->
        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { cont.resume(true) }
            override fun onError(error: String) { cont.resume(false) }
        })
    }
}
