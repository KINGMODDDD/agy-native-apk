package com.agy.nativeapp.engine

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.agy.nativeapp.model.AgySettings
import com.agy.nativeapp.model.AgyStreamEvent
import com.agy.nativeapp.parser.AgyStreamParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface PromptRunEvent {
    data class LogSnapshot(val content: String) : PromptRunEvent
    data class Completed(val exitCode: Int, val finalLog: String, val errorMessage: String? = null) : PromptRunEvent
}

class TermuxIntentEngine(
    private val context: Context,
    private val streamingViaSharedFiles: Boolean
) : AgyExecutionEngine {

    private val activeRunFiles = ConcurrentHashMap<String, File>()

    /**
     * Exact workflow requested:
     * 1. Writes user prompt to `<workdir>/.agy_prompt.txt`.
     * 2. Sends one-shot Termux `com.termux.RUN_COMMAND` running:
     *    `agy -p "Read instructions from .agy_prompt.txt in current directory and execute all tasks completely." --dangerously-skip-permissions`
     *    writing to `.agy_out.log` and creating `.agy_done` on completion.
     * 3. Polls `.agy_out.log` every 500 ms until `.agy_done` is created.
     */
    fun executePromptFileWorkflow(
        settings: AgySettings,
        workdir: String,
        prompt: String
    ): Flow<PromptRunEvent> = channelFlow {
        if (!TermuxDiagnostics.isTermuxInstalled(context)) {
            send(
                PromptRunEvent.Completed(
                    exitCode = -1,
                    finalLog = "",
                    errorMessage = "Termux (com.termux) не установлен на устройстве."
                )
            )
            return@channelFlow
        }

        if (!TermuxDiagnostics.hasRunCommandPermission(context)) {
            send(
                PromptRunEvent.Completed(
                    exitCode = -1,
                    finalLog = "",
                    errorMessage = "Не выдано разрешение com.termux.permission.RUN_COMMAND. Откройте Настройки."
                )
            )
            return@channelFlow
        }

        val workspaceDir = File(workdir)
        if (!workspaceDir.exists() && !workspaceDir.mkdirs()) {
            send(
                PromptRunEvent.Completed(
                    exitCode = -1,
                    finalLog = "",
                    errorMessage = "Нет доступа к папке $workdir. Выдайте All Files Access в Настройках."
                )
            )
            return@channelFlow
        }

        val promptFile = File(workspaceDir, WorkspaceManager.PROMPT_FILE_NAME)
        val outLogFile = File(workspaceDir, WorkspaceManager.OUTPUT_LOG_FILE_NAME)
        val doneFile = File(workspaceDir, WorkspaceManager.DONE_MARKER_FILE_NAME)

        val prepError = runCatching {
            promptFile.writeText(prompt, Charsets.UTF_8)
            outLogFile.writeText("", Charsets.UTF_8)
            if (doneFile.exists()) doneFile.delete()
        }.exceptionOrNull()

        if (prepError != null) {
            send(
                PromptRunEvent.Completed(
                    exitCode = -1,
                    finalLog = "",
                    errorMessage = "Не удалось записать .agy_prompt.txt: ${prepError.message}"
                )
            )
            return@channelFlow
        }

        val script = AgyCommandBuilder.buildPromptFileScript(settings, workdir)
        val (execId, resultDeferred) = TermuxResultReceiver.registerExecution()

        try {
            launchTermuxService(
                commandPath = settings.bashBinaryPath,
                arguments = arrayOf("-c", script),
                workdir = workdir,
                executionId = execId
            )
        } catch (e: Exception) {
            TermuxResultReceiver.unregisterExecution(execId)
            send(
                PromptRunEvent.Completed(
                    exitCode = -1,
                    finalLog = "",
                    errorMessage = "Ошибка запуска Termux RunCommandService: ${e.message}"
                )
            )
            return@channelFlow
        }

        var lastSnapshot = ""
        try {
            while (true) {
                val currentLog = runCatching {
                    if (outLogFile.exists()) outLogFile.readText(Charsets.UTF_8) else ""
                }.getOrDefault(lastSnapshot)

                if (currentLog != lastSnapshot) {
                    lastSnapshot = currentLog
                    send(PromptRunEvent.LogSnapshot(currentLog))
                }

                if (doneFile.exists()) {
                    delay(120)
                    val finalLog = runCatching {
                        if (outLogFile.exists()) outLogFile.readText(Charsets.UTF_8) else lastSnapshot
                    }.getOrDefault(lastSnapshot)
                    val exitCode = runCatching {
                        doneFile.readText(Charsets.UTF_8).trim().toIntOrNull() ?: 0
                    }.getOrDefault(0)
                    send(PromptRunEvent.Completed(exitCode = exitCode, finalLog = finalLog))
                    break
                }

                if (resultDeferred.isCompleted) {
                    val termuxResult = resultDeferred.await()
                    if (!termuxResult.internalErrorMessage.isNullOrBlank()) {
                        send(
                            PromptRunEvent.Completed(
                                exitCode = termuxResult.exitCode,
                                finalLog = lastSnapshot,
                                errorMessage = termuxResult.internalErrorMessage
                            )
                        )
                        break
                    }
                    // Give filesystem flush a brief moment if Termux just finished
                    delay(200)
                    if (!doneFile.exists()) {
                        val finalLog = runCatching {
                            if (outLogFile.exists()) outLogFile.readText(Charsets.UTF_8) else lastSnapshot
                        }.getOrDefault(lastSnapshot)
                        val combined = finalLog.ifBlank {
                            listOf(termuxResult.stdout, termuxResult.stderr)
                                .filter { it.isNotBlank() }
                                .joinToString("\n")
                        }
                        send(
                            PromptRunEvent.Completed(
                                exitCode = termuxResult.exitCode,
                                finalLog = combined
                            )
                        )
                        break
                    }
                }

                delay(500)
            }
        } finally {
            TermuxResultReceiver.unregisterExecution(execId)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Sends a one-shot Termux command `pkill -9 -f "agy"` to immediately halt execution.
     */
    fun stopWithPkill(settings: AgySettings, workdir: String) {
        val (execId, _) = TermuxResultReceiver.registerExecution()
        runCatching {
            launchTermuxService(
                commandPath = settings.bashBinaryPath,
                arguments = arrayOf("-c", "pkill -9 -f \"agy\""),
                workdir = workdir,
                executionId = execId
            )
        }
    }

    override fun execute(settings: AgySettings, request: AgyRunRequest): Flow<AgyStreamEvent> =
        channelFlow {
            if (!TermuxDiagnostics.isTermuxInstalled(context)) {
                send(
                    AgyStreamEvent.ProcessFinished(
                        exitCode = -1,
                        errorMessage = "Termux (com.termux) is not installed on this device."
                    )
                )
                return@channelFlow
            }

            if (!TermuxDiagnostics.hasRunCommandPermission(context)) {
                send(
                    AgyStreamEvent.ProcessFinished(
                        exitCode = -1,
                        errorMessage = "Permission com.termux.permission.RUN_COMMAND is not granted."
                    )
                )
                return@channelFlow
            }

            val (execId, resultDeferred) = TermuxResultReceiver.registerExecution()
            val agyCmd = AgyCommandBuilder.buildDisplayCommand(settings, request)
            val inlineScript = buildString {
                append("export PATH=\"/data/data/com.termux/files/usr/bin:\$PATH\"; ")
                append("export HOME=\"/data/data/com.termux/files/home\"; ")
                append("cd ${AgyCommandBuilder.shellQuote(request.workdir)} || exit 127; ")
                append(agyCmd)
            }

            try {
                launchTermuxService(
                    commandPath = settings.bashBinaryPath,
                    arguments = arrayOf("-c", inlineScript),
                    workdir = request.workdir,
                    executionId = execId
                )
                val result = resultDeferred.await()
                if (result.stdout.isNotBlank()) {
                    AgyStreamParser.parseMultiLine(result.stdout).forEach { send(it) }
                }
                if (result.stderr.isNotBlank()) {
                    result.stderr.lineSequence().forEach { line ->
                        if (line.isNotBlank()) send(AgyStreamEvent.Stderr(line))
                    }
                }
                send(
                    AgyStreamEvent.ProcessFinished(
                        exitCode = result.exitCode,
                        errorMessage = result.internalErrorMessage
                    )
                )
            } catch (e: Exception) {
                TermuxResultReceiver.unregisterExecution(execId)
                send(
                    AgyStreamEvent.ProcessFinished(
                        exitCode = -1,
                        errorMessage = e.message ?: "Termux PendingIntent execution failed"
                    )
                )
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun cancel(settings: AgySettings, runId: String) {
        stopWithPkill(settings, settings.defaultWorkdir)
        activeRunFiles.remove(runId)
    }

    suspend fun runQuickCommand(
        settings: AgySettings,
        command: String,
        timeoutMs: Long = 10_000L
    ): TermuxCommandResult {
        val (execId, deferred) = TermuxResultReceiver.registerExecution()
        return try {
            launchTermuxService(
                commandPath = settings.bashBinaryPath,
                arguments = arrayOf(
                    "-c",
                    "export PATH=\"/data/data/com.termux/files/usr/bin:\$PATH\"; export HOME=\"/data/data/com.termux/files/home\"; $command"
                ),
                workdir = settings.defaultWorkdir,
                executionId = execId
            )
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: TermuxCommandResult(
                    stdout = "",
                    stderr = "",
                    exitCode = -1,
                    internalErrorCode = -1,
                    internalErrorMessage = "Timed out waiting for Termux. Check allow-external-apps=true in ~/.termux/termux.properties."
                )
        } catch (e: Exception) {
            TermuxCommandResult(
                stdout = "",
                stderr = "",
                exitCode = -1,
                internalErrorCode = -1,
                internalErrorMessage = e.message
            )
        } finally {
            TermuxResultReceiver.unregisterExecution(execId)
        }
    }

    private fun launchTermuxService(
        commandPath: String,
        arguments: Array<String>,
        workdir: String,
        executionId: Int
    ) {
        val receiverIntent = Intent(context, TermuxResultReceiver::class.java).apply {
            putExtra(TermuxResultReceiver.EXTRA_EXECUTION_ID, executionId)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_ONE_SHOT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            executionId,
            receiverIntent,
            flags
        )

        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_COMMAND_PATH, commandPath)
            putExtra(EXTRA_ARGUMENTS, arguments)
            putExtra(EXTRA_WORKDIR, workdir)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
        }

        context.startService(intent)
    }

    companion object {
        private const val TERMUX_PACKAGE = "com.termux"
        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    }
}
