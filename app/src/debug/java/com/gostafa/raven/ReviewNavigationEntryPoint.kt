package com.gostafa.raven

import com.gostafa.raven.core.data.RavenRepository
import com.gostafa.raven.core.database.RavenDatabase
import com.gostafa.raven.core.reminders.ReminderCoordinator
import com.gostafa.raven.core.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// Keep instrumentation dependency access out of release builds.
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ReviewNavigationEntryPoint {
    fun repository(): RavenRepository
    fun database(): RavenDatabase
    fun settings(): SettingsRepository
    fun reminders(): ReminderCoordinator
}

