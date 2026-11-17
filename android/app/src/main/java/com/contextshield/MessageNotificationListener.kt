package com.contextshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

class MessageNotificationListener : NotificationListenerService() {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val channelId = "context_risk_signals"

    override fun onListenerConnected() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("listener_connected", true).apply()
        ensureChannel()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    override fun onListenerDisconnected() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("listener_connected", false).apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt("received_count", prefs.getInt("received_count", 0) + 1).apply()
        val text = notificationText(n).trim()
        if (text.length < 2) {
            prefs.edit().putString("last_notification_state", "Получено уведомление без читаемого текста (${sbn.packageName})").apply()
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
            return
        }
        val key = "${sbn.packageName}:${sbn.id}:${text.hashCode()}"
        if (!seen.add(key)) return
        if (seen.size > 500) seen.clear()

        val result = LocalRisk.assess(text)
        val source = runCatching {
            val app = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(app).toString().take(48)
        }.getOrDefault("Уведомление")
        val event = RiskEvent(System.currentTimeMillis(), result.score, result.factors, source, PrivacyRedactor.redact(text))
        RiskHistory.append(prefs, event)
        val contextRisk = RiskHistory.assess(RiskHistory.read(prefs))
        val editor = prefs.edit()
            .putInt("scanned_count", prefs.getInt("scanned_count", 0) + 1)
            .putString("last_notification_state", "Обработано: $source")
            .putInt("last_score", result.score)
            .putInt("context_score", contextRisk.score)
            .putString("last_factors", result.factors.joinToString("|"))
            .putLong("last_checked_at", event.at)
        if (result.high) editor.putInt("high_risk_count", prefs.getInt("high_risk_count", 0) + 1)
        editor.apply()

        if (contextRisk.blocked) postRiskAlert(contextRisk.factors.ifEmpty { result.factors })
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
        // Raw `text` stays in memory for local scoring/redaction; only redactedContent is saved.
    }

    private fun notificationText(n: Notification): String {
        val extras = n.extras ?: return ""
        val parts = mutableListOf<String>()
        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT).forEach { key ->
            extras.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
        }
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        messages?.forEach { item ->
            val bundle = item as? android.os.Bundle ?: return@forEach
            bundle.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
        }
        return parts.distinct().joinToString(" ").take(4000)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channelId, "Сигналы риска", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Предупреждения о признаках давления в уведомлениях"
            })
        }
    }

    private fun postRiskAlert(factors: List<String>) {
        val manager = getSystemService(NotificationManager::class.java)
        val summary = factors.take(2).joinToString(" · ").ifBlank { "Признаки давления" }
        val alert = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Проверьте сообщение перед переводом")
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText("Найдены признаки возможного давления: $summary. Свяжитесь с человеком по известному номеру и проверьте получателя отдельно."))
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), alert)
    }

    companion object {
        const val PREFS = "context_local_state"
        const val ACTION_STATUS = "com.contextshield.STATUS_CHANGED"
    }
}
