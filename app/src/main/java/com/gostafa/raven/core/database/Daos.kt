package com.gostafa.raven.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import com.gostafa.raven.core.model.PartRecall

@Dao
interface FolderDao {
    @Query("SELECT f.id, f.name, f.description, f.tagsJson, 0 AS completedCardCount, (SELECT COUNT(*) FROM cards c WHERE c.folderId = f.id) AS cardCount, (SELECT COUNT(*) FROM content_parts p JOIN cards c ON c.id = p.cardId WHERE c.folderId = f.id AND p.rememberThis = 1 AND p.dueAt <= :now) AS dueCount, (SELECT MIN(p.dueAt) FROM content_parts p JOIN cards c ON c.id = p.cardId WHERE c.folderId = f.id AND p.rememberThis = 1) AS nextReviewAt, f.updatedAt FROM folders f ORDER BY f.updatedAt DESC")
    fun observeSummaries(now: Long): Flow<List<FolderSummary>>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun get(id: String): FolderEntity?

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)
}

private const val CARDS_IN_FOLDER = "SELECT c.id, c.folderId, c.updatedAt, c.createdAt, (SELECT MIN(r.dueAt) FROM content_parts r WHERE r.cardId = c.id AND r.rememberThis = 1) AS nextReviewAt, 0 AS recallPercent, COALESCE((SELECT p.text FROM content_parts p WHERE p.cardId = c.id AND p.type = 'TEXT' AND TRIM(p.text) != '' ORDER BY p.orderIndex LIMIT 1), '') AS preview, (SELECT COUNT(*) FROM content_parts p WHERE p.cardId = c.id) AS partCount, (SELECT COUNT(*) FROM content_parts p WHERE p.cardId = c.id AND p.rememberThis = 1 AND p.dueAt <= :now) AS dueCount FROM cards c WHERE c.folderId = :folderId ORDER BY c.updatedAt DESC"
private const val SEARCH_CARDS = "SELECT c.id, c.folderId, c.updatedAt, c.createdAt, (SELECT MIN(r.dueAt) FROM content_parts r WHERE r.cardId = c.id AND r.rememberThis = 1) AS nextReviewAt, 0 AS recallPercent, COALESCE((SELECT p.text FROM content_parts p WHERE p.cardId = c.id AND p.type = 'TEXT' AND TRIM(p.text) != '' ORDER BY p.orderIndex LIMIT 1), '') AS preview, (SELECT COUNT(*) FROM content_parts p WHERE p.cardId = c.id) AS partCount, (SELECT COUNT(*) FROM content_parts p WHERE p.cardId = c.id AND p.rememberThis = 1 AND p.dueAt <= :now) AS dueCount FROM cards c JOIN folders f ON f.id = c.folderId LEFT JOIN content_parts p ON p.cardId = c.id WHERE lower(f.name) LIKE '%' || lower(:query) || '%' OR lower(p.text) LIKE '%' || lower(:query) || '%' GROUP BY c.id ORDER BY c.updatedAt DESC"

@Dao
interface CardDao {
    @Query("SELECT * FROM cards WHERE folderId = :folderId ORDER BY createdAt, id")
    suspend fun allInFolder(folderId: String): List<CardEntity>
    @Query(CARDS_IN_FOLDER)
    fun observeInFolder(folderId: String, now: Long): Flow<List<CardSummary>>

    @Transaction
    @Query(CARDS_IN_FOLDER)
    fun observePreviewsInFolder(folderId: String, now: Long): Flow<List<CardPreview>>

    @Query("SELECT * FROM cards WHERE id = :id LIMIT 1")
    suspend fun get(id: String): CardEntity?

    @Query(SEARCH_CARDS)
    fun search(query: String, now: Long): Flow<List<CardSummary>>

    @Transaction
    @Query(SEARCH_CARDS)
    fun searchPreviews(query: String, now: Long): Flow<List<CardPreview>>

