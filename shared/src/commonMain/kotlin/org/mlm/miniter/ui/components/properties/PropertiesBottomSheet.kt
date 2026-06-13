package org.mlm.miniter.ui.components.properties

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.miniter.editor.model.RustAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustKeyframe
import org.mlm.miniter.editor.model.RustProjectSnapshot
import org.mlm.miniter.editor.model.RustTransitionSnapshot
import org.mlm.miniter.editor.model.RustVideoEffectSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertiesBottomSheet(
    sheetState: SheetState,
    snapshot: RustProjectSnapshot?,
    selectedClipId: String?,
    playheadMs: Long = 0L,
    onDismiss: () -> Unit,
    onAddFilter: (String, RustVideoEffectSnapshot) -> Unit,
    onRemoveFilter: (String, Int) -> Unit,
    onUpdateFilterParams: (String, Int, Map<String, Float>) -> Unit,
    onToggleFilterEnabled: (String, Int, Boolean) -> Unit,
    onMoveFilter: (String, Int, Int) -> Unit,
    onSetSpeed: (String, Float) -> Unit,
    onSetVolume: (String, Float) -> Unit,
    onSetTransitionIn: (String, RustTransitionSnapshot?) -> Unit,
    onSetTransitionOut: (String, RustTransitionSnapshot?) -> Unit,
    onUpdateText: (String, String) -> Unit,
    onUpdateTextStyle: (String, Float?, String?, String?, Float?, Float?, Boolean?, Boolean?) -> Unit,
    onSetOpacity: (String, Float) -> Unit,
    onSetTextTransitionIn: (String, RustTransitionSnapshot?) -> Unit,
    onSetTextTransitionOut: (String, RustTransitionSnapshot?) -> Unit,
    onAddAudioFilter: (String, RustAudioFilterSnapshot) -> Unit = { _, _ -> },
    onRemoveAudioFilter: (String, Int) -> Unit = { _, _ -> },
    onUpdateAudioFilterDuration: (String, Int, Long) -> Unit = { _, _, _ -> },
    onAddKeyframe: (String, RustKeyframe) -> Unit = { _, _ -> },
    onRemoveKeyframe: (String, Int) -> Unit = { _, _ -> },
    onUpdateKeyframe: (String, Int, RustKeyframe) -> Unit = { _, _, _ -> },
    onSetSubtitleFont: (String, String?) -> Unit = { _, _ -> },
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 400.dp),
        ) {
            PropertiesPanel(
                snapshot = snapshot,
                selectedClipId = selectedClipId,
                playheadMs = playheadMs,
                onAddFilter = onAddFilter,
                onRemoveFilter = onRemoveFilter,
                onUpdateFilterParams = onUpdateFilterParams,
                onToggleFilterEnabled = onToggleFilterEnabled,
                onMoveFilter = onMoveFilter,
                onSetSpeed = onSetSpeed,
                onSetVolume = onSetVolume,
                onSetTransitionIn = onSetTransitionIn,
                onSetTransitionOut = onSetTransitionOut,
                onUpdateText = onUpdateText,
                onUpdateTextStyle = onUpdateTextStyle,
                onSetOpacity = onSetOpacity,
                onSetTextTransitionIn = onSetTextTransitionIn,
                onSetTextTransitionOut = onSetTextTransitionOut,
                onAddAudioFilter = onAddAudioFilter,
                onRemoveAudioFilter = onRemoveAudioFilter,
                onUpdateAudioFilterDuration = onUpdateAudioFilterDuration,
                onAddKeyframe = onAddKeyframe,
                onRemoveKeyframe = onRemoveKeyframe,
                onUpdateKeyframe = onUpdateKeyframe,
                onSetSubtitleFont = onSetSubtitleFont,
            )
        }
    }
}
