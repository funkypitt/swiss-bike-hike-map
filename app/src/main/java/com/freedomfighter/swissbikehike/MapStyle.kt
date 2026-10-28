package com.freedomfighter.swissbikehike

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Builds the one map style: swisstopo's vector base map with the clutter removed, the official trail
 * network drawn in the signpost colours, the official cycling routes, the closures, the paper map,
 * the route and the pins. Everything is in a single style so the same JSON can be saved offline.
 */
object MapStyle {
    const val STYLE_URL = "https://vectortiles.geo.admin.ch/styles/ch.swisstopo.basemap.vt/style.json"
    private const val WMTS = "https://wmts.geo.admin.ch/1.0.0/%s/default/current/3857/{z}/{x}/{y}.%s"
    private const val WMS = "https://wms.geo.admin.ch/?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=%s" +
        "&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=true&STYLES="

    /** Layers of the swisstopo style that are noise for a sport map. */
    private val clutter = setOf(
        "housenumber", "poi_rank2", "poi_rank3_rotation_flat", "poi_lm", "poi_motorway", "area_name_field")

    // Official signpost colours
    const val YELLOW = "#FFD500"
    const val RED = "#E30613"
    const val BLUE = "#0063C6"
    const val MAGENTA = "#E6007E"

    const val SRC_ROUTE = "route"
    const val SRC_TRACK = "track"
    const val SRC_PIN = "pin"
    const val IMG_PIN = "pin"

    /** Layer ids by group, so visibility can be switched without rebuilding. */
    val baseLayers = ArrayList<String>()     // swisstopo vector layers (hidden in paper mode)
    val paperLayers = listOf("paper")
    val walkLayers = listOf("trail-hiking-casing", "trail-hiking", "trail-mountain", "trail-mountain-dash",
        "trail-alpine", "trail-alpine-dash", "closures-walk")
    val bikeLayers = listOf("veloland", "mtbland", "closures-bike", "closures-mtb")

    fun styleFile(ctx: Context) = File(ctx.filesDir, "style.json")
    fun paperFile(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "paper/paper.pmtiles")

