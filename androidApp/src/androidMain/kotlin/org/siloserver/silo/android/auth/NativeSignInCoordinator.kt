package org.siloserver.silo.android.auth

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.network.ApiResult

/** How one native flow ended, for the screen that started it. */
sealed interface NativeSignInResult {
    val purpose: NativeSignInPurpose
    val serverEntryId: String

    /** The redirect arrived and the code is being redeemed. */
    data class Finishing(
        override val purpose: NativeSignInPurpose,
        override val serverEntryId: String,
        val providerName: String,
    ) : NativeSignInResult

    data class SignedIn(override val serverEntryId: String, val user: User) : NativeSignInResult {
        override val purpose get() = NativeSignInPurpose.SignIn
    }

    data class Linked(override val serverEntryId: String, val providerName: String) : NativeSignInResult {
        override val purpose get() = NativeSignInPurpose.Link
    }

    data class Failed(
        override val purpose: NativeSignInPurpose,
        override val serverEntryId: String,
        /** A server `error=` reason or one of [NativeSignInMessages]' own codes. */
        val reason: String,
        val message: String,
    ) : NativeSignInResult
}

/** What [NativeSignInCoordinator.begin] decided. */
sealed interface NativeSignInStart {
    /** A flow is recorded; open [url] in the browser. */
    data class Open(val url: String) : NativeSignInStart

    /** No flow was recorded; show [message]. */
    data class Refused(val reason: String, val message: String) : NativeSignInStart
}

/**
 * Redeems what the flow's code is worth, at the saved server's base URL and
 * only while it is still at [PendingNativeSignIn.startOrigin]. The
 * production implementation ([RepositoryNativeSignInCompleter]) signs in
 * through [org.siloserver.silo.repository.AuthRepository] and links through
 * [org.siloserver.silo.network.api.ExternalSignInApi].
 */
interface NativeSignInCompleter {
    suspend fun signIn(pending: PendingNativeSignIn, code: String): ApiResult<User>
    suspend fun link(pending: PendingNativeSignIn, code: String): ApiResult<Unit>
}

/**
 * Owns the one native OAuth flow the phone app may have in flight: starts it
 * ([begin]) and finishes it when the app redirect arrives ([handleCallback]).
 *
 * The custom scheme can be claimed by another app, and anything can send an
 * intent to it, so a callback is acted on only when it answers the flow this
 * app started: a flow must be pending and not expired, and `state` must equal
 * its `app_state` (compared in constant time). A callback that fails either
 * check changes nothing, and the pending flow stays for the real redirect.
 * Once `state` matches, the flow is spent: `iss` must be present and equal
 * the saved server's origin, where the app opened the native start, `server`
 * must be the verified identity of the saved server the flow was started
 * for, and the code is redeemed only with this flow's verifier, only at the
 * saved base.
 *
 * The `server` id is self-asserted: a hostile saved server can echo another
 * deployment's id, and its native start can redirect the browser to that
 * deployment's native start with this app's challenge and `app_state`. The
 * real server records where its native start first arrived, its own origin,
 * and names it in `iss`; that is not the saved origin, so the code is
 * discarded. This covers only relays the real server can't be tricked into
 * recording. A hostile server can request the start itself with its own
 * origin as `Host` and get back an `iss` naming that origin, so the server
 * records a start origin other than its public URL only where a server
 * outside the local network can't hold it: the origins of connected network
 * access providers, loopback, private, link-local and 100.64.0.0/10 IP
 * literals, and single-label, .local, .lan, .localdomain, .home.arpa and
 * .internal names. A hostile saved server on such an address can still relay.
 * See silo-server `docs/architecture/external-sign-in.md` ("Native apps").
 * A listing that names another origin changes nothing: only its path is
 * used, on the saved base.
 */
