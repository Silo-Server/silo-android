package org.siloserver.silo.android.ui.navigation

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Maps scanned/opened device-login URLs into the in-app "Sign in a TV" route.
 *
 * TV sign-in links arrive as:
 *  - the web approval page's app link,
 *    `silo://device?server=<server_id>&url=<base>&code=<code>`: the server is
 *    named by its deployment identity, matched against saved servers by
 *    verified identity rather than URL spelling. Without `server` the link
 *    is scoped by `url`'s origin, like an HTTPS link;
 *  - older app links, `silo://device?token=|code=`, which name no server;
 *  - server HTTPS links (`/activate`, `/device`, `/auth/device`), named by
 *    their origin. Android App Links for arbitrary self-hosted domains are
 *    best-effort, but whenever the OS delivers one this keeps routing identical.
 */
internal fun deviceLoginPairRouteOrNull(rawUri: String?): String? {
    val uri = rawUri
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { URI(it) }.getOrNull() }
        ?: return null

    if (!uri.isDeviceLoginUri()) return null

    // A device-SHAPED http(s) link whose origin cannot be read is not a usable
    // pairing request. Letting it through produced a route with no
    // `serverOrigin`, which downstream reads as "names no server" and pairs
    // against whichever server is active — exactly what the origin check
    // exists to stop.
    val scope = deviceLoginScope(rawUri)
    if (scope == DeviceLoginScope.Invalid) return null

    val params = uri.queryParameters()
    val token = params["token"]?.takeIf { it.isNotBlank() }
    val code = params["code"]?.takeIf { it.isNotBlank() }
    if (token == null && code == null) return null

    // The app link names its server by identity, plus the base URL to add it
    // from when this phone doesn't have it yet. A `url` that isn't a readable
    // http(s) origin is dropped rather than trusted.
    val unscoped = scope as? DeviceLoginScope.Unscoped
    val serverId = unscoped?.let { params["server"]?.trim()?.takeIf { it.isNotEmpty() } }
    val linkBase = unscoped?.let { params["url"] }
        ?.let { runCatching { URI(it.trim()) }.getOrNull() }
        ?.takeIf { it.scheme?.lowercase() in setOf("http", "https") }
    // The web page leaves `server` out when it doesn't know the identity; the
    // link is then scoped by `url`'s origin, as an http(s) link is, instead of
    // approving on whichever server is active.
    val serverOrigin = (scope as? DeviceLoginScope.Origin)?.origin
        ?: linkBase?.takeIf { serverId == null }?.normalizedOrigin()
    val serverUrl = linkBase?.takeIf { serverId != null }?.let { base ->
        // Keep a reverse-proxy path prefix: it is part of the server's address.
        base.normalizedOrigin()?.let { origin -> origin + base.path.orEmpty().trimEnd('/') }
    }

    return buildPairDeviceRoute(
        token = token,
        code = if (token == null) code else null,
        serverOrigin = serverOrigin,
        serverId = serverId,
        serverUrl = serverUrl,
    )
}

/** `scheme://host[:port]` of [url], or null when it can't be read. */
internal fun deviceLoginOriginOf(url: String?): String? =
    url?.takeIf { it.isNotBlank() }?.let { runCatching { URI(it.trim()) }.getOrNull() }?.normalizedOrigin()

/**
 * What server, if any, a device link names.
 *
 * The three cases must stay distinct. Collapsing "names no server" and "names
 * something unparseable" into one null meant a malformed link such as
 * `https:///device?code=...` — which still satisfies the device-path check but
 * has no host — was treated as unscoped and accepted against whichever server
 * was active, which is the behaviour this guard exists to remove.
 */
internal sealed interface DeviceLoginScope {
    /** An app-scheme link (`silo://device`): names no server. */
    data object Unscoped : DeviceLoginScope

    /** An http(s) link naming [origin], already normalized. */
    data class Origin(val origin: String) : DeviceLoginScope

    /** Not a device link, or an http(s) one whose origin cannot be read. */
    data object Invalid : DeviceLoginScope
}

