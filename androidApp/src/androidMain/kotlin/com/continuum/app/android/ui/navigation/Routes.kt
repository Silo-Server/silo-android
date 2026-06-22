package com.continuum.app.android.ui.navigation

import android.net.Uri
import com.continuum.app.common.player.video.VideoPlayerRouteArgs

/**
 * All navigation routes for the Continuum app.
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

    data class PairDevice(
        val token: String? = null,
        val code: String? = null,
    ) : Route(
        buildString {
            append("pair_device")
            val params = listOfNotNull(
                token?.takeIf { it.isNotBlank() }?.let { "token=${Uri.encode(it)}" },
                code?.takeIf { it.isNotBlank() }?.let { "code=${Uri.encode(it)}" },
            )
            if (params.isNotEmpty()) {
                append("?")
                append(params.joinToString("&"))
            }
        },
    ) {
        companion object {
            const val ROUTE = "pair_device?token={token}&code={code}"
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
    data object CardOverlays : Route("settings/card_overlays")

    // Canonical tab routes — Home is the start destination and the bottom-nav /
    // popUpTo anchor; Libraries and Recommendations back the other media tabs.
    data object Home : Route("home")
    data object Libraries : Route("libraries")
    data object Recommendations : Route("recommendations")

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
    ) : Route(
        if (seasonNumber != null) "item/$contentId?seasonNumber=$seasonNumber" else "item/$contentId"
    ) {
        companion object {
            const val ROUTE = "item/{contentId}?seasonNumber={seasonNumber}"
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
        if (libraryId != null) "collection/$collectionId?libraryId=$libraryId" else "collection/$collectionId"
    ) {
        companion object {
            const val ROUTE = "collection/{collectionId}?libraryId={libraryId}"
        }
    }

    // --- Player (fullscreen, no system bars) ---
    data class Player(
        val contentId: String,
        val fileId: Int? = null,
        val audioTrackIndex: Int? = null,
        val subtitleTrackIndex: Int? = null,
        val resumePositionSeconds: Double? = null,
        val roomId: String? = null,
    ) : Route(
        buildString {
            append("player/$contentId")
            val queryParams = listOfNotNull(
                fileId?.let { "fileId=$it" },
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
                "player/{contentId}?fileId={fileId}&audioTrackIndex={audioTrackIndex}&subtitleTrackIndex={subtitleTrackIndex}&resumePosition={resumePosition}&roomId={roomId}"
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
    ) : Route(
        "audiobook/$contentId" +
            listOfNotNull(
                fileId?.let { "fileId=$it" },
                if (fromStart) "fromStart=true" else null,
            ).let { params -> if (params.isEmpty()) "" else "?" + params.joinToString("&") },
    ) {
        companion object {
            const val ROUTE = "audiobook/{contentId}?fileId={fileId}&fromStart={fromStart}"
            const val ARG_CONTENT_ID = "contentId"
            const val ARG_FILE_ID = "fileId"
            const val ARG_FROM_START = "fromStart"
        }
    }

    // --- Book reader (fullscreen, dispatches by BookFormat) ---
    data class BookReader(val contentId: String, val fileId: Int? = null) : Route(
        "reader/$contentId" + fileId?.let { "?fileId=$it" }.orEmpty(),
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
