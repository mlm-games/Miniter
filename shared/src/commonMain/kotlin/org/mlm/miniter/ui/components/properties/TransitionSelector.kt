package org.mlm.miniter.ui.components.properties

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.mlm.miniter.editor.model.RustTransitionKind
import org.mlm.miniter.editor.model.RustTransitionSnapshot

data class TransitionOption(
    val kind: RustTransitionKind?,
    val label: String,
    val description: String? = null,
    val icon: ImageVector,
    val defaultDurationUs: Long = 500_000L,
)

val videoTransitionOptions = listOf(
    TransitionOption(null, "None", icon = Icons.Default.Block),
    TransitionOption(RustTransitionKind.CrossFade, "Cross Fade",
        "Fades to/from black between clips", Icons.Default.Animation),
    TransitionOption(RustTransitionKind.Dissolve, "Dissolve",
        "Smooth dissolve between clips", Icons.Default.BlurOn),
    TransitionOption(RustTransitionKind.SlideLeft, "Slide Left",
        "Previous slides out left, this enters from right", Icons.AutoMirrored.Filled.ArrowBack),
    TransitionOption(RustTransitionKind.SlideRight, "Slide Right",
        "Previous slides out right, this enters from left", Icons.AutoMirrored.Filled.ArrowForward),
)

val fadeTransitionOptions = listOf(
    TransitionOption(null, "None", icon = Icons.Default.Block),
    TransitionOption(RustTransitionKind.CrossFade, "Fade", defaultDurationUs = 500_000L, icon = Icons.Default.Animation),
)

val subtitleTransitionOptions = listOf(
    TransitionOption(null, "None", icon = Icons.Default.Block),
    TransitionOption(RustTransitionKind.CrossFade, "Fade", defaultDurationUs = 500_000L, icon = Icons.Default.Animation),
    TransitionOption(RustTransitionKind.SlideLeft, "Slide Left", defaultDurationUs = 500_000L, icon = Icons.AutoMirrored.Filled.ArrowBack),
    TransitionOption(RustTransitionKind.SlideRight, "Slide Right", defaultDurationUs = 500_000L, icon = Icons.AutoMirrored.Filled.ArrowForward),
)

@Composable
fun TransitionSelector(
    label: String,
    description: String? = null,
    currentTransition: RustTransitionSnapshot?,
    options: List<TransitionOption> = videoTransitionOptions,
    durationRange: ClosedFloatingPointRange<Float> = 0.1f..2f,
    durationSteps: Int = 18,
    onSetTransition: (RustTransitionSnapshot?) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    if (description != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))

    var expanded by remember { mutableStateOf(false) }
    val currentOption = options.find { it.kind == currentTransition?.kind }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Icon(
                    currentOption?.icon ?: Icons.Default.Block,
                    null,
                    Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(currentOption?.label ?: "None")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, option ->
                    if (option.kind == null) {
                        DropdownMenuItem(
                            text = { Text("None") },
                            leadingIcon = { Icon(option.icon, null, Modifier.size(18.dp)) },
                            onClick = { onSetTransition(null); expanded = false },
                        )
                    } else {
                        if (index == 1) HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(option.label)
                                    if (option.description != null) {
                                        Text(option.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            leadingIcon = { Icon(option.icon, null, Modifier.size(18.dp)) },
                            onClick = {
                                onSetTransition(RustTransitionSnapshot(option.kind, option.defaultDurationUs))
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }

    if (currentTransition != null) {
        Spacer(Modifier.height(8.dp))
        Text("Duration", style = MaterialTheme.typography.labelSmall)
        var durationVal by remember(currentTransition.duration) {
            mutableFloatStateOf(currentTransition.duration / 1_000_000f)
        }
        Slider(
            value = durationVal,
            onValueChange = { durationVal = it },
            onValueChangeFinished = {
                onSetTransition(
                    RustTransitionSnapshot(
                        kind = currentTransition.kind,
                        duration = (durationVal * 1_000_000L).toLong(),
                    )
                )
            },
            valueRange = durationRange,
            steps = durationSteps,
        )
        Text("${formatFixed(durationVal, 1)}s", style = MaterialTheme.typography.labelSmall)
    }
}
