package com.gostafa.raven.features.review

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gostafa.raven.core.database.*
import com.gostafa.raven.core.designsystem.RavenTheme
import com.gostafa.raven.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewUiTest {
    @get:Rule val compose = createComposeRule()
    private fun state(saveFailed: Boolean = false, submitting: Boolean = false): ReviewUiState {
        val prompt = ContentPartEntity("p", "c", "TEXT", "Remember this", orderIndex = 0, fsrsStateJson = "{}", dueAt = 0, createdAt = 0, updatedAt = 0)
        val hint = prompt.copy(id = "hint", text = "Supporting hint", rememberThis = false)
        return ReviewUiState(items = listOf(ReviewItem(CardWithParts(CardEntity("c", "f", 0, 0), listOf(prompt, hint), "Words"), prompt)), loading = false, saveFailed = saveFailed, submitting = submitting)
    }
    @Test fun ratingsAreImmediateAndHintsOpenAndCloseIndependently() {
        var rating: Rating? = null
        compose.setContent { RavenTheme { ReviewContent(state(), {}, { rating = it }, {}) } }
        compose.onNodeWithText("Good").assertIsEnabled()
        compose.onNodeWithContentDescription("Show hints").performClick()
        compose.onNodeWithText("Supporting hint").assertIsDisplayed()
        compose.onNodeWithText("Hint").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Good").performClick()
        compose.runOnIdle { assertEquals(Rating.GOOD, rating) }
    }
    @Test fun hintArrowsRespectBoundsAndReopeningResetsSelection() {
        val initial = state()
        val item = initial.current!!
        val second = item.content.parts.last().copy(id = "second", text = "Second hint", orderIndex = 2)
        val multiple = initial.copy(items = listOf(item.copy(content = item.content.copy(parts = item.content.parts + second))))
        compose.setContent { RavenTheme { ReviewContent(multiple, {}, {}, {}) } }
        compose.onNodeWithContentDescription("Show hints").performClick()
        compose.onNodeWithContentDescription("Previous hint").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next hint").performClick()
        compose.onNodeWithText("Second hint").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next hint").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous hint").performClick()
        compose.onNodeWithText("Supporting hint").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next hint").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("Show hints").performClick()
        compose.onNodeWithText("Supporting hint").assertIsDisplayed()
    }

    @Test fun savingDisablesRatingsAndFailedSaveAllowsRetry() {
        compose.setContent { RavenTheme { ReviewContent(state(submitting = true), {}, {}, {}) } }
        compose.onNodeWithText("Good").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close review").assertIsNotEnabled()
    }
    @Test fun failedSaveRemainsVisibleInRtl() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                RavenTheme { ReviewContent(state(saveFailed = true), {}, {}, {}) }
            }
        }
        compose.onNodeWithText("Good").assertIsEnabled()
        compose.onNodeWithText("Your review could not be saved.", substring = true).assertIsDisplayed()
    }
    @Test fun completedReviewClosesAfterFiveSeconds() {
        compose.mainClock.autoAdvance = false
        var exits = 0
        compose.setContent { RavenTheme { ReviewContent(ReviewUiState(loading = false), { exits++ }, {}, {}) } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4_000)
        compose.runOnIdle { assertEquals(0, exits) }
        compose.mainClock.advanceTimeBy(1_100)
        compose.runOnIdle { assertEquals(1, exits) }
    }
    @Test fun loadFailureOffersRetryInsteadOfCompletion() {
        var retries = 0
        compose.setContent { RavenTheme { ReviewContent(ReviewUiState(loading = false, loadFailed = true), {}, {}, { retries++ }) } }
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("Done").assertDoesNotExist()
    }

    @Test fun longContentScrollsWhileLargeFontRatingsRemainReachable() {
        val original = state()
        val entry = original.current!!
        val prompt = entry.prompt.copy(text = "A long review prompt. ".repeat(100))
        val longState = original.copy(items = listOf(entry.copy(prompt = prompt)))
        compose.setContent {
            val configuration = android.content.res.Configuration(LocalConfiguration.current).apply { fontScale = 2f }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                RavenTheme { ReviewContent(longState, {}, {}, {}) }
            }
        }
        compose.onNodeWithText("Good").assertIsDisplayed()
        compose.onNodeWithText("Again").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show hints").performScrollTo().performClick()
        compose.onNodeWithText("Supporting hint").performScrollTo().assertIsDisplayed()
    }
}
