package org.siloserver.silo.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.AccessChangeSignals
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.ProfileIdentity
import org.siloserver.silo.network.StaleProfileReport
import org.siloserver.silo.network.TemporaryAuthScope
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stale PIN profile token (403 `profile_verification_required`) must send the
 * user back to profile selection without signing them out, and only for the
 * identity that is actually active.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileVerificationRecoveryTest {

    private val serverUrl = "https://silo.example"

    private class ScopedTokenManager(
        private val barrier: IdentityTransitionBarrier,
        private val delegate: TokenManagerImpl = TokenManagerImpl(),
    ) : TokenManager by delegate {
        override suspend fun snapshotCurrentScope() = AuthScopeSnapshot(
            serverId = "server-1",
            profileId = delegate.getProfileId(),
            serverUrl = delegate.getServerUrl(),
            profileToken = delegate.getProfileToken(),
            identityGeneration = barrier.generation.value,
            isIdentityGenerationStamped = true,
            credentialEpoch = 1,
        )

        override suspend fun getCurrentServerId(): String = "server-1"

        // Interface delegation would read the delegate's identity otherwise.
        override suspend fun getProfileIdentity(): ProfileIdentity = delegate.getProfileIdentity()
    }

    private class Fixture(
        val barrier: DefaultIdentityTransitionBarrier,
        val tokens: ScopedTokenManager,
        val profiles: ProfileRepository,
        val recovery: ProfileVerificationRecovery,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.fixture(): Fixture {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = ScopedTokenManager(barrier)
        tokens.setServerUrl(serverUrl)
        tokens.saveTokens("access", "refresh", 3_600)
        val noOpClient = HttpClient(MockEngine { _ ->
            respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val profiles = ProfileRepository(
            profileApi = ProfileApi(noOpClient, ApiV2Gate.Unrestricted),
            tokenManager = tokens,
            identityTransitions = barrier,
        )
        profiles.selectProfile("kid", "stale-pin-token")
        val recovery = ProfileVerificationRecovery(AccessChangeSignals(), tokens, profiles, backgroundScope)
        return Fixture(barrier, tokens, profiles, recovery)
    }

    private fun report(
        profileId: String = "kid",
        profileToken: String? = "stale-pin-token",
        url: String = "$serverUrl/api/v2/home/sections",
        pinned: AuthScopeSnapshot? = null,
    ) = StaleProfileReport(url, profileId, profileToken, pinned)

    @Test
    fun `a stale profile token clears the profile and keeps the sign-in`() = runTest {
        val f = fixture()

        f.recovery.handle(report())

        assertNull(f.tokens.getProfileId())
        assertNull(f.tokens.getProfileToken())
        assertEquals("access", f.tokens.getAccessToken(), "the account must stay signed in")
        assertEquals("refresh", f.tokens.getRefreshToken())
        val prompt = assertNotNull(f.recovery.pending.value)
        assertEquals(
            ProfilePromptAction.Navigate,
            f.recovery.actionFor(prompt, "home", deferRoutes = setOf("player")),
        )
    }

    @Test
    fun `repeated refusals for the same identity clear it once`() = runTest {
        val f = fixture()

        f.recovery.handle(report())
        val generationAfterFirst = f.barrier.generation.value
        // Every screen that loaded under the stale token reports it too.
        repeat(3) { f.recovery.handle(report()) }

        assertEquals(generationAfterFirst, f.barrier.generation.value, "no further identity transitions")
    }

    @Test
    fun `a refusal for a token that was already replaced is ignored`() = runTest {
        val f = fixture()
        // The user re-entered the PIN before the late response arrived.
        f.profiles.selectProfile("kid", "fresh-pin-token")

        f.recovery.handle(report(profileToken = "stale-pin-token"))

        assertEquals("kid", f.tokens.getProfileId())
        assertEquals("fresh-pin-token", f.tokens.getProfileToken())
        assertNull(f.recovery.pending.value)
    }

    @Test
    fun `a refusal for another profile or server is ignored`() = runTest {
        val f = fixture()

        f.recovery.handle(report(profileId = "parent"))
        f.recovery.handle(report(url = "https://other.example/api/v2/home/sections"))

        assertEquals("kid", f.tokens.getProfileId())
        assertNull(f.recovery.pending.value)
    }

    @Test
    fun `a pinned background request from an earlier identity is ignored`() = runTest {
        val f = fixture()
        val captured = f.tokens.snapshotCurrentScope()
        // Same profile id and token, but the identity moved since the capture.
        f.barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) { }

        f.recovery.handle(report(pinned = captured))

        assertEquals("kid", f.tokens.getProfileId())
        assertNull(f.recovery.pending.value)
    }

    @Test
    fun `a pinned background request on the active identity recovers it`() = runTest {
        val f = fixture()

        f.recovery.handle(report(pinned = f.tokens.snapshotCurrentScope()))

        assertNull(f.tokens.getProfileId())
        assertNotNull(f.recovery.pending.value)
    }

    @Test
    fun `a remote playback overlay is never cleared`() = runTest {
        val f = fixture()
        f.tokens.beginTemporaryScope(
            TemporaryAuthScope(
                generationId = "overlay",
                serverId = "server-1",
                serverUrl = serverUrl,
                accessToken = "overlay-access",
                refreshToken = "overlay-refresh",
                profileId = "kid",
                profileToken = "stale-pin-token",
                expiresAtEpochMs = Long.MAX_VALUE,
            ),
        )

        f.recovery.handle(report())

        assertEquals("kid", f.tokens.getProfileId())
        assertNull(f.recovery.pending.value)
    }

    @Test
    fun `the prompt waits behind playback and drops once a profile is picked`() = runTest {
        val f = fixture()
        f.recovery.handle(report())
        val prompt = assertNotNull(f.recovery.pending.value)

        assertEquals(
            ProfilePromptAction.Defer,
            f.recovery.actionFor(prompt, "player", deferRoutes = setOf("player")),
        )

        f.profiles.selectProfile("kid", "fresh-pin-token")
        assertEquals(
            ProfilePromptAction.Drop,
            f.recovery.actionFor(prompt, "home", deferRoutes = setOf("player")),
        )
    }

    @Test
    fun `the prompt drops after sign-out`() = runTest {
        val f = fixture()
        f.recovery.handle(report())
        val prompt = assertNotNull(f.recovery.pending.value)

        f.tokens.clearTokens()

        assertFalse(f.recovery.isCurrent(prompt))
        f.recovery.consume(prompt)
        assertNull(f.recovery.pending.value)
    }

    @Test
    fun `unrelated identity transitions keep the prompt`() = runTest {
        val f = fixture()
        f.recovery.handle(report())
        val prompt = assertNotNull(f.recovery.pending.value)

        // For example another server removed, or the active one re-selected.
        f.barrier.changing(IdentityTransitionKind.SERVER_REMOVE) { }

        assertEquals(
            ProfilePromptAction.Navigate,
            f.recovery.actionFor(prompt, "home", deferRoutes = setOf("player")),
        )
    }

    @Test
    fun `a remote playback overlay defers the prompt until it ends`() = runTest {
        val f = fixture()
        f.recovery.handle(report())
        val prompt = assertNotNull(f.recovery.pending.value)
        f.tokens.beginTemporaryScope(
            TemporaryAuthScope(
                generationId = "overlay",
                serverId = "server-1",
                serverUrl = serverUrl,
                accessToken = "overlay-access",
                refreshToken = "overlay-refresh",
                profileId = "guest",
                profileToken = "guest-token",
                expiresAtEpochMs = Long.MAX_VALUE,
            ),
        )

        assertEquals(ProfilePromptAction.Defer, f.recovery.actionFor(prompt, "home", deferRoutes = emptySet()))

        f.tokens.endTemporaryScope()
        assertEquals(ProfilePromptAction.Navigate, f.recovery.actionFor(prompt, "home", deferRoutes = emptySet()))
    }

    @Test
    fun `on the picker the prompt is consumed without navigating`() = runTest {
        val f = fixture()
        val cleared = mutableListOf<Unit>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            f.recovery.profileCleared.collect { cleared += it }
        }
        f.recovery.handle(report())
        testScheduler.runCurrent()
        val prompt = assertNotNull(f.recovery.pending.value)

        assertEquals(
            ProfilePromptAction.AlreadyThere,
            f.recovery.actionFor(prompt, "profiles", deferRoutes = emptySet(), pickerRoutes = setOf("profiles")),
        )
        assertEquals(1, cleared.size, "the picker is told to reload its grid")
    }

    @Test
    fun `a failing clear does not stop later reports`() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        var failNext = true
        barrier.installGate {
            if (failNext) {
                failNext = false
                error("gate failed")
            }
        }
        val tokens = ScopedTokenManager(barrier)
        tokens.setServerUrl(serverUrl)
        tokens.saveTokens("access", "refresh", 3_600)
        tokens.setProfileIdentity("kid", "stale-pin-token")
        val profiles = ProfileRepository(
            profileApi = ProfileApi(HttpClient(MockEngine { respond("{}") }), ApiV2Gate.Unrestricted),
            tokenManager = tokens,
            identityTransitions = barrier,
        )
        val signals = AccessChangeSignals()
        val recovery = ProfileVerificationRecovery(signals, tokens, profiles, backgroundScope)
        testScheduler.runCurrent()

        signals.reportStaleProfile(report())
        testScheduler.runCurrent()
        assertEquals("kid", tokens.getProfileId(), "the failed attempt changed nothing")

        signals.reportStaleProfile(report())
        testScheduler.runCurrent()
        assertNull(tokens.getProfileId())
        assertNotNull(recovery.pending.value)
    }

    @Test
    fun `reports reach the recovery through the signals`() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = ScopedTokenManager(barrier)
        tokens.setServerUrl(serverUrl)
        tokens.saveTokens("access", "refresh", 3_600)
        val profiles = ProfileRepository(
            profileApi = ProfileApi(HttpClient(MockEngine { respond("{}") }), ApiV2Gate.Unrestricted),
            tokenManager = tokens,
            identityTransitions = barrier,
        )
        profiles.selectProfile("kid", "stale-pin-token")
        val signals = AccessChangeSignals()
        val recovery = ProfileVerificationRecovery(signals, tokens, profiles, backgroundScope)
        testScheduler.runCurrent()

        signals.reportStaleProfile(report())
        testScheduler.runCurrent()

        assertNull(tokens.getProfileId())
        assertTrue(recovery.pending.value != null)
    }
}
