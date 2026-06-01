package com.marquin.calendario

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.*
import java.util.*
import java.util.concurrent.TimeUnit

/* -------------------- DATA CLASSES -------------------- */
data class CalendarEvent(val title: String, val startTime: Long, val formattedDate: String, val calendarId: Long)
data class CalendarSource(val id: Long, val name: String, val account: String, var enabled: Boolean = true)

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
    return events.groupBy { event ->
        Instant.ofEpochMilli(event.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}

private fun isToday(date: LocalDate?): Boolean = date == LocalDate.now()

/* -------------------- ACTIVITY -------------------- */
class MainActivity : ComponentActivity() {

    private val calendarPermissionCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCalendarPermission()
        startCalendarSyncWorker()

        setContent {
            var events by remember { mutableStateOf(listOf<CalendarEvent>()) }
            var calendarSources by remember { mutableStateOf(listOf<CalendarSource>()) }
            val currentSources by rememberUpdatedState(calendarSources)
            val coroutineScope = rememberCoroutineScope()
            val lifecycleOwner = LocalLifecycleOwner.current

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        coroutineScope.launch(Dispatchers.IO) {
                            val newDbSources = getCalendarSources()
                            val mergedSources = newDbSources.map { newSrc ->
                                val oldSrc = currentSources.find { it.id == newSrc.id }
                                newSrc.copy(enabled = oldSrc?.enabled ?: true)
                            }
                            val activeIds = mergedSources.filter { it.enabled }.map { it.id }
                            val updatedEvents = getCalendarEvents(activeIds)

                            withContext(Dispatchers.Main) {
                                calendarSources = mergedSources
                                events = updatedEvents
                            }
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            MaterialTheme {
                MainAppScreen(
                    events = events,
                    calendarSources = calendarSources,
                    onSourcesChanged = { updated ->
                        calendarSources = updated
                        val sharedPrefs = getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit().apply {
                            updated.forEach { source -> putBoolean("calendar_${source.id}", source.enabled) }
                            apply()
                        }
                        coroutineScope.launch(Dispatchers.IO) {
                            val newEvents = getCalendarEvents(updated.filter { it.enabled }.map { it.id })
                            withContext(Dispatchers.Main) { events = newEvents }
                        }
                    }
                )
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missingPermissions = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), calendarPermissionCode)
        }
    }

    private fun getCalendarEvents(enabledCalendars: List<Long>): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val projection = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.CALENDAR_ID)
        val cursor = contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, null, null, "${CalendarContract.Events.DTSTART} ASC")
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val now = System.currentTimeMillis()

        cursor?.use {
            val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
            val startIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val calendarIdIndex = it.getColumnIndex(CalendarContract.Events.CALENDAR_ID)
            val format = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            while (it.moveToNext()) {
                val calendarId = it.getLong(calendarIdIndex)
                val title = it.getString(titleIndex) ?: "Sem título"
                val startMillis = it.getLong(startIndex)

                val eventId = (title + startMillis).hashCode()
                val alarmIntent = Intent(this, AlarmReceiver::class.java).apply { putExtra("EVENT_TITLE", title) }
                val pendingIntent = android.app.PendingIntent.getBroadcast(this, eventId, alarmIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

                if (enabledCalendars.contains(calendarId)) {
                    events.add(CalendarEvent(title, startMillis, format.format(Date(startMillis)), calendarId))
                    if (startMillis > now) {
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                if (alarmManager.canScheduleExactAlarms()) alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, startMillis, pendingIntent)
                                else alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, startMillis, pendingIntent)
                            } else {
                                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, startMillis, pendingIntent)
                            }
                        } catch (e: SecurityException) {
                            alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, startMillis, pendingIntent)
                        }
                    }
                } else {
                    alarmManager.cancel(pendingIntent)
                }
            }
        }
        return events
    }

    private fun getCalendarSources(): List<CalendarSource> {
        val sources = mutableListOf<CalendarSource>()
        val sharedPrefs = getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
        val selection = "${CalendarContract.Calendars.IS_PRIMARY} = ?"
        val cursor = contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME), selection, arrayOf("1"), null)

        cursor?.use {
            val idIndex = it.getColumnIndex(CalendarContract.Calendars._ID)
            val accountIndex = it.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            val nameIndex = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)

            while (it.moveToNext()) {
                val id = it.getLong(idIndex)
                sources.add(CalendarSource(id, it.getString(accountIndex) ?: "", it.getString(nameIndex) ?: "", sharedPrefs.getBoolean("calendar_$id", true)))
            }
        }
        return sources
    }
}

