package com.freedomfighter.swissbikehike

import android.content.Context
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.io.File

class RoutingException(msg: String) : Exception(msg)

/** On-device routing with the embedded BRouter engine. */
object Router {
    class Result(
        val points: List<LatLng>,
        val elevations: DoubleArray,
        val distanceM: Int,
        val ascendM: Int,
        val descendM: Int,
        val seconds: Int,
    )

    private val assetFiles = listOf("lookups.dat", "trekking.brf", "hiking-mountain.brf")

    /** Profiles are copied from the assets once; the parent folder is also where BRouter looks for debug switches. */
    fun profilesDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "brouter/profiles").apply { mkdirs() }
        for (name in assetFiles) {
            val f = File(dir, name)
            val bytes = ctx.assets.open("brouter/$name").use { it.readBytes() }
            if (!f.exists() || f.length() != bytes.size.toLong()) f.writeBytes(bytes)
        }
        return dir
    }

    suspend fun route(ctx: Context, mode: Mode, from: LatLng, to: LatLng): Result = withContext(Dispatchers.Default) {
        val rc = RoutingContext()
        rc.localFunction = File(profilesDir(ctx), "${mode.profile}.brf").absolutePath
        val maxMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
        rc.memoryclass = (maxMb / 3).coerceIn(32, 256)
        rc.turnInstructionMode = 0
        val wps = RoutingParamCollector().readPositions(
            doubleArrayOf(from.longitude, to.longitude), doubleArrayOf(from.latitude, to.latitude))
        val engine = RoutingEngine(null, null, Segments.dir(ctx), wps, rc)
        engine.quite = true
        engine.doRun(90_000)
        engine.errorMessage?.let { throw RoutingException(it) }
        val track = engine.foundTrack ?: throw RoutingException("no track")
        val pts = ArrayList<LatLng>(track.nodes.size)
        val ele = DoubleArray(track.nodes.size)
        var descend = 0.0
        var ref = Double.NaN
        track.nodes.forEachIndexed { i, n ->
            pts.add(LatLng(n.iLat / 1e6 - 90.0, n.iLon / 1e6 - 180.0))
            ele[i] = n.elev
            // descent with a 10 m hysteresis, so GPS-like noise in the model does not inflate it
            if (ref.isNaN()) ref = ele[i]
            if (ele[i] > ref) ref = ele[i]
            if (ref - ele[i] >= 10.0) { descend += ref - ele[i]; ref = ele[i] }
        }
        Result(pts, ele, track.distance, track.ascend, descend.toInt(), track.totalSeconds)
    }
}
