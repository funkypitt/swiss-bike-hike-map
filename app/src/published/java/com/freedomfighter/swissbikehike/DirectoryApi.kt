package com.freedomfighter.swissbikehike

/**
 * The published builds (F-Droid, GitHub, Google Play) ask nothing of the directory of search.ch:
 * people and firms that the register and OpenStreetMap do not know are looked up on the
 * directory's own website, in a browser tab, and the entry shown there is put on the map.
 */
object DirectoryApi {
    const val AVAILABLE = false
    const val ORDER_URL = ""
    @Suppress("UNUSED_PARAMETER")
    fun fetch(key: String, text: String, where: String = ""): List<Directory.Hit> = emptyList()
}
