package com.freedomfighter.swissbikehike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineRegion

/**
 * Keeps the app alive while a big offline pack (the whole country) downloads, with a notification
 * that shows the progress. MapLibre downloads in the app's own process, so without this the system
 * would stop the download as soon as the app left the screen.
 */
class OfflineService : Service() {
    companion object {
        const val CHANNEL = "offline"
        const val NOTIF_ID = 7
        const val ACTION_CANCEL = "cancel"
        val COUNTRY: LatLngBounds = LatLngBounds.from(47.81, 10.50, 45.81, 5.95)
        const val COUNTRY_NAME = "Switzerland"
        const val COUNTRY_MAX_ZOOM = 14.0
        @Volatile var region: OfflineRegion? = null
        @Volatile var running = false

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, OfflineService::class.java)) }
        fun cancel(ctx: Context) { ctx.startService(Intent(ctx, OfflineService::class.java).setAction(ACTION_CANCEL)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val r = region
            region = null; running = false
            if (r != null) { r.setDownloadState(OfflineRegion.STATE_INACTIVE); scope.launch { OfflineMaps.delete(r); Prefs(this@OfflineService).countryStatus = getString(R.string.country_cancelled) } }
            else Prefs(this).countryStatus = getString(R.string.country_cancelled)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        running = true
        startForegroundCompat(notification(getString(R.string.country_starting)))
        val prefs = Prefs(this)
        prefs.countryStatus = getString(R.string.country_starting)
        val styleFile = MapStyle.styleFile(this)
        OfflineMaps.download(this, styleFile, COUNTRY, COUNTRY_NAME, COUNTRY_MAX_ZOOM,
            onCreated = { r -> region = r },
            onProgress = { done, total, finished ->
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                val text = if (finished) getString(R.string.country_done) else getString(R.string.country_progress, pct, done, total)
                prefs.countryStatus = text
                if (finished) { running = false; region = null; stopForeground(STOP_FOREGROUND_REMOVE); notify(notification(text)); stopSelf() }
                else notify(notification(text))
            },
            onError = { msg -> prefs.countryStatus = getString(R.string.country_error, msg) })
        return START_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIF_ID, n)
    }

    private fun notify(n: Notification) { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n) }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.country_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MenuActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, OfflineService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.country_title)).setContentText(text).setOngoing(running)
            .setContentIntent(open).addAction(0, getString(R.string.cancel), cancel).setOnlyAlertOnce(true).build()
    }
}
