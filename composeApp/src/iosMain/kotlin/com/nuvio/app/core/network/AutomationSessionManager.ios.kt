package com.nuvio.app.core.network

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.user.UserSession

internal actual fun automationReadOnlySessionManager(): SessionManager =
    object : SessionManager {
        private val delegate = SettingsSessionManager()

        override suspend fun saveSession(session: UserSession) = Unit

        override suspend fun loadSession(): UserSession? = delegate.loadSession()

        override suspend fun deleteSession() = Unit
    }
