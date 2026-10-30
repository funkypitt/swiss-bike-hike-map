package com.freedomfighter.swissbikehike

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import java.util.Locale

/** The one map screen. */
class MapActivity : AppCompatActivity(), LocationListener {
    companion object {
        const val EXTRA_MODE = "mode"
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
    private var journey: Journey? = null

    private var lastLocation: Location? = null
    private var prevFix: Location? = null
    private var follow = 0 // 0 free, 1 centred north up, 2 centred heading up
    private var routing = false
    private var nav = false
    private var keptShown = 0L
    private var navVersion = 0

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
    /** Which point the planner asked for on the map: "from", "to", or null. */
    private var picking: String? = null
    private val routeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        when (r.resultCode) {
            RouteActivity.RESULT_PICK -> {
                picking = r.data?.getStringExtra(RouteActivity.EXTRA_PICK)
                status(getString(if (picking == "from") R.string.route_pick_from else R.string.route_pick_to))
            }
            RouteActivity.RESULT_SHOW, RouteActivity.RESULT_GO -> RouteActivity.handover?.let { it0 ->
                RouteActivity.handover = null
                loadItinerary(it0)
                if (r.resultCode == RouteActivity.RESULT_GO) startNav()
            }
        }
    }

    private fun openPlanner() {
        picking = null; status(null)
        if (RouteActivity.formMode != mode) RouteActivity.form = null
        RouteActivity.formMode = mode
        val f = RouteActivity.form ?: RouteForm().also { RouteActivity.form = it }
        if (f.to == null) stops.lastOrNull()?.let { f.to = it }
        val i = Intent(this, RouteActivity::class.java).putExtra(RouteActivity.EXTRA_MODE, mode.name)
        lastLocation?.let { i.putExtra(RouteActivity.EXTRA_LAT, it.latitude).putExtra(RouteActivity.EXTRA_LON, it.longitude) }
        routeLauncher.launch(i)
    }

