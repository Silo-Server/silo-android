package org.siloserver.silo.common.pairing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.network.AccountSessionExpectation
import org.siloserver.silo.network.AccountSignIn
import org.siloserver.silo.network.AccountSessionChangedException
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.CleartextOriginConsent
import org.siloserver.silo.network.CleartextOriginNotApprovedException
import org.siloserver.silo.network.requiresApproval
import org.siloserver.silo.repository.AuthRepository

/**
 * The saved account a pairing sign-in landed in: [accountId] is its
 * [org.siloserver.silo.model.server.AccountEntry.localAccountId] on
 * [serverId]. The same server user signed in twice lands in the same one.
 */
data class PairingCommittedAccount(val serverId: String, val accountId: String)

/**
 * Narrow commit seam for the pairing receiver after a candidate server approves device
 * login. Candidate requests do not mutate global auth state; this seam writes
 * the server and credentials only after approval. Lets tests assert the commit
 * without a real token manager / registry.
 */
interface PairingAuthPort {
    suspend fun captureExpectedIdentity(): AccountSessionExpectation? = null
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
        expectedIdentity: AccountSessionExpectation? = null,
        /** Deployment identity verified at [serverUrl], recorded on the saved server. */
        verifiedServerId: String? = null,
    )

    /**
     * [persistApprovedSession] that also names the server [user] the approval
     * signed in, so the session refreshes that user's saved account on this
     * device instead of adding a second one (multi-account spec §5). Ports
     * that keep no accounts ignore it.
     */
    suspend fun persistApprovedSession(
        serverUrl: String,
        serverName: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        expectedIdentity: AccountSessionExpectation?,
        verifiedServerId: String?,
        user: User?,
    ) = persistApprovedSession(serverUrl, serverName, accessToken, refreshToken, expiresIn, expectedIdentity, verifiedServerId)

    /**
     * [persistApprovedSession] that says which saved account now holds the
     * session, so the receiver can tell how many accounts a pairing session
     * signed in and open the first (contract §B.7). Null from ports that keep
     * no accounts.
     */
    suspend fun commitApprovedSession(
        serverUrl: String,
        serverName: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        expectedIdentity: AccountSessionExpectation?,
        verifiedServerId: String?,
        user: User?,
    ): PairingCommittedAccount? {
        persistApprovedSession(serverUrl, serverName, accessToken, refreshToken, expiresIn, expectedIdentity, verifiedServerId, user)
        return null
    }
}

/**
 * Production adapter matching Apple's receiver-side persist-on-success behavior.
 *
 * [accountOf] reads `/me` at a server with an access token that isn't saved
 * yet (null when it can't be read). An approval from an older server names
 * no user; without the account's server identity the commit can't tell a
 * refresh of a saved account from a new one, so it asks first.
 */
