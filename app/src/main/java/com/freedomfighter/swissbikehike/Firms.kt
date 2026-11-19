package com.freedomfighter.swissbikehike

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.Normalizer
import java.util.Locale

/**
 * The firms of the Swiss commercial register (Zefix), each with its registered address and a
 * position: the pack built by tools/make-firms.py, read in place. A name is found through the
 * pack's index of words (sorted, so a word or the start of one is a binary search); nothing is
 * loaded but the rows that answer.
 *
 * The register knows a firm by its legal name (« Ardentis Clinique Dentaire Lausanne SA »), not
 * by the sign on its door: shops and restaurants are rather found among the places of
 * OpenStreetMap. [same] tells when a firm and a place (or a directory entry) are one and the
 * same, so that it is listed once.
 */
class Firms private constructor(private val b: ByteBuffer) {
    /** One firm: its legal name, its registered address, where it is. `exact` = placed at its building or in its street (else at the middle of its postcode: the address is then looked up when the firm is picked). */
    class Hit(val name: String, val street: String, val zip: String, val city: String, val lat: Double, val lon: Double, val exact: Boolean, val allInName: Boolean = true) {
        val address: String get() = listOf(street, listOf(zip, city).filter { it.isNotBlank() }.joinToString(" ")).filter { it.isNotBlank() }.joinToString(", ")
    }

    val built: String
    val nFirms: Int
    private val nTokens: Int; private val nLocs: Int
    private val secStrings: Int; private val secFirms: Int; private val secLocs: Int; private val secTokens: Int; private val secPostings: Int

    init {
        require(b.getInt(0) == MAGIC) { "not a firms pack" }
        require(b.getInt(4) == 1) { "firms pack of another version" }
        val d = b.getInt(8); built = "%04d-%02d-%02d".format(d / 10000, d / 100 % 100, d % 100)
        nFirms = b.getInt(12); nTokens = b.getInt(16); nLocs = b.getInt(20)
        secStrings = b.getInt(24); secFirms = b.getInt(28); secLocs = b.getInt(32); secTokens = b.getInt(36); secPostings = b.getInt(40)
    }

    private fun string(off: Int): String {
        val at = secStrings + off
        val n = b.getShort(at).toInt() and 0xFFFF
        val bytes = ByteArray(n)
        // read in one go, through a view of its own (the buffer's position is shared between threads)
        b.duplicate().apply { position(at + 2) }.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun token(i: Int): String = string(b.getInt(secTokens + 12 * i))
    private fun postingsAt(i: Int): Int = b.getInt(secTokens + 12 * i + 4)
    private fun count(i: Int): Int = b.getInt(secTokens + 12 * i + 8)

    /** The first word of the index that is not before `w`. */
    private fun lowerBound(w: String): Int {
        var lo = 0; var hi = nTokens
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (token(mid) < w) lo = mid + 1 else hi = mid }
        return lo
    }

    /** The words of the index that start with `w` (the word itself only, for a word of two letters). */
    private fun range(w: String): IntRange {
        val from = lowerBound(w)
        val to = if (w.length < 3) (if (from < nTokens && token(from) == w) from + 1 else from) else lowerBound(w + "\u007f")
        return from until to
    }

    fun hit(i: Int, allInName: Boolean = true): Hit {
        val at = secFirms + 24 * i
        val loc = b.getShort(at + 18).toInt() and 0xFFFF
        return Hit(string(b.getInt(at)), string(b.getInt(at + 4)), "%04d".format(b.getShort(at + 16).toInt() and 0xFFFF),
            if (loc < nLocs) string(b.getInt(secLocs + 4 * loc)) else "",
            b.getInt(at + 8) / 1e6, b.getInt(at + 12) / 1e6, (b.getShort(at + 20).toInt() and 0xFFFF) <= 1, allInName)
    }

    /**
     * The firms whose name holds every typed word (a word may be only begun: « ardent »). A word
     * that is not in the name may be the firm's town (« ardentis vevey »): such firms follow the
     * others. Nearest to the given point first. Words that say nothing (« sa », « de ») are not asked.
     */
    fun search(text: String, lat: Double, lon: Double, limit: Int = 15): List<Hit> {
        val words = words(text)
        if (words.isEmpty()) return emptyList()
        // a firm must hold at least one of the words in its name: each word leads in turn (the
        // rarest first), its firms are read, and the other words are looked for in each, in the
        // name or else in the town. A word borne by tens of thousands of names (« garage ») does
        // not lead unless it is alone with nothing else to go by.
        val ranges = words.map { w -> range(w) }
        val sizes = ranges.map { r -> r.sumOf { count(it).toLong() } }
        val leads = words.indices.filter { sizes[it] in 1..MAX_READ.toLong() }.sortedBy { sizes[it] }
        if (leads.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Hit, Double>>()
        val seen = HashSet<Int>()
        for (lead in leads) {
            for (t in ranges[lead]) {
                val p = secPostings + 4 * postingsAt(t)
                for (k in 0 until count(t)) {
                    val id = b.getInt(p + 4 * k)
                    if (!seen.add(id)) continue
                    val at = secFirms + 24 * id
                    val nameWords = tokens(string(b.getInt(at)))
                    var inName = 0; var ok = true
                    var town: List<String>? = null
                    for ((j, w) in words.withIndex()) {
                        if (j == lead || nameWords.any { it.startsWith(w) }) { inName++; continue }
                        if (town == null) {
                            val loc = b.getShort(at + 18).toInt() and 0xFFFF
                            town = fold(if (loc < nLocs) string(b.getInt(secLocs + 4 * loc)) else "").split(' ')
                        }
                        if (town.none { it.startsWith(w) }) { ok = false; break }
                    }
                    if (!ok) continue
                    val h = hit(id, inName == words.size)
                    out.add(h to Places.distance(lat, lon, h.lat, h.lon))
                }
            }
            if (seen.size > 3 * MAX_READ) break
        }
        return out.sortedWith(compareBy({ !it.first.allInName }, { it.second })).take(limit).map { it.first }
    }

    companion object {
        private const val MAGIC = 0x46484253 // "SBHF" little-endian
        private const val MAX_READ = 20_000

        fun open(f: File): Firms = RandomAccessFile(f, "r").use { raf ->
            Firms(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN))
        }
        fun of(bytes: ByteArray): Firms = Firms(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))

