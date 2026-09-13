package org.siloserver.silo.common.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.EncryptedTokenManagerImpl
import org.siloserver.silo.network.api.HostedDiagnosticsApi
import org.siloserver.silo.network.api.HostedDiagnosticsApiResult
import org.siloserver.silo.network.api.HostedDiagnosticsCapabilities
import org.siloserver.silo.network.api.HostedDiagnosticsCreateReportRequest
import org.siloserver.silo.network.api.HostedDiagnosticsCreateReportResponse
import org.siloserver.silo.network.api.HostedDiagnosticsInstallationRequest
import org.siloserver.silo.network.api.HostedDiagnosticsInstallationResponse
import org.siloserver.silo.network.api.HostedDiagnosticsReportStatusResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HostedDiagnosticsTest {
    @Test
    fun captureCapabilitiesCoalesceAndExpireButUploadsAlwaysRefresh() = runTest {
        var now = 0L
        var calls = 0
        val defaults = HostedDiagnosticsCapabilitiesRepository(
            InMemoryCapabilitiesStore(), RecordingOfflineHostedApi(),
        ).local()
        val api = object : HostedDiagnosticsApi by RecordingOfflineHostedApi() {
            override suspend fun capabilities(): HostedDiagnosticsApiResult<HostedDiagnosticsCapabilities> {
                calls++
                delay(10)
                return HostedDiagnosticsApiResult.Success(defaults.copy(consentNoticeVersion = calls))
            }
        }
        val repository = HostedDiagnosticsCapabilitiesRepository(InMemoryCapabilitiesStore(defaults), api) { now }
        val results = List(20) { async { repository.refresh(requireFresh = false) } }.awaitAll()
        assertEquals(1, calls, "persisted metadata must first be attested, and concurrent capture checks share it")
        assertTrue(results.all { it == HostedDiagnosticsApiResult.Success(defaults) })
        now = 299_999
        repository.refresh(requireFresh = false)
        assertEquals(1, calls)
        now = 300_000
        repository.refresh(requireFresh = false)
        assertEquals(2, calls)
        val upload = repository.refresh()
        assertEquals(HostedDiagnosticsApiResult.Success(defaults.copy(consentNoticeVersion = 3)), upload)
        assertEquals(3, calls)
    }

    @Test
    fun failedFreshCapabilitiesInvalidateCaptureCache() = runTest {
        var calls = 0
        var invalid = false
        val defaults = HostedDiagnosticsCapabilitiesRepository(
            InMemoryCapabilitiesStore(), RecordingOfflineHostedApi(),
        ).local()
        val api = object : HostedDiagnosticsApi by RecordingOfflineHostedApi() {
            override suspend fun capabilities(): HostedDiagnosticsApiResult<HostedDiagnosticsCapabilities> {
                calls++
                return HostedDiagnosticsApiResult.Success(
                    if (invalid) defaults.copy(acceptedSchemaVersions = listOf(2)) else defaults,
                )
            }
        }
        val repository = HostedDiagnosticsCapabilitiesRepository(InMemoryCapabilitiesStore(), api)
        repository.refresh(requireFresh = false)
        invalid = true
        assertTrue(repository.refresh() is HostedDiagnosticsApiResult.Failure)
        assertTrue(repository.refresh(requireFresh = false) is HostedDiagnosticsApiResult.Failure)
        assertEquals(3, calls, "a failed fresh check must not revive previously cached capabilities")
        invalid = false
        assertTrue(repository.refresh(requireFresh = false) is HostedDiagnosticsApiResult.Success)
        assertEquals(4, calls)
    }

    @Test
    fun cachedCapabilitiesMustIncludeHostedSchemaV1() = runTest {
        val v2Only = HostedDiagnosticsCapabilities(
            status = org.siloserver.silo.network.api.HostedDiagnosticsAvailability.AVAILABLE,
            collectorId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
            acceptedSchemaVersions = listOf(2),
            maxBundleBytes = 10L * 1_024 * 1_024,
            maxManifestBytes = 64L * 1_024,
            retentionDays = HOSTED_DIAGNOSTICS_RETENTION_DAYS,
            consentNoticeVersion = 1,
        )
        val repository = HostedDiagnosticsCapabilitiesRepository(
            store = InMemoryCapabilitiesStore(v2Only),
            api = RecordingOfflineHostedApi(),
        )

        assertEquals(listOf(1), repository.local().acceptedSchemaVersions)
    }

    @Test
    fun cachedResolutionDoesNotContactCollectorButLiveCaptureFailsClosedOffline() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("hosted-offline-${System.nanoTime()}", Context.MODE_PRIVATE)
        val transitions = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(prefs, transitions)
        val serverId = registry.addOrUpdate("https://private-silo.example")
        registry.addOrUpdate("https://saved-private-silo.example:9443")
        registry.switchTo(serverId)
        val tokens = EncryptedTokenManagerImpl(prefs, registry, transitions)
        tokens.saveTokens("source-access", "source-refresh", 3_600)
        tokens.setProfileId("adult-profile")
        val offlineApi = RecordingOfflineHostedApi()
        val capabilities = HostedDiagnosticsCapabilitiesRepository(
            store = InMemoryCapabilitiesStore(),
            api = offlineApi,
        )
        var accountId = "account-a"
        val bindingOwners = InMemoryBindingOwnerStore()
        val resolver = HostedDiagnosticsIdentityResolver(
            tokenManager = tokens,
            identityTransitions = transitions,
            registry = registry,
            accountProvider = DiagnosticsAccountProvider { accountId },
            profileProvider = DiagnosticsProfileProvider { false },
            capabilities = capabilities,
            bindingOwners = bindingOwners,
        )

        val resolved = resolver.resolve(requirePersistentCapture = true)

        assertEquals(0, offlineApi.calls, "capture path must not contact the public collector")
        assertEquals(DiagnosticsDestinationKind.HOSTED, resolved?.destinationKind)
        assertEquals(HOSTED_DIAGNOSTICS_COLLECTOR_ID, resolved?.binding?.serverInstanceId)
        assertEquals(null, resolved?.profileId, "hosted manifest attribution must remain empty")
        assertEquals("adult-profile", resolved?.sourceProfileId, "source profile is local gate state only")
        assertTrue(resolved?.profileEligible == true)
        assertTrue(1 in resolved.orThrow().acceptedSchemaVersions)
        assertEquals(30, resolved.retentionDays)
        assertNull(resolver.resolveForCapture(requirePersistentCapture = true))
        assertEquals(1, offlineApi.calls, "live capture must attest the public collector")

        val redactionTokens = DestinationAwareDiagnosticsRedactionTokenProvider(tokens, registry) {
            listOf("installation-token", "fallback-installation-token")
        }
        val hostedTokens = redactionTokens.tokens(DiagnosticsDestinationKind.HOSTED)
        assertTrue("https://private-silo.example" in hostedTokens)
        assertTrue("private-silo.example" in hostedTokens)
        assertTrue("https://saved-private-silo.example:9443" in hostedTokens)
        assertTrue("saved-private-silo.example" in hostedTokens)
        assertTrue(serverId in hostedTokens)
        assertTrue("adult-profile" in hostedTokens)
        assertTrue("installation-token" in hostedTokens)
        assertTrue("fallback-installation-token" in hostedTokens)
        val selfHostedTokens = redactionTokens.tokens(DiagnosticsDestinationKind.SELF_HOSTED)
        assertFalse("https://private-silo.example" in selfHostedTokens)
        assertFalse(serverId in selfHostedTokens)
        assertFalse("adult-profile" in selfHostedTokens)

        tokens.saveTokens("other-account-access", "other-account-refresh", 3_600)
        val rotatedCredential = resolver.resolve(requirePersistentCapture = true)
        assertEquals(
            resolved.binding.accountUserId,
            rotatedCredential?.binding?.accountUserId,
            "token rotation must not change the hosted binding",
        )
        accountId = "account-b"
        val otherAccount = resolver.resolveForUpload(requirePersistentCapture = true)
        assertNotEquals(resolved.binding.accountUserId, otherAccount?.binding?.accountUserId)
        assertEquals(1, offlineApi.calls, "account isolation must not add collector calls")
    }

    @Test
    fun installationCredentialsRoundTripThroughDedicatedSecureStoreAbstraction() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("hosted-credentials-${System.nanoTime()}", Context.MODE_PRIVATE)
        val store: HostedDiagnosticsCredentialStore = EncryptedPreferencesHostedDiagnosticsCredentialStore(prefs)
        val credentials = HostedDiagnosticsCredentials("service-installation", "service-token")

        assertNull(store.load())
        store.save(credentials)
        assertEquals(credentials, store.load())
        val fallback = HostedDiagnosticsCredentials("fallback-installation", "fallback-token")
        store.saveFallback(fallback)
        assertEquals(listOf(fallback), store.loadFallbacks())
        assertFalse(prefs.contains("access_token"))
        assertFalse(prefs.contains("refresh_token"))
        assertFalse(prefs.contains("profile_token"))
        store.clear()
        assertNull(store.load())
    }

    private class InMemoryCapabilitiesStore(
        private var value: HostedDiagnosticsCapabilities? = null,
    ) : HostedDiagnosticsCapabilitiesStore {
        override suspend fun load(): HostedDiagnosticsCapabilities? = value
        override suspend fun save(capabilities: HostedDiagnosticsCapabilities) {
            value = capabilities
        }
    }

    private class InMemoryBindingOwnerStore : HostedDiagnosticsBindingOwnerStore {
        private val owners = mutableMapOf<String, String>()
        override suspend fun load(localServerId: String): String? = owners[localServerId]
        override suspend fun save(localServerId: String, owner: String) {
            owners[localServerId] = owner
        }
    }

    private class RecordingOfflineHostedApi : HostedDiagnosticsApi {
        var calls: Int = 0
        private fun <T> offline(): HostedDiagnosticsApiResult<T> {
            calls += 1
            return HostedDiagnosticsApiResult.NetworkError(IllegalStateException("offline"))
        }

        override suspend fun capabilities() = offline<HostedDiagnosticsCapabilities>()
        override suspend fun createInstallation(request: HostedDiagnosticsInstallationRequest) =
            offline<HostedDiagnosticsInstallationResponse>()
        override suspend fun createReport(
            installationToken: String,
            request: HostedDiagnosticsCreateReportRequest,
        ) = offline<HostedDiagnosticsCreateReportResponse>()
        override suspend fun uploadBundle(
            installationToken: String,
            reportId: String,
            uploadToken: String,
            bundle: ByteArray,
        ) = offline<HostedDiagnosticsReportStatusResponse>()
        override suspend fun reportStatus(
            installationToken: String,
            reportId: String,
        ) = offline<HostedDiagnosticsReportStatusResponse>()
        override suspend fun deleteReport(
            installationToken: String,
            reportId: String,
        ) = offline<Unit>()
    }
}

private fun DiagnosticsCaptureContext?.orThrow(): DiagnosticsCaptureContext = checkNotNull(this)
