package com.gostafa.raven.core.database

import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.Relation
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.gostafa.raven.core.model.FsrsState
import com.gostafa.raven.core.model.decodeTags
import kotlinx.serialization.json.Json

@Entity(tableName = "folders", indices = [Index("name")])
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val tagsJson: String = "[]",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "cards",
    foreignKeys = [ForeignKey(entity = FolderEntity::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("folderId"), Index("updatedAt")],
)
data class CardEntity(
    @PrimaryKey val id: String,
    val folderId: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "content_parts",
    foreignKeys = [ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId", "orderIndex"), Index("rememberThis", "dueAt")],
)
data class ContentPartEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val type: String,
    val text: String = "",
    val mediaPath: String? = null,
    val mimeType: String? = null,
    val orderIndex: Int,
    val rememberThis: Boolean = true,
    val fsrsStateJson: String,
    val schedulerVersion: String = "FSRS-6",
    val dueAt: Long,
    val reviewCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val originalName: String? = null,
) {
    fun fsrsState(): FsrsState = Json.decodeFromString(fsrsStateJson)
}

@Entity(
    tableName = "media",
    indices = [Index("relativePath", unique = true)],
)
data class MediaEntity(
    @PrimaryKey val id: String,
    val relativePath: String,
    val mimeType: String,
    val byteSize: Long,
    val createdAt: Long,
)

@Entity(
    tableName = "review_history",
    foreignKeys = [ForeignKey(entity = ContentPartEntity::class, parentColumns = ["id"], childColumns = ["partId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("partId", "reviewedAt")],
)
data class ReviewHistoryEntity(
    @PrimaryKey val id: String,
    val partId: String,
    val rating: Int,
    val reviewedAt: Long,
    val dueBefore: Long,
    val dueAfter: Long,
    val stateBeforeJson: String,
    val stateAfterJson: String,
)

@Entity(
    tableName = "reminder_receipts",
    foreignKeys = [ForeignKey(entity = ContentPartEntity::class, parentColumns = ["id"], childColumns = ["partId"], onDelete = ForeignKey.CASCADE)],
)
data class ReminderReceiptEntity(@PrimaryKey val partId: String, val handledDueAt: Long)

@Entity(tableName = "archive_states", foreignKeys = [ForeignKey(entity = ContentPartEntity::class, parentColumns = ["id"], childColumns = ["partId"], onDelete = ForeignKey.CASCADE)])
data class ArchiveStateEntity(@PrimaryKey val partId: String, val scheduleJson: String, val reviewsJson: String)

data class ReminderOccurrence(val partId: String, val cardId: String, val dueAt: Long, val folderName: String)

data class FolderSummary(
    val id: String,
    val name: String,
    val description: String,
    val cardCount: Int,
    val dueCount: Int,
    val updatedAt: Long,
    val tagsJson: String = "[]",
    val completedCardCount: Int = 0,
    val nextReviewAt: Long? = null,
) {
    @get:Ignore
    val tags: List<String> get() = decodeTags(tagsJson)
}

data class CardSummary(
    val id: String,
    val folderId: String,
    val updatedAt: Long,
    val preview: String,
    val partCount: Int,
    val dueCount: Int,
    val recallPercent: Int = 0,
    val createdAt: Long = updatedAt,
    val nextReviewAt: Long? = null,
)

/** Room fetches related parts in batches; consumers sort by orderIndex. */
data class CardPreview(
    @Embedded val summary: CardSummary,
    @Relation(parentColumn = "id", entityColumn = "cardId") val parts: List<ContentPartEntity>,
)
