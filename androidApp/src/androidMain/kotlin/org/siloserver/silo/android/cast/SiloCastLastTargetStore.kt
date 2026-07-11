package org.siloserver.silo.android.cast

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The last TV the user deliberately controlled, persisted so a later app
 * launch can silently re-attach while that TV is still playing (mirrors
 * silo-apple's `silocontrol.lastTarget` UserDefaults entry). Host/port are
 * only a hint — auto-resume re-discovers the TV over mDNS before connecting.
 */
@Serializable
data class SiloCastPersistedTarget(
    val deviceId: String,
    val name: String,
    val serverId: String? = null,
)

interface SiloCastLastTargetStore {
    fun save(target: SiloCastPersistedTarget)

    fun load(): SiloCastPersistedTarget?

    fun clear()
}

class SharedPrefsSiloCastLastTargetStore(context: Context) : SiloCastLastTargetStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun save(target: SiloCastPersistedTarget) {
        prefs.edit().putString(KEY, json.encodeToString(SiloCastPersistedTarget.serializer(), target)).apply()
    }

    override fun load(): SiloCastPersistedTarget? =
        prefs.getString(KEY, null)?.let { raw ->
            runCatching { json.decodeFromString(SiloCastPersistedTarget.serializer(), raw) }.getOrNull()
        }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS_NAME = "silocast"
        const val KEY = "last_target"
    }
}
