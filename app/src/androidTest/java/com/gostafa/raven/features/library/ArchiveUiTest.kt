package com.gostafa.raven.features.library

import android.app.Activity
import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gostafa.raven.core.data.RoomRavenRepository
import com.gostafa.raven.core.database.FolderSummary
import com.gostafa.raven.core.database.RavenDatabase
import com.gostafa.raven.core.designsystem.RavenTheme
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.scheduler.Fsrs6Scheduler
import com.gostafa.raven.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.assertEquals

class ArchiveUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: RavenDatabase
    private lateinit var viewModel: LibraryViewModel
    private var launches = 0
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches++
            dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
        }
    }
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RavenDatabase::class.java).build()
        val repository = RoomRavenRepository(database, database.folderDao(), database.cardDao(), database.contentPartDao(), database.mediaDao(), database.reviewHistoryDao(), Fsrs6Scheduler(), MediaStorage(context))
        val settings = SettingsRepository(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
        })
        viewModel = LibraryViewModel(repository, settings)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }) {
                RavenTheme { ArchiveControls(viewModel, listOf(FolderSummary("one", "One", "", 0, 0, 0), FolderSummary("two", "Two", "", 0, 0, 0)), null, {}) }
            }
        }
    }
    @After fun close() = database.close()

    @Test fun requiresExplicitImportModeAndPickerCancellationRestoresActions() {
        compose.onNodeWithText("Import folders").performClick()
        compose.onNodeWithText("Choose file").assertIsNotEnabled()
        compose.onNodeWithText("Continue from last review").performClick()
        compose.onNodeWithText("Choose file").assertIsEnabled().performClick()
        compose.onNodeWithText("Import folders").assertIsEnabled().performClick()
        compose.onNodeWithText("Choose file").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, launches); assertEquals(null, viewModel.archiveResult.value) }
    }

    @Test fun exportSupportsSelectAllClearAndCancellation() {
        compose.onNodeWithText("Export folders").performClick()
        compose.onNodeWithText("Choose save location").assertIsNotEnabled()
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("Choose save location").assertIsEnabled()
        compose.onNodeWithText("Clear selection").performClick()
        compose.onNodeWithText("Choose save location").assertIsNotEnabled()
        compose.onNodeWithText("Two").performClick()
        compose.onNodeWithText("Choose save location").performClick()
        compose.onNodeWithText("Export folders").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, launches); assertEquals(null, viewModel.archiveResult.value) }
    }
}
