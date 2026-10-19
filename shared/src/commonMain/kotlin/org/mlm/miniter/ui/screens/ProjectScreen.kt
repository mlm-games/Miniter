package org.mlm.miniter.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlin.math.roundToLong
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.mlm.miniter.editor.model.RustExportResolution
import org.mlm.miniter.editor.model.RustKeyframe
import org.mlm.miniter.editor.model.RustProjectSnapshot
import org.mlm.miniter.editor.model.RustTrackKind
import org.mlm.miniter.editor.model.RustTextStyleUpdate
import org.mlm.miniter.editor.model.RustTransformFilterSnapshot
import org.mlm.miniter.editor.model.RustVideoClipKind
import org.mlm.miniter.project.KeyframeParams
import org.mlm.miniter.project.defaultOf
import org.mlm.miniter.nav.Route
import org.mlm.miniter.platform.SupportedFormats
import org.mlm.miniter.rust.UndoHistoryEntry
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.ui.components.dialogs.ConfirmDialog
import org.mlm.miniter.ui.components.dialogs.SaveProjectDialog
import org.mlm.miniter.ui.components.preview.EditorVideoPreview
import org.mlm.miniter.ui.components.properties.PropertiesBottomSheet
import org.mlm.miniter.ui.components.timeline.ClipSelectionMode
import org.mlm.miniter.ui.components.timeline.MarkersPanel
import org.mlm.miniter.ui.components.timeline.TimelinePanel
import org.mlm.miniter.ui.components.toolbar.ActionBar
import org.mlm.miniter.ui.components.toolbar.CompactTopBar
import org.mlm.miniter.ui.components.toolbar.ImportProgressBanner
import org.mlm.miniter.ui.components.toolbar.PlaybackControls
import org.mlm.miniter.ui.util.popBack
import org.mlm.miniter.viewmodel.ImportProgress
import org.mlm.miniter.viewmodel.ProjectViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    videoPath: String,
    videoName: String,
    backStack: NavBackStack<NavKey>,
    savePath: String? = null,
    openAsProject: Boolean = false,
    resolution: RustExportResolution? = null,
    fps: Int? = null,
    extraImportPaths: List<String> = emptyList(),
    restoreRecovery: Boolean = false,
) {
    val vm: ProjectViewModel = koinInject()
    val uiState by vm.state.collectAsState()
    val snapshot = uiState.snapshot
    val playheadMs = uiState.playheadMs
    val isPlaying = uiState.isPlaying
    val selectedClipId = uiState.selectedClipId
    val selectedClipIds = uiState.selectedClipIds
    val zoomLevel = uiState.zoomLevel
    val snapIndicatorMs = uiState.snapIndicatorMs
    val canUndo = uiState.canUndo
    val canRedo = uiState.canRedo
    val isDirty = uiState.isDirty
    val hasClipboard by vm.hasClipboard.collectAsState()
    val autoReframeRunning = uiState.autoReframeRunning
    val stabilizeRunning = uiState.stabilizeRunning
    val maskTrackRunning = uiState.maskTrackRunning
    val removeSilenceRunning = uiState.removeSilenceRunning
    val sceneDetectRunning = uiState.sceneDetectRunning

    var showExitConfirm by remember { mutableStateOf(false) }
    var showSaveAs by remember { mutableStateOf(false) }
    var showPropertiesSheet by remember { mutableStateOf(false) }
    var autoKeyframeEnabled by remember { mutableStateOf(false) }
    var markersPanelOpen by remember { mutableStateOf(false) }
    var markerLabelFocused by remember { mutableStateOf(false) }
    var historyPanelOpen by remember { mutableStateOf(false) }
    var historyEntries by remember { mutableStateOf<List<UndoHistoryEntry>>(emptyList()) }
    var historyRevision by remember { mutableStateOf(0) }

    LaunchedEffect(
        historyPanelOpen,
        historyRevision,
        uiState.canUndo,
        uiState.canRedo,
        uiState.undoLabel,
        uiState.redoLabel,
    ) {
        if (historyPanelOpen) historyEntries = vm.undoHistoryEntries()
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()

    val editorFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        editorFocusRequester.requestFocus()
    }

    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(settingsRepository.schema.default)

    val importPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = (SupportedFormats.videoExtensions + SupportedFormats.audioExtensions + SupportedFormats.imageExtensions).toSet()),
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

    var lastInitKey by remember { mutableStateOf<String?>(null) }
    val initKey = listOf(videoPath, savePath, openAsProject, resolution, fps, extraImportPaths, restoreRecovery).toString()
    LaunchedEffect(initKey) {
        historyPanelOpen = false
        if (snapshot == null || lastInitKey != initKey) {
            lastInitKey = initKey
            vm.initProject(videoPath, videoName, savePath, openAsProject, resolution, fps, extraImportPaths, restoreRecovery)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(editorFocusRequester)
            .onFocusChanged { if (!it.hasFocus) editorFocusRequester.requestFocus() }
            .focusable()
            .onKeyEvent { if (markerLabelFocused) false else vm.handleEditorShortcut(it, snapshot) },
    ) {

        CompactTopBar(
            projectName = snapshot?.meta?.name ?: videoName,
            isDirty = isDirty,
            canUndo = canUndo,
            canRedo = canRedo,
            historyEntries = historyEntries,
            historyOpen = historyPanelOpen,
            onToggleHistory = {
                if (!historyPanelOpen) {
                    historyEntries = vm.undoHistoryEntries()
                    historyRevision++
                }
                historyPanelOpen = !historyPanelOpen
            },
            onDismissHistory = { historyPanelOpen = false },
            onJumpToHistory = { index ->
                vm.jumpToHistory(index)
                historyRevision++
            },
            onBack = {
                if (isDirty) showExitConfirm = true
                else backStack.popBack()
            },
            onSave = { if (uiState.projectPath == null) showSaveAs = true else vm.saveProject() },
            onUndo = { vm.undo() },
            onRedo = { vm.redo() },
            onExport = { backStack.add(Route.Export(savePath ?: "")) },
            onImport = { importPicker.launch() },
            onImportSubtitles = { subtitlePicker.launch() },
        )

        when {
            uiState.importProgress != null -> {
                ImportProgressBanner(uiState.importProgress!!)
            }
            uiState.isLoading -> {
                ImportProgressBanner(
                    ImportProgress(
                        current = 0,
                        total = 0,
                        currentFile = null,
                        stage = "Loading…",
                        fraction = null,
                    )
                )
            }
        }
        val selectedClip = snapshot?.timeline?.tracks?.flatMap { it.clips }?.find { it.id == selectedClipId }
        val videoKind = selectedClip?.kind as? org.mlm.miniter.editor.model.RustVideoClipKind
        val transformFilterIndex = videoKind?.filters?.indexOfFirst { it.filter is org.mlm.miniter.editor.model.RustTransformFilterSnapshot } ?: -1
        val transformFilter = if (transformFilterIndex != -1) videoKind?.filters?.get(transformFilterIndex)?.filter as? org.mlm.miniter.editor.model.RustTransformFilterSnapshot else null
        val effectiveTransformFilter = transformFilter ?: if (videoKind != null) {
            org.mlm.miniter.editor.model.RustTransformFilterSnapshot(
                scale = defaultOf(KeyframeParams.TRANSFORM_SCALE),
                translateX = defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X),
                translateY = defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y),
                rotate = defaultOf(KeyframeParams.TRANSFORM_ROTATE),
            )
        } else null

        EditorVideoPreview(
            snapshot = snapshot,
            playheadMs = playheadMs,
            isPlaying = isPlaying,
            onPlayingChange = { vm.setPlaying(it) },
            onPlayheadChange = { vm.seekTo(it) },
            thumbnailFallback = uiState.thumbnails.firstOrNull(),
            selectedClipId = selectedClipId,
            transformFilter = effectiveTransformFilter,
            onDragStart = {
                if (selectedClipId != null) vm.beginEdit()
            },
            onTransformChanged = { scale, tx, ty, rot ->
                if (selectedClipId != null) {
                    vm.updateClipTransform(selectedClipId, scale, tx, ty, rot)
                }
            },
            onCommitTransform = {
                if (selectedClipId != null) vm.commitEdit()
            },
            autoKeyframeEnabled = autoKeyframeEnabled,
            onTextPositionChanged = { clipId, x, y ->
                vm.updateTextClipStyle(clipId, RustTextStyleUpdate(positionX = x, positionY = y))
            },
            onTextFontSizeChanged = { clipId, size ->
                vm.updateTextClipStyle(clipId, RustTextStyleUpdate(fontSize = size))
            },
            onCropChanged = { clipId, left, top, right, bottom ->
                vm.updateCropFilter(clipId, left, top, right, bottom)
            },
            onCropDragStart = { vm.beginEdit("Crop") },
            onCropDragEnd = { vm.commitEdit() },
            onMaskChanged = { clipId, index, mask ->
                vm.updateMask(clipId, index, mask)
            },
            onMaskDragStart = { vm.beginEdit("Mask") },
            onMaskDragEnd = { vm.commitEdit() },
            onMaskDragCancel = { vm.cancelEdit() },
            onTextDragStart = { vm.beginEdit("Move text") },
            onTextDragEnd = { vm.commitEdit() },
            onSetKeyframe = { clipId, playheadMs, scale, tx, ty, rot ->
                val clip = snapshot?.timeline?.tracks?.flatMap { it.clips }?.find { it.id == clipId }
                if (clip != null) {
                    val clipOffsetUs = (playheadMs * 1000L - clip.timelineStartUs).coerceAtLeast(0L)
                    val videoKind = clip.kind as? RustVideoClipKind
                    val staticFilter = videoKind?.filters?.firstOrNull { it.filter is RustTransformFilterSnapshot }?.filter as? RustTransformFilterSnapshot
                    fun effective(param: String, default: Float, filterAccessor: (RustTransformFilterSnapshot) -> Float): Float {
                        val kf = clip.keyframes.evaluate(param, clipOffsetUs)
                        if (kf != null) return kf
                        return staticFilter?.let(filterAccessor) ?: default
                    }
                    data class KfParam(val param: String, val newValue: Float, val default: Float, val filterAccessor: (RustTransformFilterSnapshot) -> Float)
                    listOf(
                        KfParam(KeyframeParams.TRANSFORM_SCALE, scale, defaultOf(KeyframeParams.TRANSFORM_SCALE)) { it.scale },
                        KfParam(KeyframeParams.TRANSFORM_TRANSLATE_X, tx, defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X)) { it.translateX },
                        KfParam(KeyframeParams.TRANSFORM_TRANSLATE_Y, ty, defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y)) { it.translateY },
                        KfParam(KeyframeParams.TRANSFORM_ROTATE, rot, defaultOf(KeyframeParams.TRANSFORM_ROTATE)) { it.rotate },
                    ).forEach { (param, newVal, default, accessor) ->
                        val current = effective(param, default, accessor)
                        if (kotlin.math.abs(newVal - current) > 0.0001f) {
                            vm.addKeyframe(clipId, RustKeyframe(
                                param = param,
                                offset = clipOffsetUs.coerceAtLeast(0L),
                                value = newVal,
                            ))
                        }
                    }
                }
            },
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
            selectedCount = selectedClipIds.size,
            zoomLevel = zoomLevel,
            onZoomIn = { vm.zoomIn() },
            onZoomOut = { vm.zoomOut() },
            onAddText = { vm.addTextOverlay() },
            onAddAdjustment = { vm.addAdjustmentLayer() },
            onSplit = { selectedClipId?.let { vm.splitClipAtPlayhead(it) } },
            onDuplicate = { vm.duplicateSelectedClips() },
            onCopy = { vm.copySelectedClips() },
            onCut = { vm.cutSelectedClips() },
            onPaste = { vm.pasteClips() },
            canPaste = hasClipboard,
            onDelete = { vm.deleteSelectedClip() },
            onDeselect = { vm.clearClipSelection() },
            onSelectAll = { vm.selectAllClips() },
            onProperties = { showPropertiesSheet = true },
            onDetectScenes = { selectedClipId?.let { vm.detectScenes(it) } },
            detectScenesRunning = sceneDetectRunning,
            autoKeyframeEnabled = autoKeyframeEnabled,
            onToggleAutoKeyframe = { autoKeyframeEnabled = !autoKeyframeEnabled },
            markersCount = snapshot?.markers?.size ?: 0,
            markersPanelOpen = markersPanelOpen,
            onToggleMarkersPanel = { markersPanelOpen = !markersPanelOpen },
        )

        HorizontalDivider(thickness = 0.5.dp)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(Modifier.fillMaxSize()) {
                TimelinePanel(
                    snapshot = snapshot,
                    playheadMs = playheadMs,
                    zoomLevel = zoomLevel,
                    isPlaying = isPlaying,
                    selectedClipIds = selectedClipIds,
                    snapIndicatorMs = snapIndicatorMs,
                    beatMarkersMs = vm.beatMarkersMs.collectAsState().value,
                    markers = snapshot?.markers.orEmpty(),
                    exportRange = snapshot?.exportRange,
                    onMarkerAdd = { vm.addMarker(it) },
                    onMarkerRemove = vm::removeMarker,
                    onRangeSetIn = { vm.setExportRangeIn(it) },
                    onRangeSetOut = { vm.setExportRangeOut(it) },
                    onRangeClear = vm::clearExportRange,
                    modifier = Modifier.weight(1f),
                    onPlayheadChange = vm::seekTo,
                    onClipSelected = { clipId, mode ->
                        when (mode) {
                            ClipSelectionMode.Replace -> vm.selectClip(clipId)
                            ClipSelectionMode.Toggle -> if (clipId == null) vm.clearClipSelection() else vm.toggleClipSelection(clipId)
                            ClipSelectionMode.Extend -> if (clipId == null) vm.clearClipSelection() else vm.extendClipSelection(clipId)
                        }
                    },
                    onBeginEdit = vm::beginEdit,
                    onClipMoveAbsolute = vm::moveClipAbsolute,
                    onClipMoveToTrack = vm::moveClipToTrack,
                    onSelectionMoveToTrack = vm::moveSelectedClipsToTrack,
                    onCommitEdit = vm::commitEdit,
                    onCancelEdit = vm::cancelEdit,
                    onClipTrimStartAbsolute = vm::trimClipStartAbsolute,
                    onClipTrimEndAbsolute = vm::trimClipEndAbsolute,
                    onToggleMute = vm::toggleTrackMute,
                    onToggleSolo = vm::toggleTrackSolo,
                    onToggleLock = vm::toggleTrackLock,
                    onAddTrack = vm::addTrack,
                    onRemoveTrack = vm::removeTrack,
                    onMoveTrackUp = vm::moveTrackUp,
                    onMoveTrackDown = vm::moveTrackDown,
                    onSnapPosition = { ms, excludeIds -> vm.snapPosition(ms, excludeIds) },
                    onClearSnap = vm::clearSnapIndicator,
                    onSplitClip = vm::splitClipAtPlayhead,
                    onJoinClip = vm::joinClipWithNext,
                    onDuplicateClip = vm::duplicateClip,
                    onCopyClip = vm::copyClip,
                    onCutClip = vm::cutClip,
                    onPaste = vm::pasteClips,
                    canPaste = hasClipboard,
                    onDeleteClip = vm::removeClip,
                    onRippleDeleteClip = vm::rippleDeleteClip,
                    onToggleClipMute = vm::toggleClipMute,
                    onSplitAll = vm::splitAllAtPlayhead,
                    onCloseGap = vm::closeGapOnTrack,
                    onSlipClip = vm::slipClip,
                    onSlideClip = vm::slideClip,
                    onRollClip = vm::rollClip,
                    waveforms = vm.waveforms.collectAsState().value,
                    onNeedWaveform = vm::requestWaveform,
                    thumbnails = vm.timelineThumbnails.collectAsState().value,
                    onNeedThumbnail = vm::requestTimelineThumbnail,
                )

                if (markersPanelOpen) {
                    VerticalDivider(thickness = 0.5.dp)
                    MarkersPanel(
                        markers = snapshot?.markers.orEmpty(),
                        onSeek = vm::seekTo,
                        onAddAtPlayhead = vm::addMarkerAtPlayhead,
                        onDelete = vm::removeMarker,
                        onRelabel = vm::relabelMarker,
                        onLabelFocusChanged = { markerLabelFocused = it },
                        modifier = Modifier.width(228.dp).fillMaxHeight(),
                    )
                }
            }
        }
    }

    if (showPropertiesSheet && selectedClipId != null) {
            PropertiesBottomSheet(
                sheetState = sheetState,
                snapshot = snapshot,
                selectedClipId = selectedClipId,
                playheadMs = playheadMs,
                onDismiss = {
                    scope.launch { sheetState.hide() }
                    showPropertiesSheet = false
                },
                onAddFilter = vm::addFilter,
                onRemoveFilter = vm::removeFilter,
                onApplyFilterToAll = vm::applyFilterToAllClips,
                onUpdateFilterParams = vm::updateFilterParams,
                onToggleFilterEnabled = vm::setFilterEnabled,
                onMoveFilter = vm::moveFilter,
                onSetSpeed = vm::setClipSpeed,
                onApplySpeedCurve = vm::applySpeedCurve,
                onSetVolume = vm::setClipVolume,
                onSetTransitionIn = vm::setTransitionIn,
                onSetTransitionOut = vm::setTransitionOut,
                onApplyTransitionToAll = vm::applyTransitionToAllCuts,
                onUpdateText = vm::updateTextClip,
                onUpdateTextStyle = vm::updateTextClipStyle,
                onSetOpacity = vm::setClipOpacity,
                onSetBlendMode = vm::setClipBlendMode,
                onApplyKenBurns = vm::applyKenBurns,
                onFitToCanvas = vm::fitClipToCanvas,
                onAutoReframe = vm::autoReframeClip,
                autoReframeRunning = autoReframeRunning,
                onStabilize = vm::stabilizeClip,
                stabilizeRunning = stabilizeRunning,
                onFreezeFrame = vm::freezeFrame,
                onRemoveSilence = vm::removeSilence,
                removeSilenceRunning = removeSilenceRunning,
                onApplyTextAnimation = vm::applyTextAnimation,
                onReplaceFilter = vm::replaceFilter,
                onSetTextTransitionIn = vm::setTextTransitionIn,
                onSetTextTransitionOut = vm::setTextTransitionOut,
                onAddAudioFilter = { clipId, filter -> vm.addAudioFilter(clipId, filter) },
                onRemoveAudioFilter = { clipId, index -> vm.removeAudioFilter(clipId, index) },
                onUpdateAudioFilterDuration = { clipId, index, duration -> vm.updateAudioFilterDuration(clipId, index, duration) },
                onReplaceAudioFilter = vm::replaceAudioFilter,
                onAddKeyframe = vm::addKeyframe,
                onRemoveKeyframe = vm::removeKeyframe,
                onUpdateKeyframe = vm::updateKeyframe,
                onSetSubtitleFont = vm::setSubtitleFont,
                onTrackMask = { clipId, index -> vm.trackMask(clipId, index) },
                maskTrackRunning = maskTrackRunning,
                onAddMask = vm::addMask,
                onRemoveMask = vm::removeMask,
                onUpdateMask = { clipId, index, mask -> vm.updateMask(clipId, index, mask) },
                onSetMaskEnabled = vm::setMaskEnabled,
            )

    }

    if (showSaveAs) {
        SaveProjectDialog(
            defaultName = snapshot?.meta?.name ?: videoName,
            onDismiss = { showSaveAs = false },
            onSave = { _, path ->
                showSaveAs = false
                vm.saveProject(path)
            },
        )
    }

    if (showExitConfirm) {
        ConfirmDialog(
            title = "Unsaved Changes",
            message = "Save before leaving?",
            confirmText = "Save & Exit",
            dismissText = "Discard",
            onConfirm = {
                showExitConfirm = false
                if (uiState.projectPath == null) {
                    showSaveAs = true
                } else {
                    vm.saveProject()
                    backStack.popBack()
                }
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

private fun ProjectViewModel.handleEditorShortcut(event: KeyEvent, snapshot: RustProjectSnapshot?): Boolean {
    val ctrlOrCmd = (event.isCtrlPressed || event.isMetaPressed) && !event.isAltPressed
    val plainSpace = !ctrlOrCmd && !event.isShiftPressed && !event.isAltPressed && event.key == Key.Spacebar
    if (plainSpace) {
        if (event.type == KeyEventType.KeyUp) {
            togglePlayPause()
            return true
        }
        return false
    }
    if (event.type != KeyEventType.KeyDown) return false
    val altOnly = event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed
    val clipId = state.value.selectedClipId
    return when {
        ctrlOrCmd && event.key == Key.Z -> {
            if (event.isShiftPressed) redo() else undo()
            true
        }
        ctrlOrCmd && event.key == Key.Y -> {
            redo()
            true
        }
        ctrlOrCmd && !event.isShiftPressed && event.key == Key.A -> {
            selectAllClips()
            true
        }
        ctrlOrCmd && !event.isShiftPressed && event.key == Key.D -> {
            duplicateSelectedClips()
            true
        }
        ctrlOrCmd && !event.isShiftPressed && event.key == Key.C -> {
            copySelectedClips()
            true
        }
        ctrlOrCmd && !event.isShiftPressed && event.key == Key.X -> {
            cutSelectedClips()
            true
        }
        ctrlOrCmd && event.isShiftPressed && event.key == Key.X -> {
            clearExportRange()
            true
        }
        ctrlOrCmd && !event.isShiftPressed && event.key == Key.V -> {
            pasteClips()
            true
        }
        !ctrlOrCmd && !event.isAltPressed && (event.key == Key.Delete || event.key == Key.Backspace) -> {
            deleteSelectedClip()
            true
        }
        !ctrlOrCmd && !event.isAltPressed && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) -> {
            if (snapshot != null) {
                val fps = snapshot.exportProfile.fps.takeIf { it > 0.0 } ?: 30.0
                val stepMs = if (event.isShiftPressed) 1000L else (1000.0 / fps).roundToLong()
                seekRelative(if (event.key == Key.DirectionLeft) -stepMs else stepMs)
            }
            true
        }
        !ctrlOrCmd && !event.isAltPressed && event.key == Key.Escape -> {
            clearClipSelection()
            true
        }
        !ctrlOrCmd && !event.isAltPressed && event.key == Key.I -> {
            setExportRangeIn()
            true
        }
        !ctrlOrCmd && !event.isAltPressed && event.key == Key.O -> {
            setExportRangeOut()
            true
        }
        !ctrlOrCmd && !event.isAltPressed && (event.key == Key.Equals || event.key == Key.Plus) -> {
            zoomIn()
            true
        }
        !ctrlOrCmd && !event.isAltPressed && event.key == Key.Minus -> {
            zoomOut()
            true
        }
        altOnly && event.key == Key.Comma -> {
            clipId?.let { slideClip(it, backward = true) }
            true
        }
        altOnly && event.key == Key.Period -> {
            clipId?.let { slideClip(it, backward = false) }
            true
        }
        ctrlOrCmd && event.isShiftPressed && event.key == Key.Comma -> {
            clipId?.let { rollClip(it, backward = true) }
            true
        }
        ctrlOrCmd && event.isShiftPressed && event.key == Key.Period -> {
            clipId?.let { rollClip(it, backward = false) }
            true
        }
        !ctrlOrCmd && !event.isAltPressed && event.key == Key.Comma -> {
            clipId?.let { slipClip(it, backward = true) }
            true
        }
        !ctrlOrCmd && !event.isAltPressed && event.key == Key.Period -> {
            clipId?.let { slipClip(it, backward = false) }
            true
        }
        else -> false
    }
}
