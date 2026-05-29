package com.marquin.calendario

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import java.time.*
import java.util.*

data class CalendarEvent(
    val title: String,
    val startTime: Long,
    val formattedDate: String,
    val calendarId: Long
)

/* -------------------- DATA HELPERS -------------------- */

private fun getDaysOfMonth(yearMonth: YearMonth): List<LocalDate?> {

    val firstDayOfMonth =
        LocalDate.of(yearMonth.year, yearMonth.month, 1)

    val daysInMonth = yearMonth.lengthOfMonth()

    val firstDayWeek =
        firstDayOfMonth.dayOfWeek.value % 7

    val days = mutableListOf<LocalDate?>()

    // dias vazios no começo
    repeat(firstDayWeek) {
        days.add(null)
    }

    // dias do mês
    repeat(daysInMonth) {
        days.add(firstDayOfMonth.plusDays(it.toLong()))
    }

    // completar última linha
    while (days.size % 7 != 0) {
        days.add(null)
    }

    return days
}

private fun groupEventsByDay(events: List<CalendarEvent>): Map<LocalDate, List<CalendarEvent>> {
    return events.groupBy { event ->
        Instant.ofEpochMilli(event.startTime)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
    }
}

private fun isToday(date: LocalDate?): Boolean {
    return date == LocalDate.now()
}

/* -------------------- ACTIVITY -------------------- */

class MainActivity : ComponentActivity() {

    private val calendarPermissionCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestCalendarPermission()

        setContent {

            var events by remember { mutableStateOf(listOf<CalendarEvent>()) }

            LaunchedEffect(Unit) {
                events = getCalendarEvents()
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MonthView(events)
                }
            }
        }
    }

    private fun requestCalendarPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CALENDAR
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                ),
                calendarPermissionCode
            )
        }
    }

    private fun getCalendarEvents(): List<CalendarEvent> {

        val events = mutableListOf<CalendarEvent>()

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.CALENDAR_ID
        )

        val cursor = contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Events.DTSTART} ASC"
        )

        cursor?.use {

            val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
            val startIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val calendarIdIndex = it.getColumnIndex(CalendarContract.Events.CALENDAR_ID)

            val format = SimpleDateFormat(
                "dd/MM/yyyy HH:mm",
                Locale.getDefault()
            )

            while (it.moveToNext()) {

                val title = it.getString(titleIndex)
                val startMillis = it.getLong(startIndex)
                val calendarId = it.getLong(calendarIdIndex)

                val formattedDate = format.format(Date(startMillis))

                events.add(
                    CalendarEvent(
                        title = title ?: "Sem título",
                        startTime = startMillis,
                        formattedDate = formattedDate,
                        calendarId = calendarId
                    )
                )
            }
        }

        return events
    }
}


/* -------------------- UI -------------------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthView(events: List<CalendarEvent>) {

    var currentMonth by remember {
        mutableStateOf(YearMonth.now())
    }

    val grouped = remember(events) {
        groupEventsByDay(events)
    }

    val days = remember(currentMonth) {
        getDaysOfMonth(currentMonth)
    }

    var selectedDate by remember {
        mutableStateOf<LocalDate?>(null)
    }

    val sheetState = rememberModalBottomSheetState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 120.dp, start = 25.dp, end = 25.dp)
            .pointerInput(Unit) {

                detectHorizontalDragGestures { _, dragAmount ->

                    if (dragAmount > 40) {
                        currentMonth = currentMonth.minusMonths(1)
                    }

                    if (dragAmount < -40) {
                        currentMonth = currentMonth.plusMonths(1)
                    }
                }
            }
    ) {

        /* ---------------- MÊS ---------------- */

        Text(
            text = currentMonth.month.name.lowercase()
                .replaceFirstChar { it.uppercase() }
                    + " ${currentMonth.year}",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(12.dp))

        /* ---------------- HEADER ---------------- */

        Row(modifier = Modifier.fillMaxWidth()) {

            listOf(
                "Dom",
                "Seg",
                "Ter",
                "Qua",
                "Qui",
                "Sex",
                "Sáb"
            ).forEach {

                Text(
                    text = it,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        /* ---------------- GRID ---------------- */

        LazyColumn {

            items(days.chunked(7)) { week ->

                Row(modifier = Modifier.fillMaxWidth()) {

                    week.forEach { day ->

                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .clickable {

                                    if (day != null) {
                                        selectedDate = day
                                    }
                                }
                        ) {

                            if (day != null) {

                                val dayEvents =
                                    grouped[day] ?: emptyList()

                                val today = isToday(day)

                                Column(
                                    modifier = Modifier.padding(6.dp)
                                ) {

                                    /* -------- DIA -------- */

                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {

                                        if (today) {

                                            Box(
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        Color(0xFF1976D2)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {

                                                Text(
                                                    text = "${day.dayOfMonth}",
                                                    color = Color.White
                                                )
                                            }

                                        } else {

                                            Text(
                                                text = "${day.dayOfMonth}"
                                            )
                                        }
                                    }

                                    Spacer(
                                        modifier = Modifier.height(6.dp)
                                    )

                                    /* -------- EVENTOS -------- */

                                    if (dayEvents.isNotEmpty()) {

                                        Text(
                                            text = "${dayEvents.size} evento(s)",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }

                            } else {

                                Box(
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /* ---------------- BOTTOM SHEET ---------------- */

    if (selectedDate != null) {

        val selectedEvents =
            grouped[selectedDate] ?: emptyList()

        ModalBottomSheet(
            onDismissRequest = {
                selectedDate = null
            },
            sheetState = sheetState
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {

                Text(
                    text = "Eventos do dia",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (selectedEvents.isEmpty()) {

                    Text("Nenhum evento")

                } else {

                    selectedEvents.forEach { event ->

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                        ) {

                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {

                                Text(
                                    text = event.title,
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Spacer(
                                    modifier = Modifier.height(4.dp)
                                )

                                Text(
                                    text = event.formattedDate
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}