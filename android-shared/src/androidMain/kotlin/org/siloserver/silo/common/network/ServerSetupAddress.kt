package org.siloserver.silo.common.network

import org.siloserver.silo.network.AndroidServerRegistry
import java.net.URI

/*
 * The typed server address on the phone and TV setup screens, with the
 * protocol and port from Advanced options, as silo-apple's
 * `ServerSetupViewModel` builds it.
 */

enum class ServerSetupScheme(val label: String, val urlScheme: String?) {
    Auto("Auto", null),
    Https("HTTPS", "https"),
    Http("HTTP", "http"),
}

class ServerSetupValidationException(message: String) : IllegalArgumentException(message)

/**
 * True when the address WILL connect over plaintext HTTP: an explicit `http`
 * scheme selection, or an `http://` prefix in the input. Auto mode is not
 * flagged pre-connect — it tries HTTPS first and only falls back to HTTP.
 * Informational only (does not block connecting to LAN/IP servers).
 */
fun serverSetupUsesCleartext(
    rawInput: String,
    selectedScheme: ServerSetupScheme,
    port: String,
): Boolean {
    if (rawInput.isBlank()) return false
    if (selectedScheme == ServerSetupScheme.Http) return true
    if (selectedScheme == ServerSetupScheme.Https) return false
    return runCatching {
        buildServerSetupCandidateUrls(rawInput, selectedScheme, port)
    }.getOrNull()?.let { candidates ->
        // Auto with an explicit http:// input resolves to a single http
        // candidate; a bare host yields https first, so no pre-warning.
        candidates.size == 1 && candidates.first().startsWith("http://")
    } ?: false
}

fun buildServerSetupCandidateUrls(
    rawInput: String,
    selectedScheme: ServerSetupScheme,
    port: String,
): List<String> {
    val input = rawInput.trim()
    if (input.isBlank()) {
        throw ServerSetupValidationException("Please enter a server host.")
    }

    val parsed = parseServerSetupInput(input)
    val explicitPort = parsed.port ?: parsePort(port)
    val schemes = when {
        selectedScheme.urlScheme != null -> listOf(selectedScheme.urlScheme)
        parsed.scheme != null -> listOf(parsed.scheme)
        else -> listOf("https", "http")
    }

    val candidates = schemes.map { scheme ->
        buildServerSetupUrl(
            scheme = scheme,
            host = parsed.host,
            port = explicitPort,
            path = parsed.path,
        )
    }.toMutableList()

    if (selectedScheme == ServerSetupScheme.Auto && parsed.scheme == null && explicitPort == null) {
        candidates += buildServerSetupUrl(
            scheme = "http",
            host = parsed.host,
            port = DefaultSiloServerPort,
            path = parsed.path,
        )
    }

    return candidates.distinct()
}

private const val DefaultSiloServerPort = 8090

private data class ParsedServerSetupInput(
    val scheme: String?,
    val host: String,
    val port: Int?,
    val path: String,
)

private fun parseServerSetupInput(input: String): ParsedServerSetupInput {
    val hasScheme = input.contains("://")
    val parseTarget = if (hasScheme) input else "https://$input"
    val uri = runCatching { URI(parseTarget) }.getOrElse {
        throw ServerSetupValidationException("Enter a valid server address.")
    }
    val host = uri.host?.takeIf { it.isNotBlank() }
        ?: throw ServerSetupValidationException("Enter a valid server address.")
    val parsedPort = uri.port.takeIf { it > 0 }?.also { validatePort(it) }
    val rawPath = uri.rawPath.orEmpty().trimEnd('/')
    val path = rawPath.takeUnless { it.isBlank() || it == "/" }.orEmpty()
    return ParsedServerSetupInput(
        scheme = if (hasScheme) uri.scheme?.lowercase() else null,
        host = host,
        port = parsedPort,
        path = path,
    )
}

private fun parsePort(port: String): Int? {
    val trimmed = port.trim()
    if (trimmed.isBlank()) return null
    val parsed = trimmed.toIntOrNull()
        ?: throw ServerSetupValidationException("Port must be a number between 1 and 65535.")
    validatePort(parsed)
    return parsed
}

private fun validatePort(port: Int) {
    if (port !in 1..65535) {
        throw ServerSetupValidationException("Port must be a number between 1 and 65535.")
    }
}

private fun buildServerSetupUrl(
    scheme: String,
    host: String,
    port: Int?,
    path: String,
): String {
    val authority = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
    val portSuffix = port?.let { ":$it" }.orEmpty()
    return AndroidServerRegistry.normalizeUrl("$scheme://$authority$portSuffix$path")
}
