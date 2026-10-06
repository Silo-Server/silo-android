package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.apiv2.ApiV2Probe
import org.siloserver.silo.network.authScope
import org.siloserver.silo.network.singleAttempt
import org.siloserver.silo.network.skipSiloAuth
import org.siloserver.silo.pairing.PairingEndpoint

/**
 * The deployment identity contract (silo-server
 * `docs/architecture/server-identity.md`): `GET /api/v2/system/identity` is
 * public and answers one stable `server_id` at every address of a deployment;
 * `GET /api/v2/system/connections` is authenticated and lists the addresses the
 * deployment offers. The id is self-asserted, so it only decides which
 * addresses are worth trying and which saved server a link or a TV means.
 * Device-login approval remains the proof that two addresses share a backend.
 *
 * Mirrors silo-apple `ServerIdentity.swift`.
 */
interface ServerIdentityApi {
    /** Classify one address: the identity it reports, an older server, or unreachable. */
    suspend fun probeIdentity(serverUrl: String): ServerIdentityProbe

    /**
     * The addresses a saved server offers, read with that server's own
     * credentials through [scope]. Null when the feature is unavailable or the
     * request fails; callers then offer the saved URL alone.
     */
    suspend fun connections(scope: AuthScopeSnapshot): ServerConnections?
}

sealed interface ServerIdentityProbe {
    /** The address answered the identity operation. */
    data class Identity(val serverId: String) : ServerIdentityProbe

    /** The address answered, but it predates the identity contract. */
    data object UnsupportedServer : ServerIdentityProbe

    /** The address could not be reached, or did not answer like a Silo server. */
    data object Unreachable : ServerIdentityProbe
}

/** `GET /api/v2/system/connections`, reduced to what other devices can use. */
data class ServerConnections(
    val serverId: String,
    /** Addresses another device may try, in the server's order, de-duplicated. */
    val endpoints: List<PairingEndpoint>,
)

@Serializable
internal data class ServerIdentityV2(
    @SerialName("server_id") val serverId: String,
)

@Serializable
internal data class ServerConnectionsV2(
    val state: String? = null,
    val allowed: Boolean? = null,
    @SerialName("server_id") val serverId: String,
    val endpoints: List<Endpoint> = emptyList(),
) {
    @Serializable
    internal data class Endpoint(
        val kind: String,
        val url: String? = null,
        val provider: String? = null,
        @SerialName("display_name") val displayName: String? = null,
        val state: String? = null,
    ) {
        /**
         * Only a connected provider carries a URL; a public endpoint always
         * does. Anything without one cannot be offered to another device.
         */
        fun usable(): PairingEndpoint? {
            val url = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when (kind) {
                "public" -> PairingEndpoint.of(url = url, kind = PairingEndpoint.Kind.Public)
                "provider" -> PairingEndpoint.of(
                    url = url,
                    kind = PairingEndpoint.Kind.Provider,
                    provider = provider,
                    displayName = displayName,
                )
                else -> null
            }
        }
    }

    val isAvailable: Boolean get() = state == "available" && allowed != false

    fun domain(): ServerConnections {
        val seen = mutableSetOf<String>()
        return ServerConnections(
            serverId = serverId,
            endpoints = endpoints.mapNotNull { it.usable() }.filter { seen.add(it.url) },
        )
    }
}

class DefaultServerIdentityApi(private val client: HttpClient) : ServerIdentityApi {

    override suspend fun probeIdentity(serverUrl: String): ServerIdentityProbe {
        val base = serverUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ServerIdentityProbe.Unreachable
        return try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                val response = client.get("$base$IDENTITY_PATH") {
                    skipSiloAuth()
                    singleAttempt()
                }
                val body = response.bodyAsText()
                when {
                    response.status.isSuccess() -> {
                        val id = SiloJson.decodeFromString(ServerIdentityV2.serializer(), body)
                            .serverId.trim()
                        if (id.isEmpty()) ServerIdentityProbe.Unreachable else ServerIdentityProbe.Identity(id)
                    }
                    response.status == HttpStatusCode.NotFound && ApiV2Probe.isLegacyNotFound(body) ->
                        ServerIdentityProbe.UnsupportedServer
                    else -> ServerIdentityProbe.Unreachable
                }
            } ?: ServerIdentityProbe.Unreachable
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ServerIdentityProbe.Unreachable
        }
    }

    override suspend fun connections(scope: AuthScopeSnapshot): ServerConnections? = try {
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            val response = client.get(CONNECTIONS_PATH) {
                authScope(scope)
                singleAttempt()
            }
            if (!response.status.isSuccess()) return@withTimeoutOrNull null
            SiloJson.decodeFromString(ServerConnectionsV2.serializer(), response.bodyAsText())
                .takeIf { it.isAvailable }
                ?.domain()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        const val IDENTITY_PATH = "/api/v2/system/identity"
        const val CONNECTIONS_PATH = "/api/v2/system/connections"

        /** A TV probing several candidate addresses in turn must fail fast on the dead ones. */
        const val PROBE_TIMEOUT_MS = 8_000L
    }
}
