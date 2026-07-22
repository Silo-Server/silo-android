package org.siloserver.silo.common.diagnostics.logging

/**
 * Free-text sanitizer for diagnostics log lines and crash evidence.
 *
 * This is the collection-time layer of the two-tier redaction contract (the
 * bundle builder additionally scrubs exact current-token matches at package
 * time). Pass order matters: URLs are normalized first so the generic
 * secret-pattern passes never see (and partially mangle) query strings, then
 * header/token/JWT/email/key=value patterns, then registered sensitive
 * hostnames appearing outside URL syntax, then a UTF-8-safe byte trim.
 */
internal object DiagRedactor {

    private val URL = Regex("""(?:https?|wss?)://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
    private val AUTHORIZATION_HEADER = Regex("""(?i)\bauthorization\s*[:=]\s*[^\s,;"']+(?:\s+[^\s,;"']+)?""")
    private val COOKIE_HEADER = Regex("""(?i)\b(set-cookie|cookie)\s*[:=]\s*[^\n"']+""")
    private val BEARER = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+""")
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\b""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val SECRET_KV = Regex(
        """(?i)\b(access_token|accesstoken|refresh_token|refreshtoken|profile_token|profiletoken|""" +
            """api_key|apikey|password|passwd|secret|token|profile_id|profileid|session_id|sessionid)""" +
            """\s*[=:]\s*[^\s&,;"']+""",
    )

    // Registered server hostnames, longest-first, replaced even when they
    // appear outside URL syntax (e.g. inside a raw exception message).
    @Volatile
    private var sensitiveHosts: List<String> = emptyList()

    @Volatile
    private var sensitiveHostPattern: Regex? = null

    fun registerSensitiveHost(host: String) {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty() || normalized in sensitiveHosts) return
        synchronized(this) {
            if (normalized in sensitiveHosts) return
            val updated = (sensitiveHosts + normalized).sortedByDescending { it.length }
            sensitiveHosts = updated
            sensitiveHostPattern = Regex(
                updated.joinToString("|") { Regex.escape(it) },
                RegexOption.IGNORE_CASE,
            )
        }
    }

    fun resetForTesting() {
        synchronized(this) {
            sensitiveHosts = emptyList()
            sensitiveHostPattern = null
        }
    }

    fun sanitize(value: String, maxBytes: Int): String {
        if (value.isEmpty()) return value
        var out = value
        out = URL.replace(out) { UrlNormalizer.normalize(it.value) }
        out = AUTHORIZATION_HEADER.replace(out, "authorization: [redacted]")
        out = COOKIE_HEADER.replace(out) { "${it.groupValues[1]}: [redacted]" }
        out = BEARER.replace(out, "Bearer [redacted]")
        out = JWT.replace(out, "[jwt]")
        out = EMAIL.replace(out, "[email]")
        out = SECRET_KV.replace(out) { "${it.groupValues[1]}=[redacted]" }
        // Single forward pass: the emitted [host:…] token is never rescanned,
        // so a host that is a substring of its own replacement can't loop.
        sensitiveHostPattern?.let { pattern ->
            out = pattern.replace(out) { UrlNormalizer.hostToken(it.value) }
        }
        return truncateUtf8(out, maxBytes)
    }

    /** Trims to [maxBytes] of UTF-8 without ever splitting a multibyte sequence. */
    fun truncateUtf8(value: String, maxBytes: Int): String {
        val bytes = value.encodeToByteArray()
        if (bytes.size <= maxBytes) return value
        var cut = maxBytes
        // Walk back over UTF-8 continuation bytes (10xxxxxx).
        while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
        return bytes.decodeToString(0, cut)
    }
}
