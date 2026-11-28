package dev.a4tvbridge.independent

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

internal fun BridgeActivity.pinnedPackages(tv: TvConnection): Set<String> {
    val key = "pinned-${tv.serial}"
    preferences.getStringSet(key, null)?.let { return it }
    val safe = tv.serial.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
    if (safe in setOf("", ".", "..")) return emptySet()
    val file = File(filesDir, "tvs/$safe/pinned.txt")
    if (!file.isFile || file.length() > 64_000) return emptySet()
    return file.readLines().mapNotNull { runCatching { packageId(it.trim()) }.getOrNull() }.toSet()
}

internal fun BridgeActivity.setPinned(tv: TvConnection, id: String, enabled: Boolean) {
    val old = pinnedPackages(tv)
    preferences.edit().putStringSet("pinned-${tv.serial}", if (enabled) old + id else old - id).apply()
}

internal fun BridgeActivity.remote() {
    val tv = connection ?: return
    var usingTouch = false

    pageHeader("Remote Control", "${tvTitle() ?: tv.model} · Non-blocking key queue")

    // Full-width segmented D-Pad / Touchpad bar (48dp touch height)
    val modes = row().apply {
        setPadding(px(4), px(4), px(4), px(4))
        background = card(field, 15, outlined = true)
    }
    val touchSurface = RemotePad(this) { code -> sendInput { key(code) } }
    fun styleMode(btn: android.widget.Button, active: Boolean) {
        btn.isSelected = active
        btn.background = if (active) {
            card(accentPill, 11, outlined = false).apply {
                if (dark) setStroke(px(1), accent)
            }
        } else null
        btn.setTextColor(if (active) Color.WHITE else dim)
    }
    fun mode(label: String, touch: Boolean) = textButton(label) {
        usingTouch = touch
        touchSurface.touchMode = touch
        for (n in 0..1) {
            val item = modes.getChildAt(n) as android.widget.Button
            styleMode(item, (n == 1) == usingTouch)
        }
    }.apply {
        textSize = 14f
        minHeight = px(40)
        minimumHeight = px(40)
    }
    modes.addView(mode("D-Pad", false).also { styleMode(it, true) }, LinearLayout.LayoutParams(0, px(40), 1f))
    modes.addView(mode("Touchpad", true).also { styleMode(it, false) }, LinearLayout.LayoutParams(0, px(40), 1f))
    content.addView(modes, LinearLayout.LayoutParams(BridgeActivity.MATCH, px(48)).apply { bottomMargin = px(12) })

    // 4-item Capture & Mirror Action Bar (Mirror, Screenshot, Record, Power)
    val captureBar = row()
    val captureItems = listOf(
        Triple("Mirror", accentBright) { show(Panel.MIRROR) },
        Triple("Screenshot", green) { saveScreenshot() },
        Triple("Record", rose) { saveRecording() },
        Triple("Power", orange) { sendInput { key(26) } }
    )
    captureItems.forEachIndexed { idx, (lbl, dotColor, action) ->
        val chip = column().apply {
            gravity = Gravity.CENTER
            minimumHeight = px(52)
            background = interactiveBackground(card(field, 14, outlined = true), radius = 14)
            setPadding(px(6), px(10), px(6), px(10))
            val dot = View(this@remote).apply { background = rounded(dotColor, 8) }
            addView(dot, LinearLayout.LayoutParams(px(8), px(8)).apply { bottomMargin = px(6) })
            addView(text(lbl, 12, ink, bold = true).apply { isSingleLine = true })
            isClickable = true
            isFocusable = true
            contentDescription = lbl
            setOnClickListener { action() }
        }
        captureBar.addView(chip, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply {
            if (idx > 0) marginStart = px(8)
        })
    }
    content.addView(captureBar, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(14) })

    // Sculpted Circular D-Pad / Touchpad
    val padHeight = px((resources.configuration.screenWidthDp - 44).coerceIn(250, 320))
    content.addView(touchSurface, LinearLayout.LayoutParams(BridgeActivity.MATCH, padHeight).apply { bottomMargin = px(14) })

    // Nav Row: Back | Home | Menu
    val navRow = row()
    val navs = listOf(Triple("Back", "Back on TV", 4), Triple("Home", "Home on TV", 3), Triple("Menu", "TV menu", 82))
    navs.forEachIndexed { idx, (lbl, desc, code) ->
        val navBtn = button(lbl, desc) { sendInput { key(code) } }.apply {
            background = interactiveBackground(card(field, 15, outlined = true), radius = 15)
            setTextColor(ink)
        }
        navRow.addView(navBtn, LinearLayout.LayoutParams(0, px(50), 1f).apply {
            if (idx > 0) marginStart = px(8)
        })
    }
    content.addView(navRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

    // Volume & Media Split Cards
    val controlPair = row()
    fun splitControlCard(title: String, keys: List<Triple<String, String, Pair<Int, Boolean>>>): LinearLayout =
        outlinedCard(field, radius = 16, stroke = 1).apply {
            setPadding(px(10), px(10), px(10), px(10))
            addView(text(title, 11, dim, bold = true).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.08f
                setPadding(0, 0, 0, px(8))
            }, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
            val btnRow = row()
            keys.forEachIndexed { i, (lbl, desc, meta) ->
                val (code, hi) = meta
                val keyBg = if (hi) {
                    card(if (dark) 0xFF082F49.toInt() else accentPill, 12, outlined = false).apply {
                        setStroke(px(1), accent)
                    }
                } else {
                    card(surfaceElevated, 12, outlined = true)
                }
                val b = button(lbl, desc) { sendInput { key(code) } }.apply {
                    background = interactiveBackground(keyBg, focusFill = if (hi) accentPill else tint, radius = 12)
                    setTextColor(if (hi) Color.WHITE else ink)
                    textSize = if (lbl.length == 1) 20f else 12f
                    setPadding(px(2), 0, px(2), 0)
                    minHeight = px(54)
                    minimumHeight = px(54)
                }
                btnRow.addView(b, LinearLayout.LayoutParams(0, px(54), 1f).apply {
                    if (i > 0) marginStart = px(6)
                })
            }
            addView(btnRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
        }

    val volCard = splitControlCard("VOLUME", listOf(
        Triple("−", "Volume down", 25 to false),
        Triple("Mute", "Mute", 164 to false),
        Triple("+", "Volume up", 24 to false)
    ))
    val mediaCard = splitControlCard("MEDIA", listOf(
        Triple("◄◄", "Previous", 88 to false),
        Triple("▶ II", "Play or pause", 85 to true),
        Triple("►►", "Next", 87 to false)
    ))
    controlPair.addView(volCard, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    controlPair.addView(mediaCard, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f).apply { marginStart = px(10) })
    content.addView(controlPair, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

    // Inline SEND TEXT TO TV KEYBOARD Card (preserves unsent draft text + 48dp touch height)
    val textCard = outlinedCard(field, radius = 16, stroke = 1)
    textCard.addView(text("SEND TEXT TO TV KEYBOARD", 11, dim, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(0, 0, 0, px(8))
    })
    val inputRow = row()
    val textField = input("Type search query or URL…").apply {
        setSingleLine()
        setText(remoteDraftText)
        setSelection(text.length)
        textSize = 14f
        backgroundTintList = null
        background = card(surfaceElevated, 12, outlined = true)
        setPadding(px(12), px(10), px(12), px(10))
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { remoteDraftText = s.toString() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }
    val sendBtn = button("Send", emphasis = true) {
        val cleaned = textField.text.toString().trim()
        if (cleaned.isNotEmpty()) {
            sendInput { sendText(cleaned) }
            remoteDraftText = ""
            textField.setText("")
            report("Text sent to TV.", NoticeTone.SUCCESS)
        }
    }.apply {
        minHeight = px(48)
        minimumHeight = px(48)
        setPadding(px(18), 0, px(18), 0)
    }
    inputRow.addView(textField, LinearLayout.LayoutParams(0, px(48), 1f))
    inputRow.addView(sendBtn, LinearLayout.LayoutParams(BridgeActivity.WRAP, px(48)).apply { marginStart = px(8) })
    textCard.addView(inputRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))
    textCard.addView(text("Instant ADB input with Space, Enter & Backspace", 11, muted).apply {
        setPadding(0, px(8), 0, 0)
    })
    content.addView(textCard, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(10) })

    // Pinned TV App Shortcuts if any
    val favorites = pinnedPackages(tv).sorted()
    if (favorites.isNotEmpty()) {
        content.addView(sectionLabel("Pinned TV shortcuts"),
            LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(4); bottomMargin = px(8) })
        tiles(content, 2, *favorites.map { packageName ->
            Tile(displayName(packageName), Mark.APPS) { tvTask({ openApp(packageName) }) { report("Opened on TV.", NoticeTone.SUCCESS) } }
        }.toTypedArray())
    }
}

