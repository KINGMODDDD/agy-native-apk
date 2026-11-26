package com.agy.nativeapp

import android.app.Application
import com.agy.nativeapp.data.SessionRepository
import com.agy.nativeapp.data.SettingsRepository

class AgyApplication : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var sessionRepository: SessionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        sessionRepository = SessionRepository(this)
    }
}
