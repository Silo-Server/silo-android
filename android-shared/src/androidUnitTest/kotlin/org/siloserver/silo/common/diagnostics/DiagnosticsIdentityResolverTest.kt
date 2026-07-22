package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsStatusResponse
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.TemporaryAuthScope
import org.siloserver.silo.network.TokenManagerImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsIdentityResolverTest {
    @Test
    fun resolvesOnlyOneStableSavedAdultIdentity() = runTest {
        val fixture = fixture(profileId = "adult", child = false)

        val context = fixture.resolver.resolve(requirePersistentCapture = true)

        assertEquals(DiagnosticsBinding("instance-1", "42"), context?.binding)
        assertEquals("adult", context?.profileId)
        assertTrue(context?.profileEligible == true)
        assertEquals(7, context?.noticeVersion)
        assertEquals("saved-server", context?.localServerId)
        assertTrue(context?.credentialFingerprint?.matches(Regex("^[0-9a-f]{64}$")) == true)
        assertEquals(fixture.barrier.generation.value, context?.ownershipGeneration)
    }

    @Test
    fun childIsExplicitlyIneligibleAndUnresolvedProfileFailsClosed() = runTest {
        val child = fixture(profileId = "child", child = true).resolver.resolve(true)
        val unresolved = fixture(profileId = "missing", child = null).resolver.resolve(true)

        assertFalse(child?.profileEligible ?: true)
        assertNull(unresolved)
    }

    @Test
    fun accountScopedCaptureWithoutAnActiveProfileRemainsValidAndUnattributed() = runTest {
        val context = fixture(profileId = null, child = null).resolver.resolve(true)

        assertEquals(null, context?.profileId)
        assertTrue(context?.profileEligible == true)
    }

    @Test
    fun temporaryScopeClosesPersistentCaptureBeforeAnyRemoteProbe() = runTest {
        var statusCalls = 0
        val fixture = fixture(profileId = "adult", child = false) {
            statusCalls += 1
            status()
        }
        fixture.tokens.beginTemporaryScope(
            TemporaryAuthScope(
                generationId = "temp-1",
                serverId = "temporary-server",
                serverUrl = "https://temporary.example",
                accessToken = "temporary-access",
                refreshToken = "temporary-refresh",
                profileId = "temporary-profile",
                profileToken = "temporary-profile-token",
                expiresAtEpochMs = Long.MAX_VALUE,
            ),
        )

        assertNull(fixture.resolver.resolve(requirePersistentCapture = true))
        assertEquals(0, statusCalls)
    }

    @Test
    fun retriesWhenOwnershipGenerationChangesMidResolution() = runTest {
        var calls = 0
        lateinit var fixture: Fixture
        fixture = fixture(profileId = "adult", child = false) {
            calls += 1
            if (calls == 1) {
                fixture.barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) { Unit }
            }
            status()
        }

        val context = fixture.resolver.resolve(requirePersistentCapture = true)

        assertEquals(2, calls)
        assertEquals(fixture.barrier.generation.value, context?.ownershipGeneration)
    }

    @Test
    fun refreshObservesDiagnosticsAvailabilityChangesForTheSameIdentity() = runTest {
        var availability = DiagnosticsAvailabilityStatus.DISABLED
        var statusCalls = 0
        val fixture = fixture(profileId = "adult", child = false) {
            statusCalls += 1
            status().copy(status = availability)
        }

        val disabled = fixture.resolver.resolve(requirePersistentCapture = true)
        availability = DiagnosticsAvailabilityStatus.AVAILABLE
        val available = fixture.resolver.resolve(requirePersistentCapture = true)

        assertEquals(DiagnosticsAvailabilityStatus.DISABLED, disabled?.status)
        assertEquals(DiagnosticsAvailabilityStatus.AVAILABLE, available?.status)
        assertEquals(2, statusCalls)
    }

    @Test
    fun missingCredentialStatusOrAccountFailsClosed() = runTest {
        val noCredential = fixture(profileId = "adult", child = false, saveCredential = false)
        val noStatus = fixture(profileId = "adult", child = false, statusProvider = { null })
        val noAccount = fixture(profileId = "adult", child = false, accountId = null)

        assertNull(noCredential.resolver.resolve(true))
        assertNull(noStatus.resolver.resolve(true))
        assertNull(noAccount.resolver.resolve(true))
    }

    private suspend fun fixture(
        profileId: String?,
        child: Boolean?,
        saveCredential: Boolean = true,
        accountId: String? = "42",
        statusProvider: suspend () -> DiagnosticsStatusResponse? = { status() },
    ): Fixture {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = TokenManagerImpl(barrier)
        tokens.setServerUrl("https://saved.example")
        if (saveCredential) tokens.saveTokens("access", "refresh", 60)
        tokens.setProfileId(profileId)
        val resolver = DefaultDiagnosticsIdentityResolver(
            tokenManager = tokens,
            identityTransitions = barrier,
            savedServerProvider = DiagnosticsSavedServerProvider {
                SavedDiagnosticsServer("saved-server", "https://saved.example")
            },
            statusProvider = DiagnosticsStatusProvider { statusProvider() },
            accountProvider = DiagnosticsAccountProvider { accountId },
            profileProvider = DiagnosticsProfileProvider { requested ->
                if (requested == profileId) child else null
            },
        )
        return Fixture(barrier, tokens, resolver)
    }

    private data class Fixture(
        val barrier: DefaultIdentityTransitionBarrier,
        val tokens: TokenManagerImpl,
        val resolver: DefaultDiagnosticsIdentityResolver,
    )

    private companion object {
        fun status() = DiagnosticsStatusResponse(
            status = DiagnosticsAvailabilityStatus.AVAILABLE,
            serverInstanceId = "instance-1",
            acceptedSchemaVersions = listOf(1),
            maxBundleBytes = 10_000_000,
            maxManifestBytes = 100_000,
            retentionDays = 30,
            consentNoticeVersion = 7,
        )
    }
}
