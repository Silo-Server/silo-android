package org.siloserver.silo.android.ui.navigation

import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.siloserver.silo.common.player.video.VideoPlayerRouteArgs
import org.siloserver.silo.model.section.SectionItem

/**
 * All navigation routes for the Silo app.
 *
 * Screens that take parameters use companion objects with a ROUTE constant
 * containing the placeholder (e.g. "item/{contentId}") for use with NavHost,
 * while the data-class constructor builds the resolved route for navigate().
 */
sealed class Route(val route: String) {

    // --- Auth flow (no bottom nav) ---
    data object ServerSetup : Route("server_setup")
    data object ServerList : Route("server_list")
    data object Login : Route("login")
    data object Setup : Route("setup")
    data object Signup : Route("signup")

    /**
     * Emailed-invitation claim. The deep link carries the server URL and the
     * single-use token, so the app skips its "which server?" step entirely.
     */
    data class InviteClaim(val server: String, val token: String) : Route(
        "invite_claim?server=${Uri.encode(server)}&token=${Uri.encode(token)}",
    ) {
        companion object {
            const val ROUTE = "invite_claim?server={server}&token={token}"
        }
    }

    /** Server-driven first-run feature tour, shown after profile selection. */
    data object OnboardingTour : Route("onboarding_tour")

    data class PairDevice(
        val token: String? = null,
        val code: String? = null,
        /**
         * Origin of the server that issued this pairing request, when the link
         * named one. Carried so the screen can refuse — and explain — rather
         * than looking the code up against whichever server is active.
         */
        val serverOrigin: String? = null,
    ) : Route(
        buildString {
            append("pair_device")
            val params = listOfNotNull(
                token?.takeIf { it.isNotBlank() }?.let { "token=${Uri.encode(it)}" },
                code?.takeIf { it.isNotBlank() }?.let { "code=${Uri.encode(it)}" },
                serverOrigin?.takeIf { it.isNotBlank() }
                    ?.let { "serverOrigin=${Uri.encode(it)}" },
            )
            if (params.isNotEmpty()) {
                append("?")
                append(params.joinToString("&"))
            }
        },
    ) {
        companion object {
            const val ROUTE = "pair_device?token={token}&code={code}&serverOrigin={serverOrigin}"
        }
    }

    // --- Profile selection (no bottom nav) ---
    data object ProfileSelection : Route("profiles")
    data object CreateProfile : Route("profiles/create")
    data class EditProfile(val profileId: String) : Route("profiles/${Uri.encode(profileId)}/edit") {
        companion object {
            const val ROUTE = "profiles/{profileId}/edit"
        }
    }

    // --- Main tabs (inside bottom nav scaffold) ---
    data object Downloads : Route("downloads")
    data class Search(
        val mediaType: String? = null,
    ) : Route(
        mediaType
            ?.takeIf { it.isNotBlank() }
            ?.let { "search?mediaType=${Uri.encode(it)}" }
            ?: "search",
    ) {
        companion object {
            const val ROUTE = "search?mediaType={mediaType}"
        }
    }
    data object Settings : Route("settings")
    data object Diagnostics : Route("settings/diagnostics")
    data class DiagnosticsReport(val reportId: String) :
        Route("settings/diagnostics/report/${Uri.encode(reportId)}") {
        companion object {
            const val ROUTE = "settings/diagnostics/report/{reportId}"
        }
    }

    // Canonical tab routes. Home is the USUAL start destination, but not
    // always: an offline launch with downloads starts on Downloads instead, so
    // the bottom-nav popUpTo anchor is read from the live back stack
    // ([bottomMostTabRoute]) rather than assumed to be Home. Libraries and
    // Recommendations back the other media tabs.
    data object Home : Route("home")
    data object Libraries : Route("libraries")
    data object Recommendations : Route("recommendations")
    data object Inbox : Route("inbox")

    // --- Requests ---
    data object Requests : Route("requests")
    data object MyRequests : Route("requests/mine")
    data class RequestDetail(
        val mediaType: String,
        val tmdbId: Int,
    ) : Route("requests/detail/${Uri.encode(mediaType)}/$tmdbId") {
        companion object {
            const val ROUTE = "requests/detail/{mediaType}/{tmdbId}"
        }
    }

