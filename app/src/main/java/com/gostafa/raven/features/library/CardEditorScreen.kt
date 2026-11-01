package com.gostafa.raven.features.library

import com.gostafa.raven.core.designsystem.RavenButton as Button
import com.gostafa.raven.core.designsystem.RavenOutlinedButton as OutlinedButton
import com.gostafa.raven.core.designsystem.RavenAlertDialog as AlertDialog
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.gostafa.raven.core.designsystem.RavenTextField as OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.gostafa.raven.R
import com.gostafa.raven.core.model.PartDraft
import com.gostafa.raven.core.model.PartType
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardEditorScreen(viewModel: CardEditorViewModel, onClosed: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val window = LocalWindowInfo.current.containerSize
    val tablet = with(LocalDensity.current) { minOf(window.width, window.height).toDp() >= 600.dp }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    val compactKeyboard = imeVisible && with(LocalDensity.current) {
        (window.height - WindowInsets.ime.getBottom(this)).toDp() < 300.dp
    }
    val listState = rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var pendingType by rememberSaveable { mutableStateOf(PartType.AUDIO) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        viewModel.importMedia(it, PartType.IMAGE)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.importMedia(it, pendingType)
    }
    LaunchedEffect(state.closed) { if (state.closed) onClosed() }
    LaunchedEffect(state.focusPartId, state.focusRequest) {
        val id = state.focusPartId ?: return@LaunchedEffect
        val index = state.parts.indexOfFirst { it.id == id }
        if (index >= 0) {
            listState.animateScrollToItem(index + 1)
            withFrameNanos { }
            if (state.parts[index].type == PartType.TEXT) requesters[id]?.requestFocus()
        }
    }
    BackHandler {
        if (imeVisible) { keyboard?.hide(); focusManager.clearFocus() }
        else viewModel.requestClose()
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(if (tablet) 24.dp else 0.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 760.dp).then(if (tablet) Modifier.heightIn(max = 900.dp) else Modifier).fillMaxSize(),
            shape = if (tablet) RoundedCornerShape(12.dp) else RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.background,
            border = if (tablet) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0),
                topBar = {
                    if (!compactKeyboard) Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = viewModel::requestClose, enabled = !state.saving && !state.discarding) {
                                Icon(Icons.Default.Close, stringResource(R.string.close))
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(stringResource(if (viewModel.cardId == null) R.string.new_card else R.string.edit_card), style = MaterialTheme.typography.titleLarge)
                                state.draft?.folderName?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                },
                bottomBar = {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (compactKeyboard) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.saveFailed) EditorError(stringResource(R.string.save_failed))
                            if (state.cardUnavailable) EditorError(stringResource(R.string.card_removed))
                            if (state.showValidation && state.parts.isEmpty()) EditorError(stringResource(R.string.empty_card))
                            if (state.importing) Text(stringResource(R.string.importing_media), style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = viewModel::requestClose, enabled = !state.saving && !state.discarding) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Button(
                                    onClick = { focusManager.clearFocus(); viewModel.save() },
                                    enabled = state.editable && !state.importing,
                                    modifier = Modifier.weight(1f).heightIn(min = if (compactKeyboard) 48.dp else 52.dp),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    if (state.saving || state.discarding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    if (state.saving || state.discarding) Spacer(Modifier.width(8.dp))
                                    Text(stringResource(when {
                                        state.discarding -> R.string.discarding
                                        state.saving -> R.string.saving
                                        viewModel.cardId == null -> R.string.create_card
                                        else -> R.string.save_changes
                                    }))
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                when {
                    state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.loadFailed -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EditorError(stringResource(R.string.card_unavailable))
                        OutlinedButton(onClick = viewModel::load) { Text(stringResource(R.string.retry)) }
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = if (compactKeyboard) 0.dp else 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item(key = "help") {
                            Text(stringResource(R.string.card_editor_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        itemsIndexed(state.parts, key = { _, part -> checkNotNull(part.id) }) { index, part ->
                            val id = checkNotNull(part.id)
                            val requester = remember(id) { requesters.getOrPut(id) { FocusRequester() } }
                            EditorPart(
                                part = part, index = index, count = state.parts.size, enabled = state.editable,
                                error = state.showValidation && viewModel.invalid(part), requester = requester,
                                onText = { text -> viewModel.update(id) { it.copy(text = text) } },
                                onRemember = { checked -> viewModel.update(id) { it.copy(rememberThis = checked) } },
                                onMove = { viewModel.move(id, it) }, onRemove = { viewModel.remove(id) },
                            )
                        }
                        item(key = "add") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.add_content), style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    AddPartButton(R.string.add_text_part, Icons.AutoMirrored.Filled.Notes, state.editable, viewModel::addText)
                                    AddPartButton(R.string.add_image, Icons.Default.Image, state.editable && !state.importing) {
                                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }
                                    AddPartButton(R.string.add_audio, Icons.Default.AudioFile, state.editable && !state.importing) {
                                        pendingType = PartType.AUDIO; documentPicker.launch(arrayOf("audio/*"))
                                    }
                                    AddPartButton(R.string.add_video, Icons.Default.VideoLibrary, state.editable && !state.importing) {
                                        pendingType = PartType.VIDEO; documentPicker.launch(arrayOf("video/*"))
                                    }
                                }
                                if (state.importFailed) EditorError(stringResource(R.string.file_import_failed))
                                if (state.parts.isNotEmpty() && state.parts.none { it.rememberThis }) {
                                    Text(stringResource(R.string.context_card_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.confirmDiscard) AlertDialog(
        onDismissRequest = viewModel::keepEditing,
        title = { Text(stringResource(R.string.discard_changes_title)) },
        text = { Text(stringResource(R.string.discard_changes_message)) },
        confirmButton = { TextButton(onClick = viewModel::discard) { Text(stringResource(R.string.discard)) } },
        dismissButton = { TextButton(onClick = viewModel::keepEditing) { Text(stringResource(R.string.keep_editing)) } },
    )
}

@Composable
private fun AddPartButton(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(label))
    }
}

@Composable
private fun EditorError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun EditorPart(
    part: PartDraft, index: Int, count: Int, enabled: Boolean, error: Boolean, requester: FocusRequester,
    onText: (String) -> Unit, onRemember: (Boolean) -> Unit, onMove: (Int) -> Unit, onRemove: () -> Unit,
) {
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val compact = with(density) { (windowHeight - WindowInsets.ime.getBottom(this)).toDp() < 480.dp }
    val type = stringResource(when (part.type) {
        PartType.TEXT -> R.string.text_part
        PartType.IMAGE -> R.string.image_part
        PartType.AUDIO -> R.string.audio_part
        PartType.VIDEO -> R.string.video_part
    })
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.numbered_part, index + 1, type), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (part.type == PartType.TEXT) OutlinedTextField(
                value = part.text, onValueChange = onText, enabled = enabled,
                label = { Text(stringResource(R.string.text_part)) },
                placeholder = { Text(stringResource(R.string.text_content)) },
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
                minLines = if (compact) 1 else 3, maxLines = if (compact) 2 else 8,
                isError = error, supportingText = if (error) ({ Text(stringResource(R.string.text_required)) }) else null,
                shape = RoundedCornerShape(12.dp),
            ) else {
                EditorMedia(part, enabled)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = part.rememberThis, enabled = enabled, role = Role.Checkbox, onValueChange = onRemember).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = part.rememberThis, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp), enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (part.rememberThis) R.string.remember_this else R.string.context_only), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(if (part.rememberThis) R.string.remember_part_hint else R.string.context_part_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { onMove(-1) }, enabled = enabled && index > 0) {
                    Icon(Icons.Default.ArrowUpward, stringResource(R.string.move_part_up, index + 1))
                }
                IconButton(onClick = { onMove(1) }, enabled = enabled && index < count - 1) {
                    Icon(Icons.Default.ArrowDownward, stringResource(R.string.move_part_down, index + 1))
                }
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(Icons.Default.DeleteOutline, stringResource(R.string.remove_numbered_part, index + 1))
                }
            }
        }
    }
}

