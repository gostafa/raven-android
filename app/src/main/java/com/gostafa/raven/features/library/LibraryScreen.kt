package com.gostafa.raven.features.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import com.gostafa.raven.R
import com.gostafa.raven.core.database.CardSummary
import com.gostafa.raven.core.database.ContentPartEntity
import com.gostafa.raven.core.database.FolderSummary
import com.gostafa.raven.core.model.PartDraft
import com.gostafa.raven.core.model.PartType
import java.io.File

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    onReviewDue: () -> Unit,
    onPracticeFolder: (String) -> Unit,
    onPracticeCard: (String) -> Unit,
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedFolderId.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    var showFolderDialog by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf<FolderSummary?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var deleteFolder by remember { mutableStateOf<FolderSummary?>(null) }
    var cardEditorId by remember { mutableStateOf<String?>(null) }
    var showNewCard by remember { mutableStateOf(false) }
    var deleteCard by remember { mutableStateOf<CardSummary?>(null) }
    val selectedFolder = folders.firstOrNull { it.id == selectedId }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data("file:///android_asset/raven.svg")
                            .decoderFactory(SvgDecoder.Factory())
                            .build(),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(25.dp),
                    )
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Row {
                    FilledTonalButton(onClick = onReviewDue, enabled = folders.any { it.dueCount > 0 }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.review_due))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::search,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    { IconButton(onClick = { viewModel.search("") }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel)) } }
                } else null,
                placeholder = { Text(stringResource(R.string.search)) },
            )

            if (searchQuery.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.folders), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                    OutlinedButton(onClick = { editingFolder = null; showFolderDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.add_folder))
                    }
                }
                if (folders.isEmpty()) {
                    EmptyMessage(stringResource(R.string.no_folders))
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(folders, key = { it.id }) { folder ->
                            FolderChip(
                                folder = folder,
                                selected = folder.id == selectedId,
                                onSelect = { viewModel.selectFolder(folder.id) },
                                onEdit = { editingFolder = folder; showFolderDialog = true },
                                onDelete = { deleteFolder = folder },
                            )
                        }
                    }
                    selectedFolder?.let { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(folder.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                                if (folder.description.isNotBlank()) Text(folder.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = { onPracticeFolder(folder.id) }) {
                                Text(stringResource(R.string.practice_folder))
                            }
                            Spacer(Modifier.width(6.dp))
                            FilledTonalButton(onClick = { showNewCard = true }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(5.dp))
                                Text(stringResource(R.string.new_card))
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.outline)
                        if (cards.isEmpty()) EmptyMessage(stringResource(R.string.no_cards))
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(cards, key = { it.id }) { card ->
                                CardRow(
                                    card = card,
                                    onEdit = { cardEditorId = card.id },
                                    onDelete = { deleteCard = card },
                                    onPractice = { onPracticeCard(card.id) },
                                )
                            }
                        }
                    }
                }
            } else {
                Text(stringResource(R.string.cards), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                if (searchResults.isEmpty()) EmptyMessage(stringResource(R.string.no_cards))
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(searchResults, key = { it.id }) { card ->
                        CardRow(card, onEdit = { cardEditorId = card.id }, onDelete = { deleteCard = card }, onPractice = { onPracticeCard(card.id) })
                    }
                }
            }
        }
    }

    if (showFolderDialog) {
        FolderDialog(folder = editingFolder, onDismiss = { showFolderDialog = false }, onSave = { id, name, desc ->
            viewModel.saveFolder(id, name, desc)
            showFolderDialog = false
        })
    }
    deleteFolder?.let { folder ->
        ConfirmDialog(stringResource(R.string.confirm_delete_folder), onDismiss = { deleteFolder = null }, onConfirm = {
            viewModel.deleteFolder(folder.id)
            deleteFolder = null
        })
    }
    if (showSettings) SettingsDialog(viewModel, onDismiss = { showSettings = false })
    if (showNewCard || cardEditorId != null) {
        val id = cardEditorId
        CardEditorDialog(
            cardId = id,
            viewModel = viewModel,
            onDismiss = { showNewCard = false; cardEditorId = null },
            onSave = { drafts ->
                selectedId?.let { viewModel.saveCard(it, id, drafts) }
                showNewCard = false
                cardEditorId = null
            },
        )
    }
    deleteCard?.let { card ->
        ConfirmDialog(stringResource(R.string.confirm_delete_card), onDismiss = { deleteCard = null }, onConfirm = {
            viewModel.deleteCard(card.id)
            deleteCard = null
        })
    }
}

