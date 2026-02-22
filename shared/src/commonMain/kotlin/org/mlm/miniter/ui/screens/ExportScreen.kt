package org.mlm.miniter.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.path
import org.koin.compose.koinInject
import org.mlm.miniter.project.ExportFormat
import org.mlm.miniter.ui.util.popBack
import org.mlm.miniter.viewmodel.ProjectViewModel

@Composable
fun ExportScreen(
    backStack: NavBackStack<NavKey>,
    projectViewModel: ProjectViewModel = koinInject(),
) {
    val uiState by projectViewModel.state.collectAsState()
    val exportProgress by projectViewModel.exportProgress.collectAsState()
    val project = uiState.project

    var selectedFormat by remember {
        mutableStateOf(project?.exportSettings?.format ?: ExportFormat.MP4)
    }
    var quality by remember {
        mutableFloatStateOf(project?.exportSettings?.quality ?: 80f)
    }

    val directoryPicker = rememberDirectoryPickerLauncher(
        title = "Choose export location",
    ) { directory ->
        directory?.let { dir ->
            val fileName = "${project?.name ?: "export"}.${selectedFormat.extension}"
            val outputPath = "${dir.path}/$fileName"
            projectViewModel.exportProject(outputPath)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = {
                        projectViewModel.resetExport()
                        backStack.popBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Format", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormat.entries.forEach { format ->
                    FilterChip(
                        selected = selectedFormat == format,
                        onClick = { selectedFormat = format },
                        label = { Text(format.name) },
                    )
                }
            }

            Text(
                "Quality: ${quality.toInt()}%",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = quality,
                onValueChange = { quality = it },
                valueRange = 1f..100f,
                steps = 19,
            )

            Spacer(Modifier.height(16.dp))

            val isExporting = exportProgress.progress > 0f &&
                    !exportProgress.isComplete &&
                    !exportProgress.isCancelled &&
                    exportProgress.error == null

            if (isExporting) {
                LinearProgressIndicator(
                    progress = { exportProgress.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(exportProgress.phase, style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { projectViewModel.cancelExport() }) {
                    Text("Cancel Export")
                }
            }

            if (exportProgress.isComplete) {
                Text(
                    "✓ Export complete!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = { projectViewModel.resetExport() }) {
                    Text("Export Again")
                }
            }

            if (exportProgress.isCancelled) {
                Text(
                    "Export cancelled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            exportProgress.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { projectViewModel.resetExport() }) {
                    Text("Try Again")
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { directoryPicker.launch() },
                enabled = project != null && !isExporting && !exportProgress.isComplete,
                modifier = Modifier.fillMaxWidth(0.5f),
            ) {
                Text("Start Export")
            }
        }
    }
}