private data class RemoteKey(val mark: Mark?, val name: String, val code: Int, val label: String = "")

private fun BridgeActivity.keyLayout(index: Int) = LinearLayout.LayoutParams(0, px(48), 1f).apply {
    if (index > 0) marginStart = px(7)
}

private fun BridgeActivity.remoteIcon(mark: Mark, label: String, round: Boolean = false, action: () -> Unit) =
    ImageButton(this).apply {
        contentDescription = label
        setImageDrawable(BridgeIcon(mark, accent, px(21)))
        scaleType = ImageView.ScaleType.CENTER
        background = remoteKeySurface(if (round) 30 else 14)
        setOnClickListener { action() }
    }

private fun BridgeActivity.remoteKeySurface(radius: Int = 14) = RippleDrawable(
    ColorStateList.valueOf(ripple), card(tint, radius, outlined = true), null
)

private fun BridgeActivity.remoteKeyRow(parent: LinearLayout, keys: List<RemoteKey>) {
    val line = row()
    for ((index, key) in keys.withIndex()) {
        val send = { sendInput { key(key.code) } }
        val view = if (key.label.isEmpty() && key.mark != null) remoteIcon(key.mark, key.name, action = send)
        else button(key.label, key.name, action = send).apply {
            setTextColor(accent)
            textSize = 14f
            background = remoteKeySurface()
            key.mark?.let { labelIcon(this, it) }
        }
        line.addView(view, keyLayout(index))
    }
    parent.addView(line, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(8) })
}

