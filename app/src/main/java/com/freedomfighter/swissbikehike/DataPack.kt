package com.freedomfighter.swissbikehike

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * A data pack built by the repository's workflows and kept on a rolling GitHub release: the
 * timetable, the places. Fetched over Wi-Fi with the system DownloadManager, as a .gz beside a
 * .json description, unpacked on a worker thread, checked, then put in place. Nothing is fetched
 * without the person asking (the menu's buttons).
 */
abstract class DataPack(private val release: String, val name: String, private val key: String, private val titleRes: Int) {
    private val base get() = "https://github.com/funkypitt/swiss-bike-hike-map/releases/download/$release/"

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, key).apply { mkdirs() }
    fun file(ctx: Context) = File(dir(ctx), name)
    protected fun meta(ctx: Context) = File(dir(ctx), "$name.json")
    private fun part(ctx: Context) = File(dir(ctx), "$name.gz.part")

    @Volatile var unpacking = false; private set
    fun isReady(ctx: Context): Boolean { finalize(ctx); return file(ctx).exists() && !unpacking }

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

    /** The description of the pack on the server, or null offline. */
    suspend fun remote(): Info? = withContext(Dispatchers.IO) {
        try {
            val c = URL("$base$name.json").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = true
            val text = c.inputStream.bufferedReader().readText(); c.disconnect()
            info(JSONObject(text), true)
        } catch (e: Exception) { null }
    }

    /** Download progress in percent while one runs, else -1. */
    fun downloading(ctx: Context): Int { finalize(ctx); return Downloads.state(ctx, Prefs(ctx).downloadId(key)).percent.coerceAtLeast(-1) }
    fun waitingWifi(ctx: Context): Boolean = Downloads.state(ctx, Prefs(ctx).downloadId(key)).waitingWifi
    /** Why the last download or unpacking failed, blank when it did not. */
    fun failure(ctx: Context): String = Prefs(ctx).failure(key)

    /** Starts the download (Wi-Fi only) unless one is running; the description is fetched first, kept aside until the pack is in. */
    fun download(ctx: Context) {
        if (downloading(ctx) >= 0) return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        part(ctx).delete()
        Prefs(ctx).setFailure(key, "")
        Thread { runCatching {
            val cn = URL("$base$name.json").openConnection() as HttpURLConnection
            cn.connectTimeout = 8000; cn.readTimeout = 8000
            val text = cn.inputStream.bufferedReader().readText(); cn.disconnect()
            if (text.contains("\"built\"")) File(dir(ctx), "$name.json.new").writeText(text)
        } }.start()
        val req = DownloadManager.Request(Uri.parse("$base$name.gz"))
            .setTitle(ctx.getString(titleRes))
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, "$key/$name.gz.part")
        Prefs(ctx).setDownloadId(key, dm.enqueue(req))
    }

    /** Unpacks the downloaded .gz on a worker thread, checks it, puts it and its description in place. */
    @Synchronized private fun unpack(ctx: Context) {
        if (unpacking) return
        unpacking = true
        Thread {
            val app = ctx.applicationContext
            val prefs = Prefs(app)
            val p = part(app); val tmp = File(dir(app), "$name.tmp")
            val ok = runCatching {
                GZIPInputStream(p.inputStream().buffered()).use { i -> tmp.outputStream().buffered().use { o -> i.copyTo(o) } }
                check(tmp)
            }.getOrDefault(false)
            if (ok) {
                file(app).delete(); tmp.renameTo(file(app)); replaced()
                File(dir(app), "$name.json.new").let { if (it.exists()) { meta(app).delete(); it.renameTo(meta(app)) } }
            } else prefs.setFailure(key, app.getString(R.string.dl_bad_pack))
            tmp.delete(); p.delete()
            unpacking = false
        }.start()
    }

    /** Hands a finished download to the unpacking, notes a failed one. Cheap when nothing is pending; safe to call often. */
    fun finalize(ctx: Context) {
        val prefs = Prefs(ctx)
        val id = prefs.downloadId(key); if (id < 0) return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) { prefs.setDownloadId(key, -1); return }
            when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.setDownloadId(key, -1)
                    if (part(ctx).exists()) unpack(ctx)
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    prefs.setFailure(key, Downloads.reasonText(ctx, reason))
                    prefs.setDownloadId(key, -1); part(ctx).delete()
                }
            }
        }
    }
}

/**
 * The offline timetable pack: built weekly from the official Swiss GTFS by the repository's
 * workflow (tools/make-transit.py), published on the rolling release "transit-data".
 */
object Timetable : DataPack("transit-data", "switzerland.transit", "transit", R.string.menu_timetable) {
    @Volatile private var cached: Transit? = null
    @Volatile private var cachedStamp = 0L

    override fun info(o: JSONObject, remote: Boolean) = Info(o.optString("feed"), o.optString("built"), o.optLong(if (remote) "gz_bytes" else "bytes"))
    override fun check(f: File) = Transit.open(f).nStops > 0
    override fun replaced() { cached = null }

    /** The pack, opened once and kept; reopened after an update. */
    fun open(ctx: Context): Transit? {
        finalize(ctx)
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
object PlacesPack : DataPack("places-data", "switzerland.places", "places", R.string.menu_places) {
    const val CREDIT = "© OpenStreetMap contributors"
    @Volatile private var cached: Places? = null
    @Volatile private var cachedStamp = 0L

    override fun info(o: JSONObject, remote: Boolean) = Info(o.optInt("places").toString(), o.optString("built"), o.optLong(if (remote) "gz_bytes" else "bytes"))
    override fun check(f: File) = Places.open(f).nPlaces > 0
    override fun replaced() { cached = null }

    fun open(ctx: Context): Places? {
        finalize(ctx)
        val f = file(ctx)
        if (!f.exists() || unpacking) return null
        val c = cached
        if (c != null && cachedStamp == f.lastModified()) return c
        return runCatching { Places.open(f) }.getOrNull()?.also { cached = it; cachedStamp = f.lastModified() }
    }

    override fun local(ctx: Context): Info? = super.local(ctx) ?: open(ctx)?.let { Info(it.nPlaces.toString(), it.built, file(ctx).length()) }
}
