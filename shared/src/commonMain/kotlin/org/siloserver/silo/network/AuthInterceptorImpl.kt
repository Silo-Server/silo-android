package org.siloserver.silo.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.api.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.model.auth.RefreshRequest
import org.siloserver.silo.model.auth.RefreshResponse

/**
 * Configuration for the [SiloAuthPlugin].
 */
class SiloAuthConfig {
    /**
     * The [TokenManager] used to read/write tokens and profile state.
     */
    var tokenManager: TokenManager? = null
    var deviceMetadataProvider: DeviceMetadataProvider? = null
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

    val refreshMutex = Mutex()

    onRequest { request, _ ->
        val skipAuth = request.attributes.getOrNull(SkipSiloAuthAttributeKey) == true

        // Pinned (Track B outbox replay): bind this request to a captured scope
        // regardless of the globally-active server/profile, so a mid-drain switch
        // can't send it to the wrong account. Uses the snapshot's URL/profile and
        // the *live* per-server access token (handles rotation).
        val pinned = request.attributes.getOrNull(AuthScopeAttributeKey)
        if (pinned != null && !skipAuth) {
            if (request.url.encodedPath.startsWith("/api/") && pinned.serverUrl.isNotBlank()) {
                val originalPath = request.url.encodedPath
                val originalParameters = request.url.parameters.build()
                val originalFragment = request.url.fragment
                request.url.takeFrom(pinned.serverUrl)
                request.url.encodedPath = originalPath
                request.url.parameters.clear()
                request.url.parameters.appendAll(originalParameters)
                request.url.fragment = originalFragment
            }
            // Replace (never append) the scoped headers; clear the profile token
            // header when the snapshot has none.
            request.headers.remove(HttpHeaders.Authorization)
            tokenManager.getAccessTokenForScope(pinned)?.let { token ->
                request.header(HttpHeaders.Authorization, "Bearer $token")
            }
            request.headers.remove("X-Profile-Id")
            pinned.profileId?.let { request.header("X-Profile-Id", it) }
            request.headers.remove("X-Profile-Token")
            pinned.profileToken?.let { request.header("X-Profile-Token", it) }
            request.attachSiloDeviceMetadataHeaders(deviceMetadataProvider)
            return@onRequest
        }

        // Shared API calls use relative paths; bind them to the configured server URL
        // before the request is sent so Ktor doesn't fall back to localhost on iOS.
        if (request.url.encodedPath.startsWith("/api/") && request.url.host == "localhost") {
            val serverUrl = tokenManager.getServerUrl()
            if (serverUrl.isNotBlank()) {
                val originalPath = request.url.encodedPath
                val originalParameters = request.url.parameters.build()
                val originalFragment = request.url.fragment

                request.url.takeFrom(serverUrl)
                request.url.encodedPath = originalPath
                request.url.parameters.clear()
                request.url.parameters.appendAll(originalParameters)
                request.url.fragment = originalFragment
            }
        }

        if (skipAuth) {
            request.headers.remove(HttpHeaders.Authorization)
            request.headers.remove("X-Profile-Id")
            request.headers.remove("X-Profile-Token")
            request.attachSiloDeviceMetadataHeaders(deviceMetadataProvider)
            return@onRequest
        }

        // Skip auth headers for the refresh endpoint itself to avoid recursion
        val isRefreshRequest = request.url.encodedPath.endsWith("/auth/refresh")
        if (isRefreshRequest) return@onRequest

        tokenManager.getAccessToken()?.let { token ->
            request.header(HttpHeaders.Authorization, "Bearer $token")
        }

        if (!request.headers.contains("X-Profile-Id")) {
            tokenManager.getProfileId()?.let { profileId ->
                request.header("X-Profile-Id", profileId)
            }
        }

        tokenManager.getProfileToken()?.let { profileToken ->
            request.header("X-Profile-Token", profileToken)
        }

        request.attachSiloDeviceMetadataHeaders(deviceMetadataProvider)
    }

