package com.freedomfighter.swissbikehike

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The offline map: the files swisstopo publishes for that use, one per tile set of its base map
 * (the map itself and the relief), each fetched in one piece by [OfflineWorker]. Once a file is
 * on the phone the map reads its tiles there, with or without a network.
 */
object MapFiles {
    /** A tile set of the style: its name, the address the style gives, the file of the whole set. */
    class Tiles(val layer: String, val source: String, val remote: String)

    private val SOURCE = Regex("https://vectortiles\\.geo\\.admin\\.ch/tiles/([^/]+)/[^/]+/tiles\\.json")

    /** The tile sets of a swisstopo style. */
    fun of(style: String): List<Tiles> = runCatching {
        val sources = JSONObject(style).getJSONObject("sources")
        sources.keys().asSequence().mapNotNull { k ->
            val url = sources.getJSONObject(k).optString("url")
            SOURCE.matchEntire(url)?.let { Tiles(it.groupValues[1], url, url.removeSuffix("tiles.json") + it.groupValues[1] + ".mbtiles") }
        }.distinctBy { it.layer }.sortedBy { it.layer }.toList()
    }.getOrDefault(emptyList())

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "map").apply { mkdirs() }
    fun file(ctx: Context, layer: String) = File(dir(ctx), "$layer.mbtiles")
    fun part(ctx: Context, layer: String) = File(dir(ctx), "$layer.mbtiles.part")

    /** Bytes on the phone, the unfinished files included. */
    fun bytes(ctx: Context): Long = dir(ctx).listFiles()?.filter { !it.name.endsWith(".tag") }?.sumOf { it.length() } ?: 0L

    fun delete(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }
    fun deleteParts(ctx: Context) { dir(ctx).listFiles()?.filter { !it.name.endsWith(".mbtiles") }?.forEach { it.delete() } }

    /** For the map style: the file on the phone that holds a tile set, as the address the map engine reads. */
    fun local(ctx: Context): (String) -> String? {
        val d = dir(ctx)
        return { url ->
            SOURCE.matchEntire(url)?.let { File(d, it.groupValues[1] + ".mbtiles") }?.takeIf { it.exists() && it.length() > 0 }?.let { "mbtiles://" + it.absolutePath }
        }
    }
}
