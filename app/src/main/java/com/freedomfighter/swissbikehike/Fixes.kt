package com.freedomfighter.swissbikehike

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Where the position comes from. The satellites give the precise fix the map lives on, but indoors
 * (where one taps an address) they say nothing for minutes, and the last satellite fix is often
 * gone after a reboot. So the map also listens to the phone's own position (fused / network):
 * a rough point at once, replaced by the satellites as soon as they speak.
 */
object Fixes {
    /** A satellite fix this recent silences the rough providers. */
    private const val GPS_RULES_MS = 10_000L

    fun fine(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun coarse(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun any(ctx: Context) = fine(ctx) || coarse(ctx)

    /** The providers worth listening to, the satellites first. */
    fun providers(ctx: Context, lm: LocationManager): List<String> {
        val all = lm.allProviders
        val out = ArrayList<String>()
        if (fine(ctx) && LocationManager.GPS_PROVIDER in all) out.add(LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in all) out.add(LocationManager.FUSED_PROVIDER)
        if (LocationManager.NETWORK_PROVIDER in all) out.add(LocationManager.NETWORK_PROVIDER)
        return out
    }

    /** Listens to every provider; errors of one provider (disabled, missing) do not stop the others. */
    @SuppressLint("MissingPermission")
    fun listen(ctx: Context, lm: LocationManager, intervalMs: Long, l: LocationListener) {
        for (p in providers(ctx, lm)) try { lm.requestLocationUpdates(p, intervalMs, 0f, l) } catch (_: Exception) {}
    }

    /** The freshest position any provider remembers, or null. */
    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context, lm: LocationManager): Location? {
        if (!any(ctx)) return null
        var best: Location? = null
        for (p in providers(ctx, lm)) {
            val l = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        return best
    }

    fun isGps(l: Location) = l.provider == LocationManager.GPS_PROVIDER

    /** True when a rough fix should be dropped because the satellites spoke a moment ago. */
    fun shadowed(l: Location, lastGpsAt: Long) = !isGps(l) && System.currentTimeMillis() - lastGpsAt < GPS_RULES_MS

    /** Only a precise fix may give a course from the last few metres (rough points jump). */
    fun precise(l: Location) = !l.hasAccuracy() || l.accuracy <= 30f
}
