package com.gostafa.raven.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import com.gostafa.raven.core.companion.CompanionPosition
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val companionEnabled: Flow<Boolean> = dataStore.data.map { it[COMPANION] ?: false }
    val companionPosition: Flow<CompanionPosition> = dataStore.data.map {
        CompanionPosition(it[COMPANION_RIGHT] ?: true, it[COMPANION_Y] ?: 0.5f)
    }
    suspend fun setCompanionEnabled(enabled: Boolean) { dataStore.edit { it[COMPANION] = enabled } }
    suspend fun setCompanionPosition(position: CompanionPosition) { dataStore.edit {
        it[COMPANION_RIGHT] = position.rightEdge; it[COMPANION_Y] = position.verticalFraction.coerceIn(0f, 1f)
    } }
    val remindersEnabled: Flow<Boolean> = dataStore.data.map { it[REMINDERS] ?: true }
    val reminderPromptSeen: Flow<Boolean> = dataStore.data.map { it[REMINDER_PROMPT] ?: false }
    suspend fun setRemindersEnabled(enabled: Boolean) { dataStore.edit { it[REMINDERS] = enabled } }
    suspend fun markReminderPromptSeen() { dataStore.edit { it[REMINDER_PROMPT] = true } }
    val language: Flow<String> = dataStore.data.map { it[LANGUAGE] ?: "system" }

    suspend fun setLanguage(language: String) {
        dataStore.edit { it[LANGUAGE] = language }
    }

    private companion object {
        val COMPANION = booleanPreferencesKey("floating_companion")
        val COMPANION_RIGHT = booleanPreferencesKey("companion_right_edge")
        val COMPANION_Y = floatPreferencesKey("companion_vertical_fraction")
        val REMINDERS = booleanPreferencesKey("review_reminders")
        val REMINDER_PROMPT = booleanPreferencesKey("reminder_prompt_seen")
        val LANGUAGE = stringPreferencesKey("language")
    }
}
