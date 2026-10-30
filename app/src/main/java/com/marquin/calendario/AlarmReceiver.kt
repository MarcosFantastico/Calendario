package com.marquin.calendario

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventTitle = intent.getStringExtra("EVENT_TITLE") ?: "Alerta"
        val eventId = intent.getLongExtra("EVENT_ID", -1L)
        val isRecurring = intent.getBooleanExtra("IS_RECURRING", false)
        val recurringAlarmId = intent.getStringExtra("RECURRING_ALARM_ID")

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sharedPrefs = context.getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
        val defaultUriString = "android.resource://${context.packageName}/${R.raw.google_pixel_ringtone}"

        var customUriString: String? = null
        var isFullScreen = true
        var repeatCount = 1

        if (isRecurring && recurringAlarmId != null) {
            val recurringAlarms = loadRecurringAlarms(context)
            val alarm = recurringAlarms.find { it.id == recurringAlarmId }
            if (alarm != null) {
                customUriString = alarm.soundUri ?: sharedPrefs.getString("custom_alarm_uri", defaultUriString)
                isFullScreen = alarm.isFullScreen
                repeatCount = alarm.repeatCount

                // Reagenda automaticamente a próxima ocorrência para o ciclo periódico!
                if (alarm.isEnabled) {
                    scheduleNextTriggerForRecurringAlarm(context, alarm)
                }
            }
        } else if (eventId != -1L) {
            customUriString = sharedPrefs.getString("event_ringtone_$eventId", null) ?: sharedPrefs.getString("custom_alarm_uri", defaultUriString)
            isFullScreen = sharedPrefs.getBoolean("event_fullscreen_$eventId", true)
            repeatCount = sharedPrefs.getInt("event_repeats_$eventId", 1)
        } else {
            customUriString = sharedPrefs.getString("custom_alarm_uri", defaultUriString)
        }

        val soundUri = try {
            Uri.parse(customUriString ?: defaultUriString)
        } catch (e: Exception) {
            e.printStackTrace()
            Uri.parse(defaultUriString)
        }

        val channelId = if (isFullScreen) "ALARM_CHANNEL_SILENT_V1" else "ALARM_CHANNEL_SOUND_${soundUri.hashCode()}"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingChannel = notificationManager.getNotificationChannel(channelId)
            if (existingChannel == null) {
                val channel = NotificationChannel(
                    channelId,
                    if (isFullScreen) "Alarmes de Tela Cheia" else "Alertas de Notificação",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    if (!isFullScreen) {
                        val audioAttributes = AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build()
                        setSound(soundUri, audioAttributes)
                    } else {
                        setSound(null, null)
                    }
                    enableVibration(true)
                    enableLights(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)
            }
        }

        val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
            putExtra("EVENT_TITLE", eventTitle)
            putExtra("EVENT_ID", eventId)
            putExtra("SOUND_URI", customUriString)
            putExtra("EVENT_REPEATS", repeatCount)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            (eventTitle + System.currentTimeMillis()).hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Lembrete: $eventTitle")
            .setContentText("Alerta agendado no FlashCalendar")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)

        if (isFullScreen) {
            notificationBuilder.setFullScreenIntent(fullScreenPendingIntent, true)
        }

        notificationManager.notify((eventTitle + System.currentTimeMillis()).hashCode(), notificationBuilder.build())

        if (isFullScreen) {
            val hasOverlayPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)
            if (hasOverlayPermission) {
                val overlayIntent = Intent(context, OverlayAlarmService::class.java).apply {
                    putExtra("EVENT_TITLE", eventTitle)
                    putExtra("SOUND_URI", customUriString)
                    putExtra("EVENT_REPEATS", repeatCount)
                }
                try {
                    context.startService(overlayIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    context.startActivity(fullScreenIntent)
                }
            } else {
                try {
                    context.startActivity(fullScreenIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
