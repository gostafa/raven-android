package com.gostafa.raven.features.library

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import com.gostafa.raven.core.designsystem.RavenDropdownMenu as DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.gostafa.raven.R
import com.gostafa.raven.core.database.CardPreview
import com.gostafa.raven.core.database.ContentPartEntity
import com.gostafa.raven.core.model.PartType
import java.io.File
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun recallColor(percent: Int): Color {
    val red = Color(0xFFF87171).convert(ColorSpaces.Oklab)
    val yellow = Color(0xFFFACC15).convert(ColorSpaces.Oklab)
    val green = Color(0xFF4ADE80).convert(ColorSpaces.Oklab)
    val value = percent.coerceIn(0, 100)
    return when {
        value <= 50 -> red
        value <= 80 -> lerp(red, yellow, (value - 50) / 30f)
        else -> lerp(yellow, green, (value - 80) / 20f)
    }.convert(ColorSpaces.Srgb)
}

@Composable
internal fun CardRow(
    card: CardPreview,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPractice: () -> Unit,
    folderName: String? = null,
    selecting: Boolean = false,
    selected: Boolean = false,
    onSelect: () -> Unit = {},
    onStartSelection: () -> Unit = {},
    onMove: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
) {
    val summary = card.summary
    val parts = remember(card.parts) { card.parts.sortedBy { it.orderIndex } }
    var selectedId by rememberSaveable(summary.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(selecting) { if (selecting) { PreviewPlayback.pause(); selectedId = null } }
    val accent = recallColor(summary.recallPercent)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (folderName != null) Text(folderName, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Surface(shape = RoundedCornerShape(10.dp), color = Color.Black,
            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else accent.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) onSelect() }, onLongClick = onStartSelection)) {
            BoxWithConstraints(Modifier.padding(6.dp)) {
                val stacked = maxWidth + 12.dp < 320.dp || LocalConfiguration.current.fontScale >= 1.3f
                val rows = if (stacked) parts.size.coerceAtLeast(1) else ((parts.size + 1) / 2).coerceAtLeast(1)
                val bodyHeight = if (stacked) 180.dp * rows else maxOf(maxWidth * 1.25f - 48.dp, 160.dp * rows)
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) Checkbox(checked = selected, onCheckedChange = { onSelect() })
                        else CardActions(onEdit, onPractice, onDelete, onMove, onCopy)
                        Spacer(Modifier.weight(1f))
                        val recallDescription = stringResource(R.string.card_recall_percent, summary.recallPercent)
                        Text(NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0]).format(summary.recallPercent / 100.0), color = accent,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.semantics { contentDescription = recallDescription })
                    }
                    Box {
                    Column(Modifier.fillMaxWidth().height(bodyHeight).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.outline),
                        verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        if (parts.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(summary.preview.ifBlank { stringResource(R.string.media_card) })
                        }
                        parts.chunked(if (stacked) 1 else 2).forEach { row ->
                            Row(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.outline),
                                horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                row.forEach { part -> key(part.id) {
                                    PartTile(part, Modifier.weight(1f).fillMaxHeight()) {
                                        PreviewPlayback.pause()
                                        selectedId = part.id
                                    }
                                } }
                            }
                        }
                    }
                    if (selecting) Box(Modifier.matchParentSize().clickable(onClick = onSelect))
                    }
                }
            }
        }
    }
    parts.firstOrNull { it.id == selectedId }?.let { part ->
        PartPreviewDialog(part) { selectedId = null }
    }
}

@Composable
private fun CardActions(onEdit: () -> Unit, onPractice: () -> Unit, onDelete: () -> Unit, onMove: (() -> Unit)?, onCopy: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreHoriz, stringResource(R.string.card_actions), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, containerColor = MaterialTheme.colorScheme.surfaceVariant) {
            if (onMove != null) DropdownMenuItem(text = { Text(stringResource(R.string.move_to)) }, onClick = { expanded = false; PreviewPlayback.pause(); onMove() })
            if (onCopy != null) DropdownMenuItem(text = { Text(stringResource(R.string.copy_cards)) }, onClick = { expanded = false; PreviewPlayback.pause(); onCopy() })
            listOf(R.string.edit to onEdit, R.string.practice_card to onPractice, R.string.delete to onDelete).forEach { (label, action) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { expanded = false; PreviewPlayback.pause(); action() })
            }
        }
    }
}

