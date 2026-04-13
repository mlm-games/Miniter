package org.mlm.miniter.ui.components.toolbar

import androidx.compose.animation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mlmgames.settings.core.annotations.SettingPlatform

@Composable
fun ActionBar(
    hasSelection: Boolean,
    zoomLevel: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onAddText: () -> Unit,
    onAddSubtitle: () -> Unit,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onDeselect: () -> Unit,
    onProperties: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = hasSelection,
                transitionSpec = {
                    fadeIn() + slideInVertically { it / 2 } togetherWith
                            fadeOut() + slideOutVertically { -it / 2 }
                },
                label = "action_bar_mode",
            ) { clipSelected ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (clipSelected) {
                        ActionChip(
                            icon = Icons.Default.ContentCut,
                            label = "Split",
                            onClick = onSplit,
                        )
                        ActionChip(
                            icon = Icons.Default.ContentCopy,
                            label = "Duplicate",
                            onClick = onDuplicate,
                        )
                        ActionChip(
                            icon = Icons.Default.Delete,
                            label = "Delete",
                            onClick = onDelete,
                            isDestructive = true,
                        )
                        ActionChip(
                            icon = Icons.Default.Settings,
                            label = "Properties",
                            onClick = onProperties,
                        )
                        ActionChip(
                            icon = Icons.Default.Close,
                            label = "Deselect",
                            onClick = onDeselect,
                        )
                    } else {
                        ActionChip(
                            icon = Icons.Default.TextFields,
                            label = "Text",
                            onClick = onAddText,
                        )
                        ActionChip(
                            icon = Icons.Default.Subtitles,
                            label = "Subtitle",
                            onClick = onAddSubtitle,
                        )

                        ActionChip(
                            icon = Icons.Default.ZoomOut,
                            label = "",
                            onClick = onZoomOut,
                        )
                        Text(
                            "${(zoomLevel * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ActionChip(
                            icon = Icons.Default.ZoomIn,
                            label = "",
                            onClick = onZoomIn,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
) {
    val colors = if (isDestructive) {
        AssistChipDefaults.assistChipColors(
            labelColor = MaterialTheme.colorScheme.error,
            leadingIconContentColor = MaterialTheme.colorScheme.error,
        )
    } else {
        AssistChipDefaults.assistChipColors()
    }

    AssistChip(
        onClick = onClick,
        label = {
            if (label.isNotEmpty()) {
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        },
        leadingIcon = {
            Icon(icon, label, Modifier.size(16.dp))
        },
        colors = colors,
        modifier = Modifier.height(32.dp),
    )
}
