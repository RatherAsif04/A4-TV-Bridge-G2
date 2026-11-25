package dev.a4tvbridge.independent

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

internal enum class ReleaseApp(val title: String, val repository: String, val packageName: String) {
    SMART_TUBE("SmartTube", "yuliskov/SmartTube", "org.smarttube.stable"),
    JELLYFIN("Jellyfin for Android TV", "jellyfin/jellyfin-androidtv", "org.jellyfin.androidtv"),
    AERIAL("Aerial Views", "theothernt/AerialViews", "com.neilturner.aerialviews"),
}

internal fun downloadOfficialRelease(context: Context, app: ReleaseApp, abis: List<String>): File {
    val index = get(URL("https://api.github.com/repos/${app.repository}/releases/latest"), setOf("api.github.com")) { response ->
        response.inputStream.use { stream ->
            val bytes = java.io.ByteArrayOutputStream()
            val part = ByteArray(8192)
            while (true) {
                val size = stream.read(part)
                if (size < 0) break
                check(bytes.size() + size <= 2 * 1024 * 1024) { "The release index exceeded the download limit." }
                bytes.write(part, 0, size)
            }
            JSONObject(bytes.toString("UTF-8"))
        }
    }
    check(!index.optBoolean("draft") && !index.optBoolean("prerelease")) { "No stable release is available." }
    val assets = index.getJSONArray("assets")
    val files = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { file ->
        val name = file.optString("name")
        val link = file.optString("browser_download_url")
        name.matches(Regex("[A-Za-z0-9_.-]+\\.apk", RegexOption.IGNORE_CASE)) &&
            link.startsWith("https://github.com/${app.repository}/releases/download/") && link.endsWith("/$name") &&
            file.optLong("size") in 1..(200L * 1024 * 1024) &&
            (file.optString("digest").isBlank() || file.optString("digest").matches(Regex("sha256:[a-fA-F0-9]{64}")))
    }
    val chosen = when (app) {
        ReleaseApp.SMART_TUBE -> (abis + "universal").firstNotNullOfOrNull { abi ->
            files.firstOrNull { it.getString("name").startsWith("SmartTube_stable_", true) && it.getString("name").endsWith("_$abi.apk") }
        }
        ReleaseApp.JELLYFIN -> files.firstOrNull { it.getString("name").startsWith("jellyfin-androidtv-") && it.getString("name").endsWith("-release.apk") }
        ReleaseApp.AERIAL -> files.firstOrNull { it.getString("name").startsWith("aerial-views-") }
    } ?: error("The release has no verified APK for this TV.")
    val hash = MessageDigest.getInstance("SHA-256")
    val limit = chosen.getLong("size")
    var bytes = 0L
    val file = File.createTempFile("verified-", ".apk", context.cacheDir)
    try {
        get(URL(chosen.getString("browser_download_url")), setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { response ->
            file.outputStream().use { output ->
                response.inputStream.use { input ->
                    val buffer = ByteArray(32_768)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        check(bytes <= limit) { "APK exceeded its published size." }
                        hash.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val publishedDigest = chosen.optString("digest")
        check(bytes == limit && (publishedDigest.isBlank() || "sha256:$digest".equals(publishedDigest, ignoreCase = true))) {
            "The release checksum did not match."
        }
        check(context.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName == app.packageName) { "The APK has an unexpected package ID." }
        return file
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}

private fun <T> get(start: URL, trustedHosts: Set<String>, read: (HttpURLConnection) -> T): T {
    var url = start
    repeat(6) {
        check(url.protocol == "https" && url.host in trustedHosts && url.userInfo == null && url.port in setOf(-1, 443)) {
            "The release server returned an unexpected URL."
        }
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "A4-TV-Bridge/${BuildConfig.VERSION_NAME}")
        if (url.host == "api.github.com") {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
        }
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        try {
            val status = connection.responseCode
            if (status == 200) return read(connection)
            check(status in setOf(301, 302, 303, 307, 308)) { "GitHub returned HTTP $status." }
            url = URL(url, connection.getHeaderField("Location") ?: error("GitHub returned an empty redirect."))
        } finally {
            connection.disconnect()
        }
    }
    error("GitHub redirected the download too many times.")
}
