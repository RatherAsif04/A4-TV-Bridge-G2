package dev.a4tvbridge.independent

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class MonitorPage(val label: String) { OVERVIEW("Overview"), CPU("CPU"), MEMORY("Memory"), NETWORK("Network") }
private data class MonitorPoint(val cpu: Int?, val ram: Int?, val incoming: Long?, val outgoing: Long?)

internal fun BridgeActivity.deviceInfo() {
    val tv = connection ?: return
    var latestShareReport = "A4 TV Bridge — Device Info\nModel: ${tv.model}\nAndroid: ${tv.androidVersion}\nAddress: ${tv.address}"

    // Header row matching 05-device-info-network.png: Device Info + Share Info pill
    val topBar = row()
    topBar.addView(text("Device Info", 24, bold = true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    val sharePill = text("Share Info", 12, if (dark) 0xFFB0DEFF.toInt() else accent, bold = true).apply {
        background = RippleDrawable(
            ColorStateList.valueOf(ripple),
            card(if (dark) 0xFF131D2B.toInt() else field, 11, outlined = true),
            null
        )
        setPadding(px(13), px(8), px(13), px(8))
        isClickable = true
        isFocusable = true
        contentDescription = "Share Device Info"
        setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, latestShareReport)
            startActivity(Intent.createChooser(share, "Share Device Info"))
        }
    }
    topBar.addView(sharePill)
    content.addView(topBar, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

    // 4-tab segmented pill bar: Overview | CPU | Memory | Network
    val tabs = row().apply {
        background = card(field, 14, outlined = true)
        setPadding(px(4), px(4), px(4), px(4))
    }
    content.addView(tabs, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(48)).apply { bottomMargin = px(14) })

    val reportPane = column()
    content.addView(reportPane)

    val points = ArrayDeque<MonitorPoint>()
    var selection = MonitorPage.OVERVIEW
    var lastReading: DeviceReading? = null
    var timestamp = 0L
    var processesAt = 0L
    var networkAt = 0L
    var readingMemory: MemoryBreakdown? = null
    var wifiInfo: WifiSnapshot? = null
    var dnsLabel = "Auto"
    var appTraffic: List<AppTraffic>? = null
    var coreValues = emptyList<Pair<String, Int?>>()
    var reportViews: MonitorViews? = null

    fun screen() {
        reportPane.removeAllViews()
        reportViews = buildReport(selection, reportPane, tv) { reportText -> latestShareReport = reportText }
        for (n in 0 until tabs.childCount) {
            val tab = tabs.getChildAt(n) as TextView
            val active = MonitorPage.entries[n] == selection
            tab.setTextColor(if (active) Color.WHITE else muted)
            tab.typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
            tab.background = if (active) {
                card(accentPill, 10, outlined = false).apply {
                    if (dark) setStroke(px(1), accent)
                }
            } else null
            tab.isSelected = active
        }
        reportViews.show(lastReading, points, coreValues, readingMemory, wifiInfo, dnsLabel, appTraffic)
    }

    for (tab in MonitorPage.entries) {
        tabs.addView(textButton(tab.label) {
            if (selection != tab) {
                selection = tab
                screen()
            }
        }.apply {
            textSize = 13f
            minHeight = 0
            minimumHeight = 0
            setPadding(px(2), 0, px(2), 0)
        }, LinearLayout.LayoutParams(0, BridgeActivity.MATCH, 1f))
    }
    screen()

    watch {
        val sample = withContext(Dispatchers.IO) { tv.details() }
        val now = SystemClock.elapsedRealtime()
        val previous = lastReading
        fun rate(current: Long?, old: Long?): Long? =
            if (current == null || old == null || current < old || now <= timestamp) null
            else (current - old) * 1000 / (now - timestamp)
        points.addLast(MonitorPoint(sample.cpuUsage(previous), sample.ramUsage,
            rate(sample.received, previous?.received), rate(sample.transmitted, previous?.transmitted)))
        if (points.size > 60) points.removeFirst()
        coreValues = sample.cpuCores.map { current ->
            current.label to current.change(previous?.cpuCores?.find { it.label == current.label })
        }
        lastReading = sample
        timestamp = now
        if (wifiInfo == null) {
            val (wifi, dns) = withContext(Dispatchers.IO) {
                tv.wifiSummary() to runCatching { tv.readDns() }.getOrDefault(listOf("", ""))
            }
            wifiInfo = wifi
            dnsLabel = when {
                dns.getOrElse(0) { "" }.equals("hostname", true) && dns.getOrElse(1) { "" }.isNotBlank() -> dns[1]
                dns.getOrElse(0) { "" }.equals("off", true) -> "Off"
                else -> "Automatic"
            }
        }
        reportViews?.show(sample, points, coreValues, readingMemory, wifiInfo, dnsLabel, appTraffic)
        if (selection == MonitorPage.MEMORY && now - processesAt >= 5000) {
            processesAt = now
            readingMemory = withContext(Dispatchers.IO) { tv.processMemory() }
            reportViews?.show(lastReading, points, coreValues, readingMemory, wifiInfo, dnsLabel, appTraffic)
        }
        if (selection == MonitorPage.NETWORK && now - networkAt >= 5000) {
            networkAt = now
            val (wifi, traffic, dns) = withContext(Dispatchers.IO) {
                Triple(
                    tv.wifiSummary(),
                    tv.networkByApp(),
                    runCatching { tv.readDns() }.getOrDefault(listOf("", ""))
                )
            }
            wifiInfo = wifi
            appTraffic = traffic
            dnsLabel = when {
                dns.getOrElse(0) { "" }.equals("hostname", true) && dns.getOrElse(1) { "" }.isNotBlank() -> dns[1]
                dns.getOrElse(0) { "" }.equals("off", true) -> "Off"
                else -> "Automatic"
            }
            reportViews?.show(lastReading, points, coreValues, readingMemory, wifiInfo, dnsLabel, appTraffic)
        }
    }
}

