package dev.a4tvbridge.independent

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

internal enum class Appearance(val label: String, val summary: String) {
    SYSTEM("System Default", "Follow Android’s light or dark setting."),
    LIGHT("Light", "Crisp white surfaces with cool slate borders."),
    DARK("Dark", "Deep slate-black with ocean-blue controls."),
    AMOLED("AMOLED Black", "Pure #000000 black surfaces for OLED displays.");

    fun usesDark(systemDark: Boolean) = this == DARK || this == AMOLED || (this == SYSTEM && systemDark)

    companion object {
        fun read(value: String?): Appearance = when (value?.uppercase(java.util.Locale.ROOT)) {
            "LIGHT", "WHITE", "GLACIER", "SANDSTONE", "ORIGINAL" -> LIGHT
            "AMOLED" -> AMOLED
            "DARK", "MIDNIGHT" -> DARK
            else -> SYSTEM
        }
    }
}

internal fun BridgeActivity.appearanceChoice(): Appearance {
    val saved = preferences.getString("theme", null)
        ?: getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", null)
    return Appearance.read(saved)
}

internal fun BridgeActivity.dynamicColorEnabled(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && preferences.getBoolean("dynamic_color", false)

internal fun Context.systemUsesDark() =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

internal fun BridgeActivity.appearancePage() {
    pageHeader("Appearance", "Choose theme mode, OLED black, or Material You accent")
    val selected = appearanceChoice()
    for (choice in Appearance.entries) {
        val sampleDark = choice.usesDark(systemUsesDark())
        val isAmoled = choice == Appearance.AMOLED
        val active = selected == choice
        val tile = row().apply {
            minimumHeight = px(70)
            setPadding(px(14), px(12), px(14), px(12))
            val tileSurface = card(if (active) tint else field, corner).apply { if (active) setStroke(px(2), accent) }
            background = interactiveBackground(tileSurface)
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
        val previewBg = when {
            isAmoled -> 0xFF000000.toInt()
            sampleDark -> 0xFF142835.toInt()
            else -> 0xFFDDF3FC.toInt()
        }
        val preview = View(this).apply {
            background = card(previewBg, 32, outlined = isAmoled)
        }
        val swatch = object : View(this) {
            private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (sampleDark) 0xFF38BDF8.toInt() else 0xFF00587B.toInt()
            }
            override fun onDraw(canvas: Canvas) {
                canvas.drawCircle(width / 2f, height / 2f, width / 4f, brush)
            }
        }
        val layered = FrameLayout(this).apply {
            addView(preview)
            addView(swatch)
        }
        tile.addView(layered, LinearLayout.LayoutParams(px(32), px(32)))
        val legend = column().apply {
            addView(text(choice.label, 16, bold = true))
            addView(text(choice.summary, 13, muted).apply { setPadding(0, px(3), 0, 0) })
        }
        tile.addView(legend, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply {
            marginStart = px(12)
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynOn = dynamicColorEnabled()
        content.addView(sectionLabel("Accent color"),
            LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(6); bottomMargin = px(8) })
        val dynCard = row().apply {
            minimumHeight = px(66)
            setPadding(px(14), px(12), px(14), px(12))
            val bg = card(if (dynOn) tint else field, corner).apply {
                if (dynOn) setStroke(px(2), accent)
            }
            background = RippleDrawable(ColorStateList.valueOf(ripple), bg, null)
            isFocusable = true
            isClickable = true
            isSelected = dynOn
            contentDescription = "Material You Dynamic Color"
            setOnClickListener {
                preferences.edit().putBoolean("dynamic_color", !dynOn).apply()
                refresh()
            }
        }
        val copy = column().apply {
            addView(text("Material You Dynamic Color", 15, bold = true))
            addView(text("Match accent controls to your Android wallpaper palette.", 12, muted).apply {
                setPadding(0, px(3), 0, 0)
            })
        }
        dynCard.addView(copy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginEnd = px(10) })
        dynCard.addView(text(if (dynOn) "On" else "Off", 12, if (dynOn) onAccent else muted, bold = true).apply {
            background = if (dynOn) rounded(accentStrong, 12) else card(surfaceElevated, 12, outlined = true)
            setPadding(px(12), px(6), px(12), px(6))
        })
        content.addView(dynCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })
    }

    content.addView(textButton("About A4 TV Bridge") { openAbout() },
        LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply { topMargin = px(4) })
}
