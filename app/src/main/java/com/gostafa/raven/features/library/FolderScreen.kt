package com.gostafa.raven.features.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gostafa.raven.R
import com.gostafa.raven.core.database.CardPreview

@Composable
fun FolderScreen(
    folderId: String,
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onPracticeFolder: (String) -> Unit,
    onPracticeCard: (String) -> Unit,
    onCreateCard: (String) -> Unit,
    onEditCard: (String, String) -> Unit,
) {
    val foldersFlow = remember(viewModel, folderId) { viewModel.observeFolders() }
    val folders by foldersFlow.collectAsStateWithLifecycle(initialValue = LibraryData())
    val folder = folders.value?.firstOrNull { it.id == folderId }
    val cardsFlow = remember(viewModel, folderId) { viewModel.observeCards(folderId) }
    val cards by cardsFlow.collectAsStateWithLifecycle(initialValue = LibraryData<List<CardPreview>>())
    val listState = rememberLazyListState()
    var editingFolder by rememberSaveable(folderId) { mutableStateOf(false) }
    var deletingFolder by rememberSaveable(folderId) { mutableStateOf(false) }
    var deletingCardId by rememberSaveable(folderId) { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LibraryHeader(title = folder?.name ?: stringResource(R.string.folder), onBack = onBack) {
                if (folder != null) {
                    OverflowMenu(
                        label = stringResource(R.string.folder_actions, folder.name),
                        onEdit = { editingFolder = true },
                        onDelete = { deletingFolder = true },
                    )
                }
            }
        },
        bottomBar = {
            if (folder != null) BottomCreateButton(stringResource(R.string.new_card), onClick = { onCreateCard(folderId) })
        },
    ) { padding ->
        when {
            folders.failed -> LibraryMessage(stringResource(R.string.load_failed), Modifier.padding(padding))
            folders.value == null -> LibraryLoading(Modifier.padding(padding))
            folder == null -> LibraryMessage(
                stringResource(R.string.folder_unavailable),
                Modifier.padding(padding),
                icon = Icons.Default.Folder,
                action = { TextButton(onClick = onBack) { Text(stringResource(R.string.return_to_library)) } },
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "folder_header") {
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (folder.tags.isNotEmpty()) FolderTags(folder.tags)
                        if (folder.description.isNotBlank()) {
                            Text(folder.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            pluralStringResource(R.plurals.card_count, folder.cardCount, folder.cardCount) + " · " + stringResource(R.string.due_count, folder.dueCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { onPracticeFolder(folderId) },
                            enabled = folder.cardCount > 0,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.practice_folder))
                        }
                    }
                }
                when {
                    cards.failed -> item { Text(stringResource(R.string.load_failed), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    cards.value == null -> item { LibraryLoading(Modifier.padding(32.dp)) }
                    cards.value.orEmpty().isEmpty() -> item {
                        Text(
                            stringResource(R.string.no_cards),
                            modifier = Modifier.padding(vertical = 32.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> items(cards.value.orEmpty(), key = { it.summary.id }) { card ->
                        CardRow(
                            card = card,
                            onEdit = { onEditCard(folderId, card.summary.id) },
                            onDelete = { deletingCardId = card.summary.id },
                            onPractice = { onPracticeCard(card.summary.id) },
                        )
                    }
                }
            }
        }
    }

    if (editingFolder && folder != null) {
        FolderEditorDialog(folder, viewModel, onDismiss = { editingFolder = false }, onSaved = { editingFolder = false })
    }
    if (deletingFolder) {
        DeleteConfirmation(
            message = stringResource(R.string.confirm_delete_folder),
            onDismiss = { deletingFolder = false },
            onDelete = { viewModel.deleteFolder(folderId) },
            onDeleted = onBack,
        )
    }
    deletingCardId?.let { id ->
        DeleteConfirmation(
            message = stringResource(R.string.confirm_delete_card),
            onDismiss = { deletingCardId = null },
            onDelete = { viewModel.deleteCard(id) },
        )
    }
}
