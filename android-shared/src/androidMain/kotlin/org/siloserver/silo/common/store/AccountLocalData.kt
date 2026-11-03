package org.siloserver.silo.common.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.siloserver.silo.common.profiles.ProfileLaunchPreferences
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import java.io.File

/**
 * Keeps the account-scoped preferences, settings caches and local JSON state
 * (multi-account spec §5.1, §5.2, §4.4) in line with the registry's accounts.
 * Room tables, downloads and push have their own passes.
 *
 * - **Adoption.** Values written before accounts existed belong to the
 *   server's account with `adoptsLegacyData`. Device-only data moves under
 *   that account's token (an existing value under the token wins), and the
 *   per-profile DataStore files, which were shared by every server, are copied
 *   into the account's own file. Recorded once per account in a ledger, offline.
 *   The settings caches (card presentation, seek intervals, spoilers, title art)
 *   are hashed and only mirror server settings, so they start empty for the
 *   account and refill on the next read.
 * - **Purge.** Anything carrying the token of an account no longer in
 *   [ServerRegistry.accounts] is deleted: that is a sign-out. Never while the
 *   registry failed to load, when its emptiness proves nothing.
 * - **Legacy cleanup.** Once no server can still hand its old data to an
 *   account, the pre-account copies of the shared files and caches are dropped.
 *
 * Observes the registry rather than running inside sign-out, like
 * `OrphanedServerDataPurger`: whatever ends an account, its data follows.
 */
