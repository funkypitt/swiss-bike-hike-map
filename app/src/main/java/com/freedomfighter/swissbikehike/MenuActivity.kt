package com.freedomfighter.swissbikehike

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.freedomfighter.swissbikehike.databinding.ActivityMenuBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything that is not the map: data ages and refreshes, saved areas, the directory key, a GPX to show. */
class MenuActivity : AppCompatActivity() {
    companion object { const val RESULT_RESTYLE = 7 }
    private lateinit var b: ActivityMenuBinding
    private var restyle = false

    private val pickGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { setResult(Activity.RESULT_OK, Intent().setData(uri)); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.pad(b.root)
        b.btnSegments.setOnClickListener { Segments.download(this, Segments.names); refreshSegments(); Toast.makeText(this, "Downloading over Wi-Fi", Toast.LENGTH_SHORT).show() }
        b.btnOfflineArea.setOnClickListener { saveArea() }
        b.btnOfflineRefresh.setOnClickListener { refreshAreas() }
        b.btnOfflineDelete.setOnClickListener { deleteAreas() }
        b.btnImport.setOnClickListener { pickGpx.launch(arrayOf("*/*")) }
        val prefs = Prefs(this)
        b.dirKey.setText(prefs.directoryKey)
        b.btnDirSave.setOnClickListener {
            prefs.directoryKey = b.dirKey.text.toString()
            Toast.makeText(this, if (prefs.directoryKey.isBlank()) "People search off" else "People search on", Toast.LENGTH_SHORT).show()
        }
        b.btnDirOrder.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Directory.ORDER_URL))) }
        }
        val pm = packageManager.getPackageInfo(packageName, 0)
        b.about.text = "Swiss Bike-Hike Map ${pm.versionName}\n\n" +
            "Map: swisstopo vector base map, hiking trail network, Veloland and Mountainbikeland routes, closures (© swisstopo, © SchweizMobil).\n" +
            "Routing: BRouter (MIT) on OpenStreetMap data (ODbL), profiles « trekking » and « hiking-mountain ».\n" +
            "Map rendering: MapLibre Native (BSD).\n" +
            "Search: GeoAdmin API; people and firms: search.ch directory (Swisscom Directories AG) with your own key."
    }

    override fun onResume() { super.onResume(); refreshSegments(); refreshAreasInfo() }

    override fun finish() {
        if (restyle && !isFinishing) setResult(RESULT_RESTYLE)
        super.finish()
    }

    private fun date(t: Long) = if (t <= 0) "?" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    private fun refreshSegments() {
        val st = Segments.status(this)
        b.segInfo.text = st.joinToString("\n") { s ->
            when {
                s.downloading >= 0 -> "${s.name}  downloading ${s.downloading}%"
                s.present -> "${s.name}  ${Fmt.mb(s.size)}  ${date(s.modified)}"
                else -> "${s.name}  missing"
            }
        } + "\nchecking brouter.de…"
        lifecycleScope.launch {
            val server = Segments.serverDates()
            val lines = st.map { s ->
                val srv = server[s.name]
                val tail = when {
                    s.downloading >= 0 -> "downloading ${s.downloading}%"
                    !s.present -> "missing"
                    srv == null -> "${Fmt.mb(s.size)}  ${date(s.modified)}"
                    srv > s.modified -> "${Fmt.mb(s.size)}  ${date(s.modified)}  → newer on server: ${date(srv)}"
                    else -> "${Fmt.mb(s.size)}  ${date(s.modified)}  up to date"
                }
                "${s.name}  $tail"
            }
            b.segInfo.text = lines.joinToString("\n")
        }
    }

    private fun refreshAreasInfo() {
        lifecycleScope.launch {
            val regions = OfflineMaps.list(this@MenuActivity)
            if (regions.isEmpty()) { b.offlineInfo.text = "No saved area. Zoom the map to the area you want, then save it here (zoom 8 to 15)."; return@launch }
            val lines = regions.map { r ->
                val st = OfflineMaps.status(r)
                val size = st?.completedResourceSize ?: 0L
                val done = st?.completedResourceCount ?: 0L
                val total = st?.requiredResourceCount ?: 0L
                "${OfflineMaps.name(r)}  ${Fmt.mb(size)}  " + (if (st?.isComplete == true) "complete" else "$done / $total tiles")
            }
            val prefs = Prefs(this@MenuActivity)
            val auto = prefs.offlineRefreshStatus.ifBlank { "Not refreshed automatically yet" }
            b.offlineInfo.text = lines.joinToString("\n") + "\n\n" + auto +
                "\n(every ${OfflineMaps.AUTO_DAYS} days over Wi-Fi, when the app starts)"
        }
    }

    private fun saveArea() {
        val bounds = Prefs(this).lastBounds ?: run { Toast.makeText(this, "Open the map first", Toast.LENGTH_SHORT).show(); return }
        val styleFile = MapStyle.styleFile(this)
        if (!styleFile.exists()) { Toast.makeText(this, "Open the map first", Toast.LENGTH_SHORT).show(); return }
        val name = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        b.offlineInfo.text = "Saving $name…"
        OfflineMaps.download(this, styleFile, bounds, name,
            onProgress = { done, total, finished ->
                runOnUiThread { b.offlineInfo.text = if (finished) "Saved $name ($done tiles)" else "Saving $name: $done / $total tiles"
                    if (finished) refreshAreasInfo() }
            },
            onError = { msg -> runOnUiThread { b.offlineInfo.text = "Error: $msg" } })
    }

    private fun refreshAreas() {
        lifecycleScope.launch {
            val regions = OfflineMaps.list(this@MenuActivity)
            for (r in regions) {
                OfflineMaps.refresh(r)
                OfflineMaps.start(r, { done, total, finished ->
                    runOnUiThread { b.offlineInfo.text = if (finished) "Refreshed ${OfflineMaps.name(r)}" else "Refreshing ${OfflineMaps.name(r)}: $done / $total tiles" }
                }, { msg -> runOnUiThread { b.offlineInfo.text = "Error: $msg" } })
            }
            if (regions.isEmpty()) b.offlineInfo.text = "No saved area."
        }
    }

    private fun deleteAreas() {
        AlertDialog.Builder(this).setMessage("Delete every saved area?")
            .setPositiveButton("DELETE") { _, _ ->
                lifecycleScope.launch {
                    for (r in OfflineMaps.list(this@MenuActivity)) OfflineMaps.delete(r)
                    refreshAreasInfo()
                }
            }.setNegativeButton("CANCEL", null).show()
    }
}
