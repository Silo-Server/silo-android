package org.siloserver.silo.common.player

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.siloserver.silo.model.auth.RefreshRequest
import org.siloserver.silo.model.auth.RefreshResponse
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.CleartextOriginConsent
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.isSameHttpOrigin
import org.siloserver.silo.network.requiresApproval
import java.io.IOException

/**
 * Transport-neutral media credentials and single-flight token refresh.
 *
 * OkHttp, Cronet, and HttpEngine data sources can all consume the same header
 * snapshot and invoke the same refresh operation. Keeping refresh ownership
 * here prevents a future HTTP/3 backend from growing subtly different session
 * invalidation and multi-server race behavior.
 */
class MediaAuthSession(
    private val tokenManager: TokenManager,
    private val refreshClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val cleartextOriginConsent: CleartextOriginConsent? = null,
) {
    private val refreshMutex = Mutex()

    suspend fun isTransportApproved(requestUrl: String): Boolean =
        cleartextOriginConsent?.requiresApproval(requestUrl) != true

    suspend fun snapshot(): MediaAuthSnapshot {
        tokenManager.snapshotCurrentScope()?.let { scope ->
            if (cleartextOriginConsent?.requiresApproval(scope.serverUrl) == true) {
                return MediaAuthSnapshot(null, null, null, scope.serverId, "", null)
            }
            val accessToken = tokenManager.getAccessTokenForScope(scope)
            val scopeAfter = tokenManager.snapshotCurrentScope()
            if (scopeAfter != scope || accessToken.isNullOrBlank()) {
                return MediaAuthSnapshot(
                    accessToken = null,
                    profileId = null,
                    profileToken = null,
                    serverId = scopeAfter?.serverId,
                    serverUrl = "",
                    authScope = null,
                )
            }
            return MediaAuthSnapshot(
                accessToken = accessToken,
                profileId = scope.profileId,
                profileToken = scope.profileToken,
                serverId = scope.serverId,
                serverUrl = scope.serverUrl,
                authScope = scope,
            )
        }

        val serverIdBefore = tokenManager.getCurrentServerId()
        val serverUrl = tokenManager.getServerUrl()
        if (cleartextOriginConsent?.requiresApproval(serverUrl) == true) {
            return MediaAuthSnapshot(null, null, null, tokenManager.getCurrentServerId(), "")
        }
        val accessToken = tokenManager.getAccessToken()
        val profileIdentity = tokenManager.getProfileIdentity()
        val profileId = profileIdentity.profileId
        val profileToken = profileIdentity.profileToken
        val serverIdAfter = tokenManager.getCurrentServerId()
        val serverUrlAfter = tokenManager.getServerUrl()

        // TokenManager exposes the active fields separately. If the user
        // switches servers while they are read, never combine one server's
        // credentials with another server's URL. An empty URL makes every
        // transport's origin check fail closed and also suppresses refresh.
        if (
            serverIdBefore != serverIdAfter ||
            !isSameHttpOrigin(serverUrl, serverUrlAfter)
        ) {
            return MediaAuthSnapshot(
                accessToken = null,
                profileId = null,
                profileToken = null,
                serverId = serverIdAfter,
                serverUrl = "",
            )
        }
        return MediaAuthSnapshot(
            accessToken = accessToken,
            profileId = profileId,
            profileToken = profileToken,
            serverId = serverIdAfter,
            serverUrl = serverUrlAfter,
        )
    }

    suspend fun refreshIfStale(failedSnapshot: MediaAuthSnapshot): Boolean = refreshMutex.withLock {
        failedSnapshot.authScope?.let { failedScope ->
            val currentScope = tokenManager.snapshotCurrentScope()
            if (currentScope != failedScope) return@withLock false
            val currentAccessToken = tokenManager.getAccessTokenForScope(failedScope)
            if (!currentAccessToken.isNullOrBlank() && currentAccessToken != failedSnapshot.accessToken) {
                return@withLock true
            }
            return@withLock attemptRefresh(failedScope)
        }

        val current = snapshot()
        if (current.serverId != failedSnapshot.serverId) {
            return@withLock false
        }
        if (!current.accessToken.isNullOrBlank() && current.accessToken != failedSnapshot.accessToken) {
            return@withLock true
        }
        attemptRefresh(failedSnapshot.serverId)
    }

    private suspend fun attemptRefresh(scope: AuthScopeSnapshot): Boolean {
        val refreshToken = tokenManager.getRefreshTokenForScope(scope) ?: return false
        if (refreshToken.isBlank() || scope.serverUrl.isBlank()) return false
        if (tokenManager.snapshotCurrentScope() != scope) return false

        val request = refreshRequest(scope.serverUrl, refreshToken)
        return try {
            refreshClient.newCall(request).execute().use { response ->
                if (tokenManager.snapshotCurrentScope() != scope) return@use false
                if (tokenManager.getRefreshTokenForScope(scope).isNullOrBlank()) return@use false
                if (!response.isSuccessful) {
                    // A temporary remote-playback overlay is process-only. A
                    // rejected refresh must not remove it and expose the saved
                    // owner's credentials to the still-running media request.
                    if (
                        response.code.shouldInvalidateSessionAfterMediaRefreshFailure() &&
                        scope.credentialGenerationId == null
                    ) {
                        tokenManager.invalidateSessionForScope(scope)
                    }
                    return@use false
                }
                val tokens = decodeRefresh(response.body?.string().orEmpty()) ?: return@use false
                tokenManager.saveTokensForScope(
                    scope = scope,
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    expiresIn = tokens.expiresIn,
                )
                tokenManager.getAccessTokenForScope(scope) == tokens.accessToken
            }
        } catch (_: IOException) {
            false
        }
    }

    private suspend fun attemptRefresh(serverIdBeforeRequest: String?): Boolean {
        val refreshToken = tokenManager.getRefreshToken() ?: return false
        val serverUrl = tokenManager.getServerUrl()
        if (refreshToken.isBlank() || serverUrl.isBlank()) return false
        // Reading the refresh token and server URL is not atomic. A server
        // switch between those reads can otherwise send server A's refresh
        // token to server B before the response-time guard gets a chance to
        // reject it.
        if (tokenManager.getCurrentServerId() != serverIdBeforeRequest) return false

        val request = refreshRequest(serverUrl, refreshToken)

        return try {
            refreshClient.newCall(request).execute().use { response ->
                if (tokenManager.getCurrentServerId() != serverIdBeforeRequest) {
                    return@use false
                }
                // A sign-out that wins the race must not be undone by a late
                // refresh response.
                if (tokenManager.getRefreshToken().isNullOrBlank()) {
                    return@use false
                }
                if (!response.isSuccessful) {
                    if (response.code.shouldInvalidateSessionAfterMediaRefreshFailure()) {
                        tokenManager.invalidateSession()
                    }
                    return@use false
                }
                val tokens = decodeRefresh(response.body?.string().orEmpty()) ?: return@use false
                tokenManager.saveTokens(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    expiresIn = tokens.expiresIn,
                )
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun refreshRequest(serverUrl: String, refreshToken: String): Request =
        Request.Builder()
            .url(serverUrl.trimEnd('/') + "/api/v2/auth/refresh")
            .post(
                json.encodeToString(RefreshRequest(refreshToken))
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()

    private fun decodeRefresh(body: String): RefreshResponse? =
        runCatching { json.decodeFromString<RefreshResponse>(body) }.getOrNull()
}

data class MediaAuthSnapshot(
    val accessToken: String?,
    val profileId: String?,
    val profileToken: String?,
    val serverId: String?,
    val serverUrl: String,
    internal val authScope: AuthScopeSnapshot? = null,
) {
    fun asRequestHeaders(): Map<String, String> = buildMap {
        accessToken?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        profileId?.takeIf { it.isNotBlank() }?.let { put("X-Profile-Id", it) }
        profileToken?.takeIf { it.isNotBlank() }?.let { put("X-Profile-Token", it) }
    }
}

private fun Int.shouldInvalidateSessionAfterMediaRefreshFailure(): Boolean =
    this == 400 || this == 401 || this == 403
