package com.rwai.agent.logic

/** Чем занимался один пакет за последний час (по журналу событий Android: нужен «Доступ к статистике»). */
data class AppActivity(
    val pkg: String,
    val label: String,
    /** Минут на экране за окно. */
    val foregroundMin: Int,
    /** Минут со службой переднего плана (музыка, навигация, загрузка, запись) за окно. */
    val serviceMin: Int,
    /** Служба переднего плана запущена и сейчас. */
    val serviceNow: Boolean,
    /** Сколько минут назад приложение было на экране; null — в окне не было. */
    val lastForegroundMinAgo: Int?,
    /** Тип приложения по данным магазина: «игра», «видео», «карты», «музыка», «соцсеть», «камера»; null — неизвестно. */
    val kind: String? = null,
)

data class HeatInput(
    val tempC: Float,
    val thermalStatus: Int,
    val charging: Boolean,
    val screenOn: Boolean,
    /** Модуль тока батареи в мА (по датчику телефона); null — датчика нет. */
    val currentMa: Int?,
    val apps: List<AppActivity>,
    val ownPackage: String,
    /** Сколько минут за окно считала локальная модель Руси. */
    val ownLocalModelMin: Int,
    val ownLocalModelBackend: String,
    /** «Окей, Руся» держит микрофон прямо сейчас. */
    val ownMicHeld: Boolean,
    /** Доступ к статистике выдан (иначе про чужие приложения ничего не известно). */
    val usageAccess: Boolean,
    val windowMin: Int = 60,
)

/** Одна найденная причина: [weight] — насколько вероятно, что именно она греет (больше — вероятнее). */
data class HeatCause(val weight: Int, val title: String, val detail: String)

/**
 * Объяснение, ПОЧЕМУ телефон тёплый: только факты, которые видны приложению (что было на экране, какие службы работали,
 * чем занята сама Руся, сколько тока тянет батарея). Общих советов нет. Чего Android обычным приложениям не показывает
 * (процессор по приложениям, сеть в фоне), не придумывается: если причин не видно, так и сказано.
 */
object HeatReport {
    private val heavyKinds = setOf("игра", "видео", "карты", "камера")

    fun analyze(i: HeatInput): List<HeatCause> {
        val out = ArrayList<HeatCause>()
        val w = i.windowMin

        if (i.ownLocalModelMin >= 1) {
            val dev = if (i.ownLocalModelBackend.isNotBlank()) " (${i.ownLocalModelBackend})" else ""
            out += HeatCause(
                40 + minOf(i.ownLocalModelMin, 40),
                "Руся: локальная модель",
                "Считала ${i.ownLocalModelMin} мин за последний ${windowText(w)}$dev. Это нагрузка на процессор или видеоускоритель. Фоновые тексты можно перевести на DeepSeek: Параметры → Быстрые действия.",
            )
        }
        if (i.ownMicHeld) {
            out += HeatCause(20, "Руся: «Окей, Руся»", "Микрофон занят постоянно; нагрев небольшой, но он идёт, пока слушатель включён.")
        }

        for (a in i.apps) {
            if (a.pkg == i.ownPackage) continue
            val heavy = a.kind in heavyKinds
            if (a.foregroundMin >= 3) {
                val ago = a.lastForegroundMinAgo
                val whenText = if (ago == null || ago <= 1) "были на экране прямо сейчас" else "последний раз на экране $ago мин назад"
                out += HeatCause(
                    a.foregroundMin * (if (heavy) 2 else 1) + (if (ago != null && ago <= 15) 15 else 0),
                    a.label + (a.kind?.let { " ($it)" } ?: ""),
                    "На экране ${a.foregroundMin} мин за последний ${windowText(w)}; $whenText.",
                )
            }
            if (a.serviceNow || a.serviceMin >= 3) {
                out += HeatCause(
                    a.serviceMin + (if (a.serviceNow) 25 else 0),
                    a.label + ": работает в фоне",
                    (if (a.serviceNow) "Служба переднего плана запущена сейчас" else "Держала службу переднего плана") +
                        " (${a.serviceMin} мин за последний ${windowText(w)}): так работают музыка, навигация, загрузки, запись, звонки.",
                )
            }
        }

        val ma = i.currentMa
        if (ma != null && !i.charging && !i.screenOn && ma >= 300) {
            out += HeatCause(
                50 + minOf(ma / 20, 40),
                "Батарея тянет $ma мА при выключенном экране",
                "В покое обычно 20–150 мА. Значит, кто-то работает в фоне прямо сейчас; какой именно, Android обычным приложениям не показывает.",
            )
        } else if (ma != null && !i.charging && i.screenOn && ma >= 900) {
            out += HeatCause(30 + minOf(ma / 50, 30), "Батарея тянет $ma мА при включённом экране", "Это высокая нагрузка (яркий экран, видео, игра или тяжёлое приложение).")
        }
        return out.sortedByDescending { it.weight }
    }

    /** Итоговая строка над списком причин. */
    fun headline(i: HeatInput, causes: List<HeatCause>): String {
        val warm = i.tempC >= 38f || i.thermalStatus >= 2
        val t = "%.1f".format(i.tempC)
        val win = windowText(i.windowMin)
        return when {
            causes.isNotEmpty() && warm -> "Телефон тёплый ($t °C). Вот что за последний $win могло его греть, самое вероятное сверху:"
            causes.isNotEmpty() -> "Телефон не горячий ($t °C). Заметная нагрузка за последний $win:"
            !i.usageAccess && warm -> "Телефон тёплый ($t °C), но без «Доступа к статистике» я не вижу, какие приложения работали. Выдайте доступ кнопкой ниже."
            warm -> "Телефон тёплый ($t °C), но за последний $win я не вижу ни приложений на экране, ни служб в фоне, ни большого тока. " +
                "Вероятно, остаточное тепло (после зарядки, игры, жары) или системные процессы, которые Android обычным приложениям не показывает."
            else -> "Телефон в норме ($t °C), заметной нагрузки не вижу."
        }
    }

    private fun windowText(min: Int) = if (min == 60) "час" else "$min мин"
}
