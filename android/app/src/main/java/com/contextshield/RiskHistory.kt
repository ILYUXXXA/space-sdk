package com.contextshield

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

data class RiskEvent(
    val at: Long,
    val score: Int,
    val factors: List<String>,
    val source: String,
    val redactedContent: String = ""
)

data class ContextRisk(val score: Int, val eventCount: Int, val factors: List<String>) {
    val blocked: Boolean get() = score >= THRESHOLD

    companion object {
        const val THRESHOLD = 60
    }
}

/** Stores short, locally redacted snippets and risk summaries. Raw notification text is never persisted. */
object RiskHistory {
    private const val KEY_EVENTS = "risk_events_v1"
    private const val KEEP_EVENTS = 60
    const val WINDOW_MS = 30 * 60 * 1000L

    fun append(prefs: SharedPreferences, event: RiskEvent) {
        val now = event.at
        val events = read(prefs).filter { it.at <= now && now - it.at < WINDOW_MS }.toMutableList()
        events.add(event)
        val encoded = JSONArray()
        events.takeLast(KEEP_EVENTS).forEach { item ->
            encoded.put(JSONObject().apply {
                put("at", item.at)
                put("score", item.score.coerceIn(0, 98))
                put("factors", JSONArray(item.factors.take(8)))
                put("source", item.source.take(48))
                put("redacted_content", item.redactedContent.take(700))
            })
        }
        prefs.edit().putString(KEY_EVENTS, encoded.toString()).apply()
    }

    fun read(prefs: SharedPreferences): List<RiskEvent> {
        val raw = prefs.getString(KEY_EVENTS, null)
        if (raw.isNullOrBlank()) {
            // Migrate the summary saved by the first notification-only build.
            val checkedAt = prefs.getLong("last_checked_at", 0L)
            if (checkedAt == 0L || prefs.getInt("scanned_count", 0) == 0) return emptyList()
            val oldFactors = prefs.getString("last_factors", "").orEmpty()
                .split('|').filter(String::isNotBlank)
            return listOf(RiskEvent(checkedAt, prefs.getInt("last_score", 0), oldFactors, "Android"))
        }
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val factors = item.optJSONArray("factors") ?: JSONArray()
                RiskEvent(
                    at = item.optLong("at"),
                    score = item.optInt("score").coerceIn(0, 98),
                    factors = (0 until factors.length()).map { index -> factors.optString(index) }.filter(String::isNotBlank),
                    source = item.optString("source", "Уведомление").take(48),
                    redactedContent = item.optString("redacted_content", "").take(700)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun recent(events: List<RiskEvent>, now: Long = System.currentTimeMillis()): List<RiskEvent> =
        events.filter { it.at <= now && now - it.at < WINDOW_MS }

    /** Transparent context score: decayed local scores plus a small bonus for distinct signal types. */
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
        // Repeated harmless transfer requests should not add up to a block by themselves.
        // Escalate for several different scam signals and repeated activity, while retaining a strong single-message score.
        val contextual = (weightedScores.sum() * 0.45).toInt() + (factors.size - 1).coerceAtLeast(0) * 10 +
            (signalEvents.size - 1).coerceAtLeast(0).coerceAtMost(8) * 2
        val score = max(strongest, contextual).coerceIn(0, 98)
        return ContextRisk(score, signalEvents.size, factors)
    }

    fun clearSession(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_EVENTS)
            .putInt("scanned_count", 0)
            .putInt("high_risk_count", 0)
            .putInt("received_count", 0)
            .putInt("last_score", 0)
            .putString("last_factors", "")
            .putLong("last_checked_at", 0L)
            .apply()
    }
}
