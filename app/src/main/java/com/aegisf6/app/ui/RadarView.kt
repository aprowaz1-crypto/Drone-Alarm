package com.aegisf6.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.aegisf6.app.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_grid)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_sweep)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f
    }

    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_blip)
        style = Paint.Style.FILL
    }

    private val rejectedBlipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_blip_rejected)
        style = Paint.Style.FILL
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_center)
        style = Paint.Style.FILL
    }

    private val sweepAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweepAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private var sweepAngle = 0f
    private var targetAzimuth = 0f
    private var targetStrength = 0
    private var accepted = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sweepAnimator.start()
    }

    override fun onDetachedFromWindow() {
        sweepAnimator.cancel()
        super.onDetachedFromWindow()
    }

    fun updateTelemetry(azimuthDeg: Float, confidence: Int, accepted: Boolean) {
        this.targetAzimuth = azimuthDeg
        this.targetStrength = confidence.coerceIn(0, 100)
        this.accepted = accepted
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.45f

        drawGrid(canvas, cx, cy, radius)
        drawSweep(canvas, cx, cy, radius)
        drawTarget(canvas, cx, cy, radius)
        canvas.drawCircle(cx, cy, radius * 0.04f, centerPaint)
    }

    private fun drawGrid(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, gridPaint)
        canvas.drawCircle(cx, cy, radius * 0.66f, gridPaint)
        canvas.drawCircle(cx, cy, radius * 0.33f, gridPaint)

        canvas.drawLine(cx - radius, cy, cx + radius, cy, gridPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, gridPaint)
    }

    private fun drawSweep(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val angle = Math.toRadians(sweepAngle.toDouble())
        val x = cx + cos(angle).toFloat() * radius
        val y = cy + sin(angle).toFloat() * radius
        canvas.drawLine(cx, cy, x, y, sweepPaint)
    }

    private fun drawTarget(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val signalRadius = radius * (0.15f + 0.75f * (targetStrength / 100f))
        val azimuth = Math.toRadians(targetAzimuth.toDouble() - 90.0)
        val x = cx + cos(azimuth).toFloat() * signalRadius
        val y = cy + sin(azimuth).toFloat() * signalRadius
        val paint = if (accepted) blipPaint else rejectedBlipPaint
        val blipSize = radius * (0.03f + 0.03f * (targetStrength / 100f))
        canvas.drawCircle(x, y, blipSize, paint)
    }
}