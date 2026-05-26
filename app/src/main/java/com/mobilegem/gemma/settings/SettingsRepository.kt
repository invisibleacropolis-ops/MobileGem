package com.mobilegem.gemma.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "app_settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.appSettingsDataStore)

    private object Keys {
        val activeModel = stringPreferencesKey("active_model")
        val backend = stringPreferencesKey("backend")
        val temperature = floatPreferencesKey("temperature")
        val loggingEnabled = booleanPreferencesKey("logging_enabled")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            activeModelFileName = prefs[Keys.activeModel],
            backend = prefs[Keys.backend]
                ?.let { runCatching { InferenceBackend.valueOf(it) }.getOrNull() }
                ?: AppSettings.DEFAULT.backend,
            temperature = prefs[Keys.temperature] ?: AppSettings.DEFAULT.temperature,
            loggingEnabled = prefs[Keys.loggingEnabled] ?: AppSettings.DEFAULT.loggingEnabled,
        )
    }

    suspend fun setActiveModel(fileName: String) =
        dataStore.edit { it[Keys.activeModel] = fileName }

    suspend fun setBackend(backend: InferenceBackend) =
        dataStore.edit { it[Keys.backend] = backend.name }

    suspend fun setTemperature(value: Float) =
        dataStore.edit { it[Keys.temperature] = value }

    suspend fun setLoggingEnabled(value: Boolean) =
        dataStore.edit { it[Keys.loggingEnabled] = value }
}
