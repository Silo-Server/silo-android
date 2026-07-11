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

@RunWith(RobolectricTestRunner::class)
class RegistryCompanionPairingServerStoreTest {
    @Test
    fun snapshotExcludesSavedServersWithoutAnAccessToken() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pairing-server-store-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val registry = AndroidServerRegistry(prefs)
        val tokens = EncryptedTokenManagerImpl(prefs, registry)
        val signedInId = registry.addOrUpdate("https://signed-in.example", "Signed In")
        val signedOutId = registry.addOrUpdate("https://signed-out.example", "Signed Out")
        registry.switchTo(signedInId)
        tokens.switchActiveServer(signedInId)
        tokens.saveTokens("access", "refresh", 3600)

        val snapshot = RegistryCompanionPairingServerStore(registry, tokens).snapshot()

        assertEquals(signedInId, snapshot.activeServerId)
        assertEquals(listOf(signedInId), snapshot.servers.map { it.id })
        assertEquals("Signed In", snapshot.servers.single().displayName)
        assertEquals(signedOutId, registry.entries.value.single { it.id == signedOutId }.id)
    }
}
