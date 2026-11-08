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
        "open_url" -> openUrl(call.str("url").orEmpty())
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

    private fun lockedResult() = ToolResult.failed("Телефон заблокирован. Разблокируйте его сами — RW AI не обходит блокировку экрана.")

    private fun noService() = ToolResult.unavailable(
        "Служба специальных возможностей RW AI не включена. Владельцу нужно включить её: Настройки Android → Специальные возможности → RW AI.",
    )

    private inline fun withScreen(block: (com.rwai.agent.accessibility.ScreenCapture) -> ToolResult): ToolResult {
        if (isLocked()) return lockedResult()
        val svc = RwAccessibilityService.instance ?: return noService()
        val cap = svc.capture() ?: return ToolResult.failed("Нет активного окна: экран пуст или приложение защитило содержимое.")
        try {
            if (cap.packageName == context.packageName) return ToolResult.failed("Агент не работает с экраном самого RW AI.")
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

    private fun inputText(text: String): ToolResult {
        val svc = RwAccessibilityService.instance ?: return noService()
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
