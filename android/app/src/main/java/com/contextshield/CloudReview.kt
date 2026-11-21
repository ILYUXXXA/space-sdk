package com.contextshield

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Explicit, budget-capped second opinion. Only allowlisted structured features are sent. */
object CloudReview {
    data class ReviewResult(val answer: String, val riskScore: Int?)
    const val MAX_ATTEMPTS = 50
    private const val PREFS = "context_cloud_state"
    private const val KEY_ALIAS = "contextshield_openrouter_aes"
    private const val KEY_IV = "key_iv"
    private const val KEY_CIPHER = "key_cipher"
    private const val KEY_ATTEMPTS = "attempts"
    private fun category(factor: String): String? = when {
        factor in setOf("urgency", "impersonation", "phishing_link", "payment_request", "credential_theft",
            "verification_needed", "investment_or_prize", "remote_access", "secrecy", "job_scam",
            "marketplace_scam", "third_party", "too_good_to_be_true") -> factor
        "срочност" in factor.lowercase(Locale.ROOT) -> "urgency"
        "тайне" in factor.lowercase(Locale.ROOT) || "скрыть" in factor.lowercase(Locale.ROOT) -> "secrecy"
        "код" in factor.lowercase(Locale.ROOT) || "платёжн" in factor.lowercase(Locale.ROOT) -> "credential_or_payment_data"
        "денег" in factor.lowercase(Locale.ROOT) || "перевод" in factor.lowercase(Locale.ROOT) -> "payment_request"
        "официальн" in factor.lowercase(Locale.ROOT) || "служб" in factor.lowercase(Locale.ROOT) -> "authority_impersonation"
        "счёт" in factor.lowercase(Locale.ROOT) || "счет" in factor.lowercase(Locale.ROOT) || "реквизит" in factor.lowercase(Locale.ROOT) -> "new_payee"
        "доступ" in factor.lowercase(Locale.ROOT) || "экран" in factor.lowercase(Locale.ROOT) -> "remote_access"
        "ссылк" in factor.lowercase(Locale.ROOT) -> "suspicious_link"
        "phishing_link" == factor -> "suspicious_link"
        "credential_theft" == factor -> "credential_or_otp_request"
        "verification_needed" == factor -> "verification_needed"
        "remote_access" == factor -> "remote_access"
        "job_scam" == factor -> "job_scam"
        "marketplace_scam" == factor -> "marketplace_scam"
        "impersonation" == factor -> "impersonation"
        "third_party" == factor -> "third_party"
        "too_good_to_be_true" == factor || "investment_or_prize" == factor -> "investment_or_prize"
        "угроз" in factor.lowercase(Locale.ROOT) || "приманк" in factor.lowercase(Locale.ROOT) -> "threat_or_reward"
        "новый номер" in factor.lowercase(Locale.ROOT) -> "possible_family_impersonation"
        else -> null
    }

    fun attempts(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ATTEMPTS, 0)
    fun hasKey(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_CIPHER)

