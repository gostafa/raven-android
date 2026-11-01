package com.gostafa.raven.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gostafa.raven.core.database.*
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.model.*
import com.gostafa.raven.core.scheduler.Fsrs6Scheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class CardTransferTest {
    private lateinit var db: RavenDatabase
    private lateinit var repo: RoomRavenRepository
    private lateinit var storage: MediaStorage
    private lateinit var source: String
    private lateinit var destination: String
    private val fixtureFiles = mutableListOf<File>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, RavenDatabase::class.java).build()
        storage = MediaStorage(context)
        repo = RoomRavenRepository(db, db.folderDao(), db.cardDao(), db.contentPartDao(), db.mediaDao(), db.reviewHistoryDao(), Fsrs6Scheduler(), storage)
        source = repo.saveFolder(null, "Source", "", emptyList())
        destination = repo.saveFolder(null, "Destination", "", emptyList())
    }
    @After fun cleanup() = runBlocking {
        repo.observeFolders().first().forEach { repo.deleteFolder(it.id) }
        db.close()
        fixtureFiles.forEach { it.delete() }
    }
    private suspend fun createCard(media: Boolean = false): CardReference {
        val drafts = mutableListOf(PartDraft(text = "Remember me"), PartDraft(text = "Context", rememberThis = false))
        if (media) {
            val file = File.createTempFile("transfer-", ".png", File(context.filesDir, "media").apply { mkdirs() }).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            fixtureFiles += file
            val path = "media/${file.name}"
            repo.saveMedia(path, "image/png", file.length())
            drafts += PartDraft(type = PartType.IMAGE, mediaPath = path, mimeType = "image/png", originalName = "Original.png")
        }
        repo.saveCard(source, null, drafts)
        val id = repo.observeCards(source).first().first { summary -> summary.id !in created }.id
        created += id
        return CardReference(source, id)
    }
    private val created = mutableSetOf<String>()

    @Test fun moveKeepsIdentityHistoryAndScheduleAndSameFolderIsNoop() = runBlocking {
        val ref = createCard()
        val part = db.contentPartDao().forCard(ref.cardId).first()
        repo.rate(part.id, Rating.GOOD)
        val before = repo.cardWithParts(ref.cardId)!!
        repo.transferCards(CardTransferInput(TransferOperation.MOVE, listOf(ref, ref), source))
        assertEquals(before, repo.cardWithParts(ref.cardId))
        val result = repo.transferCards(CardTransferInput(TransferOperation.MOVE, listOf(ref), destination))
        assertEquals(CardReference(destination, ref.cardId), result.entries.single().destination)
        assertTrue(repo.observeCards(source).first().isEmpty())
        assertEquals(before.parts, repo.cardWithParts(ref.cardId)!!.parts)
        assertEquals(1, db.reviewHistoryDao().forPart(part.id).size)
    }

    @Test fun copyOwnsMediaAndRetainsProgressArchiveStateAndPartOrder() = runBlocking {
        val ref = createCard(media = true)
        val initial = repo.cardWithParts(ref.cardId)!!
        repo.rate(initial.parts.first().id, Rating.GOOD)
        val original = repo.cardWithParts(ref.cardId)!!
        db.archiveStateDao().upsert(ArchiveStateEntity(original.parts.first().id, "portable schedule", "portable reviews"))
        val result = repo.transferCards(CardTransferInput(TransferOperation.COPY, listOf(ref), destination))
        val copy = repo.cardWithParts(result.entries.single().destination.cardId)!!
        assertNotEquals(original.card.id, copy.card.id)
        assertEquals(original.parts.map { it.text }, copy.parts.map { it.text })
        assertEquals(original.parts.map { it.fsrsStateJson }, copy.parts.map { it.fsrsStateJson })
        assertEquals(original.parts.map { it.rememberThis }, copy.parts.map { it.rememberThis })
        assertTrue(original.parts.map { it.id }.intersect(copy.parts.map { it.id }.toSet()).isEmpty())
        assertEquals(1, db.reviewHistoryDao().forPart(copy.parts.first().id).size)
        assertEquals("portable schedule", db.archiveStateDao().get(copy.parts.first().id)!!.scheduleJson)
        val originalMedia = original.parts.last().mediaPath!!
        val copiedMedia = copy.parts.last().mediaPath!!
        assertNotEquals(originalMedia, copiedMedia)
        assertArrayEquals(storage.file(originalMedia).readBytes(), storage.file(copiedMedia).readBytes())
        assertEquals("Original.png", copy.parts.last().originalName)
        repo.deleteCard(ref.cardId)
        assertTrue(storage.file(copiedMedia).isFile)
    }

    @Test fun missingSourceOrDestinationRejectsWholeBatch() = runBlocking {
        val ref = createCard()
        assertTrue(runCatching { repo.transferCards(CardTransferInput(TransferOperation.MOVE, listOf(ref, CardReference(source, "gone")), destination)) }.isFailure)
        assertEquals(source, repo.cardWithParts(ref.cardId)!!.card.folderId)
        repo.deleteFolder(destination)
        assertTrue(runCatching { repo.transferCards(CardTransferInput(TransferOperation.COPY, listOf(ref), destination)) }.isFailure)
        assertEquals(source, repo.cardWithParts(ref.cardId)!!.card.folderId)
    }

    @Test fun databaseFailureRollsBackBatchAndRemovesStagedMedia() = runBlocking {
        val first = createCard(media = true)
        val second = createCard()
        val directory = File(context.filesDir, "media")
        val beforeFiles = directory.list()!!.toSet()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_second_copy BEFORE INSERT ON cards WHEN NEW.folderId = '$destination' AND (SELECT COUNT(*) FROM cards WHERE folderId = '$destination') >= 1 BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { repo.transferCards(CardTransferInput(TransferOperation.COPY, listOf(first, second), destination)) }.isFailure)
        assertTrue(repo.observeCards(destination).first().isEmpty())
        assertEquals(2, repo.observeCards(source).first().size)
        assertEquals(beforeFiles, directory.list()!!.toSet())
    }

    @Test fun selectedReviewDeduplicatesCardsAndExcludesContext() = runBlocking {
        val first = createCard()
        val second = createCard()
        val queue = repo.queueCards(listOf(second.cardId, first.cardId, second.cardId))
        assertEquals(listOf(second.cardId, first.cardId), queue.map { it.content.card.id })
        assertTrue(queue.all { it.prompt.rememberThis })
    }
}
