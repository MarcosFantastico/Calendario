package com.marquin.calendario

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.Date

class CalendarWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("CalendarWorker", "Iniciando checagem fantasma em segundo plano...")

        val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()

        // Puxamos todos os calendários primários do dispositivo
        val selection = "${CalendarContract.Calendars.IS_PRIMARY} = ?"
        val selectionArgs = arrayOf("1")
        val cursorCalendars = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            selection,
            selectionArgs,
            null
        )

        val enabledCalendars = mutableListOf<Long>()
        cursorCalendars?.use {
            val idIndex = it.getColumnIndex(CalendarContract.Calendars._ID)
            while (it.moveToNext()) {
                val calId = it.getLong(idIndex)
                // Se não houver registro salvo, consideramos ativo (true) por padrão
                val isEnabled = sharedPrefs.getBoolean("calendar_$calId", true)
                if (isEnabled) {
                    enabledCalendars.add(calId)
                }
            }
        }

        // Puxamos os eventos
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.CALENDAR_ID
        )
        val cursorEvents = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Events.DTSTART} ASC"
        )

        cursorEvents?.use {
            val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
            val startIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val calendarIdIndex = it.getColumnIndex(CalendarContract.Events.CALENDAR_ID)

            while (it.moveToNext()) {
                val calendarId = it.getLong(calendarIdIndex)
                val title = it.getString(titleIndex) ?: "Sem título"
                val startMillis = it.getLong(startIndex)

                val eventId = (title + startMillis).hashCode()
                val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                    putExtra("EVENT_TITLE", title)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    eventId,
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                if (enabledCalendars.contains(calendarId)) {
                    // Se o evento for no futuro, garante o agendamento do alarme
                    if (startMillis > now) {
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                if (alarmManager.canScheduleExactAlarms()) {
                                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMillis, pendingIntent)
                                } else {
                                    alarmManager.set(AlarmManager.RTC_WAKEUP, startMillis, pendingIntent)
                                }
                            } else {
                                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMillis, pendingIntent)
                            }
                        } catch (e: SecurityException) {
                            alarmManager.set(AlarmManager.RTC_WAKEUP, startMillis, pendingIntent)
                        }
                    }
                } else {
                    // Cancela se o calendário correspondente estiver desativado
                    alarmManager.cancel(pendingIntent)
                }
            }
        }

        Log.d("CalendarWorker", "Sincronização concluída com sucesso.")
        return Result.success()
    }
}