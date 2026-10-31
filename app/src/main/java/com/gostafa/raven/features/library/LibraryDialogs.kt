package com.gostafa.raven.features.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gostafa.raven.R
import com.gostafa.raven.core.database.FolderSummary
import com.gostafa.raven.core.model.PartDraft
import com.gostafa.raven.core.model.PartType
import com.gostafa.raven.core.model.parseTags
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FolderEditorDialog(
    folder: FolderSummary?,
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var name by rememberSaveable(folder?.id) { mutableStateOf(folder?.name.orEmpty()) }
    var description by rememberSaveable(folder?.id) { mutableStateOf(folder?.description.orEmpty()) }
    var tagsInput by rememberSaveable(folder?.id) { mutableStateOf(folder?.tags?.joinToString(", ").orEmpty()) }
    var nameChanged by rememberSaveable(folder?.id) { mutableStateOf(false) }
    val tags = parseTags(tagsInput)
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(folder?.id) { nameFocus.requestFocus() }
    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.safeDrawingPadding().imePadding().padding(12.dp).widthIn(max = 560.dp).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.padding(12.dp).size(24.dp))
                    }
                    IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close)) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (folder == null) R.string.add_folder else R.string.edit_folder), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.folder_editor_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameChanged = true; failed = false },
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                    label = { Text(stringResource(R.string.folder_name_required)) },
                    placeholder = { Text(stringResource(R.string.folder_name_example)) },
                    isError = nameChanged && name.isBlank(),
                    supportingText = if (nameChanged && name.isBlank()) ({ Text(stringResource(R.string.folder_name_required_error)) }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    enabled = !saving,
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it; failed = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.description_optional)) },
                    placeholder = { Text(stringResource(R.string.folder_description_example)) },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !saving,
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = tagsInput,
                    onValueChange = { tagsInput = it; failed = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tags_optional)) },
                    placeholder = { Text(stringResource(R.string.folder_tags_example)) },
                    supportingText = { Text(stringResource(R.string.tags_hint)) },
                    enabled = !saving,
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 3,
                )
                if (tags.isNotEmpty()) FolderTags(
                    tags, removable = true, enabled = !saving,
                    onTagClick = { removed -> tagsInput = tags.filterNot { it.equals(removed, ignoreCase = true) }.joinToString(", ") },
                )
                if (failed) Text(stringResource(R.string.save_failed), color = MaterialTheme.colorScheme.error)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
                    Button(
                        enabled = name.isNotBlank() && !saving,
                        onClick = {
                            if (saving || name.isBlank()) return@Button
                            saving = true
                            failed = false
                            scope.launch {
                                try {
                                    val id = viewModel.saveFolder(folder?.id, name, description, tags)
                                    onSaved(id)
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    failed = true
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    ) {
                        if (saving) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(if (saving) R.string.saving else if (folder == null) R.string.create_folder else R.string.save_changes))
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeleteConfirmation(
    message: String,
    onDismiss: () -> Unit,
    onDelete: suspend () -> Unit,
    onDeleted: () -> Unit = {},
) {
    var deleting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(stringResource(R.string.delete)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                if (deleting) CircularProgressIndicator()
                if (failed) Text(stringResource(R.string.delete_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !deleting,
                onClick = {
                    deleting = true
                    failed = false
                    scope.launch {
                        try {
                            onDelete()
                            onDismiss()
                            onDeleted()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            failed = true
                        } finally {
                            deleting = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !deleting) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun SettingsDialog(viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    val language by viewModel.settings.language.collectAsStateWithLifecycle(initialValue = "system")
    val choices = listOf("system" to R.string.system_language, "en" to R.string.english, "ar" to R.string.arabic)
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
                choices.forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = language == value, onClick = { viewModel.setLanguage(value) })
                        Text(stringResource(label), modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.finish)) } },
    )
}

private val PartDraftListSaver = listSaver<SnapshotStateList<PartDraft>, Any>(
    save = { parts ->
        parts.flatMap { part ->
            listOf(part.id.orEmpty(), part.type.name, part.text, part.mediaPath.orEmpty(), part.mimeType.orEmpty(), part.rememberThis)
        }
    },
    restore = { values ->
        values.chunked(6).map { part ->
            PartDraft(
                id = (part[0] as String).ifEmpty { null },
                type = PartType.valueOf(part[1] as String),
                text = part[2] as String,
                mediaPath = (part[3] as String).ifEmpty { null },
                mimeType = (part[4] as String).ifEmpty { null },
                rememberThis = part[5] as Boolean,
            )
        }.toMutableStateList()
    },
)

@Composable
internal fun CardEditorDialog(
    cardId: String?,
    folderId: String,
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    val parts = rememberSaveable(cardId, saver = PartDraftListSaver) { mutableStateListOf<PartDraft>() }
    var initialized by rememberSaveable(cardId) { mutableStateOf(false) }
    var loadFailed by rememberSaveable(cardId) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var importFailed by remember { mutableStateOf(false) }
    var pendingAudioType by rememberSaveable { mutableStateOf(PartType.AUDIO) }
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.importMedia(uri, PartType.IMAGE, { parts.add(it) }) { importFailed = true }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importMedia(uri, pendingAudioType, { parts.add(it) }) { importFailed = true }
    }
    LaunchedEffect(cardId) {
        if (!initialized) {
            try {
                if (cardId == null) {
                    parts.add(PartDraft())
                } else {
                    val content = viewModel.cardWithParts(cardId)
                    if (content == null) {
                        loadFailed = true
                    } else {
                        parts.addAll(content.parts.map { part ->
                            PartDraft(
                                id = part.id,
                                type = runCatching { PartType.valueOf(part.type) }.getOrDefault(PartType.TEXT),
                                text = part.text,
                                mediaPath = part.mediaPath,
                                mimeType = part.mimeType,
                                rememberThis = part.rememberThis,
                            )
                        })
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                loadFailed = true
            }
            initialized = true
        }
    }
    val context = LocalContext.current
    val valid = initialized && !loadFailed && parts.isNotEmpty() && parts.all { it.type != PartType.TEXT || it.text.isNotBlank() }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        modifier = Modifier.fillMaxWidth(0.94f).safeDrawingPadding(),
        title = { Text(stringResource(if (cardId == null) R.string.new_card else R.string.edit_card)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 560.dp)) {
                if (!initialized) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (loadFailed) {
                    Text(stringResource(R.string.card_unavailable), color = MaterialTheme.colorScheme.error)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { parts.add(PartDraft()) }, enabled = !saving) { Text(stringResource(R.string.add_text_part)) }
                        IconButton(onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !saving) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_image))
                        }
                        IconButton(onClick = { pendingAudioType = PartType.AUDIO; documentPicker.launch(arrayOf("audio/*")) }, enabled = !saving) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.add_audio))
                        }
                        IconButton(onClick = { pendingAudioType = PartType.VIDEO; documentPicker.launch(arrayOf("video/*")) }, enabled = !saving) {
                            Icon(Icons.Default.VideoLibrary, contentDescription = stringResource(R.string.add_video))
                        }
                    }
                    if (importFailed) Text(stringResource(R.string.file_import_failed), color = MaterialTheme.colorScheme.error)
                    if (saveFailed) Text(stringResource(R.string.save_failed), color = MaterialTheme.colorScheme.error)
                    if (saving) CircularProgressIndicator()
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
                                            enabled = !saving,
                                        )
                                    } else {
                                        Text(
                                            stringResource(when (part.type) {
                                                PartType.IMAGE -> R.string.add_image
                                                PartType.AUDIO -> R.string.add_audio
                                                PartType.VIDEO -> R.string.add_video
                                                PartType.TEXT -> R.string.cards
                                            }),
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
                                        Checkbox(checked = part.rememberThis, onCheckedChange = { parts[index] = part.copy(rememberThis = it) }, enabled = !saving)
                                        Text(stringResource(if (part.rememberThis) R.string.remember_this else R.string.context_only), modifier = Modifier.weight(1f))
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        val moveUp = stringResource(R.string.move_up)
                                        val moveDown = stringResource(R.string.move_down)
                                        IconButton(
                                            onClick = { val value = parts.removeAt(index); parts.add(index - 1, value) },
                                            enabled = index > 0 && !saving,
                                            modifier = Modifier.semantics { contentDescription = moveUp },
                                        ) { Text("↑", color = MaterialTheme.colorScheme.secondary) }
                                        IconButton(
                                            onClick = { val value = parts.removeAt(index); parts.add(index + 1, value) },
                                            enabled = index < parts.lastIndex && !saving,
                                            modifier = Modifier.semantics { contentDescription = moveDown },
                                        ) { Text("↓", color = MaterialTheme.colorScheme.secondary) }
                                        IconButton(onClick = { parts.removeAt(index) }, enabled = !saving) {
                                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_part))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !saving,
                onClick = {
                    saving = true
                    saveFailed = false
                    val drafts = parts.toList()
                    scope.launch {
                        try {
                            viewModel.saveCard(folderId, cardId, drafts)
                            onDismiss()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            saveFailed = true
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
    )
}
