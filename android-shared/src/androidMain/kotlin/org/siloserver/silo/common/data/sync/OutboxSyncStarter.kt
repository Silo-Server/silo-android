package org.siloserver.silo.common.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import org.siloserver.silo.network.ServerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Kicks an outbox drain on app launch, when connectivity returns, and when the
 * active server changes — so ops left pending by a previous session, by an
 * offline period, or by a scope the worker wasn't draining when they were
 * queued, all sync without waiting for a fresh mutation. The mutation path
 * already enqueues on `resolve(RETRIABLE)`; this covers the "reopened / back
 * online / switched back" cases.
 *
 * Used by both the phone and TV `Application`s (both perform content-level
 * mutations). Enqueues are unique-KEEP, so concurrent triggers coalesce.
 *
 * Known limitation (documented, not data-loss): while a retry chain for one
 * scope is backing off, `KEEP` can briefly defer an immediately-due op until the
 * chain's next tick. A dedicated scheduler would close that latency gap; the op
 * is never lost.
 */
class OutboxSyncStarter(
    private val context: Context,
    private val registry: ServerRegistry,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    // Seam for tests: every trigger goes through here, so a test can observe
    // them without standing up WorkManager.
    private val enqueueDrain: () -> Unit = { SyncWorker.enqueue(context) },
) {

    fun start() {
        // Drain leftovers on launch; the worker's CONNECTED constraint defers
        // the actual run until the network is available.
        enqueueDrain()

        // Drain when the active SCOPE changes — server, account or profile.
        //
        // Profile matters as much as server and had no trigger at all. The
        // outbox is scoped by (serverId, profileId), and SyncEngine counts
        // what remains for the scope active at the END of a drain; SyncWorker
        // turns a non-zero count into Result.retry(), which is the only thing
        // keeping the retry chain alive. A profile switch leaves activeServerId
        // untouched, so the chain for the scope that still had queued work just
        // stopped: watched marks, ratings, favourites and resume positions from
        // that profile never synced again. On a TV, which is never relaunched,
        // that is permanent and invisible. An account switch on one server can
        // keep both server and profile id (`default`), so the account counts too.
        val initialScope = registry.activeEntry.value?.let { Triple(it.id, it.accountId, it.profileId) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previousScope = initialScope
            registry.activeEntry
                .map { entry -> entry?.let { Triple(it.id, it.accountId, it.profileId) } }
                .collect { currentScope ->
                    if (currentScope != previousScope) {
                        previousScope = currentScope
                        enqueueDrain()
                    }
                }
        }

        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            Log.w(TAG, "No ConnectivityManager; outbox will drain on launch/activation/mutation only")
            return
        }
        runCatching {
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        enqueueDrain()
                    }
                },
            )
        }.onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed", it) }
    }

    companion object {
        private const val TAG = "OutboxSyncStarter"
    }
}
