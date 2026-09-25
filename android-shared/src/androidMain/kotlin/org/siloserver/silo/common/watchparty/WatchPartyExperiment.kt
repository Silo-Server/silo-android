package org.siloserver.silo.common.watchparty

import android.content.SharedPreferences
import org.siloserver.silo.model.feature.WatchPartyExposure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Settings → Experimental → Watch Party toggle. It is device-local (never
 * synced with the server's settings) and, until the user chooses, follows
 * [defaultEnabled]: on in debug builds, off in release builds. Turning it off
 * hides every entry point, and [onDisabled] leaves any active party.
 */
class WatchPartyExperiment(
    private val read: () -> Boolean?,
    private val write: (Boolean) -> Unit,
    private val defaultEnabled: Boolean,
    private val onDisabled: () -> Unit,
) : WatchPartyExposure {
    constructor(prefs: SharedPreferences, defaultEnabled: Boolean, onDisabled: () -> Unit) : this(
        read = { if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null },
        write = { value -> prefs.edit().putBoolean(KEY, value).apply() },
        defaultEnabled = defaultEnabled,
        onDisabled = onDisabled,
    )

    private val _enabled = MutableStateFlow(read() ?: defaultEnabled)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        write(value)
        val was = _enabled.value
        _enabled.value = value
        if (was && !value) onDisabled()
    }

    companion object {
        /** Preferences file for device-local experiments. */
        const val PREFS_NAME = "silo_experimental"
        private const val KEY = "watch_party"
    }
}
