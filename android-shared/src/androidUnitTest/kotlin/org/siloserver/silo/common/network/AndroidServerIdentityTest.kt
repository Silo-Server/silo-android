package org.siloserver.silo.common.network

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.network.AndroidServerRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidServerIdentityTest {

    @Test
    fun matchesSchemeAndHostCaseAndDefaultPorts() {
        val phone = wireId("https://Media.Example.test/library")
        val tv = wireId("HTTPS://media.example.test:443/library/")

        assertFalse(phone == tv, "persisted registry keys remain unchanged")
        assertTrue(AndroidServerRegistry.serverIdsMatch(phone, tv))
        assertTrue(
            AndroidServerRegistry.serverIdsMatch(
                wireId("http://MEDIA.example.test:80/library"),
                wireId("http://media.example.test/library"),
            ),
        )
    }

    @Test
    fun preservesCredentialsPathQueryFragmentAndNonDefaultPort() {
        val canonical = wireId("https://User:Pass@MEDIA.example.test:8443/Library?mode=A#Top")

        assertTrue(
            AndroidServerRegistry.serverIdsMatch(
                canonical,
                wireId("HTTPS://User:Pass@media.example.test:8443/Library?mode=A#Top"),
            ),
        )
        assertFalse(
            AndroidServerRegistry.serverIdsMatch(
                canonical,
                wireId("https://user:Pass@media.example.test:8443/Library?mode=A#Top"),
            ),
        )
        assertFalse(
            AndroidServerRegistry.serverIdsMatch(
                canonical,
                wireId("https://User:Pass@media.example.test:443/Library?mode=A#Top"),
            ),
        )
        assertFalse(
            AndroidServerRegistry.serverIdsMatch(
                canonical,
                wireId("https://User:Pass@media.example.test:8443/library?mode=A#Top"),
            ),
        )
        assertFalse(
            AndroidServerRegistry.serverIdsMatch(
                canonical,
                wireId("https://User:Pass@media.example.test:8443/Library?mode=a#Top"),
            ),
        )
        assertFalse(
            AndroidServerRegistry.serverIdsMatch(
                canonical,
                wireId("https://User:Pass@media.example.test:8443/Library?mode=A#top"),
            ),
        )
        assertTrue(
            AndroidServerRegistry.serverIdsMatch(
                wireId("https://User:Pass@MÉDIA.example.test:443/Library"),
                wireId("HTTPS://User:Pass@média.example.test/Library"),
            ),
        )
    }

    @Test
    fun decoderRequiresAnExactRoundTrippingHttpUrl() {
        val original = "https://Média.example.test:443/silo?mode=A#top"
        val serverId = wireId(original)

        assertEquals(original, AndroidServerRegistry.urlForServerId(serverId))
        assertNull(AndroidServerRegistry.urlForServerId("not-a-registry-id"))
        assertNull(AndroidServerRegistry.urlForServerId(wireId("file:///tmp/silo")))
        assertNull(
            AndroidServerRegistry.urlForServerId(
                AndroidServerRegistry.idFor("https://media.example.test/"),
            ),
        )
        assertNull(AndroidServerRegistry.urlForServerId("$serverId="))
    }

    @Test
    fun exactUnknownIdsMatchButMissingOrDistinctIdsDoNot() {
        assertTrue(AndroidServerRegistry.serverIdsMatch("future-format", "future-format"))
        assertFalse(AndroidServerRegistry.serverIdsMatch("future-format-a", "future-format-b"))
        assertFalse(AndroidServerRegistry.serverIdsMatch(null, "future-format"))
        assertFalse(AndroidServerRegistry.serverIdsMatch("", ""))
    }

    private fun wireId(url: String): String =
        AndroidServerRegistry.idFor(url.trim().trimEnd('/'))
}
