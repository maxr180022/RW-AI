package com.rwai.agent.logic

/**
 * Быстрые действия: простые команды, которые телефон выполняет сам напрямую (системными вызовами), без DeepSeek, без токенов и без
 * хождения по экранам. Сначала точные правила (мгновенно), потом, для нечётких фраз, локальная модель; всё остальное уходит агенту.
 */
sealed interface QuickAction {
    data class Flashlight(val on: Boolean) : QuickAction
    /** [percent] — поставить громкость музыки; иначе [steps] шагов вверх (плюс) или вниз (минус). */
    data class MediaVolume(val percent: Int?, val steps: Int) : QuickAction
    data class Dnd(val on: Boolean) : QuickAction
    data class Alarm(val hour: Int, val minute: Int) : QuickAction
    data class Timer(val seconds: Int) : QuickAction
    data class OpenApp(val name: String) : QuickAction
    data object Battery : QuickAction
    /** [kind]: play, pause, next, previous. */
    data class Media(val kind: String) : QuickAction
}

object QuickActions {
    fun label(a: QuickAction): String = when (a) {
        is QuickAction.Flashlight -> if (a.on) "фонарик: включить" else "фонарик: выключить"
        is QuickAction.MediaVolume -> a.percent?.let { "громкость музыки: $it %" } ?: ("громкость музыки: " + (if (a.steps > 0) "громче" else "тише"))
        is QuickAction.Dnd -> if (a.on) "не беспокоить: включить" else "не беспокоить: выключить"
        is QuickAction.Alarm -> "будильник: " + "%02d:%02d".format(a.hour, a.minute)
        is QuickAction.Timer -> "таймер: " + timerText(a.seconds)
        is QuickAction.OpenApp -> "открыть приложение: " + a.name
        QuickAction.Battery -> "заряд батареи"
        is QuickAction.Media -> "плеер: " + a.kind
    }

    fun timerText(seconds: Int): String = when {
        seconds % 3600 == 0 -> (seconds / 3600).toString() + " ч"
        seconds % 60 == 0 -> (seconds / 60).toString() + " мин"
        else -> "$seconds с"
    }
}

/** Точные правила для самых частых команд. Совпадение только с целой фразой: сомнительное уходит дальше, а не угадывается. */
object QuickRules {
    private val fillers = listOf("пожалуйста ", "руся ", "слушай ", "ну ", "давай ", "а ", "будь добр ", "можешь ", "ты можешь ")

    fun normalize(text: String): String {
        var s = text.lowercase().replace('ё', 'е').replace(Regex("[.,!?;:\"«»()]"), " ").replace(Regex("\\s+"), " ").trim()
        var changed = true
        while (changed) {
            changed = false
            for (f in fillers) if (s.startsWith(f)) { s = s.removePrefix(f).trim(); changed = true }
        }
        for (t in listOf(" пожалуйста", " мне", " плиз")) if (s.endsWith(t)) s = s.removeSuffix(t).trim()
        return s
    }

