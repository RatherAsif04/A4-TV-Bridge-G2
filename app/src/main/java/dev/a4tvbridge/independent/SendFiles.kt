package dev.a4tvbridge.independent

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.LinearLayout

private enum class SendTarget(val label: String, val folder: String) {
    DOWNLOADS("Downloads", "Download"), MOVIES("Movies", "Movies"), MUSIC("Music", "Music"),
    PICTURES("Pictures", "Pictures"), DOCUMENTS("Documents", "Documents")
}

private data class SentItem(val name: String, val bytes: Long)

internal fun BridgeActivity.sendPage() {
    val tv = connection ?: return
    val owner = content
    var chosen = SendTarget.DOWNLOADS

    content.addView(text("Send Files", 24, bold = true).apply { setPadding(0, px(2), 0, px(2)) })
    content.addView(text("Push media, APKs & documents straight to your TV", 12, muted).apply {
        setPadding(0, 0, 0, px(12))
    })

    // Live TV free storage pill matching Connected / Wi-Fi Ready style
    val statusText = text("Reading TV storage…", 12, if (dark) 0xFF6EE7B7.toInt() else green, bold = true)
    val statusPill = row().apply {
        background = card(greenBg, 20, outlined = false).apply { setStroke(px(1), greenStroke) }
        setPadding(px(12), px(7), px(13), px(7))
        addView(View(this@sendPage).apply { background = rounded(greenStroke, 8) },
            LinearLayout.LayoutParams(px(7), px(7)).apply { marginEnd = px(7) })
        addView(statusText)
    }
    content.addView(statusPill, LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply { bottomMargin = px(16) })

    // Destination folder selector card
    val targetCard = outlinedCard(field, radius = 20, stroke = 1)
    targetCard.addView(text("SAVE DESTINATION ON TV", 11, dim, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(0, 0, 0, px(10))
    })
    val buttons = SendTarget.entries.associateWith { target ->
        button(target.label) { chosen = target }.apply {
            textSize = 14f
            minimumHeight = px(44)
            minHeight = px(44)
            setPadding(px(8), 0, px(8), 0)
        }
    }
    fun style() {
        for ((target, widget) in buttons) {
            val selected = chosen == target
            widget.isSelected = selected
            val bg = if (selected) {
                card(accentPill, 12, outlined = false).apply {
                    if (dark) setStroke(px(1), accent)
                }
            } else {
                card(if (dark) 0xFF131D2B.toInt() else surface, 12, outlined = true)
            }
            widget.background = RippleDrawable(ColorStateList.valueOf(ripple), bg, null)
            widget.setTextColor(if (selected) Color.WHITE else ink)
        }
    }
    val groups = SendTarget.entries.chunked(3)
    groups.forEachIndexed { gIdx, group ->
        val line = row()
        for ((index, target) in group.withIndex()) {
            val button = buttons.getValue(target)
            button.setOnClickListener { chosen = target; style() }
            line.addView(button, LinearLayout.LayoutParams(0, px(44), 1f).apply { if (index > 0) marginStart = px(8) })
        }
        repeat(3 - group.size) {
            line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = px(8) })
        }
        targetCard.addView(line, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
            if (gIdx < groups.lastIndex) bottomMargin = px(8)
        })
    }
    style()
    content.addView(targetCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(16) })

    val history = column()
    content.addView(history)
    floatingAction("Choose Files", Mark.UPLOAD) {
        val target = chosen
        filePickerSheet("Choose Files", "Save in ${target.label} › A4TVBridge.") {
            chooseDocuments("*/*", true) { selected ->
                tvTask({
                    val folder = transferDestination(target.folder)
                    selected.map { uri ->
                        val (name, source) = stage(uri)
                        try {
                            upload(folder, name, source)
                            SentItem(name, source.length())
                        } finally { source.delete() }
                    }
                }) { entries ->
                    if (owner !== content) return@tvTask
                    for ((name, bytes) in entries) {
                        val rowCard = outlinedCard(field, radius = 16, stroke = 1)
                        rowCard.addView(text(name, 15, bold = true))
                        rowCard.addView(text(formatBytes(bytes), 12, muted).apply { setPadding(0, px(4), 0, 0) })
                        rowCard.addView(text("✓  Saved to ${target.label} › A4TVBridge", 13, green, bold = true).apply { setPadding(0, px(6), 0, 0) })
                        history.addView(rowCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
                    }
                    val banner = outlinedCard(greenBg, 16, stroke = 1).apply {
                        background = card(greenBg, 16, outlined = false).apply { setStroke(px(1), greenStroke) }
                        addView(text("✓  Sent. On the TV, open ${target.label} › A4TVBridge.", 14, if (dark) 0xFF6EE7B7.toInt() else green, true))
                    }
                    history.addView(banner, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
                }
            }
        }
    }
    watch {
        val sample = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tv.details() }
        statusText.text = "${formatBytes(sample.diskFree)} free on TV"
    }
}
