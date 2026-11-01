package com.gostafa.raven.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.gostafa.raven.core.data.RavenRepository
import com.gostafa.raven.core.data.RoomRavenRepository
import com.gostafa.raven.core.database.RavenDatabase
import com.gostafa.raven.core.database.MIGRATION_1_2
import com.gostafa.raven.core.database.MIGRATION_2_3
import com.gostafa.raven.core.reminders.ReminderCoordinator
import com.gostafa.raven.core.reminders.ReminderInvalidator
import com.gostafa.raven.core.scheduler.Fsrs6Scheduler
import com.gostafa.raven.core.scheduler.ReviewScheduler
import com.gostafa.raven.core.media.MediaStorage
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.ravenPreferences by preferencesDataStore(name = "raven_preferences")

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindings {
    @Binds
    abstract fun bindReminderInvalidator(coordinator: ReminderCoordinator): ReminderInvalidator
    @Binds
    @Singleton
    abstract fun bindRavenRepository(repository: RoomRavenRepository): RavenRepository
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): RavenDatabase =
        Room.databaseBuilder(context, RavenDatabase::class.java, "raven.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

    @Provides fun folderDao(database: RavenDatabase) = database.folderDao()
    @Provides fun cardDao(database: RavenDatabase) = database.cardDao()
    @Provides fun contentPartDao(database: RavenDatabase) = database.contentPartDao()
    @Provides fun mediaDao(database: RavenDatabase) = database.mediaDao()
    @Provides fun reviewHistoryDao(database: RavenDatabase) = database.reviewHistoryDao()

    @Provides
    @Singleton
    fun scheduler(): ReviewScheduler = Fsrs6Scheduler()

    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> = context.ravenPreferences

}
