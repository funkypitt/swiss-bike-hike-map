package com.freedomfighter.swissbikehike

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import com.freedomfighter.swissbikehike.databinding.ActivityMenuBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything that is not the map: trip tools, history, data ages and refreshes, saved areas, the directory key. */
class MenuActivity : AppCompatActivity() {
    companion object { const val RESULT_RESTYLE = 7; const val RESULT_TRIP = 8; const val EXTRA_FILE = "file" }
    private lateinit var b: ActivityMenuBinding
    private lateinit var prefs: Prefs

    private val pickGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { setResult(Activity.RESULT_OK, Intent().setData(uri)); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.pad(b.root)
        prefs = Prefs(this)
        b.btnUpdateAll.setOnClickListener { updateAll() }
        b.btnFollowGpx.setOnClickListener { pickGpx.launch(arrayOf("*/*")) }
        b.btnSharePos.setOnClickListener { sharePosition() }
        b.btnNight.setOnClickListener {
            prefs.night = when (prefs.night) { Night.OFF -> Night.ON; Night.ON -> Night.AUTO; else -> Night.OFF }
            updateNightButton()
        }
        b.btnSegments.setOnClickListener { Segments.download(this, Segments.names); refreshSegments(); Toast.makeText(this, R.string.downloading_wifi, Toast.LENGTH_SHORT).show() }
        b.btnTimetable.setOnClickListener { Timetable.download(this); refreshTimetable(); Toast.makeText(this, R.string.downloading_wifi, Toast.LENGTH_SHORT).show() }
        b.btnCountry.setOnClickListener { saveCountry() }
        b.btnPacks.setOnClickListener { if (PackWorker.state(this)?.isFinished == false) { PackWorker.pause(this); prefs.packStatus = getString(R.string.packs_paused) } else PackWorker.enqueue(this); lifecycleScope.launch { kotlinx.coroutines.delay(600); refreshAreasInfo() } }
        b.btnOfflineArea.setOnClickListener { saveArea() }
        b.btnOfflineRefresh.setOnClickListener { refreshAreas() }
        b.btnOfflineDelete.setOnClickListener { deleteAreas() }
        b.dirKey.setText(prefs.directoryKey)
        b.btnDirSave.setOnClickListener {
            prefs.directoryKey = b.dirKey.text.toString()
            Toast.makeText(this, if (prefs.directoryKey.isBlank()) R.string.people_off else R.string.people_on, Toast.LENGTH_SHORT).show()
        }
        b.btnDirOrder.setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Directory.ORDER_URL))) } }
        val pm = packageManager.getPackageInfo(packageName, 0)
        b.about.text = getString(R.string.app_name) + " ${pm.versionName}\n\n" + getString(R.string.about_text)
        updateNightButton()
    }

    private var ticking = false
    override fun onResume() {
        super.onResume(); refreshSegments(); refreshAreasInfo(); refreshTrips(); refreshTimetable()
        if (!ticking) { ticking = true; lifecycleScope.launch { while (ticking) { kotlinx.coroutines.delay(3000); if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) { refreshAreasInfo(); if (Timetable.downloading(this@MenuActivity) >= 0) refreshTimetable(false) } } } }
    }
    override fun onPause() { super.onPause(); ticking = false }

    private fun date(t: Long) = if (t <= 0) "?" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    private fun updateNightButton() {
        b.btnNight.text = getString(when (prefs.night) { Night.ON -> R.string.menu_night_on; Night.AUTO -> R.string.menu_night_auto; else -> R.string.menu_night_off })
    }

    // ---- position ----

    @SuppressLint("MissingPermission")
    private fun sharePosition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: run { Toast.makeText(this, R.string.no_fix, Toast.LENGTH_SHORT).show(); return }
        val lat = String.format(Locale.US, "%.5f", loc.latitude); val lon = String.format(Locale.US, "%.5f", loc.longitude)
        val name = getString(R.string.my_position)
        val text = "$name\n$lat, $lon\nhttps://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=16/$lat/$lon\ngeo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, name)
        runCatching { startActivity(Intent.createChooser(send, null)) }
    }

    // ---- trips ----

    private fun refreshTrips() {
        val trips = Trips.list(this)
        b.tripsList.removeAllViews()
        b.tripsInfo.visibility = if (trips.isEmpty()) View.VISIBLE else View.GONE
        for (t in trips.take(30)) {
            val v = TextView(this)
            v.text = t.label(); v.textSize = 15f; v.setTextColor(ContextCompat.getColor(this, R.color.ink))
            v.setPadding(0, 14, 0, 14); v.setLineSpacing(4f, 1f)
            v.setOnClickListener { setResult(RESULT_TRIP, Intent().putExtra(EXTRA_FILE, Trips.file(this, t).absolutePath)); finish() }
            v.setOnLongClickListener {
                AlertDialog.Builder(this).setMessage(getString(R.string.trip_remove_q, t.dest))
                    .setPositiveButton(R.string.remove) { _, _ -> Trips.remove(this, t); refreshTrips() }
                    .setNegativeButton(R.string.cancel, null).show(); true
            }
            b.tripsList.addView(v)
        }
    }

    // ---- routing data ----

    private fun refreshSegments() {
        val st = Segments.status(this)
        b.segInfo.text = st.joinToString("\n") { s ->
            when {
                s.downloading >= 0 -> "${s.name}  " + getString(R.string.seg_downloading, s.downloading)
                s.present -> "${s.name}  ${Fmt.mb(s.size)}  ${date(s.modified)}"
                else -> "${s.name}  " + getString(R.string.seg_missing)
            }
        } + "\n" + getString(R.string.seg_checking)
        lifecycleScope.launch {
            val server = Segments.serverDates()
            val lines = st.map { s ->
                val srv = server[s.name]
                val tail = when {
                    s.downloading >= 0 -> getString(R.string.seg_downloading, s.downloading)
                    !s.present -> getString(R.string.seg_missing)
                    srv == null -> "${Fmt.mb(s.size)}  ${date(s.modified)}"
                    srv > s.modified -> "${Fmt.mb(s.size)}  ${date(s.modified)}  " + getString(R.string.seg_newer, date(srv))
                    else -> "${Fmt.mb(s.size)}  ${date(s.modified)}  " + getString(R.string.seg_up_to_date)
                }
                "${s.name}  $tail"
            }
            b.segInfo.text = lines.joinToString("\n")
            segmentsMissing = st.any { !it.present }
            segmentsNewer = st.any { s -> s.present && (server[s.name] ?: 0L) > s.modified }
            updateAllInfo()
        }
    }

    // ---- public-transport timetable ----

    private fun refreshTimetable(askServer: Boolean = true) {
        val pct = Timetable.downloading(this)
        val l = Timetable.local(this)
        val base = when {
            pct >= 0 -> getString(R.string.timetable_downloading, pct)
            l == null -> getString(R.string.timetable_missing)
            else -> getString(R.string.timetable_info, l.feed, Fmt.mb(l.bytes), l.built)
        }
        b.timetableInfo.text = base + "\n" + getString(R.string.timetable_hint) + (if (askServer) "\n" + getString(R.string.timetable_checking) else "")
        if (!askServer) return
        lifecycleScope.launch {
            val r = Timetable.remote()
            val tail = when {
                r == null -> ""
                l == null -> "\n" + getString(R.string.timetable_newer, r.feed + " · " + Fmt.mb(r.bytes))
                r.feed > l.feed || r.built > l.built -> "\n" + getString(R.string.timetable_newer, r.feed + " · " + r.built)
                else -> "\n" + getString(R.string.timetable_up_to_date)
            }
            b.timetableInfo.text = base + "\n" + getString(R.string.timetable_hint) + tail
            timetableNewer = r != null && (l == null || r.feed > l.feed || r.built > l.built)
            updateAllInfo()
        }
    }

    // ---- offline areas ----

    private fun refreshAreasInfo() {
        lifecycleScope.launch {
            val regions = OfflineMaps.list(this@MenuActivity)
            var missing = false
            val lines = regions.map { r ->
                val st = OfflineMaps.status(r)
                val size = st?.completedResourceSize ?: 0L
                val done = st?.completedResourceCount ?: 0L
                val total = st?.requiredResourceCount ?: 0L
                if (st?.isComplete == false) missing = true
                "${OfflineMaps.name(r)}  ${Fmt.mb(size)}  " + (if (st?.isComplete == true) getString(R.string.area_complete) else getString(R.string.area_tiles, done, total))
            }
            val job = PackWorker.state(this@MenuActivity)
            val jobLine = when (job) {
                WorkInfo.State.RUNNING -> prefs.packStatus.ifBlank { getString(R.string.packs_starting) }
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> getString(R.string.packs_waiting)
                else -> if (missing) getString(R.string.packs_incomplete) else prefs.packStatus
            }
            val last = prefs.offlineRefreshStatus
            b.offlineInfo.text = (if (lines.isEmpty()) getString(R.string.no_saved_area) else lines.joinToString("\n")) +
                (if (jobLine.isNotBlank()) "\n\n$jobLine" else "") +
                (if (last.isNotBlank()) "\n$last" else "") +
                "\n\n" + getString(R.string.refresh_hint)
            areasCount = regions.size; areasMissing = missing; areasJob = job
            updateAllInfo()
            val active = job == WorkInfo.State.RUNNING || job == WorkInfo.State.ENQUEUED || job == WorkInfo.State.BLOCKED
            b.btnPacks.visibility = if (active || missing) View.VISIBLE else View.GONE
            b.btnPacks.text = getString(if (active) R.string.menu_pause_packs else R.string.menu_resume_packs)
        }
    }

    private fun saveCountry() {
        val styleFile = MapStyle.styleFile(this)
        if (!styleFile.exists()) { Toast.makeText(this, R.string.open_map_first, Toast.LENGTH_SHORT).show(); return }
        val wifi = OfflineMaps.unmetered(this)
        AlertDialog.Builder(this).setTitle(R.string.menu_country)
            .setMessage(getString(R.string.country_confirm) + if (!wifi) "\n\n" + getString(R.string.country_not_wifi) else "")
            .setPositiveButton(R.string.download) { _, _ ->
                lifecycleScope.launch {
                    if (OfflineMaps.list(this@MenuActivity).none { OfflineMaps.name(it) == OfflineMaps.COUNTRY_NAME })
                        OfflineMaps.create(this@MenuActivity, styleFile, OfflineMaps.COUNTRY, OfflineMaps.COUNTRY_NAME, OfflineMaps.COUNTRY_MAX_ZOOM)
                    else PackWorker.enqueue(this@MenuActivity)
                    refreshAreasInfo()
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun saveArea() {
        val bounds = prefs.lastBounds ?: run { Toast.makeText(this, R.string.open_map_first, Toast.LENGTH_SHORT).show(); return }
        val styleFile = MapStyle.styleFile(this)
        if (!styleFile.exists()) { Toast.makeText(this, R.string.open_map_first, Toast.LENGTH_SHORT).show(); return }
        val name = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        lifecycleScope.launch { OfflineMaps.create(this@MenuActivity, styleFile, bounds, name); refreshAreasInfo() }
    }

    private fun refreshAreas(quiet: Boolean = false) {
        lifecycleScope.launch {
            val regions = OfflineMaps.list(this@MenuActivity)
            if (regions.isEmpty()) { if (!quiet) Toast.makeText(this@MenuActivity, R.string.no_saved_area, Toast.LENGTH_SHORT).show(); return@launch }
            // the current style goes into the packs too, so they follow the app
            val styleFile = MapStyle.styleFile(this@MenuActivity)
            if (styleFile.exists()) {
                val now = System.currentTimeMillis() / 1000
                OfflineMaps.manager(this@MenuActivity).putResourceWithUrl(OfflineMaps.STYLE_URL, styleFile.readBytes(), now, now + 3650L * 86400, null, false)
            }
            for (r in regions) OfflineMaps.refresh(r)
            prefs.offlineRefreshed = System.currentTimeMillis()
            prefs.offlineRefreshStatus = getString(R.string.refresh_asked, regions.size, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            PackWorker.enqueue(this@MenuActivity)
            if (!quiet) Toast.makeText(this@MenuActivity, R.string.downloading_wifi, Toast.LENGTH_SHORT).show()
            refreshAreasInfo()
        }
    }

    // ---- everything offline, in one button ----

    private var areasCount = 0; private var areasMissing = false; private var areasJob: WorkInfo.State? = null
    private var segmentsNewer = false; private var segmentsMissing = false
    private var timetableNewer = false

    /** One line per kind of offline data, under the big button: what is there, what the server has newer. */
    private fun updateAllInfo() {
        val maps = when {
            areasCount == 0 -> getString(R.string.all_maps_none)
            areasJob == WorkInfo.State.RUNNING || areasJob == WorkInfo.State.ENQUEUED || areasJob == WorkInfo.State.BLOCKED -> getString(R.string.all_maps_running, areasCount)
            areasMissing -> getString(R.string.all_maps_incomplete, areasCount)
            else -> getString(R.string.all_maps_ok, areasCount)
        }
        val seg = when { segmentsMissing -> getString(R.string.all_routing_missing); segmentsNewer -> getString(R.string.all_routing_newer); else -> getString(R.string.all_routing_ok) }
        val tt = when { !Timetable.isReady(this) -> getString(R.string.all_timetable_missing); timetableNewer -> getString(R.string.all_timetable_newer); else -> getString(R.string.all_timetable_ok) }
        b.allInfo.text = "• $maps\n• $seg\n• $tt"
    }

    /** Refreshes every saved area, fetches the routing data and the timetable again: all of it, after one yes. */
    private fun updateAll() {
        val wifi = OfflineMaps.unmetered(this)
        AlertDialog.Builder(this).setTitle(R.string.menu_update_all)
            .setMessage(getString(R.string.update_all_confirm) + if (!wifi) "\n\n" + getString(R.string.country_not_wifi) else "")
            .setPositiveButton(R.string.download) { _, _ ->
                refreshAreas(quiet = true)
                Segments.download(this, Segments.names)
                Timetable.download(this)
                Toast.makeText(this, R.string.downloading_wifi, Toast.LENGTH_LONG).show()
                refreshSegments(); refreshTimetable(); refreshAreasInfo()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun deleteAreas() {
        AlertDialog.Builder(this).setMessage(R.string.delete_areas_q)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    for (r in OfflineMaps.list(this@MenuActivity)) OfflineMaps.delete(r)
                    PackWorker.pause(this@MenuActivity)
                    prefs.packStatus = ""
                    refreshAreasInfo()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }
}
