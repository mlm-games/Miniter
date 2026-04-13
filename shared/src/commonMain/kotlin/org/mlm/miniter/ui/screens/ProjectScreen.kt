package org.mlm.miniter.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.mlm.miniter.editor.model.RustTrackKind
import org.mlm.miniter.nav.Route
import org.mlm.miniter.platform.SupportedFormats
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.ui.components.dialogs.ConfirmDialog
import org.mlm.miniter.ui.components.preview.EditorVideoPreview
import org.mlm.miniter.ui.components.properties.PropertiesBottomSheet
import org.mlm.miniter.ui.components.timeline.TimelinePanel
import org.mlm.miniter.ui.components.toolbar.ActionBar
import org.mlm.miniter.ui.components.toolbar.CompactTopBar
import org.mlm.miniter.ui.components.toolbar.PlaybackControls
import org.mlm.miniter.ui.util.popBack
import org.mlm.miniter.viewmodel.ProjectViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    videoPath: String,
    videoName: String,
    backStack: NavBackStack<NavKey>,
    savePath: String? = null,
    openAsProject: Boolean = false,
) {
    val vm: ProjectViewModel = koinInject()
    val uiState by vm.state.collectAsState()
    val snapshot = uiState.snapshot
    val playheadMs = uiState.playheadMs
    val isPlaying = uiState.isPlaying
    val selectedClipId = uiState.selectedClipId
    val zoomLevel = uiState.zoomLevel
    val snapIndicatorMs = uiState.snapIndicatorMs
    val canUndo = uiState.canUndo
    val canRedo = uiState.canRedo
    val isDirty = uiState.isDirty

    var showExitConfirm by remember { mutableStateOf(false) }
    var showPropertiesSheet by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()

    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(settingsRepository.schema.default)

    val importPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = (SupportedFormats.videoExtensions + SupportedFormats.audioExtensions).toSet()),
        mode = FileKitMode.Multiple(),
    ) { files ->
        if (files != null) vm.importMediaFiles(files)
    }

    val subtitlePicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = SupportedFormats.subtitleExtensions.toSet()),
        mode = FileKitMode.Multiple(),
    ) { files ->
        if (files != null) vm.importSubtitleFiles(files)
    }

    LaunchedEffect(videoPath) {
        if (snapshot == null) {
            vm.initProject(videoPath, videoName, savePath, openAsProject)
        }
    }

    LaunchedEffect(settings.autoSaveProject, settings.autoSaveIntervalSeconds) {
        vm.startAutoSave(settings.autoSaveProject, settings.autoSaveIntervalSeconds)
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.stopAutoSave()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        CompactTopBar(
            projectName = snapshot?.meta?.name ?: videoName,
            isDirty = isDirty,
            canUndo = canUndo,
            canRedo = canRedo,
            onBack = {
                if (isDirty) showExitConfirm = true
                else backStack.popBack()
            },
            onSave = { vm.saveProject() },
            onUndo = { vm.undo() },
            onRedo = { vm.redo() },
            onExport = { backStack.add(Route.Export(savePath ?: "")) },
            onImport = { importPicker.launch() },
        )

        EditorVideoPreview(
            snapshot = snapshot,
            playheadMs = playheadMs,
            isPlaying = isPlaying,
            onPlayingChange = { vm.setPlaying(it) },
            onPlayheadChange = { vm.seekTo(it) },
            thumbnailFallback = uiState.thumbnails.firstOrNull(),
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.40f),
        )

        PlaybackControls(
            playheadMs = playheadMs,
            durationMs = snapshotTimelineDurationMs(snapshot),
            isPlaying = isPlaying,
            onTogglePlay = { vm.togglePlayPause() },
            onSeek = { vm.seekTo(it) },
            onSeekToStart = { vm.seekToStart() },
            onSeekToEnd = { vm.seekToEnd() },
            onSeekRelative = { vm.seekRelative(it) },
        )

        ActionBar(
            hasSelection = selectedClipId != null,
            zoomLevel = zoomLevel,
            onZoomIn = { vm.zoomIn() },
            onZoomOut = { vm.zoomOut() },
            onAddText = { vm.addTextOverlay() },
            onAddSubtitle = { subtitlePicker.launch() },
            onSplit = { selectedClipId?.let { vm.splitClipAtPlayhead(it) } },
            onDuplicate = { selectedClipId?.let { vm.duplicateClip(it) } },
            onDelete = { vm.deleteSelectedClip() },
            onDeselect = { vm.selectClip(null) },
            onProperties = { showPropertiesSheet = true },
        )

        HorizontalDivider(thickness = 0.5.dp)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            TimelinePanel(
                snapshot = snapshot,
                playheadMs = playheadMs,
                zoomLevel = zoomLevel,
                isPlaying = isPlaying,
                selectedClipId = selectedClipId,
                snapIndicatorMs = snapIndicatorMs,
                onPlayheadChange = vm::seekTo,
                onClipSelected = vm::selectClip,
                onBeginEdit = vm::beginEdit,
                onClipMoveAbsolute = vm::moveClipAbsolute,
                onClipMoveToTrack = vm::moveClipToTrack,
                onCommitEdit = vm::commitEdit,
                onCancelEdit = vm::cancelEdit,
                onClipTrimStartAbsolute = vm::trimClipStartAbsolute,
                onClipTrimEndAbsolute = vm::trimClipEndAbsolute,
                onToggleMute = vm::toggleTrackMute,
                onToggleLock = vm::toggleTrackLock,
                onAddTrack = vm::addTrack,
                onRemoveTrack = vm::removeTrack,
                onSnapPosition = vm::snapPosition,
                onClearSnap = vm::clearSnapIndicator,
                onSplitClip = vm::splitClipAtPlayhead,
                onDuplicateClip = vm::duplicateClip,
                onDeleteClip = vm::removeClip,
            )
        }
    }

    if (showPropertiesSheet && selectedClipId != null) {
        PropertiesBottomSheet(
            sheetState = sheetState,
            snapshot = snapshot,
            selectedClipId = selectedClipId,
            onDismiss = {
                scope.launch { sheetState.hide() }
                showPropertiesSheet = false
            },
            onAddFilter = vm::addFilter,
            onRemoveFilter = vm::removeFilter,
            onUpdateFilterParams = vm::updateFilterParams,
            onSetSpeed = vm::setClipSpeed,
            onSetVolume = vm::setClipVolume,
            onSetTransition = vm::setTransition,
            onUpdateText = vm::updateTextClip,
            onUpdateTextStyle = { clipId, fontSize, color, bgColor, posX, posY, bold, italic ->
                vm.updateTextClipStyle(clipId, fontSize, color, bgColor, posX, posY, bold, italic)
            },
            onSetOpacity = vm::setClipOpacity,
            onSetTextDuration = vm::setTextClipDuration,
            onSetTextTransitionIn = vm::setTextTransitionIn,
            onSetTextTransitionOut = vm::setTextTransitionOut,
        )
    }

    if (showExitConfirm) {
        ConfirmDialog(
            title = "Unsaved Changes",
            message = "Save before leaving?",
            confirmText = "Save & Exit",
            dismissText = "Discard",
            onConfirm = {
                vm.saveProject()
                showExitConfirm = false
                backStack.popBack()
            },
            onDismiss = {
                showExitConfirm = false
                backStack.popBack()
            },
        )
    }
}

private fun snapshotTimelineDurationMs(snapshot: org.mlm.miniter.editor.model.RustProjectSnapshot?): Long {
    if (snapshot == null) return 0L
    val maxEndUs = snapshot.timeline.tracks
        .flatMap { it.clips }
        .maxOfOrNull { it.timelineStartUs + it.timelineDurationUs }
        ?: 0L
    return maxEndUs / 1000L
}
