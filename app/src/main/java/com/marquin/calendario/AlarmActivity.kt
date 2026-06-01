package com.marquin.calendario

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class AlarmActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null

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

        val eventTitle = intent.getStringExtra("EVENT_TITLE") ?: "Evento"

        // ----------------- LÓGICA DO TOQUE PERSONALIZADO -----------------
        val sharedPrefs = getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
        val customUriString = sharedPrefs.getString("custom_alarm_uri", null)

        var alarmUri: Uri? = null

        // Tenta usar o toque salvo nas configurações
        if (customUriString != null) {
            try {
                alarmUri = Uri.parse(customUriString)
                ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
            } catch (e: Exception) {
                // Se der erro ao ler o arquivo, o alarmUri continua null
            }
        }

        // Se não tiver toque personalizado ou o arquivo foi deletado, usa o padrão do celular
        if (ringtone == null) {
            alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
        }

        ringtone?.play()
        // ------------------------------------------------------------------

        setContent {
            MaterialTheme {
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
                        Text(text = eventTitle, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                        Spacer(modifier = Modifier.height(48.dp))

                        Button(
                            onClick = { stopAlarm() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            modifier = Modifier.size(width = 150.dp, height = 60.dp)
                        ) {
                            Text("PARAR", color = Color.Red, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }

    private fun stopAlarm() {
        ringtone?.stop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        ringtone?.stop()
    }
}