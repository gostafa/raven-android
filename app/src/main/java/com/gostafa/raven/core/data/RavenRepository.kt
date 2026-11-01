package com.gostafa.raven.core.data

import androidx.room.withTransaction
import com.gostafa.raven.core.archive.portable
import com.gostafa.raven.core.archive.archiveJson
import com.gostafa.raven.core.archive.PortableReview
import com.gostafa.raven.core.archive.PortableSchedule
import com.gostafa.raven.core.archive.PortableLog
import com.gostafa.raven.core.archive.timestamp
import com.gostafa.raven.core.database.CardEntity
import com.gostafa.raven.core.database.CardSummary
import com.gostafa.raven.core.database.CardPreview
import com.gostafa.raven.core.database.CardDao
import com.gostafa.raven.core.database.ContentPartDao
import com.gostafa.raven.core.database.ContentPartEntity
import com.gostafa.raven.core.database.FolderDao
import com.gostafa.raven.core.database.FolderEntity
import com.gostafa.raven.core.database.FolderSummary
import com.gostafa.raven.core.database.MediaDao
import com.gostafa.raven.core.database.MediaEntity
import com.gostafa.raven.core.database.RavenDatabase
import com.gostafa.raven.core.database.ReviewHistoryDao
import com.gostafa.raven.core.database.ReviewHistoryEntity
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.model.CardWithParts
import com.gostafa.raven.core.model.FsrsState
import com.gostafa.raven.core.model.PartDraft
import com.gostafa.raven.core.model.PartType
import com.gostafa.raven.core.model.Rating
import com.gostafa.raven.core.model.ReviewItem
import com.gostafa.raven.core.model.cardRecallPercent
import com.gostafa.raven.core.model.normalizeTags
import com.gostafa.raven.core.scheduler.ReviewScheduler
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import com.gostafa.raven.core.reminders.ReminderInvalidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CardUnavailableException : IllegalStateException("Card is no longer available")

interface RavenRepository {
    fun observeFolders(): Flow<List<FolderSummary>>
    fun observeCards(folderId: String): Flow<List<CardSummary>>
    fun searchCards(query: String): Flow<List<CardSummary>>
    fun observeCardPreviews(folderId: String): Flow<List<CardPreview>>
    fun searchCardPreviews(query: String): Flow<List<CardPreview>>
    suspend fun saveFolder(id: String?, name: String, description: String, tags: List<String>): String
    suspend fun deleteFolder(id: String)
    suspend fun saveCard(folderId: String, cardId: String?, drafts: List<PartDraft>)
    suspend fun deleteCard(id: String)
    suspend fun cardWithParts(cardId: String): CardWithParts?
    suspend fun queueDue(): List<ReviewItem>
    suspend fun queueDueCard(cardId: String): List<ReviewItem> = queueDue().filter { it.content.card.id == cardId }
    suspend fun queueCard(cardId: String): List<ReviewItem>
    suspend fun queueFolder(folderId: String): List<ReviewItem>
    suspend fun rate(partId: String, rating: Rating, practice: Boolean = false)
    suspend fun saveMedia(relativePath: String, mimeType: String, byteSize: Long)
    suspend fun discardMedia(paths: List<String>)
}

