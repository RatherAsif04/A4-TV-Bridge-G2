package dev.a4tvbridge.independent

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.widget.ImageView
import android.widget.LinearLayout

internal fun BridgeActivity.openAbout() {
    showSheet("About A4 TV Bridge", closeIcon = false) { popup, frame ->
        val body = column()
        val identity = row().apply {
            background = card(surfaceElevated, 16, outlined = true)
            setPadding(px(14), px(12), px(14), px(12))
        }
        identity.addView(ImageView(this).apply {
            setImageResource(R.drawable.a4_mark)
            contentDescription = "A4 TV Bridge logo"
        }, LinearLayout.LayoutParams(px(44), px(44)))
        val idCol = column().apply {
            addView(text("A4 TV Bridge", 17, bold = true))
            addView(text("v${BuildConfig.VERSION_NAME}", 12, muted).apply {
                typeface = monoTypeface
                setPadding(0, px(2), 0, 0)
            })
        }
        identity.addView(idCol, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(12) })
        body.addView(identity, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

        body.addView(text("Control an Android TV, Google TV, or compatible Fire TV over your local network. Use the remote, mirror the screen, install apps, transfer files, and monitor device activity.", 14, muted).apply {
            setLineSpacing(px(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })

        val details = outlinedCard(surfaceSubtle, radius = 18, stroke = 1).apply {
            setPadding(px(14), px(12), px(14), px(8))
        }
        details.addView(text("DEVELOPER & PROJECT", 10, dim, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(px(2), 0, 0, px(6))
        })
        details.addView(aboutLink("RatherAsif04 on GitHub") { openLink("https://github.com/RatherAsif04") })
        details.addView(aboutLink("@ratherasif04 on X") { openLink("https://x.com/ratherasif04") })
        details.addView(rule(), LinearLayout.LayoutParams(BridgeActivity.MATCH, px(1)).apply { topMargin = px(4); bottomMargin = px(4) })
        details.addView(aboutLink("Project on GitHub") { openLink(releaseRoot) })
        val pending = availableUpdate
        val updateLabel = if (pending != null && isNewerVersion(pending.version, BuildConfig.VERSION_NAME)) {
            "Install update (${pending.tagName})"
        } else {
            "Check for updates"
        }
        details.addView(aboutLink(updateLabel, external = false) {
            popup.dismiss()
            if (pending != null && isNewerVersion(pending.version, BuildConfig.VERSION_NAME)) {
                showUpdateSheet(pending)
            } else {
                checkForUpdatesManually()
            }
        })
        details.addView(aboutLink("Releases and downloads") { openLink("$releaseRoot/releases") })
        details.addView(aboutLink("Report an issue") { openLink("$releaseRoot/issues") })
        details.addView(aboutLink("App source license", external = false) {
            popup.dismiss()
            openLicenseDocument("a4-tv-bridge.txt", "App source license", "Back to About") { openAbout() }
        })
        body.addView(details, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

        val noticesCard = outlinedCard(surfaceSubtle, radius = 16, stroke = 1).apply {
            setPadding(px(14), px(4), px(14), px(4))
            addView(aboutLink("Open-source notices", external = false) {
                popup.dismiss()
                openNotices()
            })
        }
        body.addView(noticesCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(6) })

        attachSheetBody(frame, body)
        frame.addView(button("Close", emphasis = true) { popup.dismiss() }, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(50)).apply {
            topMargin = px(8)
        })
    }
}

private val noticeTitles = linkedMapOf(
    "a4-tv-bridge.txt" to "App source license",
    "device-profiles.txt" to "Device profile license",
    "adb-client.txt" to "ADB client license",
    "wireless-pairing.txt" to "Wireless pairing license",
    "tls-provider.txt" to "TLS provider license",
    "androidx.txt" to "AndroidX license",
    "kotlin.txt" to "Kotlin license",
    "concurrency.txt" to "Kotlin coroutines license",
    "okio.txt" to "Okio license",
    "bouncy-castle.md" to "Bouncy Castle license"
)

private fun BridgeActivity.openLicenseDocument(
    filename: String,
    title: String,
    backLabel: String,
    onBack: () -> Unit
) {
    showSheet(title, closeIcon = false) { dialog, area ->
        val license = assets.open("licenses/$filename").bufferedReader().use { it.readText() }
        val source = column().apply {
            addView(text(license, 13, muted).apply {
                setLineSpacing(px(2).toFloat(), 1f)
                setTextIsSelectable(true)
            })
        }
        attachSheetBody(area, source)
        area.addView(button(backLabel, emphasis = true) { dialog.dismiss(); onBack() },
            LinearLayout.LayoutParams(BridgeActivity.MATCH, px(50)).apply { topMargin = px(8) })
    }
}

private fun BridgeActivity.openNotices() {
    showSheet("Open-source notices", closeIcon = false) { popup, frame ->
        val body = column()
        body.addView(text("Licenses for the app, device profiles, and bundled libraries.", 14, muted).apply {
            setLineSpacing(px(3).toFloat(), 1f)
            setPadding(0, 0, 0, px(10))
        })
        val files = assets.list("licenses").orEmpty()
        val listCard = outlinedCard(surfaceSubtle, radius = 16, stroke = 1).apply {
            setPadding(px(14), px(6), px(14), px(6))
        }
        val shown = noticeTitles.entries.filter { it.key in files }
        shown.forEachIndexed { idx, (filename, title) ->
            listCard.addView(aboutLink(title, external = false) {
                popup.dismiss()
                openLicenseDocument(filename, title, "Back to notices") { openNotices() }
            })
            if (idx < shown.lastIndex) listCard.addView(rule())
        }
        body.addView(listCard)
        attachSheetBody(frame, body)
        frame.addView(button("Back to About", emphasis = true) { popup.dismiss(); openAbout() },
            LinearLayout.LayoutParams(BridgeActivity.MATCH, px(50)).apply { topMargin = px(8) })
    }
}

private fun BridgeActivity.aboutLink(label: String, external: Boolean = true, select: () -> Unit) = row().apply {
    background = RippleDrawable(ColorStateList.valueOf(ripple), null, card(radius = 10))
    minimumHeight = px(48)
    setPadding(px(4), px(8), px(4), px(8))
    addView(text(label, 15, accent, bold = true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    val arrow = if (external) Glyph(this@aboutLink, Mark.EXTERNAL, muted) else text("›", 21, muted, bold = true)
    addView(arrow, LinearLayout.LayoutParams(px(19), px(22)))
    contentDescription = label
    isClickable = true
    isFocusable = true
    setOnClickListener { select() }
}
