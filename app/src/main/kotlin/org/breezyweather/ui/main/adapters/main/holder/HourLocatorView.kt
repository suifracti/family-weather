/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.ui.main.adapters.main.holder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.breezyweather.common.extensions.getThemeColor
import kotlin.math.abs
import kotlin.math.roundToInt

/** A wide-touch time locator for the scrollable hourly forecast on the home card. */
internal class HourLocatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getThemeColor(com.google.android.material.R.attr.colorOutline)
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getThemeColor(com.google.android.material.R.attr.colorOutline)
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getThemeColor(com.google.android.material.R.attr.colorSecondaryContainer)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getThemeColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private var labels: List<String> = emptyList()
    private var selectedIndex = 0
    private var downY = 0f
    private var dragged = false
    var onHourSelected: ((Int) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "逐小时定位条"
    }

    fun setHours(values: List<String>, selected: Int = 0) {
        labels = values
        selectedIndex = selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        isEnabled = labels.isNotEmpty()
        contentDescription = currentDescription()
        invalidate()
    }

    fun setSelectedIndex(index: Int) {
        selectedIndex = index.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        contentDescription = currentDescription()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (labels.isEmpty() || height <= dp(48f)) return

        val centerX = width / 2f
        val startY = trackStartY()
        val endY = trackEndY()
        canvas.drawLine(centerX, startY, centerX, endY, trackPaint)

        if (labels.size > 1) {
            labels.indices.forEach { index ->
                val y = startY + (endY - startY) * index / (labels.size - 1)
                canvas.drawCircle(centerX, y, dp(1.5f), tickPaint)
            }
        }

        val thumbY = yForIndex(selectedIndex)
        val thumb = RectF(
            centerX - dp(27f),
            thumbY - dp(19f),
            centerX + dp(27f),
            thumbY + dp(19f),
        )
        canvas.drawRoundRect(thumb, dp(12f), dp(12f), thumbPaint)
        val label = labels[selectedIndex]
        val baseline = thumb.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f
        canvas.drawText(label, thumb.centerX(), baseline, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || labels.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                dragged = false
                parent?.requestDisallowInterceptTouchEvent(true)
                selectFromY(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.y - downY) > touchSlop) dragged = true
                selectFromY(event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                selectFromY(event.y)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragged) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onHourSelected?.invoke(selectedIndex)
        return true
    }

    private fun selectFromY(y: Float) {
        if (labels.isEmpty()) return
        val next = if (labels.size == 1) {
            0
        } else {
            (((y - trackStartY()) / (trackEndY() - trackStartY())) * (labels.size - 1))
                .roundToInt()
                .coerceIn(labels.indices)
        }
        if (next != selectedIndex) {
            selectedIndex = next
            contentDescription = currentDescription()
            invalidate()
        }
        onHourSelected?.invoke(selectedIndex)
    }

    private fun currentDescription(): String = labels.getOrNull(selectedIndex)
        ?.let { "定位到$it，拖动可跳转小时" }
        ?: "暂无逐小时预报"

    private fun yForIndex(index: Int): Float = if (labels.size <= 1) {
        (trackStartY() + trackEndY()) / 2f
    } else {
        trackStartY() + (trackEndY() - trackStartY()) * index / (labels.size - 1)
    }

    private fun trackStartY(): Float = dp(24f)
    private fun trackEndY(): Float = (height - dp(24f)).coerceAtLeast(trackStartY())
    private fun dp(value: Float): Float = value * density
}
