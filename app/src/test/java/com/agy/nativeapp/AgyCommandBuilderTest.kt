package com.agy.nativeapp

import com.agy.nativeapp.engine.AgyCommandBuilder
import com.agy.nativeapp.engine.AgyRunRequest
import com.agy.nativeapp.model.AgyMode
import com.agy.nativeapp.model.AgyOutputFormat
import com.agy.nativeapp.model.AgySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgyCommandBuilderTest {

    @Test
    fun placesPromptFlagLastAndIncludesModeAndConversation() {
        val settings = AgySettings(agyBinaryPath = "/data/data/com.termux/files/usr/bin/agy")
        val request = AgyRunRequest(
            runId = "run-1",
            prompt = "Fix 'quote' and \$var",
            workdir = "/storage/emulated/0/AGYProjects/demo",
            mode = AgyMode.PLAN,
            conversationId = "uuid-abc",
            continueLastSession = true,
            outputFormat = AgyOutputFormat.STREAM_JSON,
            addDirs = listOf("/sdcard/docs"),
            printTimeout = "3m"
        )

        val args = AgyCommandBuilder.buildArgs(settings, request)
        assertEquals(
            listOf(
                "--mode", "plan",
                "--conversation", "uuid-abc",
                "--add-dir", "/sdcard/docs",
                "--print-timeout", "3m",
                "--output-format", "stream-json",
                "-p", "Fix 'quote' and \$var"
            ),
            args
        )
    }

    @Test
    fun buildPromptFileScriptUsesAgyPromptTxtAndDoneMarker() {
        val settings = AgySettings(agyBinaryPath = "/data/data/com.termux/files/usr/bin/agy")
        val script = AgyCommandBuilder.buildPromptFileScript(
            settings = settings,
            workdir = "/storage/emulated/0/AGYProjects/My Site"
        )
        assertTrue(script.contains("cd '/storage/emulated/0/AGYProjects/My Site'"))
        assertTrue(script.contains("--dangerously-skip-permissions > .agy_out.log 2>&1"))
        assertTrue(script.contains(".agy_prompt.txt"))
        assertTrue(script.contains(".agy_done"))
    }

    @Test
    fun shellQuoteEscapesSingleQuotesSafely() {
        val escaped = AgyCommandBuilder.shellQuote("don't break \$HOME")
        assertEquals("'don'\"'\"'t break \$HOME'", escaped)
        assertTrue(escaped.startsWith("'"))
    }
}
