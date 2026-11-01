package com.gostafa.raven.features.library

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import com.gostafa.raven.core.designsystem.RavenTextField as OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gostafa.raven.R
import com.gostafa.raven.core.database.CardPreview
import com.gostafa.raven.core.model.*

@Composable
fun SearchScreen(
    viewModel: LibraryViewModel, onBack: () -> Unit,
    onPracticeCard: (String) -> Unit, onEditCard: (String, String) -> Unit,
    onOpenFolder: (String) -> Unit = {}, onReviewSelection: (List<String>) -> Unit = {},
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var deletingCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val browser = rememberCardBrowserState("search")
    BackHandler(browser.selecting) { browser.exitSelection() }
    val resultsFlow = remember(viewModel, query.trim()) { viewModel.searchCards(query.trim()) }
    val cards by resultsFlow.collectAsStateWithLifecycle(initialValue = LibraryData<List<CardPreview>>())
    val visible = selectCards(cards.value.orEmpty(), browser.sort, browser.kinds.toSet(), RecallRange(browser.minimum, browser.maximum))
    val matchingFolders = folders.value.orEmpty().filter {
        query.isNotBlank() && (it.name.contains(query.trim(), true) || it.description.contains(query.trim(), true) || it.tags.any { tag -> tag.contains(query.trim(), true) })
    }
    LaunchedEffect(Unit) { if (!focusedOnce) { focusRequester.requestFocus(); focusedOnce = true } }
    Scaffold(modifier = Modifier.imePadding(), containerColor = MaterialTheme.colorScheme.background,
        topBar = { LibraryHeader(stringResource(R.string.search_title), onBack = onBack) },
    ) { padding ->
        LazyVerticalGrid(columns = ravenGridColumns(), modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineLarge)
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focusRequester), singleLine = true,
                        placeholder = { Text(stringResource(R.string.search)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.clear_search)) } }) else null)
                    CardBrowserToolbar(browser, visible, viewModel, { focusManager.clearFocus(); onReviewSelection(it) }, dataReady = cards.value != null && !cards.failed)
                }
            }
            if (query.isNotBlank()) {
                if (matchingFolders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.folders), style = MaterialTheme.typography.titleMedium) }
                    items(matchingFolders, key = { "folder:" + it.id }) { folder ->
                        OutlinedCard(onClick = { focusManager.clearFocus(); onOpenFolder(folder.id) }, colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(folder.name, style = MaterialTheme.typography.titleMedium)
                                if (folder.description.isNotBlank()) Text(folder.description, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.cards), style = MaterialTheme.typography.titleMedium) }
                when {
                    cards.failed -> item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.load_failed)) }
                    cards.value == null -> item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    visible.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.no_search_results), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else -> items(visible, key = { it.summary.id }) { card ->
                        CardRow(card, onEdit = { focusManager.clearFocus(); onEditCard(card.summary.folderId, card.summary.id) },
                            onDelete = { focusManager.clearFocus(); deletingCardId = card.summary.id },
                            onPractice = { focusManager.clearFocus(); onPracticeCard(card.summary.id) },
                            folderName = folders.value.orEmpty().firstOrNull { it.id == card.summary.folderId }?.name,
                            selecting = browser.selecting, selected = card.summary.id in browser.selected,
                            onSelect = { browser.toggle(card.summary.id) },
                            onStartSelection = { focusManager.clearFocus(); browser.selecting = true; browser.selected = (browser.selected + card.summary.id).distinct() },
                            onMove = { viewModel.requestMove(listOf(CardReference(card.summary.folderId, card.summary.id))) },
                            onCopy = { viewModel.copyCards(listOf(CardReference(card.summary.folderId, card.summary.id))) })
                    }
                }
            } else item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    deletingCardId?.let { id -> DeleteConfirmation(stringResource(R.string.confirm_delete_card), onDismiss = { deletingCardId = null }, onDelete = { viewModel.deleteCard(id) }) }
}
