package dev.a4tvbridge.independent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.zip.Inflater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class RemoteZipEntry(
    val name: String,
    val compressionMethod: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long
)

internal data class ZipEocdInfo(
    val centralDirSize: Long,
    val centralDirOffset: Long,
    val entryCount: Int
)

internal object TvAppIconLoader {
    private const val ICON_MAX_PX = 128
    private const val TAIL_INITIAL_BYTES = 65_536
    private const val MAX_CD_BYTES = 2 * 1024 * 1024
    private const val MAX_ARSC_BYTES = 3_500_000L
    private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024

    private val memoryCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val failedSession = Collections.synchronizedSet(HashSet<String>())
    private val inFlight = Collections.synchronizedSet(HashSet<String>())
    private val waiters = HashMap<String, MutableList<Pair<WeakReference<android.view.View>, (Bitmap) -> Unit>>>()
    private val waitersLock = Any()

    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adbMutex = Mutex()

    @Volatile private var cachedTvKey: String? = null
    @Volatile private var cachedApkPaths: Map<String, String> = emptyMap()

    fun getCachedOrLocal(context: Context, packageName: String): Bitmap? {
        memoryCache.get(packageName)?.let { return it }

        val diskFile = diskCacheFile(context, packageName)
        if (diskFile.isFile && diskFile.length() > 0L) {
            val decoded = runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
            if (decoded != null) {
                memoryCache.put(packageName, decoded)
                return decoded
            }
        }

        // Fast-path: if the same package is also installed on this phone, use its real icon immediately.
        val localIcon = runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawableToBitmap(drawable, ICON_MAX_PX)
        }.getOrNull()
        if (localIcon != null) {
            memoryCache.put(packageName, localIcon)
            return localIcon
        }
        return null
    }

    fun requestFromTv(
        activity: BridgeActivity,
        packageName: String,
        targetView: android.view.View,
        onLoaded: (Bitmap) -> Unit
    ) {
        val tv = activity.connection ?: return
        val tvKey = "${tv.serial}@${tv.address}"
        val cacheKey = "$tvKey|$packageName"
        if (cacheKey in failedSession) return

        val shouldLaunch: Boolean
        synchronized(waitersLock) {
            val list = waiters.getOrPut(packageName) { mutableListOf() }
            list += WeakReference(targetView) to onLoaded
            shouldLaunch = inFlight.add(packageName)
        }
        if (!shouldLaunch) return

        val appContext = activity.applicationContext
        loaderScope.launch {
            try {
                // Skip if every requesting view has already scrolled away / detached.
                if (!hasActiveWaiter(packageName)) return@launch

                val bitmap = adbMutex.withLock {
                    if (!hasActiveWaiter(packageName)) return@withLock null
                    memoryCache.get(packageName)?.let { return@withLock it }
                    extractIconFromTv(tv, tvKey, packageName)
                }

                if (bitmap != null) {
                    memoryCache.put(packageName, bitmap)
                    saveDiskCache(appContext, packageName, bitmap)
                    val callbacks = takeWaiters(packageName)
                    withContext(Dispatchers.Main) {
                        for ((ref, cb) in callbacks) {
                            val v = ref.get()
                            if (v != null && v.isAttachedToWindow) cb(bitmap)
                        }
                    }
                } else {
                    failedSession += cacheKey
                    takeWaiters(packageName)
                }
            } catch (_: Exception) {
                failedSession += cacheKey
                takeWaiters(packageName)
            } finally {
                inFlight.remove(packageName)
            }
        }
    }

    private fun hasActiveWaiter(packageName: String): Boolean = synchronized(waitersLock) {
        val list = waiters[packageName] ?: return false
        list.removeAll { it.first.get() == null }
        list.isNotEmpty()
    }

    private fun takeWaiters(packageName: String): List<Pair<WeakReference<android.view.View>, (Bitmap) -> Unit>> =
        synchronized(waitersLock) { waiters.remove(packageName).orEmpty() }

    private fun diskCacheFile(context: Context, packageName: String): File {
        val dir = File(context.cacheDir, "tv_app_icons")
        if (!dir.exists()) dir.mkdirs()
        val safe = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "$safe.png")
    }

    private fun saveDiskCache(context: Context, packageName: String, bitmap: Bitmap) {
        runCatching {
            val target = diskCacheFile(context, packageName)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)
            }
            if (!tmp.renameTo(target)) tmp.delete()
        }
    }

    private fun ensureApkPaths(tv: TvConnection, tvKey: String): Map<String, String> {
        if (cachedTvKey == tvKey && cachedApkPaths.isNotEmpty()) return cachedApkPaths
        val fresh = runCatching { tv.packageApkPaths() }.getOrDefault(emptyMap())
        if (fresh.isNotEmpty()) {
            cachedTvKey = tvKey
            cachedApkPaths = fresh
        }
        return fresh
    }

    private fun extractIconFromTv(tv: TvConnection, tvKey: String, packageName: String): Bitmap? {
        val basePath = ensureApkPaths(tv, tvKey)[packageName]
            ?: runCatching { tv.packageSplitPaths(packageName).firstOrNull() }.getOrNull()
            ?: return null

        val entries = readRemoteZipDirectory(tv, basePath) ?: return null

        // 1. Fast path: standard unobfuscated icon entry in base.apk
        pickIconEntryByName(entries)?.let { entry ->
            decodeZipIcon(tv, basePath, entry)?.let { return it }
        }

        // 2. AAPT2-optimized / obfuscated APK path: resolve via AndroidManifest.xml + resources.arsc
        val manifestEntry = entries.firstOrNull { it.name == "AndroidManifest.xml" }
        val arscEntry = entries.firstOrNull { it.name == "resources.arsc" && it.compressedSize <= MAX_ARSC_BYTES }
        if (manifestEntry != null && arscEntry != null) {
            val manifestBytes = readRemoteZipEntry(tv, basePath, manifestEntry)
            val iconResIds = manifestBytes?.let(::parseManifestIconResIds).orEmpty()
            if (iconResIds.isNotEmpty()) {
                val arscBytes = readRemoteZipEntry(tv, basePath, arscEntry)
                if (arscBytes != null) {
                    val resolvedBitmap = resolveIconViaArsc(tv, packageName, basePath, entries, arscBytes, iconResIds)
                    if (resolvedBitmap != null) return resolvedBitmap
                }
            }
        }

        // 3. Density split APK fallback (e.g., split_config.xxhdpi.apk / split_config.xhdpi.apk)
        val splits = runCatching { tv.packageSplitPaths(packageName) }.getOrDefault(emptyList())
            .filter { it != basePath && it.contains("dpi", ignoreCase = true) }
        for (splitPath in splits.take(2)) {
            val splitEntries = readRemoteZipDirectory(tv, splitPath) ?: continue
            pickIconEntryByName(splitEntries)?.let { entry ->
                decodeZipIcon(tv, splitPath, entry)?.let { return it }
            }
        }
        return null
    }

    private fun resolveIconViaArsc(
        tv: TvConnection,
        packageName: String,
        basePath: String,
        baseEntries: List<RemoteZipEntry>,
        arscBytes: ByteArray,
        iconResIds: List<Int>
    ): Bitmap? {
        val byName = baseEntries.associateBy { it.name }
        val candidatePaths = mutableListOf<String>()
        val xmlPaths = mutableListOf<String>()

        for (resId in iconResIds) {
            val paths = resolvePathsFromArsc(arscBytes, resId)
            for (path in paths) {
                when {
                    isRasterImagePath(path) -> candidatePaths += path
                    path.endsWith(".xml", ignoreCase = true) -> xmlPaths += path
                }
            }
        }

        // If only adaptive-icon XML paths were found, inspect XML to extract <foreground android:drawable="@0x7f...">
        if (candidatePaths.isEmpty() && xmlPaths.isNotEmpty()) {
            for (xmlPath in xmlPaths.distinct().take(2)) {
                val xmlEntry = byName[xmlPath] ?: continue
                val xmlBytes = readRemoteZipEntry(tv, basePath, xmlEntry) ?: continue
                val fgIds = parseAdaptiveForegroundResIds(xmlBytes)
                for (fgId in fgIds) {
                    for (path in resolvePathsFromArsc(arscBytes, fgId)) {
                        if (isRasterImagePath(path)) candidatePaths += path
                    }
                }
            }
        }

        if (candidatePaths.isEmpty()) return null

        // Try direct matches in base.apk first (preferring larger raster files / higher density)
        val matchedInBase = candidatePaths.distinct().mapNotNull { byName[it] }
            .sortedByDescending { scoreResolvedEntry(it) }
        for (entry in matchedInBase.take(2)) {
            decodeZipIcon(tv, basePath, entry)?.let { return it }
        }

        // If resource lives in a density split APK, check split APKs
        val splitPaths = runCatching { tv.packageSplitPaths(packageName) }.getOrDefault(emptyList())
            .filter { it != basePath }
        for (splitPath in splitPaths.take(3)) {
            val splitEntries = readRemoteZipDirectory(tv, splitPath) ?: continue
            val splitMap = splitEntries.associateBy { it.name }
            val matchedInSplit = candidatePaths.distinct().mapNotNull { splitMap[it] }
                .sortedByDescending { scoreResolvedEntry(it) }
            for (entry in matchedInSplit.take(1)) {
                decodeZipIcon(tv, splitPath, entry)?.let { return it }
            }
        }
        return null
    }

    private fun readRemoteZipDirectory(tv: TvConnection, apkPath: String): List<RemoteZipEntry>? {
        val tail = tv.readRemoteTail(apkPath, TAIL_INITIAL_BYTES)
        if (tail.size < 22) return null
        val eocd = parseZipEocd(tail) ?: return null
        if (eocd.centralDirSize <= 0L || eocd.centralDirSize > MAX_CD_BYTES) return null

        val eocdPosInTail = findEocdOffset(tail)
        if (eocdPosInTail < 0) return null
        val cdSizeInt = eocd.centralDirSize.toInt()
        val cdStartInTail = eocdPosInTail - cdSizeInt
        if (cdStartInTail >= 0) {
            return parseZipCentralDirectory(tail, cdStartInTail, eocd.centralDirSize)
        }

        // Central Directory is larger than 64KB; read the exact Central Directory byte range.
        val cdBytes = tv.readRemoteRange(apkPath, eocd.centralDirOffset, cdSizeInt)
        if (cdBytes.size < cdSizeInt) return null
        return parseZipCentralDirectory(cdBytes, 0, eocd.centralDirSize)
    }

    private fun readRemoteZipEntry(tv: TvConnection, apkPath: String, entry: RemoteZipEntry): ByteArray? {
        if (entry.compressedSize <= 0L || entry.compressedSize > MAX_ENTRY_BYTES) return null
        val readSize = (30 + entry.name.toByteArray().size + 512 + entry.compressedSize).toInt()
            .coerceAtMost(MAX_ENTRY_BYTES + 2048)
        val chunk = tv.readRemoteRange(apkPath, entry.localHeaderOffset, readSize)
        if (chunk.size < 30) return null
        return extractZipEntryPayload(chunk, entry)
    }

    private fun decodeZipIcon(tv: TvConnection, apkPath: String, entry: RemoteZipEntry): Bitmap? {
        val payload = readRemoteZipEntry(tv, apkPath, entry) ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload, 0, payload.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        val decodeOpts = BitmapFactory.Options().apply {
            var sample = 1
            while (opts.outWidth / sample > ICON_MAX_PX * 2 || opts.outHeight / sample > ICON_MAX_PX * 2) {
                sample *= 2
            }
            inSampleSize = sample
        }
        val raw = BitmapFactory.decodeByteArray(payload, 0, payload.size, decodeOpts) ?: return null
        return scaleDownBitmap(raw, ICON_MAX_PX)
    }

    private fun scaleDownBitmap(source: Bitmap, maxEdge: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxEdge && h <= maxEdge) return source
        val ratio = maxEdge.toFloat() / maxOf(w, h).toFloat()
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, nw, nh, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return scaleDownBitmap(drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false), sizePx)
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }
}

