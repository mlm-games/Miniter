package org.mlm.miniter.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.mlm.miniter.nav.Route
import org.mlm.miniter.ui.components.dialogs.ConfirmDialog
import org.mlm.miniter.ui.components.dialogs.ShortcutHelpDialog
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
) {
    val vm: ProjectViewModel = koinInject()
    val uiState by vm.state.collectAsState()
    val project = uiState.project
    val playheadMs = uiState.playheadMs
    val isPlaying = uiState.isPlaying
    val selectedClipId = uiState.selectedClipId
    val zoomLevel = uiState.zoomLevel
    val snapIndicatorMs = uiState.snapIndicatorMs
    val canUndo = uiState.canUndo
    val canRedo = uiState.canRedo
    val isDirty = uiState.isDirty

    var showShortcutHelp by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showPropertiesSheet by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(videoPath) {
        if (project == null) {
            vm.initProject(videoPath, videoName, savePath)
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val keyHandler = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val shift = event.isShiftPressed
        when {
            event.key == Key.Spacebar -> { vm.togglePlayPause(); true }
            event.key == Key.S && ctrl -> { vm.saveProject(); true }
            event.key == Key.Z && ctrl && shift -> { vm.redo(); true }
            event.key == Key.Z && ctrl -> { vm.undo(); true }
            event.key == Key.S && !ctrl -> {
                selectedClipId?.let { vm.splitClipAtPlayhead(it) }; true
            }
            event.key == Key.D -> {
                selectedClipId?.let { vm.duplicateClip(it) }; true
            }
            event.key == Key.T -> { vm.addTextOverlay(); true }
            event.key == Key.Delete || event.key == Key.Backspace -> {
                vm.deleteSelectedClip(); true
            }
            event.key == Key.DirectionLeft && shift -> { vm.seekRelative(-5000); true }
            event.key == Key.DirectionRight && shift -> { vm.seekRelative(5000); true }
            event.key == Key.DirectionLeft -> { vm.seekRelative(-1000); true }
            event.key == Key.DirectionRight -> { vm.seekRelative(1000); true }
            event.key == Key.MoveHome -> { vm.seekToStart(); true }
            event.key == Key.MoveEnd -> { vm.seekToEnd(); true }
            event.key == Key.Equals -> { vm.zoomIn(); true }
            event.key == Key.Minus -> { vm.zoomOut(); true }
            event.key == Key.Escape -> { vm.selectClip(null); true }
            else -> false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(keyHandler)
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        CompactTopBar(
            projectName = project?.name ?: videoName,
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
            onShortcutHelp = { showShortcutHelp = true },
        )

        EditorVideoPreview(
            project = project,
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
            durationMs = project?.timeline?.durationMs ?: 0,
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
                project = project,
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
            project = project,
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
            onUpdateTextStyle = { clipId, fontSize, color ->
                vm.updateTextClipStyle(clipId, fontSize, color)
            },
            onSetOpacity = vm::setClipOpacity,
            onSetTextDuration = vm::setTextClipDuration,
        )
    }

    if (showShortcutHelp) {
        ShortcutHelpDialog(onDismiss = { showShortcutHelp = false })
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
