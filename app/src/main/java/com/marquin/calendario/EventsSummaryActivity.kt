package com.marquin.calendario

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marquin.calendario.ui.theme.CalendarioTheme
import kotlinx.coroutines.Dispatchers
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

private val accountColorsList = listOf(
    Color(0xFFFFB300), // Amarelo Flash
    Color(0xFF2196F3), // Azul
    Color(0xFF4CAF50), // Verde
    Color(0xFFE91E63), // Rosa
    Color(0xFF9C27B0), // Roxo
    Color(0xFFFF5722), // Laranja
    Color(0xFF00BCD4), // Ciano
    Color(0xFF8BC34A)  // Lime
)

private fun getSourceColorForSummary(sourceId: Long, sources: List<CalendarSource>): Color {
    val index = sources.indexOfFirst { it.id == sourceId }
    if (index == -1) return Color(0xFFFFB300)
    return accountColorsList[index % accountColorsList.size]
}

data class SummaryEventItem(
    val id: Long,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val calendarId: Long,
    val accountName: String,
    val isHoliday: Boolean,
    val localDate: LocalDate
)

class EventsSummaryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val sharedPrefs = getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
            val themeMode = sharedPrefs.getString("app_theme", "system") ?: "system"
            val isDarkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            CalendarioTheme(darkTheme = isDarkTheme) {
                EventsSummaryScreenContent(
                    onBackClick = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EventsSummaryScreen(
    onBackClick: () -> Unit = {}
) {
    EventsSummaryScreenContent(onBackClick = onBackClick)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EventsSummaryScreenContent(
    calendarSourcesOverride: List<CalendarSource>? = null,
    refreshTrigger: Int = 0,
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)

    var searchQuery by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf("future") } // "future", "all", "this_month"

    var allEvents by remember { mutableStateOf<List<SummaryEventItem>>(emptyList()) }
    var calendarSources by remember { mutableStateOf<List<CalendarSource>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }

    var eventToDelete by remember { mutableStateOf<SummaryEventItem?>(null) }
    var selectedEventToEdit by remember { mutableStateOf<SummaryEventItem?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }

    val loadData: () -> Unit = {
        coroutineScope.launch(Dispatchers.IO) {
            val sources = calendarSourcesOverride.takeIf { !it.isNullOrEmpty() } ?: queryCalendarSources(context)
            val events = queryAllEvents(context, sources)
            withContext(Dispatchers.Main) {
                calendarSources = sources
                allEvents = events
                isLoading = false
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(calendarSourcesOverride, refreshTrigger) {
        loadData()
    }

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
                        eventToDelete?.let { ev ->
                            deleteEventFromCalendar(context, ev.id)
                            loadData()
                        }
                        eventToDelete = null
                    }
                ) { Text("Excluir", color = MaterialTheme.colorScheme.onError) }
            },
            dismissButton = { TextButton(onClick = { eventToDelete = null }) { Text("Cancelar") } }
        )
    }

    // Diálogo de edição de evento
    if (showEditDialog && selectedEventToEdit != null) {
        EditSummaryEventDialog(
            event = selectedEventToEdit!!,
            calendarSources = calendarSources,
            onDismiss = {
                showEditDialog = false
                selectedEventToEdit = null
            },
            onSave = { updatedTitle, updatedDate, startH, startM, endH, endM, soundUri, repeatCount, reminderType, isFullScreen ->
                updateCalendarEvent(context, selectedEventToEdit!!.id, updatedTitle, updatedDate, startH, startM, endH, endM, soundUri, repeatCount, reminderType, isFullScreen)
                loadData()
                showEditDialog = false
                selectedEventToEdit = null
            }
        )
    }

    val today = remember { LocalDate.now() }
    val currentYearMonth = remember { YearMonth.now() }

    val filteredEvents = remember(allEvents, searchQuery, filterType, today, currentYearMonth) {
        allEvents.filter { ev ->
            // Filtro de Busca por título ou conta
            val matchesSearch = searchQuery.isBlank() ||
                    ev.title.contains(searchQuery, ignoreCase = true) ||
                    ev.accountName.contains(searchQuery, ignoreCase = true)

            // Filtro Temporal
            val matchesTime = when (filterType) {
                "future" -> !ev.localDate.isBefore(today)
                "this_month" -> YearMonth.from(ev.localDate) == currentYearMonth
                else -> true
            }

            matchesSearch && matchesTime
        }
    }

    val monthHeaderFormatter = remember { DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale("pt", "BR")) }
    val groupedByMonth = remember(filteredEvents) {
        filteredEvents.groupBy { ev ->
            YearMonth.from(ev.localDate)
        }.toSortedMap()
    }

    if (onBackClick != null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Resumo de Eventos", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                            Text("${filteredEvents.size} evento(s) encontrado(s)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar")
                        }
                    }
                )
            }
        ) { paddingValues ->
            EventsSummaryBody(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                filterType = filterType,
                onFilterTypeChange = { filterType = it },
                isLoading = isLoading,
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    loadData()
                },
                filteredEvents = filteredEvents,
                groupedByMonth = groupedByMonth,
                monthHeaderFormatter = monthHeaderFormatter,
                calendarSources = calendarSources,
                sharedPrefs = sharedPrefs,
                onEdit = { selectedEventToEdit = it; showEditDialog = true },
                onDelete = { eventToDelete = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
            )
        }
    } else {
        // Quando incorporado na aba da MainActivity: Sem Scaffold interno, sem TopAppBar e sem GAP!
        EventsSummaryBody(
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            filterType = filterType,
            onFilterTypeChange = { filterType = it },
            isLoading = isLoading,
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                loadData()
            },
            filteredEvents = filteredEvents,
            groupedByMonth = groupedByMonth,
            monthHeaderFormatter = monthHeaderFormatter,
            calendarSources = calendarSources,
            sharedPrefs = sharedPrefs,
            onEdit = { selectedEventToEdit = it; showEditDialog = true },
            onDelete = { eventToDelete = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventsSummaryBody(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filterType: String,
    onFilterTypeChange: (String) -> Unit,
    isLoading: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    filteredEvents: List<SummaryEventItem>,
    groupedByMonth: java.util.SortedMap<YearMonth, List<SummaryEventItem>>,
    monthHeaderFormatter: DateTimeFormatter,
    calendarSources: List<CalendarSource>,
    sharedPrefs: android.content.SharedPreferences,
    onEdit: (SummaryEventItem) -> Unit,
    onDelete: (SummaryEventItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var offsetY by remember { mutableFloatStateOf(0f) }
    val refreshThreshold = 120f

    Box(
        modifier = modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onVerticalDrag = { change, dragAmount ->
                    if (dragAmount > 0 || offsetY > 0) {
                        offsetY = (offsetY + dragAmount * 0.4f).coerceIn(0f, 200f)
                    }
                },
                onDragEnd = {
                    if (offsetY >= refreshThreshold) {
                        onRefresh()
                    }
                    offsetY = 0f
                }
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ------ BARRA DE PESQUISA ------
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Pesquisar eventos ou contas...") },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color(0xFFFFB300)) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Filled.Clear, "Limpar busca")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            )

            // ------ CHIPS DE FILTRO TEMPORAL ------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf(
                    "future" to "A partir de Hoje",
                    "this_month" to "Este Mês",
                    "all" to "Todos (Inclus. Passados)"
                )
                filters.forEach { (key, label) ->
                    FilterChip(
                        selected = filterType == key,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFB300),
                            selectedLabelColor = Color(0xFF121212)
                        ),
                        onClick = { onFilterTypeChange(key) },
                        label = { Text(label, fontWeight = if (filterType == key) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp) }
                    )
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFFB300))
                }
            } else if (filteredEvents.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nenhum evento encontrado.", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Tente alterar o filtro de busca ou puxe para baixo para atualizar.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    groupedByMonth.forEach { (yearMonth, monthEvents) ->
                        stickyHeader {
                            val headerText = yearMonth.format(monthHeaderFormatter).replaceFirstChar { it.uppercase() }
                            Surface(
                                color = MaterialTheme.colorScheme.background,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = headerText,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFFFFB300),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }

                        items(monthEvents) { event ->
                            val sourceColor = getSourceColorForSummary(event.calendarId, calendarSources)
                            val userOverride = sharedPrefs.getString("event_reminder_type_${event.id}", null)
                            val googleMins = if (userOverride == null) getGoogleCalendarReminderMinutes(context, event.id) else null

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

                            val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.getDefault()) }
                            val formattedTime = remember(event.startTime) { dateFormat.format(Date(event.startTime)) }

                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = event.title,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = formattedTime + reminderText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(sourceColor)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = event.accountName,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = sourceColor,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (!event.isHoliday) {
                                            IconButton(onClick = { onEdit(event) }) {
                                                Icon(Icons.Filled.Edit, "Editar", tint = Color(0xFFFFB300))
                                            }
                                            IconButton(onClick = { onDelete(event) }) {
                                                Icon(Icons.Filled.Delete, "Excluir", tint = MaterialTheme.colorScheme.error)
                                            }
                                        } else {
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
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
                    item { Spacer(modifier = Modifier.height(24.dp)) }
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
}

