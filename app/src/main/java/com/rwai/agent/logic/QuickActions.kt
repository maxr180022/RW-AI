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
    data object TimeNow : QuickAction
    /** [percent] 1-100 или [steps] шагов ярче (плюс) или темнее (минус). */
    data class Brightness(val percent: Int?, val steps: Int) : QuickAction
    /** [mode]: silent, vibrate, normal. */
    data class Ringer(val mode: String) : QuickAction
    data class PlayMusic(val query: String) : QuickAction
    data class Weather(val city: String?) : QuickAction
    data object CalendarToday : QuickAction
    data class Call(val name: String) : QuickAction
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
        QuickAction.TimeNow -> "время и дата"
        is QuickAction.Brightness -> a.percent?.let { "яркость: $it %" } ?: ("яркость: " + (if (a.steps > 0) "ярче" else "темнее"))
        is QuickAction.Ringer -> "режим звука: " + (when (a.mode) { "silent" -> "без звука"; "vibrate" -> "вибрация"; else -> "со звуком" })
        is QuickAction.PlayMusic -> "включить: " + a.query
        is QuickAction.Weather -> "погода" + (a.city?.let { " в $it" } ?: "")
        QuickAction.CalendarToday -> "календарь на сегодня"
        is QuickAction.Call -> "позвонить: " + a.name
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

    private val timeNow = Regex("^(который час|сколько (сейчас )?времени|сколько время|какое (сегодня )?число|какой (сегодня )?день( недели)?|какая (сегодня )?дата|скажи время|скажи который час)$")
    private val brightSet = Regex("^(поставь|сделай|установи|выставь) яркость( экрана)? (на )?(\\d{1,3})( процентов| процента| процент)?$")
    private val brightUp = Regex("^((сделай )?(ярче|поярче)( экран)?|(прибавь|увеличь|добавь) яркость)$")
    private val brightDown = Regex("^((сделай )?(темнее|потемнее|тусклее)( экран)?|(убавь|уменьши|понизь) яркость)$")
    private val ringerSilent = Regex("^((включи|поставь|переключи) (беззвучный режим|режим без звука|тихий режим|без звука)|выключи звук телефона)$")
    private val ringerVibrate = Regex("^(включи|поставь|переключи) (режим вибрации|вибрацию|вибро|на вибрацию)$")
    private val ringerNormal = Regex("^(включи|верни) (звук|обычный режим|звуковой режим|звук телефона|со звуком)$")
    private val weather = Regex("^(какая )?(погода|погода сейчас|погода сегодня)$")
    private val weatherCity = Regex("^(какая )?погода (в|во) (.{2,30})$")
    private val calendarToday = Regex("^((что|какие) (у меня )?(сегодня )?(в календаре|планы|дела)( на сегодня| сегодня)?|какие (у меня )?планы( на сегодня)?)$")
    private val callRule = Regex("^(позвони|набери|вызови|звони) (.{2,40})$")
    private val callNotPlain = listOf(" и ", " потом ", " в ватсап", " в whatsapp", " в телеграм", " в telegram", " в вайбер", " в инстаграм", " по ватсап", " по телеграм", " по видео")
    private val playMusic = Regex("^(включи|поставь|запусти) (песню|трек|музыку|альбом|плейлист) (.{2,80})$")
    private val musicNotAQuery = setOf("громче", "тише", "потише", "погромче", "дальше", "на паузу", "сначала")

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
        if (timeNow.matches(s)) return QuickAction.TimeNow
        brightSet.matchEntire(s)?.let { m ->
            val p = m.groupValues[4].toIntOrNull() ?: return null
            return if (p in 1..100) QuickAction.Brightness(p, 0) else null
        }
        if (brightUp.matches(s)) return QuickAction.Brightness(null, 2)
        if (brightDown.matches(s)) return QuickAction.Brightness(null, -2)
        if (ringerSilent.matches(s)) return QuickAction.Ringer("silent")
        if (ringerVibrate.matches(s)) return QuickAction.Ringer("vibrate")
        if (ringerNormal.matches(s)) return QuickAction.Ringer("normal")
        if (weather.matches(s)) return QuickAction.Weather(null)
        weatherCity.matchEntire(s)?.let { return QuickAction.Weather(it.groupValues[3].trim()) }
        if (calendarToday.matches(s)) return QuickAction.CalendarToday
        callRule.matchEntire(s)?.let { m ->
            val who = m.groupValues[2].trim()
            // Звонок в мессенджере, цифры и составные просьбы не трогаем: их разбирает агент (или человек).
            if (who.any { it.isDigit() } || callNotPlain.any { (" $who").contains(it) } || who.split(' ').size > 3) return null
            return QuickAction.Call(who)
        }
        playMusic.matchEntire(s)?.let { m ->
            val q = m.groupValues[3].trim()
            if (q.length >= 2 && q !in musicNotAQuery) return QuickAction.PlayMusic(q)
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
        "включи", "выключи", "отключи", "открой", "запусти", "убавь", "прибавь", "поставь", "яркост", "ярче", "темнее", "вибр", "беззвуч", "погод",
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
            "brightness: percent (1-100) яркость экрана\n" +
            "brightness_step: steps (целое от -5 до 5; плюс ярче, минус темнее)\n" +
            "ringer: mode (silent без звука, vibrate вибрация, normal со звуком)\n" +
            "weather: city (необязательно) вопрос про погоду\n" +
            "play_music: query (что включить)\n" +
            "time: без полей, вопрос про время или дату\n" +
            "calendar: без полей, вопрос про события сегодня\n" +
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
            "brightness" -> int("percent")?.takeIf { it in 1..100 }?.let { QuickAction.Brightness(it, 0) }
            "brightness_step" -> int("steps")?.takeIf { it in -5..5 && it != 0 }?.let { QuickAction.Brightness(null, it) }
            "ringer" -> (map["mode"] as? String)?.takeIf { it in setOf("silent", "vibrate", "normal") }?.let { QuickAction.Ringer(it) }
            "weather" -> QuickAction.Weather((map["city"] as? String)?.trim()?.takeIf { it.length in 2..40 })
            "play_music" -> (map["query"] as? String)?.trim()?.takeIf { it.length in 2..100 }?.let { QuickAction.PlayMusic(it) }
            "time" -> QuickAction.TimeNow
            "calendar" -> QuickAction.CalendarToday
            else -> null
        }
    }
}

