package com.gostafa.raven.features.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.*
import com.gostafa.raven.core.designsystem.RavenTextField as OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gostafa.raven.R
import com.gostafa.raven.core.database.CardPreview
import com.gostafa.raven.core.designsystem.RavenAlertDialog as AlertDialog
import com.gostafa.raven.core.designsystem.RavenButton as Button
import com.gostafa.raven.core.designsystem.RavenOutlinedButton as OutlinedButton
import com.gostafa.raven.core.model.*
import java.text.NumberFormat

internal fun folderSortLabel(sort: FolderSort) = when (sort) {
    FolderSort.DEFAULT -> R.string.sort_default
    FolderSort.TAGS_ASC -> R.string.sort_tags_asc
    FolderSort.TAGS_DESC -> R.string.sort_tags_desc
    FolderSort.CARDS_ASC -> R.string.sort_cards_asc
    FolderSort.CARDS_DESC -> R.string.sort_cards_desc
    FolderSort.REVIEW_ASC -> R.string.sort_review_asc
    FolderSort.REVIEW_DESC -> R.string.sort_review_desc
}

internal fun cardSortLabel(sort: CardSort) = when (sort) {
    CardSort.CREATED_DESC -> R.string.sort_newest
    CardSort.CREATED_ASC -> R.string.sort_oldest
    CardSort.REVIEW_ASC -> R.string.sort_review_asc
    CardSort.REVIEW_DESC -> R.string.sort_review_desc
    CardSort.RECALL_ASC -> R.string.sort_recall_asc
    CardSort.RECALL_DESC -> R.string.sort_recall_desc
}

@Composable
internal fun ravenGridColumns(): GridCells {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return if (width < 600.dp || LocalConfiguration.current.fontScale >= 1.5f) GridCells.Fixed(1) else GridCells.Adaptive(300.dp)
}

@Composable
internal fun <T> SortPicker(value: T, options: List<T>, label: (T) -> Int, onSelect: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) { Text(stringResource(label(value))) }
    if (open) AlertDialog(
        onDismissRequest = { open = false }, title = { Text(stringResource(R.string.sort)) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            options.forEach { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(option == value, role = Role.RadioButton, onValueChange = { onSelect(option); open = false }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = option == value, onClick = null)
                    Text(stringResource(label(option)), Modifier.padding(start = 12.dp))
                }
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) } },
    )
}

@Stable
internal class CardBrowserState {
    var sort by mutableStateOf(CardSort.CREATED_DESC)
    var kinds by mutableStateOf(emptyList<PartType>())
    var minimum by mutableIntStateOf(0)
    var maximum by mutableIntStateOf(100)
    var selecting by mutableStateOf(false)
    var selected by mutableStateOf(emptyList<String>())
    fun clearFilters() { kinds = emptyList(); minimum = 0; maximum = 100 }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun exitSelection() { selecting = false; selected = emptyList() }
    companion object {
        val Saver = listSaver<CardBrowserState, Any>(
            save = { listOf(it.sort.name, it.kinds.map { kind -> kind.name }, it.minimum, it.maximum, it.selecting, it.selected) },
            restore = { values -> CardBrowserState().apply {
                sort = CardSort.valueOf(values[0] as String)
                @Suppress("UNCHECKED_CAST")
                val types = values[1] as List<String>
                kinds = types.map(PartType::valueOf)
                minimum = values[2] as Int; maximum = values[3] as Int; selecting = values[4] as Boolean
                @Suppress("UNCHECKED_CAST")
                val ids = values[5] as List<String>
                selected = ids
            } },
        )
    }
}