    on(Send) { request ->
        if (request.attributes.getOrNull(SkipSiloAuthAttributeKey) == true) {
            return@on proceed(request)
        }

        // Pinned scope (Track B): refresh against the *captured* scope, never the
        // active one, and never invalidate the active UI session — a failed
        // pinned refresh just surfaces the 401 so the outbox keeps the op.
        val pinnedScope = request.attributes.getOrNull(AuthScopeAttributeKey)
        if (pinnedScope != null) {
            val sentAuth = request.headers[HttpHeaders.Authorization]
            val originalCall = proceed(request)
            if (originalCall.response.status != HttpStatusCode.Unauthorized) {
                return@on originalCall
            }
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
                    val refreshResponse = client.post("${pinnedScope.serverUrl}/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(refreshToken))
                    }
                    if (refreshResponse.status.isSuccess()) {
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
                        // Don't invalidate the active session for a background scope.
                        // Re-check in case a concurrent path refreshed it in flight.
                        val after = tokenManager.getAccessTokenForScope(pinnedScope)?.let { "Bearer $it" }
                        after != null && after != sentAuth
                    }
                } catch (e: Throwable) {
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

        // Capture the access token we will actually SEND with this request.
        // If the response comes back 401, we compare against this snapshot
        // inside the refresh mutex to detect a concurrent refresh that
        // already happened — so N parallel 401s collapse into ONE refresh.
        val tokenBeforeRequest = tokenManager.getAccessToken()

        val originalCall = proceed(request)

        // Only attempt refresh on 401 for non-auth endpoints
        if (originalCall.response.status != HttpStatusCode.Unauthorized) {
            return@on originalCall
        }

        val requestPath = originalCall.request.url.encodedPath
        if (requestPath.endsWith("/auth/refresh") || requestPath.endsWith("/auth/login")) {
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
        val refreshed = refreshMutex.withLock {
            // If the user switched servers between request-send and 401-retry,
            // we are now operating against a different server. Don't try to
            // "refresh" — the refresh token wouldn't be valid for the new
            // server anyway, and we'd risk persisting cross-server tokens.
            val serverIdNow = tokenManager.getCurrentServerId()
            if (serverIdNow != serverIdBeforeRequest) {
                return@withLock false
            }

            val tokenNow = tokenManager.getAccessToken()
            if (tokenNow != null && tokenNow != tokenBeforeRequest) {
                // Another coroutine already refreshed while we were waiting —
                // just retry the original request with the new token.
                return@withLock true
            }

            val refreshToken = tokenManager.getRefreshToken()
            if (refreshToken.isNullOrBlank()) {
                return@withLock false
            }

            try {
                val serverUrl = tokenManager.getServerUrl()
                if (serverUrl.isBlank()) {
                    return@withLock false
                }

                val refreshResponse = client.post("$serverUrl/api/v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(refreshToken))
                }

                // Re-check serverId AFTER the network call as well — the user
                // could have switched while we were waiting on the network.
                // The token write below targets whichever server is active at
                // save time, so a mismatch here means we'd write to the wrong
                // slot.
                val serverIdAfterCall = tokenManager.getCurrentServerId()
                if (serverIdAfterCall != serverIdBeforeRequest) {
                    return@withLock false
                }

                // Re-check sign-out state AFTER the network call too. Logout
                // revokes the access token server-side before clearTokens()
                // runs, so concurrent requests 401 exactly during sign-out and
                // start a refresh with the still-valid refresh token; without
                // this guard the refresh response lands after clearTokens()
                // and saveTokens() silently signs the user back in.
                if (tokenManager.getRefreshToken().isNullOrBlank()) {
                    return@withLock false
                }

                if (refreshResponse.status.isSuccess()) {
                    val tokens = refreshResponse.body<RefreshResponse>()
                    tokenManager.saveTokens(
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken,
                        expiresIn = tokens.expiresIn
                    )
                    true
                } else {
                    // Only auth rejection proves the refresh token is bad.
                    // Gateway/proxy/server failures should keep the session so
                    // a temporary outage does not sign the user out.
                    if (refreshResponse.status.shouldInvalidateSessionAfterRefreshFailure()) {
                        // The [TokenManager.sessionExpired] event emitted by
                        // this call is what the root NavHost observer uses to
                        // route the user back to the login screen; without it,
                        // the UI would stay on Home and keep rendering
                        // "Failed to load..." for every subsequent API call
                        // that now has no credentials.
                        tokenManager.invalidateSession()
                    }
                    false
                }
            } catch (e: Throwable) {
                false
            }
        }

        if (refreshed) {
            // Explicitly replace the Authorization header on the request builder
            // before retrying. `proceed(request)` re-enters the pipeline at Send —
            // the `onRequest` hook (which originally attached the old token) does
            // NOT run a second time, so if we don't update the header here the
            // retry gets sent with the expired Bearer token and the server
            // returns another 401.
            val newAccessToken = tokenManager.getAccessToken()
            if (newAccessToken != null) {
                request.headers.remove(HttpHeaders.Authorization)
                request.header(HttpHeaders.Authorization, "Bearer $newAccessToken")
            }
            proceed(request)
        } else {
            originalCall
        }
    }
}

private fun HttpStatusCode.shouldInvalidateSessionAfterRefreshFailure(): Boolean =
    this == HttpStatusCode.BadRequest ||
        this == HttpStatusCode.Unauthorized ||
        this == HttpStatusCode.Forbidden

private suspend fun HttpRequestBuilder.attachSiloDeviceMetadataHeaders(
    deviceMetadataProvider: DeviceMetadataProvider?,
) {
    val device = deviceMetadataProvider?.current() ?: return
    header("X-Silo-Device-Id", device.id)
    header("X-Silo-Device-Name", device.name)
    header("X-Silo-Device-Platform", device.platform)
    device.clientName?.takeIf { it.isNotBlank() }?.let { header("X-Silo-Client", it) }
    device.clientVersion?.takeIf { it.isNotBlank() }?.let { header("X-Silo-Client-Version", it) }
}
