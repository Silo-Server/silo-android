package org.siloserver.silo.common.pairing

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.CleartextOriginConsent
import org.siloserver.silo.network.CleartextOriginNotApprovedException
import org.siloserver.silo.network.requiresApproval

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
    private val cleartextOriginConsent: CleartextOriginConsent? = null,
) : PairingAuthPort {
    private val commitMutex = Mutex()

    override suspend fun persistApprovedSession(
        serverUrl: String,
        serverName: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
    ) = withContext(NonCancellable) {
        commitMutex.withLock {
            if (cleartextOriginConsent?.requiresApproval(serverUrl) == true) {
                throw CleartextOriginNotApprovedException(serverUrl)
            }
            val previousServerId = serverRegistry.activeServerId.value
            val serverId = serverRegistry.addOrUpdate(serverUrl, fetchedName = serverName)
            try {
                // Same-server approval is still an A -> B account boundary.
                // The token manager activates the registry and replaces the
                // complete profile/token identity inside one destructive gate.
                tokenManager.replaceAccountSession(
                    serverId = serverId,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresIn = expiresIn,
                )
            } catch (error: Throwable) {
                if (previousServerId != null && serverRegistry.activeServerId.value != previousServerId) {
                    serverRegistry.switchTo(previousServerId)
                    tokenManager.switchActiveServer(previousServerId)
                } else if (previousServerId == null) {
                    serverRegistry.remove(serverId)
                }
                throw error
            }
        }
    }
}
