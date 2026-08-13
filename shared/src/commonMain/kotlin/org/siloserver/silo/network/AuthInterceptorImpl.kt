package org.siloserver.silo.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.api.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.model.auth.RefreshRequest
import org.siloserver.silo.model.auth.RefreshResponse

/**
 * How close to expiry an access token may get before a request refreshes it
 * rather than spending it.
 *
 * Wide enough to cover the round trip plus clock skew between client and
 * server, narrow enough that it never dominates a short token lifetime.
 */
private const val PROACTIVE_REFRESH_MARGIN_MS = 60_000L

/**
 * What a refresh attempt settled.
 *
 * [CredentialsDead] is the one the proactive path must not ignore: the server
 * repudiated the refresh token, so the session has been torn down or the
 * overlay generation flagged. Sending the original request anyway would spend a
 * bearer the client has just declared invalid — and if the access token has not
 * expired yet the server may well honour it, completing a write for a session
 * the app has already ended.
 */
internal enum class RefreshOutcome {
    /** New credentials are installed; retry with them. */
    Refreshed,

    /** Nothing changed and the credentials are still usable as-is. */
    NotAttempted,

    /** The server rejected the refresh token; these credentials are finished. */
    CredentialsDead,

    /**
     * The refresh could not be completed for a reason that says nothing about
     * the credentials — a 5xx, a gateway, a dropped connection. They may still
     * be perfectly good, so the caller spends them as before; but it must not
     * immediately ask again, or one request becomes two refresh attempts and
     * concurrent traffic amplifies the outage it is already suffering.
     */
    FailedTransient,
}

/**
 * Configuration for the [SiloAuthPlugin].
 */
class SiloAuthConfig {
    /**
     * The [TokenManager] used to read/write tokens and profile state.
     */
    var tokenManager: TokenManager? = null
    var deviceMetadataProvider: DeviceMetadataProvider? = null
    var diagnosticsObserver: NetworkDiagnosticsObserver? = null
    var cleartextOriginConsent: CleartextOriginConsent? = null
}

/**
 * Ktor client plugin that handles Silo authentication.
 *
 * Before each request:
 * - Attaches `Authorization: Bearer <token>` if an access token is available
 * - Attaches `X-Profile-Id` header if a profile is selected
 * - Attaches `X-Profile-Token` header if present
 *
 * On 401 response:
 * - Attempts a token refresh using the stored refresh token
 * - Saves new tokens on success
 * - Retries the original request once with the new access token
 * - Uses a [Mutex] to prevent concurrent refresh attempts, and a double-check
 *   inside the mutex so that N parallel 401s only trigger ONE refresh.
 */
