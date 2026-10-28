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
    fun mb(bytes: Long): String = String.format(Locale.US, "%.0f MB", bytes / 1e6)
}
