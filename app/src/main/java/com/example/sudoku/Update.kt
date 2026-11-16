package com.example.sudoku

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks the project's GitHub Releases for a newer version than the one installed. */
object Update {
    private const val REPO = "agenga0320/sudoku"
    private const val DOWNLOAD = "https://github.com/$REPO/releases/download/"
    const val MIME = "application/vnd.android.package-archive"

    class Release(val version: String, val url: String)

    /** The file a release of [version] must attach, as the build names it. */
    fun apkName(version: String) = "Sudoku-v$version-release.apk"

    /**
     * True if [url] is the APK of [version] in this project's releases and nothing else,
     * so that only a file from there is ever downloaded and offered for installation.
     */
    fun isTrusted(version: String, url: String) =
        Regex("""\d+\.\d+\.\d+""").matches(version) && url == "${DOWNLOAD}v$version/${apkName(version)}"

    /** The latest release if it is newer than [installed]; null when offline or up to date. Blocks. */
    fun latest(installed: String): Release? = runCatching {
        val connection =
            URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 12000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = release.getString("tag_name").trimStart('v', 'V')
            val assets = release.getJSONArray("assets")
            val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == apkName(version) && it.optString("state") == "uploaded" }
                ?.optString("browser_download_url")
            if (url != null && isTrusted(version, url) && isNewer(version, installed)) Release(version, url) else null
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

/**
 * Downloads a release's APK with the system download manager, which keeps going in the
 * background and survives the app being closed. Remembers the one download in progress.
 */
class UpdateDownload(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("apk_downloads", Context.MODE_PRIVATE)

    init {
        // Once the downloaded version (or a newer one) is installed, the file is no longer needed.
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        if (saved.version.isNotEmpty() && !Update.isNewer(saved.version, installed)) {
            if (id >= 0) manager.remove(id)
            prefs.edit().clear().apply()
        }
    }

    val id: Long get() = prefs.getLong("id", -1)
    val saved: Update.Release get() = Update.Release(prefs.getString("version", "")!!, prefs.getString("url", "")!!)

    /** [status] is a DownloadManager.STATUS_ value, or -1 when the download is unknown. */
    class Progress(val status: Int, val reason: Int, val bytes: Long, val total: Long) {
        val active: Boolean
            get() = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING ||
                status == DownloadManager.STATUS_PAUSED
    }

    fun progress(id: Long): Progress {
        if (id >= 0) {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return Progress(
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    )
                }
            }
        }
        return Progress(-1, 0, 0, -1)
    }

    /** Starts downloading [release], or returns the download of it that is already under way or done. */
    fun enqueue(release: Update.Release): Long {
        require(Update.isTrusted(release.version, release.url))
        val previous = id
        val status = progress(previous).status
        if (release.url == saved.url && status != -1 && status != DownloadManager.STATUS_FAILED) return previous

        val file = Update.apkName(release.version)
        val request = DownloadManager.Request(Uri.parse(release.url))
            .setTitle(file)
            .setDescription(context.getString(R.string.update_notification))
            .setMimeType(Update.MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "${System.currentTimeMillis()}-$file")
        val id = manager.enqueue(request)
        prefs.edit().putLong("id", id).putString("url", release.url).putString("version", release.version).apply()
        if (previous >= 0) manager.remove(previous)
        return id
    }

    fun downloaded(id: Long): Uri? = manager.getUriForDownloadedFile(id)
}
