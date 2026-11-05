package com.freedomfighter.swissbikehike

import android.content.Context
import java.io.File

/**
 * BRouter routing segments for Switzerland: two 5°x5° files from brouter.de, rebuilt weekly from
 * OpenStreetMap. Fetched by [OfflineWorker] into a .part file, renamed once complete.
 */
object Segments {
    val names = listOf("E5_N45", "E10_N45")
    private const val BASE = "https://brouter.de/brouter/segments4/"

    fun url(name: String) = "$BASE$name.rd5"

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "brouter/segments4").apply { mkdirs() }

    fun file(ctx: Context, name: String) = File(dir(ctx), "$name.rd5")
    fun part(ctx: Context, name: String) = File(dir(ctx), "$name.rd5.part")

    fun isReady(ctx: Context): Boolean = names.all { file(ctx, it).exists() }

    /** Bytes on the phone, 0 without the files. */
    fun bytes(ctx: Context): Long = names.sumOf { file(ctx, it).length() }

    fun delete(ctx: Context) { for (n in names) { file(ctx, n).delete(); part(ctx, n).delete(); File(part(ctx, n).path + ".tag").delete() } }
}
