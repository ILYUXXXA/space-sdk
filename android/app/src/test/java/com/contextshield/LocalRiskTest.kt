package com.contextshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRiskTest {
    @Test
    fun smallConvenientFamilyTransferDoesNotBlock() {
        val result = LocalRisk.assess("Привет, переведи мне пожалуйста 10 рублей на проезд, если удобно.")
        assertTrue(result.score < ContextRisk.THRESHOLD)
        assertFalse(result.high)
    }

    @Test
    fun emergencyMoneyRequestUnderUrgencyIsHighRisk() {
        val result = LocalRisk.assess("Скинь мне срочно тысячу долларов на операцию, времени нет.")
        assertTrue(result.score >= ContextRisk.THRESHOLD)
        assertTrue(result.factors.any { it.contains("срочностью", ignoreCase = true) })
        assertTrue(result.factors.any { it.contains("чрезвычайной", ignoreCase = true) })
    }

    @Test
    fun directOtpRequestIsHighRisk() {
        val result = LocalRisk.assess("Назови код из СМС, чтобы отменить перевод.")
        assertTrue(result.score >= ContextRisk.THRESHOLD)
        assertTrue(result.factors.any { it.contains("кода", ignoreCase = true) })
    }

    @Test
    fun cyberSafetyAdvisoryIsNotTreatedAsAttack() {
        val result = LocalRisk.assess("Памятка по кибербезопасности: мошенники могут просить код из SMS. Не сообщайте код и не переводите деньги.")
        assertEquals(0, result.score)
        assertEquals("security_advisory", result.messageKind)
    }

    @Test
    fun newNumberFamilyImpersonationSequenceIsHighRisk() {
        val result = LocalRisk.assess("Мам, я потерял телефон, это новый номер. Срочно переведи 1000 долларов на операцию и никому не говори.")
        assertTrue(result.score >= ContextRisk.THRESHOLD)
        assertTrue(result.factors.any { it.contains("новый номер", ignoreCase = true) })
    }

    @Test
    fun modelAloneCannotBlockWithoutIndependentEvidence() {
        val result = LocalRisk.assess(
            "Привет! Как дела?",
            ModelAssessment("dangerous", 0.99f, listOf(0f, 0f, 0f, 1f), mapOf("urgency" to 0.99f))
        )
        assertEquals(0, result.score)
        assertFalse(result.high)
        assertEquals(null, result.advisory)
    }

    @Test
    fun moneyRequestWithRiskyModelGetsNonBlockingVerificationAdvisory() {
        val result = LocalRisk.assess(
            "Илюша, привет. Мне до пятницы нужно 200 рублей. Переведи пожалуйста, на днях верну.",
            ModelAssessment("dangerous", 0.68f, listOf(0f, 0f, 0f, 0.68f), emptyMap())
        )
        assertTrue(result.score < ContextRisk.THRESHOLD)
        assertFalse(result.high)
        assertTrue(result.advisory.orEmpty().contains("подтвердите её звонком"))
    }

    @Test
    fun slangMoneyRequestWithDoxxingThreatGetsSpecificAdvisoryWithoutAutomaticBlock() {
        val result = LocalRisk.assess(
            "Фастиком денежку 50 зелёных на картейку иначе докс и сват",
            ModelAssessment("dangerous", 0.99f, listOf(0f, 0f, 0f, 0.99f), emptyMap())
        )
        assertTrue(result.score in 40 until ContextRisk.THRESHOLD)
        assertFalse(result.high)
        assertEquals("money_extortion", result.messageKind)
        assertTrue(result.factors.any { it.contains("вымогательства", ignoreCase = true) })
        assertTrue(result.advisory.orEmpty().contains("угрозой раскрыть личные данные"))
    }

    @Test
    fun ordinarySmallTransferDoesNotGetAnInterruptionByDefault() {
        val result = LocalRisk.assess(
            "Привет, переведи мне пожалуйста 10 рублей на проезд, если тебе удобно.",
            ModelAssessment("suspicious", 0.506f, listOf(0f, 0f, 0.506f, 0.461f), emptyMap())
        )
        assertTrue(result.score < ContextRisk.THRESHOLD)
        assertEquals(null, result.advisory)
    }
}
