package com.agy.nativeapp

import com.agy.nativeapp.model.AgyStreamEvent
import com.agy.nativeapp.parser.AgyStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgyStreamParserTest {

    @Test
    fun parsesInitStepAndResultEvents() {
        val initLine = """{"event":"init","conversation_id":"conv-123","model":"gemini-pro"}"""
        val init = AgyStreamParser.parseLine(initLine) as AgyStreamEvent.Init
        assertEquals("conv-123", init.conversationId)
        assertEquals("gemini-pro", init.model)

        val stepLine = """{"event":"step_update","step_update":{"conversation_id":"conv-123","step_index":4,"state":"ACTIVE","step_type":"agent_response","text_delta":"Hello "}}"""
        val step = AgyStreamParser.parseLine(stepLine) as AgyStreamEvent.StepUpdate
        assertEquals(4, step.stepIndex)
        assertEquals("ACTIVE", step.state)
        assertEquals("agent_response", step.stepType)
        assertEquals("Hello ", step.textDelta)

        val resultLine = """{"event":"result","status":"SUCCESS","response":"Hello world","conversation_id":"conv-123","duration_seconds":2.5,"num_turns":1,"usage":{"input_tokens":100,"output_tokens":20,"thinking_tokens":5,"cache_read_tokens":0,"total_tokens":120}}"""
        val result = AgyStreamParser.parseLine(resultLine) as AgyStreamEvent.Result
        assertEquals("SUCCESS", result.status)
        assertEquals("Hello world", result.response)
        assertEquals(2.5, result.durationSeconds ?: 0.0, 0.001)
        assertEquals(120, result.usage?.totalTokens)
    }

    @Test
    fun fallsBackToRawStdoutOnPlainText() {
        val event = AgyStreamParser.parseLine("Plain text output from agy")
        assertTrue(event is AgyStreamEvent.RawStdout)
        assertEquals("Plain text output from agy", (event as AgyStreamEvent.RawStdout).line)
    }
}
