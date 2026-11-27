package dev.a4tvbridge.independent

internal data class CleanupItem(
    val id: String,
    val note: String,
    val disabledCount: Int = 0,
    val keptCount: Int = 0
)

internal data class ProfileMeta(
    val tv: String = "",
    val os: String = "",
    val brand: String = ""
)

internal data class ParsedProfile(
    val disabled: List<CleanupItem>,
    val kept: List<CleanupItem>
)

internal fun cleanupEntries(input: String): List<CleanupItem> = parseProfileList(input).disabled

/**
 * Parses a CleanYourTV debloat.txt list following CleanYourTV tools/match.py conventions:
 * - Active line: `<package> [# note]` -> disabled list
 * - Single-package commented line: `# <package> [# note]` -> commented/kept list
 * Prose comment lines are skipped. Active lines take precedence over commented lines.
 */
internal fun parseProfileList(input: String): ParsedProfile {
    val disabledMap = LinkedHashMap<String, String>()
    val keptMap = LinkedHashMap<String, String>()
    for (rawLine in input.lineSequence()) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue
        val isCommented = trimmed.startsWith("#")
        val bodySource = if (isCommented) trimmed.substring(1).trim() else trimmed
        val beforeNote = bodySource.substringBefore('#').trim()
        val note = bodySource.substringAfter('#', "").trim()
        val words = beforeNote.split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.size != 1) continue
        val pkg = words[0]
        if ('.' !in pkg) continue
        val valid = runCatching { packageId(pkg) }.getOrNull() ?: continue
        val target = if (isCommented) keptMap else disabledMap
        if (valid !in target) target[valid] = note
    }
    for (id in disabledMap.keys) keptMap.remove(id)
    return ParsedProfile(
        disabled = disabledMap.entries.map { CleanupItem(it.key, it.value) },
        kept = keptMap.entries.map { CleanupItem(it.key, it.value) }
    )
}

/**
 * CleanYourTV core Android and TV middleware boot-loop protection (from tools/recovery.py).
 * Never disable these packages; doing so can prevent Android TV from booting.
 */
private val CORE_PROTECTED_REGEX = Regex(
    "^(android|android\\.ext\\.(services|shared)|com\\.(google\\.)?android\\.sdksandbox|" +
        "com\\.android\\.location\\.fused|com\\.android\\.systemui|com\\.android\\.shell|" +
        "com\\.android\\.phone|com\\.android\\.settings|com\\.android\\.tv\\.settings|" +
        "com\\.(google\\.)?android\\.(permissioncontroller|packageinstaller|ext\\.services|ext\\.shared|modulemetadata)|" +
        "com\\.google\\.android\\.overlay\\.modules.*|com\\.android\\.providers\\..*|" +
        "com\\.cltv\\.mal|com\\.mediatek\\.(tis|tv\\.service|tv\\.agent))$"
)

private val CORE_SAFE_PROVIDERS_REGEX = Regex(
    "^com\\.android\\.providers\\.(calendar|contacts|userdictionary|blockednumber)$"
)

internal fun isCoreProtectedPackage(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return true
    return CORE_PROTECTED_REGEX.matches(trimmed) && !CORE_SAFE_PROVIDERS_REGEX.matches(trimmed)
}

internal const val SETUPWRAITH_PACKAGE = "com.google.android.tungsten.setupwraith"

internal val KNOWN_STOCK_LAUNCHERS = setOf(
    "com.google.android.tvlauncher",
    "com.google.android.apps.tv.launcherx",
    "com.google.android.leanbacklauncher",
    "com.mitv.tvhome",
    "com.droidlogic.mboxlauncher"
)

private val NOT_LAUNCHERS = setOf(
    SETUPWRAITH_PACKAGE,
    "com.android.tv.settings",
    "com.android.settings",
    "com.android.provision"
)

private val FALLBACK_HOME_REGEX = Regex("/\\S*FallbackHome$")
private val FACTORY_HOME_REGEX = Regex("factorytest|factory_test|factorymode", RegexOption.IGNORE_CASE)

internal fun isRealLauncherComponent(component: String): Boolean {
    val trimmed = component.trim()
    if ('/' !in trimmed) return false
    val pkg = trimmed.substringBefore('/')
    if (pkg in NOT_LAUNCHERS) return false
    if (FALLBACK_HOME_REGEX.containsMatchIn(trimmed)) return false
    if (FACTORY_HOME_REGEX.containsMatchIn(trimmed)) return false
    return true
}

