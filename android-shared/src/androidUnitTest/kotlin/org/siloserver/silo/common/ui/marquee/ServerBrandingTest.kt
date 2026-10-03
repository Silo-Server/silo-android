package org.siloserver.silo.common.ui.marquee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Branding asset URLs are read before sign-in, so only the server's own origin is accepted. */
class ServerBrandingTest {
    @Test
    fun resolvesSiteRelativePathsAgainstTheServer() {
        assertEquals(
            "https://media.example.com/branding/mark.png",
            ServerBranding.resolveAsset("/branding/mark.png", "https://media.example.com"),
        )
        assertEquals(
            "https://media.example.com/silo/branding/mark.png",
            ServerBranding.resolveAsset("branding/mark.png", "https://media.example.com/silo"),
        )
    }

    @Test
    fun acceptsTheSameOriginWithTheDefaultPortSpelledOut() {
        assertEquals(
            "https://media.example.com:443/mark.png",
            ServerBranding.resolveAsset("https://media.example.com:443/mark.png", "https://media.example.com"),
        )
    }

    @Test
    fun rejectsOtherHostsSchemesAndPorts() {
        val base = "https://media.example.com"
        assertNull(ServerBranding.resolveAsset("https://evil.example.net/mark.png", base))
        assertNull(ServerBranding.resolveAsset("http://media.example.com/mark.png", base))
        assertNull(ServerBranding.resolveAsset("https://media.example.com:8443/mark.png", base))
        // A protocol-relative path stays on the server, as a path.
        assertEquals("https://media.example.com/evil.example.net/mark.png", ServerBranding.resolveAsset("//evil.example.net/mark.png", base))
        assertNull(ServerBranding.resolveAsset("javascript:alert(1)", base))
        assertNull(ServerBranding.resolveAsset("  ", base))
    }

    @Test
    fun clampsAccentsToALegibleTint() {
        assertNull(MarqueeScene.clampedAccent("not a color"))
        assertNull(MarqueeScene.clampedAccent("#12345"))
    }
}
