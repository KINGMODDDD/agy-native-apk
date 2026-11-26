package com.agy.nativeapp.data

import android.content.Context
import com.agy.nativeapp.model.AgySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class SettingsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val settingsFile: File
        get() = File(context.filesDir, "agy_settings.json")

    suspend fun load(): AgySettings = withContext(Dispatchers.IO) {
        if (!settingsFile.exists()) return@withContext AgySettings()
        runCatching {
            json.decodeFromString<AgySettings>(settingsFile.readText())
        }.getOrElse { AgySettings() }
    }

    suspend fun save(settings: AgySettings) = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "agy_settings.json.tmp")
        tmp.writeText(json.encodeToString(settings))
        if (!tmp.renameTo(settingsFile)) {
            settingsFile.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
