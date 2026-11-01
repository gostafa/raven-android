package com.gostafa.raven.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val remindersEnabled: Flow<Boolean> = dataStore.data.map { it[REMINDERS] ?: true }
    val reminderPromptSeen: Flow<Boolean> = dataStore.data.map { it[REMINDER_PROMPT] ?: false }
    suspend fun setRemindersEnabled(enabled: Boolean) { dataStore.edit { it[REMINDERS] = enabled } }
    suspend fun markReminderPromptSeen() { dataStore.edit { it[REMINDER_PROMPT] = true } }
    val language: Flow<String> = dataStore.data.map { it[LANGUAGE] ?: "system" }

    suspend fun setLanguage(language: String) {
        dataStore.edit { it[LANGUAGE] = language }
    }

    private companion object {
        val REMINDERS = booleanPreferencesKey("review_reminders")
        val REMINDER_PROMPT = booleanPreferencesKey("reminder_prompt_seen")
        val LANGUAGE = stringPreferencesKey("language")
    }
}
