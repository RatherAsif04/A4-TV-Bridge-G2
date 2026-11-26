package dev.a4tvbridge.independent

import android.content.res.ColorStateList
import android.os.SystemClock
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun BridgeActivity.dashboard() {
    val tv = connection
    if (tv == null) {
        val welcome = row().apply {
            background = if (dark) card(field, 22, outlined = true) else card(0xFF0B1327.toInt(), 22, false)
            minimumHeight = px(143)
            setPadding(px(16), px(20), px(16), px(20))
        }
        val words = column().apply {
            addView(text("A4 TV Bridge", 24, if (dark) ink else android.graphics.Color.WHITE, true))
            addView(text("Connect to your TV to use the remote, manage apps, or send files.", 14, if (dark) muted else 0xFFB4C4D6.toInt()).apply {
                setPadding(0, px(8), 0, 0)
                setLineSpacing(px(4).toFloat(), 1f)
            })
        }
        welcome.addView(words, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        welcome.addView(ImageView(this).apply { setImageResource(R.drawable.a4_mark) },
            LinearLayout.LayoutParams(px(92), px(92)).apply { marginStart = px(6) })
        content.addView(welcome, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })
        content.addView(connectButton(), LinearLayout.LayoutParams(BridgeActivity.MATCH, px(54)))
        content.addView(textButton("Other TV types") {
            choose("Other TV types", listOf(
                "Android TV / Google TV" to { connectionPicker() },
                "Fire TV with ADB debugging" to { connectDialog(pair = false) }
            ))
        }, LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply { topMargin = px(13) })
        return
    }

    val activity = column().apply {
        background = card(radius = 22).apply { setStroke(px(2), edge) }
        setPadding(px(16), px(16), px(16), px(16))
    }
    val overview = row()
    val identification = column()
    val name = tvTitle() ?: tv.model
    identification.addView(text(name, 24, bold = true))
    identification.addView(text(if (name != tv.model) tv.model else tv.address.toString(), 14, muted).apply {
        setPadding(0, px(4), 0, 0)
    })
    val status = text("Connected TV", 12, green, true).apply { setPadding(0, px(4), 0, 0) }
    identification.addView(status)
    overview.addView(identification, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    overview.addView(textButton("Change TV") {
        choose("Change TV", listOf("Choose another TV" to { connectionPicker() }, "Disconnect" to { disconnect() }))
    })
    activity.addView(overview)
    val divider = android.view.View(this).apply { setBackgroundColor(edge) }
    activity.addView(divider, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(1)).apply { topMargin = px(12); bottomMargin = px(5) })
    val caption = row()
    caption.addView(text("Device activity", 16, bold = true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    caption.addView(textButton("Device Info") { show(Panel.INFO) })
    activity.addView(caption)
    val storageHeading = row()
    val storage = text("—", 13, bold = true)
    storageHeading.addView(text("Storage used", 13, muted, true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    storageHeading.addView(storage)
    activity.addView(storageHeading, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(6) })
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        val track = card(edge, 6, outlined = false)
        val fill = android.graphics.drawable.ClipDrawable(
            card(accent, 6, outlined = false),
            android.view.Gravity.START,
            android.graphics.drawable.ClipDrawable.HORIZONTAL
        )
        progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        max = 100
    }
    activity.addView(bar, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(8)).apply {
        topMargin = px(6)
        bottomMargin = px(8)
    })
    val monitors = row()
    fun metric(label: String, isLast: Boolean = false): TextView {
        val stack = column().apply {
            background = card(surface, 11, outlined = true)
            setPadding(px(9), px(8), px(8), px(8))
        }
        stack.addView(text(label, 12, muted))
        val value = text("—", 15, bold = true).apply {
            setPadding(0, px(3), 0, 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        stack.addView(value)
        monitors.addView(stack, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply {
            if (!isLast) marginEnd = px(6)
        })
        return value
    }
    val cpu = metric("CPU")
    val ram = metric("RAM")
    val network = metric("Network", isLast = true)
    activity.addView(monitors, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(4) })
    content.addView(activity, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
    heading("Quick actions")
    tiles(content, 2,
        Tile("Remote", Mark.REMOTE, "Remote control", true) { show(Panel.REMOTE) },
        Tile("Clean Up TV", Mark.CLEAN, "Review installed apps") { show(Panel.CLEAN) },
        Tile("Install Apps", Mark.DOWNLOAD, "Install an APK") { show(Panel.INSTALL) },
        Tile("Send files", Mark.UPLOAD, "Phone to TV") { show(Panel.SEND) },
        Tile("ADB Shell / Terminal", Mark.SHELL, "Run TV commands") { show(Panel.SHELL) },
        Tile("Block Ads", Mark.SHIELD, "Private DNS") { showDnsOptions() },
        gap = 14
    )
    var previous: DeviceReading? = null
    var previousAt = 0L
    watch {
        val snapshot = withContext(Dispatchers.IO) { tv.details() }
        val now = SystemClock.elapsedRealtime()
        val total = snapshot.diskTotal
        val free = snapshot.diskFree
        storage.text = if (total != null && free != null) "${formatBytes(total - free)} / ${formatBytes(total)}" else "—"
        bar.progress = snapshot.diskUsage ?: 0
        cpu.text = percent(snapshot.cpuUsage(previous))
        ram.text = percent(snapshot.ramUsage)
        val old = previous
        if (old != null && now > previousAt) {
            val incoming = snapshot.received
            val outgoing = snapshot.transmitted
            val previousRx = old.received
            val previousTx = old.transmitted
            if (incoming != null && outgoing != null && previousRx != null && previousTx != null &&
                incoming >= previousRx && outgoing >= previousTx) {
                network.text = "${formatBytes((incoming - previousRx + outgoing - previousTx) * 1000 / (now - previousAt))}/s"
            }
        }
        previous = snapshot
        previousAt = now
    }
}

internal fun BridgeActivity.library() {
    heading("Apps and files")
    tiles(content, 1,
        Tile("Apps", Mark.APPS) { show(Panel.APPS) },
        Tile("Install Apps", Mark.DOWNLOAD) { show(Panel.INSTALL) },
        Tile("File Manager", Mark.FOLDER) { show(Panel.FILES) },
        Tile("Send files", Mark.UPLOAD) { show(Panel.SEND) }
    )
}

private fun BridgeActivity.connectButton() = row().apply {
    gravity = android.view.Gravity.CENTER
    background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(ripple), card(accent, corner, false), null)
    addView(Glyph(this@connectButton, Mark.TV, onAccent), LinearLayout.LayoutParams(px(23), px(23)))
    addView(text("Connect your TV", 17, onAccent, true), LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply {
        marginStart = px(10)
    })
    contentDescription = "Connect your TV"
    isFocusable = true
    setOnClickListener { connectionPicker() }
}