internal data class TvIdentity(
    val manufacturer: String = "",
    val brand: String = "",
    val model: String = "",
    val device: String = "",
    val board: String = "",
    val androidVersion: String = ""
)

internal data class CleanupProtection(
    val activeHomePackage: String? = null,
    val realHomeLaunchers: Set<String> = emptySet(),
    val activeKeyboardPackage: String? = null,
    val setupwraithOwnsWifi: Boolean = false,
    val wifiOwnersChecked: Boolean = true,
    val inputHardwarePackages: Set<String> = emptySet(),
    val channelHandlerPackages: Set<String> = emptySet(),
    val settingsHandlerPackages: Set<String> = emptySet(),
    val adbSecureRaw: String? = null
) {
    val adbInsecureWarning: Boolean
        get() = adbSecureRaw != null && (adbSecureRaw.isBlank() || adbSecureRaw == "0")

    fun blockReason(name: String, plannedDisables: Set<String> = emptySet()): String? {
        val id = runCatching { packageId(name) }.getOrNull() ?: return "Invalid package name."
        if (isCoreProtectedPackage(id)) {
            return "Core Android/TV system package — kept on to prevent boot failure."
        }
        if (id == SETUPWRAITH_PACKAGE) {
            if (setupwraithOwnsWifi) {
                return "Owns your saved Wi-Fi network — disabling it drops the TV off Wi-Fi. Forget and rejoin Wi-Fi in TV Settings first."
            }
            if (!wifiOwnersChecked) {
                return "Wi-Fi network owner could not be checked — kept on to protect Wi-Fi."
            }
        }
        if (id == activeHomePackage) {
            val remainingLaunchers = realHomeLaunchers - plannedDisables - id
            if (remainingLaunchers.isEmpty()) {
                return "Active TV Home launcher — install and set another launcher first."
            }
        } else if (realHomeLaunchers.isEmpty() && id in KNOWN_STOCK_LAUNCHERS) {
            return "Stock TV launcher — kept on because installed Home apps could not be verified."
        }
        if (id in settingsHandlerPackages) {
            return "Opens TV Settings — kept on so Developer options stays reachable."
        }
        if (id in channelHandlerPackages || id in inputHardwarePackages) {
            return "Handles HDMI/AV inputs or TV tuner hardware — kept on to prevent No Signal."
        }
        if (activeKeyboardPackage != null && id == activeKeyboardPackage) {
            return "Active TV keyboard — kept on."
        }
        if (id.contains("bluetooth", true) || id.contains("remote", true)) {
            return "Needed for TV remote or Bluetooth pairing."
        }
        return null
    }

    fun isProtected(name: String, plannedDisables: Set<String> = emptySet()): Boolean =
        blockReason(name, plannedDisables) != null
}

internal enum class ProfileMatchKind {
    MANUAL,
    EXACT_MODEL,
    BRAND_MATCH,
    AUTO_DETECTED
}

internal data class ProfileMatch(
    val profileName: String?,
    val profileTitle: String?,
    val profileOs: String?,
    val kind: ProfileMatchKind,
    val items: List<CleanupItem>,
    val totalLists: Int
)

internal data class CleanupCatalog(
    val profiles: Map<String, ParsedProfile>,
    val meta: Map<String, ProfileMeta> = emptyMap()
)

internal data class PackageTally(
    val disabledCount: Int,
    val keptCount: Int,
    val commonNote: String
)

internal fun tallyInstalledPackages(
    installedIds: Set<String>,
    profiles: Map<String, ParsedProfile>
): Map<String, PackageTally> {
    val disabledCounts = HashMap<String, Int>()
    val keptCounts = HashMap<String, Int>()
    val noteFreq = HashMap<String, MutableMap<String, Int>>()

    for (parsed in profiles.values) {
        for (item in parsed.disabled) {
            if (item.id !in installedIds) continue
            disabledCounts[item.id] = (disabledCounts[item.id] ?: 0) + 1
            if (item.note.isNotBlank()) {
                val map = noteFreq.getOrPut(item.id) { HashMap() }
                map[item.note] = (map[item.note] ?: 0) + 1
            }
        }
        for (item in parsed.kept) {
            if (item.id !in installedIds) continue
            keptCounts[item.id] = (keptCounts[item.id] ?: 0) + 1
            if (item.note.isNotBlank()) {
                val map = noteFreq.getOrPut(item.id) { HashMap() }
                map[item.note] = (map[item.note] ?: 0) + 1
            }
        }
    }

    val result = HashMap<String, PackageTally>(disabledCounts.size + keptCounts.size)
    val allKeys = disabledCounts.keys + keptCounts.keys
    for (id in allKeys) {
        val bestNote = noteFreq[id]?.maxByOrNull { it.value }?.key.orEmpty()
        result[id] = PackageTally(
            disabledCount = disabledCounts[id] ?: 0,
            keptCount = keptCounts[id] ?: 0,
            commonNote = bestNote
        )
    }
    return result
}

