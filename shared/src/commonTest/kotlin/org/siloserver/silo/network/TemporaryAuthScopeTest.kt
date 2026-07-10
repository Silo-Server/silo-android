package org.siloserver.silo.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemporaryAuthScopeTest {
    @Test
    fun temporaryReadsAndRefreshWritesDoNotOverwritePersistentIdentity() = runTest {
        val manager = TokenManagerImpl()
        manager.setServerUrl("https://tv.example")
        manager.saveTokens("tv-access", "tv-refresh", 3_600)
        manager.setProfileId("tv-profile")
        manager.setProfileToken("tv-profile-token")

        manager.beginTemporaryScope(
            TemporaryAuthScope(
                serverId = "phone-server",
                serverUrl = "https://phone.example",
                accessToken = "phone-access",
                refreshToken = "phone-refresh",
                profileId = "phone-profile",
                profileToken = "phone-profile-token",
                controllerDeviceId = "phone-1",
            ),
        )

        assertEquals("phone-access", manager.getAccessToken())
        assertEquals("phone-profile", manager.getProfileId())
        assertEquals("https://phone.example", manager.getServerUrl())
        manager.saveTokens("rotated-access", "rotated-refresh", 3_600)
        assertEquals("rotated-access", manager.getAccessToken())

        manager.endTemporaryScope()
        assertEquals("tv-access", manager.getAccessToken())
        assertEquals("tv-refresh", manager.getRefreshToken())
        assertEquals("tv-profile", manager.getProfileId())
        assertEquals("tv-profile-token", manager.getProfileToken())
        assertEquals("https://tv.example", manager.getServerUrl())
        assertNull(manager.getTemporaryScope())
    }
}
