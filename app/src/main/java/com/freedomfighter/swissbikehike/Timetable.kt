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
 * The offline timetable pack: built weekly from the official Swiss GTFS by the repository's
 * workflow (tools/make-transit.py), published as a release asset, fetched here over Wi-Fi with
 * the system DownloadManager, unpacked beside its description file. Refreshed once a week when
 * the app starts on an unmetered connection, like the maps.
 */
object Timetable {
    private const val BASE = "https://github.com/funkypitt/swiss-bike-hike-map/releases/download/transit-data/"
    const val NAME = "switzerland.transit"
    private const val KEY = "transit"

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "transit").apply { mkdirs() }
    fun file(ctx: Context) = File(dir(ctx), NAME)
    private fun meta(ctx: Context) = File(dir(ctx), "$NAME.json")
    private fun part(ctx: Context) = File(dir(ctx), "$NAME.gz.part")

    @Volatile private var cached: Transit? = null
    @Volatile private var cachedStamp = 0L

    @Volatile var unpacking = false; private set
    fun isReady(ctx: Context): Boolean { finalize(ctx); return file(ctx).exists() && !unpacking }

    /** The pack, opened once and kept; reopened after an update. */
    fun open(ctx: Context): Transit? {
        finalize(ctx)
        val f = file(ctx)
        if (!f.exists() || unpacking) return null
        val c = cached
        if (c != null && cachedStamp == f.lastModified()) return c
        return runCatching { Transit.open(f) }.getOrNull()?.also { cached = it; cachedStamp = f.lastModified() }
    }

    class Info(val feed: String, val built: String, val bytes: Long)

    /** What the local pack says about itself (its description file). */
    fun local(ctx: Context): Info? {
        val f = file(ctx); if (!f.exists()) return null
        val m = meta(ctx)
        val fromJson = if (m.exists()) runCatching { val o = JSONObject(m.readText()); Info(o.optString("feed"), o.optString("built"), o.optLong("bytes")) }.getOrNull() else null
        // without its description file (fetch failed), the pack still says when its timetable starts
        return fromJson ?: open(ctx)?.let { Info(it.feedStart.toString(), "", f.length()) }
    }

    /** The description of the pack on the server, or null offline. */
    suspend fun remote(): Info? = withContext(Dispatchers.IO) {
        try {
            val c = URL("$BASE$NAME.json").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = true
            val text = c.inputStream.bufferedReader().readText(); c.disconnect()
            val o = JSONObject(text); Info(o.optString("feed"), o.optString("built"), o.optLong("gz_bytes"))
        } catch (e: Exception) { null }
    }

    /** Download progress in percent while one runs, else -1. */
    fun downloading(ctx: Context): Int { finalize(ctx); return Downloads.state(ctx, Prefs(ctx).downloadId(KEY)).percent.coerceAtLeast(-1) }
    fun waitingWifi(ctx: Context): Boolean = Downloads.state(ctx, Prefs(ctx).downloadId(KEY)).waitingWifi
    /** Why the last download or unpacking failed, blank when it did not. */
    fun failure(ctx: Context): String = Prefs(ctx).failure(KEY)

    /** Starts the download (Wi-Fi only) unless one is running; the pack's description is fetched first, kept aside until the pack is in. */
    fun download(ctx: Context) {
        if (downloading(ctx) >= 0) return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        part(ctx).delete()
        Prefs(ctx).setFailure(KEY, "")
        Thread { runCatching {
            val cn = URL("$BASE$NAME.json").openConnection() as HttpURLConnection
            cn.connectTimeout = 8000; cn.readTimeout = 8000
            val text = cn.inputStream.bufferedReader().readText(); cn.disconnect()
            if (text.contains("\"feed\"")) File(dir(ctx), "$NAME.json.new").writeText(text)
        } }.start()
        val req = DownloadManager.Request(Uri.parse("$BASE$NAME.gz"))
            .setTitle(ctx.getString(R.string.menu_timetable))
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, "transit/$NAME.gz.part")
        Prefs(ctx).setDownloadId(KEY, dm.enqueue(req))
    }

    /** Unpacks the downloaded .gz on a worker thread (65 MB, a few seconds), checks it, puts it and its description in place. */
    @Synchronized private fun unpack(ctx: Context) {
        if (unpacking) return
        unpacking = true
        Thread {
            val app = ctx.applicationContext
            val prefs = Prefs(app)
            val p = part(app); val tmp = File(dir(app), "$NAME.tmp")
            val ok = runCatching {
                GZIPInputStream(p.inputStream().buffered()).use { i -> tmp.outputStream().buffered().use { o -> i.copyTo(o) } }
                Transit.open(tmp).nStops > 0
            }.getOrDefault(false)
            if (ok) {
                file(app).delete(); tmp.renameTo(file(app)); cached = null
                File(dir(app), "$NAME.json.new").let { if (it.exists()) { meta(app).delete(); it.renameTo(meta(app)) } }
            } else prefs.setFailure(KEY, app.getString(R.string.dl_bad_pack))
            tmp.delete(); p.delete()
            unpacking = false
        }.start()
    }

    /** Hands a finished download to the unpacking, notes a failed one. Cheap when nothing is pending; safe to call often. */
    fun finalize(ctx: Context) {
        val prefs = Prefs(ctx)
        val id = prefs.downloadId(KEY); if (id < 0) return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) { prefs.setDownloadId(KEY, -1); return }
            when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.setDownloadId(KEY, -1)
                    if (part(ctx).exists()) unpack(ctx)
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    prefs.setFailure(KEY, Downloads.reasonText(ctx, reason))
                    prefs.setDownloadId(KEY, -1); part(ctx).delete()
                }
            }
        }
    }
}
