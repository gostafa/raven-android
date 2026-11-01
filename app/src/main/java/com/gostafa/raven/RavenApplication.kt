package com.gostafa.raven

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import com.gostafa.raven.core.reminders.ReminderCoordinator
import javax.inject.Inject

@HiltAndroidApp
class RavenApplication : Application() {
    @Inject lateinit var reminders: ReminderCoordinator
    override fun onCreate() {
        super.onCreate()
        reminders.start()
    }
}
