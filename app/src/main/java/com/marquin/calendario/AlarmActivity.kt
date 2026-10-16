package com.marquin.calendario

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.marquin.calendario.ui.theme.CalendarioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AlarmActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var eventTitle: String = "Evento"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        eventTitle = intent.getStringExtra("EVENT_TITLE") ?: "Evento"
        val eventId = intent.getLongExtra("EVENT_ID", -1L)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(eventTitle.hashCode())

        val sharedPrefs = getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
        val defaultUriString = "android.resource://$packageName/${R.raw.google_pixel_ringtone}"

        val customUriString = intent.getStringExtra("SOUND_URI")
            ?: if (eventId != -1L) sharedPrefs.getString("event_ringtone_$eventId", null) else null
            ?: sharedPrefs.getString("custom_alarm_uri", defaultUriString)

        val repeatCount = if (intent.hasExtra("EVENT_REPEATS")) {
            intent.getIntExtra("EVENT_REPEATS", 1)
        } else if (eventId != -1L) {
            sharedPrefs.getInt("event_repeats_$eventId", 1)
        } else {
            1
        }

        val alarmVolume = sharedPrefs.getFloat("alarm_volume", 1.0f)
        val alarmDuration = sharedPrefs.getInt("alarm_duration", 5)

        val alarmUri: Uri = try {
            Uri.parse(customUriString ?: defaultUriString)
        } catch (e: Exception) {
            Uri.parse(defaultUriString)
        }

        fun startAudio(uri: Uri) {
            try {
                var remainingPlays = repeatCount
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(applicationContext, uri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build()
                    )
                    setVolume(alarmVolume, alarmVolume)

                    if (repeatCount == -1) {
                        isLooping = true
                    } else {
                        isLooping = false
                        setOnCompletionListener { mp ->
                            remainingPlays--
                            if (remainingPlays > 0) {
                                try {
                                    mp.start()
                                } catch (e: Exception) {
                                    stopAudioPlayback()
                                }
                            } else {
                                stopAudioPlayback()
                            }
                        }
                    }

                    prepare()
                    start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (uri.toString() != defaultUriString) {
                    startAudio(Uri.parse(defaultUriString))
                }
            }
        }

        startAudio(alarmUri)

        if (alarmDuration != -1) {
            lifecycleScope.launch {
                val durationInMillis = alarmDuration * 60 * 1000L
                delay(durationInMillis)
                stopAlarmAndFinish()
            }
        }

        setContent {
            CalendarioTheme(darkTheme = true) {
                AlarmScreen(
                    title = eventTitle,
                    onStop = { stopAlarmAndFinish() },
                    onSnooze = { minutes -> snoozeAlarm(minutes) }
                )
            }
        }
    }

    private fun stopAudioPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAlarmAndFinish() {
        stopAudioPlayback()
        finish()
    }

    private fun snoozeAlarm(minutes: Int) {
        stopAudioPlayback()

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarmIntent = Intent(applicationContext, AlarmReceiver::class.java).apply {
            putExtra("EVENT_TITLE", eventTitle)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            (eventTitle + "SNOOZE").hashCode(),
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeTime = System.currentTimeMillis() + (minutes * 60 * 1000)
        val alarmClockInfo = AlarmManager.AlarmClockInfo(snoozeTime, pendingIntent)
        alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)

        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioPlayback()
    }
}

@Composable
fun AlarmScreen(title: String, onStop: () -> Unit, onSnooze: (Int) -> Unit) {
    var showSnoozeOptions by remember { mutableStateOf(false) }
    var showCustomInput by remember { mutableStateOf(false) }
    var customHoursText by remember { mutableStateOf("") }
    var customMinutesText by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 8.dp,
                modifier = Modifier.size(90.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_flash_calendar),
                        contentDescription = "FlashCalendar Logo",
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Flash", style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic), color = Color(0xFFFFB300))
                Text("Calendar", style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), color = Color.White)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF121212)),
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text("DESLIGAR ALARME", fontWeight = FontWeight.Black, fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { showSnoozeOptions = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB300)),
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp)
            ) {
                Text("ADIAR (SNOOZE)", fontWeight = FontWeight.Bold)
            }
        }

        if (showSnoozeOptions) {
            AlertDialog(
                onDismissRequest = { showSnoozeOptions = false },
                title = { Text("Adiar alarme") },
                text = {
                    Column {
                        listOf(5, 10, 15, 30).forEach { minutes ->
                            TextButton(
                                onClick = { onSnooze(minutes) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("$minutes minutos") }
                        }

                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                        TextButton(
                            onClick = { showCustomInput = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Tempo Personalizado...", color = Color(0xFFFFB300)) }

                        if (showCustomInput) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = customHoursText,
                                    onValueChange = { customHoursText = it },
                                    label = { Text("Horas") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = customMinutesText,
                                    onValueChange = { customMinutesText = it },
                                    label = { Text("Minutos") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (showCustomInput) {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF121212)),
                            onClick = {
                                val hours = customHoursText.toIntOrNull() ?: 0
                                val mins = customMinutesText.toIntOrNull() ?: 0
                                val totalMins = (hours * 60) + mins
                                if (totalMins > 0) {
                                    onSnooze(totalMins)
                                }
                            }
                        ) { Text("Confirmar", fontWeight = FontWeight.Bold) }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showSnoozeOptions = false
                        showCustomInput = false
                        customHoursText = ""
                        customMinutesText = ""
                    }) { Text("Cancelar") }
                }
            )
        }
    }
}
