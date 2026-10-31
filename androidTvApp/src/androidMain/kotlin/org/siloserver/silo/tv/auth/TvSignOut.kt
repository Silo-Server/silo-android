package org.siloserver.silo.tv.auth

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.tv.ui.screens.servers.TV_REMOVE_SERVER_FAILED
import org.siloserver.silo.tv.ui.screens.servers.TvServerResolver
import org.siloserver.silo.tv.ui.screens.servers.TvServerSwitchDestination

/** How a sign-out ended. Each one is handed to the navigator once. */
sealed interface TvSignOutResult {
    /** Go to [destination]; [notice] says what didn't work, when something didn't. */
    data class SignedOut(val destination: TvServerSwitchDestination, val notice: String? = null) : TvSignOutResult

    /** Nobody was signed out; stay where you are and show [message]. */
    data class Refused(val message: String) : TvSignOutResult
}

/**
 * The one Android TV sign-out (silo-apple `AppRouter.requestSignOut`), behind
 * Who's Watching, the profile menu and Settings: sign out of the active
 * server's account, and with [start]'s `removeServer` forget the server too.
 *
 * It runs in [scope], which outlives the screen that asked, so leaving that
 * screen or recreating the Activity can't stop it halfway; the navigator
 * collects [result]. A second request while one runs is ignored.
 *
 * The account that leaves is the server and saved login active when it was
 * asked; if either changes before the sign-out is done, nobody is signed out.
 */
class TvSignOut(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val serverRegistry: ServerRegistry,
    private val resolver: TvServerResolver,
    private val scope: CoroutineScope,
    /** Pushes device settings still waiting to sync, while the session they belong to exists. */
    private val flushPendingSettings: suspend () -> Unit,
    /** Waits for the player's queued final report and stop, which ride on a borrowed identity. */
    private val awaitPlaybackStop: suspend () -> Unit,
    /** Ends a phone's borrowed identity through its owner ([org.siloserver.silo.tv.cast.RemotePlaybackIdentityManager.end]). */
    private val endBorrowedIdentity: suspend () -> Unit,
    /** Drops what this device cached for the signed-out profile. */
    private val clearProfileCaches: () -> Unit,
    private val playbackStopTimeoutMs: Long = PLAYBACK_STOP_TIMEOUT_MS,
    private val endBorrowedIdentityTimeoutMs: Long = END_BORROWED_IDENTITY_TIMEOUT_MS,
) {
    private val running = AtomicBoolean(false)

    private val _result = MutableStateFlow<TvSignOutResult?>(null)
    val result: StateFlow<TvSignOutResult?> = _result.asStateFlow()

    /** Starts a sign-out; false when one is already running. */
    fun start(removeServer: Boolean): Boolean {
        if (!running.compareAndSet(false, true)) return false
        scope.launch {
            try {
                _result.value = signOut(removeServer)
            } finally {
                running.set(false)
            }
        }
        return true
    }

    /** The navigator has handled [handled]. */
    fun consume(handled: TvSignOutResult) {
        _result.compareAndSet(handled, null)
    }

    private suspend fun signOut(removeServer: Boolean): TvSignOutResult {
        // Captured before anything it waits on: this is the account that leaves.
        val serverId = serverRegistry.activeServerId.value ?: return TvSignOutResult.Refused(SESSION_CHANGED)
        val loginId = tokenManager.loginSessionId(serverId)
        suspend fun stillTheSameLogin() =
            serverRegistry.activeServerId.value == serverId && tokenManager.loginSessionId(serverId) == loginId

        // A phone's borrowed identity is the current scope while it lasts, so
        // it would be the one signed out. It is not this TV's to clear: end it
        // through its owner, which tells the server and the receiver, as tvOS
        // does. Its own job, so a wait for its lock (behind a handoff) is
        // bounded without cutting its cleanup short.
        if (tokenManager.hasTemporaryScope()) {
            withTimeoutOrNull(playbackStopTimeoutMs) { awaitPlaybackStop() }
            val ending = scope.async { endBorrowedIdentity() }
            if (withTimeoutOrNull(endBorrowedIdentityTimeoutMs) { ending.await() } == null ||
                tokenManager.hasTemporaryScope()
            ) {
                return TvSignOutResult.Refused(REMOTE_PLAYBACK_NOT_ENDED)
            }
        }

        flushPendingSettings()
        val target = authRepository.captureSignOutTarget()
        if (!target.isSavedLogin(serverId, loginId) || !stillTheSameLogin()) {
            return TvSignOutResult.Refused(SESSION_CHANGED)
        }
        val signedOut = try {
            authRepository.logout(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The saved sign-in couldn't be cleared on this device.
            return TvSignOutResult.Refused(LOCAL_SIGN_OUT_FAILED)
        }
        if (!signedOut) return TvSignOutResult.Refused(SESSION_CHANGED)
        // The scoped logout cleared this login's profile; only the device's
        // caches for it are left.
        clearProfileCaches()

        if (!removeServer) return TvSignOutResult.SignedOut(TvServerSwitchDestination.Login)
        return when (val removal = resolver.remove(serverId)) {
            // Signed out, but the server is still saved: its sign-in screen.
            TvServerResolver.Removal.Failed ->
                TvSignOutResult.SignedOut(TvServerSwitchDestination.Login, notice = TV_REMOVE_SERVER_FAILED)
            is TvServerResolver.Removal.Removed ->
                TvSignOutResult.SignedOut(removal.destination ?: resolver.activeDestination())
        }
    }

    companion object {
        // Apple's wording (silo-apple `AppRouter.requestSignOut`).
        const val SESSION_CHANGED = "The active session changed. Try signing out again."
        const val REMOTE_PLAYBACK_NOT_ENDED = "Couldn't end remote playback. Try signing out again."
        const val LOCAL_SIGN_OUT_FAILED =
            "Silo couldn't clear the saved sign-in on this device. Please try signing out again."

        // As the profile return rule waits for a phone-launched title's teardown.
        const val PLAYBACK_STOP_TIMEOUT_MS = 15_000L

        // The identity's own server logout is bounded at 5s; the rest is its lock.
        const val END_BORROWED_IDENTITY_TIMEOUT_MS = 10_000L
    }
}
