package com.contextshield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Small episode-centric graph. It renders evidence categories, never sender names or message text. */
class ContextGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1.5f) }
    private var snapshot = GraphSnapshot(emptyList(), emptyMap(), emptySet(), "UNKNOWN")

    fun setSnapshot(value: GraphSnapshot) {
        snapshot = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val sources = snapshot.events.map { it.signalType }.distinct().take(3).ifEmpty { listOf("ожидание") }
        val tactics = snapshot.tacticCounts.keys.take(3).ifEmpty { listOf("нет факторов") }
        val leftX = w * .19f
        val centerX = w * .5f
        val rightX = w * .81f
        val cy = h * .5f
        val sourceYs = nodeYs(sources.size, h)
        val tacticYs = nodeYs(tactics.size, h)

        sources.forEachIndexed { i, _ -> drawEdge(canvas, leftX + dp(46f), sourceYs[i], centerX - dp(52f), cy) }
        tactics.forEachIndexed { i, _ -> drawEdge(canvas, centerX + dp(52f), cy, rightX - dp(46f), tacticYs[i]) }
        sources.forEachIndexed { i, label ->
            drawNode(canvas, leftX, sourceYs[i], shorten(sourceLabel(label)), 0xFFE5F1E9.toInt(), 0xFF16875C.toInt())
        }
        drawNode(canvas, centerX, cy, "АКТИВНЫЙ\nЭПИЗОД", 0xFF10231D.toInt(), 0xFF10231D.toInt(), 0xFFFFFFFF.toInt())
        tactics.forEachIndexed { i, label ->
            drawNode(canvas, rightX, tacticYs[i], shorten(label), 0xFFFFF0D9.toInt(), 0xFFCC8B22.toInt())
        }
        paint.color = 0xFF78857E.toInt()
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(10f)
        canvas.drawText("Имя/ник не задаёт идентичность · связь неизвестна", centerX, h - dp(6f), paint)
    }

    private fun drawEdge(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        paint.color = 0xFFBDCCC3.toInt()
        paint.strokeWidth = dp(1.5f)
        paint.style = Paint.Style.STROKE
        canvas.drawLine(x1, y1, x2, y2, paint)
    }

    private fun drawNode(canvas: Canvas, cx: Float, cy: Float, text: String, fill: Int, border: Int, textColor: Int = 0xFF14251E.toInt()) {
        val box = RectF(cx - dp(47f), cy - dp(26f), cx + dp(47f), cy + dp(26f))
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(box, dp(13f), dp(13f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.3f)
        paint.color = border
        canvas.drawRoundRect(box, dp(13f), dp(13f), paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(10.5f)
        paint.color = textColor
        val lines = text.split('\n').take(2)
        val lineHeight = dp(12f)
        val startY = cy - (lines.size - 1) * lineHeight / 2f + dp(3.5f)
        lines.forEachIndexed { i, line -> canvas.drawText(line, cx, startY + i * lineHeight, paint) }
    }

    private fun nodeYs(count: Int, height: Float): List<Float> = when (count.coerceAtMost(3)) {
        1 -> listOf(height * .5f)
        2 -> listOf(height * .30f, height * .70f)
        else -> listOf(height * .23f, height * .50f, height * .77f)
    }

    private fun sourceLabel(type: String): String = when (type) {
        "notification" -> "УВЕДОМЛЕНИЕ"
        "remote_access_activity" -> "USAGE STATS"
        "synthetic_demo" -> "СЦЕНАРИЙ"
        else -> type.uppercase()
    }

    private fun shorten(value: String): String {
        val labels = mapOf(
            "ML · urgency" to "Модель: срочно",
            "ML · secrecy" to "Модель: секрет",
            "ML · impersonation" to "Модель: подмена",
            "ML · phishing_link" to "Модель: ссылка",
            "ML · payment_request" to "Модель: перевод",
            "ML · credential_theft" to "Модель: код",
            "ML · verification_needed" to "Модель: проверка",
            "ML · investment_or_prize" to "Модель: приз",
            "ML · remote_access" to "Модель: удалёнка",
            "ML · job_scam" to "Модель: работа",
            "ML · marketplace_scam" to "Модель: продажа",
            "ML · third_party" to "Модель: посредник",
            "ML · too_good_to_be_true" to "Модель: выгода"
        )
        labels[value]?.let { return it }
        if (value.length <= 18) return value
        val words = value.split(' ')
        if (words.size >= 2) return words.take(2).joinToString(" ").take(18)
        return value.take(16) + "…"
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