internal fun parsePackageApkPaths(input: String): Map<String, String> {
    val result = HashMap<String, String>()
    for (raw in input.lineSequence()) {
        val line = raw.trim()
        if (!line.startsWith("package:")) continue
        val body = line.removePrefix("package:")
        val eq = body.lastIndexOf('=')
        if (eq <= 0 || eq >= body.lastIndex) continue
        val path = body.substring(0, eq).trim()
        val pkg = body.substring(eq + 1).trim()
        if (path.startsWith("/") && pkg.isNotBlank()) {
            result[pkg] = path
        }
    }
    return result
}

internal fun isRasterImagePath(path: String): Boolean {
    val lower = path.lowercase()
    if (lower.endsWith(".9.png")) return false
    return lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
}

internal fun pickIconEntryByName(entries: List<RemoteZipEntry>): RemoteZipEntry? {
    var best: RemoteZipEntry? = null
    var bestScore = Int.MIN_VALUE
    for (entry in entries) {
        val score = scoreNamedIconEntry(entry)
        if (score > bestScore) {
            bestScore = score
            best = entry
        }
    }
    return if (bestScore > 0) best else null
}

private fun scoreNamedIconEntry(entry: RemoteZipEntry): Int {
    val lower = entry.name.lowercase()
    if (!isRasterImagePath(lower)) return -1
    if (entry.uncompressedSize !in 200L..2_500_000L) return -1
    if (!lower.startsWith("res/")) return -1

    val fileName = lower.substringAfterLast('/')
    val base = fileName.substringBeforeLast('.')
    val excluded = listOf(
        "abc_", "ic_stat_", "notification", "notif", "monochrome", "ic_launcher_background",
        "splash", "onboarding", "placeholder", "ic_arrow", "ic_back", "ic_close", "ic_search",
        "ic_dialog", "ic_menu", "ic_action", "ic_play", "ic_pause", "ic_check", "ic_delete",
        "ic_add", "ic_edit", "ic_share", "ic_warning", "ic_error", "btn_", "bg_"
    )
    if (excluded.any { it in base }) return -1

    val nameScore = when {
        base == "ic_launcher" -> 1000
        base == "ic_launcher_round" -> 960
        base in setOf("app_icon", "ic_app", "ic_app_icon", "launcher_icon", "appicon", "icon_app") -> 940
        base == "ic_launcher_foreground" || base == "launcher_foreground" -> 840
        base in setOf("ic_tv_icon", "tv_icon", "ic_launcher_tv", "app_logo", "ic_logo") -> 800
        base == "icon" || base == "logo" -> 700
        base.startsWith("ic_launcher") -> 760
        base in setOf("tv_banner", "ic_banner", "app_banner", "banner") -> 520
        base.contains("launcher") && !base.contains("background") -> 600
        base.contains("app_icon") || base.contains("tv_banner") -> 500
        else -> return -1
    }

    val dirScore = when {
        lower.startsWith("res/mipmap") -> 70
        lower.startsWith("res/drawable") -> 30
        else -> 0
    }
    val densityScore = when {
        "xxhdpi" in lower -> 80
        "xhdpi" in lower -> 75
        "xxxhdpi" in lower -> 70
        "hdpi" in lower -> 55
        "tvdpi" in lower -> 50
        "nodpi" in lower || "anydpi" in lower -> 45
        "mdpi" in lower -> 35
        "ldpi" in lower -> 15
        else -> 40
    }
    val sizeBonus = if (entry.uncompressedSize in 2_000L..250_000L) 20 else 0
    return nameScore + dirScore + densityScore + sizeBonus
}

