package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionsTest {
    private fun m(s: String) = QuickRules.match(s)

    @Test fun flashlight() {
        assertEquals(QuickAction.Flashlight(true), m("Включи фонарик"))
        assertEquals(QuickAction.Flashlight(true), m("Руся, включи мне фонарик на телефоне, пожалуйста!"))
        assertEquals(QuickAction.Flashlight(false), m("выключи фонарик"))
        assertEquals(QuickAction.Flashlight(false), m("Выключи фонарь."))
    }

    @Test fun doNotDisturb() {
        assertEquals(QuickAction.Dnd(false), m("выключи постоянный режим не беспокоить"))
        assertEquals(QuickAction.Dnd(true), m("включи режим не беспокоить"))
        assertNull(m("выключи не беспокоить в расписании сна"))
    }

    @Test fun volume() {
        assertEquals(QuickAction.MediaVolume(50, 0), m("поставь громкость на 50"))
        assertEquals(QuickAction.MediaVolume(30, 0), m("сделай звук музыки на 30 процентов"))
        assertEquals(QuickAction.MediaVolume(100, 0), m("поставь громкость на максимум"))
        assertEquals(QuickAction.MediaVolume(null, 2), m("громче"))
        assertEquals(QuickAction.MediaVolume(null, -2), m("сделай потише"))
        assertNull(m("поставь громкость на 150"))
    }

    @Test fun alarm() {
        assertEquals(QuickAction.Alarm(20, 0), m("поставь будильник на 20:00"))
        assertEquals(QuickAction.Alarm(7, 30), m("заведи будильник на 7 30"))
        assertEquals(QuickAction.Alarm(19, 15), m("поставь будильник на 7:15 вечера"))
        assertEquals(QuickAction.Alarm(0, 5), m("поставь будильник на 12:05 ночи"))
        assertEquals(QuickAction.Alarm(7, 20), m("поставь будильник на 7:00 утра 20 минут"))
        assertEquals(QuickAction.Alarm(7, 20), m("поставь будильник на 7 утра 20 минут"))
        assertEquals(QuickAction.Alarm(7, 20), m("поставь будильник на 7 утра и 20 минут"))
        assertEquals(QuickAction.Alarm(7, 20), m("поставь будильник на 7 часов 20 минут"))
        assertEquals(QuickAction.Alarm(19, 45), m("заведи будильник на 7 вечера 45 минут"))
        assertEquals(QuickAction.Alarm(7, 0), m("поставь будильник на 7 часов"))
        assertNull(m("поставь будильник на 7:30 утра 20 минут"))
        assertNull(m("поставь будильник на 25:00"))
        assertNull(m("поставь будильник на 8 и напиши Саше"))
    }

    @Test fun timer() {
        assertEquals(QuickAction.Timer(300), m("поставь таймер на 5 минут"))
        assertEquals(QuickAction.Timer(45), m("заведи таймер на 45 секунд"))
        assertEquals(QuickAction.Timer(7200), m("запусти таймер на 2 часа"))
        assertEquals(QuickAction.Timer(1800), m("поставь таймер на полчаса"))
    }

    @Test fun batteryAndMedia() {
        assertEquals(QuickAction.Battery, m("какой заряд"))
        assertEquals(QuickAction.Battery, m("сколько у меня заряда"))
        assertEquals(QuickAction.Media("pause"), m("пауза"))
        assertEquals(QuickAction.Media("next"), m("следующий трек"))
        assertEquals(QuickAction.Media("previous"), m("предыдущая песня"))
    }

    @Test fun openAppOnlyForShortNamesWithoutExtraRequests() {
        assertEquals(QuickAction.OpenApp("telegram"), m("открой Telegram"))
        assertEquals(QuickAction.OpenApp("настройки"), m("открой настройки"))
        assertNull(m("открой ватсап и напиши Саше привет"))
        assertNull(m("открой ссылку из письма"))
        assertNull(m("открой сайт банка"))
    }

    @Test fun complexOrUnknownCommandsAreNotGuessed() {
        assertNull(m("ответь Саше что я перезвоню"))
        assertNull(m("что нового в почте"))
        assertNull(m("включи фонарик и открой камеру"))
        assertNull(m(""))
    }

    @Test fun localModelIsAskedOnlyAboutShortDeviceCommands() {
        assertTrue(QuickDispatch.worthAsking("посвети фонариком"))
        assertTrue(QuickDispatch.worthAsking("убавь звук чуть-чуть"))
        assertFalse(QuickDispatch.worthAsking("напиши Саше что я задержусь и включи свет"))
        assertFalse(QuickDispatch.worthAsking("что у меня сегодня в календаре"))
        assertFalse(QuickDispatch.worthAsking("x ".repeat(50)))
    }

    @Test fun modelAnswerIsParsedStrictly() {
        assertEquals(QuickAction.Flashlight(true), QuickDispatch.parse("""{"action":"flashlight","on":true,"confidence":0.95}"""))
        assertEquals(QuickAction.Alarm(8, 0), QuickDispatch.parse("Вот ответ: {\"action\":\"alarm\",\"hour\":8,\"minute\":0,\"confidence\":0.9} готово"))
        assertEquals(QuickAction.MediaVolume(null, -3), QuickDispatch.parse("""{"action":"volume_step","steps":-3,"confidence":0.85}"""))
        assertEquals(QuickAction.Media("next"), QuickDispatch.parse("""{"action":"media","kind":"next","confidence":0.9}"""))
    }

    @Test fun lowConfidenceBadFieldsAndNoneGoToTheAgent() {
        assertNull(QuickDispatch.parse("""{"action":"flashlight","on":true,"confidence":0.5}"""))
        assertNull(QuickDispatch.parse("""{"action":"flashlight","on":true}"""))
        assertNull(QuickDispatch.parse("""{"action":"alarm","hour":30,"minute":0,"confidence":0.9}"""))
        assertNull(QuickDispatch.parse("""{"action":"timer","seconds":0,"confidence":0.9}"""))
        assertNull(QuickDispatch.parse("""{"action":"none"}"""))
        assertNull(QuickDispatch.parse("не знаю"))
        assertNull(QuickDispatch.parse("""{"action":"format_disk","confidence":1.0}"""))
    }

    @Test fun timeBrightnessRingerWeatherCalendarMusic() {
        assertEquals(QuickAction.TimeNow, m("который час"))
        assertEquals(QuickAction.TimeNow, m("Какое сегодня число?"))
        assertEquals(QuickAction.Brightness(50, 0), m("поставь яркость на 50"))
        assertEquals(QuickAction.Brightness(null, 2), m("ярче"))
        assertEquals(QuickAction.Brightness(null, -2), m("сделай потемнее"))
        assertNull(m("поставь яркость на 0"))
        assertEquals(QuickAction.Ringer("silent"), m("включи беззвучный режим"))
        assertEquals(QuickAction.Ringer("vibrate"), m("включи вибрацию"))
        assertEquals(QuickAction.Ringer("normal"), m("включи звук"))
        assertEquals(QuickAction.Weather(null), m("какая погода"))
        assertEquals(QuickAction.Weather("перми"), m("какая погода в перми"))
        assertEquals(QuickAction.CalendarToday, m("что у меня сегодня в календаре"))
        assertEquals(QuickAction.PlayMusic("кино группа крови"), m("включи песню кино группа крови"))
        assertNull(m("включи музыку громче"))
        assertEquals(QuickAction.Media("play"), m("включи музыку"))
    }

    @Test fun modelAnswerForNewActionsIsParsed() {
        assertEquals(QuickAction.Brightness(30, 0), QuickDispatch.parse("""{"action":"brightness","percent":30,"confidence":0.9}"""))
        assertEquals(QuickAction.Ringer("vibrate"), QuickDispatch.parse("""{"action":"ringer","mode":"vibrate","confidence":0.9}"""))
        assertEquals(QuickAction.Weather("Монтевидео"), QuickDispatch.parse("""{"action":"weather","city":"Монтевидео","confidence":0.9}"""))
        assertEquals(QuickAction.TimeNow, QuickDispatch.parse("""{"action":"time","confidence":0.9}"""))
        assertNull(QuickDispatch.parse("""{"action":"ringer","mode":"explode","confidence":0.99}"""))
        assertNull(QuickDispatch.parse("""{"action":"brightness","percent":500,"confidence":0.99}"""))
    }

    @Test fun callByNameRuleIsStrict() {
        assertEquals(QuickAction.Call("саше"), m("позвони Саше"))
        assertEquals(QuickAction.Call("маме"), m("Руся, позвони маме"))
        assertEquals(QuickAction.Call("павлу короткову"), m("набери Павлу Короткову"))
        assertNull(m("позвони Саше в ватсап"))
        assertNull(m("позвони Саше и скажи что я опоздаю"))
        assertNull(m("позвони 112"))
    }

    @Test fun contactMatchHandlesRussianCases() {
        val book = listOf(ContactRef("Саша Студия", "+1"), ContactRef("Александр Иванов", "+2"), ContactRef("Мама", "+3"), ContactRef("Павел Коротаев", "+4"), ContactRef("Женек", "+5"))
        assertEquals(listOf("Саша Студия"), ContactMatch.find("саше", book).map { it.name })
        assertEquals(listOf("Мама"), ContactMatch.find("маме", book).map { it.name })
        assertEquals(listOf("Павел Коротаев"), ContactMatch.find("павлу коротаеву", book).map { it.name })
        assertEquals(listOf("Женек"), ContactMatch.find("женьку", book).map { it.name })
        assertTrue(ContactMatch.find("олегу", book).isEmpty())
    }

    @Test fun contactMatchReportsAmbiguityAndPrefersExact() {
        val book = listOf(ContactRef("Саша Студия", "+1"), ContactRef("Саша", "+2"), ContactRef("Саша Дом", "+3"))
        assertEquals(listOf("Саша"), ContactMatch.find("саша", book).map { it.name })
        val two = listOf(ContactRef("Саша Студия", "+1"), ContactRef("Саша Дом", "+3"))
        assertEquals(2, ContactMatch.find("саша", two).size)
    }

    @Test fun thinkingIsKeptForPlanningAndErrorsOnly() {
        assertTrue(ThinkingPolicy.shouldThink(true, 1, "ЗАДАЧА ВЛАДЕЛЬЦА: x"))
        assertTrue(ThinkingPolicy.shouldThink(true, 3, "РЕЗУЛЬТАТ tap_text: status=OK"))
        assertFalse(ThinkingPolicy.shouldThink(true, 4, "РЕЗУЛЬТАТ tap_text: status=OK\nНажато"))
        assertTrue(ThinkingPolicy.shouldThink(true, 9, "РЕЗУЛЬТАТ tap_text: status=FAILED\nНе найдено"))
        assertTrue(ThinkingPolicy.shouldThink(true, 9, "ОШИБКА ФОРМАТА: нет JSON"))
        assertFalse(ThinkingPolicy.shouldThink(false, 1, "ЗАДАЧА"))
    }

    @Test fun labelsAreReadable() {
        assertEquals("фонарик: выключить", QuickActions.label(QuickAction.Flashlight(false)))
        assertEquals("будильник: 07:05", QuickActions.label(QuickAction.Alarm(7, 5)))
        assertEquals("таймер: 5 мин", QuickActions.label(QuickAction.Timer(300)))
    }
}
