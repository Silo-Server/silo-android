package org.siloserver.silo.common.diagnostics.logging

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Leak-fixture tests for the collection-time sanitizer. Each case asserts the
 * sensitive substring is GONE from the sanitized output — the design doc
 * requires these leak fixtures in CI.
 */
class DiagRedactorTest {

    @Before
    fun reset() {
        DiagRedactor.resetForTesting()
    }

    private fun sanitized(input: String): String = DiagRedactor.sanitize(input, maxBytes = 4096)

    @Test
    fun `jwt is redacted`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV"
        val out = sanitized("auth failed with token $jwt during refresh")
        assertFalse(out.contains(jwt), out)
        assertFalse(out.contains("eyJhbGciOi"), out)
    }

    @Test
    fun `authorization bearer header is redacted`() {
        val out = sanitized("request headers: Authorization: Bearer abc.def.ghi, Accept: */*")
        assertFalse(out.contains("abc.def.ghi"), out)
    }

    @Test
    fun `cookie header is redacted`() {
        val out = sanitized("Cookie: session=secretvalue")
        assertFalse(out.contains("secretvalue"), out)
    }

    @Test
    fun `url with credentials and query is normalized`() {
        val out = sanitized("GET https://user:pass@my.server.example:8443/api/v1/stream?token=SECRETTOK123 failed")
        assertFalse(out.contains("user:pass"), out)
        assertFalse(out.contains("SECRETTOK123"), out)
        assertFalse(out.contains("my.server.example"), out)
        assertTrue(out.contains("[host:"), out)
        assertTrue(out.contains("/api/v1/stream"), "path must survive normalization: $out")
    }

    @Test
    fun `bare bearer token is redacted`() {
        val out = sanitized("retrying with Bearer SECRETTOKEN after 401")
        assertFalse(out.contains("SECRETTOKEN"), out)
    }

    @Test
    fun `email address is redacted`() {
        val out = sanitized("signed in as fan.of.movies+silo@example.com just now")
        assertFalse(out.contains("fan.of.movies+silo@example.com"), out)
        assertTrue(out.contains("[email]"), out)
    }

    @Test
    fun `access_token key-value is redacted`() {
        val out = sanitized("query was access_token=SECRETVALUE99&limit=10")
        assertFalse(out.contains("SECRETVALUE99"), out)
    }

    @Test
    fun `profileId key-value is redacted`() {
        val out = sanitized("mismatch for profileId: SECRETPROF42 on switch")
        assertFalse(out.contains("SECRETPROF42"), out)
    }

    @Test
    fun `loopback url keeps its host`() {
        val out = sanitized("probing http://127.0.0.1:8096/api/x now")
        assertTrue(out.contains("127.0.0.1"), out)
        assertTrue(out.contains("/api/x"), out)
        assertFalse(out.contains("[host:"), out)
    }

    @Test
    fun `registered sensitive host is replaced outside url syntax`() {
        DiagRedactor.registerSensitiveHost("my.server.example")
        val out = sanitized("java.net.UnknownHostException: my.server.example not resolved")
        assertFalse(out.contains("my.server.example"), out)
        assertTrue(out.contains(UrlNormalizer.hostToken("my.server.example")), out)
    }

    @Test
    fun `registered host matching is case-insensitive`() {
        DiagRedactor.registerSensitiveHost("my.server.example")
        val out = sanitized("connect to MY.SERVER.EXAMPLE timed out")
        assertFalse(out.lowercase().contains("my.server.example"), out)
    }

    @Test
    fun `host that is a substring of its replacement token does not loop`() {
        // hostToken("host") is "[host:…]" which itself contains "host" — the
        // single forward pass must terminate and produce exactly one token.
        DiagRedactor.registerSensitiveHost("host")
        val out = sanitized("host unreachable")
        assertEquals("${UrlNormalizer.hostToken("host")} unreachable", out)
    }

    @Test
    fun `truncateUtf8 never splits a multibyte char`() {
        val emoji = "😀" // 😀 — 4 UTF-8 bytes
        val value = emoji.repeat(4) // 16 bytes
        // Cap lands mid-sequence (byte 10 is a continuation byte of emoji 3).
        assertEquals(emoji.repeat(2), DiagRedactor.truncateUtf8(value, 10))
        // Cap on an exact boundary keeps whole chars.
        assertEquals(emoji.repeat(3), DiagRedactor.truncateUtf8(value, 12))
        // Cap below one char yields empty, not a torn sequence.
        assertEquals("", DiagRedactor.truncateUtf8(value, 3))
        // No-op when already within budget.
        assertEquals(value, DiagRedactor.truncateUtf8(value, 16))
        // Result must decode/encode losslessly (no replacement chars).
        val truncated = DiagRedactor.truncateUtf8(value, 10)
        assertEquals(truncated, truncated.encodeToByteArray().decodeToString())
        assertFalse(truncated.contains('�'))
    }
}
