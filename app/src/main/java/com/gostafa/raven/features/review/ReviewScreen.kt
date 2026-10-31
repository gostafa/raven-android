package com.gostafa.raven.features.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.gostafa.raven.R
import com.gostafa.raven.core.database.ContentPartEntity
import com.gostafa.raven.core.model.PartType
import com.gostafa.raven.core.model.Rating
import java.io.File
import android.net.Uri

@Composable
fun ReviewScreen(
    mode: String,
    target: String?,
    onExit: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(mode, target) { viewModel.begin(mode, target) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel)) }
                Text(stringResource(R.string.review), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                state.current?.let {
                    Text(stringResource(R.string.session_progress, state.index + 1, state.items.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.items.isEmpty()) {
                        Text(stringResource(R.string.no_due_items), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(stringResource(R.string.review_complete), style = MaterialTheme.typography.headlineSmall)
                            Text(
                                stringResource(R.string.reviewed_items, state.index),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = onExit) { Text(stringResource(R.string.back)) }
                        }
                    }
                }
                else -> {
                    val item = state.current!!
                    Text(item.content.folderName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Card(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(stringResource(R.string.reveal_answer), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PartContent(item.prompt)
                            if (state.revealed) {
                                item.content.parts.asSequence().filter { it.id != item.prompt.id }.forEach { contextPart ->
                                    Text(stringResource(if (contextPart.rememberThis) R.string.remember_this else R.string.context_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    PartContent(contextPart)
                                }
                            }
                        }
                    }
                    if (!state.revealed) {
                        FilledTonalButton(onClick = viewModel::reveal, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.show_context))
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            RatingButton(Rating.AGAIN, Modifier.weight(1f), enabled = !state.submitting, onClick = viewModel::rate)
                            RatingButton(Rating.HARD, Modifier.weight(1f), enabled = !state.submitting, onClick = viewModel::rate)
                            RatingButton(Rating.GOOD, Modifier.weight(1f), enabled = !state.submitting, onClick = viewModel::rate)
                            RatingButton(Rating.EASY, Modifier.weight(1f), enabled = !state.submitting, onClick = viewModel::rate)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingButton(rating: Rating, modifier: Modifier, enabled: Boolean, onClick: (Rating) -> Unit) {
    val label = when (rating) {
        Rating.AGAIN -> stringResource(R.string.again)
        Rating.HARD -> stringResource(R.string.hard)
        Rating.GOOD -> stringResource(R.string.good)
        Rating.EASY -> stringResource(R.string.easy)
    }
    OutlinedButton(onClick = { onClick(rating) }, enabled = enabled, modifier = modifier) { Text(label) }
}

@Composable
private fun PartContent(part: ContentPartEntity) {
    val context = LocalContext.current
    when (runCatching { PartType.valueOf(part.type) }.getOrDefault(PartType.TEXT)) {
        PartType.TEXT -> Text(part.text, style = MaterialTheme.typography.headlineSmall)
        PartType.IMAGE -> {
            val file = part.mediaPath?.let { File(context.filesDir, it) }
            if (file?.exists() == true) {
                AsyncImage(model = file, contentDescription = null, modifier = Modifier.fillMaxWidth().height(260.dp))
            } else Text(stringResource(R.string.media_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PartType.AUDIO, PartType.VIDEO -> {
            val file = part.mediaPath?.let { File(context.filesDir, it) }
            if (file?.exists() == true) MediaPlayer(file) else Text(stringResource(R.string.media_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MediaPlayer(file: File) {
    val context = LocalContext.current
    val player = remember(file) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player, file) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        onDispose { player.release() }
    }
    AndroidView(
        factory = { viewContext -> PlayerView(viewContext).apply { this.player = player; useController = true } },
        update = { it.player = player },
        modifier = Modifier.fillMaxWidth().height(220.dp),
    )
}
