package com.contextshield

data class RiskSignal(
    val score: Int,
    val factors: List<String>,
    val modelLabel: String = "unknown",
    val modelConfidence: Float = 0f,
    val modelTactics: Map<String, Float> = emptyMap(),
    val messageKind: String = "incoming_request",
    /** A focused, non-blocking verification prompt. Null means do not interrupt the user. */
    val advisory: String? = null
) {
    val high: Boolean get() = score >= ContextRisk.THRESHOLD
}

/** Explainable local policy. The ML classifier can add bounded evidence but never blocks alone. */
object LocalRisk {
    private val moneyAction = Regex(
        "(?iu)(?<!\\p{L})(?:перевед|скин|пришл|отправ|закин|одолж|дай|займ|оплат|пополн|переброс|перекин|кин|кид|фастик)[\\p{L}]*|нужн[аоы]?\\s+(?:деньг|сумм|тысяч)|помоги\\s+(?:деньгами|суммой)"
    )
    private val moneyAmount = Regex("(?iu)(?:\\d[\\d\\s.,]*\\s?(?:к(?!\\p{L})|тыс[\\p{L}]*|₽|руб[\\p{L}]*|р\\.?|доллар[\\p{L}]*|зел[её]н[\\p{L}]*|usd|eur|€|\\$)|(?:тысяч[уае]?|сотн[яюи]?|пара тысяч))")
    private val moneyMention = Regex("(?iu)(?:деньг[\\p{L}]*|ден[её]жк[\\p{L}]*|денюжк[\\p{L}]*|бабл[\\p{L}]*|бабк[\\p{L}]*|бабос[\\p{L}]*|лав[эеё]?(?:х[\\p{L}]*)?|кэш[\\p{L}]*|кеш[\\p{L}]*|косар[\\p{L}]*|зел[её]н[\\p{L}]*|руб[\\p{L}]*|доллар[\\p{L}]*|usd|eur|евр[\\p{L}]*|₽|€|\\$)")
    private val paymentDestination = Regex("(?iu)(?:на\\s+(?:карт[\\p{L}]*|сч[её]т[\\p{L}]*|номер|реквизит[\\p{L}]*|кошел[её]к[\\p{L}]*)|по\\s+(?:номер|реквизит[\\p{L}]*))")
    private val urgency = Regex(
        "(?iu)срочн|немедленно|прямо сейчас|сейчас же|сейчас.{0,24}(?:кид|перевед|скин|отправ|закин)|как можно скорее|времени нет|быстрее|последний шанс|пока не поздно|до конца дня|сегодня до|до\\s+(?:вечера|сегодня|завтра|\\d{1,2}:\\d{2})"
    )
    private val emergencyPretext = Regex(
        "операци|лечени|больниц|лекарств|авари|попал.{0,20}бед|задержал.{0,20}полици|угрожа|спасти|срочн.{0,25}помощ|реб[её]нок.{0,25}(?:больниц|авари)",
        RegexOption.IGNORE_CASE
    )
    private val secrecy = Regex(
        "никому не (говори|сообщай|рассказывай)|держи в секрете|не рассказывай|не клади трубку|не звони.{0,20}(мне|никому)|не говори.{0,20}(родител|семь|мужу|жене)",
        RegexOption.IGNORE_CASE
    )
    private val extortionThreat = Regex(
        "(?iu)(?:\\bдокс(?:инг)?\\b|\\bдоксн[уи]\\b|\\bсват(?:инг)?\\b|\\bсватн[уи]\\b|иначе.{0,35}(?:докс|сват)|вылож[уи].{0,30}(?:адрес|номер|фото|переписк|данн)|опубликую.{0,30}(?:адрес|номер|фото|данн)|разглаш[уи].{0,30}(?:данн|переписк))"
    )
    private val coerciveUltimatum = Regex(
        "(?isu)(?:\\b(?:или|либо)\\b.{0,160}\\b(?:будешь|получишь|окажешься|останешься|иначе)\\b|\\bа\\s+то\\b|\\bиначе\\b|\\bесли\\s+не\\b|\\bне\\s+то\\b)"
    )
    private val physicalThreatCue = Regex(
        "(?isu)(?:убью|убива|пристрел|застрел|приконч|покалеч|побью|изобью|сломаю.{0,16}ног|положу.{0,24}(?:в\\s+могил|под\\s+земл)|пойд[её]шь.{0,24}(?:на\\s+тот\\s+свет|в\\s+могил)|окажешься.{0,40}(?:под\\s+земл|в\\s+могил)|отправлю.{0,24}(?:к\\s+праотцам|на\\s+тот\\s+свет))"
    )
    private val hostileInsult = Regex("(?iu)(?:у[её]бан[\\p{L}]*|чепушил[\\p{L}]*|ублюд[\\p{L}]*|мраз[\\p{L}]*|мудак[\\p{L}]*|гнид[\\p{L}]*)")
    private val codeWord = Regex("(?iu)(?:\\bкод\\b|код.{0,24}(?:sms|смс|подтвержден)|(?:sms|смс).{0,24}код|одноразов.{0,15}код|cvc|cvv|пин-код|пароль|данные карты)")
    private val codeRequest = Regex("(?iu)(?:назови|продиктуй|пришли|отправь|сообщи|скажи|введи|подтверди|перешли|передай|дай|скинь).{0,36}(?:код|пароль|пин|cvv|cvc)|(?:код|пароль|пин|cvv|cvc).{0,36}(?:назови|продиктуй|пришли|отправь|сообщи|скажи|введи|передай|дай|скинь)")
    private val safeInstruction = Regex(
        "(?iu)(?:не\\s+(?:сообщай|сообщайте|называй|называйте|передавай|передавайте|отправляй|отправляйте|вводи|вводите|переводи|переводите|устанавливай|устанавливайте).{0,35}(?:код|парол|пин|деньг|счет|сч[её]т|приложен|ссылк)|(?:банк|служб[аы] безопасности).{0,35}никогда не (?:просит|запрашивает).{0,30}(?:код|парол|перевод))")
    private val advisoryContext = Regex(
        "(?iu)(?:предупрежда(?:ем|ет|ют)|памятка|совет по безопасности|мошенники (?:часто|обычно|могут|просят|используют)|схема мошенничества|не становитесь жертвой|кибербезопасност|не сообщайте никому|банк никогда не просит)"
    )
    private val unsafeAccount = Regex(
        "безопасн.{0,20}сч[её]т|резервн.{0,20}сч[её]т|защитн.{0,20}сч[её]т|перевед.{0,30}(?:на другую карту|по этим реквизитам|на этот сч[её]т)|нов.{0,20}реквизит|обнови.{0,20}(?:реквизит|карт)",
        RegexOption.IGNORE_CASE
    )
    private val authority = Regex(
        "служб[аы] безопасност|сотрудник.{0,20}банк|из банка|полици|следовател|налогов|техническ.{0,20}поддержк|госуслуг|служб[аы] доставки",
        RegexOption.IGNORE_CASE
    )
    private val remoteAccess = Regex(
        "установи.{0,25}(?:приложени|програм)|скачай.{0,25}(?:приложени|програм)|anydesk|rustdesk|teamviewer|удал[её]нн.{0,20}доступ|включи демонстрацию экрана|покажи экран|подключись к экрану",
        RegexOption.IGNORE_CASE
    )
    private val threatOrReward = Regex(
        "заблокир|арест|штраф|выиграл|компенсац|проблем[аы] с аккаунт|верификац.{0,20}аккаунт|последн.{0,20}предупреждени|ваш аккаунт.{0,20}(?:закро|заблок)",
        RegexOption.IGNORE_CASE
    )
    private val suspiciousLink = Regex("(?iu)(?:https?://|www\\.)\\S+|\\b[a-z0-9-]+\\.(?:top|xyz|click|help|site|online|icu)\\b")
    private val linkAction = Regex("(?iu)(?:перейди|нажми|открой|подтверди|проверь|восстанови).{0,40}(?:ссылк|аккаунт|доступ|доставк|штраф|выигрыш|получени)")
    private val newNumber = Regex("(?iu)(?:сменил|потерял|новый)\\s+(?:номер|телефон)|это (?:мой )?новый номер")
    private val familyAddress = Regex("(?iu)(?:мам|пап|бабуш|дедуш|сын|доч|родител|родн)")
    private val asksForHelp = Regex("(?iu)(?:помоги|выручи|спаси|одолжи|можешь.{0,20}(?:перевести|скинуть)|пожалуйста).{0,35}(?:деньг|сумм|тысяч|руб|доллар)?")

