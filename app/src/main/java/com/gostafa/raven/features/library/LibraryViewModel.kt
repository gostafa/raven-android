package com.gostafa.raven.features.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gostafa.raven.core.data.RavenRepository
import com.gostafa.raven.core.database.CardSummary
import com.gostafa.raven.core.database.FolderSummary
import com.gostafa.raven.core.media.MediaStorage
import com.gostafa.raven.core.model.CardWithParts
import com.gostafa.raven.core.model.PartDraft
import com.gostafa.raven.core.model.PartType
import com.gostafa.raven.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: RavenRepository,
    private val mediaStorage: MediaStorage,
    val settings: SettingsRepository,
) : ViewModel() {
    val folders: StateFlow<List<FolderSummary>> = repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedFolderId = MutableStateFlow<String?>(null)
    val selectedFolderId = _selectedFolderId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    val cards: StateFlow<List<CardSummary>> = _selectedFolderId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.observeCards(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val searchResults: StateFlow<List<CardSummary>> = _searchQuery.flatMapLatest { query ->
        if (query.isBlank()) flowOf(emptyList()) else repository.searchCards(query.trim())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            folders.collect { values ->
                if (_selectedFolderId.value == null && values.isNotEmpty()) _selectedFolderId.value = values.first().id
            }
        }
    }

    fun selectFolder(id: String) { _selectedFolderId.value = id }
    fun search(query: String) { _searchQuery.value = query }

    fun saveFolder(id: String?, name: String, description: String) {
        viewModelScope.launch { repository.saveFolder(id, name, description) }
    }

    fun deleteFolder(id: String) {
        viewModelScope.launch {
            repository.deleteFolder(id)
            if (_selectedFolderId.value == id) _selectedFolderId.value = null
        }
    }

    fun saveCard(folderId: String, id: String?, drafts: List<PartDraft>) {
        viewModelScope.launch { repository.saveCard(folderId, id, drafts) }
    }

    fun deleteCard(id: String) {
        viewModelScope.launch { repository.deleteCard(id) }
    }

    fun setLanguage(language: String) {
        viewModelScope.launch { settings.setLanguage(language) }
    }

    suspend fun cardWithParts(id: String): CardWithParts? = repository.cardWithParts(id)

    fun importMedia(uri: Uri, type: PartType, onImported: (PartDraft) -> Unit, onFailure: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                val mime = when (type) {
                    PartType.IMAGE -> "image/*"
                    PartType.AUDIO -> "audio/*"
                    PartType.VIDEO -> "video/*"
                    PartType.TEXT -> error("Text is not media")
                }
                val stored = mediaStorage.import(uri, mime)
                repository.saveMedia(stored.relativePath, stored.mimeType, stored.byteSize)
                PartDraft(type = type, mediaPath = stored.relativePath, mimeType = stored.mimeType)
            }.onSuccess(onImported).onFailure { onFailure() }
        }
    }
}
