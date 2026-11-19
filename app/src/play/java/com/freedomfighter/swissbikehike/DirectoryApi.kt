package com.freedomfighter.swissbikehike

/**
 * The Google Play build asks nothing of the directory of search.ch: people and firms that the
 * register and OpenStreetMap do not know are looked up on the directory's own website, in the
 * browser. (The builds published elsewhere hold the request, with the person's own key.)
 */
object DirectoryApi {
    const val AVAILABLE = false
    const val ORDER_URL = ""
    @Suppress("UNUSED_PARAMETER")
    fun fetch(key: String, text: String, where: String = ""): List<Directory.Hit> = emptyList()
}
