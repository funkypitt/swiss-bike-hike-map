package com.freedomfighter.swissbikehike

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Finishes every saved area that is not complete: the whole country, a zoomed area, a pack marked
 * stale by a refresh. Runs as a long-running WorkManager job with a progress notification, so it
 * survives the app being closed and the phone restarting, waits for Wi-Fi, fetches only the tiles
 * still missing, and comes back by itself after a lost connection (exponential backoff).
 * Cancelling pauses: the packs stay as they are and RESUME picks them up.
 */
class PackWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val NAME = "packs"
        const val CHANNEL = "packs"
        const val NOTIF_ID = 7
        private const val STALL_MS = 90_000L

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<PackWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
        }

        fun pause(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(NAME) }

        /** Is the job running or waiting (for Wi-Fi, for its retry delay)? */
        fun state(ctx: Context): WorkInfo.State? =
            runCatching { WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(NAME).get().firstOrNull()?.state }.getOrNull()
    }

    private val prefs = Prefs(applicationContext)

    override suspend fun doWork(): Result {
        setForeground(foreground(applicationContext.getString(R.string.packs_starting), 0, 0))
        val regions = OfflineMaps.list(applicationContext)
        var touched = 0
        try {
            for (r in regions) {
                val st = OfflineMaps.status(r) ?: continue
                if (st.isComplete) continue
                touched++
                val ok = download(r)
                if (!ok) {
                    prefs.packStatus = applicationContext.getString(R.string.packs_waiting)
                    return Result.retry()
                }
            }
        } catch (e: CancellationException) {
            for (r in regions) runCatching { r.setDownloadState(OfflineRegion.STATE_INACTIVE) }
            prefs.packStatus = applicationContext.getString(R.string.packs_paused)
            throw e
        }
        prefs.packStatus = if (touched == 0) "" else applicationContext.getString(R.string.packs_done)
        return Result.success()
    }

    /** Drives one region until complete; false on an error or a stall (then the job retries later). */
    private suspend fun download(r: OfflineRegion): Boolean = withContext(Dispatchers.Main) {
        val name = OfflineMaps.name(r)
        var lastProgress = System.currentTimeMillis()
        var lastDone = -1L
        var failed: String? = null
        var complete = false
        var waiter: ((Unit) -> Unit)? = null
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.completedResourceCount != lastDone) { lastDone = status.completedResourceCount; lastProgress = System.currentTimeMillis() }
                val pct = if (status.requiredResourceCount > 0) (status.completedResourceCount * 100 / status.requiredResourceCount).toInt() else 0
                val text = applicationContext.getString(R.string.packs_progress, name, pct, status.completedResourceCount, status.requiredResourceCount)
                prefs.packStatus = text
                setProgressAsync(androidx.work.workDataOf("text" to text))
                notify(text, pct, 100)
                if (status.isComplete) { complete = true; waiter?.invoke(Unit) }
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
                val got = withTimeoutOrNull(5_000L) { suspendCancellableCoroutine<Unit> { c -> waiter = { if (c.isActive) c.resume(Unit) } } }
                if (complete) break
                if (failed != null && System.currentTimeMillis() - lastProgress > 15_000L) break
                if (System.currentTimeMillis() - lastProgress > STALL_MS) { failed = "stalled"; break }
                if (got == null) delay(200)
            }
        } finally {
            if (!complete) r.setDownloadState(OfflineRegion.STATE_INACTIVE)
            r.setObserver(null)
        }
        if (!complete) prefs.packStatus = applicationContext.getString(R.string.packs_error, name, failed ?: "?")
        complete
    }

    private fun notify(text: String, done: Int, total: Int) {
        runCatching { (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification(text, done, total)) }
    }

    private fun foreground(text: String, done: Int, total: Int): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, notification(text, done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, notification(text, done, total))

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(applicationContext.getString(R.string.packs_starting), 0, 0)

    private fun notification(text: String, done: Int, total: Int): android.app.Notification {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.country_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MenuActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = WorkManager.getInstance(ctx).createCancelPendingIntent(id)
        return NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(ctx.getString(R.string.packs_title)).setContentText(text)
            .setProgress(total, done, total == 0).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).addAction(0, ctx.getString(R.string.pause), pause).build()
    }
}
