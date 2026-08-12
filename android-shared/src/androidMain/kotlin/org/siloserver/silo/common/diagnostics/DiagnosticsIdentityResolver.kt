package org.siloserver.silo.common.diagnostics

import java.security.MessageDigest
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsStatusResponse
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.DiagnosticsApi
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ProfileRepository

data class SavedDiagnosticsServer(
    val id: String,
    val url: String,
)

fun interface DiagnosticsSavedServerProvider {
    suspend fun activeServer(): SavedDiagnosticsServer?
}

fun interface DiagnosticsStatusProvider {
    suspend fun status(): DiagnosticsStatusResponse?
}

fun interface DiagnosticsAccountProvider {
    suspend fun accountUserId(): String?
}

/** Returns true for a confirmed child, false for a confirmed adult, and null if unresolved. */
fun interface DiagnosticsProfileProvider {
    suspend fun isChild(profileId: String): Boolean?
}

class RegistryDiagnosticsSavedServerProvider(
    private val registry: ServerRegistry,
) : DiagnosticsSavedServerProvider {
    override suspend fun activeServer(): SavedDiagnosticsServer? =
        registry.activeEntry.value?.let { SavedDiagnosticsServer(it.id, it.url) }
}

class ApiDiagnosticsStatusProvider(
    private val api: DiagnosticsApi,
) : DiagnosticsStatusProvider {
    override suspend fun status(): DiagnosticsStatusResponse? =
        (api.getStatus() as? ApiResult.Success)?.data
}

class RepositoryDiagnosticsAccountProvider(
    private val repository: AuthRepository,
) : DiagnosticsAccountProvider {
    override suspend fun accountUserId(): String? =
        (repository.getCurrentUser() as? ApiResult.Success)?.data?.id?.toString()
}

class RepositoryDiagnosticsProfileProvider(
    private val repository: ProfileRepository,
) : DiagnosticsProfileProvider {
    override suspend fun isChild(profileId: String): Boolean? =
        (repository.listProfiles() as? ApiResult.Success)
            ?.data
            ?.firstOrNull { it.id == profileId }
            ?.isChild
}

data class DiagnosticsIdentityKey(
    val binding: DiagnosticsBinding,
    val profileId: String?,
    val ownershipGeneration: Long,
)

data class DiagnosticsCaptureContext(
    val binding: DiagnosticsBinding,
    val profileId: String?,
    val profileEligible: Boolean,
    val noticeVersion: Int,
    val status: DiagnosticsAvailabilityStatus,
    val ownershipGeneration: Long,
    val acceptedSchemaVersions: Set<Int> = setOf(1),
    val maxBundleBytes: Long = Long.MAX_VALUE,
    val maxManifestBytes: Long = Long.MAX_VALUE,
    val retentionDays: Int = 7,
    val localServerId: String? = null,
    val credentialFingerprint: String? = null,
    /** Source profile is retained only in encrypted/local state for privacy gating. */
    val sourceProfileId: String? = profileId,
    val destinationKind: DiagnosticsDestinationKind = DiagnosticsDestinationKind.SELF_HOSTED,
) {
    val identityKey: DiagnosticsIdentityKey = DiagnosticsIdentityKey(
        binding = binding,
        profileId = profileId,
        ownershipGeneration = ownershipGeneration,
    )
}

interface DiagnosticsIdentityResolver {
    suspend fun resolve(requirePersistentCapture: Boolean): DiagnosticsCaptureContext?

    /** Live attestation used immediately before starting a user-requested capture. */
    suspend fun resolveForCapture(requirePersistentCapture: Boolean): DiagnosticsCaptureContext? =
        resolve(requirePersistentCapture)

    /** Live account attestation used immediately before starting a transport. */
    suspend fun resolveForUpload(requirePersistentCapture: Boolean): DiagnosticsCaptureContext? =
        resolve(requirePersistentCapture)

    /** Local-only attestation used before exposing a cached context while the server is offline. */
    suspend fun matchesCachedIdentity(cached: CachedDiagnosticsContext): Boolean = false
}

