package dev.a4tvbridge.independent

import android.graphics.Bitmap
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView

internal data class AppBadgeStyle(val monogram: String, val fill: Int, val ink: Int = 0xFFFFFFFF.toInt())

internal object AppBrandRegistry {
    private val known = listOf(
        "smarttube" to AppBadgeStyle("ST", 0xFFD92D20.toInt()),
        "youtube" to AppBadgeStyle("YT", 0xFFE11D48.toInt()),
        "netflix" to AppBadgeStyle("N", 0xFFB91C1C.toInt()),
        "amazon.avod" to AppBadgeStyle("PV", 0xFF0284C7.toInt()),
        "amazonvideo" to AppBadgeStyle("PV", 0xFF0284C7.toInt()),
        "primevideo" to AppBadgeStyle("PV", 0xFF0284C7.toInt()),
        "minitv" to AppBadgeStyle("MT", 0xFFEC4899.toInt()),
        "netmirror" to AppBadgeStyle("NM", 0xFFDC2626.toInt()),
        "tvplayer" to AppBadgeStyle("TP", 0xFF00587B.toInt()),
        "mbox" to AppBadgeStyle("MB", 0xFF0F766E.toInt()),
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

internal fun BridgeActivity.appBadge(packageName: String): FrameLayout {
    val style = AppBrandRegistry.resolve(packageName)
    val radiusPx = px(10).toFloat()
    val container = FrameLayout(this).apply {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
        clipToOutline = true
    }

    fun applyBitmap(bitmap: Bitmap, animate: Boolean) {
        val isWideBanner = bitmap.width > bitmap.height * 1.35f
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            contentDescription = displayName(packageName)
            scaleType = if (isWideBanner) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            if (isWideBanner) {
                setPadding(px(2), px(2), px(2), px(2))
            }
            if (animate) alpha = 0f
        }
        container.background = card(surfaceElevated, 10, outlined = true)
        container.removeAllViews()
        container.addView(image, FrameLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.MATCH))
        if (animate) {
            image.animate().alpha(1f).setDuration(160L).start()
        }
    }

    val immediate = TvAppIconLoader.getCachedOrLocal(this, packageName)
    if (immediate != null) {
        applyBitmap(immediate, animate = false)
        return container
    }

    // Immediate crisp A4 monogram placeholder while real TV icon extracts in background
    val monogram = text(style.monogram, 12, style.ink, bold = true).apply {
        gravity = Gravity.CENTER
        background = card(style.fill, 10, outlined = false)
        letterSpacing = 0.04f
    }
    container.addView(monogram, FrameLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.MATCH))

    if (connection != null) {
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            private var requested = false
            override fun onViewAttachedToWindow(v: View) {
                if (requested) return
                requested = true
                TvAppIconLoader.requestFromTv(this@appBadge, packageName, v) { loaded ->
                    applyBitmap(loaded, animate = true)
                }
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }
    return container
}
