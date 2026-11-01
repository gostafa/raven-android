package com.gostafa.raven.features.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import com.gostafa.raven.R
import com.gostafa.raven.core.model.FolderSort
import com.gostafa.raven.core.model.selectFolders
import com.gostafa.raven.core.model.reconcileSelection
import com.gostafa.raven.core.designsystem.RavenOutlinedButton
import com.gostafa.raven.core.designsystem.RavenAlertDialog
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenFolder: (String) -> Unit,
    onSearch: () -> Unit,
    onReviewDue: () -> Unit,
    reminderOffer: @Composable () -> Unit = {},
) {
    val state by viewModel.folders.collectAsStateWithLifecycle()
    val archiveBusy by viewModel.archiveBusy.collectAsStateWithLifecycle()
    val folders = state.value.orEmpty()
    val gridState = rememberLazyGridState()
    var creatingFolder by rememberSaveable { mutableStateOf(false) }
    var exportFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var selectedTags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var sort by rememberSaveable { mutableStateOf(FolderSort.DEFAULT) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var exportSelection by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var tagPicker by rememberSaveable { mutableStateOf(false) }
    val availableTags = folders.flatMap { it.tags }.distinctBy { it.lowercase() }.toSet()
    LaunchedEffect(availableTags, state.value != null) {
        if (state.value != null && !state.failed) {
            selectedTags = selectedTags.filter { selected -> availableTags.any { it.equals(selected, ignoreCase = true) } }
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val visibleFolders = selectFolders(folders, selectedTags, sort, locale)
    LaunchedEffect(visibleFolders.map { it.id }, state.value != null) {
        if (state.value != null && !state.failed) selection = reconcileSelection(selection, visibleFolders.map { it.id })
    }
    BackHandler(selecting) { selecting = false; selection = emptyList() }
    val toggleTag: (String) -> Unit = { tag ->
        selectedTags = if (selectedTags.any { it.equals(tag, ignoreCase = true) }) {
            selectedTags.filterNot { it.equals(tag, ignoreCase = true) }
        } else selectedTags + tag
    }
    val columns = ravenGridColumns()

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
        LazyVerticalGrid(
            columns = columns, state = gridState, modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          item(key = "library_controls", span = { GridItemSpan(maxLineSpan) }) {
           Column {
            reminderOffer()
            ArchiveControls(viewModel, folders, exportFolderId, onExportHandled = { exportFolderId = null },
                exportSelection = exportSelection, onSelectionHandled = { exportSelection = null })
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.your_folders), style = MaterialTheme.typography.headlineLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortPicker(sort, FolderSort.entries, ::folderSortLabel) { sort = it }
                    RavenOutlinedButton(onClick = { tagPicker = true }, enabled = availableTags.isNotEmpty()) { Text(stringResource(R.string.filter_tags)) }
                    RavenOutlinedButton(onClick = { selecting = !selecting; selection = emptyList() }, enabled = folders.isNotEmpty()) {
                        Text(stringResource(if (selecting) R.string.done_selecting else R.string.select_folders))
                    }
                }
                if (selecting) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.selected_count, selection.size), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { selection = visibleFolders.map { it.id } }) { Text(stringResource(R.string.select_all_shown)) }
                    TextButton(onClick = { selection = emptyList() }) { Text(stringResource(R.string.clear_selection)) }
                    RavenOutlinedButton(onClick = { exportSelection = selection }, enabled = selection.isNotEmpty() && !archiveBusy) { Text(stringResource(R.string.export_selected)) }
                }
                ClipboardActions(viewModel)
                Text(stringResource(R.string.results_count, visibleFolders.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selectedTags.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.filter_tags), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FolderTags(selectedTags, Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), selectedTags = selectedTags, onTagClick = toggleTag, removable = true)
                    TextButton(onClick = { selectedTags = emptyList() }) { Text(stringResource(R.string.clear_all)) }
                }
            }
           }
          }
            when {
                state.failed -> item(span = { GridItemSpan(maxLineSpan) }) { LibraryMessage(stringResource(R.string.load_failed), Modifier.height(240.dp)) }
                state.value == null -> item(span = { GridItemSpan(maxLineSpan) }) { LibraryLoading(Modifier.height(240.dp)) }
                folders.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { LibraryMessage(
                    stringResource(R.string.no_folders),
                    modifier = Modifier.height(240.dp),
                    icon = Icons.Default.Folder,
                ) }
                visibleFolders.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { LibraryMessage(stringResource(R.string.no_folders_matching_tags), Modifier.height(240.dp)) }
                else -> items(visibleFolders, key = { it.id }) { folder ->
                      Column(Modifier.padding(horizontal = 20.dp)) {
                        FolderTile(
                            folder = folder,
                            onOpen = { onOpenFolder(folder.id) },
                            onEdit = { editingFolderId = folder.id },
                            onDelete = { deletingFolderId = folder.id },
                            onExport = { exportFolderId = folder.id },
                            selectedTags = selectedTags,
                            onTagClick = toggleTag,
                            selecting = selecting, selected = folder.id in selection,
                            onSelect = { selection = if (folder.id in selection) selection - folder.id else selection + folder.id },
                            onStartSelection = { selecting = true; selection = (selection + folder.id).distinct() },
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
    if (tagPicker) RavenAlertDialog(onDismissRequest = { tagPicker = false }, title = { Text(stringResource(R.string.filter_tags)) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            availableTags.sorted().forEach { tag ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(selectedTags.any { it.equals(tag, true) }, role = Role.Checkbox, onValueChange = { toggleTag(tag) }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = selectedTags.any { it.equals(tag, true) }, onCheckedChange = null)
                    Text(tag, Modifier.padding(start = 12.dp))
                }
            }
        } }, confirmButton = { TextButton(onClick = { tagPicker = false }) { Text(stringResource(R.string.done)) } })
}
