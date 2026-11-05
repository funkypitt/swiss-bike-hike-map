package com.freedomfighter.swissbikehike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The closures and diversions of SwitzerlandMobility (ASTRA live layers, drawn by MapStyle as WMS):
 * what lies under a tap, read from the GeoAdmin identify service.
 */
object Closures {

    data class Closure(
        val id: String,
        val detour: Boolean,
        val kind: String,
        val title: String,
        val reason: String,
        val duration: String,
        val text: String,
        val source: String,
        val link: String,
    )

    const val WALK = "ch.astra.wanderland-sperrungen_umleitungen"
    const val BIKE = "ch.astra.veloland-sperrungen_umleitungen"
    const val MTB = "ch.astra.mountainbikeland-sperrungen_umleitungen"

    /** The service speaks de/fr/it/en; anything else gets English. */
    fun lang(l: String = Locale.getDefault().language): String = if (l in setOf("de", "fr", "it", "en")) l else "en"

    /**
     * What the given layers have within [tolerancePx] of the point, the map being [widthPx]×[heightPx]
     * over the box west/south/east/north. Null when the service could not be reached.
     */
    suspend fun at(
        layers: List<String>, lat: Double, lon: Double,
        west: Double, south: Double, east: Double, north: Double,
        widthPx: Int, heightPx: Int, tolerancePx: Int,
    ): List<Closure>? = withContext(Dispatchers.IO) {
        val lang = lang()
        val url = "https://api3.geo.admin.ch/rest/services/all/MapServer/identify?geometryType=esriGeometryPoint" +
            "&geometry=$lon,$lat&sr=4326&imageDisplay=$widthPx,$heightPx,96&mapExtent=$west,$south,$east,$north" +
            "&tolerance=$tolerancePx&layers=all:" + layers.joinToString(",") + "&returnGeometry=false&lang=$lang"
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000
            val text = c.inputStream.bufferedReader().readText()
            c.disconnect()
            parse(text, lang)
        } catch (e: Exception) { null }
    }

    /** The identify answer, one entry per feature (closures first), in [lang] with a German fallback. */
    fun parse(json: String, lang: String): List<Closure> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = LinkedHashMap<String, Closure>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val a = r.optJSONObject("attributes") ?: r.optJSONObject("properties") ?: continue
            fun s(key: String): String {
                for (k in listOf("${key}_$lang", "${key}_de", key)) {
                    val v = a.opt(k)
                    if (v != null && v != JSONObject.NULL && v.toString().isNotBlank()) return v.toString().trim()
                }
                return ""
            }
            val id = r.optString("layerBodId") + "/" + r.opt("featureId")
            if (id in out) continue
            out[id] = Closure(
                id = id,
                detour = a.optString("sperrungen_type") == "detour",
                kind = s("sperrungen_type"),
                title = s("title"),
                reason = s("reason"),
                duration = s("duration"),
                text = s("abstract"),
                source = s("content_provider"),
                link = s("url1_link").takeIf { it.startsWith("http") } ?: "",
            )
        }
        return out.values.sortedBy { it.detour }
    }
}
