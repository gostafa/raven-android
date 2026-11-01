package com.gostafa.raven.features.library

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gostafa.raven.core.data.RavenRepository
import com.gostafa.raven.core.data.CardUnavailableException
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.model.PartDraft
import com.gostafa.raven.core.model.PartType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class CardEditorDraft(
    val parts: List<PartDraft>,
    val initialParts: List<PartDraft>,
    val importedPaths: List<String> = emptyList(),
    val folderName: String = "",
)

internal data class CardEditorState(
    val draft: CardEditorDraft? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val discarding: Boolean = false,
    val importing: Boolean = false,
    val saveFailed: Boolean = false,
    val cardUnavailable: Boolean = false,
    val importFailed: Boolean = false,
    val showValidation: Boolean = false,
    val confirmDiscard: Boolean = false,
    val closed: Boolean = false,
    val focusPartId: String? = null,
    val focusRequest: Int = 0,
) {
    val parts get() = draft?.parts.orEmpty()
    val dirty get() = draft?.let { it.parts != it.initialParts } == true
    val editable get() = !loading && !loadFailed && !saving && !discarding && !closed && !cardUnavailable
}

@HiltViewModel
internal class CardEditorViewModel @Inject constructor(
    private val repository: RavenRepository,
    private val mediaStorage: MediaStorage,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val cardId: String? = savedState["cardId"]
    private val folderId: String = checkNotNull(savedState["folderId"])
    private val restored = savedState.get<String>(DRAFT_KEY)?.let {
        runCatching { Json.decodeFromString<CardEditorDraft>(it) }.getOrNull()
    }
    private val mutableState = MutableStateFlow(CardEditorState(draft = restored, loading = restored == null))
    val state = mutableState.asStateFlow()
    private var importJob: Job? = null
    private var loadJob: Job? = null

    init { if (restored == null) load() }

    fun load() {
        if (state.value.draft != null || state.value.closed || loadJob?.isActive == true) return
        mutableState.value = CardEditorState()
        loadJob = viewModelScope.launch {
            try {
                val content = cardId?.let { checkNotNull(repository.cardWithParts(it)) }
                val folder = repository.observeFolders().first()
                    .firstOrNull { it.id == (content?.card?.folderId ?: folderId) }
                checkNotNull(folder)
                val parts = content?.parts?.map { part ->
                    PartDraft(part.id, PartType.valueOf(part.type), part.text, part.mediaPath, part.mimeType, part.rememberThis, part.originalName)
                } ?: listOf(PartDraft(id = newId()))
                val draft = CardEditorDraft(parts, parts, folderName = folder.name)
                mutableState.value = CardEditorState(draft = draft, loading = false,
                    focusPartId = if (cardId == null) parts.first().id else null)
                persist(draft)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = CardEditorState(loading = false, loadFailed = true)
            }
        }
    }

    private fun persist(draft: CardEditorDraft) { savedState[DRAFT_KEY] = Json.encodeToString(draft) }

    private fun change(transform: (CardEditorDraft) -> CardEditorDraft) {
        val before = state.value
        if (!before.editable) return
        val draft = transform(checkNotNull(before.draft))
        persist(draft)
        mutableState.value = before.copy(draft = draft, saveFailed = false)
    }

    fun update(id: String, transform: (PartDraft) -> PartDraft) = change { draft ->
        draft.copy(parts = draft.parts.map { if (it.id == id) transform(it) else it })
    }

    fun addText() {
        val part = PartDraft(id = newId())
        change { it.copy(parts = it.parts + part) }
        focus(part.id)
    }

    fun move(id: String, offset: Int) = change { draft ->
        val parts = draft.parts.toMutableList()
        val index = parts.indexOfFirst { it.id == id }
        if (index >= 0 && index + offset in parts.indices) parts.add(index + offset, parts.removeAt(index))
        draft.copy(parts = parts)
    }

    fun remove(id: String) {
        if (!state.value.editable) return
        val part = state.value.parts.firstOrNull { it.id == id } ?: return
        change { it.copy(parts = it.parts.filterNot { part -> part.id == id }) }
        if (part.mediaPath in state.value.draft?.importedPaths.orEmpty()) {
            viewModelScope.launch { cleanup(listOfNotNull(part.mediaPath)) }
        }
    }

    fun invalid(part: PartDraft): Boolean = if (part.type == PartType.TEXT) part.text.isBlank()
        else part.mediaPath.isNullOrBlank() || !mediaStorage.file(part.mediaPath).isFile

    private fun focus(id: String?) {
        mutableState.value = state.value.copy(focusPartId = id, focusRequest = state.value.focusRequest + 1)
    }

    fun importMedia(uri: Uri?, type: PartType) {
        if (uri == null || !state.value.editable || state.value.importing) return
        mutableState.value = state.value.copy(importing = true, importFailed = false)
        importJob = viewModelScope.launch {
            var path: String? = null
            var attached = false
            try {
                val stored = mediaStorage.import(uri, "${type.name.lowercase()}/*")
                path = stored.relativePath
                change { it.copy(importedPaths = it.importedPaths + stored.relativePath) }
                repository.saveMedia(stored.relativePath, stored.mimeType, stored.byteSize)
                if (state.value.closed) return@launch
                val part = PartDraft(id = newId(), type = type, mediaPath = stored.relativePath, mimeType = stored.mimeType, originalName = stored.originalName)
                change { it.copy(parts = it.parts + part) }
                attached = true
                focus(part.id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = state.value.copy(importFailed = true)
            } finally {
                if (!attached && path != null) withContext(NonCancellable) { cleanup(listOf(path)) }
                mutableState.value = state.value.copy(importing = false)
            }
        }
    }

    fun save() {
        val current = state.value
        if (!current.editable || current.importing) return
        val invalid = current.parts.firstOrNull(::invalid)
        if (current.parts.isEmpty() || invalid != null) {
            mutableState.value = current.copy(showValidation = true)
            focus(invalid?.id)
            return
        }
        mutableState.value = current.copy(saving = true, saveFailed = false, confirmDiscard = false)
        viewModelScope.launch {
            try {
                repository.saveCard(folderId, cardId, current.parts)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = state.value.copy(saving = false,
                    saveFailed = error !is CardUnavailableException, cardUnavailable = error is CardUnavailableException)
                return@launch
            }
            // Never report post-commit cleanup as a failed save.
            cleanup(current.draft?.importedPaths.orEmpty())
            savedState.remove<String>(DRAFT_KEY)
            mutableState.value = state.value.copy(saving = false, closed = true)
        }
    }

    fun requestClose() {
        val current = state.value
        if (current.saving || current.discarding || current.closed) return
        if (current.dirty || current.importing) mutableState.value = current.copy(confirmDiscard = true)
        else discard()
    }

    fun keepEditing() { mutableState.value = state.value.copy(confirmDiscard = false) }

    fun discard() {
        if (state.value.saving || state.value.discarding || state.value.closed) return
        mutableState.value = state.value.copy(discarding = true, confirmDiscard = false)
        loadJob?.cancel()
        importJob?.cancel()
        viewModelScope.launch {
            loadJob?.join()
            importJob?.join()
            cleanup(state.value.draft?.importedPaths.orEmpty())
            savedState.remove<String>(DRAFT_KEY)
            mutableState.value = state.value.copy(closed = true, discarding = false)
        }
    }

    private suspend fun cleanup(paths: List<String>) {
        try { repository.discardMedia(paths) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            android.util.Log.w("CardEditor", "Could not clean up unused media", error)
        }
    }

    private companion object {
        const val DRAFT_KEY = "card_editor_draft"
        fun newId(): String = UUID.randomUUID().toString()
    }
}