val SiloAuthPlugin = createClientPlugin("SiloAuthPlugin", ::SiloAuthConfig) {
    val tokenManager = pluginConfig.tokenManager
        ?: error("TokenManager must be provided to SiloAuthPlugin")
    val deviceMetadataProvider = pluginConfig.deviceMetadataProvider
    val diagnosticsObserver = pluginConfig.diagnosticsObserver
    val cleartextOriginConsent = pluginConfig.cleartextOriginConsent

    val refreshMutex = Mutex()

    // Temporary credential generations (remote-playback overlays) whose refresh
    // the server has definitively rejected. Such an overlay is deliberately left
    // INSTALLED — clearing it would make every later token read fall through to
    // the saved owner's account, so the guest session would silently continue as
    // the owner. Flagging the generation here is what stops the plugin from
    // refreshing dead credentials again on every subsequent 401.
    val deadCredentialGenerations = MutableStateFlow<Set<String>>(emptySet())

    /**
     * One refresh of [refreshScope], serialised on [refreshMutex].
     *
     * Lifted verbatim out of the 401 path so the proactive path cannot drift
     * from it. Every guard inside — mid-flight server switch, a sign-out
     * landing while the round trip is open, a dead temporary generation —
     * exists because it was needed once; a second copy would be a second place
     * to forget one.
     *
     * Set [allowNetworkRefresh] to false when a proactive attempt for this same
     * request already failed transiently: every check still runs — including
     * the one that spots a token a concurrent request installed, which needs no
     * network call — but the refresh POST itself is skipped rather than piling
     * onto a service that is already failing.
     *
     * @return [RefreshOutcome.Refreshed] when the caller should retry with a
     * token that now differs from [authorizationBeforeRequest]; otherwise the
     * outcome describing why not.
     */
    suspend fun refreshScopeOnce(
        refreshScope: AuthScopeSnapshot,
        trustedServerUrl: String,
        activeServerIdBeforeRequest: String?,
        authorizationBeforeRequest: String?,
        temporaryGeneration: String?,
        allowNetworkRefresh: Boolean = true,
    ): RefreshOutcome = refreshMutex.withLock {
        // If the user switched servers between request-send and 401-retry,
        // we are now operating against a different server. Don't try to
        // "refresh" — the refresh token wouldn't be valid for the new
        // server anyway, and we'd risk persisting cross-server tokens.
        val serverIdNow = tokenManager.getCurrentServerId()
        val serverUrlNow = tokenManager.getServerUrl()
        if (
            serverIdNow != activeServerIdBeforeRequest ||
            !isSameHttpOrigin(trustedServerUrl, serverUrlNow)
        ) {
            return@withLock RefreshOutcome.NotAttempted
        }

        val tokenNow = tokenManager.getAccessTokenForScope(refreshScope)
        if (
            tokenManager.getCurrentServerId() != activeServerIdBeforeRequest ||
            !isSameHttpOrigin(trustedServerUrl, tokenManager.getServerUrl())
        ) {
            return@withLock RefreshOutcome.NotAttempted
        }
        if (tokenNow != null && "Bearer $tokenNow" != authorizationBeforeRequest) {
            // Another coroutine already refreshed while we were waiting —
            // just retry the original request with the new token.
            return@withLock RefreshOutcome.Refreshed
        }

        if (temporaryGeneration != null &&
            temporaryGeneration in deadCredentialGenerations.value
        ) {
            // A 401 that won the race already proved these temporary credentials
            // are dead; the token is unchanged, so without this every waiter would
            // repeat the same doomed refresh.
            return@withLock RefreshOutcome.CredentialsDead
        }

        // Scope-bound, not global: a refresh must spend the token belonging to
        // the scope this request ran under.
        val refreshToken = tokenManager.getRefreshTokenForScope(refreshScope)
        if (refreshToken.isNullOrBlank()) {
            return@withLock RefreshOutcome.NotAttempted
        }

        // A proactive attempt for this same request already failed for a
        // transient reason. Everything above still had to run — most
        // importantly the double-check, because a CONCURRENT request may have
        // installed a working token while this one was in flight, and that
        // recovery costs no network call. Only the request below is suppressed.
        if (!allowNetworkRefresh) {
            return@withLock RefreshOutcome.FailedTransient
        }

        try {
            diagnosticsObserver.safeAuthRefresh("started")
            if (trustedServerUrl.isBlank()) {
                return@withLock RefreshOutcome.NotAttempted
            }

            val refreshResponse = client.post("$trustedServerUrl/api/v1/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(refreshToken))
            }

            // Re-check serverId AFTER the network call as well — the user
            // could have switched while we were waiting on the network.
            // The token write below targets whichever server is active at
            // save time, so a mismatch here means we'd write to the wrong
            // slot.
            val serverIdAfterCall = tokenManager.getCurrentServerId()
            val serverUrlAfterCall = tokenManager.getServerUrl()
            if (
                serverIdAfterCall != activeServerIdBeforeRequest ||
                !isSameHttpOrigin(trustedServerUrl, serverUrlAfterCall)
            ) {
                return@withLock RefreshOutcome.NotAttempted
            }

            // Re-check sign-out state AFTER the network call too. Logout
            // revokes the access token server-side before clearTokens()
            // runs, so concurrent requests 401 exactly during sign-out and
            // start a refresh with the still-valid refresh token; without
            // this guard the refresh response lands after clearTokens()
            // and saveTokens() silently signs the user back in.
            if (tokenManager.getRefreshTokenForScope(refreshScope).isNullOrBlank()) {
                return@withLock RefreshOutcome.NotAttempted
            }

            if (refreshResponse.status.isSuccess()) {
                diagnosticsObserver.safeAuthRefresh("succeeded")
                val tokens = refreshResponse.body<RefreshResponse>()
                tokenManager.saveTokensForScope(
                    scope = refreshScope,
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    expiresIn = tokens.expiresIn,
                )
                val after = tokenManager.getAccessTokenForScope(refreshScope)
                if (after != null && "Bearer $after" != authorizationBeforeRequest) {
                    RefreshOutcome.Refreshed
                } else {
                    RefreshOutcome.NotAttempted
                }
            } else {
                diagnosticsObserver.safeAuthRefresh("failed")
                // Only auth rejection proves the refresh token is bad.
                // Gateway/proxy/server failures should keep the session so
                // a temporary outage does not sign the user out.
                if (!refreshResponse.status.shouldInvalidateSessionAfterRefreshFailure()) {
                    // Gateway/proxy/server failure: the credentials may well
                    // still be good, so the caller may spend them as before.
                    return@withLock RefreshOutcome.FailedTransient
                }
                run {
                    val generationNow = tokenManager.temporaryGenerationId()
                    when {
                        // The identity changed while the refresh was in flight
                        // (overlay began or ended): the rejection belongs to a
                        // credential set that is no longer installed, so it must
                        // not tear down whatever is installed now.
                        generationNow != temporaryGeneration -> Unit

                        // Remote playback: the rejected credentials are a
                        // temporary overlay. invalidateSession() would drop that
                        // overlay, and every later read would fall through to the
                        // saved OWNER's account — the guest would keep browsing
                        // and writing history as the owner. Flag the generation
                        // dead and leave the overlay installed instead; the cast
                        // teardown path is what removes it.
                        temporaryGeneration != null -> {
                            deadCredentialGenerations.update { it + temporaryGeneration }
                            return@withLock RefreshOutcome.CredentialsDead
                        }

                        // The [TokenManager.sessionExpired] event emitted by
                        // this call is what the root NavHost observer uses to
                        // route the user back to the login screen; without it,
                        // the UI would stay on Home and keep rendering
                        // "Failed to load..." for every subsequent API call
                        // that now has no credentials.
                        else -> {
                            tokenManager.invalidateSessionForScope(refreshScope)
                            return@withLock RefreshOutcome.CredentialsDead
                        }
                    }
                }
                RefreshOutcome.NotAttempted
            }
        } catch (e: Throwable) {
            diagnosticsObserver.safeAuthRefresh("failed")
            RefreshOutcome.FailedTransient
        }
    }

    onRequest { request, _ ->
        val skipAuth = request.attributes.getOrNull(SkipSiloAuthAttributeKey) == true
        val requireAuth = request.attributes.getOrNull(RequireSiloAuthAttributeKey) == true
        val diagnosticsScope = request.attributes.getOrNull(DiagnosticsRequestScopeKey)
        val diagnosticsAuthorization = request.attributes.getOrNull(DiagnosticsUploadAuthorizationKey)
        val pinned = request.attributes.getOrNull(AuthScopeAttributeKey)
        val activeServerIdBefore =
            if (diagnosticsAuthorization == null && pinned == null) tokenManager.getCurrentServerId() else null
        val trustedServerUrl = diagnosticsAuthorization?.serverUrl ?: pinned?.serverUrl ?: tokenManager.getServerUrl()

        // Shared calls are normally relative. Resolve those against the exact
        // server that owns the credential scope before deciding whether any
        // Silo header may be attached.
        if (
            request.url.encodedPath.startsWith("/api/") &&
            (request.url.host.isBlank() || request.url.host == "localhost") &&
            trustedServerUrl.isNotBlank()
        ) {
            request.url.rebaseRelativeApiUrl(trustedServerUrl)
        }

        if (
            // skipSiloAuth is also used by login/refresh/device-login POSTs:
            // those requests omit headers but still carry credentials in the
            // body. Only read-only candidate probes may bypass consent.
            (!skipAuth || request.method != HttpMethod.Get) &&
            cleartextOriginConsent?.requiresApproval(request.url.toString()) == true
        ) {
            request.removeSiloCredentialHeaders()
            throw CleartextOriginNotApprovedException(request.url.toString())
        }

        val sameOrigin = isSameSiloHttpOrigin(trustedServerUrl, request.url)
        if (skipAuth) {
            request.removeSiloCredentialHeaders()
            if (sameOrigin) {
                request.attachSiloDeviceMetadataHeaders(deviceMetadataProvider)
            }
            return@onRequest
        }

        if (!sameOrigin) {
            request.removeSiloCredentialHeaders()
            if (diagnosticsAuthorization != null) {
                throw SiloAuthUnavailableException(
                    SiloAuthUnavailableException.REQUIRED_AUTH_UNAVAILABLE,
                )
            }
            return@onRequest
        }

        // Diagnostics upload owns an identity-transition lease around this call.
        // Use only the exact credential captured before the lease: consulting the
        // persistent TokenManager (or refreshing a 401) would re-enter the same
        // non-reentrant barrier. A rejected token is surfaced to the uploader and
        // retried after the next normal preflight refresh.
        if (diagnosticsAuthorization != null) {
            request.headers.remove(HttpHeaders.Authorization)
            request.header(HttpHeaders.Authorization, "Bearer ${diagnosticsAuthorization.accessToken}")
            request.headers.remove("X-Profile-Id")
            request.headers.remove("X-Profile-Token")
            request.applyProfileHeaders(
                diagnosticsScope = diagnosticsScope,
                activeProfileId = diagnosticsAuthorization.activeProfileId,
                activeProfileToken = null,
            )
            request.attachSiloDeviceMetadataHeaders(deviceMetadataProvider)
            return@onRequest
        }

        // Pinned (Track B outbox replay): bind this request to a captured scope
        // regardless of the globally-active server/profile, so a mid-drain switch
        // can't send it to the wrong account. Uses the snapshot's URL/profile and
        // the *live* per-server access token (handles rotation).
        if (pinned != null) {
            // Replace (never append) the scoped headers; clear the profile token
            // header when the snapshot has none.
            request.headers.remove(HttpHeaders.Authorization)
            val scopedAccessToken = tokenManager.getAccessTokenForScope(pinned)
            if (requireAuth && scopedAccessToken.isNullOrBlank()) {
                request.removeSiloCredentialHeaders()
                throw SiloAuthUnavailableException(
                    SiloAuthUnavailableException.REQUIRED_AUTH_UNAVAILABLE,
                )
            }
            scopedAccessToken?.let { token ->
                request.header(HttpHeaders.Authorization, "Bearer $token")
            }
            request.headers.remove("X-Profile-Id")
            request.headers.remove("X-Profile-Token")
            request.applyProfileHeaders(
                diagnosticsScope = diagnosticsScope,
                activeProfileId = pinned.profileId,
                activeProfileToken = pinned.profileToken,
            )
            request.attachSiloDeviceMetadataHeaders(deviceMetadataProvider)
            return@onRequest
        }

        // Skip auth headers for the refresh endpoint itself to avoid recursion
        val isRefreshRequest = request.url.encodedPath.endsWith("/auth/refresh")
        if (isRefreshRequest) return@onRequest

        val accessToken = tokenManager.getAccessToken()
        // One read: taking these separately could pair the old profile id with
        // the new profile's token across a switch.
        val profileIdentity = tokenManager.getProfileIdentity()
        val profileId = profileIdentity.profileId
        val profileToken = profileIdentity.profileToken
        val activeServerIdAfter = tokenManager.getCurrentServerId()
        val activeServerUrlAfter = tokenManager.getServerUrl()
        if (
            activeServerIdBefore != activeServerIdAfter ||
            !isSameHttpOrigin(trustedServerUrl, activeServerUrlAfter)
        ) {
            request.removeSiloCredentialHeaders()
            return@onRequest
        }

        accessToken?.let { token ->
            request.header(HttpHeaders.Authorization, "Bearer $token")
        }

        request.applyProfileHeaders(
            diagnosticsScope = diagnosticsScope,
            activeProfileId = profileId,
            activeProfileToken = profileToken,
        )

        request.attachSiloDeviceMetadataHeaders(deviceMetadataProvider)
    }

    on(Send) { request ->
        val diagnosticsAuthorization = request.attributes.getOrNull(DiagnosticsUploadAuthorizationKey)
        if (diagnosticsAuthorization != null) {
            if (!isSameSiloHttpOrigin(diagnosticsAuthorization.serverUrl, request.url)) {
                request.removeSiloCredentialHeaders()
                throw SiloAuthUnavailableException(
                    SiloAuthUnavailableException.REQUIRED_AUTH_UNAVAILABLE,
                )
            }
            // Exactly one attempt: never proactively refresh, retry a 401, or
            // invalidate credentials while the caller holds the identity lease.
            return@on proceed(request)
        }
        // Pinned scope (Track B): refresh against the *captured* scope, never the
        // active one, and never invalidate the active UI session — a failed
        // pinned refresh just surfaces the 401 so the outbox keeps the op.
        val pinnedScope = request.attributes.getOrNull(AuthScopeAttributeKey)
        val normalScope =
            if (pinnedScope == null) tokenManager.snapshotCurrentScope() else null
        val activeServerIdBeforeUrl =
            if (pinnedScope == null) normalScope?.serverId ?: tokenManager.getCurrentServerId() else null
        val trustedServerUrl =
            pinnedScope?.serverUrl ?: normalScope?.serverUrl ?: tokenManager.getServerUrl()
        val activeServerIdBeforeRequest =
            if (pinnedScope == null) tokenManager.getCurrentServerId() else null
        if (
            pinnedScope == null &&
            activeServerIdBeforeUrl != activeServerIdBeforeRequest
        ) {
            request.removeSiloCredentialHeaders()
            return@on proceed(request)
        }
        if (request.attributes.getOrNull(SkipSiloAuthAttributeKey) == true) {
            if (!isSameSiloHttpOrigin(trustedServerUrl, request.url)) {
                request.removeSiloCredentialHeaders()
            }
            return@on proceed(request)
        }
        if (!isSameSiloHttpOrigin(trustedServerUrl, request.url)) {
            request.removeSiloCredentialHeaders()
            return@on proceed(request)
        }
        if (pinnedScope != null) {
            val sentAuth = request.headers[HttpHeaders.Authorization]
            val originalCall = proceed(request)
            if (originalCall.response.status != HttpStatusCode.Unauthorized) {
                return@on originalCall
            }
            val pinnedGeneration = pinnedScope.credentialGenerationId
            if (pinnedGeneration != null && pinnedGeneration in deadCredentialGenerations.value) {
                // Already-rejected temporary credentials: surface the 401 instead of
                // re-refreshing them for every pinned op (progress ticks, teardown).
                return@on originalCall
            }
            // A redirect can carry the pinned call off the Silo origin; refreshing
            // then would hand this scope's credentials to whatever answered.
            if (!isSameSiloHttpOrigin(pinnedScope.serverUrl, originalCall.request.url)) {
                return@on originalCall
            }
            diagnosticsObserver.safeAuthRefresh("required")
            val refreshed = refreshMutex.withLock {
                // Another path may have refreshed this scope while we waited.
                val current = tokenManager.getAccessTokenForScope(pinnedScope)?.let { "Bearer $it" }
                if (current != null && current != sentAuth) {
                    return@withLock true
                }
                val refreshToken = tokenManager.getRefreshTokenForScope(pinnedScope)
                if (refreshToken.isNullOrBlank() || pinnedScope.serverUrl.isBlank()) {
                    return@withLock false
                }
                try {
                    diagnosticsObserver.safeAuthRefresh("started")
                    val refreshResponse = client.post("${pinnedScope.serverUrl}/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(refreshToken))
                    }
                    if (refreshResponse.status.isSuccess()) {
                        diagnosticsObserver.safeAuthRefresh("succeeded")
                        val tokens = refreshResponse.body<RefreshResponse>()
                        tokenManager.saveTokensForScope(
                            scope = pinnedScope,
                            accessToken = tokens.accessToken,
                            refreshToken = tokens.refreshToken,
                            expiresIn = tokens.expiresIn,
                        )
                        // A temporary credential generation may have ended while
                        // refresh was in flight. Its token manager deliberately
                        // drops that stale response instead of falling through to
                        // the saved account, so retry only when the exact scope now
                        // exposes the rotated token.
                        val after = tokenManager.getAccessTokenForScope(pinnedScope)?.let { "Bearer $it" }
                        after != null && after != sentAuth
                    } else {
                        diagnosticsObserver.safeAuthRefresh("failed")
                        if (pinnedGeneration != null &&
                            refreshResponse.status.shouldInvalidateSessionAfterRefreshFailure()
                        ) {
                            deadCredentialGenerations.update { it + pinnedGeneration }
                        }
                        // Don't invalidate the active session for a background scope.
                        // Re-check in case a concurrent path refreshed it in flight.
                        val after = tokenManager.getAccessTokenForScope(pinnedScope)?.let { "Bearer $it" }
                        after != null && after != sentAuth
                    }
                } catch (e: Throwable) {
                    diagnosticsObserver.safeAuthRefresh("failed")
                    false
                }
            }
            return@on if (refreshed) {
                tokenManager.getAccessTokenForScope(pinnedScope)?.let { newToken ->
                    request.headers.remove(HttpHeaders.Authorization)
                    request.header(HttpHeaders.Authorization, "Bearer $newToken")
                }
                proceed(request)
            } else {
                originalCall
            }
        }

        val refreshScope = normalScope ?: AuthScopeSnapshot(
            serverId = activeServerIdBeforeRequest.orEmpty(),
            profileId = null,
            serverUrl = trustedServerUrl,
            profileToken = null,
        )

        // Capture the authorization value we will actually SEND, together with
        // the server identity and origin that own it. If the response comes back
        // 401, we compare against this snapshot
        // inside the refresh mutex to detect a concurrent refresh that
        // already happened — so N parallel 401s collapse into ONE refresh.
        val authorizationBeforeRequest = request.headers[HttpHeaders.Authorization]

        // Spend a token we already know is about to expire and the server will
        // simply reject it: the 401 path below then refreshes and retries, so
        // the request costs two round trips instead of one. Refreshing first
        // costs the same one refresh and drops the wasted call.
        //
        // Deliberately narrow: only an authenticated request on the active
        // scope, never the auth endpoints themselves (refreshing before a
        // login is meaningless and before a refresh is recursive), and never a
        // pinned outbox op — that path is handled above and must not disturb
        // the active session. Everything else falls through unchanged, so a
        // manager that cannot answer the expiry question keeps today's
        // behaviour exactly.
        val proactivePath = request.url.encodedPath
        // Read the installed generation BEFORE asking about expiry. The expiry
        // question is answered by whatever identity is installed right now,
        // while the refresh spends the token belonging to the scope this
        // request captured earlier; if an overlay began or ended in between,
        // those are two different identities and a rejection of one would be
        // charged against the other. Requiring them to match means the pair is
        // only ever evaluated for a single identity.
        var proactiveRefreshFailedTransiently = false
        val proactiveGeneration = tokenManager.temporaryGenerationId()
        if (
            authorizationBeforeRequest != null &&
            !proactivePath.endsWith("/auth/refresh") &&
            !proactivePath.endsWith("/auth/login") &&
            proactiveGeneration == refreshScope.credentialGenerationId &&
            proactiveGeneration !in deadCredentialGenerations.value &&
            tokenManager.accessTokenExpiresWithin(PROACTIVE_REFRESH_MARGIN_MS) &&
            tokenManager.temporaryGenerationId() == proactiveGeneration
        ) {
            diagnosticsObserver.safeAuthRefresh("required")
            val earlyOutcome = refreshScopeOnce(
                refreshScope = refreshScope,
                trustedServerUrl = trustedServerUrl,
                activeServerIdBeforeRequest = activeServerIdBeforeRequest,
                authorizationBeforeRequest = authorizationBeforeRequest,
                temporaryGeneration = proactiveGeneration,
            )
            when (earlyOutcome) {
                RefreshOutcome.Refreshed ->
                    tokenManager.getAccessTokenForScope(refreshScope)?.let { token ->
                        request.headers.remove(HttpHeaders.Authorization)
                        request.header(HttpHeaders.Authorization, "Bearer $token")
                    }

                // The refresh token was rejected, so the session is torn
                // down. This request does not go out at all.
                //
                // Not even as an anonymous GET: "safe methods don't change
                // state" is not true here — GET /downloads/{id}/file moves the
                // download to completed server-side, GET /admin/stats?refresh
                // forces a recompute — and an optionally-authenticated read
                // would return GUEST data with a 200 that callers cache while
                // sessionExpired is signing the user out. Failing the call is
                // the honest answer, so this throws rather than sending
                // anything. Genuinely public calls opt out with skipSiloAuth(),
                // never receive a bearer, and so never reach this branch.
                RefreshOutcome.CredentialsDead -> {
                    request.removeSiloCredentialHeaders()
                    throw SiloAuthUnavailableException(
                        SiloAuthUnavailableException.CREDENTIALS_REPUDIATED,
                    )
                }

                RefreshOutcome.NotAttempted, RefreshOutcome.FailedTransient -> Unit
            }
            proactiveRefreshFailedTransiently =
                earlyOutcome == RefreshOutcome.FailedTransient

            // This request's bearer was captured BEFORE the refresh mutex was
            // waited on. In that window another coroutine can have signed out,
            // switched server, or had these very credentials repudiated — and
            // every one of those returns NotAttempted, which says only "no
            // refresh happened", not "the scope is still alive". Reading it as
            // permission to proceed is how an invalidated bearer gets spent.
            //
            // So verify what is actually installed rather than trusting the
            // outcome. Checked for every non-Refreshed case: FailedTransient is
            // meant to spend the existing credentials, but only if they still
            // exist.
            if (earlyOutcome != RefreshOutcome.Refreshed) {
                val installed = tokenManager.getAccessTokenForScope(refreshScope)
                val serverNow = tokenManager.getCurrentServerId()
                when {
                    installed == null || serverNow != activeServerIdBeforeRequest -> {
                        request.removeSiloCredentialHeaders()
                        throw SiloAuthUnavailableException(
                            SiloAuthUnavailableException.CREDENTIALS_REPUDIATED,
                        )
                    }
                    // Someone else rotated them while we waited: spend the
                    // token that is actually installed, not the stale capture.
                    "Bearer $installed" != authorizationBeforeRequest -> {
                        request.headers.remove(HttpHeaders.Authorization)
                        request.header(HttpHeaders.Authorization, "Bearer $installed")
                    }
                }
            }
        }

        val originalCall = proceed(request)

        // Only attempt refresh on 401 for non-auth endpoints
        if (originalCall.response.status != HttpStatusCode.Unauthorized) {
            return@on originalCall
        }
        if (!isSameSiloHttpOrigin(trustedServerUrl, originalCall.request.url)) {
            return@on originalCall
        }
        if (
            tokenManager.getCurrentServerId() != activeServerIdBeforeRequest ||
            !isSameHttpOrigin(trustedServerUrl, tokenManager.getServerUrl())
        ) {
            return@on originalCall
        }
        diagnosticsObserver.safeAuthRefresh("required")

        val requestPath = originalCall.request.url.encodedPath
        if (requestPath.endsWith("/auth/refresh") || requestPath.endsWith("/auth/login")) {
            return@on originalCall
        }

        // Identity of the temporary overlay (remote playback) this request ran
        // under, if any. Null means the request ran on a saved account.
        val temporaryGeneration = tokenManager.temporaryGenerationId()
        if (temporaryGeneration != null &&
            temporaryGeneration in deadCredentialGenerations.value
        ) {
            // The server already rejected these credentials. Refreshing again would
            // storm it once per request for the rest of the handoff; the overlay stays
            // installed so the guest cannot fall back onto the owner's account.
            return@on originalCall
        }

        // Capture the server id as well so we can detect a mid-refresh server
        // switch — without this, a 401-refresh kicked off against server A
        // could land after the user has switched to server B and write A's
        // freshly-issued tokens into B's slot.
        val serverIdBeforeRequest = tokenManager.getCurrentServerId()

        // Attempt token refresh with mutex to prevent concurrent refreshes.
        // The mutex guarantees that only one coroutine refreshes at a time;
        // the double-check guarantees that only one coroutine HITS the network
        // for the refresh — subsequent waiters observe the already-refreshed
        // token and skip straight to retry.
        val refreshed = refreshScopeOnce(
            refreshScope = refreshScope,
            trustedServerUrl = trustedServerUrl,
            activeServerIdBeforeRequest = activeServerIdBeforeRequest,
            authorizationBeforeRequest = authorizationBeforeRequest,
            temporaryGeneration = temporaryGeneration,
            // A proactive attempt for this request already failed transiently.
            // Do not ask the network again — but do still let the double-check
            // above pick up a token a concurrent request installed meanwhile.
            allowNetworkRefresh = !proactiveRefreshFailedTransiently,
        ) == RefreshOutcome.Refreshed

        if (refreshed) {
            // Explicitly replace the Authorization header on the request builder
            // before retrying. `proceed(request)` re-enters the pipeline at Send —
            // the `onRequest` hook (which originally attached the old token) does
            // NOT run a second time, so if we don't update the header here the
            // retry gets sent with the expired Bearer token and the server
            // returns another 401.
            val retryServerIdBeforeToken = tokenManager.getCurrentServerId()
            val retryServerUrlBeforeToken = tokenManager.getServerUrl()
            val newAccessToken = tokenManager.getAccessTokenForScope(refreshScope)
            val retryServerIdAfterToken = tokenManager.getCurrentServerId()
            val retryServerUrlAfterToken = tokenManager.getServerUrl()
            if (
                retryServerIdBeforeToken != activeServerIdBeforeRequest ||
                retryServerIdAfterToken != activeServerIdBeforeRequest ||
                !isSameHttpOrigin(trustedServerUrl, retryServerUrlBeforeToken) ||
                !isSameHttpOrigin(trustedServerUrl, retryServerUrlAfterToken) ||
                !isSameSiloHttpOrigin(trustedServerUrl, request.url) ||
                newAccessToken == null
            ) {
                return@on originalCall
            }
            request.headers.remove(HttpHeaders.Authorization)
            request.header(HttpHeaders.Authorization, "Bearer $newAccessToken")
            proceed(request)
        } else {
            originalCall
        }
    }
}

