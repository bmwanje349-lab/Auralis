package com.auralis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ten draggable equaliser bands. Touch a band and drag up or down; gains snap to 0.5 dB and are
 * limited to +/- [SoundPresets.MAX_GAIN_DB]. [onBandChanged] fires only for changes made by touch.
 */
internal class GraphicEqView(context: Context) : View(context) {
    val gains = FloatArray(SoundPresets.BAND_COUNT)
    var onBandChanged: ((band: Int) -> Unit)? = null
    var bandsEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var accent = 0xFFA8E7D5.toInt()
    private var textColor = 0xFFF1F5F6.toInt()
    private var muted = 0xFF9DAEB7.toInt()
    private var grid = 0xFF2F3E49.toInt()
    private var surface = 0xFF1F2D37.toInt()
    private var activeBand = -1

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
    }
    private val dbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = sp(10f)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(12f)
        isFakeBoldText = true
    }
    private val curve = Path()
    private val rect = RectF()

    init {
        contentDescription = "Equalizer. Drag each band up or down to change it."
    }

    fun setPalette(accent: Int, text: Int, muted: Int, grid: Int, surface: Int) {
        this.accent = accent
        this.textColor = text
        this.muted = muted
        this.grid = grid
        this.surface = surface
        invalidate()
    }

    /** Replaces all band gains without notifying [onBandChanged]. */
    fun setGains(values: FloatArray) {
        for (i in gains.indices) {
            gains[i] = (values.getOrNull(i) ?: 0f).coerceIn(-SoundPresets.MAX_GAIN_DB, SoundPresets.MAX_GAIN_DB)
        }
        invalidate()
    }

    private val padLeft: Float get() = dp(30f)
    private val padRight: Float get() = dp(8f)
    private val padTop: Float get() = dp(26f)
    private val padBottom: Float get() = dp(28f)

    private fun columnX(index: Int): Float {
        val usable = width - padLeft - padRight
        return padLeft + usable * (index + 0.5f) / SoundPresets.BAND_COUNT
    }

    private fun gainToY(gain: Float): Float {
        val usable = height - padTop - padBottom
        val fraction = (gain + SoundPresets.MAX_GAIN_DB) / (2f * SoundPresets.MAX_GAIN_DB)
        return padTop + usable * (1f - fraction)
    }

    private fun yToGain(y: Float): Float {
        val usable = max(1f, height - padTop - padBottom)
        val fraction = 1f - (y - padTop) / usable
        return (fraction * 2f * SoundPresets.MAX_GAIN_DB - SoundPresets.MAX_GAIN_DB)
            .coerceIn(-SoundPresets.MAX_GAIN_DB, SoundPresets.MAX_GAIN_DB)
    }

    private fun bandAt(x: Float): Int {
        val usable = max(1f, width - padLeft - padRight)
        return ((x - padLeft) / usable * SoundPresets.BAND_COUNT).toInt().coerceIn(0, SoundPresets.BAND_COUNT - 1)
    }

    private fun formatDb(gain: Float): String {
        val clean = if (abs(gain) < 0.05f) 0f else gain
        return "%+.1f dB".format(clean)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(dp(250f).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val strength = if (bandsEnabled) 255 else 110

        for (db in intArrayOf(12, 6, 0, -6, -12)) {
            val y = gainToY(db.toFloat())
            gridPaint.color = if (db == 0) muted else grid
            gridPaint.alpha = if (db == 0) 150 else 255
            canvas.drawLine(padLeft, y, width - padRight, y, gridPaint)
            dbPaint.color = muted
            dbPaint.alpha = 200
            canvas.drawText(if (db > 0) "+$db" else "$db", dp(2f), y + dp(3.5f), dbPaint)
        }

        val zeroY = gainToY(0f)
        for (i in gains.indices) {
            val x = columnX(i)
            val y = gainToY(gains[i])
            barPaint.color = accent
            barPaint.alpha = if (bandsEnabled) 85 else 35
            rect.set(x - dp(3f), min(y, zeroY), x + dp(3f), max(y, zeroY))
            canvas.drawRoundRect(rect, dp(3f), dp(3f), barPaint)
        }

        curve.reset()
        for (i in gains.indices) {
            val x = columnX(i)
            val y = gainToY(gains[i])
            if (i == 0) {
                curve.moveTo(x, y)
            } else {
                val previousX = columnX(i - 1)
                val previousY = gainToY(gains[i - 1])
                val middle = (previousX + x) / 2f
                curve.cubicTo(middle, previousY, middle, y, x, y)
            }
        }
        curvePaint.color = accent
        curvePaint.alpha = strength
        canvas.drawPath(curve, curvePaint)

        for (i in gains.indices) {
            val x = columnX(i)
            val y = gainToY(gains[i])
            val active = i == activeBand
            knobPaint.color = if (active) textColor else accent
            knobPaint.alpha = if (bandsEnabled) 255 else 130
            canvas.drawCircle(x, y, if (active) dp(12f) else dp(9f), knobPaint)
            labelPaint.color = if (active) accent else muted
            canvas.drawText(SoundPresets.BAND_LABELS[i], x, height - dp(8f), labelPaint)
        }

        if (activeBand >= 0) {
            val text = formatDb(gains[activeBand])
            bubbleTextPaint.color = textColor
            val halfWidth = bubbleTextPaint.measureText(text) / 2f + dp(10f)
            val centre = columnX(activeBand).coerceIn(halfWidth, max(halfWidth, width - halfWidth))
            rect.set(centre - halfWidth, dp(1f), centre + halfWidth, dp(23f))
            bubblePaint.color = surface
            canvas.drawRoundRect(rect, dp(11f), dp(11f), bubblePaint)
            canvas.drawText(text, centre, dp(16.5f), bubbleTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activeBand = bandAt(event.x)
                moveActiveBand(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                moveActiveBand(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeBand = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun moveActiveBand(y: Float) {
        val band = activeBand
        if (band < 0) return
        val snapped = ((yToGain(y) * 2f).roundToInt() / 2f)
            .coerceIn(-SoundPresets.MAX_GAIN_DB, SoundPresets.MAX_GAIN_DB)
        if (snapped != gains[band]) {
            gains[band] = snapped
            onBandChanged?.invoke(band)
        }
        invalidate()
    }
}
