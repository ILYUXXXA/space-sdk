package com.contextshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRiskTest {
    @Test
    fun smallConvenientFamilyTransferDoesNotBlock() {
        val result = LocalRisk.assess("Я оплатил общий заказ. Переведи свою часть вечером, если будет удобно.")
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
            "Привет, одолжи 800 рублей до пятницы, пожалуйста. Верну после выплаты.",
            ModelAssessment("dangerous", 0.68f, listOf(0f, 0f, 0f, 0.68f), emptyMap())
        )
        assertTrue(result.score < ContextRisk.THRESHOLD)
        assertFalse(result.high)
        assertTrue(result.advisory.orEmpty().contains("подтвердите её звонком"))
    }

    @Test
    fun moneyDemandWithThreatToExposePersonalDataGetsSpecificAdvisoryWithoutAutomaticBlock() {
        val result = LocalRisk.assess(
            "Переведи 4 000 рублей, иначе выложу твой адрес и переписку.",
            ModelAssessment("dangerous", 0.99f, listOf(0f, 0f, 0f, 0.99f), emptyMap())
        )
        assertTrue("score=${result.score} factors=${result.factors}", result.score in 40 until ContextRisk.THRESHOLD)
        assertFalse(result.high)
        assertEquals("money_extortion", result.messageKind)
        assertTrue(result.factors.any { it.contains("вымогательства", ignoreCase = true) })
        assertTrue(result.advisory.orEmpty().contains("угрозой раскрыть личные данные"))
    }

    @Test
    fun routineSharedExpenseDoesNotGetAnInterruptionByDefault() {
        val result = LocalRisk.assess(
            "Я оплатил продукты для поездки. Переведи свою часть, когда тебе будет удобно.",
            ModelAssessment("suspicious", 0.60f, listOf(0.15f, 0.20f, 0.60f, 0.05f), emptyMap())
        )
        assertTrue(result.score < ContextRisk.THRESHOLD)
        assertEquals(null, result.advisory)
    }

    @Test
    fun repeatedThreatAndMoneyPatternsPauseOnlyTheDemoTransfer() {
        val scenarios = listOf(
            "Переведи тысячу рублей, иначе я тебя пристрелю.",
            "Кинь две тысячи, или я тебя покалечу.",
            "Переведи деньги, а то сломаю тебе ноги."
        )
        scenarios.forEach { text ->
            val result = LocalRisk.assess(text)
            assertTrue("$text -> score=${result.score}, factors=${result.factors}", result.score >= ContextRisk.THRESHOLD)
            assertTrue(text, result.high)
            assertEquals(text, "violent_money_coercion", result.messageKind)
            assertTrue(text, result.factors.any { it.contains("угрозой физической расправы", ignoreCase = true) })
        }
    }

    @Test
    fun urgencyOnlyTriggersVerificationAdvisoryBelowHardBlockThreshold() {
        val result = LocalRisk.assess("Переведи 900 рублей до вечера, иначе заказ отменят.")
        assertTrue("score=${result.score} factors=${result.factors}", result.score >= ContextRisk.ADVISORY_THRESHOLD)
        assertTrue(result.score < ContextRisk.THRESHOLD)
        assertFalse(result.high)
        assertTrue(result.advisory.orEmpty().contains("Перевод не приостановлен"))
    }

    @Test
    fun quotedCyberSafetyExampleDoesNotTriggerViolentMoneyRule() {
        val result = LocalRisk.assess(
            "Памятка: вымогатели могут требовать деньги и угрожать раскрыть ваш адрес. Не переводите деньги под давлением."
        )
        assertEquals(0, result.score)
        assertEquals("security_advisory", result.messageKind)
    }

    @Test
    fun rudeMessageWithoutPaymentDemandIsNotFlaggedAsMoneyFraud() {
        val result = LocalRisk.assess("Ты меня разозлил, поговорим позже.")
        assertEquals(0, result.score)
        assertEquals(null, result.advisory)
    }
}
