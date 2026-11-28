package dev.a4tvbridge.independent

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val APP_GITHUB_REPO = "RatherAsif04/A4-TV-Bridge"
private const val KEY_LAST_UPDATE_CHECK = "update-last-check-ms"
private const val KEY_UPDATE_ETAG = "update-etag"
private const val KEY_UPDATE_CACHED_JSON = "update-cached-json"

internal data class ReleaseAssetInfo(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val digest: String = ""
)

internal data class AppUpdateInfo(
    val tagName: String,
    val version: String,
    val title: String,
    val notes: String,
    val htmlUrl: String,
    val apkAsset: ReleaseAssetInfo?,
    val checksumsUrl: String?
)

internal fun normalizeVersion(raw: String): String =
    raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+').trim()

internal fun compareAppVersions(remote: String, current: String): Int {
    val a = normalizeVersion(remote).split('.').map { it.toIntOrNull() ?: 0 }
    val b = normalizeVersion(current).split('.').map { it.toIntOrNull() ?: 0 }
    val length = maxOf(a.size, b.size)
    for (i in 0 until length) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}

internal fun isNewerVersion(remote: String, current: String): Boolean =
    compareAppVersions(remote, current) > 0

internal fun pickReleaseApkAsset(
    assets: List<ReleaseAssetInfo>,
    repo: String = APP_GITHUB_REPO
): ReleaseAssetInfo? {
    val prefix = "https://github.com/$repo/releases/download/"
    val validApks = assets.filter { asset ->
        asset.name.matches(Regex("[A-Za-z0-9_.-]+\\.apk", RegexOption.IGNORE_CASE)) &&
            !asset.name.contains("unsigned", ignoreCase = true) &&
            asset.downloadUrl.startsWith(prefix) &&
            asset.downloadUrl.endsWith("/${asset.name}") &&
            asset.sizeBytes in 1L..(150L * 1024L * 1024L) &&
            (asset.digest.isBlank() || asset.digest.matches(Regex("sha256:[a-fA-F0-9]{64}")))
    }
    return validApks.firstOrNull { it.name.endsWith("-release.apk", ignoreCase = true) }
        ?: validApks.firstOrNull { !it.name.contains("debug", ignoreCase = true) }
        ?: validApks.firstOrNull()
}

internal fun parseSha256Sums(text: String, fileName: String): String? {
    val regex = Regex("""^([a-fA-F0-9]{64})\s+\*?(\S+)$""")
    for (line in text.lineSequence()) {
        val match = regex.matchEntire(line.trim()) ?: continue
        if (match.groupValues[2] == fileName) {
            return match.groupValues[1].lowercase()
        }
    }
    return null
}

private fun <T> updateHttpGet(
    start: URL,
    trustedHosts: Set<String>,
    ifNoneMatch: String? = null,
    read: (HttpURLConnection) -> T
): T {
    var url = start
    repeat(6) {
        check(url.protocol == "https" && url.host in trustedHosts && url.userInfo == null && url.port in setOf(-1, 443)) {
            "Unexpected update server URL."
        }
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "A4-TV-Bridge/${BuildConfig.VERSION_NAME}")
        if (url.host == "api.github.com") {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (!ifNoneMatch.isNullOrBlank()) {
                conn.setRequestProperty("If-None-Match", ifNoneMatch)
            }
        }
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        try {
            val status = conn.responseCode
            if (status == 200 || status == 304) return read(conn)
            check(status in setOf(301, 302, 303, 307, 308)) { "GitHub returned HTTP $status." }
            url = URL(url, conn.getHeaderField("Location") ?: error("Empty redirect from GitHub."))
        } finally {
            conn.disconnect()
        }
    }
    error("Too many redirects while checking for updates.")
}

private fun readUtf8Limited(conn: HttpURLConnection, limitBytes: Int): String =
    conn.inputStream.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            check(out.size() + n <= limitBytes) { "Release metadata exceeded size limit." }
            out.write(buf, 0, n)
        }
        out.toString("UTF-8")
    }

