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
    }
}
