package com.rwai.agent.connectors

import android.Manifest
import android.app.SearchManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.CalendarContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.rwai.agent.notifications.RwNotificationListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Управление любым плеером через медиасессии Android. Нужен включённый «Доступ к уведомлениям». */
object MediaControl {
    private fun sessions(c: Context): List<MediaController>? = try {
        (c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).getActiveSessions(ComponentName(c, RwNotificationListener::class.java))
    } catch (_: SecurityException) { null } catch (_: Exception) { emptyList() }

    private fun label(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()

    private const val NEED_ACCESS = "Для управления плеером включите «Доступ к уведомлениям» (Ещё → Доступ)."

    fun status(c: Context): String {
        val list = sessions(c) ?: return NEED_ACCESS
        if (list.isEmpty()) return "Сейчас ничего не играет: активных плееров нет."
        return list.take(3).joinToString("\n") { s ->
            val md = s.metadata
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "(без названия)"
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            val st = when (s.playbackState?.state) {
                PlaybackState.STATE_PLAYING -> "играет"; PlaybackState.STATE_PAUSED -> "на паузе"; PlaybackState.STATE_BUFFERING -> "загружается"
                PlaybackState.STATE_STOPPED -> "остановлен"; else -> "неизвестно"
            }
            "${label(c, s.packageName)}: $title" + (artist?.let { " — $it" } ?: "") + " ($st)"
        }
    }

    /** null — выполнено; иначе текст ошибки. */
    fun control(c: Context, action: String): String? {
        val list = sessions(c) ?: return NEED_ACCESS
        val s = pick(list) ?: return "Нет активного плеера. Сначала включите музыку (play_music)."
        val t = s.transportControls
        when (action) {
            "play" -> t.play()
            "pause" -> t.pause()
            "next" -> t.skipToNext()
            "previous" -> t.skipToPrevious()
            "toggle" -> if (s.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            else -> return "Неизвестное действие."
        }
        return null
    }

    /** Intent «включить по запросу»: его принимают YouTube Music, Spotify и другие плееры. */
    fun playIntent(query: String, pkg: String?): Intent =
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
            if (pkg != null) setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

/** Календарь напрямую через системный провайдер (без открытия приложения). */
object CalendarClient {
    fun canRead(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun canWrite(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun events(c: Context, days: Int): String {
        val now = System.currentTimeMillis()
        val end = now + days * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, now); ContentUris.appendId(it, end) }.build()
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val day = SimpleDateFormat("dd.MM", Locale.getDefault())
        val out = ArrayList<String>()
        c.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
            null, null, CalendarContract.Instances.BEGIN + " ASC",
        )?.use { cur ->
            while (cur.moveToNext() && out.size < 40) {
                val allDay = cur.getInt(3) == 1
                val begin = cur.getLong(1)
                val when_ = if (allDay) day.format(Date(begin)) + " (весь день)" else fmt.format(Date(begin))
                val loc = cur.getString(4)?.takeIf { it.isNotBlank() }
                out += "$when_ · ${cur.getString(0) ?: "(без названия)"}" + (loc?.let { " · $it" } ?: "")
            }
        }
        return if (out.isEmpty()) "На ближайшие $days дн. событий нет." else out.joinToString("\n")
    }

    /** null — добавлено; иначе текст ошибки. */
    fun add(c: Context, title: String, startMs: Long, minutes: Int, notes: String?): String? {
        val calId = c.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null,
        )?.use { cur ->
            var first: Long? = null
            var primary: Long? = null
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                if (first == null) first = id
                if (cur.getInt(1) == 1 && primary == null) primary = id
            }
            primary ?: first
        } ?: return "В календаре нет доступного для записи календаря."
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, startMs + minutes * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (!notes.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, notes)
        }
        return if (c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) != null) null else "Календарь не принял событие."
    }
}
