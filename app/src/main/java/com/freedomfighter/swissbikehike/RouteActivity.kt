package com.freedomfighter.swissbikehike

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.freedomfighter.swissbikehike.databinding.ActivityRouteBinding
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import java.util.Calendar

class RouteForm(var from: Stop? = null, var to: Stop? = null, var depart: Long = 0L, var result: Itinerary? = null)

/**
 * The route planner of every map: a start (my position, a search, a point on the map), a
 * destination, a date and a time, then the itinerary in detail, which can be shared, kept in
 * the app, shown on the map, or followed at once (GO). The form survives a trip to the map to
 * pick a point, through `form`.
 */
class RouteActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MODE = "mode"; const val EXTRA_LAT = "lat"; const val EXTRA_LON = "lon"; const val EXTRA_PICK = "pick"
        const val RESULT_PICK = 21; const val RESULT_SHOW = 22; const val RESULT_GO = 23
        /** What the form holds, kept while the map is used to pick a point; one map's form at a time. */
        var form: RouteForm? = null
        var formMode: Mode? = null
        /** The itinerary handed to the map when SHOW or GO is pressed. */
        var handover: Itinerary? = null
    }

    private lateinit var b: ActivityRouteBinding
    private lateinit var mode: Mode
    private var here: LatLng? = null
    private var f = RouteForm()
    private var computing = false
    /** True only on the way to the map to pick a point: then the form must survive. */
    private var keepForm = false

    override fun onDestroy() {
        super.onDestroy()
        if (!keepForm && !isChangingConfigurations) { form = null; formMode = null }
    }
    private var choices: List<Journey> = emptyList()

    private val searchFrom = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> picked(r)?.let { f.from = it; render() } }
    private val searchTo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> picked(r)?.let { f.to = it; f.result = null; render() } }

    private fun picked(r: androidx.activity.result.ActivityResult): Stop? {
        if (r.resultCode != Activity.RESULT_OK) return null
        val d = r.data ?: return null
        val p = LatLng(d.getDoubleExtra(SearchActivity.EXTRA_LAT, 0.0), d.getDoubleExtra(SearchActivity.EXTRA_LON, 0.0))
        return Stop(p, d.getStringExtra(SearchActivity.EXTRA_LABEL) ?: Favourites.coordsName(p.latitude, p.longitude),
            d.getStringExtra(SearchActivity.EXTRA_KIND) ?: Favourites.PLACE, d.getStringExtra(SearchActivity.EXTRA_DETAIL) ?: "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRouteBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.pad(b.root)
        mode = Mode.of(intent.getStringExtra(EXTRA_MODE))
        if (intent.hasExtra(EXTRA_LAT)) here = LatLng(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0))
        f = (if (formMode == mode) form else null) ?: RouteForm()
        formMode = mode
        // a departure left in the past (the form was kept for a map pick) means "now"
        if (f.depart < System.currentTimeMillis() - 120_000L) { f.depart = System.currentTimeMillis(); if (f.result?.journey != null) f.result = null }
        form = f
        b.modeLine.text = getString(when (mode) { Mode.BIKE -> R.string.bike; Mode.WALK -> R.string.walk; Mode.TRANSIT -> R.string.transit })
        b.fromSearch.setOnClickListener { searchFrom.launch(Intent(this, SearchActivity::class.java)) }
        b.toSearch.setOnClickListener { searchTo.launch(Intent(this, SearchActivity::class.java)) }
        b.fromMap.setOnClickListener { keepForm = true; setResult(RESULT_PICK, Intent().putExtra(EXTRA_PICK, "from")); finish() }
        b.toMap.setOnClickListener { keepForm = true; setResult(RESULT_PICK, Intent().putExtra(EXTRA_PICK, "to")); finish() }
        b.fromHere.setOnClickListener { f.from = null; f.result = null; render() }
        b.swap.setOnClickListener {
            val from = f.from ?: here?.let { Stop(it, getString(R.string.my_position)) }
            if (from == null || f.to == null) return@setOnClickListener
            f.from = f.to; f.to = from; f.result = null; render()
        }
        b.dateBtn.setOnClickListener { pickDate() }
        b.timeBtn.setOnClickListener { pickTime() }
        b.nowBtn.setOnClickListener { f.depart = System.currentTimeMillis(); f.result = null; render() }
        b.compute.setOnClickListener { compute() }
        b.share.setOnClickListener { f.result?.let { share(it) } }
        b.save.setOnClickListener { f.result?.let { f.result = Itineraries.save(this, it); Toast.makeText(this, R.string.route_saved_ok, Toast.LENGTH_SHORT).show(); renderSaved() } }
        b.show.setOnClickListener { f.result?.let { handover = it; setResult(RESULT_SHOW); finish() } }
        b.go.setOnClickListener { f.result?.let { handover = it; setResult(RESULT_GO); finish() } }
        render()
        renderSaved()
    }

    private fun render() {
        b.fromValue.text = f.from?.name ?: getString(R.string.my_position) + if (here == null) "  (" + getString(R.string.no_fix) + ")" else ""
        b.toValue.text = f.to?.name ?: getString(R.string.route_to_hint)
        b.toValue.setTextColor(ContextCompat.getColor(this, if (f.to == null) R.color.grey else R.color.ink))
        b.dateBtn.text = Itineraries.day(f.depart)
        b.timeBtn.text = Itineraries.clock(f.depart)
        val it = f.result
        b.result.visibility = if (it == null) View.GONE else View.VISIBLE
        if (it != null) {
            b.summary.text = Itineraries.summary(this, it)
            b.steps.text = Itineraries.steps(this, it).joinToString("\n")
            b.save.text = getString(if (it.file.isBlank()) R.string.route_save else R.string.route_saved_ok)
        }
        renderChoices()
    }

    /** Several public-transport journeys: one row each, the chosen one in black. */
    private fun renderChoices() {
        b.choices.removeAllViews()
        if (choices.size < 2) return
        for (j in choices) {
            val v = TextView(this)
            val rides = j.legs.filter { it.ride }
            v.text = Itineraries.clock(j.dep) + " → " + Itineraries.clock(j.arr) + "   " + Fmt.hm(j.seconds) + "   " +
                when (j.changes) { 0 -> getString(R.string.direct); 1 -> getString(R.string.change_1); else -> getString(R.string.changes_n, j.changes) } +
                (if (rides.isEmpty()) "" else "\n" + rides.joinToString(" · ") { Itineraries.kindLabel(this, it.kind, it.line) })
            v.textSize = 15f; v.setPadding(12, 14, 12, 14); v.setLineSpacing(4f, 1f)
            val chosen = f.result?.journey === j
            v.setBackgroundResource(if (chosen) R.drawable.btn_active else R.drawable.btn)
            v.setTextColor(ContextCompat.getColor(this, if (chosen) R.color.paper else R.color.ink))
            v.setOnClickListener { f.result = f.result?.let { r -> Itinerary(r.mode, r.from, r.to, j.dep, null, j) }; render() }
            val lp = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 8
            b.choices.addView(v, lp)
        }
    }

    private fun pickDate() {
        val c = Calendar.getInstance().apply { timeInMillis = f.depart }
        DatePickerDialog(this, { _, y, m, d -> c.set(y, m, d); f.depart = c.timeInMillis; f.result = null; render() },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime() {
        val c = Calendar.getInstance().apply { timeInMillis = f.depart }
        TimePickerDialog(this, { _, h, m -> c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0); f.depart = c.timeInMillis; f.result = null; render() },
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }

    private fun status(text: String?) { b.status.text = text ?: ""; b.status.visibility = if (text == null) View.GONE else View.VISIBLE }

    private fun compute() {
        if (computing) return
        val to = f.to ?: run { Toast.makeText(this, R.string.route_need_to, Toast.LENGTH_SHORT).show(); return }
        val from = f.from ?: here?.let { Stop(it, getString(R.string.my_position)) } ?: run { Toast.makeText(this, R.string.route_no_position, Toast.LENGTH_LONG).show(); return }
        if (mode != Mode.TRANSIT && !Segments.isReady(this)) {
            val pct = Segments.downloading(this)
            status(if (pct >= 0) getString(R.string.seg_still_downloading, pct) else getString(R.string.need_data_msg) + "\n" + getString(R.string.route_data_menu))
            return
        }
        computing = true; choices = emptyList(); f.result = null; render()
        status(getString(if (mode == Mode.TRANSIT) R.string.planning else R.string.routing))
        lifecycleScope.launch {
            val r = runCatching {
                if (mode == Mode.TRANSIT) {
                    val js = Planner.plan(this@RouteActivity, from.at, to.at, f.depart, 4)
                    Planner.takeNotice()?.let { Toast.makeText(this@RouteActivity, it, Toast.LENGTH_LONG).show() }
                    if (js.isEmpty()) throw Exception(getString(R.string.no_journey))
                    js.first().legs.last().let { l -> if (!l.ride && l.to.name.isBlank()) l.to.name = to.name }
                    for (j in js) j.legs.first().let { l -> if (!l.ride && l.from.name.isBlank()) l.from.name = from.name }
                    choices = js
                    Itinerary(mode, from, to, js.first().dep, null, js.first())
                } else {
                    val res = Router.route(this@RouteActivity, mode, from.at, to.at)
                    Itinerary(mode, from, to, f.depart, Trip.fromRoute(res, listOf(to)), null)
                }
            }
            computing = false
            r.onSuccess { f.result = it; status(null); render() }
            r.onFailure { e ->
                status(if (e is Planner.NoTimetable) getString(R.string.need_timetable_msg) else (e.message ?: getString(R.string.route_fail)))
                render()
            }
        }
    }

    private fun share(it: Itinerary) {
        val text = Itineraries.text(this, it)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, it.title)
        runCatching { startActivity(Intent.createChooser(send, null)) }
    }

    // ---- saved itineraries ----

    private fun renderSaved() {
        val all = Itineraries.list(this).filter { it.mode == mode }
        b.savedList.removeAllViews()
        b.savedInfo.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        for (itin in all) {
            val v = TextView(this)
            v.text = Itineraries.day(itin.depart) + " " + Itineraries.clock(itin.depart) + "   " + itin.title + "\n" + Itineraries.summary(this, itin)
            v.textSize = 15f; v.setTextColor(ContextCompat.getColor(this, R.color.ink)); v.setPadding(0, 14, 0, 14); v.setLineSpacing(4f, 1f)
            v.setOnClickListener { f.from = itin.from; f.to = itin.to; f.depart = itin.depart; f.result = itin; choices = emptyList(); render(); b.root.smoothScrollTo(0, 0) }
            v.setOnLongClickListener {
                AlertDialog.Builder(this).setMessage(getString(R.string.route_remove_q, itin.title))
                    .setPositiveButton(R.string.remove) { _, _ -> Itineraries.remove(this, itin); if (f.result?.file == itin.file) f.result = null; render(); renderSaved() }
                    .setNegativeButton(R.string.cancel, null).show(); true
            }
            b.savedList.addView(v)
        }
    }
}
