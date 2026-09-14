@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package org.mlm.miniter.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.mlm.miniter.editor.model.RustExportFormat
import org.mlm.miniter.editor.model.RustSubtitleMode
import org.mlm.miniter.editor.model.RustVideoClipKind
import org.mlm.miniter.engine.ExportProgress
import org.mlm.miniter.engine.isActive
import org.mlm.miniter.engine.toImageBitmap
import org.mlm.miniter.platform.getHardwareDecoderStatus
import org.mlm.miniter.platform.getSupportedHwCodecs
import org.mlm.miniter.platform.isHardwareAccelerationAvailable
import org.mlm.miniter.platform.isProjectExportSupported
import org.mlm.miniter.platform.openSaveFileDialog
import org.mlm.miniter.platform.platformPath
import org.mlm.miniter.platform.requiresExplicitExportPathSelection
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.ui.components.dialogs.ConfirmDialog
import org.mlm.miniter.ui.screens.export.ExportDraft
import org.mlm.miniter.ui.screens.export.ExportSectionCard
import org.mlm.miniter.ui.screens.export.MutedLabel
import org.mlm.miniter.ui.screens.export.SliderHeader
import org.mlm.miniter.ui.screens.export.applyTo
import org.mlm.miniter.ui.screens.export.capabilities
import org.mlm.miniter.ui.screens.export.effectiveResolutionText
import org.mlm.miniter.ui.screens.export.fileExtension
import org.mlm.miniter.ui.screens.export.label
import org.mlm.miniter.ui.screens.export.resolutionToTexts
import org.mlm.miniter.ui.util.popBack
import org.mlm.miniter.viewmodel.ProjectViewModel

private data class HwCapabilityInfo(
    val available: Boolean,
    val statusText: String,
    val codecs: List<String>,
)

