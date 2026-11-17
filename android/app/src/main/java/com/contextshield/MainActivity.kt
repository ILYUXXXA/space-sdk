package com.contextshield

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var gate: TextView
    private lateinit var transfer: Button
    private lateinit var graph: RiskTrendView
    private lateinit var events: TextView
    private lateinit var cloudState: TextView
    private lateinit var cloudButton: Button
    private val cloudExecutor = Executors.newSingleThreadExecutor()
    private val updates = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) = refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF10231D.toInt()
        window.navigationBarColor = 0xFF10231D.toInt()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(28)); setBackgroundColor(0xFFF3F6F4.toInt()) }
        page.addView(label("DEMO BANK  ·  CONTEXT SHIELD", 12, 0xFF16875C.toInt()))
        page.addView(label("Главная", 28, 0xFF14251E.toInt(), 12))
        page.addView(label("Защита оценивает новые уведомления на телефоне и складывает сигналы за 30 минут.", 14, 0xFF64736B.toInt(), 3))

        val bankCard = card(0xFF10231D.toInt())
        bankCard.addView(label("ТЕСТОВЫЙ СЧЁТ", 12, 0xFF9ECBB5.toInt()))
        bankCard.addView(label("••••  2048", 25, Color.WHITE, 8))
        bankCard.addView(label("Доступно для демо  ·  48 250 ₽", 14, 0xFFCEE2D7.toInt(), 3))
        transfer = Button(this).apply {
            text = "Перевести"; isAllCaps = false; textSize = 17f; setTextColor(Color.WHITE); setOnClickListener { confirmDemoTransfer() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(18) }
        }
        bankCard.addView(transfer)
        page.addView(bankCard)

        page.addView(card(0xFFE8F2EC.toInt()).apply {
            addView(label("СЦЕНАРИЙ ДЛЯ ДЕМО", 12, 0xFF16875C.toInt()))
            addView(label("Покажи, как ContextShield связывает сообщение с попыткой перевода.", 14, 0xFF42564B.toInt(), 5))
            addView(button("Загрузить сценарий мошенничества") { loadInvestorScenario() })
            addView(button("Сбросить сценарий") {
                RiskHistory.clearSession(getSharedPreferences(MessageNotificationListener.PREFS, MODE_PRIVATE))
                refresh()
            })
        })

        gate = label("", 15, 0xFF14251E.toInt(), 10)
        page.addView(card(0xFFFFFFFF.toInt()).apply { addView(label("РЕШЕНИЕ ПЕРЕД ПЕРЕВОДОМ", 12, 0xFF16875C.toInt())); addView(gate) })
        page.addView(card(0xFFFFFFFF.toInt()).apply {
            addView(label("Активность за 30 минут", 18, 0xFF14251E.toInt()))
            metrics = label("", 14, 0xFF64736B.toInt(), 6); addView(metrics)
            graph = RiskTrendView(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)); setBackgroundColor(0xFFF7FAF8.toInt()) }
            addView(graph)
            addView(label("Риск 0–100 · пунктир — порог блокировки 60", 11, 0xFF78857E.toInt(), 4))
        })
        page.addView(card(0xFFFFFFFF.toInt()).apply { addView(label("Сигналы и объяснение", 18, 0xFF14251E.toInt())); events = label("", 14, 0xFF64736B.toInt(), 7); addView(events) })

        val accessCard = card(0xFFFFFFFF.toInt())
        accessCard.addView(label("Подключение Android", 18, 0xFF14251E.toInt()))
        status = label("", 13, 0xFF64736B.toInt(), 6); accessCard.addView(status)
        accessCard.addView(button("Включить доступ к уведомлениям") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        accessCard.addView(button("Разрешить системные предупреждения") { if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7) })
        page.addView(accessCard)

        val cloudCard = card(0xFFFFFFFF.toInt())
        cloudCard.addView(label("Облачная перепроверка · OpenRouter", 18, 0xFF14251E.toInt()))
        cloudState = label("", 13, 0xFF64736B.toInt(), 6); cloudCard.addView(cloudState)
        cloudCard.addView(button("Сохранить / заменить ключ") { enterKey() })
        cloudButton = button("Проверить контекст в OpenRouter") { runCloudReview() }; cloudCard.addView(cloudButton)
        cloudCard.addView(button("Удалить ключ с телефона") { CloudReview.clearKey(this); refresh() })
        page.addView(cloudCard)

        page.addView(button("Сбросить демо-сессию и график") {
            AlertDialog.Builder(this).setTitle("Сбросить локальную историю?")
                .setMessage("Удалятся счётчики и сигналы графика. Ключ OpenRouter и счётчик его запросов сохранятся.")
                .setNegativeButton("Отмена", null).setPositiveButton("Сбросить") { _, _ -> RiskHistory.clearSession(getSharedPreferences(MessageNotificationListener.PREFS, MODE_PRIVATE)); CloudReview.clearReview(this); refresh() }.show()
        })
        page.addView(label("Это демонстрационный экран, а не подключение к настоящему банку. Перевод не выполняется и деньги не перемещаются. Решение локального правила эвристическое; облако — только совет и не может открыть заблокированную кнопку.", 12, 0xFF78857E.toInt(), 14))
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    override fun onResume() { super.onResume(); val filter = IntentFilter(MessageNotificationListener.ACTION_STATUS); if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(updates, filter); refresh() }
    override fun onPause() { runCatching { unregisterReceiver(updates) }; super.onPause() }

    private fun refresh() {
        if (!::status.isInitialized) return
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val enabled = enabledListeners.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it.packageName == packageName }
        val p = getSharedPreferences(MessageNotificationListener.PREFS, MODE_PRIVATE)
        val history = RiskHistory.read(p)
        val risk = RiskHistory.assess(history)
        val cloudScore = CloudReview.freshRiskScore(this, p.getLong("last_checked_at", 0L))
        val effectiveScore = max(risk.score, cloudScore ?: 0)
        val blocked = risk.blocked || (cloudScore ?: 0) >= ContextRisk.THRESHOLD
        val serviceConnected = p.getBoolean("listener_connected", false)
        status.text = when { !enabled -> "○ Включи доступ к уведомлениям ниже"; serviceConnected -> "● Сервис подключён и слушает уведомления"; else -> "◌ Доступ включён, сервис ждёт переподключения Android" }
        metrics.text = "Входящих: ${p.getInt("received_count", 0)}   ·   Проанализировано: ${p.getInt("scanned_count", 0)}   ·   Сильные сигналы: ${p.getInt("high_risk_count", 0)}\nПоследнее: ${p.getString("last_notification_state", "пока уведомлений не было")}\nСигналов в контексте: ${risk.eventCount}   ·   Локальный риск: ${risk.score}/98${cloudScore?.let { "   ·   Облако: $it/100" } ?: ""}"
        val graphEvents = RiskHistory.recent(history)
        graph.setValues(graphEvents.mapIndexed { index, event -> RiskHistory.assess(graphEvents.take(index + 1), event.at).score }.takeLast(12))
        transfer.isEnabled = !blocked
        transfer.setBackgroundColor(if (blocked) 0xFFB7C0BB.toInt() else 0xFF16875C.toInt())
        gate.text = if (blocked) "⛔ Демо-перевод приостановлен. ${if ((cloudScore ?: 0) >= ContextRisk.THRESHOLD && !risk.blocked) "Облачная проверка оценила контекст в $cloudScore/100. " else ""}${risk.factors.joinToString(", ").ifBlank { "Риск выше порога ${ContextRisk.THRESHOLD}" }}. Проверьте ситуацию по независимому каналу."
            else "✓ Кнопка перевода доступна. Локальный риск ниже порога ${ContextRisk.THRESHOLD}. Это не гарантия безопасности — перепроверьте получателя."
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        events.text = if (history.isEmpty()) "Пока нет сигналов. Уведомления проверяются после включения Android-доступа." else
            RiskHistory.recent(history).takeLast(5).asReversed().joinToString("\n\n") { e ->
                "${formatter.format(Date(e.at))}  ·  риск ${e.score}/98\n${e.factors.joinToString(" · ").ifBlank { "Совпадений с локальными правилами нет" }}"
            }.ifBlank { "За последние 30 минут сигналов нет." }
        val used = CloudReview.attempts(this)
        val messageCount = RiskHistory.recent(history).count { it.redactedContent.isNotBlank() }.coerceAtMost(8)
        cloudState.text = "${if (CloudReview.hasKey(this)) "Ключ зашифрован Android Keystore" else "Ключ ещё не добавлен"} · демо-запросов: $used/${CloudReview.MAX_ATTEMPTS}${cloudScore?.let { " · оценка модели: $it/100" } ?: ""}\nПо нажатию уйдут до $messageCount последних сообщений за 30 минут после локальной очистки телефонов, email, ссылок, кодов, номеров карт и распознаваемых имён. Название приложения и точное время не отправляются. Автоматической отправки нет; исключаются провайдеры, отмеченные как собирающие данные. Оценка ≥${ContextRisk.THRESHOLD} приостанавливает демо-перевод до нового уведомления или сброса сессии."
        cloudButton.isEnabled = CloudReview.hasKey(this) && used < CloudReview.MAX_ATTEMPTS
        cloudButton.alpha = if (cloudButton.isEnabled) 1f else 0.45f
    }

    private fun runCloudReview() {
        val history = RiskHistory.read(getSharedPreferences(MessageNotificationListener.PREFS, MODE_PRIVATE))
        val risk = RiskHistory.assess(history)
        cloudButton.isEnabled = false; cloudButton.text = "Запрос отправляется…"
        refresh()
        cloudExecutor.execute {
            val result = runCatching { CloudReview.review(this, risk, history) }
            runOnUiThread {
                cloudButton.text = "Проверить контекст в OpenRouter"
                refresh()
                AlertDialog.Builder(this).setTitle(if (result.isSuccess) "Совет модели (не решение банка)" else "Облачная проверка не выполнена")
                    .setMessage(result.getOrNull()?.answer ?: result.exceptionOrNull()?.message ?: "Неизвестная ошибка")
                    .setPositiveButton("Понятно", null).show()
            }
        }
    }

    private fun enterKey() {
        val input = EditText(this).apply { hint = "sk-or-…"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("Ключ OpenRouter")
            .setMessage("Ключ хранится зашифрованным в Android Keystore на этом телефоне; не отправляйте его в чат и не встраивайте в APK.")
            .setView(input).setNegativeButton("Отмена", null).setPositiveButton("Сохранить") { _, _ ->
                runCatching { CloudReview.saveKey(this, input.text.toString().trim()) }
                    .onFailure { AlertDialog.Builder(this).setMessage(it.message ?: "Ключ не сохранён").setPositiveButton("OK", null).show() }
                refresh()
            }.show()
    }

    private fun loadInvestorScenario() {
        val prefs = getSharedPreferences(MessageNotificationListener.PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val samples = listOf(
            "Здравствуйте, я сотрудник службы безопасности банка. По вашему счёту обнаружена подозрительная операция.",
            "Нужно срочно перевести деньги на безопасный счёт прямо сейчас, времени на проверку нет.",
            "Никому не говорите об этом звонке и не связывайтесь с банком — просто выполните перевод."
        )
        samples.forEachIndexed { index, message ->
            val signal = LocalRisk.assess(message)
            RiskHistory.append(prefs, RiskEvent(
                at = now - (2 - index) * 45_000L,
                score = signal.score,
                factors = signal.factors,
                source = "Демо-сценарий",
                redactedContent = message
            ))
        }
        prefs.edit()
            .putInt("received_count", prefs.getInt("received_count", 0) + samples.size)
            .putInt("scanned_count", prefs.getInt("scanned_count", 0) + samples.size)
            .putInt("high_risk_count", prefs.getInt("high_risk_count", 0) + 1)
            .putString("last_notification_state", "Демо: обнаружена цепочка социальной инженерии")
            .apply()
        refresh()
        AlertDialog.Builder(this)
            .setTitle("Контекст собран")
            .setMessage("Добавлены 3 связанных события: имитация банка, срочность и требование скрыть разговор. Теперь открой демо-перевод — система оценит не только сообщение, но и нового получателя.")
            .setPositiveButton("К переводу") { _, _ -> confirmDemoTransfer() }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun confirmDemoTransfer() {
        val recipient = EditText(this).apply {
            hint = "Например, Алексей Иванов"
            setSingleLine(true)
        }
        val amount = EditText(this).apply {
            hint = "Сумма, ₽"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }
        val isNewRecipient = android.widget.CheckBox(this).apply {
            text = "Получатель новый для меня"
            isChecked = true
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
            addView(label("Получатель", 13, 0xFF64736B.toInt()))
            addView(recipient)
            addView(label("Сумма", 13, 0xFF64736B.toInt(), 8))
            addView(amount)
            addView(isNewRecipient)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Новый демо-перевод")
            .setMessage("Перед подтверждением ContextShield проверит текущий контекст.")
            .setView(form)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Проверить риск", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = recipient.text.toString().trim()
                val value = amount.text.toString().replace(',', '.').toDoubleOrNull()
                if (name.isBlank() || value == null || value <= 0.0) {
                    AlertDialog.Builder(this).setMessage("Укажи получателя и корректную сумму.")
                        .setPositiveButton("Понятно", null).show()
                    return@setOnClickListener
                }
                val contextRisk = RiskHistory.assess(
                    RiskHistory.read(getSharedPreferences(MessageNotificationListener.PREFS, MODE_PRIVATE))
                )
                val result = DemoTransferRisk.evaluate(
                    contextRisk = contextRisk,
                    recipient = name,
                    amount = value,
                    isNewRecipient = isNewRecipient.isChecked
                )
                dialog.dismiss()
                val details = buildString {
                    append("Риск: ${result.score}/100 · ${result.level}\n\n")
                    append(result.reasons.joinToString("\n") { "• $it" })
                    append("\n\n")
                    append(if (result.blocked) "Демо-перевод приостановлен. Не отправляй деньги, пока не проверишь запрос по независимому каналу."
                        else "Критических сигналов не найдено. Это демонстрация, а не гарантия безопасности.")
                }
                AlertDialog.Builder(this)
                    .setTitle(if (result.blocked) "⛔ Перевод приостановлен" else "✓ Проверка завершена")
                    .setMessage(details)
                    .setPositiveButton(if (result.blocked) "Понятно" else "Завершить демо") { _, _ ->
                        if (!result.blocked) {
                            AlertDialog.Builder(this)
                                .setTitle("Демо завершено")
                                .setMessage("Условный перевод на $name на сумму ${"%.2f".format(Locale.US, value)} ₽ отмечен как тестовый. Деньги не перемещались.")
                                .setPositiveButton("Закрыть", null).show()
                        }
                    }
                    .setNegativeButton(if (result.blocked) "Проверить позже" else "Отмена", null)
                    .show()
            }
        }
        dialog.show()
    }

    private fun card(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }
    private fun label(textValue: String, size: Int, color: Int, top: Int = 0) = TextView(this).apply {
        text = textValue; textSize = size.toFloat(); setTextColor(color); if (top > 0) setPadding(0, dp(top), 0, 0)
    }
    private fun button(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue; isAllCaps = false; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
