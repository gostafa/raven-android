package com.gostafa.raven.features.library

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gostafa.raven.core.data.RavenRepository
import com.gostafa.raven.core.data.RoomRavenRepository
import com.gostafa.raven.core.database.FolderSummary
import com.gostafa.raven.core.database.RavenDatabase
import com.gostafa.raven.core.designsystem.RavenTheme
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.scheduler.Fsrs6Scheduler
import com.gostafa.raven.core.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: RavenDatabase
    private lateinit var baseRepository: RavenRepository
    private lateinit var media: MediaStorage
    private val settings = SettingsRepository(object : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    })

    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RavenDatabase::class.java).build()
        media = MediaStorage(context)
        baseRepository = RoomRavenRepository(db, db.folderDao(), db.cardDao(), db.contentPartDao(), db.mediaDao(), db.reviewHistoryDao(), Fsrs6Scheduler(), media)
    }

    @After fun close() = db.close()

    @Test fun tagTapSelectsFilterWithoutOpeningFolder() {
        var opened = false
        var selected by mutableStateOf(emptyList<String>())
        compose.setContent {
            RavenTheme {
                FolderTile(
                    FolderSummary("id", "English", "Test", 54, 20, 0, "[\"Language\",\"English\"]", 34),
                    onOpen = { opened = true }, onEdit = {}, onDelete = {},
                    selectedTags = selected, onTagClick = { selected = listOf(it) },
                )
            }
        }
        compose.onNodeWithContentDescription("Filter by tag English").performClick().assertIsSelected()
        compose.runOnIdle { assertFalse(opened); assertEquals(listOf("English"), selected) }
    }

    @Test fun editorRestoresFieldsAndRetainsEntriesAfterFailedSave() {
        val repository = object : RavenRepository by baseRepository {
            override suspend fun saveFolder(id: String?, name: String, description: String, tags: List<String>): String = error("Save failed")
        }
        val vm = LibraryViewModel(repository, media, settings)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            RavenTheme { FolderEditorDialog(null, vm, onDismiss = {}, onSaved = {}) }
        }
        compose.onNodeWithText("Create folder").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Folder name (required)").performScrollTo().performTextInput("English")
        compose.onNodeWithText("Description (optional)").performScrollTo().performTextInput("Test")
        compose.onNodeWithText("Tags (optional)").performScrollTo().performTextInput("Language, English")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Folder name (required)").performScrollTo().assertTextContains("English")
        compose.onNodeWithText("Description (optional)").performScrollTo().assertTextContains("Test")
        compose.onNodeWithText("Tags (optional)").performScrollTo().assertTextContains("Language, English")
        compose.onNodeWithContentDescription("Remove tag Language").performScrollTo().performClick()
        compose.onNodeWithText("Tags (optional)").assertTextContains("English")
        compose.onNodeWithText("Create folder").performScrollTo().performClick()
        compose.onNodeWithText("Could not save. Your changes are still here. Please try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Folder name (required)").performScrollTo().assertTextContains("English")
        compose.onNodeWithText("Tags (optional)").performScrollTo().assertTextContains("English")
    }

    @Test fun libraryFiltersSurviveRestorationAndCanBeCleared() {
        runBlocking {
            baseRepository.saveFolder(null, "English folder", "", listOf("Language", "English"))
            baseRepository.saveFolder(null, "Spanish folder", "", listOf("Language", "Spanish"))
        }
        val vm = LibraryViewModel(baseRepository, media, settings)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            RavenTheme { LibraryScreen(vm, onOpenFolder = {}, onSearch = {}, onReviewDue = {}) }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("English folder").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Filter by tag English").performScrollTo().performClick()
        compose.onNodeWithText("Spanish folder").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Remove tag English").assertIsDisplayed()
        compose.onNodeWithText("Spanish folder").assertDoesNotExist()
        compose.onNodeWithText("Clear all").performClick()
        compose.onNodeWithText("Spanish folder").assertIsDisplayed()
    }

    @Test fun savingDisablesSubmissionAndDismissalUntilCompletion() {
        val gate = CompletableDeferred<Unit>()
        var saves = 0
        var savedId: String? = null
        var dismissed = false
        val repository = object : RavenRepository by baseRepository {
            override suspend fun saveFolder(id: String?, name: String, description: String, tags: List<String>): String {
                saves++
                gate.await()
                return "saved"
            }
        }
        val vm = LibraryViewModel(repository, media, settings)
        compose.setContent {
            RavenTheme { FolderEditorDialog(null, vm, onDismiss = { dismissed = true }, onSaved = { savedId = it }) }
        }
        compose.onNodeWithText("Folder name (required)").performTextInput("English")
        compose.onNodeWithText("Create folder").performScrollTo().performClick()
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, saves); assertFalse(dismissed); gate.complete(Unit) }
        compose.waitUntil { savedId == "saved" }
    }
}
