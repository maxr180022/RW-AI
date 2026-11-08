package com.rwai.agent.logic

import java.time.LocalDateTime
import java.time.ZoneId

data class MailServer(val imapHost: String, val imapPort: Int, val smtpHost: String, val smtpPort: Int, val smtpStartTls: Boolean)

data class MailItem(val uid: Long, val from: String, val subject: String, val date: Long, val unread: Boolean, val snippet: String?)

/** Настройки почтовых серверов по адресу: владельцу достаточно ввести адрес и пароль приложения. */
object MailPresets {
    private val gmail = MailServer("imap.gmail.com", 993, "smtp.gmail.com", 465, false)
    private val yandex = MailServer("imap.yandex.ru", 993, "smtp.yandex.ru", 465, false)
    private val mailru = MailServer("imap.mail.ru", 993, "smtp.mail.ru", 465, false)
    private val icloud = MailServer("imap.mail.me.com", 993, "smtp.mail.me.com", 587, true)
    private val yahoo = MailServer("imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465, false)

    fun forAddress(email: String): MailServer? {
        val d = email.substringAfterLast('@', "").trim().lowercase()
        return when (d) {
            "gmail.com", "googlemail.com" -> gmail
            "yandex.ru", "yandex.com", "ya.ru", "yandex.ua", "yandex.kz", "yandex.by" -> yandex
            "mail.ru", "inbox.ru", "list.ru", "bk.ru", "internet.ru" -> mailru
            "icloud.com", "me.com", "mac.com" -> icloud
            "yahoo.com", "ymail.com" -> yahoo
            else -> null
        }
    }

    /** Подсказка, где взять пароль приложения (обычный пароль почтовые сервисы для таких программ не принимают). */
    fun passwordHelp(email: String): String = when (email.substringAfterLast('@', "").lowercase()) {
        "gmail.com", "googlemail.com" -> "Gmail: включите двухэтапную аутентификацию, затем на myaccount.google.com/apppasswords создайте «пароль приложения» (16 букв)."
        "yandex.ru", "yandex.com", "ya.ru" -> "Яндекс: Почта → Настройки → Почтовые программы → включить IMAP, затем в id.yandex.ru → Безопасность создать «пароль приложения»."
        "mail.ru", "inbox.ru", "list.ru", "bk.ru" -> "Mail.ru: Настройки → Все настройки → Безопасность → Пароли для внешних приложений → создать пароль."
        "icloud.com", "me.com" -> "iCloud: appleid.apple.com → Вход и безопасность → Пароли приложений."
        else -> "Создайте в настройках безопасности почты «пароль приложения» и включите доступ по IMAP."
    }
}

object MailRules {
    private val addr = Regex("^[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9.\\-]{1,200}\\.[A-Za-z]{2,24}$")

    fun validAddress(s: String) = addr.matches(s.trim())

    /** «Имя <a@b.c>» → a@b.c (или исходная строка). */
    fun extractAddress(from: String): String {
        val m = Regex("<([^<>]+)>").find(from)
        return (m?.groupValues?.get(1) ?: from).trim()
    }

    /** Письма от банков агенту не показываются (как и уведомления и приложения банков). */
    fun isBankSender(from: String): Boolean {
        val domain = extractAddress(from).substringAfterLast('@', "").lowercase()
        if (domain.isBlank()) return false
        return BankGuard.isBankingUrl("https://$domain/") || BankGuard.isBankingLabel(from.substringBefore('<'))
    }

    fun format(items: List<MailItem>, fmtDate: (Long) -> String): String {
        if (items.isEmpty()) return "Писем не найдено."
        return items.joinToString("\n") { m ->
            if (isBankSender(m.from)) "#${m.uid} · (письмо от банка скрыто)"
            else "#${m.uid} · ${fmtDate(m.date)} · ${if (m.unread) "НОВОЕ · " else ""}${m.from.take(60)} · ${m.subject.take(100)}" +
                (m.snippet?.takeIf { it.isNotBlank() }?.let { "\n   " + it.replace(Regex("\\s+"), " ").take(160) } ?: "")
        }
    }
}

object CalendarArgs {
    /** «2026-11-09T15:00» или «2026-11-09 15:00» (местное время телефона) → миллисекунды. null — неверный формат. */
    fun parseStart(s: String, zone: ZoneId = ZoneId.systemDefault()): Long? = try {
        LocalDateTime.parse(s.trim().replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli()
    } catch (_: Exception) { null }
}

object MediaActions {
    val all = listOf("play", "pause", "toggle", "next", "previous")
    val knownApps = mapOf(
        "youtube music" to "com.google.android.apps.youtube.music", "ютуб музыка" to "com.google.android.apps.youtube.music",
        "spotify" to "com.spotify.music", "спотифай" to "com.spotify.music", "youtube" to "com.google.android.youtube", "ютуб" to "com.google.android.youtube",
    )
}
