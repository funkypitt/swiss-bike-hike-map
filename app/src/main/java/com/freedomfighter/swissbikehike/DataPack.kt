package com.freedomfighter.swissbikehike

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * A data pack built by the repository's workflows and kept on a rolling GitHub release: the
 * timetable, the places. Fetched by [OfflineWorker] (a .gz beside a .json description), unpacked,
 * checked, then put in place. Nothing is fetched without the person asking.
 */
abstract class DataPack(private val release: String, val name: String, private val key: String, val part: Offline.Part) {
    private val base get() = "https://github.com/funkypitt/swiss-bike-hike-map/releases/download/$release/"
    val gzUrl get() = "$base$name.gz"

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, key).apply { mkdirs() }
    fun file(ctx: Context) = File(dir(ctx), name)
    protected fun meta(ctx: Context) = File(dir(ctx), "$name.json")
    fun part(ctx: Context) = File(dir(ctx), "$name.gz.part")

    @Volatile var unpacking = false; private set
    fun isReady(ctx: Context): Boolean = file(ctx).exists() && !unpacking

    /** What a pack says about itself: a version (feed date, count…), when it was built, its size. */
    class Info(val version: String, val built: String, val bytes: Long)

    /** Reads the description file; `remote` = the server's copy (sizes are the gzipped ones there). */
    protected abstract fun info(o: JSONObject, remote: Boolean): Info
    /** True when an unpacked file is a sound pack. */
    protected abstract fun check(f: File): Boolean
    /** Called after a new pack was put in place: drop what was opened. */
    protected open fun replaced() {}

    /** What the local pack says about itself (its description file), or null without a pack. */
    open fun local(ctx: Context): Info? {
        val f = file(ctx); if (!f.exists()) return null
        val m = meta(ctx)
        return if (m.exists()) runCatching { info(JSONObject(m.readText()), false) }.getOrNull() else null
    }

    /**
     * The server's description: its text, or null offline. GitHub's direct release links sometimes
     * answer after 10 s, or 504 for one file while the next one works (seen 2026-11-05 on
     * switzerland.transit.json for hours): each try asks the direct link, then the same file through
     * GitHub's API, and the download job makes `tries` tries.
     */
    suspend fun remoteText(tries: Int = 1): String? = withContext(Dispatchers.IO) {
        for (i in 1..tries) {
            for (ask in listOf({ get("$base$name.json") }, { viaApi() })) {
                val text = runCatching { ask() }.getOrNull()
                if (text != null && text.contains("\"built\"")) return@withContext text
            }
            if (i < tries) kotlinx.coroutines.delay(if (i == 1) 3_000L else 10_000L)
        }
        null
    }

    private fun get(url: String, accept: String? = null): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 20_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "SwissBikersPedestriansMap")
        if (accept != null) c.setRequestProperty("Accept", accept)
        return try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }

    /** The description through api.github.com: the release by its tag, then the asset as bytes. */
    private fun viaApi(): String? {
        val rel = get("https://api.github.com/repos/funkypitt/swiss-bike-hike-map/releases/tags/$release", "application/vnd.github+json") ?: return null
        val assets = JSONObject(rel).getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == "$name.json") return get(a.getString("url"), "application/octet-stream")
        }
        return null
    }
    suspend fun remote(): Info? = remoteText()?.let { runCatching { info(JSONObject(it), true) }.getOrNull() }
    fun parse(text: String, remote: Boolean): Info? = runCatching { info(JSONObject(text), remote) }.getOrNull()

    /** Is the server's pack newer than the phone's (or is there none on the phone)? */
    fun newer(local: Info?, remote: Info): Boolean = local == null || remote.built > local.built ||
        (local.built.isBlank() && remote.version > local.version)

    /** Unpacks the downloaded .gz, checks it, puts it and its description in place. False = damaged (the .gz is dropped). */
    fun install(ctx: Context, description: String?): Boolean {
        val p = part(ctx); val tmp = File(dir(ctx), "$name.tmp")
        unpacking = true
        try {
            val ok = runCatching {
                GZIPInputStream(p.inputStream().buffered()).use { i -> tmp.outputStream().buffered().use { o -> i.copyTo(o) } }
                check(tmp)
            }.getOrDefault(false)
            if (ok) {
                file(ctx).delete(); tmp.renameTo(file(ctx)); replaced()
                if (description != null) meta(ctx).writeText(description)
            }
            return ok
        } finally { tmp.delete(); p.delete(); unpacking = false }
    }

    fun delete(ctx: Context) { file(ctx).delete(); meta(ctx).delete(); part(ctx).delete(); replaced() }
}

/**
 * The offline timetable pack: built weekly from the official Swiss GTFS by the repository's
 * workflow (tools/make-transit.py), published on the rolling release "transit-data".
 */
object Timetable : DataPack("transit-data", "switzerland.transit", "transit", Offline.Part.TIMETABLE) {
    @Volatile private var cached: Transit? = null
    @Volatile private var cachedStamp = 0L

    override fun info(o: JSONObject, remote: Boolean) = Info(o.optString("feed"), o.optString("built"), o.optLong(if (remote) "gz_bytes" else "bytes"))
    override fun check(f: File) = Transit.open(f).nStops > 0
    override fun replaced() { cached = null }

    /** The pack, opened once and kept; reopened after an update. */
    fun open(ctx: Context): Transit? {
        val f = file(ctx)
        if (!f.exists() || unpacking) return null
        val c = cached
        if (c != null && cachedStamp == f.lastModified()) return c
        return runCatching { Transit.open(f) }.getOrNull()?.also { cached = it; cachedStamp = f.lastModified() }
    }

    /** Without its description file (fetch failed), the pack still says when its timetable starts. */
    override fun local(ctx: Context): Info? = super.local(ctx) ?: open(ctx)?.let { Info(it.feedStart.toString(), "", file(ctx).length()) }
}

/**
 * The offline pack of places (fountains, shelters, huts, shops…) from OpenStreetMap: built weekly
 * by tools/make-places.py, published on the rolling release "places-data".
 */
object PlacesPack : DataPack("places-data", "switzerland.places", "places", Offline.Part.PLACES) {
    const val CREDIT = "© OpenStreetMap contributors"
    @Volatile private var cached: Places? = null
    @Volatile private var cachedStamp = 0L

    override fun info(o: JSONObject, remote: Boolean) = Info(o.optInt("places").toString(), o.optString("built"), o.optLong(if (remote) "gz_bytes" else "bytes"))
    override fun check(f: File) = Places.open(f).nPlaces > 0
    override fun replaced() { cached = null }

    fun open(ctx: Context): Places? {
        val f = file(ctx)
        if (!f.exists() || unpacking) return null
        val c = cached
        if (c != null && cachedStamp == f.lastModified()) return c
        return runCatching { Places.open(f) }.getOrNull()?.also { cached = it; cachedStamp = f.lastModified() }
    }

    override fun local(ctx: Context): Info? = super.local(ctx) ?: open(ctx)?.let { Info(it.nPlaces.toString(), it.built, file(ctx).length()) }
}