internal fun parseLatestReleaseJson(body: String, repo: String = APP_GITHUB_REPO): AppUpdateInfo? {
    val json = JSONObject(body)
    if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
    val tag = json.optString("tag_name").trim()
    if (tag.isEmpty()) return null
    val version = normalizeVersion(tag)
    val title = json.optString("name").trim().ifBlank { "A4 TV Bridge $version" }
    val notes = json.optString("body").trim()
    val htmlUrl = json.optString("html_url").trim().ifBlank { "$releaseRoot/releases/tag/$tag" }

    val assetsJson = json.optJSONArray("assets")
    val parsedAssets = mutableListOf<ReleaseAssetInfo>()
    var checksumsUrl: String? = null
    val prefix = "https://github.com/$repo/releases/download/"

    if (assetsJson != null) {
        for (i in 0 until assetsJson.length()) {
            val obj = assetsJson.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            val url = obj.optString("browser_download_url").trim()
            val size = obj.optLong("size", 0L)
            val digest = obj.optString("digest").trim()
            if (name.equals("SHA256SUMS.txt", ignoreCase = true) && url.startsWith(prefix)) {
                checksumsUrl = url
            }
            parsedAssets += ReleaseAssetInfo(name, url, size, digest)
        }
    }

    return AppUpdateInfo(
        tagName = tag,
        version = version,
        title = title,
        notes = notes,
        htmlUrl = htmlUrl,
        apkAsset = pickReleaseApkAsset(parsedAssets, repo),
        checksumsUrl = checksumsUrl
    )
}

internal fun fetchLatestAppRelease(repo: String = APP_GITHUB_REPO): AppUpdateInfo? {
    val body = updateHttpGet(
        URL("https://api.github.com/repos/$repo/releases/latest"),
        setOf("api.github.com")
    ) { readUtf8Limited(it, 1024 * 1024) }
    return parseLatestReleaseJson(body, repo)
}

internal fun fetchLatestAppReleaseCached(
    context: Context,
    repo: String = APP_GITHUB_REPO
): AppUpdateInfo? {
    val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    val savedEtag = prefs.getString(KEY_UPDATE_ETAG, null)
    val savedJson = prefs.getString(KEY_UPDATE_CACHED_JSON, null)
    val ifNoneMatch = if (!savedEtag.isNullOrBlank() && !savedJson.isNullOrBlank()) savedEtag else null

    return try {
        val (status, newEtag, body) = updateHttpGet(
            URL("https://api.github.com/repos/$repo/releases/latest"),
            setOf("api.github.com"),
            ifNoneMatch = ifNoneMatch
        ) { conn ->
            val code = conn.responseCode
            val etag = conn.getHeaderField("ETag")?.trim()
            val text = if (code == 304) null else readUtf8Limited(conn, 1024 * 1024)
            Triple(code, etag, text)
        }
        if (status == 304 && !savedJson.isNullOrBlank()) {
            parseLatestReleaseJson(savedJson, repo)
        } else if (!body.isNullOrBlank()) {
            prefs.edit().apply {
                if (!newEtag.isNullOrBlank()) putString(KEY_UPDATE_ETAG, newEtag)
                putString(KEY_UPDATE_CACHED_JSON, body)
            }.apply()
            parseLatestReleaseJson(body, repo)
        } else {
            null
        }
    } catch (error: Exception) {
        if (!savedJson.isNullOrBlank()) runCatching { parseLatestReleaseJson(savedJson, repo) }.getOrNull()
        else throw error
    }
}

