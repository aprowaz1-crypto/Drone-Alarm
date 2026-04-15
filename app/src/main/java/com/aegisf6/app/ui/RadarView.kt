package com.aegisf6.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
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

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_background)
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_grid)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        alpha = 160
    }

    private val sweepLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_sweep)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f
    }

    private val sweepFanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_sweep)
        style = Paint.Style.FILL
        alpha = 38
    }

    private val blipFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_blip)
        style = Paint.Style.FILL
    }

    private val blipRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_blip)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val rejectedBlipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_blip_rejected)
        style = Paint.Style.FILL
        alpha = 160
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_center)
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_grid)
        textSize = 26f
        textAlign = Paint.Align.CENTER
        alpha = 200
    }

    private val telemetryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_blip)
        textSize = 20f
        textAlign = Paint.Align.LEFT
        alpha = 255
    }

    private val telemetrySmallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radar_grid)
        textSize = 16f
        textAlign = Paint.Align.LEFT
        alpha = 200
    }

    private val sweepAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweepAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(1f, 2.6f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = DecelerateInterpolator()
        addUpdateListener { pulseScale = it.animatedValue as Float }
    }

    private val sweepFanRect = RectF()
    private var sweepAngle = 0f
    private var pulseScale = 1f
    private var targetAzimuth = 0f
    private var targetAltitude = 0  // Висота в метрах
    private var targetDistance = 0.0  // Дальність в км
    private var targetStrength = 0
    private var accepted = false
    private var isMonitorActive = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sweepAnimator.start()
        pulseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        sweepAnimator.cancel()
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    fun updateTelemetry(azimuthDeg: Float, altitudeM: Int, distanceKm: Double, confidence: Int, accepted: Boolean, isActive: Boolean) {
        this.targetAzimuth = azimuthDeg
        this.targetAltitude = altitudeM
        this.targetDistance = distanceKm
        this.targetStrength = confidence.coerceIn(0, 100)
        this.accepted = accepted
        this.isMonitorActive = isActive
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.43f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        drawGrid(canvas, cx, cy, radius)
        drawCardinalLabels(canvas, cx, cy, radius)
        if (isMonitorActive) {
            drawSweep(canvas, cx, cy, radius)
        }
        if (isMonitorActive && targetStrength > 0) {
            drawTarget(canvas, cx, cy, radius)
        }
        canvas.drawCircle(cx, cy, radius * 0.035f, centerPaint)
    }

    private fun drawGrid(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, gridPaint)
        canvas.drawCircle(cx, cy, radius * 0.66f, gridPaint)
        canvas.drawCircle(cx, cy, radius * 0.33f, gridPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, gridPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, gridPaint)
    }

    private fun drawCardinalLabels(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val margin = radius * 0.14f
        canvas.drawText("N", cx, cy - radius + margin + labelPaint.textSize, labelPaint)
        canvas.drawText("S", cx, cy + radius - margin * 0.5f, labelPaint)
        canvas.drawText("E", cx + radius - margin, cy + labelPaint.textSize * 0.35f, labelPaint)
        canvas.drawText("W", cx - radius + margin, cy + labelPaint.textSize * 0.35f, labelPaint)
    }

    private fun drawSweep(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        sweepFanRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(sweepFanRect, sweepAngle - 55f, 55f, true, sweepFanPaint)
        val angle = Math.toRadians(sweepAngle.toDouble())
        val x = cx + cos(angle).toFloat() * radius
        val y = cy + sin(angle).toFloat() * radius
        canvas.drawLine(cx, cy, x, y, sweepLinePaint)
    }

    private fun drawTarget(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val signalRadius = radius * (0.18f + 0.70f * (targetStrength / 100f))
        val azimuth = Math.toRadians(targetAzimuth.toDouble() - 90.0)
        val x = cx + cos(azimuth).toFloat() * signalRadius
        val y = cy + sin(azimuth).toFloat() * signalRadius
        val blipSize = radius * (0.038f + 0.022f * (targetStrength / 100f))
        
        if (accepted) {
            blipRingPaint.alpha = (220 / pulseScale).toInt().coerceIn(30, 220)
            canvas.drawCircle(x, y, blipSize * pulseScale, blipRingPaint)
            canvas.drawCircle(x, y, blipSize, blipFillPaint)
        } else {
            canvas.drawCircle(x, y, blipSize, rejectedBlipPaint)
        }

        // Розташування текстових міток
        val textOffsetX = blipSize + radius * 0.08f
        val textOffsetY = blipSize + radius * 0.04f

        // Азимут: XXX°
        canvas.drawText(
            String.format("%03d°", targetAzimuth.toInt()),
            x + textOffsetX,
            y - textOffsetY + radius * 0.02f,
            telemetryPaint
        )

        // Висота: XXXm
        canvas.drawText(
            String.format("%d м", targetAltitude),
            x + textOffsetX,
            y,
            telemetrySmallPaint
        )

        // Дальність: X.Xкм
        canvas.drawText(
            String.format("%.1f км", targetDistance),
            x + textOffsetX,
            y + textOffsetY - radius * 0.02f,
            telemetrySmallPaint
        )
    }
}