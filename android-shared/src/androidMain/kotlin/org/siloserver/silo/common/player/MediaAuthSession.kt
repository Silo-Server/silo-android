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
import org.siloserver.silo.network.TokenManager
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
) {
    private val refreshMutex = Mutex()

    suspend fun snapshot(): MediaAuthSnapshot = MediaAuthSnapshot(
        accessToken = tokenManager.getAccessToken(),
        profileId = tokenManager.getProfileId(),
        profileToken = tokenManager.getProfileToken(),
        serverId = tokenManager.getCurrentServerId(),
    )

    suspend fun refreshIfStale(failedSnapshot: MediaAuthSnapshot): Boolean = refreshMutex.withLock {
        val current = snapshot()
        if (current.serverId != failedSnapshot.serverId) {
            return@withLock false
        }
        if (!current.accessToken.isNullOrBlank() && current.accessToken != failedSnapshot.accessToken) {
            return@withLock true
        }
        attemptRefresh(failedSnapshot.serverId)
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

        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/api/v1/auth/refresh")
            .post(
                json.encodeToString(RefreshRequest(refreshToken))
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()

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
                val tokens = runCatching {
                    json.decodeFromString<RefreshResponse>(response.body?.string().orEmpty())
                }.getOrNull() ?: return@use false
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
}

data class MediaAuthSnapshot(
    val accessToken: String?,
    val profileId: String?,
    val profileToken: String?,
    val serverId: String?,
) {
    fun asRequestHeaders(): Map<String, String> = buildMap {
        accessToken?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        profileId?.takeIf { it.isNotBlank() }?.let { put("X-Profile-Id", it) }
        profileToken?.takeIf { it.isNotBlank() }?.let { put("X-Profile-Token", it) }
    }
}

private fun Int.shouldInvalidateSessionAfterMediaRefreshFailure(): Boolean =
    this == 400 || this == 401 || this == 403