internal fun deviceLoginScope(rawUri: String?): DeviceLoginScope {
    val uri = rawUri
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { URI(it) }.getOrNull() }
        ?: return DeviceLoginScope.Invalid
    if (!uri.isDeviceLoginUri()) return DeviceLoginScope.Invalid
    val scheme = uri.scheme?.lowercase() ?: return DeviceLoginScope.Invalid
    if (scheme != "http" && scheme != "https") return DeviceLoginScope.Unscoped
    return uri.normalizedOrigin()?.let(DeviceLoginScope::Origin) ?: DeviceLoginScope.Invalid
}

/**
 * `scheme://host[:port]`, with the scheme's default port dropped so
 * `https://silo.example` and `https://silo.example:443` compare equal.
 */
private fun URI.normalizedOrigin(): String? {
    val scheme = scheme?.lowercase() ?: return null
    val host = host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val defaultPort = if (scheme == "https") 443 else 80
    // URI reports -1 for "omitted". Anything else must be a real port: 0 is not
    // a valid origin and must not quietly compare equal to the default one.
    val port = port
    if (port != -1 && port !in 1..65535) return null
    val explicitPort = port.takeIf { it != -1 && it != defaultPort }
    return if (explicitPort != null) "$scheme://$host:$explicitPort" else "$scheme://$host"
}

/**
 * Whether [requiredOrigin] is the origin of [activeServerUrl].
 *
 * BOTH sides are normalized. Comparing a caller-supplied origin verbatim made
 * `https://h:443` a different server from `https://h`, so a valid link was
 * refused.
 */
internal fun deviceLoginOriginMatchesServer(
    requiredOrigin: String,
    activeServerUrl: String?,
): Boolean {
    val required = runCatching { URI(requiredOrigin) }.getOrNull()?.normalizedOrigin()
        ?: return false
    val active = activeServerUrl
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { URI(it) }.getOrNull() }
        ?.normalizedOrigin()
        ?: return false
    return active == required
}

private fun URI.isDeviceLoginUri(): Boolean {
    val scheme = scheme?.lowercase()
    return when (scheme) {
        "silo" -> host.equals("device", ignoreCase = true)
        "http", "https" -> normalizedPath.endsWith("/device") || normalizedPath.endsWith("/activate")
        else -> false
    }
}

private fun URI.queryParameters(): Map<String, String> =
    rawQuery
        .orEmpty()
        .split("&")
        .filter { it.isNotBlank() }
        .mapNotNull { pair ->
            val idx = pair.indexOf("=")
            if (idx < 0) return@mapNotNull null
            // Reachable from onNewIntent with URIs other apps craft; bad
            // percent-encoding must parse to null, not throw.
            runCatching {
                pair.substring(0, idx).urlDecode() to pair.substring(idx + 1).urlDecode()
            }.getOrNull()
        }
        .toMap()

private fun String.urlDecode(): String =
    URLDecoder.decode(this, Charsets.UTF_8.name())

private fun buildPairDeviceRoute(
    token: String?,
    code: String?,
    serverOrigin: String?,
    serverId: String? = null,
    serverUrl: String? = null,
): String = buildString {
    append("pair_device")
    val params = listOfNotNull(
        token?.takeIf { it.isNotBlank() }?.let { "token=${it.routeEncode()}" },
        code?.takeIf { it.isNotBlank() }?.let { "code=${it.routeEncode()}" },
        serverOrigin?.takeIf { it.isNotBlank() }?.let { "serverOrigin=${it.routeEncode()}" },
        serverId?.takeIf { it.isNotBlank() }?.let { "serverId=${it.routeEncode()}" },
        serverUrl?.takeIf { it.isNotBlank() }?.let { "serverUrl=${it.routeEncode()}" },
    )
    if (params.isNotEmpty()) {
        append("?")
        append(params.joinToString("&"))
    }
}

private fun String.routeEncode(): String =
    URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

private val URI.normalizedPath: String
    get() = path.orEmpty().trimEnd('/').lowercase()
