package org.siloserver.silo.common.downloads

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.dao.ServerPurgeDao
import org.siloserver.silo.common.player.ActivePlaybackFile
import org.siloserver.silo.common.store.ServerScopedJsonStatePurger
import org.siloserver.silo.network.ServerRegistry

/**
 * Deletes the on-disk files and Room rows of servers that are no longer in the
 * registry, and of accounts that are no longer saved (signing out of an
 * account deletes its downloads, state and caches), and hands data written
 * before accounts existed to the account that adopts it.
 *
 * Removing a server wiped four token keys and the registry entry, and nothing
 * else. So "remove this server to reclaim space" reclaimed nothing in-app (the
 * bytes stayed until the user found them in the system Files app), and because
 * `idFor()` is base64 of the normalised URL, re-adding the same server
 * resurrected its Downloads list, resume positions, cached home and catalog
 * rows, and any pending outbox ops.
 *
 * Deliberately an observer rather than a step inside `ServerRegistry.remove`:
 *
 *  - `remove` runs inside `identityTransitions.changing { mutex.withLock { … } }`
 *    on Main. Putting a multi-gigabyte filesystem walk there would serialise
 *    every identity mutation behind it.
 *  - `AndroidServerRegistry` lives in `shared` and has no Room access at all.
 *  - Orphans are *derived* — rows present with no matching registry entry —
 *    so this is idempotent, safe to re-run at startup, and cleans installs that
 *    removed servers before any of this existed.
 *
 * Accounts: rows and files carry the saved account
 * ([org.siloserver.silo.model.server.AccountEntry.localAccountId]); `''` marks
 * data written before accounts existed. An account id that is no longer in
 * `registry.accounts` is a sign-out, so its data goes ([purgeAccount]); `''`
 * data of a still-saved server stays (old sign-outs kept data) until the
 * server is removed or an account adopts it. Adoption ([adoptLegacyData]) runs
 * before each purge pass, offline and idempotently, for every account with
 * `adoptsLegacyData`.
 */
