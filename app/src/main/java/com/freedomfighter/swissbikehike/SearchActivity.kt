package com.freedomfighter.swissbikehike

import android.app.Activity
import android.content.Intent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Search: places through the GeoAdmin search server (addresses, place names, peaks, huts) or typed
 * coordinates; people and businesses through the search.ch directory with the person's own key;
 * the favourites, with import and export of place lists. Every pick ends as a pin on the map.
 */
class SearchActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LAT = "lat"; const val EXTRA_LON = "lon"; const val EXTRA_LABEL = "label"
        const val EXTRA_KIND = "kind"; const val EXTRA_DETAIL = "detail"
    }
    private enum class Tab { PLACES, PEOPLE, FAVS }
    private class Hit(val label: String, val lat: Double, val lon: Double, val kind: String = Favourites.PLACE, val detail: String = "")

    private lateinit var b: ActivitySearchBinding
    private val hits = ArrayList<Hit>()
    private val dirHits = ArrayList<Directory.Hit>()
    private val favs = ArrayList<Fav>()
    private var tab = Tab.PLACES
    private var job: Job? = null
    private lateinit var adapter: ArrayAdapter<String>

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
        adapter = ArrayAdapter<String>(this, R.layout.item_search, R.id.text)
        b.list.adapter = adapter
        b.list.setOnItemClickListener { _, _, pos, _ ->
            when (tab) {
                Tab.PLACES -> hits.getOrNull(pos)?.let { finishWith(it) }
                Tab.PEOPLE -> dirHits.getOrNull(pos)?.let { pick(it) }
                Tab.FAVS -> favs.getOrNull(pos)?.let { finishWith(Hit(it.name, it.lat, it.lon, it.kind, it.detail)) }
            }
        }
        b.list.setOnItemLongClickListener { _, _, pos, _ ->
            if (tab != Tab.FAVS) return@setOnItemLongClickListener false
            val f = favs.getOrNull(pos) ?: return@setOnItemLongClickListener false
            AlertDialog.Builder(this).setMessage(getString(R.string.fav_remove_q, f.name))
                .setPositiveButton(R.string.remove) { _, _ -> Favourites.remove(this, f); showFavs() }
                .setNegativeButton(R.string.cancel, null).show()
            true
        }
        b.btnPlaces.setOnClickListener { setTab(Tab.PLACES) }
        b.btnPeople.setOnClickListener { setTab(Tab.PEOPLE) }
        b.btnFavs.setOnClickListener { setTab(Tab.FAVS) }
        b.btnImport.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        b.btnExport.setOnClickListener { exportFavs() }
        b.query.doAfterTextChanged { text ->
            val q = text?.toString()?.trim() ?: ""
            when (tab) {
                Tab.PEOPLE -> {}
                Tab.FAVS -> showFavs()
                Tab.PLACES -> {
                    job?.cancel()
                    if (q.length < 2) { clear(); return@doAfterTextChanged }
                    val typed = Favourites.parseCoords(q)
                    if (typed != null) {
                        val h = Hit(Favourites.coordsName(typed.latitude, typed.longitude), typed.latitude, typed.longitude, Favourites.COORDS)
                        hits.clear(); hits.add(h); adapter.clear(); adapter.add(h.label)
                        return@doAfterTextChanged
                    }
                    job = lifecycleScope.launch {
                        delay(300)
                        val found = searchPlaces(q)
                        hits.clear(); hits.addAll(found)
                        adapter.clear(); adapter.addAll(found.map { it.label })
                    }
                }
            }
        }
        b.query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH && tab == Tab.PEOPLE) { searchPeople(); true } else false
        }
        setTab(Tab.PLACES)
        b.query.requestFocus()
    }

    private fun clear() { hits.clear(); dirHits.clear(); adapter.clear() }

    private fun setTab(t: Tab) {
        tab = t
        clear()
        b.query.hint = getString(when (t) { Tab.PLACES -> R.string.search_hint; Tab.PEOPLE -> R.string.search_people_hint; Tab.FAVS -> R.string.fav_hint })
        b.credit.visibility = if (t == Tab.PEOPLE) View.VISIBLE else View.GONE
        b.credit.text = if (Directory.enabled(this)) Directory.CREDIT + " · " + getString(R.string.directory_press_search) else getString(R.string.directory_need_key)
        b.favRow.visibility = if (t == Tab.FAVS) View.VISIBLE else View.GONE
        style(b.btnPlaces, t == Tab.PLACES); style(b.btnPeople, t == Tab.PEOPLE); style(b.btnFavs, t == Tab.FAVS)
        if (t == Tab.FAVS) showFavs() else b.query.text?.let { if (it.isNotBlank()) b.query.setText(it.toString()) }
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

    private fun glyph(kind: String) = when (kind) { Favourites.PERSON -> "☺"; Favourites.FIRM -> "▣"; Favourites.COORDS -> "✚"; else -> "●" }

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
        } catch (e: Exception) { emptyList() }
    }
}
