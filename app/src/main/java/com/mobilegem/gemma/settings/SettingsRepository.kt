package com.mobilegem.gemma.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "app_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val activeModel = stringPreferencesKey("active_model")
        val backend = stringPreferencesKey("backend")
        val temperature = floatPreferencesKey("temperature")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            activeModelFileName = prefs[Keys.activeModel],
            backend = prefs[Keys.backend]
                ?.let { runCatching { InferenceBackend.valueOf(it) }.getOrNull() }
                ?: AppSettings.DEFAULT.backend,
            temperature = prefs[Keys.temperature] ?: AppSettings.DEFAULT.temperature,
        )
    }

    suspend fun setActiveModel(fileName: String) =
        context.dataStore.edit { it[Keys.activeModel] = fileName }

    suspend fun setBackend(backend: InferenceBackend) =
        context.dataStore.edit { it[Keys.backend] = backend.name }

    suspend fun setTemperature(value: Float) =
        context.dataStore.edit { it[Keys.temperature] = value }
}