    private val torchOn = Regex("^(включи|зажги|вруби|врубай|запусти|включай) (мне )?(фонарик|фонарь|вспышку)( на телефоне)?$")
    private val torchOff = Regex("^(выключи|погаси|отключи|выруби|вырубай|потуши|выключай) (мне )?(фонарик|фонарь|вспышку)( на телефоне)?$")
    private val dnd = Regex("^(включи|выключи|отключи|вруби|выруби|сними|убери) (постоянный )?(режим )?не беспокоить( на телефоне)?$")
    private val volSet = Regex("^(поставь|сделай|установи|выставь) (громкость|звук)( музыки| мультимедиа)? (на )?(\\d{1,3})( процентов| процента| процент)?$")
    private val volWord = Regex("^(поставь|сделай|установи|выставь) (громкость|звук)( музыки| мультимедиа)? на (максимум|минимум|половину|полную)$")
    private val volUp = Regex("^((сделай )?(громче|погромче)( звук| музыку)?|(прибавь|увеличь|добавь) (громкость|звук))$")
    private val volDown = Regex("^((сделай )?(тише|потише)( звук| музыку)?|(убавь|уменьши|понизь) (громкость|звук))$")
    private val alarm = Regex("^(поставь|заведи|установи|сделай|включи) (мне )?будильник на (\\d{1,2})(?:[: .](\\d{2}))?( утра| вечера| дня| ночи)?$")
    private val timer = Regex("^(поставь|заведи|запусти|включи|засеки) (мне )?таймер на (\\d{1,4}) (секунд\\p{L}*|сек|минут\\p{L}*|мин|час\\p{L}*)$")
    private val timerWord = Regex("^(поставь|заведи|запусти|включи|засеки) (мне )?таймер на (полчаса|час)$")
    private val openApp = Regex("^(открой|запусти|открыть) (приложение )?(.{2,30})$")
    private val battery = Regex("^((какой|сколько) (у меня )?(заряд|заряда|процентов)( батареи| на телефоне| осталось)?|сколько (у меня )?заряда( осталось)?|какой заряд( батареи)?( на телефоне)?)$")
    private val mediaPause = Regex("^(пауза|поставь на паузу|останови (музыку|плеер|воспроизведение)|выключи музыку)$")
    private val mediaPlay = Regex("^(продолжи( музыку| воспроизведение)?|возобнови( музыку| воспроизведение)?|включи музыку|играй)$")
    private val mediaNext = Regex("^(следующий трек|следующая песня|включи следующий трек|включи следующую песню)$")
    private val mediaPrev = Regex("^(предыдущий трек|предыдущая песня|включи предыдущий трек|включи предыдущую песню)$")

    private val openStop = setOf("и", "потом", "затем", "напиши", "отправь", "ответь", "найди", "позвони", "расскажи", "покажи", "прочитай", "включи", "выключи")

    fun match(text: String): QuickAction? {
        val s = normalize(text)
        if (s.isEmpty() || s.length > 80) return null
        if (torchOn.matches(s)) return QuickAction.Flashlight(true)
        if (torchOff.matches(s)) return QuickAction.Flashlight(false)
        dnd.matchEntire(s)?.let { return QuickAction.Dnd(it.groupValues[1] in setOf("включи", "вруби")) }
        volSet.matchEntire(s)?.let { m ->
            val p = m.groupValues[5].toIntOrNull() ?: return null
            return if (p in 0..100) QuickAction.MediaVolume(p, 0) else null
        }
        volWord.matchEntire(s)?.let { m ->
            val p = when (m.groupValues[4]) { "максимум", "полную" -> 100; "минимум" -> 0; else -> 50 }
            return QuickAction.MediaVolume(p, 0)
        }
        if (volUp.matches(s)) return QuickAction.MediaVolume(null, 2)
        if (volDown.matches(s)) return QuickAction.MediaVolume(null, -2)
        alarm.matchEntire(s)?.let { m ->
            var h = m.groupValues[3].toIntOrNull() ?: return null
            val min = m.groupValues[4].ifEmpty { "0" }.toIntOrNull() ?: return null
            when (m.groupValues[5].trim()) {
                "вечера", "дня" -> if (h < 12) h += 12
                "утра", "ночи" -> if (h == 12) h = 0
            }
            return if (h in 0..23 && min in 0..59) QuickAction.Alarm(h, min) else null
        }
        timerWord.matchEntire(s)?.let { return QuickAction.Timer(if (it.groupValues[3] == "час") 3600 else 1800) }
        timer.matchEntire(s)?.let { m ->
            val n = m.groupValues[3].toIntOrNull() ?: return null
            val unit = m.groupValues[4]
            val sec = when { unit.startsWith("сек") -> n; unit.startsWith("мин") -> n * 60; unit.startsWith("час") -> n * 3600; else -> return null }
            return if (sec in 1..86_400) QuickAction.Timer(sec) else null
        }
        if (battery.matches(s)) return QuickAction.Battery
        if (mediaPause.matches(s)) return QuickAction.Media("pause")
        if (mediaPlay.matches(s)) return QuickAction.Media("play")
        if (mediaNext.matches(s)) return QuickAction.Media("next")
        if (mediaPrev.matches(s)) return QuickAction.Media("previous")
        openApp.matchEntire(s)?.let { m ->
            val name = m.groupValues[3].trim()
            val words = name.split(' ')
            if (words.size <= 3 && words.none { it in openStop } && !name.startsWith("ссылк") && !name.startsWith("сайт") && !name.startsWith("http")) {
                return QuickAction.OpenApp(name)
            }
        }
        return null
    }
}

