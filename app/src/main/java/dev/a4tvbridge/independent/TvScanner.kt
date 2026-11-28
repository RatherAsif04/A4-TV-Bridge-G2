package dev.a4tvbridge.independent

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

internal enum class ConnectionPhase {
    IDLE,
    SCANNING,
    CONNECTING,
    AWAITING_APPROVAL,
    CONNECTED,
    ERROR
}

internal data class ConnectionStatusState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val targetLabel: String = "",
    val detail: String = "",
    val targetAddress: TvAddress? = null
)

internal enum class DiscoveredSource { LIVE_ADB, WIRELESS_PAIR, SAVED }

internal data class DiscoveredTv(
    val name: String,
    val address: TvAddress,
    val source: DiscoveredSource
)

internal fun activeConnectionStep(phase: ConnectionPhase): Int = when (phase) {
    ConnectionPhase.IDLE, ConnectionPhase.SCANNING -> 1
    ConnectionPhase.CONNECTING -> 2
    ConnectionPhase.AWAITING_APPROVAL -> 3
    ConnectionPhase.CONNECTED -> 4
    ConnectionPhase.ERROR -> 2
}

private class RadarPulseView(
    private val activity: BridgeActivity
) : View(activity) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var tvIcon = BridgeIcon(Mark.TV, 0xFF38BDF8.toInt())
    private var progress = 0f
    private var pulsing = true
    private var accentColor = 0xFF38BDF8.toInt()
    private var centerFill = 0xFF0C2338.toInt()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    fun updateStyle(color: Int, centerBg: Int, animate: Boolean) {
        accentColor = color
        centerFill = centerBg
        tvIcon = BridgeIcon(Mark.TV, color)
        pulsing = animate
        if (animate) {
            if (isAttachedToWindow && !animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pulsing && !animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(cx, cy) - activity.px(4)
        val centerR = activity.px(26).toFloat()
        if (maxR <= centerR) return

        val r = Color.red(accentColor)
        val g = Color.green(accentColor)
        val b = Color.blue(accentColor)

        // Soft ambient radial glow
        glowPaint.shader = RadialGradient(
            cx, cy, maxR,
            intArrayOf(Color.argb(64, r, g, b), Color.argb(0, r, g, b)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, maxR, glowPaint)

        // Subtle static guide rings
        ringPaint.strokeWidth = activity.px(1).toFloat()
        ringPaint.color = Color.argb(46, r, g, b)
        canvas.drawCircle(cx, cy, centerR + (maxR - centerR) * 0.45f, ringPaint)
        canvas.drawCircle(cx, cy, maxR * 0.94f, ringPaint)

        // 3 animated staggered pulse waves
        if (pulsing) {
            ringPaint.strokeWidth = activity.px(2).toFloat()
            for (i in 0..2) {
                val p = (progress + i / 3f) % 1f
                val radius = centerR + (maxR - centerR) * p
                val alpha = ((1f - p) * 185f).toInt().coerceIn(0, 255)
                ringPaint.color = Color.argb(alpha, r, g, b)
                canvas.drawCircle(cx, cy, radius, ringPaint)
            }
        }

        // Center badge + TV vector icon
        fillPaint.color = centerFill
        canvas.drawCircle(cx, cy, centerR, fillPaint)
        ringPaint.strokeWidth = activity.px(2).toFloat()
        ringPaint.color = accentColor
        canvas.drawCircle(cx, cy, centerR, ringPaint)

        val iconSide = activity.px(24)
        val half = iconSide / 2
        tvIcon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        tvIcon.draw(canvas)
    }
}

internal fun BridgeActivity.tvScannerPage() {
    val owner = content
    val known = TvDirectory(this).saved()
    val discovered = mutableListOf<DiscoveredTv>()
    val wirelessPorts = mutableMapOf<String, Int>()
    var scanning = true
    var scanJob: Job? = null
    var discovery: TvDiscovery? = null

    // 1. Hero Radar & Live Stepper Card
    val heroCard = outlinedCard(field, radius = 20, stroke = 1).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(px(16), px(18), px(16), px(16))
    }
    val radar = RadarPulseView(this)
    heroCard.addView(radar, LinearLayout.LayoutParams(px(136), px(136)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        bottomMargin = px(10)
    })
    val heroTitle = text("Scanning local network…", 18, bold = true).apply {
        gravity = Gravity.CENTER
    }
    val heroSubtitle = text("Searching for Android TV, Google TV & Fire TV", 12, muted).apply {
        gravity = Gravity.CENTER
        setPadding(0, px(4), 0, px(16))
    }
    heroCard.addView(heroTitle, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
    heroCard.addView(heroSubtitle, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))

    // 3-step horizontal connection progress stepper (Scan -> Connect -> Approve on TV)
    val stepperRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
    val stepDots = mutableListOf<View>()
    val stepLabels = mutableListOf<TextView>()
    val stepLines = mutableListOf<View>()
    val stepNames = listOf("Scan", "Connect", "Approve on TV")
    stepNames.forEachIndexed { idx, label ->
        val item = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val dot = View(this).apply { background = rounded(dim, 8) }
        val txt = text(label, 11, dim, bold = false).apply {
            isSingleLine = true
            setPadding(px(5), 0, 0, 0)
        }
        item.addView(dot, LinearLayout.LayoutParams(px(9), px(9)))
        item.addView(txt)
        stepDots.add(dot)
        stepLabels.add(txt)
        stepperRow.addView(item, LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP))
        if (idx < stepNames.lastIndex) {
            val connector = View(this).apply { setBackgroundColor(edge) }
            stepLines.add(connector)
            stepperRow.addView(connector, LinearLayout.LayoutParams(0, px(1), 1f).apply {
                marginStart = px(8)
                marginEnd = px(8)
            })
        }
    }
    heroCard.addView(stepperRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
    content.addView(heroCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        bottomMargin = px(16)
    })

    // 2. Discovered TVs Section Header + Rescan action
    val listHeaderRow = row()
    val listHeaderText = text("DISCOVERED TVS (0)", 11, dim, bold = true).apply {
        letterSpacing = 0.08f
    }
    var runScan: () -> Unit = {}
    val rescanBtn = textButton("Rescan") { runScan() }.apply {
        textSize = 13f
        minHeight = 0
        minimumHeight = 0
        setPadding(px(6), px(2), 0, px(2))
    }
    listHeaderRow.addView(listHeaderText, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    listHeaderRow.addView(rescanBtn)
    content.addView(listHeaderRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        bottomMargin = px(10)
    })

    val devicesList = column()
    content.addView(devicesList, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        bottomMargin = px(14)
    })

    // 3. Live Connection Status Banner Card (shown during Connecting / Waiting for Approval / Error)
    val statusCardHost = column()
    content.addView(statusCardHost, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        bottomMargin = px(14)
    })

    // 4. Bottom Quick Actions
    actions(
        "Enter IP Manually" to { connectDialog(pair = false, after = scannerAfter) },
        "Pair with Code" to { connectDialog(pair = true, after = scannerAfter) }
    )
    content.addView(textButton("TV not found? Open ADB Debugging Guide  ›") {
        show(Panel.ADB_GUIDE)
    }.apply {
        textSize = 13f
        gravity = Gravity.CENTER
    }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        topMargin = px(4)
    })

    fun renderStatusAndHero() {
        if (content !== owner) return
        val effective = if (connectionStatus.phase == ConnectionPhase.IDLE && scanning) {
            ConnectionStatusState(ConnectionPhase.SCANNING)
        } else {
            connectionStatus
        }
        val activeStep = activeConnectionStep(effective.phase)

        when (effective.phase) {
            ConnectionPhase.SCANNING -> {
                radar.updateStyle(accent, if (dark) 0xFF0A2238.toInt() else tint, animate = true)
                heroTitle.text = "Scanning local network…"
                heroSubtitle.text = "Searching for Android TV, Google TV & Fire TV"
            }
            ConnectionPhase.IDLE -> {
                radar.updateStyle(accent, if (dark) 0xFF0A2238.toInt() else tint, animate = false)
                heroTitle.text = if (discovered.isEmpty()) "Scan complete" else "${discovered.size} TV(s) ready"
                heroSubtitle.text = if (discovered.isEmpty()) {
                    "No TVs responded yet · Tap Rescan or check ADB Debugging Guide"
                } else {
                    "Select your TV below to connect over local Wi-Fi"
                }
            }
            ConnectionPhase.CONNECTING -> {
                radar.updateStyle(accent, if (dark) 0xFF0A2238.toInt() else tint, animate = true)
                heroTitle.text = "Connecting to TV…"
                heroSubtitle.text = effective.targetLabel.ifBlank { "Opening ADB connection" }
            }
            ConnectionPhase.AWAITING_APPROVAL -> {
                radar.updateStyle(orange, if (dark) 0xFF2A1E08.toInt() else 0xFFFEF3C7.toInt(), animate = true)
                heroTitle.text = "Waiting for TV approval"
                heroSubtitle.text = "Check 'Always allow' and press OK on your TV screen"
            }
            ConnectionPhase.CONNECTED -> {
                radar.updateStyle(greenStroke, greenBg, animate = false)
                heroTitle.text = "TV Connected"
                heroSubtitle.text = effective.detail.ifBlank { "Ready" }
            }
            ConnectionPhase.ERROR -> {
                radar.updateStyle(rose, if (dark) 0xFF2B1019.toInt() else 0xFFFFE4E6.toInt(), animate = false)
                heroTitle.text = "Could not connect"
                heroSubtitle.text = effective.targetLabel.ifBlank { "Check TV IP, Wi-Fi, and ADB debugging" }
            }
        }

        // Update 3-step stepper colors
        stepDots.forEachIndexed { idx, dot ->
            val stepNum = idx + 1
            val isCompleted = stepNum < activeStep || effective.phase == ConnectionPhase.CONNECTED
            val isCurrent = stepNum == activeStep && effective.phase != ConnectionPhase.CONNECTED
            val color = when {
                isCompleted -> greenStroke
                isCurrent && effective.phase == ConnectionPhase.AWAITING_APPROVAL -> orange
                isCurrent && effective.phase == ConnectionPhase.ERROR -> rose
                isCurrent -> accent
                else -> dim
            }
            dot.background = rounded(color, 8)
            stepLabels[idx].setTextColor(if (isCurrent || isCompleted) color else dim)
            stepLabels[idx].setTypeface(
                if (isCurrent || isCompleted) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            )
            if (idx < stepLines.size) {
                stepLines[idx].setBackgroundColor(if (stepNum < activeStep) greenStroke else edge)
            }
        }

        // Render live connection banner card when active/error
        statusCardHost.removeAllViews()
        when (effective.phase) {
            ConnectionPhase.CONNECTING, ConnectionPhase.AWAITING_APPROVAL, ConnectionPhase.ERROR -> {
                val isError = effective.phase == ConnectionPhase.ERROR
                val isApproval = effective.phase == ConnectionPhase.AWAITING_APPROVAL
                val borderColor = when {
                    isError -> rose
                    isApproval -> orange
                    else -> accent
                }
                val bgFill = when {
                    isError -> if (dark) 0xFF1D0D14.toInt() else 0xFFFFF1F2.toInt()
                    isApproval -> if (dark) 0xFF1E170A.toInt() else 0xFFFFFBEB.toInt()
                    else -> tint
                }
                val banner = row().apply {
                    background = card(bgFill, 16, outlined = false).apply { setStroke(px(1), borderColor) }
                    setPadding(px(14), px(12), px(12), px(12))
                }
                val dot = View(this@tvScannerPage).apply { background = rounded(borderColor, 8) }
                banner.addView(dot, LinearLayout.LayoutParams(px(9), px(9)).apply { marginEnd = px(10) })
                val titleText = when {
                    isError -> "Connection failed"
                    isApproval -> "Waiting for TV approval"
                    else -> "Connecting to ${effective.targetLabel.ifBlank { "TV" }}"
                }
                val copy = column().apply {
                    addView(text(titleText, 14, bold = true))
                    addView(text(effective.detail, 12, muted).apply { setPadding(0, px(2), 0, 0) })
                }
                banner.addView(copy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginEnd = px(8) })
                if (isError && effective.targetAddress != null) {
                    val retryBtn = text("Retry", 12, Color.WHITE, bold = true).apply {
                        background = RippleDrawable(ColorStateList.valueOf(ripple), rounded(accentStrong, 10), null)
                        setPadding(px(12), px(7), px(12), px(7))
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { connect(effective.targetAddress, after = scannerAfter) }
                    }
                    banner.addView(retryBtn)
                } else if (!isError) {
                    val cancelBtn = text("Cancel", 12, ink, bold = true).apply {
                        background = RippleDrawable(ColorStateList.valueOf(ripple), card(field, 10, outlined = true), null)
                        setPadding(px(12), px(7), px(12), px(7))
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { cancelActiveConnection() }
                    }
                    banner.addView(cancelBtn)
                }
                statusCardHost.addView(banner, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
            }
            else -> Unit
        }
    }

    fun renderDevices() {
        if (content !== owner) return
        listHeaderText.text = "DISCOVERED TVS (${discovered.size})"
        devicesList.removeAllViews()
        if (discovered.isEmpty()) {
            val emptyBox = outlinedCard(field, radius = 16, stroke = 1).apply {
                setPadding(px(16), px(16), px(16), px(16))
            }
            emptyBox.addView(text(
                if (scanning) "Listening for TVs on your local Wi-Fi…"
                else "No TVs found yet. Turn on ADB debugging on your TV and tap Rescan, or enter its IP manually.",
                13, muted
            ))
            devicesList.addView(emptyBox, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
            return
        }

        discovered.forEachIndexed { idx, device ->
            val isPair = device.source == DiscoveredSource.WIRELESS_PAIR
            val isLive = device.source == DiscoveredSource.LIVE_ADB
            val iconColor = when (device.source) {
                DiscoveredSource.LIVE_ADB -> accent
                DiscoveredSource.WIRELESS_PAIR -> purple
                DiscoveredSource.SAVED -> muted
            }
            val iconFill = when (device.source) {
                DiscoveredSource.LIVE_ADB -> if (dark) 0xFF0A2238.toInt() else tint
                DiscoveredSource.WIRELESS_PAIR -> if (dark) 0xFF1C1436.toInt() else 0xFFF3E8FF.toInt()
                DiscoveredSource.SAVED -> if (dark) 0xFF131D2B.toInt() else 0xFFF1F5F9.toInt()
            }
            val statusLabel = when (device.source) {
                DiscoveredSource.LIVE_ADB -> "ADB Ready"
                DiscoveredSource.WIRELESS_PAIR -> "Pairing Code"
                DiscoveredSource.SAVED -> "Saved TV"
            }

            fun triggerConnect() {
                if (isPair) {
                    connectDialog(
                        pair = true,
                        hostHint = device.address.host,
                        pairHint = device.address.port,
                        portHint = wirelessPorts[device.address.host],
                        after = scannerAfter
                    )
                } else {
                    connect(device.address, after = scannerAfter)
                }
            }

            val cardRow = row().apply {
                background = RippleDrawable(ColorStateList.valueOf(ripple), card(field, 18, outlined = true), null)
                setPadding(px(14), px(12), px(12), px(12))
                isClickable = true
                isFocusable = true
                contentDescription = "${device.name} ${device.address}"
                setOnClickListener { triggerConnect() }
            }
            val iconBox = FrameLayout(this).apply {
                background = card(iconFill, 11, outlined = false).apply { setStroke(px(1), iconColor) }
                addView(Glyph(this@tvScannerPage, Mark.TV, iconColor, 18),
                    FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER))
            }
            cardRow.addView(iconBox, LinearLayout.LayoutParams(px(38), px(38)).apply { marginEnd = px(12) })

            val copy = column().apply {
                addView(text(device.name, 15, bold = true).apply {
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(text("${device.address} · $statusLabel", 11, if (isLive) accent else muted).apply {
                    typeface = Typeface.MONOSPACE
                    setPadding(0, px(3), 0, 0)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            cardRow.addView(copy, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginEnd = px(10) })

            val actionPill = text(
                if (isPair) "Pair" else "Connect",
                12,
                if (isPair) ink else Color.WHITE,
                bold = true
            ).apply {
                background = if (isPair) {
                    card(if (dark) 0xFF131D2B.toInt() else field, 12, outlined = true)
                } else {
                    card(accentStrong, 12, outlined = false).apply {
                        if (dark) setStroke(px(1), accent)
                    }
                }
                setPadding(px(14), px(8), px(14), px(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { triggerConnect() }
            }
            cardRow.addView(actionPill)
            devicesList.addView(cardRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
                if (idx < discovered.lastIndex) bottomMargin = px(9)
            })
        }
    }

    fun upsertDevice(name: String, address: TvAddress, source: DiscoveredSource) {
        val existingIdx = discovered.indexOfFirst {
            it.address == address && (it.source == DiscoveredSource.WIRELESS_PAIR) == (source == DiscoveredSource.WIRELESS_PAIR)
        }
        if (existingIdx >= 0) {
            val prev = discovered[existingIdx]
            // Promote SAVED to LIVE_ADB if live discovery found it
            val upgradedSource = if (prev.source == DiscoveredSource.SAVED) source else prev.source
            val upgradedName = if (prev.source == DiscoveredSource.SAVED) prev.name else name
            discovered[existingIdx] = prev.copy(name = upgradedName, source = upgradedSource)
        } else {
            val insertAt = if (source == DiscoveredSource.LIVE_ADB) 0 else discovered.size
            discovered.add(insertAt, DiscoveredTv(name, address, source))
        }
        renderDevices()
        renderStatusAndHero()
    }

    runScan = {
        scanJob?.cancel()
        discovery?.close()
        scanning = true
        if (connectionStatus.phase == ConnectionPhase.ERROR) {
            updateConnectionStatus(ConnectionStatusState(ConnectionPhase.IDLE))
        }
        renderDevices()
        renderStatusAndHero()

        discovery = TvDiscovery(this) { name, station, address ->
            if (content !== owner) return@TvDiscovery
            if (station == AdvertisedService.CONNECT) wirelessPorts[address.host] = address.port
            val displayTitle = when (station) {
                AdvertisedService.PAIR -> "Pair TV at ${address.host}"
                else -> known.firstOrNull { it.address == address }?.model ?: name
            }
            upsertDevice(
                displayTitle,
                address,
                if (station == AdvertisedService.PAIR) DiscoveredSource.WIRELESS_PAIR else DiscoveredSource.LIVE_ADB
            )
        }.also { it.start() }

        scanJob = launchSheetTask {
            try {
                val addresses = withContext(Dispatchers.IO) { findLegacyEndpoints() }
                if (content === owner) {
                    for (address in addresses) {
                        val label = known.firstOrNull { it.address == address }?.model ?: "TV at ${address.host}"
                        upsertDevice(label, address, DiscoveredSource.LIVE_ADB)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (content === owner) {
                    heroSubtitle.text = error.message ?: "Connect phone and TV to the same Wi-Fi network."
                }
            } finally {
                if (content === owner) {
                    scanning = false
                    renderDevices()
                    renderStatusAndHero()
                }
            }
        }
    }

    // Pre-populate known saved TVs immediately while live scan runs
    for (saved in known) {
        discovered.add(DiscoveredTv(saved.model, saved.address, DiscoveredSource.SAVED))
    }

    onConnectionStatusChanged = { renderStatusAndHero() }
    owner.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            scanJob?.cancel()
            discovery?.close()
            onConnectionStatusChanged = null
        }
    })

    renderDevices()
    renderStatusAndHero()
    runScan()
}
