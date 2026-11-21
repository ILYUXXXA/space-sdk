package com.contextshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class MessageNotificationListener : NotificationListenerService() {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val channelId = "context_risk_signals"
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
        { runnable -> Thread(runnable, "contextshield-local-analysis").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

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

    override fun onDestroy() {
        worker.shutdownNow()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("listener_connected", false).apply()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt("received_count", prefs.getInt("received_count", 0) + 1).apply()
        val text = notificationText(notification).trim()
        if (text.length < 2) {
            prefs.edit().putString("last_notification_state", "Есть уведомление, но система не раскрыла текст")
                .apply()
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
            return
        }

        // Dedupe key and raw text live only in memory. Nothing is logged or persisted here.
        val key = "${sbn.key}:${text.hashCode()}"
        if (!seen.add(key)) return
        if (seen.size > 500) seen.clear()
        val sourcePackage = sbn.packageName
        try {
            worker.execute { analyzeAndStore(text.take(4000), sourcePackage) }
        } catch (_: RejectedExecutionException) {
            prefs.edit()
                .putInt("dropped_count", prefs.getInt("dropped_count", 0) + 1)
                .putString("last_notification_state", "Очередь заполнена; часть уведомлений пропущена")
                .apply()
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
        }
    }

    private fun analyzeAndStore(text: String, sourcePackage: String) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val source = runCatching {
                val app = packageManager.getApplicationInfo(sourcePackage, 0)
                packageManager.getApplicationLabel(app).toString().take(48)
            }.getOrDefault("Уведомление")
            val model = OnDeviceModel.assess(applicationContext, text)
            val result = LocalRisk.assess(text, model)
            val event = RiskEvent(
                at = System.currentTimeMillis(),
                score = result.score,
                factors = result.factors,
                source = source,
                modelLabel = result.modelLabel,
                modelConfidence = result.modelConfidence,
                modelTactics = result.modelTactics,
                identityContinuity = "UNKNOWN",
                signalType = "notification",
                advisory = result.advisory
            )
            RiskHistory.append(applicationContext, event)
            val contextRisk = RiskHistory.assess(RiskHistory.read(applicationContext))
            val editor = prefs.edit()
                .putInt("scanned_count", prefs.getInt("scanned_count", 0) + 1)
                .putString("last_notification_state", "Уведомление проверено локально")
                .putInt("last_score", result.score)
                .putInt("context_score", contextRisk.score)
                .putString("last_factors", result.factors.joinToString("|"))
                .putLong("last_checked_at", event.at)
                .putString("last_model_label", result.modelLabel)
                .putFloat("last_model_confidence", result.modelConfidence)
            if (result.high) editor.putInt("high_risk_count", prefs.getInt("high_risk_count", 0) + 1)
            editor.apply()
            when {
                contextRisk.blocked -> postRiskAlert(
                    "Перевод приостановлен",
                    "Найдены несколько сильных признаков риска: ${contextRisk.factors.take(2).joinToString(" · ")}. Проверьте ситуацию по известному каналу."
                )
                result.advisory != null -> postRiskAlert("Проверьте просьбу о переводе", result.advisory)
            }
        } catch (_: Exception) {
            // Never include notification contents, model inputs or keys in an error log.
            prefs.edit().putString("last_notification_state", "Локальный анализ временно недоступен")
                .apply()
        } finally {
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
        }
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

    private fun postRiskAlert(title: String, message: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            val alert = Notification.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message.take(110))
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build()
            manager.notify(4201, alert)
        }
    }

    companion object {
        const val PREFS = "context_local_state"
        const val ACTION_STATUS = "com.contextshield.STATUS_CHANGED"
    }
}
