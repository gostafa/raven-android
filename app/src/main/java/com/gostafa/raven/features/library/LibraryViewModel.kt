package com.gostafa.raven.features.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gostafa.raven.core.data.RavenRepository
import com.gostafa.raven.core.database.CardPreview
import com.gostafa.raven.core.database.FolderSummary
import com.gostafa.raven.core.settings.SettingsRepository
import com.gostafa.raven.core.archive.FolderArchiveService
import com.gostafa.raven.core.archive.ArchiveResult
import com.gostafa.raven.core.archive.ImportMode
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
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
    val settings: SettingsRepository,
    private val archiveService: FolderArchiveService? = null,
) : ViewModel() {
    val archiveBusy = MutableStateFlow(false)
    val archiveResult = MutableStateFlow<ArchiveResult?>(null)
    val archiveError = MutableStateFlow<String?>(null)
    fun exportFolders(ids: List<String>, destination: Uri) = transfer { requireNotNull(archiveService).exportFolders(ids, destination) }
    fun importFolders(source: Uri, mode: ImportMode) = transfer { requireNotNull(archiveService).importFolders(source, mode) }
    private fun transfer(operation: suspend () -> ArchiveResult) {
        if (archiveBusy.value) return
        archiveBusy.value = true
        archiveResult.value = null
        archiveError.value = null
        viewModelScope.launch {
            try { archiveResult.value = operation() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                archiveError.value = failure.message ?: "Unable to transfer archive"
            } finally { archiveBusy.value = false }
        }
    }
    val folders: StateFlow<LibraryData<List<FolderSummary>>> = observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryData())

    fun observeFolders(): Flow<LibraryData<List<FolderSummary>>> = repository.observeFolders().asLibraryData()

    fun observeCards(folderId: String): Flow<LibraryData<List<CardPreview>>> =
        repository.observeCardPreviews(folderId).asLibraryData()

    fun searchCards(query: String): Flow<LibraryData<List<CardPreview>>> =
        repository.searchCardPreviews(query.trim()).asLibraryData()

    suspend fun saveFolder(id: String?, name: String, description: String, tags: List<String>): String =
        repository.saveFolder(id, name, description, tags)

    suspend fun deleteFolder(id: String) = repository.deleteFolder(id)

    suspend fun deleteCard(id: String) = repository.deleteCard(id)

    fun setLanguage(language: String) {
        viewModelScope.launch { settings.setLanguage(language) }
    }

}
