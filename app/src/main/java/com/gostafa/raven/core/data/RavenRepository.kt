package com.gostafa.raven.core.data

import androidx.room.withTransaction
import com.gostafa.raven.core.database.CardEntity
import com.gostafa.raven.core.database.CardSummary
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
import com.gostafa.raven.core.scheduler.ReviewScheduler
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface RavenRepository {
    fun observeFolders(): Flow<List<FolderSummary>>
    fun observeCards(folderId: String): Flow<List<CardSummary>>
    fun searchCards(query: String): Flow<List<CardSummary>>
    suspend fun saveFolder(id: String?, name: String, description: String)
    suspend fun deleteFolder(id: String)
    suspend fun saveCard(folderId: String, cardId: String?, drafts: List<PartDraft>)
    suspend fun deleteCard(id: String)
    suspend fun cardWithParts(cardId: String): CardWithParts?
    suspend fun queueDue(): List<ReviewItem>
    suspend fun queueCard(cardId: String): List<ReviewItem>
    suspend fun queueFolder(folderId: String): List<ReviewItem>
    suspend fun rate(partId: String, rating: Rating, practice: Boolean = false)
    suspend fun saveMedia(relativePath: String, mimeType: String, byteSize: Long)
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
) : RavenRepository {
    override fun observeFolders() = folders.observeSummaries(System.currentTimeMillis())
    override fun observeCards(folderId: String) = cards.observeInFolder(folderId, System.currentTimeMillis())
    override fun searchCards(query: String) = cards.search(query, System.currentTimeMillis())

    override suspend fun saveFolder(id: String?, name: String, description: String) {
        val now = System.currentTimeMillis()
        val previous = if (id == null) null else folders.get(id)
        folders.upsert(
            FolderEntity(
                id = previous?.id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                description = description.trim(),
                tagsJson = previous?.tagsJson ?: "[]",
                createdAt = previous?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    override suspend fun deleteFolder(id: String) {
        val paths = parts.mediaPathsInFolder(id).distinct()
        database.withTransaction { folders.delete(id) }
        cleanupMedia(paths)
    }

    override suspend fun saveCard(folderId: String, cardId: String?, drafts: List<PartDraft>) {
        require(drafts.isNotEmpty()) { "A card must contain at least one part" }
        val now = System.currentTimeMillis()
        val oldMediaPaths = cardId?.let { parts.forCard(it).mapNotNull(ContentPartEntity::mediaPath) }.orEmpty()
        database.withTransaction {
            val existing = if (cardId == null) null else cards.get(cardId)
            val savedId = existing?.id ?: UUID.randomUUID().toString()
            cards.upsert(CardEntity(savedId, existing?.folderId ?: folderId, existing?.createdAt ?: now, now))
            val oldParts = parts.forCard(savedId).associateBy { it.id }
            val saved = drafts.mapIndexed { index, draft ->
                val id = draft.id ?: UUID.randomUUID().toString()
                val old = oldParts[id]
                val mediaPath = draft.mediaPath ?: old?.mediaPath
                val state = old?.fsrsState() ?: FsrsState(dueAt = now)
                ContentPartEntity(
                    id = id,
                    cardId = savedId,
                    type = draft.type.name,
                    text = draft.text,
                    mediaPath = mediaPath,
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
            parts.deleteForCard(savedId)
            parts.upsertAll(saved)
        }
        cleanupMedia(oldMediaPaths)
    }

    override suspend fun deleteCard(id: String) {
        val oldMediaPaths = parts.forCard(id).mapNotNull(ContentPartEntity::mediaPath)
        database.withTransaction { cards.delete(id) }
        cleanupMedia(oldMediaPaths)
    }

    override suspend fun cardWithParts(cardId: String): CardWithParts? {
        val card = cards.get(cardId) ?: return null
        val folder = folders.get(card.folderId) ?: return null
        return CardWithParts(card, parts.forCard(cardId), folder.name)
    }

    override suspend fun queueDue(): List<ReviewItem> = parts.due(System.currentTimeMillis())
        .mapNotNull { part -> cardWithParts(part.cardId)?.let { ReviewItem(it, part) } }

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
        }
    }

    override suspend fun saveMedia(relativePath: String, mimeType: String, byteSize: Long) {
        media.upsert(MediaEntity(UUID.randomUUID().toString(), relativePath, mimeType, byteSize, System.currentTimeMillis()))
    }

    private suspend fun cleanupMedia(paths: List<String>) {
        paths.distinct().forEach { path ->
            if (!parts.hasMediaReference(path)) {
                mediaStorage.delete(path)
                media.deleteByPath(path)
            }
        }
    }
}