private fun scoreResolvedEntry(entry: RemoteZipEntry): Int {
    val lower = entry.name.lowercase()
    val densityScore = when {
        "xxhdpi" in lower -> 80
        "xhdpi" in lower -> 75
        "xxxhdpi" in lower -> 70
        "hdpi" in lower -> 55
        "tvdpi" in lower -> 50
        else -> 40
    }
    val sizeScore = (entry.uncompressedSize.coerceAtMost(200_000L) / 2000L).toInt()
    return densityScore + sizeScore
}

internal fun findEocdOffset(bytes: ByteArray): Int {
    val minPos = maxOf(0, bytes.size - 65_557)
    for (i in bytes.size - 22 downTo minPos) {
        if (bytes[i] == 0x50.toByte() &&
            bytes[i + 1] == 0x4B.toByte() &&
            bytes[i + 2] == 0x05.toByte() &&
            bytes[i + 3] == 0x06.toByte()
        ) {
            val commentLen = u16(bytes, i + 20)
            if (i + 22 + commentLen == bytes.size) return i
        }
    }
    return -1
}

internal fun parseZipEocd(tailBytes: ByteArray): ZipEocdInfo? {
    val pos = findEocdOffset(tailBytes)
    if (pos < 0) return null
    val entryCount = u16(tailBytes, pos + 10)
    val cdSize = u32(tailBytes, pos + 12)
    val cdOffset = u32(tailBytes, pos + 16)
    return ZipEocdInfo(cdSize, cdOffset, entryCount)
}

