package com.mobilegem.gemma.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobilegem.gemma.logging.AppLog
import com.mobilegem.gemma.model.UriContentSource
import com.mobilegem.gemma.settings.InferenceBackend
import java.io.File
import java.io.RandomAccessFile

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showLogViewer by remember { mutableStateOf(false) }

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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Button(
            enabled = !state.busy,
            onClick = { picker.launch(arrayOf("*/*")) },
        ) { Text("Import model (.litertlm)") }

        state.error?.let { errorMsg ->
            Card { Text("Error: $errorMsg", Modifier.padding(12.dp)) }
        }

        Text("Installed models", style = MaterialTheme.typography.titleMedium)
        if (state.installedModels.isEmpty()) {
            Text("(none yet — use Import above)")
        } else {
            state.installedModels.forEach { name ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = name == state.activeModel,
                                onClick = { viewModel.selectModel(name) },
                            )
                            Text(name)
                        }
                        if (name == state.activeModel && state.modelLoaded) {
                            Text("Loaded", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Text("Inference backend", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InferenceBackend.entries.forEach { backend ->
                FilterChip(
                    selected = backend == state.backend,
                    onClick = { viewModel.setBackend(backend) },
                    label = { Text(backend.name) },
                )
            }
        }

        Text(
            "Temperature: ${"%.2f".format(state.temperature)}",
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = state.temperature,
            onValueChange = { viewModel.setTemperature(it) },
            valueRange = 0f..1.5f,
        )

        Text("Diagnostic logging", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = state.loggingEnabled,
                onCheckedChange = { viewModel.setLoggingEnabled(it) },
            )
            Text(if (state.loggingEnabled) "Enabled" else "Disabled")
        }
        state.logFilePath?.let { path ->
            Text("Log: ${File(path).name}", maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showLogViewer = true }) { Text("View log") }
                Button(onClick = { shareLogFile(context, path) }) { Text("Share log") }
            }
        }
    }

    if (showLogViewer) {
        LogViewerDialog(
            path = state.logFilePath,
            onDismiss = { showLogViewer = false },
        )
    }
}

@Composable
private fun LogViewerDialog(path: String?, onDismiss: () -> Unit) {
    if (path == null) { onDismiss(); return }
    var content by remember { mutableStateOf("(loading…)") }
    var version by remember { mutableStateOf(0) }

    LaunchedEffect(version, path) {
        AppLog.flush()
        content = readTail(File(path), maxBytes = 256 * 1024)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Log viewer", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { version++ }) { Text("Refresh") }
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                }
                Text(
                    text = "File: ${File(path).name} (${File(path).length()} bytes)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 8.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SelectionContainer {
                        Text(
                            text = content,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Reads the last [maxBytes] of [file], decoded as UTF-8. Safe to call on a live writer. */
private fun readTail(file: File, maxBytes: Int): String {
    if (!file.exists()) return "(no log file — start logging to populate it)"
    return runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val start = if (len <= maxBytes) 0L else len - maxBytes
            raf.seek(start)
            val buf = ByteArray((len - start).toInt())
            raf.readFully(buf)
            val text = buf.toString(Charsets.UTF_8)
            if (start > 0L) text.substringAfter('\n', missingDelimiterValue = text) else text
        }
    }.getOrElse { "Error reading log: ${it.message}" }
}

private fun shareLogFile(context: android.content.Context, path: String) {
    val file = File(path)
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
