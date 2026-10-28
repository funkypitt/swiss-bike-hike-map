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
        b.btnFollowGpx.setOnClickListener { pickGpx.launch(arrayOf("*/*")) }
        b.btnSharePos.setOnClickListener { sharePosition() }
        b.btnNight.setOnClickListener {
            prefs.night = when (prefs.night) { Night.OFF -> Night.ON; Night.ON -> Night.AUTO; else -> Night.OFF }
            updateNightButton()
        }
        b.btnSegments.setOnClickListener { Segments.download(this, Segments.names); refreshSegments(); Toast.makeText(this, R.string.downloading_wifi, Toast.LENGTH_SHORT).show() }
        b.btnCountry.setOnClickListener { if (OfflineService.running) cancelCountry() else saveCountry() }
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
        b.about.text = "Swiss Bike-Hike Map ${pm.versionName}\n\n" + getString(R.string.about_text)
        updateNightButton()
    }

    override fun onResume() { super.onResume(); refreshSegments(); refreshAreasInfo(); refreshTrips() }

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
        }
    }

    // ---- offline areas ----

    private fun refreshAreasInfo() {
        lifecycleScope.launch {
            val regions = OfflineMaps.list(this@MenuActivity)
            val lines = regions.map { r ->
                val st = OfflineMaps.status(r)
                val size = st?.completedResourceSize ?: 0L
                val done = st?.completedResourceCount ?: 0L
                val total = st?.requiredResourceCount ?: 0L
                "${OfflineMaps.name(r)}  ${Fmt.mb(size)}  " + (if (st?.isComplete == true) getString(R.string.area_complete) else getString(R.string.area_tiles, done, total))
            }
            val auto = prefs.offlineRefreshStatus.ifBlank { getString(R.string.auto_refresh_never) }
            val country = prefs.countryStatus
            b.offlineInfo.text = (if (lines.isEmpty()) getString(R.string.no_saved_area) else lines.joinToString("\n")) +
                (if (country.isNotBlank()) "\n\n$country" else "") +
                "\n\n" + auto + "\n" + getString(R.string.auto_refresh_rule, OfflineMaps.AUTO_DAYS)
            b.btnCountry.text = getString(if (OfflineService.running) R.string.menu_country_cancel else R.string.menu_country)
        }
    }

    private fun saveCountry() {
        val styleFile = MapStyle.styleFile(this)
        if (!styleFile.exists()) { Toast.makeText(this, R.string.open_map_first, Toast.LENGTH_SHORT).show(); return }
        val wifi = OfflineMaps.unmetered(this)
        AlertDialog.Builder(this).setTitle(R.string.menu_country)
            .setMessage(getString(R.string.country_confirm) + if (!wifi) "\n\n" + getString(R.string.country_not_wifi) else "")
            .setPositiveButton(R.string.download) { _, _ -> OfflineService.start(this); b.btnCountry.text = getString(R.string.menu_country_cancel); b.offlineInfo.text = getString(R.string.country_starting) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun cancelCountry() {
        OfflineService.cancel(this)
        b.btnCountry.text = getString(R.string.menu_country)
        lifecycleScope.launch { kotlinx.coroutines.delay(800); refreshAreasInfo() }
    }

    private fun saveArea() {
        val bounds = prefs.lastBounds ?: run { Toast.makeText(this, R.string.open_map_first, Toast.LENGTH_SHORT).show(); return }
        val styleFile = MapStyle.styleFile(this)
        if (!styleFile.exists()) { Toast.makeText(this, R.string.open_map_first, Toast.LENGTH_SHORT).show(); return }
        val name = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        b.offlineInfo.text = getString(R.string.area_saving, name, 0L, 0L)
        OfflineMaps.download(this, styleFile, bounds, name,
            onProgress = { done, total, finished ->
                runOnUiThread { b.offlineInfo.text = if (finished) getString(R.string.area_saved, name, done) else getString(R.string.area_saving, name, done, total)
                    if (finished) refreshAreasInfo() }
            },
            onError = { msg -> runOnUiThread { b.offlineInfo.text = getString(R.string.error_x, msg) } })
    }

    private fun refreshAreas() {
        lifecycleScope.launch {
            val regions = OfflineMaps.list(this@MenuActivity)
            for (r in regions) {
                OfflineMaps.refresh(r)
                OfflineMaps.start(r, { done, total, finished ->
                    runOnUiThread { b.offlineInfo.text = if (finished) getString(R.string.area_refreshed, OfflineMaps.name(r)) else getString(R.string.area_refreshing, OfflineMaps.name(r), done, total) }
                }, { msg -> runOnUiThread { b.offlineInfo.text = getString(R.string.error_x, msg) } })
            }
            if (regions.isEmpty()) b.offlineInfo.text = getString(R.string.no_saved_area)
        }
    }

    private fun deleteAreas() {
        AlertDialog.Builder(this).setMessage(R.string.delete_areas_q)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    for (r in OfflineMaps.list(this@MenuActivity)) OfflineMaps.delete(r)
                    prefs.countryStatus = ""
                    refreshAreasInfo()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }
}
