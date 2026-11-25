package dev.a4tvbridge.independent

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.widget.Button

internal enum class Mark {
    TV, CLEAN, ADD, DOCUMENT, MORE, EXTERNAL, DASHBOARD, APPS, CONTROL, REMOTE, MIRROR, SHELL,
    CARE, FOLDER, UPLOAD, DOWNLOAD, CAMERA, VIDEO, INFO, SETTINGS, SHIELD, HOME, RESTORE, CHECK, STORAGE,
    BACK, CLOSE, KEYBOARD, MENU, MUTE, PLAY, PREVIOUS, NEXT, MINUS, UP, LEFT, RIGHT, DOWN
}

internal class Glyph(activity: BridgeActivity, mark: Mark, color: Int, sizeDp: Int = 22) : View(activity) {
    private val size = activity.px(sizeDp)
    private val icon = BridgeIcon(mark, color)

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val side = minOf(width, height, size)
        val x = (width - side) / 2
        val y = (height - side) / 2
        icon.setBounds(x, y, x + side, y + side)
    }

    override fun onDraw(canvas: Canvas) = icon.draw(canvas)
}

internal fun BridgeActivity.labelIcon(button: Button, mark: Mark, color: Int = accent) {
    val side = px(19)
    val spacing = px(5)
    val icon = BridgeIcon(mark, color).apply { setBounds(0, 0, side, side) }
    val leadingIcon = object : ReplacementSpan() {
        override fun getSize(
            paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
        ) = side + spacing

        override fun draw(
            canvas: Canvas, text: CharSequence, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            val line = paint.fontMetrics
            canvas.save()
            canvas.translate(x, y + (line.ascent + line.descent - side) / 2f)
            icon.draw(canvas)
            canvas.restore()
        }
    }
    button.text = SpannableStringBuilder("\uFFFC").append(button.text).apply {
        setSpan(leadingIcon, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

internal class BridgeIcon(private val mark: Mark, color: Int, private val intrinsicSize: Int = -1) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val stroke = Paint(fill).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun getIntrinsicWidth() = intrinsicSize
    override fun getIntrinsicHeight() = intrinsicSize

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        drawSymbol(canvas)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        stroke.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Android no longer uses drawable opacity for rendering optimizations")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun drawSymbol(canvas: Canvas) {
        fun box(x: Float, y: Float, right: Float, bottom: Float, radius: Float, paint: Paint = fill) =
            canvas.drawRoundRect(x, y, right, bottom, radius, radius, paint)
        fun dot(x: Float, y: Float, radius: Float) = canvas.drawCircle(x, y, radius, fill)
        fun line(x: Float, y: Float, endX: Float, endY: Float) = canvas.drawLine(x, y, endX, endY, stroke)
        fun arc(x: Float, y: Float, radius: Float, start: Float, sweep: Float) =
            canvas.drawArc(x - radius, y - radius, x + radius, y + radius, start, sweep, false, stroke)
        fun shape(paint: Paint = fill, build: Path.() -> Unit) = canvas.drawPath(Path().apply(build), paint)
        fun wave(x: Float, y: Float, inner: Float, outer: Float) {
            dot(x, y, 1.2f)
            arc(x, y, inner, -88f, 86f)
            arc(x, y, outer, -88f, 86f)
        }
        fun triangle(left: Float, center: Float, right: Float, halfHeight: Float) = shape {
            moveTo(left, center - halfHeight)
            lineTo(right, center)
            lineTo(left, center + halfHeight)
            close()
        }
        when (mark) {
            Mark.DASHBOARD -> {
                box(3f, 3f, 11f, 12.5f, .8f)
                box(13f, 3f, 21f, 9f, .8f)
                box(3f, 14.5f, 11f, 21f, .8f)
                box(13f, 11f, 21f, 21f, .8f)
            }
            Mark.APPS -> for (x in 0..2) for (y in 0..2) {
                val left = 3.5f + x * 6f
                val top = 3.5f + y * 6f
                box(left, top, left + 4f, top + 4f, .35f)
            }
            Mark.CONTROL -> {
                shape(stroke) {
                    moveTo(3f, 14f)
                    lineTo(3f, 4f)
                    quadTo(3f, 2.5f, 4.5f, 2.5f)
                    lineTo(17f, 2.5f)
                    quadTo(18.5f, 2.5f, 18.5f, 4f)
                    lineTo(18.5f, 6f)
                    moveTo(3f, 14.5f)
                    lineTo(13f, 14.5f)
                    moveTo(10.5f, 14.5f)
                    lineTo(10.5f, 18f)
                    moveTo(7f, 18f)
                    lineTo(13f, 18f)
                }
                wave(6.1f, 11.8f, 2.7f, 5f)
                box(16.4f, 9f, 22f, 22f, 1.5f, stroke)
                dot(19.2f, 13f, 1.05f)
                line(18.7f, 18.2f, 19.7f, 18.2f)
            }
            Mark.MIRROR -> {
                shape(stroke) {
                    moveTo(2f, 8f)
                    lineTo(2f, 3.5f)
                    lineTo(22f, 3.5f)
                    lineTo(22f, 20f)
                    lineTo(14.5f, 20f)
                }
                wave(3f, 19f, 4.7f, 9f)
            }
            Mark.REMOTE -> {
                arc(12f, 13f, 11.5f, 235f, 70f)
                arc(12f, 13f, 7.8f, 235f, 70f)
                shape {
                    fillType = Path.FillType.EVEN_ODD
                    addRoundRect(7.8f, 9.5f, 16.2f, 23f, 1.1f, 1.1f, Path.Direction.CW)
                    addCircle(12f, 13f, 1.2f, Path.Direction.CW)
                }
            }
            Mark.SHELL -> {
                box(2f, 4.5f, 22f, 19.5f, 1.2f, stroke)
                box(2f, 4.5f, 22f, 8f, .8f)
                shape(stroke) {
                    moveTo(6.5f, 10.5f)
                    lineTo(9.4f, 13.4f)
                    lineTo(6.5f, 16.3f)
                }
                line(12.5f, 16.3f, 17.3f, 16.3f)
            }
            Mark.TV -> {
                box(2f, 3.5f, 22f, 17.5f, 1.3f, stroke)
                box(7f, 18f, 17f, 21f, .7f)
            }
            Mark.CLEAN -> {
                box(9f, 1.5f, 15f, 13f, 2.8f)
                box(3f, 12f, 21f, 22f, 3f, stroke)
                for (x in listOf(7.5f, 12f, 16.5f)) line(x, 17f, x, 21.7f)
            }
            Mark.SETTINGS -> {
                shape {
                    fillType = Path.FillType.EVEN_ODD
                    addCircle(12f, 12f, 8.2f, Path.Direction.CW)
                    addCircle(12f, 12f, 3.4f, Path.Direction.CW)
                }
                for (tooth in 0 until 8) {
                    canvas.save()
                    canvas.rotate(tooth * 45f, 12f, 12f)
                    box(9.8f, 1.6f, 14.2f, 5.4f, .65f)
                    canvas.restore()
                }
            }
            Mark.CARE -> for ((index, y) in listOf(5f, 12f, 19f).withIndex()) {
                val knob = if (index == 1) 7.5f else if (index == 0) 16f else 12f
                line(3f, y, knob - 3f, y)
                line(knob + 3f, y, 21f, y)
                line(knob, y - 2.4f, knob, y + 2.4f)
            }
            Mark.FOLDER -> shape {
                moveTo(2.5f, 4f)
                lineTo(10f, 4f)
                lineTo(12f, 6.2f)
                lineTo(21f, 6.2f)
                quadTo(22.5f, 6.2f, 22.5f, 7.7f)
                lineTo(22.5f, 19f)
                quadTo(22.5f, 20.6f, 21f, 20.6f)
                lineTo(2.5f, 20.6f)
                quadTo(1.5f, 20.6f, 1.5f, 19f)
                lineTo(1.5f, 5.5f)
                quadTo(1.5f, 4f, 2.5f, 4f)
                close()
            }
            Mark.ADD -> { line(4.5f, 12f, 19.5f, 12f); line(12f, 4.5f, 12f, 19.5f) }
            Mark.MINUS -> line(5f, 12f, 19f, 12f)
            Mark.MORE -> for (y in listOf(4.5f, 12f, 19.5f)) dot(12f, y, 1.8f)
            Mark.DOCUMENT -> shape {
                fillType = Path.FillType.EVEN_ODD
                moveTo(6f, 2.5f)
                lineTo(14.3f, 2.5f)
                lineTo(20f, 8.2f)
                lineTo(20f, 20f)
                quadTo(20f, 21.5f, 18.5f, 21.5f)
                lineTo(6f, 21.5f)
                quadTo(4.5f, 21.5f, 4.5f, 20f)
                lineTo(4.5f, 4f)
                quadTo(4.5f, 2.5f, 6f, 2.5f)
                close()
                moveTo(14f, 4.6f)
                lineTo(17.7f, 8.5f)
                lineTo(14f, 8.5f)
                close()
                addRect(8f, 12f, 16f, 13.7f, Path.Direction.CW)
                addRect(8f, 16f, 16f, 17.7f, Path.Direction.CW)
            }
            Mark.EXTERNAL -> {
                shape(stroke) {
                    moveTo(11f, 4f)
                    lineTo(4f, 4f)
                    lineTo(4f, 20f)
                    lineTo(20f, 20f)
                    lineTo(20f, 13f)
                    moveTo(14f, 4f)
                    lineTo(20f, 4f)
                    lineTo(20f, 10f)
                }
                line(20f, 4f, 10f, 14f)
            }
            Mark.UPLOAD, Mark.DOWNLOAD -> {
                canvas.save()
                if (mark == Mark.DOWNLOAD) canvas.rotate(180f, 12f, 10.5f)
                shape {
                    moveTo(5f, 10.5f)
                    lineTo(12f, 3f)
                    lineTo(19f, 10.5f)
                    lineTo(15.5f, 10.5f)
                    lineTo(15.5f, 18f)
                    lineTo(8.5f, 18f)
                    lineTo(8.5f, 10.5f)
                    close()
                }
                canvas.restore()
                box(4f, 20f, 20f, 22f, .55f)
            }
            Mark.CAMERA -> shape {
                fillType = Path.FillType.EVEN_ODD
                addRoundRect(1.5f, 6f, 22.5f, 20.8f, 1.7f, 1.7f, Path.Direction.CW)
                moveTo(7f, 6f)
                lineTo(8.5f, 3.7f)
                lineTo(15.5f, 3.7f)
                lineTo(17f, 6f)
                close()
                addCircle(12f, 13.4f, 5f, Path.Direction.CW)
                addCircle(12f, 13.4f, 3.15f, Path.Direction.CW)
            }
            Mark.VIDEO -> {
                box(2f, 5.7f, 16.5f, 18.3f, 1.35f)
                shape {
                    moveTo(16f, 10.2f)
                    lineTo(22f, 7f)
                    lineTo(22f, 17f)
                    lineTo(16f, 13.8f)
                    close()
                }
            }
            Mark.INFO -> shape {
                fillType = Path.FillType.EVEN_ODD
                addCircle(12f, 12f, 10f, Path.Direction.CW)
                addCircle(12f, 7.1f, 1.2f, Path.Direction.CW)
                addRoundRect(11f, 10.2f, 13f, 17.5f, 1f, 1f, Path.Direction.CW)
            }
            Mark.SHIELD -> shape {
                moveTo(12f, 2f)
                lineTo(20f, 5.4f)
                lineTo(20f, 11.7f)
                quadTo(20f, 18f, 12f, 22.5f)
                quadTo(4f, 18f, 4f, 11.7f)
                lineTo(4f, 5.4f)
                close()
            }
            Mark.HOME -> shape {
                moveTo(1.5f, 11.3f)
                lineTo(12f, 2.5f)
                lineTo(22.5f, 11.3f)
                lineTo(19.5f, 11.3f)
                lineTo(19.5f, 21f)
                lineTo(14.1f, 21f)
                lineTo(14.1f, 14.8f)
                lineTo(9.9f, 14.8f)
                lineTo(9.9f, 21f)
                lineTo(4.5f, 21f)
                lineTo(4.5f, 11.3f)
                close()
            }
            Mark.RESTORE -> {
                arc(12.5f, 12f, 8.6f, 185f, 305f)
                shape { moveTo(.8f, 11.8f); lineTo(7.2f, 11.8f); lineTo(4f, 15.5f); close() }
                line(12.5f, 7.5f, 12.5f, 12f)
                line(12.5f, 12f, 16.7f, 14.5f)
            }
            Mark.CHECK -> {
                arc(12f, 12f, 9.2f, -12f, 304f)
                shape(stroke) { moveTo(7.5f, 11.7f); lineTo(11.2f, 15f); lineTo(21.1f, 5.5f) }
            }
            Mark.STORAGE -> shape {
                fillType = Path.FillType.EVEN_ODD
                for (y in listOf(4f, 10.15f, 16.3f)) {
                    addRoundRect(1.8f, y, 22.2f, y + 3.9f, 1.4f, 1.4f, Path.Direction.CW)
                    addCircle(5.1f, y + 1.95f, .95f, Path.Direction.CW)
                }
            }
            Mark.BACK -> {
                line(4f, 12f, 20f, 12f)
                shape(stroke) { moveTo(10.8f, 5.2f); lineTo(4f, 12f); lineTo(10.8f, 18.8f) }
            }
            Mark.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Mark.KEYBOARD -> shape {
                fillType = Path.FillType.EVEN_ODD
                addRoundRect(1.8f, 5f, 22.2f, 19f, 2.2f, 2.2f, Path.Direction.CW)
                for (x in 6..18 step 4) for (y in listOf(9f, 12.2f)) addCircle(x.toFloat(), y, .85f, Path.Direction.CW)
                addRect(7f, 15f, 17f, 16.2f, Path.Direction.CW)
            }
            Mark.MENU -> for (y in listOf(6f, 12f, 18f)) line(3f, y, 21f, y)
            Mark.MUTE -> {
                shape(stroke) { moveTo(9.5f, 8f); lineTo(14.5f, 4.5f); lineTo(14.5f, 12f) }
                shape(stroke) {
                    moveTo(13.7f, 19f); lineTo(8f, 15.3f); lineTo(4.5f, 15.3f)
                    lineTo(4.5f, 9.2f); lineTo(6f, 9.2f)
                }
                line(3.5f, 3.5f, 20.5f, 20.5f)
            }
            Mark.PLAY -> triangle(7f, 12f, 19f, 7f)
            Mark.PREVIOUS, Mark.NEXT -> {
                canvas.save()
                if (mark == Mark.PREVIOUS) canvas.rotate(180f, 12f, 12f)
                triangle(3f, 12f, 12f, 6f)
                triangle(12f, 12f, 21f, 6f)
                canvas.restore()
            }
            Mark.RIGHT, Mark.DOWN, Mark.LEFT, Mark.UP -> {
                val rotation = when (mark) { Mark.DOWN -> 90f; Mark.LEFT -> 180f; Mark.UP -> 270f; else -> 0f }
                canvas.save()
                canvas.rotate(rotation, 12f, 12f)
                shape(stroke) { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
                canvas.restore()
            }
        }
    }
}
