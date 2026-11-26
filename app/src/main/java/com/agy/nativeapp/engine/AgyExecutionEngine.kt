package com.agy.nativeapp.engine

import com.agy.nativeapp.model.AgySettings
import com.agy.nativeapp.model.AgyStreamEvent
import kotlinx.coroutines.flow.Flow

interface AgyExecutionEngine {
    fun execute(settings: AgySettings, request: AgyRunRequest): Flow<AgyStreamEvent>
    suspend fun cancel(settings: AgySettings, runId: String)
}