    // --- Detail screens (back navigation, no bottom nav) ---
    data class ItemDetail(
        val contentId: String,
        val seasonNumber: Int? = null,
        val episodeContentId: String? = null,
    ) : Route(
        "item/${contentId.routeEncode()}" +
            listOfNotNull(
                seasonNumber?.let { "seasonNumber=$it" },
                episodeContentId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "episodeContentId=${it.routeEncode()}" },
            ).let { params -> if (params.isEmpty()) "" else "?" + params.joinToString("&") },
    ) {
        companion object {
            const val ROUTE =
                "item/{contentId}?seasonNumber={seasonNumber}&episodeContentId={episodeContentId}"
        }
    }

    data class PersonDetail(val personId: Long) : Route("person/$personId") {
        companion object {
            const val ROUTE = "person/{personId}"
        }
    }

    // --- Catalog / Browse ---
    data class Browse(val libraryId: Int? = null) : Route(
        if (libraryId != null) "browse?libraryId=$libraryId" else "browse"
    ) {
        companion object {
            const val ROUTE = "browse?libraryId={libraryId}"
        }
    }

    data class CollectionDetail(
        val collectionId: String,
        val libraryId: Int? = null,
    ) : Route(
        if (libraryId != null) {
            "collection/${collectionId.routeEncode()}?libraryId=$libraryId"
        } else {
            "collection/${collectionId.routeEncode()}"
        }
    ) {
        companion object {
            const val ROUTE = "collection/{collectionId}?libraryId={libraryId}"
        }
    }

    // --- Player / casting (fullscreen or hidden shell routes, no menu entries) ---
    data object SiloCastRemote : Route("silocast/remote")

    data class Player(
        val contentId: String,
        val fileId: Int? = null,
        val quality: String? = null,
        val audioTrackIndex: Int? = null,
        val subtitleTrackIndex: Int? = null,
        val resumePositionSeconds: Double? = null,
        val roomId: String? = null,
    ) : Route(
        buildString {
            append("player/${contentId.routeEncode()}")
            val queryParams = listOfNotNull(
                fileId?.let { "fileId=$it" },
                // normalizeQuality is a closed wire-value set, so no URI
                // escaping (or Android framework dependency) is needed here.
                VideoPlayerRouteArgs.normalizeQuality(quality)?.let { "quality=$it" },
                audioTrackIndex?.let { "audioTrackIndex=$it" },
                subtitleTrackIndex?.let { "subtitleTrackIndex=$it" },
                VideoPlayerRouteArgs.encodeResumePosition(resumePositionSeconds)
                    ?.let { "${VideoPlayerRouteArgs.RESUME_POSITION}=$it" },
                roomId?.takeIf { it.isNotBlank() }?.let { "roomId=${Uri.encode(it)}" },
            )
            if (queryParams.isNotEmpty()) {
                append("?")
                append(queryParams.joinToString("&"))
            }
        },
    ) {
        companion object {
            const val ROUTE =
                "player/{contentId}?fileId={fileId}&quality={quality}&audioTrackIndex={audioTrackIndex}&subtitleTrackIndex={subtitleTrackIndex}&resumePosition={resumePosition}&roomId={roomId}"
        }
    }

    // --- Watch Together (synchronized playback rooms) ---
    data class WatchTogetherLobby(val roomId: String) : Route("watch_together/${Uri.encode(roomId)}") {
        companion object {
            const val ROUTE = "watch_together/{roomId}"
            const val ARG_ROOM_ID = "roomId"
        }
    }

    // --- Audiobook player (fullscreen, audio-only UI) ---
    data class AudiobookPlayer(
        val contentId: String,
        val fileId: Int? = null,
        val fromStart: Boolean = false,
        // Whole-book (global) start offset for Parts / Chapters. The player VM
        // resolves which part contains it; null resumes from the stored position.
        val startPosition: Double? = null,
    ) : Route(
        "audiobook/${contentId.routeEncode()}" +
            listOfNotNull(
                fileId?.let { "fileId=$it" },
                if (fromStart) "fromStart=true" else null,
                startPosition?.takeIf { it.isFinite() && it >= 0.0 }?.let { "startPosition=$it" },
            ).let { params -> if (params.isEmpty()) "" else "?" + params.joinToString("&") },
    ) {
        companion object {
            const val ROUTE =
                "audiobook/{contentId}?fileId={fileId}&fromStart={fromStart}&startPosition={startPosition}"
            const val ARG_CONTENT_ID = "contentId"
            const val ARG_FILE_ID = "fileId"
            const val ARG_FROM_START = "fromStart"
            const val ARG_START_POSITION = "startPosition"
        }
    }

    // --- Book reader (fullscreen, dispatches by BookFormat) ---
    data class BookReader(val contentId: String, val fileId: Int? = null) : Route(
        "reader/${contentId.routeEncode()}" + fileId?.let { "?fileId=$it" }.orEmpty(),
    ) {
        companion object {
            const val ROUTE = "reader/{contentId}?fileId={fileId}"
            const val ARG_CONTENT_ID = "contentId"
            const val ARG_FILE_ID = "fileId"
        }
    }

    // --- Calendar / upcoming ---
    data object Calendar : Route("calendar")

    // --- Personal data ---
    data object Favorites : Route("favorites")
    data object Watchlist : Route("watchlist")
    data object History : Route("history")
    data object PersonalLists : Route("personal_lists")
    data class Collections(val libraryId: Int? = null) : Route(
        if (libraryId != null) "collections?libraryId=$libraryId" else "collections"
    ) {
        companion object {
            const val ROUTE = "collections?libraryId={libraryId}"
        }
    }

}

/**
 * Continue Watching episodes belong to the unified series detail surface.
 * Preserve the episode as route state so opening (for example) S3E1 loads the
 * parent series, selects Season 3, and focuses that exact episode in its rail.
 */
internal fun continueWatchingDetailRoute(item: SectionItem): String {
    val seriesId = item.seriesId?.takeIf { it.isNotBlank() }
    return if (item.type.equals("episode", ignoreCase = true) && seriesId != null) {
        Route.ItemDetail(
            contentId = seriesId,
            seasonNumber = item.seasonNumber,
            episodeContentId = item.contentId,
        ).route
    } else {
        Route.ItemDetail(item.contentId).route
    }
}

/**
 * Percent-encode a value for use as a route path segment.
 *
 * Deliberately `java.net.URLEncoder` rather than `android.net.Uri.encode`:
 * routes are built in plain JVM unit tests, where `android.net.Uri` is stubbed
 * and silently returns null — a route would become "item/null" and the test
 * would assert against nonsense. Mirrors the TV app's `routeEncode`.
 *
 * `URLEncoder` is form encoding, where a space becomes `+`; a path segment
 * needs `%20`, hence the fixup.
 */
private fun String.routeEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.toString()).replace("+", "%20")
