package org.siloserver.silo.common.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
    fun offlineCollectorDoesNotDisablePersistentHostedCapture() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("hosted-offline-${System.nanoTime()}", Context.MODE_PRIVATE)
        val transitions = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(prefs, transitions)
        val serverId = registry.addOrUpdate("https://private-silo.example")
        registry.switchTo(serverId)
        val tokens = EncryptedTokenManagerImpl(prefs, registry, transitions)
        tokens.saveTokens("source-access", "source-refresh", 3_600)
        tokens.setProfileId("adult-profile")
        val offlineApi = RecordingOfflineHostedApi()
        val capabilities = HostedDiagnosticsCapabilitiesRepository(
            store = InMemoryCapabilitiesStore(),
            api = offlineApi,
        )
        val resolver = HostedDiagnosticsIdentityResolver(
            tokenManager = tokens,
            identityTransitions = transitions,
            registry = registry,
            profileProvider = DiagnosticsProfileProvider { false },
            capabilities = capabilities,
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

        val redactionTokens = DestinationAwareDiagnosticsRedactionTokenProvider(tokens) { "installation-token" }
        val hostedTokens = redactionTokens.tokens(DiagnosticsDestinationKind.HOSTED)
        assertTrue("https://private-silo.example" in hostedTokens)
        assertTrue(serverId in hostedTokens)
        assertTrue("adult-profile" in hostedTokens)
        assertTrue("installation-token" in hostedTokens)
        val selfHostedTokens = redactionTokens.tokens(DiagnosticsDestinationKind.SELF_HOSTED)
        assertFalse("https://private-silo.example" in selfHostedTokens)
        assertFalse(serverId in selfHostedTokens)
        assertFalse("adult-profile" in selfHostedTokens)

        tokens.saveTokens("other-account-access", "other-account-refresh", 3_600)
        val otherAccount = resolver.resolve(requirePersistentCapture = true)
        assertNotEquals(
            resolved.binding.accountUserId,
            otherAccount?.binding?.accountUserId,
            "the local-only hosted binding must preserve cross-account report isolation",
        )
        assertEquals(0, offlineApi.calls, "account isolation must not contact the public collector")
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
        assertFalse(prefs.contains("access_token"))
        assertFalse(prefs.contains("refresh_token"))
        assertFalse(prefs.contains("profile_token"))
        store.clear()
        assertNull(store.load())
    }

    private class InMemoryCapabilitiesStore : HostedDiagnosticsCapabilitiesStore {
        private var value: HostedDiagnosticsCapabilities? = null
        override suspend fun load(): HostedDiagnosticsCapabilities? = value
        override suspend fun save(capabilities: HostedDiagnosticsCapabilities) {
            value = capabilities
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
    }
}

private fun DiagnosticsCaptureContext?.orThrow(): DiagnosticsCaptureContext = checkNotNull(this)