class RoomRavenRepository @Inject constructor(
    private val database: RavenDatabase,
    private val folders: FolderDao,
    private val cards: CardDao,
    private val parts: ContentPartDao,
    private val media: MediaDao,
    private val history: ReviewHistoryDao,
    private val scheduler: ReviewScheduler,
    private val mediaStorage: MediaStorage,
    private val reminders: ReminderInvalidator = ReminderInvalidator {},
    private val dueTimeRefresh: DueTimeRefresh = DueTimeRefresh(),
) : RavenRepository {
    // Database changes restart the clock; due boundaries refresh queries even without writes.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun dueClock(): Flow<Long> = combine(parts.observeReviewable(), dueTimeRefresh.changes) { reviewable, _ -> reviewable }.flatMapLatest { reviewable ->
        flow {
            while (true) {
                val now = System.currentTimeMillis()
                emit(now)
                val next = reviewable.map { it.dueAt }.filter { it > now }.minOrNull()
                // Also detect wall-clock changes while a screen remains open.
                delay(next?.minus(now)?.coerceIn(1L, 60_000L) ?: 60_000L)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeFolders(): Flow<List<FolderSummary>> =
        combine(dueClock().flatMapLatest { folders.observeSummaries(it) }, history.observeRecall()) { summaries, recall ->
            val completed = recall.groupBy { it.cardId }.values
                .filter { cardRecallPercent(it) == 100 }
                .groupingBy { it.first().folderId }.eachCount()
            summaries.map { it.copy(completedCardCount = completed[it.id] ?: 0) }
        }

    private fun Flow<List<CardSummary>>.withRecall(): Flow<List<CardSummary>> =
        combine(this, history.observeRecall()) { summaries, recall ->
            val percentages = recall.groupBy { it.cardId }.mapValues { cardRecallPercent(it.value) }
            summaries.map { it.copy(recallPercent = percentages[it.id] ?: 0) }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeCards(folderId: String) = dueClock().flatMapLatest { cards.observeInFolder(folderId, it) }.withRecall()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun searchCards(query: String) = dueClock().flatMapLatest { cards.search(query, it) }.withRecall()

    private fun Flow<List<CardPreview>>.withPreviewRecall(): Flow<List<CardPreview>> =
        combine(this, history.observeRecall()) { previews, recall ->
            val percentages = recall.groupBy { it.cardId }.mapValues { cardRecallPercent(it.value) }
            previews.map { it.copy(summary = it.summary.copy(recallPercent = percentages[it.summary.id] ?: 0), parts = it.parts.sortedBy { part -> part.orderIndex }) }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeCardPreviews(folderId: String) = dueClock().flatMapLatest { cards.observePreviewsInFolder(folderId, it) }.withPreviewRecall()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun searchCardPreviews(query: String) = dueClock().flatMapLatest { cards.searchPreviews(query, it) }.withPreviewRecall()

    override suspend fun saveFolder(id: String?, name: String, description: String, tags: List<String>): String {
        require(name.isNotBlank()) { "A folder must have a name" }
        val now = System.currentTimeMillis()
        val previous = if (id == null) null else folders.get(id)
        val savedId = previous?.id ?: UUID.randomUUID().toString()
        folders.upsert(
            FolderEntity(
                id = savedId,
                name = name.trim(),
                description = description.trim(),
                tagsJson = Json.encodeToString(normalizeTags(tags)),
                createdAt = previous?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        return savedId
    }

    override suspend fun deleteFolder(id: String) {
        val paths = parts.mediaPathsInFolder(id).distinct()
        database.withTransaction { folders.delete(id) }
        reminders.changed()
        cleanupMedia(paths)
    }

    override suspend fun saveCard(folderId: String, cardId: String?, drafts: List<PartDraft>) {
        require(drafts.isNotEmpty()) { "A card must contain at least one part" }
        val valid = withContext(Dispatchers.IO) {
            drafts.all { draft ->
                if (draft.type == PartType.TEXT) draft.text.isNotBlank()
                else draft.mediaPath?.takeIf { it.isNotBlank() }?.let { mediaStorage.file(it).isFile } == true
            }
        }
        require(valid) {
            "Each part must contain text or attached media"
        }
        require(drafts.mapNotNull { it.id }.distinct().size == drafts.count { it.id != null }) { "Duplicate part IDs" }
        val now = System.currentTimeMillis()
        val oldMediaPaths = cardId?.let { parts.forCard(it).mapNotNull(ContentPartEntity::mediaPath) }.orEmpty()
        database.withTransaction {
            val existing = if (cardId == null) null else cards.get(cardId)
            if (cardId != null && existing == null) throw CardUnavailableException()
            check(folders.get(existing?.folderId ?: folderId) != null) { "Folder is no longer available" }
            val savedId = existing?.id ?: UUID.randomUUID().toString()
            cards.upsert(CardEntity(savedId, existing?.folderId ?: folderId, existing?.createdAt ?: now, now))
            val oldParts = parts.forCard(savedId).associateBy { it.id }
            val saved = drafts.mapIndexed { index, draft ->
                val id = draft.id ?: UUID.randomUUID().toString()
                val old = oldParts[id]
                check(parts.get(id)?.cardId.let { it == null || it == savedId }) { "Part belongs to another card" }
                val mediaPath = draft.mediaPath ?: old?.mediaPath
                val state = old?.fsrsState() ?: FsrsState(dueAt = now)
                ContentPartEntity(
                    id = id,
                    cardId = savedId,
                    type = draft.type.name,
                    text = draft.text,
                    mediaPath = mediaPath,
                    originalName = draft.originalName ?: old?.originalName?.takeIf { old.mediaPath == mediaPath },
                    mimeType = draft.mimeType ?: old?.mimeType,
                    orderIndex = index,
                    rememberThis = draft.rememberThis,
                    fsrsStateJson = Json.encodeToString(state),
                    schedulerVersion = old?.schedulerVersion ?: scheduler.version,
                    dueAt = state.dueAt,
                    reviewCount = old?.reviewCount ?: 0,
                    createdAt = old?.createdAt ?: now,
                    updatedAt = now,
                )
            }
            val retainedIds = saved.map { it.id }.toSet()
            oldParts.keys.filterNot { it in retainedIds }.forEach { parts.delete(it) }
            parts.upsertAll(saved)
        }
        reminders.changed()
        // The card has committed. Cleanup failure must not turn a successful save into a retry.
        try {
            cleanupMedia(oldMediaPaths)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            android.util.Log.w("RavenRepository", "Could not clean up replaced media", error)
        }
    }

    override suspend fun deleteCard(id: String) {
        val oldMediaPaths = parts.forCard(id).mapNotNull(ContentPartEntity::mediaPath)
        database.withTransaction { cards.delete(id) }
        reminders.changed()
        cleanupMedia(oldMediaPaths)
    }

    override suspend fun cardWithParts(cardId: String): CardWithParts? {
        val card = cards.get(cardId) ?: return null
        val folder = folders.get(card.folderId) ?: return null
        return CardWithParts(card, parts.forCard(cardId), folder.name)
    }

    override suspend fun queueDue(): List<ReviewItem> = parts.due(System.currentTimeMillis())
        .mapNotNull { part -> cardWithParts(part.cardId)?.let { ReviewItem(it, part) } }

    override suspend fun queueDueCard(cardId: String): List<ReviewItem> = parts.reviewableForCard(cardId)
        .filter { it.dueAt <= System.currentTimeMillis() }
        .mapNotNull { part -> cardWithParts(cardId)?.let { ReviewItem(it, part) } }

    override suspend fun queueCard(cardId: String): List<ReviewItem> = parts.reviewableForCard(cardId)
        .mapNotNull { part -> cardWithParts(part.cardId)?.let { ReviewItem(it, part) } }

    override suspend fun queueFolder(folderId: String): List<ReviewItem> = parts.practiceInFolder(folderId)
        .mapNotNull { part -> cardWithParts(part.cardId)?.let { ReviewItem(it, part) } }

    override suspend fun rate(partId: String, rating: Rating, practice: Boolean) {
        database.withTransaction {
            val current = parts.get(partId) ?: return@withTransaction
            if (!current.rememberThis) return@withTransaction
            val now = System.currentTimeMillis()
            val before = current.fsrsState()
            if (before.dueAt > now) {
                if (practice) return@withTransaction
                return@withTransaction
            }
            val after = scheduler.schedule(before, rating, now)
            parts.upsert(
                current.copy(
                    fsrsStateJson = Json.encodeToString(after),
                    schedulerVersion = scheduler.version,
                    dueAt = after.dueAt,
                    reviewCount = current.reviewCount + 1,
                    updatedAt = now,
                ),
            )
            history.insert(
                ReviewHistoryEntity(
                    id = UUID.randomUUID().toString(),
                    partId = partId,
                    rating = rating.value,
                    reviewedAt = now,
                    dueBefore = before.dueAt,
                    dueAfter = after.dueAt,
                    stateBeforeJson = Json.encodeToString(before),
                    stateAfterJson = Json.encodeToString(after),
                ),
            )
            database.archiveStateDao().get(partId)?.let { portable ->
                val reviews = archiveJson.decodeFromString<List<PortableReview>>(portable.reviewsJson)
                val previous = archiveJson.decodeFromString<PortableSchedule>(portable.scheduleJson)
                val appended = reviews + PortableReview(PortableLog(previous.lastReview, timestamp(now), rating.value,
                    previous.scheduledDays, previous.stability, previous.difficulty, previous.remainingSteps, previous.state))
                val next = after.portable().copy(reps = previous.reps + 1u, lapses = previous.lapses + (after.lapses - before.lapses).coerceAtLeast(0).toULong())
                database.archiveStateDao().upsert(portable.copy(
                    scheduleJson = archiveJson.encodeToString(next),
                    reviewsJson = archiveJson.encodeToString(appended),
                ))
            }
        }
        reminders.changed()
    }

    override suspend fun saveMedia(relativePath: String, mimeType: String, byteSize: Long) {
        media.upsert(MediaEntity(UUID.randomUUID().toString(), relativePath, mimeType, byteSize, System.currentTimeMillis()))
    }

    override suspend fun discardMedia(paths: List<String>) = cleanupMedia(paths)

    private suspend fun cleanupMedia(paths: List<String>) {
        paths.distinct().forEach { path ->
            if (!parts.hasMediaReference(path)) {
                mediaStorage.delete(path)
                media.deleteByPath(path)
            }
        }
    }
}
