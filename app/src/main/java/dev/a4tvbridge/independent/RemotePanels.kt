package dev.a4tvbridge.independent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.min

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
    val header = row().apply { gravity = Gravity.CENTER }
    fun toolLayout(index: Int) = LinearLayout.LayoutParams(px(48), px(48)).apply {
        if (index > 0) marginStart = px(9)
    }
    header.addView(remoteIcon(Mark.KEYBOARD, "Keyboard", round = true) {
        ask("Send text to TV") { raw ->
            val cleaned = raw.trim()
            if (cleaned.isNotEmpty()) sendInput { sendText(cleaned) }
        }
    }, toolLayout(0))
    header.addView(remoteIcon(Mark.CAMERA, "Screenshot", round = true, action = ::saveScreenshot), toolLayout(1))
    header.addView(remoteIcon(Mark.APPS, "Apps", round = true) { show(Panel.APPS) }, toolLayout(2))
    content.addView(header, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply {
        bottomMargin = px(9)
    })
    val modes = row().apply {
        setPadding(px(4), px(4), px(4), px(4))
        background = card(tint, 28, false)
    }
    val touchSurface = RemotePad(this) { code -> sendInput { key(code) } }
    fun mode(label: String, touch: Boolean) = textButton(label) {
        usingTouch = touch
        touchSurface.touchMode = touch
        for (n in 0..1) {
            val item = modes.getChildAt(n) as android.widget.Button
            val active = (n == 1) == usingTouch
            item.isSelected = active
            item.background = if (active) card(surface, 24, false) else null
            item.setTextColor(if (active) accent else muted)
        }
    }
    modes.addView(mode("D-pad", false).apply {
        isSelected = true
        background = card(surface, 24, false)
        setTextColor(accent)
    }, LinearLayout.LayoutParams(0, px(40), 1f))
    modes.addView(mode("Touchpad", true).apply { setTextColor(muted) }, LinearLayout.LayoutParams(0, px(40), 1f))
    content.addView(modes, LinearLayout.LayoutParams(px(220), px(48)).apply {
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        bottomMargin = px(22)
    })
    content.addView(touchSurface, LinearLayout.LayoutParams(BridgeActivity.MATCH,
        px((resources.configuration.screenHeightDp * .43f).toInt().coerceIn(245, 425))).apply { bottomMargin = px(18) })
    remoteKeyRow(content, listOf(RemoteKey(Mark.BACK, "Back on TV", 4, "Back"),
        RemoteKey(Mark.HOME, "Home on TV", 3, "Home"), RemoteKey(Mark.MENU, "TV menu", 82, "Menu")))
    remoteKeyRow(content, listOf(RemoteKey(Mark.MINUS, "Volume down", 25), RemoteKey(Mark.PREVIOUS, "Previous", 88),
        RemoteKey(Mark.PLAY, "Play or pause", 85), RemoteKey(Mark.NEXT, "Next", 87), RemoteKey(Mark.ADD, "Volume up", 24)))
    val shortcuts = row()
    shortcuts.addView(textButton("Mute") { sendInput { key(164) } }.apply { labelIcon(this, Mark.MUTE) })
    shortcuts.addView(textButton("Screen Mirror") { show(Panel.MIRROR) }.apply { labelIcon(this, Mark.MIRROR) })
    content.addView(shortcuts)
    val favorites = pinnedPackages(tv).sorted()
    if (favorites.isNotEmpty()) {
        heading("Shortcuts", 19)
        tiles(content, 2, *favorites.map { packageName ->
            Tile(displayName(packageName), Mark.APPS) { tvTask({ openApp(packageName) }) { report("Opened on TV.") } }
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
        background = remoteKeySurface(if (round) 30 else corner)
        setOnClickListener { action() }
    }

private fun BridgeActivity.remoteKeySurface(radius: Int = corner) = android.graphics.drawable.RippleDrawable(
    android.content.res.ColorStateList.valueOf(ripple), card(tint, radius, outlined = false), null
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
    heading("Screen Mirror")
    var paused = false
    val state = text("Live view", 16, green, true)
    val mode = row()
    mode.addView(state, LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    val toggle = textButton("Pause mirror") {}
    toggle.setOnClickListener {
        paused = !paused
        state.text = if (paused) "Mirror paused" else "Live view"
        val label = if (paused) "Resume mirror" else "Pause mirror"
        toggle.text = label
        toggle.contentDescription = label
    }
    mode.addView(toggle)
    content.addView(mode, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { bottomMargin = px(11) })
    val screen = TouchPreview(this) { start, end, interval ->
        sendInput { gesture(start.first, start.second, end.first, end.second, interval) }
    }
    screen.background = card(0xFF101820.toInt(), 16, outlined = true)
    screen.clipToOutline = true
    val usableWidth = resources.configuration.screenWidthDp - 60
    content.addView(screen, LinearLayout.LayoutParams(BridgeActivity.MATCH, px((usableWidth * 9 / 16).coerceAtLeast(125))))
    paragraph("Tap, hold, or swipe the picture to control your TV.")
    val keys = outlinedCard()
    keys.addView(text("Remote controls", 16, bold = true))
    remoteKeyRow(keys, listOf(RemoteKey(Mark.UP, "Up", 19), RemoteKey(Mark.LEFT, "Left", 21),
        RemoteKey(null, "Select", 23, "OK"), RemoteKey(Mark.RIGHT, "Right", 22), RemoteKey(Mark.DOWN, "Down", 20)))
    remoteKeyRow(keys, listOf(RemoteKey(Mark.BACK, "Back on TV", 4), RemoteKey(Mark.HOME, "Home on TV", 3),
        RemoteKey(Mark.PLAY, "Play or pause", 85), RemoteKey(Mark.MINUS, "Volume down", 25), RemoteKey(Mark.ADD, "Volume up", 24)))
    content.addView(keys, LinearLayout.LayoutParams(BridgeActivity.MATCH, BridgeActivity.WRAP).apply { topMargin = px(8) })
    val capture = row()
    capture.addView(textButton("Take screenshot", action = ::saveScreenshot).apply { labelIcon(this, Mark.CAMERA) },
        LinearLayout.LayoutParams(0, BridgeActivity.WRAP, 1f))
    capture.addView(textButton("Record", action = ::saveRecording).apply { labelIcon(this, Mark.VIDEO) },
        LinearLayout.LayoutParams(BridgeActivity.WRAP, BridgeActivity.WRAP))
    content.addView(capture)
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
    private val arrows = listOf(Mark.UP, Mark.RIGHT, Mark.DOWN, Mark.LEFT).map { BridgeIcon(it, Color.WHITE) }
    private var startX = 0f
    private var startY = 0f
    var touchMode = false
        set(value) { field = value; invalidate() }

    init {
        val colors = if (activity.dark) intArrayOf(0xFF0E2230.toInt(), 0xFF15324A.toInt())
        else intArrayOf(0xFF00587B.toInt(), 0xFF0C6B94.toInt())
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            colors
        ).apply {
            cornerRadius = activity.px(32).toFloat()
            if (activity.dark) setStroke(activity.px(1), activity.edge)
        }
        contentDescription = "Directional pad"
        isFocusable = true
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(centerX, centerY) * .92f
        brush.textAlign = Paint.Align.CENTER
        if (touchMode) {
            brush.color = android.graphics.Color.WHITE
            brush.textSize = activity.px(17).toFloat()
            canvas.drawText("Swipe to move · Tap to select", centerX, centerY, brush)
            return
        }
        val ring = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        brush.color = 0x28FFFFFF
        for (side in 0..3) canvas.drawArc(ring, side * 90f - 40f, 80f, true, brush)
        brush.color = activity.surface
        canvas.drawCircle(centerX, centerY, radius * .4f, brush)
        val gap = radius * .67f
        val halfIcon = activity.px(12)
        val centers = listOf(centerX to centerY - gap, centerX + gap to centerY,
            centerX to centerY + gap, centerX - gap to centerY)
        for ((icon, center) in arrows.zip(centers)) {
            val x = center.first.roundToInt()
            val y = center.second.roundToInt()
            icon.setBounds(x - halfIcon, y - halfIcon, x + halfIcon, y + halfIcon)
            icon.draw(canvas)
        }
        brush.color = activity.accent
        brush.textSize = activity.px(19).toFloat()
        canvas.drawText("OK", centerX, centerY + brush.textSize / 3, brush)
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
    activity: BridgeActivity,
    val send: (Pair<Int, Int>, Pair<Int, Int>, Long) -> Unit
) : View(activity) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val region = RectF()
    private var start: Pair<Int, Int>? = null
    private var startTime = 0L
    var frame: Bitmap? = null
        set(value) { field = value; invalidate() }

    init {
        setBackgroundColor(0xFF101820.toInt())
        contentDescription = "Live TV preview"
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val image = frame ?: return
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