        /** The legal forms and the small words around them: they say nothing about which firm it is. The same list as in tools/make-firms.py. */
        val STOP: Set<String> = ("sa sarl ag gmbh sagl ltd llc inc co cie kg snc sas se sc scrl ab bv nv plc corp " +
            "en in liquidation liq liquidazione radiation " +
            "de du des la le les l d et and und der die das den dem von vom zum zur am im " +
            "di del della dei delle il lo e a au aux the of for fur pour per con").split(' ').toSet()

        private val MARKS = Regex("\\p{M}+")
        private val NOT_WORD = Regex("[^a-z0-9]+")

        /** Lower case, no accents; everything that is not a letter or a digit separates words (as tools/make-firms.py does). */
        fun fold(s: String): String =
            NOT_WORD.replace(MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT), " ").trim()

        /** The words of a name that tell firms apart. */
        fun tokens(name: String): List<String> = fold(name).split(' ').filter { it.length >= 2 && it !in STOP }
        /** The typed words that can be looked for. */
        fun words(text: String): List<String> = tokens(text).distinct()

        /** A name without its legal form and small words, as one string: « Aimé Pouly SA » and « Aimé Pouly » give the same. */
        fun nameKey(name: String): String = tokens(name).joinToString(" ")

        /** The street without its number, folded: « Rue du Collège 3 » → « rue du college ». */
        fun streetKey(street: String): String =
            fold(street).split(' ').filter { w -> w.isNotEmpty() && !w[0].isDigit() }.joinToString(" ")

        /**
         * Are these two entries the same firm? The names agree (equal once the legal form is gone,
         * or all the words of the shorter are words of the longer), and nothing about the place
         * says otherwise: the postcodes when both are known, the streets when both are known. With
         * neither postcode nor street on one side, the towns must agree; names alone are not enough
         * (« Ardentis » in Lausanne and in Vevey are two).
         */
        fun same(nameA: String, streetA: String, zipA: String, cityA: String, nameB: String, streetB: String, zipB: String, cityB: String): Boolean {
            val a = tokens(nameA); val b = tokens(nameB)
            if (a.isEmpty() || b.isEmpty()) return false
            val (short, long) = if (a.size <= b.size) a to b else b to a
            if (!long.containsAll(short)) return false
            val zipsKnown = zipA.isNotBlank() && zipB.isNotBlank()
            if (zipsKnown && zipA.trim() != zipB.trim()) return false
            val sa = streetKey(streetA); val sb = streetKey(streetB)
            val streetsKnown = sa.isNotEmpty() && sb.isNotEmpty()
            if (streetsKnown) return sa == sb || sa.startsWith(sb) || sb.startsWith(sa)
            if (zipsKnown) return true
            val ca = fold(cityA); val cb = fold(cityB)
            return ca.isNotEmpty() && cb.isNotEmpty() && (ca == cb || ca.startsWith("$cb ") || cb.startsWith("$ca "))
        }

        /** Two points this close are one address (a firm and the place of OpenStreetMap at its door). */
        const val SAME_PLACE_M = 60.0

        /** `text` read as a Swiss address copied from elsewhere (« Rue du Collège 3, 1800 Vevey », lines or commas), or null. */
        fun copiedAddress(text: String): Copied? {
            if (text.length > 400) return null
            val parts = text.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            val zipLine = Regex("^(?:CH[- ]?)?([1-9]\\d{3})\\s+(\\p{L}[\\p{L} .'’()/-]{1,40})$")
            for ((i, p) in parts.withIndex()) {
                val m = zipLine.find(p) ?: continue
                val zip = m.groupValues[1]; val city = m.groupValues[2].trim()
                // the street is the line before, when it reads as one: letters and, usually, a number
                val street = parts.getOrNull(i - 1)?.takeIf { s -> s.length <= 60 && s.any { it.isLetter() } && !zipLine.matches(s) && !s.contains('@') && !Regex("^\\+?[\\d ()./-]{7,}$").matches(s) } ?: ""
                val name = if (street.isNotEmpty()) parts.getOrNull(i - 2)?.takeIf { it.length <= 80 && !it.contains('@') } ?: "" else ""
                return Copied(name, street, zip, city)
            }
            return null
        }
    }

    /** An address found in a copied text. */
    class Copied(val name: String, val street: String, val zip: String, val city: String) {
        val address: String get() = listOf(street, "$zip $city").filter { it.isNotBlank() }.joinToString(", ")
    }
}
