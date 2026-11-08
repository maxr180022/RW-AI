package com.rwai.agent.connectors

import com.rwai.agent.Graph
import com.rwai.agent.logic.MailItem
import com.rwai.agent.logic.MailPresets
import com.rwai.agent.logic.MailRules
import com.rwai.agent.logic.MailServer
import com.rwai.agent.logic.WebText
import com.sun.mail.imap.IMAPFolder
import java.util.Date
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.BodyTerm
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm

data class MailAccount(val user: String, val pass: String, val server: MailServer)

class MailException(message: String) : Exception(message)

/** Хранение данных входа в почту: только в зашифрованном хранилище на телефоне. */
object MailStore {
    private const val U = "mail_user"; private const val P = "mail_pass"; private const val I = "mail_imap"; private const val S = "mail_smtp"

    fun save(user: String, pass: String, customImap: String?, customSmtp: String?) {
        val s = Graph.secretStore
        s.put(U, user.trim()); s.put(P, pass.trim())
        if (!customImap.isNullOrBlank()) s.put(I, customImap.trim()) else s.remove(I)
        if (!customSmtp.isNullOrBlank()) s.put(S, customSmtp.trim()) else s.remove(S)
    }

    fun clear() { val s = Graph.secretStore; s.remove(U); s.remove(P); s.remove(I); s.remove(S) }

    fun user(): String? = Graph.secretStore.get(U)

    fun load(): MailAccount? {
        val s = Graph.secretStore
        val user = s.get(U) ?: return null
        val pass = s.get(P) ?: return null
        val server = resolve(user, s.get(I), s.get(S)) ?: return null
        return MailAccount(user, pass, server)
    }

    /** Свои серверы вводятся как «host» или «host:port». */
    fun resolve(user: String, imap: String?, smtp: String?): MailServer? {
        val preset = MailPresets.forAddress(user)
        if (imap.isNullOrBlank() && smtp.isNullOrBlank()) return preset
        fun hp(v: String?, defPort: Int, fallbackHost: String?): Pair<String, Int>? {
            val raw = v?.trim().orEmpty()
            if (raw.isEmpty()) return fallbackHost?.let { it to defPort }
            val host = raw.substringBefore(':')
            val port = raw.substringAfter(':', "").toIntOrNull() ?: defPort
            return if (host.isBlank()) null else host to port
        }
        val i = hp(imap, 993, preset?.imapHost) ?: return null
        val sm = hp(smtp, 465, preset?.smtpHost) ?: return null
        return MailServer(i.first, i.second, sm.first, sm.second, sm.second == 587)
    }
}

/** IMAP/SMTP через JavaMail. Все вызовы — не из главного потока. Чтение не помечает письма прочитанными (peek). */
object MailClient {
    private fun fail(e: Exception): Nothing = throw when (e) {
        is AuthenticationFailedException -> MailException("Почта не приняла адрес или пароль приложения. Нужен именно «пароль приложения», а не обычный пароль.")
        is MailException -> e
        is MessagingException -> MailException("Ошибка почты: " + (e.message ?: e.javaClass.simpleName).take(160))
        is java.io.IOException -> MailException("Нет связи с почтовым сервером.")
        else -> MailException("Ошибка почты: " + (e.message ?: e.javaClass.simpleName).take(160))
    }