internal fun downloadVerifiedUpdateApk(
    context: Context,
    update: AppUpdateInfo,
    onProgress: (Int) -> Unit
): File {
    val asset = update.apkAsset ?: error("This release has no installable APK attached yet.")
    val trustedAssetHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

    // Resolve expected SHA-256 from asset digest or SHA256SUMS.txt
    var expectedSha256 = asset.digest.takeIf { it.startsWith("sha256:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.lowercase()
    if (expectedSha256 == null && update.checksumsUrl != null) {
        expectedSha256 = runCatching {
            val sumsText = updateHttpGet(URL(update.checksumsUrl), trustedAssetHosts) {
                readUtf8Limited(it, 128 * 1024)
            }
            parseSha256Sums(sumsText, asset.name)
        }.getOrNull()
    }

    val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
    // Clean stale update APKs
    updatesDir.listFiles()?.forEach { old -> if (old.isFile) runCatching { old.delete() } }

    val safeName = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val target = File(updatesDir, safeName)
    val digest = MessageDigest.getInstance("SHA-256")
    var downloaded = 0L

    try {
        updateHttpGet(URL(asset.downloadUrl), trustedAssetHosts) { conn ->
            target.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(32_768)
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        downloaded += n
                        check(downloaded <= asset.sizeBytes) { "Downloaded APK exceeded published size." }
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        val pct = ((downloaded * 100L) / asset.sizeBytes.coerceAtLeast(1L)).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        check(downloaded == asset.sizeBytes) { "Download ended early (${formatBytes(downloaded)} of ${formatBytes(asset.sizeBytes)})." }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        if (expectedSha256 != null) {
            check(actualSha256.equals(expectedSha256, ignoreCase = true)) {
                "The update APK checksum did not match SHA256SUMS.txt."
            }
        }
        val archiveInfo = context.packageManager.getPackageArchiveInfo(target.path, 0)
            ?: error("Android could not read the downloaded APK.")
        check(archiveInfo.packageName == context.packageName) {
            "Downloaded APK package ID (${archiveInfo.packageName}) does not match A4 TV Bridge."
        }
        return target
    } catch (error: Exception) {
        target.delete()
        throw error
    }
}

private fun cleanReleaseNotes(raw: String): String {
    if (raw.isBlank()) return "Includes the latest improvements and fixes for A4 TV Bridge."
    return raw.lineSequence()
        .map { line ->
            line.trim()
                .replace(Regex("^#+\\s*"), "")
                .replace("**", "")
                .replace("`", "")
        }
        .filter { it.isNotBlank() && !it.startsWith(">") }
        .take(14)
        .joinToString("\n")
}

internal fun BridgeActivity.checkAppUpdateOnStartup() {
    if (isFinishing || isDestroyed) return
    if (activeUpdateDialog?.isShowing == true) return
    if (updateCheckJob?.isActive == true) return

    updateCheckJob = launchSheetTask {
        val latest = try {
            updateFetcher()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        } ?: return@launchSheetTask

        if (isFinishing || isDestroyed) return@launchSheetTask
        preferences.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
        if (!isNewerVersion(latest.version, BuildConfig.VERSION_NAME)) {
            availableUpdate = null
            return@launchSheetTask
        }

        availableUpdate = latest
        refreshDashboardIfVisible()
        if (activeUpdateDialog?.isShowing != true && !isFinishing && !isDestroyed) {
            showUpdateSheet(latest)
        }
    }
}

internal fun BridgeActivity.checkForUpdatesManually() {
    report("Checking GitHub Releases for updates…")
    launchSheetTask {
        val result = try {
            Result.success(withContext(Dispatchers.IO) { fetchLatestAppRelease() })
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Result.failure<AppUpdateInfo?>(error)
        }
        if (isFinishing || isDestroyed) return@launchSheetTask

        result.onFailure { err ->
            report(err.message?.take(160) ?: "Could not check GitHub Releases right now.")
        }.onSuccess { latest ->
            preferences.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
            if (latest == null) {
                report("No public GitHub Release found yet.")
            } else if (isNewerVersion(latest.version, BuildConfig.VERSION_NAME)) {
                availableUpdate = latest
                report("")
                showUpdateSheet(latest)
            } else {
                availableUpdate = null
                report("You're up to date (v${BuildConfig.VERSION_NAME}).")
            }
        }
    }
}

internal fun BridgeActivity.launchApkInstaller(apkFile: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
        pendingUpdateApk = apkFile
        confirm(
            "Allow app installs?",
            "Android requires permission for A4 TV Bridge to install downloaded updates. Enable 'Allow from this source', then come back to finish installing."
        ) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }.onFailure {
                report("Open Android Settings → Apps → A4 TV Bridge → Install unknown apps to allow updates.")
            }
        }
        return
    }
    pendingUpdateApk = null
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(installIntent) }
        .onFailure { report("Could not open the Android installer. Download from GitHub Releases instead.") }
}

