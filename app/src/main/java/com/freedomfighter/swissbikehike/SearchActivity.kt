package com.freedomfighter.swissbikehike

import android.app.Activity
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
 * Search key (or the last row) adds the directory: firms complete the places they name (phone,
 * address), people and other firms follow. An empty field lists the categories around and the
 * favourites (with import and export of place lists). Every pick ends as a pin on the map.
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
                      val starred: Boolean = false)

    /** One row of the list. */
    private sealed class Row {
        class Category(val cat: Places.Cat) : Row()
        object Back : Row()
        object Favs : Row()
        class Item(val hit: Hit) : Row()
        class FavItem(val fav: Fav) : Row()
        class Dir(val hit: Directory.Hit) : Row()
        object DirAsk : Row()
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

    private val hits: List<Hit> get() = rows.mapNotNull { (it as? Row.Item)?.hit }

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
                shownQuery = q
                setRows(listOf(Row.Item(Hit(Favourites.coordsName(typed.latitude, typed.longitude), typed.latitude, typed.longitude, Favourites.COORDS))))
                return@doAfterTextChanged
            }
            job = lifecycleScope.launch { delay(300); searchAll(q) }
        }
        b.query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH || favView) return@setOnEditorActionListener false
            if (Directory.enabled(this)) { mergeDirectory(); true } else false
        }
        showHome()
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
        Row.DirAsk -> getString(R.string.search_directory_row, b.query.text?.toString()?.trim() ?: "")
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
        favView = false; category = null; shownQuery = ""
        b.favRow.visibility = View.GONE
        b.credit.visibility = View.VISIBLE
        b.credit.text = credit()
        val out = ArrayList<Row>()
        out.add(Row.Favs)
        if (PlacesPack.isReady(this)) categories.forEach { out.add(Row.Category(it)) }
        else out.add(Row.Info(getString(R.string.places_pack_missing)))
        setRows(out)
    }

    /** Where the answers come from, under the field. */
    private fun credit(): String {
        val parts = ArrayList<String>()
        if (PlacesPack.isReady(this)) parts.add(PlacesPack.CREDIT + " · " + getString(if (nearIsGps) R.string.around_you else R.string.around_map))
        parts.add(if (Planner.online(this)) TransitOnline.CREDIT else if (Timetable.isReady(this)) getString(R.string.offline_timetable) else getString(R.string.timetable_missing))
        parts.add(if (Directory.enabled(this)) Directory.CREDIT + " (" + getString(R.string.directory_press_search) + ")" else getString(R.string.directory_need_key))
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
            is Row.Item -> if (r.hit.place != null) showPlace(r.hit) else finishWith(r.hit)
            is Row.FavItem -> finishWith(Hit(r.fav.name, r.fav.lat, r.fav.lon, r.fav.kind, r.fav.detail))
            is Row.Dir -> showDirectory(r.hit)
            Row.DirAsk -> mergeDirectory()
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
            b.credit.text = PlacesPack.CREDIT + " · " + getString(if (nearIsGps) R.string.around_you else R.string.around_map) + " · " + getString(R.string.place_directory_more)
        }
    }

    /** Around the position; when nothing is within reach of it (abroad, no fix yet), around the map's centre instead. */
    private fun nearbyOf(pack: Places, cats: Set<Places.Cat>, limit: Int): List<Places.Found> {
        val atPos = pack.nearby(near.latitude, near.longitude, NEARBY_M, cats, limit)
        if (atPos.isNotEmpty() || !nearIsGps || centre == near) return atPos
        nearIsGps = false; near = centre
        return pack.nearby(near.latitude, near.longitude, NEARBY_M, cats, limit)
    }

    private fun hitOf(f: Places.Found): Hit {
        val p = f.place
        val lines = Places.describe(this, p)
        val detail = (listOf(Places.categoryText(this, p)) + lines).joinToString(" · ")
        return Hit(p.label(this), p.at.latitude, p.at.longitude, Favourites.POI, detail, p, f.metres)
    }

    /** A place of the pack on two lines (glyph, name, distance; category and details); a stop or an address on one. */
    private fun row(h: Hit): String {
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

    /**
     * A typed text: the favourites that hold it, the categories whose label matches (their places
     * around), the stops of that name, GeoAdmin's towns, addresses and peaks, then the places of
     * the pack whose name holds the words; the directory is offered last when a key is set.
     */
    private suspend fun searchAll(q: String) {
        val pack = PlacesPack.open(this)
        val fq = Transit.fold(q)
        shownQuery = q
        b.credit.text = credit()
        setRows(listOf(Row.Info("…")))
        val favs = Favourites.list(this).filter { Transit.fold(it.name).contains(fq) || Transit.fold(it.detail).contains(fq) }.take(5)
            .map { Hit(it.name, it.lat, it.lon, it.kind, it.detail, starred = true) }
        val cats = categories.filter { c -> val l = Transit.fold(getString(c.label)); l.contains(fq) || (fq.length >= 4 && l.split(Regex("[ ,]+")).any { it.startsWith(fq) }) }.toSet()
        // GeoAdmin and the stops are asked while the pack is searched
        val geoJob = lifecycleScope.async { runCatching { searchPlaces(q, limit = if (cats.isEmpty()) 12 else 6) } }
        val stopJob = lifecycleScope.async { runCatching { Planner.searchStops(this@SearchActivity, q) }.getOrDefault(emptyList()) }
        val catHits = ArrayList<Hit>(); val nameHits = ArrayList<Hit>(); val tagHits = ArrayList<Hit>()
        if (pack != null) withContext(Dispatchers.Default) {
            if (cats.isNotEmpty()) catHits.addAll(nearbyOf(pack, cats, 12).map { hitOf(it) })
            // a cuisine or a kind of shop around (« pizza », « sushi », « books »)
            val kinds = Places.SHOP_KINDS.filter { (_, res) -> Transit.fold(getString(res)).let { it.contains(fq) || (fq.length >= 4 && it.split(' ').any { w -> w.startsWith(fq) }) } }.keys
            tagHits.addAll(pack.nearbyTagged(near.latitude, near.longitude, q, kinds).map { hitOf(it) }.filter { t -> catHits.none { it.place?.index == t.place?.index } })
            nameHits.addAll(pack.search(q, near.latitude, near.longitude, 15).map { hitOf(it) })
        }
        val geo = geoJob.await()
        fun away(lat: Double, lon: Double) = Places.distance(near.latitude, near.longitude, lat, lon)
        val stops = stopJob.await().take(8).map { Hit(it.name, it.at.latitude, it.at.longitude, Favourites.STOP, it.id, metres = away(it.at.latitude, it.at.longitude)) }
        if (shownQuery != q) return
        val around = catHits + tagHits
        // a stop named like a GeoAdmin answer (« Lausanne, gare ») is said once, as a stop
        val geoHits = geo.getOrDefault(emptyList()).filter { g -> stops.none { Transit.fold(it.label) == Transit.fold(g.label) } }
            .map { g -> Hit(g.label, g.lat, g.lon, g.kind, g.detail, metres = away(g.lat, g.lon)) }
        val names = nameHits.filter { n -> around.none { it.place?.index == n.place?.index } }
        // stops, addresses and places by name together, nearest first (the public-transport map: stops first)
        val rest = if (mode == Mode.TRANSIT) stops.sortedBy { it.metres } + (geoHits + names).sortedBy { it.metres }
            else (stops + geoHits + names).sortedBy { it.metres }
        val found = favs + around + rest
        val out = ArrayList<Row>(found.map { Row.Item(it) })
        if (found.isEmpty() && geo.isFailure) out.add(Row.Info(getString(R.string.search_no_answer)))
        if (Directory.enabled(this) && q.length >= 3) out.add(Row.DirAsk)
        setRows(out)
    }

    /** The card of a place of the pack (hours, phone, address…): PIN puts it on the map. */
    private fun showPlace(h: Hit) {
        val p = h.place ?: return
        val info = PlaceInfo.of(this, p)
        h.dir?.let { e ->
            if (info.phone.isNullOrBlank()) info.phone = e.phone.ifBlank { null }
            if (info.street.isBlank()) { info.street = e.street; info.zip = e.zip; info.city = e.city.ifBlank { info.city } }
        }
        PlaceCard.show(this, info, if (nearIsGps) near else null, R.string.place_pin to { finishWith(h) })
    }

    /** The card of a directory entry (person or business): ON THE MAP places it through GeoAdmin. */
    private fun showDirectory(e: Directory.Hit) {
        PlaceCard.show(this, PlaceInfo.of(e), null, R.string.place_on_map to { pick(e) })
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
     * completes that row with its phone and address; people and the other firms are appended,
     * placed through GeoAdmin when picked.
     */
    private fun mergeDirectory() {
        val q = b.query.text?.toString()?.trim() ?: ""
        if (q.length < 3) return
        lifecycleScope.launch {
            job?.join()
            if (shownQuery != q) searchAll(q)
            val asking = rows.indexOfFirst { it === Row.DirAsk }
            if (asking >= 0) { rows[asking] = Row.Info("▣  …"); setRows(rows.toList()) }
            val found = withContext(Dispatchers.IO) { runCatching { Directory.search(this@SearchActivity, q) } }
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
                rows.removeAll { it === Row.DirAsk || it is Row.Dir }
                rows.addAll(rest.map { Row.Dir(it) }); rows.addAll(people.map { Row.Dir(it) })
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
        if (h.city.isBlank() && h.street.isBlank()) return
        lifecycleScope.launch {
            val place = (if (h.street.isNotBlank()) geocode("${h.street} ${h.zip} ${h.city}", "address") else null)
                ?: geocode("${h.zip} ${h.city}", "zipcode")
            if (place == null) { Toast.makeText(this@SearchActivity, R.string.address_not_found, Toast.LENGTH_SHORT).show(); return@launch }
            finishWith(Hit(h.name, place.lat, place.lon, if (h.person) Favourites.PERSON else Favourites.FIRM,
                listOf(h.address, h.phone).filter { it.isNotBlank() }.joinToString(" · ")))
        }
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
