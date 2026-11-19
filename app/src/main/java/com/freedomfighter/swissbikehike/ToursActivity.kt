package com.freedomfighter.swissbikehike

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.freedomfighter.swissbikehike.databinding.ActivityToursBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The tours kept on the phone, in one place: lines imported from files (GPX, KML, KMZ, GeoJSON,
 * TCX, FIT), tracks recorded with REC, and the bike and foot itineraries kept from the planner.
 * A tap opens one on the map, where it can be started; « ⋯ » holds everything else.
 */
class ToursActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        /** The answer to the map: the tour's file (with EXTRA_REVERSE), or a kept itinerary's file. */
        const val EXTRA_FILE = "file"; const val EXTRA_REVERSE = "reverse"; const val EXTRA_ITINERARY = "itinerary"
        /** …or a signposted tour of Switzerland, by its place in the list. */
        const val EXTRA_SWISS = "swiss"
        const val EXTRA_LAT = "lat"; const val EXTRA_LON = "lon"
        const val ALL = ""
        private const val PAGE = 40

        /** `near`: where the person is (or looks at on the map), so that the signposted tours come nearest first. */
        fun intent(ctx: Context, mode: Mode, near: LatLng? = null): Intent {
            val i = Intent(ctx, ToursActivity::class.java).putExtra(EXTRA_MODE, mode.name)
            near?.let { i.putExtra(EXTRA_LAT, it.latitude).putExtra(EXTRA_LON, it.longitude) }
            return i
        }

        /** "3 tours added", "Already in your tours", "No track in that file": what an import did, in a sentence. */
        fun importText(ctx: Context, added: Int, known: Int): String = when {
            added > 0 -> ctx.resources.getQuantityString(R.plurals.tours_added, added, added)
            known > 0 -> ctx.getString(R.string.tours_known)
            else -> ctx.getString(R.string.no_track_in_file)
        }
    }

    private lateinit var b: ActivityToursBinding
    private lateinit var mode: Mode
    private var filter = ALL
    private var near: LatLng? = null
    /** Which list shows: the person's own tours, or the signposted tours of Switzerland. */
    private var swissTab = false
    private var land = SwissTours.WALK
    private var easyOnly = false
    private var span = 0      // 0 any, 1 short, 2 medium, 3 long
    private var level = 0     // 0 any, 1 easy, 2 medium, 3 difficult
    private var shown = PAGE
    private var pack: SwissTours.Pack? = null

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val kind = filter.ifBlank { Tours.kindOf(mode) }
        lifecycleScope.launch {
            var added = 0; var known = 0; var unread = 0
            withContext(Dispatchers.IO) {
                for (u in uris) {
                    val r = Tours.import(this@ToursActivity, u, kind)
                    if (r == null || r.all.isEmpty()) unread++ else { added += r.added.size; known += r.known.size }
                }
            }
            val text = importText(this@ToursActivity, added, known) +
                (if (unread > 0 && added + known > 0) "\n" + resources.getQuantityString(R.plurals.tours_unread, unread, unread) else "")
            Toast.makeText(this@ToursActivity, text, Toast.LENGTH_LONG).show()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityToursBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.pad(b.root)
        mode = Mode.of(intent.getStringExtra(EXTRA_MODE))
        filter = savedInstanceState?.getString("filter") ?: ALL
        if (intent.hasExtra(EXTRA_LAT)) near = LatLng(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0))
        val prefs = getSharedPreferences("tours", MODE_PRIVATE)
        // the first time, with nothing of one's own yet: what there is to walk around here
        swissTab = savedInstanceState?.getBoolean("swiss") ?: prefs.getBoolean("swiss", Tours.list(this).isEmpty())
        land = savedInstanceState?.getString("land") ?: if (mode == Mode.BIKE) SwissTours.BIKE else SwissTours.WALK
        b.tabMine.setOnClickListener { swissTab = false; prefs.edit().putBoolean("swiss", false).apply(); render() }
        b.tabSwiss.setOnClickListener { swissTab = true; prefs.edit().putBoolean("swiss", true).apply(); render() }
        b.sWalk.setOnClickListener { land = SwissTours.WALK; span = 0; shown = PAGE; renderSwiss() }
        b.sBike.setOnClickListener { land = SwissTours.BIKE; easyOnly = false; span = 0; shown = PAGE; renderSwiss() }
        b.sMtb.setOnClickListener { land = SwissTours.MTB; easyOnly = false; span = 0; shown = PAGE; renderSwiss() }
        b.sEasy.setOnClickListener { easyOnly = !easyOnly; if (easyOnly) land = SwissTours.WALK; shown = PAGE; renderSwiss() }
        for ((i, v) in listOf(b.d0, b.d1, b.d2, b.d3).withIndex()) v.setOnClickListener { span = i; shown = PAGE; renderSwiss() }
        for ((i, v) in listOf(b.l0, b.l1, b.l2, b.l3).withIndex()) v.setOnClickListener { level = i; shown = PAGE; renderSwiss() }
        b.sMore.setOnClickListener { shown += PAGE; renderSwiss() }
        b.sFilter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { shown = PAGE; renderSwiss() }
        })
        lifecycleScope.launch {
            pack = withContext(Dispatchers.IO) { runCatching { SwissTours.load(this@ToursActivity) }.getOrNull() }
            if (swissTab) renderSwiss()
        }
        b.btnImport.setOnClickListener { pick.launch(arrayOf("*/*")) }
        for ((v, k) in listOf(b.fAll to ALL, b.fWalk to Tours.WALK, b.fBike to Tours.BIKE, b.fMtb to Tours.MTB)) v.setOnClickListener { filter = k; render() }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); outState.putString("filter", filter); outState.putBoolean("swiss", swissTab); outState.putString("land", land)
    }

    private fun mark(v: TextView, on: Boolean) {
        v.setBackgroundResource(if (on) R.drawable.btn_active else R.drawable.btn)
        v.setTextColor(AppCompatResources.getColorStateList(this, if (on) R.drawable.btn_active_text else R.drawable.btn_text))
    }

    /** "52 min", "1 h 05". */
    private fun duration(minutes: Int): String =
        if (minutes < 60) getString(R.string.minutes_n, minutes) else getString(R.string.dur_h_min, minutes / 60, minutes % 60)

    private fun levelText(l: Int) = getString(when (l) { 1 -> R.string.level_1; 2 -> R.string.level_2; else -> R.string.level_3 }).lowercase(Locale.getDefault())

    private fun title(s: SwissTour) = s.name.ifBlank { s.from + " → " + s.to }

    // ---- the signposted tours of Switzerland ----

    private fun renderSwiss() {
        val p = pack
        mark(b.sWalk, land == SwissTours.WALK && !easyOnly); mark(b.sBike, land == SwissTours.BIKE); mark(b.sMtb, land == SwissTours.MTB); mark(b.sEasy, easyOnly)
        val walk = land == SwissTours.WALK
        // on foot the signposts give hours, by bike kilometres
        // …and a stage of a cycling route is about twice a mountain-bike one
        val (short, long) = if (land == SwissTours.BIKE) Pair(30, 50) else Pair(20, 40)
        b.d1.text = if (walk) getString(R.string.swiss_h_max, 2) else getString(R.string.swiss_km_max, short)
        b.d2.text = if (walk) getString(R.string.swiss_h_range, 2, 4) else getString(R.string.swiss_km_range, short, long)
        b.d3.text = if (walk) getString(R.string.swiss_h_min, 4) else getString(R.string.swiss_km_min, long)
        for ((i, v) in listOf(b.d0, b.d1, b.d2, b.d3).withIndex()) mark(v, span == i)
        for ((i, v) in listOf(b.l0, b.l1, b.l2, b.l3).withIndex()) mark(v, level == i)
        b.sList.removeAllViews()
        if (p == null) { b.sInfo.text = ""; b.sMore.visibility = View.GONE; return }
        b.sCredit.text = getString(R.string.swiss_credit, p.stamp.take(10))
        val words = SwissTours.fold(b.sFilter.text.toString()).split(' ').filter { it.isNotBlank() }
        val here = near
        fun away(s: SwissTour): Double = if (here == null) 0.0 else minOf(here.distanceTo(LatLng(s.lat, s.lon)), here.distanceTo(LatLng(s.lat2, s.lon2)))
        val found = p.tours.filter { s ->
            s.land == land && (!easyOnly || s.barrierFree) && (level == 0 || s.fit == level) && words.all { it in s.words } && when (span) {
                0 -> true
                1 -> if (walk) s.minutes <= 120 else s.metres <= short * 1000
                2 -> if (walk) s.minutes in 121..240 else s.metres > short * 1000 && s.metres <= long * 1000
                else -> if (walk) s.minutes > 240 else s.metres > long * 1000
            }
        }.map { Pair(it, away(it)) }.sortedWith(compareBy({ it.second }, { it.first.id }))
        b.sInfo.text = if (found.isEmpty()) getString(R.string.swiss_none)
            else resources.getQuantityString(if (here != null) R.plurals.swiss_count_near else R.plurals.swiss_count, found.size, found.size)
        b.sMore.visibility = if (found.size > shown) View.VISIBLE else View.GONE
        val dp = resources.displayMetrics.density
        for ((s, d) in found.take(shown)) {
            val head = title(s) + "  ·  " + s.nr + (if (s.of > 1) "  ·  " + getString(R.string.swiss_stage, s.stage, s.of) else "")
            val ends = if (s.loop) getString(R.string.swiss_loop, s.from) else s.from + " → " + s.to
            val figures = Fmt.km(s.metres.toDouble()) + " km" + (if (s.minutes > 0) "   " + duration(s.minutes) else "") + "   ↑ ${s.up} m   ↓ ${s.down} m"
            val notes = listOfNotNull(
                if (s.fit > 0) getString(R.string.swiss_fit, levelText(s.fit)) else null,
                if (s.tech > 0) getString(R.string.swiss_tech, levelText(s.tech)) else null,
                getString(when (s.kind) { 'n' -> R.string.swiss_kind_n; 'r' -> R.string.swiss_kind_r; 'h' -> R.string.swiss_easy; else -> R.string.swiss_kind_l }).lowercase(Locale.getDefault()),
                if (here != null) getString(R.string.swiss_away, Fmt.dist(d)) else null).joinToString("  ·  ")
            val text = SpannableString(head + "\n" + ends + "\n" + figures + "\n" + notes)
            text.setSpan(StyleSpan(Typeface.BOLD), 0, head.length, 0)
            text.setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.grey)), text.length - notes.length, text.length, 0)
            text.setSpan(android.text.style.RelativeSizeSpan(0.88f), text.length - notes.length, text.length, 0)
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
            val label = TextView(this).apply {
                this.text = text; textSize = 16f; setTextColor(ContextCompat.getColor(this@ToursActivity, R.color.ink))
                setPadding(0, (12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt()); setLineSpacing(3 * dp, 1f)
                setOnClickListener { openSwiss(s, false) }
            }
            val more = TextView(this, null, 0, R.style.Btn).apply {
                this.text = "⋯"; textSize = 24f; contentDescription = getString(R.string.tour_more)
                setOnClickListener { swissActions(s) }
            }
            line.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(more, LinearLayout.LayoutParams((56 * dp).toInt(), (48 * dp).toInt()))
            b.sList.addView(line)
        }
    }

    private fun openSwiss(s: SwissTour, reverse: Boolean) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SWISS, s.id).putExtra(EXTRA_REVERSE, reverse)); finish()
    }

    private fun swissActions(s: SwissTour) {
        val labels = ArrayList<String>(); val runs = ArrayList<() -> Unit>()
        labels.add(getString(R.string.tour_show)); runs.add { openSwiss(s, false) }
        if (!s.loop) { labels.add(getString(R.string.tour_reverse) + (if (s.back > 0) "  ·  " + duration(s.back) else "")); runs.add { openSwiss(s, true) } }
        labels.add(getString(R.string.swiss_keep)); runs.add { keepSwiss(s) }
        labels.add(getString(R.string.swiss_page)); runs.add {
            val lang = Locale.getDefault().language
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SwissTours.page(s, lang)))) }
        }
        AlertDialog.Builder(this).setTitle(title(s))
            .setItems(labels.toTypedArray()) { _, which -> runs[which]() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun keepSwiss(s: SwissTour) {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    val ends = if (s.loop) s.from else s.from + " → " + s.to
                    val name = if (s.name.isBlank() || s.name == ends) ends else s.name + ": " + ends
                    Tours.add(this@ToursActivity, Imported(name, s.tourKind, SwissTours.line(this@ToursActivity, s)), s.tourKind, Tours.SIGNPOSTED).second
                }.getOrNull()
            } ?: return@launch
            Toast.makeText(this@ToursActivity, if (added) R.string.tour_kept else R.string.tours_known, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- the person's own tours ----


    private class Row(val at: Long, val kind: String, val tour: Tour?, val itinerary: Itinerary?)

    private fun day(t: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    private fun render() {
        mark(b.tabMine, !swissTab); mark(b.tabSwiss, swissTab)
        b.mine.visibility = if (swissTab) View.GONE else View.VISIBLE
        b.swiss.visibility = if (swissTab) View.VISIBLE else View.GONE
        if (swissTab) { renderSwiss(); return }
        for ((v, k) in listOf(b.fAll to ALL, b.fWalk to Tours.WALK, b.fBike to Tours.BIKE, b.fMtb to Tours.MTB)) mark(v, filter == k)
        val rows = (Tours.list(this).map { Row(it.addedAt, it.kind, it, null) } +
            Itineraries.list(this).filter { it.trip != null && it.mode == mode && mode != Mode.TRANSIT }.map { Row(it.depart, Tours.kindOf(it.mode), null, it) })
            .sortedByDescending { it.at }
        val shown = rows.filter { filter == ALL || it.kind == filter }
        b.info.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        b.info.text = getString(if (rows.isEmpty()) R.string.tours_empty else R.string.tours_none_of_kind)
        b.list.removeAllViews()
        val dp = resources.displayMetrics.density
        for (r in shown) {
            val name = r.tour?.name ?: r.itinerary!!.title
            val dist = r.tour?.distanceM ?: r.itinerary!!.trip!!.distanceM
            val asc = r.tour?.ascendM ?: r.itinerary!!.trip!!.ascendM
            val desc = r.tour?.descendM ?: r.itinerary!!.trip!!.descendM
            val source = getString(when { r.itinerary != null -> R.string.tour_src_plan; r.tour!!.source == Tours.RECORDED -> R.string.tour_src_rec; r.tour.source == Tours.SIGNPOSTED -> R.string.tour_src_swiss; else -> R.string.tour_src_import })
            val figures = Fmt.km(dist.toDouble()) + " km" + (if (asc > 0 || desc > 0) "   ↑ $asc m   ↓ $desc m" else "")
            val text = SpannableString(name + "\n" + figures + "\n" + getString(Tours.kindLabel(r.kind)) + "  ·  " + source + " " + day(r.at))
            text.setSpan(StyleSpan(Typeface.BOLD), 0, name.length, 0)

            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
            val label = TextView(this).apply {
                this.text = text; textSize = 16f; setTextColor(ContextCompat.getColor(this@ToursActivity, R.color.ink))
                setPadding(0, (12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt()); setLineSpacing(3 * dp, 1f)
                setOnClickListener { open(r, reverse = false) }
            }
            val more = TextView(this, null, 0, R.style.Btn).apply {
                this.text = "⋯"; textSize = 24f; contentDescription = getString(R.string.tour_more)
                setOnClickListener { actions(r) }
            }
            line.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(more, LinearLayout.LayoutParams((56 * dp).toInt(), (48 * dp).toInt()))
            b.list.addView(line)
        }
    }

    private fun open(r: Row, reverse: Boolean) {
        val i = Intent()
        if (r.tour != null) i.putExtra(EXTRA_FILE, r.tour.file).putExtra(EXTRA_REVERSE, reverse) else i.putExtra(EXTRA_ITINERARY, r.itinerary!!.file)
        setResult(RESULT_OK, i); finish()
    }

    /** Everything one can do with a tour, in words. */
    private fun actions(r: Row) {
        val t = r.tour
        val labels = ArrayList<String>(); val runs = ArrayList<() -> Unit>()
        labels.add(getString(R.string.tour_show)); runs.add { open(r, false) }
        if (t != null) {
            labels.add(getString(R.string.tour_reverse)); runs.add { open(r, true) }
            labels.add(getString(R.string.tour_rename)); runs.add { rename(t) }
            labels.add(getString(R.string.tour_kind, getString(Tours.kindLabel(t.kind)))); runs.add { chooseKind(t) }
            labels.add(getString(R.string.tour_share)); runs.add { share(t) }
        }
        labels.add(getString(R.string.tour_delete)); runs.add { delete(r) }
        AlertDialog.Builder(this).setTitle(t?.name ?: r.itinerary!!.title)
            .setItems(labels.toTypedArray()) { _, which -> runs[which]() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun rename(t: Tour) {
        val dp = resources.displayMetrics.density
        val edit = EditText(this).apply { setText(t.name); setSelection(t.name.length); setSingleLine() }
        val box = LinearLayout(this).apply { setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0); addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(this).setTitle(R.string.tour_rename).setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotBlank()) { Tours.update(this, t.with(name = name)); render() }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun chooseKind(t: Tour) {
        val names = Tours.KINDS.map { getString(Tours.kindLabel(it)) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(t.name)
            .setSingleChoiceItems(names, Tours.KINDS.indexOf(t.kind)) { d, which -> Tours.update(this, t.with(kind = Tours.KINDS[which])); d.dismiss(); render() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** The GPX goes to Documents first, under the tour's name, then to the share sheet. */
    private fun share(t: Tour) {
        val bytes = runCatching { Tours.file(this, t).readBytes() }.getOrNull() ?: return
        val name = Tours.fileName(t)
        val uri = Documents.save(this, name, "application/gpx+xml", bytes) ?: return
        Toast.makeText(this, getString(R.string.saved, name), Toast.LENGTH_LONG).show()
        Documents.share(this, uri, "application/gpx+xml")
    }

    private fun delete(r: Row) {
        AlertDialog.Builder(this).setMessage(getString(R.string.tour_delete_q, r.tour?.name ?: r.itinerary!!.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (r.tour != null) Tours.remove(this, r.tour) else Itineraries.remove(this, r.itinerary!!)
                render()
            }.setNegativeButton(R.string.cancel, null).show()
    }
}
