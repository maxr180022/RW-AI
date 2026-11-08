@file:Suppress("DEPRECATION")

package com.rwai.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.rwai.agent.logic.UiNodeInfo

/** Снимок дерева интерфейса: платформо-независимое описание + реальные узлы для действий. */
class ScreenCapture(
    val packageName: String?,
    val infos: List<UiNodeInfo>,
    private val nodes: List<AccessibilityNodeInfo>,
) {
    fun node(index: Int): AccessibilityNodeInfo? = nodes.getOrNull(index)

    fun release() {
        nodes.forEach { try { it.recycle() } catch (_: Exception) { } }
    }
}

/**
 * Служба специальных возможностей Руся AI. Работает только после явного включения пользователем в системных настройках.
 * Не обходит защищённые поля: тексты полей паролей не читаются, ввод в них запрещён; окна с FLAG_SECURE
 * система не отдаёт — в этом случае дерево будет пустым и агент честно сообщит об ограничении.
 */
class RwAccessibilityService : AccessibilityService() {

    @Volatile
    var lastForegroundPackage: String? = null
        private set

    @Volatile
    private var lastExternalPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            // Системные панели (шторка, клавиатура) не считаем «текущим приложением»
            if (pkg != null && pkg != "com.android.systemui" && !pkg.contains("inputmethod")) {
                lastForegroundPackage = pkg
                if (pkg != packageName) lastExternalPackage = pkg
            }
        }
    }

    override fun onInterrupt() {}

    // ------------------------------------------------------------------ чтение

    fun currentPackage(): String? = rootInActiveWindow?.let { r ->
        val p = r.packageName?.toString()
        r.recycle()
        p
    } ?: lastForegroundPackage

    /** Приложение, из которого владелец дал команду: текущее, а если сверху Руся AI (панель/шторка) — последнее чужое. */
    fun originPackage(): String? {
        val cur = currentPackage()
        return if (cur != null && cur != packageName) cur else lastExternalPackage
    }

    fun capture(maxNodes: Int = MAX_NODES): ScreenCapture? {
        val root = rootInActiveWindow ?: return null
        val infos = ArrayList<UiNodeInfo>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        walk(root, 0, infos, nodes, maxNodes)
        return ScreenCapture(root.packageName?.toString(), infos, nodes)
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, infos: MutableList<UiNodeInfo>, nodes: MutableList<AccessibilityNodeInfo>, max: Int) {
        if (infos.size >= max || depth > MAX_DEPTH) return
        if (node.isVisibleToUser) {
            val idx = infos.size
            infos += UiNodeInfo(
                index = idx,
                depth = depth,
                className = node.className?.toString() ?: "View",
                text = if (node.isPassword) null else node.text?.toString(),
                description = if (node.isPassword) null else node.contentDescription?.toString(),
                viewId = node.viewIdResourceName?.substringAfter(":id/"),
                clickable = node.isClickable,
                editable = node.isEditable,
                scrollable = node.isScrollable,
                password = node.isPassword,
                focused = node.isFocused,
            )
            nodes += node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.isVisibleToUser) {
                child.recycle()
                continue
            }
            walk(child, depth + 1, infos, nodes, max)
        }
    }

    // ------------------------------------------------------------------ действия

    /** Нажимает на узел или ближайшего кликабельного предка. Возвращает true, если система приняла действие. */
    fun click(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isClickable && cur.isEnabled) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
            hops++
        }
        return false
    }

    sealed interface InputResult {
        data class Ok(val field: String) : InputResult
        data class Failed(val reason: String) : InputResult
    }

    /** Вводит текст в поле: сначала в поле с фокусом ввода, иначе — в единственное редактируемое поле экрана. */
    fun setText(capture: ScreenCapture, text: String): InputResult {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target: AccessibilityNodeInfo? = when {
            focused != null && focused.isEditable -> focused
            else -> {
                val editable = capture.infos.filter { it.editable }
                when (editable.size) {
                    0 -> null
                    1 -> capture.node(editable[0].index)
                    else -> return InputResult.Failed("На экране несколько полей ввода (${editable.size}); сначала нажмите на нужное поле через tap_text.")
                }
            }
        }
        if (target == null) return InputResult.Failed("На экране нет поля ввода.")
        if (target.isPassword) return InputResult.Failed("Это поле пароля — ввод в него запрещён.")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) InputResult.Ok(target.viewIdResourceName ?: target.className?.toString() ?: "поле") else InputResult.Failed("Приложение отклонило ввод текста.")
    }

    fun scroll(capture: ScreenCapture, down: Boolean): Boolean {
        val rect = Rect()
        val best = capture.infos.filter { it.scrollable }.maxByOrNull { info ->
            capture.node(info.index)?.getBoundsInScreen(rect)
            rect.width().toLong() * rect.height().toLong()
        } ?: return false
        val node = capture.node(best.index) ?: return false
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return node.performAction(action)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    companion object {
        private const val MAX_NODES = 700
        private const val MAX_DEPTH = 40

        @Volatile
        var instance: RwAccessibilityService? = null
            private set
    }
}
