package org.mlm.miniter.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.path
import org.koin.compose.koinInject
import org.mlm.miniter.engine.ExportProgress
import org.mlm.miniter.project.ExportFormat
import org.mlm.miniter.project.ExportSettings
import org.mlm.miniter.ui.components.dialogs.ConfirmDialog
import org.mlm.miniter.ui.util.popBack
import org.mlm.miniter.viewmodel.ProjectViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(backStack: NavBackStack<NavKey>) {
    val vm: ProjectViewModel = koinInject()
    val uiState by vm.state.collectAsState()
    val project = uiState.project
    val progress by vm.exportProgress.collectAsState()

    val settings = project?.exportSettings ?: ExportSettings()
    var format by remember(settings.format) { mutableStateOf(settings.format) }
    var quality by remember(settings.quality) { mutableFloatStateOf(settings.quality) }
    var customWidth by remember(settings.width) { mutableStateOf(settings.width.takeIf { it > 0 }?.toString() ?: "") }
    var customHeight by remember(settings.height) { mutableStateOf(settings.height.takeIf { it > 0 }?.toString() ?: "") }

    var outputFile by remember { mutableStateOf<PlatformFile?>(null) }
    var showCancelConfirm by remember { mutableStateOf(false) }

    val isExporting = progress.progress > 0f &&
            !progress.isComplete &&
            !progress.isCancelled &&
            progress.error == null

    DisposableEffect(Unit) {
        onDispose { vm.resetExport() }
    }

    val fileSaverLauncher = rememberFileSaverLauncher { file: PlatformFile? ->
        outputFile = file
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isExporting) showCancelConfirm = true
                            else backStack.popBack()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Format", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ExportFormat.entries.forEachIndexed { index, fmt ->
                    val enabled = !isExporting && fmt != ExportFormat.WebM
                    SegmentedButton(
                        selected = format == fmt,
                        onClick = { format = fmt },
                        shape = SegmentedButtonDefaults.itemShape(index, ExportFormat.entries.size),
                        enabled = enabled,
                    ) {
                        Text(fmt.name + if (fmt == ExportFormat.WebM) " (unsupported)" else "")
                    }
                }
            }

            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Quality", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${quality.toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = quality,
                    onValueChange = { quality = it },
                    valueRange = 1f..100f,
                    steps = 19,
                    enabled = !isExporting,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Smaller file", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Higher quality", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text("Resolution", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = customWidth,
                    onValueChange = { customWidth = it.filter { c -> c.isDigit() } },
                    label = { Text("Width") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = !isExporting,
                    supportingText = { Text("0 = source") },
                )
                Text("×", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = customHeight,
                    onValueChange = { customHeight = it.filter { c -> c.isDigit() } },
                    label = { Text("Height") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = !isExporting,
                    supportingText = { Text("0 = source") },
                )
            }

            HorizontalDivider()

            // Output file picker — uses native Save dialog
            Text("Save as", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = outputFile?.path ?: "Choose save location…",
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !isExporting,
                )
                FilledTonalButton(
                    onClick = {
                        fileSaverLauncher.launch(
                            suggestedName = project?.name ?: "export",
                            extension = format.extension,
                        )
                    },
                    enabled = !isExporting,
                ) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browse")
                }
            }

            Spacer(Modifier.height(4.dp))

            AnimatedContent(
                targetState = Triple(progress.isComplete, progress.error, isExporting),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "export_status",
            ) { (complete, error, exporting) ->
                when {
                    complete -> {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            "Export complete!",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            outputFile?.path ?: "${project?.name}.${format.extension}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    vm.resetExport()
                                    outputFile = null
                                }) {
                                    Text("Export Again")
                                }
                                OutlinedButton(onClick = { backStack.popBack() }) {
                                    Text("Back to Editor")
                                }
                            }
                        }
                    }

                    error != null -> {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Error, null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                            OutlinedButton(onClick = { vm.resetExport() }) {
                                Text("Try Again")
                            }
                        }
                    }

                    exporting -> {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                progress.phase,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            LinearProgressIndicator(
                                progress = { progress.progress },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                            )
                            Text(
                                "${(progress.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = { vm.cancelExport() },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Cancel Export")
                            }
                        }
                    }

                    else -> {
                        Button(
                            onClick = {
                                val file = outputFile ?: return@Button
                                vm.updateExportSettings(
                                    ExportSettings(
                                        format = format,
                                        quality = quality,
                                        width = customWidth.toIntOrNull() ?: 0,
                                        height = customHeight.toIntOrNull() ?: 0,
                                    )
                                )
                                // Pass the platform file path — on Android this is a
                                // content:// URI that PlatformVideoEngine handles via
                                // the temp-file + contentResolver.openOutputStream approach
                                vm.startExport(file.path)
                            },
                            enabled = outputFile != null && project != null,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Icon(Icons.Default.FileDownload, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start Export")
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            if (project != null) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Project Info",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))

                        val totalClips = project.timeline.tracks.sumOf { it.clips.size }
                        val totalTracks = project.timeline.tracks.size
                        val durationSec = project.timeline.durationMs / 1000

                        Text(
                            "$totalTracks tracks · $totalClips clips · ${durationSec}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        val w = project.exportSettings.width
                        val h = project.exportSettings.height
                        if (w > 0 && h > 0) {
                            Text(
                                "Source: ${w}×${h}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCancelConfirm) {
        ConfirmDialog(
            title = "Cancel Export?",
            message = "Export is in progress. Are you sure you want to cancel?",
            confirmText = "Cancel Export",
            dismissText = "Continue",
            onConfirm = {
                vm.cancelExport()
                showCancelConfirm = false
                backStack.popBack()
            },
            onDismiss = { showCancelConfirm = false },
        )
    }
}