/** Контакт из телефонной книги: имя и номер. */
data class ContactRef(val name: String, val number: String)

/**
 * Поиск контакта по тому, как человек сказал: «Саше», «маме», «Павлу Короткову». Русские падежи учитываются отбрасыванием окончания.
 * Возвращает контакты с лучшим совпадением (уникальные по имени): один — можно звонить, несколько — переспросить.
 */
object ContactMatch {
    fun norm(s: String): String = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun wordTier(qw: String, words: List<String>): Int {
        if (words.any { it == qw }) return 1
        if (words.any { it.startsWith(qw) }) return 2
        val stem = if (qw.length >= 4) qw.dropLast(1) else qw
        if (stem.length >= 3 && words.any { it.startsWith(stem) }) return 3
        if (qw.length >= 4 && words.any { it.startsWith(qw.take(3)) }) return 4
        return 99
    }

    fun find(query: String, contacts: List<ContactRef>): List<ContactRef> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val qWords = q.split(' ')
        val scored = contacts.mapNotNull { c ->
            val n = norm(c.name)
            val words = n.split(' ')
            val tier = if (n == q) 0 else qWords.maxOf { wordTier(it, words) }
            if (tier < 99) c to tier else null
        }
        val best = scored.minOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { it.first }.distinctBy { norm(it.name) }
    }
}
