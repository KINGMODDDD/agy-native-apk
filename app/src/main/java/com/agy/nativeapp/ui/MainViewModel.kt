package com.agy.nativeapp.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agy.nativeapp.AgyApplication
import com.agy.nativeapp.engine.AgyCommandBuilder
import com.agy.nativeapp.engine.AgyExecutionEngine
import com.agy.nativeapp.engine.AgyRunRequest
import com.agy.nativeapp.engine.DiagnosticStatus
import com.agy.nativeapp.engine.DirectProcessEngine
import com.agy.nativeapp.engine.PromptRunEvent
import com.agy.nativeapp.engine.TermuxDiagnostics
import com.agy.nativeapp.engine.TermuxIntentEngine
import com.agy.nativeapp.engine.WorkspaceManager
import com.agy.nativeapp.model.AgentStep
import com.agy.nativeapp.model.AgyMode
import com.agy.nativeapp.model.AgySettings
import com.agy.nativeapp.model.AgyStreamEvent
import com.agy.nativeapp.model.ChatMessage
import com.agy.nativeapp.model.ChatSession
import com.agy.nativeapp.model.ExecutionBackend
import com.agy.nativeapp.model.MessageRole
import com.agy.nativeapp.model.WorkspaceFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ConsoleLogEntry(
    val timestampMs: Long,
    val stream: String,
    val text: String
)