@Composable
private fun EditorMedia(part: PartDraft, enabled: Boolean) {
    val context = LocalContext.current
    val file = part.mediaPath?.let { File(context.filesDir, it) }
    var preview by rememberSaveable(part.id) { mutableStateOf(false) }
    var failed by remember(part.mediaPath) { mutableStateOf(false) }
    if (file?.isFile != true || failed) {
        EditorError(stringResource(R.string.media_unavailable))
    } else if (part.type == PartType.IMAGE) {
        AsyncImage(model = file, contentDescription = stringResource(R.string.image_part),
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp), onError = { failed = true })
    } else {
        if (preview && enabled) {
            val player = remember(file) { ExoPlayer.Builder(context).build() }
            DisposableEffect(player) {
                val listener = object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true }
                }
                player.addListener(listener)
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
                onDispose { player.release() }
            }
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player; useController = true } },
                update = { it.player = player },
                modifier = Modifier.fillMaxWidth().height(if (part.type == PartType.VIDEO) 220.dp else 100.dp),
            )
        }
        OutlinedButton(onClick = { preview = !preview }, enabled = enabled) {
            Icon(if (preview) Icons.Default.Close else Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (preview) R.string.close_preview else if (part.type == PartType.AUDIO) R.string.preview_audio else R.string.preview_video))
        }
    }
}
