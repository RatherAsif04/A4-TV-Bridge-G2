package dev.a4tvbridge.independent

import android.view.Gravity
import android.widget.TextView

internal data class AppBadgeStyle(val monogram: String, val fill: Int, val ink: Int = 0xFFFFFFFF.toInt())

internal object AppBrandRegistry {
    private val known = listOf(
        "smarttube" to AppBadgeStyle("ST", 0xFFD92D20.toInt()),
        "youtube" to AppBadgeStyle("YT", 0xFFE11D48.toInt()),
        "netflix" to AppBadgeStyle("N", 0xFFB91C1C.toInt()),
        "amazon.avod" to AppBadgeStyle("PV", 0xFF0284C7.toInt()),
        "primevideo" to AppBadgeStyle("PV", 0xFF0284C7.toInt()),
        "disney" to AppBadgeStyle("D+", 0xFF1D4ED8.toInt()),
        "jellyfin" to AppBadgeStyle("JF", 0xFF7C3AED.toInt()),
        "plex" to AppBadgeStyle("PX", 0xFFD97706.toInt()),
        "kodi" to AppBadgeStyle("KD", 0xFF0369A1.toInt()),
        "spotify" to AppBadgeStyle("SP", 0xFF15803D.toInt()),
        "twitch" to AppBadgeStyle("TW", 0xFF6D28D9.toInt()),
        "videolan.vlc" to AppBadgeStyle("VL", 0xFFEA580C.toInt()),
        "stremio" to AppBadgeStyle("SM", 0xFF4F46E5.toInt()),
        "aerialviews" to AppBadgeStyle("AV", 0xFF0F766E.toInt()),
        "projengmenu" to AppBadgeStyle("PL", 0xFF0284C7.toInt()),
        "chrome" to AppBadgeStyle("CH", 0xFF2563EB.toInt()),
        "firefox" to AppBadgeStyle("FF", 0xFFEA580C.toInt()),
        "vending" to AppBadgeStyle("GP", 0xFF059669.toInt()),
        "settings" to AppBadgeStyle("ST", 0xFF475569.toInt()),
        "launcher" to AppBadgeStyle("HM", 0xFF00587B.toInt()),
        "hotstar" to AppBadgeStyle("HS", 0xFF1E3A8A.toInt()),
        "jio" to AppBadgeStyle("JC", 0xFFBE185D.toInt()),
        "sonyliv" to AppBadgeStyle("SL", 0xFF374151.toInt()),
        "hbo" to AppBadgeStyle("MX", 0xFF1D4ED8.toInt()),
        "apple.atve" to AppBadgeStyle("TV", 0xFF111827.toInt()),
        "hulu" to AppBadgeStyle("HL", 0xFF16A34A.toInt()),
        "telegram" to AppBadgeStyle("TG", 0xFF0284C7.toInt())
    )

    private val palette = intArrayOf(
        0xFF00587B.toInt(),
        0xFF15573E.toInt(),
        0xFF5C45A0.toInt(),
        0xFF9A4E13.toInt(),
        0xFF1E40AF.toInt(),
        0xFF0F766E.toInt(),
        0xFF6D28D9.toInt(),
        0xFF334155.toInt()
    )

    fun resolve(packageName: String): AppBadgeStyle {
        val lower = packageName.lowercase()
        for ((needle, style) in known) {
            if (needle in lower) return style
        }
        val title = displayName(packageName)
        val words = title.split(' ').filter(String::isNotBlank)
        val monogram = when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            title.length >= 2 -> title.take(2).uppercase()
            title.isNotEmpty() -> title.take(1).uppercase()
            else -> "TV"
        }
        val index = (lower.hashCode() and Int.MAX_VALUE) % palette.size
        return AppBadgeStyle(monogram, palette[index])
    }
}

internal fun BridgeActivity.appBadge(packageName: String): TextView {
    val style = AppBrandRegistry.resolve(packageName)
    return text(style.monogram, 12, style.ink, bold = true).apply {
        gravity = Gravity.CENTER
        background = card(style.fill, 10, outlined = false)
        letterSpacing = 0.04f
    }
}
