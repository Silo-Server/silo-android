package org.siloserver.silo.android.ui.navigation

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.server.ServerEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `silo://device?server=<server_id>&url=<base>&code=` links name their server
 * by deployment identity. These pin how that identity picks a saved server.
 */
class DeviceLoginServerIdentityMatchTest {
    private val home = ServerEntry(id = "a", url = "https://home.example", fetchedName = "Home")
    private val lan = ServerEntry(id = "b", url = "http://192.168.1.5:8090", fetchedName = "Home (LAN)")
    private val other = ServerEntry(id = "c", url = "https://other.example", fetchedName = "Other")

    private suspend fun match(
        linkUrl: String?,
        active: ServerEntry?,
        ids: Map<String, String?>,
        required: String = "S-HOME",
    ) = deviceLoginServerMatchByIdentity(
        requiredServerId = required,
        linkUrl = linkUrl,
        activeEntry = active,
        entries = listOf(home, lan, other),
        identityOf = { ids[it.id] },
    )

    @Test
    fun activeServerWithTheIdentityProceedsEvenAtAnotherAddress() = runTest {
        // The link names the public URL; the phone saved the LAN address of the same server.
        assertEquals(
            DeviceLoginServerMatch.Active,
            match("https://home.example", lan, mapOf("b" to "S-HOME", "a" to "S-HOME")),
        )
    }

    @Test
    fun anotherSavedServerWithTheIdentityOffersTheSwitch() = runTest {
        assertEquals(
            DeviceLoginServerMatch.SwitchRequired(lan),
            match("https://public.example", other, mapOf("c" to "S-OTHER", "b" to "S-HOME")),
        )
    }

    @Test
    fun noSavedServerWithTheIdentityIsUnknownEvenWhenTheAddressMatches() = runTest {
        // Same address, different deployment: identity wins over URL spelling.
        assertEquals(
            DeviceLoginServerMatch.UnknownServer("https://home.example"),
            match("https://home.example", home, mapOf("a" to "S-ELSE", "b" to null, "c" to "S-OTHER")),
        )
    }

    @Test
    fun unverifiableServersFallBackToTheLinkOrigin() = runTest {
        assertEquals(
            DeviceLoginServerMatch.Active,
            match("https://home.example", home, emptyMap()),
        )
        assertEquals(
            DeviceLoginServerMatch.SwitchRequired(home),
            match("https://home.example", other, mapOf("c" to "S-OTHER")),
        )
    }

    @Test
    fun noServerYetProceedsThroughSetup() = runTest {
        assertEquals(DeviceLoginServerMatch.Active, match("https://home.example", null, emptyMap()))
    }

    @Test
    fun parserCarriesTheServerIdentityAndBase() {
        assertEquals(
            "pair_device?code=48217730&serverId=S-HOME&serverUrl=https%3A%2F%2Fhome.example%2Fsilo",
            deviceLoginPairRouteOrNull("silo://device?server=S-HOME&url=https%3A%2F%2Fhome.example%2Fsilo%2F&code=48217730"),
        )
        // A url that isn't http(s) is dropped, not trusted.
        assertEquals(
            "pair_device?code=48217730&serverId=S-HOME",
            deviceLoginPairRouteOrNull("silo://device?server=S-HOME&url=javascript%3Aalert(1)&code=48217730"),
        )
        // The TV's QR link opens the app too when the OS delivers it.
        assertEquals(
            "pair_device?code=48217730&serverOrigin=https%3A%2F%2Fhome.example",
            deviceLoginPairRouteOrNull("https://home.example/activate?code=48217730"),
        )
    }

    @Test
    fun aCodeLinkForAnotherSavedServerIsApprovedThereWithoutSwitching() {
        val match = DeviceLoginServerMatch.SwitchRequired(lan)
        assertEquals(lan, match.inPlaceApprovalServer(token = null, code = "48217730"))
        // Only the active server's lookup accepts a token: that still needs the switch.
        assertEquals(null, match.inPlaceApprovalServer(token = "tok", code = null))
        assertEquals(null, match.inPlaceApprovalServer(token = null, code = " "))
    }
}
