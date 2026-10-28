package com.freedomfighter.swissbikehike

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import btools.router.TurnHints
import com.freedomfighter.swissbikehike.databinding.ActivityMapBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.utils.BitmapUtils
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale
import kotlin.math.roundToInt

/** The one map screen. */
class MapActivity : AppCompatActivity(), LocationListener {
    companion object {
        const val EXTRA_MODE = "mode"
        private const val OFF_ROUTE_M = 60.0      // farther than this from the line, three fixes in a row = reroute
        private const val ARRIVED_M = 25.0
        private const val NAV_ZOOM = 17.0
    }

    private lateinit var b: ActivityMapBinding
    private lateinit var prefs: Prefs
    private lateinit var mode: Mode
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var pin: LatLng? = null
    private var pinName = ""
    private var pinKind = Favourites.COORDS
    private var pinDetail = ""
    private var route: Router.Result? = null
    private var routeCum: DoubleArray? = null
    private var lastLocation: Location? = null
    private var prevFix: Location? = null
    private var follow = 0 // 0 free, 1 centred north up, 2 centred heading up
    private var routing = false
    private var nav = false
    private var arrived = false
    private var offRouteFixes = 0
    private var buzzedTurn = -1

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) { startLocation(); enableLocationLayer() }
    }
    private val searchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.let { d ->
            val p = LatLng(d.getDoubleExtra(SearchActivity.EXTRA_LAT, 0.0), d.getDoubleExtra(SearchActivity.EXTRA_LON, 0.0))
            if (route != null) clearRoute()
            setPin(p, d.getStringExtra(SearchActivity.EXTRA_LABEL) ?: Favourites.coordsName(p.latitude, p.longitude),
                d.getStringExtra(SearchActivity.EXTRA_KIND) ?: Favourites.PLACE, d.getStringExtra(SearchActivity.EXTRA_DETAIL) ?: "")
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(p, 15.0))
        }
    }
    private val menuLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.data?.let { showGpx(it) }
        if (r.resultCode == MenuActivity.RESULT_RESTYLE) loadStyle()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        mode = Mode.of(intent.getStringExtra(EXTRA_MODE) ?: prefs.mode.name)
        b = ActivityMapBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.pad(b.root)
        b.map.onCreate(savedInstanceState)

        b.modeBadge.text = getString(if (mode == Mode.BIKE) R.string.bike else R.string.walk)
        b.modeBadge.setBackgroundResource(if (mode == Mode.BIKE) R.drawable.badge_bike else R.drawable.badge_walk)
        b.modeBadge.setTextColor(ContextCompat.getColor(this, if (mode == Mode.BIKE) R.color.paper else R.color.ink))
        b.modeBadge.setOnClickListener { finish() }
        b.btnFind.setOnClickListener { searchLauncher.launch(Intent(this, SearchActivity::class.java)) }
        b.btnMenu.setOnClickListener {
            map?.let { prefs.lastBounds = it.projection.visibleRegion.latLngBounds }
            menuLauncher.launch(Intent(this, MenuActivity::class.java))
        }
        b.btnZoomIn.setOnClickListener { zoomBy(1.0) }
        b.btnZoomOut.setOnClickListener { zoomBy(-1.0) }
        b.btnFollow.setOnClickListener { setFollow((follow + 1) % 3) }
        b.btnGo.setOnClickListener { if (route != null) clearRoute() else go() }
        b.btnNav.setOnClickListener { if (nav) stopNav() else startNav() }
        b.btnRec.setOnClickListener { toggleRecording() }
        b.btnSave.setOnClickListener { toggleFavourite() }

        b.map.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = true; isTiltGesturesEnabled = false
                isAttributionEnabled = false; isLogoEnabled = false; isCompassEnabled = true
                setCompassFadeFacingNorth(true)
            }
            val (target, zoom, bearing) = prefs.camera
            m.cameraPosition = CameraPosition.Builder().target(target).zoom(zoom).bearing(bearing).build()
            m.addOnMapLongClickListener { p -> if (route != null) clearRoute(); setPin(p); true }
            loadStyle()
        }

        val wanted = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (!hasLocation()) askPermissions.launch(wanted.toTypedArray())

        lifecycleScope.launch { RecordService.state.collect { onRecState(it) } }
    }

    // ---------- style ----------

    private fun loadStyle() {
        val m = map ?: return
        lifecycleScope.launch {
            val json = withContext(Dispatchers.Default) {
                val built = MapStyle.build(MapStyle.baseStyle(this@MapActivity))
                MapStyle.styleFile(this@MapActivity).writeText(built)
                built
            }
            m.setStyle(Style.Builder().fromJson(json)) { s ->
                style = s
                AppCompatResources.getDrawable(this@MapActivity, R.drawable.pin)?.let { d ->
                    BitmapUtils.getBitmapFromDrawable(d)?.let { s.addImage(MapStyle.IMG_PIN, it) }
                }
                applyVisibility()
                enableLocationLayer()
                pin?.let { drawPin(it) }
                route?.let { drawRoute(it) }
                RecordService.state.value.let { if (it.running) drawTrack(it.points) }
            }
        }
    }

    private fun setVisible(ids: List<String>, on: Boolean) {
        val s = style ?: return
        val v = PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)
        for (id in ids) s.getLayer(id)?.setProperties(v)
    }

    private fun applyVisibility() {
        setVisible(MapStyle.walkLayers, mode == Mode.WALK)
        setVisible(MapStyle.bikeLayers, mode == Mode.BIKE)
    }

    // ---------- location and camera ----------

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocation()) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationLayer() {
        val m = map ?: return; val s = style ?: return
        if (!hasLocation() || !s.isFullyLoaded) return
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) {
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, s).useDefaultLocationEngine(false)
                    .locationComponentOptions(puckOptions(nav)).build())
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() { follow = 0; updateFollowButton() }
                override fun onCameraTrackingChanged(currentMode: Int) {}
            })
            // A tap on the position dot brings the map back onto it.
            lc.addOnLocationClickListener { setFollow(if (follow == 0) (if (nav) 2 else 1) else follow) }
        }
        lc.isLocationComponentEnabled = true
        applyPuck()
        lastLocation?.let { lc.forceLocationUpdate(it) }
    }

    /** The blue dot with its compass tick outside navigation; a blue triangle turned to the course in navigation. */
    private fun puckOptions(arrow: Boolean): LocationComponentOptions {
        val b = LocationComponentOptions.builder(this)
        if (arrow) b.gpsDrawable(R.drawable.nav_arrow).backgroundDrawable(R.drawable.nav_none)
            .backgroundDrawableStale(R.drawable.nav_none).gpsDrawable(R.drawable.nav_arrow).accuracyAlpha(0f)
        return b.build()
    }

    private fun applyPuck() {
        val lc = map?.locationComponent ?: return
        if (!lc.isLocationComponentActivated) return
        lc.applyStyle(puckOptions(nav))
        lc.renderMode = if (nav) RenderMode.GPS else RenderMode.COMPASS
    }

    override fun onLocationChanged(loc: Location) {
        // Some receivers give no course when slow or just started: take it from the last few metres.
        prevFix?.let { p -> if (!loc.hasBearing() && p.distanceTo(loc) >= 3f) loc.bearing = p.bearingTo(loc).let { if (it < 0) it + 360f else it } }
        if (prevFix == null || prevFix!!.distanceTo(loc) >= 3f) prevFix = loc
        lastLocation = loc
        map?.locationComponent?.let { if (it.isLocationComponentActivated) it.forceLocationUpdate(loc) }
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        b.vSpeed.text = if (kmh < 1.0) "0" else String.format(Locale.US, "%.0f", kmh)
        val alt = if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) loc.mslAltitudeMeters
                  else if (loc.hasAltitude()) loc.altitude else Double.NaN
        b.vAlt.text = if (alt.isNaN()) "--" else String.format(Locale.US, "%.0f", alt)
        updateDist()
        if (nav) updateNav()
    }

    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    /**
     * 0 = free map. 1 = centred on the position, north up. 2 = centred, the way ahead up: by the
     * GPS course on a bike, by the compass on foot (a walker's course is too noisy).
     * Tapping the position dot calls this too, so the map recentres on request.
     */
    private fun setFollow(f: Int) {
        val m = map ?: return
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) { Toast.makeText(this, R.string.no_fix, Toast.LENGTH_SHORT).show(); return }
        val loc = lastLocation
        if (loc == null) { Toast.makeText(this, R.string.no_fix, Toast.LENGTH_SHORT).show(); return }
        follow = f
        lc.forceLocationUpdate(loc)
        val zoom = if (nav) maxOf(m.cameraPosition.zoom, NAV_ZOOM) else m.cameraPosition.zoom
        // The component animates to the position itself when tracking starts; an animateCamera of our
        // own would cancel that transition and the tracking with it. Already tracking = already centred.
        when (f) {
            0 -> lc.cameraMode = CameraMode.NONE
            1 -> lc.setCameraMode(CameraMode.TRACKING, 600L, zoom, 0.0, 0.0, null)
            else -> lc.setCameraMode(if (mode == Mode.BIKE) CameraMode.TRACKING_GPS else CameraMode.TRACKING_COMPASS, 600L, zoom, null, 0.0, null)
        }
        updateFollowButton()
    }

    private fun zoomBy(d: Double) {
        val m = map ?: return
        val lc = m.locationComponent
        if (follow > 0 && lc.isLocationComponentActivated) lc.zoomWhileTracking(m.cameraPosition.zoom + d, 300)
        else m.animateCamera(if (d > 0) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut())
    }

    private fun updateFollowButton() {
        b.btnFollow.text = when (follow) { 2 -> "➤"; 1 -> "◉"; else -> "◎" }
        b.btnFollow.setBackgroundResource(if (follow > 0) R.drawable.btn_active else R.drawable.btn)
        b.btnFollow.setTextColor(AppCompatResources.getColorStateList(this, if (follow > 0) R.drawable.btn_active_text else R.drawable.btn_text))
    }

    // ---------- pin, favourites, route ----------

    private fun setPin(p: LatLng, name: String = Favourites.coordsName(p.latitude, p.longitude), kind: String = Favourites.COORDS, detail: String = "") {
        pin = p; pinName = name; pinKind = kind; pinDetail = detail
        drawPin(p)
        b.btnGo.isEnabled = true
        updatePinStrip()
    }

    private fun updatePinStrip() {
        val p = pin
        if (p == null || nav) { b.pinStrip.visibility = View.GONE; return }
        b.pinStrip.visibility = View.VISIBLE
        val saved = Favourites.find(this, p.latitude, p.longitude)
        b.pinLabel.text = listOf(saved?.name ?: pinName, pinDetail).filter { it.isNotBlank() }.joinToString("\n")
        b.btnSave.text = getString(if (saved != null) R.string.saved_star else R.string.save)
        b.btnSave.isActivated = saved != null
    }

    private fun toggleFavourite() {
        val p = pin ?: return
        val saved = Favourites.find(this, p.latitude, p.longitude)
        if (saved == null) {
            Favourites.add(this, Fav(pinName, p.latitude, p.longitude, pinKind, pinDetail))
            Toast.makeText(this, R.string.fav_added, Toast.LENGTH_SHORT).show()
            updatePinStrip()
        } else AlertDialog.Builder(this).setMessage("Remove « ${saved.name} » from the favourites?")
            .setPositiveButton("REMOVE") { _, _ -> Favourites.remove(this, saved); Toast.makeText(this, R.string.fav_removed, Toast.LENGTH_SHORT).show(); updatePinStrip() }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun drawPin(p: LatLng) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_PIN)
            ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)))
    }

    private fun go() {
        val to = pin ?: return
        if (!Segments.isReady(this)) { askForData(); return }
        val from = lastLocation?.let { LatLng(it.latitude, it.longitude) } ?: run {
            Toast.makeText(this, "No GPS fix: route from the map centre", Toast.LENGTH_SHORT).show()
            map?.cameraPosition?.target
        } ?: return
        computeRoute(from, to, fit = true)
    }

    private fun computeRoute(from: LatLng, to: LatLng, fit: Boolean) {
        if (routing) return
        routing = true
        status(getString(if (nav) R.string.off_route else R.string.routing))
        lifecycleScope.launch {
            try {
                val r = Router.route(this@MapActivity, mode, from, to)
                route = r
                routeCum = cumulative(r.points)
                drawRoute(r)
                b.routeStrip.text = String.format(Locale.US, "%s km   ↑%d m   ↓%d m   %s",
                    Fmt.km(r.distanceM.toDouble()), r.ascendM, r.descendM, Fmt.hm(r.seconds))
                b.routeStrip.visibility = if (nav) View.GONE else View.VISIBLE
                b.btnGo.text = getString(R.string.clear)
                b.btnNav.isEnabled = true
                b.lDist.text = getString(R.string.togo)
                offRouteFixes = 0; buzzedTurn = -1; arrived = false
                updateDist()
                if (nav) updateNav()
                if (fit) {
                    val bb = LatLngBounds.Builder().also { bld -> r.points.forEach { bld.include(it) } }.build()
                    map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bb, 80, 360, 80, 120))
                }
            } catch (e: Exception) {
                Toast.makeText(this@MapActivity, getString(R.string.route_fail) + ": " + e.message, Toast.LENGTH_LONG).show()
            }
            routing = false
            status(null)
        }
    }

    private fun clearRoute() {
        if (nav) stopNav()
        route = null; routeCum = null; pin = null
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_PIN)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        b.routeStrip.visibility = View.GONE
        b.btnGo.text = getString(R.string.go)
        b.btnGo.isEnabled = false
        b.btnNav.isEnabled = false
        b.lDist.text = getString(R.string.dist)
        updatePinStrip()
        updateDist()
    }

    private fun drawRoute(r: Router.Result) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_ROUTE)
            ?.setGeoJson(LineString.fromLngLats(r.points.map { Point.fromLngLat(it.longitude, it.latitude) }))
    }

    private fun cumulative(pts: List<LatLng>): DoubleArray {
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + pts[i - 1].distanceTo(pts[i])
        return cum
    }

    /** Where we are along the route: nearest point index, distance to it, and metres travelled along the line. */
    private class Along(val index: Int, val offM: Double, val doneM: Double)

    private fun along(here: LatLng): Along? {
        val r = route ?: return null; val cum = routeCum ?: return null
        var best = 0; var bestD = Double.MAX_VALUE
        for (i in r.points.indices) { val d = here.distanceTo(r.points[i]); if (d < bestD) { bestD = d; best = i } }
        // refine between the nearest point and its neighbours, so the distance to the next turn does not jump
        var done = cum[best]
        val k = Math.cos(Math.toRadians(here.latitude)) // metres per degree of longitude, relative to latitude
        for (j in listOf(best - 1, best + 1)) {
            if (j !in r.points.indices) continue
            val a = r.points[minOf(best, j)]; val c = r.points[maxOf(best, j)]
            val len = a.distanceTo(c); if (len < 1.0) continue
            val vx = (c.longitude - a.longitude) * k; val vy = c.latitude - a.latitude
            val wx = (here.longitude - a.longitude) * k; val wy = here.latitude - a.latitude
            val t = ((wx * vx + wy * vy) / (vx * vx + vy * vy)).coerceIn(0.0, 1.0)
            val proj = LatLng(a.latitude + t * vy, a.longitude + t * vx / k)
            val d = here.distanceTo(proj)
            if (d < bestD) { bestD = d; done = cum[minOf(best, j)] + t * len }
        }
        return Along(best, bestD, done)
    }

    /** Distance still to go along the route. */
    private fun remaining(): Double {
        val r = route ?: return -1.0; val cum = routeCum ?: return -1.0
        val loc = lastLocation ?: return r.distanceM.toDouble()
        val a = along(LatLng(loc.latitude, loc.longitude)) ?: return r.distanceM.toDouble()
        return cum[cum.size - 1] - a.doneM
    }

    private fun updateDist() {
        val rec = RecordService.state.value
        b.vDist.text = when {
            route != null -> Fmt.km(remaining())
            rec.running -> Fmt.km(rec.distanceM)
            else -> "--"
        }
    }

    // ---------- navigation ----------

    private fun startNav() {
        if (route == null) return
        nav = true; arrived = false; offRouteFixes = 0; buzzedTurn = -1
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b.navPanel.visibility = View.VISIBLE
        b.routeStrip.visibility = View.GONE
        b.pinStrip.visibility = View.GONE
        b.btnNav.text = getString(R.string.exit_nav)
        b.btnNav.setBackgroundResource(R.drawable.btn_active)
        b.btnNav.setTextColor(AppCompatResources.getColorStateList(this, R.drawable.btn_active_text))
        // The position sits in the lower part of the screen, so most of the map shows the way ahead.
        map?.setPadding(0, (b.map.height * 0.45).toInt(), 0, 0)
        follow = 0 // so that the tracking transition below runs even if the map was already following
        applyPuck()
        setFollow(2)
        updateNav()
    }

    private fun stopNav() {
        nav = false
        applyPuck()
        map?.setPadding(0, 0, 0, 0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b.navPanel.visibility = View.GONE
        if (route != null) b.routeStrip.visibility = View.VISIBLE
        b.btnNav.text = getString(R.string.nav)
        b.btnNav.setBackgroundResource(R.drawable.btn)
        b.btnNav.setTextColor(AppCompatResources.getColorStateList(this, R.drawable.btn_text))
        updatePinStrip()
    }

    /** One pass per fix: where we are, the next turn, off-route and arrival. */
    private fun updateNav() {
        val r = route ?: return; val cum = routeCum ?: return
        val loc = lastLocation ?: return
        val here = LatLng(loc.latitude, loc.longitude)
        val a = along(here) ?: return
        val left = cum[cum.size - 1] - a.doneM

        if (arrived || (left < ARRIVED_M && a.offM < ARRIVED_M * 2)) {
            if (!arrived) { arrived = true; buzz(longArrayOf(0, 250, 150, 250)) }
            b.navArrow.set(TurnHints.END); b.navDist.text = getString(R.string.arrived)
            b.navThenLabel.visibility = View.GONE; b.navThenArrow.visibility = View.GONE; b.navThen.text = ""
            return
        }

        if (a.offM > OFF_ROUTE_M) {
            offRouteFixes++
            if (offRouteFixes >= 3 && !routing && Segments.isReady(this)) {
                offRouteFixes = 0
                buzz(longArrayOf(0, 120, 80, 120, 80, 120))
                pin?.let { computeRoute(here, it, fit = false) }
            }
        } else offRouteFixes = 0

        val turns = r.turns.filter { it.cmd != TurnHints.C && it.cmd != TurnHints.BL }
        val next = turns.firstOrNull { cum[it.index] - a.doneM > 5.0 }
        val after = next?.let { n -> turns.firstOrNull { it.index > n.index } }
        if (next == null) {
            b.navArrow.set(TurnHints.END); b.navDist.text = navM(left)
            b.navThenLabel.visibility = View.GONE; b.navThenArrow.visibility = View.GONE; b.navThen.text = ""
            return
        }
        val toTurn = cum[next.index] - a.doneM
        b.navArrow.set(next.cmd, next.exit)
        b.navDist.text = navM(toTurn)
        if (after != null) {
            b.navThenLabel.visibility = View.VISIBLE; b.navThenArrow.visibility = View.VISIBLE
            b.navThenArrow.set(after.cmd, after.exit)
            b.navThen.text = navM(cum[after.index] - cum[next.index])
        } else {
            b.navThenLabel.visibility = View.VISIBLE; b.navThenArrow.visibility = View.VISIBLE
            b.navThenArrow.set(TurnHints.END)
            b.navThen.text = navM(cum[cum.size - 1] - cum[next.index])
        }
        val warn = if (mode == Mode.BIKE) 80.0 else 30.0
        if (toTurn < warn && buzzedTurn != next.index) { buzzedTurn = next.index; buzz(longArrayOf(0, 300)) }
    }

    private fun navM(d: Double): String = when {
        d >= 1000 -> String.format(Locale.US, "%.1f km", d / 1000)
        d >= 100 -> "${(d / 10).roundToInt() * 10} m"
        else -> "${d.roundToInt()} m"
    }

    private fun buzz(pattern: LongArray) {
        val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun askForData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.need_data_title)
            .setMessage(R.string.need_data_msg)
            .setPositiveButton(R.string.download) { _, _ -> Segments.download(this); Toast.makeText(this, "Downloading in the background", Toast.LENGTH_LONG).show() }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun status(text: String?) {
        b.status.text = text ?: ""
        b.status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ---------- recording and tracks ----------

    private fun toggleRecording() {
        if (RecordService.state.value.running) RecordService.stop(this)
        else {
            if (!hasLocation()) { askPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)); return }
            RecordService.start(this, mode)
        }
    }

    private fun onRecState(s: RecState) {
        b.btnRec.text = getString(if (s.running) R.string.stop else R.string.rec)
        b.btnRec.setBackgroundResource(if (s.running) R.drawable.btn_active else R.drawable.btn)
        b.btnRec.setTextColor(AppCompatResources.getColorStateList(this, if (s.running) R.drawable.btn_active_text else R.drawable.btn_text))
        if (s.running) drawTrack(s.points)
        s.lastSaved?.let { Toast.makeText(this, getString(R.string.saved, it), Toast.LENGTH_LONG).show() }
        updateDist()
    }

    private fun drawTrack(pts: List<LatLng>) {
        val src = style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_TRACK) ?: return
        if (pts.size < 2) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) }))
    }

    private fun showGpx(uri: Uri) {
        lifecycleScope.launch {
            val pts = withContext(Dispatchers.IO) {
                try { contentResolver.openInputStream(uri)?.use { Gpx.read(it) } ?: emptyList() } catch (e: Exception) { emptyList() }
            }
            if (pts.size < 2) { Toast.makeText(this@MapActivity, "No track in that file", Toast.LENGTH_SHORT).show(); return@launch }
            drawTrack(pts)
            val bb = LatLngBounds.Builder().also { bld -> pts.forEach { bld.include(it) } }.build()
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bb, 80, 360, 80, 120))
        }
    }

    // ---------- lifecycle ----------

    override fun onStart() { super.onStart(); b.map.onStart() }
    override fun onResume() { super.onResume(); b.map.onResume(); startLocation() }
    override fun onPause() {
        super.onPause(); b.map.onPause()
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        map?.let {
            val c = it.cameraPosition
            prefs.camera = Triple(c.target ?: LatLng(46.52, 6.63), c.zoom, c.bearing)
            prefs.lastBounds = it.projection.visibleRegion.latLngBounds
        }
    }
    override fun onStop() { super.onStop(); b.map.onStop() }
    override fun onLowMemory() { super.onLowMemory(); b.map.onLowMemory() }
    override fun onDestroy() { super.onDestroy(); b.map.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); b.map.onSaveInstanceState(outState) }
}
