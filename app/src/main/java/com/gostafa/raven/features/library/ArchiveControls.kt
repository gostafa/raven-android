package com.gostafa.raven.features.library

import com.gostafa.raven.core.designsystem.RavenAlertDialog as AlertDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gostafa.raven.R
import com.gostafa.raven.core.archive.ImportMode
import com.gostafa.raven.core.database.FolderSummary

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ArchiveControls(viewModel: LibraryViewModel, folders: List<FolderSummary>, exportFolderId: String?, onExportHandled: () -> Unit,
    exportSelection: List<String>? = null, onSelectionHandled: () -> Unit = {},
) {
    var operation by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var mode by rememberSaveable { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf(false) }
    val busy by viewModel.archiveBusy.collectAsStateWithLifecycle()
    val result by viewModel.archiveResult.collectAsStateWithLifecycle()
    val error by viewModel.archiveError.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        picking = false
        if (uri != null) viewModel.exportFolders(selected, uri)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri != null && mode.isNotEmpty()) viewModel.importFolders(uri, ImportMode.valueOf(mode))
    }
    LaunchedEffect(exportFolderId) {
        if (exportFolderId != null) {
            if (!busy && !picking) { selected = listOf(exportFolderId); operation = "export" }
            onExportHandled()
        }
    }
    LaunchedEffect(exportSelection) {
        if (exportSelection != null) {
            if (!busy && !picking) { selected = exportSelection; operation = "export" }
            onSelectionHandled()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy && !picking, onClick = { mode = ""; operation = "import" }) { Text(stringResource(R.string.import_folders)) }
            TextButton(enabled = !busy && !picking && folders.isNotEmpty(), onClick = { selected = emptyList(); operation = "export" }) { Text(stringResource(R.string.export_folders)) }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.archive_transferring), style = MaterialTheme.typography.bodySmall)
        }
        result?.let { Text(stringResource(R.string.archive_success, it.folderCount, it.cardCount, it.mediaCount), style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(stringResource(R.string.archive_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (operation.isNotEmpty()) {
        val exporting = operation == "export"
        AlertDialog(
            onDismissRequest = { operation = "" },
            title = { Text(stringResource(if (exporting) R.string.export_folders else R.string.import_folders)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(if (exporting) R.string.archive_export_description else R.string.archive_import_description))
                    if (exporting) {
                        TextButton(onClick = { selected = if (selected.size == folders.size) emptyList() else folders.map { it.id } }) {
                            Text(stringResource(if (selected.size == folders.size) R.string.archive_clear_selection else R.string.archive_select_all))
                        }
                        folders.forEach { folder ->
                            Row {
                                Checkbox(checked = folder.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + folder.id else selected - folder.id })
                                TextButton(onClick = { selected = if (folder.id in selected) selected - folder.id else selected + folder.id }) { Text(folder.name) }
                            }
                        }
                    } else {
                        listOf(ImportMode.FRESH to R.string.archive_fresh, ImportMode.RESUME to R.string.archive_resume).forEach { (value, label) ->
                            Row {
                                RadioButton(selected = mode == value.name, onClick = { mode = value.name })
                                Column {
                                    TextButton(onClick = { mode = value.name }) { Text(stringResource(label)) }
                                    Text(stringResource(if (value == ImportMode.FRESH) R.string.archive_fresh_description else R.string.archive_resume_description), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { operation = "" }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(enabled = if (exporting) selected.isNotEmpty() else mode.isNotEmpty(), onClick = {
                    operation = ""; picking = true
                    try {
                        if (exporting) save.launch("raven-folders.zip") else open.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    } catch (failure: Exception) { picking = false; viewModel.archiveError.value = failure.message }
                }) { Text(stringResource(if (exporting) R.string.archive_choose_save else R.string.archive_choose_file)) }
            },
        )
    }
}