@Composable
fun EditSummaryEventDialog(
    event: SummaryEventItem,
    calendarSources: List<CalendarSource>,
    onDismiss: () -> Unit,
    onSave: (title: String, date: LocalDate, startH: Int, startM: Int, endH: Int, endM: Int, soundUri: String?, repeatCount: Int, reminderType: String, isFullScreen: Boolean) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)

    var titleInput by remember { mutableStateOf(event.title) }
    var eventDate by remember { mutableStateOf(event.localDate) }

    val startCal = remember(event.startTime) { Calendar.getInstance().apply { timeInMillis = event.startTime } }
    val endCal = remember(event.endTime) { Calendar.getInstance().apply { timeInMillis = event.endTime } }

    var startHour by remember { mutableStateOf(startCal.get(Calendar.HOUR_OF_DAY)) }
    var startMinute by remember { mutableStateOf(startCal.get(Calendar.MINUTE)) }
    var endHour by remember { mutableStateOf(endCal.get(Calendar.HOUR_OF_DAY)) }
    var endMinute by remember { mutableStateOf(endCal.get(Calendar.MINUTE)) }

    var soundUri by remember { mutableStateOf(sharedPrefs.getString("event_ringtone_${event.id}", null)) }
    var expandedSoundDropdown by remember { mutableStateOf(false) }

    var reminderType by remember { mutableStateOf(sharedPrefs.getString("event_reminder_type_${event.id}", null) ?: getGoogleCalendarReminderType(context, event.id)) }
    var expandedReminderDropdown by remember { mutableStateOf(false) }

    var isFullScreenAlarm by remember { mutableStateOf(sharedPrefs.getBoolean("event_fullscreen_${event.id}", true)) }

    var repeatInputText by remember { mutableStateOf("1") }
    var isLooping by remember { mutableStateOf(false) }

    val builtInSounds = remember { getBuiltInSounds(context) }
    val userSavedSounds = remember { getSavedSounds(context) }
    val allAvailableSounds = remember(userSavedSounds) { builtInSounds + userSavedSounds }

    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy", Locale("pt", "BR")) }

    LaunchedEffect(event.id) {
        if (sharedPrefs.contains("event_repeats_${event.id}")) {
            val repeats = sharedPrefs.getInt("event_repeats_${event.id}", 1)
            if (repeats == -1) {
                isLooping = true
                repeatInputText = "1"
            } else {
                isLooping = false
                repeatInputText = if (repeats > 0) repeats.toString() else "1"
            }
        } else {
            isLooping = false
            repeatInputText = "1"
        }
    }

    val computedRepeatCount = remember(repeatInputText, isLooping) {
        if (isLooping) -1
        else repeatInputText.toIntOrNull()?.takeIf { it > 0 } ?: 1
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
        title = { Text("Editar Evento") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
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
                        Text(eventDate.format(dateFormatter), fontWeight = FontWeight.Bold)
                    }
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Início:", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    android.app.TimePickerDialog(context, { _, h, m -> startHour = h; startMinute = m }, startHour, startMinute, true).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(String.format("%02d:%02d", startHour, startMinute)) }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Término:", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    android.app.TimePickerDialog(context, { _, h, m -> endHour = h; endMinute = m }, endHour, endMinute, true).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(String.format("%02d:%02d", endHour, endMinute)) }
                        }
                    }
                }

                item {
                    Text("Lembrete / Alerta Antecipado:", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val currentReminderText = reminderOptions.find { it.first == reminderType }?.second ?: "No horário"
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
                                    text = { Text(label, fontWeight = if (reminderType == key) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = {
                                        reminderType = key
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
                            Text("Se desmarcado, alerta via banner de notificação", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }

                item {
                    Text("Som do Alerta (Toque):", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val currentSoundName = remember(soundUri, allAvailableSounds) {
                            if (soundUri == null) "Google Pixel Ringtone (Padrão)"
                            else allAvailableSounds.find { it.uriString == soundUri }?.name ?: "Toque Selecionado"
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
                                    text = { Text("${sound.category}: ${sound.name}", fontWeight = if (soundUri == sound.uriString) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = {
                                        soundUri = sound.uriString
                                        expandedSoundDropdown = false
                                    }
                                )
                            }
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
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF121212)),
                onClick = {
                    onSave(titleInput, eventDate, startHour, startMinute, endHour, endMinute, soundUri, computedRepeatCount, reminderType, isFullScreenAlarm)
                }
            ) { Text("Atualizar", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ------ HELPER FUNCTIONS PARA QUERY NO BANCO ------

private fun queryCalendarSources(context: Context): List<CalendarSource> {
    val sources = mutableListOf<CalendarSource>()
    val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)

    val cursor = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY
        ),
        null, null, null
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

    return sources.sortedWith(compareBy { source ->
        when {
            source.name.contains("Feriados Nacionais", ignoreCase = true) || source.name.contains("Feriados no Brasil", ignoreCase = true) -> 0
            source.name.contains("Feriados Religiosos", ignoreCase = true) || source.name.contains("Feriados Cristãos", ignoreCase = true) -> 1
            source.isHoliday -> 2
            else -> 3
        }
    })
}

private fun queryAllEvents(context: Context, sources: List<CalendarSource>): List<SummaryEventItem> {
    val rawItems = mutableListOf<SummaryEventItem>()
    val enabledCalendarIds = sources.filter { it.enabled }.map { it.id }.toSet()

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
    val cursor = context.contentResolver.query(
        CalendarContract.Events.CONTENT_URI,
        projection, selection, null,
        "${CalendarContract.Events.DTSTART} ASC"
    )

    val holidayIds = sources.filter { it.isHoliday }.map { it.id }.toSet()
    val sourceMap = sources.associateBy { it.id }

    cursor?.use {
        val idIdx = it.getColumnIndex(CalendarContract.Events._ID)
        val titleIdx = it.getColumnIndex(CalendarContract.Events.TITLE)
        val startIdx = it.getColumnIndex(CalendarContract.Events.DTSTART)
        val endIdx = it.getColumnIndex(CalendarContract.Events.DTEND)
        val calIdx = it.getColumnIndex(CalendarContract.Events.CALENDAR_ID)
        val allDayIdx = it.getColumnIndex(CalendarContract.Events.ALL_DAY)
        val tzIdx = it.getColumnIndex(CalendarContract.Events.EVENT_TIMEZONE)

        while (it.moveToNext()) {
            val calendarId = it.getLong(calIdx)
            // Filtro por Checkbox: exibe apenas eventos das contas que estão marcadas no menu lateral!
            if (!enabledCalendarIds.contains(calendarId)) continue

            val eventId = it.getLong(idIdx)
            val title = it.getString(titleIdx) ?: "Sem título"
            val startMillis = it.getLong(startIdx)
            var endMillis = if (endIdx != -1 && !it.isNull(endIdx)) it.getLong(endIdx) else (startMillis + 3600000)
            if (endMillis <= startMillis) endMillis = startMillis + 3600000

            val isAllDay = (allDayIdx != -1 && it.getInt(allDayIdx) == 1)
            val eventTz = if (tzIdx != -1) it.getString(tzIdx) else null
            val isHoliday = holidayIds.contains(calendarId)

            val isUtcDate = isAllDay || isHoliday || eventTz == "UTC"
            val localDate = if (isUtcDate) {
                Instant.ofEpochMilli(startMillis).atZone(ZoneId.of("UTC")).toLocalDate()
            } else {
                Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            }

            val source = sourceMap[calendarId]
            val accountName = source?.account?.ifBlank { source.name } ?: "Google Agenda"

            rawItems.add(
                SummaryEventItem(
                    id = eventId,
                    title = title,
                    startTime = startMillis,
                    endTime = endMillis,
                    calendarId = calendarId,
                    accountName = accountName,
                    isHoliday = isHoliday,
                    localDate = localDate
                )
            )
        }
    }

    // Deduplicação: Remove duplicatas do mesmo título na mesma data (ex: "Dia do Servidor Público")
    val map = mutableMapOf<String, SummaryEventItem>()
    for (item in rawItems) {
        val key = "${item.title.trim().lowercase()}_${item.localDate}"
        val existing = map[key]
        if (existing == null) {
            map[key] = item
        } else if (item.isHoliday && !existing.isHoliday) {
            map[key] = item
        }
    }
    return map.values.sortedBy { it.startTime }
}

private fun updateCalendarEvent(
    context: Context,
    eventId: Long,
    title: String,
    date: LocalDate,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    soundUri: String?,
    repeatCount: Int,
    reminderType: String,
    isFullScreen: Boolean
) {
    val startCal = Calendar.getInstance().apply { set(date.year, date.monthValue - 1, date.dayOfMonth, startHour, startMinute, 0) }
    val endCal = Calendar.getInstance().apply { set(date.year, date.monthValue - 1, date.dayOfMonth, endHour, endMinute, 0) }

    var startMillis = startCal.timeInMillis
    var endMillis = endCal.timeInMillis
    if (endMillis <= startMillis) endMillis = startMillis + 3600000

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
            if (soundUri != null) putString("event_ringtone_$eventId", soundUri)
            else remove("event_ringtone_$eventId")
            putInt("event_repeats_$eventId", repeatCount)
            putString("event_reminder_type_$eventId", reminderType)
            putBoolean("event_fullscreen_$eventId", isFullScreen)
            apply()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun deleteEventFromCalendar(context: Context, eventId: Long) {
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