class AccountLocalData(
    private val context: Context,
    private val registry: ServerRegistry,
    private val launchPreferences: ProfileLaunchPreferences? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val jsonState: ServerScopedJsonStatePurger = ServerScopedJsonStatePurger(context.filesDir),
) {
    private val lock = Any()
    private val ledger: SharedPreferences by lazy { prefs(LEDGER_PREFS) }
    private val dataStoreDir: File get() = File(context.filesDir, "datastore")

    /**
     * Adopts synchronously, so a store opened right after this returns reads
     * the adopted values, then purges and keeps following the registry on
     * [scope].
     */
    fun start(): Job {
        runCatching { synchronized(lock) { adoptPendingLocked() } }
            .onFailure { Log.w(TAG, "startup adoption failed", it) }
        launchPreferences?.trackActiveTile(registry, scope)
        return scope.launch {
            var startup = true
            combine(registry.accounts, registry.entries) { accounts, entries ->
                accounts.map { it.localAccountId to it.adoptsLegacyData }.toSet() to
                    entries.filter { it.legacyDataUnclaimed }.map { it.id }.toSet()
            }
                .distinctUntilChanged()
                .collect {
                    runCatching { reconcile(dropSettledLegacy = startup) }
                        .onFailure { Log.w(TAG, "account data pass failed", it) }
                    startup = false
                }
        }
    }

    /**
     * One pass: adopt, purge signed-out accounts and, when [dropSettledLegacy],
     * drop pre-account copies nothing can adopt any more. That last step runs
     * only at startup, before any store could hold a pre-account file open.
     */
    fun reconcile(dropSettledLegacy: Boolean = false) = synchronized(lock) {
        if (registry.stateLoadFailed) {
            Log.w(TAG, "registry state unreadable; skipping account data pass")
            return@synchronized
        }
        adoptPendingLocked()
        val live = registry.accounts.value.mapTo(mutableSetOf()) { it.localAccountId }
        PREFS_WITH_ACCOUNT_KEYS.forEach { purgeKeys(prefs(it), live) }
        purgeKeys(ledger, live)
        purgeDataStoreFiles(live)
        jsonState.deleteAccountsExcept(live)
        launchPreferences?.retainAccounts(live)
        if (dropSettledLegacy && nothingLeftToAdopt()) dropLegacy()
    }

    // ---- Adoption ----

    private fun adoptPendingLocked() {
        if (registry.stateLoadFailed) return
        val entries = registry.entries.value
        registry.accounts.value
            .filter { it.adoptsLegacyData && !isAdopted(it.localAccountId) }
            .forEach { account ->
                val server = entries.firstOrNull { it.id == account.serverId } ?: return@forEach
                adopt(server, account.localAccountId)
                ledger.edit().putBoolean(adoptedKey(account.localAccountId), true).commit()
                Log.i(TAG, "adopted pre-account local data")
            }
    }

    private fun adopt(server: ServerEntry, accountId: String) {
        val token = AccountScopedKeys.token(accountId)
        val serverId = server.id
        moveKeys(prefs(BROWSE_PREFS), "android.browsePrefs.$serverId.", "$token.")
        moveKeys(prefs(BROWSE_PREFS), "androidtv.browsePrefs.$serverId.", "$token.")
        moveKeys(prefs(HOME_SECTIONS_PREFS), "android.homeSections.v1.$serverId.", "$token.")
        moveKeys(prefs(ONBOARDING_PREFS), "done:$serverId:", "$token:")
        moveKeys(prefs(SERIES_AUTO_RESET_PREFS), "$serverId\u001f", "$token\u001f")
        copyLegacyDataStoreFiles(accountId)
        jsonState.adoptLegacy(serverId, accountId)
    }

    /**
     * Moves every key `<legacyPrefix><rest>` to `<legacyPrefix><insert><rest>`
     * unless the account already has it. Keys already carrying a token stay.
     */
    private fun moveKeys(prefs: SharedPreferences, legacyPrefix: String, insert: String) {
        val legacy = prefs.all.filterKeys { key ->
            key.startsWith(legacyPrefix) && !key.removePrefix(legacyPrefix).startsWith("@")
        }
        if (legacy.isEmpty()) return
        val editor = prefs.edit()
        legacy.forEach { (key, value) ->
            val target = legacyPrefix + insert + key.removePrefix(legacyPrefix)
            if (!prefs.contains(target)) editor.putAny(target, value)
            editor.remove(key)
        }
        editor.commit()
    }

    /**
     * Each per-profile DataStore file used to be shared by every server, so
     * it is copied (not moved): another server's adopting account needs it too.
     */
    private fun copyLegacyDataStoreFiles(accountId: String) {
        legacyDataStoreFiles().forEach { (base, profileHash, file) ->
            val target = File(dataStoreDir, AccountScopedKeys.profileFileName(base, accountId, profileHash) + DATASTORE_SUFFIX)
            if (target.exists()) return@forEach
            runCatching { file.copyTo(target) }
                .onFailure { Log.w(TAG, "could not adopt a settings file", it) }
        }
    }

    private fun isAdopted(accountId: String): Boolean =
        ledger.getBoolean(adoptedKey(accountId), false)

    private fun nothingLeftToAdopt(): Boolean =
        registry.entries.value.none { it.legacyDataUnclaimed } &&
            registry.accounts.value.filter { it.adoptsLegacyData }.all { isAdopted(it.localAccountId) }

    // ---- Purge ----

    private fun purgeKeys(prefs: SharedPreferences, live: Set<String>) {
        val gone = prefs.all.keys.filter { key -> AccountScopedKeys.accountIdsIn(key).any { it !in live } }
        if (gone.isEmpty()) return
        prefs.edit().apply { gone.forEach(::remove) }.apply()
    }

    private fun purgeDataStoreFiles(live: Set<String>) {
        dataStoreDir.listFiles().orEmpty()
            .filter { file ->
                DATASTORE_BASES.any { file.name.startsWith(it + "_@") } &&
                    AccountScopedKeys.accountIdsIn(file.name).any { it !in live }
            }
            .forEach { file -> file.delete() }
    }

    // ---- Legacy cleanup ----

    private fun dropLegacy() {
        legacyDataStoreFiles().forEach { (_, _, file) -> file.delete() }
        HASHED_CACHE_PREFS.forEach { name ->
            val prefs = prefs(name)
            val legacy = prefs.all.keys.filterNot(AccountScopedKeys::hasAccountToken)
            if (legacy.isNotEmpty()) prefs.edit().apply { legacy.forEach(::remove) }.apply()
        }
    }

    private data class LegacyFile(val base: String, val profileHash: String, val file: File)

    private fun legacyDataStoreFiles(): List<LegacyFile> =
        dataStoreDir.listFiles().orEmpty().mapNotNull { file ->
            DATASTORE_BASES.firstNotNullOfOrNull { base ->
                Regex("${Regex.escape(base)}_([0-9a-f]{16})${Regex.escape(DATASTORE_SUFFIX)}")
                    .matchEntire(file.name)
                    ?.let { LegacyFile(base, it.groupValues[1], file) }
            }
        }

    private fun prefs(name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun SharedPreferences.Editor.putAny(key: String, value: Any?) {
        @Suppress("UNCHECKED_CAST")
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value as Set<String>)
        }
    }

    private companion object {
        const val TAG = "AccountLocalData"
        const val LEDGER_PREFS = "silo_account_local_data"
        const val DATASTORE_SUFFIX = ".preferences_pb"

        const val BROWSE_PREFS = "browse_prefs"
        const val HOME_SECTIONS_PREFS = "home_section_preferences"
        const val ONBOARDING_PREFS = "onboarding_tour"
        const val SERIES_AUTO_RESET_PREFS = "series_track_auto_resets"

        /** Hashed caches of server settings; see each store's `PREFS_NAME`. */
        val HASHED_CACHE_PREFS = listOf(
            "silo_card_presentation",
            "silo_seek_intervals",
            "silo_episode_spoilers",
            "silo_title_art",
        )

        /** Every SharedPreferences file whose keys may carry an account token. */
        val PREFS_WITH_ACCOUNT_KEYS = listOf(
            BROWSE_PREFS,
            HOME_SECTIONS_PREFS,
            ONBOARDING_PREFS,
            SERIES_AUTO_RESET_PREFS,
        ) + HASHED_CACHE_PREFS

        /**
         * Per-(account, profile) DataStore files: `FILE_BASE` of
         * AndroidPlayerSettingsStore, AudiobookSettingsStore and the TV app's
         * TvHomeSectionPreferences, TvLibraryScopeStore, TvLibrarySelectionStore.
         */
        val DATASTORE_BASES = listOf(
            "silo_player_settings",
            "silo_audiobook_settings",
            "tv_home_sections",
            "tv_library_scope",
            "tv_library_selection",
        )

        fun adoptedKey(accountId: String) = "adopted.v1.${AccountScopedKeys.token(accountId)}"
    }
}
