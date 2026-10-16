package com.marquin.calendario

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.marquin.calendario.ui.theme.CalendarioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/* -------------------- DATA CLASSES -------------------- */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val formattedDate: String,
    val calendarId: Long,
    val localDate: LocalDate
)
data class CalendarSource(
    val id: Long,
    val name: String,
    val account: String,
    var enabled: Boolean = true,
    val isHoliday: Boolean = false
)

data class SavedSound(
    val id: String,
    val name: String,
    val uriString: String,
    val category: String = "Toques de Alarme"
)

/* -------------------- CORES POR CONTA -------------------- */
private val accountColors = listOf(
    Color(0xFFFFB300), // Amarelo Flash
    Color(0xFF2196F3), // Azul
    Color(0xFF4CAF50), // Verde (Feriados / Contas)
    Color(0xFFE91E63), // Rosa
    Color(0xFF9C27B0), // Roxo
    Color(0xFFFF5722), // Laranja
    Color(0xFF00BCD4), // Ciano
    Color(0xFF8BC34A)  // Lime
)

private fun getSourceColor(sourceId: Long, sources: List<CalendarSource>): Color {
    val index = sources.indexOfFirst { it.id == sourceId }
    if (index == -1) return Color(0xFFFFB300)
    return accountColors[index % accountColors.size]
}

/* -------------------- DATA HELPERS -------------------- */
private fun getDaysOfMonth(yearMonth: YearMonth): List<LocalDate?> {
    val firstDayOfMonth = LocalDate.of(yearMonth.year, yearMonth.month, 1)
    val daysInMonth = yearMonth.lengthOfMonth()
    val firstDayWeek = firstDayOfMonth.dayOfWeek.value % 7
    val days = mutableListOf<LocalDate?>()

    repeat(firstDayWeek) { days.add(null) }
    repeat(daysInMonth) { days.add(firstDayOfMonth.plusDays(it.toLong())) }
    while (days.size % 7 != 0) { days.add(null) }
    return days
}

private fun groupEventsByDay(events: List<CalendarEvent>): Map<LocalDate, List<CalendarEvent>> {
    return events.groupBy { it.localDate }
}

private fun isToday(date: LocalDate?): Boolean = date == LocalDate.now()

fun getBuiltInSounds(context: Context): List<SavedSound> {
    val pkg = context.packageName
    return listOf(
        SavedSound("builtin_pixel", "Google Pixel Ringtone (Padrão Alarme)", "android.resource://$pkg/${R.raw.google_pixel_ringtone}", "Toques de Alarme"),
        SavedSound("builtin_setsound", "setSound (Padrão Notificação)", "android.resource://$pkg/${R.raw.setsound}", "Sons de Notificação"),
        SavedSound("builtin_iphone1", "iPhone 1", "android.resource://$pkg/${R.raw.iphone_1}", "Sons de Notificação"),
        SavedSound("builtin_iphone2", "iPhone 2", "android.resource://$pkg/${R.raw.iphone_2}", "Sons de Notificação")
    )
}

fun getSavedSounds(context: Context): List<SavedSound> {
    val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
    val jsonString = sharedPrefs.getString("saved_sounds_json", null) ?: return emptyList()
    val list = mutableListOf<SavedSound>()
    try {
        val array = org.json.JSONArray(jsonString)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val rawCategory = if (obj.has("category")) obj.getString("category") else "Toques de Alarme"
            val category = if (rawCategory.contains("Personalizado") || rawCategory.isBlank()) "Toques de Alarme" else rawCategory
            list.add(SavedSound(
                id = obj.getString("id"),
                name = obj.getString("name"),
                uriString = obj.getString("uri"),
                category = category
            ))
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}

fun saveSavedSounds(context: Context, sounds: List<SavedSound>) {
    val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
    val array = org.json.JSONArray()
    sounds.forEach { sound ->
        val obj = org.json.JSONObject().apply {
            put("id", sound.id)
            put("name", sound.name)
            put("uri", sound.uriString)
            put("category", sound.category)
        }
        array.put(obj)
    }
    sharedPrefs.edit().putString("saved_sounds_json", array.toString()).apply()
}

fun getFileNameFromUri(context: Context, uri: Uri): String {
    var fileName = "Áudio Personalizado"
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                fileName = it.getString(nameIndex) ?: fileName
            }
        }
    }
    return fileName
}

