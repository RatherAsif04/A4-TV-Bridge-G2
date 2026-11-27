package dev.a4tvbridge.independent

import android.widget.ImageView
import android.widget.LinearLayout

internal fun BridgeActivity.openAbout() {
    showSheet("About A4 TV Bridge", closeIcon = false) { popup, frame ->
        val body = column()
        val identity = row()
        identity.addView(ImageView(this).apply { setImageResource(R.drawable.a4_mark) }, LinearLayout.LayoutParams(px(48), px(48)))
        identity.addView(text("Version ${BuildConfig.VERSION_NAME}", 16, bold = true),
            LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply { marginStart = px(12) })
        body.addView(identity)
        body.addView(text("Control an Android TV, Google TV, or compatible Fire TV over your local network. Use the remote, mirror the screen, install apps, transfer files, and monitor device activity.", 16, muted).apply {
            setLineSpacing(px(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(13); bottomMargin = px(13) })
        val details = outlinedCard()
        details.addView(text("Developer", 16, bold = true))
        details.addView(aboutLink("RatherAsif04 on GitHub") { openLink("https://github.com/RatherAsif04") })
        details.addView(aboutLink("@ratherasif04 on X") { openLink("https://x.com/ratherasif04") })
        details.addView(rule())
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
        body.addView(details)
        body.addView(aboutLink("Open-source notices", external = false) {
            popup.dismiss()
            openNotices()
        }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(10) })
        attachSheetBody(frame, body)
        frame.addView(button("Close", emphasis = true) { popup.dismiss() }, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(54)))
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

private fun BridgeActivity.openNotices() {
    showSheet("Open-source notices", closeIcon = false) { popup, frame ->
        val body = column()
        body.addView(text("Licenses for the app, device profiles, and bundled libraries.", 16, muted).apply { setLineSpacing(px(3).toFloat(), 1f) })
        val files = assets.list("licenses").orEmpty()
        for ((filename, title) in noticeTitles) {
            if (filename !in files) continue
            body.addView(aboutLink(title, external = false) {
                popup.dismiss()
                showSheet(title, closeIcon = false) { dialog, area ->
                    val license = assets.open("licenses/$filename").bufferedReader().use { it.readText() }
                    val source = column().apply { addView(text(license, 14, muted).apply { setTextIsSelectable(true) }) }
                    attachSheetBody(area, source)
                    area.addView(button("Back to notices", emphasis = true) { dialog.dismiss(); openNotices() },
                        LinearLayout.LayoutParams(BridgeActivity.MATCH, px(54)))
                }
            })
        }
        attachSheetBody(frame, body)
        frame.addView(button("Back to About", emphasis = true) { popup.dismiss(); openAbout() },
            LinearLayout.LayoutParams(BridgeActivity.MATCH, px(54)))
    }
}

private fun BridgeActivity.aboutLink(label: String, external: Boolean = true, select: () -> Unit) = row().apply {
    minimumHeight = px(46)
    setPadding(px(4), px(7), px(4), px(7))
    addView(text(label, 16, accent), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    val arrow = if (external) Glyph(this@aboutLink, Mark.EXTERNAL, muted) else text("›", 23, muted)
    addView(arrow, LinearLayout.LayoutParams(px(19), px(22)))
    contentDescription = label
    isFocusable = true
    setOnClickListener { select() }
}
