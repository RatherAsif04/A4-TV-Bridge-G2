package dev.a4tvbridge.independent

internal data class CleanupItem(val id: String, val note: String)

internal fun cleanupEntries(input: String): List<CleanupItem> = input.lineSequence()
    .mapNotNull { source ->
        val name = source.substringBefore('#').trim()
        val id = runCatching { packageId(name) }.getOrNull() ?: return@mapNotNull null
        CleanupItem(id, source.substringAfter('#', "").trim())
    }.distinctBy(CleanupItem::id).toList()

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
    val activeKeyboardPackage: String? = null
) {
    fun isProtected(name: String): Boolean {
        val id = runCatching { packageId(name) }.getOrNull() ?: return true
        if (id in CORE_PROTECTED) return true
        if (activeHomePackage != null && id == activeHomePackage) return true
        if (activeKeyboardPackage != null && id == activeKeyboardPackage) return true
        return id.contains("tvinput", true) ||
            id.contains("bluetooth", true) ||
            id.contains("remote", true)
    }

    companion object {
        private val CORE_PROTECTED = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.vending",
            "com.google.android.gms"
        )
    }
}

internal enum class ProfileMatchKind {
    MANUAL,
    EXACT_MODEL,
    BRAND_MATCH,
    AUTO_DETECTED
}

internal data class ProfileMatch(
    val profileName: String?,
    val kind: ProfileMatchKind,
    val items: List<CleanupItem>
)

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
    profiles: Map<String, List<CleanupItem>>,
    savedProfile: String? = null
): ProfileMatch {
    if (!savedProfile.isNullOrBlank()) {
        val chosen = profiles[savedProfile]
        if (chosen != null) {
            return ProfileMatch(
                profileName = savedProfile,
                kind = ProfileMatchKind.MANUAL,
                items = chosen.filter { it.id in installedIds }
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
    val modelTokens = (slugTokens(modelSlug) + slugTokens(deviceSlug) + slugTokens(boardSlug))
    val allIdentityTokens = modelTokens + brandSlugs.flatMap { slugTokens(it) }

    var bestSlug: String? = null
    var bestIdentityScore = 0
    var bestTotalScore = 0
    var bestHits = 0
    var bestMatched: List<CleanupItem> = emptyList()

    for ((slug, entries) in profiles) {
        val matched = entries.filter { it.id in installedIds }
        if (matched.isEmpty()) continue

        var idScore = 0
        if (slug in exactCandidates) {
            idScore += 1000
        } else {
            if (modelSlug.length >= 4 && modelSlug !in GENERIC_TOKENS && slug.contains(modelSlug)) {
                idScore += 550
            }
            if (deviceSlug.length >= 4 && deviceSlug !in GENERIC_TOKENS && slug.contains(deviceSlug)) {
                idScore += 380
            }
            if (boardSlug.length >= 4 && boardSlug !in GENERIC_TOKENS && slug.contains(boardSlug)) {
                idScore += 220
            }
            if (brandSlugs.any { b -> slug == b || slug.startsWith("$b-") }) {
                idScore += 160
            }
            val pTokens = slugTokens(slug)
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
        return ProfileMatch(bestSlug, ProfileMatchKind.EXACT_MODEL, bestMatched)
    }
    if (bestSlug != null && bestIdentityScore >= 160 && bestHits >= 3) {
        return ProfileMatch(bestSlug, ProfileMatchKind.BRAND_MATCH, bestMatched)
    }

    // Universal smart fallback: score installed packages across all bundled profiles
    val pkgWeights = mutableMapOf<String, Int>()
    val pkgNotes = mutableMapOf<String, String>()
    for ((slug, entries) in profiles) {
        val sameBrand = brandSlugs.any { b -> slug == b || slug.startsWith("$b-") }
        val weight = if (sameBrand) 3 else 1
        for (entry in entries) {
            if (entry.id !in installedIds) continue
            pkgWeights[entry.id] = (pkgWeights[entry.id] ?: 0) + weight
            if (entry.note.isNotBlank() && pkgNotes[entry.id].isNullOrBlank()) {
                pkgNotes[entry.id] = entry.note
            }
        }
    }

    val minWeight = if (profiles.size >= 10) 3 else 1
    val detected = pkgWeights.entries
        .filter { it.value >= minWeight }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { (id, _) -> CleanupItem(id, pkgNotes[id].orEmpty()) }

    if (detected.isNotEmpty()) {
        return ProfileMatch(null, ProfileMatchKind.AUTO_DETECTED, detected)
    }
    if (bestSlug != null && bestMatched.isNotEmpty()) {
        return ProfileMatch(bestSlug, ProfileMatchKind.BRAND_MATCH, bestMatched)
    }
    return ProfileMatch(null, ProfileMatchKind.AUTO_DETECTED, emptyList())
}
