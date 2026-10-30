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
 * Search: places through the offline OpenStreetMap pack (by category around you, or by name),
 * the GeoAdmin search server (addresses, place names, peaks) or typed coordinates; people and
 * businesses through the search.ch directory with the person's own key, which also completes a
 * place of the pack (phone, address) on request; public-transport stops (online, or from the
 * offline timetable); the favourites, with import and export of place lists. Every pick ends as
 * a pin on the map.
 */
class SearchActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LAT = "lat"; const val EXTRA_LON = "lon"; const val EXTRA_LABEL = "label"
        const val EXTRA_KIND = "kind"; const val EXTRA_DETAIL = "detail"
        /** Where "around you" is: the position when the map has one, else the map's centre (always given, the fallback when nothing is around the position). */
        const val EXTRA_NEAR_LAT = "near_lat"; const val EXTRA_NEAR_LON = "near_lon"; const val EXTRA_NEAR_GPS = "near_gps"
        const val EXTRA_CENTRE_LAT = "centre_lat"; const val EXTRA_CENTRE_LON = "centre_lon"
        private const val NEARBY_M = 2000.0
    }
    private enum class Tab { PLACES, PEOPLE, STOPS, FAVS }
    private class Hit(val label: String, val lat: Double, val lon: Double, val kind: String = Favourites.PLACE, var detail: String = "",
                      val place: Place? = null, val metres: Double = -1.0, var extraLine: String = "")

    private lateinit var b: ActivitySearchBinding
    private val hits = ArrayList<Hit>()
    private val dirHits = ArrayList<Directory.Hit>()
    private val favs = ArrayList<Fav>()
    private var tab = Tab.PLACES
    private var job: Job? = null
    private lateinit var adapter: ArrayAdapter<String>
    private var near = LatLng(46.52, 6.63)
    private var nearIsGps = false
    private var centre = near
    /** The category whose places around are listed, null = the list of categories or a text search. */
    private var category: Places.Cat? = null
    private val categories = Places.Cat.entries

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
            when (tab) {
                Tab.FAVS -> {
                    val f = favs.getOrNull(pos) ?: return@setOnItemLongClickListener false
                    AlertDialog.Builder(this).setMessage(getString(R.string.fav_remove_q, f.name))
                        .setPositiveButton(R.string.remove) { _, _ -> Favourites.remove(this, f); showFavs() }
                        .setNegativeButton(R.string.cancel, null).show()
                    true
                }
                Tab.PLACES -> {
                    val h = rowHit(pos) ?: return@setOnItemLongClickListener false
                    if (h.place == null) return@setOnItemLongClickListener false
                    showPlace(h); true
                }
                else -> false
            }
        }
        b.btnPlaces.setOnClickListener { setTab(Tab.PLACES) }
        b.btnPeople.setOnClickListener { setTab(Tab.PEOPLE) }
        b.btnStops.setOnClickListener { setTab(Tab.STOPS) }
        b.btnFavs.setOnClickListener { setTab(Tab.FAVS) }
        b.btnImport.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        b.btnExport.setOnClickListener { exportFavs() }
        b.query.doAfterTextChanged { text ->
            val q = text?.toString()?.trim() ?: ""
            when (tab) {
                Tab.PEOPLE -> {}
                Tab.FAVS -> showFavs()
                Tab.STOPS -> {
                    job?.cancel()
                    if (q.length < 2) { clear(); return@doAfterTextChanged }
                    job = lifecycleScope.launch {
                        delay(300)
                        val found = runCatching { Planner.searchStops(this@SearchActivity, q) }.getOrDefault(emptyList())
                            .map { Hit(it.name, it.at.latitude, it.at.longitude, Favourites.STOP, it.id) }
                        hits.clear(); hits.addAll(found)
                        adapter.clear()
                        if (found.isEmpty() && !Timetable.isReady(this@SearchActivity) && !Planner.online(this@SearchActivity)) adapter.add(getString(R.string.timetable_missing))
                        else adapter.addAll(found.map { it.label })
                    }
                }
                Tab.PLACES -> {
                    job?.cancel()
                    category = null
                    if (q.length < 2) { showCategories(); return@doAfterTextChanged }
                    val typed = Favourites.parseCoords(q)
                    if (typed != null) {
                        val h = Hit(Favourites.coordsName(typed.latitude, typed.longitude), typed.latitude, typed.longitude, Favourites.COORDS)
                        hits.clear(); hits.add(h); adapter.clear(); adapter.add(h.label)
                        return@doAfterTextChanged
                    }
                    job = lifecycleScope.launch { delay(300); searchAll(q) }
                }
            }
        }
        b.query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            when (tab) {
                Tab.PEOPLE -> { searchPeople(); true }
                Tab.PLACES -> { if (Directory.enabled(this)) { mergeDirectory(); true } else false }
                else -> false
            }
        }
        setTab(Tab.PLACES)
        b.query.requestFocus()
    }

    private fun clear() { hits.clear(); dirHits.clear(); adapter.clear() }

    private fun setTab(t: Tab) {
        tab = t
        clear()
        category = null
        b.query.hint = getString(when (t) { Tab.PLACES -> R.string.search_hint; Tab.PEOPLE -> R.string.search_people_hint; Tab.STOPS -> R.string.search_stops_hint; Tab.FAVS -> R.string.fav_hint })
        b.credit.visibility = if (t == Tab.FAVS) View.GONE else View.VISIBLE
        b.credit.text = when (t) {
            Tab.STOPS -> if (Planner.online(this)) TransitOnline.CREDIT else getString(if (Timetable.isReady(this)) R.string.offline_timetable else R.string.timetable_missing)
            Tab.PLACES -> placesCredit()
            else -> if (Directory.enabled(this)) Directory.CREDIT + " · " + getString(R.string.directory_press_search) else getString(R.string.directory_need_key)
        }
        b.favRow.visibility = if (t == Tab.FAVS) View.VISIBLE else View.GONE
        style(b.btnPlaces, t == Tab.PLACES); style(b.btnPeople, t == Tab.PEOPLE); style(b.btnStops, t == Tab.STOPS); style(b.btnFavs, t == Tab.FAVS)
        when {
            t == Tab.FAVS -> showFavs()
            t == Tab.PLACES && (b.query.text?.isBlank() != false) -> showCategories()
            else -> b.query.text?.let { if (it.isNotBlank()) b.query.setText(it.toString()) }
        }
    }

    private fun placesCredit(): String {
        val ready = PlacesPack.isReady(this)
        val where = getString(if (nearIsGps) R.string.around_you else R.string.around_map)
        return if (!ready) PlacesPack.CREDIT
        else PlacesPack.CREDIT + " · " + where + (if (Directory.enabled(this)) " · " + Directory.CREDIT + " (" + getString(R.string.directory_press_search) + ")" else "")
    }

    private fun style(v: TextView, active: Boolean) {
        v.setBackgroundResource(if (active) R.drawable.btn_active else R.drawable.btn)
        v.setTextColor(AppCompatResources.getColorStateList(this, if (active) R.drawable.btn_active_text else R.drawable.btn_text))
    }

    private fun finishWith(h: Hit) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_LAT, h.lat).putExtra(EXTRA_LON, h.lon).putExtra(EXTRA_LABEL, h.label)
            .putExtra(EXTRA_KIND, h.kind).putExtra(EXTRA_DETAIL, h.detail))
        finish()
    }

    // ---- places: categories around, names, GeoAdmin ----

    /** The list of categories (query empty): one tap lists what is around. */
    private fun showCategories() {
        hits.clear(); dirHits.clear(); adapter.clear()
        b.credit.text = placesCredit()
        if (!PlacesPack.isReady(this)) { adapter.add(getString(R.string.places_pack_missing)); return }
        adapter.addAll(categories.map { c -> c.glyph + "  " + getString(c.label) })
    }

    /** The places of one category around the point, nearest first, under a row that goes back to the categories. */
    private fun showNearby(cat: Places.Cat) {
        category = cat
        val pack = PlacesPack.open(this) ?: return
        lifecycleScope.launch {
            adapter.clear(); adapter.add(getString(R.string.back_categories)); adapter.add("…")
            val found = withContext(Dispatchers.Default) { nearbyOf(pack, setOf(cat), 40) }
            if (category != cat) return@launch
            hits.clear(); hits.addAll(found.map { hitOf(it) })
            adapter.clear(); adapter.add(getString(R.string.back_categories))
            adapter.addAll(hits.map { row(it) })
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
        val detail = (listOf(getString(p.cat.label)) + lines).joinToString(" · ")
        return Hit(p.label(this), p.at.latitude, p.at.longitude, Favourites.POI, detail, p, f.metres)
    }

    /** Two lines: glyph, name (or category) and distance; then the category and the first details. */
    private fun row(h: Hit): String {
        val p = h.place ?: return h.label
        val head = p.cat.glyph + "  " + h.label + (if (h.metres >= 0) "   " + Fmt.dist(h.metres) else "")
        val lines = (if (h.label == getString(p.cat.label)) emptyList() else listOf(getString(p.cat.label))) + Places.describe(this, p).take(2) +
            (if (h.extraLine.isNotBlank()) listOf(h.extraLine) else emptyList())
        return if (lines.isEmpty()) head else head + "\n" + lines.joinToString(" · ")
    }

    /** The hit behind a row of the PLACES list (the first row goes back to the categories when one is shown). */
    private fun rowHit(pos: Int): Hit? = if (category != null) hits.getOrNull(pos - 1) else hits.getOrNull(pos)

    private fun pickPlaceRow(pos: Int) {
        val q = b.query.text?.toString()?.trim() ?: ""
        if (category != null) {
            if (pos == 0) { category = null; showCategories(); return }
            rowHit(pos)?.let { finishWith(it) }; return
        }
        if (q.length < 2 && PlacesPack.isReady(this)) { categories.getOrNull(pos)?.let { showNearby(it) }; return }
        // the directory's own rows follow the hits: a tap on one places it through GeoAdmin
        if (pos >= hits.size) dirHits.getOrNull(pos - hits.size)?.let { pick(it) }
        else hits.getOrNull(pos)?.let { finishWith(it) }
    }

    /**
     * A typed text: the categories whose label matches (their places around first), then GeoAdmin
     * (towns, addresses, peaks), then the places of the pack whose name holds the words.
     */
    private suspend fun searchAll(q: String) {
        val pack = PlacesPack.open(this)
        val fq = Transit.fold(q)
        val catHits = ArrayList<Hit>(); val nameHits = ArrayList<Hit>()
        adapter.clear(); adapter.add("…")
        val cats = categories.filter { c -> val l = Transit.fold(getString(c.label)); l.contains(fq) || (fq.length >= 4 && l.split(Regex("[ ,]+")).any { it.startsWith(fq) }) }.toSet()
        // GeoAdmin is asked while the pack is searched
        val geoJob = lifecycleScope.async { runCatching { searchPlaces(q, limit = if (cats.isEmpty()) 20 else 8) } }
        if (pack != null) withContext(Dispatchers.Default) {
            if (cats.isNotEmpty()) catHits.addAll(nearbyOf(pack, cats, 12).map { hitOf(it) })
            nameHits.addAll(pack.search(q, near.latitude, near.longitude, 15).map { hitOf(it) })
        }
        val geo = geoJob.await()
        val found = catHits + geo.getOrDefault(emptyList()) + nameHits.filter { n -> catHits.none { it.place?.index == n.place?.index } }
        hits.clear(); hits.addAll(found); dirHits.clear()
        adapter.clear(); adapter.addAll(found.map { row(it) })
        if (geo.isFailure && found.isEmpty()) adapter.add(getString(R.string.search_no_answer))
    }

    /** The details of a place of the pack, with what can be done: pin it, call, open its site, or ask the directory. */
    private fun showPlace(h: Hit) {
        val p = h.place ?: return
        val lines = (if (p.name.isBlank()) emptyList() else listOf(getString(p.cat.label))) + Places.describe(this, p) +
            (if (h.extraLine.isNotBlank()) listOf(h.extraLine) else emptyList()) + listOf(Favourites.coordsName(p.at.latitude, p.at.longitude))
        val tel = p.extras["tel"]; val web = p.extras["web"]
        val d = AlertDialog.Builder(this).setTitle(h.label).setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.place_pin) { _, _ -> finishWith(h) }
        when {
            tel != null -> d.setNeutralButton(R.string.place_call) { _, _ -> dial(tel) }
            Directory.enabled(this) -> d.setNeutralButton(R.string.place_directory) { _, _ -> completeFromDirectory(h) }
        }
        if (web != null) d.setNegativeButton(R.string.place_web) { _, _ -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (web.startsWith("http")) web else "https://$web"))) } }
        else d.setNegativeButton(R.string.cancel, null)
        d.show()
    }

    private fun dial(tel: String) { runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + tel.replace(" ", "")))) } }

    /** Asks search.ch for this place (name and town): the entries found, each one a call away; the first phone goes into the row. */
    private fun completeFromDirectory(h: Hit) {
        val p = h.place ?: return
        if (p.name.isBlank()) return
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { Directory.search(this@SearchActivity, p.name, p.extras["city"] ?: "") } }
            found.onFailure { e -> Toast.makeText(this@SearchActivity, (e.message ?: "no answer").take(120), Toast.LENGTH_LONG).show() }
            found.onSuccess { list ->
                if (list.isEmpty()) { Toast.makeText(this@SearchActivity, R.string.place_directory_none, Toast.LENGTH_LONG).show(); return@onSuccess }
                list.firstOrNull { it.phone.isNotBlank() }?.let { enrich(h, it) }
                AlertDialog.Builder(this@SearchActivity).setTitle(Directory.CREDIT)
                    .setItems(list.map { e -> listOf(e.name, e.address, e.phone).filter { it.isNotBlank() }.joinToString("\n") }.toTypedArray()) { _, i -> list[i].phone.takeIf { it.isNotBlank() }?.let { dial(it) } }
                    .setPositiveButton(R.string.place_pin) { _, _ -> finishWith(h) }.show()
            }
        }
    }

    /** Adds the directory's phone and address to a hit of the pack, and refreshes its row. */
    private fun enrich(h: Hit, e: Directory.Hit) {
        h.extraLine = listOf(e.phone, e.address).filter { it.isNotBlank() }.joinToString(" · ")
        if (h.extraLine.isNotBlank() && !h.detail.contains(e.phone)) h.detail = h.detail + " · " + h.extraLine
        val pos = hits.indexOf(h); if (pos < 0) return
        val rowPos = if (category != null) pos + 1 else pos
        if (rowPos < adapter.count) { adapter.setNotifyOnChange(false); adapter.remove(adapter.getItem(rowPos)); adapter.insert(row(h), rowPos); adapter.setNotifyOnChange(true); adapter.notifyDataSetChanged() }
    }

    /**
     * Search pressed with a key: the directory's firms for the typed text join the list. One that
     * names a place already listed (same name, same town when both say it) completes that row with
     * its phone and address; the others are appended, placed through GeoAdmin when picked.
     */
    private fun mergeDirectory() {
        val q = b.query.text?.toString()?.trim() ?: ""
        if (q.length < 3) return
        lifecycleScope.launch {
            job?.join()
            val found = withContext(Dispatchers.IO) { runCatching { Directory.search(this@SearchActivity, q) } }
            found.onFailure { e -> Toast.makeText(this@SearchActivity, (e.message ?: "no answer").take(120), Toast.LENGTH_LONG).show() }
            found.onSuccess { list ->
                val firms = list.filter { !it.person }
                val rest = ArrayList<Directory.Hit>()
                for (e in firms) {
                    val fe = Transit.fold(e.name)
                    val match = hits.firstOrNull { h ->
                        val p = h.place ?: return@firstOrNull false
                        val fp = Transit.fold(p.name)
                        fp.isNotBlank() && (fp.contains(fe) || fe.contains(fp)) && (p.extras["city"].isNullOrBlank() || e.city.isBlank() || Transit.fold(p.extras["city"]!!) == Transit.fold(e.city))
                    }
                    if (match != null) { if (match.extraLine.isBlank()) enrich(match, e) } else rest.add(e)
                }
                dirHits.clear(); dirHits.addAll(rest)
                adapter.addAll(rest.map { e -> "▣  " + listOf(e.name, e.address, e.phone).filter { it.isNotBlank() }.joinToString("\n") })
                if (firms.isEmpty()) Toast.makeText(this@SearchActivity, R.string.directory_nobody, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onRow(pos: Int) {
        when (tab) {
            Tab.PLACES -> pickPlaceRow(pos)
            Tab.PEOPLE -> dirHits.getOrNull(pos)?.let { pick(it) }
            Tab.STOPS -> hits.getOrNull(pos)?.let { finishWith(it) }
            Tab.FAVS -> favs.getOrNull(pos)?.let { finishWith(Hit(it.name, it.lat, it.lon, it.kind, it.detail)) }
        }
    }

    // ---- favourites ----

    private fun showFavs() {
        val q = b.query.text?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
        val all = Favourites.list(this)
        favs.clear()
        favs.addAll(all.filter { q.isEmpty() || it.name.lowercase(Locale.ROOT).contains(q) || it.detail.lowercase(Locale.ROOT).contains(q) }
            .sortedBy { it.name.lowercase(Locale.ROOT) })
        adapter.clear()
        if (all.isEmpty()) adapter.add(getString(R.string.fav_empty))
        else adapter.addAll(favs.map { f -> listOf(glyph(f.kind) + " " + f.name, f.detail).filter { it.isNotBlank() }.joinToString("\n") })
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

    private fun searchPeople() {
        val q = b.query.text?.toString()?.trim() ?: ""
        if (q.length < 3) return
        if (!Directory.enabled(this)) { Toast.makeText(this, R.string.directory_need_key, Toast.LENGTH_LONG).show(); return }
        lifecycleScope.launch {
            adapter.clear(); adapter.add("…")
            val found = withContext(Dispatchers.IO) { runCatching { Directory.search(this@SearchActivity, q) } }
            dirHits.clear(); adapter.clear()
            found.onSuccess { list ->
                dirHits.addAll(list)
                if (list.isEmpty()) adapter.add(getString(R.string.directory_nobody))
                else adapter.addAll(list.map { h -> listOf(h.name, h.address, h.phone).filter { it.isNotBlank() }.joinToString("\n") })
            }.onFailure { e -> adapter.add((e.message ?: "no answer").take(120)) }
        }
    }

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
