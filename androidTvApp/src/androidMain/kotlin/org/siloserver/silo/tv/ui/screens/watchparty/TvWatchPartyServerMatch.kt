package org.siloserver.silo.tv.ui.screens.watchparty

/**
 * Whether an invitation link's server is the server this app is signed in
 * to: the same scheme, host (ignoring case), port (an omitted port is the
 * scheme's default), and base path (ignoring a trailing slash). A LAN URL and
 * a public URL for the same server are different servers here, so the join
 * token is never sent to a deployment the user did not sign in to.
 */
internal fun tvWatchPartyServerMatches(linkServerUrl: String, activeServerUrl: String?): Boolean {
    val link = tvServerOrigin(linkServerUrl) ?: return false
    val active = tvServerOrigin(activeServerUrl ?: return false) ?: return false
    return link == active
}

private data class TvServerOrigin(val scheme: String, val host: String, val port: Int, val path: String)

private fun tvServerOrigin(url: String): TvServerOrigin? {
    val text = url.trim()
    val schemeEnd = text.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = text.substring(0, schemeEnd).lowercase()
    val defaultPort = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> return null
    }
    val rest = text.substring(schemeEnd + 3).substringBefore('#').substringBefore('?')
    val authority = rest.substringBefore('/')
    if (authority.isEmpty() || '@' in authority) return null
    val host: String
    val portText: String?
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0) return null
        host = authority.substring(0, close + 1)
        val afterHost = authority.substring(close + 1)
        portText = when {
            afterHost.isEmpty() -> ""
            afterHost.startsWith(":") -> afterHost.substring(1)
            else -> return null
        }
    } else {
        host = authority.substringBefore(':')
        portText = if (':' in authority) authority.substringAfter(':') else ""
    }
    if (host.isEmpty()) return null
    val port = if (portText.isEmpty()) defaultPort else portText.toIntOrNull() ?: return null
    val path = rest.substring(authority.length).trimEnd('/')
    return TvServerOrigin(scheme, host.lowercase(), port, path)
}
