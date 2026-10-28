package com.freedomfighter.swissbikehike

import android.content.Context
import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * People and businesses from the Swiss directory of search.ch (data of Swisscom Directories AG),
 * asked with the person's own key, as in Reader's Phone. Off until a key is pasted in the menu.
 * A private key allows about a thousand requests a month, so a search is sent only when asked
 * for (no search-as-you-type), every answer is kept for a month, and no more than a few dozen
 * questions leave the phone in a day.
 */
object Directory {
    class Hit(val name: String, val street: String, val zip: String, val city: String, val phone: String, val person: Boolean = false) {
        val address: String get() = listOf(street, listOf(zip, city).filter { it.isNotBlank() }.joinToString(" ")).filter { it.isNotBlank() }.joinToString(", ")
    }

    private const val ENDPOINT = "https://search.ch/tel/api/"
    const val ORDER_URL = "https://search.ch/tel/api/getkey.html"
    const val CREDIT = "Data: Swisscom Directories AG"
    private const val KEEP_MS = 30L * 24 * 3600 * 1000
    private const val PER_DAY = 40

    private fun cache(ctx: Context): SharedPreferences = ctx.getSharedPreferences("directory", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = Prefs(ctx).directoryKey.isNotBlank()

    /** The hits for this text: from the cache when asked within a month, else from search.ch. Throws on a network failure. */
    fun search(ctx: Context, text: String): List<Hit> {
        val q = text.trim().lowercase(Locale.ROOT)
        val sp = cache(ctx)
        sp.getString("q:$q", null)?.let { v ->
            val nl = v.indexOf('\n')
            val t = v.substring(0, nl).toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - t < KEEP_MS) return decode(v.substring(nl + 1))
        }
        if (!underQuota(sp)) throw IllegalStateException("daily limit of $PER_DAY directory searches reached")
        val hits = fetch(Prefs(ctx).directoryKey, text.trim())
        countOne(sp)
        sp.edit().putString("q:$q", "${System.currentTimeMillis()}\n" + encode(hits)).apply()
        return hits
    }

    private fun today() = (System.currentTimeMillis() / (24 * 3600 * 1000L)).toString()
    private fun underQuota(sp: SharedPreferences) = sp.getInt("day:" + today(), 0) < PER_DAY
    private fun countOne(sp: SharedPreferences) { val k = "day:" + today(); sp.edit().putInt(k, sp.getInt(k, 0) + 1).apply() }

    fun fetch(key: String, text: String): List<Hit> {
        val lang = Locale.getDefault().language.takeIf { it in setOf("de", "fr", "it", "en") } ?: "fr"
        val url = ENDPOINT + "?was=" + URLEncoder.encode(text, "UTF-8") + "&maxnum=20&lang=$lang&key=" + URLEncoder.encode(key, "UTF-8")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 8_000
            setRequestProperty("User-Agent", "swiss-bike-hike-map")
        }
        try {
            val code = c.responseCode
            if (code == 403) throw IllegalStateException("search.ch refused this key")
            if (code !in 200..299) throw IllegalStateException("directory answered $code")
            val xml = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            tag(xml, "errorMessage")?.let { throw IllegalStateException(it) }
            return parse(xml)
        } finally { c.disconnect() }
    }

    // ---- pure, public for tests ----

    /** Every entry of the directory's Atom feed. */
    fun parse(xml: String): List<Hit> {
        val entries = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }
        val out = ArrayList<Hit>()
        for (e in entries) {
            val type = tag(e, "type").orEmpty()
            val name = if (type.equals("Person", ignoreCase = true))
                listOfNotNull(tag(e, "firstname"), tag(e, "name")).joinToString(" ").trim()
            else tag(e, "name") ?: tag(e, "org") ?: ""
            if (name.isBlank()) continue
            val street = listOfNotNull(tag(e, "street"), tag(e, "streetno")).joinToString(" ").trim()
            out.add(Hit(name, street, tag(e, "zip").orEmpty(), tag(e, "city").orEmpty(), tag(e, "phone").orEmpty(),
                type.equals("Person", ignoreCase = true)))
        }
        return out
    }

    private fun tag(entry: String, name: String): String? =
        Regex("<tel:$name(?:\\s[^>]*)?>(.*?)</tel:$name>", RegexOption.DOT_MATCHES_ALL).find(entry)?.groupValues?.get(1)
            ?.let { unescape(it).trim() }?.takeIf { it.isNotBlank() }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")

    private fun encode(hits: List<Hit>) = hits.joinToString("\n") { listOf(it.name, it.street, it.zip, it.city, it.phone, if (it.person) "p" else "o").joinToString("\t") }
    private fun decode(s: String): List<Hit> = if (s.isBlank()) emptyList() else s.split('\n').mapNotNull { line ->
        val p = line.split('\t'); if (p.size < 5) null else Hit(p[0], p[1], p[2], p[3], p[4], p.getOrNull(5) == "p")
    }
}
