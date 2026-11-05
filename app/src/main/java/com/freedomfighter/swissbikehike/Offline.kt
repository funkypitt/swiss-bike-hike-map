package com.freedomfighter.swissbikehike

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Switzerland offline: one thing to download, made of four parts (timetable, places, routing,
 * map), fetched one after the other by [OfflineWorker] with one notification and one progress
 * bar. Nothing starts by itself: the person asks (the menu, or the map when it needs a part),
 * and the job then goes on through lost Wi-Fi, a closed app or a restart until it is done or
 * paused. A pause is never undone by the app.
 *
 * Updates follow how often each part really changes, so a daily user is never asked to fetch
 * gigabytes for nothing: see [Policy].
 */
object Offline {
    enum class Part(val key: String, val label: Int, val estimate: Long) {
        TIMETABLE("timetable", R.string.off_part_timetable, 16_000_000L),
        PLACES("places", R.string.off_part_places, 5_000_000L),
        ROUTING("routing", R.string.off_part_routing, 460_000_000L),
        MAP("map", R.string.off_part_map, 2_400_000_000L);
    }

    const val WORK = "offline"
    const val CHANNEL = "offline"
    const val NOTIF_ID = 7
    const val NOTIF_DONE = 8
    const val ACTION_PAUSE = "com.freedomfighter.swissbikehike.OFFLINE_PAUSE"

    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences("offline", Context.MODE_PRIVATE)
    private fun set(ctx: Context, key: String): Set<String> = sp(ctx).getStringSet(key, emptySet())!!.toSet()

    // ---- what is asked for ----

    /** Parts still to fetch in the current run, in the order they are fetched. */
    fun wanted(ctx: Context): List<Part> { val s = set(ctx, "wanted"); return Part.entries.filter { it.key in s } }
    /** Parts of the current run (done ones included), for the overall progress. */
    fun run(ctx: Context): List<Part> { val s = set(ctx, "run"); return Part.entries.filter { it.key in s } }
    fun paused(ctx: Context) = sp(ctx).getBoolean("paused", false)

    /**
     * Asks for parts: adds them to the run and starts (or wakes) the job. `fresh` = fetch them even
     * when the phone's copy looks current (the map's tiles are then all checked again).
     */
    fun start(ctx: Context, parts: Collection<Part>, fresh: Boolean = false) {
        if (parts.isEmpty()) return
        val p = sp(ctx)
        val keys = parts.map { it.key }
        val running = isActive(ctx)
        val run = if (running || wanted(ctx).isNotEmpty()) set(ctx, "run") + keys else keys.toSet()
        p.edit().putStringSet("wanted", set(ctx, "wanted") + keys).putStringSet("run", run)
            .putStringSet("fresh", if (fresh) set(ctx, "fresh") + keys else set(ctx, "fresh"))
            .putBoolean("paused", false).apply()
        for (k in keys) setFailure(ctx, Part.entries.first { it.key == k }, "")
        enqueue(ctx)
    }

    /** Goes on with a paused or interrupted run. */
    fun resume(ctx: Context) {
        sp(ctx).edit().putBoolean("paused", false).apply()
        for (p in wanted(ctx)) setFailure(ctx, p, "")
        if (wanted(ctx).isNotEmpty()) enqueue(ctx)
    }

    /** Stops the job; what is fetched stays, RESUME goes on from there. */
    fun pause(ctx: Context) {
        sp(ctx).edit().putBoolean("paused", true).apply()
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }

    /** Drops the run (nothing more to fetch). */
    fun clearRun(ctx: Context) { sp(ctx).edit().remove("wanted").remove("run").remove("fresh").putBoolean("paused", false).apply() }

    internal fun done(ctx: Context, part: Part) {
        sp(ctx).edit().putStringSet("wanted", set(ctx, "wanted") - part.key).putStringSet("fresh", set(ctx, "fresh") - part.key)
            .putLong("updated_${part.key}", System.currentTimeMillis()).remove("progress_${part.key}").apply()
    }
    internal fun fresh(ctx: Context, part: Part) = part.key in set(ctx, "fresh")