class OrphanedServerDataPurger(
    private val registry: ServerRegistry,
    private val purgeDao: ServerPurgeDao,
    private val storage: DownloadStorage,
    private val cancelDownload: suspend (recordId: String) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val activePlaybackFileId: () -> Int? = { ActivePlaybackFile.fileId.value },
    private val purgeRows: suspend (String) -> Unit = purgeDao::deleteAllRowsForServer,
    private val deleteDownloadStorage: suspend (String) -> Unit = {
        storage.deleteAllForServer(it)
        Unit
    },
    private val jsonStatePurger: ServerScopedJsonStatePurger? = null,
    /**
     * Finishes the legacy `.record.json` import before adoption. The import
     * writes pre-account (`''`) rows, so a pass that adopted before it ran
     * would leave those downloads unadopted (and unlisted) until next launch.
     */
    private val awaitLegacyImport: suspend () -> Unit = {},
) {

    private val runLock = Mutex()

    private data class RegistryShape(val servers: Set<String>, val accounts: Set<String>, val adopters: Set<Pair<String, String>>)

    private fun shape() = RegistryShape(
        servers = registry.entries.value.map { it.id }.toSet(),
        accounts = registry.accounts.value.map { it.localAccountId }.toSet(),
        adopters = registry.accounts.value.filter { it.adoptsLegacyData }.map { it.serverId to it.localAccountId }.toSet(),
    )

    fun start(): Job = scope.launch {
        var previous = shape()
        jsonStatePurger?.allowRegistered(previous.servers)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            // Subscribe before the startup filesystem scan. A removal during
            // that scan is then buffered instead of falling between a snapshot
            // and a later collector subscription.
            combine(registry.entries, registry.accounts) { _, _ -> shape() }
                .distinctUntilChanged()
                .collect { next ->
                    val lostServers = previous.servers - next.servers
                    val lostAccounts = previous.accounts - next.accounts
                    val newAdopters = next.adopters - previous.adopters
                    previous = next
                    jsonStatePurger?.allowRegistered(next.servers)
                    if (lostServers.isNotEmpty() || lostAccounts.isNotEmpty() || newAdopters.isNotEmpty()) {
                        runCatching { purgeOnce() }
                            .onFailure { Log.w(TAG, "registry-change purge failed", it) }
                    }
                }
        }
        try {
            runCatching { purgeOnce() }
                .onFailure { Log.w(TAG, "startup purge failed", it) }
            AccountDataAdoption.markFirstPassDone()
            observer.join()
        } finally {
            AccountDataAdoption.markFirstPassDone()
            observer.cancel()
        }
    }

    /**
     * Purges every orphan once. Returns the ids fully purged.
     *
     * Serialised against itself: startup and a removal can fire together, and
     * two passes deleting the same tree would have one of them see files vanish
     * mid-walk.
     */
    suspend fun purgeOnce(): Set<String> = runLock.withLock {
        withContext(io) {
            // Orphan-ness is derived purely from registry absence, so a registry
            // that failed to load presents every server as an orphan and this
            // becomes a device wipe. Absence is only evidence of removal when
            // the registry actually knows what it holds.
            if (registry.stateLoadFailed) {
                Log.w(TAG, "registry state unreadable; skipping purge")
                AccountDataAdoption.markFirstPassDone()
                return@withContext emptySet()
            }
            val registered = registry.entries.value.map { it.id }.toSet()
            runCatching { awaitLegacyImport() }
                .onFailure { Log.w(TAG, "legacy download import failed before adoption", it) }
            adoptLegacyData(registered)
            AccountDataAdoption.markFirstPassDone()
            val orphans = purgeDao.knownServerIds().toSet() - registered
            val purged = mutableSetOf<String>()
            orphans.forEach { serverId ->
                // Once destructive work starts, complete the filesystem/Room pair
                // even if the observing app scope is cancelled. Otherwise a
                // teardown between those halves can strand rows for a server whose
                // one removal-triggered pass has already been consumed.
                if (withContext(NonCancellable) { purgeServer(serverId) }) {
                    purged += serverId
                }
            }
            // Signed-out accounts of servers that are still saved (a removed
            // server's accounts went with it above). The registry is read after
            // the rows: an account is saved before it writes anything, so one
            // signed in meanwhile is never mistaken for a signed-out one.
            val known = purgeDao.knownAccounts()
            val savedAccounts = registry.accounts.value.map { it.localAccountId }.toSet()
            known
                .filter { it.serverId in registered && it.accountId !in savedAccounts }
                .forEach { orphan ->
                    withContext(NonCancellable) { purgeAccount(orphan.serverId, orphan.accountId) }
                }
            runCatching {
                jsonStatePurger?.deleteJsonOnlyOrphans(
                    registeredServerIds = registered,
                    protectedServerIds = orphans - purged,
                )
            }.onFailure { Log.w(TAG, "JSON-only orphan purge failed; retaining directory evidence", it) }
            purged
        }
    }

    /**
     * Gives each saved server's pre-account rows and files to its account with
     * `adoptsLegacyData`. Idempotent and offline: rows are re-labelled in
     * place and files stay where they are ([DownloadStorage.adoptLegacyFiles]).
     */
    private suspend fun adoptLegacyData(registered: Set<String>) {
        registry.accounts.value
            .filter { it.adoptsLegacyData && it.serverId in registered }
            .forEach { account ->
                runCatching {
                    // Files first: once the rows say they belong to the account,
                    // its lookups must already find the files they point at. No
                    // recorded owner (or another one) leaves the rows for a
                    // later pass to retry.
                    if (storage.adoptLegacyFiles(account.serverId, account.localAccountId)) {
                        purgeDao.adoptLegacyRows(account.serverId, account.localAccountId)
                    }
                }.onFailure { Log.w(TAG, "legacy data adoption failed for an account", it) }
            }
    }

    /** Sign-out: every row, file and transfer of one account that is no longer saved. */
    private suspend fun purgeAccount(serverId: String, accountId: String): Boolean {
        val activeFileId = activePlaybackFileId()
        if (activeFileId != null && activeFileId in purgeDao.downloadFileIdsForAccount(serverId, accountId)) {
            Log.i(TAG, "deferring purge of a signed-out account: file $activeFileId is playing")
            return false
        }
        // Cancel first, as for a server: a running worker would write its row back.
        try {
            purgeDao.downloadRecordIdsForAccount(serverId, accountId).forEach { recordId ->
                cancelDownload(recordId)
            }
        } catch (failure: Exception) {
            Log.w(TAG, "download cancellation failed for a signed-out account; retaining rows", failure)
            return false
        }
        try {
            storage.deleteAllForAccount(serverId, accountId)
        } catch (failure: Exception) {
            Log.w(TAG, "file purge failed for a signed-out account; retaining rows", failure)
            return false
        }
        purgeDao.deleteAllRowsForAccount(serverId, accountId)
        Log.i(TAG, "purged the data of a signed-out account on $serverId")
        return true
    }

    private suspend fun purgeServer(serverId: String): Boolean {
        // Offline playback holds a file open — PiP survives leaving the screen,
        // and the player keeps running after a sign-out. Deleting underneath it
        // ends playback mid-scene. Leave the whole server for the next pass;
        // startup will catch it even if nothing else does.
        val activeFileId = activePlaybackFileId()
        if (activeFileId != null && activeFileId in purgeDao.downloadFileIdsForServer(serverId)) {
            Log.i(TAG, "deferring purge of $serverId: file $activeFileId is playing")
            return false
        }

        // Cancel first. A running DownloadWorker writes its row back on every
        // progress tick, so deleting rows underneath it resurrects one — with
        // no registry entry behind it, permanently un-purgeable by this pass.
        try {
            purgeDao.downloadRecordIdsForServer(serverId).forEach { recordId ->
                cancelDownload(recordId)
            }
        } catch (failure: Exception) {
            Log.w(TAG, "download cancellation failed for $serverId; retaining rows", failure)
            return false
        }

        try {
            deleteDownloadStorage(serverId)
            jsonStatePurger?.deleteAllForServer(serverId)
        } catch (failure: Exception) {
            Log.w(TAG, "local-state purge failed for $serverId; retaining rows", failure)
            return false
        }

        purgeRows(serverId)
        Log.i(TAG, "purged orphaned server data for $serverId")
        return true
    }

    private companion object {
        const val TAG = "OrphanedServerPurge"
    }
}

