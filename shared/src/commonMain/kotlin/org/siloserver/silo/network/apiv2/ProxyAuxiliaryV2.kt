package org.siloserver.silo.network.apiv2

import io.ktor.http.Url
import org.siloserver.silo.network.isSameHttpOrigin

private val proxySubtitlePath = Regex("/stream/v3/([A-Za-z0-9_-]+)/subtitles/(0|[1-9][0-9]*)\\.(ass|ssa|srt|vtt|sup)")
private val proxySessionPath = Regex("/stream/v3/([A-Za-z0-9_-]+)(?:/.*)?")
private val auxiliaryQueryKeys = setOf("file_id", "embedded_stream_index", "external_subtitle_key", "downloaded_subtitle_id", "windowed", "position", "duration")

/** Classification is deliberately broader than validation, so malformed routes fail closed. */
fun isProxyAuxiliaryUrl(raw: String): Boolean = runCatching {
    val path = Url(raw).encodedPath
    path.startsWith("/stream/v3/") && path.contains("/subtitles/")
}.getOrDefault(false)

fun capturedProxyAuxiliaryHeaders(headers: Map<String, String>): Map<String, String> {
    fun one(name: String) = headers.entries.singleOrNull { it.key.equals(name, true) }?.value
    val authorization = one("Authorization")
    val profile = one("X-Profile-Id")
    require(authorization != null && authorization.startsWith("Bearer ") && authorization.removePrefix("Bearer ").isNotBlank())
    require(!profile.isNullOrBlank())
    require(!authorization.contains('\r') && !authorization.contains('\n') && !profile.contains('\r') && !profile.contains('\n'))
    return mapOf("Authorization" to authorization, "X-Profile-Id" to profile)
}

/** Validate without rebuilding the issued URL or its immutable query pins. Fonts have no native caller. */
fun validateProxySubtitleUrl(raw: String, streamUrl: String, sessionId: String) {
    require(raw.startsWith("https://") || raw.startsWith("http://"))
    val url = Url(raw)
    val stream = Url(streamUrl)
    require(url.user == null && url.password == null && url.fragment.isEmpty())
    require(isSameHttpOrigin(raw, streamUrl))
    val subtitle = requireNotNull(proxySubtitlePath.matchEntire(url.encodedPath))
    require(sessionId.matches(Regex("[A-Za-z0-9_-]+")))
    require(subtitle.groupValues[1] == sessionId)
    // A signed primary is opaque. Only a header-auth primary path carries a session to cross-check.
    if (stream.encodedPath.startsWith("/stream/v3/")) {
        val primarySession = requireNotNull(proxySessionPath.matchEntire(stream.encodedPath))
        require(primarySession.groupValues[1] == sessionId)
    }
    require(url.parameters.names().all { it in auxiliaryQueryKeys })
    require(url.parameters.getAll("file_id")?.all { it.toLongOrNull()?.let { id -> id > 0 && id.toString() == it } == true } == true)
    // Keep duplicate pins and PGS options byte-for-byte; the producer owns their authoritative rejection.
}

/** Ephemeral only: passed through native mount state, never serialized into a wire plan or journal. */
class ProxyAuxiliaryRequestHeaders(
    val streamUrl: String,
    val sessionId: String,
    val references: Set<String>,
    headers: Map<String, String>,
    val isCurrent: suspend () -> Boolean,
) : Map<String, String> by capturedProxyAuxiliaryHeaders(headers) {
    init { references.forEach { validateProxySubtitleUrl(it, streamUrl, sessionId) } }
    override fun toString(): String = "ProxyAuxiliaryRequestHeaders(<redacted>)"
}
