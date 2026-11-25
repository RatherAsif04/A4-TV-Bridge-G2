package dev.a4tvbridge.independent

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

internal enum class Appearance(val label: String, val summary: String) {
    SYSTEM("System Default", "Follow Android’s light or dark setting."),
    LIGHT("Light", "White surfaces with neutral gray borders."),
    DARK("Dark", "True black with ice-blue controls.");

    fun usesDark(systemDark: Boolean) = this == DARK || (this == SYSTEM && systemDark)

    companion object {
        fun read(value: String?): Appearance = when (value?.uppercase(java.util.Locale.ROOT)) {
            "LIGHT", "WHITE", "GLACIER", "SANDSTONE", "ORIGINAL" -> LIGHT
            "DARK", "AMOLED", "MIDNIGHT" -> DARK
            else -> SYSTEM
        }
    }
}

internal fun BridgeActivity.appearanceChoice(): Appearance {
    val saved = preferences.getString("theme", null)
        ?: getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", null)
    return Appearance.read(saved)
}

internal fun Context.systemUsesDark() =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

internal fun BridgeActivity.appearancePage() {
    heading("Appearance")
    val selected = appearanceChoice()
    for (choice in Appearance.entries) {
        val sampleDark = choice.usesDark(systemUsesDark())
        val active = selected == choice
        val tile = row().apply {
            minimumHeight = px(70)
            setPadding(px(14), px(12), px(14), px(12))
            val tileSurface = card(if (active) tint else field, corner).apply { if (active) setStroke(px(2), accent) }
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ripple), tileSurface, null
            )
            isFocusable = true
            isClickable = true
            isSelected = active
            contentDescription = choice.label
            setOnClickListener {
                if (!active) {
                    preferences.edit().putString("theme", choice.name).apply()
                    refresh()
                }
            }
        }
        val preview = View(this).apply { background = card(if (sampleDark) 0xFF142835.toInt() else 0xFFDDF3FC.toInt(), 32, false) }
        val swatch = object : View(this) {
            private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (sampleDark) 0xFFB0DEFF.toInt() else 0xFF00587B.toInt()
            }
            override fun onDraw(canvas: Canvas) {
                canvas.drawCircle(width / 2f, height / 2f, width / 4f, brush)
            }
        }
        val layered = android.widget.FrameLayout(this).apply {
            addView(preview)
            addView(swatch)
        }
        tile.addView(layered, LinearLayout.LayoutParams(px(32), px(32)))
        val legend = column().apply {
            addView(text(choice.label, 16, bold = true))
            addView(text(choice.summary, 13, muted).apply { setPadding(0, px(3), 0, 0) })
        }
        tile.addView(legend, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply {
            marginStart = px(10)
            marginEnd = px(10)
        })
        val radio = object : View(this) {
            private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (active) accent else muted
                strokeWidth = resources.displayMetrics.density * 2
            }
            override fun onDraw(canvas: Canvas) {
                val center = width / 2f
                brush.style = Paint.Style.STROKE
                canvas.drawCircle(center, height / 2f, center - brush.strokeWidth, brush)
                if (active) {
                    brush.style = Paint.Style.FILL
                    canvas.drawCircle(center, height / 2f, center * .52f, brush)
                }
            }
        }
        tile.addView(radio, LinearLayout.LayoutParams(px(22), px(22)).apply { gravity = Gravity.CENTER_VERTICAL })
        content.addView(tile, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
    }
    content.addView(textButton("About A4 TV Bridge") { openAbout() })
}
