package com.marquin.calendario

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.marquin.calendario.ui.theme.CalendarioTheme

class OverlayAlarmService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var mediaPlayer: MediaPlayer? = null
    private var eventTitle: String = "Alerta"

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        eventTitle = intent.getStringExtra("EVENT_TITLE") ?: "Alerta"
        val customUriString = intent.getStringExtra("SOUND_URI")
        val repeatCount = intent.getIntExtra("EVENT_REPEATS", 1)

        val sharedPrefs = getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
        val defaultUriString = "android.resource://$packageName/${R.raw.google_pixel_ringtone}"
        val alarmVolume = sharedPrefs.getFloat("alarm_volume", 1.0f)

        val alarmUri: Uri = try {
            Uri.parse(customUriString ?: defaultUriString)
        } catch (e: Exception) {
            Uri.parse(defaultUriString)
        }

        // Toca áudio com volume e repetição
        startAudio(alarmUri, alarmVolume, repeatCount, defaultUriString)

        // Exibe janela flutuante se permissão estiver concedida
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            showFloatingWindow()
        }

        return START_NOT_STICKY
    }

    private fun startAudio(uri: Uri, volume: Float, repeatCount: Int, defaultUriString: String) {
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
                setVolume(volume, volume)

                if (repeatCount == -1) {
                    isLooping = true
                } else {
                    isLooping = false
                    setOnCompletionListener { mp ->
                        remainingPlays--
                        if (remainingPlays > 0) {
                            try { mp.start() } catch (e: Exception) { stopAudioPlayback() }
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
                startAudio(Uri.parse(defaultUriString), volume, repeatCount, defaultUriString)
            }
        }
    }

    private fun showFloatingWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayAlarmService)
            setViewTreeViewModelStoreOwner(this@OverlayAlarmService)
            setViewTreeSavedStateRegistryOwner(this@OverlayAlarmService)

            setContent {
                CalendarioTheme(darkTheme = true) {
                    FloatingAlarmCard(
                        title = eventTitle,
                        onStop = { stopAlarmAndDismiss() },
                        onSnooze = { minutes -> snoozeAndDismiss(minutes) }
                    )
                }
            }
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAudioPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {}
    }

    private fun stopAlarmAndDismiss() {
        stopAudioPlayback()
        removeFloatingWindow()
        stopSelf()
    }

    private fun snoozeAndDismiss(minutes: Int) {
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

        removeFloatingWindow()
        stopSelf()
    }

    private fun removeFloatingWindow() {
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        stopAudioPlayback()
        removeFloatingWindow()
    }
}

@Composable
fun FloatingAlarmCard(
    title: String,
    onStop: () -> Unit,
    onSnooze: (Int) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_flash_calendar),
                        contentDescription = "Logo",
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Flash", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic), color = Color(0xFFFFB300))
                    Text("Calendar", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), color = Color.White)
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFB300).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "⚡ Flutuante",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFFFB300),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onSnooze(10) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB300)),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Adiar 10m", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF121212)),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("DESLIGAR", fontWeight = FontWeight.Black, fontSize = 13.sp)
                }
            }
        }
    }
}
