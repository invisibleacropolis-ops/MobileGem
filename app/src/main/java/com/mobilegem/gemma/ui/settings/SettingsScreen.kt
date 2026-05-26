package com.mobilegem.gemma.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobilegem.gemma.model.UriContentSource
import com.mobilegem.gemma.settings.InferenceBackend
import java.io.File

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            val name = UriContentSource.queryDisplayName(resolver, uri)
            viewModel.importModel(UriContentSource(resolver, uri, name))
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings")
        Button(
            enabled = !state.busy,
            onClick = { picker.launch(arrayOf("*/*")) },
        ) { Text("Import model (.litertlm)") }

        state.error?.let { Text("Error: $it") }

        Text("Installed models")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.installedModels) { name ->
                Card {
                    Column(Modifier.padding(12.dp)) {
                        RadioButton(
                            selected = name == state.activeModel,
                            onClick = { viewModel.selectModel(name) },
                        )
                        Text(name)
                        if (name == state.activeModel && state.modelLoaded) {
                            Text("Loaded")
                        }
                    }
                }
            }
        }

        Text("Inference backend")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InferenceBackend.entries.forEach { backend ->
                FilterChip(
                    selected = backend == state.backend,
                    onClick = { viewModel.setBackend(backend) },
                    label = { Text(backend.name) },
                )
            }
        }

        Text("Temperature: ${"%.2f".format(state.temperature)}")
        Slider(
            value = state.temperature,
            onValueChange = { viewModel.setTemperature(it) },
            valueRange = 0f..1.5f,
        )

        Text("Diagnostic logging")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = state.loggingEnabled,
                onCheckedChange = { viewModel.setLoggingEnabled(it) },
            )
            Text(if (state.loggingEnabled) "Enabled" else "Disabled")
        }
        state.logFilePath?.let { path ->
            Text("Log: ${File(path).name}", maxLines = 2)
            Button(onClick = { shareLogFile(context, path) }) { Text("Share log file") }
        }
    }
}

private fun shareLogFile(context: android.content.Context, path: String) {
    val file = java.io.File(path)
    if (!file.exists()) return
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(send, "Share log"))
}
