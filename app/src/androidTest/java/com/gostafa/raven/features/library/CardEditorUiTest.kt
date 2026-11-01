package com.gostafa.raven.features.library

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gostafa.raven.core.data.RavenRepository
import com.gostafa.raven.core.data.RoomRavenRepository
import com.gostafa.raven.core.database.RavenDatabase
import com.gostafa.raven.core.designsystem.RavenTheme
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.model.PartDraft
import com.gostafa.raven.core.model.PartType
import com.gostafa.raven.core.scheduler.Fsrs6Scheduler
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardEditorUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: RavenDatabase
    private lateinit var repository: RavenRepository
    private lateinit var media: MediaStorage
    private lateinit var folderId: String
    private val temporaryFiles = mutableListOf<File>()

    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RavenDatabase::class.java).build()
        media = MediaStorage(context)
        repository = RoomRavenRepository(db, db.folderDao(), db.cardDao(), db.contentPartDao(), db.mediaDao(), db.reviewHistoryDao(), Fsrs6Scheduler(), media)
        folderId = runBlocking { repository.saveFolder(null, "English", "", emptyList()) }
    }

    @After fun close() { db.close(); temporaryFiles.forEach { it.delete() } }

    private fun editor(repo: RavenRepository = repository, handle: SavedStateHandle = SavedStateHandle(mapOf("folderId" to folderId))): CardEditorViewModel =
        CardEditorViewModel(repo, media, handle)

    private fun show(vm: CardEditorViewModel, closed: () -> Unit = {}) {
        compose.setContent { RavenTheme { CardEditorScreen(vm, closed) } }
        compose.waitUntil(5_000) { !vm.state.value.loading }
    }

    @Test fun validationExplainsEmptyTextAndEmptyCard() {
        val vm = editor()
        show(vm)
        compose.onNodeWithText("Create card").performClick()
        compose.onNodeWithText("Write some text or remove this part.").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Text")).performTextInput("   ")
        compose.onNodeWithText("Create card").performClick()
        compose.runOnIdle { assertTrue(vm.state.value.showValidation); assertFalse(vm.state.value.saving) }
        compose.onNodeWithContentDescription("Remove part 1").performScrollTo().performClick()
        compose.onNodeWithText("Create card").performClick()
        compose.onNodeWithText("Add at least one content part").assertIsDisplayed()
        assertTrue(runBlocking { repository.observeCards(folderId).first().isEmpty() })
    }

    @Test fun stablePartsKeepValuesAndReviewSettingsWhenReorderedAndRemoved() {
        val vm = editor()
        show(vm)
        val first = vm.state.value.parts.single().id!!
        compose.runOnIdle { vm.update(first) { it.copy(text = "A") }; vm.addText() }
        compose.waitUntil { vm.state.value.parts.size == 2 }
        val second = vm.state.value.parts.last().id!!
        compose.runOnIdle { vm.update(second) { it.copy(text = "B", rememberThis = false) } }
        compose.onNodeWithContentDescription("Move part 2 up").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(second, first), vm.state.value.parts.map { it.id })
            assertEquals(listOf("B", "A"), vm.state.value.parts.map { it.text })
            assertFalse(vm.state.value.parts.first().rememberThis)
        }
        compose.onNodeWithContentDescription("Remove part 2").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(second, vm.state.value.parts.single().id) }
        compose.onNodeWithText("B", substring = false).assertExists()
    }

    @Test fun dirtyCloseCanKeepEditingOrDiscardButUnchangedFormClosesImmediately() {
        val vm = editor()
        var closed = false
        show(vm) { closed = true }
        compose.runOnIdle { vm.update(vm.state.value.parts.single().id!!) { it.copy(text = "Draft") } }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.runOnIdle { assertFalse(closed); assertEquals("Draft", vm.state.value.parts.single().text) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Discard", substring = false).performClick()
        compose.waitUntil(5_000) { closed }
    }

    @Test fun pristineCloseDoesNotAskToDiscard() {
        val vm = editor()
        var closed = false
        show(vm) { closed = true }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntil(5_000) { closed }
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
    }

    @Test fun loadFailureCanRetryAndContextOnlyCardCanBeSaved() {
        var attempts = 0
        val fake = object : RavenRepository by repository {
            override fun observeFolders() = if (++attempts == 1) kotlinx.coroutines.flow.flow { error("Unavailable") }
                else repository.observeFolders()
        }
        val vm = editor(fake)
        var closed = false
        show(vm) { closed = true }
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5_000) { vm.state.value.editable }
        compose.runOnIdle { vm.update(vm.state.value.parts.single().id!!) { it.copy(text = "Hint", rememberThis = false) } }
        compose.onNodeWithText("This card contains only context.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Create card").performClick()
        compose.waitUntil(5_000) { closed }
        assertEquals(1, runBlocking { repository.observeCards(folderId).first().size })
        assertTrue(runBlocking { repository.queueFolder(folderId).isEmpty() })
    }

    @Test fun cleanupFailureAfterCommitDoesNotOfferSaveRetry() {
        val fake = object : RavenRepository by repository {
            override suspend fun discardMedia(paths: List<String>) = error("Cleanup unavailable")
        }
        val vm = editor(fake)
        var closed = false
        show(vm) { closed = true }
        compose.runOnIdle { vm.update(vm.state.value.parts.single().id!!) { it.copy(text = "Saved") }; vm.save() }
        compose.waitUntil(5_000) { closed }
        compose.runOnIdle { assertFalse(vm.state.value.saveFailed) }
        assertEquals(1, runBlocking { repository.observeCards(folderId).first().size })
    }

    @Test fun deletedCardShowsUnavailableAndDoesNotRecreateIt() {
        val cardId = runBlocking {
            repository.saveCard(folderId, null, listOf(PartDraft(text = "Original")))
            repository.observeCards(folderId).first().single().id
        }
        val vm = editor(handle = SavedStateHandle(mapOf("folderId" to folderId, "cardId" to cardId)))
        show(vm)
        compose.runOnIdle { vm.update(vm.state.value.parts.single().id!!) { it.copy(text = "Unsaved changes") } }
        runBlocking { repository.deleteCard(cardId) }
        compose.onNodeWithText("Save changes").performClick()
        compose.waitUntil(5_000) { vm.state.value.cardUnavailable }
        compose.onNodeWithText("This card is no longer available. Your changes have not been saved.").assertIsDisplayed()
        compose.onNodeWithText("Save changes").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("Unsaved changes", vm.state.value.parts.single().text) }
        assertTrue(runBlocking { repository.observeCards(folderId).first().isEmpty() })
    }

    @Test fun failedSaveKeepsDraftAndRetryCannotSubmitTwice() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val fake = object : RavenRepository by repository {
            override suspend fun saveCard(folderId: String, cardId: String?, drafts: List<PartDraft>) {
                calls++
                if (calls == 1) error("Unavailable")
                gate.await()
                repository.saveCard(folderId, cardId, drafts)
            }
        }
        val vm = editor(fake)
        var closed = false
        show(vm) { closed = true }
        compose.runOnIdle { vm.update(vm.state.value.parts.single().id!!) { it.copy(text = "Keep me") }; vm.save() }
        compose.waitUntil { vm.state.value.saveFailed }
        compose.onNodeWithText("Could not save. Your changes are still here. Please try again.").assertIsDisplayed()
        compose.runOnIdle { vm.save(); vm.save(); vm.requestClose(); assertFalse(vm.state.value.confirmDiscard) }
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, calls); assertFalse(closed); gate.complete(Unit) }
        compose.waitUntil(5_000) { closed }
        assertEquals(1, runBlocking { repository.observeCards(folderId).first().size })
    }

    @Test fun savedStateRestoresOrderValuesSettingsAndDirtyBaseline() {
        val handle = SavedStateHandle(mapOf("folderId" to folderId))
        val vm = editor(handle = handle)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RavenTheme { CardEditorScreen(vm) {} } }
        compose.waitUntil(5_000) { !vm.state.value.loading }
        compose.runOnIdle {
            vm.update(vm.state.value.parts.single().id!!) { it.copy(text = "A") }
            vm.addText()
            val id = vm.state.value.parts.last().id!!
            vm.update(id) { it.copy(text = "B", rememberThis = false) }
            vm.move(id, -1)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            val restored = editor(handle = SavedStateHandle(mapOf("folderId" to folderId, "card_editor_draft" to handle.get<String>("card_editor_draft"))))
            assertEquals(vm.state.value.draft, restored.state.value.draft)
            assertTrue(restored.state.value.dirty)
            assertEquals(listOf("B", "A"), restored.state.value.parts.map { it.text })
        }
    }

    @Test fun importSurvivesUiRestorationAndDiscardCleansFile() {
        val gate = CompletableDeferred<Unit>()
        var path: String? = null
        val fake = object : RavenRepository by repository {
            override suspend fun saveMedia(relativePath: String, mimeType: String, byteSize: Long) {
                path = relativePath
                gate.await()
                repository.saveMedia(relativePath, mimeType, byteSize)
            }
        }
        val vm = editor(fake)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RavenTheme { CardEditorScreen(vm) {} } }
        compose.waitUntil(5_000) { !vm.state.value.loading }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("raven-audio", ".mp3", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        temporaryFiles += source
        compose.runOnIdle { vm.importMedia(Uri.fromFile(source), PartType.AUDIO) }
        compose.waitUntil(5_000) { path != null }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertTrue(vm.state.value.importing); assertTrue(media.file(path!!).exists()) }
        compose.onNodeWithText("Create card").assertIsNotEnabled()
        compose.runOnIdle { vm.requestClose() }
        compose.onNodeWithText("Discard", substring = false).performClick()
        compose.waitUntil(5_000) { vm.state.value.closed }
        assertFalse(media.file(path!!).exists())
        assertEquals(1, vm.state.value.parts.size)
    }

    @Test fun cancelledPickerIsSilentAndImportFailureCanRetry() {
        val vm = editor()
        show(vm)
        compose.runOnIdle { vm.importMedia(null, PartType.IMAGE); assertFalse(vm.state.value.importFailed) }
        compose.runOnIdle { vm.importMedia(Uri.parse("file:///missing/raven-image.png"), PartType.IMAGE) }
        compose.waitUntil(5_000) { vm.state.value.importFailed }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("raven-audio", ".mp3", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        temporaryFiles += source
        compose.runOnIdle { vm.importMedia(Uri.fromFile(source), PartType.AUDIO) }
        compose.waitUntil(5_000) { !vm.state.value.importing && vm.state.value.parts.size == 2 }
        compose.runOnIdle {
            assertFalse(vm.state.value.importFailed)
            assertEquals(source.name, vm.state.value.parts.last().originalName)
            val restored = editor(handle = SavedStateHandle(mapOf("folderId" to folderId, "card_editor_draft" to kotlinx.serialization.json.Json.encodeToString(CardEditorDraft.serializer(), vm.state.value.draft!!))))
            assertEquals(source.name, restored.state.value.parts.last().originalName)
            vm.discard()
        }
        compose.waitUntil { vm.state.value.closed }
    }

    @Test fun visualLayoutKeepsSaveAndCloseReachable() {
        val args = InstrumentationRegistry.getArguments()
        val locale = args.getString("editorLocale") ?: "en"
        val fontScale = args.getString("editorFontScale")?.toFloat() ?: 1f
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(locale))
        }
        val localized = context.createConfigurationContext(configuration)
        val vm = editor()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalResources provides localized.resources,
                LocalLayoutDirection provides if (locale == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, fontScale),
            ) { RavenTheme { CardEditorScreen(vm) {} } }
        }
        compose.waitUntil(5_000) { vm.state.value.editable }
        val save = localized.getString(com.gostafa.raven.R.string.create_card)
        val close = localized.getString(com.gostafa.raven.R.string.close)
        compose.onNodeWithText(save).assertIsDisplayed()
        compose.onNodeWithContentDescription(close).assertIsDisplayed()
        compose.onNodeWithText(save).performClick()
        compose.onNodeWithText(localized.getString(com.gostafa.raven.R.string.text_required)).assertIsDisplayed()
        val name = args.getString("editorProfile") ?: "phone"
        val output = File(context.getExternalFilesDir(null), "card-editor-$name.png")
        output.outputStream().use { stream ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}
