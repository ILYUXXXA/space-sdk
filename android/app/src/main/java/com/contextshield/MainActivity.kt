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
import android.provider.ContactsContract
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var gate: TextView
    private lateinit var transfer: Button
    private lateinit var episodeChain: LinearLayout
    private lateinit var events: TextView
    private lateinit var sourceState: TextView
    private lateinit var cloudState: TextView
    private lateinit var cloudButton: Button
    private val cloudExecutor = Executors.newSingleThreadExecutor()
    private val demoExecutor = Executors.newSingleThreadExecutor()
    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF10231D.toInt()
        window.navigationBarColor = 0xFF10231D.toInt()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
            setBackgroundColor(0xFFF3F6F4.toInt())
        }
        page.addView(label("DEMO BANK  ·  CONTEXT SHIELD", 12, 0xFF16875C.toInt()))
        page.addView(label("Защита перевода", 28, 0xFF14251E.toInt(), 12))
        page.addView(label("Локальный анализ уведомлений и контекста эпизода на Android. Текст не сохраняется.", 14, 0xFF64736B.toInt(), 3))

        val bankCard = card(0xFF10231D.toInt())
        bankCard.addView(label("ТЕСТОВЫЙ СЧЁТ", 12, 0xFF9ECBB5.toInt()))
        bankCard.addView(label("••••  2048", 25, Color.WHITE, 8))
        bankCard.addView(label("Доступно для демо  ·  48 250 ₽", 14, 0xFFCEE2D7.toInt(), 3))
        transfer = Button(this).apply {
            text = "Перевести"
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.WHITE)
            setOnClickListener { confirmDemoTransfer() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(18) }
        }
        bankCard.addView(transfer)
        page.addView(bankCard)

        gate = label("", 15, 0xFF14251E.toInt(), 10)
        page.addView(card(0xFFFFFFFF.toInt()).apply {
            addView(label("РЕШЕНИЕ ПЕРЕД ПЕРЕВОДОМ", 12, 0xFF16875C.toInt()))
            addView(gate)
        })

        page.addView(card(0xFFFFFFFF.toInt()).apply {
            addView(label("Что связано в эпизоде", 18, 0xFF14251E.toInt()))
            metrics = label("", 14, 0xFF64736B.toInt(), 6)
            addView(metrics)
            episodeChain = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addView(episodeChain)
            addView(label("Связь здесь означает только соседство по времени за 30 минут. Приложение не устанавливает, кто именно отправил сообщение: имя и ник не подтверждают личность.", 12, 0xFF78857E.toInt(), 8))
        })
        page.addView(card(0xFFFFFFFF.toInt()).apply {
            addView(label("События и объяснение", 18, 0xFF14251E.toInt()))
            events = label("", 14, 0xFF64736B.toInt(), 7)
            addView(events)
        })

        val accessCard = card(0xFFFFFFFF.toInt())
        accessCard.addView(label("Источники Android", 18, 0xFF14251E.toInt()))
        status = label("", 13, 0xFF64736B.toInt(), 6)
        accessCard.addView(status)
        sourceState = label("", 13, 0xFF64736B.toInt(), 6)
        accessCard.addView(sourceState)
        accessCard.addView(button("Включить доступ к уведомлениям") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        accessCard.addView(button("Включить дополнительный сигнал удалённой помощи") {
            explainUsageAccess()
        })
        accessCard.addView(button("Позвонить по сохранённому номеру") {
            pickKnownContact()
        })
        accessCard.addView(button("Разрешить системные предупреждения") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        })
        accessCard.addView(label("Дополнительные сигналы необязательны и не нужны для локальной проверки уведомлений. UsageStats ищет только недавний запуск нескольких приложений удалённой помощи. Кнопка звонка лишь помогает самостоятельно проверить просьбу: она не подтверждает личность, не меняет оценку и не сохраняет номер.", 12, 0xFF78857E.toInt(), 6))
        page.addView(accessCard)

        page.addView(card(0xFFFFFFFF.toInt()).apply {
            addView(label("Живая демонстрация", 18, 0xFF14251E.toInt()))
            addView(label("Это готовые синтетические кейсы, не ручной ввод и не настоящие уведомления. Они проходят ту же локальную модель и policy, чтобы можно было показать оба исхода.", 12, 0xFF78857E.toInt(), 4))
            addView(button("Демо: близкий пишет с нового номера и просит срочный перевод") {
                runPresetScenario(
                    "Привет, мам, я потерял телефон, это мой новый номер. Срочно переведи 1000 долларов на операцию, никому не говори.",
                    "Синтетический кейс: новый номер + срочный перевод"
                )
            })
            addView(button("Демо: вымогательство с угрозой раскрыть данные") {
                runPresetScenario(
                    "Переведи 4 000 рублей по этим реквизитам, иначе выложу твой адрес и переписку.",
                    "Сценарий с угрозой раскрыть личные данные"
                )
            })
            addView(button("Демо: запрос кода от имени банка") {
                runPresetScenario(
                    "Сотрудник банка просит сообщить код из СМС, чтобы отменить подозрительную операцию.",
                    "Синтетический кейс: запрос кода подтверждения"
                )
            })
            addView(button("Демо: обычный расчёт за общую покупку") {
                runPresetScenario(
                    "Я оплатил продукты для поездки. Переведи свою часть, когда тебе будет удобно.",
                    "Синтетический кейс: обычный расчёт без давления"
                )
            })
            addView(button("Демо: предупреждение о мошенничестве") {
                runPresetScenario(
                    "Памятка по кибербезопасности: мошенники могут просить код из SMS. Не сообщайте код и не переводите деньги по их просьбе.",
                    "Синтетический кейс: предупреждение, не атака"
                )
            })
        })

        val cloudCard = card(0xFFFFFFFF.toInt())
        cloudCard.addView(label("Облачное мнение · OpenRouter", 18, 0xFF14251E.toInt()))
        cloudState = label("", 13, 0xFF64736B.toInt(), 6)
        cloudCard.addView(cloudState)
        cloudCard.addView(button("Сохранить / заменить ключ") { enterKey() })
        cloudButton = button("Передать категории сигналов на проверку") { runCloudReview() }
        cloudCard.addView(cloudButton)
        cloudCard.addView(button("Удалить ключ с телефона") { CloudReview.clearKey(this); refresh() })
        page.addView(cloudCard)

        page.addView(button("Сбросить сессию и историю") {
            AlertDialog.Builder(this).setTitle("Сбросить локальную историю?")
                .setMessage("Удалятся зашифрованные события эпизода и счётчики. Ключ OpenRouter и лимит запросов останутся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сбросить") { _, _ ->
                    RiskHistory.clearSession(this)
                    CloudReview.clearReview(this)
                    refresh()
                }.show()
        })
        page.addView(label("Демо-песочница не подключена к банку и не выполняет настоящий перевод. Решение касается только этой кнопки. Имя/ник не является идентификатором: continuity остаётся UNKNOWN. Облако не может снять локальную паузу.", 12, 0xFF78857E.toInt(), 14))
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MessageNotificationListener.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(updates, filter)
        refresh()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(updates) }
        super.onPause()
    }

    @Deprecated("Uses the Android system contact picker intent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_PHONE_REQUEST || resultCode != RESULT_OK) return
        val selectedUri: Uri = data?.data ?: return
        // The system picker grants access to this one selected phone row. Do not persist the URI or number.
        val number = runCatching {
            contentResolver.query(selectedUri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        if (number.isNullOrBlank()) {
            showMessage("Не удалось прочитать выбранный номер. Проверьте номер в приложении контактов и позвоните вручную.")
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) }
            .onFailure { showMessage("Не удалось открыть системный экран звонка.") }
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val enabled = enabledListeners.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it.packageName == packageName }
        val p = getSharedPreferences(MessageNotificationListener.PREFS, MODE_PRIVATE)
        val history = RiskHistory.read(this)
        val risk = RiskHistory.assess(history)
        val cloudScore = CloudReview.freshRiskScore(this, p.getLong("last_checked_at", 0L))
        val blocked = risk.blocked // Cloud advice is never a transfer decision.
        val serviceConnected = p.getBoolean("listener_connected", false)
        status.text = when {
            !enabled -> "○ Нужно включить доступ к уведомлениям в системных настройках"
            serviceConnected -> "● Android listener подключён"
            else -> "◌ Разрешение включено; Android ждёт подключения listener"
        }
        val modelConfidence = p.getFloat("last_model_confidence", 0f)
        val modelLabel = p.getString("last_model_label", "unknown") ?: "unknown"
        val recentEvents = RiskHistory.recent(history)
        val signalCount = recentEvents.count { it.score > 0 || it.factors.isNotEmpty() || it.advisory != null }
        val sourceCount = recentEvents.map { it.source }.distinct().size
        metrics.text = "За 30 минут: ${recentEvents.size} событий · $signalCount с признаками · $sourceCount источников\nПолучено всего: ${p.getInt("received_count", 0)} · проверено локально: ${p.getInt("scanned_count", 0)} · пропущено очередью: ${p.getInt("dropped_count", 0)}\nРиск эпизода: ${risk.score}/98 · последнее: ${p.getString("last_notification_state", "уведомлений пока не было")}${cloudScore?.let { " · мнение облака: $it/100" } ?: ""}"
        renderEpisodeChain(recentEvents)
        transfer.isEnabled = !blocked
        transfer.text = when { blocked -> "Перевод приостановлен"; risk.advisory != null -> "Проверить перед переводом"; else -> "Перевести" }
        transfer.setBackgroundColor(when { blocked -> 0xFF78857E.toInt(); risk.advisory != null -> 0xFFB97718.toInt(); else -> 0xFF16875C.toInt() })
        gate.text = if (blocked) {
            "⛔ Демо-перевод приостановлен локальной policy (≥${ContextRisk.THRESHOLD}). ${risk.factors.joinToString(" · ").ifBlank { "Совокупность сигналов" }}. Проверьте человека по известному каналу."
        } else if (risk.advisory != null) {
            "⚠ ПРОВЕРКА РЕКОМЕНДОВАНА. ${risk.advisory}"
        } else {
            "✓ Кнопка доступна: локальный риск ниже ${ContextRisk.THRESHOLD}. Это не доказательство безопасности — проверьте получателя отдельно."
        }
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        events.text = if (history.isEmpty()) "Пока нет событий. Разреши Android-доступ к уведомлениям или запусти синтетический демо-кейс." else
            RiskHistory.recent(history).takeLast(7).asReversed().joinToString("\n\n") { event ->
                val confidence = if (event.modelConfidence > 0f) " · класс модели ${labelRu(event.modelLabel)}, уверенность ${(event.modelConfidence * 100).toInt()}% (не вероятность мошенничества)" else ""
                val tacticLine = event.modelTactics.keys.take(3).takeIf { it.isNotEmpty() }
                    ?.joinToString(", ")?.let { "\nТактики модели: $it" }.orEmpty()
                "${formatter.format(Date(event.at))} · ${event.source} · риск ${event.score}/98$confidence\n${event.factors.joinToString(" · ").ifBlank { "Сильных признаков правилами не найдено" }}$tacticLine"
            }.ifBlank { "Активных событий за 30 минут нет." }
        val usageEnabled = UsageSignals.hasAccess(this)
        sourceState.text = "Модель: ${if (OnDeviceModel.status().startsWith("Локальная модель недоступна")) OnDeviceModel.status() else "RuBERT-tiny2 fine-tuned · ONNX INT8 · на устройстве"}\nUsageStats: ${if (usageEnabled) "opt-in включён; короткий запрос выполняется только при нажатии перевода" else "выключен; сам не запрашивается"}\nПроверочный звонок: только открывает системный набор; не устанавливает личность и не влияет на риск."
        val used = CloudReview.attempts(this)
        cloudState.text = "${if (CloudReview.hasKey(this)) "Ключ зашифрован Android Keystore" else "Ключ не добавлен"} · запросов $used/${CloudReview.MAX_ATTEMPTS}${cloudScore?.let { " · ответ модели $it/100" } ?: ""}\nПередаются только разрешённые категории сигналов, грубые интервалы и локальные оценки. Текст сообщений, имя отправителя, номер, package ID и точное время не передаются. Только по кнопке; cloud-ответ не открывает и не блокирует демо-перевод."
        cloudButton.isEnabled = CloudReview.hasKey(this) && used < CloudReview.MAX_ATTEMPTS
        cloudButton.alpha = if (cloudButton.isEnabled) 1f else 0.45f
    }

    private fun renderEpisodeChain(recent: List<RiskEvent>) {
        episodeChain.removeAllViews()
        val shown = recent.takeLast(5)
        if (shown.isEmpty()) {
            episodeChain.addView(label("Пока нет сигналов. После уведомления здесь появится источник, найденные признаки и объяснение решения.", 13, 0xFF64736B.toInt(), 8))
            return
        }
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        shown.forEachIndexed { index, event ->
            if (index > 0) episodeChain.addView(label("↓  следующий сигнал в том же 30-минутном окне", 11, 0xFF78857E.toInt(), 4))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(if (event.advisory != null) 0xFFFFF5E4.toInt() else 0xFFF3F6F4.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            row.addView(label("${formatter.format(Date(event.at))}  ·  ${event.source}", 14, 0xFF14251E.toInt()))
            row.addView(label("Проверено на устройстве · риск события ${event.score}/98", 12, 0xFF64736B.toInt(), 3))
            val findings = event.factors.joinToString(" · ").ifBlank { "Явных признаков локальными правилами не найдено" }
            row.addView(label(findings, 13, 0xFF14251E.toInt(), 5))
            if (event.modelConfidence > 0f) {
                row.addView(label("Класс модели: ${labelRu(event.modelLabel)} · уверенность ${(event.modelConfidence * 100).toInt()}% (не вероятность мошенничества)", 12, 0xFF64736B.toInt(), 5))
            }
            if (event.advisory != null) row.addView(label("Рекомендация: ${event.advisory}", 13, 0xFF8A570F.toInt(), 5))
            episodeChain.addView(row)
        }
    }

    private fun explainUsageAccess() {
        AlertDialog.Builder(this)
            .setTitle("Дополнительный системный сигнал")
            .setMessage("Если ты отдельно включишь доступ, ContextShield один раз запросит события foreground только за последние 15 минут при нажатии «Перевести» и только в активном эпизоде. Сопоставляются 4 известные программы удалённой помощи; список всех установленных приложений не читается. Сохраняется только факт совпадения и время с округлением до 5 минут. Одна активность такого приложения не блокирует перевод.")
            .setNegativeButton("Не сейчас", null)
            .setPositiveButton("Открыть системную настройку") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    .onFailure { showMessage("Эта версия Android не открыла настройку Usage Access.") }
            }.show()
    }

    private fun pickKnownContact() {
        val picker = Intent(Intent.ACTION_PICK).apply {
            type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
        }
        if (picker.resolveActivity(packageManager) == null) {
            showMessage("Системный выбор контакта недоступен на этом устройстве.")
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(picker, PICK_PHONE_REQUEST)
    }

    private fun runPresetScenario(text: String, title: String) {
        demoExecutor.execute {
            val model = OnDeviceModel.assess(applicationContext, text)
            val signal = LocalRisk.assess(text, model)
            RiskHistory.append(applicationContext, RiskEvent(
                at = System.currentTimeMillis(), score = signal.score, factors = signal.factors,
                source = "Синтетический демо-кейс", modelLabel = signal.modelLabel,
                modelConfidence = signal.modelConfidence, modelTactics = signal.modelTactics,
                identityContinuity = "UNKNOWN", signalType = "synthetic_demo", advisory = signal.advisory
            ))
            runOnUiThread {
                refresh()
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage("Локальная policy: ${signal.score}/98 · класс модели: ${labelRu(signal.modelLabel)} · уверенность ${if (signal.modelConfidence > 0) "${(signal.modelConfidence * 100).toInt()}%" else "недоступна"} (не вероятность мошенничества)\n\n${signal.factors.joinToString("\n").ifBlank { "Опасные локальные признаки не обнаружены." }}${signal.advisory?.let { "\n\nРекомендация: $it" } ?: ""}\n\nТекст кейса не сохранён.")
                    .setPositiveButton("Понятно", null).show()
            }
        }
    }

    private fun confirmDemoTransfer() {
        val history = RiskHistory.read(this)
        val active = RiskHistory.assess(history)
        if (active.eventCount > 0 && UsageSignals.hasAccess(this)) {
            val remote = UsageSignals.recentRemoteAccess(this)
            if (remote.foundInActiveWindow) {
                RiskHistory.append(this, RiskEvent(
                    at = remote.coarseAt ?: System.currentTimeMillis(), score = 10,
                    factors = listOf("В активном эпизоде было foreground-приложение удалённой помощи"),
                    source = "Системная статистика использования", signalType = "remote_access_activity"
                ))
            }
        }
        val risk = RiskHistory.assess(RiskHistory.read(this))
        refresh()
        if (risk.blocked) {
            AlertDialog.Builder(this).setTitle("Демо-перевод приостановлен")
                .setMessage("Перед нажатием появился дополнительный контекст. ${risk.factors.joinToString(" · ")}\n\nЭто только демо. Проверьте получателя через известный канал.")
                .setPositiveButton("Понятно", null).show()
        } else if (risk.advisory != null) {
            AlertDialog.Builder(this).setTitle("Перед переводом проверьте просьбу")
                .setMessage("${risk.advisory}\n\nДемо-перевод не заблокирован. Продолжайте только если вы проверили просьбу независимо от этого чата.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Я проверил — продолжить демо") { _, _ ->
                    AlertDialog.Builder(this).setTitle("Демо-перевод доступен")
                        .setMessage("Реальный перевод не выполняется.")
                        .setPositiveButton("Закрыть", null).show()
                }.show()
        } else {
            AlertDialog.Builder(this).setTitle("Демо-перевод доступен")
                .setMessage("Локальная проверка не набрала порог риска. Это демонстрация интерфейса: настоящий перевод не выполняется.")
                .setPositiveButton("Закрыть", null).show()
        }
    }

    private fun runCloudReview() {
        val history = RiskHistory.read(this)
        val risk = RiskHistory.assess(history)
        cloudButton.isEnabled = false
        cloudButton.text = "Запрос отправляется…"
        cloudExecutor.execute {
            val result = runCatching { CloudReview.review(this, risk, history) }
            runOnUiThread {
                cloudButton.text = "Передать категории сигналов на проверку"
                refresh()
                AlertDialog.Builder(this)
                    .setTitle(if (result.isSuccess) "Облачное мнение (не решение)" else "Облачная проверка не выполнена")
                    .setMessage(result.getOrNull()?.answer ?: result.exceptionOrNull()?.message ?: "Неизвестная ошибка")
                    .setPositiveButton("Понятно", null).show()
            }
        }
    }

    private fun enterKey() {
        val input = EditText(this).apply {
            hint = "sk-or-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("Ключ OpenRouter")
            .setMessage("Ключ хранится зашифрованным в Android Keystore. Не отправляй его в чат и не встраивай в APK.")
            .setView(input).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                runCatching { CloudReview.saveKey(this, input.text.toString().trim()) }
                    .onFailure { showMessage(it.message ?: "Ключ не сохранён") }
                refresh()
            }.show()
    }

    private fun showMessage(text: String) {
        AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun labelRu(label: String): String = when (label) {
        "safe" -> "низкий"
        "needs_check" -> "нужна проверка"
        "suspicious" -> "подозрительно"
        "dangerous" -> "опасно"
        else -> "не оценено"
    }

    private fun card(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun label(textValue: String, size: Int, color: Int, top: Int = 0) = TextView(this).apply {
        text = textValue
        textSize = size.toFloat()
        setTextColor(color)
        if (top > 0) setPadding(0, dp(top), 0, 0)
    }

    private fun button(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PICK_PHONE_REQUEST = 22
    }
}
