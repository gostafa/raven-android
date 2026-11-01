package com.gostafa.raven.core.model

import com.gostafa.raven.core.database.CardEntity
import com.gostafa.raven.core.database.ContentPartEntity
import kotlinx.serialization.Serializable

enum class PartType { TEXT, IMAGE, AUDIO, VIDEO }

enum class Rating(val value: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }

@Serializable
data class FsrsState(
    val version: Int = 6,
    val status: String = "New",
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val dueAt: Long,
    val lastReviewAt: Long? = null,
    val scheduledDays: Int = 0,
    val elapsedDays: Int = 0,
    val repetitions: Int = 0,
    val lapses: Int = 0,
    val learningStep: Int = 0,
)

@Serializable
data class PartDraft(
    val id: String? = null,
    val type: PartType = PartType.TEXT,
    val text: String = "",
    val mediaPath: String? = null,
    val mimeType: String? = null,
    val rememberThis: Boolean = true,
    val originalName: String? = null,
)

data class CardWithParts(
    val card: CardEntity,
    val parts: List<ContentPartEntity>,
    val folderName: String,
)

data class ReviewItem(
    val content: CardWithParts,
    val prompt: ContentPartEntity,
)
