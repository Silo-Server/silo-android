package org.siloserver.silo.common.diagnostics

import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.HostedDiagnosticsApi
import org.siloserver.silo.network.api.HostedDiagnosticsApiResult
import org.siloserver.silo.network.api.HostedDiagnosticsAvailability
import org.siloserver.silo.network.api.HostedDiagnosticsCapabilities
import org.siloserver.silo.network.api.HostedDiagnosticsInstallationRequest
import org.siloserver.silo.network.api.HostedDiagnosticsInstallationResponse

enum class DiagnosticsDestinationKind {
    HOSTED,
    SELF_HOSTED,
}

const val HOSTED_DIAGNOSTICS_COLLECTOR_ID = "silo-public-diagnostics-v1"
const val HOSTED_DIAGNOSTICS_RETENTION_DAYS = 30

interface HostedDiagnosticsCapabilitiesStore {
    suspend fun load(): HostedDiagnosticsCapabilities?
    suspend fun save(capabilities: HostedDiagnosticsCapabilities)
}

class HostedDiagnosticsCapabilitiesRepository(
    private val store: HostedDiagnosticsCapabilitiesStore,
    private val api: HostedDiagnosticsApi,
) {
    suspend fun local(): HostedDiagnosticsCapabilities =
        store.load()?.takeIf { it.isUsable() } ?: conservativeDefaults()

    suspend fun refresh(): HostedDiagnosticsApiResult<HostedDiagnosticsCapabilities> =
        when (val result = api.capabilities()) {
            is HostedDiagnosticsApiResult.Success -> {
                if (!result.value.isUsable()) {
                    HostedDiagnosticsApiResult.Failure(502, "invalid_capabilities", "Invalid collector capabilities")
                } else {
                    runCatching { store.save(result.value) }
                    result
                }
            }
            is HostedDiagnosticsApiResult.Failure -> result
            is HostedDiagnosticsApiResult.NetworkError -> result
        }

    private fun conservativeDefaults() = HostedDiagnosticsCapabilities(
        status = HostedDiagnosticsAvailability.AVAILABLE,
        collectorId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
        acceptedSchemaVersions = listOf(1),
        maxBundleBytes = 10L * 1_024 * 1_024,
        maxManifestBytes = 64L * 1_024,
        retentionDays = HOSTED_DIAGNOSTICS_RETENTION_DAYS,
        consentNoticeVersion = 1,
    )

    private fun HostedDiagnosticsCapabilities.isUsable(): Boolean =
        collectorId == HOSTED_DIAGNOSTICS_COLLECTOR_ID &&
            acceptedSchemaVersions.isNotEmpty() &&
            maxBundleBytes > 0 &&
            maxManifestBytes > 0 &&
            retentionDays == HOSTED_DIAGNOSTICS_RETENTION_DAYS &&
            consentNoticeVersion > 0
}

data class HostedDiagnosticsCredentials(
    val installationId: String,
    val installationToken: String,
) {
    init {
        require(installationId.isNotBlank())
        require(installationToken.isNotBlank())
    }
}

interface HostedDiagnosticsCredentialStore {
    suspend fun load(): HostedDiagnosticsCredentials?
    suspend fun save(credentials: HostedDiagnosticsCredentials)
    suspend fun clear()
}

/** The injected preferences instance is the app's Android Keystore-backed encrypted store. */
class EncryptedPreferencesHostedDiagnosticsCredentialStore(
    private val encryptedPreferences: SharedPreferences,
) : HostedDiagnosticsCredentialStore {
    override suspend fun load(): HostedDiagnosticsCredentials? = synchronized(encryptedPreferences) {
        val id = encryptedPreferences.getString(INSTALLATION_ID_KEY, null)?.takeIf(String::isNotBlank)
        val token = encryptedPreferences.getString(INSTALLATION_TOKEN_KEY, null)?.takeIf(String::isNotBlank)
        if (id == null || token == null) null else HostedDiagnosticsCredentials(id, token)
    }

    override suspend fun save(credentials: HostedDiagnosticsCredentials) {
        check(
            encryptedPreferences.edit()
                .putString(INSTALLATION_ID_KEY, credentials.installationId)
                .putString(INSTALLATION_TOKEN_KEY, credentials.installationToken)
                .commit(),
        ) { "unable to persist hosted diagnostics credentials" }
    }

    override suspend fun clear() {
        check(
            encryptedPreferences.edit()
                .remove(INSTALLATION_ID_KEY)
                .remove(INSTALLATION_TOKEN_KEY)
                .commit(),
        ) { "unable to clear hosted diagnostics credentials" }
    }

    internal companion object {
        const val INSTALLATION_ID_KEY = "diagnostics.hosted.installation_id"
        const val INSTALLATION_TOKEN_KEY = "diagnostics.hosted.installation_token"
    }
}