internal fun BridgeActivity.mirror() {
    val tv = connection ?: return
    pageHeader("Screen Mirror", "Tap, hold, or swipe the live TV frame to control your TV")
    var paused = false

    val liveDot = View(this).apply { background = rounded(greenStroke, 8) }
    val state = text("Live view", 12, successInk, true)
    val statePill = row().apply {
        background = card(greenBg, 20, outlined = false).apply { setStroke(px(1), greenStroke) }
        setPadding(px(11), px(6), px(12), px(6))
        addView(liveDot, LinearLayout.LayoutParams(px(7), px(7)).apply { marginEnd = px(6) })
        addView(state)
    }
    val mode = row()
    mode.addView(statePill, LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP))
    mode.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    val toggle = button("Pause") {}.apply {
        minHeight = px(48)
        minimumHeight = px(48)
        textSize = 13f
        setPadding(px(16), 0, px(16), 0)
    }
    toggle.setOnClickListener {
        paused = !paused
        state.text = if (paused) "Paused" else "Live view"
        liveDot.background = rounded(if (paused) orange else greenStroke, 8)
        statePill.background = card(if (paused) warningBg else greenBg, 20, outlined = false).apply {
            setStroke(px(1), if (paused) orange else greenStroke)
        }
        state.setTextColor(if (paused) orange else successInk)
        val label = if (paused) "Resume" else "Pause"
        toggle.text = label
        toggle.contentDescription = "$label mirror"
    }
    mode.addView(toggle)
    content.addView(mode, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

    val screen = TouchPreview(this) { start, end, interval ->
        sendInput { gesture(start.first, start.second, end.first, end.second, interval) }
    }
    screen.background = card(0xFF0D1219.toInt(), 18, outlined = true)
    screen.clipToOutline = true
    val usableWidth = (resources.configuration.screenWidthDp - 40).coerceAtMost(600)
    content.addView(screen, LinearLayout.LayoutParams(BridgeActivity.MATCH, px((usableWidth * 9 / 16).coerceAtLeast(160))).apply {
        bottomMargin = px(12)
    })

    // Styled Capture Action Pills (matching Remote screen)
    val captureRow = row()
    fun capturePill(label: String, dotColor: Int, action: () -> Unit): View = row().apply {
        gravity = Gravity.CENTER
        minimumHeight = px(48)
        background = interactiveBackground(card(field, 14, outlined = true), radius = 14)
        setPadding(px(14), px(10), px(14), px(10))
        addView(View(this@mirror).apply { background = rounded(dotColor, 8) },
            LinearLayout.LayoutParams(px(8), px(8)).apply { marginEnd = px(8) })
        addView(text(label, 13, ink, bold = true))
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { action() }
    }
    captureRow.addView(capturePill("Take Screenshot", green, ::saveScreenshot), LinearLayout.LayoutParams(0, px(48), 1f))
    captureRow.addView(capturePill("Record Video", rose, ::saveRecording), LinearLayout.LayoutParams(0, px(48), 1f).apply { marginStart = px(10) })
    content.addView(captureRow, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(12) })

    val keys = outlinedCard(field, radius = 18, stroke = 1)
    keys.addView(text("REMOTE CONTROLS", 11, dim, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(0, 0, 0, px(10))
    })
    remoteKeyRow(keys, listOf(RemoteKey(Mark.UP, "Up", 19), RemoteKey(Mark.LEFT, "Left", 21),
        RemoteKey(null, "Select", 23, "OK"), RemoteKey(Mark.RIGHT, "Right", 22), RemoteKey(Mark.DOWN, "Down", 20)))
    remoteKeyRow(keys, listOf(RemoteKey(Mark.BACK, "Back on TV", 4), RemoteKey(Mark.HOME, "Home on TV", 3),
        RemoteKey(Mark.PLAY, "Play or pause", 85), RemoteKey(Mark.MINUS, "Volume down", 25), RemoteKey(Mark.ADD, "Volume up", 24)))
    content.addView(keys, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP))

    watch {
        if (!paused) {
            val image = withContext(Dispatchers.IO) {
                val data = tv.screenshot()
                BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("The TV returned an unreadable frame.")
            }
            screen.frame = image
        }
    }
}