    fun assess(text: String, model: ModelAssessment? = null): RiskSignal {
        val normalized = normalizeLookalikes(text.take(4000))
        val looksLikeAdvisory = advisoryContext.containsMatchIn(normalized) &&
            (safeInstruction.containsMatchIn(normalized) || normalized.contains("мошенник", ignoreCase = true) || normalized.contains("предупрежд", ignoreCase = true))
        if (looksLikeAdvisory) {
            return RiskSignal(
                score = 0,
                factors = listOf("Похоже на предупреждение о мошенничестве, а не на просьбу к пользователю"),
                modelLabel = model?.label ?: "unknown",
                modelConfidence = model?.confidence ?: 0f,
                modelTactics = model?.tactics?.filterValues { it >= 0.70f }.orEmpty(),
                messageKind = "security_advisory"
            )
        }

        val safelyWarns = safeInstruction.containsMatchIn(normalized)
        val threatensDoxxing = extortionThreat.containsMatchIn(normalized)
        val hasMoneyMention = moneyMention.containsMatchIn(normalized)
        val hasMoneyAmount = moneyAmount.containsMatchIn(normalized)
        val hasPaymentTarget = hasMoneyAmount || hasMoneyMention || paymentDestination.containsMatchIn(normalized)
        val asksForMoney = !safelyWarns && ((moneyAction.containsMatchIn(normalized) && hasPaymentTarget) ||
            (moneyAmount.containsMatchIn(normalized) && (asksForHelp.containsMatchIn(normalized) ||
                (emergencyPretext.containsMatchIn(normalized) && urgency.containsMatchIn(normalized)))) ||
            (threatensDoxxing && hasMoneyMention && paymentDestination.containsMatchIn(normalized)))
        val threatensPhysicalHarm = physicalThreatCue.containsMatchIn(normalized)
        val hasUltimatum = coerciveUltimatum.containsMatchIn(normalized) ||
            (threatensPhysicalHarm && Regex("(?iu)\\b(?:или|либо|иначе|а\\s+то)\\b").containsMatchIn(normalized))
        val usesHostileLanguage = hostileInsult.containsMatchIn(normalized)
        val violentMoneyThreat = asksForMoney && hasUltimatum && threatensPhysicalHarm
        val abusiveMoneyUltimatum = asksForMoney && hasUltimatum && usesHostileLanguage && !violentMoneyThreat
        val isUrgent = urgency.containsMatchIn(normalized)
        val hasEmergency = emergencyPretext.containsMatchIn(normalized)
        val asksForSecret = secrecy.containsMatchIn(normalized)
        val asksForCode = !safelyWarns && codeWord.containsMatchIn(normalized) && codeRequest.containsMatchIn(normalized)
        val movesToUnsafeAccount = unsafeAccount.containsMatchIn(normalized)
        val impersonatesAuthority = authority.containsMatchIn(normalized)
        val asksRemoteAccess = remoteAccess.containsMatchIn(normalized)
        val threatensOrBaits = threatOrReward.containsMatchIn(normalized)
        val link = suspiciousLink.containsMatchIn(normalized) && linkAction.containsMatchIn(normalized)
        val asksMoneyWithNewNumber = asksForMoney && newNumber.containsMatchIn(normalized) && familyAddress.containsMatchIn(normalized)
        val predictedTactics = model?.tactics?.filterValues { it >= 0.70f }?.toSortedMap()?.toList()
            ?.take(8)?.associate { it.first to it.second }.orEmpty()

        var score = 0
        val factors = mutableListOf<String>()
        if (asksForMoney) { score += 14; factors += "Просьба о деньгах или переводе" }
        if (violentMoneyThreat) {
            score += 58
            factors += "Денежное требование подкреплено угрозой физической расправы"
        } else if (abusiveMoneyUltimatum) {
            score += 38
            factors += "Денежное требование сопровождается оскорбительным ультиматумом"
        }
        val moneyExtortion = asksForMoney && threatensDoxxing && (hasMoneyMention || moneyAction.containsMatchIn(normalized))
        if (moneyExtortion) {
            score += 42
            factors += "Денежная просьба связана с угрозой доксинга/свата — признак вымогательства"
        }
        if (isUrgent) { score += 20; factors += "Давление срочностью" }
        if (hasEmergency) { score += 14; factors += "Легенда о срочной проблеме" }
        if (asksForSecret) { score += 30; factors += "Просьба скрыть разговор" }
        if (asksForCode) { score += 60; factors += "Запрос кода или платёжных данных" }
        if (movesToUnsafeAccount) { score += 38; factors += "Перевод на новый или «безопасный» счёт" }
        if (impersonatesAuthority && (asksForMoney || asksForCode || movesToUnsafeAccount || asksRemoteAccess || link)) {
            score += 18; factors += "Возможная имитация официальной службы"
        }
        if (asksRemoteAccess) { score += 42; factors += "Запрос доступа к устройству или экрану" }
        if (threatensOrBaits && (link || asksForCode || asksForMoney || impersonatesAuthority)) {
            score += 18; factors += "Угроза или приманка связана с действием"
        }
        if (link) { score += 24; factors += "Ссылка с призывом перейти или подтвердить данные" }
        if (asksForMoney && isUrgent && hasEmergency) {
            score += 18
            factors += "Срочный денежный запрос под предлогом чрезвычайной ситуации"
        }
        if (asksForMoney && asksForSecret) score += 12
        if (asksForMoney && movesToUnsafeAccount) score += 10
        if (asksMoneyWithNewNumber) {
            score += 12
            factors += "Новый номер плюс просьба о деньгах от имени близкого"
        }

        // A small calibrated-model contribution is allowed only when independent local evidence exists.
        // The synthetic training set is not sufficient to make the model a standalone payment gate.
        // The targeted extortion case is deliberately an advisory for one event. Do not let the
        // classifier turn that same event into a hard hold; independent later signals still count.
        if (score > 0 && model != null && !moneyExtortion && !violentMoneyThreat) {
            when {
                model.label == "dangerous" && model.confidence >= 0.86f -> {
                    score += 8
                    factors += "Локальная модель согласна с независимыми признаками (${(model.confidence * 100).toInt()}%)"
                }
                model.label == "suspicious" && model.confidence >= 0.82f -> {
                    score += 5
                    factors += "Локальная модель видит подозрительный шаблон (${(model.confidence * 100).toInt()}%)"
                }
                model.label == "needs_check" && model.confidence >= 0.78f && score >= 20 -> {
                    score += 3
                    factors += "Модели недостаточно контекста для уверенного безопасного вывода"
                }
            }
        }
        val modelFlagsPayment = asksForMoney && model != null && when (model.label) {
            // This is an advisory threshold only. It can prompt independent verification,
            // but one message-level prediction never pauses the demo transfer.
            "dangerous" -> model.confidence >= 0.65f
            "suspicious" -> model.confidence >= 0.80f
            "needs_check" -> model.confidence >= 0.55f && (isUrgent || hasUltimatum || usesHostileLanguage)
            else -> false
        }
        val advisory = when {
            violentMoneyThreat -> "Требование денег связано с угрозой физической расправы. Демо-перевод приостановлен. Не встречайтесь с отправителем в одиночку; сохраните сообщение и проверьте ситуацию с доверенным человеком. При непосредственной опасности обратись в местную экстренную службу."
            abusiveMoneyUltimatum -> "Просьба о деньгах сопровождается оскорбительным ультиматумом. Это признак давления, хотя само сообщение не доказывает мошенничество. Не переводите под давлением; проверьте ситуацию по независимому каналу."
            moneyExtortion -> "В сообщении просьба о деньгах связана с угрозой раскрыть личные данные или устроить сватинг. Это похоже на вымогательство. Не переводите под давлением; сохраните сообщение и проверьте ситуацию по независимому каналу."
            modelFlagsPayment -> "Есть просьба перевести деньги, и локальная модель отметила сходство с рискованными сообщениями (уверенность класса ${(model!!.confidence * 100).toInt()}%, это не вероятность мошенничества). Перевод не приостановлен: если просьба неожиданна, подтвердите её звонком по уже известному номеру."
            asksForMoney && score >= ContextRisk.ADVISORY_THRESHOLD -> "Денежная просьба сопровождается срочностью или другими сигналами давления. Перевод не приостановлен. Если просьба неожиданна, подтвердите её по уже известному каналу."
            else -> null
        }
        val kind = when {
            violentMoneyThreat -> "violent_money_coercion"
            abusiveMoneyUltimatum -> "abusive_money_ultimatum"
            moneyExtortion -> "money_extortion"
            score == 0 && asksForMoney -> "ordinary_or_ambiguous_request"
            else -> "incoming_request"
        }
        return RiskSignal(
            score = score.coerceIn(0, 98),
            factors = factors.distinct(),
            modelLabel = model?.label ?: "unknown",
            modelConfidence = model?.confidence ?: 0f,
            modelTactics = predictedTactics,
            messageKind = kind,
            advisory = advisory
        )
    }

    private fun normalizeLookalikes(value: String): String {
        val cyrillicCount = value.count { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
        if (cyrillicCount < 2) return value
        val map = mapOf('a' to 'а', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'c' to 'с', 'x' to 'х', 'y' to 'у', 'k' to 'к', 'm' to 'м', 't' to 'т', 'b' to 'в', 'h' to 'н')
        return buildString(value.length) {
            value.forEach { ch -> append(if (ch.isUpperCase()) map[ch.lowercaseChar()]?.uppercaseChar() ?: ch else map[ch] ?: ch) }
        }
    }
}
