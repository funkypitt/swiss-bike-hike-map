package com.freedomfighter.swissbikehike

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
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

/** Place search through the GeoAdmin search server (addresses, place names, peaks, huts). */
class SearchActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LAT = "lat"; const val EXTRA_LON = "lon"; const val EXTRA_LABEL = "label"
    }
    private class Hit(val label: String, val lat: Double, val lon: Double)
    private val hits = ArrayList<Hit>()
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.pad(b.root)
        val adapter = ArrayAdapter<String>(this, R.layout.item_search, R.id.text)
        b.list.adapter = adapter
        b.list.setOnItemClickListener { _, _, pos, _ ->
            val h = hits[pos]
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_LAT, h.lat).putExtra(EXTRA_LON, h.lon).putExtra(EXTRA_LABEL, h.label))
            finish()
        }
        b.query.doAfterTextChanged { text ->
            job?.cancel()
            val q = text?.toString()?.trim() ?: ""
            if (q.length < 2) { hits.clear(); adapter.clear(); return@doAfterTextChanged }
            job = lifecycleScope.launch {
                delay(300)
                val found = search(q)
                hits.clear(); hits.addAll(found)
                adapter.clear(); adapter.addAll(found.map { it.label })
            }
        }
        b.query.requestFocus()
    }

    private suspend fun search(q: String): List<Hit> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api3.geo.admin.ch/rest/services/api/SearchServer?type=locations&sr=4326&limit=20&searchText=" +
                URLEncoder.encode(q, "UTF-8")
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
