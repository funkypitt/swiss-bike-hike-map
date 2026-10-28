package com.freedomfighter.swissbikehike

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import btools.router.TurnHints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** What the navigation knows right now; the map screen only draws it. */
class NavState(
    val running: Boolean = false,
    val mode: Mode = Mode.BIKE,
    val trip: Trip? = null,
    val tripVersion: Int = 0,          // changes when the trip is replaced by a reroute
    val location: Location? = null,
    val doneM: Double = -1.0,
    val leftM: Double = -1.0,
    val next: Router.Turn? = null,
    val toTurnM: Double = 0.0,
    val after: Router.Turn? = null,
    val afterM: Double = 0.0,
    val arrived: Boolean = false,
    val offM: Double = 0.0,
    val rerouting: Boolean = false,
    val eta: String = "",
    val kept: TripRecord? = null,      // the trip just put into the history, once, when stopping
)

/**
 * Turn-by-turn guidance as a foreground service, so it goes on with the screen off or another
 * app in front: own GPS updates, next turn, stops passed, off-route rerouting, arrival, the
 * vibrations, a notification with the arrow and the distance, and the trip into the history.
 */
class NavService : Service(), LocationListener {
    companion object {
        const val ACTION_START = "start"; const val ACTION_STOP = "stop"
        private const val CHANNEL = "nav"; private const val NOTIF_ID = 2
        private const val OFF_ROUTE_M = 60.0; private const val ARRIVED_M = 25.0; private const val STOP_M = 30.0
        val state = MutableStateFlow(NavState())
        /** The trip to start with: handed over in memory, a Trip is too big for an Intent. */
        @Volatile var pending: Pair<Trip, Mode>? = null

        fun start(ctx: Context, trip: Trip, mode: Mode) {
            pending = Pair(trip, mode)
            ContextCompat.startForegroundService(ctx, Intent(ctx, NavService::class.java).setAction(ACTION_START))
        }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, NavService::class.java).setAction(ACTION_STOP)) }

        fun navM(d: Double): String = when {
            d >= 1000 -> String.format(Locale.US, "%.1f km", d / 1000)
            d >= 100 -> "${(d / 10).roundToInt() * 10} m"
            else -> "${d.roundToInt()} m"
        }

        fun glyph(cmd: Int): String = when (cmd) {
            TurnHints.TL -> "←"; TurnHints.TSLL -> "↖"; TurnHints.TSHL -> "↙"
            TurnHints.TR -> "→"; TurnHints.TSLR -> "↗"; TurnHints.TSHR -> "↘"
            TurnHints.KL, TurnHints.EL -> "↰"; TurnHints.KR, TurnHints.ER -> "↱"
            TurnHints.TLU, TurnHints.TU -> "↩"; TurnHints.TRU -> "↪"
            TurnHints.RNDB, TurnHints.RNLB -> "↻"; TurnHints.END -> "◎"; else -> "↑"
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var trip: Trip
    private lateinit var mode: Mode
    private var version = 0
    private var passed = BooleanArray(0)
    private var arrived = false
    private var offRouteFixes = 0
    private var buzzedTurn = -1
    private var routing = false
    private var prevFix: Location? = null
    private var startedAt = 0L
    private val track = ArrayList<TrackPoint>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> { val p = pending; pending = null; if (p != null && !state.value.running) begin(p.first, p.second) else if (p == null && !state.value.running) stopSelf() }
            ACTION_STOP -> finish()
        }
        return START_STICKY
    }

    private fun begin(t: Trip, m: Mode) {
        trip = t; mode = m; version = 1
        passed = BooleanArray(t.stops.size); arrived = false; offRouteFixes = 0; buzzedTurn = -1
        startedAt = System.currentTimeMillis(); track.clear(); prevFix = null
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.nav_channel), NotificationManager.IMPORTANCE_LOW))
        val notif = notification(getString(R.string.nav_starting))
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTIF_ID, notif)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sbh:nav").also { it.acquire(12 * 3600_000L) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            state.value = NavState(true, mode, trip, version)
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        } else state.value = NavState(true, mode, trip, version)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MapActivity::class.java)
            .putExtra(MapActivity.EXTRA_MODE, if (::mode.isInitialized) mode.name else Mode.BIKE.name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, NavService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(if (::trip.isInitialized) trip.name else getString(R.string.nav))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop_nav), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onLocationChanged(loc: Location) {
        if (!state.value.running) return
        prevFix?.let { p -> if (!loc.hasBearing() && p.distanceTo(loc) >= 3f) loc.bearing = p.bearingTo(loc).let { if (it < 0) it + 360f else it } }
        if (prevFix == null || prevFix!!.distanceTo(loc) >= 3f) prevFix = loc
        val alt = if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) loc.mslAltitudeMeters else if (loc.hasAltitude()) loc.altitude else Double.NaN
        val last = track.lastOrNull()
        if (last == null || Location("").apply { latitude = last.lat; longitude = last.lon }.distanceTo(loc) >= 5f)
            track.add(TrackPoint(loc.latitude, loc.longitude, alt, loc.time))
        step(loc)
    }

    /** One pass per fix: where we are, the next turn, stops passed, off-route and arrival. */
    private fun step(loc: Location) {
        val t = trip
        val here = LatLng(loc.latitude, loc.longitude)
        val a = t.along(here)
        val left = t.cum[t.cum.size - 1] - a.doneM
        val eta = eta(left, t)

        if (arrived || (left < ARRIVED_M && a.offM < ARRIVED_M * 2)) {
            if (!arrived) { arrived = true; buzz(longArrayOf(0, 250, 150, 250)) }
            publish(loc, a, left, null, 0.0, null, 0.0, eta)
            notify(getString(R.string.arrived))
            return
        }
        for (s in 0 until t.stops.size - 1) {
            if (!passed[s] && t.cum[t.stopIdx[s]] <= a.doneM + STOP_M && here.distanceTo(t.stops[s].at) < 80) {
                passed[s] = true; buzz(longArrayOf(0, 250))
            }
        }
        if (a.offM > OFF_ROUTE_M) {
            offRouteFixes++
            if (!t.fromGpx && offRouteFixes >= 3 && !routing && Segments.isReady(this)) {
                offRouteFixes = 0
                buzz(longArrayOf(0, 120, 80, 120, 80, 120))
                val rest = t.stops.filterIndexed { i, _ -> i == t.stops.size - 1 || !passed[i] }
                reroute(here, rest)
            }
        } else offRouteFixes = 0

        val turns = t.turns.filter { it.cmd != TurnHints.C && it.cmd != TurnHints.BL }
        val next = turns.firstOrNull { t.cum[it.index] - a.doneM > 5.0 }
        val after = next?.let { n -> turns.firstOrNull { it.index > n.index } }
        val toTurn = if (next != null) t.cum[next.index] - a.doneM else left
        val afterM = when { next == null -> 0.0; after != null -> t.cum[after.index] - t.cum[next.index]; else -> t.cum[t.cum.size - 1] - t.cum[next.index] }
        publish(loc, a, left, next, toTurn, after, afterM, eta)
        if (next != null) {
            val warn = if (mode == Mode.BIKE) 80.0 else 30.0
            if (toTurn < warn && buzzedTurn != next.index) { buzzedTurn = next.index; buzz(longArrayOf(0, 300)) }
        }
        val text = (if (next != null) glyph(next.cmd) + " " + navM(toTurn) else "◎ " + navM(left)) +
            (if (next != null && after != null) "  ·  " + getString(R.string.then) + " " + glyph(after.cmd) + " " + navM(afterM) else "") +
            "  ·  " + getString(R.string.eta_short, eta) + (if (a.offM > OFF_ROUTE_M) "  ·  " + getString(R.string.off_track, navM(a.offM)) else "")
        notify(text)
    }

    private fun publish(loc: Location, a: Trip.Along, left: Double, next: Router.Turn?, toTurn: Double, after: Router.Turn?, afterM: Double, eta: String) {
        state.value = NavState(true, mode, trip, version, loc, a.doneM, left, next, toTurn, after, afterM, arrived, a.offM, routing, eta)
    }

    private fun notify(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < 3000 && !arrived) return
        lastNotified = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun reroute(from: LatLng, via: List<Stop>) {
        if (routing || via.isEmpty()) return
        routing = true
        state.value = NavState(true, mode, trip, version, state.value.location, state.value.doneM, state.value.leftM, state.value.next,
            state.value.toTurnM, state.value.after, state.value.afterM, arrived, state.value.offM, true, state.value.eta)
        scope.launch {
            try {
                val r = Router.route(this@NavService, mode, listOf(from) + via.map { it.at })
                trip = Trip.fromRoute(r, via); version++
                passed = BooleanArray(trip.stops.size); buzzedTurn = -1
            } catch (_: Exception) { }
            routing = false
            state.value.location?.let { step(it) }
        }
    }

    /** Time of arrival from the trip's own pace (BRouter's estimate), or a plain pace for a GPX. */
    private fun eta(leftM: Double, t: Trip): String {
        val mps = if (t.seconds > 0 && t.distanceM > 0) t.distanceM.toDouble() / t.seconds else if (mode == Mode.BIKE) 4.2 else 1.2
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (leftM / mps * 1000).toLong()))
    }

    private fun buzz(pattern: LongArray) {
        val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun finish() {
        if (!state.value.running) { stopSelf(); return }
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        val kept = Trips.add(this, startedAt, mode, trip.name, track.toList())
        state.value = NavState(false, mode, null, 0, kept = kept)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
