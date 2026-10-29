package com.freedomfighter.swissbikehike

import android.app.DownloadManager
import android.content.Context

/** What the system DownloadManager says about one download, in words a person can act on. */
object Downloads {
    /** -2 = no such download, -1 = finished or failed (see `reason`), else percent. */
    class State(val percent: Int, val waitingWifi: Boolean, val failed: Boolean, val reason: String, val successful: Boolean)

    fun state(ctx: Context, id: Long): State {
        if (id < 0) return State(-2, false, false, "", false)
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return State(-2, false, false, "", false)
            val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val pct = if (total > 0) (done * 100 / total).toInt() else 0
            return when (st) {
                DownloadManager.STATUS_SUCCESSFUL -> State(-1, false, false, "", true)
                DownloadManager.STATUS_FAILED -> State(-1, false, true, reasonText(ctx, reason), false)
                DownloadManager.STATUS_PAUSED -> State(pct, reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI || reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK, false, "", false)
                // pending with no Wi-Fi around = the manager is waiting for one
                DownloadManager.STATUS_PENDING -> State(0, !OfflineMaps.unmetered(ctx), false, "", false)
                else -> State(pct, false, false, "", false)
            }
        }
    }

    fun reasonText(ctx: Context, reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> ctx.getString(R.string.dl_no_space)
        DownloadManager.ERROR_CANNOT_RESUME -> ctx.getString(R.string.dl_cannot_resume)
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> ctx.getString(R.string.dl_network)
        DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_FILE_ALREADY_EXISTS -> ctx.getString(R.string.dl_file)
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> ctx.getString(R.string.dl_network)
        in 400..599 -> ctx.getString(R.string.dl_server, reason)
        else -> ctx.getString(R.string.dl_unknown, reason)
    }
}