/* -------------------- UI COMPONENTS -------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    events: List<CalendarEvent>,
    calendarSources: List<CalendarSource>,
    onSourcesChanged: (List<CalendarSource>) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf("Home") }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Spacer(modifier = Modifier.height(24.dp))
                Text("Menu", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium)
                Divider()

                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Calendário") },
                    selected = currentScreen == "Home",
                    onClick = { currentScreen = "Home"; scope.launch { drawerState.close() } },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Configurações") },
                    selected = currentScreen == "Settings",
                    onClick = { currentScreen = "Settings"; scope.launch { drawerState.close() } },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                Divider(modifier = Modifier.padding(vertical = 16.dp))
                Text("Contas Sincronizadas", modifier = Modifier.padding(start = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall)

                LazyColumn {
                    items(calendarSources) { source ->
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = source.enabled,
                                onCheckedChange = { checked ->
                                    val updated = calendarSources.map { if (it.id == source.id) it.copy(enabled = checked) else it }
                                    onSourcesChanged(updated)
                                }
                            )
                            Column {
                                Text(source.name, style = MaterialTheme.typography.bodyMedium)
                                Text(source.account, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
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
                    title = { Text(if (currentScreen == "Home") "Meu Calendário" else "Configurações") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu Lateral")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                when (currentScreen) {
                    "Home" -> MonthView(events)
                    "Settings" -> SettingsScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthView(events: List<CalendarEvent>) {
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }
    val grouped = remember(events) { groupEventsByDay(events) }
    val days = remember(currentMonth) { getDaysOfMonth(currentMonth) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val sheetState = rememberModalBottomSheetState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) { Icon(Icons.Filled.KeyboardArrowLeft, null) }
            Text(currentMonth.month.name.lowercase().replaceFirstChar { it.uppercase() } + " ${currentMonth.year}", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) { Icon(Icons.Filled.KeyboardArrowRight, null) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb").forEach {
                Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(days.chunked(7)) { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        Card(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clickable { if (day != null) selectedDate = day }) {
                            if (day != null) {
                                val dayEvents = grouped[day] ?: emptyList()
                                Column(modifier = Modifier.padding(4.dp)) {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        if (isToday(day)) {
                                            Box(modifier = Modifier.size(26.dp).clip(CircleShape).background(Color(0xFF1976D2)), contentAlignment = Alignment.Center) {
                                                Text("${day.dayOfMonth}", color = Color.White)
                                            }
                                        } else {
                                            Text("${day.dayOfMonth}")
                                        }
                                    }
                                    if (dayEvents.isNotEmpty()) {
                                        Text("${dayEvents.size}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            } else Box(modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    if (selectedDate != null) {
        val selectedEvents = grouped[selectedDate] ?: emptyList()
        ModalBottomSheet(onDismissRequest = { selectedDate = null }, sheetState = sheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Eventos do dia", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                if (selectedEvents.isEmpty()) Text("Nenhum evento")
                else selectedEvents.forEach { event ->
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(event.title, style = MaterialTheme.typography.titleMedium)
                            Text(event.formattedDate)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
    var savedUriString by remember { mutableStateOf(sharedPrefs.getString("custom_alarm_uri", null)) }

    // Lançador que abre o gerenciador de arquivos do celular pedindo um arquivo de áudio
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            // Garante que o app vai poder ler esse arquivo para sempre, mesmo depois de fechar
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            // Salva na memória
            sharedPrefs.edit().putString("custom_alarm_uri", it.toString()).apply()
            savedUriString = it.toString()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Áudio do Alarme", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (savedUriString != null) "Toque personalizado ativado!" else "Toque padrão do sistema",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }) {
            Text("Escolher arquivo .mp3")
        }

        if (savedUriString != null) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = {
                sharedPrefs.edit().remove("custom_alarm_uri").apply()
                savedUriString = null
            }) {
                Text("Restaurar toque padrão", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}