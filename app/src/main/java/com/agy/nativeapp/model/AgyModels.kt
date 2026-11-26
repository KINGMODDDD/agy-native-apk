package com.agy.nativeapp.model

import com.agy.nativeapp.engine.WorkspaceManager
import kotlinx.serialization.Serializable

@Serializable
enum class ExecutionBackend(
    val displayName: String,
    val shortLabel: String,
    val description: String
) {
    TERMUX_INTENT_STREAM(
        displayName = "Termux Direct (.agy_prompt.txt + .agy_out.log)",
        shortLabel = "Termux Direct",
        description = "Writes .agy_prompt.txt into the workspace, runs agy via com.termux.RUN_COMMAND, and polls .agy_out.log every 500ms until .agy_done appears."
    ),
    TERMUX_PENDING_INTENT(
        displayName = "Termux PendingIntent Only",
        shortLabel = "Termux Intent",
        description = "Runs agy via Termux RunCommandService and receives stdout/stderr directly in a PendingIntent result bundle."
    ),
    DIRECT_PROCESS(
        displayName = "Direct ProcessBuilder",
        shortLabel = "Direct Exec",
        description = "Spawns the agy binary directly via java.lang.ProcessBuilder (for custom binary paths or rooted devices)."
    )
}

@Serializable
enum class AgyMode(
    val displayName: String,
    val cliDescription: String
) {
    DEFAULT("Default", "Standard confirmation policy"),
    ACCEPT_EDITS("Accept Edits", "--mode accept-edits"),
    PLAN("Plan", "--mode plan"),
    SANDBOX("Sandbox", "--sandbox"),
    UNRESTRICTED("Unrestricted", "--dangerously-skip-permissions")
}

@Serializable
enum class AgyOutputFormat(val cliValue: String) {
    STREAM_JSON("stream-json"),
    JSON("json"),
    TEXT("text")
}

@Serializable
data class AgySettings(
    val backend: ExecutionBackend = ExecutionBackend.TERMUX_INTENT_STREAM,
    val agyBinaryPath: String = "/data/data/com.termux/files/usr/bin/agy",
    val bashBinaryPath: String = "/data/data/com.termux/files/usr/bin/bash",
    val defaultWorkdir: String = "${WorkspaceManager.DEFAULT_PROJECTS_ROOT}/default",
    val streamDirPath: String = "/storage/emulated/0/Documents/AGYNative/streams",
    val extraAddDirs: List<String> = emptyList(),
    val printTimeout: String = "5m",
    val defaultMode: AgyMode = AgyMode.UNRESTRICTED,
    val outputFormat: AgyOutputFormat = AgyOutputFormat.TEXT,
    val useRootForDirectProcess: Boolean = false,
    val extraEnvLines: String = ""
)

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM_ERROR
}

@Serializable
data class AgentStep(
    val stepIndex: Int,
    val stepType: String,
    val state: String,
    val detail: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

@Serializable
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val thinkingTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val totalTokens: Int = 0
)

@Serializable
data class WorkspaceFileItem(
    val name: String,
    val relativePath: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

@Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val steps: List<AgentStep> = emptyList(),
    val terminalLog: String = "",
    val createdFiles: List<WorkspaceFileItem> = emptyList(),
    val usage: TokenUsage? = null,
    val durationSeconds: Double? = null,
    val numTurns: Int? = null,
    val isStreaming: Boolean = false,
    val commandPreview: String? = null
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val conversationId: String? = null,
    val modelName: String? = null,
    val workdir: String,
    val mode: AgyMode = AgyMode.UNRESTRICTED,
    val continueLastSession: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class SlashCommandItem(
    val command: String,
    val description: String,
    val isLocalOnly: Boolean = false
)

object AgySlashCommands {
    val all = listOf(
        SlashCommandItem("/plan", "Enter step-by-step planning mode before editing"),
        SlashCommandItem("/goal", "Run autonomously until the goal is achieved"),
        SlashCommandItem("/fast", "Direct fast execution for small tasks"),
        SlashCommandItem("/grill-me", "Ask clarifying questions to align on design"),
        SlashCommandItem("/codesearch", "Search the codebase"),
        SlashCommandItem("/context", "Inspect context window usage"),
        SlashCommandItem("/learn", "Persist fixes as workspace rules/skills"),
        SlashCommandItem("/skills", "List available workspace and global skills"),
        SlashCommandItem("/mcp", "Inspect configured MCP integrations"),
        SlashCommandItem("/help", "Show available agy slash commands"),
        SlashCommandItem("/new", "Start a fresh local conversation thread", isLocalOnly = true),
        SlashCommandItem("/stop", "Cancel the currently running agy task", isLocalOnly = true)
    )
}
