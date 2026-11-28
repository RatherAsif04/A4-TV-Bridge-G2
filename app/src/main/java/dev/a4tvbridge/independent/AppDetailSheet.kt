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
    val riskyUninstall: Boolean,
    val cautious: Boolean = false
)

internal fun describeAppForSheet(
    item: InstalledPackage,
    catalogNote: String? = null
): AppSheetGuidance {
    if (!item.system) {
        return AppSheetGuidance(
            summary = "Uninstalling is permanent. Get it again from Google Play or its original source.",
            warning = "You installed this app. Uninstalling removes it and its data. You can reinstall it from its original source.",
            riskyUninstall = true,
            cautious = true
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

    return AppSheetGuidance(summary = summary, warning = warning, riskyUninstall = false, cautious = cautious)
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

    val handle = View(this).apply { background = card(dim, 5, false) }
    sheet.addView(handle, LinearLayout.LayoutParams(px(36), px(4)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        bottomMargin = px(14)
    })

    val body = column()

    // 1. A4 TV Bridge App Identity Header (monogram badge + name + monospace package + status pill)
    val headerRow = row()
    headerRow.addView(appBadge(id), LinearLayout.LayoutParams(px(42), px(42)).apply { marginEnd = px(12) })
    val titleCol = column().apply {
        addView(text(title, 20, bold = true).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(text(id, 11, if (dark) accent else muted).apply {
            typeface = monoTypeface
            setPadding(0, px(2), 0, 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    }
    headerRow.addView(titleCol, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginEnd = px(8) })

    val pillLabel = when {
        !item.enabled -> "Off"
        item.system -> "Built-in"
        else -> "Installed"
    }
    val pillFill = when {
        !item.enabled -> warningBg
        item.system -> tint
        else -> greenBg
    }
    val pillStroke = when {
        !item.enabled -> orange
        item.system -> if (dark) accent else edge
        else -> greenStroke
    }
    val pillInk = when {
        !item.enabled -> orange
        item.system -> accent
        else -> successInk
    }
    headerRow.addView(text(pillLabel, 11, pillInk, bold = true).apply {
        background = card(pillFill, 12, outlined = false).apply { setStroke(px(1), pillStroke) }
        setPadding(px(10), px(5), px(10), px(5))
    })
    body.addView(headerRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        bottomMargin = px(10)
    })

    // Plain-English summary
    body.addView(text(guidance.summary, 14, muted).apply {
        setLineSpacing(px(3).toFloat(), 1f)
        setPadding(0, 0, 0, px(12))
    })

    // 2. A4-Styled Safety Notice Card (matches TV Scanner status card language)
    val noticeBorder = if (guidance.cautious) orange else accent
    val noticeFill = if (guidance.cautious) warningBg else tint
    val noticeLabel = when {
        guidance.riskyUninstall -> "USER INSTALLED APP"
        guidance.cautious -> "THINK FIRST"
        else -> "EASY TO UNDO"
    }
    val noticeCard = column().apply {
        background = card(noticeFill, 16, outlined = false).apply { setStroke(px(1), noticeBorder) }
        setPadding(px(14), px(11), px(14), px(12))
        val tagRow = row()
        tagRow.addView(View(this@packageOptions).apply { background = rounded(noticeBorder, 8) },
            LinearLayout.LayoutParams(px(7), px(7)).apply { marginEnd = px(6) })
        tagRow.addView(text(noticeLabel, 10, noticeBorder, bold = true).apply { letterSpacing = 0.08f })
        addView(tagRow)
        addView(text(guidance.warning, 13, ink).apply {
            setLineSpacing(px(3).toFloat(), 1f)
            setPadding(0, px(5), 0, 0)
        })
    }
    body.addView(noticeCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        bottomMargin = px(14)
    })

    // 3. Primary A4 Action Button (Turn off / Turn on)
    val toggleLabel = if (item.enabled) "Turn off" else "Turn on"
    val toggleBtn = button(toggleLabel, description = "$toggleLabel $title", emphasis = true) {
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
        if (!item.enabled) {
            background = RippleDrawable(
                ColorStateList.valueOf(ripple),
                card(if (dark) 0xFF059669.toInt() else green, 15, outlined = false).apply {
                    if (dark) setStroke(px(1), greenStroke)
                },
                null
            )
        }
    }
    body.addView(toggleBtn, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(50)).apply {
        bottomMargin = px(9)
    })

    // 4. Uninstall Button (A4 rose tinted destructive for user apps, native outlined for built-in apps)
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
        if (guidance.riskyUninstall) {
            setTextColor(dangerInk)
            background = interactiveBackground(
                card(dangerBg, 15, outlined = false).apply { setStroke(px(1), rose) },
                radius = 15
            )
        }
    }
    body.addView(uninstallBtn, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(50)).apply {
        bottomMargin = px(12)
    })

    // 5. Collapsible "More app options" in A4's grouped hairline MORE TOOLS style
    var expanded = false
    val moreCard = outlinedCard(surfaceSubtle, radius = 18, stroke = 1).apply {
        setPadding(0, px(4), 0, px(4))
        visibility = View.GONE
    }

    val destructiveInk = dangerInk
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
        Triple("Export APK", false) {
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

    extraActions.forEachIndexed { idx, (label, destructive, action) ->
        val itemRow = row().apply {
            background = RippleDrawable(ColorStateList.valueOf(ripple), null, card(radius = 12))
            minimumHeight = px(48)
            setPadding(px(16), px(11), px(14), px(11))
            isClickable = true
            isFocusable = true
            contentDescription = label
            addView(text(label, 14, if (destructive) destructiveInk else ink, bold = true),
                LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
            addView(text("›", 19, if (destructive) destructiveInk else dim, bold = true))
            setOnClickListener { action() }
        }
        moreCard.addView(itemRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
        if (idx < extraActions.lastIndex) {
            moreCard.addView(rule(), LinearLayout.LayoutParams(BridgeActivity.MATCH, px(1)).apply {
                marginStart = px(16)
                marginEnd = px(16)
            })
        }
    }

    val toggleText = text("More app options", 14, accent, bold = true)
    val toggleChevron = text("▾", 16, accent, bold = true)
    val toggleMore = row().apply {
        background = RippleDrawable(ColorStateList.valueOf(ripple), null, card(radius = 12))
        minimumHeight = px(48)
        setPadding(px(4), px(6), px(4), px(6))
        isClickable = true
        isFocusable = true
        contentDescription = "More app options"
        addView(toggleText, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        addView(toggleChevron)
    }

    val gotItBtn = button("Done") { popup.dismiss() }

    toggleMore.setOnClickListener {
        expanded = !expanded
        toggleText.text = if (expanded) "Hide app options" else "More app options"
        toggleChevron.text = if (expanded) "▴" else "▾"
        toggleMore.contentDescription = toggleText.text
        moreCard.visibility = if (expanded) View.VISIBLE else View.GONE
        gotItBtn.visibility = if (expanded) View.GONE else View.VISIBLE
    }

    body.addView(toggleMore, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        bottomMargin = px(6)
    })
    body.addView(moreCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        bottomMargin = px(8)
    })
    body.addView(gotItBtn, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(48)).apply {
        topMargin = px(4)
    })

    val maxBodyHeight = px((resources.configuration.screenHeightDp * 0.78f).toInt().coerceAtLeast(320))
    val scroller = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        addView(body)
    }
    sheet.addView(scroller, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))

    popup.setContentView(sheet)
    popup.show()
    popup.window?.run {
        setBackgroundDrawableResource(android.R.color.transparent)
        setDimAmount(0.55f)
        addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (android.os.Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            navigationBarColor = field
        }
        androidx.core.view.WindowCompat.getInsetsController(this, decorView).isAppearanceLightNavigationBars = !dark
        setGravity(Gravity.BOTTOM)
        val sheetWidth = if (resources.configuration.screenWidthDp >= 640) px(600) else BridgeActivity.MATCH
        setLayout(sheetWidth, BridgeActivity.WRAP)
    }

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
