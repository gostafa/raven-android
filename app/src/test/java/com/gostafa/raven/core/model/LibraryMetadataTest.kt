package com.gostafa.raven.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMetadataTest {
    private fun part(reviews: Int, successes: Int, remember: Boolean = true) =
        PartRecall("part", "card", "folder", remember, reviews, successes)

    @Test fun emptyAndContextOnlyCardsHaveZeroRecall() {
        assertEquals(0, cardRecallPercent(emptyList()))
        assertEquals(0, cardRecallPercent(listOf(part(10, 10, false))))
    }

    @Test fun recallUsesSuccessFractionsAndIncludesUnreviewedParts() {
        assertEquals(0, cardRecallPercent(listOf(part(0, 0))))
        assertEquals(0, cardRecallPercent(listOf(part(1, 0))))
        assertEquals(100, cardRecallPercent(listOf(part(1, 1))))
        assertEquals(75, cardRecallPercent(listOf(part(2, 1), part(1, 1))))
        assertEquals(50, cardRecallPercent(listOf(part(20, 20), part(1, 0))))
        assertEquals(50, cardRecallPercent(listOf(part(20, 20), part(0, 0))))
        assertEquals(25, cardRecallPercent(listOf(part(20, 20), part(0, 0), part(0, 0), part(0, 0))))
        assertEquals(100, cardRecallPercent(listOf(part(1, 1), part(10, 0, false))))
    }

    @Test fun roundsOnlyAfterAveragingLikeRaven() {
        assertEquals(42, cardRecallPercent(listOf(part(3, 1), part(2, 1))))
        // Raven counts the rounded score, including a success fraction that rounds to 100.
        assertEquals(100, cardRecallPercent(listOf(part(201, 200))))
        assertEquals(99, cardRecallPercent(listOf(part(100, 99))))
    }

    @Test fun tagsAreTrimmedDeduplicatedAndPreserveFirstSpelling() {
        assertEquals(listOf("Language", "English", "لغة"), parseTags(" Language, English,,language, لغة،لغة "))
        assertEquals(emptyList<String>(), parseTags(" , ، "))
        assertEquals(listOf("English"), decodeTags("[\" English \",\"english\"]"))
        assertEquals(emptyList<String>(), decodeTags("invalid JSON"))
    }

    @Test fun tagFilteringIsCaseInsensitiveAndRequiresEverySelection() {
        val tags = listOf("English", "Language")
        assertTrue(matchesTags(tags, emptyList()))
        assertTrue(matchesTags(tags, listOf("english", "LANGUAGE")))
        assertFalse(matchesTags(tags, listOf("english", "Travel")))
        assertFalse(matchesTags(emptyList(), listOf("English")))
    }
}
