package com.marquin.calendario

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventTitle = intent.getStringExtra("EVENT_TITLE") ?: "Evento"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Puxamos o som escolhido pelo usuário para tocar na notificação também!
        val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
        val customUriString = sharedPrefs.getString("custom_alarm_uri", null)
        val soundUri = if (customUriString != null) Uri.parse(customUriString) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        // Usamos o hash do som para o Android atualizar o canal se você trocar de música
        val channelId = "ALARM_CHANNEL_${soundUri.hashCode()}"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Alarmes do Calendário",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                // Colocamos o som na notificação para garantir que toque se a tela estiver desbloqueada
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                setSound(soundUri, audioAttributes)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Prepara a tela vermelha
        val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
            putExtra("EVENT_TITLE", eventTitle)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            (eventTitle + System.currentTimeMillis()).hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Monta a notificação blindada
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Alarme: $eventTitle")
            .setContentText("Toque para abrir ou desligar.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()

        notificationManager.notify(eventTitle.hashCode(), notification)

        // Tenta forçar a tela a abrir de forma bruta caso o celular esteja permitindo
        try {
            context.startActivity(fullScreenIntent)
        } catch (e: Exception) {
            // Se o Android bloquear (porque você tá usando o celular), a notificação acima vai tocar alto!
        }
    }
}