private class MonitorViews(
    val show: (DeviceReading?, Collection<MonitorPoint>, List<Pair<String, Int?>>, MemoryBreakdown?, WifiSnapshot?, String, List<AppTraffic>?) -> Unit
)

private fun BridgeActivity.buildReport(
    section: MonitorPage,
    pane: LinearLayout,
    tv: TvConnection,
    onShareText: (String) -> Unit
): MonitorViews {
    val summary = outlinedCard(field, radius = 20, stroke = 1)
    pane.addView(summary, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })

    val rates = ArrayList<TextView>(2)
    val gauges = ArrayList<Gauge>(3)
    val metric = text("—", 26, bold = true)
    val detail = text("", 14, muted, true)
    val rangeRight = text("Now", 11, dim).apply { typeface = Typeface.MONOSPACE }

    if (section == MonitorPage.OVERVIEW) {
        summary.addView(text("TV ACTIVITY (LIVE)", 11, dim, bold = true).apply { letterSpacing = 0.08f })
        val meters = row()
        for ((name, color) in listOf("CPU" to 0xFF38BDF8.toInt(), "Memory" to purple, "Storage" to green)) {
            val area = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            val gauge = Gauge(this, color)
            gauges += gauge
            area.addView(gauge, LinearLayout.LayoutParams(px(68), px(68)))
            area.addView(text(name, 14, bold = true).apply { setPadding(0, px(6), 0, px(2)) })
            meters.addView(area, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        }
        summary.addView(meters, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
            topMargin = px(12)
            bottomMargin = px(14)
        })
        summary.addView(rule())
    } else if (section == MonitorPage.NETWORK) {
        summary.addView(text("LIVE THROUGHPUT (1s SAMPLE)", 11, dim, bold = true).apply { letterSpacing = 0.08f })
    } else {
        summary.addView(text(if (section == MonitorPage.CPU) "CPU UTILIZATION" else "MEMORY USAGE", 11, dim, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(0, 0, 0, px(6))
        })
        summary.addView(metric)
        summary.addView(detail, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(4) })
    }

    if (section in listOf(MonitorPage.OVERVIEW, MonitorPage.NETWORK)) {
        val networkRow = row()
        val cols = listOf(
            Triple("↓ DOWNLOAD", 0xFF38BDF8.toInt(), if (dark) 0xFF7DD3FC.toInt() else accent),
            Triple("↑ UPLOAD", green, if (dark) 0xFF6EE7B7.toInt() else green)
        )
        for ((label, valColor, lblColor) in cols) {
            val rate = text("—", 22, valColor, bold = true)
            rates += rate
            val item = column().apply {
                addView(text(label, 11, lblColor, bold = true).apply { letterSpacing = 0.06f })
                addView(rate.apply { setPadding(0, px(3), 0, 0) })
            }
            networkRow.addView(item, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        }
        summary.addView(networkRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
            topMargin = px(12)
        })
    }

    val chart = MonitorTrend(this)
    summary.addView(chart, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(122)).apply { topMargin = px(10) })
    val range = row()
    range.addView(text("60s ago", 11, dim).apply { typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    range.addView(rangeRight)
    summary.addView(range, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(4) })

    val facts = text("", 13, muted)
    if (section != MonitorPage.NETWORK) {
        summary.addView(facts, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(10) })
    }

    // Network Link Details card (exact match to 05-device-info-network.png)
    val linkCard = outlinedCard(field, radius = 20, stroke = 1)
    val linkRows = column()
    if (section == MonitorPage.NETWORK) {
        linkCard.addView(text("WI-FI LINK DETAILS", 11, dim, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(0, 0, 0, px(8))
        })
        linkCard.addView(linkRows)
        pane.addView(linkCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })
    }

    val subheading = outlinedCard(field, radius = 20, stroke = 1)
    val extra = text("", 13, muted)
    val processes = column()
    if (section == MonitorPage.MEMORY) {
        pane.addView(subheading)
        subheading.addView(text("MEMORY BY PROCESS GROUP", 11, dim, bold = true).apply { letterSpacing = 0.08f })
        subheading.addView(extra.apply { setPadding(0, px(6), 0, px(6)) })
        subheading.addView(processes)
    } else if (section == MonitorPage.NETWORK) {
        pane.addView(subheading)
        subheading.addView(text("PER-APP TRAFFIC (UID ATTRIBUTION)", 11, dim, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(0, 0, 0, px(4))
        })
        subheading.addView(extra.apply { setPadding(0, 0, 0, px(6)) })
        subheading.addView(processes)
    } else if (section == MonitorPage.OVERVIEW) {
        pane.addView(subheading)
        subheading.addView(text("ABOUT THIS TV", 11, dim, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(0, 0, 0, px(8))
        })
        subheading.addView(extra)
    }

    fun addKvRow(host: LinearLayout, key: String, value: String, mono: Boolean = false) {
        val line = row().apply { setPadding(0, px(6), 0, px(6)) }
        line.addView(text(key, 13, muted), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        line.addView(text(value, 13, ink, bold = true).apply {
            if (mono) typeface = Typeface.MONOSPACE
            gravity = Gravity.END
        })
        host.addView(line, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
    }

    var lastList: MemoryBreakdown? = null
    var lastTraffic: List<AppTraffic>? = null
    return MonitorViews { reading, series, cores, memory, wifi, dns, trafficRows ->
        val point = series.lastOrNull()
        if (rates.isNotEmpty()) {
            rates[0].text = point?.incoming?.let { "${formatBytes(it)}/s" } ?: "—"
            rates[1].text = point?.outgoing?.let { "${formatBytes(it)}/s" } ?: "—"
        }
        val used = reading?.ramTotal?.let { total -> reading.ramAvailable?.let { total - it } }
        val traffic = listOf(series.map { it.incoming?.toDouble() }, series.map { it.outgoing?.toDouble() })
        val uptime = reading?.uptimeSeconds?.let { "${it / 3600} h ${it / 60 % 60} min" } ?: "—"
        val linkDesc = listOfNotNull(
            wifi?.transport,
            wifi?.bandLabel,
            wifi?.linkSpeedMbps?.let { "$it Mbps" }
        ).joinToString(" · ")
        val diskUsed = reading?.diskTotal?.let { total -> reading.diskFree?.let { total - it } }
        onShareText(buildString {
            appendLine("A4 TV Bridge — Device Info")
            appendLine("Model: ${tv.model}")
            appendLine("Android: ${tv.androidVersion}")
            appendLine("Address: ${tv.address.host}")
            appendLine("Uptime: $uptime")
            if (cores.isNotEmpty()) appendLine("CPU cores: ${cores.size} (current ${percent(point?.cpu)})")
            appendLine("Memory: ${formatBytes(used)} / ${formatBytes(reading?.ramTotal)} (${percent(reading?.ramUsage)})")
            appendLine("Storage: ${formatBytes(diskUsed)} / ${formatBytes(reading?.diskTotal)} (${percent(reading?.diskUsage)})")
            if (linkDesc.isNotBlank()) appendLine("Link: $linkDesc")
            appendLine("Private DNS: $dns")
        }.trim())

        when (section) {
            MonitorPage.OVERVIEW -> {
                gauges[0].value = point?.cpu
                gauges[1].value = reading?.ramUsage
                gauges[2].value = reading?.diskUsage
                chart.update(traffic, listOf(0xFF38BDF8.toInt(), green), null)
                facts.visibility = View.GONE
                extra.text = buildString {
                    append("${tv.model}  ·  Android ${tv.androidVersion}\n")
                    append("Address  ${tv.address.host}  ·  Uptime $uptime")
                    if (linkDesc.isNotBlank()) append("\nNetwork  $linkDesc")
                    append("\nPrivate DNS  $dns")
                }
            }
            MonitorPage.CPU -> {
                metric.text = percent(point?.cpu)
                detail.text = if (cores.isEmpty()) "Processor" else "${cores.size} CPU cores"
                chart.update(listOf(series.map { it.cpu?.toDouble() }), listOf(0xFF38BDF8.toInt()), 100.0)
                facts.text = cores.take(8).mapIndexed { index, (_, value) ->
                    "Core ${index + 1}  ${percent(value)}"
                }.chunked(2).joinToString("\n") { it.joinToString("       ") }
            }
            MonitorPage.MEMORY -> {
                metric.text = percent(reading?.ramUsage)
                detail.text = "${formatBytes(used)} of ${formatBytes(reading?.ramTotal)}"
                chart.update(listOf(series.map { it.ram?.toDouble() }), listOf(purple), 100.0)
                val spare = reading?.ramAvailable?.let { available -> reading.ramFree?.let { (available - it).coerceAtLeast(0) } }
                val swap = reading?.swapTotal?.let { total -> reading.swapFree?.let { total - it } }
                facts.text = "In use  ${formatBytes(used)}       Free  ${formatBytes(reading?.ramFree)}\n" +
                    "Reclaimable  ${formatBytes(spare)}\nSwap used  ${formatBytes(swap)} of ${formatBytes(reading?.swapTotal)}"
                extra.text = when {
                    memory == null -> "Reading memory by process…"
                    memory.rows.isEmpty() -> "Process memory is unavailable."
                    memory.method == "PSS" -> "Proportional set size (PSS) from Android."
                    else -> "Resident memory estimate (RSS) from Android."
                }
                if (memory != lastList) {
                    lastList = memory
                    processes.removeAllViews()
                    for ((name, kib, count) in memory?.rows.orEmpty().take(12)) {
                        processes.addView(rule())
                        val line = row().apply { minimumHeight = px(48) }
                        val process = column().apply {
                            addView(text(displayName(name), 14, bold = true))
                            addView(text("$count process${if (count == 1) "" else "es"}", 11, muted))
                        }
                        line.addView(process, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
                        line.addView(text(formatBytes(kib * 1024), 15, bold = true).apply { typeface = Typeface.MONOSPACE })
                        processes.addView(line)
                    }
                }
            }
            MonitorPage.NETWORK -> {
                chart.update(traffic, listOf(0xFF38BDF8.toInt(), green), null)
                val iface = if (wifi?.transport.equals("Ethernet", true)) "eth0" else "wlan0"
                rangeRight.text = "Now ($iface)"

                linkRows.removeAllViews()
                addKvRow(linkRows, "Interface / IP", "$iface · ${tv.address.host}", mono = true)
                val bandSpeed = listOfNotNull(
                    wifi?.bandLabel ?: wifi?.transport,
                    wifi?.linkSpeedMbps?.let { "$it Mbps" }
                ).joinToString(" · ").ifBlank { "Connected" }
                addKvRow(linkRows, "Band & Link Speed", bandSpeed)
                val sigLabel = wifi?.rssiDbm?.let { rssi ->
                    val qual = when {
                        rssi >= -58 -> "Excellent"
                        rssi >= -68 -> "Good"
                        rssi >= -78 -> "Fair"
                        else -> "Weak"
                    }
                    "$rssi dBm ($qual)"
                } ?: "Since boot ↓ ${formatBytes(reading?.received)} · ↑ ${formatBytes(reading?.transmitted)}"
                addKvRow(linkRows, if (wifi?.rssiDbm != null) "Signal Strength" else "Total Transfer", sigLabel)
                addKvRow(linkRows, "Private DNS", dns, mono = true)

                extra.text = when {
                    trafficRows == null -> "Reading per-app network stats…"
                    trafficRows.isEmpty() -> "App-level traffic is unavailable on this TV firmware."
                    else -> ""
                }
                extra.visibility = if (extra.text.isBlank()) View.GONE else View.VISIBLE

                if (trafficRows != lastTraffic) {
                    lastTraffic = trafficRows
                    processes.removeAllViews()
                    val topRows = trafficRows.orEmpty().take(12)
                    val maxBytes = topRows.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1L) ?: 1L
                    val barPalette = listOf(0xFF38BDF8.toInt(), purple, green, orange)
                    topRows.forEachIndexed { idx, entry ->
                        val item = column().apply { setPadding(0, px(8), 0, px(8)) }
                        val headerLine = row()
                        headerLine.addView(text(entry.label, 14, bold = true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
                        headerLine.addView(text(formatBytes(entry.totalBytes), 14, bold = true).apply { typeface = Typeface.MONOSPACE })
                        item.addView(headerLine)
                        item.addView(text("↓ ${formatBytes(entry.rxBytes)} · ↑ ${formatBytes(entry.txBytes)}", 11, dim).apply {
                            typeface = Typeface.MONOSPACE
                            setPadding(0, px(2), 0, px(6))
                        })
                        val track = FrameLayout(this).apply {
                            background = rounded(edge, 4)
                            val pct = (entry.totalBytes.toFloat() / maxBytes.toFloat()).coerceIn(0.06f, 1f)
                            val barFill = View(this@buildReport).apply {
                                background = rounded(barPalette[idx % barPalette.size], 4)
                            }
                            addView(barFill, FrameLayout.LayoutParams(0, px(7)))
                            post {
                                val targetW = (width * pct).toInt().coerceAtLeast(px(12))
                                barFill.layoutParams = FrameLayout.LayoutParams(targetW, px(7))
                            }
                        }
                        item.addView(track, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(7)))
                        processes.addView(item)
                    }
                }
            }
        }
    }
}

private class Gauge(private val activity: BridgeActivity, private val tint: Int) : FrameLayout(activity) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val label = activity.text("—", 17, bold = true)

    var value: Int? = null
        set(next) {
            field = next
            label.text = percent(next)
            invalidate()
        }

    init {
        setWillNotDraw(false)
        addView(label, LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP, Gravity.CENTER))
    }

    override fun onDraw(canvas: Canvas) {
        paint.strokeWidth = activity.px(5).toFloat()
        val inset = paint.strokeWidth
        paint.color = activity.edge
        canvas.drawOval(inset, inset, width - inset, height - inset, paint)
        paint.color = tint
        value?.let { canvas.drawArc(inset, inset, width - inset, height - inset, -90f, it * 3.6f, false, paint) }
    }
}

