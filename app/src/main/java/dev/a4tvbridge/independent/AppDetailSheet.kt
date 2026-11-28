package dev.a4tvbridge.independent

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView

internal data class AppSheetGuidance(
    val summary: String,
    val warning: String,
    val riskyUninstall: Boolean
)

internal fun describeAppForSheet(
    item: InstalledPackage,
    catalogNote: String? = null
): AppSheetGuidance {
    if (!item.system) {
        return AppSheetGuidance(
            summary = "Uninstalling is permanent. Get it again from Google Play or its original source.",
            warning = "You installed this app. Uninstalling removes it and its data. You can reinstall it from its original source.",
            riskyUninstall = true
        )
    }

    val lower = item.id.lowercase()
    val summary = when {
        !catalogNote.isNullOrBlank() -> catalogNote.trim().let { if (it.endsWith('.')) it else "$it." }
        "speech" in lower || "pumpkin" in lower || "katniss" in lower || "voice" in lower ->
            "The TV may use it for voice input."
        "bluetooth" in lower || "remote" in lower || "rcu" in lower ->
            "The TV may use it for remote controls or Bluetooth accessories."
        "inputmethod" in lower || "keyboard" in lower || "ime" in lower ->
            "The TV uses it for on-screen typing."
        "launcher" in lower || "tvhome" in lower || "leanback" in lower ->
            "Provides a home screen or launcher on this TV."
        "mediashell" in lower || "chromecast" in lower || "cast" in lower ->
            "Handles casting from phones and tablets to this TV."
        "settings" in lower ->
            "Manages system settings on this TV."
        "vending" in lower || "play" in lower ->
            "Manages Google Play Store apps and updates."
        else ->
            "Preinstalled on this TV. Turning it off hides it without deleting the system copy."
    }

    val cautious = isCoreProtectedPackage(item.id) ||
        listOf("speech", "pumpkin", "katniss", "voice", "remote", "bluetooth", "inputmethod", "keyboard", "launcher", "settings")
            .any { it in lower }

    val warning = if (cautious) {
        "Think first: this app may be used by your TV or remote. Turning it off is easy to undo."
    } else {
        "Preinstalled on this TV. Turning it off is easy to undo, and Restore can bring back apps removed for this TV."
    }

    return AppSheetGuidance(summary = summary, warning = warning, riskyUninstall = false)
}

