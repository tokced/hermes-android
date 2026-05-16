package com.hermes.chat

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class AppSettings(
    val apiBaseUrl: String = "https://api.988987.xyz",
    val apiKey: String = "hermes-bridge-secret-key-change-me",
    val darkMode: Boolean = false,
    val streamResponse: Boolean = true,
    val saveSessions: Boolean = true
)

class SettingsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSettings(settings: AppSettings) {
        prefs.edit().apply {
            putString(KEY_API_BASE_URL, settings.apiBaseUrl)
            putString(KEY_API_KEY, settings.apiKey)
            putBoolean(KEY_DARK_MODE, settings.darkMode)
            putBoolean(KEY_STREAM_RESPONSE, settings.streamResponse)
            putBoolean(KEY_SAVE_SESSIONS, settings.saveSessions)
            apply()
        }
    }

    fun loadSettings(): AppSettings {
        return AppSettings(
            apiBaseUrl = prefs.getString(KEY_API_BASE_URL, "https://api.988987.xyz") ?: "https://api.988987.xyz",
            apiKey = prefs.getString(KEY_API_KEY, "hermes-bridge-secret-key-change-me") ?: "",
            darkMode = prefs.getBoolean(KEY_DARK_MODE, false),
            streamResponse = prefs.getBoolean(KEY_STREAM_RESPONSE, true),
            saveSessions = prefs.getBoolean(KEY_SAVE_SESSIONS, true)
        )
    }

    fun isFirstLaunch(): Boolean {
        val first = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        if (first) {
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
        }
        return first
    }

    companion object {
        private const val PREFS_NAME = "hermes_chat_settings"
        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_STREAM_RESPONSE = "stream_response"
        private const val KEY_SAVE_SESSIONS = "save_sessions"
        private const val KEY_FIRST_LAUNCH = "first_launch"
    }
}