fun getGoogleCalendarReminderMinutes(context: Context, eventId: Long): Int? {
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

fun getGoogleCalendarReminderType(context: Context, eventId: Long): String {
    val mins = getGoogleCalendarReminderMinutes(context, eventId)
    return if (mins != null && mins > 0) {
        when {
            mins in 1..5 -> "5_min_before"
            mins in 6..15 -> "15_min_before"
            mins in 16..30 -> "30_min_before"
            mins in 31..120 -> "1_hour_before"
            mins >= 1440 -> "1_day_before"
            else -> "at_time"
        }
    } else "at_time"
}

// ------ FUNÇÕES DE BANCO DE DADOS NATIVO DO CALENDÁRIO ------

private fun saveEventToAndroidCalendar(
    context: Context,
    calendarId: Long,
    title: String,
    date: LocalDate,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    eventSoundUri: String? = null,
    eventRepeatCount: Int = -1,
    reminderType: String = "at_time",
    isFullScreen: Boolean = true
) {
    val startCal = Calendar.getInstance().apply {
        set(date.year, date.monthValue - 1, date.dayOfMonth, startHour, startMinute, 0)
    }
    val endCal = Calendar.getInstance().apply {
        set(date.year, date.monthValue - 1, date.dayOfMonth, endHour, endMinute, 0)
    }

    val startMillis = startCal.timeInMillis
    var endMillis = endCal.timeInMillis
    if (endMillis <= startMillis) {
        endMillis = startMillis + (60 * 60 * 1000)
    }

    val values = ContentValues().apply {
        put(CalendarContract.Events.DTSTART, startMillis)
        put(CalendarContract.Events.DTEND, endMillis)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
    }

    try {
        val newUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        newUri?.let { uri ->
            val eventId = ContentUris.parseId(uri)
            val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
            sharedPrefs.edit().apply {
                if (eventSoundUri != null) putString("event_ringtone_$eventId", eventSoundUri)
                putInt("event_repeats_$eventId", eventRepeatCount)
                putString("event_reminder_type_$eventId", reminderType)
                putBoolean("event_fullscreen_$eventId", isFullScreen)
                apply()
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun updateEventInAndroidCalendar(
    context: Context,
    eventId: Long,
    title: String,
    date: LocalDate,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    eventSoundUri: String? = null,
    eventRepeatCount: Int = -1,
    reminderType: String = "at_time",
    isFullScreen: Boolean = true
) {
    val startCal = Calendar.getInstance().apply {
        set(date.year, date.monthValue - 1, date.dayOfMonth, startHour, startMinute, 0)
    }
    val endCal = Calendar.getInstance().apply {
        set(date.year, date.monthValue - 1, date.dayOfMonth, endHour, endMinute, 0)
    }

    val startMillis = startCal.timeInMillis
    var endMillis = endCal.timeInMillis
    if (endMillis <= startMillis) {
        endMillis = startMillis + (60 * 60 * 1000)
    }

    val values = ContentValues().apply {
        put(CalendarContract.Events.DTSTART, startMillis)
        put(CalendarContract.Events.DTEND, endMillis)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
    }

    val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
    try {
        context.contentResolver.update(updateUri, values, null, null)
        val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().apply {
            if (eventSoundUri != null) putString("event_ringtone_$eventId", eventSoundUri)
            else remove("event_ringtone_$eventId")
            putInt("event_repeats_$eventId", eventRepeatCount)
            putString("event_reminder_type_$eventId", reminderType)
            putBoolean("event_fullscreen_$eventId", isFullScreen)
            apply()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun deleteEventFromAndroidCalendar(context: Context, eventId: Long) {
    val deleteUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
    try {
        context.contentResolver.delete(deleteUri, null, null)
        val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().apply {
            remove("event_ringtone_$eventId")
            remove("event_repeats_$eventId")
            remove("event_reminder_type_$eventId")
            remove("event_fullscreen_$eventId")
            apply()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/* -------------------- ACTIVITY -------------------- */
class MainActivity : ComponentActivity() {

    private val calendarPermissionCode = 100

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCalendarPermission()
        requestExactAlarmPermission()
        startCalendarSyncWorker()

        setContent {
            val sharedPrefs = getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
            var themeMode by remember { mutableStateOf(sharedPrefs.getString("app_theme", "system") ?: "system") }
            var isLoading by remember { mutableStateOf(true) }
            var loadingProgress by remember { mutableFloatStateOf(0f) }
            var events by remember { mutableStateOf(listOf<CalendarEvent>()) }
            var calendarSources by remember { mutableStateOf(listOf<CalendarSource>()) }
            var refreshTrigger by remember { mutableIntStateOf(0) }
            val deletedEventIds = remember { mutableSetOf<Long>() }

            val currentSources by rememberUpdatedState(calendarSources)
            val coroutineScope = rememberCoroutineScope()
            val lifecycleOwner = LocalLifecycleOwner.current

            val isDarkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            val refreshEventsData: (sourcesOverride: List<CalendarSource>?) -> Unit = { sourcesOverride ->
                coroutineScope.launch(Dispatchers.IO) {
                    val sourcesToUse = sourcesOverride ?: currentSources
                    val activeIds = sourcesToUse.filter { it.enabled }.map { it.id }
                    val updatedEvents = getCalendarEvents(activeIds, deletedEventIds)
                    withContext(Dispatchers.Main) {
                        events = updatedEvents
                        refreshTrigger++
                    }
                }
            }

            val deleteEventLocallyAndFromDb: (Long) -> Unit = { eventId ->
                deletedEventIds.add(eventId)
                events = events.filterNot { it.id == eventId }
                coroutineScope.launch(Dispatchers.IO) {
                    deleteEventFromAndroidCalendar(applicationContext, eventId)
                    val activeIds = currentSources.filter { it.enabled }.map { it.id }
                    val updatedEvents = getCalendarEvents(activeIds, deletedEventIds)
                    withContext(Dispatchers.Main) {
                        events = updatedEvents
                        refreshTrigger++
                    }
                }
            }

            // Carregamento com progresso suave de 0 a 100%
            LaunchedEffect(Unit) {
                coroutineScope.launch(Dispatchers.IO) {
                    val sources = getCalendarSources()
                    val activeIds = sources.filter { it.enabled }.map { it.id }
                    val loadedEvents = getCalendarEvents(activeIds, deletedEventIds)

                    withContext(Dispatchers.Main) {
                        calendarSources = sources
                        events = loadedEvents
                    }
                }

                val totalSteps = 50
                for (step in 1..totalSteps) {
                    delay(25)
                    loadingProgress = step / totalSteps.toFloat()
                }

                delay(100)
                isLoading = false
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME && !isLoading) {
                        coroutineScope.launch(Dispatchers.IO) {
                            val newDbSources = getCalendarSources()
                            val mergedSources = newDbSources.map { newSrc ->
                                val oldSrc = currentSources.find { it.id == newSrc.id }
                                newSrc.copy(enabled = oldSrc?.enabled ?: true)
                            }
                            val activeIds = mergedSources.filter { it.enabled }.map { it.id }
                            val updatedEvents = getCalendarEvents(activeIds, deletedEventIds)

                            withContext(Dispatchers.Main) {
                                calendarSources = mergedSources
                                events = updatedEvents
                                refreshTrigger++
                            }
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            CalendarioTheme(darkTheme = isDarkTheme) {
                Crossfade(
                    targetState = isLoading,
                    animationSpec = tween(500),
                    label = "SplashTransition"
                ) { loading ->
                    if (loading) {
                        LandingSplashScreen(progress = loadingProgress)
                    } else {
                        MainAppScreen(
                            events = events,
                            calendarSources = calendarSources,
                            refreshTrigger = refreshTrigger,
                            themeMode = themeMode,
                            onThemeModeChanged = { newTheme ->
                                themeMode = newTheme
                                sharedPrefs.edit().putString("app_theme", newTheme).apply()
                            },
                            onSourcesChanged = { updated ->
                                calendarSources = updated
                                sharedPrefs.edit().apply {
                                    updated.forEach { source -> putBoolean("calendar_${source.id}", source.enabled) }
                                    apply()
                                }
                                refreshEventsData(updated)
                            },
                            onDataChanged = { refreshEventsData(null) },
                            onDeleteEvent = deleteEventLocallyAndFromDb
                        )
                    }
                }
            }
        }
    }

    private fun startCalendarSyncWorker() {
        val syncWorkRequest = PeriodicWorkRequestBuilder<CalendarWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "SincronizarCalendarios", ExistingPeriodicWorkPolicy.KEEP, syncWorkRequest
        )
    }

    private fun requestCalendarPermission() {
        val permissions = mutableListOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missingPermissions = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), calendarPermissionCode)
        }
    }

    private fun getCalendarEvents(enabledCalendars: List<Long>, deletedIds: Set<Long> = emptySet()): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val sharedPrefs = getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.DELETED,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE
        )
        val selection = "(${CalendarContract.Events.DELETED} IS NULL OR ${CalendarContract.Events.DELETED} = 0)"
        val cursor = contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, selection, null, "${CalendarContract.Events.DTSTART} ASC")
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val now = System.currentTimeMillis()

        // Puxamos IDs de calendários de Feriados Nacionais e Religiosos para desativar alarmes por padrão
        val holidayCalendarIds = getCalendarSources().filter { it.isHoliday }.map { it.id }.toSet()

        cursor?.use {
            val idIndex = it.getColumnIndex(CalendarContract.Events._ID)
            val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
            val startIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIndex = it.getColumnIndex(CalendarContract.Events.DTEND)
            val calendarIdIndex = it.getColumnIndex(CalendarContract.Events.CALENDAR_ID)
            val deletedIndex = it.getColumnIndex(CalendarContract.Events.DELETED)
            val allDayIndex = it.getColumnIndex(CalendarContract.Events.ALL_DAY)
            val tzIndex = it.getColumnIndex(CalendarContract.Events.EVENT_TIMEZONE)

            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

            while (it.moveToNext()) {
                val eventId = it.getLong(idIndex)
                if (deletedIds.contains(eventId)) continue
                if (deletedIndex != -1 && it.getInt(deletedIndex) == 1) continue

                val calendarId = it.getLong(calendarIdIndex)
                val title = it.getString(titleIndex) ?: "Sem título"
                val startMillis = it.getLong(startIndex)
                var endMillis = if (endIndex != -1 && !it.isNull(endIndex)) it.getLong(endIndex) else (startMillis + 60 * 60 * 1000)
                if (endMillis <= startMillis) {
                    endMillis = startMillis + (60 * 60 * 1000)
                }

                val isAllDay = (allDayIndex != -1 && it.getInt(allDayIndex) == 1)
                val eventTz = if (tzIndex != -1) it.getString(tzIndex) else null
                val isHolidayCalendar = holidayCalendarIds.contains(calendarId)

                // Correção precisa de fuso horário UTC para evitar que Feriados recuem 1 dia!
                val isUtcDate = isAllDay || isHolidayCalendar || eventTz == "UTC"
                val localDate = if (isUtcDate) {
                    Instant.ofEpochMilli(startMillis).atZone(ZoneId.of("UTC")).toLocalDate()
                } else {
                    Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                }

                val formattedDate = "${dateFormat.format(Date(startMillis))} • ${timeFormat.format(Date(startMillis))} às ${timeFormat.format(Date(endMillis))}"

                // Puxa preferência do usuário ou os minutos exatos de lembrete do Google Agenda
                val userReminderOverride = sharedPrefs.getString("event_reminder_type_$eventId", null)
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
                    val googleMins = getGoogleCalendarReminderMinutes(this, eventId)
                    if (googleMins != null && googleMins > 0) {
                        startMillis - (googleMins * 60 * 1000L)
                    } else {
                        startMillis
                    }
                }

                val alarmId = (title + startMillis).hashCode()
                val alarmIntent = Intent(this, AlarmReceiver::class.java).apply {
                    putExtra("EVENT_TITLE", title)
                    putExtra("EVENT_ID", eventId)
                }
                val pendingIntent = android.app.PendingIntent.getBroadcast(this, alarmId, alarmIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

                if (enabledCalendars.contains(calendarId)) {
                    events.add(CalendarEvent(eventId, title, startMillis, endMillis, formattedDate, calendarId, localDate))
                    // FERIADOS NACIONAIS / RELIGIOSOS NÃO DISPARAM ALARME POR PADRÃO
                    if (!isHolidayCalendar && alarmTriggerMillis > now) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                if (alarmManager.canScheduleExactAlarms()) alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, alarmTriggerMillis, pendingIntent)
                                else alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, alarmTriggerMillis, pendingIntent)
                            } else {
                                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, alarmTriggerMillis, pendingIntent)
                            }
                        } catch (e: SecurityException) {
                            alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, alarmTriggerMillis, pendingIntent)
                        }
                    }
                } else {
                    alarmManager.cancel(pendingIntent)
                }
            }
        }

        // Deduplicação: Evita que eventos duplicados no mesmo dia apareçam (ex: "Dia do Servidor Público")
        val map = mutableMapOf<String, CalendarEvent>()
        for (event in events) {
            val key = "${event.title.trim().lowercase()}_${event.localDate}"
            val existing = map[key]
            if (existing == null) {
                map[key] = event
            } else if (holidayCalendarIds.contains(event.calendarId) && !holidayCalendarIds.contains(existing.calendarId)) {
                map[key] = event
            }
        }
        return map.values.sortedBy { it.startTime }
    }

    private fun getCalendarSources(): List<CalendarSource> {
        val sources = mutableListOf<CalendarSource>()
        val sharedPrefs = getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)

        val cursor = contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.IS_PRIMARY
            ),
            null,
            null,
            null
        )

        var nationalHolidayAdded = false
        var religiousHolidayAdded = false

        cursor?.use {
            val idIndex = it.getColumnIndex(CalendarContract.Calendars._ID)
            val accountIndex = it.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            val nameIndex = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val isPrimaryIndex = it.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)

            while (it.moveToNext()) {
                val id = it.getLong(idIndex)
                val account = it.getString(accountIndex) ?: "Sem conta"
                val name = it.getString(nameIndex) ?: "Calendário"
                val isPrimary = if (isPrimaryIndex != -1) it.getInt(isPrimaryIndex) == 1 else false

                if (isPrimary) {
                    if (sources.none { s -> s.id == id }) {
                        sources.add(CalendarSource(id, name, account, sharedPrefs.getBoolean("calendar_$id", true), isHoliday = false))
                    }
                } else if (!religiousHolidayAdded && (name.contains("cristã", ignoreCase = true) || name.contains("religio", ignoreCase = true) || name.contains("christian", ignoreCase = true))) {
                    if (sources.none { s -> s.id == id }) {
                        sources.add(CalendarSource(id, "Feriados Religiosos", "Feriados Cristãos (Google)", sharedPrefs.getBoolean("calendar_$id", true), isHoliday = true))
                        religiousHolidayAdded = true
                    }
                } else if (!nationalHolidayAdded && (name.contains("Feriado", ignoreCase = true) || name.contains("Holiday", ignoreCase = true) || account.contains("holiday", ignoreCase = true))) {
                    if (sources.none { s -> s.id == id }) {
                        sources.add(CalendarSource(id, "Feriados Nacionais", "Feriados no Brasil (Google)", sharedPrefs.getBoolean("calendar_$id", true), isHoliday = true))
                        nationalHolidayAdded = true
                    }
                }
            }
        }

        // ORDENAÇÃO: 1. Feriados Nacionais, 2. Feriados Religiosos, 3. Contas Principais
        return sources.sortedWith(compareBy { source ->
            when {
                source.name.contains("Feriados Nacionais", ignoreCase = true) || source.name.contains("Feriados no Brasil", ignoreCase = true) -> 0
                source.name.contains("Feriados Religiosos", ignoreCase = true) || source.name.contains("Feriados Cristãos", ignoreCase = true) -> 1
                source.isHoliday -> 2
                else -> 3
            }
        })
    }
}