@Composable
private fun PartTile(part: ContentPartEntity, modifier: Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    val file = remember(part.mediaPath) { part.mediaPath?.let { File(context.filesDir, it) } }
    val type = runCatching { PartType.valueOf(part.type) }.getOrDefault(PartType.TEXT)
    val label = partLabel(type)
    val openLabel = stringResource(R.string.open_card_part, label, part.orderIndex + 1)
    var visible by remember(part.id) { mutableStateOf(false) }
    val window = LocalWindowInfo.current.containerSize
    Box(modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 && bounds.right > 0 && bounds.top < window.height && bounds.left < window.width
    }.background(Color.Black).then(if (type != PartType.AUDIO) Modifier.clickable(onClickLabel = openLabel, onClick = onOpen) else Modifier),
        contentAlignment = Alignment.Center) {
        when (type) {
            PartType.TEXT -> Text(part.text, modifier = Modifier.padding(12.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
            PartType.IMAGE -> CardImage(file, part.originalName ?: label, ContentScale.Crop, Modifier.fillMaxSize())
            PartType.AUDIO -> CardAudio(part, file, Modifier.fillMaxSize().padding(horizontal = 10.dp), visible = visible)
            PartType.VIDEO -> {
                VideoThumbnail(file, part.originalName ?: label)
                FilledIconButton(onClick = onOpen, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    Icon(Icons.Default.PlayArrow, openLabel)
                }
            }
        }
    }
}

@Composable
internal fun partLabel(type: PartType): String = stringResource(when (type) {
    PartType.TEXT -> R.string.text_part
    PartType.IMAGE -> R.string.image_part
    PartType.AUDIO -> R.string.audio_part
    PartType.VIDEO -> R.string.video_part
})

@Composable
private fun CardImage(file: File?, label: String, scale: ContentScale, modifier: Modifier) {
    var failed by remember(file) { mutableStateOf(false) }
    if (file == null || failed) MediaUnavailable()
    else AsyncImage(model = file, contentDescription = label, contentScale = scale, modifier = modifier, onError = { failed = true })
}

@Composable
private fun VideoThumbnail(file: File?, label: String) {
    var loaded by remember(file) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { file?.let { source ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(source.path)
                    if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 480)
                    else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                finally { retriever.release() }
            } }.getOrNull()
        }
        loaded = true
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else if (loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { MediaUnavailable() }
    else Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 96.dp, start = 8.dp, end = 8.dp))
}


@Composable
internal fun MediaUnavailable() {
    Text(stringResource(R.string.media_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
}

@Composable
private fun PartPreviewDialog(part: ContentPartEntity, onClose: () -> Unit) {
    val context = LocalContext.current
    val file = remember(part.mediaPath) { part.mediaPath?.let { File(context.filesDir, it) } }
    val type = runCatching { PartType.valueOf(part.type) }.getOrDefault(PartType.TEXT)
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.85f
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).heightIn(max = maxHeight),
            shape = RoundedCornerShape(10.dp), color = Color.Black, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(part.originalName ?: partLabel(type), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close_preview)) }
                }
                when (type) {
                    PartType.TEXT -> Text(part.text, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
                    PartType.IMAGE -> Box(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 480.dp), contentAlignment = Alignment.Center) {
                        CardImage(file, part.originalName ?: partLabel(type), ContentScale.Fit, Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 480.dp))
                    }
                    PartType.VIDEO -> CardVideo(file)
                    PartType.AUDIO -> CardAudio(part, file, Modifier.fillMaxWidth().padding(16.dp))
                }
            }
        }
    }
}