class HostedDiagnosticsInstallationManager(
    private val store: HostedDiagnosticsCredentialStore,
    private val api: HostedDiagnosticsApi,
    private val environment: ExitReportEnvironment,
    private val appId: String = "org.siloserver.silo",
) {
    suspend fun current(): HostedDiagnosticsCredentials? = store.load()

    suspend fun getOrCreate(): HostedDiagnosticsCredentials? {
        store.load()?.let { return it }
        val request = HostedDiagnosticsInstallationRequest(
            platform = environment.platform.wireValue(),
            appId = appId,
            appVersion = environment.appVersion,
            appBuild = environment.appBuild,
        )
        val created = when (val result = api.createInstallation(request)) {
            is HostedDiagnosticsApiResult.Success -> result.value
            is HostedDiagnosticsApiResult.Failure,
            is HostedDiagnosticsApiResult.NetworkError,
            -> return null
        }
        val credentials = created.toCredentialsOrNull() ?: return null
        return try {
            store.save(credentials)
            credentials
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun clear() = store.clear()

    private fun HostedDiagnosticsInstallationResponse.toCredentialsOrNull(): HostedDiagnosticsCredentials? =
        runCatching { HostedDiagnosticsCredentials(installationId, installationToken) }.getOrNull()
}

class DestinationAwareDiagnosticsRedactionTokenProvider(
    private val tokenManager: TokenManager,
    private val hostedInstallationToken: suspend () -> String?,
) : DiagnosticsRedactionTokenProvider {
    override suspend fun tokens(destinationKind: DiagnosticsDestinationKind): List<String> = buildList {
        add(tokenManager.getAccessToken())
        add(tokenManager.getRefreshToken())
        add(tokenManager.getProfileToken())
        add(hostedInstallationToken())
        if (destinationKind == DiagnosticsDestinationKind.HOSTED) {
            add(tokenManager.getServerUrl())
            add(tokenManager.getCurrentServerId())
            add(tokenManager.getProfileId())
        }
    }.filterNotNull().filter(String::isNotBlank).distinct()
}

class HostedDiagnosticsIdentityResolver(
    private val tokenManager: TokenManager,
    private val identityTransitions: IdentityTransitionBarrier,
    private val registry: ServerRegistry,
    private val profileProvider: DiagnosticsProfileProvider,
    private val capabilities: HostedDiagnosticsCapabilitiesRepository,
    private val maxAttempts: Int = 3,
) : DiagnosticsIdentityResolver {
    init {
        require(maxAttempts > 0)
    }

    override suspend fun resolve(requirePersistentCapture: Boolean): DiagnosticsCaptureContext? {
        if (requirePersistentCapture && tokenManager.hasTemporaryScope()) return null
        for (attempt in 0 until maxAttempts) {
            val generation = identityTransitions.generation.value
            val source = registry.activeEntry.value?.takeIf { it.id.isNotBlank() && it.url.isNotBlank() }
            if (source == null) {
                if (identityTransitions.generation.value != generation) continue
                return null
            }
            if (tokenManager.getCurrentServerId() != source.id) {
                if (identityTransitions.generation.value != generation) continue
                return null
            }
            if (tokenManager.getServerUrl().trimEnd('/') != source.url.trimEnd('/')) {
                if (identityTransitions.generation.value != generation) continue
                return null
            }
            if (tokenManager.getAccessToken().isNullOrBlank()) {
                if (identityTransitions.generation.value != generation) continue
                return null
            }

            val sourceProfileId = tokenManager.getProfileId()
            val profileEligible = if (sourceProfileId == null) {
                true
            } else {
                val child = profileProvider.isChild(sourceProfileId)
                if (child == null) {
                    if (identityTransitions.generation.value != generation) continue
                    return null
                }
                !child
            }
            // Capture never depends on the public collector being online. Live
            // capabilities and installation registration are intentionally send-time.
            val localCapabilities = capabilities.local()
            val localBindingOwner = currentLocalBindingOwner(source.id) ?: return null
            if (identityTransitions.generation.value != generation) continue
            return localCapabilities.toCaptureContext(
                sourceServerId = source.id,
                sourceProfileId = sourceProfileId,
                profileEligible = profileEligible,
                generation = generation,
                credentialFingerprint = currentCredentialFingerprint(),
                localBindingOwner = localBindingOwner,
            )
        }
        return null
    }

    override suspend fun matchesCachedIdentity(cached: CachedDiagnosticsContext): Boolean {
        if (cached.destinationKind != DiagnosticsDestinationKind.HOSTED || tokenManager.hasTemporaryScope()) return false
        val sourceServerId = cached.localServerId?.takeIf(String::isNotBlank) ?: return false
        val fingerprint = cached.credentialFingerprint?.takeIf(String::isNotBlank) ?: return false
        val source = registry.activeEntry.value ?: return false
        return source.id == sourceServerId &&
            tokenManager.getCurrentServerId() == sourceServerId &&
            tokenManager.getServerUrl().trimEnd('/') == source.url.trimEnd('/') &&
            !tokenManager.getAccessToken().isNullOrBlank() &&
            tokenManager.getProfileId() == cached.sourceProfileId &&
            currentCredentialFingerprint()?.let { current ->
                MessageDigest.isEqual(current.encodeToByteArray(), fingerprint.encodeToByteArray())
            } == true
    }

    private fun HostedDiagnosticsCapabilities.toCaptureContext(
        sourceServerId: String,
        sourceProfileId: String?,
        profileEligible: Boolean,
        generation: Long,
        credentialFingerprint: String?,
        localBindingOwner: String,
    ): DiagnosticsCaptureContext? {
        if (
            collectorId.isBlank() || consentNoticeVersion <= 0 || retentionDays <= 0 ||
            maxBundleBytes <= 0 || maxManifestBytes <= 0
        ) return null
        return DiagnosticsCaptureContext(
            // The one-way owner is local sidecar metadata only and is never included
            // in the manifest/envelope. It preserves existing cross-account isolation.
            binding = DiagnosticsBinding(collectorId, localBindingOwner),
            profileId = null,
            profileEligible = profileEligible,
            noticeVersion = consentNoticeVersion,
            status = status.toDiagnosticsStatus(),
            ownershipGeneration = generation,
            acceptedSchemaVersions = acceptedSchemaVersions.toSet(),
            maxBundleBytes = maxBundleBytes,
            maxManifestBytes = maxManifestBytes,
            retentionDays = retentionDays,
            localServerId = sourceServerId,
            credentialFingerprint = credentialFingerprint,
            sourceProfileId = sourceProfileId,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        )
    }

    private suspend fun currentCredentialFingerprint(): String? {
        val credential = tokenManager.getRefreshToken()?.takeIf(String::isNotBlank)
            ?: tokenManager.getAccessToken()?.takeIf(String::isNotBlank)
            ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(credential.encodeToByteArray())
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private suspend fun currentLocalBindingOwner(sourceServerId: String): String? {
        val accessToken = tokenManager.getAccessToken()?.takeIf(String::isNotBlank) ?: return null
        val stableAccount = accessToken.jwtUserIdOrNull()?.let { userId ->
            "$sourceServerId|user:$userId"
        } ?: currentCredentialFingerprint()?.let { fingerprint ->
            "$sourceServerId|credential:$fingerprint"
        } ?: return null
        return "hosted-" + stableAccount.sha256Hex().take(32)
    }

    private fun String.jwtUserIdOrNull(): String? = runCatching {
        val segments = split('.')
        if (segments.size != 3) return null
        val payload = Base64.decode(segments[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        Json.parseToJsonElement(payload.decodeToString()).jsonObject["user_id"]
            ?.jsonPrimitive
            ?.content
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

}

class DestinationDiagnosticsIdentityResolver(
    private val destination: suspend () -> DiagnosticsDestinationKind,
    private val hosted: DiagnosticsIdentityResolver,
    private val selfHosted: DiagnosticsIdentityResolver,
) : DiagnosticsIdentityResolver {
    override suspend fun resolve(requirePersistentCapture: Boolean): DiagnosticsCaptureContext? =
        selected().resolve(requirePersistentCapture)

    override suspend fun matchesCachedIdentity(cached: CachedDiagnosticsContext): Boolean =
        when (cached.destinationKind) {
            DiagnosticsDestinationKind.HOSTED -> hosted.matchesCachedIdentity(cached)
            DiagnosticsDestinationKind.SELF_HOSTED -> selfHosted.matchesCachedIdentity(cached)
        }

    private suspend fun selected(): DiagnosticsIdentityResolver = when (destination()) {
        DiagnosticsDestinationKind.HOSTED -> hosted
        DiagnosticsDestinationKind.SELF_HOSTED -> selfHosted
    }
}

private fun HostedDiagnosticsAvailability.toDiagnosticsStatus(): DiagnosticsAvailabilityStatus = when (this) {
    HostedDiagnosticsAvailability.AVAILABLE -> DiagnosticsAvailabilityStatus.AVAILABLE
    HostedDiagnosticsAvailability.DISABLED -> DiagnosticsAvailabilityStatus.DISABLED
    HostedDiagnosticsAvailability.STORAGE_UNAVAILABLE -> DiagnosticsAvailabilityStatus.STORAGE_UNAVAILABLE
}

private fun DiagnosticsPlatform.wireValue(): String = when (this) {
    DiagnosticsPlatform.ANDROID -> "android"
    DiagnosticsPlatform.ANDROID_TV -> "android-tv"
    DiagnosticsPlatform.IOS -> "ios"
    DiagnosticsPlatform.TVOS -> "tvos"
}