private fun HttpRequestBuilder.applyProfileHeaders(
    diagnosticsScope: DiagnosticsRequestScope?,
    activeProfileId: String?,
    activeProfileToken: String?,
) {
    when (diagnosticsScope?.mode ?: DiagnosticsProfileHeaderMode.ACTIVE) {
        DiagnosticsProfileHeaderMode.ACTIVE -> {
            if (!headers.contains("X-Profile-Id")) {
                activeProfileId?.let { header("X-Profile-Id", it) }
            }
            if (!headers.contains("X-Profile-Token")) {
                activeProfileToken?.let { header("X-Profile-Token", it) }
            }
        }
        DiagnosticsProfileHeaderMode.SUPPRESS -> {
            headers.remove("X-Profile-Id")
            headers.remove("X-Profile-Token")
        }
        DiagnosticsProfileHeaderMode.EXACT -> {
            headers.remove("X-Profile-Id")
            headers.remove("X-Profile-Token")
            val exactProfileId = checkNotNull(diagnosticsScope?.exactProfileId)
            header("X-Profile-Id", exactProfileId)
        }
    }
}

/**
 * Generation id of the temporary credential overlay currently installed (remote
 * playback), or null when the active identity is a saved account. Managers that
 * don't model overlays report null, which keeps the saved-account behaviour.
 */