private val GENERIC_TOKENS = setOf(
    "tv", "android", "google", "smart", "smarttv", "box", "stick", "4k", "2k", "hd", "fhd",
    "uhd", "pro", "plus", "mini", "max", "air", "lite", "series", "edition", "global", "eu",
    "us", "uk", "in", "cn", "jp", "au", "ca", "pl", "generic", "unknown", "atv", "googletv",
    "androidtv", "2018", "2019", "2020", "2021", "2022", "2023", "2024", "2025", "2026"
)

private fun slugify(value: String): String =
    value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

private fun slugTokens(slug: String): Set<String> =
    slug.split('-').filter { it.length >= 2 && it !in GENERIC_TOKENS }.toSet()

internal fun autoMatchCleanup(
    identity: TvIdentity,
    installedIds: Set<String>,
    catalog: CleanupCatalog,
    savedProfile: String? = null
): ProfileMatch {
    val profiles = catalog.profiles
    val meta = catalog.meta
    val tallies = tallyInstalledPackages(installedIds, profiles)

    fun enrich(items: List<CleanupItem>): List<CleanupItem> = items.map { item ->
        val t = tallies[item.id]
        item.copy(
            note = item.note.ifBlank { t?.commonNote.orEmpty() },
            disabledCount = t?.disabledCount ?: item.disabledCount,
            keptCount = t?.keptCount ?: item.keptCount
        )
    }

    if (!savedProfile.isNullOrBlank()) {
        val chosen = profiles[savedProfile]
        if (chosen != null) {
            val info = meta[savedProfile]
            return ProfileMatch(
                profileName = savedProfile,
                profileTitle = info?.tv?.ifBlank { null },
                profileOs = info?.os?.ifBlank { null },
                kind = ProfileMatchKind.MANUAL,
                items = enrich(chosen.disabled.filter { it.id in installedIds }),
                totalLists = profiles.size
            )
        }
    }

    val modelSlug = slugify(identity.model)
    val brandSlug = slugify(identity.brand)
    val mfgSlug = slugify(identity.manufacturer)
    val deviceSlug = slugify(identity.device)
    val boardSlug = slugify(identity.board)

    val brandSlugs = setOf(brandSlug, mfgSlug).filter { it.length >= 2 && it !in GENERIC_TOKENS }
    val exactCandidates = buildSet {
        if (modelSlug.length >= 3 && modelSlug !in GENERIC_TOKENS) add(modelSlug)
        for (b in brandSlugs) {
            if (modelSlug.isNotEmpty()) add("$b-$modelSlug")
            if (deviceSlug.isNotEmpty()) add("$b-$deviceSlug")
        }
    }
    val modelTokens = slugTokens(modelSlug) + slugTokens(deviceSlug) + slugTokens(boardSlug)
    val allIdentityTokens = modelTokens + brandSlugs.flatMap { slugTokens(it) }

    var bestSlug: String? = null
    var bestIdentityScore = 0
    var bestTotalScore = 0
    var bestHits = 0
    var bestMatched: List<CleanupItem> = emptyList()

    for ((slug, parsed) in profiles) {
        val entries = parsed.disabled
        val matched = entries.filter { it.id in installedIds }
        if (matched.isEmpty()) continue

        val info = meta[slug]
        val metaTvSlug = info?.tv?.let(::slugify).orEmpty()
        val metaBrandSlug = info?.brand?.let(::slugify).orEmpty()

        var idScore = 0
        if (slug in exactCandidates) {
            idScore += 1000
        } else {
            if (modelSlug.length >= 4 && modelSlug !in GENERIC_TOKENS &&
                (slug.contains(modelSlug) || metaTvSlug.contains(modelSlug))
            ) {
                idScore += 550
            }
            if (deviceSlug.length >= 4 && deviceSlug !in GENERIC_TOKENS &&
                (slug.contains(deviceSlug) || metaTvSlug.contains(deviceSlug))
            ) {
                idScore += 420
            }
            if (boardSlug.length >= 4 && boardSlug !in GENERIC_TOKENS &&
                (slug.contains(boardSlug) || metaTvSlug.contains(boardSlug))
            ) {
                idScore += 240
            }
            if (brandSlugs.any { b -> slug == b || slug.startsWith("$b-") || metaBrandSlug == b }) {
                idScore += 160
            }
            val pTokens = slugTokens(slug) + slugTokens(metaTvSlug)
            val sharedModel = pTokens.intersect(modelTokens).size
            val sharedAll = pTokens.intersect(allIdentityTokens).size
            idScore += sharedModel * 90 + (sharedAll - sharedModel) * 35
        }

        val coverage = (matched.size * 100) / entries.size.coerceAtLeast(1)
        val totalScore = idScore + matched.size * 8 + coverage / 2

        if (totalScore > bestTotalScore || (totalScore == bestTotalScore && matched.size > bestHits)) {
            bestSlug = slug
            bestIdentityScore = idScore
            bestTotalScore = totalScore
            bestHits = matched.size
            bestMatched = matched
        }
    }

    if (bestSlug != null && bestIdentityScore >= 400 && bestHits > 0) {
        val info = meta[bestSlug]
        return ProfileMatch(
            profileName = bestSlug,
            profileTitle = info?.tv?.ifBlank { null },
            profileOs = info?.os?.ifBlank { null },
            kind = ProfileMatchKind.EXACT_MODEL,
            items = enrich(bestMatched),
            totalLists = profiles.size
        )
    }
    if (bestSlug != null && bestIdentityScore >= 160 && bestHits >= 3) {
        val info = meta[bestSlug]
        return ProfileMatch(
            profileName = bestSlug,
            profileTitle = info?.tv?.ifBlank { null },
            profileOs = info?.os?.ifBlank { null },
            kind = ProfileMatchKind.BRAND_MATCH,
            items = enrich(bestMatched),
            totalLists = profiles.size
        )
    }

    // CleanYourTV match.py consensus fallback for unlisted TVs:
    // Weight same-brand lists 3x and prefer packages disabled more often than commented/kept.
    val pkgWeights = HashMap<String, Int>()
    for ((slug, parsed) in profiles) {
        val metaBrandSlug = meta[slug]?.brand?.let(::slugify).orEmpty()
        val sameBrand = brandSlugs.any { b -> slug == b || slug.startsWith("$b-") || metaBrandSlug == b }
        val weight = if (sameBrand) 3 else 1
        for (entry in parsed.disabled) {
            if (entry.id !in installedIds || isCoreProtectedPackage(entry.id)) continue
            pkgWeights[entry.id] = (pkgWeights[entry.id] ?: 0) + weight
        }
    }

    val minWeight = if (profiles.size >= 10) 3 else 1
    val detected = pkgWeights.entries
        .filter { (id, w) ->
            val t = tallies[id]
            w >= minWeight && (t == null || t.disabledCount >= t.keptCount)
        }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { (id, _) ->
            val t = tallies[id]
            CleanupItem(
                id = id,
                note = t?.commonNote.orEmpty(),
                disabledCount = t?.disabledCount ?: 0,
                keptCount = t?.keptCount ?: 0
            )
        }

    if (detected.isNotEmpty()) {
        return ProfileMatch(null, null, null, ProfileMatchKind.AUTO_DETECTED, detected, profiles.size)
    }
    if (bestSlug != null && bestMatched.isNotEmpty()) {
        val info = meta[bestSlug]
        return ProfileMatch(
            profileName = bestSlug,
            profileTitle = info?.tv?.ifBlank { null },
            profileOs = info?.os?.ifBlank { null },
            kind = ProfileMatchKind.BRAND_MATCH,
            items = enrich(bestMatched),
            totalLists = profiles.size
        )
    }
    return ProfileMatch(null, null, null, ProfileMatchKind.AUTO_DETECTED, emptyList(), profiles.size)
}
