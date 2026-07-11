package org.siloserver.silo.common.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.EncryptedTokenManagerImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class RegistryPairingAuthPortTest {
    @Test
    fun approvedSameUrlSessionClearsOldProfileAndReplacesTokens() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-auth-port-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val registry = AndroidServerRegistry(prefs)
        val tokens = EncryptedTokenManagerImpl(prefs, registry)
        val serverUrl = "https://silo.example"
        val serverId = registry.addOrUpdate(serverUrl, fetchedName = "Old Server Name")
        registry.rename(serverId, "Living Room")
        registry.switchTo(serverId)
        tokens.switchActiveServer(serverId)
        tokens.saveTokens("old-access", "old-refresh", 3600)
        tokens.setProfileId("old-profile")
        tokens.setProfileToken("old-profile-token")
        registry.setProfileId(serverId, "old-profile")

        RegistryPairingAuthPort(tokens, registry).persistApprovedSession(
            serverUrl = serverUrl,
            serverName = "New Server Name",
            accessToken = "new-access",
            refreshToken = "new-refresh",
            expiresIn = 7200,
        )

        assertEquals(serverId, registry.activeServerId.value)
        assertNull(registry.activeEntry.value?.profileId)
        assertEquals("Living Room", registry.activeEntry.value?.userOverrideName)
        assertEquals("New Server Name", registry.activeEntry.value?.fetchedName)
        assertEquals("new-access", tokens.getAccessToken())
        assertEquals("new-refresh", tokens.getRefreshToken())
        assertNull(tokens.getProfileId())
        assertNull(tokens.getProfileToken())
    }
}