    private fun imapProps(a: MailAccount) = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", a.server.imapHost); put("mail.imaps.port", a.server.imapPort.toString())
        put("mail.imaps.ssl.enable", "true"); put("mail.imaps.connectiontimeout", "15000"); put("mail.imaps.timeout", "30000"); put("mail.imaps.peek", "true")
    }

    private fun <T> withInbox(a: MailAccount, block: (IMAPFolder) -> T): T {
        val store = Session.getInstance(imapProps(a)).getStore("imaps")
        try {
            store.connect(a.server.imapHost, a.server.imapPort, a.user, a.pass)
            val f = store.getFolder("INBOX") as IMAPFolder
            f.open(Folder.READ_ONLY)
            val result = try { block(f) } finally { try { f.close(false) } catch (_: Exception) { } }
            return result
        } catch (e: Exception) {
            fail(e)
        } finally {
            try { store.close() } catch (_: Exception) { }
        }
    }

    /** null — вход удался; иначе текст ошибки. */
    fun test(a: MailAccount): String? = try { withInbox(a) { it.messageCount }; null } catch (e: MailException) { e.message }

    private fun fromOf(m: Message): String = try {
        (m.from?.firstOrNull() as? InternetAddress)?.let { (it.personal?.let { p -> "$p " } ?: "") + "<" + it.address + ">" } ?: "(неизвестно)"
    } catch (_: Exception) { "(неизвестно)" }

    fun list(a: MailAccount, limit: Int, unreadOnly: Boolean, query: String?): List<MailItem> = withInbox(a) { f ->
        val q = query?.trim().orEmpty()
        val msgs: Array<Message> = when {
            q.isNotEmpty() -> {
                val term: SearchTerm = OrTerm(arrayOf<SearchTerm>(SubjectTerm(q), FromStringTerm(q), BodyTerm(q)))
                f.search(term)
            }
            unreadOnly -> f.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
            else -> { val n = f.messageCount; if (n == 0) emptyArray<Message>() else f.getMessages(maxOf(1, n - limit + 1), n) }
        }
        val last = msgs.takeLast(limit).toTypedArray()
        if (last.isNotEmpty()) {
            val fp = FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID) }
            f.fetch(last, fp)
        }
        last.map { m ->
            MailItem(
                uid = f.getUID(m), from = fromOf(m), subject = m.subject ?: "(без темы)",
                date = (m.receivedDate ?: m.sentDate)?.time ?: 0L, unread = !m.isSet(Flags.Flag.SEEN), snippet = null,
            )
        }.sortedByDescending { it.date }
    }

    fun read(a: MailAccount, uid: Long, fmtDate: (Long) -> String): String = withInbox(a) { f ->
        val m = f.getMessageByUID(uid) ?: throw MailException("Письмо #$uid не найдено.")
        val from = fromOf(m)
        if (MailRules.isBankSender(from)) return@withInbox "Письмо от банка скрыто: банковские данные агенту не показываются."
        val text = (textOf(m) ?: "(у письма нет текстовой части)").trim()
        "От: $from\nТема: ${m.subject ?: "(без темы)"}\nДата: ${fmtDate((m.receivedDate ?: m.sentDate)?.time ?: 0L)}\n\n" + text.take(5000)
    }

    private fun textOf(p: Part): String? {
        if (p.isMimeType("text/plain")) return p.content as? String
        if (p.isMimeType("text/html")) return (p.content as? String)?.let { WebText.htmlToText(it, 6000) }
        if (p.isMimeType("multipart/*")) {
            val mp = p.content as? Multipart ?: return null
            var fallback: String? = null
            for (i in 0 until mp.count) {
                val bp = mp.getBodyPart(i)
                if (Part.ATTACHMENT.equals(bp.disposition, ignoreCase = true)) continue
                if (bp.isMimeType("text/plain")) return textOf(bp)
                val t = textOf(bp)
                if (fallback == null && t != null) fallback = t
            }
            return fallback
        }
        return null
    }

    fun send(a: MailAccount, to: String, subject: String, body: String) {
        val props = Properties().apply {
            put("mail.transport.protocol", "smtp"); put("mail.smtp.host", a.server.smtpHost); put("mail.smtp.port", a.server.smtpPort.toString())
            put("mail.smtp.auth", "true"); put("mail.smtp.connectiontimeout", "15000"); put("mail.smtp.timeout", "30000"); put("mail.smtp.writetimeout", "30000")
            if (a.server.smtpStartTls) put("mail.smtp.starttls.enable", "true") else put("mail.smtp.ssl.enable", "true")
        }
        val session = Session.getInstance(props)
        try {
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(a.user))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false).take(1).toTypedArray())
                setSubject(subject, "UTF-8"); setText(body, "UTF-8"); sentDate = Date()
            }
            val t = session.getTransport("smtp")
            try {
                t.connect(a.server.smtpHost, a.server.smtpPort, a.user, a.pass)
                t.sendMessage(msg, msg.allRecipients)
            } finally { try { t.close() } catch (_: Exception) { } }
        } catch (e: Exception) {
            fail(e)
        }
    }
}
