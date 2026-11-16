package com.example.sudoku

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks the project's GitHub Releases for a newer version than the one installed. */
object Update {
    private const val REPO = "agenga0320/sudoku"

    /** [url] downloads the APK directly, or opens the release page if no APK is attached. */
    class Release(val version: String, val url: String)

    /** The latest release; null when offline or when there is none. Blocks. */
    fun latest(): Release? = runCatching {
        val connection =
            URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = release.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0))
                .map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
                ?.takeIf { it.isNotEmpty() }
            Release(
                release.getString("tag_name").trimStart('v', 'V'),
                apk ?: release.optString("html_url", "https://github.com/$REPO/releases/latest"),
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Compares versions such as "1.10.0" and "1.9" part by part, as numbers. */
    fun isNewer(remote: String, local: String): Boolean {
        val a = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
