package com.freedomfighter.swissbikehike

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.maplibre.android.geometry.LatLng
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecState(
    val running: Boolean = false,
    val mode: Mode = Mode.WALK,
    val points: List<LatLng> = emptyList(),
    val distanceM: Double = 0.0,
    val startedAt: Long = 0,
    val lastSaved: String? = null,
)

/** Foreground GPS track recorder. Survives the screen going off thanks to the wakelock; saves a GPX on stop. */
class RecordService : Service(), LocationListener {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_MODE = "mode"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 1
        val state = MutableStateFlow(RecState())

        fun start(ctx: Context, mode: Mode) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecordService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_MODE, mode.name))
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_STOP))
        }
    }

    private val points = ArrayList<TrackPoint>()
    private var distance = 0.0
    private var last: Location? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var mode: Mode

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!state.value.running) begin(Mode.of(intent.getStringExtra(EXTRA_MODE)))
            ACTION_STOP -> finish()
        }
        return START_STICKY
    }

    private fun begin(m: Mode) {
        mode = m
        points.clear(); distance = 0.0; last = null
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val notif = notification("0.00 km")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, notif)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sbh:rec").also { it.acquire(12 * 3600_000L) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        }
        state.value = RecState(true, mode, emptyList(), 0.0, System.currentTimeMillis(), null)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MapActivity::class.java)
            .putExtra(MapActivity.EXTRA_MODE, mode.name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.recording_notif))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onLocationChanged(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 50f) return
        val l = last
        if (l != null) {
            val d = l.distanceTo(loc).toDouble()
            if (d < 2.0) return
            distance += d
        }
        last = loc
        val ele = if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) loc.mslAltitudeMeters
                  else if (loc.hasAltitude()) loc.altitude else Double.NaN
        points.add(TrackPoint(loc.latitude, loc.longitude, ele, loc.time))
        state.value = state.value.let { RecState(true, it.mode, points.map { p -> LatLng(p.lat, p.lon) }, distance, it.startedAt, null) }
        if (points.size % 10 == 0)
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification("${Fmt.km(distance)} km"))
    }

    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun finish() {
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        var saved: String? = null
        if (points.size >= 2) saved = save()
        state.value = RecState(false, state.value.mode, emptyList(), distance, 0, saved)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Documents/Swiss Bikers and Pedestrians Map/<date>_<mode>.gpx through MediaStore, so the file is visible to any file manager. */
    private fun save(): String? {
        val name = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(state.value.startedAt)) +
            "_" + mode.name.lowercase() + ".gpx"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Swiss Bikers and Pedestrians Map")
        }
        val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return null
        contentResolver.openOutputStream(uri)?.use { it.write(Gpx.write(name, points).toByteArray()) } ?: return null
        return name
    }
}