    /** The swisstopo style: cached copy refreshed weekly, the asset copy as the last resort. */
    suspend fun baseStyle(ctx: Context): String = withContext(Dispatchers.IO) {
        val cache = File(ctx.filesDir, "basemap-style.json")
        val stale = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > 7L * 86400_000
        if (stale) try {
            val c = URL(STYLE_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000
            val text = c.inputStream.bufferedReader().readText()
            c.disconnect()
            if (text.contains("\"layers\"")) cache.writeText(text)
        } catch (_: Exception) { }
        if (cache.exists()) cache.readText() else ctx.assets.open("basemap-style.json").bufferedReader().readText()
    }

    fun build(ctx: Context, base: String): String {
        val s = JSONObject(base)
        val sources = s.getJSONObject("sources")
        val baseSrc = sources.keys().asSequence()
            .first { sources.getJSONObject(it).optString("url").contains("ch.swisstopo.base.vt") }

        // --- sources ---
        val paper = paperFile(ctx)
        sources.put("paper", raster(
            if (paper.exists()) "pmtiles://file://${paper.absolutePath}" else WMTS.format("ch.swisstopo.pixelkarte-farbe", "jpeg"),
            128, 18))
        sources.put("veloland", raster(WMTS.format("ch.astra.veloland", "png"), 128, 16))
        sources.put("mtbland", raster(WMTS.format("ch.astra.mountainbikeland", "png"), 128, 16))
        sources.put("closures-walk", raster(WMS.format("ch.astra.wanderland-sperrungen_umleitungen"), 256, 14))
        sources.put("closures-bike", raster(WMS.format("ch.astra.veloland-sperrungen_umleitungen"), 256, 14))
        sources.put("closures-mtb", raster(WMS.format("ch.astra.mountainbikeland-sperrungen_umleitungen"), 256, 14))
        for (id in listOf(SRC_ROUTE, SRC_TRACK, SRC_PIN))
            sources.put(id, JSONObject().put("type", "geojson").put("data", emptyCollection()))

        // --- layers: prune, remember, insert ---
        val old = s.getJSONArray("layers")
        val layers = JSONArray()
        baseLayers.clear()
        // Insert our layers where the label block starts: above every road and building fill,
        // below the names. (The style's first symbol layer, landcover_pt, comes before the roads.)
        val anchors = listOf("housenumber", "contour_line_pt", "transportation_label", "place_other")
        var firstSymbol = -1
        for (i in 0 until old.length()) {
            val l = old.getJSONObject(i)
            val id = l.getString("id")
            if (firstSymbol < 0 && id in anchors) firstSymbol = layers.length()
            if (id in clutter) continue
            if (id != "background") baseLayers.add(id)
            layers.put(l)
        }
        if (firstSymbol < 0) firstSymbol = layers.length()

        // Below the labels: paper (just above background), then official routes and trails.
        val below = JSONArray()
        below.put(rasterLayer("veloland", "veloland", 9))
        below.put(rasterLayer("mtbland", "mtbland", 9))
        below.put(trail("trail-hiking-casing", baseSrc, "hiking", "#5A4A00", widthX(1.6), null))
        below.put(trail("trail-hiking", baseSrc, "hiking", YELLOW, widthX(1.0), null))
        below.put(trail("trail-mountain", baseSrc, "mountain_hiking", RED, widthX(1.0), null))
        below.put(trail("trail-mountain-dash", baseSrc, "mountain_hiking", "#FFFFFF", widthX(0.4), JSONArray().put(2).put(2)))
        below.put(trail("trail-alpine", baseSrc, "alpine_hiking", BLUE, widthX(1.0), null))
        below.put(trail("trail-alpine-dash", baseSrc, "alpine_hiking", "#FFFFFF", widthX(0.4), JSONArray().put(2).put(2)))

        val merged = JSONArray()
        for (i in 0 until layers.length()) {
            if (i == 1) merged.put(rasterLayer("paper", "paper", 0))
            if (i == firstSymbol) for (j in 0 until below.length()) merged.put(below.get(j))
            merged.put(layers.get(i))
        }
        if (firstSymbol >= layers.length()) for (j in 0 until below.length()) merged.put(below.get(j))

        // Above the labels: closures, track, route, pin.
        merged.put(rasterLayer("closures-walk", "closures-walk", 10))
        merged.put(rasterLayer("closures-bike", "closures-bike", 10))
        merged.put(rasterLayer("closures-mtb", "closures-mtb", 10))
        merged.put(line("track-line", SRC_TRACK, "#000000", 3.0, JSONArray().put(1).put(2)))
        merged.put(line("route-casing", SRC_ROUTE, "#FFFFFF", 10.0, null))
        merged.put(line("route-line", SRC_ROUTE, MAGENTA, 6.0, null))
        merged.put(JSONObject().put("id", "pin").put("type", "symbol").put("source", SRC_PIN)
            .put("layout", JSONObject().put("icon-image", IMG_PIN).put("icon-anchor", "bottom")
                .put("icon-allow-overlap", true).put("icon-ignore-placement", true)))
        s.put("layers", merged)
        return s.toString()
    }

    fun emptyCollection(): JSONObject = JSONObject().put("type", "FeatureCollection").put("features", JSONArray())

    private fun raster(url: String, tileSize: Int, maxZoom: Int) = JSONObject()
        .put("type", "raster").put("tiles", JSONArray().put(url)).put("tileSize", tileSize)
        .put("minzoom", 6).put("maxzoom", maxZoom).put("attribution", "© swisstopo")

    private fun rasterLayer(id: String, src: String, minZoom: Int) = JSONObject()
        .put("id", id).put("type", "raster").put("source", src).put("minzoom", minZoom)
        .put("layout", JSONObject().put("visibility", "none"))
        .put("paint", JSONObject().put("raster-fade-duration", 0))

    /** Line width that grows with zoom: 1.5 px at z10, 3 at z14, 6 at z17, times a factor. */
    private fun widthX(f: Double): JSONArray = JSONArray().put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
        .put(10).put(1.5 * f).put(14).put(3.0 * f).put(17).put(6.0 * f)

    private fun trail(id: String, src: String, sac: String, color: String, width: JSONArray, dash: JSONArray?): JSONObject {
        val paint = JSONObject().put("line-color", color).put("line-width", width)
        if (dash != null) paint.put("line-dasharray", dash)
        return JSONObject().put("id", id).put("type", "line").put("source", src).put("source-layer", "transportation")
            .put("minzoom", 9)
            .put("filter", JSONArray().put("==").put(JSONArray().put("get").put("sac_scale")).put(sac))
            .put("layout", JSONObject().put("line-cap", if (dash == null) "round" else "butt").put("line-join", "round").put("visibility", "none"))
            .put("paint", paint)
    }

    private fun line(id: String, src: String, color: String, width: Double, dash: JSONArray?): JSONObject {
        val paint = JSONObject().put("line-color", color).put("line-width", width)
        if (dash != null) paint.put("line-dasharray", dash)
        return JSONObject().put("id", id).put("type", "line").put("source", src)
            .put("layout", JSONObject().put("line-cap", "round").put("line-join", "round")).put("paint", paint)
    }
}