@Composable
private fun FolderChip(folder: FolderSummary, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier
            .border(1.dp, if (selected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onSelect),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
    ) {
        Row(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(7.dp))
            Column {
                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${folder.cardCount}  ·  ${folder.dueCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(35.dp)) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit), modifier = Modifier.size(16.dp)) }
            IconButton(onClick = onDelete, modifier = Modifier.size(35.dp)) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun CardRow(card: CardSummary, onEdit: () -> Unit, onDelete: () -> Unit, onPractice: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 5.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(card.preview.ifBlank { stringResource(R.string.cards) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.part_count, card.partCount) + "  ·  " + stringResource(R.string.due_count, card.dueCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPractice) { Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.practice_card)) }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit)) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FolderDialog(folder: FolderSummary?, onDismiss: () -> Unit, onSave: (String?, String, String) -> Unit) {
    var name by remember(folder) { mutableStateOf(folder?.name.orEmpty()) }
    var description by remember(folder) { mutableStateOf(folder?.description.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (folder == null) R.string.add_folder else R.string.edit_folder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.folder_name)) }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.description)) }, minLines = 2)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(folder?.id, name, description) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SettingsDialog(viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    val language by viewModel.settings.language.collectAsStateWithLifecycle(initialValue = "system")
    val choices = listOf("system" to R.string.system_language, "en" to R.string.english, "ar" to R.string.arabic)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
                choices.forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = language == value, onClick = { viewModel.setLanguage(value) })
                        Text(stringResource(label))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.finish)) } },
    )
}

@Composable
private fun ConfirmDialog(message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CardEditorDialog(
    cardId: String?,
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit,
    onSave: (List<PartDraft>) -> Unit,
) {
    val parts = remember(cardId) { mutableStateListOf<PartDraft>() }
    var loading by remember(cardId) { mutableStateOf(cardId != null) }
    var importFailed by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.importMedia(uri, PartType.IMAGE, { parts.add(it) }) { importFailed = true }
    }
    var pendingAudioType by remember { mutableStateOf(PartType.AUDIO) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importMedia(uri, pendingAudioType, { parts.add(it) }) { importFailed = true }
    }
    LaunchedEffect(cardId) {
        if (cardId != null) {
            val content = viewModel.cardWithParts(cardId)
            content?.parts?.forEach { part ->
                parts.add(
                    PartDraft(
                        id = part.id,
                        type = runCatching { PartType.valueOf(part.type) }.getOrDefault(PartType.TEXT),
                        text = part.text,
                        mediaPath = part.mediaPath,
                        mimeType = part.mimeType,
                        rememberThis = part.rememberThis,
                    ),
                )
            }
            loading = false
        } else if (parts.isEmpty()) {
            parts.add(PartDraft())
            loading = false
        }
    }

    val context = LocalContext.current
    val valid = parts.isNotEmpty() && parts.all { it.type != PartType.TEXT || it.text.isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        title = { Text(stringResource(if (cardId == null) R.string.new_card else R.string.edit_card)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 560.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { parts.add(PartDraft()) }) { Text(stringResource(R.string.add_text_part)) }
                    IconButton(onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_image))
                    }
                    IconButton(onClick = { pendingAudioType = PartType.AUDIO; documentPicker.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.add_audio))
                    }
                    IconButton(onClick = { pendingAudioType = PartType.VIDEO; documentPicker.launch(arrayOf("video/*")) }) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = stringResource(R.string.add_video))
                    }
                }
                if (importFailed) Text(stringResource(R.string.file_import_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                        items(parts.size) { index ->
                            val part = parts[index]
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                    if (part.type == PartType.TEXT) {
                                        OutlinedTextField(
                                            value = part.text,
                                            onValueChange = { parts[index] = part.copy(text = it) },
                                            modifier = Modifier.fillMaxWidth(),
                                            placeholder = { Text(stringResource(R.string.text_content)) },
                                            minLines = 2,
                                        )
                                    } else {
                                        Text(
                                            when (part.type) {
                                                PartType.IMAGE -> stringResource(R.string.add_image)
                                                PartType.AUDIO -> stringResource(R.string.add_audio)
                                                PartType.VIDEO -> stringResource(R.string.add_video)
                                                PartType.TEXT -> ""
                                            },
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                        part.mediaPath?.let { path ->
                                            if (part.type == PartType.IMAGE) {
                                                AsyncImage(
                                                    model = File(context.filesDir, path),
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxWidth().height(120.dp),
                                                )
                                            } else Text(File(path).name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = part.rememberThis, onCheckedChange = { parts[index] = part.copy(rememberThis = it) })
                                        Text(stringResource(if (part.rememberThis) R.string.remember_this else R.string.context_only), modifier = Modifier.weight(1f))
                                        IconButton(onClick = { if (index > 0) { val value = parts.removeAt(index); parts.add(index - 1, value) } }, enabled = index > 0) {
                                            Text("↑", color = MaterialTheme.colorScheme.secondary)
                                        }
                                        IconButton(onClick = { if (index < parts.lastIndex) { val value = parts.removeAt(index); parts.add(index + 1, value) } }, enabled = index < parts.lastIndex) {
                                            Text("↓", color = MaterialTheme.colorScheme.secondary)
                                        }
                                        IconButton(onClick = { parts.removeAt(index) }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_part)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(parts.toList()) }, enabled = valid && !loading) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
