package org.siloserver.silo.common.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import org.siloserver.silo.catalog.filter.CatalogFilterState
import org.siloserver.silo.network.ServerRegistry

/**
 * Persists browse filter + sort state per server, per profile, per library —
 * mirrors iOS `BrowsePrefsStore` (`ios.browsePrefs.<server>.<profile>.<lib>`,
 * here `<keyPrefix>.browsePrefs.…`). Nothing is persisted without an active
 * profile, so anonymous browsing never leaks into a profile's saved state.
 *
 * The phone and TV apps each pass their own [keyPrefix] (`android`,
 * `androidtv`), as Apple keeps `ios.` / `tv.` / `mac.` apart.
 *
 * Gated by a user-facing "Preserve sort & filters" toggle (default ON);
 * turning it off clears the saved state.
 */
class BrowsePrefsStore(
    context: Context,
    private val serverRegistry: ServerRegistry,
    private val keyPrefix: String = "android",
) {
    private val prefs = context.getSharedPreferences("browse_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun base(libraryId: Int?): String? {
        val serverId = serverRegistry.activeServerId.value ?: "default"
        val profileId = serverRegistry.activeEntry.value?.profileId
            ?.takeIf { it.isNotBlank() } ?: return null
        val lib = libraryId?.toString() ?: "all"
        return "$keyPrefix.browsePrefs.$serverId.$profileId.$lib"
    }

    fun savedState(libraryId: Int?): CatalogFilterState? {
        if (!preserveEnabled(libraryId)) return null
        val key = base(libraryId) ?: return null
        val raw = prefs.getString("$key.state", null) ?: return null
        return runCatching { json.decodeFromString<CatalogFilterState>(raw) }.getOrNull()
    }

    fun saveState(libraryId: Int?, state: CatalogFilterState) {
        if (!preserveEnabled(libraryId)) return
        val key = base(libraryId) ?: return
        prefs.edit { putString("$key.state", json.encodeToString(state)) }
    }

    /** Default ON when the key is absent (iOS parity). */
    fun preserveEnabled(libraryId: Int?): Boolean {
        val key = base(libraryId) ?: return false
        return prefs.getBoolean("$key.preserve", true)
    }

    fun setPreserveEnabled(libraryId: Int?, enabled: Boolean) {
        val key = base(libraryId) ?: return
        prefs.edit {
            putBoolean("$key.preserve", enabled)
            if (!enabled) remove("$key.state")
        }
    }
}
