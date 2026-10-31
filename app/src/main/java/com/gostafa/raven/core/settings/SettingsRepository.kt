package com.gostafa.raven.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val language: Flow<String> = dataStore.data.map { it[LANGUAGE] ?: "system" }

    suspend fun setLanguage(language: String) {
        dataStore.edit { it[LANGUAGE] = language }
    }

    private companion object {
        val LANGUAGE = stringPreferencesKey("language")
    }
}