data class MainUiState(
    val settings: AgySettings = AgySettings(),
    val sessions: List<ChatSession> = emptyList(),
    val activeSessionId: String = "",
    val isRunning: Boolean = false,
    val activeRunId: String? = null,
    val workspaceFiles: List<WorkspaceFileItem> = emptyList(),
    val indexHtmlPath: String? = null,
    val exportStatusMessage: String? = null,
    val diagnostics: DiagnosticStatus = DiagnosticStatus(
        termuxInstalled = false,
        runCommandPermissionGranted = false,
        allFilesAccessGranted = false
    ),
    val diagnosticTestResult: String? = null,
    val isTestingDiagnostics: Boolean = false,
    val consoleLogs: List<ConsoleLogEntry> = emptyList()
) {
    val activeSession: ChatSession?
        get() = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.firstOrNull()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AgyApplication
    private val settingsRepo = app.settingsRepository
    private val sessionRepo = app.sessionRepository

    private val directEngine = DirectProcessEngine()
    private val termuxStreamEngine = TermuxIntentEngine(application, streamingViaSharedFiles = true)
    private val termuxPendingIntentEngine = TermuxIntentEngine(application, streamingViaSharedFiles = false)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var activeRunJob: Job? = null

    init {
        viewModelScope.launch {
            val loadedSettings = settingsRepo.load()
            val loadedSessions = sessionRepo.loadAll()
            val initialSessions = if (loadedSessions.isEmpty()) {
                listOf(createNewSession(loadedSettings))
            } else {
                loadedSessions
            }
            val firstSession = initialSessions.first()
            _uiState.update {
                it.copy(
                    settings = loadedSettings,
                    sessions = initialSessions,
                    activeSessionId = firstSession.id,
                    diagnostics = TermuxDiagnostics.inspect(app)
                )
            }
            refreshWorkspaceFiles(firstSession.workdir)
        }
    }

    fun refreshDiagnostics() {
        _uiState.update {
            it.copy(diagnostics = TermuxDiagnostics.inspect(app))
        }
    }

    fun refreshWorkspaceFiles(workdirOverride: String? = null) {
        val workdir = workdirOverride
            ?: _uiState.value.activeSession?.workdir
            ?: _uiState.value.settings.defaultWorkdir
        viewModelScope.launch {
            val (files, indexFile) = withContext(Dispatchers.IO) {
                val list = WorkspaceManager.listWorkspaceFiles(workdir)
                val html = WorkspaceManager.findIndexHtml(workdir)?.absolutePath
                list to html
            }
            _uiState.update {
                it.copy(
                    workspaceFiles = files,
                    indexHtmlPath = indexFile
                )
            }
        }
    }

    fun applyDocumentTreeUri(uri: Uri): String? {
        val resolved = WorkspaceManager.resolveTreeUriToPath(uri) ?: return null
        updateActiveSessionWorkdir(resolved)
        return resolved
    }

    fun createNewProjectFolder(folderName: String): Result<String> {
        val res = WorkspaceManager.createProjectFolder(folderName)
        res.onSuccess { dir ->
            updateActiveSessionWorkdir(dir.absolutePath)
        }
        return res.map { it.absolutePath }
    }

    fun exportCurrentWorkspaceToZip(context: Context) {
        val workdir = _uiState.value.activeSession?.workdir
            ?: _uiState.value.settings.defaultWorkdir
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                WorkspaceManager.exportWorkspaceToZip(context, workdir)
            }
            result.onSuccess { zipFile ->
                _uiState.update {
                    it.copy(exportStatusMessage = "Архив создан: ${zipFile.name} (и скопирован в Download)")
                }
                WorkspaceManager.shareZipFile(context, zipFile)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(exportStatusMessage = "Ошибка экспорта: ${err.message}")
                }
            }
        }
    }

    fun clearExportStatus() {
        _uiState.update { it.copy(exportStatusMessage = null) }
    }

    fun runDiagnosticTest() {
        if (_uiState.value.isTestingDiagnostics) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isTestingDiagnostics = true, diagnosticTestResult = "Выполняется agy --version ...")
            }
            val currentSettings = _uiState.value.settings
            val resultText = if (currentSettings.backend == ExecutionBackend.DIRECT_PROCESS) {
                runCatching {
                    val pb = ProcessBuilder(currentSettings.agyBinaryPath, "--version")
                    val proc = pb.start()
                    val out = proc.inputStream.bufferedReader().readText().trim()
                    val err = proc.errorStream.bufferedReader().readText().trim()
                    val code = proc.waitFor()
                    "Exit code: $code\nStdout: ${out.ifBlank { "(empty)" }}\nStderr: ${err.ifBlank { "(empty)" }}"
                }.getOrElse { "Direct exec failed: ${it.message}" }
            } else {
                val cmdResult = termuxStreamEngine.runQuickCommand(
                    settings = currentSettings,
                    command = "${AgyCommandBuilder.shellQuote(currentSettings.agyBinaryPath)} --version"
                )
                buildString {
                    appendLine("Exit code: ${cmdResult.exitCode}")
                    if (!cmdResult.internalErrorMessage.isNullOrBlank()) {
                        appendLine("Termux error: ${cmdResult.internalErrorMessage}")
                    }
                    if (cmdResult.stdout.isNotBlank()) {
                        appendLine("Stdout: ${cmdResult.stdout.trim()}")
                    }
                    if (cmdResult.stderr.isNotBlank()) {
                        appendLine("Stderr: ${cmdResult.stderr.trim()}")
                    }
                }.trim()
            }
            _uiState.update {
                it.copy(
                    isTestingDiagnostics = false,
                    diagnosticTestResult = resultText,
                    diagnostics = TermuxDiagnostics.inspect(app)
                )
            }
        }
    }

    fun updateSettings(newSettings: AgySettings) {
        _uiState.update { it.copy(settings = newSettings) }
        viewModelScope.launch {
            settingsRepo.save(newSettings)
        }
    }

    fun selectSession(sessionId: String) {
        _uiState.update { it.copy(activeSessionId = sessionId) }
        refreshWorkspaceFiles(_uiState.value.sessions.firstOrNull { it.id == sessionId }?.workdir)
    }

    fun createAndSelectNewSession() {
        val currentSettings = _uiState.value.settings
        val fresh = createNewSession(currentSettings)
        val updated = listOf(fresh) + _uiState.value.sessions
        _uiState.update {
            it.copy(
                sessions = updated,
                activeSessionId = fresh.id
            )
        }
        persistSessions(updated)
        refreshWorkspaceFiles(fresh.workdir)
    }

    fun deleteSession(sessionId: String) {
        val remaining = _uiState.value.sessions.filterNot { it.id == sessionId }
        val finalSessions = if (remaining.isEmpty()) {
            listOf(createNewSession(_uiState.value.settings))
        } else {
            remaining
        }
        val nextActive = if (_uiState.value.activeSessionId == sessionId) {
            finalSessions.first().id
        } else {
            _uiState.value.activeSessionId
        }
        _uiState.update {
            it.copy(
                sessions = finalSessions,
                activeSessionId = nextActive
            )
        }
        persistSessions(finalSessions)
        refreshWorkspaceFiles(finalSessions.firstOrNull { it.id == nextActive }?.workdir)
    }

    fun updateActiveSessionMode(mode: AgyMode) {
        val session = _uiState.value.activeSession ?: return
        updateSession(session.copy(mode = mode, updatedAtMs = System.currentTimeMillis()))
    }

    fun updateActiveSessionWorkdir(workdir: String) {
        val clean = workdir.trim().ifBlank { return }
        val session = _uiState.value.activeSession ?: return
        updateSession(session.copy(workdir = clean, updatedAtMs = System.currentTimeMillis()))
        refreshWorkspaceFiles(clean)
    }

    fun toggleContinueLastSession() {
        val session = _uiState.value.activeSession ?: return
        updateSession(
            session.copy(
                continueLastSession = !session.continueLastSession,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    fun clearConsoleLogs() {
        _uiState.update { it.copy(consoleLogs = emptyList()) }
    }

    fun sendPrompt(rawPrompt: String) {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty()) return

        if (prompt == "/new") {
            createAndSelectNewSession()
            return
        }
        if (prompt == "/stop") {
            stopActiveRun()
            return
        }
        if (_uiState.value.isRunning) return

        val settings = _uiState.value.settings
        val session = _uiState.value.activeSession ?: createNewSession(settings)
        val workdir = session.workdir.ifBlank { settings.defaultWorkdir }
        val runId = "run-${UUID.randomUUID().toString().take(8)}"

        if (settings.backend == ExecutionBackend.TERMUX_INTENT_STREAM) {
            runPromptFileWorkflow(settings, session, workdir, prompt, runId)
        } else {
            runStreamEngineWorkflow(settings, session, workdir, prompt, runId)
        }
    }

    private fun runPromptFileWorkflow(
        settings: AgySettings,
        session: ChatSession,
        workdir: String,
        prompt: String,
        runId: String
    ) {
        val commandPreview =
            "cd ${AgyCommandBuilder.shellQuote(workdir)} && ${settings.agyBinaryPath} -p \"${AgyCommandBuilder.PROMPT_FILE_INSTRUCTION}\" --dangerously-skip-permissions"

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            text = prompt,
            commandPreview = commandPreview
        )
        val assistantMsgId = UUID.randomUUID().toString()
        val assistantPlaceholder = ChatMessage(
            id = assistantMsgId,
            role = MessageRole.ASSISTANT,
            text = "",
            terminalLog = "Запись .agy_prompt.txt и запуск agy в Termux...",
            isStreaming = true
        )

        val newTitle = if (session.messages.isEmpty()) {
            prompt.lineSequence().firstOrNull()?.take(48).orEmpty().ifBlank { "AGY Задача" }
        } else {
            session.title
        }

        val updatedSession = session.copy(
            title = newTitle,
            messages = session.messages + userMsg + assistantPlaceholder,
            updatedAtMs = System.currentTimeMillis()
        )
        updateSession(updatedSession)
        appendLog("CMD", "$ $commandPreview")

        _uiState.update { it.copy(isRunning = true, activeRunId = runId) }

        activeRunJob = viewModelScope.launch {
            val preFilesSnapshot = withContext(Dispatchers.IO) {
                WorkspaceManager.listWorkspaceFiles(workdir)
                    .associateBy({ it.relativePath }, { it.lastModifiedMs })
            }

            termuxStreamEngine.executePromptFileWorkflow(settings, workdir, prompt).collect { event ->
                when (event) {
                    is PromptRunEvent.LogSnapshot -> {
                        val currentFiles = withContext(Dispatchers.IO) {
                            WorkspaceManager.listWorkspaceFiles(workdir)
                        }
                        val indexHtml = withContext(Dispatchers.IO) {
                            WorkspaceManager.findIndexHtml(workdir)?.absolutePath
                        }
                        val changedFiles = currentFiles.filter { item ->
                            !item.isDirectory && (preFilesSnapshot[item.relativePath] != item.lastModifiedMs)
                        }
                        _uiState.update {
                            it.copy(workspaceFiles = currentFiles, indexHtmlPath = indexHtml)
                        }
                        mutateAssistantMessage(updatedSession.id, assistantMsgId) { msg ->
                            msg.copy(
                                terminalLog = event.content,
                                createdFiles = changedFiles,
                                isStreaming = true
                            )
                        }
                    }

                    is PromptRunEvent.Completed -> {
                        appendLog("EXIT", "code=${event.exitCode} err=${event.errorMessage.orEmpty()}")
                        val finalFiles = withContext(Dispatchers.IO) {
                            WorkspaceManager.listWorkspaceFiles(workdir)
                        }
                        val indexHtml = withContext(Dispatchers.IO) {
                            WorkspaceManager.findIndexHtml(workdir)?.absolutePath
                        }
                        val changedFiles = finalFiles.filter { item ->
                            !item.isDirectory && (preFilesSnapshot[item.relativePath] != item.lastModifiedMs)
                        }

                        val isErr = !event.errorMessage.isNullOrBlank()
                        val summaryText = when {
                            isErr -> "Ошибка: ${event.errorMessage}"
                            event.exitCode == 0 && changedFiles.isNotEmpty() ->
                                "Выполнение завершено (exit 0). Создано/обновлено файлов: ${changedFiles.size}."
                            event.exitCode == 0 ->
                                event.finalLog.ifBlank { "Выполнение завершено (exit 0)." }
                            else ->
                                "Процесс завершился с кодом ${event.exitCode}."
                        }

                        mutateAssistantMessage(updatedSession.id, assistantMsgId) { msg ->
                            msg.copy(
                                role = if (isErr) MessageRole.SYSTEM_ERROR else MessageRole.ASSISTANT,
                                text = summaryText,
                                terminalLog = event.finalLog,
                                createdFiles = changedFiles,
                                isStreaming = false
                            )
                        }

                        _uiState.update {
                            it.copy(
                                isRunning = false,
                                activeRunId = null,
                                workspaceFiles = finalFiles,
                                indexHtmlPath = indexHtml
                            )
                        }
                        persistSessions(_uiState.value.sessions)
                    }
                }
            }
        }
    }

    private fun runStreamEngineWorkflow(
        settings: AgySettings,
        session: ChatSession,
        workdir: String,
        prompt: String,
        runId: String
    ) {
        val request = AgyRunRequest(
            runId = runId,
            prompt = prompt,
            workdir = workdir,
            mode = session.mode,
            conversationId = session.conversationId,
            continueLastSession = session.continueLastSession,
            outputFormat = settings.outputFormat,
            addDirs = settings.extraAddDirs,
            printTimeout = settings.printTimeout
        )

        val commandPreview = AgyCommandBuilder.buildDisplayCommand(settings, request)
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            text = prompt,
            commandPreview = commandPreview
        )
        val assistantMsgId = UUID.randomUUID().toString()
        val assistantPlaceholder = ChatMessage(
            id = assistantMsgId,
            role = MessageRole.ASSISTANT,
            text = "",
            isStreaming = true
        )

        val newTitle = if (session.messages.isEmpty()) {
            prompt.lineSequence().firstOrNull()?.take(48).orEmpty().ifBlank { "AGY Session" }
        } else {
            session.title
        }

        val updatedSession = session.copy(
            title = newTitle,
            messages = session.messages + userMsg + assistantPlaceholder,
            updatedAtMs = System.currentTimeMillis()
        )
        updateSession(updatedSession)
        appendLog("CMD", "$ $commandPreview")

        _uiState.update { it.copy(isRunning = true, activeRunId = runId) }

        val engine = engineFor(settings.backend)
        activeRunJob = viewModelScope.launch {
            val streamedBuffer = StringBuilder()
            val rawStdoutBuffer = StringBuilder()
            val stderrBuffer = StringBuilder()

            engine.execute(settings, request).collect { event ->
                when (event) {
                    is AgyStreamEvent.Init -> {
                        appendLog("INIT", "conversation_id=${event.conversationId} model=${event.model}")
                        mutateSession(updatedSession.id) { current ->
                            current.copy(
                                conversationId = event.conversationId ?: current.conversationId,
                                modelName = event.model ?: current.modelName,
                                updatedAtMs = System.currentTimeMillis()
                            )
                        }
                    }

                    is AgyStreamEvent.StepUpdate -> {
                        appendLog(
                            "STEP",
                            "#${event.stepIndex} [${event.state}] ${event.stepType} ${event.detail.orEmpty()}"
                        )
                        if (!event.textDelta.isNullOrEmpty()) {
                            streamedBuffer.append(event.textDelta)
                        }
                        val step = AgentStep(
                            stepIndex = event.stepIndex,
                            stepType = event.stepType,
                            state = event.state,
                            detail = event.detail.orEmpty()
                        )
                        mutateAssistantMessage(updatedSession.id, assistantMsgId) { msg ->
                            msg.copy(
                                text = streamedBuffer.toString(),
                                steps = mergeStep(msg.steps, step),
                                isStreaming = true
                            )
                        }
                    }

                    is AgyStreamEvent.Result -> {
                        appendLog(
                            "RESULT",
                            "status=${event.status} turns=${event.numTurns} duration=${event.durationSeconds}s"
                        )
                        val finalText = event.response.ifBlank { streamedBuffer.toString() }
                        mutateSession(updatedSession.id) { current ->
                            current.copy(
                                conversationId = event.conversationId ?: current.conversationId,
                                updatedAtMs = System.currentTimeMillis(),
                                messages = current.messages.map { m ->
                                    if (m.id == assistantMsgId) {
                                        m.copy(
                                            text = finalText,
                                            usage = event.usage,
                                            durationSeconds = event.durationSeconds,
                                            numTurns = event.numTurns,
                                            isStreaming = false
                                        )
                                    } else {
                                        m
                                    }
                                }
                            )
                        }
                    }

                    is AgyStreamEvent.RawStdout -> {
                        appendLog("STDOUT", event.line)
                        rawStdoutBuffer.appendLine(event.line)
                        if (streamedBuffer.isEmpty()) {
                            mutateAssistantMessage(updatedSession.id, assistantMsgId) { msg ->
                                msg.copy(
                                    text = rawStdoutBuffer.toString().trimEnd(),
                                    isStreaming = true
                                )
                            }
                        }
                    }

                    is AgyStreamEvent.Stderr -> {
                        appendLog("STDERR", event.line)
                        stderrBuffer.appendLine(event.line)
                    }

                    is AgyStreamEvent.ProcessFinished -> {
                        appendLog("EXIT", "code=${event.exitCode} err=${event.errorMessage.orEmpty()}")
                        mutateAssistantMessage(updatedSession.id, assistantMsgId) { msg ->
                            val fallbackText = when {
                                msg.text.isNotBlank() -> msg.text
                                rawStdoutBuffer.isNotBlank() -> rawStdoutBuffer.toString().trimEnd()
                                !event.errorMessage.isNullOrBlank() -> "Error: ${event.errorMessage}"
                                stderrBuffer.isNotBlank() -> "stderr (exit ${event.exitCode}):\n${stderrBuffer.toString().trimEnd()}"
                                else -> "(agy exited with code ${event.exitCode})"
                            }
                            val finalRole = if (
                                msg.text.isBlank() &&
                                rawStdoutBuffer.isBlank() &&
                                (event.exitCode != 0 || !event.errorMessage.isNullOrBlank())
                            ) {
                                MessageRole.SYSTEM_ERROR
                            } else {
                                msg.role
                            }
                            msg.copy(
                                role = finalRole,
                                text = fallbackText,
                                isStreaming = false
                            )
                        }
                        _uiState.update { it.copy(isRunning = false, activeRunId = null) }
                        persistSessions(_uiState.value.sessions)
                        refreshWorkspaceFiles(workdir)
                    }
                }
            }
        }
    }

    fun stopActiveRun() {
        val runId = _uiState.value.activeRunId
        val settings = _uiState.value.settings
        val workdir = _uiState.value.activeSession?.workdir ?: settings.defaultWorkdir

        viewModelScope.launch {
            if (settings.backend == ExecutionBackend.DIRECT_PROCESS && runId != null) {
                directEngine.cancel(settings, runId)
            } else {
                termuxStreamEngine.stopWithPkill(settings, workdir)
            }
            activeRunJob?.cancel()
            activeRunJob = null

            _uiState.update { current ->
                val updatedSessions = current.sessions.map { session ->
                    session.copy(
                        messages = session.messages.map { msg ->
                            if (msg.isStreaming) {
                                msg.copy(
                                    text = (msg.text + "\n\n[Остановлено пользователем (pkill -9 -f agy)]").trim(),
                                    isStreaming = false
                                )
                            } else {
                                msg
                            }
                        }
                    )
                }
                persistSessions(updatedSessions)
                current.copy(
                    isRunning = false,
                    activeRunId = null,
                    sessions = updatedSessions
                )
            }
            appendLog("STOP", "pkill -9 -f \"agy\"")
            refreshWorkspaceFiles(workdir)
        }
    }

    private fun engineFor(backend: ExecutionBackend): AgyExecutionEngine = when (backend) {
        ExecutionBackend.TERMUX_INTENT_STREAM -> termuxStreamEngine
        ExecutionBackend.TERMUX_PENDING_INTENT -> termuxPendingIntentEngine
        ExecutionBackend.DIRECT_PROCESS -> directEngine
    }

    private fun createNewSession(settings: AgySettings): ChatSession {
        return ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Новая задача AGY",
            workdir = settings.defaultWorkdir,
            mode = settings.defaultMode
        )
    }

    private fun mergeStep(existing: List<AgentStep>, incoming: AgentStep): List<AgentStep> {
        val idx = existing.indexOfFirst {
            it.stepIndex == incoming.stepIndex && it.stepType == incoming.stepType
        }
        return if (idx >= 0) {
            existing.toMutableList().apply {
                val prev = this[idx]
                this[idx] = incoming.copy(detail = incoming.detail.ifBlank { prev.detail })
            }
        } else {
            existing + incoming
        }
    }

    private fun updateSession(updated: ChatSession) {
        _uiState.update { state ->
            val list = state.sessions.map { if (it.id == updated.id) updated else it }
            state.copy(sessions = list)
        }
        persistSessions(_uiState.value.sessions)
    }

    private fun mutateSession(sessionId: String, transform: (ChatSession) -> ChatSession) {
        _uiState.update { state ->
            state.copy(
                sessions = state.sessions.map {
                    if (it.id == sessionId) transform(it) else it
                }
            )
        }
    }

    private fun mutateAssistantMessage(
        sessionId: String,
        messageId: String,
        transform: (ChatMessage) -> ChatMessage
    ) {
        mutateSession(sessionId) { session ->
            session.copy(
                updatedAtMs = System.currentTimeMillis(),
                messages = session.messages.map { msg ->
                    if (msg.id == messageId) transform(msg) else msg
                }
            )
        }
    }

    private fun appendLog(stream: String, text: String) {
        _uiState.update { state ->
            val next = (state.consoleLogs + ConsoleLogEntry(System.currentTimeMillis(), stream, text))
                .takeLast(300)
            state.copy(consoleLogs = next)
        }
    }

    private fun persistSessions(sessions: List<ChatSession>) {
        viewModelScope.launch {
            sessionRepo.saveAll(sessions)
        }
    }
}
