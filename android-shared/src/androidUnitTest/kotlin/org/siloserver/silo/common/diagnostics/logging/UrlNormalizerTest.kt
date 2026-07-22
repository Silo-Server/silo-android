package org.siloserver.silo.common.diagnostics.logging

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlNormalizerTest {

    @Test
    fun `strips userinfo query and fragment but keeps path`() {
        val out = UrlNormalizer.normalize("https://user:pass@example.com:8443/path/to?x=1&token=abc#frag")
        assertTrue(out.endsWith("/path/to"), out)
        assertFalse(out.contains("user"), out)
        assertFalse(out.contains("pass"), out)
        assertFalse(out.contains("x=1"), out)
        assertFalse(out.contains("token"), out)
        assertFalse(out.contains("frag"), out)
    }

    @Test
    fun `non-loopback host becomes stable 12-hex host token`() {
        val out = UrlNormalizer.normalize("https://example.com/path/to")
        assertTrue(Regex("""^https://\[host:[0-9a-f]{12}]/path/to$""").matches(out), out)
        // Stable across calls and case-insensitive on the host.
        assertEquals(out, UrlNormalizer.normalize("https://EXAMPLE.COM/path/to"))
        assertTrue(out.contains(UrlNormalizer.hostToken("example.com")), out)
    }

    @Test
    fun `localhost is kept verbatim`() {
        assertEquals("http://localhost/api/items", UrlNormalizer.normalize("http://localhost:8080/api/items"))
        assertEquals("http://127.0.0.1/api/x", UrlNormalizer.normalize("http://127.0.0.1:8096/api/x"))
    }

    @Test
    fun `garbage input degrades to url placeholder`() {
        assertEquals("[url]", UrlNormalizer.normalize("notaurl"))
        assertEquals("[url]", UrlNormalizer.normalize("http://")) // no host
        assertEquals("[url]", UrlNormalizer.normalize("ht tp://broken .com/x")) // unparseable
        assertEquals("[url]", UrlNormalizer.normalize("//example.com/path")) // no scheme
    }
}