internal fun BridgeActivity.packageOptions(item: InstalledPackage) {
    val id = packageId(item.id)
    val tv = connection ?: return
    val pinned = pinnedPackages(tv).contains(id)
    val title = displayName(id)
    val catalogNote = runCatching {
        tallyInstalledPackages(setOf(id), cleanupCatalog().profiles)[id]?.commonNote
    }.getOrNull()
    val guidance = describeAppForSheet(item, catalogNote)

    val popup = Dialog(this, dialogTheme)
    popup.requestWindowFeature(Window.FEATURE_NO_TITLE)

    val sheet = column().apply {
        val r = px(22).toFloat()
        background = GradientDrawable().apply {
            setColor(field)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            if (dark) setStroke(px(1), edge)
        }
        setPadding(px(20), px(14), px(20), px(20))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            view.setPadding(px(20), px(14), px(20), px(20) + nav.bottom)
            insets
        }
    }

    // Centered drag handle
    val handle = View(this).apply { background = card(dim, 5, false) }
    sheet.addView(handle, LinearLayout.LayoutParams(px(36), px(4)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        bottomMargin = px(14)
    })

    val body = column()

    // Title + package ID + plain-English description
    body.addView(text(title, 22, bold = true))
    body.addView(text(id, 12, if (dark) accent else muted, bold = true).apply {
        typeface = Typeface.MONOSPACE
        setPadding(0, px(3), 0, px(8))
    })
    body.addView(text(guidance.summary, 14, ink).apply {
        setLineSpacing(px(3).toFloat(), 1f)
        setPadding(0, 0, 0, px(12))
    })

    // Warm safety callout box
    val warningBg = if (dark) 0xFF241A0E.toInt() else 0xFFFFF1DF.toInt()
    val warningInk = if (dark) 0xFFFDBA74.toInt() else 0xFF92400E.toInt()
    val warningCard = text(guidance.warning, 13, warningInk).apply {
        background = card(warningBg, 14, outlined = false).apply {
            if (dark) setStroke(px(1), 0xFF78350F.toInt())
        }
        setLineSpacing(px(3).toFloat(), 1f)
        setPadding(px(14), px(12), px(14), px(12))
    }
    body.addView(warningCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        bottomMargin = px(14)
    })

    // Primary green Turn off / Turn on button
    val toggleLabel = if (item.enabled) "Turn off" else "Turn on"
    val toggleFill = if (dark) 0xFF15803D.toInt() else 0xFF166534.toInt()
    val toggleBtn = button(toggleLabel, description = "$toggleLabel $title") {
        popup.dismiss()
        confirm(if (item.enabled) "Turn off $title?" else "Turn on $title?", id) {
            tvTask({
                if (item.enabled) {
                    check(!protectForCleanup(id)) { "This app is needed by the TV." }
                    rememberDisabled(this, id)
                } else {
                    rememberEnabled(this, id)
                }
            }) {
                report(if (item.enabled) "$title turned off." else "$title turned on.")
                refresh()
            }
        }
    }.apply {
        setTextColor(Color.WHITE)
        textSize = 15f
        background = RippleDrawable(ColorStateList.valueOf(ripple), rounded(toggleFill, 14), null)
    }
    body.addView(toggleBtn, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(50)).apply {
        bottomMargin = px(10)
    })

    // Uninstall button (crimson red for user apps, outlined for built-in TV apps)
    val uninstallLabel = if (item.system) "Uninstall for this TV" else "Uninstall for good"
    val uninstallBtn = button(uninstallLabel, description = "$uninstallLabel $title") {
        popup.dismiss()
        val note = if (item.system) {
            "Removes the built-in app for the current Android user. Restore can reinstall it when Android still has the system copy."
        } else {
            "This removes the installed app and its data. You can reinstall it from its original source."
        }
        confirm("Uninstall $title?", note) {
            tvTask({
                check(!protectForCleanup(id)) { "This app is needed by the TV." }
                val result = command("pm uninstall --user 0 $id")
                if (result.status == 0 && result.text.lineSequence().any { it.trim() == "Success" } && item.system) {
                    recordSystemRemoval(this, id, removed = true)
                }
                result
            }) { result ->
                if (result.status == 0 && result.text.lineSequence().any { it.trim() == "Success" }) {
                    report("Uninstalled $title.")
                    refresh()
                } else {
                    report(result.text.ifBlank { "The TV refused to uninstall the app." })
                }
            }
        }
    }.apply {
        textSize = 15f
        if (guidance.riskyUninstall) {
            val dangerFill = if (dark) 0xFF991B1B.toInt() else 0xFFB02A37.toInt()
            setTextColor(Color.WHITE)
            background = RippleDrawable(
                ColorStateList.valueOf(ripple),
                card(dangerFill, 14, outlined = false).apply {
                    if (dark) setStroke(px(1), 0xFFDC2626.toInt())
                },
                null
            )
        } else {
            setTextColor(ink)
            background = RippleDrawable(
                ColorStateList.valueOf(ripple),
                card(if (dark) 0xFF131D2B.toInt() else field, 14, outlined = true),
                null
            )
        }
    }
    body.addView(uninstallBtn, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(50)).apply {
        bottomMargin = px(12)
    })

    // Expandable "More app options" / "Hide app options" section
    var expanded = false
    val moreCard = outlinedCard(if (dark) 0xFF111824.toInt() else field, radius = 16, stroke = 1).apply {
        setPadding(px(4), px(6), px(4), px(6))
        visibility = View.GONE
    }

    val dangerInk = if (dark) 0xFFF87171.toInt() else 0xFFC5221F.toInt()
    val extraActions = listOf<Triple<String, Boolean, () -> Unit>>(
        Triple("Open on TV", false) {
            popup.dismiss()
            tvTask({ openApp(id) }) { report("Opened $title on the TV.") }
        },
        Triple(if (pinned) "Remove Shortcut" else "Add Shortcut", false) {
            popup.dismiss()
            setPinned(tv, id, !pinned)
            report(if (pinned) "Shortcut removed." else "Shortcut added to Remote.")
        },
        Triple("Permissions", false) {
            popup.dismiss()
            permissionsForPackage(id)
        },
        Triple("Download", false) {
            popup.dismiss()
            exportApks(id)
        },
        Triple("Force stop", false) {
            popup.dismiss()
            tvTask({ command("am force-stop --user 0 $id") }) { commandStatus(it, "Stopped $title.") }
        },
        Triple("Clear data", true) {
            popup.dismiss()
            confirm("Clear app data?", "This resets $title’s settings and removes its stored data.") {
                tvTask({ command("pm clear --user 0 $id") }) { commandStatus(it, "App data cleared.") }
            }
        },
        Triple("Open app info settings", false) {
            popup.dismiss()
            tvTask({ command("am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$id") }) {
                commandStatus(it, "Opened on TV.")
            }
        },
        Triple("Open in Play Store on TV", false) {
            popup.dismiss()
            tvTask({ command("am start -a android.intent.action.VIEW -d 'market://details?id=$id'") }) {
                commandStatus(it, "Opened on TV.")
            }
        },
        Triple("Open in local Play Store", false) {
            popup.dismiss()
            openLink("https://play.google.com/store/apps/details?id=$id")
        }
    )

    for ((label, destructive, action) in extraActions) {
        val optionView = text(label, 15, if (destructive) dangerInk else accent, bold = true).apply {
            background = RippleDrawable(ColorStateList.valueOf(ripple), null, card(radius = 10))
            minimumHeight = px(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), px(10), px(14), px(10))
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { action() }
        }
        moreCard.addView(optionView, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
    }

    val gotItBtn = button("Got it", emphasis = true) { popup.dismiss() }.apply {
        textSize = 15f
    }

    val toggleMore = textButton("More app options") {}.apply {
        textSize = 15f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(px(2), px(6), px(8), px(6))
    }
    toggleMore.setOnClickListener {
        expanded = !expanded
        toggleMore.text = if (expanded) "Hide app options" else "More app options"
        moreCard.visibility = if (expanded) View.VISIBLE else View.GONE
        gotItBtn.visibility = if (expanded) View.GONE else View.VISIBLE
    }

    body.addView(toggleMore, LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply {
        bottomMargin = px(8)
    })
    body.addView(moreCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        bottomMargin = px(8)
    })
    body.addView(gotItBtn, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(50)).apply {
        topMargin = px(4)
    })

    val maxBodyHeight = px((resources.configuration.screenHeightDp * 0.78f).toInt().coerceAtLeast(320))
    val scroller = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        addView(body)
    }
    sheet.addView(scroller, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        // Wrap content naturally while keeping sheet comfortably bounded on smaller screens
    })

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

    // Clamp tall expanded sheets so they scroll cleanly without touching the status bar
    sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val params = scroller.layoutParams
        if (body.measuredHeight > maxBodyHeight && params.height != maxBodyHeight) {
            params.height = maxBodyHeight
            scroller.layoutParams = params
        } else if (body.measuredHeight <= maxBodyHeight && params.height != BridgeActivity.WRAP) {
            params.height = BridgeActivity.WRAP
            scroller.layoutParams = params
        }
    }
}