class DefaultDiagnosticsIdentityResolver(
    private val tokenManager: TokenManager,
    private val identityTransitions: IdentityTransitionBarrier,
    private val savedServerProvider: DiagnosticsSavedServerProvider,
    private val statusProvider: DiagnosticsStatusProvider,
    private val accountProvider: DiagnosticsAccountProvider,
    private val profileProvider: DiagnosticsProfileProvider,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) : DiagnosticsIdentityResolver {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    override suspend fun resolve(requirePersistentCapture: Boolean): DiagnosticsCaptureContext? {
        if (requirePersistentCapture && tokenManager.hasTemporaryScope()) return null

        for (attempt in 0 until maxAttempts) {
            val generation = identityTransitions.generation.value
            val server = savedServerProvider.activeServer()
                ?.takeIf { it.id.isNotBlank() && it.url.isNotBlank() }
            if (server == null) {
                if (identityTransitions.generation.value != generation) continue
                return null
            }
            val currentServerId = tokenManager.getCurrentServerId()
            if (currentServerId != null && currentServerId != server.id) {
                if (identityTransitions.generation.value != generation) continue
                return null
            }
            val currentUrl = tokenManager.getServerUrl().trimEnd('/')
            if (currentUrl.isBlank() || currentUrl != server.url.trimEnd('/')) {
                if (identityTransitions.generation.value != generation) continue
                return null
            }
            if (tokenManager.getAccessToken().isNullOrBlank()) {
                if (identityTransitions.generation.value != generation) continue
                return null
            }

            val status = statusProvider.status()
            if (status == null) {
                if (identityTransitions.generation.value != generation) continue
                return null
            }
            val accountUserId = accountProvider.accountUserId()?.takeIf(String::isNotBlank)
            if (accountUserId == null) {
                if (identityTransitions.generation.value != generation) continue
                return null
            }
            val profileId = tokenManager.getProfileId()
            val profileEligible = if (profileId == null) {
                true
            } else {
                val child = profileProvider.isChild(profileId)
                if (child == null) {
                    if (identityTransitions.generation.value != generation) continue
                    return null
                }
                !child
            }

            val credentialFingerprint = currentCredentialFingerprint()
            if (identityTransitions.generation.value != generation) continue
            val context = DiagnosticsCaptureContext(
                binding = DiagnosticsBinding(status.serverInstanceId, accountUserId),
                profileId = profileId,
                profileEligible = profileEligible,
                noticeVersion = status.consentNoticeVersion,
                status = status.status,
                ownershipGeneration = generation,
                acceptedSchemaVersions = status.acceptedSchemaVersions.toSet(),
                maxBundleBytes = status.maxBundleBytes,
                maxManifestBytes = status.maxManifestBytes,
                retentionDays = status.retentionDays,
                localServerId = server.id,
                credentialFingerprint = credentialFingerprint,
            )
            return context
        }
        return null
    }

    override suspend fun matchesCachedIdentity(cached: CachedDiagnosticsContext): Boolean {
        if (tokenManager.hasTemporaryScope()) return false
        val localServerId = cached.localServerId?.takeIf(String::isNotBlank) ?: return false
        val cachedCredential = cached.credentialFingerprint?.takeIf(String::isNotBlank) ?: return false
        for (attempt in 0 until maxAttempts) {
            val generation = identityTransitions.generation.value
            val server = savedServerProvider.activeServer() ?: return false
            val matches = server.id == localServerId &&
                tokenManager.getCurrentServerId() == localServerId &&
                tokenManager.getServerUrl().trimEnd('/') == server.url.trimEnd('/') &&
                !tokenManager.getAccessToken().isNullOrBlank() &&
                currentCredentialFingerprint()?.let { current ->
                    MessageDigest.isEqual(current.encodeToByteArray(), cachedCredential.encodeToByteArray())
                } == true &&
                tokenManager.getProfileId() == cached.profileId
            if (identityTransitions.generation.value != generation) continue
            return matches
        }
        return false
    }

    private suspend fun currentCredentialFingerprint(): String? {
        val credential = tokenManager.getRefreshToken()?.takeIf(String::isNotBlank)
            ?: tokenManager.getAccessToken()?.takeIf(String::isNotBlank)
            ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(credential.encodeToByteArray())
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
