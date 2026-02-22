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
import org.koin.compose.koinInject
import org.mlm.miniter.engine.ExportProgress
import org.mlm.miniter.engine.VideoExporter
import org.mlm.miniter.project.ExportFormat
import org.mlm.miniter.ui.util.popBack
import org.mlm.miniter.viewmodel.ProjectViewModel

@Composable
fun ExportScreen(
    backStack: NavBackStack<NavKey>,
    projectViewModel: ProjectViewModel = koinInject(),
    exporter: VideoExporter = koinInject(),
) {
    val uiState by projectViewModel.state.collectAsState()
    val exportProgress by exporter.progress.collectAsState()
    val project = uiState.project

    var selectedFormat by remember {
        mutableStateOf(project?.exportSettings?.format ?: ExportFormat.MP4)
    }
    var quality by remember {
        mutableFloatStateOf(project?.exportSettings?.quality ?: 80f)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { backStack.popBack() }) {
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

            Text("Quality: ${quality.toInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = quality,
                onValueChange = { quality = it },
                valueRange = 1f..100f,
                steps = 19,
            )

            Spacer(Modifier.height(16.dp))

            if (exportProgress.progress > 0f && !exportProgress.isComplete) {
                LinearProgressIndicator(
                    progress = { exportProgress.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(exportProgress.phase, style = MaterialTheme.typography.bodySmall)
            }

            if (exportProgress.isComplete) {
                Text(
                    "✓ Export complete!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }

            exportProgress.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    // TODO: show file save dialog, then:
                    // exporter.export(project!!, outputPath)
                },
                enabled = project != null && !exportProgress.isComplete && exportProgress.progress == 0f,
                modifier = Modifier.fillMaxWidth(0.5f),
            ) {
                Text("Start Export")
            }
        }
    }
}