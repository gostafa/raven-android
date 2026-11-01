package com.gostafa.raven.core.model

import com.gostafa.raven.core.database.CardPreview
import com.gostafa.raven.core.database.FolderSummary
import java.text.Collator
import java.util.Locale

enum class FolderSort { DEFAULT, TAGS_ASC, TAGS_DESC, CARDS_ASC, CARDS_DESC, REVIEW_ASC, REVIEW_DESC }
enum class CardSort { CREATED_DESC, CREATED_ASC, REVIEW_ASC, REVIEW_DESC, RECALL_ASC, RECALL_DESC }

data class RecallRange(val minimum: Int = 0, val maximum: Int = 100) {
    init { require(minimum in 0..100 && maximum in minimum..100) }
    operator fun contains(percent: Int) = percent in minimum..maximum
}

fun selectFolders(folders: List<FolderSummary>, tags: List<String>, sort: FolderSort, locale: Locale): List<FolderSummary> {
    val matching = folders.filter { matchesTags(it.tags, tags) }
    if (sort == FolderSort.DEFAULT) return matching
    val collator = Collator.getInstance(locale)
    return matching.sortedWith { left, right ->
        val comparison = when (sort) {
            FolderSort.TAGS_ASC -> left.tags.size.compareTo(right.tags.size)
            FolderSort.TAGS_DESC -> right.tags.size.compareTo(left.tags.size)
            FolderSort.CARDS_ASC -> left.cardCount.compareTo(right.cardCount)
            FolderSort.CARDS_DESC -> right.cardCount.compareTo(left.cardCount)
            FolderSort.REVIEW_ASC -> compareReview(left.nextReviewAt, right.nextReviewAt, false)
            FolderSort.REVIEW_DESC -> compareReview(left.nextReviewAt, right.nextReviewAt, true)
            else -> 0
        }
        comparison.takeIf { it != 0 } ?: collator.compare(left.name, right.name).takeIf { it != 0 } ?: left.id.compareTo(right.id)
    }
}

private fun compareReview(left: Long?, right: Long?, descending: Boolean): Int = when {
    left == right -> 0
    left == null -> 1
    right == null -> -1
    descending -> right.compareTo(left)
    else -> left.compareTo(right)
}

fun selectCards(cards: List<CardPreview>, sort: CardSort, kinds: Set<PartType>, range: RecallRange): List<CardPreview> =
    cards.filter { card ->
        card.summary.recallPercent in range && (kinds.isEmpty() || card.parts.any { part -> kinds.any { it.name == part.type } })
    }.sortedWith { left, right ->
        val a = left.summary
        val b = right.summary
        val comparison = when (sort) {
            CardSort.CREATED_ASC -> a.createdAt.compareTo(b.createdAt)
            CardSort.CREATED_DESC -> b.createdAt.compareTo(a.createdAt)
            CardSort.REVIEW_ASC -> compareReview(a.nextReviewAt, b.nextReviewAt, false)
            CardSort.REVIEW_DESC -> compareReview(a.nextReviewAt, b.nextReviewAt, true)
            CardSort.RECALL_ASC -> a.recallPercent.compareTo(b.recallPercent)
            CardSort.RECALL_DESC -> b.recallPercent.compareTo(a.recallPercent)
        }
        comparison.takeIf { it != 0 } ?: a.id.compareTo(b.id)
    }

fun reconcileSelection(selected: List<String>, visible: List<String>): List<String> {
    val available = visible.toSet()
    return selected.distinct().filter { it in available }
}

data class CardReference(val folderId: String, val cardId: String)
enum class TransferOperation { COPY, MOVE }
data class CardTransferInput(val operation: TransferOperation, val sources: List<CardReference>, val destinationFolderId: String)
data class CardTransferEntry(val source: CardReference, val destination: CardReference)
data class CardTransferResult(val entries: List<CardTransferEntry>)
