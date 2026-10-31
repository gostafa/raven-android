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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryData<T>(val value: T? = null, val failed: Boolean = false)

private fun <T> Flow<T>.asLibraryData(): Flow<LibraryData<T>> =
    map { LibraryData(value = it) }
        .onStart { emit(LibraryData()) }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(LibraryData(failed = true))
        }

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: RavenRepository,
    private val mediaStorage: MediaStorage,
    val settings: SettingsRepository,
) : ViewModel() {
    val folders: StateFlow<LibraryData<List<FolderSummary>>> = observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryData())

    fun observeFolders(): Flow<LibraryData<List<FolderSummary>>> = repository.observeFolders().asLibraryData()

    fun observeCards(folderId: String): Flow<LibraryData<List<CardSummary>>> =
        repository.observeCards(folderId).asLibraryData()

    fun searchCards(query: String): Flow<LibraryData<List<CardSummary>>> =
        repository.searchCards(query.trim()).asLibraryData()

    suspend fun saveFolder(id: String?, name: String, description: String, tags: List<String>): String =
        repository.saveFolder(id, name, description, tags)

    suspend fun deleteFolder(id: String) = repository.deleteFolder(id)

    suspend fun saveCard(folderId: String, id: String?, drafts: List<PartDraft>) =
        repository.saveCard(folderId, id, drafts)

    suspend fun deleteCard(id: String) = repository.deleteCard(id)

    fun setLanguage(language: String) {
        viewModelScope.launch { settings.setLanguage(language) }
    }

    suspend fun cardWithParts(id: String): CardWithParts? = repository.cardWithParts(id)

    fun importMedia(uri: Uri, type: PartType, onImported: (PartDraft) -> Unit, onFailure: () -> Unit) {
        viewModelScope.launch {
            try {
                val mime = when (type) {
                    PartType.IMAGE -> "image/*"
                    PartType.AUDIO -> "audio/*"
                    PartType.VIDEO -> "video/*"
                    PartType.TEXT -> error("Text is not media")
                }
                val stored = mediaStorage.import(uri, mime)
                repository.saveMedia(stored.relativePath, stored.mimeType, stored.byteSize)
                onImported(PartDraft(type = type, mediaPath = stored.relativePath, mimeType = stored.mimeType))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onFailure()
            }
        }
    }
}
