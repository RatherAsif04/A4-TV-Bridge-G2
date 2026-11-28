package dev.a4tvbridge.independent

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout

internal data class Tile(
    val title: String,
    val icon: Mark,
    val detail: String = "",
    val selected: Boolean = false,
    val click: () -> Unit
)

internal fun BridgeActivity.card(fill: Int = field, radius: Int = corner, outlined: Boolean = true) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = px(radius).toFloat()
    if (outlined) setStroke(px(1), edge)
}

internal fun BridgeActivity.sectionLabel(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null): View =
    row().apply {
        addView(text(title.uppercase(), 11, dim, bold = true).apply {
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        if (actionLabel != null && onAction != null) {
            addView(textButton("$actionLabel  ›", actionLabel, onAction).apply {
                textSize = 12f
                minHeight = 0
                minimumHeight = 0
                setPadding(px(4), px(2), 0, px(2))
            })
        }
    }

internal fun BridgeActivity.tile(tile: Tile): View = row().apply {
    val surfaceBg = card(if (tile.selected) tint else field, corner).apply {
        if (tile.selected) setStroke(px(1), accent)
    }
    background = RippleDrawable(ColorStateList.valueOf(ripple), surfaceBg, null)
    minimumHeight = px(64)
    setPadding(px(13), px(12), px(12), px(12))
    val iconColor = when (tile.icon) {
        Mark.REMOTE, Mark.SHELL, Mark.VIDEO, Mark.SHIELD -> purple
        Mark.FOLDER, Mark.UPLOAD, Mark.HOME -> orange
        Mark.CHECK, Mark.RESTORE -> green
        else -> accent
    }
    val iconBadge = FrameLayout(this@tile).apply {
        val badgeBg = if (dark) 0xFF0C1A28.toInt() else 0xFFF1F5F9.toInt()
        background = card(badgeBg, 10, outlined = false).apply {
            setStroke(px(1), if (tile.selected) accent else edge)
        }
        addView(Glyph(this@tile, tile.icon, iconColor, 18), FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER))
    }
    addView(iconBadge, LinearLayout.LayoutParams(px(36), px(36)))
    val copy = column().apply {
        addView(text(tile.title, 15, bold = true))
        if (tile.detail.isNotBlank()) {
            addView(text(tile.detail, 12, muted).apply { setPadding(0, px(2), 0, 0) })
        }
    }
    addView(copy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(12) })
    addView(text("›", 19, dim, bold = true).apply { setPadding(px(4), 0, 0, 0) })
    isFocusable = true
    isClickable = true
    contentDescription = tile.title
    setOnClickListener { tile.click() }
}

internal fun BridgeActivity.tiles(parent: LinearLayout, columns: Int = 2, vararg items: Tile, gap: Int = 10) {
    for (chunk in items.toList().chunked(columns)) {
        val strip = row()
        for ((index, item) in chunk.withIndex()) {
            strip.addView(tile(item), LinearLayout.LayoutParams(0, BridgeActivity.MATCH, 1f).apply {
                if (index > 0) marginStart = px(10)
            })
        }
        repeat(columns - chunk.size) { strip.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
        parent.addView(strip, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(gap) })
    }
}

internal fun BridgeActivity.dock(current: Panel, control: () -> Unit): View {
    // Exact 5-tab dock from screenshots: Home | Apps | [Control FAB] | TV Care | Shell
    val options = listOf(
        Triple("Home", Mark.DASHBOARD, Panel.DASHBOARD),
        Triple("Apps", Mark.APPS, Panel.LIBRARY),
        Triple("Control", Mark.CONTROL, null),
        Triple("TV Care", Mark.CARE, Panel.CARE),
        Triple("Shell", Mark.SHELL, Panel.SHELL)
    )
    val library = setOf(Panel.LIBRARY, Panel.APPS, Panel.FILES, Panel.INSTALL, Panel.SEND)
    val care = setOf(Panel.CARE, Panel.CLEAN, Panel.CHECK, Panel.RESTORE, Panel.SPACE, Panel.INFO, Panel.HOME)
    val selected = when (current) {
        in library -> Panel.LIBRARY
        in care -> Panel.CARE
        else -> current
    }
    return FrameLayout(this).apply {
        clipChildren = false
        val dockFill = if (dark) 0xFF0C1118.toInt() else Color.WHITE
        val surfaceView = View(this@dock).apply {
            background = card(dockFill, 24, outlined = true)
        }
        addView(surfaceView, FrameLayout.LayoutParams(BridgeActivity.MATCH, px(66), Gravity.BOTTOM))
        val strip = row().apply { gravity = Gravity.BOTTOM; clipChildren = false }
        addView(strip, FrameLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.MATCH, Gravity.BOTTOM))
        for ((label, icon, destination) in options) {
            val highlight = destination == selected
            val color = if (highlight) accent else muted
            val tab = column().apply {
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                contentDescription = label
                background = RippleDrawable(ColorStateList.valueOf(ripple), null, card(radius = 16))
                setOnClickListener { if (destination == null) control() else show(destination) }
                if (destination == null) {
                    val fab = FrameLayout(this@dock).apply {
                        val fabFill = if (dark) 0xFF0284C7.toInt() else 0xFF0077A8.toInt()
                        background = card(fabFill, 50, outlined = false).apply {
                            setStroke(px(2), 0xFF38BDF8.toInt())
                        }
                        elevation = px(4).toFloat()
                        addView(Glyph(this@dock, icon, Color.WHITE, 26), FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER))
                    }
                    addView(fab, LinearLayout.LayoutParams(px(50), px(50)).apply { bottomMargin = px(3) })
                    addView(text(label, 11, if (current == Panel.REMOTE || current == Panel.MIRROR) accent else muted, bold = current == Panel.REMOTE || current == Panel.MIRROR).apply {
                        gravity = Gravity.CENTER
                        isSingleLine = true
                    })
                } else {
                    val iconWrap = FrameLayout(this@dock).apply {
                        if (highlight) {
                            background = card(if (dark) 0xFF0C2A3D.toInt() else 0xFFE0F2FE.toInt(), 14, outlined = false)
                        }
                        addView(Glyph(this@dock, icon, color, 19), FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER))
                    }
                    addView(iconWrap, LinearLayout.LayoutParams(px(44), px(28)).apply { bottomMargin = px(4) })
                    addView(text(label, 11, color, bold = highlight).apply {
                        gravity = Gravity.CENTER
                        isSingleLine = true
                    })
                }
            }
            strip.addView(tab, LinearLayout.LayoutParams(0, px(if (destination == null) 78 else 58), 1f).apply {
                marginStart = px(2); marginEnd = px(2); bottomMargin = px(if (destination == null) 6 else 4)
            })
        }
    }
}

