package com.freedomfighter.swissbikehike

import android.app.Activity
import android.app.PendingIntent
import android.os.Build
import android.widget.RemoteViews
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.graphics.drawable.toBitmap
import android.content.ClipDescription
import android.content.ClipboardManager
import android.provider.ContactsContract
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.freedomfighter.swissbikehike.databinding.ActivitySearchBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One search for everything, as in any map app: a single field whose answers mix the favourites,
 * the categories of places around (« café », « fountain »), public-transport stops (online, or
 * from the offline timetable), addresses and place names (GeoAdmin), the places of the offline
 * OpenStreetMap pack by name, and typed coordinates. With the person's own search.ch key, the
 * Search key (or the directory row) adds the directory: firms complete the places they name
 * (phone, address), people and other firms follow. A text that ends or starts with a town
 * (« ardentis lausanne ») is read as a name looked for in that town. The answers that hold every
 * typed word come first; what the servers merely find alike (the towns and stops called
 * Lausanne, for that text) comes last. An empty field lists the categories around and the
 * favourites (with import and export of place lists). Every pick ends as a pin on the map.
 *
 * Firms come from the commercial register (the firms pack, [Firms]): by their legal name, each
 * once — a firm that is also a place of OpenStreetMap, or an answer of the directory, is said by
 * that row. A person is reached through the phone's own contacts (the row under the favourites:
 * Android's picker hands over the one address chosen, no permission asked) or, in the build
 * without the directory's interface, on the website of search.ch: the address copied there is
 * offered on the way back.
 */
class SearchActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LAT = "lat"; const val EXTRA_LON = "lon"; const val EXTRA_LABEL = "label"
        const val EXTRA_KIND = "kind"; const val EXTRA_DETAIL = "detail"
        /** Where "around you" is: the position when the map has one, else the map's centre (always given, the fallback when nothing is around the position). */
        const val EXTRA_NEAR_LAT = "near_lat"; const val EXTRA_NEAR_LON = "near_lon"; const val EXTRA_NEAR_GPS = "near_gps"
        const val EXTRA_CENTRE_LAT = "centre_lat"; const val EXTRA_CENTRE_LON = "centre_lon"
        /** The map's mode: on the public-transport map, stops come first. */
        const val EXTRA_MODE = "mode"
        private const val NEARBY_M = 2000.0
        /** Set with RESULT_OK when the list is to be shown on the map: the places are in `results`. */
        const val EXTRA_RESULTS = "results"
        /** Open on the list of favourites. */
        const val EXTRA_FAVS = "favs"
        /** Sent by the browser tab's button, with the address of the page shown there. */
        private const val ACTION_WEB_ENTRY = "com.freedomfighter.swissbikehike.WEB_ENTRY"
        /** A place of a result list drawn on the map; `placeIndex` = its index in the places pack. */
        class Result(val label: String, val at: LatLng, val placeIndex: Int, val detail: String)
        /** The list handed to the map by SHOW ON THE MAP (kept in memory: an Intent is too small for it). */
        var results: List<Result>? = null
        var resultsTitle: String = ""

        /** The search, with "around" set to a point when there is one. */
        fun intent(ctx: android.content.Context, mode: Mode, near: LatLng?, gps: Boolean, centre: LatLng? = null): Intent {
            val i = Intent(ctx, SearchActivity::class.java).putExtra(EXTRA_MODE, mode.name)
            near?.let { i.putExtra(EXTRA_NEAR_LAT, it.latitude).putExtra(EXTRA_NEAR_LON, it.longitude).putExtra(EXTRA_NEAR_GPS, gps) }
            centre?.let { i.putExtra(EXTRA_CENTRE_LAT, it.latitude).putExtra(EXTRA_CENTRE_LON, it.longitude) }
            return i
        }
    }
    private class Hit(val label: String, val lat: Double, val lon: Double, val kind: String = Favourites.PLACE, var detail: String = "",
                      val place: Place? = null, val metres: Double = -1.0, var extraLine: String = "", var dir: Directory.Hit? = null,
                      val starred: Boolean = false, val firm: Firms.Hit? = null)

    /** One row of the list. */
    private sealed class Row {
        class Category(val cat: Places.Cat) : Row()
        object Back : Row()
        object Favs : Row()
        class Item(val hit: Hit) : Row()
        class FavItem(val fav: Fav) : Row()
        class Dir(val hit: Directory.Hit) : Row()
        object DirAsk : Row()
        /** The same question asked of the website of search.ch, in the browser. */
        object Web : Row()
        /** The address of one of the phone's contacts. */
        object Contact : Row()
        class Info(val text: String) : Row()
    }

    private lateinit var b: ActivitySearchBinding
    private val rows = ArrayList<Row>()
    private var job: Job? = null
    private lateinit var adapter: ArrayAdapter<String>
    private var mode = Mode.WALK
    private var near = LatLng(46.52, 6.63)
    private var nearIsGps = false
    private var centre = near
    /** The category whose places around are listed; null otherwise. */
    private var category: Places.Cat? = null
    /** The favourites are listed (★ row), with import and export. */
    private var favView = false
    private val categories = Places.Cat.entries
    /** The text whose answers are on screen, so a late answer for an older text is dropped. */
    private var shownQuery = ""
    /** The same text read as « what, where », when it names a town: the directory is asked that way. */
    private var shownWhere: Places.Where? = null

    private val hits: List<Hit> get() = rows.mapNotNull { (it as? Row.Item)?.hit }

    /** When the browser was opened on search.ch (0: it was not): an address copied since then is offered on the way back. */
    private var leftForWeb = 0L
    private var webName = ""

    /** Android's own picker of a contact's postal address: the app is handed the one address chosen, and needs no access to the contacts. */
    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@registerForActivityResult
        if (r.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        var name = ""; var street = ""; var zip = ""; var city = ""; var whole = ""
        runCatching {
            contentResolver.query(uri, arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.StructuredPostal.STREET, ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE, ContactsContract.CommonDataKinds.StructuredPostal.CITY, ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0).orEmpty(); street = c.getString(1).orEmpty().lines().lastOrNull { it.isNotBlank() }.orEmpty().trim()
                    zip = c.getString(2).orEmpty().trim(); city = c.getString(3).orEmpty().trim(); whole = c.getString(4).orEmpty()
                }
            }
        }
        val known = if (city.isNotBlank() || zip.isNotBlank()) Firms.Copied(name, street, zip, city) else Firms.copiedAddress(whole)?.let { Firms.Copied(name, it.street, it.zip, it.city) }
        if (known == null) { Toast.makeText(this, R.string.contact_no_address, Toast.LENGTH_LONG).show(); return@registerForActivityResult }
        pick(Directory.Hit(name.ifBlank { known.address }, known.street, known.zip, known.city, "", person = true))
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { Favourites.read(it) } ?: emptyList() }.getOrDefault(emptyList())
            }
            if (found.isEmpty()) { Toast.makeText(this@SearchActivity, R.string.fav_none_in_file, Toast.LENGTH_LONG).show(); return@launch }
            val n = Favourites.addAll(this@SearchActivity, found)
            Toast.makeText(this@SearchActivity, getString(R.string.fav_imported, n), Toast.LENGTH_LONG).show()
            showFavs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.pad(b.root)
        mode = Mode.of(intent.getStringExtra(EXTRA_MODE))
        if (intent.hasExtra(EXTRA_NEAR_LAT)) {
            near = LatLng(intent.getDoubleExtra(EXTRA_NEAR_LAT, near.latitude), intent.getDoubleExtra(EXTRA_NEAR_LON, near.longitude))
            nearIsGps = intent.getBooleanExtra(EXTRA_NEAR_GPS, false)
        }
        centre = if (intent.hasExtra(EXTRA_CENTRE_LAT)) LatLng(intent.getDoubleExtra(EXTRA_CENTRE_LAT, near.latitude), intent.getDoubleExtra(EXTRA_CENTRE_LON, near.longitude)) else near
        // the first name search folds every name of the pack: done now, off the main thread, so the first typed word answers at once
        PlacesPack.open(this)?.let { pack -> lifecycleScope.launch(Dispatchers.Default) { pack.warm() } }
        adapter = ArrayAdapter<String>(this, R.layout.item_search, R.id.text)
        b.list.adapter = adapter
        b.list.setOnItemClickListener { _, _, pos, _ -> onRow(pos) }
        b.list.setOnItemLongClickListener { _, _, pos, _ ->
            when (val r = rows.getOrNull(pos)) {
                is Row.FavItem -> {
                    AlertDialog.Builder(this).setMessage(getString(R.string.fav_remove_q, r.fav.name))
                        .setPositiveButton(R.string.remove) { _, _ -> Favourites.remove(this, r.fav); showFavs() }
                        .setNegativeButton(R.string.cancel, null).show()
                    true
                }
                is Row.Item -> if (r.hit.place != null) { showPlace(r.hit); true } else false
                else -> false
            }
        }
        b.btnImport.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        b.btnExport.setOnClickListener { exportFavs() }
        b.btnShowAll.setOnClickListener { showAllOnMap() }
        b.query.hint = getString(R.string.search_hint)
        b.query.doAfterTextChanged { text ->
            val q = text?.toString()?.trim() ?: ""
            job?.cancel()
            if (favView) { showFavs(); return@doAfterTextChanged }
            category = null
            if (q.length < 2) { showHome(); return@doAfterTextChanged }
            val typed = Favourites.parseCoords(q)
            if (typed != null) {
                shownQuery = q; shownWhere = null
                setRows(listOf(Row.Item(Hit(Favourites.coordsName(typed.latitude, typed.longitude), typed.latitude, typed.longitude, Favourites.COORDS))))
                return@doAfterTextChanged
            }
            job = lifecycleScope.launch { delay(300); searchAll(q) }
        }
        b.query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH || favView) return@setOnEditorActionListener false
            if (Directory.enabled(this)) { mergeDirectory(); true } else false
        }
        if (intent.getBooleanExtra(EXTRA_FAVS, false)) showFavs() else showHome()
        b.query.requestFocus()
    }

    // ---- the list ----

    private fun text(r: Row): String = when (r) {
        is Row.Category -> r.cat.glyph + "  " + getString(r.cat.label)
        Row.Back -> getString(if (favView) R.string.search_back else R.string.back_categories)
        Row.Favs -> getString(R.string.search_favs_row, Favourites.list(this).size)
        is Row.Item -> row(r.hit)
        is Row.FavItem -> listOf(glyph(r.fav.kind) + " " + r.fav.name, r.fav.detail).filter { it.isNotBlank() }.joinToString("\n")
        is Row.Dir -> (if (r.hit.person) "☺  " else "▣  ") + listOf(r.hit.name, r.hit.address, r.hit.phone).filter { it.isNotBlank() }.joinToString("\n")
        Row.DirAsk -> shownWhere?.let { getString(R.string.search_directory_row_in, it.what, it.town.name) }
            ?: getString(R.string.search_directory_row, b.query.text?.toString()?.trim() ?: "")
        Row.Web -> shownWhere?.let { getString(R.string.search_web_row_in, it.what, it.town.name) }
            ?: getString(R.string.search_web_row, b.query.text?.toString()?.trim() ?: "")
        Row.Contact -> getString(R.string.search_contact_row)
        is Row.Info -> r.text
    }

    private fun setRows(list: List<Row>) {
        rows.clear(); rows.addAll(list)
        adapter.setNotifyOnChange(false)
        adapter.clear(); adapter.addAll(rows.map { text(it) })
        adapter.notifyDataSetChanged()
        updateShowAll()
    }

    /** SHOW ON THE MAP (N) under the field, when a list holds several places of the pack. */
    private fun updateShowAll() {
        val n = hits.count { it.place != null }
        b.btnShowAll.visibility = if (n >= 2 && !favView) View.VISIBLE else View.GONE
        if (n >= 2) b.btnShowAll.text = getString(R.string.results_show, n)
    }

    /** Back to the map with every place of the list drawn there: a tap on one opens its card. */
    private fun showAllOnMap() {
        val list = hits.filter { it.place != null }
        if (list.isEmpty()) return
        results = list.map { h -> Result(h.label, LatLng(h.lat, h.lon), h.place!!.index, h.detail) }
        resultsTitle = category?.let { getString(it.label) } ?: (b.query.text?.toString()?.trim() ?: "")
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULTS, true))
        finish()
    }

    /** The empty field: the favourites row, then the categories of places around. */
    private fun showHome() {
        favView = false; category = null; shownQuery = ""; shownWhere = null
        b.favRow.visibility = View.GONE
        b.credit.visibility = View.VISIBLE
        b.credit.text = credit()
        val out = ArrayList<Row>()
        out.add(Row.Favs)
        out.add(Row.Contact)
        if (PlacesPack.isReady(this)) categories.forEach { out.add(Row.Category(it)) }
        else out.add(Row.Info(getString(R.string.places_pack_missing)))
        setRows(out)
    }

    /** Where the answers come from, under the field. */
    private fun credit(): String {
        val parts = ArrayList<String>()
        if (PlacesPack.isReady(this)) parts.add(PlacesPack.CREDIT + " · " + getString(if (nearIsGps) R.string.around_you else R.string.around_map))
        parts.add(if (Planner.online(this)) TransitOnline.CREDIT else if (Timetable.isReady(this)) getString(R.string.offline_timetable) else getString(R.string.timetable_missing))
        if (FirmsPack.isReady(this)) parts.add(getString(R.string.firms_credit))
        if (Directory.enabled(this)) parts.add(Directory.CREDIT + " (" + getString(R.string.directory_press_search) + ")")
        else if (DirectoryApi.AVAILABLE) parts.add(getString(R.string.directory_need_key))
        return parts.joinToString("\n")
    }

    private fun finishWith(h: Hit) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_LAT, h.lat).putExtra(EXTRA_LON, h.lon).putExtra(EXTRA_LABEL, h.label)
            .putExtra(EXTRA_KIND, h.kind).putExtra(EXTRA_DETAIL, h.detail))
        finish()
    }

    private fun onRow(pos: Int) {
        when (val r = rows.getOrNull(pos) ?: return) {
            is Row.Category -> showNearby(r.cat)
            Row.Back -> if (favView) { b.query.setText(""); showHome() } else { category = null; showHome() }
            Row.Favs -> { favView = true; b.query.setText(""); showFavs() }
            is Row.Item -> if (r.hit.place != null) showPlace(r.hit) else if (r.hit.firm != null) showFirm(r.hit) else finishWith(r.hit)
            is Row.FavItem -> finishWith(Hit(r.fav.name, r.fav.lat, r.fav.lon, r.fav.kind, r.fav.detail))
            is Row.Dir -> showDirectory(r.hit)
            Row.DirAsk -> mergeDirectory()
            Row.Web -> openWeb()
            Row.Contact -> runCatching { pickContact.launch(Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_TYPE)) }
                .onFailure { Toast.makeText(this, R.string.contact_no_app, Toast.LENGTH_LONG).show() }
            is Row.Info -> {}
        }
    }

    // ---- places around, by category ----

    /** The places of one category around the point, nearest first, under a row that goes back. */
    private fun showNearby(cat: Places.Cat) {
        category = cat
        val pack = PlacesPack.open(this) ?: return
        lifecycleScope.launch {
            setRows(listOf(Row.Back, Row.Info("…")))
            val found = withContext(Dispatchers.Default) { nearbyOf(pack, setOf(cat), 40) }
            if (category != cat) return@launch
            setRows(listOf<Row>(Row.Back) + found.map { Row.Item(hitOf(it)) })
            b.credit.text = PlacesPack.CREDIT + " · " + getString(if (nearIsGps) R.string.around_you else R.string.around_map) + " · " + getString(if (DirectoryApi.AVAILABLE) R.string.place_directory_more else R.string.place_card_more)
        }
    }

    /** Around the position; when nothing is within reach of it (abroad, no fix yet), around the map's centre instead. */
    private fun nearbyOf(pack: Places, cats: Set<Places.Cat>, limit: Int): List<Places.Found> {
        val atPos = pack.nearby(near.latitude, near.longitude, NEARBY_M, cats, limit)
        if (atPos.isNotEmpty() || !nearIsGps || centre == near) return atPos
        nearIsGps = false; near = centre
        return pack.nearby(near.latitude, near.longitude, NEARBY_M, cats, limit)
    }

    private fun hitOf(f: Places.Found, metres: Double = f.metres): Hit {
        val p = f.place
        val lines = Places.describe(this, p)
        val detail = (listOf(Places.categoryText(this, p)) + lines).joinToString(" · ")
        return Hit(p.label(this), p.at.latitude, p.at.longitude, Favourites.POI, detail, p, metres)
    }

    /** A place of the pack on two lines (glyph, name, distance; category and details); a stop or an address on one. */
    private fun row(h: Hit): String {
        h.firm?.let { f -> return "▣  " + h.label + (if (h.metres >= 0 && f.exact) "   " + Fmt.dist(h.metres) else "") + "\n" + f.address }
        val p = h.place ?: return when {
            h.starred -> "★  "
            h.kind == Favourites.STOP -> "◉  "
            h.kind == Favourites.COORDS -> "✚  "
            else -> "●  "
        } + h.label + (if (h.metres >= 0) "   " + Fmt.dist(h.metres) else "")
        val head = p.cat.glyph + "  " + h.label + (if (h.metres >= 0) "   " + Fmt.dist(h.metres) else "")
        // open or closed now, when the hours can be read; else the hours as written
        val status = PlaceCard.statusLine(this, p.extras["oh"])
        val desc = Places.describe(this, p).filter { status == null || it != p.extras["oh"] }
        val lines = (if (h.label == getString(p.cat.label)) emptyList() else listOf(Places.categoryText(this, p))) + listOfNotNull(status) +
            desc.take(if (status != null) 1 else 2) + (if (h.extraLine.isNotBlank()) listOf(h.extraLine) else emptyList())
        return if (lines.isEmpty()) head else head + "\n" + lines.joinToString(" · ")
    }

    // ---- everything at once ----

    /** The categories whose label holds the text (or has a word that starts with it). */
    private fun catsOf(fq: String): Set<Places.Cat> =
        categories.filter { c -> val l = Transit.fold(getString(c.label)); l.contains(fq) || (fq.length >= 4 && l.split(Regex("[ ,]+")).any { it.startsWith(fq) }) }.toSet()

    /** The kinds of shop whose name holds the text (« books », « fleuriste »). */
    private fun kindsOf(fq: String): Set<String> =
        Places.SHOP_KINDS.filter { (_, res) -> Transit.fold(getString(res)).let { it.contains(fq) || (fq.length >= 4 && it.split(' ').any { w -> w.startsWith(fq) }) } }.keys

    /**
     * A typed text: the favourites that hold it, the categories whose label matches (their places
     * around), then what holds every typed word (stops, GeoAdmin's towns, addresses and peaks,
     * places of the pack by name), nearest first. A text that names a town beside other words
     * (« ardentis lausanne », « hotel bern ») is also read as those words looked for in that town:
     * the places of that name, category or kind around its centre, nearest to it first. The
     * answers GeoAdmin and the timetable give without the typed words in them come last. The
     * directory is offered when a key is set: before those when nothing bears the name, else last.
     */
    private suspend fun searchAll(q: String) {
        val pack = PlacesPack.open(this)
        val fq = Transit.fold(q)
        val words = Places.words(q)
        shownQuery = q; shownWhere = null
        b.credit.text = credit()
        setRows(listOf(Row.Info("…")))
        val favs = Favourites.list(this).filter { Transit.fold(it.name).contains(fq) || Transit.fold(it.detail).contains(fq) }.take(5)
            .map { Hit(it.name, it.lat, it.lon, it.kind, it.detail, starred = true) }
        val cats = catsOf(fq)
        // GeoAdmin and the stops are asked while the pack is searched
        val geoJob = lifecycleScope.async { runCatching { searchPlaces(q, limit = if (cats.isEmpty()) 12 else 6) } }
        val stopJob = lifecycleScope.async { runCatching { Planner.searchStops(this@SearchActivity, q) }.getOrDefault(emptyList()) }
        fun away(lat: Double, lon: Double) = Places.distance(near.latitude, near.longitude, lat, lon)
        fun away(f: Places.Found) = away(f.place.at.latitude, f.place.at.longitude)
        val catHits = ArrayList<Hit>(); val nameHits = ArrayList<Hit>(); val tagHits = ArrayList<Hit>()
        val townNames = ArrayList<Hit>(); val townAround = ArrayList<Hit>()
        var where: Places.Where? = null
        if (pack != null) withContext(Dispatchers.Default) {
            if (cats.isNotEmpty()) catHits.addAll(nearbyOf(pack, cats, 12).map { hitOf(it) })
            // a cuisine or a kind of shop around (« pizza », « sushi », « books »)
            tagHits.addAll(pack.nearbyTagged(near.latitude, near.longitude, q, kindsOf(fq)).map { hitOf(it) })
            nameHits.addAll(pack.search(q, near.latitude, near.longitude, 15).map { hitOf(it) })
            where = pack.where(q)
            where?.let { w ->
                val t = w.town.at
                townNames.addAll(pack.search(w.what, t.latitude, t.longitude, 15, Places.TOWN_M).map { hitOf(it, away(it)) })
                val townCats = catsOf(w.what)
                if (townCats.isNotEmpty()) townAround.addAll(pack.nearby(t.latitude, t.longitude, NEARBY_M, townCats, 12, Places.TOWN_M).map { hitOf(it, away(it)) })
                townAround.addAll(pack.nearbyTagged(t.latitude, t.longitude, w.what, kindsOf(w.what)).map { hitOf(it, away(it)) })
            }
        }
        // the firms of the register: every typed word in the name (or the town); around the town named, else around here
        val firmsPack = FirmsPack.open(this)
        val firmHits = if (firmsPack == null) emptyList() else withContext(Dispatchers.Default) {
            val ref = where?.town?.at ?: near
            firmsPack.search(q, ref.latitude, ref.longitude, 12).map { f -> Hit(f.name, f.lat, f.lon, Favourites.FIRM, f.address, metres = away(f.lat, f.lon), firm = f) }
        }
        val geo = geoJob.await()
        val allStops = stopJob.await().take(8).map { Hit(it.name, it.at.latitude, it.at.longitude, Favourites.STOP, it.id, metres = away(it.at.latitude, it.at.longitude)) }
        if (shownQuery != q) return
        shownWhere = where
        // what holds every typed word is an answer; the rest is what a server found alike
        fun holds(h: Hit) = Transit.fold(h.label).let { l -> words.all { l.contains(it) } }
        val (stops, looseStops) = allStops.partition { holds(it) }
        // a stop named like a GeoAdmin answer (« Lausanne, gare ») is said once, as a stop
        val (geoHits, looseGeo) = geo.getOrDefault(emptyList()).filter { g -> allStops.none { Transit.fold(it.label) == Transit.fold(g.label) } }
            .map { g -> Hit(g.label, g.lat, g.lon, g.kind, g.detail, metres = away(g.lat, g.lon)) }.partition { holds(it) }
        // stops, addresses and places by name together, nearest first (the public-transport map: stops first)
        val sure = if (mode == Mode.TRANSIT) stops.sortedBy { it.metres } + (geoHits + nameHits).sortedBy { it.metres }
            else (stops + geoHits + nameHits).sortedBy { it.metres }
        // of the look-alikes, those that hold none of the typed words (« Luzein » for « ardentis lausanne ») say nothing once there is an answer
        val answered = favs.isNotEmpty() || catHits.isNotEmpty() || tagHits.isNotEmpty() || stops.isNotEmpty() || geoHits.isNotEmpty() || nameHits.isNotEmpty() || townNames.isNotEmpty() || townAround.isNotEmpty()
        val loose = (if (mode == Mode.TRANSIT) looseStops + looseGeo else looseGeo + looseStops)
            .filter { h -> !answered || Transit.fold(h.label).let { l -> words.any { it.length >= 3 && l.contains(it) } } }
        // each place once, where it first comes
        val seen = HashSet<Int>()
        val named = (favs + catHits + tagHits + sure + townNames + townAround).filter { h -> h.place == null || seen.add(h.place.index) }
        // a firm that is already there as a place of OpenStreetMap is said by that row
        val firms = firmHits.filter { f -> named.none { h -> sameFirm(h, f.firm!!) } }
        val found = named + firms + loose.filter { h -> h.place == null || seen.add(h.place.index) }
        val out = ArrayList<Row>(found.map { Row.Item(it) })
        if (found.isEmpty() && geo.isFailure) out.add(Row.Info(getString(R.string.search_no_answer)))
        // the directory: before the look-alikes when no place bears the name, else last
        if (q.length >= 3) {
            // with a key of the directory: its answers join the list; without one (or in the build without its interface): the same question on its website
            val ask = if (Directory.enabled(this)) Row.DirAsk else Row.Web
            val bears = nameHits.isNotEmpty() || townNames.isNotEmpty() || stops.isNotEmpty() || geoHits.isNotEmpty() || firms.isNotEmpty()
            if (bears) out.add(ask) else out.add(out.size - loose.size, ask)
        }
        setRows(out)
    }

    /** Is this row (a place of OpenStreetMap, a favourite…) the firm of the register? Same name at the same door, or by what both say of the address. */
    private fun sameFirm(h: Hit, f: Firms.Hit): Boolean {
        val p = h.place
        val street = p?.extras?.get("addr") ?: ""; val zip = p?.extras?.get("pc") ?: ""; val city = p?.extras?.get("city") ?: ""
        val name = p?.name?.ifBlank { h.label } ?: h.label
        if (f.exact && Places.distance(h.lat, h.lon, f.lat, f.lon) <= Firms.SAME_PLACE_M) return Firms.same(name, "", "", f.city, f.name, "", "", f.city)
        return Firms.same(name, street, zip, city, f.name, f.street, f.zip, f.city)
    }

    /** The card of a firm of the register: ON THE MAP puts it there (its address is looked up first when the pack only knows its postcode). */
    private fun showFirm(h: Hit) {
        val f = h.firm ?: return
        val e = Directory.Hit(f.name, f.street, f.zip, f.city, "")
        if (!f.exact) { showDirectory(e); return }
        PlaceCard.show(this, PlaceInfo.of(e, LatLng(f.lat, f.lon)), if (nearIsGps) near else null, R.string.place_on_map to { finishWith(h) },
            favourite = { Fav(h.label, h.lat, h.lon, h.kind, h.detail) })
    }

    // ---- a person on the website of search.ch (the build without the directory's interface) ----

    /**
     * Opens search.ch on the typed name, in a tab of the browser laid over this screen. The tab
     * carries one button of this app (a bar under the page, and a pin beside the address): the
     * browser answers it with the address of the page shown, and an entry's page names its town,
     * street and name there, so nothing has to be copied. A browser without such tabs opens the
     * page as usual: an address copied there is then offered on the way back.
     */
    private fun openWeb() {
        val q = b.query.text?.toString()?.trim() ?: ""
        val w = shownWhere
        val url = "https://search.ch/tel/?was=" + URLEncoder.encode(w?.what ?: q, "UTF-8") + (if (w != null) "&wo=" + URLEncoder.encode(w.town.name, "UTF-8") else "")
        if (openTab(url)) {
            leftForWeb = System.currentTimeMillis(); webName = w?.what ?: q
            Toast.makeText(this, R.string.web_copy_hint, Toast.LENGTH_LONG).show()
        }
    }

    private fun openTab(url: String): Boolean {
        val back = PendingIntent.getActivity(this, 1,
            Intent(this, SearchActivity::class.java).setAction(ACTION_WEB_ENTRY).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
        val dp = resources.displayMetrics.density
        val pin = AppCompatResources.getDrawable(this, R.drawable.pin)!!.toBitmap((18 * dp).toInt(), (24 * dp).toInt())
        val tab = CustomTabsIntent.Builder().setShowTitle(true)
            // the browser draws the bar in the phone's language: the words are given in the app's own
            .setSecondaryToolbarViews(RemoteViews(packageName, R.layout.web_bar).apply { setTextViewText(R.id.webUse, getString(R.string.web_use)) }, intArrayOf(R.id.webUse), back)
            .setActionButton(pin, getString(R.string.web_use), back, false)
            .build()
        return runCatching { tab.launchUrl(this, Uri.parse(url)) }.isSuccess
    }

    /** The tab's button was pressed: the entry shown there goes on the map; on any other page the tab comes back with a word of help. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != ACTION_WEB_ENTRY) return
        val url = intent.dataString ?: return
        val e = Firms.webEntry(url)
        if (e == null) {
            Toast.makeText(this, R.string.web_open_entry, Toast.LENGTH_LONG).show()
            if (url.startsWith("https://")) openTab(url)
            return
        }
        leftForWeb = 0L
        lifecycleScope.launch {
            // the map service knows the address as it is written, postcode included
            val place = (if (e.street.isNotBlank()) geocode("${e.street} ${e.city}", "address") else null)
                ?: geocode(e.city, "zipcode") ?: geocode(e.city, "gg25")
            if (place == null) { Toast.makeText(this@SearchActivity, R.string.address_not_found, Toast.LENGTH_SHORT).show(); return@launch }
            finishWith(Hit(e.name, place.lat, place.lon, Favourites.PERSON, if (e.street.isNotBlank()) place.label else e.city))
        }
    }

    /**
     * Back from the browser: Android lets an app read the clipboard only while it has the focus,
     * and this one looks only after it sent the person to search.ch, only at a text copied since
     * then (the clip's own date is read first, which shows nothing to the person), and asks before using it.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || leftForWeb == 0L) return
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val d = runCatching { cm.primaryClipDescription }.getOrNull() ?: return
        if (d.timestamp < leftForWeb || !(d.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || d.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))) return
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull() ?: return
        leftForWeb = 0L
        val a = Firms.copiedAddress(text) ?: return
        AlertDialog.Builder(this).setTitle(R.string.copied_use_q).setMessage(a.address)
            .setPositiveButton(R.string.copied_use) { _, _ -> pick(Directory.Hit(a.name.ifBlank { webName.ifBlank { a.address } }, a.street, a.zip, a.city, "", person = true)) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** The card of a place of the pack (hours, phone, address…): PIN puts it on the map. */
    private fun showPlace(h: Hit) {
        val p = h.place ?: return
        val info = PlaceInfo.of(this, p)
        h.dir?.let { e ->
            if (info.phone.isNullOrBlank()) info.phone = e.phone.ifBlank { null }
            if (info.street.isBlank()) { info.street = e.street; info.zip = e.zip; info.city = e.city.ifBlank { info.city } }
        }
        PlaceCard.show(this, info, if (nearIsGps) near else null, R.string.place_pin to { finishWith(h) },
            favourite = { Fav(h.label, h.lat, h.lon, h.kind, h.detail) })
    }

    /** The card of a directory entry (person or business): ON THE MAP places it through GeoAdmin. */
    private fun showDirectory(e: Directory.Hit) {
        PlaceCard.show(this, PlaceInfo.of(e), null, R.string.place_on_map to { pick(e) },
            favourite = { locate(e)?.let { Fav(it.label, it.lat, it.lon, it.kind, it.detail) } })
    }

    /** Adds the directory's phone and address to a hit of the pack, and refreshes its row. */
    private fun enrich(h: Hit, e: Directory.Hit) {
        h.dir = e
        h.extraLine = listOf(e.phone, e.address).filter { it.isNotBlank() }.joinToString(" · ")
        if (h.extraLine.isNotBlank() && !h.detail.contains(e.phone)) h.detail = h.detail + " · " + h.extraLine
    }

    /**
     * Search pressed with a key (or the last row): the directory's answers for the typed text join
     * the list. A firm that names a place already listed (same name, same town when both say it)
     * completes that row with its phone and address; people and the other firms take the place of
     * the directory row, placed through GeoAdmin when picked. A text that names a town is asked as
     * the name in that town.
     */
    private fun mergeDirectory() {
        val q = b.query.text?.toString()?.trim() ?: ""
        if (q.length < 3) return
        lifecycleScope.launch {
            job?.join()
            if (shownQuery != q) searchAll(q)
            val asking = rows.indexOfFirst { it === Row.DirAsk }
            if (asking >= 0) { rows[asking] = Row.Info("▣  …"); setRows(rows.toList()) }
            val w = shownWhere
            val found = withContext(Dispatchers.IO) { runCatching { if (w != null) Directory.search(this@SearchActivity, w.what, w.town.name) else Directory.search(this@SearchActivity, q) } }
            if (shownQuery != q) return@launch
            val at = rows.indexOfFirst { it is Row.Info && it.text == "▣  …" }.let { if (it < 0) rows.size else it }
            rows.removeAll { it is Row.Info && it.text == "▣  …" }
            found.onFailure { e -> setRows(rows.toList()); Toast.makeText(this@SearchActivity, (e.message ?: "no answer").take(120), Toast.LENGTH_LONG).show() }
            found.onSuccess { list ->
                val firms = list.filter { !it.person }
                val rest = ArrayList<Directory.Hit>()
                val hits = hits
                for (e in firms) {
                    val fe = Transit.fold(e.name)
                    val match = hits.firstOrNull { h ->
                        val p = h.place ?: return@firstOrNull false
                        val fp = Transit.fold(p.name)
                        fp.isNotBlank() && (fp.contains(fe) || fe.contains(fp)) && (p.extras["city"].isNullOrBlank() || e.city.isBlank() || Transit.fold(p.extras["city"]!!) == Transit.fold(e.city))
                    }
                    if (match != null) { if (match.extraLine.isBlank()) enrich(match, e) } else rest.add(e)
                }
                val people = list.filter { it.person }
                // the directory's entry carries the phone: it is the one kept when the register has the firm too
                rows.removeAll { r -> (r as? Row.Item)?.hit?.firm?.let { f -> firms.any { e -> Firms.same(e.name, e.street, e.zip, e.city, f.name, f.street, f.zip, f.city) } } == true }
                rows.removeAll { it === Row.DirAsk || it is Row.Dir }
                rows.addAll(minOf(at, rows.size), rest.map { Row.Dir(it) } + people.map { Row.Dir(it) })
                setRows(rows.toList())
                if (list.isEmpty()) Toast.makeText(this@SearchActivity, R.string.directory_nobody, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- favourites ----

    /** The favourites (filtered by the field), with import and export. */
    private fun showFavs() {
        favView = true
        b.favRow.visibility = View.VISIBLE
        b.credit.visibility = View.GONE
        val q = Transit.fold(b.query.text?.toString()?.trim() ?: "")
        val all = Favourites.list(this)
        val out = ArrayList<Row>(); out.add(Row.Back)
        if (all.isEmpty()) out.add(Row.Info(getString(R.string.fav_empty)))
        else all.filter { q.isEmpty() || Transit.fold(it.name).contains(q) || Transit.fold(it.detail).contains(q) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { out.add(Row.FavItem(it)) }
        setRows(out)
    }

    private fun glyph(kind: String) = when (kind) { Favourites.PERSON -> "☺"; Favourites.FIRM -> "▣"; Favourites.COORDS -> "✚"; Favourites.STOP -> "◉"; Favourites.POI -> "◆"; else -> "●" }

    private fun exportFavs() {
        val all = Favourites.list(this)
        if (all.isEmpty()) { Toast.makeText(this, R.string.fav_empty, Toast.LENGTH_LONG).show(); return }
        val name = "favourites-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".gpx"
        val uri = Documents.save(this, name, "application/gpx+xml", Favourites.gpx(all).toByteArray())
        if (uri == null) { Toast.makeText(this, R.string.fav_write_fail, Toast.LENGTH_LONG).show(); return }
        Toast.makeText(this, getString(R.string.fav_exported, name), Toast.LENGTH_LONG).show()
        Documents.share(this, uri, "application/gpx+xml")
    }

    // ---- people and businesses ----

    /** A directory hit has an address, not a position: GeoAdmin turns it into one (the street first, the town as a fallback). */
    private fun pick(h: Directory.Hit) {
        lifecycleScope.launch {
            val hit = locate(h)
            if (hit == null) { Toast.makeText(this@SearchActivity, R.string.address_not_found, Toast.LENGTH_SHORT).show(); return@launch }
            finishWith(hit)
        }
    }

    /** The entry with a position, its address and phone kept as its detail; null when the address cannot be placed. */
    private suspend fun locate(h: Directory.Hit): Hit? {
        if (h.city.isBlank() && h.street.isBlank()) return null
        val place = (if (h.street.isNotBlank()) geocode("${h.street} ${h.zip} ${h.city}", "address") else null)
            ?: geocode("${h.zip} ${h.city}", "zipcode") ?: return null
        return Hit(h.name, place.lat, place.lon, if (h.person) Favourites.PERSON else Favourites.FIRM,
            listOf(h.address, h.phone).filter { it.isNotBlank() }.joinToString(" · "))
    }

    // ---- GeoAdmin ----


    private suspend fun geocode(text: String, origins: String): Hit? =
        searchPlaces(text, origins, 1).firstOrNull()

    private suspend fun searchPlaces(q: String, origins: String? = null, limit: Int = 20): List<Hit> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api3.geo.admin.ch/rest/services/api/SearchServer?type=locations&sr=4326&limit=$limit" +
                (if (origins != null) "&origins=$origins" else "") + "&searchText=" + URLEncoder.encode(q, "UTF-8")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000
            val text = c.inputStream.bufferedReader().readText()
            c.disconnect()
            val arr = JSONObject(text).getJSONArray("results")
            (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i).getJSONObject("attrs")
                val raw = a.getString("label").replace(Regex("<i>.*?</i>"), "")
                val label = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                Hit(label, a.getDouble("lat"), a.getDouble("lon"))
            }
        } catch (e: Exception) { if (origins == null) throw e else emptyList() }
    }
}
