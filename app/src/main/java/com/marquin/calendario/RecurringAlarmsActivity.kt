package com.marquin.calendario

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marquin.calendario.ui.theme.CalendarioTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class RecurringAlarmItem(
    val id: String,
    val title: String,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val intervalMinutes: Int, // ex: 60 (1 hora), 120 (2 horas), 180 (3 horas)
    val soundUri: String?,
    val isEnabled: Boolean = true,
    val isFullScreen: Boolean = true,
    val repeatCount: Int = 1 // -1 = Loop contínuo do toque, >0 = quantidade de repetições do toque
)

class RecurringAlarmsActivity : ComponentActivity() {

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
                RecurringAlarmsScreenContent(
                    onBackClick = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringAlarmsScreen(
    onBackClick: () -> Unit = {}
) {
    RecurringAlarmsScreenContent(onBackClick = onBackClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringAlarmsScreenContent(
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var recurringAlarms by remember { mutableStateOf(loadRecurringAlarms(context)) }

    var showDialog by remember { mutableStateOf(false) }
    var alarmToEdit by remember { mutableStateOf<RecurringAlarmItem?>(null) }
    var alarmToDelete by remember { mutableStateOf<RecurringAlarmItem?>(null) }

    val updateAlarms: (List<RecurringAlarmItem>) -> Unit = { newList ->
        recurringAlarms = newList
        saveRecurringAlarms(context, newList)
        rescheduleAllRecurringAlarms(context, newList)
    }

    if (alarmToDelete != null) {
        AlertDialog(
            onDismissRequest = { alarmToDelete = null },
            title = { Text("Excluir Alerta Recorrente") },
            text = { Text("Tem certeza que deseja excluir \"${alarmToDelete?.title}\"?") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        alarmToDelete?.let { target ->
                            val updated = recurringAlarms.filterNot { it.id == target.id }
                            cancelRecurringAlarm(context, target)
                            updateAlarms(updated)
                        }
                        alarmToDelete = null
                    }
                ) { Text("Excluir", color = MaterialTheme.colorScheme.onError) }
            },
            dismissButton = { TextButton(onClick = { alarmToDelete = null }) { Text("Cancelar") } }
        )
    }

    if (showDialog) {
        EditRecurringAlarmDialog(
            alarmToEdit = alarmToEdit,
            onDismiss = {
                showDialog = false
                alarmToEdit = null
            },
            onSave = { savedItem ->
                val updated = if (alarmToEdit != null) {
                    recurringAlarms.map { if (it.id == savedItem.id) savedItem else it }
                } else {
                    recurringAlarms + savedItem
                }
                updateAlarms(updated)
                showDialog = false
                alarmToEdit = null
            }
        )
    }

    if (onBackClick != null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Alertas Recorrentes Diários", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                            Text("Notificações periódicas em horários configuráveis", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        alarmToEdit = null
                        showDialog = true
                    },
                    containerColor = Color(0xFFFFB300),
                    contentColor = Color(0xFF121212)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Adicionar Alerta", modifier = Modifier.size(28.dp))
                }
            }
        ) { paddingValues ->
            RecurringAlarmsBody(
                recurringAlarms = recurringAlarms,
                updateAlarms = updateAlarms,
                onEdit = { alarmToEdit = it; showDialog = true },
                onDelete = { alarmToDelete = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            )
        }
    } else {
        // Quando incorporado na aba da MainActivity: Sem Scaffold interno, sem TopAppBar e sem título duplicado!
        Box(modifier = Modifier.fillMaxSize()) {
            RecurringAlarmsBody(
                recurringAlarms = recurringAlarms,
                updateAlarms = updateAlarms,
                onEdit = { alarmToEdit = it; showDialog = true },
                onDelete = { alarmToDelete = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            )

            FloatingActionButton(
                onClick = {
                    alarmToEdit = null
                    showDialog = true
                },
                containerColor = Color(0xFFFFB300),
                contentColor = Color(0xFF121212),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Adicionar Alerta", modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun RecurringAlarmsBody(
    recurringAlarms: List<RecurringAlarmItem>,
    updateAlarms: (List<RecurringAlarmItem>) -> Unit,
    onEdit: (RecurringAlarmItem) -> Unit,
    onDelete: (RecurringAlarmItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier) {
        if (recurringAlarms.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Nenhum alerta recorrente criado.", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Ex: Lembrete de beber água de 2 em 2 horas", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(recurringAlarms) { item ->
                    val intervalText = when {
                        item.intervalMinutes < 60 -> "De ${item.intervalMinutes} em ${item.intervalMinutes} minutos"
                        item.intervalMinutes == 60 -> "De 1 em 1 hora"
                        item.intervalMinutes % 60 == 0 -> "De ${item.intervalMinutes / 60} em ${item.intervalMinutes / 60} horas"
                        else -> "A cada ${item.intervalMinutes} minutos"
                    }

                    val repeatQtyText = if (item.repeatCount == -1) " • Loop contínuo" else " • Toque ${item.repeatCount}x"

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (item.isEnabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (item.isEnabled) Color(0xFFFFB300) else Color.Gray)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Switch(
                                    checked = item.isEnabled,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF121212),
                                        checkedTrackColor = Color(0xFFFFB300)
                                    ),
                                    onCheckedChange = { checked ->
                                        val updated = recurringAlarms.map { if (it.id == item.id) it.copy(isEnabled = checked) else it }
                                        updateAlarms(updated)
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = String.format("⏰ %02d:%02d até %02d:%02d • %s%s", item.startHour, item.startMinute, item.endHour, item.endMinute, intervalText, repeatQtyText),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                                ) {
                                    Text(
                                        text = if (item.isFullScreen) "🔔 Tela Cheia (Alarme)" else "💬 Banner de Notificação",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = Color.Gray
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = {
                                        val testIntent = Intent(context, AlarmReceiver::class.java).apply {
                                            putExtra("EVENT_TITLE", item.title)
                                            putExtra("RECURRING_ALARM_ID", item.id)
                                            putExtra("IS_RECURRING", true)
                                        }
                                        context.sendBroadcast(testIntent)
                                    }) {
                                        Text("▶ Testar Alerta", color = Color(0xFFFFB300), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                    }

                                    IconButton(onClick = { onEdit(item) }) {
                                        Icon(Icons.Filled.Edit, "Editar", tint = Color(0xFFFFB300))
                                    }
                                    IconButton(onClick = { onDelete(item) }) {
                                        Icon(Icons.Filled.Delete, "Excluir", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditRecurringAlarmDialog(
    alarmToEdit: RecurringAlarmItem?,
    onDismiss: () -> Unit,
    onSave: (RecurringAlarmItem) -> Unit
) {
    val context = LocalContext.current
    var titleInput by remember { mutableStateOf(alarmToEdit?.title ?: "Beber Água") }

    var startHour by remember { mutableStateOf(alarmToEdit?.startHour ?: 6) }
    var startMinute by remember { mutableStateOf(alarmToEdit?.startMinute ?: 0) }

    var endHour by remember { mutableStateOf(alarmToEdit?.endHour ?: 22) }
    var endMinute by remember { mutableStateOf(alarmToEdit?.endMinute ?: 0) }

    var selectedIntervalMinutes by remember { mutableStateOf(alarmToEdit?.intervalMinutes ?: 120) }
    var isCustomInterval by remember { mutableStateOf(alarmToEdit != null && alarmToEdit.intervalMinutes !in listOf(30, 60, 120, 180, 240)) }
    var customIntervalInputText by remember { mutableStateOf((alarmToEdit?.intervalMinutes ?: 120).toString()) }

    var repeatInputText by remember { mutableStateOf(if (alarmToEdit != null && alarmToEdit.repeatCount > 0) alarmToEdit.repeatCount.toString() else "1") }
    var isLooping by remember { mutableStateOf(alarmToEdit?.repeatCount == -1) }

    var isFullScreen by remember { mutableStateOf(alarmToEdit?.isFullScreen ?: true) }

    val builtInSounds = remember { getBuiltInSounds(context) }
    val userSavedSounds = remember { getSavedSounds(context) }
    val allAvailableSounds = remember(userSavedSounds) { builtInSounds + userSavedSounds }

    var selectedSoundUri by remember { mutableStateOf(alarmToEdit?.soundUri) }
    var expandedSoundDropdown by remember { mutableStateOf(false) }

    val presetIntervals = listOf(
        30 to "30 min",
        60 to "1 hora",
        120 to "2 horas",
        180 to "3 horas",
        240 to "4 horas"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (alarmToEdit == null) "Novo Alerta Recorrente" else "Editar Alerta Recorrente") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Nome do Alerta (Ex: Beber Água)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Início (a partir das):", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    android.app.TimePickerDialog(context, { _, h, m -> startHour = h; startMinute = m }, startHour, startMinute, true).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(String.format("%02d:%02d", startHour, startMinute)) }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Término (até as):", style = MaterialTheme.typography.labelMedium)
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

                // ------ FATOR DE REPETIÇÃO / FREQUÊNCIA ------
                item {
                    Text("Fator de Repetição (Intervalo):", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presetIntervals.take(3).forEach { (mins, label) ->
                            FilterChip(
                                selected = !isCustomInterval && selectedIntervalMinutes == mins,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFFFB300),
                                    selectedLabelColor = Color(0xFF121212)
                                ),
                                onClick = {
                                    isCustomInterval = false
                                    selectedIntervalMinutes = mins
                                },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presetIntervals.drop(3).forEach { (mins, label) ->
                            FilterChip(
                                selected = !isCustomInterval && selectedIntervalMinutes == mins,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFFFB300),
                                    selectedLabelColor = Color(0xFF121212)
                                ),
                                onClick = {
                                    isCustomInterval = false
                                    selectedIntervalMinutes = mins
                                },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }

                        FilterChip(
                            selected = isCustomInterval,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFFB300),
                                selectedLabelColor = Color(0xFF121212)
                            ),
                            onClick = { isCustomInterval = true },
                            label = { Text("Personalizado", fontSize = 11.sp, fontWeight = if (isCustomInterval) FontWeight.Bold else FontWeight.Normal) }
                        )
                    }

                    if (isCustomInterval) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = customIntervalInputText,
                            onValueChange = { input ->
                                if (input.all { it.isDigit() }) {
                                    customIntervalInputText = input
                                    input.toIntOrNull()?.let { selectedIntervalMinutes = it }
                                }
                            },
                            label = { Text("Intervalo personalizado (em minutos)") },
                            placeholder = { Text("Ex: 45, 90, 150") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ------ REPETIÇÃO DO TOQUE (VEZES OU LOOP) ------
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
                            label = { Text("Loop", fontWeight = if (isLooping) FontWeight.Bold else FontWeight.Normal, fontSize = 11.sp) }
                        )
                    }
                }

                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isFullScreen = !isFullScreen }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = isFullScreen,
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFFB300), checkmarkColor = Color(0xFF121212)),
                            onCheckedChange = { isFullScreen = it }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("Alertar em tela inteira (Alarme)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Text("Se desmarcado, alerta via banner de notificação", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }

                item {
                    Text("Som do Alerta:", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val currentSoundName = remember(selectedSoundUri, allAvailableSounds) {
                            if (selectedSoundUri == null) "Google Pixel Ringtone (Padrão)"
                            else allAvailableSounds.find { it.uriString == selectedSoundUri }?.name ?: "Som Selecionado"
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
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = titleInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF121212)),
                onClick = {
                    val finalInterval = if (isCustomInterval) {
                        customIntervalInputText.toIntOrNull()?.takeIf { it > 0 } ?: 60
                    } else selectedIntervalMinutes

                    val finalRepeatCount = if (isLooping) -1 else (repeatInputText.toIntOrNull()?.takeIf { it > 0 } ?: 1)

                    val newItem = RecurringAlarmItem(
                        id = alarmToEdit?.id ?: ("rec_" + System.currentTimeMillis()),
                        title = titleInput,
                        startHour = startHour,
                        startMinute = startMinute,
                        endHour = endHour,
                        endMinute = endMinute,
                        intervalMinutes = finalInterval,
                        soundUri = selectedSoundUri,
                        isEnabled = alarmToEdit?.isEnabled ?: true,
                        isFullScreen = isFullScreen,
                        repeatCount = finalRepeatCount
                    )
                    onSave(newItem)
                }
            ) { Text("Salvar", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ------ STORAGE & SCHEDULING HELPERS ------

fun loadRecurringAlarms(context: Context): List<RecurringAlarmItem> {
    val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
    val jsonString = sharedPrefs.getString("recurring_alarms_json", null) ?: return emptyList()
    val list = mutableListOf<RecurringAlarmItem>()
    try {
        val array = JSONArray(jsonString)
        for (i in 0 until array.length()) {
            val obj = JSONArray(jsonString).let {
                val itemObj = it.getJSONObject(i)
                list.add(
                    RecurringAlarmItem(
                        id = itemObj.getString("id"),
                        title = itemObj.getString("title"),
                        startHour = itemObj.getInt("startHour"),
                        startMinute = itemObj.getInt("startMinute"),
                        endHour = itemObj.getInt("endHour"),
                        endMinute = itemObj.getInt("endMinute"),
                        intervalMinutes = itemObj.getInt("intervalMinutes"),
                        soundUri = if (itemObj.has("soundUri") && !itemObj.isNull("soundUri")) itemObj.getString("soundUri") else null,
                        isEnabled = if (itemObj.has("isEnabled")) itemObj.getBoolean("isEnabled") else true,
                        isFullScreen = if (itemObj.has("isFullScreen")) itemObj.getBoolean("isFullScreen") else true,
                        repeatCount = if (itemObj.has("repeatCount")) itemObj.getInt("repeatCount") else (if (itemObj.has("maxRepeats")) itemObj.getInt("maxRepeats") else 1)
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}

fun saveRecurringAlarms(context: Context, alarms: List<RecurringAlarmItem>) {
    val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
    val array = JSONArray()
    alarms.forEach { alarm ->
        val obj = JSONObject().apply {
            put("id", alarm.id)
            put("title", alarm.title)
            put("startHour", alarm.startHour)
            put("startMinute", alarm.startMinute)
            put("endHour", alarm.endHour)
            put("endMinute", alarm.endMinute)
            put("intervalMinutes", alarm.intervalMinutes)
            put("soundUri", alarm.soundUri)
            put("isEnabled", alarm.isEnabled)
            put("isFullScreen", alarm.isFullScreen)
            put("repeatCount", alarm.repeatCount)
        }
        array.put(obj)
    }
    sharedPrefs.edit().putString("recurring_alarms_json", array.toString()).apply()
}

fun rescheduleAllRecurringAlarms(context: Context, alarms: List<RecurringAlarmItem>) {
    alarms.forEach { alarm ->
        if (alarm.isEnabled) {
            scheduleNextTriggerForRecurringAlarm(context, alarm)
        } else {
            cancelRecurringAlarm(context, alarm)
        }
    }
}

fun scheduleNextTriggerForRecurringAlarm(context: Context, alarm: RecurringAlarmItem) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val now = System.currentTimeMillis()

    val nextTrigger = calculateNextTriggerMillis(alarm, now) ?: return

    val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
        putExtra("EVENT_TITLE", alarm.title)
        putExtra("RECURRING_ALARM_ID", alarm.id)
        putExtra("IS_RECURRING", true)
    }

    val pendingIntent = PendingIntent.getBroadcast(
        context,
        alarm.id.hashCode(),
        alarmIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, nextTrigger, pendingIntent)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pendingIntent)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun cancelRecurringAlarm(context: Context, alarm: RecurringAlarmItem) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val alarmIntent = Intent(context, AlarmReceiver::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        alarm.id.hashCode(),
        alarmIntent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )
    pendingIntent?.let { alarmManager.cancel(it) }
}

fun calculateNextTriggerMillis(alarm: RecurringAlarmItem, nowMillis: Long): Long? {
    val startCal = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, alarm.startHour)
        set(Calendar.MINUTE, alarm.startMinute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    val endCal = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, alarm.endHour)
        set(Calendar.MINUTE, alarm.endMinute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    var startMillis = startCal.timeInMillis
    var endMillis = endCal.timeInMillis
    if (endMillis <= startMillis) {
        endMillis += 24 * 60 * 60 * 1000L
    }

    val intervalMins = if (alarm.intervalMinutes > 0) alarm.intervalMinutes else 60
    val intervalMillis = intervalMins * 60 * 1000L

    // 1. Se a hora atual for anterior ao horário de início de hoje
    if (nowMillis < startMillis) {
        return startMillis
    }

    // 2. Se a hora atual estiver dentro da janela de hoje [startMillis, endMillis]
    var currentSlot = startMillis
    while (currentSlot <= endMillis) {
        if (currentSlot > nowMillis) {
            return currentSlot
        }
        currentSlot += intervalMillis
    }

    // 3. Se todos os horários do intervalo de hoje já passaram, o próximo disparo é na hora de início de amanhã
    startCal.add(Calendar.DAY_OF_YEAR, 1)
    return startCal.timeInMillis
}
