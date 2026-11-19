package com.freedomfighter.swissbikehike

import android.content.Context
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * A signposted tour of Switzerland: one stage of a SwitzerlandMobility route (a local route is a
 * single stage), from its start to its end, with the figures of its signposts.
 */
class SwissTour(
    val id: Int, val land: String, val kind: Char, val nr: String, val name: String, val stage: Int, val of: Int,
    val from: String, val to: String, val metres: Int, val up: Int, val down: Int,
    /** 1 easy, 2 medium, 3 difficult; 0 unknown. */
    val fit: Int, val tech: Int,
    /** Walking time from start to end and back, 0 when the source gives none (by bike). */
    val minutes: Int, val back: Int, val loop: Boolean,
    val lat: Double, val lon: Double, val lat2: Double, val lon2: Double,
    val offset: Long, val bytes: Int,
) {
    /** What the person's own tours call this kind. */
    val tourKind: String get() = when (land) { SwissTours.MTB -> Tours.MTB; SwissTours.BIKE -> Tours.BIKE; else -> Tours.WALK }
    val barrierFree: Boolean get() = kind == 'h'
    /** Lower-case, without accents: what the filter is matched against. */
    val words: String by lazy { SwissTours.fold("$name $from $to $nr") }
}

/**
 * The signposted hiking, cycling and mountain-bike tours, read from the file shipped with the app
 * (tools/make-tours.py builds it from the open data of the Federal Roads Office, SwitzerlandMobility
 * and the cantons), or from a newer one fetched since ([ToursPack]). The list is small and kept in
 * memory; a tour's line is read when it is opened.
 */
object SwissTours {
    const val WALK = "walk"; const val BIKE = "bike"; const val MTB = "mtb"
    private const val ASSET = "tours.pack"

    class Pack(val built: String, val source: String, val tours: List<SwissTour>, val linesAt: Long) {
        /** The fetched file this was read from; null = the one shipped with the app. */
        var file: File? = null
        /** How recent the data are: the date of the source, or of the build for a pack that does not say. */
        val stamp: String get() = source.ifBlank { built }
        /** The end of the last line: a whole file is at least this long. */
        val bytes: Long get() = linesAt + (tours.maxOfOrNull { it.offset + it.bytes } ?: 0L)
    }

    @Volatile private var pack: Pack? = null

    /** The newer of the shipped pack and the fetched one (same source: the one built last). */
    fun load(ctx: Context): Pack = pack ?: synchronized(this) {
        pack ?: run {
            val shipped = ctx.assets.open(ASSET).use { readIndex(it) }
            val f = ToursPack.file(ctx)
            val fetched = if (f.exists() && !ToursPack.unpacking) runCatching { f.inputStream().buffered().use { readIndex(it) }.also { it.file = f } }.getOrNull() else null
            if (fetched != null && compareValuesBy(fetched, shipped, { it.stamp }, { it.built }) > 0) fetched
            else { if (fetched != null) ToursPack.delete(ctx); shipped }  // an update of the app brought a newer one
        }.also { pack = it }
    }

    /** A pack was fetched or deleted: read again at the next use. */
    fun forget() { pack = null }

    fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** The head of the file: "SBHT", a version byte, the length of the index, the index. */
    fun readIndex(input: InputStream): Pack {
        val d = DataInputStream(input)
        val magic = ByteArray(5); d.readFully(magic)
        require(magic[0] == 'S'.code.toByte() && magic[1] == 'B'.code.toByte() && magic[2] == 'H'.code.toByte() && magic[3] == 'T'.code.toByte() && magic[4].toInt() == 1) { "not a tours file" }
        val l = ByteArray(4); d.readFully(l)
        val len = (l[0].toInt() and 0xFF) or ((l[1].toInt() and 0xFF) shl 8) or ((l[2].toInt() and 0xFF) shl 16) or ((l[3].toInt() and 0xFF) shl 24)
        val head = ByteArray(len); d.readFully(head)
        val root = JSONObject(head.toString(Charsets.UTF_8))
        val a = root.getJSONArray("tours")
        val tours = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SwissTour(i, o.getString("l"), o.optString("k", "l").first(), o.optString("nr"), o.optString("name"), o.optInt("st"), o.optInt("of", 1),
                o.optString("from"), o.optString("to"), o.optInt("m"), o.optInt("up"), o.optInt("down"), o.optInt("fit"), o.optInt("tech"),
                o.optInt("min"), o.optInt("back"), o.optInt("loop") == 1,
                o.getDouble("lat"), o.getDouble("lon"), o.optDouble("lat2", o.getDouble("lat")), o.optDouble("lon2", o.getDouble("lon")),
                o.getLong("o"), o.getInt("b"))
        }
        return Pack(root.optString("built"), root.optString("source"), tours, 9L + len)
    }

    /** The line of a tour, from its start to its end, with elevations. */
    fun line(ctx: Context, t: SwissTour): List<TrackPoint> {
        val p = load(ctx)
        return (p.file?.inputStream()?.buffered() ?: ctx.assets.open(ASSET)).use { line(it, p, t) }
    }

    /** `input` at the start of the file. */
    fun line(input: InputStream, p: Pack, t: SwissTour): List<TrackPoint> {
        var left = p.linesAt + t.offset
        while (left > 0) { val s = input.skip(left); if (s <= 0) { if (input.read() < 0) break; left-- } else left -= s }
        val bytes = ByteArray(t.bytes); DataInputStream(input).readFully(bytes)
        return decode(bytes)
    }

    /** Zigzag varints: latitude and longitude in millionths of a degree, elevation in decimetres, each as the difference from the point before. */
    fun decode(bytes: ByteArray): List<TrackPoint> {
        val out = ArrayList<TrackPoint>(bytes.size / 5)
        var i = 0
        fun next(): Long {
            var v = 0L; var shift = 0
            while (true) { val b = bytes[i++].toInt(); v = v or ((b and 0x7F).toLong() shl shift); if (b and 0x80 == 0) break; shift += 7 }
            return (v ushr 1) xor -(v and 1)
        }
        var la = 0L; var lo = 0L; var z = 0L
        while (i < bytes.size) {
            la += next(); lo += next(); z += next()
            out.add(TrackPoint(la / 1e6, lo / 1e6, z / 10.0, 0L))
        }
        return out
    }

    /** The route's page on schweizmobil.ch, in the person's language when the site has it (German, French, Italian), else in English. */
    fun page(t: SwissTour, language: String): String {
        fun of(walk: String, bike: String, mtb: String) = when (t.land) { MTB -> mtb; BIKE -> bike; else -> walk }
        val path = when (language) {
            "de" -> "de/" + of("wanderland", "veloland", "mountainbikeland") + "/route-"
            "fr" -> "fr/suisse-" + of("a-pied", "a-velo", "a-vtt") + "/itineraire-"
            "it" -> "it/svizzera-" + of("a-piedi", "in-bici", "in-mountain-bike") + "/percorso-"
            else -> "en/" + of("hiking", "cycling", "mountainbiking") + "-in-switzerland/route-"
        }
        return "https://schweizmobil.ch/" + path + t.nr
    }
}
