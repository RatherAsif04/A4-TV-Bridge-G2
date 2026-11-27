package dev.a4tvbridge.independent

import android.app.Dialog
import android.content.res.ColorStateList
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

internal fun BridgeActivity.tile(tile: Tile): View = row().apply {
    val surface = card(if (tile.selected) tint else field, corner)
    background = RippleDrawable(ColorStateList.valueOf(ripple), surface, null)
    minimumHeight = px(64)
    setPadding(px(11), px(12), px(10), px(12))
    val iconColor = when (tile.icon) {
        Mark.REMOTE, Mark.SHELL, Mark.VIDEO, Mark.SHIELD -> purple
        Mark.FOLDER, Mark.UPLOAD, Mark.CHECK -> orange
        else -> accent
    }
    addView(Glyph(this@tile, tile.icon, iconColor), LinearLayout.LayoutParams(px(22), px(22)))
    val copy = column().apply {
        addView(text(tile.title, 16, bold = true))
        if (tile.detail.isNotBlank()) addView(text(tile.detail, 11, muted, true).apply { letterSpacing = .035f }.apply { setPadding(0, px(3), 0, 0) })
    }
    addView(copy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(10) })
    isFocusable = true
    isClickable = true
    contentDescription = tile.title
    setOnClickListener { tile.click() }
}

internal fun BridgeActivity.tiles(parent: LinearLayout, columns: Int = 2, vararg items: Tile, gap: Int = 9) {
    for (chunk in items.toList().chunked(columns)) {
        val strip = row()
        for ((index, item) in chunk.withIndex()) {
            strip.addView(tile(item), LinearLayout.LayoutParams(0, BridgeActivity.MATCH, 1f).apply {
                if (index > 0) marginStart = px(9)
            })
        }
        repeat(columns - chunk.size) { strip.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
        parent.addView(strip, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(gap) })
    }
}

