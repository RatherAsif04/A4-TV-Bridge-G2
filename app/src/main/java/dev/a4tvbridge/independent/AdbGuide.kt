package dev.a4tvbridge.independent

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

internal data class AdbGuideStep(
    val number: Int,
    val title: String,
    val description: String,
    val pathChip: String? = null,
    val badge: String? = null,
    val showToggle: Boolean = false,
    val finalStep: Boolean = false
)

internal enum class TvGuideBrand(val label: String, val steps: List<AdbGuideStep>) {
    GOOGLE_TV(
        "Google TV",
        listOf(
            AdbGuideStep(
                number = 1,
                title = "Open TV Settings",
                description = "Open Settings from the top-right profile icon on your TV home screen.",
                pathChip = "Settings  ›  System  ›  About"
            ),
            AdbGuideStep(
                number = 2,
                title = "Unlock Developer Options",
                description = "Highlight Android TV OS build and press OK 7 times until developer mode unlocks.",
                badge = "Press OK 7×"
            ),
            AdbGuideStep(
                number = 3,
                title = "Enable USB Debugging",
                description = "Go back to System › Developer options and switch on USB debugging (or Wireless debugging).",
                showToggle = true
            ),
            AdbGuideStep(
                number = 4,
                title = "Approve TV Prompt",
                description = "Ensure phone & TV share the same Wi-Fi, then check 'Always allow' on the TV popup.",
                finalStep = true
            )
        )
    ),
    ANDROID_TV(
        "Android TV",
        listOf(
            AdbGuideStep(
                number = 1,
                title = "Open TV Settings",
                description = "Open Settings from the gear icon on your Android TV home screen.",
                pathChip = "Settings  ›  Device Preferences  ›  About"
            ),
            AdbGuideStep(
                number = 2,
                title = "Unlock Developer Options",
                description = "Scroll down to Build and press OK 7 times until 'You are now a developer' appears.",
                badge = "Press OK 7×"
            ),
            AdbGuideStep(
                number = 3,
                title = "Enable USB / Network Debugging",
                description = "Return to Device Preferences › Developer options and turn on USB or Network debugging.",
                showToggle = true
            ),
            AdbGuideStep(
                number = 4,
                title = "Approve TV Prompt",
                description = "Connect phone & TV to the same Wi-Fi, then select 'Always allow' on the TV prompt.",
                finalStep = true
            )
        )
    ),
    FIRE_TV(
        "Fire TV",
        listOf(
            AdbGuideStep(
                number = 1,
                title = "Open Fire TV Settings",
                description = "Go to the Settings gear on your Fire TV home screen and open My Fire TV.",
                pathChip = "Settings  ›  My Fire TV  ›  About"
            ),
            AdbGuideStep(
                number = 2,
                title = "Unlock Developer Options",
                description = "Highlight your Fire TV device name and press Select 7 times until Developer options appears.",
                badge = "Press Select 7×"
            ),
            AdbGuideStep(
                number = 3,
                title = "Enable ADB Debugging",
                description = "Go back to My Fire TV › Developer options and turn ADB Debugging ON.",
                showToggle = true
            ),
            AdbGuideStep(
                number = 4,
                title = "Approve TV Prompt",
                description = "Keep phone & Fire TV on the same Wi-Fi, then check 'Always allow' and press OK on TV.",
                finalStep = true
            )
        )
    ),
    XIAOMI_TV(
        "Xiaomi TV",
        listOf(
            AdbGuideStep(
                number = 1,
                title = "Open Xiaomi TV Settings",
                description = "Open Settings on your Xiaomi or Mi TV and go to Device Preferences.",
                pathChip = "Settings  ›  Device Preferences  ›  About"
            ),
            AdbGuideStep(
                number = 2,
                title = "Unlock Developer Options",
                description = "Highlight Build (or PatchWall / OS version) and press OK 7 times to unlock developer mode.",
                badge = "Press OK 7×"
            ),
            AdbGuideStep(
                number = 3,
                title = "Enable USB Debugging",
                description = "Open Developer options and enable USB debugging (plus ADB over network if shown).",
                showToggle = true
            ),
            AdbGuideStep(
                number = 4,
                title = "Approve TV Prompt",
                description = "Join the same Wi-Fi network, connect from this phone, and press 'Always allow' on your TV.",
                finalStep = true
            )
        )
    )
}

private class MiniOnToggleView(
    private val activity: BridgeActivity,
    private val trackColor: Int,
    private val strokeColor: Int
) : View(activity) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        paint.style = Paint.Style.FILL
        paint.color = trackColor
        canvas.drawRoundRect(rect, r, r, paint)

        val border = activity.px(1).toFloat()
        rect.inset(border / 2f, border / 2f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = border
        paint.color = strokeColor
        canvas.drawRoundRect(rect, r, r, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        val knobR = h * 0.36f
        canvas.drawCircle(w - r, h / 2f, knobR, paint)
    }
}

