package org.siloserver.silo.watchtogether

import android.content.SharedPreferences

/**
 * Keeps the recent party in the app's encrypted preferences (see
 * `createSecureSharedPrefs`). The value holds a room code and display
 * metadata, never a room or join token.
 */
class SharedPreferencesRecentWatchPartyStorage(
    private val prefs: SharedPreferences,
) : RecentWatchPartyStorage {
    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(value: String?) {
        prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.apply()
    }

    private companion object {
        const val KEY = "watch_party.recent"
    }
}
