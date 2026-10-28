package com.freedomfighter.swissbikehike

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Keeps the layout out of the status and navigation bars (edge-to-edge is forced from Android 15). */
object Insets {
    fun pad(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }
    }
}
