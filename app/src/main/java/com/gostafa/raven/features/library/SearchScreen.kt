package com.gostafa.raven.features.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gostafa.raven.R
import com.gostafa.raven.core.database.CardSummary

@Composable
fun SearchScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onPracticeCard: (String) -> Unit,
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var editingCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        if (!focusedOnce) {
            focusRequester.requestFocus()
            focusedOnce = true
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { LibraryHeader(stringResource(R.string.search_title), onBack = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).focusRequester(focusRequester),
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                placeholder = { Text(stringResource(R.string.search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search)) } }
                } else null,
            )
            if (query.isBlank()) {
                LibraryMessage(stringResource(R.string.search_hint), icon = Icons.Default.Search)
            } else {
                key(query.trim()) {
                    SearchResults(
                        query = query.trim(),
                        viewModel = viewModel,
                        folderNames = folders.value.orEmpty().associate { it.id to it.name },
                        onEdit = { card ->
                            focusManager.clearFocus()
                            editingCardId = card.id
                            editingFolderId = card.folderId
                        },
                        onDelete = { card -> focusManager.clearFocus(); deletingCardId = card.id },
                        onPractice = { id -> focusManager.clearFocus(); onPracticeCard(id) },
                    )
                }
            }
        }
    }
    val cardId = editingCardId
    val folderId = editingFolderId
    if (cardId != null && folderId != null) {
        CardEditorDialog(cardId, folderId, viewModel, onDismiss = { editingCardId = null; editingFolderId = null })
    }
    deletingCardId?.let { id ->
        DeleteConfirmation(
            message = stringResource(R.string.confirm_delete_card),
            onDismiss = { deletingCardId = null },
            onDelete = { viewModel.deleteCard(id) },
        )
    }
}

@Composable
private fun SearchResults(
    query: String,
    viewModel: LibraryViewModel,
    folderNames: Map<String, String>,
    onEdit: (CardSummary) -> Unit,
    onDelete: (CardSummary) -> Unit,
    onPractice: (String) -> Unit,
) {
    val resultsFlow = remember(viewModel, query) { viewModel.searchCards(query) }
    val state by resultsFlow.collectAsStateWithLifecycle(initialValue = LibraryData<List<CardSummary>>())
    val listState = rememberLazyListState()
    when {
        state.failed -> LibraryMessage(stringResource(R.string.load_failed))
        state.value == null -> LibraryLoading()
        state.value.orEmpty().isEmpty() -> LibraryMessage(stringResource(R.string.no_search_results), icon = Icons.Default.Search)
        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.value.orEmpty(), key = { it.id }) { card ->
                CardRow(
                    card = card,
                    folderName = folderNames[card.folderId] ?: stringResource(R.string.folder),
                    onEdit = { onEdit(card) },
                    onDelete = { onDelete(card) },
                    onPractice = { onPractice(card.id) },
                )
            }
        }
    }
}
