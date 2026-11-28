package dev.a4tvbridge.independent

import android.content.res.ColorStateList
import android.graphics.Color
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
            addView(text(getString(R.string.app_name), 24, if (dark) ink else Color.WHITE, true))
            addView(text(getString(R.string.app_tagline), 14, if (dark) muted else 0xFFB4C4D6.toInt()).apply {
                setPadding(0, px(8), 0, 0)
                setLineSpacing(px(4).toFloat(), 1f)
            })
        }
        welcome.addView(words, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        welcome.addView(ImageView(this).apply {
            setImageResource(R.drawable.a4_mark)
            contentDescription = getString(R.string.app_name)
        }, LinearLayout.LayoutParams(px(88), px(88)).apply { marginStart = px(8) })
        content.addView(welcome, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })
        content.addView(connectButton(), LinearLayout.LayoutParams(BridgeActivity.MATCH, px(54)).apply { bottomMargin = px(14) })

        // 1-Tap Quick Reconnect for Saved TVs
        val savedTvs = TvDirectory(this).saved()
        if (savedTvs.isNotEmpty()) {
            content.addView(sectionLabel("Saved TVs · 1-tap reconnect"),
                LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })
            savedTvs.take(4).forEachIndexed { idx, saved ->
                val savedRow = row().apply {
                    background = interactiveBackground(card(field, 18, outlined = true), radius = 18)
                    minimumHeight = px(62)
                    setPadding(px(14), px(11), px(12), px(11))
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Reconnect to ${saved.model}"
                    setOnClickListener { connect(saved.address) }
                }
                val iconBox = FrameLayout(this).apply {
                    background = card(tint, 11, outlined = false).apply { setStroke(px(1), accent) }
                    addView(Glyph(this@dashboard, Mark.TV, accent, 18),
                        FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER))
                }
                savedRow.addView(iconBox, LinearLayout.LayoutParams(px(36), px(36)).apply { marginEnd = px(12) })
                val copy = column().apply {
                    addView(text(saved.model, 15, bold = true).apply {
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(text("${saved.address} · Saved", 11, muted).apply {
                        typeface = monoTypeface
                        setPadding(0, px(2), 0, 0)
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
                savedRow.addView(copy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginEnd = px(10) })
                savedRow.addView(text("Connect", 12, onAccent, bold = true).apply {
                    background = card(accentStrong, 12, outlined = false).apply {
                        if (dark) setStroke(px(1), accent)
                    }
                    setPadding(px(13), px(8), px(13), px(8))
                })
                content.addView(savedRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
                    bottomMargin = px(if (idx == savedTvs.take(4).lastIndex) 14 else 8)
                })
            }
        }

        tiles(content, 1,
            Tile("ADB Debugging Guide", Mark.INFO, "4-step setup for Google TV, Android TV, Fire TV & Xiaomi") {
                show(Panel.ADB_GUIDE)
            }
        )
        return
    }

    // 1. Connected TV Hero Card
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
        typeface = monoTypeface
        setPadding(0, px(3), 0, 0)
    })
    topRow.addView(idCol, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginEnd = px(8) })

    val renameBtn = text("Rename", 12, if (dark) 0xFFB0DEFF.toInt() else accent, bold = true).apply {
        background = interactiveBackground(
            card(if (dark) surfaceElevated else tint, 12, outlined = false).apply {
                setStroke(px(1), if (dark) 0xFF2A3F5A.toInt() else edge)
            },
            radius = 12
        )
        minHeight = px(48)
        minimumHeight = px(48)
        gravity = Gravity.CENTER
        setPadding(px(14), 0, px(14), 0)
        isClickable = true
        isFocusable = true
        contentDescription = "Rename TV"
        setOnClickListener { renameConnectedTv() }
    }
    topRow.addView(renameBtn, LinearLayout.LayoutParams(BridgeActivity.WRAP, px(48)))
    tvCard.addView(topRow)

    val storageRow = row()
    val storageText = text("—", 12, if (dark) 0xFFCBD5E1.toInt() else ink).apply {
        typeface = monoTypeface
    }
    storageRow.addView(text("TV Storage", 12, muted, true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    storageRow.addView(storageText)
    tvCard.addView(storageRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(14) })

    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        val track = card(edge, 6, outlined = false)
        val fill = ClipDrawable(
            card(accentBright, 6, outlined = false),
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
    content.addView(tvCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })

    // 2. LIVE TV METRICS (3 accent-dotted cards)
    content.addView(sectionLabel("Live TV metrics", "Device Info") { show(Panel.INFO) },
        LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(6) })

    val monitors = row()
    fun metricCard(label: String, dotColor: Int, initialSub: String, isLast: Boolean = false): Pair<TextView, TextView> {
        val box = outlinedCard(field, radius = 16, stroke = 1).apply {
            background = interactiveBackground(card(field, 16, outlined = true), radius = 16)
            setPadding(px(12), px(11), px(10), px(11))
            isClickable = true
            isFocusable = true
            contentDescription = "$label live metric — open Device Info"
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
    val (cpuVal, cpuSub) = metricCard("CPU", accentBright, "Reading…")
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
            accentBright,
            tint
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
            warningBg
        ) { show(Panel.HOME) }
    )

    for (pair in quickCards.chunked(2)) {
        val gridRow = row()
        for ((idx, item) in pair.withIndex()) {
            val cardView = column().apply {
                background = interactiveBackground(card(field, 18, outlined = true), radius = 18)
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
        Triple("File Manager", "Browse TV /sdcard storage & folders") { show(Panel.FILES) },
        Triple("Send Files to TV", "Push APKs, videos & media over Wi-Fi") { show(Panel.SEND) },
        Triple("Block Ads (Private DNS)", "AdGuard / Mullvad / Control D / NextDNS") { showDnsOptions() },
        Triple("Screen Mirror & Capture", "Live tap/swipe mirror, PNG & MP4 record") { controls() },
        Triple("About & In-App Updates", updateHint) { openAbout() }
    )
    moreItems.forEachIndexed { idx, (title, sub, action) ->
        val itemRow = row().apply {
            background = RippleDrawable(ColorStateList.valueOf(ripple), null, card(radius = 12))
            minimumHeight = px(56)
            setPadding(px(16), px(12), px(14), px(12))
            val textCol = column().apply {
                addView(text(title, 15, bold = true))
                addView(text(sub, 12, if (idx == 4 && availableUpdate != null) accent else muted).apply {
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

private fun BridgeActivity.connectButton() = row().apply {
    gravity = Gravity.CENTER
    background = interactiveBackground(
        card(accentStrong, 15, false).apply {
            if (dark) setStroke(px(1), accent)
        },
        focusFill = accentStrong,
        radius = 15
    )
    addView(Glyph(this@connectButton, Mark.TV, onAccent), LinearLayout.LayoutParams(px(22), px(22)))
    addView(text(getString(R.string.action_connect_tv), 16, onAccent, true), LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP).apply {
        marginStart = px(10)
    })
    contentDescription = getString(R.string.action_connect_tv)
    isClickable = true
    isFocusable = true
    setOnClickListener { connectionPicker() }
}
