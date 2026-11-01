package com.gostafa.raven.core.reminders

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gostafa.raven.core.data.RoomRavenRepository
import com.gostafa.raven.core.data.removeOriginalNameForMigration
import com.gostafa.raven.core.database.*
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.model.*
import com.gostafa.raven.core.scheduler.Fsrs6Scheduler
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderDatabaseTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun repository(db: RavenDatabase) = RoomRavenRepository(db, db.folderDao(), db.cardDao(), db.contentPartDao(), db.mediaDao(), db.reviewHistoryDao(), Fsrs6Scheduler(), MediaStorage(context))

    @Test fun receiptsDeduplicateUntilRescheduledAndCascadeOnDelete() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, RavenDatabase::class.java).build()
        try {
            val repo = repository(db)
            val folder = repo.saveFolder(null, "Words", "", emptyList())
            repo.saveCard(folder, null, listOf(PartDraft(text = "A"), PartDraft(text = "B"), PartDraft(text = "Hint", rememberThis = false)))
            val card = repo.observeCards(folder).first().single().id
            val dao = db.reminderDao()
            val due = dao.pendingDue(Long.MAX_VALUE)
            assertEquals(2, due.size)
            dao.acknowledge(due.map { ReminderReceiptEntity(it.partId, it.dueAt) })
            assertTrue(dao.pendingDue(Long.MAX_VALUE).isEmpty())
            assertNull(dao.nextUnhandledDue())
            val part = db.contentPartDao().get(due.first().partId)!!
            db.contentPartDao().upsert(part.copy(dueAt = part.dueAt + 100))
            assertEquals(part.id, dao.pendingDue(Long.MAX_VALUE).single().partId)
            assertEquals(part.dueAt + 100, dao.nextUnhandledDue())
            repo.deleteCard(card)
            assertTrue(dao.pendingDue(Long.MAX_VALUE).isEmpty())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM reminder_receipts").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        } finally { db.close() }
    }

    @Test fun dueCardExcludesFutureAndContextAndDueCountsRefreshWithoutWrites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, RavenDatabase::class.java).build()
        try {
            val repo = repository(db)
            val folder = repo.saveFolder(null, "Words", "", emptyList())
            repo.saveCard(folder, null, listOf(PartDraft(text = "A"), PartDraft(text = "B"), PartDraft(text = "Hint", rememberThis = false)))
            val card = repo.observeCards(folder).first().single().id
            val parts = db.contentPartDao().forCard(card)
            db.contentPartDao().upsert(parts[1].copy(dueAt = System.currentTimeMillis() + 60_000))
            assertEquals(listOf(parts[0].id), repo.queueDueCard(card).map { it.prompt.id })
            assertEquals(2, repo.queueCard(card).size)
            db.contentPartDao().upsert(parts[0].copy(dueAt = System.currentTimeMillis() + 500))
            assertEquals(0, repo.observeCards(folder).first().single().dueCount)
            withTimeout(5_000) {
                val folders = async(start = CoroutineStart.UNDISPATCHED) { repo.observeFolders().first { it.single().dueCount == 1 } }
                val cards = async(start = CoroutineStart.UNDISPATCHED) { repo.observeCards(folder).first { it.single().dueCount == 1 } }
                assertEquals(1, folders.await().single().dueCount)
                assertEquals(1, cards.await().single().dueCount)
            }
            assertTrue(repo.queueDueCard("missing").isEmpty())
        } finally { db.close() }
    }

    @Test fun versionOneMigrationPreservesContentAndHistory() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, RavenDatabase::class.java, name).build()
        try {
            var repo = repository(db)
            val folder = repo.saveFolder(null, "Words", "Keep me", listOf("English"))
            repo.saveCard(folder, null, listOf(PartDraft(text = "Hello")))
            val card = repo.observeCards(folder).first().single().id
            val part = db.contentPartDao().forCard(card).single().id
            repo.rate(part, Rating.GOOD)
            val before = db.contentPartDao().get(part)!!
            db.close()
            // v1 has exactly the same existing tables; only the receipt table is new in v2.
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { legacy ->
                legacy.execSQL("DROP TABLE reminder_receipts")
                removeOriginalNameForMigration(legacy)
                legacy.version = 1
            }
            db = Room.databaseBuilder(context, RavenDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
            repo = repository(db)
            assertEquals(before, db.contentPartDao().get(part))
            assertEquals("Keep me", db.folderDao().get(folder)!!.description)
            assertEquals(1, db.reviewHistoryDao().observeForPart(part).first().size)
            assertEquals(1, db.reminderDao().pendingDue(Long.MAX_VALUE).size)
            assertEquals("Hello", repo.cardWithParts(card)!!.parts.single().text)
            db.reminderDao().acknowledge(listOf(ReminderReceiptEntity(part, before.dueAt)))
            db.close()
            db = Room.databaseBuilder(context, RavenDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
            assertTrue(db.reminderDao().pendingDue(Long.MAX_VALUE).isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