    private fun enqueue(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<OfflineWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // APPEND: a part asked for while the job is ending is picked up by the next one in the chain
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun jobState(ctx: Context): WorkInfo.State? = runCatching {
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(WORK).get().lastOrNull { !it.state.isFinished }?.state
    }.getOrNull()
    fun isActive(ctx: Context) = jobState(ctx) != null

    // ---- progress, as the job writes it ----

    internal fun setProgress(ctx: Context, part: Part, done: Long, total: Long, line: String) {
        sp(ctx).edit().putString("progress_${part.key}", "$done/$total").putString("line", line).putString("current", part.key).apply()
    }
    /** Fraction done of a part in the current run (0..1), -1 when it has not started. */
    fun fraction(ctx: Context, part: Part): Double {
        if (part.key in set(ctx, "run") && part.key !in set(ctx, "wanted")) return 1.0
        val s = sp(ctx).getString("progress_${part.key}", null) ?: return -1.0
        val (d, t) = s.split('/').map { it.toLongOrNull() ?: 0L }
        return if (t > 0) (d.toDouble() / t).coerceIn(0.0, 1.0) else 0.0
    }
    /** Percent of a part while it is asked for, else -1: what GO says when routing data is still coming. */
    fun percent(ctx: Context, part: Part): Int =
        if (part.key !in set(ctx, "wanted")) -1 else (fraction(ctx, part).coerceAtLeast(0.0) * 100).toInt()

    /** The whole run, weighted by the size of each part. */
    fun overall(ctx: Context): Int {
        val parts = run(ctx); if (parts.isEmpty()) return -1
        val total = parts.sumOf { it.estimate }.toDouble()
        return (parts.sumOf { it.estimate * fraction(ctx, it).coerceAtLeast(0.0) } * 100 / total).toInt()
    }
    fun line(ctx: Context): String = sp(ctx).getString("line", "") ?: ""
    fun current(ctx: Context): Part? = sp(ctx).getString("current", null)?.let { k -> Part.entries.firstOrNull { it.key == k } }

    fun failure(ctx: Context, part: Part): String = sp(ctx).getString("fail_${part.key}", "") ?: ""
    internal fun setFailure(ctx: Context, part: Part, text: String) = sp(ctx).edit().putString("fail_${part.key}", text).apply()

    // ---- ages, for the update policy ----

    fun updated(ctx: Context, part: Part): Long = sp(ctx).getLong("updated_${part.key}", 0L)
    /** When the server last said there is nothing newer worth fetching. */
    fun checked(ctx: Context, part: Part): Long = sp(ctx).getLong("checked_${part.key}", 0L)
    fun setChecked(ctx: Context, part: Part) = sp(ctx).edit().putLong("checked_${part.key}", System.currentTimeMillis()).apply()

    /** The map's server signature when it was last fetched (swisstopo and ASTRA validators). */
    fun mapSignature(ctx: Context): String = sp(ctx).getString("map_signature", "") ?: ""
    fun setMapSignature(ctx: Context, s: String) = sp(ctx).edit().putString("map_signature", s).apply()
    /** The map's tiles were marked stale and are being checked: a retry must not mark them again. */
    fun mapRefreshing(ctx: Context) = sp(ctx).getBoolean("map_refreshing", false)
    fun setMapRefreshing(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("map_refreshing", v).apply()

    /** Is this part on the phone? (The map: is the Switzerland pack complete? → asked of MapLibre, see OfflineMaps.) */
    fun present(ctx: Context, part: Part): Boolean = when (part) {
        Part.TIMETABLE -> Timetable.file(ctx).exists()
        Part.PLACES -> PlacesPack.file(ctx).exists()
        Part.ROUTING -> Segments.names.all { Segments.file(ctx, it).exists() }
        Part.MAP -> sp(ctx).getBoolean("map_complete", false)
    }
    internal fun setMapComplete(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("map_complete", v).apply()

    /** Parts that are on the phone and old enough to be worth a look, from local dates only (no network). */
    fun dueLocally(ctx: Context, now: Long = System.currentTimeMillis()): List<Part> =
        Part.entries.filter { present(ctx, it) && Policy.oldEnough(it, maxOf(updated(ctx, it), checked(ctx, it)), now) }

    // ---- once, after the update from the version with DownloadManager and saved areas ----

    fun migrate(ctx: Context) {
        val p = sp(ctx)
        if (p.getBoolean("migrated", false)) return
        val old = Prefs(ctx)
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val resume = mutableListOf<Part>()
        for ((key, part) in listOf("E5_N45" to Part.ROUTING, "E10_N45" to Part.ROUTING, "transit" to Part.TIMETABLE, "places" to Part.PLACES)) {
            val id = old.downloadId(key); if (id < 0) continue
            val st = Downloads.state(ctx, id)
            if (st.percent >= 0) resume += part            // it was running: the person had asked for it
            runCatching { dm.remove(id) }; old.setDownloadId(key, -1)
        }
        runCatching { WorkManager.getInstance(ctx).cancelUniqueWork("packs") }
        val now = System.currentTimeMillis()
        p.edit().putBoolean("migrated", true).apply {
            if (Timetable.file(ctx).exists()) putLong("updated_timetable", Timetable.file(ctx).lastModified())
            if (PlacesPack.file(ctx).exists()) putLong("updated_places", PlacesPack.file(ctx).lastModified())
            Segments.names.map { Segments.file(ctx, it) }.filter { it.exists() }.minOfOrNull { it.lastModified() }?.let { putLong("updated_routing", it) }
            putLong("updated_map", now)
        }.apply()
        if (resume.isNotEmpty()) start(ctx, resume.distinct())
    }

    // ---- downloading one file, resumable ----

    sealed class Fetch {
        object NotModified : Fetch()
        class Done(val etag: String, val modified: Long) : Fetch()
        class Failed(val why: String, val retry: Boolean) : Fetch()
    }

    /**
     * Fetches `url` into `part`: goes on from what `part` already holds (Range, with If-Range so a
     * file changed on the server starts over), or asks only whether the server has something newer
     * than `etag` / `modified` (304 = nothing to fetch). `stopped` is polled between blocks.
     */
    suspend fun fetch(url: String, part: File, etag: String?, modified: Long, onBytes: (Long, Long) -> Unit, stopped: () -> Boolean): Fetch = withContext(Dispatchers.IO) {
        val tag = File(part.path + ".tag")
        val have = if (part.exists() && tag.exists()) part.length() else 0L
        if (have == 0L) { part.delete(); tag.delete() }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
            if (have > 0) { c.setRequestProperty("Range", "bytes=$have-"); c.setRequestProperty("If-Range", tag.readText()) }
            else {
                if (!etag.isNullOrBlank()) c.setRequestProperty("If-None-Match", etag)
                if (modified > 0) c.ifModifiedSince = modified
            }
            val code = c.responseCode
            if (code == 304) return@withContext Fetch.NotModified
            if (code == 416) { part.delete(); tag.delete(); return@withContext Fetch.Failed("416", true) }
            if (code != 200 && code != 206) return@withContext Fetch.Failed("server $code", code >= 500 || code == 429)
            val append = code == 206
            val len = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val total = if (len < 0) -1L else if (append) have + len else len
            val newTag = c.getHeaderField("ETag") ?: c.getHeaderField("Last-Modified") ?: ""
            if (!append) { part.delete(); if (newTag.isNotBlank()) tag.writeText(newTag) else tag.delete() }
            var done = if (append) have else 0L
            RandomAccessFile(part, "rw").use { out ->
                out.seek(done)
                c.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var last = 0L
                    while (true) {
                        if (stopped()) return@withContext Fetch.Failed("stopped", true)
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val t = System.currentTimeMillis()
                        if (t - last > 500) { last = t; onBytes(done, total) }
                    }
                }
            }
            onBytes(done, total)
            if (total > 0 && done != total) return@withContext Fetch.Failed("short", true)
            tag.delete()
            Fetch.Done(c.getHeaderField("ETag") ?: "", c.lastModified)
        } catch (e: IOException) {
            Fetch.Failed(e.javaClass.simpleName, true)
        } finally { c.disconnect() }
    }

    /** One HEAD for a signature: ETag, else Last-Modified; null offline. */
    suspend fun validator(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "HEAD"; c.connectTimeout = 8000; c.readTimeout = 8000
            try { if (c.responseCode != 200) null else c.getHeaderField("ETag") ?: c.getHeaderField("Last-Modified") } finally { c.disconnect() }
        }.getOrNull()
    }

