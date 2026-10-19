package com.ugport.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ugport.data.model.AppData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ugport")

class ProfileStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val dataKey = stringPreferencesKey("app_data")

    val data: Flow<AppData> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> decode(prefs[dataKey]) }

    suspend fun update(transform: (AppData) -> AppData) {
        context.dataStore.edit { prefs ->
            prefs[dataKey] = json.encodeToString(transform(decode(prefs[dataKey])))
        }
    }

    private fun decode(raw: String?): AppData {
        if (raw.isNullOrBlank()) return AppData()
        return runCatching { json.decodeFromString<AppData>(raw) }.getOrDefault(AppData())
    }
}