internal fun parseZipCentralDirectory(bytes: ByteArray, offsetInBuffer: Int, cdSize: Long): List<RemoteZipEntry> {
    val entries = ArrayList<RemoteZipEntry>()
    val end = minOf(bytes.size, offsetInBuffer + cdSize.toInt())
    var pos = offsetInBuffer
    while (pos + 46 <= end) {
        if (i32(bytes, pos) != 0x02014b50) break
        val method = u16(bytes, pos + 10)
        val compSize = u32(bytes, pos + 20)
        val uncompSize = u32(bytes, pos + 24)
        val nameLen = u16(bytes, pos + 28)
        val extraLen = u16(bytes, pos + 30)
        val commentLen = u16(bytes, pos + 32)
        val localOffset = u32(bytes, pos + 42)
        val next = pos + 46 + nameLen + extraLen + commentLen
        if (next > end) break
        val name = String(bytes, pos + 46, nameLen, Charsets.UTF_8)
        entries += RemoteZipEntry(name, method, compSize, uncompSize, localOffset)
        pos = next
    }
    return entries
}

internal fun extractZipEntryPayload(localChunk: ByteArray, entry: RemoteZipEntry): ByteArray? {
    if (localChunk.size < 30 || i32(localChunk, 0) != 0x04034b50) return null
    val nameLen = u16(localChunk, 26)
    val extraLen = u16(localChunk, 28)
    val dataStart = 30 + nameLen + extraLen
    val compSize = entry.compressedSize.toInt()
    if (compSize < 0 || dataStart + compSize > localChunk.size) return null

    return when (entry.compressionMethod) {
        0 -> localChunk.copyOfRange(dataStart, dataStart + compSize)
        8 -> {
            val inflater = Inflater(true)
            try {
                inflater.setInput(localChunk, dataStart, compSize)
                val out = ByteArrayOutputStream(entry.uncompressedSize.toInt().coerceIn(256, 512_000))
                val buf = ByteArray(8192)
                val maxOut = maxOf(entry.uncompressedSize, 4_500_000L).coerceAtMost(8_000_000L)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buf)
                    if (count <= 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        if (out.size() + count > maxOut) return null
                        out.write(buf, 0, count)
                    }
                }
                out.toByteArray()
            } catch (_: Exception) {
                null
            } finally {
                inflater.end()
            }
        }
        else -> null
    }
}

