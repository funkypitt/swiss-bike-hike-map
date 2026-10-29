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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val journey: Journey? = null,      // public transport: what is followed instead of a trip
    val guide: Guide? = null,          // public transport: the instruction of the moment
)

/** One public-transport instruction: a big title (WALK to…, BOARD…, GET OFF at…), the details under it. */
class Guide(val title: String, val detail: String, val kind: Int, val leg: Int, val urgent: Boolean)

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
            pending = Pair(trip, mode); pendingJourney = null
            ContextCompat.startForegroundService(ctx, Intent(ctx, NavService::class.java).setAction(ACTION_START))
        }
        @Volatile var pendingJourney: Journey? = null
        fun startTransit(ctx: Context, journey: Journey) {
            pending = null; pendingJourney = journey
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
    // public transport
    private var journey: Journey? = null
    private var guide: TransitGuide? = null
    private var lastStep: TransitGuide.Step? = null
    private var replanning = false
    private var run = 0                      // bumped at every (re)start, so a late replan answer is dropped
    private var ticker: Job? = null
    private var lastFix: Location? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val p = pending; pending = null; val j = pendingJourney; pendingJourney = null
                // a new trip while one runs: switch in place (the old one goes to the history), never stop and restart
                if (state.value.running && (p != null || j != null)) keepTrack()
                if (p != null) begin(p.first, p.second)
                else if (j != null) beginTransit(j)
                else if (!state.value.running) stopSelf()
            }
            ACTION_STOP -> finish()
            // restarted by the system with nothing to do: static state is gone, so is the point
            null -> if (!state.value.running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private var foregroundUp = false
    private var listening = false

    private fun beginTransit(j: Journey) {
        journey = j; guide = TransitGuide(j); lastStep = null; run++
        mode = Mode.TRANSIT; version++
        startedAt = System.currentTimeMillis(); track.clear(); prevFix = null; arrived = false; replanning = false
        foreground(j.destination.name.ifBlank { getString(R.string.nav) })
        state.value = NavState(true, mode, null, version, journey = j)
        listen(1000L)
        // the instructions depend on the clock as much as on the position
        ticker?.cancel()
        ticker = scope.launch { while (true) { delay(10_000); if (state.value.running && journey != null) stepTransit(freshFix()) } }
        stepTransit(freshFix())
    }

    private fun foreground(title: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.nav_channel), NotificationManager.IMPORTANCE_LOW))
        val notif = notification(getString(R.string.nav_starting), title)
        if (!foregroundUp) {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTIF_ID, notif)
            foregroundUp = true
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sbh:nav").also { it.acquire(12 * 3600_000L) }
        } else nm.notify(NOTIF_ID, notif)
    }

    private var listenInterval = 0L
    /** GPS updates at the given pace; re-requested when the pace changes (slower on board a train). */
    private fun listen(intervalMs: Long) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        if (listening && listenInterval == intervalMs) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val first = !listening
        if (listening) lm.removeUpdates(this)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, this)
        listening = true; listenInterval = intervalMs   // noted before the first fix is fed: the guidance calls listen() again
        if (first) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
    }

    /** The last fix, only if it is recent enough to mean something (a minute). */
    private fun freshFix(): Location? = lastFix?.takeIf { System.currentTimeMillis() - it.time < 60_000L }

    private fun begin(t: Trip, m: Mode) {
        trip = t; mode = m; version++; journey = null; guide = null; ticker?.cancel(); ticker = null
        passed = BooleanArray(t.stops.size); arrived = false; offRouteFixes = 0; buzzedTurn = -1
        startedAt = System.currentTimeMillis(); track.clear(); prevFix = null
        foreground(t.name)
        state.value = NavState(true, mode, trip, version)
        listen(1000L)
    }

    private fun notification(text: String, title: String? = null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MapActivity::class.java)
            .putExtra(MapActivity.EXTRA_MODE, if (::mode.isInitialized) mode.name else Mode.BIKE.name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, NavService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title ?: journey?.destination?.name?.ifBlank { null } ?: (if (::trip.isInitialized) trip.name else getString(R.string.nav)))
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
        lastFix = loc
        if (journey != null) stepTransit(loc) else step(loc)
    }

    // ---- public transport ----

    private fun label(l: Leg): String = when (l.kind) {
        Journey.BUS -> getString(R.string.kind_bus) + " " + l.line
        Journey.TRAM -> if (l.line.startsWith("m", ignoreCase = true)) getString(R.string.kind_metro) + " " + l.line else if (l.line.length <= 3) getString(R.string.kind_tram) + " " + l.line else l.line
        Journey.BOAT -> getString(R.string.kind_boat) + " " + l.line
        Journey.LIFT -> getString(R.string.kind_lift) + " " + l.line
        else -> l.line
    }.trim()

    private fun platform(p: String) = if (p.isBlank()) "" else "  ·  " + getString(R.string.nav_platform, p)

    private fun minutesTo(t: Long): String {
        val m = ((t - System.currentTimeMillis()) / 60_000).toInt()
        return if (m <= 0) getString(R.string.nav_now) else getString(R.string.nav_in_min, m)
    }

    /** What to say about the ride that comes next: its name, where it goes, when, from which platform. */
    private fun nextRideText(from: Int): String {
        val j = journey ?: return ""
        val r = j.legs.drop(from).firstOrNull { it.ride } ?: return ""
        return label(r) + " " + getString(R.string.nav_towards, r.headsign) + "  ·  " + getString(R.string.nav_leaves, Journey.clock(r.dep)) + platform(r.platDep)
    }

    private fun remainingM(from: Int, here: LatLng?): Double {
        val j = journey ?: return -1.0
        var d = 0.0
        for ((i, l) in j.legs.withIndex()) {
            if (i < from) continue
            val pts = l.path
            if (i == from && here != null && pts.isNotEmpty()) d += here.distanceTo(l.to.at)
            else for (k in 1 until pts.size) d += pts[k - 1].distanceTo(pts[k])
        }
        return d
    }

    /** One pass, on a fix or on the clock: the guide says the step, this says it in words and buzzes on changes. */
    private fun stepTransit(loc: Location?) {
        val j = journey ?: return; val g = guide ?: return
        if (replanning) return
        val now = System.currentTimeMillis()
        val here = loc?.let { LatLng(it.latitude, it.longitude) }
        val st = g.step(here, now)
        val prev = lastStep; lastStep = st
        val changed = prev == null || prev::class != st::class || prev.leg != st.leg
        when (st) {
            is TransitGuide.Step.Arrived -> {
                if (!arrived) { arrived = true; buzz(longArrayOf(0, 250, 150, 250)) }
                state.value = NavState(true, mode, null, version, loc, 0.0, 0.0, arrived = true, eta = Journey.clock(j.arr), journey = j,
                    guide = Guide(getString(R.string.arrived), j.destination.name, Journey.WALK, st.leg, false))
                notify(getString(R.string.arrived))
            }
            is TransitGuide.Step.Walk -> {
                listen(1000L)
                val leg = j.legs[st.leg]
                val target = if (st.last) j.destination.name.ifBlank { "◎" } else leg.to.name
                val title = (if (st.change) getString(R.string.nav_change) + "  ·  " else "") + getString(R.string.nav_walk_to, target)
                val detail = (if (st.distM != null) navM(st.distM) + "  ·  " else "") + (if (st.last) getString(R.string.eta_short, Journey.clock(leg.arr)) else nextRideText(st.leg + 1))
                if (changed && st.change) buzz(longArrayOf(0, 250, 150, 250))
                publishTransit(loc, here, st.leg, Guide(title, detail, Journey.WALK, st.leg, false), j)
            }
            is TransitGuide.Step.Board -> {
                listen(1000L)
                val leg = j.legs[st.leg]
                val title = (if (st.change) getString(R.string.nav_change) + "  ·  " else "") + getString(R.string.nav_board, label(leg))
                val detail = getString(R.string.nav_towards, leg.headsign) + "  ·  " + getString(R.string.nav_leaves, Journey.clock(leg.dep)) + " (" + minutesTo(leg.dep) + ")" +
                    platform(leg.platDep) + "\n" + getString(R.string.nav_get_off_at, leg.to.name) + "  ·  " + Journey.clock(leg.arr)
                if (st.soon && (changed || (prev as? TransitGuide.Step.Board)?.soon == false)) buzz(longArrayOf(0, 300))
                publishTransit(loc, here, st.leg, Guide(title, detail, leg.kind, st.leg, st.soon), j)
            }
            is TransitGuide.Step.Ride -> {
                listen(5000L)   // on board, the position matters less than the clock
                val leg = j.legs[st.leg]
                val title = getString(R.string.nav_ride, label(leg)) + " " + getString(R.string.nav_towards, leg.headsign)
                val detail = getString(R.string.nav_get_off_at, leg.to.name) + "  ·  " + Journey.clock(leg.arr) + platform(leg.platArr) + "  ·  " +
                    (if (st.stopsLeft == 1) getString(R.string.nav_stop_left) else getString(R.string.nav_stops_left, st.stopsLeft))
                publishTransit(loc, here, st.leg, Guide(title, detail, leg.kind, st.leg, false), j)
            }
            is TransitGuide.Step.GetOff -> {
                listen(1000L)
                val leg = j.legs[st.leg]; val next = j.legs.getOrNull(st.leg + 1)
                if (changed) buzz(longArrayOf(0, 500, 200, 500))
                val after = if (next == null) getString(R.string.arrived) else if (next.ride) getString(R.string.nav_change) + ": " + nextRideText(st.leg + 1)
                    else nextRideText(st.leg + 1).ifBlank { getString(R.string.nav_walk_to, j.destination.name.ifBlank { "◎" }) }
                publishTransit(loc, here, st.leg, Guide(getString(R.string.nav_get_off, leg.to.name), Journey.clock(leg.arr) + platform(leg.platArr) + "\n" + after, leg.kind, st.leg, true), j)
            }
            is TransitGuide.Step.Missed -> replan(here, now)
        }
    }

    private fun publishTransit(loc: Location?, here: LatLng?, legIdx: Int, g: Guide, j: Journey) {
        state.value = NavState(true, mode, null, version, loc, -1.0, remainingM(legIdx, here), arrived = false, rerouting = replanning,
            eta = Journey.clock(j.arr), journey = j, guide = g)
        notify(g.title + "  ·  " + g.detail.replace("\n", "  ·  "))
    }

    /** The ride left without us: the next journeys from here, the first one taken; without one, the board stays and we try again in a minute. */
    private fun replan(here: LatLng?, now: Long) {
        val j = journey ?: return
        val from = here ?: lastFix?.let { LatLng(it.latitude, it.longitude) } ?: return
        if (replanning) return
        replanning = true
        val myRun = run
        state.value = NavState(true, mode, null, version, lastFix, rerouting = true, eta = Journey.clock(j.arr), journey = j,
            guide = Guide(getString(R.string.nav_missed), "", Journey.WALK, guide?.legIdx ?: 0, true))
        notify(getString(R.string.nav_missed))
        buzz(longArrayOf(0, 120, 80, 120, 80, 120))
        scope.launch {
            val found = runCatching { Planner.plan(this@NavService, from, j.destination.at, now, 1) }.getOrDefault(emptyList())
            if (myRun != run || !state.value.running) return@launch   // stopped or replaced meanwhile
            replanning = false
            val n = found.firstOrNull()
            if (n != null) { journey = n; guide?.restart(n); lastStep = null; version++; stepTransit(freshFix()) }
            else {
                state.value = NavState(true, mode, null, version, lastFix, eta = Journey.clock(j.arr), journey = j,
                    guide = Guide(getString(R.string.nav_replan_failed), nextRideText(guide?.legIdx ?: 0), Journey.WALK, guide?.legIdx ?: 0, true))
                notify(getString(R.string.nav_replan_failed))
                delay(60_000); if (myRun == run && state.value.running) stepTransit(freshFix())
            }
        }
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

    /** The trip so far goes into the history (when switching to another, or stopping). */
    private fun keepTrack(): TripRecord? {
        val dest = journey?.destination?.name ?: if (::trip.isInitialized) trip.name else ""
        return Trips.add(this, startedAt, mode, dest, track.toList())
    }

    private fun finish() {
        if (!state.value.running) { stopSelf(); return }
        run++
        ticker?.cancel(); ticker = null
        if (listening) (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        listening = false
        wakeLock?.let { if (it.isHeld) it.release() }
        val kept = keepTrack()
        journey = null; guide = null; lastStep = null; replanning = false
        state.value = NavState(false, mode, null, 0, kept = kept)
        stopForeground(STOP_FOREGROUND_REMOVE); foregroundUp = false
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
