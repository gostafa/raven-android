package com.gostafa.raven.core.reminders

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gostafa.raven.core.data.RoomRavenRepository
import com.gostafa.raven.core.database.RavenDatabase
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.model.PartDraft
import com.gostafa.raven.core.settings.SettingsRepository
import com.gostafa.raven.core.scheduler.Fsrs6Scheduler
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    @Test fun postsPerCardWithRavenSoundAndSuppressesActiveCardWithoutRepeating() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // The application's recovery worker uses the on-disk DB and would cancel
        // notifications belonging to this test's separate in-memory DB.
        val work = androidx.work.WorkManager.getInstance(context)
        work.cancelUniqueWork("review-reminder-recovery").result.get()
        work.cancelUniqueWork("review-reminder-reconcile").result.get()
        (context.applicationContext as com.gostafa.raven.RavenApplication).reminders.reconcile()
        val db = Room.inMemoryDatabaseBuilder(context, RavenDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
        val active = ActiveReview()
        val reminders = ReminderCoordinator(context, db, settings, active)
        val manager = context.getSystemService(NotificationManager::class.java)
        val repo = RoomRavenRepository(db, db.folderDao(), db.cardDao(), db.contentPartDao(), db.mediaDao(), db.reviewHistoryDao(), Fsrs6Scheduler(), MediaStorage(context))
        val cardIds = mutableListOf<String>()
        try {
            val folder = repo.saveFolder(null, "Reminder test", "", emptyList())
            repo.saveCard(folder, null, listOf(PartDraft(text = "A"), PartDraft(text = "B")))
            repo.saveCard(folder, null, listOf(PartDraft(text = "C")))
            cardIds.addAll(repo.observeCards(folder).first().map { it.id })
            reminders.reconcile()
            // NotificationManager publishes asynchronously; wait for delivery before checking it.
            val notices = withTimeout(5_000) {
                var posted = manager.activeNotifications.filter { it.tag in cardIds.map { id -> "review/$id" } }
                while (posted.size != 2) {
                    delay(50)
                    posted = manager.activeNotifications.filter { it.tag in cardIds.map { id -> "review/$id" } }
                }
                posted
            }
            assertEquals(2, notices.size)
            val channel = manager.getNotificationChannel(ReminderCoordinator.CHANNEL_ID)
            assertEquals("android.resource://${context.packageName}/raw/raven", channel.sound.toString())
            assertTrue(db.reminderDao().pendingDue(Long.MAX_VALUE).isEmpty())
            val posted = notices.associate { it.tag to it.postTime }
            reminders.reconcile()
            assertEquals(posted, manager.activeNotifications.filter { it.tag in posted.keys }.associate { it.tag to it.postTime })

            val part = db.contentPartDao().forCard(cardIds.first()).first()
            db.contentPartDao().upsert(part.copy(dueAt = part.dueAt - 1))
            active.show(part.cardId)
            reminders.reconcile()
            withTimeout(5_000) { while (manager.activeNotifications.any { it.tag == "review/${part.cardId}" }) delay(50) }
            assertTrue(manager.activeNotifications.none { it.tag == "review/${part.cardId}" })
            assertTrue(db.reminderDao().pendingDue(Long.MAX_VALUE).isEmpty())
            active.show(null)
            reminders.reconcile()
            assertTrue(manager.activeNotifications.none { it.tag == "review/${part.cardId}" })
            settings.setRemindersEnabled(false)
            reminders.reconcile()
            withTimeout(5_000) { while (manager.activeNotifications.any { it.tag in cardIds.map { id -> "review/$id" } }) delay(50) }
            assertTrue(manager.activeNotifications.none { it.tag in cardIds.map { id -> "review/$id" } })
        } finally {
            cardIds.forEach { manager.cancel("review/$it", 1) }
            settings.setRemindersEnabled(false)
            reminders.reconcile()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
            file.delete()
        }
    }
}
