package dev.pact.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** An event from one of the phone's own calendars (Google, Exchange, …); read-only. */
data class PhoneEvent(
    val id: Long,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val color: Int,
    val calendar: String,
    val location: String,
)

object PhoneCalendar {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Instances touching [from]..[to]; empty without permission. */
    fun load(context: Context, from: LocalDate, to: LocalDate): List<PhoneEvent> {
        if (!hasPermission(context)) return emptyList()
        val zone = ZoneId.systemDefault()
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = mutableListOf<PhoneEvent>()
        runCatching {
            context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(4) != 0
                    // All-day instances are stored at UTC midnight.
                    val z = if (allDay) ZoneOffset.UTC else zone
                    val s = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), z)
                    var e = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(3)), z)
                    if (allDay) e = e.minusDays(1) // exclusive end
                    if (s.toLocalDate().isAfter(to) || e.toLocalDate().isBefore(from)) continue
                    out += PhoneEvent(c.getLong(0), c.getString(1) ?: "(no title)", s, if (e.isBefore(s)) s else e, allDay, c.getInt(5), c.getString(6) ?: "", c.getString(7) ?: "")
                }
            }
        }
        return out
    }

    fun openIntent(id: Long): Intent =
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
