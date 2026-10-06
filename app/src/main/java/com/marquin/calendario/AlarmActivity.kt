package com.marquin.calendario
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
class AlarmActivity : ComponentActivity() {

    // Substituímos o Ringtone pelo MediaPlayer para ter controle total de volume e duração
    private var mediaPlayer: MediaPlayer? = null
    private var eventTitle: String = "Evento"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Força a tela a acender e quebrar o bloqueio
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

        // Desliga a notificação para que apenas a tela principal toque a música
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(eventTitle.hashCode())

        // ------ LEITURA DAS CONFIGURAÇÕES ------
        val sharedPrefs = getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
        val customUriString = sharedPrefs.getString("custom_alarm_uri", null)
        val alarmVolume = sharedPrefs.getFloat("alarm_volume", 1.0f) // Vai de 0.0 a 1.0
        val alarmDuration = sharedPrefs.getInt("alarm_duration", 5) // Em minutos

        var alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        if (customUriString != null) {
            try {
                alarmUri = Uri.parse(customUriString)
            } catch (e: Exception) {}
        }
        // ------ CONFIGURAÇÃO DO MEDIAPLAYER ------
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                         .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                // Aplica o volume definido pelo usuário no Slider das configurações!
                setVolume(alarmVolume, alarmVolume)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // ------ LÓGICA DE DURAÇÃO (AUTO-DESLIGAR) ------
        // Se a duração não for infinita (-1), agendamos o encerramento
        if (alarmDuration != -1) {
            lifecycleScope.launch {
                val durationInMillis = alarmDuration * 60 * 1000L
                delay(durationInMillis) // Espera o tempo configurado
                stopAlarm() // Desliga a música e fecha a tela automaticamente
            }
        }

        setContent {
            MaterialTheme {
                AlarmScreen(
                    title = eventTitle,
                    onStop = { stopAlarm() },
                    onSnooze = { minutes -> snoozeAlarm(minutes) }
                )
            }
        }
    }

    private fun stopAlarm() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        finish()
    }

    private fun snoozeAlarm(minutes: Int) {
        stopAlarm() // Já cuida de parar o MediaPlayer e liberar a memória

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

        // A ARMA SECRETA: setAlarmClock ignora o Doze Mode e qualquer bloqueio de bateria!
        val alarmClockInfo = AlarmManager.AlarmClockInfo(snoozeTime, pendingIntent)
        alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {}
    }
}

@Composable
fun AlarmScreen(title: String, onStop: () -> Unit, onSnooze: (Int) -> Unit) {
    var showSnoozeOptions by remember { mutableStateOf(false) }
    var showCustomInput by remember { mutableStateOf(false) }
    var customMinutesText by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.error
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "ALARME!", style = MaterialTheme.typography.displayLarge, color = Color.White)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = title, style = MaterialTheme.typography.headlineMedium, color = Color.White)

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier.size(width = 200.dp, height = 60.dp)
            ) {
                Text("PARAR", color = Color.Red, style = MaterialTheme.typography.titleLarge)
            }

            Spacer(modifier = Modifier.height(24.dp))

            TextButton(
                onClick = { showSnoozeOptions = true },
                modifier = Modifier.size(width = 200.dp, height = 50.dp)
            ) {
                Text("ADIAR (SNOOZE)", color = Color.White, style = MaterialTheme.typography.titleMedium)
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
                        ) { Text("Tempo Personalizado...", color = MaterialTheme.colorScheme.primary) }

                        if (showCustomInput) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customMinutesText,
                                onValueChange = { customMinutesText = it },
                                label = { Text("Minutos") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },
                confirmButton = {
                    if (showCustomInput) {
                        Button(onClick = {
                            val mins = customMinutesText.toIntOrNull()
                            if (mins != null && mins > 0) {
                                onSnooze(mins)
                            }
                        }) { Text("Confirmar") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showSnoozeOptions = false
                        showCustomInput = false
                        customMinutesText = ""
                    }) { Text("Cancelar") }
                }
            )
        }
    }
}