package com.aurahear.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class VuMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var targetLeftLevel = 0f
    private var targetRightLevel = 0f

    private var currentLeftLevel = 0f
    private var currentRightLevel = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B") // Dark slate background
        style = Paint.Style.FILL
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val rectL = RectF()
    private val rectR = RectF()
    private val bgRectL = RectF()
    private val bgRectR = RectF()

    private var gradient: LinearGradient? = null

    fun setLevels(left: Float, right: Float) {
        targetLeftLevel = left.coerceIn(0f, 1f)
        targetRightLevel = right.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            // Gradient from vibrant teal to amber to alert red at maximum level
            gradient = LinearGradient(
                0f, h.toFloat(), 0f, 0f,
                intArrayOf(
                    Color.parseColor("#06B6D4"), // Teal
                    Color.parseColor("#3B82F6"), // Blue
                    Color.parseColor("#F59E0B"), // Amber
                    Color.parseColor("#EF4444")  // Red
                ),
                floatArrayOf(0f, 0.6f, 0.85f, 1.0f),
                Shader.TileMode.CLAMP
            )
            barPaint.shader = gradient
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Smooth physics-based falloff animation
        val attackSpeed = 0.5f
        val decaySpeed = 0.15f

        currentLeftLevel = if (targetLeftLevel > currentLeftLevel) {
            currentLeftLevel + (targetLeftLevel - currentLeftLevel) * attackSpeed
        } else {
            max(0f, currentLeftLevel - decaySpeed)
        }

        currentRightLevel = if (targetRightLevel > currentRightLevel) {
            currentRightLevel + (targetRightLevel - currentRightLevel) * attackSpeed
        } else {
            max(0f, currentRightLevel - decaySpeed)
        }

        val labelAreaHeight = 40f
        val meterHeight = h - labelAreaHeight
        val barWidth = (w / 2f) - 32f
        val cornerRadius = 14f

        // Left Channel Background
        bgRectL.set(16f, 8f, 16f + barWidth, meterHeight)
        canvas.drawRoundRect(bgRectL, cornerRadius, cornerRadius, bgPaint)

        // Left Channel Active Bar (rises from bottom)
        val leftFillTop = meterHeight - (meterHeight - 8f) * currentLeftLevel
        rectL.set(16f, leftFillTop.coerceAtLeast(8f), 16f + barWidth, meterHeight)
        if (currentLeftLevel > 0.01f) {
            canvas.drawRoundRect(rectL, cornerRadius, cornerRadius, barPaint)
        }

        // Right Channel Background
        val rightStartX = (w / 2f) + 16f
        bgRectR.set(rightStartX, 8f, rightStartX + barWidth, meterHeight)
        canvas.drawRoundRect(bgRectR, cornerRadius, cornerRadius, bgPaint)

        // Right Channel Active Bar
        val rightFillTop = meterHeight - (meterHeight - 8f) * currentRightLevel
        rectR.set(rightStartX, rightFillTop.coerceAtLeast(8f), rightStartX + barWidth, meterHeight)
        if (currentRightLevel > 0.01f) {
            canvas.drawRoundRect(rectR, cornerRadius, cornerRadius, barPaint)
        }

        // Channel Labels
        canvas.drawText("LEFT EAR", bgRectL.centerX(), h - 8f, textPaint)
        canvas.drawText("RIGHT EAR", bgRectR.centerX(), h - 8f, textPaint)

        // Continuous redraw while animating
        if (currentLeftLevel > 0.005f || currentRightLevel > 0.005f || targetLeftLevel > 0.005f || targetRightLevel > 0.005f) {
            postInvalidateOnAnimation()
        }
    }
}
