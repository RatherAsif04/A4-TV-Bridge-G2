package dev.a4tvbridge.independent

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
    val status = text("Reading free space…", 13, bold = true).apply {
        background = card(if (dark) field else 0xFFECEFF3.toInt(), 24, false)
        setPadding(px(13), px(9), px(13), px(9))
    }
    val label = text("Save in", 13, muted, true).apply { setPadding(0, px(8), 0, px(12)) }
    content.addView(label)
    val buttons = SendTarget.entries.associateWith { target ->
        button(target.label) { chosen = target }.apply {
            textSize = 16f
            minimumHeight = px(35)
            minHeight = px(35)
            setPadding(px(8), 0, px(8), 0)
        }
    }
    fun style() {
        val selectedFill = if (dark) 0xFF163C33.toInt() else 0xFFE2F5EA.toInt()
        for ((target, widget) in buttons) {
            val selected = chosen == target
            widget.isSelected = selected
            widget.background = card(if (selected) selectedFill else surface, 9, !selected)
            widget.setTextColor(if (selected) green else muted)
        }
    }
    for (group in SendTarget.entries.chunked(3)) {
        val line = row()
        for ((index, target) in group.withIndex()) {
            val button = buttons.getValue(target)
            button.setOnClickListener { chosen = target; style() }
            line.addView(button, LinearLayout.LayoutParams(0, px(36), 1f).apply { if (index > 0) marginStart = px(8) })
        }
        content.addView(line, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(17) })
    }
    style()
    content.addView(status, LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply { topMargin = px(4); bottomMargin = px(14) })
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
                        val row = outlinedCard()
                        row.addView(text(name, 16, bold = true))
                        row.addView(text(formatBytes(bytes), 16, muted).apply { setPadding(0, px(6), 0, 0) })
                        row.addView(text("✓  Saved as $name", 14, green).apply { setPadding(0, px(10), 0, 0) })
                        history.addView(row, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })
                    }
                    val banner = outlinedCard(if (dark) 0xFF163C33.toInt() else 0xFFE2F5EA.toInt(), 16).apply {
                        background = card(if (dark) 0xFF163C33.toInt() else 0xFFE2F5EA.toInt(), 16).apply { setStroke(px(2), green) }
                        addView(text("✓  Sent. On the TV, open ${target.label} › A4TVBridge.", 16, green, true))
                    }
                    history.addView(banner)
                }
            }
        }
    }
    watch {
        val sample = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tv.details() }
        status.text = "${formatBytes(sample.diskFree)} free on the TV"
    }
}
