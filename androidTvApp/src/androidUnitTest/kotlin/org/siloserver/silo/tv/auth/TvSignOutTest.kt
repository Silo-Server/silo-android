package org.siloserver.silo.tv.auth

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.cast.SiloCastHandoffOffer
import org.siloserver.silo.common.profiles.ProfileLaunchBehavior
import org.siloserver.silo.common.profiles.ProfileLaunchPreferences
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.CleartextOriginConsent
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.EncryptedTokenManagerImpl
import org.siloserver.silo.network.TemporaryAuthScope
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.DeviceLoginApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.tv.cast.FakeRemotePlaybackApi
import org.siloserver.silo.tv.cast.RemotePlaybackIdentityManager
import org.siloserver.silo.tv.ui.screens.profiles.InMemoryPrefs
import org.siloserver.silo.tv.ui.screens.servers.TV_REMOVE_SERVER_FAILED
import org.siloserver.silo.tv.ui.screens.servers.TvServerResolver
import org.siloserver.silo.tv.ui.screens.servers.TvServerSwitchDestination
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The TV's Sign Out against a real saved-server registry and token store: who
 * is signed out, which servers stay saved, and where the TV goes.
 */
// Robolectric for the real android.util.Base64 a phone's handoff offer is checked with.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TvSignOutTest {
    /** Holds the server's logout answer while set. */
    private var logoutGate: CompletableDeferred<Unit>? = null
    /** What reached the server and the borrowed identity's owner, in order. */
    private val events = mutableListOf<String>()
    private val http = HttpClient(
        MockEngine {
            events += "logout"
            logoutGate?.await()
            respond("", HttpStatusCode.NoContent)
        },
    )

    @AfterTest fun tearDown() = http.close()

    @Test
    fun signOutKeepsTheServerAndGoesToItsSignIn() = runTest {
        val tv = Tv(this, servers = listOf("home", "cabin"))
        val home = tv.signIn("home")
        val cabin = tv.signIn("cabin")
        tv.switchTo(home)

        assertEquals(TvSignOutResult.SignedOut(TvServerSwitchDestination.Login), tv.signOut(removeServer = false))

        assertNull(tv.tokens.loginSessionId(home), "home is signed out")
        assertNotNull(tv.tokens.loginSessionId(cabin), "cabin keeps its login")
        assertEquals(home, tv.registry.activeServerId.value)
        assertEquals(setOf(home, cabin), tv.savedServers())
        assertEquals(1, tv.cachesCleared)
    }

    @Test
    fun signOutAndRemoveForgetsTheServerAndOpensTheNextSavedOne() = runTest {
        val tv = Tv(this, servers = listOf("home", "cabin"))
        val cabin = tv.signIn("cabin")
        tv.signIn("home")

        // Cabin is still signed in with no profile chosen: Who's Watching.
        assertEquals(
            TvSignOutResult.SignedOut(TvServerSwitchDestination.ProfileSelection),
            tv.signOut(removeServer = true),
        )
        assertEquals(setOf(cabin), tv.savedServers())
        assertEquals(cabin, tv.registry.activeServerId.value)
        assertEquals("$cabin-access", tv.tokens.getAccessToken())

        // The last saved server: first-run setup.
        assertEquals(
            TvSignOutResult.SignedOut(TvServerSwitchDestination.ServerSetup),
            tv.signOut(removeServer = true),
        )
        assertEquals(emptySet(), tv.savedServers())
        assertNull(tv.registry.activeServerId.value)
    }

    @Test
    fun aSecondRequestWhileOneRunsIsIgnored() = runTest {
        val tv = Tv(this)
        val home = tv.signIn("home")
        logoutGate = CompletableDeferred()

        assertTrue(tv.signOut.start(removeServer = false))
        assertFalse(tv.signOut.start(removeServer = true), "a double Select or a second entry point")
        logoutGate!!.complete(Unit)

        assertEquals(TvSignOutResult.SignedOut(TvServerSwitchDestination.Login), tv.awaitResult())
        assertEquals(listOf("logout"), events, "the server is told once")
        assertEquals(setOf(home), tv.savedServers(), "the ignored request's removal never ran")
        // Free again once it finished.
        assertTrue(tv.signOut.start(removeServer = true))
    }

    @Test
    fun aBorrowedIdentityIsEndedThroughItsOwnerAndTheTvsOwnLoginSignsOut() = runTest {
        lateinit var tv: Tv
        tv = Tv(this, endBorrowedIdentity = { events += "borrowed identity ended"; tv.tokens.endTemporaryScope() })
        val home = tv.signIn("home")
        tv.tokens.beginTemporaryScope(borrowed(home))

        assertEquals(TvSignOutResult.SignedOut(TvServerSwitchDestination.Login), tv.signOut(removeServer = false))

        // The phone's session ends through its owner before the TV's own
        // login is signed out.
        assertEquals(listOf("borrowed identity ended", "logout"), events)
        assertNull(tv.tokens.loginSessionId(home))
    }

    @Test
    fun aBorrowedIdentityThatOutlivesItsEndSignsNobodyOut() = runTest {
        lateinit var tv: Tv
        // The phone handed off again as soon as the identity ended.
        tv = Tv(this, endBorrowedIdentity = {
            tv.tokens.endTemporaryScope()
            tv.tokens.beginTemporaryScope(borrowed(tv.registry.activeServerId.value!!))
            true
        })
        val home = tv.signIn("home")
        tv.tokens.beginTemporaryScope(borrowed(home))

        assertEquals(TvSignOutResult.Refused(TvSignOut.REMOTE_PLAYBACK_NOT_ENDED), tv.signOut(removeServer = true))

        assertTrue(events.isEmpty(), "no server was told")
        tv.tokens.endTemporaryScope()
        assertNotNull(tv.tokens.loginSessionId(home), "the TV stays signed in")
        assertEquals(setOf(home), tv.savedServers())
    }

    @Test
    fun anEndQueuedBehindAHandoffLeavesThePhonesNewIdentityAlone() = runTest {
        val approval = CompletableDeferred<Unit>()
        val phone = HeldApprovalApi()
        lateinit var identities: RemotePlaybackIdentityManager
        val tv = Tv(this, phone = { tokens -> RemotePlaybackIdentityManager(phone, tokens, deviceNameProvider = { "TV" }).also { identities = it } })
        val home = tv.signIn("home")
        identities.prepare(handoffOffer(), "phone", null, receiverRun = 1, allowReuse = false) {}

        // A second handoff holds the identity's lock while its approval is pending.
        phone.approval = approval
        var replacement: String? = null
        val handoff = launch {
            identities.prepare(handoffOffer(), "phone", null, receiverRun = 2, allowReuse = false, onPrepared = { replacement = it }) {}
        }
        runCurrent()

        assertEquals(TvSignOutResult.Refused(TvSignOut.REMOTE_PLAYBACK_NOT_ENDED), tv.signOut(removeServer = false))

        // Approved after the sign-out gave up: the end it queued must not take the new identity with it.
        approval.complete(Unit)
        handoff.join()
        runCurrent()
        assertEquals(assertNotNull(replacement), identities.activeIdentity?.generationId)
        assertTrue(tv.tokens.hasTemporaryScope())
        assertEquals(1, phone.endCalls, "only the first identity, which the handoff replaced")
        assertNotNull(tv.tokens.loginSessionId(home), "the TV stays signed in")
    }

    @Test
    fun aServerSwitchWhileTheSignOutWaitsSignsNobodyOut() = runTest {
        lateinit var tv: Tv
        var cabin = ""
        tv = Tv(this, servers = listOf("home", "cabin"), flushPendingSettings = { tv.switchTo(cabin) })
        cabin = tv.signIn("cabin")
        val home = tv.signIn("home")

        assertEquals(TvSignOutResult.Refused(TvSignOut.SESSION_CHANGED), tv.signOut(removeServer = true))

        assertNotNull(tv.tokens.loginSessionId(home))
        assertNotNull(tv.tokens.loginSessionId(cabin))
        assertEquals(setOf(home, cabin), tv.savedServers())
        assertEquals(0, tv.cachesCleared)
    }

    @Test
    fun aFailedLocalWriteRefusesAndTheNextTrySucceeds() = runTest {
        val tv = Tv(this)
        val home = tv.signIn("home")
        tv.failCommits = true

        assertEquals(TvSignOutResult.Refused(TvSignOut.LOCAL_SIGN_OUT_FAILED), tv.signOut(removeServer = false))
        assertEquals(setOf(home), tv.savedServers())

        tv.failCommits = false
        assertEquals(TvSignOutResult.SignedOut(TvServerSwitchDestination.Login), tv.signOut(removeServer = false))
        assertNull(tv.tokens.loginSessionId(home))
    }

    @Test
    fun aRemovalThatFailsToSaveLeavesTheServerSignedOutOnItsSignIn() = runTest {
        lateinit var tv: Tv
        // The sign-out saved; the removal right after it doesn't.
        tv = Tv(this, clearProfileCaches = { tv.failCommits = true })
        val home = tv.signIn("home")

        assertEquals(
            TvSignOutResult.SignedOut(TvServerSwitchDestination.Login, notice = TV_REMOVE_SERVER_FAILED),
            tv.signOut(removeServer = true),
        )
        assertEquals(setOf(home), tv.savedServers())
        assertEquals(home, tv.registry.activeServerId.value)
        assertNull(tv.tokens.loginSessionId(home))
    }

    @Test
    fun aFailureBeforeTheLogoutRefusesAndAFailureAfterItStillSignsOut() = runTest {
        var flushFails = true
        var cachesFail = false
        val tv = Tv(
            this,
            flushPendingSettings = { if (flushFails) error("settings store unavailable") },
            clearProfileCaches = { if (cachesFail) error("cache store unavailable") },
        )
        val home = tv.signIn("home")

        assertEquals(TvSignOutResult.Refused(TvSignOut.LOCAL_SIGN_OUT_FAILED), tv.signOut(removeServer = false))
        assertNotNull(tv.tokens.loginSessionId(home), "nobody was signed out")

        flushFails = false
        cachesFail = true
        assertEquals(TvSignOutResult.SignedOut(TvServerSwitchDestination.Login), tv.signOut(removeServer = false))
        assertNull(tv.tokens.loginSessionId(home))
    }

    @Test
    fun aDestinationThatCantBeWorkedOutAfterRemovingStillSignsOut() = runTest {
        // The next server would reopen Who's Watching, and clearing its profile fails.
        val tv = Tv(
            this,
            servers = listOf("home", "cabin"),
            clearActiveProfile = { error("profile store unavailable") },
            launchBehavior = ProfileLaunchBehavior.EveryTime,
        )
        val cabin = tv.signIn("cabin")
        tv.tokens.setProfileId("cabin-profile")
        val home = tv.signIn("home")

        assertEquals(TvSignOutResult.SignedOut(TvServerSwitchDestination.ServerSetup), tv.signOut(removeServer = true))
        assertEquals(setOf(cabin), tv.savedServers())
        assertNull(tv.tokens.loginSessionId(home))
    }

    private fun borrowed(serverId: String) = TemporaryAuthScope(
        generationId = "phone-1",
        serverId = serverId,
        serverUrl = "https://phone.example",
        accessToken = "phone-access",
        refreshToken = "phone-refresh",
        profileId = "phone-profile",
        profileToken = "phone-profile-token",
        expiresAtEpochMs = Long.MAX_VALUE,
    )

    private fun handoffOffer() = SiloCastHandoffOffer(
        requestId = "request",
        serverId = AndroidServerRegistry.idFor(PHONE_SERVER_URL),
        serverURL = PHONE_SERVER_URL,
        profileId = "profile-1",
    )

    /** Approves each handoff, after [approval] when it is set. */
    private class HeldApprovalApi(private val inner: FakeRemotePlaybackApi = FakeRemotePlaybackApi()) : DeviceLoginApi by inner {
        var approval: CompletableDeferred<Unit>? = null
        val endCalls get() = inner.endCalls

        override suspend fun pollDeviceLoginAt(serverUrl: String, deviceCode: String) =
            approval?.await().let { inner.pollDeviceLoginAt(serverUrl, deviceCode) }
    }

    private inner class Tv(
        test: TestScope,
        servers: List<String> = listOf("home"),
        flushPendingSettings: suspend () -> Unit = {},
        endBorrowedIdentity: suspend (generationId: String) -> Boolean = { true },
        clearProfileCaches: () -> Unit = {},
        clearActiveProfile: suspend () -> Unit = {},
        launchBehavior: ProfileLaunchBehavior? = null,
        /** The real owner of borrowed identities, instead of [endBorrowedIdentity]. */
        phone: ((EncryptedTokenManagerImpl) -> RemotePlaybackIdentityManager)? = null,
    ) {
        var failCommits = false
        var cachesCleared = 0

        // Saved but signed out; android.util.Base64 (server ids) is a stub here.
        private val prefs = InMemoryPrefs().apply {
            val entries = servers.joinToString(",") { """{"id":"$it","url":"https://$it.example"}""" }
            edit()
                .putBoolean(AndroidServerRegistry.KEY_MIGRATED, true)
                .putString(AndroidServerRegistry.KEY_REGISTRY_STATE, """{"entries":[$entries],"activeServerId":"${servers.first()}"}""")
                .commit()
        }
        private val transitions = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(
            prefs,
            transitions,
            commitEditor = { editor -> !failCommits && editor.commit() },
        )
        val tokens = EncryptedTokenManagerImpl(prefs, registry, transitions)
        private val identities = phone?.invoke(tokens)
        private val auth = AuthRepository(AuthApi(http, ApiV2Gate.Unrestricted), tokens, registry)
        private val resolver = TvServerResolver(
            serverRegistry = registry,
            tokenManager = tokens,
            authRepository = auth,
            profileLaunchPreferences = ProfileLaunchPreferences(InMemoryPrefs()).apply { launchBehavior?.let(::setBehavior) },
            cleartextConsent = object : CleartextOriginConsent {
                override suspend fun isApproved(origin: String) = true
            },
            clearActiveProfile = clearActiveProfile,
        )
        val signOut = TvSignOut(
            authRepository = auth,
            tokenManager = tokens,
            serverRegistry = registry,
            resolver = resolver,
            scope = test.backgroundScope,
            flushPendingSettings = flushPendingSettings,
            awaitPlaybackStop = {},
            borrowedIdentity = { identities?.activeIdentity?.generationId ?: "phone-1" },
            endBorrowedIdentity = { generationId -> identities?.end(generationId) ?: endBorrowedIdentity(generationId) },
            clearProfileCaches = { cachesCleared++; clearProfileCaches() },
        )

        /** Signs in to saved server [id] and leaves it active. */
        suspend fun signIn(id: String): String {
            switchTo(id)
            tokens.saveTokens("$id-access", "$id-refresh", 3600)
            return id
        }

        suspend fun switchTo(id: String) {
            registry.switchTo(id)
            tokens.switchActiveServer(id)
        }

        fun savedServers() = registry.entries.value.map { it.id }.toSet()

        suspend fun signOut(removeServer: Boolean): TvSignOutResult {
            assertTrue(signOut.start(removeServer))
            return awaitResult()
        }

        suspend fun awaitResult(): TvSignOutResult =
            signOut.result.filterNotNull().first().also(signOut::consume)
    }

    private companion object {
        const val PHONE_SERVER_URL = "https://phone.example"
    }
}