private class MonitorTrend(private val activity: BridgeActivity) : View(activity) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lines = emptyList<List<Double?>>()
    private var colors = emptyList<Int>()
    private var peak = 1.0

    fun update(series: List<List<Double?>>, palette: List<Int>, limit: Double?) {
        lines = series
        colors = palette
        peak = limit ?: series.flatten().filterNotNull().maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val pad = activity.px(6).toFloat()
        val chartH = height - 2 * pad
        paint.style = Paint.Style.STROKE
        paint.color = if (activity.dark) 0xFF172030.toInt() else activity.edge
        paint.strokeWidth = activity.px(1).toFloat()
        for (part in 0..3) {
            val y = pad + chartH * part / 3f
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
        val bottomY = pad + chartH
        for ((index, values) in lines.withIndex()) {
            val path = Path()
            val area = Path()
            var open = false
            var firstX = 0f
            var lastX = 0f
            for ((position, value) in values.withIndex()) {
                if (value == null) { open = false; continue }
                val x = width * (60 - values.size + position) / 59f
                val y = pad + chartH * (1 - (value / peak).coerceIn(0.0, 1.0)).toFloat()
                if (open) {
                    path.lineTo(x, y)
                    area.lineTo(x, y)
                } else {
                    path.moveTo(x, y)
                    area.moveTo(x, y)
                    firstX = x
                }
                lastX = x
                open = true
            }
            if (index == 0 && open) {
                area.lineTo(lastX, bottomY)
                area.lineTo(firstX, bottomY)
                area.close()
                paint.style = Paint.Style.FILL
                val baseColor = colors[index]
                paint.color = Color.argb(if (activity.dark) 44 else 32, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                canvas.drawPath(area, paint)
            }
            paint.color = colors[index]
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = activity.px(if (index == 0) 3 else 2).toFloat()
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