private suspend fun TokenManager.temporaryGenerationId(): String? =
    snapshotCurrentScope()?.credentialGenerationId

private fun HttpStatusCode.shouldInvalidateSessionAfterRefreshFailure(): Boolean =
    this == HttpStatusCode.BadRequest ||
        this == HttpStatusCode.Unauthorized ||
        this == HttpStatusCode.Forbidden

private fun NetworkDiagnosticsObserver?.safeAuthRefresh(state: String) {
    runCatching { this?.authRefresh(state) }
}

private suspend fun HttpRequestBuilder.attachSiloDeviceMetadataHeaders(
    deviceMetadataProvider: DeviceMetadataProvider?,
) {
    val device = deviceMetadataProvider?.current() ?: return
    header("X-Silo-Device-Id", device.id)
    header("X-Silo-Device-Name", device.name)
    header("X-Silo-Device-Platform", device.platform)
    device.clientName?.takeIf { it.isNotBlank() }?.let { header("X-Silo-Client", it) }
    device.clientVersion?.takeIf { it.isNotBlank() }?.let { header("X-Silo-Client-Version", it) }
    device.clientBuild?.takeIf { it.isNotBlank() }?.let { header("X-Silo-Client-Build", it) }
    device.clientChannel?.takeIf { it.isNotBlank() }?.let { header("X-Silo-Client-Channel", it) }
}

