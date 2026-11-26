package com.agy.nativeapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.agy.nativeapp.engine.DiagnosticStatus
import com.agy.nativeapp.engine.TermuxDiagnostics
import com.agy.nativeapp.model.AgyOutputFormat
import com.agy.nativeapp.model.AgySettings
import com.agy.nativeapp.model.ExecutionBackend

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AgySettings,
    diagnostics: DiagnosticStatus,
    diagnosticTestResult: String?,
    isTestingDiagnostics: Boolean,
    onRequestRunCommandPermission: () -> Unit,
    onRunDiagnosticTest: () -> Unit,
    onSaveSettings: (AgySettings) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var draft by remember(settings) { mutableStateOf(settings) }
    var extraDirsText by remember(settings) {
        mutableStateOf(settings.extraAddDirs.joinToString("\n"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AGY Settings & Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = {
                        onSaveSettings(
                            draft.copy(
                                extraAddDirs = extraDirsText.lines()
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                            )
                        )
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Diagnostics & Termux Bridge Status
            SectionCard(title = "Execution Environment & Permissions") {
                DiagnosticRow(
                    label = "Termux package (com.termux)",
                    ok = diagnostics.termuxInstalled,
                    detail = if (diagnostics.termuxInstalled) "Installed" else "Not found"
                )
                DiagnosticRow(
                    label = "com.termux.permission.RUN_COMMAND",
                    ok = diagnostics.runCommandPermissionGranted,
                    detail = if (diagnostics.runCommandPermissionGranted) "Granted" else "Required for Termux modes"
                )
                DiagnosticRow(
                    label = "Shared storage access (live tail)",
                    ok = diagnostics.allFilesAccessGranted,
                    detail = if (diagnostics.allFilesAccessGranted) "Granted" else "Optional (needed for real-time NDJSON file tail)"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!diagnostics.runCommandPermissionGranted) {
                        OutlinedButton(
                            onClick = onRequestRunCommandPermission,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Grant Termux Perm")
                        }
                    }
                    if (!diagnostics.allFilesAccessGranted) {
                        OutlinedButton(
                            onClick = { TermuxDiagnostics.openAllFilesAccessSettings(context) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("All Files Access")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { TermuxDiagnostics.openTermuxApp(context) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Open Termux")
                    }
                    Button(
                        onClick = {
                            onSaveSettings(draft)
                            onRunDiagnosticTest()
                        },
                        enabled = !isTestingDiagnostics,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isTestingDiagnostics) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Test agy")
                    }
                }

                if (!diagnosticTestResult.isNullOrBlank()) {
                    Text(
                        text = diagnosticTestResult,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Text(
                    text = "Termux setup tip: ensure ~/.termux/termux.properties has allow-external-apps = true, then run termux-reload-settings (and termux-setup-storage if using Live Stream mode).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Backend Selector
            SectionCard(title = "Execution Backend (No Server / No Python / No Localhost)") {
                ExecutionBackend.entries.forEach { backend ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { draft = draft.copy(backend = backend) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(
                            selected = draft.backend == backend,
                            onClick = { draft = draft.copy(backend = backend) }
                        )
                        Column(modifier = Modifier.padding(start = 6.dp)) {
                            Text(
                                text = backend.displayName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = backend.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (draft.backend == ExecutionBackend.DIRECT_PROCESS) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Run via su -c (Root)", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Enable if agy lives inside /data/data/com.termux on a rooted device",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = draft.useRootForDirectProcess,
                            onCheckedChange = { draft = draft.copy(useRootForDirectProcess = it) }
                        )
                    }
                }
            }

            // CLI Paths & Options
            SectionCard(title = "CLI Paths & Arguments") {
                OutlinedTextField(
                    value = draft.agyBinaryPath,
                    onValueChange = { draft = draft.copy(agyBinaryPath = it) },
                    label = { Text("agy binary path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.bashBinaryPath,
                    onValueChange = { draft = draft.copy(bashBinaryPath = it) },
                    label = { Text("bash binary path (Termux modes)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.defaultWorkdir,
                    onValueChange = { draft = draft.copy(defaultWorkdir = it) },
                    label = { Text("Default workspace directory") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.streamDirPath,
                    onValueChange = { draft = draft.copy(streamDirPath = it) },
                    label = { Text("Shared stream directory (NDJSON tail)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.printTimeout,
                    onValueChange = { draft = draft.copy(printTimeout = it) },
                    label = { Text("--print-timeout (e.g. 5m, 10m)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = extraDirsText,
                    onValueChange = { extraDirsText = it },
                    label = { Text("Extra --add-dir paths (one per line)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.extraEnvLines,
                    onValueChange = { draft = draft.copy(extraEnvLines = it) },
                    label = { Text("Extra environment variables (KEY=VALUE)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Output Format (--output-format)", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgyOutputFormat.entries.forEach { fmt ->
                        FilterChip(
                            selected = draft.outputFormat == fmt,
                            onClick = { draft = draft.copy(outputFormat = fmt) },
                            label = { Text(fmt.cliValue) }
                        )
                    }
                }
            }

            Button(
                onClick = {
                    onSaveSettings(
                        draft.copy(
                            extraAddDirs = extraDirsText.lines()
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                        )
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Settings")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    ok: Boolean,
    detail: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
