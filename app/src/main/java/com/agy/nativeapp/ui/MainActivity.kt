package com.agy.nativeapp.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agy.nativeapp.engine.TermuxDiagnostics
import com.agy.nativeapp.ui.screens.ChatScreen
import com.agy.nativeapp.ui.screens.HtmlPreviewScreen
import com.agy.nativeapp.ui.screens.SessionsDrawerContent
import com.agy.nativeapp.ui.screens.SettingsScreen
import com.agy.nativeapp.ui.theme.AgyNativeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AgyNativeTheme(darkTheme = true) {
                val state by viewModel.uiState.collectAsState()
                var showingSettings by rememberSaveable { mutableStateOf(false) }
                var previewHtmlPath by rememberSaveable { mutableStateOf<String?>(null) }
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    viewModel.refreshDiagnostics()
                    if (!granted) {
                        TermuxDiagnostics.openAppDetailsSettings(this@MainActivity)
                    }
                }

                val folderTreeLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    if (uri != null) {
                        val resolved = viewModel.applyDocumentTreeUri(uri)
                        if (resolved == null) {
                            Toast.makeText(
                                this@MainActivity,
                                "Не удалось преобразовать URI в путь. Выберите папку во внутренней памяти.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                val lifecycle = this@MainActivity.lifecycle
                DisposableEffect(lifecycle) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.refreshDiagnostics()
                            viewModel.refreshWorkspaceFiles()
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }

                when {
                    previewHtmlPath != null -> {
                        HtmlPreviewScreen(
                            indexHtmlPath = previewHtmlPath!!,
                            onClose = { previewHtmlPath = null }
                        )
                    }

                    showingSettings -> {
                        SettingsScreen(
                            settings = state.settings,
                            diagnostics = state.diagnostics,
                            diagnosticTestResult = state.diagnosticTestResult,
                            isTestingDiagnostics = state.isTestingDiagnostics,
                            onRequestRunCommandPermission = {
                                permissionLauncher.launch(TermuxDiagnostics.PERMISSION_RUN_COMMAND)
                            },
                            onRunDiagnosticTest = viewModel::runDiagnosticTest,
                            onSaveSettings = viewModel::updateSettings,
                            onBack = { showingSettings = false }
                        )
                    }

                    else -> {
                        ModalNavigationDrawer(
                            drawerState = drawerState,
                            drawerContent = {
                                SessionsDrawerContent(
                                    sessions = state.sessions,
                                    activeSessionId = state.activeSessionId,
                                    onSelectSession = { id ->
                                        viewModel.selectSession(id)
                                        scope.launch { drawerState.close() }
                                    },
                                    onNewSession = {
                                        viewModel.createAndSelectNewSession()
                                        scope.launch { drawerState.close() }
                                    },
                                    onDeleteSession = viewModel::deleteSession
                                )
                            }
                        ) {
                            ChatScreen(
                                state = state,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                onOpenSettings = { showingSettings = true },
                                onPickFolderWithSaf = { folderTreeLauncher.launch(null) },
                                onCreateProjectFolder = viewModel::createNewProjectFolder,
                                onWorkdirChange = viewModel::updateActiveSessionWorkdir,
                                onSendPrompt = viewModel::sendPrompt,
                                onStopRun = viewModel::stopActiveRun,
                                onRefreshFiles = { viewModel.refreshWorkspaceFiles() },
                                onExportZip = { viewModel.exportCurrentWorkspaceToZip(this@MainActivity) },
                                onClearExportStatus = viewModel::clearExportStatus,
                                onOpenHtmlPreview = { path -> previewHtmlPath = path }
                            )
                        }
                    }
                }
            }
        }
    }
}
