package com.agy.nativeapp.engine

import com.agy.nativeapp.model.AgyMode
import com.agy.nativeapp.model.AgyOutputFormat
import com.agy.nativeapp.model.AgySettings

data class AgyRunRequest(
    val runId: String,
    val prompt: String,
    val workdir: String,
    val mode: AgyMode,
    val conversationId: String?,
    val continueLastSession: Boolean,
    val outputFormat: AgyOutputFormat,
    val addDirs: List<String>,
    val printTimeout: String
)

object AgyCommandBuilder {

    const val PROMPT_FILE_INSTRUCTION =
        "Read instructions from .agy_prompt.txt in current directory and execute all tasks completely."

    /**
     * Builds the argv list for invoking `agy` in headless/streaming mode.
     * Note: `-p` is placed last because it consumes the prompt argument immediately after it.
     */
    fun buildArgs(settings: AgySettings, request: AgyRunRequest): List<String> {
        val args = mutableListOf<String>()

        when (request.mode) {
            AgyMode.DEFAULT -> Unit
            AgyMode.ACCEPT_EDITS -> {
                args += "--mode"
                args += "accept-edits"
            }
            AgyMode.PLAN -> {
                args += "--mode"
                args += "plan"
            }
            AgyMode.SANDBOX -> {
                args += "--sandbox"
            }
            AgyMode.UNRESTRICTED -> {
                args += "--dangerously-skip-permissions"
            }
        }

        if (!request.conversationId.isNullOrBlank()) {
            args += "--conversation"
            args += request.conversationId
        } else if (request.continueLastSession) {
            args += "--continue"
        }

        request.addDirs
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { dir ->
                args += "--add-dir"
                args += dir
            }

        if (request.printTimeout.isNotBlank()) {
            args += "--print-timeout"
            args += request.printTimeout.trim()
        }

        args += "--output-format"
        args += request.outputFormat.cliValue

        args += "-p"
        args += request.prompt

        return args
    }

    fun buildDisplayCommand(settings: AgySettings, request: AgyRunRequest): String {
        val full = listOf(settings.agyBinaryPath) + buildArgs(settings, request)
        return full.joinToString(" ") { shellQuote(it) }
    }

    /**
     * Builds the exact one-shot bash script for Termux RUN_COMMAND:
     * 1. Changes into the selected workspace directory.
     * 2. Runs `agy -p "Read instructions from .agy_prompt.txt in current directory and execute all tasks completely." --dangerously-skip-permissions`
     *    redirecting combined output to `.agy_out.log`.
     * 3. Writes `.agy_done` with the process exit code when agy terminates.
     */
    fun buildPromptFileScript(
        settings: AgySettings,
        workdir: String
    ): String {
        val envSetup = parseExtraEnv(settings.extraEnvLines)
            .entries
            .joinToString("\n") { (k, v) -> "export ${k}=${shellQuote(v)}" }

        val agyBin = shellQuote(settings.agyBinaryPath)
        val instruction = shellQuote(PROMPT_FILE_INSTRUCTION)

        return buildString {
            appendLine("export PATH=\"/data/data/com.termux/files/usr/bin:\$PATH\"")
            appendLine("export HOME=\"/data/data/com.termux/files/home\"")
            if (envSetup.isNotBlank()) appendLine(envSetup)
            appendLine("cd ${shellQuote(workdir)} || { echo \"Cannot cd to workspace: ${workdir}\" > ${WorkspaceManager.OUTPUT_LOG_FILE_NAME}; echo 1 > ${WorkspaceManager.DONE_MARKER_FILE_NAME}; exit 1; }")
            appendLine("rm -f ${WorkspaceManager.DONE_MARKER_FILE_NAME}")
            appendLine(": > ${WorkspaceManager.OUTPUT_LOG_FILE_NAME}")
            appendLine("$agyBin -p $instruction --dangerously-skip-permissions > ${WorkspaceManager.OUTPUT_LOG_FILE_NAME} 2>&1")
            appendLine("EC=\$?")
            appendLine("echo \"\$EC\" > ${WorkspaceManager.DONE_MARKER_FILE_NAME}")
            appendLine("exit \"\$EC\"")
        }
    }

    fun buildTermuxStreamingScript(
        settings: AgySettings,
        request: AgyRunRequest,
        stdoutFile: String,
        stderrFile: String,
        pidFile: String,
        doneFile: String
    ): String {
        val envSetup = parseExtraEnv(settings.extraEnvLines)
            .entries
            .joinToString("\n") { (k, v) -> "export ${k}=${shellQuote(v)}" }

        val agyCmd = buildDisplayCommand(settings, request)

        return buildString {
            appendLine("export PATH=\"/data/data/com.termux/files/usr/bin:\$PATH\"")
            appendLine("export HOME=\"/data/data/com.termux/files/home\"")
            if (envSetup.isNotBlank()) appendLine(envSetup)
            appendLine("mkdir -p ${shellQuote(settings.streamDirPath)}")
            appendLine("cd ${shellQuote(request.workdir)} || exit 127")
            appendLine(": > ${shellQuote(stdoutFile)}")
            appendLine(": > ${shellQuote(stderrFile)}")
            appendLine("$agyCmd > ${shellQuote(stdoutFile)} 2> ${shellQuote(stderrFile)} &")
            appendLine("AGY_PID=\$!")
            appendLine("echo \"\$AGY_PID\" > ${shellQuote(pidFile)}")
            appendLine("wait \"\$AGY_PID\"")
            appendLine("EC=\$?")
            appendLine("rm -f ${shellQuote(pidFile)}")
            appendLine("echo \"\$EC\" > ${shellQuote(doneFile)}")
            appendLine("exit \"\$EC\"")
        }
    }

    fun parseExtraEnv(raw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                val key = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                    result[key] = value
                }
            }
        }
        return result
    }

    fun shellQuote(value: String): String {
        if (value.isEmpty()) return "''"
        val safe = value.all {
            it.isLetterOrDigit() || it in setOf('-', '_', '.', '/', ':', '=')
        }
        if (safe) return value
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
