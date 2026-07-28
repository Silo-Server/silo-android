package org.siloserver.silo.android.ui.navigation

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Maps an emailed-invitation link (`silo://invite?server=...&token=...`)
 * into the in-app claim route.
 *
 * Cold starts match through the composable's navDeepLink; this exists for
 * the warm path — an intent delivered to a live activity via onNewIntent
 * never reaches navDeepLink matching, and without this the tap would be
 * silently dropped.
 */
internal fun inviteClaimRouteOrNull(rawUri: String?): String? {
    val uri = rawUri
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { URI(it) }.getOrNull() }
        ?: return null

    if (!uri.scheme.equals("silo", ignoreCase = true)) return null
    if (!uri.host.equals("invite", ignoreCase = true)) return null

    // Any other app can hand the exported activity a malformed URI via
    // onNewIntent; bad percent-encoding must parse to null, not throw.
    val params = uri.rawQuery
        .orEmpty()
        .split("&")
        .filter { it.isNotBlank() }
        .mapNotNull { pair ->
            val idx = pair.indexOf("=")
            if (idx < 0) return@mapNotNull null
            runCatching {
                val key = URLDecoder.decode(pair.substring(0, idx), Charsets.UTF_8.name())
                val value = URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8.name())
                key to value
            }.getOrNull()
        }
        .toMap()

    val server = params["server"]?.takeIf { it.isNotBlank() } ?: return null
    val token = params["token"]?.takeIf { it.isNotBlank() } ?: return null

    return "invite_claim?server=${server.routeEncode()}&token=${token.routeEncode()}"
}

private fun String.routeEncode(): String =
    URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
