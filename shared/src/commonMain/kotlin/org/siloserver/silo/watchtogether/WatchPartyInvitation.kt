package org.siloserver.silo.watchtogether

/** Something a user entered, pasted, or opened to join a Watch Party. */
sealed interface WatchPartyInvite {
    /** An 8-character room code for the current server. */
    data class Code(val code: String) : WatchPartyInvite

    /**
     * A join token from an invitation link. [serverUrl] is the deployment the
     * link names (scheme, host, port, and base path); the caller must match it
     * to the current server before sending the token anywhere.
     */
    data class Link(val serverUrl: String, val token: String) : WatchPartyInvite {
        override fun toString(): String = "Link(serverUrl=<redacted>, token=<redacted>)"
    }
}

/** Room codes are 8 characters from this alphabet (no 0, 1, I, or O). */
const val WATCH_PARTY_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
const val WATCH_PARTY_CODE_LENGTH = 8

/**
 * Normalizes typed input into a room code: trims, drops spaces and dashes,
 * and uppercases. Returns null unless the result is a well-formed code. The
 * server matches codes without regard to case but does not strip
 * separators, so this must run before sending.
 */
fun normalizeWatchPartyCode(input: String): String? {
    val code = input.filterNot { it.isWhitespace() || it == '-' }.uppercase()
    return code.takeIf { it.length == WATCH_PARTY_CODE_LENGTH && it.all { c -> c in WATCH_PARTY_CODE_ALPHABET } }
}

/**
 * Parses a room code, a server invitation link
 * (`https://host[:port][/base]/rooms/join?token=…`), or an app link
 * (`silo://watch-party?server=<server URL>&token=…`). The app link is
 * validated as strictly as the web link: an http or https server with no
 * credentials, query, or fragment, and exactly one non-empty `server` and
 * `token`. Account invitation links (`silo://invite`) are not party links.
 */
fun parseWatchPartyInvite(input: String): WatchPartyInvite? {
    val text = input.trim()
    if (text.isEmpty()) return null
    normalizeWatchPartyCode(text)?.let { return WatchPartyInvite.Code(it) }
    val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
    return when (scheme) {
        "http", "https" -> parseWebInvite(text)
        "silo" -> parseAppInvite(text)
        else -> null
    }
}

private fun parseWebInvite(text: String): WatchPartyInvite? {
    val withoutFragment = text.substringBefore('#')
    val base = withoutFragment.substringBefore('?')
    val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
    if (!base.endsWith(JOIN_PATH)) return null
    val server = base.removeSuffix(JOIN_PATH)
    if (!validServerUrl(server)) return null
    val token = singleParameter(query, "token") ?: return null
    if (parameterNames(query).any { it != "token" }) return null
    return WatchPartyInvite.Link(serverUrl = server, token = token)
}

private fun parseAppInvite(text: String): WatchPartyInvite? {
    if (text.contains('#')) return null
    val afterScheme = text.substringAfter("://")
    val host = afterScheme.substringBefore('?').removeSuffix("/")
    if (!host.equals(APP_LINK_HOST, ignoreCase = true)) return null
    val query = afterScheme.substringAfter('?', missingDelimiterValue = "")
    val names = parameterNames(query)
    if (names.any { it != "server" && it != "token" }) return null
    val server = singleParameter(query, "server")?.removeSuffix("/") ?: return null
    val token = singleParameter(query, "token") ?: return null
    if (!validServerUrl(server)) return null
    return WatchPartyInvite.Link(serverUrl = server, token = token)
}

private fun validServerUrl(url: String): Boolean {
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    val rest = url.substringAfter("://")
    val authority = rest.substringBefore('/')
    return authority.isNotEmpty() && '@' !in authority && '?' !in url && '#' !in url &&
        authority.none { it.isWhitespace() }
}

private fun parameterNames(query: String): List<String> =
    query.split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') }

/** The single non-empty value of [name], or null when missing, empty, or repeated. */
private fun singleParameter(query: String, name: String): String? {
    val values = query.split('&').filter { it.substringBefore('=') == name }.map { it.substringAfter('=', "") }
    if (values.size != 1) return null
    return percentDecode(values.single()).takeIf { it.isNotBlank() }
}

private fun percentDecode(value: String): String {
    if ('%' !in value && '+' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c == '%' && i + 2 <= value.lastIndex -> {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex == null) {
                    bytes += c.code.toByte()
                    i++
                } else {
                    bytes += hex.toByte()
                    i += 3
                }
            }
            c == '+' -> {
                bytes += ' '.code.toByte()
                i++
            }
            else -> {
                c.toString().encodeToByteArray().forEach { bytes += it }
                i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

private const val JOIN_PATH = "/rooms/join"
private const val APP_LINK_HOST = "watch-party"

/**
 * The web invitation link for a host's `invite_path`, resolved against the
 * verified server URL including its base path. Null when the path is not a
 * relative room invitation.
 */
fun watchPartyInviteUrl(serverUrl: String, invitePath: String?): String? {
    val path = invitePath?.takeIf { it.startsWith("$JOIN_PATH?") } ?: return null
    return serverUrl.trimEnd('/') + path
}
