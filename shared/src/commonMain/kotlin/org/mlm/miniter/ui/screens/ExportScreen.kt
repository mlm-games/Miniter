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
import io.github.mlmgames.settings.core.SettingsRepository
import org.koin.compose.koinInject
import org.mlm.miniter.platform.platformPath
import org.mlm.miniter.platform.isProjectExportSupported
import org.mlm.miniter.platform.requiresExplicitExportPathSelection
import org.mlm.miniter.editor.model.RustExportFormat
import org.mlm.miniter.editor.model.RustExportProfileSnapshot
import org.mlm.miniter.editor.model.RustExportResolution
import org.mlm.miniter.editor.model.RustSubtitleMode
import org.mlm.miniter.editor.model.RustVideoClipKind
import org.mlm.miniter.engine.ExportProgress
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.ui.components.dialogs.ConfirmDialog
import org.mlm.miniter.ui.util.popBack
import org.mlm.miniter.viewmodel.ProjectViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(backStack: NavBackStack<NavKey>) {
    val vm: ProjectViewModel = koinInject()
    val uiState by vm.state.collectAsState()
    val snapshot = uiState.snapshot
    val progress by vm.exportProgress.collectAsState()

    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(settingsRepository.schema.default)

    val profile = snapshot?.exportProfile
    val profileFormat = (profile?.format ?: settings.defaultExportFormat).takeIf { it != RustExportFormat.Mov } ?: RustExportFormat.Av1Mp4
    val (profileWidth, profileHeight) = when (val resolution = profile?.resolution) {
        RustExportResolution.Source -> 0 to 0
        RustExportResolution.Sd480 -> 854 to 480
        RustExportResolution.Hd720 -> 1280 to 720
        RustExportResolution.Hd1080 -> 1920 to 1080
        RustExportResolution.Uhd4k -> 3840 to 2160
        is RustExportResolution.Custom -> resolution.width to resolution.height
        null -> 0 to 0
    }
    val profileQuality = profile
        ?.videoBitrateKbps
        ?.let { bitrate -> ((bitrate.toFloat() - 500f) / 80f).coerceIn(1f, 100f) }
        ?: settings.exportQuality

    val sourceWidth = snapshot?.timeline?.tracks
        ?.flatMap { it.clips }
        ?.firstNotNullOfOrNull { clip ->
            (clip.kind as? RustVideoClipKind)?.width?.takeIf { it > 0 }
        } ?: 0
    val sourceHeight = snapshot?.timeline?.tracks
        ?.flatMap { it.clips }
        ?.firstNotNullOfOrNull { clip ->
            (clip.kind as? RustVideoClipKind)?.height?.takeIf { it > 0 }
        } ?: 0
    val hasSourceDimensions = sourceWidth > 0 && sourceHeight > 0
    val sourceResolutionText = if (hasSourceDimensions) "${sourceWidth}×${sourceHeight}" else "Unknown"
    val sourceHintText = if (hasSourceDimensions) "0 = source ($sourceResolutionText)" else "0 = source (No vid?)"

    val displayWidth = if (profileWidth > 0) profileWidth else sourceWidth
    val displayHeight = if (profileHeight > 0) profileHeight else sourceHeight

    var format by remember(profileFormat) { mutableStateOf(profileFormat) }
    var quality by remember(profileQuality) { mutableFloatStateOf(profileQuality) }
    var customWidth by remember(displayWidth) { mutableStateOf(displayWidth.takeIf { it > 0 }?.toString() ?: "") }
    var customHeight by remember(displayHeight) { mutableStateOf(displayHeight.takeIf { it > 0 }?.toString() ?: "") }
    var subtitleMode by remember(profile?.subtitleMode) {
        mutableStateOf(profile?.subtitleMode ?: RustSubtitleMode.Soft)
    }

    var outputFile by remember { mutableStateOf<PlatformFile?>(null) }
    var showCancelConfirm by remember { mutableStateOf(false) }

    val isExporting = progress.progress > 0f &&
            !progress.isComplete &&
            !progress.isCancelled &&
            progress.error == null
    val exportSupported = isProjectExportSupported
    val needsOutputPicker = requiresExplicitExportPathSelection
    val platformFormats = RustExportFormat.entries.filter { it != RustExportFormat.Mov }

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
                platformFormats.forEachIndexed { index, fmt ->
                    val enabled = !isExporting
                    SegmentedButton(
                        selected = format == fmt,
                        onClick = { format = fmt },
                        shape = SegmentedButtonDefaults.itemShape(index, platformFormats.size),
                        enabled = enabled,
                    ) {
                        Text(
                            when (fmt) {
                                RustExportFormat.Mp4 -> "H.264 / MP4"
                                RustExportFormat.Av1Mp4 -> "AV1 / MP4"
                                RustExportFormat.Av1Ivf -> "AV1 / IVF"
                                RustExportFormat.Mov -> "H.264 / MOV"
                            }
                        )
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
                    supportingText = { Text(sourceHintText) },
                )
                Text("×", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = customHeight,
                    onValueChange = { customHeight = it.filter { c -> c.isDigit() } },
                    label = { Text("Height") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = !isExporting,
                    supportingText = { Text(sourceHintText) },
                )
            }

            Text("Subtitles", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val subtitleOptions = listOf(RustSubtitleMode.Soft, RustSubtitleMode.Hard)
                subtitleOptions.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = subtitleMode == mode,
                        onClick = { subtitleMode = mode },
                        shape = SegmentedButtonDefaults.itemShape(index, subtitleOptions.size),
                        enabled = !isExporting && mode != RustSubtitleMode.Hard,
                    ) {
                        Text(
                            when (mode) {
                                RustSubtitleMode.Hard -> "Burn in"
                                RustSubtitleMode.Soft -> "Embed (soft)"
                            }
                        )
                    }
                }
            }

            if (subtitleMode == RustSubtitleMode.Soft && format == RustExportFormat.Mp4) {
                Text(
                    "ASS/SSA styling is converted to plain text in MP4 soft subtitles.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider()

            if (!exportSupported) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Export is not available on web yet. Use desktop or Android to render files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else if (needsOutputPicker) {
                Text("Save as", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = outputFile?.platformPath() ?: "Choose save location…",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !isExporting,
                    )
                    FilledTonalButton(
                        onClick = {
                            fileSaverLauncher.launch(
                                suggestedName = snapshot?.meta?.name ?: "export",
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
                                            outputFile?.platformPath()
                                                ?: if (needsOutputPicker) {
                                                    "${snapshot?.meta?.name}.${format.extension}"
                                                } else {
                                                    "Downloaded via browser"
                                                },
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
                        if (exportSupported) {
                            Button(
                                onClick = {
                                    val outputPath = if (needsOutputPicker) {
                                        val file = outputFile ?: return@Button
                                        file.platformPath()
                                    } else {
                                        "${snapshot?.meta?.name ?: "export"}.${format.extension}"
                                    }
                                    val parsedWidth = customWidth.toIntOrNull() ?: 0
                                    val parsedHeight = customHeight.toIntOrNull() ?: 0
                                    vm.updateExportProfile(
                                        RustExportProfileSnapshot(
                                            format = format,
                                            resolution = when {
                                                parsedWidth <= 0 || parsedHeight <= 0 -> RustExportResolution.Source
                                                hasSourceDimensions && parsedWidth == sourceWidth && parsedHeight == sourceHeight -> RustExportResolution.Source
                                                parsedWidth == 854 && parsedHeight == 480 -> RustExportResolution.Sd480
                                                parsedWidth == 1280 && parsedHeight == 720 -> RustExportResolution.Hd720
                                                parsedWidth == 1920 && parsedHeight == 1080 -> RustExportResolution.Hd1080
                                                parsedWidth == 3840 && parsedHeight == 2160 -> RustExportResolution.Uhd4k
                                                parsedWidth > 0 && parsedHeight > 0 -> RustExportResolution.Custom(
                                                    parsedWidth,
                                                    parsedHeight,
                                                )
                                                else -> RustExportResolution.Source
                                            },
                                            fps = 30.0,
                                            videoBitrateKbps = (500 + quality * 80).toInt().coerceAtLeast(500),
                                            audioBitrateKbps = 192,
                                            audioSampleRate = 48_000,
                                            outputPath = "",
                                            subtitleMode = subtitleMode,
                                        )
                                    )
                                    vm.startExport(outputPath)
                                },
                                enabled = snapshot != null && (!needsOutputPicker || outputFile != null),
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) {
                                Icon(Icons.Default.FileDownload, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Start Export")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            if (snapshot != null) {
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

                        val totalClips = snapshot.timeline.tracks.sumOf { it.clips.size }
                        val totalTracks = snapshot.timeline.tracks.size
                        val durationSec = (snapshot.timeline.tracks
                            .flatMap { it.clips }
                            .maxOfOrNull { it.timelineStartUs + it.timelineDurationUs } ?: 0L) / 1_000_000L

                        Text(
                            "$totalTracks tracks · $totalClips clips · ${durationSec}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        val outputWidth = (customWidth.toIntOrNull() ?: 0).takeIf { it > 0 }
                            ?: sourceWidth.takeIf { it > 0 }
                        val outputHeight = (customHeight.toIntOrNull() ?: 0).takeIf { it > 0 }
                            ?: sourceHeight.takeIf { it > 0 }
                        if (outputWidth != null && outputHeight != null) {
                            Text(
                                "Output: ${outputWidth}×${outputHeight}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "Source: $sourceResolutionText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

private val RustExportFormat.extension: String
    get() = when (this) {
        RustExportFormat.Mp4 -> "mp4"
        RustExportFormat.Av1Ivf -> "ivf"
        RustExportFormat.Av1Mp4 -> "mp4"
        RustExportFormat.Mov -> "mov"
    }