    /** A planned itinerary onto the map: its destination as the stop, its route or journey drawn (a running navigation switches to it on GO). */
    private fun loadItinerary(it: Itinerary) {
        stops.clear(); stops.add(it.to); drawStops()
        it.trip?.let { t -> setTrip(t, fit = true) }
        it.journey?.let { j -> setJourney(j, fit = true) }
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
        picking = savedInstanceState?.getString("picking")
        b = ActivityMapBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.pad(b.root)
        b.map.onCreate(savedInstanceState)

        b.modeBadgeText.text = getString(when (mode) { Mode.BIKE -> R.string.bike; Mode.WALK -> R.string.walk; Mode.TRANSIT -> R.string.transit_short })
        b.modeBadge.setBackgroundResource(when (mode) { Mode.BIKE -> R.drawable.badge_bike; Mode.WALK -> R.drawable.badge_walk; Mode.TRANSIT -> R.drawable.badge_transit })
        b.modeBadgeText.setTextColor(ContextCompat.getColor(this, if (mode == Mode.WALK) R.color.ink else R.color.paper))
        b.modeBadge.setOnClickListener { finish() }
        b.btnFind.setOnClickListener {
            // "around you" in the search: the position when there is one, else the centre of the map
            val i = Intent(this, SearchActivity::class.java)
            val l = lastLocation; val c = map?.cameraPosition?.target
            if (l != null) i.putExtra(SearchActivity.EXTRA_NEAR_LAT, l.latitude).putExtra(SearchActivity.EXTRA_NEAR_LON, l.longitude).putExtra(SearchActivity.EXTRA_NEAR_GPS, true)
            else if (c != null) i.putExtra(SearchActivity.EXTRA_NEAR_LAT, c.latitude).putExtra(SearchActivity.EXTRA_NEAR_LON, c.longitude).putExtra(SearchActivity.EXTRA_NEAR_GPS, false)
            if (c != null) i.putExtra(SearchActivity.EXTRA_CENTRE_LAT, c.latitude).putExtra(SearchActivity.EXTRA_CENTRE_LON, c.longitude)
            searchLauncher.launch(i)
        }
        b.btnPlaces.setOnClickListener { togglePlaces() }
        b.btnMenu.setOnClickListener {
            map?.let { prefs.lastBounds = it.projection.visibleRegion.latLngBounds }
            menuLauncher.launch(Intent(this, MenuActivity::class.java))
        }
        b.btnZoomIn.setOnClickListener { zoomBy(1.0) }
        b.btnZoomOut.setOnClickListener { zoomBy(-1.0) }
        b.btnFollow.setOnClickListener { setFollow((follow + 1) % 3) }
        b.btnPlan.setOnClickListener { openPlanner() }
        b.btnGo.setOnClickListener { if (nav) stopNav() else go() }
        b.btnNav.setOnClickListener { startNav() }
        b.btnRec.setOnClickListener { toggleRecording() }
        b.btnSave.setOnClickListener { toggleFavourite() }
        b.pinLabel.setOnClickListener { stops.lastOrNull()?.let { share(it.name, it.at) } }
        b.btnDrop.setOnClickListener { if (trip?.fromGpx == true) clearTrip() else dropLastStop() }
        b.btnDepartures.setOnClickListener { stops.lastOrNull()?.let { showDepartures(it) } }

        b.map.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = true; isTiltGesturesEnabled = false
                isAttributionEnabled = false; isLogoEnabled = false; isCompassEnabled = true
                setCompassFadeFacingNorth(true)
            }
            val (target, zoom, bearing) = prefs.camera
            m.cameraPosition = CameraPosition.Builder().target(target).zoom(zoom).bearing(bearing).build()
            m.addOnMapLongClickListener { p ->
                if (nav) return@addOnMapLongClickListener true
                val pick = picking
                if (pick != null) {
                    // the planner asked for this point: back to it with the point in place
                    picking = null; status(null)
                    val f = RouteActivity.form ?: RouteForm().also { RouteActivity.form = it }
                    val s = Stop(p, Favourites.coordsName(p.latitude, p.longitude))
                    if (pick == "from") f.from = s else { f.to = s; f.result = null }
                    openPlanner()
                } else addStop(Stop(p, Favourites.coordsName(p.latitude, p.longitude)))
                true
            }
            // a tap on a place dot pins it; on a stop dot of the transit map: its departures, line by line
            m.addOnMapClickListener { p ->
                val pt = m.projection.toScreenLocation(p)
                val r = 28f * resources.displayMetrics.density
                val box = RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r)
                if (!nav && prefs.placesOnMap) m.queryRenderedFeatures(box, "places").firstOrNull()?.let { f ->
                    val g = f.geometry() as? Point ?: return@let
                    addStop(Stop(LatLng(g.latitude(), g.longitude()), f.getStringProperty("n") ?: "", Favourites.POI, f.getStringProperty("d") ?: ""))
                    return@addOnMapClickListener true
                }
                if (mode != Mode.TRANSIT) return@addOnMapClickListener false
                val f = m.queryRenderedFeatures(box, "pt-stops").firstOrNull() ?: return@addOnMapClickListener false
                val g = f.geometry() as? Point ?: return@addOnMapClickListener false
                val id = f.getProperty("station_id")?.asString ?: ""
                showDepartures(Stop(LatLng(g.latitude(), g.longitude()), f.getStringProperty("name") ?: "", Favourites.STOP, id))
                true
            }
            m.addOnCameraIdleListener { refreshPlaces() }
            night = wantNight()
            loadStyle()
            handleIntent(intent)
        }

        val wanted = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (!hasLocation()) askPermissions.launch(wanted.toTypedArray())

        lifecycleScope.launch { RecordService.state.collect { onRecState(it) } }
        lifecycleScope.launch { NavService.state.collect { onNavState(it) } }
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
                journey?.let { drawJourney(it) }
                RecordService.state.value.let { if (it.running) drawTrack(it.points) }
                refreshPlaces()
            }
        }
    }

    // ---------- places of the OpenStreetMap pack ----------

    private var placesShown = false
    private var placesJob: kotlinx.coroutines.Job? = null

    /** The ◆ button: the dots on or off; without the pack on the phone, the offer to fetch it (3 MB, Wi-Fi). */
    private fun togglePlaces() {
        if (!prefs.placesOnMap && !PlacesPack.isReady(this)) {
            val pct = PlacesPack.downloading(this)
            if (pct >= 0) { Toast.makeText(this, getString(R.string.timetable_downloading, pct), Toast.LENGTH_SHORT).show(); return }
            AlertDialog.Builder(this).setTitle(R.string.menu_places).setMessage(R.string.places_offer_msg)
                .setPositiveButton(R.string.download) { _, _ -> PlacesPack.download(this); prefs.placesOnMap = true; updatePlacesButton(); Toast.makeText(this, R.string.downloading_wifi, Toast.LENGTH_LONG).show(); watchPlacesPack() }
                .setNegativeButton(R.string.later, null).show()
            return
        }
        prefs.placesOnMap = !prefs.placesOnMap
        refreshPlaces()
        if (prefs.placesOnMap && (map?.cameraPosition?.zoom ?: 0.0) < 13) Toast.makeText(this, R.string.places_zoom_in, Toast.LENGTH_SHORT).show()
    }

    /** After the download asked for from the map: the dots appear as soon as the pack is unpacked (a few seconds). */
    private fun watchPlacesPack() {
        lifecycleScope.launch {
            repeat(120) {
                kotlinx.coroutines.delay(2000)
                if (PlacesPack.isReady(this@MapActivity)) { refreshPlaces(); return@launch }
                if (PlacesPack.failure(this@MapActivity).isNotBlank()) { Toast.makeText(this@MapActivity, PlacesPack.failure(this@MapActivity), Toast.LENGTH_LONG).show(); return@launch }
            }
        }
    }

    private fun updatePlacesButton() {
        val on = prefs.placesOnMap
        b.btnPlaces.setBackgroundResource(if (on) R.drawable.btn_active else R.drawable.btn)
        b.btnPlaces.setTextColor(AppCompatResources.getColorStateList(this, if (on) R.drawable.btn_active_text else R.drawable.btn_text))
        b.btnPlaces.contentDescription = getString(if (on) R.string.menu_places_map_on else R.string.menu_places_map_off)
    }

    /** Fills the places source with what the pack has in view (zoom 13 and closer), when the dots are asked for. */
    private fun refreshPlaces() {
        updatePlacesButton()
        val m = map ?: return; val s = style ?: return
        val want = prefs.placesOnMap && m.cameraPosition.zoom >= 13
        if (want != placesShown) { placesShown = want; setVisible(MapStyle.placeLayers, want) }
        if (!want) { if (placesJob != null) { placesJob?.cancel(); placesJob = null; s.getSourceAs<GeoJsonSource>(MapStyle.SRC_PLACES)?.setGeoJson(FeatureCollection.fromFeatures(emptyList())) }; return }
        val pack = PlacesPack.open(this) ?: return
        val bb = m.projection.visibleRegion.latLngBounds
        placesJob?.cancel()
        placesJob = lifecycleScope.launch {
            val feats = withContext(Dispatchers.Default) {
                pack.inBox(bb.latitudeSouth, bb.longitudeWest, bb.latitudeNorth, bb.longitudeEast, null, 600).map { p ->
                    Feature.fromGeometry(Point.fromLngLat(p.at.longitude, p.at.latitude)).also { f ->
                        f.addStringProperty("n", p.label(this@MapActivity))
                        f.addStringProperty("c", Places.GROUP_COLOURS[p.cat.group])
                        f.addStringProperty("d", (listOf(getString(p.cat.label)) + Places.describe(this@MapActivity, p)).joinToString(" · "))
                    }
                }
            }
            style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_PLACES)?.setGeoJson(FeatureCollection.fromFeatures(feats))
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
        setVisible(MapStyle.transitLayers, mode == Mode.TRANSIT)
        // walkers like to know where the stops are: the dots and names, nothing else, on the walking map
        setVisible(MapStyle.stopLayers, mode == Mode.TRANSIT || mode == Mode.WALK)
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
        if (journey != null) { journey = null; clearJourneyDrawing(); b.lDist.text = getString(R.string.dist); updateDist() }
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
        if (journey != null) { journey = null; clearJourneyDrawing(); b.lDist.text = getString(R.string.dist); updateDist() }
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
        b.pinLabel.text = head + listOf(saved?.name ?: s.name, if (s.kind == Favourites.STOP) "" else s.detail).filter { it.isNotBlank() }.joinToString("\n")
        b.btnSave.text = if (saved != null) "★" else "☆"
        b.btnSave.isActivated = saved != null
        b.btnDrop.visibility = View.VISIBLE
        b.btnDepartures.visibility = if (s.kind == Favourites.STOP) View.VISIBLE else View.GONE
    }

    /** The next departures at a public-transport stop, as a plain list. */
    private fun showDepartures(s: Stop) {
        status(getString(R.string.planning))
        lifecycleScope.launch {
            val found = runCatching { Planner.departures(this@MapActivity, TStop(s.name, s.at, s.detail)) }
            status(null)
            Planner.takeNotice()?.let { Toast.makeText(this@MapActivity, it, Toast.LENGTH_LONG).show() }
            found.onFailure { e -> if (e is Planner.NoTimetable) askForTimetable() else Toast.makeText(this@MapActivity, e.message ?: "?", Toast.LENGTH_LONG).show() }
            found.onSuccess { list ->
                if (list.isEmpty()) { Toast.makeText(this@MapActivity, R.string.no_departures, Toast.LENGTH_SHORT).show(); return@onSuccess }
                // one row per line and direction, with its next departure
                val next = LinkedHashMap<String, Planner.Departure>()
                for (d in list.sortedBy { it.time }) next.putIfAbsent(d.line + "→" + d.headsign, d)
                val now = System.currentTimeMillis()
                val lines = next.values.map { d ->
                    val m = ((d.time - now) / 60_000).toInt()
                    Journey.clock(d.time) + (if (d.delayMin > 0) " +${d.delayMin}" else "") + " (" + (if (m <= 0) getString(R.string.nav_now) else getString(R.string.nav_in_min, m)) + ")   " +
                        kindLabel(d.kind, d.line) + "  → " + d.headsign + (if (d.platform.isNotBlank()) "   " + getString(R.string.nav_platform, d.platform) else "")
                }
                val live = list.any { it.realtime }
                val dlg = AlertDialog.Builder(this@MapActivity).setTitle(getString(R.string.departures_title, s.name) + "  ·  " + getString(if (live) R.string.realtime else R.string.offline_timetable))
                    .setItems(lines.toTypedArray(), null).setPositiveButton(android.R.string.ok, null)
                if (!nav && stops.none { it.at.distanceTo(s.at) < 20 }) dlg.setNeutralButton(R.string.set_destination) { _, _ -> addStop(s); map?.animateCamera(CameraUpdateFactory.newLatLngZoom(s.at, maxOf(map?.cameraPosition?.zoom ?: 15.0, 15.0))) }
                dlg.show()
            }
        }
    }

    private fun kindLabel(kind: Int, line: String): String = when (kind) {
        Journey.BUS -> getString(R.string.kind_bus) + " " + line
        Journey.TRAM -> if (line.startsWith("m", ignoreCase = true)) getString(R.string.kind_metro) + " " + line else if (line.length <= 3) getString(R.string.kind_tram) + " " + line else line
        Journey.BOAT -> getString(R.string.kind_boat) + " " + line
        Journey.LIFT -> getString(R.string.kind_lift) + " " + line
        else -> line
    }.trim()

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
        if (mode == Mode.TRANSIT) { planTransit(); return }
        if (!Segments.isReady(this)) {
            val pct = Segments.downloading(this)
            if (pct >= 0) { Toast.makeText(this, getString(R.string.seg_still_downloading, pct), Toast.LENGTH_LONG).show(); return }
            val failed = Segments.status(this).firstOrNull { it.failure.isNotBlank() }?.failure
            if (failed != null) Toast.makeText(this, getString(R.string.seg_failed_hint, failed), Toast.LENGTH_LONG).show()
            askForData(); return
        }
        val from = lastLocation?.let { LatLng(it.latitude, it.longitude) } ?: run {
            Toast.makeText(this, R.string.no_fix_centre, Toast.LENGTH_SHORT).show()
            map?.cameraPosition?.target
        } ?: return
        computeRoute(from, stops.toList(), fit = true)
    }

    private fun computeRoute(from: LatLng, via: List<Stop>, fit: Boolean) {
        if (routing || via.isEmpty()) return
        routing = true
        status(getString(R.string.routing))
        lifecycleScope.launch {
            try {
                val r = Router.route(this@MapActivity, mode, listOf(from) + via.map { it.at })
                val t = Trip.fromRoute(r, via)
                setTrip(t, fit)
            } catch (e: Exception) {
                Toast.makeText(this@MapActivity, routeFailText(e), Toast.LENGTH_LONG).show()
            }
            routing = false
            status(null)
        }
    }

    // ---------- public transport ----------

    /** Journeys to the last stop (through the others in turn), offered as a list; the chosen one is drawn. */
    private fun planTransit() {
        if (routing) return
        val from = lastLocation?.let { LatLng(it.latitude, it.longitude) } ?: run {
            Toast.makeText(this, R.string.no_fix_centre, Toast.LENGTH_SHORT).show()
            map?.cameraPosition?.target
        } ?: return
        routing = true
        status(getString(R.string.planning))
        lifecycleScope.launch {
            val result = runCatching {
                val now = System.currentTimeMillis()
                if (stops.size == 1) Planner.plan(this@MapActivity, from, stops[0].at, now, 4)
                else {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MapActivity, R.string.transit_multi_hint, Toast.LENGTH_LONG).show() }
                    // several stops: the first leg has the choice, the following legs take the first journey each
                    val firsts = Planner.plan(this@MapActivity, from, stops[0].at, now, 4)
                    firsts.mapNotNull { f ->
                        val legs = ArrayList(f.legs); var ok = true
                        for (i in 1 until stops.size) {
                            val n = Planner.plan(this@MapActivity, stops[i - 1].at, stops[i].at, legs.last().arr + 60_000, 1).firstOrNull()
                            if (n == null) { ok = false; break }
                            legs.addAll(n.legs)
                        }
                        if (ok) Journey(legs, f.offline) else null
                    }
                }
            }
            routing = false
            status(null)
            result.onFailure { e ->
                if (e is Planner.NoTimetable) askForTimetable()
                else Toast.makeText(this@MapActivity, getString(R.string.no_journey) + ": " + e.message, Toast.LENGTH_LONG).show()
            }
            Planner.takeNotice()?.let { Toast.makeText(this@MapActivity, it, Toast.LENGTH_LONG).show() }
            result.onSuccess { js ->
                if (js.isEmpty()) { Toast.makeText(this@MapActivity, R.string.no_journey, Toast.LENGTH_LONG).show(); return@onSuccess }
                if (js.size == 1) { setJourney(js[0], fit = true); return@onSuccess }
                val lines = js.map { journeyLine(it) }
                AlertDialog.Builder(this@MapActivity).setTitle(getString(R.string.journeys_title, stops.last().name))
                    .setItems(lines.toTypedArray()) { _, i -> setJourney(js[i], fit = true) }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        }
    }

    private fun changesText(j: Journey): String = when (j.changes) { 0 -> getString(R.string.direct); 1 -> getString(R.string.change_1); else -> getString(R.string.changes_n, j.changes) }

    private fun journeyLine(j: Journey): String {
        val rides = j.legs.filter { it.ride }
        val head = Journey.clock(j.dep) + " → " + Journey.clock(j.arr) + "   " + Fmt.hm(j.seconds) + "   " + changesText(j)
        val detail = rides.joinToString("  ·  ") { kindLabel(it.kind, it.line) + (if (it.platDep.isNotBlank()) " (" + getString(R.string.nav_platform, it.platDep) + ")" else "") }
        return if (rides.isEmpty()) head else head + "\n" + detail
    }

    private fun setJourney(j: Journey, fit: Boolean) {
        if (trip != null) { trip = null; clearTripDrawing() }
        journey = j
        // the last walk ends at the pin: give it the pin's name, so the guidance can say it
        j.legs.last().let { l -> if (!l.ride && l.to.name.isBlank()) l.to.name = stops.lastOrNull()?.name ?: "" }
        drawJourney(j)
        val rides = j.legs.filter { it.ride }
        b.routeStrip.text = Journey.clock(j.dep) + " → " + Journey.clock(j.arr) + "   " + Fmt.hm(j.seconds) + "   " + changesText(j) +
            (if (rides.isEmpty()) "" else "\n" + rides.joinToString(" · ") { kindLabel(it.kind, it.line) }) +
            "\n" + getString(if (j.offline) R.string.offline_timetable else R.string.realtime)
        b.routeStrip.visibility = if (nav) View.GONE else View.VISIBLE
        b.profile.visibility = View.GONE
        b.btnGo.text = getString(if (nav) R.string.stop_nav else R.string.go)
        b.btnGo.isEnabled = true
        b.btnNav.isEnabled = true
        b.lDist.text = getString(R.string.togo)
        updateDist()
        updatePinStrip()
        if (fit) fitTo(j.points)
    }

    private fun drawJourney(j: Journey) {
        val lines = j.legs.filter { it.path.size >= 2 }.map { l ->
            Feature.fromGeometry(LineString.fromLngLats(l.path.map { Point.fromLngLat(it.longitude, it.latitude) })).also { f ->
                f.addStringProperty("c", Journey.colour(l.kind)); f.addNumberProperty("w", if (l.ride) 0 else 1)
            }
        }
        val pts = ArrayList<Feature>()
        for (l in j.legs) if (l.ride) {
            pts.add(Feature.fromGeometry(Point.fromLngLat(l.from.at.longitude, l.from.at.latitude)).also { it.addStringProperty("n", l.from.name + " " + Journey.clock(l.dep)) })
            pts.add(Feature.fromGeometry(Point.fromLngLat(l.to.at.longitude, l.to.at.latitude)).also { it.addStringProperty("n", l.to.name + " " + Journey.clock(l.arr)) })
        }
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_JOURNEY)?.setGeoJson(FeatureCollection.fromFeatures(lines))
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_JOURNEY_PTS)?.setGeoJson(FeatureCollection.fromFeatures(pts))
    }

    private fun clearJourneyDrawing() {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_JOURNEY)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_JOURNEY_PTS)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        b.routeStrip.visibility = View.GONE
    }

    private fun askForTimetable() {
        AlertDialog.Builder(this).setTitle(R.string.need_timetable_title).setMessage(R.string.need_timetable_msg)
            .setPositiveButton(R.string.download) { _, _ -> Timetable.download(this); Toast.makeText(this, R.string.downloading_wifi, Toast.LENGTH_LONG).show() }
            .setNegativeButton(R.string.later, null).show()
    }

    /** BRouter's messages in plain words: the usual one is a start or end too far from any way. */
    private fun routeFailText(e: Exception): String {
        val m = e.message ?: ""
        return when {
            m.contains("not found", true) || m.contains("no track", true) || m.contains("no way", true) -> getString(R.string.route_fail) + ": " + getString(R.string.route_fail_far)
            m.contains("memory", true) -> getString(R.string.route_fail) + ": " + getString(R.string.route_fail_memory)
            m.contains("rd5", true) || m.contains("file", true) -> getString(R.string.route_fail) + ": " + getString(R.string.route_fail_data)
            else -> getString(R.string.route_fail) + ": " + m
        }
    }

    private fun setTrip(t: Trip, fit: Boolean) {
        if (journey != null) { journey = null; clearJourneyDrawing() }
        trip = t
        drawTrip(t)
        b.routeStrip.text = String.format(Locale.US, "%s km  ↑%d m  ↓%d m", Fmt.km(t.distanceM.toDouble()), t.ascendM, t.descendM) +
            (if (t.seconds > 0) "  " + Fmt.hm(t.seconds) else "")
        b.routeStrip.visibility = if (nav) View.GONE else View.VISIBLE
        b.profile.set(t)
        b.profile.visibility = if (t.hasElevation) View.VISIBLE else View.GONE
        b.btnGo.text = getString(if (nav) R.string.stop_nav else R.string.go)
        b.btnGo.isEnabled = true
        b.btnNav.isEnabled = true
        b.lDist.text = getString(R.string.togo)
        updateDist()
        updatePinStrip()
        if (fit) fitTo(t.points)
    }

    private fun fitTo(pts: List<LatLng>) {
        if (pts.size < 2) return
        val bb = LatLngBounds.Builder().also { bld -> pts.forEach { bld.include(it) } }.build()
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bb, 80, 560, 200, 120))
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
            if (nav) { stopNav(); exitNavUi() }
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
        if (nav) { stopNav(); exitNavUi() }
        trip = null; stops.clear(); journey = null
        clearTripDrawing(); clearJourneyDrawing()
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

    private fun remaining(): Double {
        journey?.let { j ->
            var d = 0.0
            for (l in j.legs) for (k in 1 until l.path.size) d += l.path[k - 1].distanceTo(l.path[k])
            return d
        }
        val t = trip ?: return -1.0
        val loc = lastLocation ?: return t.distanceM.toDouble()
        return t.cum[t.cum.size - 1] - t.along(LatLng(loc.latitude, loc.longitude)).doneM
    }

    private fun updateDist() {
        val rec = RecordService.state.value
        b.vDist.text = when {
            trip != null || journey != null -> Fmt.km(remaining())
            rec.running -> Fmt.km(rec.distanceM)
            else -> "--"
        }
    }

    // ---------- navigation (done by NavService; this screen shows it) ----------

    private fun startNav() {
        if (!hasLocation()) { askPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)); return }
        journey?.let { NavService.startTransit(this, it); return }
        val t = trip ?: return
        NavService.start(this, t, mode)
    }

    private fun stopNav() { NavService.stop(this) }

    private fun onNavState(st: NavState) {
        if (st.running && !nav) enterNavUi(st)
        if (!st.running && nav) exitNavUi()
        if (!st.running) { st.kept?.let { if (keptShown != it.startedAt) { keptShown = it.startedAt; Toast.makeText(this, getString(R.string.trip_kept, Fmt.km(it.distanceM.toDouble())), Toast.LENGTH_SHORT).show() } }; return }
        // a running navigation may have switched kind (bike trip → transit journey or back): the right panel
        if (nav) {
            val transit = st.journey != null
            b.navPanel.visibility = if (transit) View.GONE else View.VISIBLE
            b.transitPanel.visibility = if (transit) View.VISIBLE else View.GONE
        }
        if (st.journey != null) { onTransitState(st); return }
        // a reroute replaced the trip
        if (st.trip != null && st.tripVersion != navVersion) {
            navVersion = st.tripVersion
            stops.clear(); stops.addAll(st.trip.stops); drawStops()
            setTrip(st.trip, fit = false)
            b.routeStrip.visibility = View.GONE; b.pinStrip.visibility = View.GONE
        }
        status(if (st.rerouting) getString(R.string.off_route) else if (st.trip?.fromGpx == true && st.offM > 60) getString(R.string.off_track, NavService.navM(st.offM)) else null)
        if (st.doneM >= 0) b.profile.progress(st.doneM)
        if (st.eta.isNotEmpty()) b.vAlt.text = st.eta
        if (st.leftM >= 0) b.vDist.text = Fmt.km(st.leftM)
        if (st.arrived) {
            b.navArrow.set(TurnHints.END); b.navDist.text = getString(R.string.arrived)
            b.navThenLabel.visibility = View.GONE; b.navThenArrow.visibility = View.GONE; b.navThen.text = ""
            return
        }
        val next = st.next
        if (next == null) {
            b.navArrow.set(TurnHints.END); b.navDist.text = if (st.leftM >= 0) NavService.navM(st.leftM) else "--"
            b.navThenLabel.visibility = View.GONE; b.navThenArrow.visibility = View.GONE; b.navThen.text = ""
            return
        }
        b.navArrow.set(next.cmd, next.exit)
        b.navDist.text = NavService.navM(st.toTurnM)
        b.navThenLabel.visibility = View.VISIBLE; b.navThenArrow.visibility = View.VISIBLE
        if (st.after != null) b.navThenArrow.set(st.after.cmd, st.after.exit) else b.navThenArrow.set(TurnHints.END)
        b.navThen.text = NavService.navM(st.afterM)
    }

    /** The public-transport guidance: the instruction of the moment in big letters, the journey redrawn when replanned. */
    private fun onTransitState(st: NavState) {
        val j = st.journey ?: return
        if (st.tripVersion != navVersion) {
            navVersion = st.tripVersion
            if (stops.isEmpty()) stops.add(Stop(j.destination.at, j.destination.name.ifBlank { Favourites.coordsName(j.destination.at.latitude, j.destination.at.longitude) }))
            drawStops()
            setJourney(j, fit = false)
            b.routeStrip.visibility = View.GONE; b.pinStrip.visibility = View.GONE
        }
        status(if (st.rerouting) getString(R.string.nav_missed) else null)
        if (st.eta.isNotEmpty()) b.vAlt.text = st.eta
        if (st.leftM >= 0) b.vDist.text = Fmt.km(st.leftM)
        val g = st.guide ?: return
        b.tTitle.text = g.title
        b.tDetail.text = g.detail
        b.tDetail.visibility = if (g.detail.isBlank()) View.GONE else View.VISIBLE
        b.tTitle.setTextColor(ContextCompat.getColor(this, if (g.urgent) R.color.walk_yellow else R.color.paper))
    }

    private fun enterNavUi(st: NavState) {
        nav = true; picking = null
        navVersion = 0
        if (trip == null && st.trip != null) { stops.clear(); stops.addAll(st.trip.stops); drawStops(); setTrip(st.trip, fit = false) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val transit = st.journey != null
        b.navPanel.visibility = if (transit) View.GONE else View.VISIBLE
        b.transitPanel.visibility = if (transit) View.VISIBLE else View.GONE
        b.routeStrip.visibility = View.GONE
        b.pinStrip.visibility = View.GONE
        b.btnGo.text = getString(R.string.stop_nav)
        b.btnGo.isEnabled = true
        b.btnNav.visibility = View.GONE
        b.btnPlan.visibility = View.GONE
        b.lAlt.text = getString(R.string.eta)
        b.vAlt.text = "--:--"
        b.lDist.text = getString(R.string.togo)
        // The position sits in the lower part of the screen, so most of the map shows the way ahead.
        map?.setPadding(0, (b.map.height * 0.45).toInt(), 0, 0)
        follow = 0 // so that the tracking transition below runs even if the map was already following
        applyPuck()
        setFollow(2)
    }

    private fun exitNavUi() {
        nav = false
        applyPuck()
        map?.setPadding(0, 0, 0, 0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b.navPanel.visibility = View.GONE
        b.transitPanel.visibility = View.GONE
        if (trip != null || journey != null) b.routeStrip.visibility = View.VISIBLE
        b.btnGo.text = getString(R.string.go)
        b.btnNav.visibility = View.VISIBLE
        b.btnPlan.visibility = View.VISIBLE
        b.lAlt.text = getString(R.string.alt)
        lastLocation?.let { onLocationChanged(it) }
        b.profile.progress(-1.0)
        updatePinStrip()
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
    override fun onResume() {
        super.onResume(); b.map.onResume(); startLocation()
        if (map != null) { checkNight(force = true); refreshPlaces() }
        // back from the background during navigation: the map takes the course up again once it is laid out
        b.root.postDelayed({ if (nav && follow == 0 && map != null) setFollow(2) }, 700)
    }
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
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); b.map.onSaveInstanceState(outState); outState.putString("picking", picking) }
}
