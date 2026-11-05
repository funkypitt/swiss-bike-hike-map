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

/** Everything that is not the map: trip tools, history, data ages and refreshes (routing, timetable, places), saved areas, the directory key. */
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
        b.btnPlacesMap.setOnClickListener { prefs.placesOnMap = !prefs.placesOnMap; updatePlacesMapButton() }
        b.btnOffMain.setOnClickListener { offMain() }
        b.btnOffDelete.setOnClickListener { if (Offline.wanted(this).isNotEmpty()) offCancel() else offDelete() }
        b.btnOffOld.setOnClickListener { offDeleteOld() }
        b.dirKey.setText(prefs.directoryKey)
        b.btnDirSave.setOnClickListener {
            prefs.directoryKey = b.dirKey.text.toString()
            Toast.makeText(this, if (prefs.directoryKey.isBlank()) R.string.people_off else R.string.people_on, Toast.LENGTH_SHORT).show()
        }
        b.btnDirOrder.setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Directory.ORDER_URL))) } }
        val pm = packageManager.getPackageInfo(packageName, 0)
        b.about.text = getString(R.string.app_name) + " ${pm.versionName}\n\n" + getString(R.string.about_text)
        updateNightButton(); updatePlacesMapButton()
    }

    private var ticking = false
    override fun onResume() {
        super.onResume(); refreshTrips(); refreshOffline(askServer = true)
        if (!ticking) { ticking = true; lifecycleScope.launch { while (ticking) { kotlinx.coroutines.delay(1500); if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) tick() } } }
    }
    override fun onPause() { super.onPause(); ticking = false }

    private fun date(t: Long) = if (t <= 0) "?" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    private fun updatePlacesMapButton() {
        b.btnPlacesMap.text = getString(if (prefs.placesOnMap) R.string.menu_places_map_on else R.string.menu_places_map_off)
    }

    private fun updateNightButton() {
        b.btnNight.text = getString(when (prefs.night) { Night.ON -> R.string.menu_night_on; Night.AUTO -> R.string.menu_night_auto; else -> R.string.menu_night_off })
    }

    // ---- position ----

    @SuppressLint("MissingPermission")
    private fun sharePosition() {
        if (!Fixes.any(this)) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = Fixes.lastKnown(this, lm) ?: run { Toast.makeText(this, R.string.no_fix, Toast.LENGTH_SHORT).show(); return }
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


    // ---- Switzerland offline: one download, one update ----

    /** What the server said this time the menu was open: is there newer data? (null = not asked, or no answer) */
    private val newer = mutableMapOf<Offline.Part, Boolean?>()
    private var asked = false
    private var asking = false
    private var mapBytes = 0L
    private var mapComplete = false
    private var oldCount = 0
    private var oldBytes = 0L

    private var wasInRun = false
    /** Every 1.5 s while the menu shows: the job's progress from its notes (cheap); the tile database only when a run has just ended. */
    private fun tick() {
        val inRun = Offline.wanted(this).isNotEmpty()
        if (wasInRun && !inRun) { asked = false; newer.clear(); refreshOffline(true) } else renderOffline()
        wasInRun = inRun
    }

    private fun refreshOffline(askServer: Boolean) {
        lifecycleScope.launch {
            val st = OfflineMaps.country(this@MenuActivity)?.let { OfflineMaps.status(it) }
            mapBytes = st?.completedResourceSize ?: 0L
            mapComplete = st?.isComplete == true && !Offline.mapRefreshing(this@MenuActivity)
            if (!Offline.mapRefreshing(this@MenuActivity)) Offline.setMapComplete(this@MenuActivity, mapComplete)
            val old = OfflineMaps.legacy(this@MenuActivity)
            oldCount = old.size
            oldBytes = old.sumOf { OfflineMaps.status(it)?.completedResourceSize ?: 0L }
            renderOffline()
            if (askServer && !asked) { asked = true; askServers(); renderOffline() }
        }
    }

    /** One small request per part on the phone: is the server's copy newer? */
    private suspend fun askServers() {
        asking = true; renderOffline()
        val ctx = this
        if (Timetable.file(ctx).exists()) newer[Offline.Part.TIMETABLE] = Timetable.remote()?.let { Timetable.newer(Timetable.local(ctx), it) }
        if (PlacesPack.file(ctx).exists()) newer[Offline.Part.PLACES] = PlacesPack.remote()?.let { PlacesPack.newer(PlacesPack.local(ctx), it) }
        if (Segments.isReady(ctx)) {
            val dates = Segments.names.map { Offline.serverModified(Segments.url(it)) }
            newer[Offline.Part.ROUTING] = if (dates.any { it == null }) null
                else Segments.names.zip(dates).any { (n, d) -> d!! > Segments.file(ctx, n).lastModified() + 60_000L }
        }
        if (mapComplete) {
            val sig = OfflineMaps.signature(ctx)
            newer[Offline.Part.MAP] = when {
                sig == null -> null
                Offline.mapSignature(ctx).isBlank() -> { Offline.setMapSignature(ctx, sig); false } // first look: this is the version on the phone
                else -> sig != Offline.mapSignature(ctx)
            }
        }
        for ((p, n) in newer) if (n == false) Offline.setChecked(ctx, p)
        asking = false
    }

    private fun present(p: Offline.Part) = if (p == Offline.Part.MAP) mapComplete else Offline.present(this, p)

    private fun bytes(p: Offline.Part): Long = when (p) {
        Offline.Part.TIMETABLE -> Timetable.file(this).length()
        Offline.Part.PLACES -> PlacesPack.file(this).length()
        Offline.Part.ROUTING -> Segments.bytes(this)
        Offline.Part.MAP -> mapBytes
    }

    private fun due(): List<Offline.Part> = Offline.Part.entries.filter { p ->
        present(p) && Policy.due(p, Offline.updated(this, p), System.currentTimeMillis(), newer[p] == true)
    }
    private fun missing(): List<Offline.Part> = Offline.Part.entries.filter { !present(it) }
    private fun missingBytes(): Long = missing().sumOf { if (it == Offline.Part.MAP) (it.estimate - mapBytes).coerceAtLeast(0L) else it.estimate }

    private fun renderOffline() {
        val wanted = Offline.wanted(this)
        val job = Offline.jobState(this)
        val paused = Offline.paused(this)
        val running = job == WorkInfo.State.RUNNING
        val current = Offline.current(this)
        val due = due()

        b.offInfo.text = Offline.Part.entries.joinToString("\n") { p ->
            val name = getString(p.label)
            val pct = (Offline.fraction(this, p).coerceAtLeast(0.0) * 100).toInt()
            val state = when {
                p in wanted && paused -> getString(R.string.off_s_paused, pct)
                p in wanted && running && current == p -> getString(R.string.off_s_downloading, pct)
                p in wanted && p == wanted.first() && !running && !OfflineMaps.unmetered(this) -> getString(R.string.dl_waiting_wifi)
                p in wanted -> getString(R.string.off_s_queued)
                !present(p) && p == Offline.Part.MAP && mapBytes > 0 -> getString(R.string.off_s_partial, Fmt.size(mapBytes))
                !present(p) -> getString(R.string.off_s_missing)
                else -> Fmt.size(bytes(p)) + " · " + date(Offline.updated(this, p)) + when {
                    p in due -> " · " + getString(R.string.off_s_due)
                    newer[p] == true -> " · " + getString(R.string.off_s_newer_later)
                    newer[p] == false -> " · " + getString(R.string.off_s_current)
                    asking -> " · " + getString(R.string.off_s_checking)
                    p in newer -> " · " + getString(R.string.off_s_unknown)
                    else -> ""
                }
            }
            "• $name — $state"
        } + "\n" + PlacesPack.CREDIT + " · swisstopo · ASTRA · brouter.de · opentransportdata.swiss"

        val inRun = Offline.run(this).isNotEmpty() && wanted.isNotEmpty()
        b.offBar.visibility = if (inRun) View.VISIBLE else View.GONE
        if (inRun) b.offBar.progress = Offline.overall(this).coerceAtLeast(0)
        val fail = wanted.map { Offline.failure(this, it) }.firstOrNull { it.isNotBlank() }
        val state = when {
            !inRun -> ""
            paused -> getString(R.string.off_paused, Offline.overall(this)) + (fail?.let { "\n" + getString(R.string.dl_failed, it) } ?: "")
            running -> getString(R.string.off_running, Offline.overall(this)) + "\n" + Offline.line(this)
            job != null && !OfflineMaps.unmetered(this) -> getString(R.string.off_wait_wifi, Offline.overall(this))
            job != null -> getString(R.string.off_wait_retry, Offline.overall(this)) + (Offline.line(this).takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
            else -> getString(R.string.off_paused, Offline.overall(this)) + (fail?.let { "\n" + getString(R.string.dl_failed, it) } ?: "")
        }
        b.offState.visibility = if (state.isBlank()) View.GONE else View.VISIBLE
        b.offState.text = state

        val miss = missing()
        b.btnOffMain.text = when {
            inRun && job != null && !paused -> getString(R.string.off_pause)
            inRun -> getString(R.string.off_resume)
            miss.size == Offline.Part.entries.size -> getString(R.string.off_download_size, Fmt.size(missingBytes()))
            miss.isNotEmpty() -> getString(R.string.off_complete_size, Fmt.size(missingBytes()))
            due.isNotEmpty() -> getString(R.string.off_update_n, due.joinToString(", ") { getString(it.label).lowercase(Locale.getDefault()) })
            else -> getString(R.string.off_up_to_date)
        }
        val quiet = !inRun && miss.isEmpty() && due.isEmpty()
        b.btnOffMain.setBackgroundResource(if (quiet) R.drawable.btn else R.drawable.btn_active)
        b.btnOffMain.setTextColor(androidx.appcompat.content.res.AppCompatResources.getColorStateList(this, if (quiet) R.drawable.btn_text else R.drawable.btn_active_text))
        val stopped = inRun && (paused || job == null)
        b.btnOffDelete.visibility = if (stopped || (!inRun && Offline.Part.entries.any { present(it) || (it == Offline.Part.MAP && mapBytes > 0) })) View.VISIBLE else View.GONE
        b.btnOffDelete.text = getString(if (stopped) R.string.off_cancel else R.string.off_delete)
        b.btnOffOld.visibility = if (oldCount > 0 && !inRun) View.VISIBLE else View.GONE
        b.btnOffOld.text = getString(R.string.off_delete_old, oldCount, Fmt.size(oldBytes))
    }

    private fun offMain() {
        val wanted = Offline.wanted(this)
        val job = Offline.jobState(this)
        val inRun = Offline.run(this).isNotEmpty() && wanted.isNotEmpty()
        when {
            inRun && job != null && !Offline.paused(this) -> Offline.pause(this)
            inRun -> Offline.resume(this)
            missing().isNotEmpty() -> confirmDownload()
            due().isNotEmpty() -> confirmUpdate(due())
            else -> confirmFresh()
        }
        lifecycleScope.launch { kotlinx.coroutines.delay(400); renderOffline() }
    }

    private fun wifiNote(need: Long): String {
        val free = runCatching { android.os.StatFs((getExternalFilesDir(null) ?: filesDir).path).availableBytes }.getOrDefault(-1L)
        return (if (!OfflineMaps.unmetered(this)) "\n\n" + getString(R.string.off_not_wifi) else "") +
            (if (free in 0 until need) "\n\n" + getString(R.string.off_no_space_now, Fmt.size(need), Fmt.size(free)) else "")
    }

    private fun confirmDownload() {
        val miss = missing()
        val lines = miss.joinToString("\n") { "• " + getString(it.label) + " — ≈ " + Fmt.size(if (it == Offline.Part.MAP) (it.estimate - mapBytes).coerceAtLeast(0L) else it.estimate) }
        AlertDialog.Builder(this).setTitle(R.string.off_head)
            .setMessage(getString(R.string.off_download_msg, lines) + wifiNote(missingBytes()))
            .setPositiveButton(R.string.download) { _, _ -> Offline.start(this, miss); notifyPermission(); renderOffline() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun confirmUpdate(due: List<Offline.Part>) {
        val lines = due.joinToString("\n") { "• " + getString(it.label) + " — " + getString(when (it) {
            Offline.Part.TIMETABLE -> R.string.off_why_timetable
            Offline.Part.PLACES -> R.string.off_why_places
            Offline.Part.ROUTING -> R.string.off_why_routing
            Offline.Part.MAP -> R.string.off_why_map
        }) }
        AlertDialog.Builder(this).setTitle(R.string.off_update)
            .setMessage(getString(R.string.off_update_msg, lines) + wifiNote(0))
            .setPositiveButton(R.string.off_update) { _, _ -> Offline.start(this, due); notifyPermission(); renderOffline() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun confirmFresh() {
        AlertDialog.Builder(this).setTitle(R.string.off_up_to_date)
            .setMessage(getString(R.string.off_fresh_msg) + wifiNote(0))
            .setPositiveButton(R.string.off_fresh_go) { _, _ -> Offline.start(this, Offline.Part.entries, fresh = true); notifyPermission(); renderOffline() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun offDelete() {
        AlertDialog.Builder(this).setMessage(R.string.off_delete_q)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    Offline.pause(this@MenuActivity); Offline.clearRun(this@MenuActivity)
                    for (r in OfflineMaps.list(this@MenuActivity)) OfflineMaps.delete(r)
                    Offline.setMapComplete(this@MenuActivity, false); Offline.setMapRefreshing(this@MenuActivity, false)
                    Segments.delete(this@MenuActivity); Timetable.delete(this@MenuActivity); PlacesPack.delete(this@MenuActivity)
                    newer.clear()
                    refreshOffline(false)
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /** Gives up a paused run: what is complete stays, the unfinished part goes. */
    private fun offCancel() {
        AlertDialog.Builder(this).setMessage(R.string.off_cancel_q)
            .setPositiveButton(R.string.off_cancel) { _, _ ->
                lifecycleScope.launch {
                    val wanted = Offline.wanted(this@MenuActivity)
                    Offline.pause(this@MenuActivity); Offline.clearRun(this@MenuActivity)
                    if (Offline.Part.MAP in wanted && !mapComplete) {
                        OfflineMaps.country(this@MenuActivity)?.let { OfflineMaps.delete(it) }
                        Offline.setMapRefreshing(this@MenuActivity, false)
                    }
                    for (p in listOf(Timetable, PlacesPack)) p.part(this@MenuActivity).let { it.delete(); java.io.File(it.path + ".tag").delete() }
                    for (n in Segments.names) Segments.part(this@MenuActivity, n).let { it.delete(); java.io.File(it.path + ".tag").delete() }
                    refreshOffline(false)
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun offDeleteOld() {
        AlertDialog.Builder(this).setMessage(getString(R.string.off_delete_old_q, oldCount))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch { for (r in OfflineMaps.legacy(this@MenuActivity)) OfflineMaps.delete(r); refreshOffline(false) }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /** The progress lives in a notification: ask for the right to show it (Android 13+), once. */
    private fun notifyPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        Toast.makeText(this, R.string.downloading_wifi, Toast.LENGTH_SHORT).show()
    }
}
