package org.mlm.miniter.ui.components.toolbar

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import miniter.shared.generated.resources.Res
import miniter.shared.generated.resources.common_back
import miniter.shared.generated.resources.common_export
import miniter.shared.generated.resources.common_more
import miniter.shared.generated.resources.common_redo
import miniter.shared.generated.resources.common_save
import miniter.shared.generated.resources.common_undo
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import org.mlm.miniter.rust.UndoHistoryEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactTopBar(
    projectName: String,
    isDirty: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    historyEntries: List<UndoHistoryEntry>,
    historyOpen: Boolean,
    onToggleHistory: () -> Unit,
    onDismissHistory: () -> Unit,
    onJumpToHistory: (Int) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onImportSubtitles: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Text(
                text = projectName + if (isDirty) " *" else "",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back))
            }
        },
        actions = {
            Box {
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(40.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            enabled = canUndo || canRedo,
                            onClickLabel = stringResource(Res.string.common_undo),
                            onLongClickLabel = "Undo history",
                            onLongClick = onToggleHistory,
                            onClick = { if (canUndo) onUndo() else onToggleHistory() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(Res.string.common_undo), modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = historyOpen,
                    onDismissRequest = onDismissHistory,
                ) {
                    UndoHistoryPanel(
                        entries = historyEntries,
                        onJump = onJumpToHistory,
                    )
                }
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(Res.string.common_redo), modifier = Modifier.size(20.dp))
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.common_more))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.common_save)) },
                        leadingIcon = { Icon(Icons.Default.Save, null, Modifier.size(20.dp)) },
                        onClick = { onSave(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.common_export)) },
                        leadingIcon = { Icon(Icons.Default.FileDownload, null, Modifier.size(20.dp)) },
                        onClick = { onExport(); showMenu = false },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Import to Existing Track") },
                        leadingIcon = { Icon(Icons.Default.VideoFile, null, Modifier.size(20.dp)) },
                        onClick = { onImport(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Import Subtitles") },
                        leadingIcon = { Icon(Icons.Default.Subtitles, null, Modifier.size(20.dp)) },
                        onClick = { onImportSubtitles(); showMenu = false },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

@Composable
private fun UndoHistoryPanel(
    entries: List<UndoHistoryEntry>,
    onJump: (Int) -> Unit,
) {
    if (entries.isEmpty()) {
        Text(
            "Nothing to undo yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
        return
    }

    val currentIndex = entries.indexOfLast { it.applied }
    val rows = entries.withIndex().toList().asReversed()

    Column {
        Text(
            "History",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
        )
        LazyColumn(
            modifier = Modifier.width(240.dp).heightIn(max = 320.dp),
        ) {
            items(rows, key = { it.index }) { row ->
                DropdownMenuItem(
                    text = {
                        Text(
                            row.value.label.ifEmpty { "Edit" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (row.value.applied) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            },
                        )
                    },
                    trailingIcon = if (row.index == currentIndex) {
                        {
                            Icon(
                                Icons.Default.Check,
                                "Current position",
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    enabled = row.index != currentIndex,
                    onClick = { onJump(row.index) },
                )
            }
        }
    }
}