private fun URLBuilder.rebaseRelativeApiUrl(serverUrl: String) {
    val originalPath = encodedPath
    val originalParameters = parameters.build()
    val originalFragment = fragment
    val originalProtocol = protocol

    takeFrom(serverUrl)
    restoreWebSocketProtocol(originalProtocol)
    encodedPath = originalPath
    parameters.clear()
    parameters.appendAll(originalParameters)
    fragment = originalFragment
}

private fun HttpRequestBuilder.removeSiloCredentialHeaders() {
    headers.remove(HttpHeaders.Authorization)
    headers.remove("X-Profile-Id")
    headers.remove("X-Profile-Token")
    headers.names()
        .filter { name -> name.startsWith("X-Silo-", ignoreCase = true) }
        .forEach(headers::remove)
}

private fun isSameSiloHttpOrigin(serverUrl: String, requestUrl: URLBuilder): Boolean {
    val httpRequestUrl = when (requestUrl.protocol) {
        URLProtocol.WS -> requestUrl.toString().replaceSchemeForOriginCheck("http")
        URLProtocol.WSS -> requestUrl.toString().replaceSchemeForOriginCheck("https")
        else -> requestUrl.toString()
    }
    return isSameHttpOrigin(serverUrl, httpRequestUrl)
}

private fun isSameSiloHttpOrigin(serverUrl: String, requestUrl: Url): Boolean {
    val httpRequestUrl = when (requestUrl.protocol) {
        URLProtocol.WS -> requestUrl.toString().replaceSchemeForOriginCheck("http")
        URLProtocol.WSS -> requestUrl.toString().replaceSchemeForOriginCheck("https")
        else -> requestUrl.toString()
    }
    return isSameHttpOrigin(serverUrl, httpRequestUrl)
}

private fun String.replaceSchemeForOriginCheck(scheme: String): String =
    "$scheme://${substringAfter("://")}"

/**
 * Re-applies the websocket protocol after a `takeFrom(serverUrl)` rebase.
 *
 * `takeFrom` copies the configured server URL's scheme (http/https) over the
 * ws/wss protocol Ktor set for `webSocket { }` requests. With the WS scheme
 * gone the engine sends a plain GET — no Upgrade/Connection/Sec-WebSocket-Key
 * handshake at all — and the server rejects it (this is exactly how every
 * events-socket connection from the app failed silently in the field: 19k+
 * 400s over one week while browsers connected fine). Map back to ws/wss with
 * TLS matching the server URL's scheme.
 */
private fun URLBuilder.restoreWebSocketProtocol(originalProtocol: URLProtocol) {
    if (originalProtocol != URLProtocol.WS && originalProtocol != URLProtocol.WSS) return
    protocol = if (protocol.isSecure()) URLProtocol.WSS else URLProtocol.WS
}
