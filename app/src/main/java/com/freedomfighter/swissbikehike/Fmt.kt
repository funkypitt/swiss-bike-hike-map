package com.freedomfighter.swissbikehike

import java.util.Locale

object Fmt {
    fun km(meters: Double): String = when {
        meters < 0 -> "--"
        meters < 10_000 -> String.format(Locale.US, "%.2f", meters / 1000)
        meters < 100_000 -> String.format(Locale.US, "%.1f", meters / 1000)
        else -> String.format(Locale.US, "%.0f", meters / 1000)
    }
    fun hm(seconds: Int): String = String.format(Locale.US, "%d:%02d", seconds / 3600, (seconds % 3600) / 60)
    /** A distance to a place: "320 m", "2.4 km", "12 km". */
    fun dist(m: Double): String = when {
        m < 1000 -> String.format(Locale.US, "%.0f m", m)
        m < 10_000 -> String.format(Locale.US, "%.1f km", m / 1000)
        else -> String.format(Locale.US, "%.0f km", m / 1000)
    }
    fun mb(bytes: Long): String = String.format(Locale.US, "%.0f MB", bytes / 1e6)
}
