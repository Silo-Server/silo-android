package org.siloserver.silo.common.pairing

import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.ServerRegistry

/**
 * Narrow commit seam for the pairing receiver after a candidate server approves device
 * login. Candidate requests do not mutate global auth state; this seam writes
 * the server and credentials only after approval. Lets tests assert the commit
 * without a real token manager / registry.
 */
interface PairingAuthPort {
    /**
     * Commit an approved account session. Reauthorizing the same URL is an
     * account boundary, so old profile selection/token state must not survive.
     */
    suspend fun persistApprovedSession(
        serverUrl: String,
        serverName: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
    )
}

/** Production adapter matching Apple's receiver-side persist-on-success behavior. */
class RegistryPairingAuthPort(
    private val tokenManager: TokenManager,
    private val serverRegistry: ServerRegistry,
) : PairingAuthPort {
    override suspend fun persistApprovedSession(
        serverUrl: String,
        serverName: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
    ) {
        val serverId = serverRegistry.addOrUpdate(serverUrl, fetchedName = serverName)
        serverRegistry.setProfileId(serverId, null)
        serverRegistry.switchTo(serverId)
        tokenManager.switchActiveServer(serverId)
        tokenManager.setProfileId(null)
        tokenManager.setProfileToken(null)
        tokenManager.saveTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = expiresIn,
        )
    }
}
