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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** The one map screen. */
class MapActivity : AppCompatActivity(), LocationListener {
    companion object {
        const val EXTRA_MODE = "mode"
        private const val OFF_ROUTE_M = 60.0      // farther than this from the line, three fixes in a row = reroute
        private const val ARRIVED_M = 25.0
        private const val STOP_M = 30.0
        private const val NAV_ZOOM = 17.0
    }

    private lateinit var b: ActivityMapBinding
    private lateinit var prefs: Prefs
    private lateinit var mode: Mode
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var night = false
    private var lastNightCheck = 0L

    private val stops = ArrayList<Stop>()
    private var trip: Trip? = null
    private var passed = BooleanArray(0)

    private var lastLocation: Location? = null
    private var prevFix: Location? = null
    private var follow = 0 // 0 free, 1 centred north up, 2 centred heading up
    private var routing = false
    private var nav = false
    private var arrived = false
    private var offRouteFixes = 0
    private var buzzedTurn = -1
    private var navStartedAt = 0L
    private val navTrack = ArrayList<TrackPoint>()

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) { startLocation(); enableLocationLayer() }
    }
    private val searchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.let { d ->
            val p = LatLng(d.getDoubleExtra(SearchActivity.EXTRA_LAT, 0.0), d.getDoubleExtra(SearchActivity.EXTRA_LON, 0.0))
            addStop(Stop(p, d.getStringExtra(SearchActivity.EXTRA_LABEL) ?: Favourites.coordsName(p.latitude, p.longitude),
                d.getStringExtra(SearchActivity.EXTRA_KIND) ?: Favourites.PLACE, d.getStringExtra(SearchActivity.EXTRA_DETAIL) ?: ""))
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(p, 15.0))
        }
    }
    private val menuLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        when (r.resultCode) {
            Activity.RESULT_OK -> r.data?.data?.let { followGpx(it) }
            MenuActivity.RESULT_TRIP -> r.data?.getStringExtra(MenuActivity.EXTRA_FILE)?.let { showTrip(File(it)) }
            MenuActivity.RESULT_RESTYLE -> loadStyle()
        }
        if (r.resultCode != MenuActivity.RESULT_RESTYLE) checkNight(force = true)
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
        b.btnGo.setOnClickListener { if (nav) stopNav() else if (trip != null) clearTrip() else go() }
        b.btnNav.setOnClickListener { startNav() }
        b.btnRec.setOnClickListener { toggleRecording() }
        b.btnSave.setOnClickListener { toggleFavourite() }
        b.btnShare.setOnClickListener { stops.lastOrNull()?.let { share(it.name, it.at) } }
        b.btnDrop.setOnClickListener { dropLastStop() }

        b.map.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = true; isTiltGesturesEnabled = false
                isAttributionEnabled = false; isLogoEnabled = false; isCompassEnabled = true
                setCompassFadeFacingNorth(true)
            }
            val (target, zoom, bearing) = prefs.camera
            m.cameraPosition = CameraPosition.Builder().target(target).zoom(zoom).bearing(bearing).build()
            m.addOnMapLongClickListener { p -> if (!nav) addStop(Stop(p, Favourites.coordsName(p.latitude, p.longitude))); true }
            night = wantNight()
            loadStyle()
            handleIntent(intent)
        }

        val wanted = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (!hasLocation()) askPermissions.launch(wanted.toTypedArray())

        lifecycleScope.launch { RecordService.state.collect { onRecState(it) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (map != null) handleIntent(intent)
    }

    /** A place handed over by another app: a geo: link, a shared text with coordinates, or a GPX file. */
    private fun handleIntent(i: Intent?) {
        i ?: return
        if (i.getBooleanExtra("handled", false)) return
        i.putExtra("handled", true)
        val uri = i.data
        when {
            i.action == Intent.ACTION_VIEW && uri != null && uri.scheme == "geo" -> {
                val text = Uri.decode(uri.toString())
                val label = Regex("\\((.*?)\\)").find(text)?.groupValues?.get(1)
                val q = text.substringAfter("q=", "")
                val p = Favourites.parseCoords(q.ifBlank { text.removePrefix("geo:") }) ?: return
                if (p.latitude == 0.0 && p.longitude == 0.0) return
                addStop(Stop(p, label ?: Favourites.coordsName(p.latitude, p.longitude), Favourites.PLACE))
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(p, 15.0))
            }
            i.action == Intent.ACTION_SEND -> {
                val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val (p, span) = Favourites.coordsIn(text) ?: run { Toast.makeText(this, R.string.no_coords_in_text, Toast.LENGTH_LONG).show(); return }
                val name = text.lines().firstOrNull { it.isNotBlank() && Favourites.coordsIn(it) == null }?.trim()
                addStop(Stop(p, name ?: Favourites.coordsName(p.latitude, p.longitude), Favourites.PLACE))
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(p, 15.0))
            }
            i.action == Intent.ACTION_VIEW && uri != null -> followGpx(uri)
        }
    }

    // ---------- style ----------

    private fun wantNight(): Boolean = when (prefs.night) {
        Night.ON -> true
        Night.AUTO -> { val l = lastLocation; Night.isNight(l?.latitude ?: 46.8, l?.longitude ?: 8.2) }
        else -> false
    }

    /** Switches the map between day and night when the setting or the sun says so (checked once a minute). */
    private fun checkNight(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNightCheck < 60_000) return
        lastNightCheck = now
        val want = wantNight()
        if (want != night) { night = want; loadStyle() }
    }

    private fun loadStyle() {
        val m = map ?: return
        lifecycleScope.launch {
            val json = withContext(Dispatchers.Default) {
                val base = MapStyle.baseStyle(this@MapActivity)
                MapStyle.styleFile(this@MapActivity).writeText(MapStyle.build(base, false))
                if (night) MapStyle.build(base, true) else MapStyle.build(base, false)
            }
            m.setStyle(Style.Builder().fromJson(json)) { s ->
                style = s
                AppCompatResources.getDrawable(this@MapActivity, R.drawable.pin)?.let { d ->
                    BitmapUtils.getBitmapFromDrawable(d)?.let { s.addImage(MapStyle.IMG_PIN, it) }
                }
                applyVisibility()
                enableLocationLayer()
                drawStops()
                trip?.let { drawTrip(it) }
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
            // A tap on the position brings the map back onto it.
            lc.addOnLocationClickListener { setFollow(if (follow == 0) (if (nav) 2 else 1) else follow) }
        }
        lc.isLocationComponentEnabled = true
        applyPuck()
        lastLocation?.let { lc.forceLocationUpdate(it) }
    }

    /** The blue dot with its compass tick outside navigation; a blue triangle turned to the course in navigation. */
    private fun puckOptions(arrow: Boolean): LocationComponentOptions {
        val bld = LocationComponentOptions.builder(this)
        if (arrow) bld.gpsDrawable(R.drawable.nav_arrow).backgroundDrawable(R.drawable.nav_none)
            .backgroundDrawableStale(R.drawable.nav_none).accuracyAlpha(0f)
        return bld.build()
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
        if (!nav) b.vAlt.text = if (alt.isNaN()) "--" else String.format(Locale.US, "%.0f", alt)
        updateDist()
        if (nav) {
            val last = navTrack.lastOrNull()
            if (last == null || Location("").apply { latitude = last.lat; longitude = last.lon }.distanceTo(loc) >= 5f)
                navTrack.add(TrackPoint(loc.latitude, loc.longitude, alt, loc.time))
            updateNav()
        }
        checkNight()
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

    // ---------- stops, favourites, sharing ----------

    /** Every point put on the map is a stop; the last one is the destination. A new stop voids the computed trip. */
    private fun addStop(s: Stop) {
        if (trip?.fromGpx == true) clearTrip()
        stops.add(s)
        if (trip != null) { trip = null; clearTripDrawing(); b.lDist.text = getString(R.string.dist); updateDist() }
        drawStops()
        b.btnGo.isEnabled = true
        b.btnGo.text = getString(R.string.go)
        b.btnNav.isEnabled = false
        updatePinStrip()
    }

    private fun dropLastStop() {
        if (stops.isEmpty()) return
        stops.removeAt(stops.size - 1)
        if (trip != null) { trip = null; clearTripDrawing(); b.lDist.text = getString(R.string.dist); updateDist() }
        drawStops()
        b.btnGo.isEnabled = stops.isNotEmpty()
        b.btnGo.text = getString(R.string.go)
        b.btnNav.isEnabled = false
        updatePinStrip()
    }

    private fun drawStops() {
        val feats = stops.mapIndexed { i, s ->
            Feature.fromGeometry(Point.fromLngLat(s.at.longitude, s.at.latitude)).also { f ->
                f.addStringProperty("n", if (stops.size > 1) (i + 1).toString() else "")
            }
        }
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_PIN)?.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    private fun updatePinStrip() {
        val s = stops.lastOrNull()
        if (s == null || nav) { b.pinStrip.visibility = View.GONE; return }
        b.pinStrip.visibility = View.VISIBLE
        val saved = Favourites.find(this, s.at.latitude, s.at.longitude)
        val head = if (stops.size > 1) getString(R.string.stop_n, stops.size) + "  " else ""
        b.pinLabel.text = head + listOf(saved?.name ?: s.name, s.detail).filter { it.isNotBlank() }.joinToString("\n")
        b.btnSave.text = if (saved != null) "★" else "☆"
        b.btnSave.isActivated = saved != null
        b.btnDrop.visibility = if (trip?.fromGpx == true) View.GONE else View.VISIBLE
    }

    private fun toggleFavourite() {
        val s = stops.lastOrNull() ?: return
        val saved = Favourites.find(this, s.at.latitude, s.at.longitude)
        if (saved == null) {
            Favourites.add(this, Fav(s.name, s.at.latitude, s.at.longitude, s.kind, s.detail))
            Toast.makeText(this, R.string.fav_added, Toast.LENGTH_SHORT).show()
            updatePinStrip()
        } else AlertDialog.Builder(this).setMessage(getString(R.string.fav_remove_q, saved.name))
            .setPositiveButton(R.string.remove) { _, _ -> Favourites.remove(this, saved); Toast.makeText(this, R.string.fav_removed, Toast.LENGTH_SHORT).show(); updatePinStrip() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** A place as text any app understands: name, coordinates, a map link, and a geo: link for map apps. */
    private fun share(name: String, p: LatLng) {
        val lat = String.format(Locale.US, "%.5f", p.latitude); val lon = String.format(Locale.US, "%.5f", p.longitude)
        val text = "$name\n$lat, $lon\nhttps://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=16/$lat/$lon\ngeo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, name)
        runCatching { startActivity(Intent.createChooser(send, null)) }
    }

    // ---------- trip: route or GPX ----------

    private fun go() {
        if (stops.isEmpty()) return
        if (!Segments.isReady(this)) { askForData(); return }
        val from = lastLocation?.let { LatLng(it.latitude, it.longitude) } ?: run {
            Toast.makeText(this, R.string.no_fix_centre, Toast.LENGTH_SHORT).show()
            map?.cameraPosition?.target
        } ?: return
        computeRoute(from, stops.toList(), fit = true)
    }

    private fun computeRoute(from: LatLng, via: List<Stop>, fit: Boolean) {
        if (routing || via.isEmpty()) return
        routing = true
        status(getString(if (nav) R.string.off_route else R.string.routing))
        lifecycleScope.launch {
            try {
                val r = Router.route(this@MapActivity, mode, listOf(from) + via.map { it.at })
                val t = Trip.fromRoute(r, via)
                setTrip(t, fit)
            } catch (e: Exception) {
                Toast.makeText(this@MapActivity, getString(R.string.route_fail) + ": " + e.message, Toast.LENGTH_LONG).show()
            }
            routing = false
            status(null)
        }
    }

    private fun setTrip(t: Trip, fit: Boolean) {
        trip = t
        passed = BooleanArray(t.stops.size)
        drawTrip(t)
        b.routeStrip.text = String.format(Locale.US, "%s km  ↑%d m  ↓%d m", Fmt.km(t.distanceM.toDouble()), t.ascendM, t.descendM) +
            (if (t.seconds > 0) "  " + Fmt.hm(t.seconds) else "")
        b.routeStrip.visibility = if (nav) View.GONE else View.VISIBLE
        b.profile.set(t)
        b.profile.visibility = if (t.hasElevation) View.VISIBLE else View.GONE
        b.btnGo.text = getString(R.string.clear)
        b.btnGo.isEnabled = true
        b.btnNav.isEnabled = true
        b.lDist.text = getString(R.string.togo)
        offRouteFixes = 0; buzzedTurn = -1; arrived = false
        updateDist()
        updatePinStrip()
        if (nav) updateNav()
        if (fit) fitTo(t.points)
    }

    private fun fitTo(pts: List<LatLng>) {
        if (pts.size < 2) return
        val bb = LatLngBounds.Builder().also { bld -> pts.forEach { bld.include(it) } }.build()
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bb, 80, 480, 80, 120))
    }

    /** A GPX file becomes the trip itself: followed as it is, with turns read off its shape. */
    private fun followGpx(uri: Uri) {
        lifecycleScope.launch {
            val track = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { Gpx.readTrack(it) } }.getOrNull()
            }
            val name = track?.name?.ifBlank { null } ?: uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".gpx") ?: "GPX"
            val t = track?.let { Trip.fromTrack(it, name) }
            if (t == null) { Toast.makeText(this@MapActivity, R.string.no_track_in_file, Toast.LENGTH_SHORT).show(); return@launch }
            if (nav) stopNav()
            stops.clear(); stops.addAll(t.stops)
            drawStops()
            setTrip(t, fit = true)
        }
    }

    /** A past trip from the history, shown as a dotted track. */
    private fun showTrip(f: File) {
        lifecycleScope.launch {
            val pts = withContext(Dispatchers.IO) { runCatching { f.inputStream().use { Gpx.read(it) } }.getOrDefault(emptyList()) }
            if (pts.size < 2) { Toast.makeText(this@MapActivity, R.string.no_track_in_file, Toast.LENGTH_SHORT).show(); return@launch }
            drawTrack(pts)
            fitTo(pts)
        }
    }

    private fun clearTrip() {
        if (nav) stopNav()
        trip = null; stops.clear()
        clearTripDrawing()
        drawStops()
        b.btnGo.text = getString(R.string.go)
        b.btnGo.isEnabled = false
        b.btnNav.isEnabled = false
        b.lDist.text = getString(R.string.dist)
        updatePinStrip()
        updateDist()
    }

    private fun clearTripDrawing() {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        b.routeStrip.visibility = View.GONE
        b.profile.visibility = View.GONE
        b.profile.set(null)
    }

    private fun drawTrip(t: Trip) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_ROUTE)
            ?.setGeoJson(LineString.fromLngLats(t.points.map { Point.fromLngLat(it.longitude, it.latitude) }))
    }

    /** Where we are along the trip: nearest point index, distance to the line, and metres travelled along it. */
    private class Along(val index: Int, val offM: Double, val doneM: Double)

    private fun along(here: LatLng): Along? {
        val t = trip ?: return null
        val best = t.nearest(here)
        var bestD = here.distanceTo(t.points[best])
        var done = t.cum[best]
        val k = Math.cos(Math.toRadians(here.latitude))
        for (j in listOf(best - 1, best + 1)) {
            if (j !in t.points.indices) continue
            val a = t.points[minOf(best, j)]; val c = t.points[maxOf(best, j)]
            val len = a.distanceTo(c); if (len < 1.0) continue
            val vx = (c.longitude - a.longitude) * k; val vy = c.latitude - a.latitude
            val wx = (here.longitude - a.longitude) * k; val wy = here.latitude - a.latitude
            val tt = ((wx * vx + wy * vy) / (vx * vx + vy * vy)).coerceIn(0.0, 1.0)
            val proj = LatLng(a.latitude + tt * vy, a.longitude + tt * vx / k)
            val d = here.distanceTo(proj)
            if (d < bestD) { bestD = d; done = t.cum[minOf(best, j)] + tt * len }
        }
        return Along(best, bestD, done)
    }

    private fun remaining(): Double {
        val t = trip ?: return -1.0
        val loc = lastLocation ?: return t.distanceM.toDouble()
        val a = along(LatLng(loc.latitude, loc.longitude)) ?: return t.distanceM.toDouble()
        return t.cum[t.cum.size - 1] - a.doneM
    }

    private fun updateDist() {
        val rec = RecordService.state.value
        b.vDist.text = when {
            trip != null -> Fmt.km(remaining())
            rec.running -> Fmt.km(rec.distanceM)
            else -> "--"
        }
    }

    // ---------- navigation ----------

    private fun startNav() {
        val t = trip ?: return
        nav = true; arrived = false; offRouteFixes = 0; buzzedTurn = -1
        passed = BooleanArray(t.stops.size)
        navStartedAt = System.currentTimeMillis(); navTrack.clear()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b.navPanel.visibility = View.VISIBLE
        b.routeStrip.visibility = View.GONE
        b.pinStrip.visibility = View.GONE
        b.btnGo.text = getString(R.string.stop_nav)
        b.btnNav.visibility = View.GONE
        b.lAlt.text = getString(R.string.eta)
        b.vAlt.text = "--:--"
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
        if (trip != null) b.routeStrip.visibility = View.VISIBLE
        b.btnGo.text = getString(if (trip != null) R.string.clear else R.string.go)
        b.btnNav.visibility = View.VISIBLE
        b.lAlt.text = getString(R.string.alt)
        lastLocation?.let { onLocationChanged(it) }
        b.profile.progress(-1.0)
        updatePinStrip()
        // the trip goes into the local history
        trip?.let { t -> Trips.add(this, navStartedAt, mode, t.name, navTrack.toList())?.let {
            Toast.makeText(this, getString(R.string.trip_kept, Fmt.km(it.distanceM.toDouble())), Toast.LENGTH_SHORT).show() } }
        navTrack.clear()
    }

    /** One pass per fix: where we are, the next turn, stops passed, off-route and arrival. */
    private fun updateNav() {
        val t = trip ?: return
        val loc = lastLocation ?: return
        val here = LatLng(loc.latitude, loc.longitude)
        val a = along(here) ?: return
        val left = t.cum[t.cum.size - 1] - a.doneM
        b.profile.progress(a.doneM)
        b.vAlt.text = eta(left, t)

        if (arrived || (left < ARRIVED_M && a.offM < ARRIVED_M * 2)) {
            if (!arrived) { arrived = true; buzz(longArrayOf(0, 250, 150, 250)) }
            b.navArrow.set(TurnHints.END); b.navDist.text = getString(R.string.arrived)
            b.navThenLabel.visibility = View.GONE; b.navThenArrow.visibility = View.GONE; b.navThen.text = ""
            return
        }

        // intermediate stops
        for (s in 0 until t.stops.size - 1) {
            if (!passed[s] && t.cum[t.stopIdx[s]] <= a.doneM + STOP_M && here.distanceTo(t.stops[s].at) < 80) {
                passed[s] = true; buzz(longArrayOf(0, 250))
                Toast.makeText(this, getString(R.string.stop_reached, s + 1, t.stops[s].name), Toast.LENGTH_LONG).show()
            }
        }

        if (a.offM > OFF_ROUTE_M) {
            offRouteFixes++
            if (t.fromGpx) status(getString(R.string.off_track, navM(a.offM)))
            else if (offRouteFixes >= 3 && !routing && Segments.isReady(this)) {
                offRouteFixes = 0
                buzz(longArrayOf(0, 120, 80, 120, 80, 120))
                val rest = t.stops.filterIndexed { i, _ -> i == t.stops.size - 1 || !passed[i] }
                computeRoute(here, rest, fit = false)
            }
        } else { offRouteFixes = 0; if (t.fromGpx) status(null) }

        val turns = t.turns.filter { it.cmd != TurnHints.C && it.cmd != TurnHints.BL }
        val next = turns.firstOrNull { t.cum[it.index] - a.doneM > 5.0 }
        val after = next?.let { n -> turns.firstOrNull { it.index > n.index } }
        if (next == null) {
            b.navArrow.set(TurnHints.END); b.navDist.text = navM(left)
            b.navThenLabel.visibility = View.GONE; b.navThenArrow.visibility = View.GONE; b.navThen.text = ""
            return
        }
        val toTurn = t.cum[next.index] - a.doneM
        b.navArrow.set(next.cmd, next.exit)
        b.navDist.text = navM(toTurn)
        b.navThenLabel.visibility = View.VISIBLE; b.navThenArrow.visibility = View.VISIBLE
        if (after != null) { b.navThenArrow.set(after.cmd, after.exit); b.navThen.text = navM(t.cum[after.index] - t.cum[next.index]) }
        else { b.navThenArrow.set(TurnHints.END); b.navThen.text = navM(t.cum[t.cum.size - 1] - t.cum[next.index]) }
        val warn = if (mode == Mode.BIKE) 80.0 else 30.0
        if (toTurn < warn && buzzedTurn != next.index) { buzzedTurn = next.index; buzz(longArrayOf(0, 300)) }
    }

    /** Time of arrival from the trip's own pace (BRouter's estimate), or a plain pace for a GPX. */
    private fun eta(leftM: Double, t: Trip): String {
        val mps = if (t.seconds > 0 && t.distanceM > 0) t.distanceM.toDouble() / t.seconds else if (mode == Mode.BIKE) 4.2 else 1.2
        val arrival = System.currentTimeMillis() + (leftM / mps * 1000).toLong()
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(arrival))
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
            .setPositiveButton(R.string.download) { _, _ -> Segments.download(this); Toast.makeText(this, R.string.downloading_bg, Toast.LENGTH_LONG).show() }
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

    // ---------- lifecycle ----------

    override fun onStart() { super.onStart(); b.map.onStart() }
    override fun onResume() { super.onResume(); b.map.onResume(); startLocation(); if (map != null) checkNight(force = true) }
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
