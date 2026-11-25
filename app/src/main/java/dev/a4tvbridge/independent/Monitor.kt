package dev.a4tvbridge.independent

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
    heading("Device Info")
    val identity = outlinedCard()
    val identityRow = row()
    identityRow.addView(Glyph(this, Mark.TV, accent).apply { background = card(tint, 12, false) }, LinearLayout.LayoutParams(px(40), px(40)))
    val title = column()
    title.addView(text(tvTitle() ?: tv.model, 16, bold = true))
    title.addView(text(tv.address.host, 13, muted, true))
    identityRow.addView(title, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(10) })
    identityRow.addView(textButton("Shell") { show(Panel.SHELL) }.apply {
        labelIcon(this, Mark.SHELL)
    })
    identity.addView(identityRow)
    content.addView(identity, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })
    val tabs = row().apply { background = card(radius = 13, outlined = false); setPadding(px(4), px(4), px(4), px(4)) }
    content.addView(tabs, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(52)).apply { bottomMargin = px(14) })
    val reportPane = column()
    content.addView(reportPane)
    val points = ArrayDeque<MonitorPoint>()
    var selection = MonitorPage.OVERVIEW
    var lastReading: DeviceReading? = null
    var timestamp = 0L
    var processesAt = 0L
    var readingMemory: MemoryBreakdown? = null
    var coreValues = emptyList<Pair<String, Int?>>()
    var reportViews: MonitorViews? = null

    fun screen() {
        reportPane.removeAllViews()
        reportViews = buildReport(selection, reportPane, tv)
        for (n in 0 until tabs.childCount) {
            val tab = tabs.getChildAt(n) as TextView
            val active = MonitorPage.entries[n] == selection
            tab.setTextColor(if (active) accent else muted)
            tab.background = if (active) card(tint, 10, false) else null
            tab.isSelected = active
        }
        reportViews?.show(lastReading, points, coreValues, readingMemory)
    }

    for (tab in MonitorPage.entries) {
        tabs.addView(textButton(tab.label) {
            if (selection != tab) {
                selection = tab
                screen()
            }
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
        reportViews?.show(sample, points, coreValues, readingMemory)
        if (selection == MonitorPage.MEMORY && now - processesAt >= 5000) {
            processesAt = now
            readingMemory = withContext(Dispatchers.IO) { tv.processMemory() }
            reportViews?.show(lastReading, points, coreValues, readingMemory)
        }
    }
}

private class MonitorViews(
    val show: (DeviceReading?, Collection<MonitorPoint>, List<Pair<String, Int?>>, MemoryBreakdown?) -> Unit
)

private fun BridgeActivity.buildReport(section: MonitorPage, pane: LinearLayout, tv: TvConnection): MonitorViews {
    val summary = outlinedCard()
    pane.addView(summary, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })
    val rates = ArrayList<TextView>(2)
    val gauges = ArrayList<Gauge>(3)
    val metric = text("—", 24)
    val detail = text("", 16, muted, true)
    if (section == MonitorPage.OVERVIEW) {
        summary.addView(text("TV activity", 16, bold = true))
        val meters = row()
        for ((name, color) in listOf("CPU" to accent, "Memory" to purple, "Storage" to green)) {
            val area = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            val gauge = Gauge(this, color)
            gauges += gauge
            area.addView(gauge, LinearLayout.LayoutParams(px(65), px(65)))
            area.addView(text(name, 17, bold = true).apply { setPadding(0, px(4), 0, px(3)) })
            meters.addView(area, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        }
        summary.addView(meters, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
            topMargin = px(12)
            bottomMargin = px(15)
        })
        summary.addView(rule())
    } else if (section != MonitorPage.NETWORK) {
        summary.addView(metric)
        summary.addView(detail, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(4) })
    }
    if (section in listOf(MonitorPage.OVERVIEW, MonitorPage.NETWORK)) {
        val network = row()
        for ((label, color) in listOf("↓ Download" to accent, "↑ Upload" to orange)) {
            val rate = text("—", 23, color)
            rates += rate
            val item = column().apply {
                addView(rate)
                addView(text(label, 16, muted, true).apply { setPadding(0, px(4), 0, 0) })
            }
            network.addView(item, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        }
        summary.addView(network, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
            topMargin = px(14)
        })
    }
    val chart = MonitorTrend(this)
    summary.addView(chart, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(109)).apply { topMargin = px(8) })
    val range = row()
    range.addView(text("60 s ago", 11, muted, true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    range.addView(text("Now", 11, muted, true))
    summary.addView(range)
    val facts = text("", 14, muted)
    summary.addView(facts, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(10) })
    val subheading = outlinedCard()
    val extra = text("", 14, muted)
    val processes = column()
    if (section == MonitorPage.MEMORY) {
        pane.addView(subheading)
        subheading.addView(text("Memory by process group", 16, bold = true))
        subheading.addView(extra)
        subheading.addView(processes)
    } else if (section == MonitorPage.OVERVIEW) {
        pane.addView(subheading)
        val footer = row()
        footer.addView(text("About this TV", 16, bold = true), LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
        footer.addView(textButton("Share Device Info") {
            val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "A4 TV Bridge\nModel: ${tv.model}\nAndroid: ${tv.androidVersion}\n${extra.text}")
            startActivity(Intent.createChooser(share, "Share Device Info"))
        })
        subheading.addView(footer)
        subheading.addView(extra)
    }

    var lastList: MemoryBreakdown? = null
    return MonitorViews { reading, series, cores, memory ->
        val point = series.lastOrNull()
        if (rates.isNotEmpty()) {
            rates[0].text = point?.incoming?.let { "${formatBytes(it)}/s" } ?: "—"
            rates[1].text = point?.outgoing?.let { "${formatBytes(it)}/s" } ?: "—"
        }
        val used = reading?.ramTotal?.let { total -> reading.ramAvailable?.let { total - it } }
        val traffic = listOf(series.map { it.incoming?.toDouble() }, series.map { it.outgoing?.toDouble() })
        when (section) {
            MonitorPage.OVERVIEW -> {
                gauges[0].value = point?.cpu
                gauges[1].value = reading?.ramUsage
                gauges[2].value = reading?.diskUsage
                chart.update(traffic, listOf(accent, orange), null)
                facts.visibility = View.GONE
                val uptime = reading?.uptimeSeconds?.let { "${it / 3600} h ${it / 60 % 60} min" } ?: "—"
                extra.text = "${tv.model}   ·   Android ${tv.androidVersion}\nUptime $uptime"
            }
            MonitorPage.CPU -> {
                metric.text = percent(point?.cpu)
                detail.text = if (cores.isEmpty()) "Processor" else "${cores.size} CPU cores"
                chart.update(listOf(series.map { it.cpu?.toDouble() }), listOf(accent), 100.0)
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
                            addView(text(displayName(name), 14))
                            addView(text("$count process${if (count == 1) "" else "es"}", 11, muted, true))
                        }
                        line.addView(process, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
                        line.addView(text(formatBytes(kib * 1024), 16, bold = true))
                        processes.addView(line)
                    }
                }
            }
            MonitorPage.NETWORK -> {
                chart.update(traffic, listOf(accent, orange), null)
                facts.text = "Since boot     ↓ ${formatBytes(reading?.received)}    ↑ ${formatBytes(reading?.transmitted)}"
            }
        }
    }
}

private class Gauge(private val activity: BridgeActivity, private val tint: Int) : FrameLayout(activity) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val label = activity.text("—", 18, bold = true)

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
        val pad = activity.px(5).toFloat()
        val height = height - 2 * pad
        paint.color = activity.edge
        paint.strokeWidth = activity.px(1).toFloat()
        for (part in 0..2) {
            val y = pad + height * (part + 1) / 4
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
        for ((index, values) in lines.withIndex()) {
            val path = Path()
            var open = false
            for ((position, value) in values.withIndex()) {
                if (value == null) { open = false; continue }
                val x = width * (60 - values.size + position) / 59f
                val y = pad + height * (1 - (value / peak).coerceIn(0.0, 1.0)).toFloat()
                if (open) path.lineTo(x, y) else path.moveTo(x, y)
                open = true
            }
            paint.color = colors[index]
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = activity.px(2).toFloat()
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
