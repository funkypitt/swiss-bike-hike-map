package com.freedomfighter.swissbikehike

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
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

/**
 * Search: places through the GeoAdmin search server (addresses, place names, peaks, huts), or
 * people and businesses through the search.ch directory with the person's own key. A directory
 * hit is placed on the map by geocoding its address with GeoAdmin.
 */
class SearchActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LAT = "lat"; const val EXTRA_LON = "lon"; const val EXTRA_LABEL = "label"
    }
    private class Hit(val label: String, val lat: Double, val lon: Double)
    private lateinit var b: ActivitySearchBinding
    private val hits = ArrayList<Hit>()
    private val dirHits = ArrayList<Directory.Hit>()
    private var people = false
    private var job: Job? = null
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.pad(b.root)
        adapter = ArrayAdapter<String>(this, R.layout.item_search, R.id.text)
        b.list.adapter = adapter
        b.list.setOnItemClickListener { _, _, pos, _ -> if (people) pick(dirHits[pos]) else finishWith(hits[pos]) }
        b.btnPlaces.setOnClickListener { setMode(false) }
        b.btnPeople.setOnClickListener { setMode(true) }
        b.query.doAfterTextChanged { text ->
            if (people) return@doAfterTextChanged
            job?.cancel()
            val q = text?.toString()?.trim() ?: ""
            if (q.length < 2) { clear(); return@doAfterTextChanged }
            job = lifecycleScope.launch {
                delay(300)
                val found = searchPlaces(q)
                hits.clear(); hits.addAll(found)
                adapter.clear(); adapter.addAll(found.map { it.label })
            }
        }
        b.query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH && people) { searchPeople(); true } else false
        }
        setMode(false)
        b.query.requestFocus()
    }

    private fun clear() { hits.clear(); dirHits.clear(); adapter.clear() }

    private fun setMode(p: Boolean) {
        people = p
        clear()
        b.query.hint = getString(if (p) R.string.search_people_hint else R.string.search_hint)
        b.credit.visibility = if (p) View.VISIBLE else View.GONE
        b.credit.text = if (Directory.enabled(this)) Directory.CREDIT + " · press Search" else getString(R.string.directory_need_key)
        style(b.btnPlaces, !p); style(b.btnPeople, p)
    }

    private fun style(v: android.widget.TextView, active: Boolean) {
        v.setBackgroundResource(if (active) R.drawable.btn_active else R.drawable.btn)
        v.setTextColor(AppCompatResources.getColorStateList(this, if (active) R.drawable.btn_active_text else R.drawable.btn_text))
    }

    private fun finishWith(h: Hit) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_LAT, h.lat).putExtra(EXTRA_LON, h.lon).putExtra(EXTRA_LABEL, h.label))
        finish()
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
            if (place == null) { Toast.makeText(this@SearchActivity, "Address not found on the map", Toast.LENGTH_SHORT).show(); return@launch }
            finishWith(Hit(h.name + ", " + h.address, place.lat, place.lon))
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
