package com.agy.nativeapp.data

import android.content.Context
import com.agy.nativeapp.model.ChatSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class SessionRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private val sessionsFile: File
        get() = File(context.filesDir, "agy_sessions.json")

    suspend fun loadAll(): List<ChatSession> = withContext(Dispatchers.IO) {
        if (!sessionsFile.exists()) return@withContext emptyList()
        runCatching {
            json.decodeFromString<List<ChatSession>>(sessionsFile.readText())
        }.getOrElse { emptyList() }
    }

    suspend fun saveAll(sessions: List<ChatSession>) = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "agy_sessions.json.tmp")
        tmp.writeText(json.encodeToString(sessions))
        if (!tmp.renameTo(sessionsFile)) {
            sessionsFile.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
