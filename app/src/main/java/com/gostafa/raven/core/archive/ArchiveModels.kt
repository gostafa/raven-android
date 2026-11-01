package com.gostafa.raven.core.archive

import com.gostafa.raven.core.model.FsrsState
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val archiveJson = Json { encodeDefaults = true }

enum class ImportMode { FRESH, RESUME }
data class ArchiveResult(val folderCount: Int, val cardCount: Int, val mediaCount: Int, val folderIds: List<String> = emptyList())

@Serializable
internal data class ArchiveDocument(val version: Int = 1, @SerialName("exported_at") val exportedAt: String, val folders: List<ArchiveFolder>)
@Serializable
internal data class ArchiveFolder(val folder: ArchiveFolderMetadata, val cards: List<ArchiveCard>)
@Serializable
internal data class ArchiveFolderMetadata(val id: String, val title: String, val description: String = "", val tags: List<String> = emptyList())
@Serializable
internal data class ArchiveCard(
    val id: String, @SerialName("folder_id") val folderId: String,
    @SerialName("created_at") val createdAt: Long, val items: List<ArchiveItem>,
    @SerialName("due_at") val dueAt: Long = 0, @SerialName("last_reviewed") val lastReviewed: Long = 0,
)
@Serializable
internal data class ArchiveItem(
    val id: String, @SerialName("folder_id") val folderId: String, @SerialName("card_id") val cardId: String,
    val kind: String, val position: Int, val schedule: PortableSchedule, val reviews: List<PortableReview>,
    val text: String = "", @SerialName("relative_path") val relativePath: String = "",
    @SerialName("mime_type") val mimeType: String = "", @SerialName("original_name") val originalName: String = "",
    @SerialName("byte_size") val byteSize: Long = 0, val hint: Boolean = false,
    @SerialName("last_notified_due") val lastNotifiedDue: Long = 0,
)

// Keep unsigned counters and RFC3339 strings intact: the Android projection is not the archive source of truth.
@Serializable
internal data class PortableSchedule(
    val due: String, @SerialName("last_review") val lastReview: String,
    val stability: Double = 0.0, val difficulty: Double = 0.0,
    @SerialName("scheduled_days") val scheduledDays: ULong = 0u,
    val reps: ULong = 0u, val lapses: ULong = 0u,
    @SerialName("remaining_steps") val remainingSteps: Long = 0, val state: Int = 0,
) {
    fun androidState() = FsrsState(
        status = listOf("New", "Learning", "Review", "Relearning")[state],
        stability = stability, difficulty = difficulty, dueAt = Instant.parse(due).toEpochMilli(),
        lastReviewAt = lastReview.takeUnless { it == ZERO_TIME }?.let { Instant.parse(it).toEpochMilli() },
        scheduledDays = scheduledDays.project(), repetitions = reps.project(), lapses = lapses.project(),
        learningStep = remainingSteps.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
    )
}
@Serializable
internal data class PortableReview(val log: PortableLog, val automatic: Boolean = false)
@Serializable
internal data class PortableLog(
    val due: String, val review: String, val rating: Int,
    @SerialName("scheduled_days") val scheduledDays: ULong = 0u,
    val stability: Double = 0.0, val difficulty: Double = 0.0,
    @SerialName("remaining_steps") val remainingSteps: Long = 0, val state: Int = 0,
)
private fun ULong.project() = coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
internal const val ZERO_TIME = "0001-01-01T00:00:00Z"
internal fun timestamp(millis: Long) = Instant.ofEpochMilli(millis).toString()
internal fun FsrsState.portable() = PortableSchedule(
    timestamp(dueAt), lastReviewAt?.let(::timestamp) ?: ZERO_TIME, stability, difficulty,
    scheduledDays.coerceAtLeast(0).toULong(), repetitions.coerceAtLeast(0).toULong(), lapses.coerceAtLeast(0).toULong(),
    learningStep.toLong(), listOf("New", "Learning", "Review", "Relearning").indexOf(status).coerceAtLeast(0),
)
// go-fsrs logs the pre-review memory and previous review time, rather than the new due date.
internal fun portableReview(before: FsrsState, rating: Int, at: Long) = PortableReview(
    PortableLog(timestamp(before.lastReviewAt ?: before.dueAt), timestamp(at), rating,
        before.scheduledDays.coerceAtLeast(0).toULong(), before.stability, before.difficulty, before.learningStep.toLong(),
        listOf("New", "Learning", "Review", "Relearning").indexOf(before.status).coerceAtLeast(0)),
)

// Raven initializes by applying Again to an empty go-fsrs/v4 card (not a manual review).
internal fun freshSchedule(now: Long) = PortableSchedule(timestamp(now + 60_000), timestamp(now),
    stability = 0.212, difficulty = 6.4133, reps = 1u, remainingSteps = 2, state = 1)
internal fun freshReviews(now: Long) = listOf(PortableReview(
    PortableLog(timestamp(now), timestamp(now), 1), automatic = true,
))