@Composable
internal fun rememberCardBrowserState(key: String) = rememberSaveable(key, saver = CardBrowserState.Saver) { CardBrowserState() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardBrowserToolbar(
    state: CardBrowserState, visible: List<CardPreview>, viewModel: LibraryViewModel,
    onReviewSelection: (List<String>) -> Unit, folderId: String? = null, dataReady: Boolean = true,
) {
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    val busy by viewModel.cardTransferBusy.collectAsStateWithLifecycle()
    val ids = visible.map { it.summary.id }
    LaunchedEffect(ids, dataReady) { if (dataReady) state.selected = reconcileSelection(state.selected, ids) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SortPicker(state.sort, CardSort.entries, ::cardSortLabel) { state.sort = it }
            OutlinedButton(onClick = { filterOpen = true }) { Text(stringResource(R.string.filters)) }
            OutlinedButton(onClick = { if (state.selecting) state.exitSelection() else state.selecting = true }, enabled = !busy) {
                Text(stringResource(if (state.selecting) R.string.done_selecting else R.string.select_cards))
            }
        }
        if (state.kinds.isNotEmpty() || state.minimum != 0 || state.maximum != 100) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.kinds.forEach { type -> TextButton(onClick = { state.kinds = state.kinds - type }) { Text(partLabel(type) + " ×") } }
                if (state.minimum != 0 || state.maximum != 100) TextButton(onClick = { state.minimum = 0; state.maximum = 100 }) {
                    Text(stringResource(R.string.recall_range, state.minimum, state.maximum) + " ×", style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr))
                }
                TextButton(onClick = state::clearFilters) { Text(stringResource(R.string.clear_filters)) }
            }
        }
        if (state.selecting) {
            Text(stringResource(R.string.selected_count, state.selected.size), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { state.selected = ids }) { Text(stringResource(R.string.select_all_shown)) }
                TextButton(onClick = { state.selected = emptyList() }) { Text(stringResource(R.string.clear_selection)) }
                val sources = visible.filter { it.summary.id in state.selected }.map { CardReference(it.summary.folderId, it.summary.id) }
                OutlinedButton(onClick = { viewModel.requestMove(sources) }, enabled = sources.isNotEmpty() && !busy) { Text(stringResource(R.string.move_to)) }
                OutlinedButton(onClick = { viewModel.copyCards(sources); state.exitSelection() }, enabled = sources.isNotEmpty() && !busy) { Text(stringResource(R.string.copy_cards)) }
                OutlinedButton(onClick = { onReviewSelection(state.selected) }, enabled = sources.isNotEmpty() && !busy) { Text(stringResource(R.string.review_selected)) }
            }
        }
        ClipboardActions(viewModel, folderId)
        Text(stringResource(R.string.results_count, visible.size), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    if (filterOpen) RecallFilterDialog(state) { filterOpen = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecallFilterDialog(state: CardBrowserState, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.filters)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.content_types), style = MaterialTheme.typography.titleSmall)
            PartType.entries.forEach { type ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(type in state.kinds, role = Role.Checkbox,
                    onValueChange = { checked -> state.kinds = if (checked) state.kinds + type else state.kinds - type }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = type in state.kinds, onCheckedChange = null)
                    Text(partLabel(type), Modifier.padding(start = 12.dp))
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.recall_percentage), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to 100, 0 to 0, 0 to 50, 80 to 100).forEach { (min, max) ->
                    FilterChip(selected = state.minimum == min && state.maximum == max,
                        onClick = { state.minimum = min; state.maximum = max },
                        label = {
                            if (min == 0 && max == 100) Text(stringResource(R.string.any_recall))
                            else Text(stringResource(R.string.recall_range, min, max), style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr))
                        })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RecallNumber(state.minimum, R.string.minimum_recall, Modifier.weight(1f)) { state.minimum = it.coerceAtMost(state.maximum) }
                RecallNumber(state.maximum, R.string.maximum_recall, Modifier.weight(1f)) { state.maximum = it.coerceAtLeast(state.minimum) }
            }
            RangeSlider(value = state.minimum.toFloat()..state.maximum.toFloat(), onValueChange = {
                state.minimum = it.start.toInt(); state.maximum = it.endInclusive.toInt()
            }, valueRange = 0f..100f, steps = 99)
            Text(stringResource(R.string.recall_filter_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        dismissButton = { TextButton(onClick = state::clearFilters) { Text(stringResource(R.string.clear_filters)) } },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.done)) } })
}

@Composable
private fun RecallNumber(value: Int, label: Int, modifier: Modifier, onValue: (Int) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    var text by remember(value, locale) { mutableStateOf(NumberFormat.getIntegerInstance(locale).format(value)) }
    OutlinedTextField(value = text, onValueChange = { input ->
        if (input.isEmpty()) text = input
        else if (input.all { it.isDigit() } && input.length <= 3) {
            val number = input.map { Character.digit(it, 10) }.joinToString("").toIntOrNull()
            if (number != null) { text = input; onValue(number.coerceIn(0, 100)) }
        }
    }, modifier = modifier, label = { Text(stringResource(label)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClipboardActions(viewModel: LibraryViewModel, folderId: String? = null) {
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val busy by viewModel.cardTransferBusy.collectAsStateWithLifecycle()
    if (clipboard.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { viewModel.requestPaste(folderId) }, enabled = !busy) { Text(stringResource(R.string.paste_count, clipboard.size)) }
        TextButton(onClick = viewModel::clearClipboard, enabled = !busy) { Text(stringResource(R.string.clear_clipboard)) }
    }
}

@Composable
internal fun CardTransferHost(viewModel: LibraryViewModel) {
    val request by viewModel.cardTransferRequest.collectAsStateWithLifecycle()
    val busy by viewModel.cardTransferBusy.collectAsStateWithLifecycle()
    val failed by viewModel.cardTransferFailed.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    request?.let { transfer ->
        var query by rememberSaveable(transfer) { mutableStateOf("") }
        var destination by rememberSaveable(transfer) { mutableStateOf<String?>(null) }
        val options = folders.value.orEmpty().filter { it.name.contains(query.trim(), ignoreCase = true) }
        AlertDialog(onDismissRequest = viewModel::dismissTransfer,
            title = { Text(stringResource(if (transfer.operation == TransferOperation.MOVE) R.string.move_to else R.string.paste_cards)) },
            text = { Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.transfer_count, transfer.sources.size))
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.search_folders)) }, singleLine = true)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.card_transfer_failed), color = MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    options.forEach { folder ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(destination == folder.id, enabled = !busy,
                            role = Role.RadioButton, onValueChange = { destination = folder.id }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = destination == folder.id, onClick = null, enabled = !busy)
                            Text(folder.name, Modifier.weight(1f).padding(start = 12.dp))
                        }
                    }
                    if (options.isEmpty()) Text(stringResource(R.string.no_matching_folders))
                }
            } }, dismissButton = { TextButton(onClick = viewModel::dismissTransfer, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { Button(onClick = { destination?.let(viewModel::transferCardsTo) }, enabled = !busy && folders.value.orEmpty().any { it.id == destination }) {
                Text(stringResource(if (busy) R.string.saving else if (transfer.operation == TransferOperation.MOVE) R.string.move_cards else R.string.paste_cards))
            } })
    }
}
