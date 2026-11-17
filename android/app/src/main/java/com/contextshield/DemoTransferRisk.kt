package com.contextshield

data class DemoTransferAssessment(
    val score: Int,
    val level: String,
    val blocked: Boolean,
    val reasons: List<String>
)

/**
 * Demo-only transaction gate. It combines the recent communication context
 * with transaction details; it does not connect to or control a real bank.
 */
object DemoTransferRisk {
    fun evaluate(
        contextRisk: ContextRisk,
        recipient: String,
        amount: Double,
        isNewRecipient: Boolean
    ): DemoTransferAssessment {
        val reasons = contextRisk.factors.toMutableList()
        var score = contextRisk.score

        if (isNewRecipient) {
            score += 18
            reasons += "Получатель ещё не встречался в истории переводов"
        }
        if (amount >= 10_000.0) {
            score += 12
            reasons += "Сумма выше демонстрационного порога 10 000 ₽"
        }
        if (amount >= 50_000.0) {
            score += 10
            reasons += "Крупная сумма требует дополнительной проверки"
        }
        if (recipient.length < 3) {
            score += 5
            reasons += "Недостаточно данных о получателе"
        }

        val finalScore = score.coerceIn(0, 100)
        val blocked = finalScore >= ContextRisk.THRESHOLD
        val level = when {
            finalScore >= 80 -> "Критический"
            finalScore >= ContextRisk.THRESHOLD -> "Высокий"
            finalScore >= 30 -> "Повышенный"
            else -> "Низкий"
        }

        if (reasons.isEmpty()) reasons += "Подозрительных сигналов в текущем контексте не найдено"
        return DemoTransferAssessment(finalScore, level, blocked, reasons.distinct())
    }
}