/** Разбор нечётких фраз локальной моделью: она отвечает JSON, мы его проверяем жёстко. Сомнение или ошибка: команда уходит агенту. */
object QuickDispatch {
    private val hints = listOf(
        "фонар", "громк", "звук", "громче", "тише", "будиль", "таймер", "не беспок", "заряд", "пауз", "трек", "песн", "музык", "разбуд",
        "включи", "выключи", "отключи", "открой", "запусти", "убавь", "прибавь", "поставь",
    )
    private val stops = listOf(
        "напиши", "ответь", "отправь", "найди", "расскажи", "переведи", "составь", "прочитай", "почт", "сообщен", "письм", "видео", "ссылк", "сайт",
        "http", "документ", "pdf", "фото", "картин", "календар", "позвони", "купи", "оплат", "перевед",
    )

    /** Стоит ли тратить на фразу локальную модель: коротко, похоже на управление телефоном и нет слов про сообщения, документы и деньги. */
    fun worthAsking(text: String): Boolean {
        val s = QuickRules.normalize(text)
        if (s.isEmpty() || s.length > 70 || s.split(' ').size > 9) return false
        if (stops.any { s.contains(it) }) return false
        return hints.any { s.contains(it) }
    }

    fun prompt(text: String): String =
        "Ты разбираешь короткую команду владельца телефона. Верни ТОЛЬКО один JSON-объект без пояснений и без Markdown.\n" +
            "Если команда ТОЧНО подходит под одно из действий, верни {\"action\":\"имя\", поля, \"confidence\":число от 0 до 1}. " +
            "Если не подходит, это просьба написать, найти, объяснить или есть любое сомнение, верни {\"action\":\"none\"}.\n" +
            "Действия и поля:\n" +
            "flashlight: on (true или false)\n" +
            "volume: percent (целое 0-100) громкость музыки\n" +
            "volume_step: steps (целое от -5 до 5; плюс громче, минус тише)\n" +
            "dnd: on (true или false) режим «Не беспокоить»\n" +
            "alarm: hour (0-23), minute (0-59)\n" +
            "timer: seconds (целое 1-86400)\n" +
            "open_app: name (название приложения)\n" +
            "battery: без полей, вопрос про заряд\n" +
            "media: kind (play, pause, next или previous)\n" +
            "Команда: «" + text.trim().take(120) + "»"

    private const val MIN_CONFIDENCE = 0.8

    fun parse(output: String): QuickAction? {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val map = try { Json.parse(output.substring(start, end + 1)) as? Map<*, *> } catch (_: Exception) { null } ?: return null
        val conf = (map["confidence"] as? Number)?.toDouble() ?: return null
        if (conf < MIN_CONFIDENCE) return null
        fun int(k: String): Int? = (map[k] as? Number)?.toInt()
        fun bool(k: String): Boolean? = map[k] as? Boolean
        return when (map["action"] as? String) {
            "flashlight" -> bool("on")?.let { QuickAction.Flashlight(it) }
            "volume" -> int("percent")?.takeIf { it in 0..100 }?.let { QuickAction.MediaVolume(it, 0) }
            "volume_step" -> int("steps")?.takeIf { it in -5..5 && it != 0 }?.let { QuickAction.MediaVolume(null, it) }
            "dnd" -> bool("on")?.let { QuickAction.Dnd(it) }
            "alarm" -> {
                val h = int("hour"); val m = int("minute") ?: 0
                if (h != null && h in 0..23 && m in 0..59) QuickAction.Alarm(h, m) else null
            }
            "timer" -> int("seconds")?.takeIf { it in 1..86_400 }?.let { QuickAction.Timer(it) }
            "open_app" -> (map["name"] as? String)?.trim()?.takeIf { it.length in 2..30 && it.split(' ').size <= 3 }?.let { QuickAction.OpenApp(it) }
            "battery" -> QuickAction.Battery
            "media" -> (map["kind"] as? String)?.takeIf { it in setOf("play", "pause", "next", "previous") }?.let { QuickAction.Media(it) }
            else -> null
        }
    }
}
