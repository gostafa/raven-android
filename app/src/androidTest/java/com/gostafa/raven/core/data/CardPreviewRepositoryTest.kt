package com.gostafa.raven.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gostafa.raven.core.database.*
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.model.*
import com.gostafa.raven.core.scheduler.Fsrs6Scheduler
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CardPreviewRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun repository(db: RavenDatabase) = RoomRavenRepository(db, db.folderDao(), db.cardDao(), db.contentPartDao(), db.mediaDao(), db.reviewHistoryDao(), Fsrs6Scheduler(), MediaStorage(context))

    @Test fun previewsKeepAllOrderedPartsAndReactToEditsAndDeletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, RavenDatabase::class.java).build()
        try {
            val repo = repository(db)
            val folder = repo.saveFolder(null, "Words", "", emptyList())
            repo.saveCard(folder, null, listOf(PartDraft(text = "First"), PartDraft(text = "Match", rememberThis = false), PartDraft(text = "Last")))
            val before = repo.observeCardPreviews(folder).first().single()
            assertEquals(listOf("First", "Match", "Last"), before.parts.map { it.text })
            assertEquals(before, repo.searchCardPreviews("Match").first().single())
            assertEquals(before, repo.searchCardPreviews("Words").first().single())
            repo.rate(before.parts.first().id, Rating.GOOD)
            assertEquals(repo.observeCards(folder).first().single(), repo.observeCardPreviews(folder).first().single().summary)
            withTimeout(5_000) {
                val update = async(start = CoroutineStart.UNDISPATCHED) { repo.observeCardPreviews(folder).first { it.single().parts.first().text == "Last edited" } }
                repo.saveCard(folder, before.summary.id, listOf(PartDraft(id = before.parts.last().id, text = "Last edited"), PartDraft(id = before.parts.first().id, text = "First")))
                assertEquals(listOf("Last edited", "First"), update.await().single().parts.map { it.text })
                val deletion = async(start = CoroutineStart.UNDISPATCHED) { repo.observeCardPreviews(folder).first { it.isEmpty() } }
                repo.deleteCard(before.summary.id)
                assertTrue(deletion.await().isEmpty())
            }
            assertTrue(repo.searchCardPreviews("Match").first().isEmpty())
        } finally { db.close() }
    }

    @Test fun importAndEditorRoundTripPreserveOriginalFilename() = runBlocking {
        val source = File.createTempFile("maple-syrup", ".mp3", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val media = MediaStorage(context)
        val stored = media.import(Uri.fromFile(source), "audio/*")
        val db = Room.inMemoryDatabaseBuilder(context, RavenDatabase::class.java).build()
        try {
            assertEquals(source.name, stored.originalName)
            val repo = repository(db)
            val folder = repo.saveFolder(null, "Audio", "", emptyList())
            repo.saveCard(folder, null, listOf(PartDraft(type = PartType.AUDIO, mediaPath = stored.relativePath, originalName = stored.originalName)))
            val card = repo.observeCardPreviews(folder).first().single()
            assertEquals(source.name, card.parts.single().originalName)
            repo.saveCard(folder, card.summary.id, listOf(PartDraft(id = card.parts.single().id, type = PartType.AUDIO, mediaPath = stored.relativePath, originalName = stored.originalName)))
            assertEquals(source.name, repo.cardWithParts(card.summary.id)!!.parts.single().originalName)
        } finally { db.close(); source.delete(); media.delete(stored.relativePath) }
    }

    @Test fun versionTwoMigrationPreservesCardsHistoryAndReceipts() = runBlocking {
        val name = "card-preview-migration-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, RavenDatabase::class.java, name).build()
        try {
            var repo = repository(db)
            val folder = repo.saveFolder(null, "Words", "Keep", emptyList())
            repo.saveCard(folder, null, listOf(PartDraft(text = "Hello")))
            val card = repo.observeCards(folder).first().single()
            val part = repo.cardWithParts(card.id)!!.parts.single()
            repo.rate(part.id, Rating.GOOD)
            val saved = db.contentPartDao().get(part.id)!!
            db.reminderDao().acknowledge(listOf(ReminderReceiptEntity(part.id, saved.dueAt)))
            db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE archive_states")
                removeOriginalNameForMigration(it)
                it.version = 2
            }
            db = Room.databaseBuilder(context, RavenDatabase::class.java, name).addMigrations(MIGRATION_2_3, MIGRATION_3_4).build()
            repo = repository(db)
            assertEquals(saved, db.contentPartDao().get(part.id))
            assertNull(repo.observeCardPreviews(folder).first().single().parts.single().originalName)
            assertEquals(1, db.reviewHistoryDao().observeForPart(part.id).first().size)
            assertEquals("Keep", db.folderDao().get(folder)!!.description)
            assertTrue(db.reminderDao().pendingDue(Long.MAX_VALUE).isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

/** Rebuild the pre-v3 table without requiring SQLite DROP COLUMN (unavailable on older Android). */
internal fun removeOriginalNameForMigration(db: SQLiteDatabase) {
    db.setForeignKeyConstraintsEnabled(false)
    val schema = db.rawQuery("SELECT sql FROM sqlite_master WHERE name = 'content_parts'", null).use {
        it.moveToFirst(); it.getString(0)
    }
    val columns = db.rawQuery("PRAGMA table_info(content_parts)", null).use {
        buildList { while (it.moveToNext()) if (it.getString(1) != "originalName") add("`" + it.getString(1) + "`") }.joinToString(",")
    }
    db.execSQL(schema.replaceFirst("`content_parts`", "`legacy_content_parts`").replace(", `originalName` TEXT", ""))
    db.execSQL("INSERT INTO legacy_content_parts ($columns) SELECT $columns FROM content_parts")
    db.execSQL("DROP TABLE content_parts")
    db.execSQL("ALTER TABLE legacy_content_parts RENAME TO content_parts")
    db.execSQL("CREATE INDEX index_content_parts_cardId_orderIndex ON content_parts(cardId, orderIndex)")
    db.execSQL("CREATE INDEX index_content_parts_rememberThis_dueAt ON content_parts(rememberThis, dueAt)")
}
