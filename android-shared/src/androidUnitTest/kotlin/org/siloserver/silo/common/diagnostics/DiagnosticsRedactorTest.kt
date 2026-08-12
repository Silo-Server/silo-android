package org.siloserver.silo.common.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiagnosticsRedactorTest {
    private val redactor = DiagnosticsRedactor(
        knownServerHosts = setOf("secret.example", "media.internal"),
        sensitiveValues = setOf("registered-secret"),
    )

    @Test
    fun redactsCredentialsHostsQueriesAndFragments() {
        val input = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature " +
            "user@example.com https://alice:password@secret.example/a?token=raw#private " +
            "Cookie: sid=cookie-secret; profile_token=profile-secret registered-secret"

        val output = redactor.sanitize(input)

        listOf(
            "eyJ",
            "user@example.com",
            "alice",
            "password",
            "token=raw",
            "private",
            "secret.example",
            "cookie-secret",
            "profile-secret",
            "registered-secret",
        ).forEach { leaked -> assertFalse(output.contains(leaked), "leaked $leaked in $output") }
        assertTrue(output.contains("host_"), output)
    }

    @Test
    fun hostTokensAreStableAndDoNotRevealTheHost() {
        val first = redactor.sanitize("https://media.internal/library?id=1")
        val second = redactor.sanitize("https://media.internal/other?id=2")
        val different = redactor.sanitize("https://secret.example/library")

        val token = Regex("host_[0-9a-f]{16}").find(first)?.value
        assertTrue(token != null, first)
        assertTrue(second.contains(token), second)
        assertFalse(different.contains(token), different)
        assertNotEquals("media.internal", token)
    }

    @Test
    fun loopbackHostsRemainUsefulWhileUrlSecretsAreRemoved() {
        assertEquals(
            "http://127.0.0.1:8080/api/v1/items",
            redactor.sanitizeUrl("http://user:pass@127.0.0.1:8080/api/v1/items?q=secret#fragment"),
        )
        assertEquals(
            "http://localhost:8096/health",
            redactor.sanitizeUrl("http://localhost:8096/health?token=secret"),
        )
        assertEquals(
            "http://[::1]:9000/status",
            redactor.sanitizeUrl("http://[::1]:9000/status#secret"),
        )
    }

    @Test
    fun websocketUrlsAndPrivatePathIdsUseTheSameRedactionBoundary() {
        val output = redactor.sanitize(
            "wss://secret.example/items/42?access_token=secret " +
                "ws://media.internal/users/0123456789abcdef#private",
        )

        assertFalse(output.contains("secret.example"), output)
        assertFalse(output.contains("media.internal"), output)
        assertFalse(output.contains("access_token"), output)
        assertFalse(output.contains("/items/42"), output)
        assertFalse(output.contains("0123456789abcdef"), output)
        assertTrue(output.contains("wss://host_"), output)
        assertTrue(output.contains("/items/{id}"), output)
        assertTrue(output.contains("/users/{id}"), output)
    }

    @Test
    fun structurallyValidJwtIsRedactedWithoutRedactingDottedCodecNames() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJl"

        assertEquals("jwt [REDACTED_JWT]", redactor.sanitize("jwt $jwt"))
        assertEquals("decoder=c2.android.aac.decoder", redactor.sanitize("decoder=c2.android.aac.decoder"))
    }

    @Test
    fun throwableSanitizationIsDepthAndUtf8ByteBounded() {
        val root = IllegalStateException("root user@example.com " + "界".repeat(1_000))
        var throwable: Throwable = root
        repeat(12) { index ->
            throwable = IllegalArgumentException("level-$index registered-secret", throwable)
        }

        val output = redactor.sanitizeThrowable(throwable, maxDepth = 4, maxUtf8Bytes = 320)

        assertTrue(output.encodeToByteArray().size <= 320)
        assertFalse(output.contains("user@example.com"), output)
        assertFalse(output.contains("registered-secret"), output)
        assertTrue(output.lineSequence().count() <= 4)
        assertFalse(output.endsWith("\uFFFD"), "must not split a UTF-8 code point")
    }
}