@Composable
fun ExportScreen(backStack: NavBackStack<NavKey>) {
    val vm: ProjectViewModel = koinInject()
    val uiState by vm.state.collectAsState()
    val snapshot = uiState.snapshot
    val progress by vm.exportProgress.collectAsState()

    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(settingsRepository.schema.default)

    val profile = snapshot?.exportProfile
    val profileFormat = (profile?.format ?: settings.defaultExportFormat)
        .takeIf { it != RustExportFormat.Mov }
        ?: RustExportFormat.Av1Mp4

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

    val draftKey = remember(snapshot?.id) { Any() }
    var format by remember(draftKey) { mutableStateOf(profileFormat) }
    var draft by remember(draftKey) {
        val (w, h) = if (profile != null) {
            resolutionToTexts(profile.resolution, sourceWidth, sourceHeight)
        } else {
            "" to ""
        }
        mutableStateOf(
            ExportDraft(
                widthText = w,
                heightText = h,
                fpsText = profile?.fps?.toString() ?: "30",
                videoBitrateKbpsText = (profile?.videoBitrateKbps ?: 8000).toString(),
                audioBitrateText = (profile?.audioBitrateKbps ?: 192).toString(),
                audioSampleRate = profile?.audioSampleRate ?: 48_000,
            ),
        )
    }
    var subtitleMode by remember(draftKey) {
        mutableStateOf(profile?.subtitleMode ?: RustSubtitleMode.Soft)
    }
    var encodeEffort by remember(draftKey) {
        mutableStateOf((profile?.encodeEffort ?: 6).toFloat())
    }
    var lastSyncedResolution by remember(draftKey) { mutableStateOf(profile?.resolution) }
    var lastSyncedFps by remember(draftKey) { mutableStateOf(profile?.fps) }

    LaunchedEffect(profile?.resolution, profile?.fps) {
        val res = profile?.resolution ?: return@LaunchedEffect
        val fps = profile?.fps ?: return@LaunchedEffect
        if (res == lastSyncedResolution && fps == lastSyncedFps) return@LaunchedEffect
        if (lastSyncedResolution != null && lastSyncedFps != null) {
            val (w, h) = resolutionToTexts(res, sourceWidth, sourceHeight)
            draft = draft.copy(widthText = w, heightText = h, fpsText = fps.toString())
        }
        lastSyncedResolution = res
        lastSyncedFps = fps
    }

    var outputFile by remember { mutableStateOf<PlatformFile?>(null) }
    var pickerEpoch by remember { mutableStateOf(0) }
    var showCancelConfirm by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showErrorDetails by remember { mutableStateOf(false) }
    var hwEnabled by remember { mutableStateOf(settings.hardwareAccelerationEnabled) }
    var validationErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(settings.hardwareAccelerationEnabled) {
        hwEnabled = settings.hardwareAccelerationEnabled
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var hwFallbackConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(progress.hardwareFallback) {
        if (progress.hardwareFallback && !hwFallbackConsumed) {
            hwFallbackConsumed = true
            snackbarHostState.showSnackbar("Hardware encoder wasn't available, fell back to software.")
        }
        if (!progress.hardwareFallback) hwFallbackConsumed = false
    }

    val isExporting = progress.isActive()
    val exportSupported = isProjectExportSupported
    val needsOutputPicker = requiresExplicitExportPathSelection
    val platformFormats = RustExportFormat.entries.filter { it != RustExportFormat.Mov }
    val capabilities = format.capabilities
    val isAudioOnly = format == RustExportFormat.Opus || format == RustExportFormat.Flac

    var hwInfo by remember {
        mutableStateOf(HwCapabilityInfo(false, "Checking device capabilities…", emptyList()))
    }
    LaunchedEffect(Unit) {
        hwInfo = withContext(Dispatchers.Default) {
            val available = isHardwareAccelerationAvailable()
            HwCapabilityInfo(
                available = available,
                statusText = if (available) {
                    getHardwareDecoderStatus()
                } else {
                    "Not available on this device"
                },
                codecs = getSupportedHwCodecs(),
            )
        }
    }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val isExportingUpdated by rememberUpdatedState(isExporting)
    DisposableEffect(Unit) {
        onDispose { if (!isExportingUpdated) vm.resetExport() }
    }

    LaunchedEffect(draft.widthText, draft.heightText, draft.fpsText, isExporting) {
        if (isExporting || isAudioOnly) return@LaunchedEffect
        val pendingWidth = draft.widthText
        val pendingHeight = draft.heightText
        val pendingFps = draft.fpsText
        val w = pendingWidth.trim().toIntOrNull() ?: 0
        val h = pendingHeight.trim().toIntOrNull() ?: 0
        val f = pendingFps.trim().toDoubleOrNull()
        if (w <= 0 || h <= 0 || f == null || f !in 1.0..240.0) return@LaunchedEffect
        delay(500)
        if (draft.widthText != pendingWidth || draft.heightText != pendingHeight || draft.fpsText != pendingFps) {
            return@LaunchedEffect
        }
        val currentProfile = profile ?: return@LaunchedEffect
        val applied = draft.applyTo(currentProfile, sourceWidth, sourceHeight) ?: return@LaunchedEffect
        val withFps = applied.copy(fps = f)
        if (withFps.resolution != currentProfile.resolution || withFps.fps != currentProfile.fps) {
            lastSyncedResolution = withFps.resolution
            lastSyncedFps = withFps.fps
            vm.updateExportProfile(withFps)
        }
    }

    LaunchedEffect(format) {
        val required = format.fileExtension
        val current = outputFile?.platformPath()
        if (current != null && !current.substringAfterLast('.', "").equals(required, ignoreCase = true)) {
            outputFile = null
        }
    }

    var validationJson by remember(snapshot, draft.widthText, draft.heightText, format) {
        mutableStateOf<String?>(null)
    }
    LaunchedEffect(snapshot, draft.widthText, draft.heightText, format) {
        validationJson = if (isAudioOnly) {
            null
        } else {
            try {
                val w = draft.widthText.trim().toIntOrNull()?.takeIf { it > 0 }
                    ?: sourceWidth.takeIf { it > 0 } ?: 1920
                val h = draft.heightText.trim().toIntOrNull()?.takeIf { it > 0 }
                    ?: sourceHeight.takeIf { it > 0 } ?: 1080
                vm.validateCurrentPlan(w, h)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun launchFileSaver(suggestedName: String, extension: String) {
        val epoch = ++pickerEpoch
        val projectId = snapshot?.id
        scope.launch {
            val picked = openSaveFileDialog(
                suggestedName = suggestedName,
                extension = extension,
            )
            if (pickerEpoch != epoch) return@launch
            if (projectId != snapshot?.id) return@launch
            if (format.fileExtension != extension) return@launch
            if (picked != null) outputFile = picked
        }
    }

    fun startExport() {
        if (isExporting) return
        val currentSnapshot = snapshot ?: return
        val currentProfile = profile ?: return

        val validation = draft.validate(audioOnly = isAudioOnly)
        validationErrors = validation.errors
        val parsed = validation.parsed
        if (parsed == null) {
            scope.launch { snackbarHostState.showSnackbar("Fix the highlighted export settings first.") }
            return
        }

        val outputPath = if (needsOutputPicker) {
            outputFile?.platformPath()
                ?: run {
                    launchFileSaver(currentSnapshot.meta.name, format.fileExtension)
                    return
                }
        } else {
            "${currentSnapshot.meta.name}.${format.fileExtension}"
        }

        val applied = vm.updateExportProfile(
            draft.applyTo(currentProfile, sourceWidth, sourceHeight, audioOnly = isAudioOnly)!!.copy(
                format = format,
                audioSampleRate = draft.audioSampleRate,
                outputPath = "",
                subtitleMode = if (capabilities.supportsEmbeddedSubtitles) {
                    subtitleMode
                } else {
                    RustSubtitleMode.Hard
                },
                hardwareAcceleration = hwEnabled && hwInfo.available && !isAudioOnly,
                encodeEffort = encodeEffort.toInt().coerceIn(0, 10),
            ),
        )
        if (applied) {
            validationErrors = emptyMap()
            vm.startExport(outputPath)
        }
    }

    fun requestBack() {
        backStack.popBack()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Export", fontWeight = FontWeight.SemiBold)
                        snapshot?.meta?.name?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    ExportBottomBar(
                            progress = progress,
                            isExporting = isExporting,
                            exportSupported = exportSupported,
                            canStart = snapshot != null && profile != null && exportSupported &&
                                (!needsOutputPicker || outputFile != null),
                            needsOutputPicker = needsOutputPicker,
                            outputLabel = progress.outputPath
                                ?: outputFile?.platformPath()
                                ?: if (needsOutputPicker) "Choose a save location to continue"
                                else "${snapshot?.meta?.name ?: "export"}.${format.fileExtension}",
                            onStartExport = ::startExport,
                            onChooseLocation = {
                                launchFileSaver(snapshot?.meta?.name ?: "export", format.fileExtension)
                            },
                            onCancelExport = { showCancelConfirm = true },
                            onResetExport = {
                                vm.resetExport()
                                outputFile = null
                                showErrorDetails = false
                            },
                            onBackToEditor = { backStack.popBack() },
                        )
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val contentPadding = if (maxWidth < 600.dp) 16.dp else 24.dp

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 980.dp)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ExportSectionCard(
                    title = "Format",
                    icon = Icons.Default.Movie,
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        platformFormats.forEach { fmt ->
                            FilterChip(
                                selected = format == fmt,
                                onClick = { format = fmt },
                                enabled = !isExporting,
                                label = { Text(fmt.label) },
                            )
                        }
                    }
                }

                if (isAudioOnly) {
                    ExportSectionCard(
                        title = "Audio-only export",
                        icon = Icons.Default.Audiotrack,
                    ) {
                        AudioFields(
                            audioBitrate = draft.audioBitrateText,
                            audioSampleRate = draft.audioSampleRate,
                            enabled = !isExporting,
                            errors = validationErrors,
                            onAudioBitrateChange = { draft = draft.copy(audioBitrateText = it) },
                            onAudioSampleRateChange = { draft = draft.copy(audioSampleRate = it) },
                        )
                    }
                } else {
                    ExportSectionCard(
                        title = "Video",
                        icon = Icons.Default.Videocam,
                    ) {
                        SliderHeader(
                            title = "Video bitrate",
                            value = "${draft.videoBitrateKbpsText} kbps",
                        )
                        BitrateSlider(
                            bitrateText = draft.videoBitrateKbpsText,
                            enabled = !isExporting,
                            onChange = { draft = draft.copy(videoBitrateKbpsText = it.toString()) },
                        )
                        validationErrors["videoBitrate"]?.let { FieldError(it) }

                        HorizontalDivider()

                        Text(
                            "Resolution",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ResolutionChips(
                            widthText = draft.widthText,
                            heightText = draft.heightText,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight,
                            hasSourceDimensions = hasSourceDimensions,
                            enabled = !isExporting,
                            onSize = { w, h -> draft = draft.copy(widthText = w, heightText = h) },
                        )
                        AspectRatioChips(
                            width = draft.widthText,
                            height = draft.heightText,
                            enabled = !isExporting,
                            onSize = { w, h -> draft = draft.copy(widthText = w, heightText = h) },
                        )
                        ResolutionInputs(
                            width = draft.widthText,
                            height = draft.heightText,
                            fps = draft.fpsText,
                            sourceHint = if (hasSourceDimensions) {
                                "Empty = source ($sourceResolutionText)"
                            } else {
                                "Empty = source"
                            },
                            enabled = !isExporting,
                            errors = validationErrors,
                            onWidthChange = { draft = draft.copy(widthText = it) },
                            onHeightChange = { draft = draft.copy(heightText = it) },
                            onFpsChange = { draft = draft.copy(fpsText = it) },
                        )

                        val evenW = draft.widthText.trim().toIntOrNull()?.let { (it / 2) * 2 }
                        val evenH = draft.heightText.trim().toIntOrNull()?.let { (it / 2) * 2 }
                        if (evenW != null && evenH != null && evenW > 0 && evenH > 0 &&
                            (evenW.toString() != draft.widthText.trim() || evenH.toString() != draft.heightText.trim())
                        ) {
                            MutedLabel("Exports at ${evenW}×${evenH}.")
                        }
                    }
                }

                if (!exportSupported) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text("Export unavailable", fontWeight = FontWeight.SemiBold) },
                            supportingContent = {
                                Text("Export is not available on this platform. Use desktop or Android to render files.")
                            },
                            leadingContent = {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                } else if (needsOutputPicker) {
                    ExportSectionCard(
                        title = "Save location",
                        icon = Icons.Default.Save,
                    ) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (maxWidth < 560.dp) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    PathPreview(outputFile?.platformPath() ?: "No location selected")
                                    FilledTonalButton(
                                        onClick = {
                                            launchFileSaver(snapshot?.meta?.name ?: "export", format.fileExtension)
                                        },
                                        enabled = !isExporting,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Browse")
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PathPreview(
                                        text = outputFile?.platformPath() ?: "No location selected",
                                        modifier = Modifier.weight(1f),
                                    )
                                    FilledTonalButton(
                                        onClick = {
                                            launchFileSaver(snapshot?.meta?.name ?: "export", format.fileExtension)
                                        },
                                        enabled = !isExporting,
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Browse")
                                    }
                                }
                            }
                        }
                    }
                }

                FilledTonalButton(
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (showAdvanced) "Hide advanced" else "Advanced")
                }

                if (showAdvanced) {
                    if (!isAudioOnly && capabilities.supportsAudio) {
                        ExportSectionCard(
                            title = "Audio",
                            icon = Icons.Default.Audiotrack,
                        ) {
                            AudioFields(
                                audioBitrate = draft.audioBitrateText,
                                audioSampleRate = draft.audioSampleRate,
                                enabled = !isExporting,
                                errors = validationErrors,
                                onAudioBitrateChange = { draft = draft.copy(audioBitrateText = it) },
                                onAudioSampleRateChange = { draft = draft.copy(audioSampleRate = it) },
                            )
                        }
                    }

                    if (!isAudioOnly && (capabilities.supportsEmbeddedSubtitles || capabilities.supportsBurnedInSubtitles)) {
                        ExportSectionCard(
                            title = "Subtitles",
                            icon = Icons.Default.Subtitles,
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (capabilities.supportsEmbeddedSubtitles) {
                                    FilterChip(
                                        selected = subtitleMode == RustSubtitleMode.Soft,
                                        onClick = { subtitleMode = RustSubtitleMode.Soft },
                                        enabled = !isExporting,
                                        label = { Text("Embed soft") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp))
                                        },
                                    )
                                }
                                if (capabilities.supportsBurnedInSubtitles) {
                                    FilterChip(
                                        selected = subtitleMode == RustSubtitleMode.Hard ||
                                            !capabilities.supportsEmbeddedSubtitles,
                                        onClick = { subtitleMode = RustSubtitleMode.Hard },
                                        enabled = !isExporting && capabilities.supportsEmbeddedSubtitles,
                                        label = {
                                            Text(
                                                if (capabilities.supportsEmbeddedSubtitles) "Burn in"
                                                else "Burn in (only option)",
                                            )
                                        },
                                    )
                                }
                            }
                            if (capabilities.stylingWarning && subtitleMode == RustSubtitleMode.Soft) {
                                Text(
                                    "ASS/SSA styling is converted to plain text in MP4 soft subtitles.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    if (!isAudioOnly) {
                        ExportSectionCard(
                            title = "Encoder",
                            icon = Icons.Default.Speed,
                            trailing = {
                                Switch(
                                    checked = hwEnabled && hwInfo.available,
                                    onCheckedChange = { enabled ->
                                        hwEnabled = enabled
                                        scope.launch {
                                            settingsRepository.update {
                                                it.copy(hardwareAccelerationEnabled = enabled)
                                            }
                                        }
                                    },
                                    enabled = !isExporting && hwInfo.available,
                                )
                            },
                        ) {
                            Text(
                                hwInfo.statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hwInfo.available) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (hwInfo.codecs.isNotEmpty()) {
                                Text(
                                    hwInfo.codecs.joinToString(", "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SliderHeader(title = "Encode effort", value = encodeEffort.toInt().toString())
                            Slider(
                                value = encodeEffort,
                                onValueChange = { encodeEffort = it },
                                valueRange = 0f..10f,
                                steps = 9,
                                enabled = !isExporting,
                            )
                            MutedLabel("Hardware encoders may ignore this.")
                        }
                    }

                    val currentValidation = validationJson
                    if (
                        currentValidation != null &&
                        currentValidation.trim().isNotEmpty() &&
                        currentValidation.trim() != "[]"
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        "Render plan diagnostics (current frame)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                                SelectionContainer {
                                    Text(
                                        currentValidation,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }

                    snapshot?.let {
                        ExportSectionCard(title = "Project details", icon = Icons.Default.Info) {
                            val totalTracks = it.timeline.tracks.size
                            val totalClips = it.timeline.tracks.sumOf { track -> track.clips.size }
                            val durationSeconds = (
                                it.timeline.tracks
                                    .flatMap { track -> track.clips }
                                    .maxOfOrNull { clip -> clip.timelineStartUs + clip.timelineDurationUs } ?: 0L
                                ) / 1_000_000L
                            InfoLine("Tracks", totalTracks.toString())
                            InfoLine("Clips", totalClips.toString())
                            InfoLine("Duration", "${durationSeconds}s")
                            InfoLine("Source", sourceResolutionText)
                            InfoLine(
                                "Output",
                                effectiveResolutionText(
                                    draft.widthText, draft.heightText, sourceWidth, sourceHeight, isAudioOnly,
                                ),
                            )
                        }
                    }
                }

                if (progress.error != null) {
                    ExportErrorDetails(
                        error = progress.error!!,
                        expanded = showErrorDetails,
                        onToggle = { showErrorDetails = !showErrorDetails },
                    )
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }

    if (showCancelConfirm) {
        ConfirmDialog(
            title = "Cancel export?",
            message = "The export keeps running if you leave. Cancel it now?",
            confirmText = "Cancel Export",
            dismissText = "Keep Running",
            onConfirm = {
                vm.cancelExport()
                showCancelConfirm = false
            },
            onDismiss = { showCancelConfirm = false },
        )
    }
}

@Composable
private fun BitrateSlider(bitrateText: String, enabled: Boolean, onChange: (Int) -> Unit) {
    val bitrate = bitrateText.toIntOrNull() ?: 8000
    val sliderValue = when {
        bitrate <= 500 -> 0f
        bitrate >= 20_000 -> 1f
        else -> (bitrate - 500) / 19_500f
    }
    Slider(
        value = sliderValue,
        onValueChange = { v -> onChange((500 + v * 19_500).toInt()) },
        enabled = enabled,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MutedLabel("500 kbps")
        MutedLabel("20+ Mbps")
    }
}

@Composable
private fun ResolutionChips(
    widthText: String,
    heightText: String,
    sourceWidth: Int,
    sourceHeight: Int,
    hasSourceDimensions: Boolean,
    enabled: Boolean,
    onSize: (String, String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasSourceDimensions) {
            FilterChip(
                selected = widthText == sourceWidth.toString() && heightText == sourceHeight.toString(),
                onClick = { onSize(sourceWidth.toString(), sourceHeight.toString()) },
                enabled = enabled,
                label = { Text("Source") },
            )
        }
        ResolutionChip("720p", "1280", "720", widthText, heightText, enabled, onSize)
        ResolutionChip("1080p", "1920", "1080", widthText, heightText, enabled, onSize)
        ResolutionChip("4K", "3840", "2160", widthText, heightText, enabled, onSize)
    }
}

@Composable
private fun ResolutionChip(
    label: String,
    width: String,
    height: String,
    selectedWidth: String,
    selectedHeight: String,
    enabled: Boolean,
    onSize: (String, String) -> Unit,
) {
    FilterChip(
        selected = selectedWidth == width && selectedHeight == height,
        onClick = { onSize(width, height) },
        enabled = enabled,
        label = { Text(label) },
    )
}

@Composable
private fun AspectRatioChips(
    width: String,
    height: String,
    enabled: Boolean,
    onSize: (String, String) -> Unit,
) {
    val aspectW = width.toIntOrNull() ?: 0
    val aspectH = height.toIntOrNull() ?: 0

    fun matchesRatio(aw: Int, ah: Int): Boolean {
        if (aspectW <= 0 || aspectH <= 0) return false
        return aspectW.toLong() * ah == aspectH.toLong() * aw
    }

    fun applyAspect(aw: Int, ah: Int, fallbackW: String, fallbackH: String) {
        val baseW = width.toIntOrNull()?.takeIf { it > 0 }
        if (baseW == null) {
            onSize(fallbackW, fallbackH)
        } else {
            val h = (baseW.toLong() * ah / aw).toInt().let { (it / 2) * 2 }
            onSize(baseW.toString(), h.toString())
        }
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = matchesRatio(16, 9),
            onClick = { applyAspect(16, 9, "1920", "1080") },
            enabled = enabled,
            label = { Text("16:9") },
        )
        FilterChip(
            selected = matchesRatio(9, 16),
            onClick = { applyAspect(9, 16, "1080", "1920") },
            enabled = enabled,
            label = { Text("9:16") },
        )
        FilterChip(
            selected = matchesRatio(1, 1),
            onClick = { applyAspect(1, 1, "1080", "1080") },
            enabled = enabled,
            label = { Text("1:1") },
        )
    }
}

@Composable
private fun ResolutionInputs(
    width: String,
    height: String,
    fps: String,
    sourceHint: String,
    enabled: Boolean,
    errors: Map<String, String>,
    onWidthChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onFpsChange: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val numberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
        val decimalKeyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        if (maxWidth < 560.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { onWidthChange(it.filter { c -> c.isDigit() }) },
                        label = { Text("Width") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        keyboardOptions = numberKeyboard,
                        supportingText = { Text(sourceHint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        isError = errors["resolution"] != null,
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { onHeightChange(it.filter { c -> c.isDigit() }) },
                        label = { Text("Height") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        keyboardOptions = numberKeyboard,
                        supportingText = { Text(sourceHint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        isError = errors["resolution"] != null,
                    )
                }
                OutlinedTextField(
                    value = fps,
                    onValueChange = { onFpsChange(it.filter { c -> c.isDigit() || c == '.' }) },
                    label = { Text("FPS") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    keyboardOptions = decimalKeyboard,
                    supportingText = { Text("1–240") },
                    isError = errors["fps"] != null,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                OutlinedTextField(
                    value = width,
                    onValueChange = { onWidthChange(it.filter { c -> c.isDigit() }) },
                    label = { Text("Width") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    keyboardOptions = numberKeyboard,
                    supportingText = { Text(sourceHint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    isError = errors["resolution"] != null,
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { onHeightChange(it.filter { c -> c.isDigit() }) },
                    label = { Text("Height") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    keyboardOptions = numberKeyboard,
                    supportingText = { Text(sourceHint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    isError = errors["resolution"] != null,
                )
                OutlinedTextField(
                    value = fps,
                    onValueChange = { onFpsChange(it.filter { c -> c.isDigit() || c == '.' }) },
                    label = { Text("FPS") },
                    singleLine = true,
                    modifier = Modifier.width(132.dp),
                    enabled = enabled,
                    keyboardOptions = decimalKeyboard,
                    supportingText = { Text("1–240") },
                    isError = errors["fps"] != null,
                )
            }
        }
    }
    errors["resolution"]?.let { FieldError(it) }
    errors["fps"]?.let { FieldError(it) }
}

@Composable
private fun AudioFields(
    audioBitrate: String,
    audioSampleRate: Int,
    enabled: Boolean,
    errors: Map<String, String>,
    onAudioBitrateChange: (String) -> Unit,
    onAudioSampleRateChange: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 560.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = audioBitrate,
                    onValueChange = { onAudioBitrateChange(it.filter { c -> c.isDigit() }) },
                    label = { Text("Bitrate (kbps)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("32–510") },
                    isError = errors["audioBitrate"] != null,
                )
                SampleRateMenu(audioSampleRate, onAudioSampleRateChange, enabled, Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                OutlinedTextField(
                    value = audioBitrate,
                    onValueChange = { onAudioBitrateChange(it.filter { c -> c.isDigit() }) },
                    label = { Text("Bitrate (kbps)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("32–510") },
                    isError = errors["audioBitrate"] != null,
                )
                SampleRateMenu(audioSampleRate, onAudioSampleRateChange, enabled, Modifier.weight(1f))
            }
        }
    }
    errors["audioBitrate"]?.let { FieldError(it) }
}

@Composable
private fun SampleRateMenu(
    audioSampleRate: Int,
    onAudioSampleRateChange: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    val sampleRateOptions = listOf(8_000, 12_000, 16_000, 24_000, 48_000)
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = "${audioSampleRate / 1000} kHz",
            onValueChange = {},
            readOnly = true,
            label = { Text("Sample rate") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            enabled = enabled,
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            sampleRateOptions.forEach { rate ->
                DropdownMenuItem(
                    text = { Text("${rate / 1000} kHz") },
                    onClick = {
                        onAudioSampleRateChange(rate)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun FieldError(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun PathPreview(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Save,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ExportErrorDetails(error: String, expanded: Boolean, onToggle: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    "Export failed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onToggle) {
                    Text(if (expanded) "Hide details" else "Details")
                }
            }
            Text(
                error.lines().firstOrNull()?.takeIf { it.isNotBlank() } ?: "Unknown error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                SelectionContainer {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportBottomBar(
    progress: ExportProgress,
    isExporting: Boolean,
    exportSupported: Boolean,
    canStart: Boolean,
    needsOutputPicker: Boolean,
    outputLabel: String,
    onStartExport: () -> Unit,
    onChooseLocation: () -> Unit,
    onCancelExport: () -> Unit,
    onResetExport: () -> Unit,
    onBackToEditor: () -> Unit,
) {
    AnimatedContent(
        targetState = Triple(progress.isComplete, progress.error, isExporting),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "export_bottom_bar",
        modifier = Modifier.fillMaxWidth(),
    ) { (complete, error, exporting) ->
        when {
            complete -> ExportCompleteFooter(
                outputLabel = outputLabel,
                onResetExport = onResetExport,
                onBackToEditor = onBackToEditor,
            )

            error != null -> ExportErrorFooter(
                error = error,
                onResetExport = onResetExport,
            )

            exporting -> ExportProgressFooter(
                progress = progress,
                onCancelExport = onCancelExport,
            )

            else -> ExportIdleFooter(
                exportSupported = exportSupported,
                canStart = canStart,
                needsOutputPicker = needsOutputPicker,
                outputLabel = outputLabel,
                onStartExport = onStartExport,
                onChooseLocation = onChooseLocation,
            )
        }
    }
}

@Composable
private fun ExportIdleFooter(
    exportSupported: Boolean,
    canStart: Boolean,
    needsOutputPicker: Boolean,
    outputLabel: String,
    onStartExport: () -> Unit,
    onChooseLocation: () -> Unit,
) {
    val action: () -> Unit = if (needsOutputPicker && !canStart) onChooseLocation else onStartExport
    val actionLabel = when {
        !exportSupported -> "Export unavailable"
        needsOutputPicker && !canStart -> "Choose save location…"
        else -> "Start Export"
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 560.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FooterTextBlock(
                    title = if (exportSupported) "Ready to export" else "Export unavailable",
                    subtitle = outputLabel,
                )
                Button(
                    onClick = action,
                    enabled = exportSupported && (canStart || (needsOutputPicker && !canStart)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(
                        if (needsOutputPicker && !canStart) Icons.Default.FolderOpen else Icons.Default.FileDownload,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(actionLabel)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FooterTextBlock(
                    title = if (exportSupported) "Ready to export" else "Export unavailable",
                    subtitle = outputLabel,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = action,
                    enabled = exportSupported && (canStart || (needsOutputPicker && !canStart)),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        if (needsOutputPicker && !canStart) Icons.Default.FolderOpen else Icons.Default.FileDownload,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun ExportProgressFooter(progress: ExportProgress, onCancelExport: () -> Unit) {
    val safeProgress = progress.progress.coerceIn(0f, 1f).let {
        if (it.isNaN()) 0f else it
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 560.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        progress.phase.ifBlank { "Exporting…" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(safeProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { safeProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                OutlinedButton(
                    onClick = onCancelExport,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cancel")
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                progress.previewFrame?.let { frame ->
                    val bitmap = remember(frame) { frame.toImageBitmap() }
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Export preview",
                        modifier = Modifier
                            .size(width = 84.dp, height = 48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(Color.Black),
                        contentScale = ContentScale.Fit,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            progress.phase.ifBlank { "Exporting…" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${(safeProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { safeProgress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                }
                OutlinedButton(
                    onClick = onCancelExport,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun ExportCompleteFooter(
    outputLabel: String,
    onResetExport: () -> Unit,
    onBackToEditor: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 560.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FooterIconText(
                    icon = Icons.Default.CheckCircle,
                    title = "Export complete",
                    subtitle = outputLabel,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onResetExport, modifier = Modifier.weight(1f)) {
                        Text("Again")
                    }
                    Button(onClick = onBackToEditor, modifier = Modifier.weight(1f)) {
                        Text("Editor")
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FooterIconText(
                    icon = Icons.Default.CheckCircle,
                    title = "Export complete",
                    subtitle = outputLabel,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onResetExport) { Text("Export Again") }
                Button(onClick = onBackToEditor) { Text("Back to Editor") }
            }
        }
    }
}

@Composable
private fun ExportErrorFooter(error: String, onResetExport: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            error.lines().firstOrNull()?.takeIf { it.isNotBlank() } ?: "Export failed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onResetExport) {
            Text("Try Again")
        }
    }
}

@Composable
private fun FooterTextBlock(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FooterIconText(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        FooterTextBlock(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
    }
}