/* -------------------- LANDING SPLASH SCREEN -------------------- */

@Composable
fun LandingSplashScreen(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        // Marca d'água sutil no fundo
        Image(
            painter = painterResource(id = R.drawable.ic_flash_calendar),
            contentDescription = null,
            alpha = 0.06f,
            modifier = Modifier
                .size(340.dp)
                .align(Alignment.Center)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // Ícone Principal com Elevação
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 12.dp,
                modifier = Modifier.size(110.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_flash_calendar),
                        contentDescription = "FlashCalendar Logo",
                        modifier = Modifier.size(80.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Título Oficial: FlashCalendar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Flash",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic,
                        fontSize = 36.sp
                    ),
                    color = Color(0xFFFFB300)
                )
                Text(
                    text = "Calendar",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                        fontSize = 36.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Todos seus calendários em um!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Barra de Progresso com porcentagem (0% - 100%)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(220.dp)
            ) {
                LinearProgressIndicator(
                    progress = progress,
                    color = Color(0xFFFFB300),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFFFFB300)
                )
            }
        }
    }
}

/* -------------------- UI COMPONENTS -------------------- */

@Composable
fun CreateEventDialog(
    selectedDate: LocalDate,
    calendarSources: List<CalendarSource>,
    onDismiss: () -> Unit,
    onSave: (title: String, calendarId: Long, eventDate: LocalDate, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, soundUri: String?, repeatCount: Int, reminderType: String, isFullScreen: Boolean) -> Unit
) {
    val context = LocalContext.current
    var eventTitleInput by remember { mutableStateOf("") }
    var eventDate by remember { mutableStateOf(selectedDate) }
    val calendar = Calendar.getInstance()

    var startHour by remember { mutableStateOf(calendar.get(Calendar.HOUR_OF_DAY)) }
    var startMinute by remember { mutableStateOf(calendar.get(Calendar.MINUTE)) }
    var endHour by remember { mutableStateOf((calendar.get(Calendar.HOUR_OF_DAY) + 1) % 24) }
    var endMinute by remember { mutableStateOf(calendar.get(Calendar.MINUTE)) }

    val builtInSounds = remember { getBuiltInSounds(context) }
    val userSavedSounds = remember { mutableStateListOf<SavedSound>().apply { addAll(getSavedSounds(context)) } }
    val allAvailableSounds = remember(userSavedSounds) { builtInSounds + userSavedSounds }

    var selectedSoundUri by remember { mutableStateOf<String?>(null) }
    var expandedSoundDropdown by remember { mutableStateOf(false) }

    var selectedReminderType by remember { mutableStateOf("at_time") }
    var expandedReminderDropdown by remember { mutableStateOf(false) }

    var isFullScreenAlarm by remember { mutableStateOf(true) }

    var repeatInputText by remember { mutableStateOf("1") }
    var isLooping by remember { mutableStateOf(false) }

    var showImportTypeDialog by remember { mutableStateOf(false) }

    val audioPickerForAlarm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            val name = getFileNameFromUri(context, it)
            val currentSaved = getSavedSounds(context).toMutableList()
            if (currentSaved.none { sound -> sound.uriString == uri.toString() || sound.name == name }) {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val newSound = SavedSound(id = System.currentTimeMillis().toString(), name = name, uriString = uri.toString(), category = "Toques de Alarme")
                currentSaved.add(newSound)
                saveSavedSounds(context, currentSaved)
                userSavedSounds.clear()
                userSavedSounds.addAll(currentSaved)
                selectedSoundUri = uri.toString()
            } else {
                selectedSoundUri = uri.toString()
            }
        }
    }

    val audioPickerForNotification = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            val name = getFileNameFromUri(context, it)
            val currentSaved = getSavedSounds(context).toMutableList()
            if (currentSaved.none { sound -> sound.uriString == uri.toString() || sound.name == name }) {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val newSound = SavedSound(id = System.currentTimeMillis().toString(), name = name, uriString = uri.toString(), category = "Sons de Notificação")
                currentSaved.add(newSound)
                saveSavedSounds(context, currentSaved)
                userSavedSounds.clear()
                userSavedSounds.addAll(currentSaved)
                selectedSoundUri = uri.toString()
            } else {
                selectedSoundUri = uri.toString()
            }
        }
    }

    if (showImportTypeDialog) {
        AlertDialog(
            onDismissRequest = { showImportTypeDialog = false },
            title = { Text("Importar Áudio") },
            text = { Text("Como deseja categorizar este arquivo de áudio na biblioteca de sons?") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF121212)),
                    onClick = {
                        showImportTypeDialog = false
                        audioPickerForAlarm.launch(arrayOf("audio/*"))
                    }
                ) { Text("Toque de Alarme", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showImportTypeDialog = false
                        audioPickerForNotification.launch(arrayOf("audio/*"))
                    }
                ) { Text("Som de Notificação") }
            }
        )
    }

    // Exclui calendários de Feriados para criação de eventos!
    val enabledSources = remember(calendarSources) { calendarSources.filter { it.enabled && !it.isHoliday } }
    var selectedCalendar by remember(enabledSources) {
        mutableStateOf(enabledSources.firstOrNull() ?: calendarSources.firstOrNull { !it.isHoliday })
    }
    var expandedDropdown by remember { mutableStateOf(false) }

    val dateTitleFormatter = remember { DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy", Locale("pt", "BR")) }

    val computedRepeatCount = remember(repeatInputText, isLooping) {
        if (isLooping) -1
        else {
            val parsed = repeatInputText.toIntOrNull()
            if (parsed != null && parsed > 0) parsed else 1
        }
    }

    val reminderOptions = listOf(
        "at_time" to "⚡ No horário do evento",
        "5_min_before" to "⏱ 5 minutos antes",
        "15_min_before" to "⏱ 15 minutos antes",
        "30_min_before" to "⏱ 30 minutos antes",
        "1_hour_before" to "⏰ 1 hora antes",
        "1_day_before" to "📅 1 dia antes",
        "hourly_on_day" to "🔄 De hora em hora no dia"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo Evento") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = eventTitleInput,
                        onValueChange = { eventTitleInput = it },
                        label = { Text("Título do Evento") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Text("Data do Evento:", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            android.app.DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    eventDate = LocalDate.of(year, month + 1, dayOfMonth)
                                },
                                eventDate.year,
                                eventDate.monthValue - 1,
                                eventDate.dayOfMonth
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(eventDate.format(dateTitleFormatter), fontWeight = FontWeight.Bold)
                    }
                }

                item {
                    Text("Conta Principal:", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expandedDropdown = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = selectedCalendar?.let {
                                    if (it.account.isNotBlank() && it.account != it.name) "${it.account} (${it.name})" else it.account.ifBlank { it.name }
                                } ?: "Nenhuma conta disponível",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = expandedDropdown,
                            onDismissRequest = { expandedDropdown = false }
                        ) {
                            enabledSources.forEach { source ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (source.account.isNotBlank() && source.account != source.name)
                                                "${source.account} (${source.name})"
                                            else source.account.ifBlank { source.name }
                                        )
                                    },
                                    onClick = {
                                        selectedCalendar = source
                                        expandedDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Início:", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    android.app.TimePickerDialog(
                                        context,
                                        { _, hour, minute ->
                                            startHour = hour
                                            startMinute = minute
                                            if (endHour < startHour || (endHour == startHour && endMinute <= startMinute)) {
                                                endHour = (startHour + 1) % 24
                                                endMinute = startMinute
                                            }
                                        },
                                        startHour, startMinute, true
                                    ).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(String.format("%02d:%02d", startHour, startMinute))
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Término:", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    android.app.TimePickerDialog(
                                        context,
                                        { _, hour, minute ->
                                            endHour = hour
                                            endMinute = minute
                                        },
                                        endHour, endMinute, true
                                    ).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(String.format("%02d:%02d", endHour, endMinute))
                            }
                        }
                    }
                }

                item {
                    Text("Lembrete / Alerta Antecipado:", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val currentReminderText = reminderOptions.find { it.first == selectedReminderType }?.second ?: "No horário"
                        OutlinedButton(
                            onClick = { expandedReminderDropdown = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(currentReminderText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        DropdownMenu(
                            expanded = expandedReminderDropdown,
                            onDismissRequest = { expandedReminderDropdown = false }
                        ) {
                            reminderOptions.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, fontWeight = if (selectedReminderType == key) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = {
                                        selectedReminderType = key
                                        expandedReminderDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isFullScreenAlarm = !isFullScreenAlarm }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = isFullScreenAlarm,
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFFB300), checkmarkColor = Color(0xFF121212)),
                            onCheckedChange = { isFullScreenAlarm = it }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("Alertar em tela inteira (Alarme)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Text("Se desmarcado, alerta via pop-up de notificação", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }

                item {
                    Text("Som do Alerta (Toque):", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val currentSoundName = remember(selectedSoundUri, allAvailableSounds) {
                            if (selectedSoundUri == null) "Google Pixel Ringtone (Padrão)"
                            else allAvailableSounds.find { it.uriString == selectedSoundUri }?.name ?: "Toque Selecionado"
                        }

                        OutlinedButton(
                            onClick = { expandedSoundDropdown = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(currentSoundName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        DropdownMenu(
                            expanded = expandedSoundDropdown,
                            onDismissRequest = { expandedSoundDropdown = false }
                        ) {
                            allAvailableSounds.forEach { sound ->
                                DropdownMenuItem(
                                    text = { Text("${sound.category}: ${sound.name}", fontWeight = if (selectedSoundUri == sound.uriString) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = {
                                        selectedSoundUri = sound.uriString
                                        expandedSoundDropdown = false
                                    }
                                )
                            }
                            Divider()
                            DropdownMenuItem(
                                text = { Text("+ Escolher arquivo .mp3 do celular", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                                onClick = {
                                    expandedSoundDropdown = false
                                    showImportTypeDialog = true
                                }
                            )
                        }
                    }
                }

                item {
                    Text("Repetição do Toque (Vezes):", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedIconButton(
                            onClick = {
                                if (!isLooping) {
                                    val current = repeatInputText.toIntOrNull() ?: 1
                                    if (current > 1) {
                                        repeatInputText = (current - 1).toString()
                                    }
                                }
                            },
                            enabled = !isLooping && (repeatInputText.toIntOrNull() ?: 1) > 1,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Text("-", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                        }

                        OutlinedTextField(
                            value = if (isLooping) "" else repeatInputText,
                            onValueChange = { input ->
                                if (input.all { it.isDigit() }) {
                                    repeatInputText = input
                                    isLooping = false
                                }
                            },
                            enabled = !isLooping,
                            placeholder = { Text("1") },
                            label = { Text("Vezes") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedIconButton(
                            onClick = {
                                if (isLooping) {
                                    isLooping = false
                                    repeatInputText = "2"
                                } else {
                                    val current = repeatInputText.toIntOrNull() ?: 1
                                    repeatInputText = (current + 1).toString()
                                }
                            },
                            enabled = !isLooping,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Text("+", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                        }

                        FilterChip(
                            selected = isLooping,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFFB300),
                                selectedLabelColor = Color(0xFF121212)
                            ),
                            onClick = {
                                isLooping = !isLooping
                                if (!isLooping && repeatInputText.isBlank()) repeatInputText = "1"
                            },
                            label = { Text("Loop", fontWeight = if (isLooping) FontWeight.Bold else FontWeight.Normal) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = eventTitleInput.isNotBlank() && selectedCalendar != null,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF121212)),
                onClick = {
                    selectedCalendar?.let { cal ->
                        onSave(eventTitleInput, cal.id, eventDate, startHour, startMinute, endHour, endMinute, selectedSoundUri, computedRepeatCount, selectedReminderType, isFullScreenAlarm)
                    }
                }
            ) { Text("Salvar", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    events: List<CalendarEvent>,
    calendarSources: List<CalendarSource>,
    refreshTrigger: Int = 0,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    onSourcesChanged: (List<CalendarSource>) -> Unit,
    onDataChanged: () -> Unit,
    onDeleteEvent: (Long) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val pagerState = rememberPagerState(initialPage = 0) { 3 }

    var currentMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed) {
            onDataChanged()
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val localDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                        currentMonth = YearMonth.of(localDate.year, localDate.month)
                        selectedDate = localDate
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancelar") } }
        ) { DatePicker(state = datePickerState) }
    }

    if (showCreateDialog) {
        CreateEventDialog(
            selectedDate = selectedDate ?: LocalDate.now(),
            calendarSources = calendarSources,
            onDismiss = { showCreateDialog = false },
            onSave = { title, calendarId, date, startHour, startMinute, endHour, endMinute, soundUri, repeatCount, reminderType, isFullScreen ->
                saveEventToAndroidCalendar(context, calendarId, title, date, startHour, startMinute, endHour, endMinute, soundUri, repeatCount, reminderType, isFullScreen)
                onDataChanged()
                showCreateDialog = false
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_flash_calendar),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Flash", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic), color = Color(0xFFFFB300))
                    Text("Calendar", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                }
                Divider()

                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.SettingsIcon, null) },
                    label = { Text("Configurações") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                Divider(modifier = Modifier.padding(vertical = 16.dp))
                Text("Contas Principais & Feriados", modifier = Modifier.padding(start = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall)

                LazyColumn {
                    items(calendarSources) { source ->
                        val color = getSourceColor(source.id, calendarSources)
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = source.enabled,
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFFB300), checkmarkColor = Color(0xFF121212)),
                                onCheckedChange = { checked ->
                                    val updated = calendarSources.map { if (it.id == source.id) it.copy(enabled = checked) else it }
                                    onSourcesChanged(updated)
                                }
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(color)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(source.account.ifBlank { source.name }, style = MaterialTheme.typography.bodyMedium)
                                if (source.name.isNotBlank() && source.name != source.account) {
                                    Text(source.name, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        when (pagerState.currentPage) {
                            0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_flash_calendar),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Flash", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic), color = Color(0xFFFFB300))
                                Text("Calendar", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                            }
                            1 -> Text("Resumo de Eventos", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                            2 -> Text("Alertas Recorrentes Diários", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, "Menu Lateral") }
                    },
                    actions = {
                        if (pagerState.currentPage == 0) {
                            IconButton(onClick = { onDataChanged() }) { Icon(Icons.Filled.Refresh, "Atualizar Calendário", tint = Color(0xFFFFB300)) }
                            TextButton(onClick = {
                                currentMonth = YearMonth.now()
                                selectedDate = LocalDate.now()
                            }) { Text("Hoje", style = MaterialTheme.typography.titleMedium, color = Color(0xFFFFB300)) }
                            IconButton(onClick = { showDatePicker = true }) { Icon(Icons.Filled.DateRange, "Filtrar por Data") }
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Home, contentDescription = "Calendário") },
                        label = { Text("Calendário") },
                        selected = pagerState.currentPage == 0,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF121212),
                            selectedTextColor = Color(0xFFFFB300),
                            indicatorColor = Color(0xFFFFB300)
                        ),
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.DateRange, contentDescription = "Resumo") },
                        label = { Text("Resumo") },
                        selected = pagerState.currentPage == 1,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF121212),
                            selectedTextColor = Color(0xFFFFB300),
                            indicatorColor = Color(0xFFFFB300)
                        ),
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Notifications, contentDescription = "Alertas") },
                        label = { Text("Alertas") },
                        selected = pagerState.currentPage == 2,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF121212),
                            selectedTextColor = Color(0xFFFFB300),
                            indicatorColor = Color(0xFFFFB300)
                        ),
                        onClick = { scope.launch { pagerState.animateScrollToPage(2) } }
                    )
                }
            },
            floatingActionButtonPosition = FabPosition.Center,
            floatingActionButton = {
                if (pagerState.currentPage == 0) {
                    FloatingActionButton(
                        onClick = { showCreateDialog = true },
                        containerColor = Color(0xFFFFB300),
                        contentColor = Color(0xFF121212),
                        shape = CircleShape,
                        modifier = Modifier.size(56.dp)
                    ) { Icon(Icons.Filled.Add, "Adicionar Evento", modifier = Modifier.size(28.dp)) }
                }
            }
        ) { paddingValues ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                userScrollEnabled = true
            ) { page ->
                when (page) {
                    0 -> MonthView(
                        events = events,
                        currentMonth = currentMonth,
                        onMonthChange = { currentMonth = it },
                        selectedDate = selectedDate,
                        onDateSelect = { selectedDate = it },
                        calendarSources = calendarSources,
                        onDataChanged = onDataChanged,
                        onDeleteEvent = onDeleteEvent
                    )
                    1 -> EventsSummaryScreenContent(
                        calendarSourcesOverride = calendarSources,
                        refreshTrigger = refreshTrigger
                    )
                    2 -> RecurringAlarmsScreenContent()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthView(
    events: List<CalendarEvent>,
    currentMonth: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    selectedDate: LocalDate?,
    onDateSelect: (LocalDate?) -> Unit,
    calendarSources: List<CalendarSource>,
    onDataChanged: () -> Unit,
    onDeleteEvent: (Long) -> Unit
) {
    val grouped = remember(events) { groupEventsByDay(events) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    var showEditDialog by remember { mutableStateOf(false) }
    var selectedEventToEdit by remember { mutableStateOf<CalendarEvent?>(null) }
    var editTitleInput by remember { mutableStateOf("") }

    var editStartHour by remember { mutableStateOf(9) }
    var editStartMinute by remember { mutableStateOf(0) }
    var editEndHour by remember { mutableStateOf(10) }
    var editEndMinute by remember { mutableStateOf(0) }

    var editSoundUri by remember { mutableStateOf<String?>(null) }
    var editRepeatCount by remember { mutableStateOf(-1) }
    var editRepeatInputText by remember { mutableStateOf("1") }
    var editIsLooping by remember { mutableStateOf(false) }
    var editReminderType by remember { mutableStateOf("at_time") }
    var editIsFullScreenAlarm by remember { mutableStateOf(true) }
    var expandedEditReminderDropdown by remember { mutableStateOf(false) }

    val builtInSounds = remember { getBuiltInSounds(context) }
    val userSavedSounds = remember { mutableStateListOf<SavedSound>().apply { addAll(getSavedSounds(context)) } }
    val allAvailableSounds = remember(userSavedSounds) { builtInSounds + userSavedSounds }
    var expandedEditSoundDropdown by remember { mutableStateOf(false) }

    var eventToDelete by remember { mutableStateOf<CalendarEvent?>(null) }
    var showCreateDialogFromSheet by remember { mutableStateOf(false) }

    var showEditImportTypeDialog by remember { mutableStateOf(false) }

    var offsetY by remember { mutableFloatStateOf(0f) }
    var isRefreshing by remember { mutableStateOf(false) }
    val refreshThreshold = 120f

    val editAudioPickerForAlarm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            val name = getFileNameFromUri(context, it)
            val currentSaved = getSavedSounds(context).toMutableList()
            if (currentSaved.none { sound -> sound.uriString == uri.toString() || sound.name == name }) {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val newSound = SavedSound(id = System.currentTimeMillis().toString(), name = name, uriString = uri.toString(), category = "Toques de Alarme")
                currentSaved.add(newSound)
                saveSavedSounds(context, currentSaved)
                userSavedSounds.clear()
                userSavedSounds.addAll(currentSaved)
                editSoundUri = uri.toString()
            } else {
                editSoundUri = uri.toString()
            }
        }
    }

    val editAudioPickerForNotification = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            val name = getFileNameFromUri(context, it)
            val currentSaved = getSavedSounds(context).toMutableList()
            if (currentSaved.none { sound -> sound.uriString == uri.toString() || sound.name == name }) {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val newSound = SavedSound(id = System.currentTimeMillis().toString(), name = name, uriString = uri.toString(), category = "Sons de Notificação")
                currentSaved.add(newSound)
                saveSavedSounds(context, currentSaved)
                userSavedSounds.clear()
                userSavedSounds.addAll(currentSaved)
                editSoundUri = uri.toString()
            } else {
                editSoundUri = uri.toString()
            }
        }
    }

    if (showEditImportTypeDialog) {
        AlertDialog(
            onDismissRequest = { showEditImportTypeDialog = false },
            title = { Text("Importar Áudio") },
            text = { Text("Como deseja categorizar este arquivo de áudio na biblioteca de sons?") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF121212)),
                    onClick = {
                        showEditImportTypeDialog = false
                        editAudioPickerForAlarm.launch(arrayOf("audio/*"))
                    }
                ) { Text("Toque de Alarme", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showEditImportTypeDialog = false
                        editAudioPickerForNotification.launch(arrayOf("audio/*"))
                    }
                ) { Text("Som de Notificação") }
            }
        )
    }

    val computedEditRepeatCount = remember(editRepeatInputText, editIsLooping) {
        if (editIsLooping) -1
        else {
            val parsed = editRepeatInputText.toIntOrNull()
            if (parsed != null && parsed > 0) parsed else 1
        }
    }

    val reminderOptions = listOf(
        "at_time" to "⚡ No horário do evento",
        "5_min_before" to "⏱ 5 minutos antes",
        "15_min_before" to "⏱ 15 minutos antes",
        "30_min_before" to "⏱ 30 minutos antes",
        "1_hour_before" to "⏰ 1 hora antes",
        "1_day_before" to "📅 1 dia antes",
        "hourly_on_day" to "🔄 De hora em hora no dia"
    )

    // Formata o nome do mês em Português do Brasil (ex: "Fevereiro de 2026")
    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale("pt", "BR")) }
    val formattedMonth = remember(currentMonth) { currentMonth.format(monthFormatter).replaceFirstChar { it.uppercase() } }

    // Diálogo de confirmação de exclusão
    if (eventToDelete != null) {
        AlertDialog(
            onDismissRequest = { eventToDelete = null },
            title = { Text("Excluir Evento") },
            text = { Text("Tem certeza que deseja excluir o evento \"${eventToDelete?.title}\"? Esta ação não pode ser desfeita.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        eventToDelete?.let { ev -> onDeleteEvent(ev.id) }
                        eventToDelete = null
                    }
                ) {
                    Text("Excluir", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { eventToDelete = null }) { Text("Cancelar") }
            }
        )
    }

    if (showEditDialog && selectedEventToEdit != null) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Editar Evento") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        OutlinedTextField(
                            value = editTitleInput,
                            onValueChange = { editTitleInput = it },
                            label = { Text("Novo Título") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Início:", style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedButton(
                                    onClick = {
                                        android.app.TimePickerDialog(
                                            context,
                                            { _, hour, minute -> editStartHour = hour; editStartMinute = minute },
                                            editStartHour, editStartMinute, true
                                        ).show()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(String.format("%02d:%02d", editStartHour, editStartMinute)) }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text("Término:", style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedButton(
                                    onClick = {
                                        android.app.TimePickerDialog(
                                            context,
                                            { _, hour, minute -> editEndHour = hour; editEndMinute = minute },
                                            editEndHour, editEndMinute, true
                                        ).show()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(String.format("%02d:%02d", editEndHour, editEndMinute)) }
                            }
                        }
                    }

                    item {
                        Text("Lembrete / Alerta Antecipado:", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(modifier = Modifier.fillMaxWidth()) {
                            val currentReminderText = reminderOptions.find { it.first == editReminderType }?.second ?: "No horário"
                            OutlinedButton(
                                onClick = { expandedEditReminderDropdown = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(currentReminderText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            DropdownMenu(
                                expanded = expandedEditReminderDropdown,
                                onDismissRequest = { expandedEditReminderDropdown = false }
                            ) {
                                reminderOptions.forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, fontWeight = if (editReminderType == key) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = {
                                            editReminderType = key
                                            expandedEditReminderDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editIsFullScreenAlarm = !editIsFullScreenAlarm }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = editIsFullScreenAlarm,
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFFB300), checkmarkColor = Color(0xFF121212)),
                                onCheckedChange = { editIsFullScreenAlarm = it }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text("Alertar em tela inteira (Alarme)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                Text("Se desmarcado, alerta via pop-up de notificação", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }
                    }

                    item {
                        Text("Som do Alerta (Toque):", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(modifier = Modifier.fillMaxWidth()) {
                            val currentSoundName = remember(editSoundUri, allAvailableSounds) {
                                if (editSoundUri == null) "Google Pixel Ringtone (Padrão)"
                                else allAvailableSounds.find { it.uriString == editSoundUri }?.name ?: "Toque Selecionado"
                            }

                            OutlinedButton(
                                onClick = { expandedEditSoundDropdown = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(currentSoundName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            DropdownMenu(
                                expanded = expandedEditSoundDropdown,
                                onDismissRequest = { expandedEditSoundDropdown = false }
                            ) {
                                allAvailableSounds.forEach { sound ->
                                    DropdownMenuItem(
                                        text = { Text("${sound.category}: ${sound.name}", fontWeight = if (editSoundUri == sound.uriString) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = {
                                            editSoundUri = sound.uriString
                                            expandedEditSoundDropdown = false
                                        }
                                    )
                                }
                                Divider()
                                DropdownMenuItem(
                                    text = { Text("+ Escolher arquivo .mp3 do celular", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        expandedEditSoundDropdown = false
                                        showEditImportTypeDialog = true
                                    }
                                )
                            }
                        }
                    }

                    item {
                        Text("Repetição do Toque (Vezes):", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedIconButton(
                                onClick = {
                                    if (!editIsLooping) {
                                        val current = editRepeatInputText.toIntOrNull() ?: 1
                                        if (current > 1) {
                                            editRepeatInputText = (current - 1).toString()
                                        }
                                    }
                                },
                                enabled = !editIsLooping && (editRepeatInputText.toIntOrNull() ?: 1) > 1,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Text("-", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                            }

                            OutlinedTextField(
                                value = if (editIsLooping) "" else editRepeatInputText,
                                onValueChange = { input ->
                                    if (input.all { it.isDigit() }) {
                                        editRepeatInputText = input
                                        editIsLooping = false
                                    }
                                },
                                enabled = !editIsLooping,
                                placeholder = { Text("1") },
                                label = { Text("Vezes") },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )

                            OutlinedIconButton(
                                onClick = {
                                    if (editIsLooping) {
                                        editIsLooping = false
                                        editRepeatInputText = "2"
                                    } else {
                                        val current = editRepeatInputText.toIntOrNull() ?: 1
                                        editRepeatInputText = (current + 1).toString()
                                    }
                                },
                                enabled = !editIsLooping,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Text("+", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                            }

                            FilterChip(
                                selected = editIsLooping,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFFFB300),
                                    selectedLabelColor = Color(0xFF121212)
                                ),
                                onClick = {
                                    editIsLooping = !editIsLooping
                                    if (!editIsLooping && editRepeatInputText.isBlank()) editRepeatInputText = "1"
                                },
                                label = { Text("Loop", fontWeight = if (editIsLooping) FontWeight.Bold else FontWeight.Normal) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF121212)),
                    onClick = {
                        selectedEventToEdit?.let { event ->
                            val date = Instant.ofEpochMilli(event.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
                            updateEventInAndroidCalendar(context, event.id, editTitleInput, date, editStartHour, editStartMinute, editEndHour, editEndMinute, editSoundUri, computedEditRepeatCount, editReminderType, editIsFullScreenAlarm)
                            onDataChanged()
                        }
                        showEditDialog = false
                        selectedEventToEdit = null
                    }
                ) { Text("Atualizar", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Cancelar") } }
        )
    }

    if (showCreateDialogFromSheet && selectedDate != null) {
        CreateEventDialog(
            selectedDate = selectedDate,
            calendarSources = calendarSources,
            onDismiss = { showCreateDialogFromSheet = false },
            onSave = { title, calendarId, date, startHour, startMinute, endHour, endMinute, soundUri, repeatCount, reminderType, isFullScreen ->
                saveEventToAndroidCalendar(context, calendarId, title, date, startHour, startMinute, endHour, endMinute, soundUri, repeatCount, reminderType, isFullScreen)
                onDataChanged()
                showCreateDialogFromSheet = false
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        if (dragAmount > 0 || offsetY > 0) {
                            offsetY = (offsetY + dragAmount * 0.4f).coerceIn(0f, 200f)
                        }
                    },
                    onDragEnd = {
                        if (offsetY >= refreshThreshold) {
                            isRefreshing = true
                            onDataChanged()
                            isRefreshing = false
                        }
                        offsetY = 0f
                    }
                )
            }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMonthChange(currentMonth.minusMonths(1)) }) { Icon(Icons.Filled.KeyboardArrowLeft, null) }
                Text(formattedMonth, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                IconButton(onClick = { onMonthChange(currentMonth.plusMonths(1)) }) { Icon(Icons.Filled.KeyboardArrowRight, null) }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb").forEach {
                    Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AnimatedContent(
                targetState = currentMonth,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    if (targetState.isAfter(initialState)) {
                        (slideInHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(400))) togetherWith
                                (slideOutHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(400)))
                    } else {
                        (slideInHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(400))) togetherWith
                                (slideOutHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(400)))
                    }
                },
                label = "MonthGridSlideTransition"
            ) { targetMonth ->
                val days = remember(targetMonth) { getDaysOfMonth(targetMonth) }

                val monthEvents = remember(events, targetMonth) {
                    events.filter { event ->
                        YearMonth.from(event.localDate) == targetMonth
                    }
                }

                val holidayCalendarIds = remember(calendarSources) {
                    calendarSources.filter { it.isHoliday }.map { it.id }.toSet()
                }

                val monthHolidaysCount = remember(monthEvents, holidayCalendarIds) {
                    monthEvents.count { holidayCalendarIds.contains(it.calendarId) }
                }
                val monthEventsCount = remember(monthEvents, monthHolidaysCount) {
                    monthEvents.size - monthHolidaysCount
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        days.chunked(7).forEach { week ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                week.forEach { day ->
                                    val isSelected = day != null && day == selectedDate
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(0.82f)
                                            .padding(2.dp)
                                            .clickable { if (day != null) onDateSelect(day) },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) Color(0xFFFFB300).copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        if (day != null) {
                                            val dayEvents = grouped[day] ?: emptyList()
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(2.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Box(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (isToday(day)) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(20.dp)
                                                                .clip(CircleShape)
                                                                .background(Color(0xFFFFB300)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text(
                                                                "${day.dayOfMonth}",
                                                                color = Color(0xFF121212),
                                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                                            )
                                                        }
                                                    } else {
                                                        Text(
                                                            "${day.dayOfMonth}",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                                                        )
                                                    }
                                                }

                                                if (dayEvents.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    val maxVisible = 2
                                                    Column(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalArrangement = Arrangement.spacedBy(1.dp)
                                                    ) {
                                                        dayEvents.take(maxVisible).forEach { event ->
                                                            val sourceColor = getSourceColor(event.calendarId, calendarSources)
                                                            Surface(
                                                                shape = RoundedCornerShape(2.dp),
                                                                color = sourceColor.copy(alpha = 0.25f),
                                                                modifier = Modifier.fillMaxWidth()
                                                            ) {
                                                                Text(
                                                                    text = event.title,
                                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 10.sp),
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    color = MaterialTheme.colorScheme.onSurface,
                                                                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                        }
                                                        if (dayEvents.size > maxVisible) {
                                                            Text(
                                                                text = "+${dayEvents.size - maxVisible} mais",
                                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                                                                color = Color(0xFFFFB300),
                                                                maxLines = 1,
                                                                modifier = Modifier.padding(start = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        } else Box(modifier = Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                    }

                    // ------ ESTATÍSTICAS E INFORMAÇÕES DO MÊS ------
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Eventos no mês: $monthEventsCount",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFFFFB300)
                            )
                            Text(
                                text = "Feriados no mês: $monthHolidaysCount",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // ------ LEGENDA DE CORES POR CONTA EM LINHA ------
                    if (calendarSources.any { it.enabled }) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            calendarSources.filter { it.enabled }.take(4).forEach { source ->
                                val color = getSourceColor(source.id, calendarSources)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = source.account.ifBlank { source.name },
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // INDICADOR DE REFRESH (SWIPE DOWN)
        if (offsetY > 0f || isRefreshing) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (offsetY * 0.3f).dp.coerceAtMost(50.dp))
                    .padding(top = 8.dp)
                    .size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color(0xFFFFB300),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Atualizar",
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    if (selectedDate != null) {
        val selectedEvents = grouped[selectedDate] ?: emptyList()
        val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)

        ModalBottomSheet(onDismissRequest = { onDateSelect(null) }, sheetState = sheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Eventos do dia", style = MaterialTheme.typography.titleLarge)
                    Button(
                        onClick = { showCreateDialogFromSheet = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF121212))
                    ) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Novo", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (selectedEvents.isEmpty()) {
                    Text("Nenhum evento agendado para este dia.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(selectedEvents) { event ->
                            val sourceColor = getSourceColor(event.calendarId, calendarSources)
                            val userOverride = sharedPrefs.getString("event_reminder_type_${event.id}", null)
                            val googleMins = if (userOverride == null) getGoogleCalendarReminderMinutes(context, event.id) else null
                            val reminderType = userOverride ?: when {
                                googleMins != null && googleMins > 0 -> "${googleMins}_min_before"
                                else -> "at_time"
                            }

                            val reminderText = when {
                                userOverride != null -> when (userOverride) {
                                    "5_min_before" -> " • 🔔 5 min antes"
                                    "15_min_before" -> " • 🔔 15 min antes"
                                    "30_min_before" -> " • 🔔 30 min antes"
                                    "1_hour_before" -> " • 🔔 1 hora antes"
                                    "1_day_before" -> " • 🔔 1 dia antes"
                                    "hourly_on_day" -> " • 🔄 De hora em hora"
                                    else -> ""
                                }
                                googleMins != null && googleMins > 0 -> " • 🔔 $googleMins min antes (Google)"
                                else -> ""
                            }

                            val isHolidayEvent = calendarSources.find { it.id == event.calendarId }?.isHoliday == true

                            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(event.title, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            text = event.formattedDate + reminderText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        val eventSource = calendarSources.find { it.id == event.calendarId }
                                        val accountDisplay = eventSource?.account?.ifBlank { eventSource.name } ?: ""
                                        if (accountDisplay.isNotBlank()) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(sourceColor)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(accountDisplay, style = MaterialTheme.typography.labelSmall, color = sourceColor)
                                            }
                                        }
                                    }
                                    Row {
                                        if (!isHolidayEvent) {
                                            IconButton(onClick = {
                                                selectedEventToEdit = event
                                                editTitleInput = event.title

                                                editSoundUri = sharedPrefs.getString("event_ringtone_${event.id}", null)
                                                editReminderType = sharedPrefs.getString("event_reminder_type_${event.id}", null) ?: getGoogleCalendarReminderType(context, event.id)
                                                editIsFullScreenAlarm = sharedPrefs.getBoolean("event_fullscreen_${event.id}", true)
                                                if (sharedPrefs.contains("event_repeats_${event.id}")) {
                                                    val repeats = sharedPrefs.getInt("event_repeats_${event.id}", 1)
                                                    if (repeats == -1) {
                                                        editIsLooping = true
                                                        editRepeatInputText = "1"
                                                    } else {
                                                        editIsLooping = false
                                                        editRepeatInputText = if (repeats > 0) repeats.toString() else "1"
                                                    }
                                                } else {
                                                    editIsLooping = false
                                                    editRepeatInputText = "1"
                                                }

                                                val startCal = Calendar.getInstance().apply { timeInMillis = event.startTime }
                                                editStartHour = startCal.get(Calendar.HOUR_OF_DAY)
                                                editStartMinute = startCal.get(Calendar.MINUTE)

                                                val endCal = Calendar.getInstance().apply { timeInMillis = event.endTime }
                                                editEndHour = endCal.get(Calendar.HOUR_OF_DAY)
                                                editEndMinute = endCal.get(Calendar.MINUTE)

                                                showEditDialog = true
                                            }) { Icon(Icons.Filled.Edit, "Editar evento", tint = Color(0xFFFFB300)) }

                                            IconButton(onClick = {
                                                eventToDelete = event
                                            }) { Icon(Icons.Filled.Delete, "Excluir evento", tint = MaterialTheme.colorScheme.error) }
                                        } else {
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                modifier = Modifier.padding(start = 8.dp)
                                            ) {
                                                Text(
                                                    text = "🔒 Oficial",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = Color.Gray,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun SettingsScreen(
    themeMode: String,
    onThemeModeChanged: (String) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)

    var savedUriString by remember { mutableStateOf(sharedPrefs.getString("custom_alarm_uri", null)) }
    var alarmVolume by remember { mutableStateOf(sharedPrefs.getFloat("alarm_volume", 1.0f)) }
    var alarmDuration by remember { mutableStateOf(sharedPrefs.getInt("alarm_duration", 5)) }

    val builtInSounds = remember { getBuiltInSounds(context) }
    val userSavedSounds = remember { mutableStateListOf<SavedSound>() }
    var playingPreviewId by remember { mutableStateOf<String?>(null) }
    var activeMediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Re-sincroniza com o SharedPreferences sempre que a tela de Configurações é exibida
    LaunchedEffect(Unit) {
        userSavedSounds.clear()
        userSavedSounds.addAll(getSavedSounds(context))
    }

    val multiAlarmAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        uris.forEach { uri ->
            try {
                val name = getFileNameFromUri(context, uri)
                val currentSaved = getSavedSounds(context).toMutableList()
                if (currentSaved.none { sound -> sound.uriString == uri.toString() || sound.name == name }) {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val newSound = SavedSound(
                        id = System.currentTimeMillis().toString() + "_" + uri.hashCode(),
                        name = name,
                        uriString = uri.toString(),
                        category = "Toques de Alarme"
                    )
                    currentSaved.add(newSound)
                    saveSavedSounds(context, currentSaved)
                    userSavedSounds.clear()
                    userSavedSounds.addAll(currentSaved)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val multiNotificationAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        uris.forEach { uri ->
            try {
                val name = getFileNameFromUri(context, uri)
                val currentSaved = getSavedSounds(context).toMutableList()
                if (currentSaved.none { sound -> sound.uriString == uri.toString() || sound.name == name }) {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val newSound = SavedSound(
                        id = System.currentTimeMillis().toString() + "_" + uri.hashCode(),
                        name = name,
                        uriString = uri.toString(),
                        category = "Sons de Notificação"
                    )
                    currentSaved.add(newSound)
                    saveSavedSounds(context, currentSaved)
                    userSavedSounds.clear()
                    userSavedSounds.addAll(currentSaved)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                activeMediaPlayer?.stop()
                activeMediaPlayer?.release()
            } catch (e: Exception) {}
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {

        // ------ 1. TEMA DO APLICATIVO ------
        item {
            Text("Tema do Aplicativo", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Escolha a aparência do aplicativo (Claro, Escuro ou Padrão do Sistema).", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val themeOptions = listOf("system" to "Sistema", "light" to "Claro", "dark" to "Escuro")
                themeOptions.forEach { (key, label) ->
                    FilterChip(
                        selected = themeMode == key,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFB300),
                            selectedLabelColor = Color(0xFF121212)
                        ),
                        onClick = { onThemeModeChanged(key) },
                        label = { Text(label, fontWeight = if (themeMode == key) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Divider()
        }

        // ------ 2. BIBLIOTECA UNIFICADA DE SONS & TOQUES ------
        item {
            Text("Biblioteca de Sons & Toques", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Dica: Para Notificações, escolha áudios curtos de até 5 segundos. Para áudios mais longos ou músicas, adicione em Toques de Alarme.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { multiAlarmAudioPicker.launch(arrayOf("audio/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF121212)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+ Toque de Alarme", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Button(
                    onClick = { multiNotificationAudioPicker.launch(arrayOf("audio/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF121212)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+ Som Notificação", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val soundCategories = listOf("Toques de Alarme", "Sons de Notificação")
            soundCategories.forEach { category ->
                val categoryBuiltIn = builtInSounds.filter { it.category == category }
                val categoryUser = userSavedSounds.filter { it.category == category }
                val allCategorySounds = categoryBuiltIn + categoryUser

                Text(category, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = Color(0xFFFFB300))
                Spacer(modifier = Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    allCategorySounds.forEach { sound ->
                        val isBuiltIn = sound.id.startsWith("builtin_")
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(sound.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val isPlaying = playingPreviewId == sound.id
                                    TextButton(onClick = {
                                        try {
                                            activeMediaPlayer?.stop()
                                            activeMediaPlayer?.release()
                                            activeMediaPlayer = null

                                            if (isPlaying) {
                                                playingPreviewId = null
                                            } else {
                                                playingPreviewId = sound.id
                                                val player = MediaPlayer.create(context, Uri.parse(sound.uriString))
                                                player?.apply {
                                                    setAudioAttributes(
                                                        AudioAttributes.Builder()
                                                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                                            .setUsage(AudioAttributes.USAGE_ALARM)
                                                            .build()
                                                    )
                                                    setVolume(alarmVolume, alarmVolume)
                                                    setOnCompletionListener { playingPreviewId = null }
                                                    start()
                                                }
                                                activeMediaPlayer = player
                                            }
                                        } catch (e: Exception) {
                                            playingPreviewId = null
                                        }
                                    }) {
                                        Text(if (isPlaying) "⏹ Parar" else "▶ Ouvir", color = Color(0xFFFFB300), fontWeight = FontWeight.Bold)
                                    }

                                    if (!isBuiltIn) {
                                        IconButton(onClick = {
                                            if (playingPreviewId == sound.id) {
                                                try { activeMediaPlayer?.stop(); activeMediaPlayer?.release() } catch (e: Exception) {}
                                                playingPreviewId = null
                                            }
                                            userSavedSounds.remove(sound)
                                            saveSavedSounds(context, userSavedSounds)
                                        }) {
                                            Icon(Icons.Filled.Delete, "Excluir", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider()
        }

        // ------ 3. VOLUME DO APLICATIVO ------
        item {
            Text("Volume do Aplicativo", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Depende do volume do alarme do celular. Solte o seletor para ouvir um preview sonoro.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("0%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = alarmVolume,
                    onValueChange = { novoVolume ->
                        alarmVolume = novoVolume
                        sharedPrefs.edit().putFloat("alarm_volume", novoVolume).apply()
                    },
                    onValueChangeFinished = {
                        try {
                            activeMediaPlayer?.stop()
                            activeMediaPlayer?.release()
                            activeMediaPlayer = null

                            val defaultUri = Uri.parse("android.resource://${context.packageName}/${R.raw.google_pixel_ringtone}")
                            val alarmUri = if (savedUriString != null) {
                                try { Uri.parse(savedUriString) } catch (e: Exception) { defaultUri }
                            } else defaultUri

                            val player = MediaPlayer.create(context, alarmUri) ?: MediaPlayer.create(context, defaultUri)
                            player?.apply {
                                setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .setUsage(AudioAttributes.USAGE_ALARM)
                                        .build()
                                )
                                setVolume(alarmVolume, alarmVolume)
                                start()
                            }
                            activeMediaPlayer = player

                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                try {
                                    activeMediaPlayer?.stop()
                                    activeMediaPlayer?.release()
                                    activeMediaPlayer = null
                                } catch (e: Exception) {}
                            }, 1800)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFFB300),
                        activeTrackColor = Color(0xFFFFB300)
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text("${(alarmVolume * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Divider()
        }

        // ------ 4. DURAÇÃO DO TOQUE ------
        item {
            Text("Duração da Notificação", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Desliga o alarme automaticamente se você não responder.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val options = listOf(1 to "1 min", 5 to "5 min", 15 to "15 min", -1 to "Infinito")
                options.forEach { (value, label) ->
                    FilterChip(
                        selected = alarmDuration == value,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFB300),
                            selectedLabelColor = Color(0xFF121212)
                        ),
                        onClick = {
                            alarmDuration = value
                            sharedPrefs.edit().putInt("alarm_duration", value).apply()
                        },
                        label = { Text(label, fontWeight = if (alarmDuration == value) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }
        }
    }
}