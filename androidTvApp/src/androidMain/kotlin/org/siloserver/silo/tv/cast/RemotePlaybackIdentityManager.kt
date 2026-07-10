package org.siloserver.silo.tv.cast

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import org.siloserver.silo.cast.SiloCastHandoffChallenge
import org.siloserver.silo.cast.SiloCastHandoffOffer
import org.siloserver.silo.cast.SiloCastHandoffReady
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.common.settings.LibraryPlaybackPrefsStore
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse
import org.siloserver.silo.model.auth.DeviceLoginPollRequest
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartRequest
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.model.auth.DeviceLoginStatus
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TemporaryAuthScope
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.skipSiloAuth
import org.siloserver.silo.tv.watchnext.WatchNextSeeder

data class RemotePlaybackIdentity(
    val generationId: String,
    val serverId: String,
    val serverUrl: String,
    val serverName: String?,
    val profileId: String,
    val profileName: String?,
    val controllerDeviceId: String,
    val controllerDeviceName: String?,
    val usesDifferentServer: Boolean,
    val sessionExpiresAtEpochMs: Long,
)

/** Owns Android TV's playback-scoped, process-only phone identity. */
class RemotePlaybackIdentityManager(
    private val tokenManager: TokenManager,
    private val serverRegistry: ServerRegistry,
    private val authenticatedClient: HttpClient,
    private val overlayPrefsStore: OverlayPrefsStore,
    private val libraryPlaybackPrefsStore: LibraryPlaybackPrefsStore,
    private val watchNextSeeder: WatchNextSeeder,
    private val deviceNameProvider: () -> String,
) {
    @Volatile
    var activeIdentity: RemotePlaybackIdentity? = null
        private set

    private val transport = ExplicitDeviceLoginTransport(authenticatedClient)

    val effectiveServerId: String?
        get() = activeIdentity?.serverId ?: serverRegistry.activeServerId.value

    val effectiveServerName: String?
        get() = activeIdentity?.serverName ?: serverRegistry.activeEntry.value?.displayName

    fun matches(offer: SiloCastHandoffOffer, controllerDeviceId: String): Boolean =
        activeIdentity?.let {
            it.serverId == offer.serverId &&
                it.profileId == offer.profileId &&
                it.controllerDeviceId == controllerDeviceId
        } == true

    suspend fun prepare(
        offer: SiloCastHandoffOffer,
        controllerDeviceId: String,
        controllerDeviceName: String?,
        onChallenge: suspend (SiloCastHandoffChallenge) -> Unit,
    ): SiloCastHandoffReady {
        val normalizedUrl = validateOffer(offer)
        activeIdentity?.takeIf { matches(offer, controllerDeviceId) }?.let { identity ->
            return SiloCastHandoffReady(
                requestId = offer.requestId,
                serverId = identity.serverId,
                profileId = identity.profileId,
                sessionExpiresAt = Instant.ofEpochMilli(identity.sessionExpiresAtEpochMs).toString(),
                reused = true,
            )
        }

        val capability = transport.capability(normalizedUrl)
        require(capability.remotePlaybackHandoff && SiloCastProtocol.version in capability.protocolVersions) {
            "Update the phone's Silo server to use profile handoff."
        }
        val started = transport.start(normalizedUrl, deviceNameProvider())
        require(started.clientPurpose == "remote_playback" && started.temporary == true) {
            "The server does not support temporary profile handoff."
        }
        onChallenge(
            SiloCastHandoffChallenge(
                requestId = offer.requestId,
                userCode = started.userCode,
                matchCode = started.matchCode,
                expiresAt = started.expiresAt,
            ),
        )

        val deadline = System.currentTimeMillis() + started.expiresIn * 1_000L
        while (System.currentTimeMillis() < deadline) {
            val poll = transport.poll(normalizedUrl, started.deviceCode)
            when (DeviceLoginStatus.fromWire(poll.status)) {
                DeviceLoginStatus.Approved -> {
                    val access = poll.accessToken?.takeIf { it.isNotBlank() }
                        ?: error("The handoff response did not include an access token.")
                    val refresh = poll.refreshToken?.takeIf { it.isNotBlank() }
                        ?: error("The handoff response did not include a refresh token.")
                    val profileToken = poll.profileToken?.takeIf { it.isNotBlank() }
                        ?: error("The handoff response did not include a profile token.")
                    require(poll.temporary == true && poll.profileId == offer.profileId) {
                        "The server activated a different profile."
                    }
                    val expiresAt = poll.sessionExpiresAt
                        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                        ?: (System.currentTimeMillis() + MAX_SESSION_MS)
                    if (activeIdentity != null) {
                        end()
                    }
                    activate(
                        identity = RemotePlaybackIdentity(
                            generationId = UUID.randomUUID().toString(),
                            serverId = offer.serverId,
                            serverUrl = normalizedUrl,
                            serverName = offer.serverName,
                            profileId = offer.profileId,
                            profileName = offer.profileName,
                            controllerDeviceId = controllerDeviceId,
                            controllerDeviceName = controllerDeviceName,
                            usesDifferentServer = offer.serverId != serverRegistry.activeServerId.value,
                            sessionExpiresAtEpochMs = expiresAt,
                        ),
                        accessToken = access,
                        refreshToken = refresh,
                        profileToken = profileToken,
                    )
                    return SiloCastHandoffReady(
                        requestId = offer.requestId,
                        serverId = offer.serverId,
                        profileId = offer.profileId,
                        sessionExpiresAt = Instant.ofEpochMilli(expiresAt).toString(),
                        reused = false,
                    )
                }
                DeviceLoginStatus.Denied -> error("Profile handoff was denied.")
                DeviceLoginStatus.Expired,
                DeviceLoginStatus.Consumed,
                -> error("Profile handoff expired.")
                DeviceLoginStatus.Pending,
                DeviceLoginStatus.Unknown,
                -> delay(((poll.pollAfter ?: started.interval).coerceAtLeast(1)) * 1_000L)
            }
        }
        error("Profile handoff expired.")
    }

    suspend fun end() {
        if (tokenManager.getTemporaryScope() == null && activeIdentity == null) return
        runCatching { authenticatedClient.post("/api/v1/auth/logout") }
        tokenManager.endTemporaryScope()
        activeIdentity = null
        clearIdentityCaches()
    }

    private suspend fun activate(
        identity: RemotePlaybackIdentity,
        accessToken: String,
        refreshToken: String,
        profileToken: String,
    ) {
        clearIdentityCaches()
        tokenManager.beginTemporaryScope(
            TemporaryAuthScope(
                serverId = identity.serverId,
                serverUrl = identity.serverUrl,
                accessToken = accessToken,
                refreshToken = refreshToken,
                profileId = identity.profileId,
                profileToken = profileToken,
                controllerDeviceId = identity.controllerDeviceId,
                expiresAtEpochMs = identity.sessionExpiresAtEpochMs,
            ),
        )
        activeIdentity = identity
    }

    private fun validateOffer(offer: SiloCastHandoffOffer): String {
        val normalized = AndroidServerRegistry.normalizeUrl(offer.serverURL)
        val uri = runCatching { URI(normalized) }.getOrNull()
        require(
            normalized.isNotBlank() &&
                uri != null &&
                uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                offer.profileId.isNotBlank() &&
                AndroidServerRegistry.idFor(normalized) == offer.serverId,
        ) { "The phone sent an invalid server or profile." }
        return normalized
    }

    private fun clearIdentityCaches() {
        overlayPrefsStore.clear()
        libraryPlaybackPrefsStore.clear()
        watchNextSeeder.clear()
    }

    private companion object {
        const val MAX_SESSION_MS = 24 * 60 * 60 * 1_000L
    }
}

