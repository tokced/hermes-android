package com.hermes.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.hermes.chat.ui.ChatScreen
import com.hermes.chat.ui.SettingsScreen
import com.hermes.chat.ui.theme.HermesChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settingsManager = remember { SettingsManager(this) }
            val settings = remember { settingsManager.loadSettings() }

            HermesChatTheme(darkTheme = settings.darkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf("main") }

                    when (currentScreen) {
                        "settings" -> SettingsScreen(onBack = { currentScreen = "main" })
                        "main" -> ChatScreen(
                            sessionManager = SessionManager(this),
                            onNavigateToSettings = { currentScreen = "settings" }
                        )
                    }
                }
            }
        }
    }
}
