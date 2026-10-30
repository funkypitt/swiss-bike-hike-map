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
 * network drawn in the signpost colours, the official cycling routes, the closures, the route and
 * the pins. Everything is in a single style so the same JSON can be saved offline.
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
    // Public transport: the lines of the transit mode and of a journey
    const val PT_TRAIN = "#D40000"
    const val PT_TRAM = "#00843D"
    const val PT_BUS = "#7A4A00"
    const val PT_BOAT = "#0070C0"
    const val PT_LIFT = "#8E24AA"

    const val SRC_ROUTE = "route"
    const val SRC_TRACK = "track"
    const val SRC_PIN = "pin"
    const val IMG_PIN = "pin"
    const val SRC_JOURNEY = "journey"
    const val SRC_JOURNEY_PTS = "journey-pts"
    const val SRC_PLACES = "places"
    val placeLayers = listOf("places", "places-name")

    /** Layer ids by group, so visibility can be switched without rebuilding. */
    val walkLayers = listOf("trail-hiking-casing", "trail-hiking", "trail-mountain", "trail-mountain-dash",
        "trail-alpine", "trail-alpine-dash", "closures-walk")
    val bikeLayers = listOf("veloland", "mtbland", "closures-bike", "closures-mtb")
    val transitLayers = listOf("pt-rail-casing", "pt-rail", "pt-tram-casing", "pt-tram", "pt-lift", "pt-lift-dash", "pt-boat")
    val stopLayers = listOf("pt-stops", "pt-stops-name")

    fun styleFile(ctx: Context) = File(ctx.filesDir, "style.json")

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

    fun build(base: String, night: Boolean = false): String {
        val s = JSONObject(base)
        val sources = s.getJSONObject("sources")
        val baseSrc = sources.keys().asSequence()
            .first { sources.getJSONObject(it).optString("url").contains("ch.swisstopo.base.vt") }

        // --- sources ---
        sources.put("veloland", raster(WMTS.format("ch.astra.veloland", "png"), 128, 16))
        sources.put("mtbland", raster(WMTS.format("ch.astra.mountainbikeland", "png"), 128, 16))
        sources.put("closures-walk", raster(WMS.format("ch.astra.wanderland-sperrungen_umleitungen"), 256, 14))
        sources.put("closures-bike", raster(WMS.format("ch.astra.veloland-sperrungen_umleitungen"), 256, 14))
        sources.put("closures-mtb", raster(WMS.format("ch.astra.mountainbikeland-sperrungen_umleitungen"), 256, 14))
        for (id in listOf(SRC_ROUTE, SRC_TRACK, SRC_PIN, SRC_JOURNEY, SRC_JOURNEY_PTS, SRC_PLACES))
            sources.put(id, JSONObject().put("type", "geojson").put("data", emptyCollection()))

        // --- layers: prune, remember, insert ---
        val old = s.getJSONArray("layers")
        val layers = JSONArray()
        // Insert our layers where the label block starts: above every road and building fill,
        // below the names. (The style's first symbol layer, landcover_pt, comes before the roads.)
        val anchors = listOf("housenumber", "contour_line_pt", "transportation_label", "place_other")
        var firstSymbol = -1
        for (i in 0 until old.length()) {
            val l = old.getJSONObject(i)
            val id = l.getString("id")
            if (firstSymbol < 0 && id in anchors) firstSymbol = layers.length()
            if (id in clutter) continue
            layers.put(l)
        }
        if (firstSymbol < 0) firstSymbol = layers.length()

        // Below the labels: official routes and trails.
        val below = JSONArray()
        below.put(rasterLayer("veloland", "veloland", 9))
        below.put(rasterLayer("mtbland", "mtbland", 9))
        below.put(trail("trail-hiking-casing", baseSrc, "hiking", "#5A4A00", widthX(1.6), null))
        below.put(trail("trail-hiking", baseSrc, "hiking", YELLOW, widthX(1.0), null))
        below.put(trail("trail-mountain", baseSrc, "mountain_hiking", RED, widthX(1.0), null))
        below.put(trail("trail-mountain-dash", baseSrc, "mountain_hiking", "#FFFFFF", widthX(0.4), JSONArray().put(2).put(2)))
        below.put(trail("trail-alpine", baseSrc, "alpine_hiking", BLUE, widthX(1.0), null))
        below.put(trail("trail-alpine-dash", baseSrc, "alpine_hiking", "#FFFFFF", widthX(0.4), JSONArray().put(2).put(2)))
        // Public transport, drawn like the trails: the ways in their colour, the stops as dots.
        val notSiding = JSONArray().put("!=").put(JSONArray().put("get").put("service")).put("siding")
        val rail = JSONArray().put("all").put(JSONArray().put("==").put(JSONArray().put("get").put("class")).put("rail")).put(notSiding)
        val tram = JSONArray().put("all").put(JSONArray().put("==").put(JSONArray().put("get").put("class")).put("transit")).put(notSiding)
        val lift = JSONArray().put("in").put(JSONArray().put("get").put("class"))
            .put(JSONArray().put("literal").put(JSONArray().put("cable_car").put("gondola").put("chair_lift").put("aerialway").put("aerialway_transportation")))
        val boat = JSONArray().put("in").put(JSONArray().put("get").put("class")).put(JSONArray().put("literal").put(JSONArray().put("ferry").put("car_ferry")))
        below.put(way("pt-rail-casing", baseSrc, rail, "#FFFFFF", widthX(1.6), null))
        below.put(way("pt-rail", baseSrc, rail, PT_TRAIN, widthX(1.0), null))
        below.put(way("pt-tram-casing", baseSrc, tram, "#FFFFFF", widthX(1.6), null))
        below.put(way("pt-tram", baseSrc, tram, PT_TRAM, widthX(1.0), null))
        below.put(way("pt-lift", baseSrc, lift, PT_LIFT, widthX(0.9), null))
        below.put(way("pt-lift-dash", baseSrc, lift, "#FFFFFF", widthX(0.35), JSONArray().put(3).put(3)))
        below.put(way("pt-boat", baseSrc, boat, PT_BOAT, widthX(0.9), JSONArray().put(2).put(1.5)))
        val stopFilter = JSONArray().put("all")
            .put(JSONArray().put("in").put(JSONArray().put("get").put("class")).put(JSONArray().put("literal")
                .put(JSONArray().put("bus").put("railway").put("ferry_terminal").put("funicular").put("aerialway"))))
            .put(JSONArray().put("!=").put(JSONArray().put("get").put("subclass")).put("stop_position"))
        below.put(JSONObject().put("id", "pt-stops").put("type", "circle").put("source", baseSrc).put("source-layer", "poi")
            .put("minzoom", 12).put("filter", stopFilter).put("layout", JSONObject().put("visibility", "none"))
            .put("paint", JSONObject().put("circle-color", "#FFFFFF").put("circle-stroke-color", "#000000")
                .put("circle-radius", JSONArray().put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom")).put(12).put(2.5).put(16).put(6))
                .put("circle-stroke-width", JSONArray().put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom")).put(12).put(1).put(16).put(2.5))))
        below.put(JSONObject().put("id", "pt-stops-name").put("type", "symbol").put("source", baseSrc).put("source-layer", "poi")
            .put("minzoom", 14).put("filter", stopFilter)
            .put("layout", JSONObject().put("visibility", "none").put("text-field", JSONArray().put("get").put("name"))
                .put("text-font", JSONArray().put("Frutiger Neue Regular")).put("text-size", 11).put("text-anchor", "top")
                .put("text-offset", JSONArray().put(0).put(0.7)).put("text-max-width", 8))
            .put("paint", JSONObject().put("text-color", "#000000").put("text-halo-color", "#FFFFFF").put("text-halo-width", 1.5)))

        val merged = JSONArray()
        for (i in 0 until layers.length()) {
            if (i == firstSymbol) for (j in 0 until below.length()) merged.put(below.get(j))
            merged.put(layers.get(i))
        }
        if (firstSymbol >= layers.length()) for (j in 0 until below.length()) merged.put(below.get(j))

        // Above the labels: closures, track, route, pin.
        merged.put(rasterLayer("closures-walk", "closures-walk", 10))
        merged.put(rasterLayer("closures-bike", "closures-bike", 10))
        merged.put(rasterLayer("closures-mtb", "closures-mtb", 10))
        // The places of the OpenStreetMap pack (when asked for in the menu): a dot in the colour of their group, the name close up.
        merged.put(JSONObject().put("id", "places").put("type", "circle").put("source", SRC_PLACES).put("minzoom", 13)
            .put("layout", JSONObject().put("visibility", "none"))
            .put("paint", JSONObject().put("circle-color", JSONArray().put("get").put("c")).put("circle-stroke-color", "#FFFFFF")
                .put("circle-radius", JSONArray().put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom")).put(13).put(3.5).put(17).put(7))
                .put("circle-stroke-width", 1.5)))
        merged.put(JSONObject().put("id", "places-name").put("type", "symbol").put("source", SRC_PLACES).put("minzoom", 15)
            .put("layout", JSONObject().put("visibility", "none").put("text-field", JSONArray().put("get").put("n"))
                .put("text-font", JSONArray().put("Frutiger Neue Regular")).put("text-size", 11).put("text-anchor", "top")
                .put("text-offset", JSONArray().put(0).put(0.8)).put("text-max-width", 8).put("text-optional", true))
            .put("paint", JSONObject().put("text-color", JSONArray().put("get").put("c")).put("text-halo-color", "#FFFFFF").put("text-halo-width", 1.5)))
        merged.put(line("track-line", SRC_TRACK, "#000000", 3.0, JSONArray().put(1).put(2)))
        merged.put(line("route-casing", SRC_ROUTE, "#FFFFFF", 10.0, null))
        merged.put(line("route-line", SRC_ROUTE, MAGENTA, 6.0, null))
        // A public-transport journey: the rides in their colour over a white casing, the walks dotted.
        merged.put(line("journey-casing", SRC_JOURNEY, "#FFFFFF", 10.0, null))
        merged.put(line("journey-ride", SRC_JOURNEY, JSONArray().put("get").put("c"), 6.0, null)
            .put("filter", JSONArray().put("==").put(JSONArray().put("get").put("w")).put(0)))
        merged.put(line("journey-walk", SRC_JOURNEY, "#000000", 4.0, JSONArray().put(0.5).put(2))
            .put("filter", JSONArray().put("==").put(JSONArray().put("get").put("w")).put(1)))
        merged.put(JSONObject().put("id", "journey-pts").put("type", "circle").put("source", SRC_JOURNEY_PTS)
            .put("paint", JSONObject().put("circle-color", "#FFFFFF").put("circle-stroke-color", "#000000").put("circle-radius", 7).put("circle-stroke-width", 3)))
        merged.put(JSONObject().put("id", "journey-pts-name").put("type", "symbol").put("source", SRC_JOURNEY_PTS)
            .put("layout", JSONObject().put("text-field", JSONArray().put("get").put("n")).put("text-font", JSONArray().put("Frutiger Neue Medium"))
                .put("text-size", 13).put("text-anchor", "top").put("text-offset", JSONArray().put(0).put(0.9)).put("text-max-width", 10))
            .put("paint", JSONObject().put("text-color", "#000000").put("text-halo-color", "#FFFFFF").put("text-halo-width", 2)))
        merged.put(JSONObject().put("id", "pin").put("type", "symbol").put("source", SRC_PIN)
            .put("layout", JSONObject().put("icon-image", IMG_PIN).put("icon-anchor", "bottom")
                .put("icon-allow-overlap", true).put("icon-ignore-placement", true)
                .put("text-field", JSONArray().put("get").put("n")).put("text-font", JSONArray().put("Frutiger Neue Medium"))
                .put("text-size", 16).put("text-offset", JSONArray().put(0).put(-2.9)).put("text-allow-overlap", true).put("text-ignore-placement", true))
            .put("paint", JSONObject().put("text-color", "#000000").put("text-halo-color", "#FFFFFF").put("text-halo-width", 2)))
        s.put("layers", merged)
        if (night) Night.darken(s)
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

    /** A public-transport way of the base map, off until the transit mode shows it. */
    private fun way(id: String, src: String, filter: JSONArray, color: String, width: JSONArray, dash: JSONArray?): JSONObject {
        val paint = JSONObject().put("line-color", color).put("line-width", width)
        if (dash != null) paint.put("line-dasharray", dash)
        return JSONObject().put("id", id).put("type", "line").put("source", src).put("source-layer", "transportation")
            .put("minzoom", 8).put("filter", filter)
            .put("layout", JSONObject().put("line-cap", if (dash == null) "round" else "butt").put("line-join", "round").put("visibility", "none"))
            .put("paint", paint)
    }

    private fun line(id: String, src: String, color: Any, width: Double, dash: JSONArray?): JSONObject {
        val paint = JSONObject().put("line-color", color).put("line-width", width)
        if (dash != null) paint.put("line-dasharray", dash)
        return JSONObject().put("id", id).put("type", "line").put("source", src)
            .put("layout", JSONObject().put("line-cap", "round").put("line-join", "round")).put("paint", paint)
    }
}
