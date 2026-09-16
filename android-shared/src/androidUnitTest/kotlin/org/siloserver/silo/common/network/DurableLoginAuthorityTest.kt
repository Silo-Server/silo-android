package org.siloserver.silo.common.network

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.engine.mock.respond
import io.ktor.serialization.kotlinx.json.json
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.network.*
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class DurableLoginAuthorityTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("authority-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)

    @Test
    fun invalidatedInvitationAttemptCannotInstallAfterWaitingForIdentityLock() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(prefs, barrier)
        val server = registry.addOrUpdate("https://existing.example.test")
        val manager = EncryptedTokenManagerImpl(prefs = prefs, registry = registry, identityTransitions = barrier)
        manager.replaceAccountSession(serverId = server, accessToken = "old-access", refreshToken = "old-refresh", expiresIn = 3600, profileId = "profile")
        val before = manager.snapshotDurableLoginAuthority()
        var valid = true
        val expected = checkNotNull(manager.captureAccountSessionExpectation()).copy(installationAllowed = { valid })
        val locked = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val blocker = async { barrier.withCurrentGeneration(expected.generation) { locked.complete(Unit); release.await(); Unit } }
        locked.await()
        val replacing = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            assertFailsWith<AccountSessionChangedException> {
                manager.replaceAccountSession(accessToken = "stale-access", refreshToken = "stale-refresh", expiresIn = 3600, expectedIdentity = expected)
            }
        }
        valid = false
        release.complete(Unit); blocker.await(); replacing.await()
        assertEquals("old-access", manager.getAccessToken())
        assertEquals("old-refresh", manager.getRefreshToken())
        assertEquals(before, manager.snapshotDurableLoginAuthority())
    }


    @Test fun refreshAndRecreationPreserveLoginButExplicitReloginRotatesAndRejectsLateRefresh() = runTest {
        val transitions = DefaultIdentityTransitionBarrier()
        val registry = AndroidServerRegistry(prefs, transitions)
        val id = registry.addOrUpdate("https://example.invalid")
        val tokens = EncryptedTokenManagerImpl(prefs, registry, transitions)
        tokens.replaceAccountSession(id, null, "a", "r", 3600, "p", "pt")
        val first = assertNotNull(tokens.snapshotDurableLoginAuthority())
        tokens.saveTokensForScope(first.scope, "rotated-a", "rotated-r", 3600)
        assertEquals(first.loginId, tokens.snapshotDurableLoginAuthority()?.loginId)
        tokens.saveTokens("rotated-again", "rotated-again-r", 3600)
        assertEquals(first.loginId, tokens.snapshotDurableLoginAuthority()?.loginId)
        val reopened = EncryptedTokenManagerImpl(prefs, AndroidServerRegistry(prefs))
        assertEquals(first.loginId, reopened.snapshotDurableLoginAuthority()?.loginId)
        tokens.setProfileIdentity("p2", "pt2")
        assertEquals(first.loginId, tokens.snapshotDurableLoginAuthority()?.loginId)
        tokens.replaceAccountSession(id, null, "new-a", "new-r", 3600, "p", "pt")
        val second = assertNotNull(tokens.snapshotDurableLoginAuthority())
        assertNotEquals(first.loginId, second.loginId)
        tokens.saveTokensForScope(first.scope, "stale-a", "stale-r", 3600)
        assertEquals(second.loginId, tokens.snapshotDurableLoginAuthority()?.loginId)
        assertEquals("new-a", tokens.getAccessToken())
        tokens.clearTokens()
        assertNull(prefs.getString("$id.login_authority_id", null))
        assertNull(tokens.snapshotDurableLoginAuthority())
        tokens.replaceAccountSession(id, null, "third-a", "third-r", 3600, "p", "pt")
        assertNotEquals(second.loginId, tokens.snapshotDurableLoginAuthority()?.loginId)
    }

    @Test fun bootstrapIsConcurrentAndCheckedEvenIfFailedCommitChangedPreferenceMemory() = runTest {
        val registry = AndroidServerRegistry(prefs)
        val id = registry.addOrUpdate("https://example.invalid")
        registry.switchTo(id)
        // Existing authenticated installation predating durable identity.
        prefs.edit().putString("$id.access_token", "a").putString("$id.refresh_token", "r")
            .putString("$id.profile_id", "p").commit()
        var fail = true
        val controlled = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                    override fun commit(): Boolean { delegate.commit(); return !fail }
                }
            }
        }
        val tokens = EncryptedTokenManagerImpl(controlled, registry)
        assertNull(tokens.snapshotDurableLoginAuthority())
        assertNull(tokens.snapshotDurableLoginAuthority())
        fail = false
        val ids = List(8) { async { assertNotNull(tokens.snapshotDurableLoginAuthority()).loginId } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        assertEquals(ids.first(), EncryptedTokenManagerImpl(prefs, AndroidServerRegistry(prefs)).snapshotDurableLoginAuthority()?.loginId)
    }

    @Test fun failedExplicitReplacementExposesNoAuthority() = runTest {
        var fail = false
        val registry = AndroidServerRegistry(prefs, commitEditor = { it.commit(); !fail })
        val id = registry.addOrUpdate("https://example.invalid")
        val tokens = EncryptedTokenManagerImpl(prefs, registry)
        tokens.replaceAccountSession(id, null, "a", "r", 3600, "p", "pt")
        val first = assertNotNull(tokens.snapshotDurableLoginAuthority())
        fail = true
        assertFailsWith<IllegalStateException> { tokens.replaceAccountSession(id, null, "b", "s", 3600, "p", "pt") }
        assertNull(tokens.snapshotDurableLoginAuthority())
        fail = false
        tokens.replaceAccountSession(id, null, "c", "t", 3600, "p", "pt")
        assertNotEquals(first.loginId, tokens.snapshotDurableLoginAuthority()?.loginId)
    }

    @Test fun serverSwitchAndTemporaryOverlayDoNotReplaceSavedLogin() = runTest {
        val registry = AndroidServerRegistry(prefs)
        val a = registry.addOrUpdate("https://a.example.invalid")
        val b = registry.addOrUpdate("https://b.example.invalid")
        val tokens = EncryptedTokenManagerImpl(prefs, registry)
        tokens.replaceAccountSession(a, null, "a", "r", 3600, "p", "pt")
        val first = assertNotNull(tokens.snapshotDurableLoginAuthority())
        tokens.replaceAccountSession(b, null, "b", "s", 3600, "p", "pt")
        assertNotEquals(first.loginId, tokens.snapshotDurableLoginAuthority()?.loginId)
        registry.switchTo(a)
        assertEquals(first.loginId, tokens.snapshotDurableLoginAuthority()?.loginId)
        tokens.beginTemporaryScope(TemporaryAuthScope("overlay", a, "https://a.example.invalid", "o", "or", "p", "pt", Long.MAX_VALUE))
        assertNull(tokens.snapshotDurableLoginAuthority())
        tokens.endTemporaryScope()
        assertEquals(first.loginId, tokens.snapshotDurableLoginAuthority()?.loginId)
    }

    @Test fun delayedLoginCannotInstallAfterServerSwitchOrSameAccountRelogin() = runTest {
        for (switchServer in listOf(true, false)) {
            val transitions = DefaultIdentityTransitionBarrier()
            val registry = AndroidServerRegistry(prefs, transitions)
            val firstServer = registry.addOrUpdate("https://first.example.invalid")
            val otherServer = registry.addOrUpdate("https://other.example.invalid")
            val tokens = EncryptedTokenManagerImpl(prefs, registry, transitions)
            tokens.replaceAccountSession(firstServer, null, "original", "original-r", 3600, "p", "pt")
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
                assertEquals("first.example.invalid", request.url.host)
                assertEquals("/api/v2/auth/login", request.url.encodedPath)
                entered.complete(Unit); release.await()
                respond("""{"access_token":"stale","refresh_token":"stale-r","expires_in":3600,"user":{"id":"1","username":"user","email":"u@example.test","role":"user"}}""",
                    io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
            }) {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json(SiloJson) }
            }
            try {
                val repository = org.siloserver.silo.repository.AuthRepository(org.siloserver.silo.network.api.AuthApi(client, ApiV2Gate.Unrestricted), tokens, registry)
                val login = async { repository.login("user", "password") }; entered.await()
                if (switchServer) registry.switchTo(otherServer)
                else tokens.replaceAccountSession(firstServer, null, "newer", "newer-r", 3600, "p", "pt")
                val expectedAuthority = tokens.snapshotDurableLoginAuthority()
                release.complete(Unit)
                assertEquals("identity_changed", assertIs<ApiResult.Error>(login.await()).error)
                assertEquals(if (switchServer) otherServer else firstServer, registry.activeServerId.value)
                assertEquals(if (switchServer) null else "newer", tokens.getAccessToken())
                assertEquals(expectedAuthority, tokens.snapshotDurableLoginAuthority())
            } finally { client.close() }
        }
    }
}
