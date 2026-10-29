package com.freedomfighter.swissbikehike

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BRouter routing segments for Switzerland: two 5°x5° files from brouter.de, rebuilt weekly from OpenStreetMap.
 * Downloads go through the system DownloadManager into a .part file, renamed once complete.
 */
object Segments {
    val names = listOf("E5_N45", "E10_N45")
    private const val BASE = "https://brouter.de/brouter/segments4/"

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "brouter/segments4").apply { mkdirs() }

    fun file(ctx: Context, name: String) = File(dir(ctx), "$name.rd5")

    fun isReady(ctx: Context): Boolean {
        finalizeDownloads(ctx)
        return names.all { file(ctx, it).exists() }
    }

    class Status(val name: String, val present: Boolean, val size: Long, val modified: Long, val downloading: Int, val waitingWifi: Boolean, val failure: String)

    fun status(ctx: Context): List<Status> {
        finalizeDownloads(ctx)
        val prefs = Prefs(ctx)
        return names.map { n ->
            val f = file(ctx, n)
            val st = Downloads.state(ctx, prefs.downloadId(n))
            Status(n, f.exists(), f.length(), f.lastModified(), st.percent.coerceAtLeast(-1), st.waitingWifi, prefs.failure("seg_$n"))
        }
    }

    /** True while any segment is still coming, so GO can say so instead of asking to download again. */
    fun downloading(ctx: Context): Int = status(ctx).maxOf { it.downloading }

    /** Moves finished .part downloads into place. Safe to call often. */
    fun finalizeDownloads(ctx: Context) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val prefs = Prefs(ctx)
        for (n in names) {
            val id = prefs.downloadId(n)
            if (id < 0) continue
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) { prefs.setDownloadId(n, -1); return@use }
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (st == DownloadManager.STATUS_SUCCESSFUL) {
                    val part = File(dir(ctx), "$n.rd5.part")
                    if (part.exists()) { file(ctx, n).delete(); part.renameTo(file(ctx, n)) }
                    prefs.setDownloadId(n, -1); prefs.setFailure("seg_$n", "")
                } else if (st == DownloadManager.STATUS_FAILED) {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    prefs.setFailure("seg_$n", Downloads.reasonText(ctx, reason))
                    prefs.setDownloadId(n, -1)
                    File(dir(ctx), "$n.rd5.part").delete()
                }
            }
        }
    }

    /** Starts the download of the named files (all missing ones when null). */
    fun download(ctx: Context, which: List<String> = names.filter { !file(ctx, it).exists() }) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val prefs = Prefs(ctx)
        val st = status(ctx).associateBy { it.name }
        for (n in which) {
            if ((st[n]?.downloading ?: -1) >= 0) continue
            File(dir(ctx), "$n.rd5.part").delete()
            prefs.setFailure("seg_$n", "")
            val req = DownloadManager.Request(Uri.parse("$BASE$n.rd5"))
                .setTitle(ctx.getString(R.string.need_data_title) + " " + n)
                .setDescription(ctx.getString(R.string.app_name))
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(ctx, null, "brouter/segments4/$n.rd5.part")
            prefs.setDownloadId(n, dm.enqueue(req))
        }
    }

    /** Server dates, to tell whether a newer weekly build exists. Null entry when offline. */
    suspend fun serverDates(): Map<String, Long?> = withContext(Dispatchers.IO) {
        names.associateWith { n ->
            try {
                val c = URL("$BASE$n.rd5").openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"; c.connectTimeout = 8000; c.readTimeout = 8000
                val t = c.lastModified
                c.disconnect()
                if (t > 0) t else null
            } catch (e: Exception) { null }
        }
    }

    fun date(t: Long): String = if (t <= 0) "?" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))
}

class SegmentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) Segments.finalizeDownloads(context)
    }
}
