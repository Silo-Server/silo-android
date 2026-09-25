package org.siloserver.silo.android.ui.navigation

import org.siloserver.silo.cast.SiloCastPlaybackRequest
import org.siloserver.silo.common.player.video.VideoPlayerRouteArgs
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Maps content deep links onto in-app routes — the Android analogue of iOS's
 * `handleDeepLink` (`continuum://` there; this app's scheme is `silo://`).
 * Routing is by URL host with the content id as the first path segment:
 *
 * - `silo://downloads`          → the Downloads tab
 * - `silo://item/<contentId>`   → item detail
 * - `silo://play/<contentId>`   → the player (immediate playback)
 *
 * Auth gating is inherited from the pendingExternalRoute mechanism: a link
 * opened before sign-in stays queued until the authenticated graph shows.
 */
internal fun contentDeepLinkRouteOrNull(rawUri: String?): String? {
    val uri = rawUri
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { URI(it) }.getOrNull() }
        ?: return null
    if (!uri.scheme.equals("silo", ignoreCase = true)) return null

    // Read the RAW path and decode exactly one segment. `URI.path` is already
    // percent-decoded, so taking it and interpolating the result back into a
    // route re-parsed the decoded bytes as route syntax: an id containing an
    // encoded `?` or `/` could truncate the id or inject an argument. The
    // route constructors below re-encode, so this must hand them the decoded
    // id exactly once.
    val contentId = uri.rawPath.orEmpty()
        .trim('/')
        .substringBefore('/')
        .let(::decodePathSegment)
        .orEmpty()
        .trim()

    return when (uri.host?.lowercase()) {
        "downloads" -> Route.Downloads.route
        "item" -> contentId.takeIf { it.isNotBlank() }?.let { Route.ItemDetail(it).route }
        "play" -> contentId.takeIf { it.isNotBlank() }?.let {
            Route.Player(
                contentId = it,
                fileId = uri.queryParameter("fileId")?.toIntOrNull()?.takeIf { id -> id > 0 },
                quality = VideoPlayerRouteArgs.normalizeQuality(uri.queryParameter(VideoPlayerRouteArgs.QUALITY)),
                audioTrackIndex = uri.queryParameter("audioTrackIndex")?.toIntOrNull()?.takeIf { index -> index >= 0 },
                subtitleTrackIndex = uri.queryParameter("subtitleTrackIndex")?.toIntOrNull()?.takeIf { index -> index >= -1 },
            ).route
        }
        else -> null
    }
}

/**
 * The Remote Control request a video player route stands for, so an engaged
 * TV can take a `silo://play` link the way iOS routes one, instead of the
 * player opening on the phone. Null for any other route, and for a Watch
 * Together room's player, which always stays on the phone.
 */
internal fun playerRouteCastRequestOrNull(route: String): SiloCastPlaybackRequest? {
    if (!route.startsWith(PLAYER_ROUTE_PREFIX)) return null
    val contentId = route.removePrefix(PLAYER_ROUTE_PREFIX)
        .substringBefore('?')
        .let(::decodePathSegment)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val query = route.substringAfter('?', "")
        .split('&')
        .filter { it.isNotEmpty() }
        .associate { part -> part.substringBefore('=') to decodeQueryComponent(part.substringAfter('=', "")) }
    if (!query["roomId"].isNullOrBlank()) return null
    // As on the phone: no position resumes where the profile left off, and an
    // explicit 0 is "start over".
    val resume = VideoPlayerRouteArgs.parseResumePosition(query[VideoPlayerRouteArgs.RESUME_POSITION])
    return SiloCastPlaybackRequest(
        contentId = contentId,
        fileId = query["fileId"]?.toIntOrNull(),
        audioTrackIndex = query["audioTrackIndex"]?.toIntOrNull(),
        subtitleTrackIndex = query["subtitleTrackIndex"]?.toIntOrNull(),
        startFromBeginning = resume == 0.0,
        resumePosition = resume?.takeIf { it > 0.0 },
        libraryId = query["libraryId"]?.toIntOrNull(),
    )
}

private const val PLAYER_ROUTE_PREFIX = "player/"

private fun URI.queryParameter(name: String): String? = rawQuery
    ?.split('&')
    ?.asSequence()
    ?.map { part -> part.substringBefore('=') to part.substringAfter('=', "") }
    ?.firstOrNull { (key, _) -> decodeQueryComponent(key) == name }
    ?.second
    ?.let(::decodeQueryComponent)

private fun decodeQueryComponent(value: String): String? = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrNull()

/**
 * Percent-decoding for a PATH segment. Deliberately not [decodeQueryComponent]:
 * `URLDecoder` implements form encoding, where `+` means space — but in a path
 * a `+` is a literal plus, so an id containing one would be corrupted.
 */
private fun decodePathSegment(value: String): String? = runCatching {
    // Escape `+` first: URLDecoder implements form encoding where `+` means
    // space, but in a path a `+` is a literal plus. android.net.Uri.decode
    // would do this correctly, but it is stubbed in plain JVM unit tests.
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
}.getOrNull()
