package org.siloserver.silo.common.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.EncryptedTokenManagerImpl
import org.siloserver.silo.network.TemporaryAuthScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class EncryptedTokenManagerScopeIsolationTest {
    @Test
    fun persistentAndTemporarySnapshotsNeverFallThroughToEachOther() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("token-scope-isolation-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val registry = AndroidServerRegistry(prefs)
        val serverId = registry.addOrUpdate("https://silo.example")
        registry.switchTo(serverId)
        val tokens = EncryptedTokenManagerImpl(prefs, registry)
        tokens.switchActiveServer(serverId)
        tokens.saveTokens("persistent-access", "persistent-refresh", 3600)
        tokens.setProfileId("tv-profile")
        tokens.setProfileToken("tv-profile-token")
        val persistentScope = requireNotNull(tokens.snapshotCurrentScope())

        tokens.beginTemporaryScope(
            TemporaryAuthScope(
                generationId = "remote-generation",
                serverId = serverId,
                serverUrl = "https://silo.example",
                accessToken = "temporary-access",
                refreshToken = "temporary-refresh",
                profileId = "phone-profile",
                profileToken = "phone-profile-token",
                expiresAtEpochMs = Long.MAX_VALUE,
            ),
        )
        val temporaryScope = requireNotNull(tokens.snapshotCurrentScope())

        assertEquals("persistent-access", tokens.getAccessTokenForScope(persistentScope))
        assertEquals("persistent-access", tokens.getAccessTokenForScope(serverId))
        assertEquals("temporary-access", tokens.getAccessTokenForScope(temporaryScope))

        tokens.saveTokensForScope(persistentScope, "persistent-rotated", "persistent-refresh-2", 3600)
        tokens.saveTokensForScope(temporaryScope, "temporary-rotated", "temporary-refresh-2", 3600)
        assertEquals("persistent-rotated", tokens.getAccessTokenForScope(persistentScope))
        assertEquals("temporary-rotated", tokens.getAccessTokenForScope(temporaryScope))

        tokens.endTemporaryScope()
        assertNull(tokens.getAccessTokenForScope(temporaryScope))
        assertEquals("persistent-rotated", tokens.getAccessTokenForScope(persistentScope))

        // A refresh response arriving after the temporary generation ended is
        // discarded instead of overwriting the saved account.
        tokens.saveTokensForScope(temporaryScope, "stale-temporary", "stale-refresh", 3600)
        assertEquals("persistent-rotated", tokens.getAccessTokenForScope(persistentScope))
    }
}
