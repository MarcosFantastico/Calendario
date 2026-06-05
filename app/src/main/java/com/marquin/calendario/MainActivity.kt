package com.marquin.calendario

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings as SettingsIcon
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
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/* -------------------- DATA CLASSES -------------------- */
// Agora a classe CalendarEvent armazena o ID oficial do banco de dados!
data class CalendarEvent(val id: Long, val title: String, val startTime: Long, val formattedDate: String, val calendarId: Long)
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

// ------ FUNÇÕES DE BANCO DE DADOS NATIVO DO CALENDÁRIO ------

private fun saveEventToAndroidCalendar(context: Context, calendarId: Long, title: String, date: LocalDate, hour: Int, minute: Int) {
    val calendar = Calendar.getInstance().apply {
        set(date.year, date.monthValue - 1, date.dayOfMonth, hour, minute, 0)
    }
    val startMillis = calendar.timeInMillis
    val endMillis = startMillis + (60 * 60 * 1000) // 1 hora de duração padrão

    val values = ContentValues().apply {
        put(CalendarContract.Events.DTSTART, startMillis)
        put(CalendarContract.Events.DTEND, endMillis)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id) // Obrigatório para o Google Sync!
    }

    try {
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun updateEventInAndroidCalendar(context: Context, eventId: Long, title: String, date: LocalDate, hour: Int, minute: Int) {
    val calendar = Calendar.getInstance().apply {
        set(date.year, date.monthValue - 1, date.dayOfMonth, hour, minute, 0)
    }
    val startMillis = calendar.timeInMillis
    val endMillis = startMillis + (60 * 60 * 1000)

    val values = ContentValues().apply {
        put(CalendarContract.Events.DTSTART, startMillis)
        put(CalendarContract.Events.DTEND, endMillis)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
    }

    val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
    try {
        context.contentResolver.update(updateUri, values, null, null)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun deleteEventFromAndroidCalendar(context: Context, eventId: Long) {
    val deleteUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
    try {
        context.contentResolver.delete(deleteUri, null, null)
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
            var events by remember { mutableStateOf(listOf<CalendarEvent>()) }
            var calendarSources by remember { mutableStateOf(listOf<CalendarSource>()) }
            val currentSources by rememberUpdatedState(calendarSources)
            val coroutineScope = rememberCoroutineScope()
            val lifecycleOwner = LocalLifecycleOwner.current

            // FUNÇÃO PARA ATUALIZAR A INTERFACE IMEDIATAMENTE APÓS QUALQUER MUDANÇA!
            val refreshEventsData: () -> Unit = {
                coroutineScope.launch(Dispatchers.IO) {
                    val activeIds = currentSources.filter { it.enabled }.map { it.id }
                    val updatedEvents = getCalendarEvents(activeIds)
                    withContext(Dispatchers.Main) { events = updatedEvents }
                }
            }

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
                        refreshEventsData()
                    },
                    onDataChanged = { refreshEventsData() } // Passamos o gatilho para atualizar tudo!
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missingPermissions = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), calendarPermissionCode)
        }
    }

    private fun getCalendarEvents(enabledCalendars: List<Long>): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val projection = arrayOf(
            CalendarContract.Events._ID, // Agora pegamos o ID oficial!
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.CALENDAR_ID
        )
        val cursor = contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, null, null, "${CalendarContract.Events.DTSTART} ASC")
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val now = System.currentTimeMillis()

        cursor?.use {
            val idIndex = it.getColumnIndex(CalendarContract.Events._ID)
            val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
            val startIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val calendarIdIndex = it.getColumnIndex(CalendarContract.Events.CALENDAR_ID)
            val format = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            while (it.moveToNext()) {
                val eventId = it.getLong(idIndex)
                val calendarId = it.getLong(calendarIdIndex)
                val title = it.getString(titleIndex) ?: "Sem título"
                val startMillis = it.getLong(startIndex)

                val alarmId = (title + startMillis).hashCode()
                val alarmIntent = Intent(this, AlarmReceiver::class.java).apply { putExtra("EVENT_TITLE", title) }
                val pendingIntent = android.app.PendingIntent.getBroadcast(this, alarmId, alarmIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

                if (enabledCalendars.contains(calendarId)) {
                    events.add(CalendarEvent(eventId, title, startMillis, format.format(Date(startMillis)), calendarId))
                    if (startMillis > now) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
    onSourcesChanged: (List<CalendarSource>) -> Unit,
    onDataChanged: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var currentScreen by remember { mutableStateOf("Home") }
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var eventTitleInput by remember { mutableStateOf("") }
    var eventHour by remember { mutableStateOf(9) }
    var eventMinute by remember { mutableStateOf(0) }

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
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Novo Evento para ${selectedDate ?: LocalDate.now()}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = eventTitleInput,
                        onValueChange = { eventTitleInput = it },
                        label = { Text("Título do Evento") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Botão para abrir o relógio nativo do Android
                    Text("Horário do Evento:", style = MaterialTheme.typography.labelMedium)
                    OutlinedButton(
                        onClick = {
                            android.app.TimePickerDialog(
                                context,
                                { _, hour, minute -> eventHour = hour; eventMinute = minute },
                                eventHour, eventMinute, true
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(String.format("%02d:%02d", eventHour, eventMinute))
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val primaryCalendar = calendarSources.firstOrNull { it.enabled }
                    if (primaryCalendar != null && eventTitleInput.isNotBlank()) {
                        saveEventToAndroidCalendar(context, primaryCalendar.id, eventTitleInput, selectedDate ?: LocalDate.now(), eventHour, eventMinute)
                        onDataChanged() // Gatilho de recarregar
                    }
                    eventTitleInput = ""
                    showCreateDialog = false
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancelar") } }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Spacer(modifier = Modifier.height(24.dp))
                Text("Menu", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium)
                Divider()

                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Home, null) },
                    label = { Text("Calendário") },
                    selected = currentScreen == "Home",
                    onClick = { scope.launch { drawerState.close(); currentScreen = "Home" } },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.SettingsIcon, null) },
                    label = { Text("Configurações") },
                    selected = currentScreen == "Settings",
                    onClick = { scope.launch { drawerState.close(); currentScreen = "Settings" } },
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
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, "Menu Lateral") }
                    },
                    actions = {
                        if (currentScreen == "Home") {
                            TextButton(onClick = {
                                currentMonth = YearMonth.now()
                                selectedDate = LocalDate.now()
                            }) { Text("Hoje", style = MaterialTheme.typography.titleMedium) }
                            IconButton(onClick = { showDatePicker = true }) { Icon(Icons.Filled.DateRange, "Filtrar por Data") }
                        }
                    }
                )
            },
            floatingActionButton = {
                if (currentScreen == "Home") {
                    FloatingActionButton(
                        onClick = {
                            val primaryCalendar = calendarSources.firstOrNull { it.enabled }
                            if (primaryCalendar != null) {
                                eventHour = 9 // Reseta a hora padrão
                                eventMinute = 0
                                showCreateDialog = true
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) { Icon(Icons.Filled.Add, "Adicionar Evento") }
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        (slideInHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(400))) togetherWith
                                (slideOutHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(400)))
                    },
                    label = "ScreenTransition"
                ) { screen ->
                    when (screen) {
                        "Home" -> MonthView(
                            events = events,
                            currentMonth = currentMonth,
                            onMonthChange = { currentMonth = it },
                            selectedDate = selectedDate,
                            onDateSelect = { selectedDate = it },
                            calendarSources = calendarSources,
                            onDataChanged = onDataChanged
                        )
                        "Settings" -> SettingsScreen()
                    }
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
    onDataChanged: () -> Unit
) {
    val grouped = remember(events) { groupEventsByDay(events) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    var showEditDialog by remember { mutableStateOf(false) }
    var selectedEventToEdit by remember { mutableStateOf<CalendarEvent?>(null) }
    var editTitleInput by remember { mutableStateOf("") }
    var editHour by remember { mutableStateOf(9) }
    var editMinute by remember { mutableStateOf(0) }

    var showCreateDialogFromSheet by remember { mutableStateOf(false) }
    var eventTitleInputFromSheet by remember { mutableStateOf("") }
    var createHour by remember { mutableStateOf(9) }
    var createMinute by remember { mutableStateOf(0) }

    if (showEditDialog && selectedEventToEdit != null) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Editar Evento") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editTitleInput,
                        onValueChange = { editTitleInput = it },
                        label = { Text("Novo Título") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Horário do Evento:", style = MaterialTheme.typography.labelMedium)
                    OutlinedButton(
                        onClick = {
                            android.app.TimePickerDialog(
                                context,
                                { _, hour, minute -> editHour = hour; editMinute = minute },
                                editHour, editMinute, true
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(String.format("%02d:%02d", editHour, editMinute)) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    selectedEventToEdit?.let { event ->
                        val date = Instant.ofEpochMilli(event.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
                        updateEventInAndroidCalendar(context, event.id, editTitleInput, date, editHour, editMinute)
                        onDataChanged() // Atualiza!
                    }
                    showEditDialog = false
                    selectedEventToEdit = null
                }) { Text("Atualizar") }
            },
            dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Cancelar") } }
        )
    }

    if (showCreateDialogFromSheet) {
        AlertDialog(
            onDismissRequest = { showCreateDialogFromSheet = false },
            title = { Text("Novo Evento") },
            text = {
                Column {
                    OutlinedTextField(
                        value = eventTitleInputFromSheet,
                        onValueChange = { eventTitleInputFromSheet = it },
                        label = { Text("Título") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Horário do Evento:", style = MaterialTheme.typography.labelMedium)
                    OutlinedButton(
                        onClick = {
                            android.app.TimePickerDialog(
                                context,
                                { _, hour, minute -> createHour = hour; createMinute = minute },
                                createHour, createMinute, true
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(String.format("%02d:%02d", createHour, createMinute)) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val primaryCalendar = calendarSources.firstOrNull { it.enabled }
                    if (primaryCalendar != null && eventTitleInputFromSheet.isNotBlank() && selectedDate != null) {
                        saveEventToAndroidCalendar(context, primaryCalendar.id, eventTitleInputFromSheet, selectedDate, createHour, createMinute)
                        onDataChanged() // Atualiza!
                    }
                    eventTitleInputFromSheet = ""
                    showCreateDialogFromSheet = false
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialogFromSheet = false }) { Text("Cancelar") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonthChange(currentMonth.minusMonths(1)) }) { Icon(Icons.Filled.KeyboardArrowLeft, null) }
            Text(currentMonth.month.name.lowercase().replaceFirstChar { it.uppercase() } + " ${currentMonth.year}", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { onMonthChange(currentMonth.plusMonths(1)) }) { Icon(Icons.Filled.KeyboardArrowRight, null) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb").forEach {
                Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        AnimatedContent(
            targetState = currentMonth,
            modifier = Modifier.weight(1f),
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
            LazyColumn {
                items(days.chunked(7)) { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            val isSelected = day != null && day == selectedDate
                            Card(
                                modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clickable { if (day != null) onDateSelect(day) },
                                colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                if (day != null) {
                                    val dayEvents = grouped[day] ?: emptyList()
                                    Column(modifier = Modifier.padding(4.dp)) {
                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            if (isToday(day)) {
                                                Box(modifier = Modifier.size(26.dp).clip(CircleShape).background(Color(0xFF1976D2)), contentAlignment = Alignment.Center) {
                                                    Text("${day.dayOfMonth}", color = Color.White)
                                                }
                                            } else { Text("${day.dayOfMonth}") }
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
    }

    if (selectedDate != null) {
        val selectedEvents = grouped[selectedDate] ?: emptyList()
        ModalBottomSheet(onDismissRequest = { onDateSelect(null) }, sheetState = sheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Eventos do dia", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = {
                        createHour = 9; createMinute = 0; showCreateDialogFromSheet = true
                    }) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Novo")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (selectedEvents.isEmpty()) {
                    Text("Nenhum evento agendado para este dia.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(selectedEvents) { event ->
                            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(event.title, style = MaterialTheme.typography.titleMedium)
                                        Text(event.formattedDate)
                                    }
                                    Row {
                                        // BOTÃO DE EDITAR
                                        IconButton(onClick = {
                                            selectedEventToEdit = event
                                            editTitleInput = event.title
                                            val cal = Calendar.getInstance().apply { timeInMillis = event.startTime }
                                            editHour = cal.get(Calendar.HOUR_OF_DAY)
                                            editMinute = cal.get(Calendar.MINUTE)
                                            showEditDialog = true
                                        }) { Icon(Icons.Filled.Edit, "Editar evento", tint = MaterialTheme.colorScheme.primary) }

                                        // BOTÃO DE DELETAR
                                        IconButton(onClick = {
                                            deleteEventFromAndroidCalendar(context, event.id)
                                            onDataChanged() // Atualiza após apagar!
                                        }) { Icon(Icons.Filled.Delete, "Excluir evento", tint = MaterialTheme.colorScheme.error) }
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
fun SettingsScreen() {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)

    // Estados das configurações
    var savedUriString by remember { mutableStateOf(sharedPrefs.getString("custom_alarm_uri", null)) }
    var alarmVolume by remember { mutableStateOf(sharedPrefs.getFloat("alarm_volume", 1.0f)) }
    var alarmDuration by remember { mutableStateOf(sharedPrefs.getInt("alarm_duration", 5)) } // Padrão de 5 minutos

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            sharedPrefs.edit().putString("custom_alarm_uri", it.toString()).apply()
            savedUriString = it.toString()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {

        // ------ 1. CONFIGURAÇÃO DE SOM ------
        Text("Áudio do Alarme", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = if (savedUriString != null) "Toque personalizado ativado!" else "Toque padrão do sistema", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }) { Text("Escolher arquivo .mp3") }
            if (savedUriString != null) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { sharedPrefs.edit().remove("custom_alarm_uri").apply(); savedUriString = null }) {
                    Text("Restaurar padrão", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Divider()
        Spacer(modifier = Modifier.height(32.dp))

        // ------ 2. CONFIGURAÇÃO DE VOLUME INDEPENDENTE ------
        Text("Volume do Aplicativo", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Define o volume deste alarme (independente do volume do celular).", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("0%", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = alarmVolume,
                onValueChange = { novoVolume ->
                    alarmVolume = novoVolume
                    sharedPrefs.edit().putFloat("alarm_volume", novoVolume).apply()
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text("${(alarmVolume * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
        }

        Spacer(modifier = Modifier.height(32.dp))
        Divider()
        Spacer(modifier = Modifier.height(32.dp))

        // ------ 3. CONFIGURAÇÃO DE DURAÇÃO ------
        Text("Duração do Toque", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Desliga o alarme automaticamente se você não responder.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val options = listOf(1 to "1 min", 5 to "5 min", 15 to "15 min", -1 to "Infinito")
            options.forEach { (value, label) ->
                FilterChip(
                    selected = alarmDuration == value,
                    onClick = {
                        alarmDuration = value
                        sharedPrefs.edit().putInt("alarm_duration", value).apply()
                    },
                    label = { Text(label) }
                )
            }
        }
    }
}