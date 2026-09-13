package org.siloserver.silo.repository

import org.siloserver.silo.network.apiv2.ApiV2Gate

import org.siloserver.silo.model.profile.VerifyPinResponse
import org.siloserver.silo.model.profile.authorizedProfileToken
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.ProfileApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The profile id and the profile token are one identity. These cover the ways
 * they used to come apart — the client would claim one profile while holding
 * another's proof, or commit an answer that arrived after the user had moved on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileIdentityCommitTest {

    private val noOpClient = HttpClient(MockEngine { _ ->
        respond(content = "{}", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
    })

    private fun repository(
        tokenManager: org.siloserver.silo.network.TokenManager,
        barrier: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
    ) = ProfileRepository(
        profileApi = ProfileApi(noOpClient, ApiV2Gate.Unrestricted),
        tokenManager = tokenManager,
        identityTransitions = barrier,
    )

    /**
     * The deterministic phone bug: switching from a PIN-protected profile to an
     * unprotected one left the protected profile's token in place, so requests
     * went out as `X-Profile-Id: B` with A's `X-Profile-Token`.
     */
    @Test
    fun `selecting an unprotected profile drops the previous profile's token`() = runTest {
        val tokenManager = TokenManagerImpl()
        val repo = repository(tokenManager)

        repo.selectProfile(profileId = "profile-a", profileToken = "token-for-a")
        assertEquals("token-for-a", tokenManager.getProfileToken())

        repo.selectProfile(profileId = "profile-b", profileToken = null)

        assertEquals("profile-b", tokenManager.getProfileId())
        assertNull(
            tokenManager.getProfileToken(),
            "profile B must not inherit profile A's proof",
        )
    }

    @Test
    fun `selecting a protected profile commits that profile's own token`() = runTest {
        val tokenManager = TokenManagerImpl()
        val repo = repository(tokenManager)

        repo.selectProfile(profileId = "profile-a", profileToken = "token-for-a")
        repo.selectProfile(profileId = "profile-b", profileToken = "token-for-b")

        assertEquals("profile-b", tokenManager.getProfileId())
        assertEquals("token-for-b", tokenManager.getProfileToken())
    }

    /**
     * A token manager that models identity scopes, like the production
     * (encrypted) one does. Plain [TokenManagerImpl] reports no scope at all,
     * which deliberately leaves the guard inert — so it cannot exercise this.
     */
    private class ScopedTokenManager(
        private val barrier: IdentityTransitionBarrier,
        private val delegate: TokenManagerImpl = TokenManagerImpl(),
    ) : org.siloserver.silo.network.TokenManager by delegate {
        // Reads the SAME barrier the repository commits through, so the
        // generation moves exactly as it does in production. A double with a
        // hand-set generation hid a real bug: `changing` bumps the generation
        // on entry, so an in-block comparison against the captured value
        // reported "changed" for every ordinary selection.
        override suspend fun snapshotCurrentScope() = AuthScopeSnapshot(
            serverId = "server-1",
            profileId = delegate.getProfileId(),
            serverUrl = "https://one.example",
            profileToken = delegate.getProfileToken(),
            identityGeneration = barrier.generation.value,
        )
    }

    /**
     * A verification captured against one identity must not be applied to
     * whoever is active by the time it lands — the remote-playback overlay
     * case, where committing would put this profile's proof in someone
     * else's session.
     */
    @Test
    fun `a commit whose scope moved is discarded`() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokenManager = ScopedTokenManager(barrier)
        val repo = repository(tokenManager, barrier)
        repo.selectProfile(profileId = "profile-a", profileToken = "token-for-a")

        val captured = repo.captureIdentityScope()
        // Something else moves the identity while verification is in flight —
        // a remote-playback overlay, a server switch, a sign-out.
        barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) { }

        val result = repo.selectProfile(
            profileId = "profile-x",
            profileToken = "token-for-x",
            expectedScope = captured,
        )

        assertEquals(ProfileCommitResult.ScopeChanged, result)
        assertEquals("profile-a", tokenManager.getProfileId(), "identity must be untouched")
        assertEquals("token-for-a", tokenManager.getProfileToken())
    }

    /** Same manager, unmoved scope: the ordinary path must still commit. */
    @Test
    fun `a commit whose scope held is applied`() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokenManager = ScopedTokenManager(barrier)
        val repo = repository(tokenManager, barrier)

        val captured = repo.captureIdentityScope()
        val result = repo.selectProfile(
            profileId = "profile-b",
            profileToken = "token-for-b",
            expectedScope = captured,
        )

        assertEquals(ProfileCommitResult.Committed, result)
        assertEquals("profile-b", tokenManager.getProfileId())
        assertEquals("token-for-b", tokenManager.getProfileToken())
    }

    /**
     * A remote-playback overlay owns identity while it exists, and an
     * unprotected selection carries no scope to compare — so the repository
     * refuses the commit outright rather than relying on the token manager to
     * absorb it. Both layers now decline: without the repository check the
     * managers would no-op the write but the caller would be told `Committed`
     * and would run the downstream side effects (registry write, cache resets,
     * navigation) for a switch that never happened.
     */
    @Test
    fun `no profile commits while a temporary overlay owns identity`() = runTest {
        val tokenManager = TokenManagerImpl()
        val repo = repository(tokenManager)
        // Seed a real persistent identity so we can prove it survives intact.
        repo.selectProfile(profileId = "profile-a", profileToken = "token-for-a")
        tokenManager.beginTemporaryScope(
            org.siloserver.silo.network.TemporaryAuthScope(
                generationId = "overlay-1",
                serverId = "server-1",
                serverUrl = "https://one.example",
                accessToken = "overlay-access",
                refreshToken = "overlay-refresh",
                profileId = "overlay-profile",
                profileToken = "overlay-token",
                expiresAtEpochMs = Long.MAX_VALUE,
            ),
        )

        val result = repo.selectProfile(profileId = "profile-b", profileToken = null)

        assertEquals(ProfileCommitResult.ScopeChanged, result)
        // The overlay's own identity is untouched...
        assertEquals("overlay-profile", tokenManager.getProfileId())
        assertEquals("overlay-token", tokenManager.getProfileToken())

        // ...and so is the persistent identity underneath it, which is what
        // the user returns to when the handoff ends.
        tokenManager.endTemporaryScope()
        assertEquals("profile-a", tokenManager.getProfileId())
        assertEquals("token-for-a", tokenManager.getProfileToken())
    }

    /** No captured scope means the guard stays inert rather than failing closed. */
    @Test
    fun `a commit with no captured scope still applies`() = runTest {
        val tokenManager = TokenManagerImpl()
        val repo = repository(tokenManager)

        val result = repo.selectProfile(
            profileId = "profile-b",
            profileToken = "token-for-b",
            expectedScope = null,
        )

        assertEquals(ProfileCommitResult.Committed, result)
        assertEquals("profile-b", tokenManager.getProfileId())
    }

    /**
     * The profile list is identity-bound. A response fetched under an identity
     * that has since been replaced must be dropped, or the grid offers profiles
     * from a session the app no longer holds — and an unprotected tap on one of
     * those carries no scope to reject it.
     */
    @Test
    fun `a list fetched under a replaced identity is not accepted`() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokenManager = ScopedTokenManager(barrier)
        val repo = repository(tokenManager, barrier)

        val captured = repo.captureIdentityScope()
        barrier.changing(IdentityTransitionKind.SIGN_OUT) { }

        assertFalse(repo.identityScopeUnchanged(captured))
    }

    @Test
    fun `a list fetched under the current identity is accepted`() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokenManager = ScopedTokenManager(barrier)
        val repo = repository(tokenManager, barrier)

        val captured = repo.captureIdentityScope()

        assertTrue(repo.identityScopeUnchanged(captured))
    }

    /**
     * `valid` alone was the old gate. A 200 carrying no usable proof let the
     * client enter a protected profile holding nothing to present, which
     * surfaced much later as a confusing 403 on an unrelated action.
     */
    @Test
    fun `a verification without a usable token does not authorize`() {
        assertNull(VerifyPinResponse(valid = true, profileToken = null).authorizedProfileToken())
        assertNull(VerifyPinResponse(valid = true, profileToken = "").authorizedProfileToken())
        assertNull(VerifyPinResponse(valid = true, profileToken = "   ").authorizedProfileToken())
        assertNull(VerifyPinResponse(valid = false, profileToken = "token").authorizedProfileToken())
    }

    @Test
    fun `a valid verification authorizes with its token`() {
        assertEquals(
            "token-for-a",
            VerifyPinResponse(valid = true, profileToken = "token-for-a").authorizedProfileToken(),
        )
    }
}