internal fun parseManifestIconResIds(axml: ByteArray): List<Int> =
    parseAxmlRefAttributes(
        axml = axml,
        targetTag = "application",
        preferredAttrResIds = intArrayOf(0x01010002, 0x0101052c, 0x010103f2), // icon, roundIcon, banner
        preferredAttrNames = setOf("icon", "roundIcon", "banner")
    )

internal fun parseAdaptiveForegroundResIds(axml: ByteArray): List<Int> =
    parseAxmlRefAttributes(
        axml = axml,
        targetTag = "foreground",
        preferredAttrResIds = intArrayOf(0x01010199), // drawable
        preferredAttrNames = setOf("drawable")
    )

private fun parseAxmlRefAttributes(
    axml: ByteArray,
    targetTag: String,
    preferredAttrResIds: IntArray,
    preferredAttrNames: Set<String>
): List<Int> {
    if (axml.size < 8 || u16(axml, 0) != 0x0003) return emptyList()
    var pos = u16(axml, 2)
    var strings: List<String> = emptyList()
    var resMap: IntArray = IntArray(0)
    val ordered = LinkedHashMap<Int, Int>() // priority -> resId

    while (pos + 8 <= axml.size) {
        val type = u16(axml, pos)
        val headerSize = u16(axml, pos + 2)
        val chunkSize = i32(axml, pos + 4)
        if (chunkSize < 8 || pos + chunkSize > axml.size) break

        when (type) {
            0x0001 -> strings = parseStringPool(axml, pos)
            0x0180 -> {
                val count = (chunkSize - headerSize) / 4
                if (count > 0) {
                    resMap = IntArray(count) { idx -> i32(axml, pos + headerSize + idx * 4) }
                }
            }
            0x0102 -> {
                if (pos + 36 <= pos + chunkSize) {
                    val nameIdx = i32(axml, pos + 20)
                    val tagName = strings.getOrNull(nameIdx)
                    if (tagName == targetTag) {
                        val attrStart = u16(axml, pos + 24)
                        val attrSize = u16(axml, pos + 26)
                        val attrCount = u16(axml, pos + 28)
                        val attrsBase = pos + 16 + attrStart
                        for (i in 0 until attrCount) {
                            val aPos = attrsBase + i * attrSize
                            if (aPos + 20 > pos + chunkSize) break
                            val attrNameIdx = i32(axml, aPos + 4)
                            val attrResId = if (attrNameIdx in resMap.indices) resMap[attrNameIdx] else 0
                            val attrName = strings.getOrNull(attrNameIdx)
                            val valueType = axml[aPos + 15].toInt() and 0xFF
                            val valueData = i32(axml, aPos + 16)
                            if (valueType == 0x01 && valueData != 0) {
                                val resPriority = preferredAttrResIds.indexOf(attrResId)
                                if (resPriority >= 0) {
                                    ordered[resPriority] = valueData
                                } else if (attrName in preferredAttrNames) {
                                    ordered[10 + i] = valueData
                                }
                            }
                        }
                        if (ordered.isNotEmpty()) {
                            return ordered.entries.sortedBy { it.key }.map { it.value }.distinct()
                        }
                    }
                }
            }
        }
        pos += chunkSize
    }
    return ordered.entries.sortedBy { it.key }.map { it.value }.distinct()
}