    @Upsert
    suspend fun upsert(card: CardEntity)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ContentPartDao {
    @Query("SELECT * FROM content_parts WHERE rememberThis = 1")
    fun observeReviewable(): Flow<List<ContentPartEntity>>

    @Query("SELECT * FROM content_parts WHERE cardId = :cardId ORDER BY orderIndex")
    suspend fun forCard(cardId: String): List<ContentPartEntity>

    @Query("SELECT * FROM content_parts WHERE cardId = :cardId ORDER BY orderIndex")
    fun observeForCard(cardId: String): Flow<List<ContentPartEntity>>

    @Query("SELECT p.* FROM content_parts p JOIN cards c ON c.id = p.cardId WHERE p.rememberThis = 1 AND p.dueAt <= :now ORDER BY p.dueAt")
    suspend fun due(now: Long): List<ContentPartEntity>

    @Query("SELECT p.* FROM content_parts p WHERE p.cardId = :cardId AND p.rememberThis = 1 ORDER BY p.orderIndex")
    suspend fun reviewableForCard(cardId: String): List<ContentPartEntity>

    @Query("SELECT p.* FROM content_parts p JOIN cards c ON c.id = p.cardId WHERE c.folderId = :folderId AND p.rememberThis = 1 ORDER BY RANDOM()")
    suspend fun practiceInFolder(folderId: String): List<ContentPartEntity>

    @Query("SELECT * FROM content_parts WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ContentPartEntity?

    @Upsert
    suspend fun upsertAll(parts: List<ContentPartEntity>)

    @Upsert
    suspend fun upsert(part: ContentPartEntity)

    @Query("DELETE FROM content_parts WHERE cardId = :cardId")
    suspend fun deleteForCard(cardId: String)

    @Query("SELECT p.mediaPath FROM content_parts p JOIN cards c ON c.id = p.cardId WHERE c.folderId = :folderId AND p.mediaPath IS NOT NULL")
    suspend fun mediaPathsInFolder(folderId: String): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM content_parts WHERE mediaPath = :path)")
    suspend fun hasMediaReference(path: String): Boolean

    @Query("DELETE FROM content_parts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ReminderDao {
    @Query("""SELECT p.id AS partId, p.cardId, p.dueAt, f.name AS folderName
        FROM content_parts p JOIN cards c ON c.id = p.cardId JOIN folders f ON f.id = c.folderId
        LEFT JOIN reminder_receipts r ON r.partId = p.id
        WHERE p.rememberThis = 1 AND p.dueAt <= :now AND (r.partId IS NULL OR r.handledDueAt != p.dueAt)
        ORDER BY p.dueAt, p.id""")
    suspend fun pendingDue(now: Long): List<ReminderOccurrence>

    @Query("""SELECT MIN(p.dueAt) FROM content_parts p LEFT JOIN reminder_receipts r ON r.partId = p.id
        WHERE p.rememberThis = 1 AND (r.partId IS NULL OR r.handledDueAt != p.dueAt)""")
    suspend fun nextUnhandledDue(): Long?

    @Query("SELECT DISTINCT cardId FROM content_parts WHERE rememberThis = 1 AND dueAt <= :now")
    suspend fun dueCardIds(now: Long): List<String>

    @Upsert
    suspend fun acknowledge(receipts: List<ReminderReceiptEntity>)
}

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(media: MediaEntity)

    @Query("SELECT * FROM media WHERE relativePath = :path LIMIT 1")
    suspend fun getByPath(path: String): MediaEntity?

    @Query("DELETE FROM media WHERE relativePath = :path")
    suspend fun deleteByPath(path: String)
}

@Dao
interface ReviewHistoryDao {
    @Query("SELECT * FROM review_history WHERE partId = :partId ORDER BY reviewedAt, rowid")
    suspend fun forPart(partId: String): List<ReviewHistoryEntity>
    @Query("""
        SELECT p.id AS partId, p.cardId, c.folderId, p.rememberThis,
               COUNT(h.id) AS reviewCount,
               COALESCE(SUM(CASE WHEN h.rating > 1 THEN 1 ELSE 0 END), 0) AS successCount
        FROM content_parts p
        JOIN cards c ON c.id = p.cardId
        LEFT JOIN review_history h ON h.partId = p.id AND h.rating BETWEEN 1 AND 4
        GROUP BY p.id
    """)
    fun observeRecall(): Flow<List<PartRecall>>

    @Insert
    suspend fun insert(review: ReviewHistoryEntity)

    @Query("SELECT * FROM review_history WHERE partId = :partId ORDER BY reviewedAt DESC")
    fun observeForPart(partId: String): Flow<List<ReviewHistoryEntity>>
}

@Dao
interface ArchiveStateDao {
    @Query("SELECT * FROM archive_states WHERE partId = :partId")
    suspend fun get(partId: String): ArchiveStateEntity?
    @Upsert
    suspend fun upsert(state: ArchiveStateEntity)
}