class NativeSignInCoordinator(
    private val store: PendingNativeSignInStore,
    private val completer: NativeSignInCompleter,
    private val scope: CoroutineScope,
    private val accountChoices: AccountChoiceStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _result = MutableStateFlow<NativeSignInResult?>(null)

    /**
     * Moves when a flow is started or discarded, under [mutex]. A redemption
     * publishes only while it is unchanged, so a flow superseded meanwhile
     * (a new one began, a sign-in or sign-out discarded it) reports nothing.
     */
    private var flowGeneration = 0L

    /** The latest flow's outcome until the screen that started it [consume]s it. */
    val result: StateFlow<NativeSignInResult?> = _result.asStateFlow()

    /** The saved server whose login screen starts the provider sign-in by itself; memory only. */
    private var autoStartServerEntryId: String? = null

    /**
     * An explicit sign-out of [serverEntryId] (Settings, the profile menu,
     * "Not you? Switch account"): its next provider sign-in asks the provider
     * to offer another account (`prompt=select_account`), until someone signs
     * in there. A session that expired never asks.
     *
     * [autoStart] ("Not you? Switch account" where the provider takes
     * `select_account`) also has that server's login screen start the sign-in
     * as soon as it has its providers (silo-apple `SelectAccountPrompt`). It
     * lives in memory only, so a relaunch never opens a browser by itself.
     */
    fun requestAccountChoice(serverEntryId: String, autoStart: Boolean = false) {
        accountChoices.request(serverEntryId)
        if (autoStart) synchronized(this) { autoStartServerEntryId = serverEntryId }
    }

    fun accountChoiceRequested(serverEntryId: String): Boolean = accountChoices.isRequested(serverEntryId)

    /** Someone signed in on [serverEntryId] (by password or a provider): stop asking for another account. */
    fun clearAccountChoice(serverEntryId: String) {
        accountChoices.clear(serverEntryId)
        synchronized(this) { if (autoStartServerEntryId == serverEntryId) autoStartServerEntryId = null }
    }

    /** Whether the login screen for [serverEntryId] should start the provider sign-in by itself. True once per request. */
    fun consumeAutoStart(serverEntryId: String): Boolean = synchronized(this) {
        (autoStartServerEntryId == serverEntryId).also { if (it) autoStartServerEntryId = null }
    }

    /**
     * Gives back an auto-start that [consumeAutoStart] took for a sign-in that
     * never opened because its login screen went away. Does nothing once
     * someone signed in there ([clearAccountChoice]).
     */
    fun restoreAutoStart(serverEntryId: String) {
        if (accountChoiceRequested(serverEntryId)) synchronized(this) { autoStartServerEntryId = serverEntryId }
    }

    /**
     * Records a new flow and returns the URL to open in the browser. Replaces
     * any earlier pending flow, whose redirect is then ignored.
     *
     * The callers read [verifiedServerId] from the saved server's identity
     * (refreshed) just before. [serverUrl] is that server's saved base URL,
     * and the native start opens there: [nativeStartPath] is the
     * base-relative path from the provider listing, never an origin. The flow
     * is bound to the saved origin. [prompt] (a sign-in's `select_account`)
     * is never sent on a linking flow, which asks the provider for a fresh
     * sign-in itself. [loginSessionId] is the saved server's login the flow
     * belongs to ([PendingNativeSignIn.loginSessionId]).
     */
    suspend fun begin(
        purpose: NativeSignInPurpose,
        serverEntryId: String,
        serverUrl: String,
        verifiedServerId: String,
        providerName: String,
        nativeStartPath: String,
        linkTicket: String? = null,
        loginSessionId: String? = null,
        prompt: String? = null,
    ): NativeSignInStart {
        val serverOrigin = NativeSignInProtocol.origin(serverUrl)
        val appState = NativeSignInProtocol.newAppState()
        val codeVerifier = NativeSignInProtocol.newCodeVerifier()
        val url = NativeSignInProtocol.startUrl(
            serverUrl,
            nativeStartPath,
            appState,
            codeVerifier,
            linkTicket,
            prompt = prompt.takeIf { purpose == NativeSignInPurpose.SignIn },
        )
        if (serverOrigin == null || url == null) {
            return NativeSignInStart.Refused("login_failed", NativeSignInMessages.forReason("login_failed", providerName))
        }
        return mutex.withLock {
            flowGeneration++
            store.save(
                PendingNativeSignIn(
                    purpose = purpose,
                    serverEntryId = serverEntryId,
                    verifiedServerId = verifiedServerId,
                    startOrigin = serverOrigin,
                    appState = appState,
                    codeVerifier = codeVerifier,
                    providerName = providerName,
                    startedAtEpochMs = clock(),
                    loginSessionId = loginSessionId,
                ),
            )
            _result.value = null
            NativeSignInStart.Open(url)
        }
    }

    /**
     * Handles an intent to the app redirect. Returns the job that [finish]es
     * it, or null when [uri] isn't the app redirect at all; [finish] itself
     * ignores a redirect that doesn't answer the pending flow. The job runs on
     * this coordinator's own scope, so it outlives the activity that received
     * the intent.
     */
    fun handleCallback(uri: String?): Job? {
        val callback = NativeSignInCallback.parse(uri) ?: return null
        return scope.launch { finish(callback) }
    }

    /**
     * Another sign-in or a sign-out happened: the pending flow, if any, no
     * longer speaks for the session, so its redirect is ignored, a
     * redemption under way reports nothing, and an unshown result goes.
     */
    suspend fun discardPending() {
        mutex.withLock {
            flowGeneration++
            store.clear()
            _result.value = null
        }
    }

    /** [handleCallback]'s work, run inline. Returns whether the callback answered the pending flow. */
    suspend fun finish(callback: NativeSignInCallback): Boolean {
        val (pending, generation) = mutex.withLock {
            val pending = store.load() ?: return false
            if (clock() - pending.startedAtEpochMs !in 0..NativeSignInProtocol.FLOW_LIFETIME_MS) {
                store.clear()
                return false
            }
            if (!constantTimeEquals(callback.state, pending.appState)) return false
            // The flow is spent from here on, whatever the outcome.
            store.clear()
            pending to flowGeneration
        }
        val failure = when {
            // RFC 9207: the server names where the native start first arrived,
            // which must be the saved origin the app opened it on. A relay
            // through another server's native start comes back with that
            // server's own origin.
            !issuedBy(callback.iss, pending.startOrigin) -> NativeSignInMessages.ISSUER_MISMATCH
            callback.server == null || !constantTimeEquals(callback.server, pending.verifiedServerId) ->
                NativeSignInMessages.WRONG_SERVER
            callback.error != null -> callback.error
            callback.code == null -> "login_failed"
            callback.link != (pending.purpose == NativeSignInPurpose.Link) -> "login_failed"
            else -> null
        }
        if (failure != null) {
            publishFor(generation, failed(pending, failure))
            return true
        }
        val code = checkNotNull(callback.code)
        publishFor(generation, NativeSignInResult.Finishing(pending.purpose, pending.serverEntryId, pending.providerName))
        val outcome = try {
            when (pending.purpose) {
                NativeSignInPurpose.SignIn -> when (val signedIn = completer.signIn(pending, code)) {
                    is ApiResult.Success -> {
                        clearAccountChoice(pending.serverEntryId)
                        NativeSignInResult.SignedIn(pending.serverEntryId, signedIn.data)
                    }
                    is ApiResult.Error -> failed(pending, completionReason(signedIn))
                    is ApiResult.NetworkError -> failed(pending, NativeSignInMessages.networkReason(signedIn))
                }
                NativeSignInPurpose.Link -> when (val linked = completer.link(pending, code)) {
                    is ApiResult.Success -> NativeSignInResult.Linked(pending.serverEntryId, pending.providerName)
                    is ApiResult.Error -> failed(pending, completionReason(linked))
                    is ApiResult.NetworkError -> failed(pending, NativeSignInMessages.networkReason(linked))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed(pending, "login_failed")
        }
        publishFor(generation, outcome)
        return true
    }

    /** The screen has shown [result]; a later flow's result replaces it anyway. */
    fun consume(result: NativeSignInResult) {
        _result.update { if (it == result) null else it }
    }

    /** Publishes [result] only while the flow claimed at [generation] is still the latest. */
    private suspend fun publishFor(generation: Long, result: NativeSignInResult) {
        mutex.withLock { if (flowGeneration == generation) _result.value = result }
    }

    private fun failed(pending: PendingNativeSignIn, reason: String) = NativeSignInResult.Failed(
        purpose = pending.purpose,
        serverEntryId = pending.serverEntryId,
        reason = reason,
        message = NativeSignInMessages.forReason(reason, pending.providerName),
    )

    companion object {
        /**
         * The reason a failed redemption shows. The redemption's own cases
         * first: a verifier that doesn't fit (400 `invalid_grant`) means the
         * browser flow didn't finish as this app started it; an unknown, used
         * or expired code (401 `invalid_token`) took too long, and either way
         * the flow starts again. Then the refusals any account operation can
         * meet ([NativeSignInMessages.problemReason]).
         */
        internal fun completionReason(error: ApiResult.Error): String = when {
            error.error == "identity_changed" -> NativeSignInMessages.ACCOUNT_CHANGED
            error.error == "password_expired" -> "password_expired"
            NativeSignInMessages.isSharedProblem(error.error) -> error.error
            error.error == "invalid_grant" -> "state_invalid"
            error.error == "invalid_token" || error.code == 401 -> "session_expired"
            error.code == 409 && error.error == "conflict" -> "already_linked"
            // A redemption refused outright is about the account, not this session.
            error.code == 403 -> "not_permitted"
            else -> NativeSignInMessages.problemReason(error) ?: "login_failed"
        }

        /** Whether [iss] names [startOrigin]; a missing `iss` never does. */
        internal fun issuedBy(iss: String?, startOrigin: String): Boolean {
            val issuer = NativeSignInProtocol.origin(iss) ?: return false
            return constantTimeEquals(issuer, startOrigin)
        }

        internal fun constantTimeEquals(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
        }
    }
}