internal fun BridgeActivity.showSheet(
    title: String,
    closeIcon: Boolean = true,
    populate: (Dialog, LinearLayout) -> Unit
) {
    val popup = Dialog(this, dialogTheme)
    popup.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val sheet = column().apply {
        val r = px(22).toFloat()
        background = GradientDrawable().apply {
            setColor(field)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            if (dark) setStroke(px(1), edge)
        }
        setPadding(px(20), px(16), px(20), px(24))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            view.setPadding(px(20), px(16), px(20), px(24) + nav.bottom)
            insets
        }
        val handle = View(this@showSheet).apply { background = card(dim, 5, false) }
        addView(handle, LinearLayout.LayoutParams(px(36), px(4)).apply { gravity = Gravity.CENTER; bottomMargin = px(14) })
        val header = row()
        header.addView(text(title, 20, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginEnd = px(8) })
        if (closeIcon) header.addView(Glyph(this@showSheet, Mark.CLOSE, muted, 20).apply {
            contentDescription = "Close"
            isFocusable = true
            setOnClickListener { popup.dismiss() }
        }, LinearLayout.LayoutParams(px(40), px(40)))
        else header.minimumHeight = px(40)
        addView(header, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
    }
    populate(popup, sheet)
    popup.setContentView(sheet)
    popup.show()
    popup.window?.run {
        setBackgroundDrawableResource(android.R.color.transparent)
        setDimAmount(0.55f)
        addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        navigationBarColor = field
        androidx.core.view.WindowCompat.getInsetsController(this, decorView).isAppearanceLightNavigationBars = !dark
        setGravity(Gravity.BOTTOM)
        setLayout(BridgeActivity.MATCH, BridgeActivity.WRAP)
    }
}

internal fun BridgeActivity.attachSheetBody(frame: LinearLayout, body: LinearLayout) {
    val height = px((resources.configuration.screenHeightDp - 220).coerceAtLeast(160))
    body.measure(
        View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.widthPixels - px(40)).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
    frame.addView(
        android.widget.ScrollView(this).apply { addView(body) },
        LinearLayout.LayoutParams(BridgeActivity.MATCH, minOf(height, body.measuredHeight))
    )
}

internal fun BridgeActivity.outlinedCard(fill: Int = field, radius: Int = 20, stroke: Int = 1) =
    column().apply {
        background = card(fill, radius, outlined = false).apply { setStroke(px(stroke), edge) }
        setPadding(px(16), px(15), px(16), px(15))
    }

internal fun BridgeActivity.rule() = View(this).apply {
    setBackgroundColor(edge)
    layoutParams = LinearLayout.LayoutParams(BridgeActivity.MATCH, px(1))
}

internal fun BridgeActivity.floatingAction(label: String, icon: Mark, action: () -> Unit) {
    content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, px(102))
    val chip = row().apply {
        gravity = Gravity.CENTER
        background = RippleDrawable(ColorStateList.valueOf(ripple), card(accentStrong, 18, false).apply {
            if (dark) setStroke(px(1), accent)
        }, null)
        val graphic = Glyph(this@floatingAction, icon, Color.WHITE)
        addView(graphic, LinearLayout.LayoutParams(px(20), px(20)))
        addView(text(label, 16, Color.WHITE, true), LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply {
            marginStart = px(10)
        })
        setPadding(px(20), 0, px(20), 0)
        elevation = px(6).toFloat()
        contentDescription = label
        isFocusable = true
        setOnClickListener { action() }
    }
    screenBody.addView(chip, FrameLayout.LayoutParams(BridgeActivity.WRAP, px(54), Gravity.BOTTOM or Gravity.END).apply {
        bottomMargin = px(24)
    })
}

internal fun BridgeActivity.filePickerSheet(title: String, detail: String, choose: () -> Unit) {
    showSheet(title) { sheet, area ->
        area.addView(text(detail, 14, muted).apply { setPadding(0, 0, 0, px(13)) })
        area.addView(button("Browse device", emphasis = true) {
            sheet.dismiss()
            choose()
        }, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(52)))
    }
}
