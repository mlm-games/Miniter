package org.mlm.miniter.ui.components.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.mlmgames.settings.core.annotations.SettingPlatform
import org.mlm.miniter.editor.model.RustTrackKind
import org.mlm.miniter.editor.model.RustTrackSnapshot

@Composable
fun TrackHeader(
    track: RustTrackSnapshot,
    canRemove: Boolean,
    onToggleMute: () -> Unit,
    onToggleLock: () -> Unit,
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

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
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
            Icon(
                icon, track.name,
                Modifier.size(18.dp),
                tint = if (track.muted) trackColor.copy(alpha = 0.3f) else trackColor,
            )

            if (track.locked) {
                Icon(
                    Icons.Default.Lock, "Locked",
                    Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
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
                text = { Text(if (track.locked) "Unlock" else "Lock") },
                leadingIcon = {
                    Icon(
                        if (track.locked) Icons.Default.LockOpen else Icons.Default.Lock,
                        null, Modifier.size(18.dp),
                    )
                },
                onClick = { onToggleLock(); showMenu = false },
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
                    DropdownMenuItem(
                        text = { Text("Subtitle Track") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Subtitles,
                                null,
                                Modifier.size(18.dp)
                            )
                        },
                        onClick = { onAddTrack(RustTrackKind.Subtitle); expanded = false },
                    )

                }
            }
        }
    }
}

fun formatRulerTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val tenths = (ms % 1000) / 100
    return if (min > 0) "%d:%02d.%d".format(min, sec, tenths) else "%d.%ds".format(sec, tenths)
}
