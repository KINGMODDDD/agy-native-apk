package com.agy.nativeapp.model

sealed interface AgyStreamEvent {
    data class Init(
        val conversationId: String?,
        val model: String?
    ) : AgyStreamEvent

    data class StepUpdate(
        val conversationId: String?,
        val stepIndex: Int,
        val state: String,
        val stepType: String,
        val textDelta: String?,
        val detail: String?
    ) : AgyStreamEvent

    data class Result(
        val status: String,
        val response: String,
        val conversationId: String?,
        val durationSeconds: Double?,
        val numTurns: Int?,
        val usage: TokenUsage?
    ) : AgyStreamEvent

    data class RawStdout(val line: String) : AgyStreamEvent

    data class Stderr(val line: String) : AgyStreamEvent

    data class ProcessFinished(
        val exitCode: Int,
        val errorMessage: String? = null
    ) : AgyStreamEvent
}
