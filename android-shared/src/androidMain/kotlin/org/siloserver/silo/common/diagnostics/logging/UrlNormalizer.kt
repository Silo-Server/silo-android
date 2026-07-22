package org.siloserver.silo.common.diagnostics.logging

import org.siloserver.silo.common.diagnostics.sha256Hex
import java.net.URI

/**
 * Collection-time URL normalization per the safe-logging contract: userinfo,
 * query, and fragment are always stripped; the path is preserved; non-loopback
 * hosts are replaced with a stable `[host:xxxxxxxxxxxx]` token (first 12 hex
 * chars of SHA-256 of the lowercased host) so lines stay correlatable within a
 * bundle without exposing the server's domain.
 */
internal object UrlNormalizer {

    fun normalize(raw: String): String {
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: return "[url]" // unparseable URL-looking text: drop it entirely
        val scheme = uri.scheme ?: return "[url]"
        val host = uri.host ?: return "[url]"
        val path = uri.rawPath.orEmpty()
        val safeHost = if (isLoopback(host)) host else hostToken(host)
        return "$scheme://$safeHost$path"
    }

    fun hostToken(host: String): String = "[host:${sha256Hex(host.lowercase()).take(12)}]"

    private fun isLoopback(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "localhost" || lower == "127.0.0.1" || lower == "::1" || lower == "[::1]"
    }
}
