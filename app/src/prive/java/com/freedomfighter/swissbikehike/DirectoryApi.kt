package com.freedomfighter.swissbikehike

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * The request to the directory of search.ch, with the person's own key. This file is of the
 * private build only, which is not published; the published builds (F-Droid, GitHub, Google
 * Play) have another [DirectoryApi] that asks nothing.
 */
object DirectoryApi {
    const val AVAILABLE = true
    private const val ENDPOINT = "https://search.ch/tel/api/"
    const val ORDER_URL = "https://search.ch/tel/api/getkey.html"

    fun fetch(key: String, text: String, where: String = ""): List<Directory.Hit> {
        val lang = Locale.getDefault().language.takeIf { it in setOf("de", "fr", "it", "en") } ?: "fr"
        val url = ENDPOINT + "?was=" + URLEncoder.encode(text, "UTF-8") + (if (where.isBlank()) "" else "&wo=" + URLEncoder.encode(where, "UTF-8")) +
            "&maxnum=20&lang=$lang&key=" + URLEncoder.encode(key, "UTF-8")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 8_000
            setRequestProperty("User-Agent", "swiss-bike-hike-map")
        }
        try {
            val code = c.responseCode
            if (code == 403) throw IllegalStateException("search.ch refused this key")
            if (code !in 200..299) throw IllegalStateException("directory answered $code")
            val xml = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            Directory.tag(xml, "errorMessage")?.let { throw IllegalStateException(it) }
            return Directory.parse(xml)
        } finally { c.disconnect() }
    }
}
