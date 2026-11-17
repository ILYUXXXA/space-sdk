package com.contextshield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class RiskTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF30423A.toInt(); strokeWidth = dp(1f) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF72E2AA.toInt(); strokeWidth = dp(3f); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB4F4D1.toInt(); style = Paint.Style.FILL }
    private val threshold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFA46B.toInt(); strokeWidth = dp(1f); pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(5f)), 0f)
    }
    private var values: List<Int> = emptyList()

    fun setValues(scores: List<Int>) {
        values = scores.takeLast(12).map { it.coerceIn(0, 98) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(8f)
        val right = width - dp(8f)
        val top = dp(8f)
        val bottom = height - dp(8f)
        if (right <= left || bottom <= top) return
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val y = bottom - (bottom - top) * fraction
            canvas.drawLine(left, y, right, y, grid)
        }
        val thresholdY = bottom - (bottom - top) * (60f / 100f)
        canvas.drawLine(left, thresholdY, right, thresholdY, threshold)
        if (values.isEmpty()) return
        val path = Path()
        val step = if (values.size == 1) 0f else (right - left) / (values.size - 1)
        values.forEachIndexed { index, value ->
            val x = left + step * index
            val y = bottom - (bottom - top) * (value / 100f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            canvas.drawCircle(x, y, dp(3.5f), dot)
        }
        canvas.drawPath(path, line)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
