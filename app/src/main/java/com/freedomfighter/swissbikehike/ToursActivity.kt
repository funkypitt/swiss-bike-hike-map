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
        const val ALL = ""

        fun intent(ctx: Context, mode: Mode) = Intent(ctx, ToursActivity::class.java).putExtra(EXTRA_MODE, mode.name)

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
        b.btnImport.setOnClickListener { pick.launch(arrayOf("*/*")) }
        for ((v, k) in listOf(b.fAll to ALL, b.fWalk to Tours.WALK, b.fBike to Tours.BIKE, b.fMtb to Tours.MTB)) v.setOnClickListener { filter = k; render() }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString("filter", filter) }

    private class Row(val at: Long, val kind: String, val tour: Tour?, val itinerary: Itinerary?)

    private fun day(t: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    private fun render() {
        for ((v, k) in listOf(b.fAll to ALL, b.fWalk to Tours.WALK, b.fBike to Tours.BIKE, b.fMtb to Tours.MTB)) {
            val on = filter == k
            v.setBackgroundResource(if (on) R.drawable.btn_active else R.drawable.btn)
            v.setTextColor(AppCompatResources.getColorStateList(this, if (on) R.drawable.btn_active_text else R.drawable.btn_text))
        }
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
            val source = getString(when { r.itinerary != null -> R.string.tour_src_plan; r.tour!!.source == Tours.RECORDED -> R.string.tour_src_rec; else -> R.string.tour_src_import })
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