    /** The server's Last-Modified, null offline. */
    suspend fun serverModified(url: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "HEAD"; c.connectTimeout = 8000; c.readTimeout = 8000
            try { if (c.responseCode != 200 || c.lastModified <= 0) null else c.lastModified } finally { c.disconnect() }
        }.getOrNull()
    }

    fun etag(ctx: Context, name: String): String = sp(ctx).getString("etag_$name", "") ?: ""
    fun setEtag(ctx: Context, name: String, v: String) = sp(ctx).edit().putString("etag_$name", v).apply()
}

/**
 * How often each part is worth fetching again, for someone who uses the app every day.
 *
 * - Timetable: the official timetable is republished every week with the works and changes in it,
 *   and the pack is rebuilt on Tuesdays (≈ 15 MB): due when a week old and the server has a newer one.
 * - Places (≈ 5 MB) and routing (≈ 450 MB): OpenStreetMap moves slowly where people walk and ride;
 *   a month is plenty. Due when a month old and the server has newer.
 * - Map (≈ 2.4 GB): swisstopo rebuilds its base map a few times a year, ASTRA its routes about yearly.
 *   Due only when their servers show a new version (one HEAD per source, see [OfflineMaps.signature]);
 *   then every tile is only *checked* (unchanged ones answer 304, no data), and changed ones are fetched.
 *   Looked at again after three months.
 */
object Policy {
    private const val DAY = 86_400_000L
    fun days(part: Offline.Part): Int = when (part) {
        Offline.Part.TIMETABLE -> 7
        Offline.Part.PLACES, Offline.Part.ROUTING -> 30
        Offline.Part.MAP -> 90
    }
    /** Old enough that the server is worth asking (`since` = last fetch or last "nothing newer"). */
    fun oldEnough(part: Offline.Part, since: Long, now: Long): Boolean = since > 0 && now - since >= days(part) * DAY - DAY / 2

    /** Due = old enough and the server has something newer (map: a new version on the server). */
    fun due(part: Offline.Part, since: Long, now: Long, newerOnServer: Boolean): Boolean =
        newerOnServer && (part == Offline.Part.MAP || oldEnough(part, since, now))
}

/** The PAUSE button of the progress notification. */
class OfflineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Offline.ACTION_PAUSE) Offline.pause(context)
    }
}
