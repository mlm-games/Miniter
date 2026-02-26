package org.mlm.miniter.ui.components.properties

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.miniter.project.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertiesBottomSheet(
    sheetState: SheetState,
    project: MinterProject?,
    selectedClipId: String?,
    onDismiss: () -> Unit,
    onAddFilter: (String, VideoFilter) -> Unit,
    onRemoveFilter: (String, Int) -> Unit,
    onUpdateFilterParams: (String, Int, Map<String, Float>) -> Unit,
    onSetSpeed: (String, Float) -> Unit,
    onSetVolume: (String, Float) -> Unit,
    onSetTransition: (String, Transition?) -> Unit,
    onUpdateText: (String, String) -> Unit,
    onUpdateTextStyle: (String, Float?, String?, String?, Float?, Float?, Boolean?, Boolean?) -> Unit,
    onSetOpacity: (String, Float) -> Unit,
    onSetTextDuration: (String, Long) -> Unit,
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
                project = project,
                selectedClipId = selectedClipId,
                onAddFilter = onAddFilter,
                onRemoveFilter = onRemoveFilter,
                onUpdateFilterParams = onUpdateFilterParams,
                onSetSpeed = onSetSpeed,
                onSetVolume = onSetVolume,
                onSetTransition = onSetTransition,
                onUpdateText = onUpdateText,
                onUpdateTextStyle = onUpdateTextStyle,
                onSetOpacity = onSetOpacity,
                onSetTextDuration = onSetTextDuration,
            )
        }
    }
}
