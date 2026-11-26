package com.agy.nativeapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agy.nativeapp.engine.WorkspaceManager
import com.agy.nativeapp.model.ExecutionBackend
import com.agy.nativeapp.ui.MainUiState
import com.agy.nativeapp.ui.components.MessageCard
import com.agy.nativeapp.ui.components.PromptComposer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: MainUiState,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickFolderWithSaf: () -> Unit,
    onCreateProjectFolder: (String) -> Result<String>,
    onWorkdirChange: (String) -> Unit,
    onSendPrompt: (String) -> Unit,
    onStopRun: () -> Unit,
    onRefreshFiles: () -> Unit,
    onExportZip: () -> Unit,
    onClearExportStatus: () -> Unit,
    onOpenHtmlPreview: (String) -> Unit
) {
    val activeSession = state.activeSession
    val currentWorkdir = activeSession?.workdir ?: state.settings.defaultWorkdir
    var prompt by rememberSaveable { mutableStateOf("") }
    var showWorkdirDialog by remember { mutableStateOf(false) }
    var showFileExplorer by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val messages = activeSession?.messages.orEmpty()

    LaunchedEffect(messages.size, messages.lastOrNull()?.terminalLog?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val needsTermuxPermission = state.settings.backend != ExecutionBackend.DIRECT_PROCESS &&
        (!state.diagnostics.termuxInstalled || !state.diagnostics.runCommandPermissionGranted)
    val needsStoragePermission = state.settings.backend == ExecutionBackend.TERMUX_INTENT_STREAM &&
        !state.diagnostics.allFilesAccessGranted

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Сессии")
                    }
                },
                title = {
                    Column(
                        modifier = Modifier.clickable { showWorkdirDialog = true }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = currentWorkdir.substringAfterLast('/').ifBlank { currentWorkdir },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Выбрать папку",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = currentWorkdir,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        onRefreshFiles()
                        onClearExportStatus()
                        showFileExplorer = true
                    }) {
                        Icon(Icons.Default.Folder, contentDescription = "Файлы проекта")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        },
        bottomBar = {
            PromptComposer(
                prompt = prompt,
                onPromptChange = { prompt = it },
                onSend = {
                    val toSend = prompt
                    prompt = ""
                    onSendPrompt(toSend)
                },
                onStop = onStopRun,
                isRunning = state.isRunning
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (needsTermuxPermission || needsStoragePermission) {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSettings() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        val warningText = when {
                            !state.diagnostics.termuxInstalled ->
                                "Termux не установлен. Нажмите, чтобы открыть Настройки."
                            !state.diagnostics.runCommandPermissionGranted ->
                                "Нет разрешения com.termux.permission.RUN_COMMAND. Выдайте в Настройках."
                            else ->
                                "Нет доступа ко всем файлам (All Files Access) для чтения .agy_out.log. Выдайте в Настройках."
                        }
                        Text(
                            text = warningText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            state.indexHtmlPath?.let { htmlPath ->
                Surface(
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Обнаружен index.html",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "${state.workspaceFiles.size} файлов в проекте",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { onOpenHtmlPreview(htmlPath) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Запустить сайт (Preview)")
                        }
                    }
                }
            }

            if (messages.isEmpty()) {
                EmptySessionView(
                    workdir = currentWorkdir,
                    onPickFolder = { showWorkdirDialog = true },
                    onQuickPrompt = { starter -> prompt = starter },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageCard(
                            message = msg,
                            onOpenHtmlPreview = onOpenHtmlPreview
                        )
                    }
                }
            }
        }
    }

    if (showWorkdirDialog) {
        WorkspacePickerDialog(
            currentWorkdir = currentWorkdir,
            onPickWithSaf = {
                showWorkdirDialog = false
                onPickFolderWithSaf()
            },
            onCreateFolder = onCreateProjectFolder,
            onApplyManualPath = { path ->
                onWorkdirChange(path)
                showWorkdirDialog = false
            },
            onDismiss = { showWorkdirDialog = false }
        )
    }

    if (showFileExplorer) {
        FileExplorerSheet(
            workdir = currentWorkdir,
            files = state.workspaceFiles,
            exportStatus = state.exportStatusMessage,
            onRefresh = onRefreshFiles,
            onExportZip = onExportZip,
            onOpenHtmlPreview = { htmlPath ->
                showFileExplorer = false
                onOpenHtmlPreview(htmlPath)
            },
            onDismiss = { showFileExplorer = false }
        )
    }
}

@Composable
private fun WorkspacePickerDialog(
    currentWorkdir: String,
    onPickWithSaf: () -> Unit,
    onCreateFolder: (String) -> Result<String>,
    onApplyManualPath: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newFolderName by remember { mutableStateOf("") }
    var manualPath by remember(currentWorkdir) { mutableStateOf(currentWorkdir) }
    var createError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Рабочая папка проекта") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPickWithSaf,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Выбрать папку на смартфоне (SAF)")
                }

                HorizontalDivider()

                Text(
                    text = "Или создать новую в ${WorkspaceManager.DEFAULT_PROJECTS_ROOT}/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = {
                            newFolderName = it
                            createError = null
                        },
                        label = { Text("Имя папки") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            onCreateFolder(newFolderName)
                                .onSuccess { onDismiss() }
                                .onFailure { createError = it.message }
                        },
                        enabled = newFolderName.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Создать")
                    }
                }
                if (createError != null) {
                    Text(
                        text = createError.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = manualPath,
                    onValueChange = { manualPath = it },
                    label = { Text("Путь вручную") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (manualPath.isNotBlank()) onApplyManualPath(manualPath)
            }) {
                Text("Применить путь")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun EmptySessionView(
    workdir: String,
    onPickFolder: () -> Unit,
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val starters = listOf(
        "Создай современный одностраничный сайт-портфолио в index.html с тёмной темой и анимациями",
        "Создай интерактивную мини-игру на HTML5 Canvas + JS в index.html",
        "Проанализируй файлы в текущей папке и исправь найденные ошибки"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "AGY Native",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Прямой вызов agy через Termux без серверов, Python и localhost.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPickFolder() }
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = workdir,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            starters.forEach { sample ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onQuickPrompt(sample) }
                ) {
                    Text(
                        text = sample,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
