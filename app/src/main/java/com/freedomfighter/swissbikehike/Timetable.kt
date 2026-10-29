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
    const val AUTO_DAYS = 7

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "transit").apply { mkdirs() }
    fun file(ctx: Context) = File(dir(ctx), NAME)
    private fun meta(ctx: Context) = File(dir(ctx), "$NAME.json")
    private fun part(ctx: Context) = File(dir(ctx), "$NAME.gz.part")

    @Volatile private var cached: Transit? = null
    @Volatile private var cachedStamp = 0L

    fun isReady(ctx: Context): Boolean { finalize(ctx); return file(ctx).exists() }

    /** The pack, opened once and kept; reopened after an update. */
    fun open(ctx: Context): Transit? {
        val f = file(ctx)
        if (!f.exists()) return null
        val c = cached
        if (c != null && cachedStamp == f.lastModified()) return c
        return runCatching { Transit.open(f) }.getOrNull()?.also { cached = it; cachedStamp = f.lastModified() }
    }

    class Info(val feed: String, val built: String, val bytes: Long)

    /** What the local pack says about itself (its description file). */
    fun local(ctx: Context): Info? {
        val m = meta(ctx); if (!m.exists() || !file(ctx).exists()) return null
        return runCatching { val o = JSONObject(m.readText()); Info(o.optString("feed"), o.optString("built"), o.optLong("bytes")) }.getOrNull()
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
    fun downloading(ctx: Context): Int {
        finalize(ctx)
        val id = Prefs(ctx).downloadId(KEY); if (id < 0) return -1
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return -1
            val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (st != DownloadManager.STATUS_RUNNING && st != DownloadManager.STATUS_PENDING && st != DownloadManager.STATUS_PAUSED) return -1
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return if (total > 0) (done * 100 / total).toInt() else 0
        }
    }

    /** Starts the download (Wi-Fi only) unless one is running. */
    fun download(ctx: Context) {
        if (downloading(ctx) >= 0) return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        part(ctx).delete()
        val req = DownloadManager.Request(Uri.parse("$BASE$NAME.gz"))
            .setTitle(ctx.getString(R.string.menu_timetable))
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, "transit/$NAME.gz.part")
        Prefs(ctx).setDownloadId(KEY, dm.enqueue(req))
    }

    /** Unpacks a finished download into place and fetches its description. Safe to call often. */
    fun finalize(ctx: Context) {
        val prefs = Prefs(ctx)
        val id = prefs.downloadId(KEY); if (id < 0) return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) { prefs.setDownloadId(KEY, -1); return }
            when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.setDownloadId(KEY, -1)
                    val p = part(ctx); if (!p.exists()) return
                    val tmp = File(dir(ctx), "$NAME.tmp")
                    val ok = runCatching {
                        GZIPInputStream(p.inputStream().buffered()).use { i -> tmp.outputStream().buffered().use { o -> i.copyTo(o) } }
                        Transit.open(tmp).nStops > 0
                    }.getOrDefault(false)
                    if (ok) { file(ctx).delete(); tmp.renameTo(file(ctx)); cached = null }
                    tmp.delete(); p.delete()
                    if (ok) Thread { runCatching {
                        val cn = URL("$BASE$NAME.json").openConnection() as HttpURLConnection
                        cn.connectTimeout = 8000; cn.readTimeout = 8000
                        val text = cn.inputStream.bufferedReader().readText(); cn.disconnect()
                        if (text.contains("\"feed\"")) meta(ctx).writeText(text)
                    } }.start()
                }
                DownloadManager.STATUS_FAILED -> { prefs.setDownloadId(KEY, -1); part(ctx).delete() }
            }
        }
    }

    /** Once a week on Wi-Fi: fetch the pack again when the server has a newer feed. */
    suspend fun autoUpdate(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!file(ctx).exists()) return
        if (System.currentTimeMillis() - prefs.timetableChecked < AUTO_DAYS * 86400_000L) return
        if (!OfflineMaps.unmetered(ctx)) return
        val r = remote() ?: return
        prefs.timetableChecked = System.currentTimeMillis()
        val l = local(ctx)
        if (l == null || r.feed > l.feed || r.built > l.built) download(ctx)
    }
}
