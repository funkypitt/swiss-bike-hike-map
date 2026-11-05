package com.freedomfighter.swissbikehike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import java.io.File
import java.text.NumberFormat
import kotlin.coroutines.resume

/**
 * The one download job of Switzerland offline: fetches the parts asked for, smallest first
 * (timetable, places, routing, map), each resumable — files with HTTP ranges, the map with
 * MapLibre's pack, which only fetches the tiles it lacks. One notification with the overall
 * progress and PAUSE. Waits for Wi-Fi, survives a closed app and a restart, comes back by itself
 * after a lost connection (WorkManager's backoff). A part the server has nothing newer for is
 * skipped after one small request.
 */
class OfflineWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app = ctx.applicationContext
    private class Stop(val why: String, val pause: Boolean) : Exception(why)

    private val fetched = mutableListOf<Offline.Part>()

    override suspend fun doWork(): Result {
        if (Offline.paused(app) || Offline.wanted(app).isEmpty()) return Result.success()
        try { setForeground(foreground(app.getString(R.string.off_starting), -1)) } catch (e: Exception) { return Result.retry() }
        try {
            while (true) {
                if (Offline.paused(app)) return Result.success()
                val part = Offline.wanted(app).firstOrNull() ?: break
                try {
                    if (get(part)) fetched += part
                    Offline.done(app, part)
                    Offline.setFailure(app, part, "")
                } catch (s: Stop) {
                    Offline.setFailure(app, part, s.why)
                    if (s.pause) {
                        Offline.pause(app)
                        finished(app.getString(R.string.off_stopped, s.why))
                        return Result.failure()
                    }
                    Offline.setProgress(app, part, 0, 0, app.getString(R.string.off_retrying, s.why))
                    return Result.retry()
                }
            }
        } catch (e: CancellationException) {
            if (!Offline.paused(app)) Offline.setProgress(app, Offline.current(app) ?: Offline.Part.MAP, 0, 0, app.getString(R.string.off_interrupted))
            throw e
        }
        val run = Offline.run(app)
        Offline.clearRun(app)
        if (run.isNotEmpty()) finished(
            if (fetched.isEmpty()) app.getString(R.string.off_done_nothing)
            else app.getString(R.string.off_done, fetched.joinToString(", ") { app.getString(it.label) }))
        return Result.success()
    }

    /** Fetches one part; false when the phone's copy was already the newest. */
    private suspend fun get(part: Offline.Part): Boolean = when (part) {
        Offline.Part.TIMETABLE -> pack(Timetable)
        Offline.Part.PLACES -> pack(PlacesPack)
        Offline.Part.ROUTING -> routing()
        Offline.Part.MAP -> map()
    }

    private fun progress(part: Offline.Part, done: Long, total: Long, line: String) {
        Offline.setProgress(app, part, done, total, line)
        notify(line, Offline.overall(app))
    }

    private fun bytesLine(part: Offline.Part, done: Long, total: Long) =
        app.getString(R.string.off_line_bytes, app.getString(part.label), Fmt.mb(done), if (total > 0) Fmt.mb(total) else "?")

    private suspend fun pack(p: DataPack): Boolean {
        val part = p.part
        progress(part, 0, 0, app.getString(R.string.off_checking, app.getString(part.label)))
        val text = p.remoteText() ?: throw Stop(app.getString(R.string.server_unreachable), false)
        val remote = p.parse(text, true) ?: throw Stop(app.getString(R.string.server_unreachable), false)
        if (p.file(app).exists() && !p.part(app).exists() && !p.newer(p.local(app), remote)) { Offline.setChecked(app, part); return false }
        space(p.dir(app), remote.bytes * 5)
        val r = Offline.fetch(p.gzUrl, p.part(app), null, 0, { d, t -> progress(part, d, t, bytesLine(part, d, t)) }, { isStopped })
        when (r) {
            is Offline.Fetch.Done -> {
                progress(part, 1, 1, app.getString(R.string.off_unpacking, app.getString(part.label)))
                if (!withContext(Dispatchers.IO) { p.install(app, text) }) throw Stop(app.getString(R.string.dl_bad_pack), false)
                return true
            }
            is Offline.Fetch.NotModified -> return false
            is Offline.Fetch.Failed -> throw Stop(failText(r.why), !r.retry)
        }
    }

    private suspend fun routing(): Boolean {
        val part = Offline.Part.ROUTING
        var any = false
        val names = Segments.names
        for ((i, n) in names.withIndex()) {
            val f = Segments.file(app, n); val pt = Segments.part(app, n)
            if (!pt.exists()) space(Segments.dir(app), part.estimate / names.size + 50_000_000L)
            val r = Offline.fetch(Segments.url(n), pt, if (f.exists()) Offline.etag(app, n) else null, if (f.exists()) f.lastModified() else 0L,
                { d, t ->
                    val frac = if (t > 0) d.toDouble() / t else 0.0
                    progress(part, ((i + frac) * 1000).toLong(), names.size * 1000L, bytesLine(part, d, t) + "  ($n)")
                }, { isStopped })
            when (r) {
                is Offline.Fetch.NotModified -> {}
                is Offline.Fetch.Done -> {
                    f.delete(); pt.renameTo(f)
                    if (r.modified > 0) f.setLastModified(r.modified)
                    Offline.setEtag(app, n, r.etag); any = true
                }
                is Offline.Fetch.Failed -> throw Stop(failText(r.why), !r.retry)
            }
        }
        if (!any) Offline.setChecked(app, part)
        return any
    }

    private suspend fun map(): Boolean {
        val part = Offline.Part.MAP
        progress(part, 0, 0, app.getString(R.string.off_checking, app.getString(part.label)))
        val sig = OfflineMaps.signature(app) ?: throw Stop(app.getString(R.string.server_unreachable), false)
        val style = runCatching { OfflineMaps.packStyle(app) }.getOrNull() ?: throw Stop(app.getString(R.string.server_unreachable), false)
        val region = OfflineMaps.ensureCountry(app, style) ?: throw Stop(app.getString(R.string.dl_file), false)
        val st = OfflineMaps.status(region)
        val complete = st?.isComplete == true
        val known = Offline.mapSignature(app)
        if (complete && !Offline.mapRefreshing(app)) {
            val changed = known.isNotBlank() && known != sig
            if (!changed && !Offline.fresh(app, part)) {
                Offline.setMapSignature(app, sig); Offline.setMapComplete(app, true); Offline.setChecked(app, part)
                dropLegacy(); return false
            }
            OfflineMaps.invalidate(region)
            Offline.setMapRefreshing(app, true)
        } else if (!complete) {
            val have = st?.completedResourceSize ?: 0L
            space(Segments.dir(app).parentFile ?: app.filesDir, (part.estimate - have).coerceAtLeast(200_000_000L))
        }
        download(region)
        Offline.setMapSignature(app, sig); Offline.setMapRefreshing(app, false); Offline.setMapComplete(app, true)
        dropLegacy()
        return true
    }

    private suspend fun dropLegacy() { for (r in OfflineMaps.legacy(app)) OfflineMaps.delete(r) }

    /** Drives the country pack until complete; a stall or an error = retry later (only missing or stale tiles are fetched then). */
    private suspend fun download(r: OfflineRegion) = withContext(Dispatchers.Main) {
        val part = Offline.Part.MAP
        val nf = NumberFormat.getIntegerInstance()
        var lastProgress = System.currentTimeMillis()
        var lastDone = -1L
        var lastWrite = 0L
        var failed: String? = null
        var complete = false
        var waiter: (() -> Unit)? = null
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                val now = System.currentTimeMillis()
                if (status.completedResourceCount != lastDone) { lastDone = status.completedResourceCount; lastProgress = now }
                if (status.isComplete) { complete = true; waiter?.invoke() }
                if (now - lastWrite > 1000 || status.isComplete) {
                    lastWrite = now
                    progress(part, status.completedResourceCount, status.requiredResourceCount,
                        app.getString(R.string.off_line_tiles, app.getString(part.label), nf.format(status.completedResourceCount), nf.format(status.requiredResourceCount), Fmt.mb(status.completedResourceSize)))
                }
            }
            override fun onError(error: OfflineRegionError) {
                if (error.reason == OfflineRegionError.REASON_NOT_FOUND) return // a missing tile is skipped by the engine
                failed = error.message
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) { failed = "tile limit $limit" }
        })
        r.setDownloadState(OfflineRegion.STATE_ACTIVE)
        try {
            while (!complete) {
                withTimeoutOrNull(5_000L) { suspendCancellableCoroutine<Unit> { c -> waiter = { if (c.isActive) c.resume(Unit) } } }
                if (complete) break
                if (failed != null && System.currentTimeMillis() - lastProgress > 15_000L) throw Stop(failText(failed!!), false)
                if (System.currentTimeMillis() - lastProgress > STALL_MS) throw Stop(app.getString(R.string.dl_network), false)
                delay(200)
            }
        } finally {
            r.setDownloadState(OfflineRegion.STATE_INACTIVE)
            r.setObserver(null)
        }
    }

    /** Stops for good (paused, with the reason) when the phone has not the room for the part. */
    private fun space(dir: File, need: Long) {
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < need) throw Stop(app.getString(R.string.off_no_space, Fmt.size(need), Fmt.size(free)), true)
    }

    private fun failText(why: String): String = when {
        why.startsWith("server ") -> app.getString(R.string.dl_server, why.removePrefix("server ").toIntOrNull() ?: 0)
        why == "stopped" -> app.getString(R.string.off_interrupted)
        else -> app.getString(R.string.dl_network)
    }

    // ---- notifications ----

    private fun channel(): NotificationManager {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(Offline.CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(Offline.CHANNEL, app.getString(R.string.off_channel), NotificationManager.IMPORTANCE_LOW))
        return nm
    }

    private fun openMenu(): PendingIntent = PendingIntent.getActivity(app, 0, Intent(app, MenuActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun notification(text: String, pct: Int): Notification {
        channel()
        val pause = PendingIntent.getBroadcast(app, 1, Intent(app, OfflineReceiver::class.java).setAction(Offline.ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(app, Offline.CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (pct >= 0) app.getString(R.string.off_title_pct, pct) else app.getString(R.string.off_title))
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, pct.coerceAtLeast(0), pct < 0).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openMenu()).addAction(0, app.getString(R.string.pause), pause).build()
    }

    private var lastNotify = 0L
    private fun notify(text: String, pct: Int) {
        val now = System.currentTimeMillis(); if (now - lastNotify < 1000) return; lastNotify = now
        runCatching { channel().notify(Offline.NOTIF_ID, notification(text, pct)) }
    }

    /** The closing word, kept in the shade: done, nothing new, or why it stopped. */
    private fun finished(text: String) {
        runCatching {
            channel().notify(Offline.NOTIF_DONE, NotificationCompat.Builder(app, Offline.CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(app.getString(R.string.off_title)).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(openMenu()).build())
        }
    }

    private fun foreground(text: String, pct: Int): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(Offline.NOTIF_ID, notification(text, pct), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(Offline.NOTIF_ID, notification(text, pct))

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(app.getString(R.string.off_starting), -1)

    companion object { private const val STALL_MS = 90_000L }
}
