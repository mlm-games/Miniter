package org.mlm.miniter.ui.components.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import org.mlm.miniter.editor.model.RustMarkerSnapshot
import org.mlm.miniter.platform.usToMs

@Composable
fun MarkersPanel(
    markers: List<RustMarkerSnapshot>,
    onSeek: (Long) -> Unit,
    onAddAtPlayhead: () -> Unit,
    onDelete: (String) -> Unit,
    onRelabel: (String, String) -> Unit,
    onLabelFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusedLabelIds = remember { mutableStateListOf<String>() }
    SideEffect { onLabelFocusChanged(focusedLabelIds.isNotEmpty()) }
    DisposableEffect(Unit) {
        onDispose { onLabelFocusChanged(false) }
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(32.dp).padding(start = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Markers",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onAddAtPlayhead, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Add, "Add marker at playhead", Modifier.size(16.dp))
            }
        }
        HorizontalDivider(thickness = 0.5.dp)
        if (markers.isEmpty()) {
            Text(
                "No markers yet. Right-click the ruler to add one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(markers, key = { it.id }) { marker ->
                    MarkerRow(
                        marker = marker,
                        onSeek = onSeek,
                        onDelete = onDelete,
                        onRelabel = onRelabel,
                        onLabelFocusChanged = { focused ->
                            if (focused) {
                                if (marker.id !in focusedLabelIds) focusedLabelIds.add(marker.id)
                            } else {
                                focusedLabelIds.remove(marker.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkerRow(
    marker: RustMarkerSnapshot,
    onSeek: (Long) -> Unit,
    onDelete: (String) -> Unit,
    onRelabel: (String, String) -> Unit,
    onLabelFocusChanged: (Boolean) -> Unit,
) {
    val timeMs = marker.time.usToMs
    var text by remember(marker.id, marker.label) { mutableStateOf(marker.label.orEmpty()) }
    val focusManager = LocalFocusManager.current

    DisposableEffect(marker.id) {
        onDispose { onLabelFocusChanged(false) }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatRulerTime(timeMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onSeek(timeMs) }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier.weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            if (text.isEmpty()) {
                Text(
                    "Label",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { focusState ->
                        onLabelFocusChanged(focusState.isFocused)
                        if (!focusState.isFocused && text != marker.label.orEmpty()) {
                            onRelabel(marker.id, text)
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter)
                        ) {
                            focusManager.clearFocus()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        IconButton(onClick = { onDelete(marker.id) }, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.Default.Delete,
                "Delete marker",
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
