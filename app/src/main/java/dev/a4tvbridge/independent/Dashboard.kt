package dev.a4tvbridge.independent

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class QuickCardSpec(
    val title: String,
    val subtitle: String,
    val status: String,
    val icon: Mark,
    val accentColor: Int,
    val badgeFill: Int,
    val action: () -> Unit
)

internal fun BridgeActivity.dashboard() {
    val tv = connection
    if (tv == null) {
        val welcome = row().apply {
            background = if (dark) card(field, 22, outlined = true) else card(0xFF0B1327.toInt(), 22, false)
            minimumHeight = px(143)
            setPadding(px(16), px(20), px(16), px(20))
        }
        val words = column().apply {
            addView(text("A4 TV Bridge", 24, if (dark) ink else Color.WHITE, true))
            addView(text("Connect to your TV to use the remote, clean bloatware, manage apps, or send files.", 14, if (dark) muted else 0xFFB4C4D6.toInt()).apply {
                setPadding(0, px(8), 0, 0)
                setLineSpacing(px(4).toFloat(), 1f)
            })
        }
        welcome.addView(words, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        welcome.addView(ImageView(this).apply { setImageResource(R.drawable.a4_mark) },
            LinearLayout.LayoutParams(px(88), px(88)).apply { marginStart = px(8) })
        content.addView(welcome, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })
        content.addView(connectButton(), LinearLayout.LayoutParams(BridgeActivity.MATCH, px(54)).apply { bottomMargin = px(12) })
        tiles(content, 1,
            Tile("ADB Debugging Guide", Mark.INFO, "4-step setup for Google TV, Android TV, Fire TV & Xiaomi") {
                show(Panel.ADB_GUIDE)
            }
        )
        content.addView(textButton("Other TV types") {
            choose("Other TV types", listOf(
                "Android TV / Google TV" to { connectionPicker() },
                "Fire TV with ADB debugging" to { connectDialog(pair = false) }
            ))
        }, LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply { topMargin = px(6) })
        return
    }

    // 1. Connected TV Hero Card (matches 01-dashboard-dark.png)
    val tvCard = outlinedCard(field, radius = 20, stroke = 1)
    val topRow = row().apply { gravity = Gravity.TOP }
    val idCol = column()
    val name = tvTitle() ?: tv.model
    val modelLine = buildString {
        append(tv.model)
        if (tv.identity.device.isNotBlank() && !tv.model.contains(tv.identity.device, ignoreCase = true)) {
            append(" (${tv.identity.device})")
        }
        if (tv.androidVersion.isNotBlank()) {
            append(" · Android TV ${tv.androidVersion}")
        }
    }
    idCol.addView(text(name, 20, bold = true))
    idCol.addView(text(modelLine, 12, muted).apply { setPadding(0, px(4), 0, 0) })
    idCol.addView(text("${tv.address} · ADB", 12, accent).apply {
        typeface = Typeface.MONOSPACE
        setPadding(0, px(3), 0, 0)
    })
    topRow.addView(idCol, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginEnd = px(8) })

    val switchBtn = text("Switch TV", 12, if (dark) 0xFFB0DEFF.toInt() else accent, bold = true).apply {
        background = RippleDrawable(
            ColorStateList.valueOf(ripple),
            card(if (dark) 0xFF152233.toInt() else tint, 10, outlined = false).apply {
                setStroke(px(1), if (dark) 0xFF2A3F5A.toInt() else edge)
            },
            null
        )
        setPadding(px(12), px(7), px(12), px(7))
        isClickable = true
        isFocusable = true
        contentDescription = "Switch TV"
        setOnClickListener {
            choose("Switch TV", listOf("Choose another TV" to { connectionPicker() }, "Disconnect" to { disconnect() }))
        }
    }
    topRow.addView(switchBtn, LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP))
    tvCard.addView(topRow)

    val storageRow = row()
    val storageText = text("—", 12, if (dark) 0xFFCBD5E1.toInt() else ink).apply {
        typeface = Typeface.MONOSPACE
    }
    storageRow.addView(text("TV Storage", 12, muted, true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    storageRow.addView(storageText)
    tvCard.addView(storageRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(14) })

    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        val track = card(edge, 6, outlined = false)
        val fill = ClipDrawable(
            card(0xFF0EA5E9.toInt(), 6, outlined = false),
            Gravity.START,
            ClipDrawable.HORIZONTAL
        )
        progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        max = 100
    }
    tvCard.addView(bar, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(9)).apply { topMargin = px(7) })
    content.addView(tvCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(16) })

    // 2. LIVE TV METRICS (3 accent-dotted cards)
    content.addView(sectionLabel("Live TV metrics", "Device Info") { show(Panel.INFO) },
        LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })

    val monitors = row()
    fun metricCard(label: String, dotColor: Int, initialSub: String, isLast: Boolean = false): Pair<TextView, TextView> {
        val box = outlinedCard(field, radius = 16, stroke = 1).apply {
            setPadding(px(12), px(11), px(10), px(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { show(Panel.INFO) }
        }
        val titleRow = row()
        val dot = View(this).apply { background = rounded(dotColor, 8) }
        titleRow.addView(dot, LinearLayout.LayoutParams(px(7), px(7)).apply { marginEnd = px(6) })
        titleRow.addView(text(label, 12, muted, true))
        box.addView(titleRow)

        val value = text("—", 20, bold = true).apply {
            setPadding(0, px(7), 0, 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sub = text(initialSub, 11, dim).apply {
            setPadding(0, px(4), 0, 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        box.addView(value)
        box.addView(sub)
        monitors.addView(box, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply {
            if (!isLast) marginEnd = px(8)
        })
        return value to sub
    }
    val (cpuVal, cpuSub) = metricCard("CPU", 0xFF38BDF8.toInt(), "Reading…")
    val (ramVal, ramSub) = metricCard("Memory", purple, "Reading…")
    val (netVal, netSub) = metricCard("Network", green, "Live rate", isLast = true)
    content.addView(monitors, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(18) })

    // 3. QUICK ACTIONS (2x2 signature feature cards)
    content.addView(sectionLabel("Quick actions"),
        LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })

    val savedOffCount = savedChanges(tv).size
    val quickCards = listOf(
        QuickCardSpec(
            "Clean Up TV",
            "518 CleanYourTV lists",
            if (savedOffCount > 0) "$savedOffCount apps cleaned" else "Auto-match & guards",
            Mark.CLEAN,
            0xFF0EA5E9.toInt(),
            if (dark) 0xFF082032.toInt() else 0xFFE0F2FE.toInt()
        ) { show(Panel.CLEAN) },
        QuickCardSpec(
            "Check my TV",
            "Verify drift & reboot",
            if (savedOffCount > 0) "Verify $savedOffCount off" else "Run health check",
            Mark.CHECK,
            greenStroke,
            greenBg
        ) { show(Panel.CHECK) },
        QuickCardSpec(
            "1-Click Apps",
            "Verified GitHub APKs",
            "SmartTube, Projectivy…",
            Mark.DOWNLOAD,
            purple,
            if (dark) 0xFF1C1436.toInt() else 0xFFF3E8FF.toInt()
        ) { show(Panel.INSTALL) },
        QuickCardSpec(
            "Home & Themes",
            "Launchers & 1080p",
            "4 clean-room themes",
            Mark.HOME,
            orange,
            if (dark) 0xFF2A1E08.toInt() else 0xFFFEF3C7.toInt()
        ) { show(Panel.HOME) }
    )

    for (pair in quickCards.chunked(2)) {
        val gridRow = row()
        for ((idx, item) in pair.withIndex()) {
            val cardView = column().apply {
                background = RippleDrawable(ColorStateList.valueOf(ripple), card(field, 18, outlined = true), null)
                setPadding(px(14), px(14), px(14), px(14))
                val iconBox = FrameLayout(this@dashboard).apply {
                    background = card(item.badgeFill, 10, outlined = false).apply {
                        setStroke(px(1), item.accentColor)
                    }
                    addView(Glyph(this@dashboard, item.icon, item.accentColor, 18),
                        FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER))
                }
                addView(iconBox, LinearLayout.LayoutParams(px(34), px(34)).apply { bottomMargin = px(10) })
                addView(text(item.title, 15, bold = true))
                addView(text(item.subtitle, 12, muted).apply {
                    setPadding(0, px(3), 0, 0)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(text(item.status, 11, item.accentColor, bold = true).apply {
                    setPadding(0, px(6), 0, 0)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                isClickable = true
                isFocusable = true
                contentDescription = item.title
                setOnClickListener { item.action() }
            }
            gridRow.addView(cardView, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply {
                if (idx > 0) marginStart = px(10)
            })
        }
        content.addView(gridRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })
    }

    // 4. MORE TOOLS (grouped hairline list card)
    content.addView(sectionLabel("More tools"),
        LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(6); bottomMargin = px(8) })

    val moreCard = outlinedCard(field, radius = 18, stroke = 1).apply {
        setPadding(0, px(4), 0, px(4))
    }
    val updateHint = availableUpdate?.let { "Update ${it.tagName} available" }
        ?: "Up to date · v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
    val moreItems = listOf(
        Triple("File Manager & Send Files", "Browse /sdcard, upload APKs & media") { show(Panel.FILES) },
        Triple("Block Ads (Private DNS)", "AdGuard / NextDNS / Control D one-tap") { showDnsOptions() },
        Triple("Screen Mirror & Capture", "Live tap/swipe mirror, PNG & MP4 record") { controls() },
        Triple("About & In-App Updates", updateHint) { openAbout() }
    )
    moreItems.forEachIndexed { idx, (title, sub, action) ->
        val itemRow = row().apply {
            background = RippleDrawable(ColorStateList.valueOf(ripple), null, card(radius = 12))
            setPadding(px(16), px(12), px(14), px(12))
            val textCol = column().apply {
                addView(text(title, 15, bold = true))
                addView(text(sub, 12, if (idx == 3 && availableUpdate != null) accent else muted).apply {
                    setPadding(0, px(2), 0, 0)
                })
            }
            addView(textCol, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
            addView(text("›", 20, dim, bold = true))
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener { action() }
        }
        moreCard.addView(itemRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
        if (idx < moreItems.lastIndex) {
            moreCard.addView(rule(), LinearLayout.LayoutParams(BridgeActivity.MATCH, px(1)).apply {
                marginStart = px(16)
                marginEnd = px(16)
            })
        }
    }
    content.addView(moreCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))

    // Live 1s TV polling loop
    var previous: DeviceReading? = null
    var previousAt = 0L
    watch {
        val snapshot = withContext(Dispatchers.IO) { tv.details() }
        val now = SystemClock.elapsedRealtime()
        val total = snapshot.diskTotal
        val free = snapshot.diskFree
        storageText.text = if (total != null && free != null) "${formatBytes(free)} free of ${formatBytes(total)}" else "—"
        bar.progress = snapshot.diskUsage ?: 0
        cpuVal.text = percent(snapshot.cpuUsage(previous))
        cpuSub.text = if (snapshot.cpuCores.isNotEmpty()) "${snapshot.cpuCores.size} cores" else "Processor"

        val ramTotal = snapshot.ramTotal
        val ramAvail = snapshot.ramAvailable
        if (ramTotal != null && ramAvail != null) {
            val used = (ramTotal - ramAvail).coerceAtLeast(0)
            ramVal.text = formatBytes(used)
            ramSub.text = "${percent(snapshot.ramUsage)} of ${formatBytes(ramTotal)}"
        } else {
            ramVal.text = percent(snapshot.ramUsage)
        }

        val old = previous
        if (old != null && now > previousAt) {
            val incoming = snapshot.received
            val outgoing = snapshot.transmitted
            val previousRx = old.received
            val previousTx = old.transmitted
            if (incoming != null && outgoing != null && previousRx != null && previousTx != null &&
                incoming >= previousRx && outgoing >= previousTx) {
                val bps = (incoming - previousRx + outgoing - previousTx) * 1000 / (now - previousAt)
                netVal.text = "${formatBytes(bps)}/s"
                netSub.text = "Live traffic"
            }
        }
        previous = snapshot
        previousAt = now
    }
}

internal fun BridgeActivity.library() {
    heading("Apps and files")
    tiles(content, 1,
        Tile("Apps", Mark.APPS, "Installed, Built-in & Running") { show(Panel.APPS) },
        Tile("Install Apps", Mark.DOWNLOAD, "Local APKs, Split APKs & 1-Click") { show(Panel.INSTALL) },
        Tile("File Manager", Mark.FOLDER, "Browse TV /sdcard storage") { show(Panel.FILES) },
        Tile("Send files", Mark.UPLOAD, "Push media & APKs to TV") { show(Panel.SEND) }
    )
}

private fun BridgeActivity.connectButton() = row().apply {
    gravity = Gravity.CENTER
    background = RippleDrawable(ColorStateList.valueOf(ripple), card(accentStrong, 15, false).apply {
        if (dark) setStroke(px(1), accent)
    }, null)
    addView(Glyph(this@connectButton, Mark.TV, onAccent), LinearLayout.LayoutParams(px(22), px(22)))
    addView(text("Connect your TV", 16, onAccent, true), LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply {
        marginStart = px(10)
    })
    contentDescription = "Connect your TV"
    isFocusable = true
    setOnClickListener { connectionPicker() }
}
