package org.siloserver.silo.common.network

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
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
}
