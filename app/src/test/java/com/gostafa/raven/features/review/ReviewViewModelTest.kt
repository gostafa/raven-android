package com.gostafa.raven.features.review

import com.gostafa.raven.core.data.RavenRepository
import com.gostafa.raven.core.database.*
import com.gostafa.raven.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun dueCardUsesScheduledReviewAndTargetChangesReload() = runTest {
        val repo = ReviewRepository()
        val vm = ReviewViewModel(repo)
        vm.begin("due-card", "a"); runCurrent()
        assertEquals("a", vm.state.value.current!!.content.card.id)
        vm.rate(Rating.GOOD); runCurrent()
        assertEquals(false, repo.practice)
        assertEquals(1, vm.state.value.index)
        vm.begin("due-card", "b"); runCurrent()
        assertEquals("b", vm.state.value.current!!.content.card.id)
        assertEquals(2, repo.loads)
        vm.begin("due-card", "b"); runCurrent()
        assertEquals(2, repo.loads)
    }

    @Test fun failedLoadRetriesWithoutShowingCompletion() = runTest {
        val repo = ReviewRepository().apply { failLoad = true }
        val vm = ReviewViewModel(repo)
        vm.begin("due", null); runCurrent()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.complete)
        repo.failLoad = false
        vm.retry(); runCurrent()
        assertNotNull(vm.state.value.current)
    }

    @Test fun doubleTapSubmitsOnceAndFailedSaveKeepsItemForRetry() = runTest {
        val repo = ReviewRepository()
        val gate = CompletableDeferred<Unit>()
        repo.ratingGate = gate
        repo.failSave = true
        val vm = ReviewViewModel(repo)
        vm.begin("due", null); runCurrent()
        vm.rate(Rating.AGAIN); vm.rate(Rating.EASY); runCurrent()
        assertEquals(1, repo.ratings)
        assertTrue(vm.state.value.submitting)
        gate.complete(Unit); runCurrent()
        assertEquals(0, vm.state.value.index)
        assertTrue(vm.state.value.saveFailed)
        assertFalse(vm.state.value.submitting)
        repo.failSave = false
        vm.rate(Rating.GOOD); runCurrent()
        assertEquals(1, vm.state.value.index)
        assertEquals(2, repo.ratings)
    }

    @Test fun practiceModesKeepPracticeFlag() = runTest {
        val repo = ReviewRepository()
        val vm = ReviewViewModel(repo)
        for (mode in listOf("card", "folder")) {
            vm.begin(mode, "a"); runCurrent()
            vm.rate(Rating.GOOD); runCurrent()
            assertEquals(true, repo.practice)
        }
    }
}

private class ReviewRepository : RavenRepository {
    var loads = 0
    var ratings = 0
    var practice: Boolean? = null
    var failLoad = false
    var failSave = false
    var ratingGate: CompletableDeferred<Unit>? = null
    private fun items(id: String): List<ReviewItem> {
        loads++
        if (failLoad) error("load failed")
        val part = ContentPartEntity("p$id", id, "TEXT", "Prompt", orderIndex = 0, fsrsStateJson = "{}", dueAt = 0, createdAt = 0, updatedAt = 0)
        return listOf(ReviewItem(CardWithParts(CardEntity(id, "f", 0, 0), listOf(part), "Words"), part))
    }
    override suspend fun queueDue() = items("a")
    override suspend fun queueDueCard(cardId: String) = items(cardId)
    override suspend fun queueCard(cardId: String) = items(cardId)
    override suspend fun queueFolder(folderId: String) = items(folderId)
    override suspend fun rate(partId: String, rating: Rating, practice: Boolean) {
        ratings++; this.practice = practice
        ratingGate?.await()
        if (failSave) error("save failed")
    }
    override fun observeFolders(): Flow<List<FolderSummary>> = flowOf(emptyList())
    override fun observeCards(folderId: String): Flow<List<CardSummary>> = flowOf(emptyList())
    override fun searchCards(query: String): Flow<List<CardSummary>> = flowOf(emptyList())
    override suspend fun saveFolder(id: String?, name: String, description: String, tags: List<String>) = "f"
    override suspend fun deleteFolder(id: String) = Unit
    override suspend fun saveCard(folderId: String, cardId: String?, drafts: List<PartDraft>) = Unit
    override suspend fun deleteCard(id: String) = Unit
    override suspend fun cardWithParts(cardId: String): CardWithParts? = null
    override suspend fun saveMedia(relativePath: String, mimeType: String, byteSize: Long) = Unit
    override suspend fun discardMedia(paths: List<String>) = Unit
}
