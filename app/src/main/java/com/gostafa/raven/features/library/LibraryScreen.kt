package com.gostafa.raven.features.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import com.gostafa.raven.R
import com.gostafa.raven.core.database.FolderSummary

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenFolder: (String) -> Unit,
    onSearch: () -> Unit,
    onReviewDue: () -> Unit,
) {
    val state by viewModel.folders.collectAsStateWithLifecycle()
    val folders = state.value.orEmpty()
    val gridState = rememberLazyGridState()
    var creatingFolder by rememberSaveable { mutableStateOf(false) }
    var editingFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val columns = when {
        windowWidth < 340.dp || LocalConfiguration.current.fontScale >= 1.5f -> 1
        else -> (windowWidth.value / 180).toInt().coerceAtLeast(2)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LibraryHeader(
                title = stringResource(R.string.app_name),
                leading = {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data("file:///android_asset/raven.svg")
                            .decoderFactory(SvgDecoder.Factory())
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.padding(start = 8.dp, end = 12.dp).size(28.dp),
                    )
                },
                actions = {
                    IconButton(onClick = onReviewDue, enabled = folders.any { it.dueCount > 0 }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.review_due))
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        bottomBar = {
            BottomCreateButton(
                label = stringResource(R.string.add_folder),
                onClick = { creatingFolder = true },
            )
        },
    ) { padding ->
        when {
            state.failed -> LibraryMessage(stringResource(R.string.load_failed), Modifier.padding(padding))
            state.value == null -> LibraryLoading(Modifier.padding(padding))
            folders.isEmpty() -> LibraryMessage(
                stringResource(R.string.no_folders),
                Modifier.padding(padding),
                icon = Icons.Default.Folder,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(folders, key = { it.id }) { folder ->
                    FolderTile(
                        folder = folder,
                        onOpen = { onOpenFolder(folder.id) },
                        onEdit = { editingFolderId = folder.id },
                        onDelete = { deletingFolderId = folder.id },
                    )
                }
            }
        }
    }

    if (creatingFolder) {
        FolderEditorDialog(
            folder = null,
            viewModel = viewModel,
            onDismiss = { creatingFolder = false },
            onSaved = { id -> creatingFolder = false; onOpenFolder(id) },
        )
    }
    folders.firstOrNull { it.id == editingFolderId }?.let { folder ->
        FolderEditorDialog(
            folder = folder,
            viewModel = viewModel,
            onDismiss = { editingFolderId = null },
            onSaved = { editingFolderId = null },
        )
    }
    deletingFolderId?.let { id ->
        DeleteConfirmation(
            message = stringResource(R.string.confirm_delete_folder),
            onDismiss = { deletingFolderId = null },
            onDelete = { viewModel.deleteFolder(id) },
        )
    }
    if (showSettings) SettingsDialog(viewModel, onDismiss = { showSettings = false })
}

@Composable
private fun FolderTile(folder: FolderSummary, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                }
                OverflowMenu(
                    label = stringResource(R.string.folder_actions, folder.name),
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            }
            Text(
                folder.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (folder.description.isNotBlank()) {
                Text(
                    folder.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(pluralStringResource(R.plurals.card_count, folder.cardCount, folder.cardCount), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.due_count, folder.dueCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