class RegistryPairingAuthPort(
    private val tokenManager: TokenManager,
    private val serverRegistry: ServerRegistry,
    private val cleartextOriginConsent: CleartextOriginConsent? = null,
    private val authRepository: AuthRepository? = null,
    private val accountOf: (suspend (serverUrl: String, accessToken: String) -> User?)? = null,
) : PairingAuthPort {
    private val commitMutex = Mutex()
    override suspend fun captureExpectedIdentity() = tokenManager.captureAccountSessionExpectation()

    override suspend fun persistApprovedSession(
        serverUrl: String,
        serverName: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        expectedIdentity: AccountSessionExpectation?,
        verifiedServerId: String?,
    ) = persistApprovedSession(serverUrl, serverName, accessToken, refreshToken, expiresIn, expectedIdentity, verifiedServerId, user = null)

    override suspend fun persistApprovedSession(
        serverUrl: String,
        serverName: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        expectedIdentity: AccountSessionExpectation?,
        verifiedServerId: String?,
        user: User?,
    ) {
        commitApprovedSession(serverUrl, serverName, accessToken, refreshToken, expiresIn, expectedIdentity, verifiedServerId, user)
    }

    override suspend fun commitApprovedSession(
        serverUrl: String,
        serverName: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        expectedIdentity: AccountSessionExpectation?,
        verifiedServerId: String?,
        user: User?,
    ): PairingCommittedAccount? = withContext(NonCancellable) {
        commitMutex.withLock {
            if (cleartextOriginConsent?.requiresApproval(serverUrl) == true) {
                throw CleartextOriginNotApprovedException(serverUrl)
            }
            // Bounded: the phone waits about 30 s for the result. Without an
            // answer the commit falls back to the background backfill.
            val signedIn = user ?: readAccount(serverUrl, accessToken)
            val previousServerId = serverRegistry.activeServerId.value
            val serverId = serverRegistry.addOrUpdate(serverUrl, fetchedName = serverName)
            val accountId = try {
                // Same-server approval is still an A -> B account boundary.
                // The token manager activates the registry and replaces the
                // complete profile/token identity inside one destructive gate.
                tokenManager.replaceAccountSession(
                    serverId = serverId,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresIn = expiresIn,
                    expectedIdentity = expectedIdentity,
                    // The same server user refreshes its saved account (and
                    // keeps its remembered profile); another user becomes
                    // another account on this server.
                    account = (
                        authRepository?.accountSignIn(serverId, signedIn)
                            ?: AccountSignIn(serverUserId = signedIn?.id, username = signedIn?.username)
                        ).copy(keepsRememberedProfile = true),
                )
            } catch (changed: AccountSessionChangedException) {
                throw changed
            } catch (error: Throwable) {
                if (previousServerId != null && serverRegistry.activeServerId.value != previousServerId) {
                    serverRegistry.switchTo(previousServerId)
                    tokenManager.switchActiveServer(previousServerId)
                    // Restoring is a server switch too; re-establish its contract
                    // verdict like every other switch path, under the same bound
                    // and with the same background replacement on no verdict.
                    authRepository?.refreshServerContractWithFallback(previousServerId)
                } else if (previousServerId == null) {
                    serverRegistry.remove(serverId)
                }
                throw error
            }
            // Pairing is a server switch too: the newly paired server is now
            // active, so establish its v2 contract verdict before the port
            // reports SignedIn — otherwise the entry keeps UNKNOWN (or a
            // stale UPDATE_REQUIRED from an older build) and gated startup
            // consumers act on it. The probe never throws on a failed
            // request, so this cannot roll back a committed session.
            //
            // Bounded: the companion waits only ~30 s for ServerResult while
            // an unanswered request would sit on the client's ~60 s socket
            // timeout, so an unbounded probe here made the phone report
            // failure after the credentials were already committed. No
            // verdict within the bound (timeout or failure) leaves the stored
            // state alone — which passes the gate for UNKNOWN but not for a
            // stale UPDATE_REQUIRED — so, like switchToServer, a replacement
            // probe is handed to the repository's background scope.
            authRepository?.refreshServerContractWithFallback(serverId)
            if (verifiedServerId != null) serverRegistry.setVerifiedServerId(serverId, verifiedServerId)
            if (expectedIdentity != null && tokenManager.captureAccountSessionExpectation()?.generation != expectedIdentity.generation + 1) throw AccountSessionChangedException()
            // An approval that named no user (an older server) whose /me
            // couldn't be read leaves the account without its server
            // identity; fill it in later, in the background.
            if (signedIn == null) authRepository?.backfillAccountIdentitiesInBackground()
            accountId?.let { PairingCommittedAccount(serverId, it) }
        }
    }

    private suspend fun readAccount(serverUrl: String, accessToken: String): User? {
        val read = accountOf ?: return null
        return try {
            withTimeoutOrNull(ACCOUNT_READ_TIMEOUT) { read(serverUrl, accessToken) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        val ACCOUNT_READ_TIMEOUT = 5.seconds
    }
}