internal fun BridgeActivity.adbGuidePage() {
    var selectedBrand = TvGuideBrand.GOOGLE_TV

    content.addView(text("ADB Debugging Guide", 24, bold = true).apply {
        setPadding(0, px(2), 0, px(2))
    })
    content.addView(text("Enable debugging on your TV in 4 quick steps", 13, muted).apply {
        setPadding(0, 0, 0, px(14))
    })

    // Brand selector pills
    val pillStrip = row()
    val pillScroller = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(pillStrip)
    }
    content.addView(pillScroller, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        bottomMargin = px(16)
    })

    content.addView(sectionLabel("Step-by-step on your TV"),
        LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

    val stepsHost = column()
    content.addView(stepsHost, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        bottomMargin = px(14)
    })

    fun renderBrand() {
        pillStrip.removeAllViews()
        TvGuideBrand.entries.forEachIndexed { idx, brand ->
            val active = brand == selectedBrand
            val pillBg = if (active) {
                card(if (dark) 0xFF0284C7.toInt() else accentStrong, 20, outlined = false).apply {
                    if (dark) setStroke(px(1), 0xFF38BDF8.toInt())
                }
            } else {
                card(field, 20, outlined = true)
            }
            val chip = text(brand.label, 13, if (active) Color.WHITE else muted, bold = active).apply {
                background = RippleDrawable(ColorStateList.valueOf(ripple), pillBg, null)
                setPadding(px(14), px(9), px(14), px(9))
                isClickable = true
                isFocusable = true
                isSelected = active
                contentDescription = "${brand.label} guide"
                setOnClickListener {
                    if (selectedBrand != brand) {
                        selectedBrand = brand
                        renderBrand()
                    }
                }
            }
            pillStrip.addView(chip, LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply {
                if (idx > 0) marginStart = px(8)
            })
        }

        stepsHost.removeAllViews()
        selectedBrand.steps.forEachIndexed { idx, step ->
            val cardView = outlinedCard(field, radius = 18, stroke = 1).apply {
                setPadding(px(14), px(13), px(14), px(13))
            }
            val topRow = row().apply { gravity = Gravity.TOP }
            val badgeFill = if (step.finalStep) greenStroke else if (dark) 0xFF0284C7.toInt() else accentStrong
            val numBadge = text(step.number.toString(), 14, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(badgeFill, 16)
            }
            topRow.addView(numBadge, LinearLayout.LayoutParams(px(30), px(30)).apply { marginEnd = px(12) })

            val bodyCol = column()
            val titleRow = row()
            titleRow.addView(text(step.title, 15, bold = true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply {
                marginEnd = px(8)
            })
            if (step.badge != null) {
                titleRow.addView(text(step.badge, 11, if (dark) 0xFF7DD3FC.toInt() else accent, bold = true).apply {
                    background = card(tint, 8, outlined = false).apply {
                        setStroke(px(1), if (dark) 0xFF0284C7.toInt() else edge)
                    }
                    setPadding(px(8), px(4), px(8), px(4))
                })
            }
            if (step.showToggle) {
                titleRow.addView(
                    MiniOnToggleView(
                        this@adbGuidePage,
                        trackColor = if (dark) 0xFF0284C7.toInt() else accentStrong,
                        strokeColor = if (dark) 0xFF38BDF8.toInt() else accentStrong
                    ),
                    LinearLayout.LayoutParams(px(40), px(22))
                )
            }
            bodyCol.addView(titleRow)
            bodyCol.addView(text(step.description, 13, muted).apply {
                setPadding(0, px(5), 0, 0)
                setLineSpacing(px(3).toFloat(), 1f)
            })
            if (step.pathChip != null) {
                val chipFill = if (dark) 0xFF131D2B.toInt() else 0xFFF1F5F9.toInt()
                bodyCol.addView(text(step.pathChip, 12, if (dark) 0xFFE2E8F0.toInt() else ink).apply {
                    typeface = Typeface.MONOSPACE
                    background = card(chipFill, 10, outlined = true)
                    setPadding(px(10), px(6), px(10), px(6))
                }, LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply {
                    topMargin = px(8)
                })
            }
            topRow.addView(bodyCol, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
            cardView.addView(topRow)

            stepsHost.addView(cardView, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
                if (idx < selectedBrand.steps.lastIndex) bottomMargin = px(10)
            })
        }
    }

    renderBrand()

    content.addView(button("Find & Connect My TV", emphasis = true) {
        connectionPicker()
    }, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(52)).apply { bottomMargin = px(6) })

    content.addView(textButton("Having trouble? Enter TV IP manually") {
        connectDialog(pair = false)
    }.apply {
        textSize = 13f
        gravity = Gravity.CENTER
    }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
}
