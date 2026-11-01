package com.gostafa.raven.features.library

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.gostafa.raven.R
import com.gostafa.raven.core.database.ContentPartEntity
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Ownership spans visible cards so starting a preview always pauses the previous one. */
internal object PreviewPlayback {
    private var active: Player? = null
    fun claim(player: Player) { if (active !== player) active?.pause(); active = player }
    fun pause() { active?.pause() }
    fun release(player: Player) { if (active === player) active = null; player.release() }
}

@Composable
private fun previewPlayer(file: File?, enabled: Boolean): ExoPlayer? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(context, file, enabled, lifecycle) {
        if (!enabled || file?.isFile != true) null else ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) player?.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player?.let(PreviewPlayback::release)
        }
    }
    return player
}

internal fun previewTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CardAudio(part: ContentPartEntity, file: File?, modifier: Modifier = Modifier, visible: Boolean = true) {
    var requested by remember(file) { mutableStateOf(false) }
    var duration by remember(file) { mutableLongStateOf(0L) }
    var position by remember(file) { mutableLongStateOf(0L) }
    var playing by remember(file) { mutableStateOf(false) }
    var buffering by remember(file) { mutableStateOf(false) }
    var failed by remember(file) { mutableStateOf(false) }
    var metadataLoaded by remember(file) { mutableStateOf(false) }
    val player = previewPlayer(file, requested && visible)
    val label = part.originalName ?: stringResource(R.string.audio_part)
    LaunchedEffect(visible) {
        if (!visible) { requested = false; playing = false; buffering = false; position = 0 }
    }
    LaunchedEffect(file, visible) {
        if (!visible || metadataLoaded) return@LaunchedEffect
        val metadata = withContext(Dispatchers.IO) {
            runCatching {
                checkNotNull(file)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.path)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally { retriever.release() }
            }
        }
        duration = metadata.getOrDefault(0L)
        metadataLoaded = true
        failed = metadata.isFailure
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; if (isPlaying) failed = false }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!playWhenReady) buffering = false }
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING && player?.playWhenReady == true
                if (player != null && player.duration > 0) duration = player.duration
            }
            override fun onPlayerError(error: PlaybackException) { failed = true; buffering = false; playing = false }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player) {
        if (player != null) { PreviewPlayback.claim(player); player.play() }
    }
    LaunchedEffect(player, playing) {
        while (player != null && playing) {
            position = player.currentPosition.coerceAtLeast(0)
            delay(200)
        }
        if (player != null) position = player.currentPosition.coerceAtLeast(0)
    }
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledIconButton(
                onClick = {
                    if (player == null) requested = true
                    else if (playing || buffering) player.pause()
                    else {
                        failed = false
                        if (player.playerError != null) player.prepare()
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        PreviewPlayback.claim(player)
                        player.play()
                    }
                },
                enabled = file?.isFile == true && metadataLoaded,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
            ) {
                Icon(if (playing || buffering) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (playing || buffering) R.string.pause_card_audio else R.string.play_card_audio, label))
            }
            Column(Modifier.weight(1f)) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(when {
                    failed -> R.string.preview_playback_failed
                    buffering || !metadataLoaded -> R.string.preview_loading
                    playing -> R.string.preview_playing
                    else -> R.string.audio_part
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val seekLabel = stringResource(R.string.seek_card_audio, label)
            val timesLabel = stringResource(R.string.preview_audio_times, previewTime(position), if (duration > 0) previewTime(duration) else "--:--")
            Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                onValueChange = { position = it.toLong(); player?.seekTo(position) },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0 && player != null && !failed,
                modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = seekLabel; stateDescription = timesLabel },
                thumb = {
                    Box(Modifier.size(16.dp).background(if (player != null && !failed) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                },
                track = {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outline)) {
                        Box(Modifier.fillMaxWidth((position.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f))
                            .height(2.dp).background(Color.White))
                    }
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(previewTime(position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (duration > 0) previewTime(duration) else "--:--", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun CardVideo(file: File?) {
    val player = previewPlayer(file, true)
    var failed by remember(file) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && player != null) PreviewPlayback.claim(player)
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player) { if (player != null) { PreviewPlayback.claim(player); player.play() } }
    if (player == null || failed) {
        MediaUnavailable()
        if (player != null) TextButton(onClick = { failed = false; player.prepare(); PreviewPlayback.claim(player); player.play() }) { Text(stringResource(R.string.retry)) }
    } else AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true } },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxWidth().height(260.dp),
    )
}
