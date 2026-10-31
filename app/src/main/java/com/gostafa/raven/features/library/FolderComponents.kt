package com.gostafa.raven.features.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gostafa.raven.R
import com.gostafa.raven.core.database.FolderSummary

private val RecallOrange = Color(0xFFFF9955)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FolderTags(
    tags: List<String>,
    modifier: Modifier = Modifier,
    selectedTags: List<String> = emptyList(),
    onTagClick: ((String) -> Unit)? = null,
    removable: Boolean = false,
    enabled: Boolean = true,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.forEach { tag ->
            val selected = selectedTags.any { it.equals(tag, ignoreCase = true) }
            val description = stringResource(if (removable) R.string.remove_tag else R.string.filter_by_tag, tag)
            val content: @Composable () -> Unit = {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tag, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (removable) Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
            if (onTagClick == null) {
                Surface(shape = RoundedCornerShape(7.dp), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), content = content)
            } else {
                Surface(
                    onClick = { onTagClick(tag) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
                    selected = selected,
                    shape = RoundedCornerShape(7.dp),
                    color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun FolderTile(
    folder: FolderSummary,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    selectedTags: List<String>,
    onTagClick: (String) -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    val rear = MaterialTheme.colorScheme.surfaceVariant
    val density = androidx.compose.ui.platform.LocalDensity.current
    val radius = with(density) { 18.dp.toPx() }
    val lip = with(density) { 30.dp.toPx() }
    val shape = GenericShape { size, direction ->
        val w = size.width
        val h = size.height
        val tab = w * 0.40f
        fun x(value: Float) = if (direction == LayoutDirection.Rtl) w - value else value
        moveTo(x(0f), radius)
        quadraticTo(x(0f), 0f, x(radius), 0f)
        lineTo(x(tab - radius), 0f)
        quadraticTo(x(tab), 0f, x(tab), radius)
        lineTo(x(tab), lip)
        lineTo(x(w - radius), lip)
        quadraticTo(x(w), lip, x(w), lip + radius)
        lineTo(x(w), h - radius)
        quadraticTo(x(w), h, x(w - radius), h)
        lineTo(x(radius), h)
        quadraticTo(x(0f), h, x(0f), h - radius)
        close()
    }
    Box(Modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize()) {
            val inset = 10.dp.toPx()
            val top = 15.dp.toPx()
            val backSize = Size(size.width - inset * 2, size.height - top)
            drawRoundRect(rear, Offset(inset, top), backSize, CornerRadius(radius))
            drawRoundRect(outline, Offset(inset, top), backSize, CornerRadius(radius), style = Stroke(1.dp.toPx()))
        }
        Surface(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, outline),
        ) {
            Column(Modifier.fillMaxWidth().heightIn(min = 236.dp).padding(start = 18.dp, top = 44.dp, end = 18.dp, bottom = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(folder.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    OverflowMenu(label = stringResource(R.string.folder_actions, folder.name), onEdit = onEdit, onDelete = onDelete, horizontal = true)
                }
                if (folder.description.isNotBlank()) {
                    Text(folder.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    pluralStringResource(R.plurals.card_count, folder.cardCount, folder.cardCount) + " · " + stringResource(R.string.due_count, folder.dueCount),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FolderTags(folder.tags, Modifier.weight(1f), selectedTags, onTagClick)
                    FolderRecallRing(folder.completedCardCount, folder.cardCount)
                }
            }
        }
    }
}

@Composable
internal fun FolderRecallRing(completed: Int, total: Int) {
    val progress = if (total == 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)
    val description = stringResource(R.string.folder_recall_progress, completed, total)
    Box(
        Modifier.size(68.dp).semantics(mergeDescendants = true) {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize().padding(4.dp)) {
            val stroke = Stroke(5.dp.toPx(), cap = StrokeCap.Round)
            drawArc(RecallOrange.copy(alpha = 0.25f), -90f, 360f, false, style = stroke)
            if (progress > 0) drawArc(RecallOrange, -90f, progress * 360f, false, style = stroke)
        }
        Text(stringResource(R.string.completed_fraction, completed, total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    }
}
