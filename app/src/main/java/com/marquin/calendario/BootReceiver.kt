package com.marquin.calendario

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "BootReceiver acionado com a ação: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            try {
                // 1. Reagenda todos os Alertas Recorrentes Diários (Beber Água, etc)
                val recurringAlarms = loadRecurringAlarms(context)
                rescheduleAllRecurringAlarms(context, recurringAlarms)
                Log.d("BootReceiver", "${recurringAlarms.size} alerta(s) recorrente(s) reagendado(s) após a inicialização.")

                // 2. Reagenda todos os Eventos do Calendário nativo do Android
                val syncWorkRequest = OneTimeWorkRequestBuilder<CalendarWorker>().build()
                WorkManager.getInstance(context.applicationContext).enqueue(syncWorkRequest)
                Log.d("BootReceiver", "Trabalho de sincronização de eventos do calendário agendado com sucesso.")
            } catch (e: Exception) {
                Log.e("BootReceiver", "Erro ao reagendar alertas na inicialização", e)
            }
        }
    }
}
