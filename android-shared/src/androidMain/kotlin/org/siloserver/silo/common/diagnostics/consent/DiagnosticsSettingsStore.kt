package org.siloserver.silo.common.diagnostics.consent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device-scoped diagnostics flags. Deliberately not the per-profile pattern of
 * AndroidPlayerSettingsStore: debug logging is a property of this device, and
 * consent lives in [DiagnosticsConsentStore] keyed by server+account. Never
 * synced to the server.
 */
class DiagnosticsSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _debugLogging = MutableStateFlow(prefs.getBoolean(KEY_DEBUG_LOGGING, false))
    val debugLogging: StateFlow<Boolean> = _debugLogging.asStateFlow()

    fun setDebugLogging(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_LOGGING, enabled).apply()
        _debugLogging.value = enabled
    }

    private companion object {
        const val PREFS_NAME = "silo_diagnostics"
        const val KEY_DEBUG_LOGGING = "debug_logging_enabled"
    }
}
