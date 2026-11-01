package com.gostafa.raven.features.review

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.gostafa.raven.R
import com.gostafa.raven.core.database.ContentPartEntity
import com.gostafa.raven.core.model.PartType
import com.gostafa.raven.core.model.Rating
import java.io.File
import kotlinx.coroutines.delay

@Composable
fun ReviewScreen(
    mode: String,
    target: String?,
    onExit: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(mode, target) { viewModel.begin(mode, target) }
    ReviewContent(state, onExit, viewModel::rate, viewModel::retry, viewModel::visibleCard)
}

@Composable
internal fun ReviewContent(
    state: ReviewUiState,
    onExit: () -> Unit,
    onRate: (Rating) -> Unit,
    onRetry: () -> Unit,
    onVisibleCard: (String?) -> Unit = {},
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val exit by rememberUpdatedState(onExit)
    val visibleCard by rememberUpdatedState(onVisibleCard)
    val cardId = state.current?.content?.card?.id
    DisposableEffect(lifecycle, cardId) {
        val observer = LifecycleEventObserver { _, _ ->
            visibleCard(cardId.takeIf { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) })
        }
        lifecycle.addObserver(observer)
        visibleCard(cardId.takeIf { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) })
        onDispose { lifecycle.removeObserver(observer); visibleCard(null) }
    }
    LaunchedEffect(state.complete, lifecycle) {
        if (state.complete) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(5_000)
            exit()
        }
    }
    BackHandler(enabled = state.submitting) { /* Keep a pending review attached to its result. */ }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(state.current?.content?.folderName ?: stringResource(R.string.review), style = MaterialTheme.typography.labelLarge)
                    state.current?.let { Text(stringResource(R.string.session_progress, state.index + 1, state.items.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                IconButton(onClick = onExit, enabled = !state.submitting) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close_review))
                }
            }
        },
        bottomBar = {
            if (state.current != null && !state.loading && !state.loadFailed) {
                Column(Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text(stringResource(R.string.review_question), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    RatingActions(!state.submitting, onRate)
                    if (state.submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.saveFailed) Text(stringResource(R.string.review_save_failed), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                state.loading -> CircularProgressIndicator()
                state.loadFailed -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.review_load_failed), textAlign = TextAlign.Center)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                state.current == null -> Completion(state.index, onExit)
                else -> key(state.current!!.prompt.id) {
                    val item = state.current!!
                    var hintsVisible by remember { mutableStateOf(false) }
                    var selectedHint by remember { mutableStateOf<ContentPartEntity?>(null) }
                    val hints = item.content.parts.filterNot { it.id == item.prompt.id }
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        PartContent(item.prompt)
                        if (hints.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            OutlinedIconButton(onClick = { hintsVisible = !hintsVisible }, enabled = !state.submitting, shape = RoundedCornerShape(6.dp)) {
                                Icon(Icons.Default.Lightbulb, contentDescription = stringResource(if (hintsVisible) R.string.hide_hints else R.string.show_hints))
                            }
                            if (hintsVisible) HintTiles(hints, !state.submitting) { selectedHint = it }
                        }
                    }
                    selectedHint?.let { hint ->
                        AlertDialog(
                            onDismissRequest = { selectedHint = null },
                            containerColor = MaterialTheme.colorScheme.surface,
                            title = { Text(stringResource(R.string.hint)) },
                            text = { Column(Modifier.verticalScroll(rememberScrollState())) { PartContent(hint) } },
                            confirmButton = { TextButton(onClick = { selectedHint = null }) { Text(stringResource(R.string.close)) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingActions(enabled: Boolean, onRate: (Rating) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp || LocalConfiguration.current.fontScale >= 1.3f
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Rating.entries.chunked(if (compact) 2 else 4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { rating ->
                        val label = stringResource(when (rating) {
                            Rating.AGAIN -> R.string.again
                            Rating.HARD -> R.string.hard
                            Rating.GOOD -> R.string.good
                            Rating.EASY -> R.string.easy
                        })
                        val modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        val shape = RoundedCornerShape(6.dp)
                        if (rating == Rating.GOOD) Button(onClick = { onRate(rating) }, enabled = enabled, modifier = modifier, shape = shape, contentPadding = PaddingValues(6.dp)) { Text(label) }
                        else OutlinedButton(onClick = { onRate(rating) }, enabled = enabled, modifier = modifier, shape = shape, contentPadding = PaddingValues(6.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Text(label) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HintTiles(hints: List<ContentPartEntity>, enabled: Boolean, onSelect: (ContentPartEntity) -> Unit) {
    val context = LocalContext.current
    FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        hints.forEachIndexed { index, hint ->
            Surface(
                modifier = Modifier.padding(horizontal = 4.dp).size(88.dp).clickable(enabled = enabled, onClickLabel = stringResource(R.string.open_hint, index + 1)) { onSelect(hint) },
                shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                    if (hint.type == PartType.IMAGE.name && hint.mediaPath != null) {
                        AsyncImage(File(context.filesDir, hint.mediaPath), contentDescription = stringResource(R.string.open_hint, index + 1), contentScale = ContentScale.Crop)
                    } else Text(
                        if (hint.type == PartType.TEXT.name) hint.text else stringResource(if (hint.type == PartType.AUDIO.name) R.string.audio_part else R.string.video_part),
                        maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Completion(count: Int, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = CircleShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.size(65.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(30.dp)) }
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.a_little_more_remembered), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(if (count > 0) R.string.showing_up else R.string.all_caught_up), style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif), textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(if (count > 0) stringResource(R.string.reviewed_items, count) else stringResource(R.string.no_due_items), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onExit, shape = RoundedCornerShape(6.dp)) { Text(stringResource(R.string.done)) }
    }
}

@Composable
private fun PartContent(part: ContentPartEntity) {
    val context = LocalContext.current
    when (runCatching { PartType.valueOf(part.type) }.getOrDefault(PartType.TEXT)) {
        PartType.TEXT -> Text(part.text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        PartType.IMAGE -> {
            val file = part.mediaPath?.let { File(context.filesDir, it) }
            if (file?.exists() == true) AsyncImage(model = file, contentDescription = stringResource(R.string.image_part), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp))
            else Text(stringResource(R.string.media_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PartType.AUDIO, PartType.VIDEO -> {
            val file = part.mediaPath?.let { File(context.filesDir, it) }
            if (file?.exists() == true) MediaPlayer(file, part.type == PartType.AUDIO.name)
            else Text(stringResource(R.string.media_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun MediaPlayer(file: File, audio: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(file) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player, lifecycle) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true; controllerShowTimeoutMs = if (audio) 0 else 5_000 } },
        update = { it.player = player },
        modifier = Modifier.fillMaxWidth().height(if (audio) 100.dp else 260.dp),
        onRelease = { it.player = null },
    )
}
