package com.gostafa.raven.features.library

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gostafa.raven.core.data.RoomRavenRepository
import com.gostafa.raven.core.database.RavenDatabase
import com.gostafa.raven.core.designsystem.RavenTheme
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.model.*
import com.gostafa.raven.core.scheduler.Fsrs6Scheduler
import com.gostafa.raven.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class BrowserUiTest {
    @get:Rule val compose = createAndroidComposeRule<com.gostafa.raven.UiTestActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RavenDatabase
    private lateinit var repo: RoomRavenRepository
    private lateinit var vm: LibraryViewModel
    private lateinit var folder: String
    private lateinit var other: String
    private lateinit var cardId: String
    private val settings = SettingsRepository(object : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    })
    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, RavenDatabase::class.java).build()
        repo = RoomRavenRepository(db, db.folderDao(), db.cardDao(), db.contentPartDao(), db.mediaDao(), db.reviewHistoryDao(), Fsrs6Scheduler(), MediaStorage(context))
        folder = repo.saveFolder(null, "Everyday English", "Words and little things worth remembering.", listOf("Language", "English"))
        other = repo.saveFolder(null, "Design notes", "Ideas for thoughtful interfaces.", listOf("Design"))
        repo.saveCard(folder, null, listOf(PartDraft(text = "A little more remembered"), PartDraft(text = "One small step, every day.", rememberThis = false)))
        cardId = repo.observeCards(folder).first().single().id
        vm = LibraryViewModel(repo, settings)
    }
    @After fun cleanup() { db.close() }

    @Test fun filtersAndSelectionSurviveRestorationAndReconcileVisibleCards() {
        lateinit var browser: CardBrowserState
        val cards = runBlocking { repo.observeCardPreviews(folder).first() }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            RavenTheme {
                browser = rememberCardBrowserState("test")
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    val visible = selectCards(cards, browser.sort, browser.kinds.toSet(), RecallRange(browser.minimum, browser.maximum))
                    CardBrowserToolbar(browser, visible, vm, {})
                }
            }
        }
        compose.onNodeWithText("Select cards").performClick()
        compose.onNodeWithText("Select all shown").performScrollTo().performClick()
        compose.onNodeWithText("1 selected").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithText("Filters", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Image", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Done", substring = false).performClick()
        compose.onNodeWithText("0 selected").assertExists()
        compose.runOnIdle { assertEquals(listOf(PartType.IMAGE), browser.kinds) }
    }

    @Test fun copyPasteAndMoveUseTheDestinationPicker() {
        val ref = CardReference(folder, cardId)
        compose.setContent { RavenTheme {
            Column { ClipboardActions(vm); CardTransferHost(vm) }
        } }
        compose.runOnIdle { vm.copyCards(listOf(ref)) }
        compose.onNodeWithText("Paste (1)").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Design notes").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Design notes").performClick()
        compose.onNode(hasText("Paste") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { vm.cardTransferRequest.value == null }
        assertEquals(1, runBlocking { repo.observeCards(other).first().size })
        compose.runOnIdle { vm.requestMove(listOf(ref)) }
        compose.onNodeWithText("Design notes").performClick()
        compose.onNodeWithText("Move", substring = false).performClick()
        compose.waitUntil(5_000) { vm.cardTransferRequest.value == null }
        assertEquals(other, runBlocking { repo.cardWithParts(cardId)!!.card.folderId })
        assertEquals(listOf(CardReference(other, cardId)), vm.clipboard.value)
    }

    @Test fun visualLibraryAndSettings() {
        val args = InstrumentationRegistry.getArguments()
        val locale = Locale.forLanguageTag(args.getString("uiLocale") ?: "en")
        val scale = args.getString("uiFontScale")?.toFloat() ?: 1f
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale); fontScale = scale }
        val localized = context.createConfigurationContext(configuration)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalResources provides localized.resources,
                LocalLayoutDirection provides if (locale.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, scale)) {
                RavenTheme { LibraryScreen(vm, {}, {}, {}) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Everyday English").fetchSemanticsNodes().isNotEmpty() }
        capture("library")
        compose.onNodeWithContentDescription(localized.getString(com.gostafa.raven.R.string.settings)).performClick()
        compose.onNodeWithText(localized.getString(com.gostafa.raven.R.string.settings), substring = false).assertIsDisplayed()
        capture("settings", dialog = true)
    }

    @Test fun visualFolderAndFilterDialog() {
        val resources = profileContext().resources
        compose.setContent { Profile { FolderScreen(folder, vm, {}, {}, {}, {}, { _, _ -> }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(resources.getString(com.gostafa.raven.R.string.sort_newest)).fetchSemanticsNodes().isNotEmpty() }
        capture("folder")
        compose.onNodeWithText(resources.getString(com.gostafa.raven.R.string.filters), substring = false).performScrollTo().performClick()
        compose.onNodeWithText(resources.getString(com.gostafa.raven.R.string.recall_percentage), substring = false).performScrollTo().assertIsDisplayed()
        capture("filters", dialog = true)
    }

    private fun profileContext(): Context {
        val args = InstrumentationRegistry.getArguments()
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(args.getString("uiLocale") ?: "en"))
            fontScale = args.getString("uiFontScale")?.toFloat() ?: 1f
        }
        return context.createConfigurationContext(configuration)
    }

    @Composable private fun Profile(content: @Composable () -> Unit) {
        val localized = profileContext()
        val configuration = localized.resources.configuration
        val density = LocalDensity.current
        CompositionLocalProvider(LocalConfiguration provides configuration, LocalResources provides localized.resources,
            LocalLayoutDirection provides if (configuration.locales[0].language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
            LocalDensity provides Density(density.density, configuration.fontScale)) {
            RavenTheme(content)
        }
    }

    @Test fun globalSearchShowsFoldersAndAppliesSharedCardFilters() {
        var opened: String? = null
        compose.setContent { RavenTheme { SearchScreen(vm, {}, {}, { _, _ -> }, onOpenFolder = { opened = it }) } }
        compose.onNode(hasSetTextAction()).performTextInput("Design")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Design notes").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Design notes").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(other, opened) }
        compose.onNode(hasSetTextAction()).performTextReplacement("remembered")
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Card actions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Filters", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Image", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Done", substring = false).performClick()
        compose.onNodeWithText("No matching cards found").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Clear filters", substring = false).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Card actions").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val root = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        val bitmap = root.captureToImage().asAndroidBitmap()
        val profile = InstrumentationRegistry.getArguments().getString("uiProfile") ?: "phone"
        val file = File(context.getExternalFilesDir(null), "raven-$profile-$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Sample the interior away from controls: app-owned surfaces must remain black.
        assertEquals(android.graphics.Color.BLACK, bitmap.getPixel(bitmap.width / 2, (bitmap.height - 4).coerceAtLeast(0)))
    }
}
