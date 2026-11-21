package com.contextshield

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.max

/** Only event features and explanations are persisted. Notification text is never stored. */
data class RiskEvent(
    val at: Long,
    val score: Int,
    val factors: List<String>,
    val source: String,
    val modelLabel: String = "unknown",
    val modelConfidence: Float = 0f,
    val modelTactics: Map<String, Float> = emptyMap(),
    val identityContinuity: String = "UNKNOWN",
    val signalType: String = "notification",
    val advisory: String? = null
)

data class ContextRisk(val score: Int, val eventCount: Int, val factors: List<String>, val advisory: String? = null) {
    val blocked: Boolean get() = score >= THRESHOLD

    companion object {
        const val THRESHOLD = 60
    }
}

object RiskHistory {
    private const val STORE = "context_encrypted_episode_store_v1"
    private const val CIPHER_KEY = "events_ciphertext"
    private const val IV_KEY = "events_iv"
    private const val KEY_ALIAS = "contextshield_episode_aes_v1"
    private const val LEGACY_KEY_EVENTS = "risk_events_v1"
    private const val KEEP_EVENTS = 60
    const val WINDOW_MS = 30 * 60 * 1000L

    @Synchronized
    fun append(context: Context, event: RiskEvent) {
        val now = event.at
        val events = read(context).filter { it.at <= now && now - it.at < WINDOW_MS }.toMutableList()
        events.add(event)
        write(context, events.takeLast(KEEP_EVENTS))
    }

    @Synchronized
    fun read(context: Context): List<RiskEvent> {
        val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val encoded = store.getString(CIPHER_KEY, null)
        if (!encoded.isNullOrBlank()) {
            val iv = store.getString(IV_KEY, null) ?: return emptyList()
            return runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                val plain = String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8)
                decode(plain)
            }.getOrDefault(emptyList())
        }
        return migrateLegacySummaries(context)
    }

    fun recent(events: List<RiskEvent>, now: Long = System.currentTimeMillis()): List<RiskEvent> =
        events.filter { it.at <= now && now - it.at < WINDOW_MS }

    /** Combine bounded, decayed evidence. Repeated benign requests alone stay below the gate. */
    fun assess(events: List<RiskEvent>, now: Long = System.currentTimeMillis()): ContextRisk {
        val recent = recent(events, now)
        val signalEvents = recent.filter { it.score > 0 || it.factors.isNotEmpty() }
        if (signalEvents.isEmpty()) return ContextRisk(0, 0, emptyList())
        val weightedScores = signalEvents.map { event ->
            val freshness = 1.0 - ((now - event.at).toDouble() / WINDOW_MS).coerceIn(0.0, 1.0)
            event.score * freshness
        }
        val strongest = (weightedScores.maxOrNull() ?: 0.0).toInt()
        val factors = signalEvents.flatMap { it.factors }.distinct().take(8)
        val distinctSignals = signalEvents.map { it.signalType }.distinct().size
        val contextual = (weightedScores.sum() * 0.38).toInt() +
            (factors.size - 1).coerceAtLeast(0) * 8 +
            (signalEvents.size - 1).coerceAtLeast(0).coerceAtMost(8) * 2 +
            (distinctSignals - 1).coerceAtLeast(0) * 4
        val advisory = signalEvents.asSequence().filter { !it.advisory.isNullOrBlank() }.maxByOrNull { it.at }?.advisory
        return ContextRisk(max(strongest, contextual).coerceIn(0, 98), signalEvents.size, factors, advisory)
    }

    @Synchronized
    fun clearSession(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(KEY_ALIAS) } }
        val prefs = context.getSharedPreferences(MessageNotificationListener.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(LEGACY_KEY_EVENTS)
            .putInt("scanned_count", 0)
            .putInt("high_risk_count", 0)
            .putInt("received_count", 0)
            .putInt("dropped_count", 0)
            .putInt("last_score", 0)
            .putString("last_factors", "")
            .putLong("last_checked_at", 0L)
            .remove("last_model_label")
            .remove("last_model_confidence")
            .apply()
    }

    private fun migrateLegacySummaries(context: Context): List<RiskEvent> {
        val prefs = context.getSharedPreferences(MessageNotificationListener.PREFS, Context.MODE_PRIVATE)
        val legacy = prefs.getString(LEGACY_KEY_EVENTS, null)
        if (!legacy.isNullOrBlank()) {
            val migrated = runCatching { decode(legacy) }.getOrDefault(emptyList())
                .map { it.copy(signalType = "notification", identityContinuity = "UNKNOWN") }
                .takeLast(KEEP_EVENTS)
            write(context, migrated)
            prefs.edit().remove(LEGACY_KEY_EVENTS).apply()
            return migrated
        }
        // Migrate the summary from the earliest notification-only prototype without carrying text.
        val checkedAt = prefs.getLong("last_checked_at", 0L)
        if (checkedAt == 0L || prefs.getInt("scanned_count", 0) == 0) return emptyList()
        val factors = prefs.getString("last_factors", "").orEmpty().split('|').filter(String::isNotBlank)
        val migrated = listOf(RiskEvent(checkedAt, prefs.getInt("last_score", 0), factors, "Android"))
        write(context, migrated)
        return migrated
    }

    private fun write(context: Context, events: List<RiskEvent>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(encode(events).toByteArray(StandardCharsets.UTF_8))
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CIPHER_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    private fun encode(events: List<RiskEvent>): String {
        val array = JSONArray()
        events.takeLast(KEEP_EVENTS).forEach { event ->
            array.put(JSONObject().apply {
                put("at", event.at)
                put("score", event.score.coerceIn(0, 98))
                put("factors", JSONArray(event.factors.take(8)))
                put("source", event.source.take(48))
                put("model_label", event.modelLabel.take(24))
                put("model_confidence", event.modelConfidence.coerceIn(0f, 1f).toDouble())
                put("model_tactics", JSONObject().apply {
                    event.modelTactics.entries.take(8).forEach { (name, confidence) ->
                        put(name.take(48), confidence.coerceIn(0f, 1f).toDouble())
                    }
                })
                put("identity_continuity", "UNKNOWN")
                put("signal_type", event.signalType.take(32))
                event.advisory?.take(500)?.let { put("advisory", it) }
            })
        }
        return array.toString()
    }

    private fun decode(raw: String): List<RiskEvent> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val factors = item.optJSONArray("factors") ?: JSONArray()
            RiskEvent(
                at = item.optLong("at"),
                score = item.optInt("score").coerceIn(0, 98),
                factors = (0 until factors.length()).map { factors.optString(it) }.filter(String::isNotBlank),
                source = item.optString("source", "Уведомление").take(48),
                modelLabel = item.optString("model_label", "unknown").take(24),
                modelConfidence = item.optDouble("model_confidence", 0.0).toFloat().coerceIn(0f, 1f),
                modelTactics = item.optJSONObject("model_tactics")?.let { objectValue ->
                    objectValue.keys().asSequence().take(8).associateWith { key -> objectValue.optDouble(key, 0.0).toFloat().coerceIn(0f, 1f) }
                }.orEmpty(),
                identityContinuity = "UNKNOWN",
                signalType = item.optString("signal_type", "notification").take(32),
                advisory = item.optString("advisory").takeIf { it.isNotBlank() }?.take(500)
            )
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }
}
