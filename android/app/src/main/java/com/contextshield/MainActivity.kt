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
import android.graphics.drawable.GradientDrawable
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

        gate = label("", 14, 0xFF14251E.toInt(), 10).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        page.addView(card(0xFFFFFFFF.toInt()).apply {
            addView(label("РЕШЕНИЕ ПЕРЕД ПЕРЕВОДОМ", 12, 0xFF16875C.toInt()))
            addView(gate)
        })

        page.addView(card(0xFFFFFFFF.toInt()).apply {
            addView(label("Как читать оценку модели", 18, 0xFF14251E.toInt()))
            addView(label("ВЫХОД 1 · КЛАСС СООБЩЕНИЯ", 11, 0xFF16875C.toInt(), 10))
            addView(label("Модель относит текст к одному из четырёх классов: низкорисковый, требующий контекста, подозрительный или опасный шаблон. Это классификация текста, а не вывод о человеке.", 13, 0xFF64736B.toInt(), 3))
            addView(label("ВЫХОД 2 · ТАКТИКИ", 11, 0xFF16875C.toInt(), 10))
            addView(label("Отдельно модель ищет приёмы вроде срочности, выдачи себя за службу, фишинговой ссылки, запроса денег или кода, секретности и удалённого доступа. В карточке показываются только заметные модельные сигналы.", 13, 0xFF64736B.toInt(), 3))
            addView(label("КАК ПРИНИМАЕТСЯ РЕШЕНИЕ", 11, 0xFF16875C.toInt(), 10))
            addView(label("Решение кнопки строит локальная policy по явным признакам и недавнему контексту. Баллы 0–98 — это шкала сигналов, не процент и не вероятность мошенничества. Модельная уверенность не показывается числом: она не калибрована и не доказывает безопасность.", 13, 0xFF64736B.toInt(), 3))
        })

        page.addView(card(0xFFFFFFFF.toInt()).apply {
            addView(label("Связанные события · 30 минут", 18, 0xFF14251E.toInt()))
            metrics = label("", 14, 0xFF64736B.toInt(), 6)
            addView(metrics)
            episodeChain = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addView(episodeChain)
            addView(label("Стрелка связывает события только по времени; она не доказывает, что сообщения отправил один человек. Имя и ник не подтверждают личность.", 12, 0xFF78857E.toInt(), 8))
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
        val recentEvents = RiskHistory.recent(history)
        val signalCount = recentEvents.count { it.score > 0 || it.factors.isNotEmpty() || it.advisory != null }
        val sourceCount = recentEvents.map { it.source }.distinct().size
        metrics.text = "За окно: ${recentEvents.size} событий · $signalCount с сигналами · $sourceCount источников\nПолучено всего: ${p.getInt("received_count", 0)} · проверено локально: ${p.getInt("scanned_count", 0)} · пропущено очередью: ${p.getInt("dropped_count", 0)}\nПоследнее: ${p.getString("last_notification_state", "уведомлений пока не было")}${cloudScore?.let { " · отдельное мнение облака: $it/100" } ?: ""}"
        renderEpisodeChain(recentEvents)
        transfer.isEnabled = !blocked
        val needsReview = risk.advisory != null || risk.score >= ContextRisk.ADVISORY_THRESHOLD
        transfer.text = when { blocked -> "Демо-перевод приостановлен"; needsReview -> "Проверить перед переводом"; else -> "Перевести" }
        transfer.setBackgroundColor(when { blocked -> 0xFF78857E.toInt(); needsReview -> 0xFFB97718.toInt(); else -> 0xFF16875C.toInt() })
        val bannerColor = when { blocked -> 0xFFFFECEA.toInt(); needsReview -> 0xFFFFF3D9.toInt(); else -> 0xFFEAF5EF.toInt() }
        gate.setBackground(GradientDrawable().apply { setColor(bannerColor); cornerRadius = dp(14).toFloat() })
        gate.setTextColor(when { blocked -> 0xFF9B2F28.toInt(); needsReview -> 0xFF80510B.toInt(); else -> 0xFF205E42.toInt() })
        gate.text = if (blocked) {
            "⛔ ПАУЗА В ДЕМО\nСводный сигнал: ${risk.score}/98 баллов · порог паузы ${ContextRisk.THRESHOLD}.\nОснования: ${risk.factors.joinToString(" · ").ifBlank { "Совокупность сигналов за 30 минут" }}.\nКнопка демо приостановлена. Баллы не являются вероятностью мошенничества."
        } else if (risk.advisory != null) {
            "⚠ ПРОВЕРЬТЕ ПРОСЬБУ\nСводный сигнал: ${risk.score}/98 баллов.\n${risk.advisory}\nДемо-перевод не заблокирован. Баллы не являются вероятностью мошенничества."
        } else if (risk.score >= ContextRisk.ADVISORY_THRESHOLD) {
            "⚠ ПРОВЕРКА РЕКОМЕНДОВАНА\nСводный сигнал: ${risk.score}/98 баллов · от ${ContextRisk.ADVISORY_THRESHOLD} рекомендуется проверка.\nОснования: ${risk.factors.joinToString(" · ").ifBlank { "несколько сигналов в одном временном окне" }}.\nПодтвердите просьбу по известному каналу. Демо-перевод доступен; баллы не являются вероятностью мошенничества."
        } else {
            "✓ СИГНАЛ НИЖЕ ПОРОГА ПРОВЕРКИ\nСводный сигнал: ${risk.score}/98 баллов · проверка рекомендуется от ${ContextRisk.ADVISORY_THRESHOLD}.\nКнопка демо доступна. Это не подтверждение безопасности и не проверка личности получателя."
        }
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
            episodeChain.addView(label("Пока нет событий. Разреши Android-доступ к уведомлениям или запусти синтетический демо-кейс.", 13, 0xFF64736B.toInt(), 8))
            return
        }
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        shown.asReversed().forEachIndexed { index, event ->
            if (index > 0) episodeChain.addView(label("↓  ещё одно событие в том же 30-минутном окне", 11, 0xFF78857E.toInt(), 4))
            val eventNeedsReview = event.advisory != null || event.score >= ContextRisk.ADVISORY_THRESHOLD
            val eventStrongSignal = event.score >= ContextRisk.THRESHOLD
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(if (eventStrongSignal) 0xFFFFECEA.toInt() else if (eventNeedsReview) 0xFFFFF5E4.toInt() else 0xFFF3F6F4.toInt())
                    cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            row.addView(label("${formatter.format(Date(event.at))}  ·  ${event.source}", 14, 0xFF14251E.toInt()))
            row.addView(label(eventStatus(event.score, event.advisory != null), 12, if (eventStrongSignal) 0xFFAA352B.toInt() else if (eventNeedsReview) 0xFF8A570F.toInt() else 0xFF287A56.toInt(), 5))
            row.addView(label("СИГНАЛЫ ЛОКАЛЬНЫХ ПРАВИЛ · ${event.score}/98 баллов", 10, 0xFF64736B.toInt(), 8))
            row.addView(label(event.factors.joinToString("\n• ").let { if (it.isBlank()) "• Явных признаков правилами не найдено" else "• $it" }, 13, 0xFF14251E.toInt(), 3))
            if (event.modelConfidence > 0f) {
                row.addView(label("ВЫХОД МОДЕЛИ 1 · КЛАСС ТЕКСТА", 10, 0xFF64736B.toInt(), 9))
                row.addView(label(labelRu(event.modelLabel), 14, 0xFF14251E.toInt(), 2))
                row.addView(label("Это класс сообщения, а не заключение о мошеннике. Даже низкорисковый класс не гарантирует безопасность; итоговый статус учитывает отдельные правила и контекст эпизода.", 12, 0xFF64736B.toInt(), 3))
                row.addView(label("ВЫХОД МОДЕЛИ 2 · ТАКТИКИ", 10, 0xFF64736B.toInt(), 9))
                row.addView(label(renderTactics(event.modelTactics), 12, 0xFF14251E.toInt(), 2))
            }
            if (event.advisory != null) row.addView(label("РЕКОМЕНДАЦИЯ\n${event.advisory}", 13, 0xFF8A570F.toInt(), 8))
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
                    .setMessage("РЕШЕНИЕ POLICY\n${eventStatus(signal.score, signal.advisory != null)} · ${signal.score}/98 баллов\n\nВЫХОД 1 · КЛАСС ТЕКСТА\n${labelRu(signal.modelLabel)}\nЭто категория текста, не вероятность мошенничества и не подтверждение личности.\n\nВЫХОД 2 · ТАКТИКИ\n${renderTactics(signal.modelTactics)}\n\nПРИЗНАКИ ЛОКАЛЬНЫХ ПРАВИЛ\n${signal.factors.joinToString("\n• ").let { if (it.isBlank()) "• Явных признаков правилами не найдено" else "• $it" }}${signal.advisory?.let { "\n\nРЕКОМЕНДАЦИЯ\n$it" } ?: ""}\n\nТекст кейса не сохранён.")
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
                .setMessage("Сводный сигнал: ${risk.score}/98 баллов. ${risk.factors.joinToString(" · ")}\n\nБаллы не являются вероятностью мошенничества. Это только демо. Проверьте получателя через известный канал.")
                .setPositiveButton("Понятно", null).show()
        } else if (risk.advisory != null || risk.score >= ContextRisk.ADVISORY_THRESHOLD) {
            val recommendation = risk.advisory ?: "Сводный сигнал ${risk.score}/98 достиг порога проверки ${ContextRisk.ADVISORY_THRESHOLD}. Подтвердите просьбу по уже известному каналу."
            AlertDialog.Builder(this).setTitle("Перед переводом проверьте просьбу")
                .setMessage("$recommendation\n\nБаллы не являются вероятностью мошенничества. Демо-перевод не заблокирован. Продолжайте только если вы проверили просьбу независимо от этого чата.")
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
        "safe" -> "Низкорисковый текст"
        "needs_check" -> "Требуется контекст"
        "suspicious" -> "Подозрительный шаблон"
        "dangerous" -> "Опасный шаблон"
        else -> "Модель не оценила текст"
    }

    private fun eventStatus(score: Int, hasAdvisory: Boolean): String = when {
        score >= ContextRisk.THRESHOLD -> "СИЛЬНЫЙ СИГНАЛ · ПРОВЕРЬТЕ ПЕРЕД ПЕРЕВОДОМ"
        hasAdvisory || score >= ContextRisk.ADVISORY_THRESHOLD -> "РЕКОМЕНДОВАНА ПРОВЕРКА"
        else -> "ПАУЗЫ НЕТ · ЭТО НЕ ЗНАЧИТ, ЧТО СООБЩЕНИЕ БЕЗОПАСНО"
    }

    private fun renderTactics(tactics: Map<String, Float>): String {
        val names = tactics.keys.take(5).mapNotNull { tacticNames[it] }
        return if (names.isEmpty()) {
            "Сильных сигналов из списка тактик не отмечено. Это не подтверждает безопасность сообщения."
        } else {
            names.joinToString("\n• ", prefix = "• ") + "\nМодельные сигналы требуют проверки и сами по себе не доказывают мошенничество."
        }
    }

    private val tacticNames = mapOf(
        "urgency" to "Давление срочностью",
        "impersonation" to "Возможная имитация организации или человека",
        "phishing_link" to "Фишинговая ссылка",
        "payment_request" to "Просьба о платеже",
        "credential_theft" to "Запрос кода или учётных данных",
        "verification_needed" to "Подталкивание к проверке/подтверждению",
        "investment_or_prize" to "Инвестиционная или призовая приманка",
        "remote_access" to "Просьба о дистанционном доступе",
        "secrecy" to "Просьба сохранить разговор в тайне",
        "job_scam" to "Признаки сомнительного предложения работы",
        "marketplace_scam" to "Признаки риска сделки на площадке",
        "third_party" to "Участие третьего лица",
        "too_good_to_be_true" to "Слишком выгодное предложение"
    )

    private fun card(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun label(textValue: String, size: Int, color: Int, top: Int = 0) = TextView(this).apply {
        text = textValue
        textSize = size.toFloat()
        setTextColor(color)
        setLineSpacing(dp(2).toFloat(), 1f)
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
