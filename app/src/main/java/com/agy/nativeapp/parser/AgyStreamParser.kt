package com.agy.nativeapp.parser

import com.agy.nativeapp.model.AgyStreamEvent
import com.agy.nativeapp.model.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses lines emitted by `agy --output-format stream-json`, `json`, or `text`.
 * Tolerates minor schema variations and non-JSON terminal output without throwing.
 */
object AgyStreamParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseLine(rawLine: String): AgyStreamEvent? {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return null

        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return AgyStreamEvent.RawStdout(rawLine)
        }

        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return AgyStreamEvent.RawStdout(rawLine)

        val eventType = obj.string("event") ?: obj.string("type")
        return when (eventType) {
            "init" -> AgyStreamEvent.Init(
                conversationId = obj.string("conversation_id") ?: obj.string("conversationId"),
                model = obj.string("model")
            )

            "step_update" -> parseStepUpdate(obj)

            "result" -> parseResult(obj)

            else -> {
                // Support single-shot `--output-format json` envelope that omits `event`
                if (obj.containsKey("response") || obj.containsKey("structured_output")) {
                    parseResult(obj)
                } else {
                    AgyStreamEvent.RawStdout(rawLine)
                }
            }
        }
    }

    fun parseMultiLine(text: String): List<AgyStreamEvent> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence()
            .mapNotNull { parseLine(it) }
            .toList()
    }

    private fun parseStepUpdate(root: JsonObject): AgyStreamEvent.StepUpdate {
        val payload = root["step_update"]?.let {
            runCatching { it.jsonObject }.getOrNull()
        } ?: root

        val conversationId = payload.string("conversation_id")
            ?: root.string("conversation_id")
        val stepIndex = payload.int("step_index") ?: 0
        val state = payload.string("state") ?: "ACTIVE"
        val stepType = payload.string("step_type") ?: payload.string("type") ?: "step"
        val textDelta = payload.string("text_delta") ?: payload.string("delta")

        val detail = buildString {
            payload.string("tool_name")?.let { append(it) }
            payload.string("summary")?.let {
                if (isNotEmpty()) append(": ")
                append(it)
            }
            payload.string("command")?.let {
                if (isNotEmpty()) append(" — ")
                append(it)
            }
            payload.string("file_path")?.let {
                if (isNotEmpty()) append(" — ")
                append(it)
            }
        }.ifBlank { null }

        return AgyStreamEvent.StepUpdate(
            conversationId = conversationId,
            stepIndex = stepIndex,
            state = state,
            stepType = stepType,
            textDelta = textDelta,
            detail = detail
        )
    }

    private fun parseResult(root: JsonObject): AgyStreamEvent.Result {
        val usageObj = root["usage"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val usage = usageObj?.let {
            TokenUsage(
                inputTokens = it.int("input_tokens") ?: 0,
                outputTokens = it.int("output_tokens") ?: 0,
                thinkingTokens = it.int("thinking_tokens") ?: 0,
                cacheReadTokens = it.int("cache_read_tokens") ?: 0,
                totalTokens = it.int("total_tokens") ?: 0
            )
        }

        val responseText = root.string("response")
            ?: root["structured_output"]?.toString()
            ?: ""

        return AgyStreamEvent.Result(
            status = root.string("status") ?: "SUCCESS",
            response = responseText,
            conversationId = root.string("conversation_id") ?: root.string("conversationId"),
            durationSeconds = root.double("duration_seconds"),
            numTurns = root.int("num_turns"),
            usage = usage
        )
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()

    private fun JsonObject.double(key: String): Double? =
        runCatching { this[key]?.jsonPrimitive?.doubleOrNull }.getOrNull()
}