internal fun BridgeActivity.dock(current: Panel, control: () -> Unit): View {
    val options = listOf(
        Triple("Dashboard", Mark.DASHBOARD, Panel.DASHBOARD),
        Triple("Apps & Files", Mark.APPS, Panel.LIBRARY),
        Triple("Control", Mark.CONTROL, null),
        Triple("ADB Shell / Terminal", Mark.SHELL, Panel.SHELL),
        Triple("TV Care", Mark.CARE, Panel.CARE)
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
        val surface = View(this@dock).apply { background = card(radius = corner) }
        addView(surface, FrameLayout.LayoutParams(BridgeActivity.MATCH, px(66), Gravity.BOTTOM))
        val strip = row().apply { gravity = Gravity.BOTTOM; clipChildren = false }
        addView(strip, FrameLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.MATCH, Gravity.BOTTOM))
        for ((label, icon, destination) in options) {
            val highlight = destination == selected
            val color = if (highlight || destination == null) accent else muted
            val tab = column().apply {
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                contentDescription = label
                background = RippleDrawable(ColorStateList.valueOf(ripple), if (highlight) card(tint, 14, false) else null, card(radius = 14))
                setOnClickListener { if (destination == null) control() else show(destination) }
                val graphic = Glyph(this@dock, icon, if (destination == null) onAccent else color, if (destination == null) 30 else 19)
                if (destination == null) {
                    graphic.background = card(accent, 50, false)
                    graphic.elevation = px(3).toFloat()
                    addView(graphic, LinearLayout.LayoutParams(px(48), px(48)).apply { bottomMargin = px(4) })
                } else addView(graphic, LinearLayout.LayoutParams(px(20), px(20)).apply { bottomMargin = px(3) })
                addView(text(when (destination) {
                    Panel.LIBRARY -> "Apps &\nFiles"
                    Panel.SHELL -> "ADB\nTerminal"
                    else -> label
                }, 11, color, true).apply {
                    letterSpacing = .03f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    minLines = if (destination == null) 1 else 2
                })
            }
            strip.addView(tab, LinearLayout.LayoutParams(0, px(if (destination == null) 78 else 60), 1f).apply {
                marginStart = px(3); marginEnd = px(3); bottomMargin = px(if (destination == null) 6 else 3)
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
            setColor(surface)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            if (dark) setStroke(px(1), edge)
        }
        setPadding(px(21), px(18), px(21), px(24))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            view.setPadding(px(21), px(18), px(21), px(24) + nav.bottom)
            insets
        }
        val handle = View(this@showSheet).apply { background = card(muted, 5, false) }
        addView(handle, LinearLayout.LayoutParams(px(34), px(4)).apply { gravity = Gravity.CENTER; bottomMargin = px(14) })
        val header = row()
        header.addView(text(title, 21, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginEnd = px(8) })
        if (closeIcon) header.addView(Glyph(this@showSheet, Mark.CLOSE, muted, 20).apply {
            contentDescription = "Close"
            isFocusable = true
            setOnClickListener { popup.dismiss() }
        }, LinearLayout.LayoutParams(px(42), px(40)))
        else header.minimumHeight = px(44)
        addView(header, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
    }
    populate(popup, sheet)
    popup.setContentView(sheet)
    popup.show()
    popup.window?.run {
        setBackgroundDrawableResource(android.R.color.transparent)
        setDimAmount(0.45f)
        addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        navigationBarColor = surface
        androidx.core.view.WindowCompat.getInsetsController(this, decorView).isAppearanceLightNavigationBars = !dark
        setGravity(Gravity.BOTTOM)
        setLayout(BridgeActivity.MATCH, BridgeActivity.WRAP)
    }
}

internal fun BridgeActivity.attachSheetBody(frame: LinearLayout, body: LinearLayout) {
    val height = px((resources.configuration.screenHeightDp - 220).coerceAtLeast(160))
    body.measure(
        View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.widthPixels - px(42)).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
    frame.addView(
        android.widget.ScrollView(this).apply { addView(body) },
        LinearLayout.LayoutParams(BridgeActivity.MATCH, minOf(height, body.measuredHeight))
    )
}

internal fun BridgeActivity.outlinedCard(fill: Int = field, radius: Int = 22, stroke: Int = 2) =
    column().apply {
        background = card(fill, radius).apply { setStroke(px(stroke), edge) }
        setPadding(px(16), px(14), px(16), px(14))
    }

internal fun BridgeActivity.rule() = View(this).apply {
    setBackgroundColor(edge)
    layoutParams = LinearLayout.LayoutParams(BridgeActivity.MATCH, px(1))
}

internal fun BridgeActivity.floatingAction(label: String, icon: Mark, action: () -> Unit) {
    content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, px(102))
    val chip = row().apply {
        gravity = Gravity.CENTER
        background = RippleDrawable(ColorStateList.valueOf(ripple), card(tint, 20, false), null)
        val graphic = Glyph(this@floatingAction, icon, accent)
        addView(graphic, LinearLayout.LayoutParams(px(21), px(21)))
        addView(text(label, 17, accent, true), LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply {
            marginStart = px(10)
        })
        setPadding(px(20), 0, px(20), 0)
        elevation = px(7).toFloat()
        contentDescription = label
        isFocusable = true
        setOnClickListener { action() }
    }
    screenBody.addView(chip, FrameLayout.LayoutParams(BridgeActivity.WRAP, px(56), Gravity.BOTTOM or Gravity.END).apply {
        bottomMargin = px(26)
    })
}

internal fun BridgeActivity.filePickerSheet(title: String, detail: String, choose: () -> Unit) {
    showSheet(title) { sheet, area ->
        area.addView(text(detail, 15, muted).apply { setPadding(0, 0, 0, px(13)) })
        area.addView(button("Browse device", emphasis = true) {
            sheet.dismiss()
            choose()
        }, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(54)))
    }
}
