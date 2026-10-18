package org.mlm.miniter.ui.components.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mlm.miniter.editor.model.RustTrackKind
import org.mlm.miniter.editor.model.RustTrackSnapshot

@Composable
fun TrackHeader(
    track: RustTrackSnapshot,
    canRemove: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    soloActive: Boolean,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onToggleLock: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemoveTrack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = when (track.kind) {
        RustTrackKind.Video -> Icons.Default.Videocam
        RustTrackKind.Audio -> Icons.Default.MusicNote
        RustTrackKind.Text -> Icons.Default.TextFields
        RustTrackKind.Subtitle -> Icons.Default.Subtitles
    }

    val trackColor = when (track.kind) {
        RustTrackKind.Video -> MaterialTheme.colorScheme.primary
        RustTrackKind.Audio -> MaterialTheme.colorScheme.secondary
        RustTrackKind.Text -> MaterialTheme.colorScheme.tertiary
        RustTrackKind.Subtitle -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    }

    var showMenu by remember { mutableStateOf(false) }
    val soloExcluded = soloActive && !track.solo

    Box(
        modifier = modifier
            .background(
                if (track.solo) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { showMenu = true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    icon, track.name,
                    Modifier.size(18.dp),
                    tint = if (track.muted || soloExcluded) trackColor.copy(alpha = 0.3f) else trackColor,
                )
                if (track.locked) {
                    Icon(
                        Icons.Default.Lock, "Locked",
                        Modifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }

            Spacer(Modifier.height(3.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            if (track.solo) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                        .clickable(onClick = onToggleSolo)
                        .semantics {
                            contentDescription =
                                if (track.solo) "Solo on, tap to unsolo ${track.name}"
                                else "Solo ${track.name}"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "S",
                        fontSize = 10.sp,
                        fontWeight = if (track.solo) FontWeight.Bold else FontWeight.Medium,
                        color = if (track.solo) MaterialTheme.colorScheme.onTertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }

                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            if (track.muted) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                        .clickable(onClick = onToggleMute)
                        .semantics {
                            contentDescription =
                                if (track.muted) "Muted, tap to unmute ${track.name}"
                                else "Mute ${track.name}"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeOff, null,
                        Modifier.size(12.dp),
                        tint = if (track.muted) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(if (track.muted) "Unmute" else "Mute") },
                leadingIcon = {
                    Icon(
                        if (track.muted) Icons.AutoMirrored.Filled.VolumeUp
                        else Icons.AutoMirrored.Filled.VolumeOff,
                        null, Modifier.size(18.dp),
                    )
                },
                onClick = { onToggleMute(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text(if (track.solo) "Unsolo" else "Solo") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Headphones, null, Modifier.size(18.dp),
                        tint = if (track.solo) MaterialTheme.colorScheme.tertiary
                        else LocalContentColor.current,
                    )
                },
                onClick = { onToggleSolo(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text(if (track.locked) "Unlock" else "Lock") },
                leadingIcon = {
                    Icon(
                        if (track.locked) Icons.Default.LockOpen else Icons.Default.Lock,
                        null, Modifier.size(18.dp),
                    )
                },
                onClick = { onToggleLock(); showMenu = false },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Move Up") },
                leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(18.dp)) },
                enabled = canMoveUp,
                onClick = { onMoveUp(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text("Move Down") },
                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp)) },
                enabled = canMoveDown,
                onClick = { onMoveDown(); showMenu = false },
            )
            if (canRemove) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Remove Track", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { onRemoveTrack(); showMenu = false },
                )
            }
        }
    }
}

@Composable
fun AddTrackRow(onAddTrack: (RustTrackKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(48.dp).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Box {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Default.Add, "Add Track", Modifier.size(18.dp))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Video Track") },
                        leadingIcon = { Icon(Icons.Default.Videocam, null, Modifier.size(18.dp)) },
                        onClick = { onAddTrack(RustTrackKind.Video); expanded = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Audio Track") },
                        leadingIcon = { Icon(Icons.Default.MusicNote, null, Modifier.size(18.dp)) },
                        onClick = { onAddTrack(RustTrackKind.Audio); expanded = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Text Track") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.TextFields,
                                null,
                                Modifier.size(18.dp)
                            )
                        },
                        onClick = { onAddTrack(RustTrackKind.Text); expanded = false },
                    )
                }
            }
        }
    }
}

fun formatRulerTime(ms: Long): String {
    val sign = if (ms < 0) "-" else ""
    val absMs = kotlin.math.abs(ms)
    val totalSec = absMs / 1000
    val hours = totalSec / 3600
    val min = (totalSec % 3600) / 60
    val sec = totalSec % 60
    val tenths = (absMs % 1000) / 100
    return buildString {
        append(sign)
        if (hours > 0) {
            append(hours)
            append(':')
            if (min < 10) append('0')
            append(min)
            append(':')
            if (sec < 10) append('0')
            append(sec)
            append('.')
            append(tenths)
        } else if (min > 0) {
            append(min)
            append(':')
            if (sec < 10) append('0')
            append(sec)
            append('.')
            append(tenths)
        } else {
            append(sec)
            append('.')
            append(tenths)
            append('s')
        }
    }
}
