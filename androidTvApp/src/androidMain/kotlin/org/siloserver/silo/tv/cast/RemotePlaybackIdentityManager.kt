package org.siloserver.silo.tv.cast

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.cast.SiloCastHandoffChallenge
import org.siloserver.silo.cast.SiloCastHandoffOffer
import org.siloserver.silo.cast.SiloCastHandoffReady
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TemporaryAuthScope
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.DeviceLoginApi

/**
 * Owns the process-only identity installed while content launched by a phone
 * is playing on Android TV. Saved servers and their credentials are never
 * changed: the normal HTTP/player graph reads this short-lived overlay through
 * [TokenManager], then automatically sees the saved TV identity again on end.
 */
class RemotePlaybackIdentityManager(
    private val deviceLoginApi: DeviceLoginApi,
    private val tokenManager: TokenManager,
    private val deviceNameProvider: () -> String,
) {
    data class ActiveIdentity(
        val generationId: String,
        val serverId: String,
        val serverUrl: String,
        val serverName: String?,
        val profileId: String,
        val profileName: String?,
        val controllerDeviceId: String,
        val controllerDeviceName: String?,
        val expiresAtEpochMs: Long,
    )

    private val mutex = Mutex()

    @Volatile
    var activeIdentity: ActiveIdentity? = null
        private set

    fun matches(offer: SiloCastHandoffOffer, controllerDeviceId: String): Boolean {
        val active = activeIdentity ?: return false
        return active.serverId == offer.serverId &&
            active.profileId == offer.profileId &&
            active.controllerDeviceId == controllerDeviceId
    }

    suspend fun prepare(
        offer: SiloCastHandoffOffer,
        controllerDeviceId: String,
        controllerDeviceName: String?,
        onChallenge: suspend (SiloCastHandoffChallenge) -> Unit,
    ): SiloCastHandoffReady = mutex.withLock {
        validateOffer(offer)

        activeIdentity?.takeIf { matches(offer, controllerDeviceId) }?.let { active ->
            return@withLock active.toReady(offer.requestId, reused = true)
        }

        endLocked()

        val capability = deviceLoginApi.remotePlaybackCapabilityAt(offer.serverURL).successOrThrow()
        require(capability.remotePlaybackHandoff && SiloCastProtocol.version in capability.protocolVersions) {
            "The phone's Silo server does not support remote playback handoff."
        }

        val started = deviceLoginApi.startRemotePlaybackAt(
            serverUrl = offer.serverURL,
            deviceName = deviceNameProvider(),
            // Matches the X-Silo-Device-Platform header spelling.
            devicePlatform = "android-tv",
        ).successOrThrow()
        require(started.clientPurpose == "remote_playback" && started.temporary == true) {
            "The server did not create a temporary remote playback session."
        }

        onChallenge(
            SiloCastHandoffChallenge(
                requestId = offer.requestId,
                userCode = started.userCode,
                matchCode = started.matchCode,
                expiresAt = started.expiresAt,
            ),
        )

        val deadlineMs = System.currentTimeMillis() + started.expiresIn.coerceAtLeast(1) * 1_000L
        while (System.currentTimeMillis() < deadlineMs) {
            when (val result = deviceLoginApi.pollDeviceLoginAt(offer.serverURL, started.deviceCode)) {
                is ApiResult.Success -> {
                    val poll = result.data
                    when (poll.status) {
                        "approved" -> {
                            val accessToken = poll.accessToken.requireValue("access token")
                            val refreshToken = poll.refreshToken.requireValue("refresh token")
                            val profileId = poll.profileId.requireValue("profile")
                            val profileToken = poll.profileToken.requireValue("profile token")
                            require(poll.temporary == true && profileId == offer.profileId) {
                                "The server approved a different or non-temporary profile."
                            }
                            val expiresAtMs = poll.sessionExpiresAt?.let(::parseInstantMillis)
                                ?: (System.currentTimeMillis() + DEFAULT_SESSION_MS)
                            val generationId = UUID.randomUUID().toString()
                            tokenManager.beginTemporaryScope(
                                TemporaryAuthScope(
                                    generationId = generationId,
                                    serverId = offer.serverId,
                                    serverUrl = normalizedUrl(offer.serverURL),
                                    accessToken = accessToken,
                                    refreshToken = refreshToken,
                                    profileId = profileId,
                                    profileToken = profileToken,
                                    // The SESSION deadline — hours past the
                                    // access token it was issued with, so it
                                    // must never be read as a token expiry.
                                    expiresAtEpochMs = expiresAtMs,
                                    // The token's own deadline, kept separate.
                                    // Null when the server omits expires_in,
                                    // which keeps this overlay reactive-only
                                    // rather than guessing off the session.
                                    accessTokenExpiresAtEpochMs = poll.expiresIn
                                        ?.let { System.currentTimeMillis() + it * 1000L },
                                    accessTokenLifetimeMs = poll.expiresIn?.times(1000L),
                                ),
                            )
                            val active = ActiveIdentity(
                                generationId = generationId,
                                serverId = offer.serverId,
                                serverUrl = normalizedUrl(offer.serverURL),
                                serverName = offer.serverName,
                                profileId = profileId,
                                profileName = offer.profileName,
                                controllerDeviceId = controllerDeviceId,
                                controllerDeviceName = controllerDeviceName,
                                expiresAtEpochMs = expiresAtMs,
                            )
                            activeIdentity = active
                            return@withLock active.toReady(offer.requestId, reused = false)
                        }
                        "denied" -> error("Remote playback handoff was denied.")
                        "expired", "consumed" -> error("Remote playback handoff expired.")
                        "pending" -> delay((poll.pollAfter ?: started.interval).coerceAtLeast(1) * 1_000L)
                        else -> error("The server returned an unknown remote playback status.")
                    }
                }
                is ApiResult.Error -> {
                    if (result.code == 404) error("Remote playback handoff expired.")
                    delay(started.interval.coerceAtLeast(1) * 1_000L)
                }
                is ApiResult.NetworkError -> delay(started.interval.coerceAtLeast(1) * 1_000L)
            }
        }
        error("Remote playback handoff expired.")
    }

    suspend fun end() = mutex.withLock { endLocked() }

    private suspend fun endLocked() {
        val active = activeIdentity
        try {
            if (active != null && tokenManager.hasTemporaryScope()) {
                deviceLoginApi.endRemotePlayback(
                    AuthScopeSnapshot(
                        serverId = active.serverId,
                        serverUrl = active.serverUrl,
                        profileId = active.profileId,
                        profileToken = null,
                        credentialGenerationId = active.generationId,
                    ),
                )
            }
        } finally {
            // Local credential teardown is mandatory even if the best-effort
            // server logout throws or the transport disappears mid-request.
            tokenManager.endTemporaryScope()
            activeIdentity = null
        }
    }

    private fun validateOffer(offer: SiloCastHandoffOffer) {
        val normalized = normalizedUrl(offer.serverURL)
        require(normalized.isNotBlank() && offer.profileId.isNotBlank()) {
            "The phone sent an invalid server or profile."
        }
        require(AndroidServerRegistry.idFor(normalized) == offer.serverId) {
            "The phone's server identity does not match its URL."
        }
    }

    private fun ActiveIdentity.toReady(requestId: String, reused: Boolean) = SiloCastHandoffReady(
        requestId = requestId,
        serverId = serverId,
        profileId = profileId,
        sessionExpiresAt = Instant.ofEpochMilli(expiresAtEpochMs).toString(),
        reused = reused,
    )

    // Wire identity follows silo-apple's ServerRegistry contract exactly:
    // trim whitespace and trailing slashes, but do not otherwise rewrite the
    // string. Android's persisted registry additionally lowercases scheme/host;
    // applying that Android-only normalization to an iPhone offer would change
    // its base64url server id and reject an otherwise valid cross-platform handoff.
    private fun normalizedUrl(url: String): String = url.trim().trimEnd('/')

    private fun parseInstantMillis(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }
            .getOrDefault(System.currentTimeMillis() + DEFAULT_SESSION_MS)

    private fun String?.requireValue(label: String): String =
        this?.takeIf { it.isNotBlank() } ?: error("The approved remote session omitted its $label.")

    private fun <T> ApiResult<T>.successOrThrow(): T = when (this) {
        is ApiResult.Success -> data
        is ApiResult.Error -> error(message.ifBlank { "Server returned HTTP $code." })
        is ApiResult.NetworkError -> throw exception
    }

    private companion object {
        const val DEFAULT_SESSION_MS = 24 * 60 * 60 * 1_000L
    }
}
