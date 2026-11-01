package com.gostafa.raven.features.library

import com.gostafa.raven.core.designsystem.RavenButton as Button
import com.gostafa.raven.core.designsystem.RavenAlertDialog as AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.gostafa.raven.core.designsystem.RavenTextField as OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gostafa.raven.R
import com.gostafa.raven.core.database.FolderSummary
import com.gostafa.raven.core.model.parseTags
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
            shape = RoundedCornerShape(12.dp),
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
                        if (saving) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
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
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
                choices.forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(language == value, role = Role.RadioButton, onClick = { viewModel.setLanguage(value) })) {
                        RadioButton(selected = language == value, onClick = null)
                        Text(stringResource(label), modifier = Modifier.weight(1f).padding(start = 12.dp))
                    }
                }
                com.gostafa.raven.core.reminders.ReminderControls()
                com.gostafa.raven.core.companion.CompanionControls(viewModel.settings)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.finish)) } },
    )
}