/**
 * Lets readers that must see adopted data (the Downloads list, offline
 * playback) wait for the first adoption pass after launch, so a download made
 * before accounts existed never reads as missing while its rows are being
 * handed to the account. Bounded: a purger that never starts delays a read by
 * [FIRST_PASS_TIMEOUT_MS] once, not forever.
 */
object AccountDataAdoption {
    /** Released by the pass, or by a reader's timeout. */
    private val firstPass = CompletableDeferred<Unit>()

    /** Released only by the pass itself; a timeout never completes it. */
    private val passFinished = CompletableDeferred<Unit>()
    private const val FIRST_PASS_TIMEOUT_MS = 3_000L

    internal fun markFirstPassDone() {
        passFinished.complete(Unit)
        firstPass.complete(Unit)
    }

    suspend fun awaitFirstPass() {
        if (firstPass.isCompleted) return
        if (withTimeoutOrNull(FIRST_PASS_TIMEOUT_MS) { firstPass.await() } == null) firstPass.complete(Unit)
    }

    /**
     * True once the pass has really run, false if it hasn't within the
     * timeout. Unlike [awaitFirstPass] a timeout here releases nobody: a
     * caller that must know who owns legacy data (a pre-account download
     * worker) retries instead of guessing while adoption is still moving it.
     */
    suspend fun awaitFirstPassFinished(): Boolean =
        passFinished.isCompleted ||
            withTimeoutOrNull(FIRST_PASS_TIMEOUT_MS) { passFinished.await() } != null
}

/**
 * Builds and starts the purger.
 *
 * Lives here rather than in each `Application` because the app modules do not
 * have Room on their compile classpath — they can name [SiloDatabase] but not
 * call through it.
 */
fun installOrphanedServerDataPurge(
    context: Context,
    registry: ServerRegistry,
    database: SiloDatabase,
    storage: DownloadStorage,
    legacyImporter: LegacyDownloadImporter? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
): OrphanedServerDataPurger = OrphanedServerDataPurger(
    registry = registry,
    purgeDao = database.serverPurgeDao(),
    storage = storage,
    cancelDownload = { recordId -> DownloadWorker.cancelAndAwait(context, recordId) },
    scope = scope,
    jsonStatePurger = ServerScopedJsonStatePurger(context.filesDir),
    awaitLegacyImport = { legacyImporter?.awaitImport(System.currentTimeMillis()) },
).also { it.start() }
