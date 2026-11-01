package com.gostafa.raven.features.library

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gostafa.raven.MainActivity
import com.gostafa.raven.R
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardEditorNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var library: LibraryViewModel
    private lateinit var folderId: String
    private val folder = "Editor ${System.nanoTime() % 100000}"

    @Before fun seedFolder() {
        compose.activityRule.scenario.onActivity { library = ViewModelProvider(it)[LibraryViewModel::class.java] }
        folderId = runBlocking { library.saveFolder(null, folder, "", emptyList()) }
    }

    @After fun removeFixture() {
        if (::folderId.isInitialized) runBlocking { library.deleteFolder(folderId) }
    }

    private fun hideKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5_000) {
            var hidden = false
            compose.activityRule.scenario.onActivity { activity ->
                hidden = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
            hidden
        }
        compose.waitForIdle()
    }

    @Test fun folderAndSearchUseTheSameRestorableEditor() {
        val text = "Navigation smoke $folder"
        compose.waitUntil(10_000) { compose.onAllNodesWithText(folder).fetchSemanticsNodes().isNotEmpty() }
        val locale = if (compose.onAllNodesWithText("مجلد جديد").fetchSemanticsNodes().isNotEmpty()) "ar" else "en"
        val configuration = android.content.res.Configuration(compose.activity.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(locale))
        }
        val resources = compose.activity.createConfigurationContext(configuration).resources
        fun label(id: Int, vararg args: Any) = resources.getString(id, *args)
        compose.onNodeWithText(folder).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.new_card)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.new_card)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasText(label(R.string.text_part))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText(label(R.string.text_part))).assertIsDisplayed().assertIsFocused().performTextInput(text)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.create_card)).assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(label(R.string.create_card)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription(label(R.string.card_actions)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.edit), substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText(text)).performTextReplacement("Unfinished edit")
        hideKeyboard()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(label(R.string.discard_changes_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.discard), substring = false).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(label(R.string.save_changes)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        compose.onNodeWithContentDescription(label(R.string.search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(text)
        hideKeyboard()
        val result = hasText(text) and !hasSetTextAction()
        compose.waitUntil(10_000) { compose.onAllNodes(result).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(result).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.card_actions)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.edit), substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.save_changes)).fetchSemanticsNodes().isNotEmpty() }
        val editedField = hasSetTextAction() and hasText(text) and hasText(label(R.string.text_part))
        compose.waitUntil(10_000) { compose.onAllNodes(editedField).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(editedField).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.save_changes)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(label(R.string.save_changes)).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun selectedCardIdsReachTheReviewScreenAndSurviveRecreation() {
        val repository = dagger.hilt.android.EntryPointAccessors.fromApplication(
            compose.activity.application, com.gostafa.raven.ReviewNavigationEntryPoint::class.java,
        ).repository()
        runBlocking { repository.saveCard(folderId, null, listOf(com.gostafa.raven.core.model.PartDraft(text = "Selected review prompt"))) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(folder).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(folder).performScrollTo().performClick()
        compose.onNodeWithText("Select cards").performScrollTo().performClick()
        compose.onNodeWithText("Select all shown").performScrollTo().performClick()
        compose.onNodeWithText("Review selected").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Close review").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Selected review prompt").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Close review").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Selected review prompt").assertIsDisplayed()
    }
}
