package com.freedomfighter.swissbikehike

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.freedomfighter.swissbikehike.databinding.ActivityMapBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
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

/** The one map screen. */
class MapActivity : AppCompatActivity(), LocationListener {
    companion object { const val EXTRA_MODE = "mode" }

    private lateinit var b: ActivityMapBinding
    private lateinit var prefs: Prefs
    private lateinit var mode: Mode
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var paper = false
    private var pin: LatLng? = null
    private var route: Router.Result? = null
    private var routeCum: DoubleArray? = null
    private var lastLocation: Location? = null
    private var follow = 0 // 0 off, 1 north up, 2 heading up
    private var routing = false

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) { startLocation(); enableLocationLayer() }
    }
    private val searchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.let { d ->
            val p = LatLng(d.getDoubleExtra(SearchActivity.EXTRA_LAT, 0.0), d.getDoubleExtra(SearchActivity.EXTRA_LON, 0.0))
            setPin(p)
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
        paper = prefs.paper
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
        b.btnZoomIn.setOnClickListener { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
        b.btnZoomOut.setOnClickListener { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
        b.btnFollow.setOnClickListener { cycleFollow() }
        b.btnGo.setOnClickListener { if (route != null) clearRoute() else go() }
        b.btnRec.setOnClickListener { toggleRecording() }
        b.btnPaper.setOnClickListener { paper = !paper; prefs.paper = paper; applyVisibility() }
        updatePaperButton()

        b.map.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = false; isTiltGesturesEnabled = false
                isAttributionEnabled = false; isLogoEnabled = false; isCompassEnabled = true
                setCompassFadeFacingNorth(true)
            }
            val (target, zoom, bearing) = prefs.camera
            m.cameraPosition = CameraPosition.Builder().target(target).zoom(zoom).bearing(bearing).build()
            m.addOnMapLongClickListener { p -> setPin(p); true }
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
                val built = MapStyle.build(this@MapActivity, MapStyle.baseStyle(this@MapActivity))
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
        setVisible(MapStyle.baseLayers, !paper)
        setVisible(MapStyle.paperLayers, paper)
        setVisible(MapStyle.walkLayers, mode == Mode.WALK)
        setVisible(MapStyle.bikeLayers, mode == Mode.BIKE)
        updatePaperButton()
    }

    private fun updatePaperButton() {
        b.btnPaper.text = getString(if (paper) R.string.map else R.string.paper)
        b.btnPaper.setBackgroundResource(if (paper) R.drawable.btn_active else R.drawable.btn)
        b.btnPaper.setTextColor(AppCompatResources.getColorStateList(this, if (paper) R.drawable.btn_active_text else R.drawable.btn_text))
    }

    // ---------- location ----------

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
                LocationComponentActivationOptions.builder(this, s).useDefaultLocationEngine(false).build())
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() { follow = 0; updateFollowButton() }
                override fun onCameraTrackingChanged(currentMode: Int) {}
            })
        }
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.COMPASS
        lastLocation?.let { lc.forceLocationUpdate(it) }
    }

    override fun onLocationChanged(loc: Location) {
        lastLocation = loc
        map?.locationComponent?.let { if (it.isLocationComponentActivated) it.forceLocationUpdate(loc) }
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        b.vSpeed.text = if (kmh < 1.0) "0" else String.format(Locale.US, "%.0f", kmh)
        val alt = if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) loc.mslAltitudeMeters
                  else if (loc.hasAltitude()) loc.altitude else Double.NaN
        b.vAlt.text = if (alt.isNaN()) "--" else String.format(Locale.US, "%.0f", alt)
        updateDist()
    }

    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun cycleFollow() {
        val lc = map?.locationComponent ?: return
        if (!lc.isLocationComponentActivated) { Toast.makeText(this, R.string.no_fix, Toast.LENGTH_SHORT).show(); return }
        follow = (follow + 1) % 3
        lc.cameraMode = when (follow) { 1 -> CameraMode.TRACKING; 2 -> CameraMode.TRACKING_GPS; else -> CameraMode.NONE }
        if (follow == 1) map?.animateCamera(CameraUpdateFactory.bearingTo(0.0))
        updateFollowButton()
    }

    private fun updateFollowButton() {
        b.btnFollow.text = if (follow == 2) "➤" else "◎"
        b.btnFollow.setBackgroundResource(if (follow > 0) R.drawable.btn_active else R.drawable.btn)
        b.btnFollow.setTextColor(AppCompatResources.getColorStateList(this, if (follow > 0) R.drawable.btn_active_text else R.drawable.btn_text))
    }

    // ---------- pin and route ----------

    private fun setPin(p: LatLng) {
        pin = p
        drawPin(p)
        b.btnGo.isEnabled = true
    }

    private fun drawPin(p: LatLng) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_PIN)
            ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)))
    }

    private fun go() {
        val to = pin ?: return
        if (routing) return
        if (!Segments.isReady(this)) { askForData(); return }
        val from = lastLocation?.let { LatLng(it.latitude, it.longitude) } ?: run {
            Toast.makeText(this, "No GPS fix: route from the map centre", Toast.LENGTH_SHORT).show()
            map?.cameraPosition?.target
        } ?: return
        routing = true
        status(getString(R.string.routing))
        lifecycleScope.launch {
            try {
                val r = Router.route(this@MapActivity, mode, from, to)
                route = r
                routeCum = cumulative(r.points)
                drawRoute(r)
                b.routeStrip.text = String.format(Locale.US, "%s km   ↑%d m   ↓%d m   %s",
                    Fmt.km(r.distanceM.toDouble()), r.ascendM, r.descendM, Fmt.hm(r.seconds))
                b.routeStrip.visibility = View.VISIBLE
                b.btnGo.text = getString(R.string.clear)
                b.lDist.text = getString(R.string.togo)
                updateDist()
                val bb = LatLngBounds.Builder().also { bld -> r.points.forEach { bld.include(it) } }.build()
                map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bb, 80, 360, 80, 120))
            } catch (e: Exception) {
                Toast.makeText(this@MapActivity, getString(R.string.route_fail) + ": " + e.message, Toast.LENGTH_LONG).show()
            }
            routing = false
            status(null)
        }
    }

    private fun clearRoute() {
        route = null; routeCum = null; pin = null
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_PIN)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        b.routeStrip.visibility = View.GONE
        b.btnGo.text = getString(R.string.go)
        b.btnGo.isEnabled = false
        b.lDist.text = getString(R.string.dist)
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

    /** Distance still to go along the route, from the nearest route point. */
    private fun remaining(): Double {
        val r = route ?: return -1.0; val cum = routeCum ?: return -1.0
        val loc = lastLocation ?: return r.distanceM.toDouble()
        val here = LatLng(loc.latitude, loc.longitude)
        var best = 0; var bestD = Double.MAX_VALUE
        for (i in r.points.indices) { val d = here.distanceTo(r.points[i]); if (d < bestD) { bestD = d; best = i } }
        return cum[cum.size - 1] - cum[best]
    }

    private fun updateDist() {
        val rec = RecordService.state.value
        b.vDist.text = when {
            route != null -> Fmt.km(remaining())
            rec.running -> Fmt.km(rec.distanceM)
            else -> "--"
        }
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
