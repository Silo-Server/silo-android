package org.siloserver.silo.common.watchparty

import java.net.URI

/**
 * Whether an invitation's server is the server this app is signed in to:
 * the same scheme, host (ignoring case), port (an omitted default port equals
 * the explicit one), and path once a trailing slash is trimmed. A LAN address
 * and a public address for the same server do not match, so a join token is
 * never sent to a deployment the user did not sign in to. Phone and TV share
 * this so a link matches on both or on neither.
 */
fun watchPartyServerMatches(linkServerUrl: String, activeServerUrl: String?): Boolean {
    val link = comparableServer(linkServerUrl) ?: return false
    val active = activeServerUrl?.let(::comparableServer) ?: return false
    return link == active
}

private data class ComparableServer(val scheme: String, val host: String, val port: Int, val path: String)

private fun comparableServer(url: String): ComparableServer? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (uri.isOpaque) return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val port = when {
        uri.port != -1 -> uri.port
        scheme == "https" -> 443
        else -> 80
    }
    return ComparableServer(scheme, host, port, uri.rawPath.orEmpty().trimEnd('/'))
}