/** Public start/poll transport pinned to the offered origin and carrying no TV credentials. */
private class ExplicitDeviceLoginTransport(
    private val client: HttpClient,
) {

    suspend fun capability(serverUrl: String): DeviceLoginCapabilityResponse =
        client.get("$serverUrl/api/v1/auth/device/capability") {
            skipSiloAuth()
        }.checkedBody()

    suspend fun start(serverUrl: String, deviceName: String): DeviceLoginStartResponse =
        client.post("$serverUrl/api/v1/auth/device/start") {
            skipSiloAuth()
            contentType(ContentType.Application.Json)
            setBody(
                DeviceLoginStartRequest(
                    deviceName = deviceName,
                    devicePlatform = "android-tv",
                    clientPurpose = "remote_playback",
                    temporary = true,
                ),
            )
        }.checkedBody()

    suspend fun poll(serverUrl: String, deviceCode: String): DeviceLoginPollResponse =
        client.post("$serverUrl/api/v1/auth/device/poll") {
            skipSiloAuth()
            contentType(ContentType.Application.Json)
            setBody(DeviceLoginPollRequest(deviceCode))
        }.checkedBody()

    private suspend inline fun <reified T> io.ktor.client.statement.HttpResponse.checkedBody(): T {
        check(status.isSuccess()) { "The phone's Silo server returned HTTP ${status.value}." }
        return body()
    }
}
