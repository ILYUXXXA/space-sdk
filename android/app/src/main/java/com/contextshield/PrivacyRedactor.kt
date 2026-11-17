package com.contextshield

/** Best-effort on-device PII scrubber. Raw notification text is passed in memory only. */
object PrivacyRedactor {
    private val email = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val url = Regex("(?i)\\b(?:https?://|www\\.)\\S+|\\b[a-z0-9.-]+\\.(?:ru|com|net|org|io|me|рф)(?:/\\S*)?")
    private val iban = Regex("(?i)\\b[A-Z]{2}\\s?\\d{2}(?:[ -]?[A-Z0-9]){11,30}\\b")
    private val card = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
    private val phone = Regex("(?<!\\w)(?:\\+?\\d[\\d ()-]{6,}\\d)(?!\\w)")
    private val otp = Regex("(?i)\\b(код|пароль|pin|пин|sms|смс|cvv|cvc)(\\s*(?:из|для|:|-)?\\s*)(\\d{3,8})\\b")
    private val handle = Regex("(?<!\\w)@[A-Za-z0-9_.]{3,}")
    private val named = Regex("(?i)\\b(меня зовут|зовут|для|кому|от|получатель|имя)\\s+([A-ZА-ЯЁ][A-ZА-ЯЁa-zа-яё-]{2,}(?:\\s+[A-ZА-ЯЁ][A-ZА-ЯЁa-zа-яё-]{2,}){0,2})")
    private val capitalizedPair = Regex("\\b[A-ZА-ЯЁ][a-zа-яё-]{2,}\\s+[A-ZА-ЯЁ][a-zа-яё-]{2,}\\b")
    private val nameAfterGreeting = Regex("(?i)(привет|здравствуйте|добрый день|доброе утро|уважаемый|уважаемая)[,! ]+([A-ZА-ЯЁ][a-zа-яё-]{2,})")
    private val spaces = Regex("\\s+")

    fun redact(input: String): String {
        var text = input.take(4000)
        text = email.replace(text, "[EMAIL]")
        text = url.replace(text, "[ССЫЛКА]")
        text = iban.replace(text, "[БАНКОВСКИЙ_ИДЕНТИФИКАТОР]")
        text = card.replace(text, "[НОМЕР_КАРТЫ]")
        text = otp.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}[КОД]" }
        text = phone.replace(text, "[ТЕЛЕФОН]")
        text = handle.replace(text, "[АККАУНТ]")
        text = named.replace(text) { "${it.groupValues[1]} [ИМЯ]" }
        text = capitalizedPair.replace(text, "[ИМЯ]")
        text = nameAfterGreeting.replace(text) { "${it.groupValues[1]} [ИМЯ]" }
        return spaces.replace(text, " ").trim().take(700)
    }
}
