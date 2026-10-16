package com.marquin.calendario

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CalendarWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private fun getGoogleCalendarReminderMinutes(context: Context, eventId: Long): Int? {
        val projection = arrayOf(CalendarContract.Reminders.MINUTES)
        val selection = "${CalendarContract.Reminders.EVENT_ID} = ?"
        val cursor = context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            projection,
            selection,
            arrayOf(eventId.toString()),
            null
        )
        var maxMinutes: Int? = null
        cursor?.use {
            val minIndex = it.getColumnIndex(CalendarContract.Reminders.MINUTES)
            while (it.moveToNext()) {
                if (minIndex != -1) {
                    val mins = it.getInt(minIndex)
                    if (mins >= 0 && (maxMinutes == null || mins > maxMinutes!!)) {
                        maxMinutes = mins
                    }
                }
            }
        }
        return maxMinutes
    }

    override suspend fun doWork(): Result {
        Log.d("CalendarWorker", "Iniciando checagem fantasma em segundo plano...")

        val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()

        // Puxamos todos os calendários do dispositivo e identificamos feriados
        val cursorCalendars = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME
            ),
            null,
            null,
            null
        )

        val enabledCalendars = mutableListOf<Long>()
        val holidayCalendarIds = mutableSetOf<Long>()

        cursorCalendars?.use {
            val idIndex = it.getColumnIndex(CalendarContract.Calendars._ID)
            val nameIndex = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accountIndex = it.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)

            while (it.moveToNext()) {
                val calId = it.getLong(idIndex)
                val name = it.getString(nameIndex) ?: ""
                val account = it.getString(accountIndex) ?: ""

                if (name.contains("Feriado", ignoreCase = true) || name.contains("Holiday", ignoreCase = true) || account.contains("holiday", ignoreCase = true)) {
                    holidayCalendarIds.add(calId)
                }

                val isEnabled = sharedPrefs.getBoolean("calendar_$calId", true)
                if (isEnabled) {
                    enabledCalendars.add(calId)
                }
            }
        }

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.CALENDAR_ID
        )
        val eventsSelection = "(${CalendarContract.Events.DELETED} IS NULL OR ${CalendarContract.Events.DELETED} = 0)"
        val cursorEvents = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            eventsSelection,
            null,
            "${CalendarContract.Events.DTSTART} ASC"
        )

        cursorEvents?.use {
            val idIndex = it.getColumnIndex(CalendarContract.Events._ID)
            val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
            val startIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val calendarIdIndex = it.getColumnIndex(CalendarContract.Events.CALENDAR_ID)

            while (it.moveToNext()) {
                val dbEventId = it.getLong(idIndex)
                val calendarId = it.getLong(calendarIdIndex)
                val title = it.getString(titleIndex) ?: "Sem título"
                val startMillis = it.getLong(startIndex)

                val userReminderOverride = sharedPrefs.getString("event_reminder_type_$dbEventId", null)
                val alarmTriggerMillis = if (userReminderOverride != null) {
                    when (userReminderOverride) {
                        "5_min_before" -> startMillis - (5 * 60 * 1000L)
                        "15_min_before" -> startMillis - (15 * 60 * 1000L)
                        "30_min_before" -> startMillis - (30 * 60 * 1000L)
                        "1_hour_before" -> startMillis - (60 * 60 * 1000L)
                        "1_day_before" -> startMillis - (24 * 60 * 60 * 1000L)
                        else -> startMillis
                    }
                } else {
                    val googleMins = getGoogleCalendarReminderMinutes(context, dbEventId)
                    if (googleMins != null && googleMins > 0) {
                        startMillis - (googleMins * 60 * 1000L)
                    } else {
                        startMillis
                    }
                }

                val alarmId = (title + startMillis).hashCode()
                val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                    putExtra("EVENT_TITLE", title)
                    putExtra("EVENT_ID", dbEventId)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    alarmId,
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val isHoliday = holidayCalendarIds.contains(calendarId)

                if (enabledCalendars.contains(calendarId) && !isHoliday) {
                    if (alarmTriggerMillis > now) {
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                if (alarmManager.canScheduleExactAlarms()) {
                                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTriggerMillis, pendingIntent)
                                } else {
                                    alarmManager.set(AlarmManager.RTC_WAKEUP, alarmTriggerMillis, pendingIntent)
                                }
                            } else {
                                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTriggerMillis, pendingIntent)
                            }
                        } catch (e: SecurityException) {
                            alarmManager.set(AlarmManager.RTC_WAKEUP, alarmTriggerMillis, pendingIntent)
                        }
                    }
                } else {
                    alarmManager.cancel(pendingIntent)
                }
            }
        }

        Log.d("CalendarWorker", "Sincronização concluída com sucesso.")
        return Result.success()
    }
}