internal fun resolvePathsFromArsc(arsc: ByteArray, resId: Int): List<String> {
    if (arsc.size < 12 || u16(arsc, 0) != 0x0002) return emptyList()
    val targetPkgId = (resId ushr 24) and 0xFF
    val targetTypeId = (resId ushr 16) and 0xFF
    val targetEntryId = resId and 0xFFFF

    var globalStrings: List<String> = emptyList()
    val resolved = LinkedHashSet<String>()
    var pos = u16(arsc, 2)

    while (pos + 8 <= arsc.size) {
        val type = u16(arsc, pos)
        val headerSize = u16(arsc, pos + 2)
        val chunkSize = i32(arsc, pos + 4)
        if (chunkSize < 8 || pos + chunkSize > arsc.size) break

        if (type == 0x0001 && globalStrings.isEmpty()) {
            globalStrings = parseStringPool(arsc, pos)
        } else if (type == 0x0200) {
            val pkgId = i32(arsc, pos + 8)
            if (pkgId == targetPkgId) {
                var subPos = pos + headerSize
                val pkgEnd = pos + chunkSize
                while (subPos + 8 <= pkgEnd) {
                    val subType = u16(arsc, subPos)
                    val subHeaderSize = u16(arsc, subPos + 2)
                    val subSize = i32(arsc, subPos + 4)
                    if (subSize < 8 || subPos + subSize > pkgEnd) break

                    if (subType == 0x0201 && subPos + 20 <= pkgEnd) {
                        val typeId = arsc[subPos + 8].toInt() and 0xFF
                        val flags = arsc[subPos + 9].toInt() and 0xFF
                        val entryCount = i32(arsc, subPos + 12)
                        val entriesStart = i32(arsc, subPos + 16)
                        if (typeId == targetTypeId && entryCount > 0) {
                            val relOffset = findArscEntryOffset(arsc, subPos, subHeaderSize, subSize, flags, entryCount, targetEntryId)
                            if (relOffset >= 0) {
                                val ePos = subPos + entriesStart + relOffset
                                if (ePos + 8 <= subPos + subSize) {
                                    val eSize = u16(arsc, ePos)
                                    val eFlags = u16(arsc, ePos + 2)
                                    val isComplex = (eFlags and 0x01) != 0
                                    val isCompact = (eFlags and 0x08) != 0
                                    if (isCompact) {
                                        val valType = (eFlags ushr 8) and 0xFF
                                        val valData = i32(arsc, ePos + 4)
                                        if (valType == 0x03) {
                                            globalStrings.getOrNull(valData)?.let { resolved += it }
                                        }
                                    } else if (!isComplex) {
                                        val valPos = ePos + eSize
                                        if (valPos + 8 <= subPos + subSize) {
                                            val valType = arsc[valPos + 3].toInt() and 0xFF
                                            val valData = i32(arsc, valPos + 4)
                                            if (valType == 0x03) {
                                                globalStrings.getOrNull(valData)?.let { resolved += it }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    subPos += subSize
                }
            }
        }
        pos += chunkSize
    }
    return resolved.toList()
}

private fun findArscEntryOffset(
    arsc: ByteArray,
    subPos: Int,
    subHeaderSize: Int,
    subSize: Int,
    flags: Int,
    entryCount: Int,
    targetEntryId: Int
): Int {
    val offsetsBase = subPos + subHeaderSize
    val isSparse = (flags and 0x01) != 0
    val isOffset16 = (flags and 0x02) != 0
    if (isSparse) {
        for (i in 0 until entryCount) {
            val p = offsetsBase + i * 4
            if (p + 4 > subPos + subSize) break
            val idx = u16(arsc, p)
            if (idx == targetEntryId) {
                val off = u16(arsc, p + 2)
                return if (off == 0xFFFF) -1 else off * 4
            }
        }
        return -1
    }
    if (targetEntryId >= entryCount) return -1
    if (isOffset16) {
        val p = offsetsBase + targetEntryId * 2
        if (p + 2 > subPos + subSize) return -1
        val off = u16(arsc, p)
        return if (off == 0xFFFF) -1 else off * 4
    }
    val p = offsetsBase + targetEntryId * 4
    if (p + 4 > subPos + subSize) return -1
    return i32(arsc, p)
}

private fun parseStringPool(buf: ByteArray, chunkStart: Int): List<String> {
    if (chunkStart + 28 > buf.size) return emptyList()
    val headerSize = u16(buf, chunkStart + 2)
    val chunkSize = i32(buf, chunkStart + 4)
    val chunkEnd = minOf(buf.size, chunkStart + chunkSize)
    val stringCount = i32(buf, chunkStart + 8).coerceIn(0, 200_000)
    val flags = i32(buf, chunkStart + 16)
    val stringsStart = i32(buf, chunkStart + 20)
    val isUtf8 = (flags and 0x100) != 0

    val result = ArrayList<String>(stringCount)
    val offsetsBase = chunkStart + headerSize
    val dataBase = chunkStart + stringsStart
    for (i in 0 until stringCount) {
        val offPos = offsetsBase + i * 4
        if (offPos + 4 > chunkEnd) break
        var p = dataBase + i32(buf, offPos)
        if (p < dataBase || p >= chunkEnd) {
            result += ""
            continue
        }
        if (isUtf8) {
            // Skip char length (1 or 2 bytes)
            val b0 = buf[p++].toInt() and 0xFF
            if ((b0 and 0x80) != 0 && p < chunkEnd) p++
            if (p >= chunkEnd) { result += ""; continue }
            var byteLen = buf[p++].toInt() and 0xFF
            if ((byteLen and 0x80) != 0 && p < chunkEnd) {
                byteLen = ((byteLen and 0x7F) shl 8) or (buf[p++].toInt() and 0xFF)
            }
            if (p + byteLen <= chunkEnd && byteLen >= 0) {
                result += String(buf, p, byteLen, Charsets.UTF_8)
            } else {
                result += ""
            }
        } else {
            if (p + 2 > chunkEnd) { result += ""; continue }
            var charLen = u16(buf, p)
            p += 2
            if ((charLen and 0x8000) != 0 && p + 2 <= chunkEnd) {
                charLen = ((charLen and 0x7FFF) shl 16) or u16(buf, p)
                p += 2
            }
            val byteLen = charLen * 2
            if (p + byteLen <= chunkEnd && byteLen >= 0) {
                result += String(buf, p, byteLen, Charsets.UTF_16LE)
            } else {
                result += ""
            }
        }
    }
    return result
}

private fun u16(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

private fun i32(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

private fun u32(b: ByteArray, o: Int): Long = i32(b, o).toLong() and 0xFFFFFFFFL
