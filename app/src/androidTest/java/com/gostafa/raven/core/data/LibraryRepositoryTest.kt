package com.gostafa.raven.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gostafa.raven.core.database.RavenDatabase
import com.gostafa.raven.core.database.ReviewHistoryEntity
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.model.PartDraft
import com.gostafa.raven.core.model.PartType
import com.gostafa.raven.core.model.Rating
import com.gostafa.raven.core.scheduler.Fsrs6Scheduler
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {
    private lateinit var db: RavenDatabase
    private lateinit var repository: RoomRavenRepository

    @Before fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RavenDatabase::class.java).build()
        repository = RoomRavenRepository(db, db.folderDao(), db.cardDao(), db.contentPartDao(), db.mediaDao(), db.reviewHistoryDao(), Fsrs6Scheduler(), MediaStorage(context))
    }

    @After fun closeDatabase() = db.close()

    @Test fun tagsRoundTripWithoutReplacingFolderOrCards() = runBlocking {
        val id = repository.saveFolder(null, " English ", " Test ", listOf(" Language ", "English", "language", ""))
        repository.saveCard(id, null, listOf(PartDraft(text = "Hello")))
        val created = repository.observeFolders().first().single()
        assertEquals(listOf("Language", "English"), created.tags)
        assertEquals("English", created.name)
        val createdAt = db.folderDao().get(id)!!.createdAt
        repository.saveFolder(id, "Renamed", "Context", listOf("Travel"))
        val edited = repository.observeFolders().first().single()
        assertEquals(id, edited.id)
        assertEquals(1, edited.cardCount)
        assertEquals(listOf("Travel"), edited.tags)
        assertEquals(createdAt, db.folderDao().get(id)!!.createdAt)
        repository.saveFolder(id, "Renamed", "", emptyList())
        assertTrue(repository.observeFolders().first().single().tags.isEmpty())
    }

    @Test fun invalidCardContentIsRejectedWithoutWritingCards() = runBlocking {
        val folder = repository.saveFolder(null, "Cards", "", emptyList())
        listOf(emptyList(), listOf(PartDraft(text = " \n ")), listOf(PartDraft(type = PartType.IMAGE)),
            listOf(PartDraft(type = PartType.AUDIO, mediaPath = "media/missing.audio"))).forEach { drafts ->
            val result = runCatching { repository.saveCard(folder, null, drafts) }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(repository.observeCards(folder).first().isEmpty())
    }

    @Test fun deletedCardCannotBeRecreatedBySavingStaleEditor() = runBlocking {
        val folder = repository.saveFolder(null, "Cards", "", emptyList())
        repository.saveCard(folder, null, listOf(PartDraft(text = "A")))
        val card = repository.observeCards(folder).first().single()
        repository.deleteCard(card.id)
        assertTrue(runCatching { repository.saveCard(folder, card.id, listOf(PartDraft(text = "Edited"))) }.isFailure)
        assertTrue(repository.observeCards(folder).first().isEmpty())
    }

    @Test fun cleanupDeletesOnlyUnreferencedMedia() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MediaStorage(context)
        val folder = repository.saveFolder(null, "Cards", "", emptyList())
        val directory = java.io.File(context.filesDir, "media").apply { mkdirs() }
        val used = java.io.File.createTempFile("used-", ".png", directory).apply { writeText("image") }
        val unused = java.io.File.createTempFile("unused-", ".png", directory).apply { writeText("image") }
        val usedPath = "media/${used.name}"
        val unusedPath = "media/${unused.name}"
        try {
            repository.saveMedia(usedPath, "image/png", used.length())
            repository.saveMedia(unusedPath, "image/png", unused.length())
            repository.saveCard(folder, null, listOf(PartDraft(type = PartType.IMAGE, mediaPath = usedPath)))
            repository.discardMedia(listOf(usedPath, unusedPath))
            assertTrue(storage.file(usedPath).exists())
            assertTrue(db.mediaDao().getByPath(usedPath) != null)
            assertTrue(!storage.file(unusedPath).exists())
            assertEquals(null, db.mediaDao().getByPath(unusedPath))
        } finally { used.delete(); unused.delete() }
    }

    @Test fun recallUpdatesReactivelyForFolderCardsAndSearch() = runBlocking {
        withTimeout(10_000) {
            val folder = repository.saveFolder(null, "English", "", emptyList())
            repository.saveCard(folder, null, listOf(PartDraft(text = "Hello")))
            val card = repository.observeCards(folder).first().single()
            val part = db.contentPartDao().forCard(card.id).single()
            assertEquals(0, card.recallPercent)
            val folderUpdate = async(start = CoroutineStart.UNDISPATCHED) {
                repository.observeFolders().first { it.single().completedCardCount == 1 }
            }
            val cardUpdate = async(start = CoroutineStart.UNDISPATCHED) {
                repository.observeCards(folder).first { it.single().recallPercent == 100 }
            }
            val searchUpdate = async(start = CoroutineStart.UNDISPATCHED) {
                repository.searchCards("Hello").first { it.single().recallPercent == 100 }
            }
            repository.rate(part.id, Rating.GOOD)
            assertEquals(1, folderUpdate.await().single().completedCardCount)
            assertEquals(100, cardUpdate.await().single().recallPercent)
            assertEquals(100, searchUpdate.await().single().recallPercent)
        }
    }

    @Test fun invalidRatingsAreExcludedAndPartsHaveEqualWeight() = runBlocking {
        val folder = repository.saveFolder(null, "English", "", emptyList())
        repository.saveCard(folder, null, listOf(PartDraft(text = "A"), PartDraft(text = "B"), PartDraft(text = "Hint", rememberThis = false)))
        val card = repository.observeCards(folder).first().single()
        val parts = db.contentPartDao().forCard(card.id)
        listOf(0, 5, 1, 3).forEach { review(parts[0].id, it) }
        review(parts[1].id, 4)
        review(parts[2].id, 1)
        assertEquals(75, repository.observeCards(folder).first().single().recallPercent)
        assertEquals(0, repository.observeFolders().first().single().completedCardCount)
    }

    @Test fun editingReorderingAndRatingKeepRetainedHistory() = runBlocking {
        val folder = repository.saveFolder(null, "English", "", emptyList())
        repository.saveCard(folder, null, listOf(PartDraft(text = "A"), PartDraft(text = "B")))
        val card = repository.observeCards(folder).first().single()
        val parts = db.contentPartDao().forCard(card.id)
        repository.rate(parts[0].id, Rating.GOOD)
        repository.rate(parts[1].id, Rating.EASY)
        val state = db.contentPartDao().get(parts[0].id)!!.fsrsStateJson
        repository.saveCard(folder, card.id, listOf(PartDraft(id = parts[1].id, text = "B edited"), PartDraft(id = parts[0].id, text = "A edited")))
        assertEquals(listOf(parts[1].id, parts[0].id), db.contentPartDao().forCard(card.id).map { it.id })
        assertEquals(state, db.contentPartDao().get(parts[0].id)!!.fsrsStateJson)
        assertEquals(1, db.reviewHistoryDao().observeForPart(parts[0].id).first().size)
        assertEquals(1, db.reviewHistoryDao().observeForPart(parts[1].id).first().size)
        assertEquals(100, repository.observeCards(folder).first().single().recallPercent)
        repository.saveCard(folder, card.id, listOf(PartDraft(id = parts[0].id, text = "A"), PartDraft(text = "New")))
        assertTrue(db.reviewHistoryDao().observeForPart(parts[1].id).first().isEmpty())
        assertEquals(1, db.reviewHistoryDao().observeForPart(parts[0].id).first().size)
        assertEquals(50, repository.observeCards(folder).first().single().recallPercent)
    }

    private suspend fun review(part: String, rating: Int) {
        db.reviewHistoryDao().insert(ReviewHistoryEntity(UUID.randomUUID().toString(), part, rating, 0, 0, 0, "{}", "{}"))
    }
}