internal fun BridgeActivity.showUpdateSheet(update: AppUpdateInfo) {
    activeUpdateDialog?.takeIf { it.isShowing }?.dismiss()
    showSheet("Update available · ${update.tagName}") { popup, frame ->
        activeUpdateDialog = popup
        popup.setOnDismissListener {
            if (activeUpdateDialog === popup) activeUpdateDialog = null
        }
        val body = column()
        val versionCard = outlinedCard(fill = tint)
        versionCard.addView(text(update.title, 17, bold = true))
        val sizeText = update.apkAsset?.let { " · ${formatBytes(it.sizeBytes)}" }.orEmpty()
        versionCard.addView(text(
            "Installed: v${BuildConfig.VERSION_NAME}  →  Latest: v${update.version}$sizeText",
            13,
            ink
        ).apply { setPadding(0, px(4), 0, 0) })
        body.addView(versionCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

        val notesCard = outlinedCard()
        notesCard.addView(text("What's new", 15, bold = true))
        notesCard.addView(text(cleanReleaseNotes(update.notes), 13, muted).apply {
            setPadding(0, px(6), 0, 0)
            setLineSpacing(px(2).toFloat(), 1f)
        })
        body.addView(notesCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

        attachSheetBody(frame, body)

        val progressHost = column()
        frame.addView(progressHost, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(8) })

        val actionHost = column()
        frame.addView(actionHost, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(8) })

        if (update.apkAsset != null) {
            val installBtn = button("Download & install ${update.tagName}", emphasis = true) {}
            installBtn.setOnClickListener {
                installBtn.isEnabled = false
                installBtn.alpha = 0.6f
                installBtn.text = "Downloading ${update.tagName}…"
                progressHost.removeAllViews()
                val progressText = text("Connecting to GitHub Releases…", 13, muted).apply {
                    setPadding(0, 0, 0, px(6))
                }
                val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = 0
                    progressDrawable = LayerDrawable(arrayOf(
                        card(edge, 6, outlined = false),
                        ClipDrawable(card(accent, 6, outlined = false), Gravity.START, ClipDrawable.HORIZONTAL)
                    )).apply {
                        setId(0, android.R.id.background)
                        setId(1, android.R.id.progress)
                    }
                }
                progressHost.addView(progressText)
                progressHost.addView(bar, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(8)).apply { bottomMargin = px(6) })

                launchSheetTask {
                    val downloaded = try {
                        withContext(Dispatchers.IO) {
                            downloadVerifiedUpdateApk(this@showUpdateSheet, update) { pct ->
                                runOnUiThread {
                                    bar.progress = pct
                                    progressText.text = if (pct < 100) "Downloading & verifying SHA-256… $pct%"
                                    else "Verifying checksum and package signature…"
                                }
                            }
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        installBtn.isEnabled = true
                        installBtn.alpha = 1f
                        installBtn.text = "Retry download"
                        progressText.text = error.message?.take(180) ?: "Download failed."
                        return@launchSheetTask
                    }

                    popup.dismiss()
                    launchApkInstaller(downloaded)
                }
            }
            actionHost.addView(installBtn, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(52)).apply { bottomMargin = px(8) })
        } else {
            actionHost.addView(button("Open GitHub Release", emphasis = true) {
                popup.dismiss()
                openLink(update.htmlUrl)
            }, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(52)).apply { bottomMargin = px(8) })
        }

        val secondary = row()
        secondary.addView(button("GitHub page") {
            popup.dismiss()
            openLink(update.htmlUrl)
        }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        secondary.addView(button("Later") {
            popup.dismiss()
        }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(8) })
        actionHost.addView(secondary, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
    }
}
