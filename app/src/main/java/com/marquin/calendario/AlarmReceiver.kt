package com.marquin.calendario

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventTitle = intent.getStringExtra("EVENT_TITLE") ?: "Evento"

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "ALARM_CHANNEL"

        // 1. Criar o canal de notificação (Obrigatório em Androids mais novos)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Alarmes do Calendário",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        // 2. Criar a "Intenção" de abrir a nossa AlarmActivity em tela cheia
        val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
            putExtra("EVENT_TITLE", eventTitle)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            eventTitle.hashCode(), // ID único pro alarme
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Construir a Notificação e plugar a Tela Cheia nela
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Um ícone padrão do Android
            .setContentTitle("Alarme do Calendário")
            .setContentText(eventTitle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true) // <--- É ISSO QUE FAZ A MÁGICA
            .build()

        // 4. Disparar!
        notificationManager.notify(eventTitle.hashCode(), notification)
    }
}