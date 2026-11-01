package com.gostafa.raven.core.model

import com.gostafa.raven.core.database.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LibrarySelectionTest {
    private fun folder(id: String, tags: List<String> = emptyList(), cards: Int = 0, due: Long? = null, name: String = id) =
        FolderSummary(id, name, "", cards, 0, 0, tagsJson = kotlinx.serialization.json.Json.encodeToString(tags), nextReviewAt = due)
    private fun card(id: String, recall: Int, created: Long, due: Long?, vararg types: PartType) = CardPreview(
        CardSummary(id, "f", 0, id, types.size, 0, recall, created, due),
        types.mapIndexed { i, type -> ContentPartEntity("$id-$i", id, type.name, orderIndex = i, fsrsStateJson = "{}", dueAt = 0, createdAt = 0, updatedAt = 0) },
    )

    @Test fun recallBoundsAreInclusiveAndMediaTypesUseOrAcrossAnAndWithRecall() {
        val values = listOf(card("a", 0, 1, 1, PartType.TEXT), card("b", 50, 2, 2, PartType.IMAGE),
            card("c", 80, 3, 3, PartType.TEXT, PartType.AUDIO), card("d", 100, 4, null, PartType.VIDEO))
        fun select(min: Int, max: Int, vararg kinds: PartType) = selectCards(values, CardSort.CREATED_ASC, kinds.toSet(), RecallRange(min, max)).map { it.summary.id }
        assertEquals(listOf("a"), select(0, 0))
        assertEquals(listOf("a", "b"), select(0, 50))
        assertEquals(listOf("c", "d"), select(80, 100))
        assertEquals(listOf("b", "c"), select(50, 80, PartType.IMAGE, PartType.AUDIO))
        assertTrue(select(0, 50, PartType.VIDEO).isEmpty())
        assertTrue(runCatching { RecallRange(90, 80) }.isFailure)
        assertTrue(runCatching { RecallRange(-1, 101) }.isFailure)
    }

    @Test fun folderTagsRequireEverySelectedTagAndDefaultOrderIsRetained() {
        val values = listOf(folder("b", listOf("Language", "English")), folder("a", listOf("Language")))
        assertEquals(listOf("b", "a"), selectFolders(values, emptyList(), FolderSort.DEFAULT, Locale.ENGLISH).map { it.id })
        assertEquals(listOf("b"), selectFolders(values, listOf("language", "ENGLISH"), FolderSort.DEFAULT, Locale.ENGLISH).map { it.id })
    }

    @Test fun sortUsesCreationTimeAndPutsUnscheduledCardsLastInBothDirections() {
        val values = listOf(card("a", 10, 20, null), card("b", 50, 10, 10), card("c", 100, 30, 20))
        fun order(sort: CardSort) = selectCards(values, sort, emptySet(), RecallRange()).map { it.summary.id }
        assertEquals(listOf("c", "a", "b"), order(CardSort.CREATED_DESC))
        assertEquals(listOf("b", "a", "c"), order(CardSort.CREATED_ASC))
        assertEquals(listOf("b", "c", "a"), order(CardSort.REVIEW_ASC))
        assertEquals(listOf("c", "b", "a"), order(CardSort.REVIEW_DESC))
        assertEquals(listOf("c", "b", "a"), order(CardSort.RECALL_DESC))
    }

    @Test fun folderTiesUseLocaleCollationAndReviewNullsRemainLast() {
        val values = listOf(folder("z", name = "Zulu"), folder("a", name = "Älg"), folder("b", name = "Beta"))
        assertEquals(listOf("a", "b", "z"), selectFolders(values, emptyList(), FolderSort.CARDS_ASC, Locale.ENGLISH).map { it.id })
        assertEquals(listOf("b", "z", "a"), selectFolders(values, emptyList(), FolderSort.CARDS_ASC, Locale.forLanguageTag("sv")).map { it.id })
        val reviews = listOf(folder("none"), folder("early", due = 1), folder("late", due = 2))
        assertEquals(listOf("late", "early", "none"), selectFolders(reviews, emptyList(), FolderSort.REVIEW_DESC, Locale.ENGLISH).map { it.id })
    }

    @Test fun selectionDropsHiddenAndDeletedIdsAndDeduplicates() {
        assertEquals(listOf("b"), reconcileSelection(listOf("a", "b", "b", "gone"), listOf("b", "c")))
    }
}
