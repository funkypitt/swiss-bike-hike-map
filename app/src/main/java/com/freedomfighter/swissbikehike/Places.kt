package com.freedomfighter.swissbikehike

import android.content.Context
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/** A place of the pack: a point with a category, a name (may be blank) and a few extra tags. */
class Place(val index: Int, val name: String, val at: LatLng, val cat: Places.Cat, val extras: Map<String, String>) {
    /** The name, or the category when the place has none. */
    fun label(ctx: Context): String = name.ifBlank { ctx.getString(cat.label) }
}

/**
 * The offline places (fountains, shelters, huts, bike shops, bakeries…) from OpenStreetMap: the
 * binary written by tools/make-places.py, memory-mapped. Places are sorted by grid cell, so the
 * ones around a point are read from a few cells; names are searched linearly (a quarter second
 * for the whole country, once the folded names are cached).
 */
class Places private constructor(private val b: ByteBuffer) {
    /** The categories the app knows, by the key the pack uses. `group` colours the dots on the map. */
    enum class Cat(val key: String, val label: Int, val group: Int, val glyph: String) {
        WATER("water", R.string.cat_water, WATER_G, "≈"),
        FOUNTAIN("fountain", R.string.cat_fountain, WATER_G, "≈"),
        TOILETS("toilets", R.string.cat_toilets, REST_G, "WC"),
        SHELTER("shelter", R.string.cat_shelter, REST_G, "⌂"),
        HUT("hut", R.string.cat_hut, REST_G, "⌂"),
        PICNIC("picnic", R.string.cat_picnic, REST_G, "⌸"),
        FIREPLACE("fireplace", R.string.cat_fireplace, REST_G, "♨"),
        VIEWPOINT("viewpoint", R.string.cat_viewpoint, REST_G, "◉"),
        BATHING("bathing", R.string.cat_bathing, WATER_G, "≋"),
        BIKE_SHOP("bike_shop", R.string.cat_bike_shop, BIKE_G, "⚙"),
        BIKE_REPAIR("bike_repair", R.string.cat_bike_repair, BIKE_G, "⚙"),
        BIKE_RENTAL("bike_rental", R.string.cat_bike_rental, BIKE_G, "⚙"),
        BIKE_PARKING("bike_parking", R.string.cat_bike_parking, BIKE_G, "P"),
        CHARGING("charging", R.string.cat_charging, BIKE_G, "⚡"),
        OUTDOOR("outdoor", R.string.cat_outdoor, BIKE_G, "▲"),
        BAKERY("bakery", R.string.cat_bakery, FOOD_G, "●"),
        GROCERY("grocery", R.string.cat_grocery, FOOD_G, "●"),
        RESTAURANT("restaurant", R.string.cat_restaurant, FOOD_G, "●"),
        CAMPING("camping", R.string.cat_camping, SLEEP_G, "▲"),
        LODGING("lodging", R.string.cat_lodging, SLEEP_G, "■"),
        PHARMACY("pharmacy", R.string.cat_pharmacy, SERVICE_G, "✚"),
        HOSPITAL("hospital", R.string.cat_hospital, SERVICE_G, "✚"),
        INFO("info", R.string.cat_info, SERVICE_G, "i"),
        SIGHT("sight", R.string.cat_sight, SERVICE_G, "★"),
        ATM("atm", R.string.cat_atm, SERVICE_G, "$");

        companion object { fun of(key: String): Cat? = entries.firstOrNull { it.key == key } }
    }

    companion object {
        private const val MAGIC = 0x50484253 // "SBHP" little-endian
        private const val CELL = 0.02
        private const val LAT0 = 45.0; private const val LON0 = 5.0
        const val WATER_G = 0; const val REST_G = 1; const val BIKE_G = 2; const val FOOD_G = 3; const val SLEEP_G = 4; const val SERVICE_G = 5
        /** One colour per group, for the dots on the map. */
        val GROUP_COLOURS = arrayOf("#1E6FD9", "#4F8A1F", "#D4380D", "#D98E00", "#7A3E9D", "#444444")

        fun open(f: File): Places {
            val ch = RandomAccessFile(f, "r").channel
            val buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
            ch.close()
            return Places(buf)
        }
        fun of(bytes: ByteArray): Places = Places(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))