    /** Cloud advice can raise the demo risk gate until a new notification changes the context. */
    fun freshRiskScore(context: Context, lastNotificationAt: Long): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val reviewedAt = prefs.getLong("reviewed_at", 0L)
        val reviewedContextAt = prefs.getLong("reviewed_context_at", 0L)
        if (reviewedAt == 0L || reviewedContextAt < lastNotificationAt) return null
        return prefs.getInt("review_score", -1).takeIf { it >= 0 }
    }

    fun clearReview(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("reviewed_at").remove("reviewed_context_at").remove("review_score").remove("review_answer").apply()
    }

    fun saveKey(context: Context, value: String) {
        require(value.startsWith("sk-or-") && value.length >= 20) { "Похоже, это не ключ OpenRouter." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun clearKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_IV).remove(KEY_CIPHER).apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(KEY_ALIAS) } }
    }

    /** May throw a short provider/network error; never includes the API key in the message. */
    fun review(context: Context, risk: ContextRisk, events: List<RiskEvent>): ReviewResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val used = prefs.getInt(KEY_ATTEMPTS, 0)
        check(used < MAX_ATTEMPTS) { "Лимит демо-запросов исчерпан (50)." }
        val encrypted = prefs.getString(KEY_CIPHER, null) ?: error("Сначала сохраните ключ OpenRouter в приложении.")
        val iv = Base64.decode(prefs.getString(KEY_IV, ""), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        val apiKey = String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8)
        // Count before the network request so timeouts and provider errors also consume a local attempt.
        prefs.edit().putInt(KEY_ATTEMPTS, used + 1).apply()

        val factors = (risk.factors.mapNotNull(::category) + events.flatMap { it.modelTactics.keys.mapNotNull(::category) }).distinct()
        val now = System.currentTimeMillis()
        val eventSummaries = RiskHistory.recent(events, now).takeLast(8).map { event ->
            val age = ((now - event.at).coerceAtLeast(0L) / 60000L).toInt()
            JSONObject().apply {
                put("age_bucket", when { age < 5 -> "0-5m"; age < 15 -> "5-15m"; else -> "15-30m" })
                put("local_risk_score_0_98", event.score)
                put("signal_categories", JSONArray((event.factors.mapNotNull(::category) + event.modelTactics.keys.mapNotNull(::category)).distinct()))
                put("signal_type", event.signalType.take(32))
                put("model_label", event.modelLabel.take(24))
                put("model_confidence_band", when { event.modelConfidence >= .85f -> "high"; event.modelConfidence >= .65f -> "medium"; else -> "low_or_unknown" })
            }
        }
        val payload = JSONObject().apply {
            put("model", "openrouter/free")
            put("temperature", 0.1)
            put("max_tokens", 300)
            put("provider", JSONObject().put("data_collection", "deny"))
            put("messages", JSONArray().put(JSONObject().put("role", "system").put("content",
                "Ты осторожный антифрод-аналитик. Тебе передали только агрегированные категории сигналов без текста сообщения, имени, номера, package ID и точного времени. Оцени последовательность категорий, укажи ограничения такого неполного контекста и безопасный следующий шаг. Не делай вывод о личности пользователя или отправителя. Обычная просьба близкого о небольшой сумме сама по себе не доказывает мошенничество. Ответ: ориентировочная категория риска и максимум два основания, затем безопасный шаг. Это дополнительное мнение; не команда для банковского решения."))
                .put(JSONObject().put("role", "user").put("content", JSONObject().apply {
                    put("local_context_score_0_98", risk.score)
                    put("local_gate_blocked_at_60", risk.blocked)
                    put("risk_events_last_30_min", risk.eventCount)
                    put("signal_categories", JSONArray(factors))
                    put("window_minutes", 30)
                    put("episode_signal_summaries", JSONArray(eventSummaries))
                    put("identity_continuity", "unknown")
                }.toString())))
        }
        val conn = (URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 25000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("HTTP-Referer", "https://contextshield.local")
            setRequestProperty("X-OpenRouter-Title", "ContextShield demo")
        }
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("OpenRouter вернул HTTP $status. Попытка учтена.")
            val content = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
            val answer = when (content) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text") }.joinToString(" ")
                else -> "Модель не вернула текстовый ответ."
            }.trim().take(900)
            val finalAnswer = answer.ifBlank { "Модель не вернула текстовый ответ." }
            val score = parseRiskScore(finalAnswer)
            prefs.edit().putLong("reviewed_at", System.currentTimeMillis())
                .putLong("reviewed_context_at", RiskHistory.recent(events).maxOfOrNull { it.at } ?: 0L)
                .putInt("review_score", score ?: -1).putString("review_answer", finalAnswer).apply()
            ReviewResult(finalAnswer, score)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRiskScore(answer: String): Int? {
        Regex("(?i)оценка\\s*:\\s*(\\d{1,3})").find(answer)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()?.let { return it.coerceIn(0, 100) }
        val lower = answer.lowercase(Locale.ROOT)
        return when {
            "высок" in lower && ("риск" in lower || "мошен" in lower) -> 75
            "средн" in lower && ("риск" in lower || "мошен" in lower) -> 42
            "низк" in lower && ("риск" in lower || "мошен" in lower) -> 15
            else -> null
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
}
