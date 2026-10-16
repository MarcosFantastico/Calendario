package com.marquin.calendario

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.marquin.calendario.ui.theme.CalendarioTheme

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val sharedPrefs = getSharedPreferences("calendario_prefs", Context.MODE_PRIVATE)
            var themeMode by remember { mutableStateOf(sharedPrefs.getString("app_theme", "system") ?: "system") }

            val isDarkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            CalendarioTheme(darkTheme = isDarkTheme) {
                OptInSettingsScreen(
                    themeMode = themeMode,
                    onThemeModeChanged = { newTheme ->
                        themeMode = newTheme
                        sharedPrefs.edit().putString("app_theme", newTheme).apply()
                    },
                    onBackClick = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptInSettingsScreen(
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurações", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            SettingsScreen(
                themeMode = themeMode,
                onThemeModeChanged = onThemeModeChanged
            )
        }
    }
}
