package org.siloserver.silo.common.diagnostics

import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Removes credentials and network identity before a value enters diagnostics storage.
 * This is intentionally independent of Koin so it is usable during early startup and crashes.
 */
class DiagnosticsRedactor(
    knownServerHosts: Set<String> = emptySet(),
    sensitiveValues: Set<String> = emptySet(),
) {
    private val knownServerHosts = knownServerHosts
        .mapTo(linkedSetOf()) { it.trim().trim('[', ']').lowercase(Locale.ROOT) }
        .filterTo(linkedSetOf()) { it.isNotEmpty() }
    private val sensitiveValues = sensitiveValues
        .filter { it.isNotEmpty() }
        .sortedByDescending(String::length)

    fun sanitize(value: String): String {
        var output = URL_PATTERN.replace(value) { match ->
            sanitizeMatchedUrl(match.value)
        }
        output = AUTHORIZATION_PATTERN.replace(output) { match ->
            "${match.groupValues[1]}: [REDACTED]"
        }
        output = COOKIE_PATTERN.replace(output) { match ->
            "${match.groupValues[1]}: [REDACTED]"
        }
        output = JWT_CANDIDATE_PATTERN.replace(output) { match ->
            if (match.isStructurallyValidJwt()) "[REDACTED_JWT]" else match.value
        }
        output = EMAIL_PATTERN.replace(output, "[REDACTED_EMAIL]")
        output = NAMED_SECRET_PATTERN.replace(output) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        sensitiveValues.forEach { secret -> output = output.replace(secret, "[REDACTED]") }
        knownServerHosts.forEach { host ->
            output = output.replace(host, stableHostToken(host), ignoreCase = true)
        }
        return output
    }

    fun sanitizeUrl(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: return sanitize(value)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return sanitize(value)
        val rawHost = uri.host ?: return sanitize(value)
        val host = rawHost.trim('[', ']').lowercase(Locale.ROOT)
        val safeHost = when {
            isLoopbackHost(host) -> host
            host in knownServerHosts -> stableHostToken(host)
            else -> stableHostToken(host)
        }
        return buildString {
            append(scheme)
            append("://")
            if (':' in safeHost && !safeHost.startsWith('[')) {
                append('[')
                append(safeHost)
                append(']')
            } else {
                append(safeHost)
            }
            if (uri.port >= 0) {
                append(':')
                append(uri.port)
            }
            append(sanitizePath(uri.rawPath.orEmpty()))
        }
    }

    fun sanitizeThrowable(
        throwable: Throwable,
        maxDepth: Int = DEFAULT_THROWABLE_DEPTH,
        maxUtf8Bytes: Int = DEFAULT_THROWABLE_BYTES,
    ): String {
        if (maxDepth <= 0 || maxUtf8Bytes <= 0) return ""
        val seen = HashSet<Throwable>()
        val result = buildString {
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < maxDepth && seen.add(current)) {
                if (isNotEmpty()) append('\n')
                if (depth > 0) append("caused by ")
                append(current.javaClass.name)
                current.message?.takeIf(String::isNotBlank)?.let { message ->
                    append(": ")
                    append(sanitize(message))
                }
                current = current.cause
                depth += 1
            }
        }
        return result.truncateUtf8(maxUtf8Bytes)
    }

    private fun sanitizeMatchedUrl(candidate: String): String {
        val trailing = candidate.takeLastWhile { it in TRAILING_URL_PUNCTUATION }
        val url = candidate.dropLast(trailing.length)
        return sanitizeUrl(url) + trailing
    }

    private fun stableHostToken(host: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(host.lowercase(Locale.ROOT).encodeToByteArray())
        return buildString(HOST_TOKEN_HEX_BYTES * 2 + HOST_TOKEN_PREFIX.length) {
            append(HOST_TOKEN_PREFIX)
            repeat(HOST_TOKEN_HEX_BYTES) { index -> append("%02x".format(Locale.ROOT, digest[index])) }
        }
    }

    private fun isLoopbackHost(host: String): Boolean =
        host == "localhost" || host == "::1" || IPV4_LOOPBACK_PATTERN.matches(host)

    private fun sanitizePath(path: String): String = path
        .split('/')
        .joinToString("/") { segment ->
            if (segment.isPrivateIdentifierPathSegment()) "{id}" else segment
        }

    private fun String.isPrivateIdentifierPathSegment(): Boolean =
        UUID_PATH_SEGMENT.matches(this) ||
            NUMERIC_ID_PATH_SEGMENT.matches(this) ||
            HEX_ID_PATH_SEGMENT.matches(this) ||
            OPAQUE_ID_PATH_SEGMENT.matches(this)

    private fun MatchResult.isStructurallyValidJwt(): Boolean =
        groupValues[1].decodesToJsonObject() && groupValues[2].decodesToJsonObject()

    private fun String.decodesToJsonObject(): Boolean {
        if (length > MAX_JWT_SEGMENT_CHARS || length % 4 == 1) return false
        val padded = this + "=".repeat((4 - length % 4) % 4)
        val decoded = runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull() ?: return false
        if (decoded.isEmpty() || decoded.size > MAX_JWT_JSON_BYTES) return false
        return runCatching { JWT_JSON.parseToJsonElement(decoded.decodeToString()) is JsonObject }.getOrDefault(false)
    }

    private fun String.truncateUtf8(maxBytes: Int): String {
        if (encodeToByteArray().size <= maxBytes) return this
        val result = StringBuilder(length.coerceAtMost(maxBytes))
        var index = 0
        var usedBytes = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            val value = String(Character.toChars(codePoint))
            val valueBytes = value.encodeToByteArray().size
            if (usedBytes + valueBytes > maxBytes) break
            result.append(value)
            usedBytes += valueBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private companion object {
        const val DEFAULT_THROWABLE_DEPTH = 6
        const val DEFAULT_THROWABLE_BYTES = 4 * 1_024
        const val HOST_TOKEN_PREFIX = "host_"
        const val HOST_TOKEN_HEX_BYTES = 8
        const val MAX_JWT_SEGMENT_CHARS = 8 * 1_024
        const val MAX_JWT_JSON_BYTES = 6 * 1_024

        val JWT_JSON = Json { isLenient = false }
        val TRAILING_URL_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
        val URL_PATTERN = Regex("(?i)\\b(?:https?|wss?)://[^\\s<>\\\"']+")
        val UUID_PATH_SEGMENT = Regex(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        val NUMERIC_ID_PATH_SEGMENT = Regex("^[0-9]+$")
        val HEX_ID_PATH_SEGMENT = Regex("(?i)^[0-9a-f]{16,}$")
        val OPAQUE_ID_PATH_SEGMENT = Regex("^[A-Za-z0-9_-]{20,}$")
        val AUTHORIZATION_PATTERN = Regex(
            "(?i)\\b(authorization|proxy-authorization)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+",
        )
        val COOKIE_PATTERN = Regex("(?i)\\b(cookie|set-cookie)\\s*:\\s*[^\\r\\n]+")
        val JWT_CANDIDATE_PATTERN = Regex(
            "(?<![A-Za-z0-9_-])([A-Za-z0-9_-]+)\\.([A-Za-z0-9_-]+)\\.([A-Za-z0-9_-]+)(?![A-Za-z0-9_-])",
        )
        val EMAIL_PATTERN = Regex("(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9.-])")
        val NAMED_SECRET_PATTERN = Regex(
            "(?i)\\b(access_token|refresh_token|profile_token|token|api_key)\\s*[:=]\\s*[^\\s,;&]+",
        )
        val IPV4_LOOPBACK_PATTERN = Regex("127(?:\\.\\d{1,3}){3}")
    }
}