private class RemotePad(
    private val activity: BridgeActivity,
    private val send: (Int) -> Unit
) : View(activity) {
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    private var startX = 0f
    private var startY = 0f
    var touchMode = false
        set(value) { field = value; invalidate() }

    init {
        contentDescription = "Directional pad"
        isFocusable = true
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (touchMode) {
            val padRect = RectF(activity.px(4).toFloat(), activity.px(4).toFloat(), width - activity.px(4).toFloat(), height - activity.px(4).toFloat())
            val r = activity.px(24).toFloat()
            brush.style = Paint.Style.FILL
            brush.color = if (activity.dark) 0xFF0C1420.toInt() else Color.WHITE
            canvas.drawRoundRect(padRect, r, r, brush)
            brush.style = Paint.Style.STROKE
            brush.strokeWidth = activity.px(2).toFloat()
            brush.color = if (activity.dark) 0xFF1E3A5F.toInt() else activity.edge
            canvas.drawRoundRect(padRect, r, r, brush)
            brush.style = Paint.Style.FILL
            brush.color = if (activity.dark) 0xFFB0DEFF.toInt() else activity.accent
            brush.textAlign = Paint.Align.CENTER
            brush.textSize = activity.px(16).toFloat()
            brush.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            canvas.drawText("Swipe to move · Tap to select", cx, cy + brush.textSize / 3f, brush)
            return
        }

        val radius = min(cx, cy) * 0.94f
        val innerRadius = radius * 0.91f
        val centerRadius = radius * 0.34f

        brush.style = Paint.Style.FILL
        brush.color = if (activity.dark) 0xFF0C1420.toInt() else Color.WHITE
        canvas.drawCircle(cx, cy, radius, brush)
        brush.style = Paint.Style.STROKE
        brush.strokeWidth = activity.px(2).toFloat()
        brush.color = if (activity.dark) 0xFF1E3A5F.toInt() else activity.edge
        canvas.drawCircle(cx, cy, radius, brush)

        brush.style = Paint.Style.FILL
        brush.color = if (activity.dark) 0xFF0F1926.toInt() else 0xFFF8FAFC.toInt()
        canvas.drawCircle(cx, cy, innerRadius, brush)
        brush.style = Paint.Style.STROKE
        brush.strokeWidth = activity.px(1).toFloat()
        brush.color = activity.edge
        canvas.drawCircle(cx, cy, innerRadius, brush)

        val d1 = radius * 0.65f
        val d2 = centerRadius * 1.15f
        canvas.drawLine(cx - d1, cy - d1, cx - d2, cy - d2, brush)
        canvas.drawLine(cx + d1, cy - d1, cx + d2, cy - d2, brush)
        canvas.drawLine(cx - d1, cy + d1, cx - d2, cy + d2, brush)
        canvas.drawLine(cx + d1, cy + d1, cx + d2, cy + d2, brush)

        brush.style = Paint.Style.FILL
        brush.color = if (activity.dark) 0xFFB0DEFF.toInt() else activity.accent
        val arrowDist = radius * 0.66f
        val sz = activity.px(9).toFloat()
        fun drawTri(px0: Float, py0: Float, px1: Float, py1: Float, px2: Float, py2: Float) {
            val p = Path().apply { moveTo(px0, py0); lineTo(px1, py1); lineTo(px2, py2); close() }
            canvas.drawPath(p, brush)
        }
        drawTri(cx, cy - arrowDist - sz, cx - sz, cy - arrowDist + sz * 0.7f, cx + sz, cy - arrowDist + sz * 0.7f)
        drawTri(cx, cy + arrowDist + sz, cx - sz, cy + arrowDist - sz * 0.7f, cx + sz, cy + arrowDist - sz * 0.7f)
        drawTri(cx - arrowDist - sz, cy, cx - arrowDist + sz * 0.7f, cy - sz, cx - arrowDist + sz * 0.7f, cy + sz)
        drawTri(cx + arrowDist + sz, cy, cx + arrowDist - sz * 0.7f, cy - sz, cx + arrowDist - sz * 0.7f, cy + sz)

        brush.style = Paint.Style.FILL
        brush.color = activity.accentPill
        canvas.drawCircle(cx, cy, centerRadius, brush)
        brush.style = Paint.Style.STROKE
        brush.strokeWidth = activity.px(2).toFloat()
        brush.color = activity.accentBright
        canvas.drawCircle(cx, cy, centerRadius, brush)

        brush.style = Paint.Style.FILL
        brush.color = Color.WHITE
        brush.textAlign = Paint.Align.CENTER
        brush.typeface = Typeface.DEFAULT_BOLD
        brush.textSize = activity.px(19).toFloat()
        canvas.drawText("OK", cx, cy + brush.textSize * 0.35f, brush)
    }

    override fun performClick(): Boolean {
        super.performClick()
        send(23)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                startX = event.x
                startY = event.y
            }
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                val x = event.x - if (touchMode) startX else width / 2f
                val y = event.y - if (touchMode) startY else height / 2f
                val middle = if (touchMode) activity.px(18).toFloat() else min(width, height) * .18f
                val code = when {
                    x * x + y * y <= middle * middle -> 23
                    kotlin.math.abs(x) > kotlin.math.abs(y) -> if (x < 0) 21 else 22
                    else -> if (y < 0) 19 else 20
                }
                if (code == 23) performClick() else send(code)
            }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}

