package com.rwai.agent.executor

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import com.rwai.agent.accessibility.RwAccessibilityService
import com.rwai.agent.core.Draft
import com.rwai.agent.data.RwDb
import com.rwai.agent.logic.AppAliases
import com.rwai.agent.logic.BankGuard
import com.rwai.agent.logic.LabelClassifier
import com.rwai.agent.logic.ToolCall
import com.rwai.agent.logic.ToolExecutor
import com.rwai.agent.logic.ToolResult
import com.rwai.agent.logic.UiFormatter
import com.rwai.agent.logic.UiMatcher
import com.rwai.agent.logic.asJsonMap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.rwai.agent.settings.SettingsStore
import com.rwai.agent.voice.Speaker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Реальная реализация инструментов агента поверх AccessibilityService, Intents и локальной базы.
 * Каждый инструмент возвращает результат с кодом состояния; ничего не объявляется успешным без проверки,
 * где проверка возможна (например, запуск приложения подтверждается сменой активного пакета).
 */
class AndroidToolExecutor(
    private val context: Context,
    private val db: RwDb,
    private val settings: SettingsStore,
    private val speaker: Speaker,
    private val onDraft: (Draft) -> Unit,
) : ToolExecutor {

    private val timeFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    override fun currentApp(): String? {
        val pkg = RwAccessibilityService.instance?.currentPackage() ?: return null
        return labelOf(pkg)
    }

    override suspend fun execute(call: ToolCall): ToolResult = when (call.tool) {
        "launch_app" -> launchApp(call.str("app").orEmpty())
        "open_settings" -> openSettings(call.str("page").orEmpty(), call.str("app"))
        "open_url" -> openUrl(call.str("url").orEmpty())
        "web_search" -> webSearch(call.str("query").orEmpty())
        "fetch_page" -> fetchPage(call.str("url").orEmpty())
        "ask_claude_code" -> askClaudeCode(call.str("task").orEmpty())
        "phone_status" -> phoneStatus()
        "media_status" -> ToolResult.ok(com.rwai.agent.connectors.MediaControl.status(context), untrusted = true)
        "media_control" -> mediaControl(call.str("action").orEmpty())
        "play_music" -> playMusic(call.str("query").orEmpty(), call.str("app"))
        "youtube_search" -> youtubeSearch(call.str("query").orEmpty())
        "calendar_events" -> calendarEvents(call.int("days") ?: 3)
        "calendar_add" -> calendarAdd(call)
        "weather" -> weather(call.str("city").orEmpty())
        "make_pdf" -> makePdf(call.str("file_name"), call.str("content").orEmpty())
        "draw_image" -> drawImage(call.str("file_name"), call.str("commands").orEmpty())
        "annotate_document" -> annotateDocument(call.str("source"), call.str("commands").orEmpty())
        "mail_list" -> mailList(call)
        "mail_read" -> mailRead(call.int("id") ?: 0)
        "mail_send" -> mailSend(call)
        "inspect_screen" -> withScreen { cap -> ToolResult.ok(UiFormatter.format(cap.packageName, cap.infos), untrusted = true) }
        "find_text" -> findText(call.str("query").orEmpty())
        "tap_text" -> tapText(call.str("text").orEmpty(), call.int("index") ?: 0)
        "scroll" -> scroll(call.str("direction") == "down")
        "input_text" -> inputText(call.str("text").orEmpty())
        "press_back" -> pressBack()
        "wait" -> { delay((call.int("ms") ?: 500).toLong()); ToolResult.ok("Ожидание завершено.") }
        "read_notifications" -> readNotifications(call.int("limit") ?: 20, call.str("app"))
        "search_memory" -> searchMemory(call.str("query").orEmpty(), call.str("app"), call.int("limit") ?: 10)
        "save_excerpt" -> {
            db.addMemory("excerpt", call.str("app"), call.str("contact"), call.str("title").orEmpty(), call.str("text").orEmpty())
            ToolResult.ok("Выдержка сохранена в локальную память.")
        }
        "show_draft" -> {
            onDraft(Draft(call.str("recipient").orEmpty(), call.str("app").orEmpty(), call.str("text").orEmpty()))
            ToolResult.ok("Черновик показан пользователю (ничего не отправлено).")
        }
        "speak" -> if (speaker.speak(call.str("text").orEmpty(), settings.current.language)) ToolResult.ok("Озвучено.")
        else ToolResult.failed("Озвучивание недоступно: нет движка синтеза речи или языка ${settings.current.language}.")
        else -> ToolResult.failed("Инструмент '${call.tool}' не реализован.")
    }

    // ------------------------------------------------------------------ запуск

    private suspend fun launchApp(name: String): ToolResult {
        val pm = context.packageManager
        if (BankGuard.isBankingLabel(name)) return ToolResult.failed(BankGuard.REFUSAL)
        val pkg = resolvePackage(name) ?: return ToolResult.failed("Приложение «$name» не найдено среди установленных.")
        if (BankGuard.isBanking(pkg, labelOf(pkg))) return ToolResult.failed(BankGuard.REFUSAL)
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return ToolResult.failed("У приложения «$name» нет экрана запуска.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isLocked()) return lockedResult()
        try {
            (RwAccessibilityService.instance ?: context).startActivity(intent)
        } catch (e: Exception) {
            return ToolResult.failed("Не удалось запустить «${labelOf(pkg)}»: ${e.javaClass.simpleName}.")
        }
        val svc = RwAccessibilityService.instance
            ?: return ToolResult.ok("Команда запуска «${labelOf(pkg)}» отправлена, но проверить результат нельзя: служба специальных возможностей выключена.")
        repeat(24) {
            delay(250)
            if (svc.currentPackage() == pkg) return ToolResult.ok("Открыто: ${labelOf(pkg)} ($pkg).")
        }
        return ToolResult.failed("Запуск отправлен, но «${labelOf(pkg)}» не вышло на передний план (возможно, ограничения Android на запуск из фона).")
    }

    /** Прямой переход на страницу системных настроек. Банковские приложения закрыты, как и для запуска. */
    private suspend fun openSettings(page: String, app: String?): ToolResult {
        val pkg: String? = if (app.isNullOrBlank()) null else {
            if (BankGuard.isBankingLabel(app)) return ToolResult.failed(BankGuard.REFUSAL)
            val p = resolvePackage(app) ?: return ToolResult.failed("Приложение «$app» не найдено среди установленных.")
            if (BankGuard.isBanking(p, labelOf(p))) return ToolResult.failed(BankGuard.REFUSAL)
            p
        }
        val intent: Intent = when (page) {
            "app_details" -> pkg?.let { Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + it)) }
            "app_notifications" -> pkg?.let { Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, it) }
            "battery_optimization" -> Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "battery" -> Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "wifi" -> Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            "sound" -> Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
            "display" -> Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
            "accessibility" -> Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "apps" -> Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            "location" -> Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "notification_policy" -> Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "main" -> Intent(android.provider.Settings.ACTION_SETTINGS)
            else -> null
        } ?: return ToolResult.failed("Для страницы «$page» нужно указать приложение (аргумент app) или такой страницы нет.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isLocked()) return lockedResult()
        try {
            (RwAccessibilityService.instance ?: context).startActivity(intent)
        } catch (e: Exception) {
            return ToolResult.failed("Не удалось открыть страницу настроек: ${e.javaClass.simpleName}.")
        }
        delay(1200)
        return ToolResult.ok("Открыта страница настроек «$page»" + (pkg?.let { " для " + labelOf(it) } ?: "") + ". Осмотри экран (inspect_screen) и нажимай нужные пункты.")
    }

    private suspend fun openUrl(url: String): ToolResult {
        if (BankGuard.isBankingUrl(url)) return ToolResult.failed(BankGuard.REFUSAL)
        if (isLocked()) return lockedResult()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            (RwAccessibilityService.instance ?: context).startActivity(intent)
            delay(1500)
            ToolResult.ok("Ссылка открыта. Текущее приложение: ${currentApp() ?: "не определено"}.")
        } catch (e: Exception) {
            ToolResult.failed("Не удалось открыть ссылку: ${e.javaClass.simpleName}.")
        }
    }

    private fun resolvePackage(name: String): String? {
        val pm = context.packageManager
        AppAliases.candidates(name).firstOrNull { pm.getLaunchIntentForPackage(it) != null }?.let { return it }
        val q = UiMatcher.normalize(name)
        if (q.isEmpty()) return null
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName to UiMatcher.normalize(it.loadLabel(pm).toString()) }
        return apps.firstOrNull { it.second == q }?.first ?: apps.firstOrNull { it.second.contains(q) }?.first
    }

    // ------------------------------------------------------------------ экран

    private fun isLocked(): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    private fun lockedResult() = ToolResult.failed("Телефон заблокирован. Разблокируйте его сами — Руся AI не обходит блокировку экрана.")

    private fun noService() = ToolResult.unavailable(
        "Служба специальных возможностей Руся AI не включена. Владельцу нужно включить её: Настройки Android → Специальные возможности → Руся AI.",
    )

    private inline fun withScreen(block: (com.rwai.agent.accessibility.ScreenCapture) -> ToolResult): ToolResult {
        if (isLocked()) return lockedResult()
        val svc = RwAccessibilityService.instance ?: return noService()
        val cap = svc.capture() ?: return ToolResult.failed("Нет активного окна: экран пуст или приложение защитило содержимое.")
        try {
            if (cap.packageName == context.packageName) return ToolResult.failed("Агент не работает с экраном самого Руся AI.")
            if (BankGuard.isBanking(cap.packageName, cap.packageName?.let { labelOf(it) })) return ToolResult.failed(BankGuard.REFUSAL)
            return block(cap)
        } finally {
            cap.release()
        }
    }

    private fun findText(query: String): ToolResult = withScreen { cap ->
        val found = UiMatcher.find(cap.infos, query, 10)
        if (found.isEmpty()) ToolResult.ok("Совпадений для «$query» на экране нет.", untrusted = true)
        else ToolResult.ok(
            found.joinToString("\n") { n ->
                "[" + n.index + "] \"" + n.label.take(150) + "\"" + (if (n.clickable) " <click>" else "") + (if (n.editable) " <input>" else "")
            },
            untrusted = true,
        )
    }

    private fun tapText(text: String, index: Int): ToolResult {
        val svc = RwAccessibilityService.instance ?: return noService()
        return withScreen { cap ->
            val matches = UiMatcher.find(cap.infos, text, 20)
            val m = matches.getOrNull(index)
                ?: return@withScreen ToolResult.failed(
                    if (matches.isEmpty()) "Элемент «$text» не найден на экране. Вызови inspect_screen и уточни текст."
                    else "Найдено ${matches.size} совпадений, индекса $index нет.",
                )
            if (LabelClassifier.isEscalation(text, m.label)) {
                return@withScreen ToolResult.failed("Найденный элемент «${m.label}» опаснее запроса «$text» и требует отдельного подтверждения; нажатие отменено. Уточни запрос.")
            }
            val node = cap.node(m.index) ?: return@withScreen ToolResult.failed("Элемент исчез с экрана.")
            if (svc.click(node)) ToolResult.ok("Нажато: «${m.label.take(100)}». Проверь экран через inspect_screen.")
            else ToolResult.failed("Элемент «${m.label.take(100)}» найден, но не принимает нажатие.")
        }
    }

    private fun scroll(down: Boolean): ToolResult {
        val svc = RwAccessibilityService.instance ?: return noService()
        return withScreen { cap ->
            if (svc.scroll(cap, down)) ToolResult.ok(if (down) "Прокручено вниз." else "Прокручено вверх.")
            else ToolResult.failed("На экране нет прокручиваемого элемента или прокрутка невозможна (конец списка).")
        }
    }

    /** Запрос не должен нести личные данные владельца: проверяем по профилю и по ключам. */
    private fun leaksPrivateData(text: String): Boolean =
        com.rwai.agent.logic.ProfileTemplates.redact(text, com.rwai.agent.Graph.profile.values()) != text

    private suspend fun weather(city: String): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            if (city.isNotBlank()) {
                if (leaksPrivateData(city)) return@withContext ToolResult.failed("В названии города есть личные данные владельца.")
                val g = com.rwai.agent.connectors.WeatherClient.geocode(city.trim())
                    ?: return@withContext ToolResult.failed("Город «${city.take(40)}» не найден. Уточните название.")
                return@withContext ToolResult.ok(com.rwai.agent.connectors.WeatherClient.forecast(g.name, g.lat, g.lon), untrusted = true)
            }
            if (!com.rwai.agent.connectors.LocationFinder.hasPermission(context)) {
                return@withContext ToolResult.unavailable("Нет доступа к геолокации. Включите: Ещё → Подключения → Погода. Или назовите город.")
            }
            val loc = com.rwai.agent.connectors.LocationFinder.find(context)
                ?: return@withContext ToolResult.failed("Не удалось определить местоположение (включите геолокацию в шторке телефона) — назовите город.")
            val name = com.rwai.agent.connectors.LocationFinder.placeName(context, loc) ?: "По вашей геолокации"
            ToolResult.ok(com.rwai.agent.connectors.WeatherClient.forecast(name, loc.latitude, loc.longitude), untrusted = true)
        } catch (e: java.io.IOException) {
            ToolResult.failed((e.message ?: "Нет связи с сервисом погоды.").take(160))
        } catch (e: Exception) {
            ToolResult.failed("Не удалось получить погоду: " + e.javaClass.simpleName)
        }
    }

    // --------------------------------------------------------------------------------------------- документы

    private fun registerDoc(f: com.rwai.agent.docs.GeneratedFile, index: List<com.rwai.agent.logic.LineBox>? = null): com.rwai.agent.docs.GeneratedFile {
        val saved = com.rwai.agent.docs.DocFiles.copyToDownloads(context, f)
        val full = com.rwai.agent.docs.GeneratedFile(f.name, f.path, f.mime, saved)
        com.rwai.agent.docs.GeneratedFiles.add(full, index)
        return full
    }

    private suspend fun makePdf(fileName: String?, content: String): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val exp = com.rwai.agent.logic.ProfileTemplates.expand(content, com.rwai.agent.Graph.profile.values())
            val parsed = com.rwai.agent.logic.DocMarkup.parse(exp.text)
            if (parsed.blocks.isEmpty()) return@withContext ToolResult.failed("Документ пустой.")
            val out = com.rwai.agent.docs.DocFiles.uniqueFile(context, com.rwai.agent.logic.DocMarkup.fileName(fileName, "pdf", "Документ"))
            val r = com.rwai.agent.docs.PdfBuilder.build(parsed.blocks, out)
            val f = registerDoc(com.rwai.agent.docs.GeneratedFile(out.name, out.absolutePath, "application/pdf"), r.index)
            val notes = buildList {
                addAll(parsed.warnings)
                if (exp.missing.isNotEmpty()) add("В профиле нет данных: " + exp.missing.joinToString(", ") + " — в тексте остались метки, попроси владельца дополнить профиль.")
            }
            ToolResult.ok("PDF готов: ${f.name}, страниц: ${r.pages}. Владелец видит кнопки «Открыть / Поделиться / Скачать»." + (f.savedTo?.let { " Копия: $it." } ?: "") + (if (notes.isEmpty()) "" else " " + notes.joinToString(" ")))
        } catch (e: Exception) {
            ToolResult.failed("Не удалось создать PDF: " + (e.message ?: e.javaClass.simpleName).take(160))
        }
    }

    private suspend fun drawImage(fileName: String?, commands: String): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val p = com.rwai.agent.logic.ShapeMarkup.parse(commands)
            if (p.shapes.isEmpty()) return@withContext ToolResult.failed("Нет корректных команд. " + p.errors.take(3).joinToString("; "))
            val bmp = com.rwai.agent.docs.Drawing.renderImage(p.shapes)
            val f = registerDoc(com.rwai.agent.docs.Drawing.savePng(context, bmp, com.rwai.agent.logic.DocMarkup.fileName(fileName, "png", "Картинка")))
            bmp.recycle()
            ToolResult.ok("Картинка готова: ${f.name}. Владелец видит кнопки «Открыть / Поделиться / Скачать»." + (if (p.errors.isEmpty()) "" else " Ошибки в командах: " + p.errors.take(3).joinToString("; ")))
        } catch (e: Exception) {
            ToolResult.failed("Не удалось нарисовать: " + (e.message ?: e.javaClass.simpleName).take(160))
        } catch (e: OutOfMemoryError) {
            ToolResult.failed("Не хватило памяти, уменьшите размер canvas.")
        }
    }

    private suspend fun annotateDocument(source: String?, commands: String): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val src = com.rwai.agent.docs.GeneratedFiles.find(source)
                ?: return@withContext ToolResult.failed("Нет файла для разметки. Создайте документ или поделитесь PDF/картинкой с приложением.")
            val p = com.rwai.agent.logic.ShapeMarkup.parse(commands)
            if (p.shapes.isEmpty()) return@withContext ToolResult.failed("Нет корректных команд. " + p.errors.take(3).joinToString("; "))
            val r = com.rwai.agent.docs.Drawing.annotate(context, src, p.shapes, src.name.substringBeforeLast('.') + "_разметка")
            val f = registerDoc(r.file)
            val bad = p.errors.take(3) + (if (r.missing.isEmpty()) emptyList() else listOf("не найдены фразы: " + r.missing.joinToString(", ") { "«$it»" } + " (поиск по тексту работает только для PDF, созданных этим приложением)"))
            ToolResult.ok("Разметка готова: ${f.name}, пометок: ${r.marks}." + (if (bad.isEmpty()) "" else " " + bad.joinToString("; ")))
        } catch (e: Exception) {
            ToolResult.failed("Не удалось сделать разметку: " + (e.message ?: e.javaClass.simpleName).take(160))
        } catch (e: OutOfMemoryError) {
            ToolResult.failed("Не хватило памяти для разметки.")
        }
    }

    private suspend fun webSearch(query: String): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (query.isBlank()) return@withContext ToolResult.failed("Пустой запрос.")
        if (leaksPrivateData(query)) return@withContext ToolResult.failed("В запросе есть личные данные владельца. Переформулируй запрос обезличенно.")
        try {
            val hits = com.rwai.agent.web.WebClient.search(query, com.rwai.agent.Graph.secretStore.get("brave_search_key"), settings.current.language)
            if (hits.isEmpty()) ToolResult.ok("Ничего не найдено. Попробуй другую формулировку.", untrusted = true)
            else ToolResult.ok("Результаты поиска «$query»:\n" + com.rwai.agent.logic.SearchParser.format(hits), untrusted = true)
        } catch (e: com.rwai.agent.web.WebException) {
            ToolResult.failed(e.message ?: "Ошибка поиска")
        } catch (e: java.io.IOException) {
            ToolResult.failed("Нет доступа к интернету или поисковик не отвечает.")
        }
    }

    // ------------------------------------------------------------------ подключения: музыка, YouTube, календарь, почта

    private fun mediaControl(action: String): ToolResult =
        com.rwai.agent.connectors.MediaControl.control(context, action)?.let { ToolResult.failed(it) } ?: ToolResult.ok("Выполнено: $action.")

    private suspend fun playMusic(query: String, app: String?): ToolResult {
        if (query.isBlank()) return ToolResult.failed("Пустой запрос.")
        if (isLocked()) return lockedResult()
        val pkg = app?.let { com.rwai.agent.logic.MediaActions.knownApps[it.trim().lowercase()] }
        val starter = RwAccessibilityService.instance ?: context
        try {
            starter.startActivity(com.rwai.agent.connectors.MediaControl.playIntent(query, pkg))
        } catch (e: Exception) {
            // Нет приложения, понимающего «включить по запросу»: открываем поиск YouTube Music / YouTube
            return try {
                starter.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolResult.ok("Нет плеера, который включает по запросу напрямую; открыл поиск «$query» в YouTube Music. Включить трек придётся нажатием.")
            } catch (e2: Exception) {
                ToolResult.failed("Не нашёл приложение для воспроизведения музыки.")
            }
        }
        delay(3000)
        return ToolResult.ok("Запрос «$query» отправлен плееру. Сейчас: " + com.rwai.agent.connectors.MediaControl.status(context), untrusted = true)
    }

    private suspend fun youtubeSearch(query: String): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (query.isBlank()) return@withContext ToolResult.failed("Пустой запрос.")
        if (leaksPrivateData(query)) return@withContext ToolResult.failed("В запросе есть личные данные владельца.")
        val key = com.rwai.agent.Graph.secretStore.get("youtube_api_key")
        if (key.isNullOrBlank()) {
            if (isLocked()) return@withContext lockedResult()
            return@withContext try {
                (RwAccessibilityService.instance ?: context).startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query, "UTF-8"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                ToolResult.ok("Открыл поиск YouTube по запросу «$query». Ключ YouTube API не задан, поэтому списка результатов нет (Ещё → Подключения).")
            } catch (e: Exception) { ToolResult.failed("Не удалось открыть YouTube.") }
        }
        try {
            ToolResult.ok(com.rwai.agent.connectors.YouTubeClient.search(key, query), untrusted = true)
        } catch (e: java.io.IOException) {
            ToolResult.failed((e.message ?: "Нет связи с YouTube.").replace(key, "***"))
        }
    }

    private fun calendarEvents(days: Int): ToolResult {
        if (!com.rwai.agent.connectors.CalendarClient.canRead(context)) return ToolResult.unavailable("Нет доступа к календарю. Включите его: Ещё → Подключения → Календарь.")
        return try {
            ToolResult.ok(com.rwai.agent.connectors.CalendarClient.events(context, days.coerceIn(1, 14)), untrusted = true)
        } catch (e: Exception) { ToolResult.failed("Не удалось прочитать календарь: ${e.javaClass.simpleName}.") }
    }

    private fun calendarAdd(call: ToolCall): ToolResult {
        if (!com.rwai.agent.connectors.CalendarClient.canWrite(context)) return ToolResult.unavailable("Нет права записи в календарь. Включите его: Ещё → Подключения → Календарь.")
        val start = com.rwai.agent.logic.CalendarArgs.parseStart(call.str("start").orEmpty()) ?: return ToolResult.failed("Неверный формат времени. Нужен вид 2026-11-09T15:00.")
        return try {
            com.rwai.agent.connectors.CalendarClient.add(context, call.str("title").orEmpty(), start, call.int("minutes") ?: 60, call.str("notes"))
                ?.let { ToolResult.failed(it) } ?: ToolResult.ok("Событие «${call.str("title")}» добавлено в календарь.")
        } catch (e: Exception) { ToolResult.failed("Не удалось добавить событие: ${e.javaClass.simpleName}.") }
    }

    private fun mailAccount() = com.rwai.agent.connectors.MailStore.load()
    private val noMail = "Почта не подключена. Подключите её: Ещё → Подключения → Почта."

    private suspend fun mailList(call: ToolCall): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val acc = mailAccount() ?: return@withContext ToolResult.unavailable(noMail)
        val q = call.str("query")
        if (!q.isNullOrBlank() && leaksPrivateData(q)) return@withContext ToolResult.failed("В запросе есть личные данные владельца.")
        try {
            val items = com.rwai.agent.connectors.MailClient.list(acc, (call.int("limit") ?: 10).coerceIn(1, 20), call.bool("unread_only") == true, q)
            ToolResult.ok(com.rwai.agent.logic.MailRules.format(items) { timeFmt.format(Date(it)) }, untrusted = true)
        } catch (e: com.rwai.agent.connectors.MailException) { ToolResult.failed(e.message ?: "Ошибка почты") }
    }

    private suspend fun mailRead(id: Int): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val acc = mailAccount() ?: return@withContext ToolResult.unavailable(noMail)
        if (id <= 0) return@withContext ToolResult.failed("Нужен номер письма из mail_list.")
        try {
            ToolResult.ok(com.rwai.agent.connectors.MailClient.read(acc, id.toLong()) { timeFmt.format(Date(it)) }, untrusted = true)
        } catch (e: com.rwai.agent.connectors.MailException) { ToolResult.failed(e.message ?: "Ошибка почты") }
    }

    private suspend fun mailSend(call: ToolCall): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val acc = mailAccount() ?: return@withContext ToolResult.unavailable(noMail)
        try {
            com.rwai.agent.connectors.MailClient.send(acc, call.str("to").orEmpty().trim(), call.str("subject").orEmpty(), call.str("body").orEmpty())
            ToolResult.ok("Письмо отправлено на ${call.str("to")} (принято почтовым сервером).")
        } catch (e: com.rwai.agent.connectors.MailException) { ToolResult.failed(e.message ?: "Ошибка почты") }
    }

    /** Состояние телефона из системных API: без интерфейса, без открытия приложений. */
    private fun phoneStatus(): ToolResult = try {
        val i = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = (i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
        val pct = if (level < 0) null else level * 100 / scale
        val temp = (i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val plugged = i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val health = i?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL || plugged != 0
        val src = when { plugged and 4 != 0 -> "беспроводная"; plugged and 2 != 0 -> "USB"; plugged and 1 != 0 -> "сеть"; else -> "" }
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29)
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus else 0
        val thermalText = when (thermal) { 0 -> "норма"; 1 -> "слегка тёплый"; 2 -> "тёплый"; 3 -> "перегрев"; 4 -> "сильный перегрев"; else -> "критический" }
        val healthText = when (health) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "хорошее"; android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "перегрев"
            android.os.BatteryManager.BATTERY_HEALTH_COLD -> "холодная"; android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "изношена"
            else -> "не сообщается"
        }
        val sf = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val freeGb = sf.availableBytes / 1_073_741_824.0
        val totalGb = sf.totalBytes / 1_073_741_824.0
        val mi = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        val net = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "нет сети"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
                else -> "сеть есть"
            }
        } catch (_: Exception) { "неизвестно" }
        ToolResult.ok(
            "Заряд: ${pct?.let { "$it%" } ?: "неизвестно"}" + (if (charging) ", заряжается" + (if (src.isNotEmpty()) " ($src)" else "") else ", не заряжается") + "\n" +
                "Температура батареи: ${"%.1f".format(temp)} °C; тепловое состояние: $thermalText; состояние батареи: $healthText\n" +
                "Свободно на накопителе: ${"%.1f".format(freeGb)} из ${"%.0f".format(totalGb)} ГБ\n" +
                "Свободно оперативной памяти: ${mi.availMem / 1_048_576} из ${mi.totalMem / 1_048_576} МБ" + (if (mi.lowMemory) " (мало)" else "") + "\n" +
                "Сеть: $net\n" +
                "Время: " + timeFmt.format(Date()),
        )
    } catch (e: Exception) {
        ToolResult.failed("Не удалось прочитать состояние телефона: " + (e.message ?: e.javaClass.simpleName))
    }

    private suspend fun askClaudeCode(task: String): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val base = settings.current.bridgeUrl.trim().trimEnd('/')
        val token = com.rwai.agent.Graph.secretStore.get("bridge_token")
        if (base.isEmpty() || token.isNullOrBlank()) return@withContext ToolResult.unavailable("Мост к Claude Code не настроен (Настройки → Дополнительно).")
        if (leaksPrivateData(task)) return@withContext ToolResult.failed("В задаче есть личные данные владельца. Опиши её обезличенно.")
        try {
            val body = com.rwai.agent.logic.Json.stringify(mapOf("task" to task))
            val req = okhttp3.Request.Builder().url("$base/ask").header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val client = okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(290, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val map = com.rwai.agent.logic.Json.parse(text).asJsonMap()
                when {
                    r.code == 401 -> ToolResult.failed("Мост отклонил токен. Проверьте токен в настройках.")
                    r.isSuccessful -> ToolResult.ok((map?.get("answer") as? String ?: text).take(6000), untrusted = true)
                    else -> ToolResult.failed("Мост ответил ошибкой ${r.code}: " + (map?.get("error") as? String ?: "").take(300))
                }
            }
        } catch (e: java.io.IOException) {
            ToolResult.failed("Компьютер с мостом недоступен (включён ли он и запущен ли rw_bridge.py?).")
        }
    }

    private suspend fun fetchPage(url: String): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (leaksPrivateData(url)) return@withContext ToolResult.failed("В ссылке есть личные данные владельца.")
        try {
            val (html, finalUrl) = com.rwai.agent.web.WebClient.request(url)
            val text = com.rwai.agent.logic.WebText.htmlToText(html, 6000)
            if (text.isBlank()) ToolResult.ok("Страница пустая или состоит только из скриптов.", untrusted = true)
            else ToolResult.ok("Страница $finalUrl:\n$text", untrusted = true)
        } catch (e: com.rwai.agent.web.WebException) {
            ToolResult.failed(e.message ?: "Не удалось открыть страницу")
        } catch (e: java.io.IOException) {
            ToolResult.failed("Нет доступа к интернету или сайт не отвечает.")
        }
    }

    private fun inputText(rawText: String): ToolResult {
        val svc = RwAccessibilityService.instance ?: return noService()
        val expanded = com.rwai.agent.logic.ProfileTemplates.expand(rawText, com.rwai.agent.Graph.profile.values())
        if (expanded.missing.isNotEmpty()) {
            return ToolResult.failed("В профиле владельца нет полей: " + expanded.missing.joinToString(", ") + ". Спроси владельца или попроси заполнить раздел «Профиль».")
        }
        val text = expanded.text
        return withScreen { cap ->
            when (val r = svc.setText(cap, text)) {
                is RwAccessibilityService.InputResult.Ok -> ToolResult.ok("Текст введён в поле (${text.length} симв.). Сообщение НЕ отправлено.")
                is RwAccessibilityService.InputResult.Failed -> ToolResult.failed(r.reason)
            }
        }
    }

    private fun pressBack(): ToolResult {
        if (isLocked()) return lockedResult()
        val svc = RwAccessibilityService.instance ?: return noService()
        return if (svc.back()) ToolResult.ok("Нажата кнопка «Назад».") else ToolResult.failed("Система не приняла действие «Назад».")
    }

    // ------------------------------------------------------------------ уведомления и память

    private fun readNotifications(limit: Int, app: String?): ToolResult {
        val rows = db.recentNotifications(limit, app)
        if (rows.isEmpty()) {
            val enabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            return ToolResult.ok(
                if (enabled) "Сохранённых уведомлений нет (новые появятся по мере поступления)."
                else "Доступ к уведомлениям не включён: владельцу нужно разрешить его в разделе «Доступ».",
            )
        }
        return ToolResult.ok(
            rows.joinToString("\n") { r ->
                val body = if (r.hidden) "[содержимое скрыто приложением]" else (r.body ?: "").replace("\n", " ").take(300)
                timeFmt.format(Date(r.ts)) + " | " + r.app + " | " + (r.sender ?: r.title ?: "") + ": " + body
            },
            untrusted = true,
        )
    }

    private fun searchMemory(query: String, app: String?, limit: Int): ToolResult {
        val rows = db.searchMemory(query, app, limit)
        if (rows.isEmpty()) return ToolResult.ok("В локальной памяти ничего не найдено по запросу «$query». Это не значит, что переписки нет: память содержит только сохранённые уведомления и выдержки.", untrusted = true)
        return ToolResult.ok(
            rows.joinToString("\n---\n") { r ->
                "#" + r.id + " " + timeFmt.format(Date(r.ts)) + " | " + (r.app ?: r.source) + " | " + (r.contact ?: "") + "\n" + r.title + ": " + r.body.take(500)
            },
            untrusted = true,
        )
    }

    private fun labelOf(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
