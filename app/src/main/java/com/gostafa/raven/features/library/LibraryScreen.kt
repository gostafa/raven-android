package com.gostafa.raven.features.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import com.gostafa.raven.R
import com.gostafa.raven.core.model.matchesTags

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
    var selectedTags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val availableTags = folders.flatMap { it.tags }.map { it.lowercase() }.toSet()
    LaunchedEffect(availableTags, state.value != null) {
        if (state.value != null && !state.failed) {
            selectedTags = selectedTags.filter { it.lowercase() in availableTags }
        }
    }
    val visibleFolders = folders.filter { matchesTags(it.tags, selectedTags) }
    val toggleTag: (String) -> Unit = { tag ->
        selectedTags = if (selectedTags.any { it.equals(tag, ignoreCase = true) }) {
            selectedTags.filterNot { it.equals(tag, ignoreCase = true) }
        } else selectedTags + tag
    }
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val columns = if (windowWidth < 600.dp || LocalConfiguration.current.fontScale >= 1.5f) GridCells.Fixed(1) else GridCells.Adaptive(320.dp)

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
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (selectedTags.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.filter_tags), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FolderTags(selectedTags, Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), selectedTags = selectedTags, onTagClick = toggleTag, removable = true)
                    TextButton(onClick = { selectedTags = emptyList() }) { Text(stringResource(R.string.clear_all)) }
                }
            }
            when {
                state.failed -> LibraryMessage(stringResource(R.string.load_failed))
                state.value == null -> LibraryLoading()
                folders.isEmpty() -> LibraryMessage(
                    stringResource(R.string.no_folders),
                    icon = Icons.Default.Folder,
                )
                visibleFolders.isEmpty() -> LibraryMessage(stringResource(R.string.no_folders_matching_tags))
                else -> LazyVerticalGrid(
                    columns = columns,
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visibleFolders, key = { it.id }) { folder ->
                        FolderTile(
                            folder = folder,
                            onOpen = { onOpenFolder(folder.id) },
                            onEdit = { editingFolderId = folder.id },
                            onDelete = { deletingFolderId = folder.id },
                            selectedTags = selectedTags,
                            onTagClick = toggleTag,
                        )
                    }
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