private class TouchPreview(
    private val activity: BridgeActivity,
    val send: (Pair<Int, Int>, Pair<Int, Int>, Long) -> Unit
) : View(activity) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val region = RectF()
    private var start: Pair<Int, Int>? = null
    private var startTime = 0L
    var frame: Bitmap? = null
        set(value) { field = value; invalidate() }

    init {
        setBackgroundColor(0xFF0D1219.toInt())
        contentDescription = "Live TV preview"
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val image = frame
        if (image == null) {
            paint.color = 0xFF94A3B8.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = activity.px(14).toFloat()
            paint.typeface = activity.monoTypeface
            canvas.drawText("Waiting for first TV frame…", width / 2f, height / 2f + paint.textSize / 3f, paint)
            return
        }
        val ratio = min(width.toFloat() / image.width, height.toFloat() / image.height)
        val left = (width - image.width * ratio) / 2f
        val top = (height - image.height * ratio) / 2f
        region.set(left, top, width - left, height - top)
        canvas.drawBitmap(image, null, region, paint)
    }

    private fun coordinates(x: Float, y: Float, contained: Boolean): Pair<Int, Int>? {
        val image = frame ?: return null
        if (region.isEmpty || (contained && !region.contains(x, y))) return null
        return (image.width * (x - region.left) / region.width()).toInt().coerceIn(0, image.width - 1) to
            (image.height * (y - region.top) / region.height()).toInt().coerceIn(0, image.height - 1)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start = coordinates(event.x, event.y, contained = true)
                if (start == null) return false
                startTime = event.eventTime
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                val a = start ?: return false
                val b = coordinates(event.x, event.y, contained = false) ?: return false
                send(a, b, event.eventTime - startTime)
                start = null
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                start = null
            }
        }
        return true
    }
}

internal fun BridgeActivity.saveScreenshot() {
    tvTask({
        val file = File.createTempFile("screen", ".png", cacheDir)
        try {
            file.writeBytes(screenshot())
            file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }) { file -> saveDocument(file, "A4 TV Bridge ${System.currentTimeMillis()}.png", "image/png") }
}

internal fun BridgeActivity.saveRecording() {
    choose("Record TV screen", listOf(5, 15, 30).map { seconds ->
        "$seconds seconds" to {
            tvTask({
                val file = File.createTempFile("screen", ".mp4", cacheDir)
                try {
                    record(seconds, file)
                    file
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
            }) { file -> saveDocument(file, "A4 TV Bridge ${System.currentTimeMillis()}.mp4", "video/mp4") }
        }
    })
}

internal fun formatBytes(bytes: Long?): String {
    val amount = bytes ?: return "—"
    return when {
        amount >= (1L shl 30) -> String.format(java.util.Locale.getDefault(), "%.1f GB", amount / 1073741824.0)
        amount >= (1L shl 20) -> String.format(java.util.Locale.getDefault(), "%.1f MB", amount / 1048576.0)
        amount >= (1L shl 10) -> "${(amount / 1024.0).roundToInt()} KB"
        else -> "$amount B"
    }
}
