package com.contextshield

data class RiskSignal(val score: Int, val factors: List<String>) {
    val high: Boolean get() = score >= 60
}

/** Explainable on-device Russian-language social-engineering signals. */
object LocalRisk {
    private val moneyRequest = Regex(
        "\\b(перевед[иите]|скинь|пришли|отправь|закинь|одолжи|дай|займи|оплат[иите]|пополн[иите]|нужны деньги|нужна сумма|перевод)\\b",
        RegexOption.IGNORE_CASE
    )
    private val urgency = Regex(
        "срочн|немедленно|прямо сейчас|сейчас же|как можно скорее|времени нет|быстрее|последний шанс|пока не поздно|до конца дня",
        RegexOption.IGNORE_CASE
    )
    private val emergencyPretext = Regex(
        "операци|лечени|больниц|лекарств|авари|попал.{0,20}бед|задержал.{0,20}полици|угрожа|спасти|срочн.{0,25}помощ",
        RegexOption.IGNORE_CASE
    )
    private val secrecy = Regex(
        "никому не (говори|сообщай|рассказывай)|держи в секрете|не рассказывай|не клади трубку|не звони.{0,20}(мне|никому)|не говори.{0,20}(родител|семь|мужу|жене)",
        RegexOption.IGNORE_CASE
    )
    private val codeOrCredential = Regex(
        "код из (sms|смс)|код подтверждения|одноразов.{0,15}код|cvc|cvv|пин-код|пароль|данные карты|назови.{0,20}код|продиктуй.{0,20}код",
        RegexOption.IGNORE_CASE
    )
    private val unsafeAccount = Regex(
        "безопасн.{0,20}сч[её]т|резервн.{0,20}сч[её]т|защитн.{0,20}сч[её]т|перевед.{0,30}(на другую карту|по этим реквизитам|на этот сч[её]т)|нов.{0,20}реквизит",
        RegexOption.IGNORE_CASE
    )
    private val authority = Regex(
        "служб[аы] безопасност|сотрудник.{0,20}банк|из банка|полици|следовател|налогов|техническ.{0,20}поддержк|госуслуг",
        RegexOption.IGNORE_CASE
    )
    private val remoteAccess = Regex(
        "установи.{0,25}(приложени|програм)|скачай.{0,25}(приложени|програм)|anydesk|rustdesk|удал[её]нн.{0,20}доступ|включи демонстрацию экрана|покажи экран",
        RegexOption.IGNORE_CASE
    )
    private val threatOrReward = Regex(
        "заблокир|арест|штраф|выиграл|компенсац|проблем[аы] с аккаунт|верификац.{0,20}аккаунт|последн.{0,20}предупреждени",
        RegexOption.IGNORE_CASE
    )

    fun assess(text: String): RiskSignal {
        val factors = mutableListOf<String>()
        val asksForMoney = moneyRequest.containsMatchIn(text)
        val isUrgent = urgency.containsMatchIn(text)
        val hasEmergency = emergencyPretext.containsMatchIn(text)
        val asksForSecret = secrecy.containsMatchIn(text)
        val asksForCode = codeOrCredential.containsMatchIn(text)
        val movesToUnsafeAccount = unsafeAccount.containsMatchIn(text)
        val impersonatesAuthority = authority.containsMatchIn(text)
        val asksRemoteAccess = remoteAccess.containsMatchIn(text)
        val threatensOrBaits = threatOrReward.containsMatchIn(text)

        var score = 0
        if (asksForMoney) { score += 14; factors += "Просьба о деньгах или переводе" }
        if (isUrgent) { score += 22; factors += "Давление срочностью" }
        if (hasEmergency) { score += 14; factors += "Легенда о срочной проблеме" }
        if (asksForSecret) { score += 28; factors += "Просьба скрыть разговор" }
        if (asksForCode) { score += 60; factors += "Запрос кода или платёжных данных" }
        if (movesToUnsafeAccount) { score += 36; factors += "Перевод на новый или «безопасный» счёт" }
        if (impersonatesAuthority) { score += 22; factors += "Возможная имитация банка или службы" }
        if (asksRemoteAccess) { score += 42; factors += "Запрос доступа к устройству или экрану" }
        if (threatensOrBaits) { score += 20; factors += "Угроза, блокировка или приманка" }

        // A plausible emergency plus a money request under time pressure is a common scam pattern.
        if (asksForMoney && isUrgent && hasEmergency) {
            score += 18
            factors += "Срочный денежный запрос под предлогом чрезвычайной ситуации"
        }
        if (asksForMoney && asksForSecret) score += 12
        if (asksForMoney && impersonatesAuthority) score += 12
        if (asksForMoney && movesToUnsafeAccount) score += 10

        return RiskSignal(score.coerceIn(0, 98), factors.distinct())
    }
}