        /** Metres between two points, flat-earth, good to a percent at this scale. */
        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
            hypot((lat2 - lat1) * 110_540.0, (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1)))

        /** The extra tags as lines a person reads: hours, phone, website, address, altitude… */
        fun describe(ctx: Context, p: Place): List<String> {
            val x = p.extras
            val out = ArrayList<String>()
            x["ele"]?.toDoubleOrNull()?.let { out.add(String.format(Locale.US, "%.0f m", it)) }
            x["oh"]?.let { out.add(it) }
            x["cuisine"]?.let { out.add(it.replace('_', ' ').replace(';', ',')) }
            x["type"]?.let { out.add(it.replace('_', ' ')) }
            x["cap"]?.let { out.add(ctx.getString(R.string.place_capacity, it)) }
            if (x["fee"] == "yes") out.add(ctx.getString(R.string.place_fee))
            x["op"]?.let { out.add(it) }
            listOfNotNull(x["addr"], x["city"]).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }?.let { out.add(it) }
            x["tel"]?.let { out.add(it) }
            x["web"]?.let { out.add(it.removePrefix("https://").removePrefix("http://").removeSuffix("/")) }
            return out
        }
    }

    val nPlaces: Int
    val built: String
    private val secStrings: Int; private val secCats: Int; private val secPlaces: Int; private val secCells: Int
    private val nStrings: Int
    private val cats: Array<Cat?>
    private val nCells: Int
    private val folded: Array<String?>

    init {
        require(b.getInt(0) == MAGIC) { "not a places pack" }
        require(b.getInt(4) == 1) { "unknown pack version" }
        val d = b.getInt(8)
        built = String.format(Locale.US, "%04d-%02d-%02d", d / 10000, d / 100 % 100, d % 100)
        nPlaces = b.getInt(12)
        secStrings = b.getInt(16); secCats = b.getInt(20); secPlaces = b.getInt(24); secCells = b.getInt(28)
        nStrings = b.getInt(secStrings)
        val nCats = b.getShort(secCats).toInt() and 0xFFFF
        cats = Array(nCats) { i -> Cat.of(string(b.getInt(secCats + 2 + 4 * i))) }
        nCells = b.getInt(secCells)
        folded = arrayOfNulls(nPlaces)
    }

    // ---- raw access ----

    fun string(i: Int): String {
        if (i <= 0 || i >= nStrings) return ""
        val offs = secStrings + 4
        val s = b.getInt(offs + 4 * i); val e = b.getInt(offs + 4 * (i + 1))
        val bytes = ByteArray(e - s)
        val blob = secStrings + 4 + 4 * (nStrings + 1)
        for (k in bytes.indices) bytes[k] = b.get(blob + s + k)
        return String(bytes, Charsets.UTF_8)
    }
    fun lat(i: Int): Double = b.getInt(secPlaces + 20 * i) / 1e6
    fun lon(i: Int): Double = b.getInt(secPlaces + 20 * i + 4) / 1e6
    fun name(i: Int): String = string(b.getInt(secPlaces + 20 * i + 8))
    private fun extraText(i: Int): String = string(b.getInt(secPlaces + 20 * i + 12))
    fun cat(i: Int): Cat? = cats.getOrNull(b.getShort(secPlaces + 20 * i + 16).toInt() and 0xFFFF)

    fun place(i: Int): Place? {
        val c = cat(i) ?: return null
        val extras = LinkedHashMap<String, String>()
        for (line in extraText(i).split('\n')) {
            val eq = line.indexOf('='); if (eq > 0) extras[line.substring(0, eq)] = line.substring(eq + 1)
        }
        return Place(i, name(i), LatLng(lat(i), lon(i)), c, extras)
    }

    private fun cellKey(cellLat: Int, cellLon: Int) = cellLat * 1024 + cellLon
    private fun cellLatOf(lat: Double) = floor((lat - LAT0) / CELL).toInt()
    private fun cellLonOf(lon: Double) = floor((lon - LON0) / CELL).toInt()

    /** The range of place indices in a cell, or null when it is empty. */
    private fun cell(key: Int): IntRange? {
        var lo = 0; var hi = nCells - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = b.getInt(secCells + 4 + 8 * mid)
            if (k < key) lo = mid + 1 else if (k > key) hi = mid - 1 else {
                val first = b.getInt(secCells + 4 + 8 * mid + 4)
                val next = b.getInt(secCells + 4 + 8 * (mid + 1) + 4)
                return first until next
            }
        }
        return null
    }

    class Found(val place: Place, val metres: Double)

    /**
     * The places around a point, nearest first: within `radiusM`, of the given categories (null =
     * all). The radius grows up to `maxM` while fewer than `limit` are found.
     */
    fun nearby(lat: Double, lon: Double, radiusM: Double, cats: Set<Cat>?, limit: Int = 40, maxM: Double = 60_000.0): List<Found> {
        var r = radiusM
        while (true) {
            val found = within(lat, lon, r, cats)
            if (found.size >= limit || r >= maxM) return found.take(limit)
            r *= 2
        }
    }

    private fun within(lat: Double, lon: Double, r: Double, cats: Set<Cat>?): List<Found> {
        val dLat = r / 110_540.0
        val dLon = r / (111_320.0 * cos(Math.toRadians(lat)))
        val out = ArrayList<Found>()
        for (cl in cellLatOf(lat - dLat)..cellLatOf(lat + dLat)) for (co in cellLonOf(lon - dLon)..cellLonOf(lon + dLon)) {
            val range = cell(cellKey(cl, co)) ?: continue
            for (i in range) {
                val c = cat(i) ?: continue
                if (cats != null && c !in cats) continue
                val d = distance(lat, lon, lat(i), lon(i))
                if (d <= r) place(i)?.let { out.add(Found(it, d)) }
            }
        }
        out.sortBy { it.metres }
        return out
    }

    /** Folds every name once (a second or two for the country), so the first search answers at once. */
    fun warm() { for (i in 0 until nPlaces) if (folded[i] == null) folded[i] = Transit.fold(name(i)) }

    /** The places whose name holds every word of `q`, nearest to the point first. */
    fun search(q: String, lat: Double, lon: Double, limit: Int = 30): List<Found> {
        val words = Transit.fold(q).split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Found>()
        for (i in 0 until nPlaces) {
            val n = folded[i] ?: Transit.fold(name(i)).also { folded[i] = it }
            if (n.isEmpty() || !words.all { n.contains(it) }) continue
            place(i)?.let { out.add(Found(it, distance(lat, lon, it.at.latitude, it.at.longitude))) }
        }
        // a name that starts with the first word comes before one that merely holds it, then by distance
        return out.sortedWith(compareBy({ !folded[it.place.index]!!.startsWith(words[0]) }, { it.metres })).take(limit)
    }

    /** Every place of the given categories inside a box (for the map), at most `limit`. */
    fun inBox(south: Double, west: Double, north: Double, east: Double, cats: Set<Cat>?, limit: Int = 600): List<Place> {
        val out = ArrayList<Place>()
        for (cl in cellLatOf(south)..cellLatOf(north)) for (co in cellLonOf(west)..cellLonOf(east)) {
            val range = cell(cellKey(cl, co)) ?: continue
            for (i in range) {
                val c = cat(i) ?: continue
                if (cats != null && c !in cats) continue
                val la = lat(i); val lo = lon(i)
                if (la < south || la > north || lo < west || lo > east) continue
                place(i)?.let { out.add(it) }
                if (out.size >= limit) return out
            }
        }
        return out
    }
}
