package org.siloserver.silo.tv.ui.navigation

import org.siloserver.silo.common.player.video.VideoPlayerRouteArgs
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * TV navigation routes.
 *
 * Mirrors the phone app's `Route` but drops touch-only screens (Downloads) and
 * reorganizes the main tabs into a single [Main] destination that hosts the
 * top-menu shell for Home/Search/Libraries/For You/Requests/Settings.
 *
 * Immersive screens (detail, player, collection detail) live outside the shell
 * so they can use the full screen.
 */
sealed class TvRoute(val route: String) {

    // --- Auth flow (no drawer) ---
    data object ServerSetup : TvRoute("server_setup")
    data object ServerList : TvRoute("server_list")

    /** First-time server setup (first-admin creation) when the server isn't set up yet. */
    data object Setup : TvRoute("server_first_setup")

    /** Invite-code signup, reachable from Login when the server allows signups. */
    data object Signup : TvRoute("signup")

    data class Login(val signupEnabled: Boolean = false) :
        TvRoute("login?signupEnabled=$signupEnabled") {
        companion object {
            const val ROUTE = "login?signupEnabled={signupEnabled}"
            const val ARG_SIGNUP_ENABLED = "signupEnabled"
        }
    }

    // --- Profile selection (no drawer) ---
    data object ProfileSelection : TvRoute("profiles")

    // --- Profile management (no drawer) ---
    data object CreateProfile : TvRoute("profiles/create")

    data class EditProfile(val profileId: String) :
        TvRoute("profiles/edit/${profileId.routeEncode()}") {
        companion object {
            const val ROUTE = "profiles/edit/{profileId}"
            const val ARG_PROFILE_ID = "profileId"
        }
    }

    // --- Main (drawer + nested nav: home, libraries, search, settings) ---
    data object Main : TvRoute("main")

    // No "diagnostics" list route: the diagnostics settings surface is a
    // category inside Settings (tvOS parity). Only the report detail is pushed.
    data class DiagnosticsReport(val reportId: String) :
        TvRoute("diagnostics/report/${reportId.routeEncode()}") {
        companion object {
            const val ROUTE = "diagnostics/report/{reportId}"
            const val ARG_REPORT_ID = "reportId"
        }
    }

    // --- Detail & player (no drawer, immersive) ---
    data class ItemDetail(
        val contentId: String,
        val seasonNumber: Int? = null,
        val episodeContentId: String? = null,
    ) : TvRoute(
        buildString {
            append("item/${contentId.routeEncode()}")
            val query = buildList {
                seasonNumber?.let { add("seasonNumber=$it") }
                episodeContentId?.let { add("episodeContentId=${it.routeEncode()}") }
            }
            if (query.isNotEmpty()) append("?${query.joinToString("&")}")
        },
    ) {
        companion object {
            const val ROUTE =
                "item/{contentId}?seasonNumber={seasonNumber}&episodeContentId={episodeContentId}"
            const val ARG_CONTENT_ID = "contentId"
            const val ARG_SEASON_NUMBER = "seasonNumber"
            const val ARG_EPISODE_CONTENT_ID = "episodeContentId"
        }
    }

    /**
     * Playback route. An optional `fileId` query param lets the detail screen
     * pre-select a specific file version (e.g. 4K vs 1080p). When absent, the
     * player uses `auto` selection. An optional `roomId` query param binds the
     * player to a Watch Together room (synced playback); absent for solo play.
     */
    data class Player(
        val contentId: String,
        val fileId: Int? = null,
        val quality: String? = null,
        val roomId: String? = null,
        val resumePositionSeconds: Double? = null,
        /** Pre-selected audio track index (0-based) chosen on the detail screen. */
        val audioTrackIndex: Int? = null,
        /**
         * True when [audioTrackIndex] is a pick the viewer made this session,
         * false when it is a durable value seeded onto the detail screen. The
         * ordinal alone cannot tell them apart, and the difference decides
         * whether the choice carries to the next episode.
         */
        val audioPickedThisSession: Boolean = false,
        /** Pre-selected subtitle track index (combined space; -1 = Off). */
        val subtitleTrackIndex: Int? = null,
        /**
         * True when [subtitleTrackIndex] is the detail row's Auto preview
         * rather than a pick the viewer made. It still decides what plays —
         * that is the point of handing it over — but it must never be recorded
         * as an explicit choice.
         */
        val subtitleAutoResolved: Boolean = false,
        /** Consecutive auto-advance count for pass-out protection (0 = manual start). */
        val autoAdvanceCount: Int = 0,
        /** Opaque key for a process-only, target-bound episode selection handoff. */
        val episodeSelectionHandoffNonce: String? = null,
    ) : TvRoute(
        buildString {
            append("player/${contentId.routeEncode()}")
            val query = buildList {
                if (fileId != null) add("fileId=$fileId")
                VideoPlayerRouteArgs.normalizeQuality(quality)?.let { value ->
                    add("quality=${value.routeEncode()}")
                }
                if (roomId != null) add("roomId=${roomId.routeEncode()}")
                if (audioTrackIndex != null) add("audioTrackIndex=$audioTrackIndex")
                if (audioPickedThisSession) add("audioPicked=true")
                if (subtitleTrackIndex != null) add("subtitleTrackIndex=$subtitleTrackIndex")
                if (subtitleTrackIndex != null && subtitleAutoResolved) {
                    add("$ARG_SUBTITLE_AUTO_RESOLVED=true")
                }
                if (autoAdvanceCount > 0) add("autoAdvanceCount=$autoAdvanceCount")
                episodeSelectionHandoffNonce
                    ?.takeIf(::isValidTvEpisodeSelectionHandoffNonce)
                    ?.let { nonce -> add("$ARG_EPISODE_SELECTION_HANDOFF_NONCE=$nonce") }
                VideoPlayerRouteArgs.encodeResumePosition(resumePositionSeconds)?.let { value ->
                    add("${VideoPlayerRouteArgs.RESUME_POSITION}=$value")
                }
            }
            if (query.isNotEmpty()) append("?").append(query.joinToString("&"))
        },
    ) {
        companion object {
            const val ROUTE = "player/{contentId}?fileId={fileId}&quality={quality}&roomId={roomId}" +
                "&audioTrackIndex={audioTrackIndex}&audioPicked={audioPicked}" +
                "&subtitleTrackIndex={subtitleTrackIndex}" +
                "&subtitleAutoResolved={subtitleAutoResolved}" +
                "&autoAdvanceCount={autoAdvanceCount}&resumePosition={resumePosition}" +
                "&episodeSelectionHandoffNonce={episodeSelectionHandoffNonce}"
            const val ARG_CONTENT_ID = "contentId"
            const val ARG_FILE_ID = "fileId"
            const val ARG_QUALITY = "quality"
            const val ARG_ROOM_ID = "roomId"
            const val ARG_AUDIO_TRACK_INDEX = "audioTrackIndex"
            const val ARG_AUDIO_PICKED = "audioPicked"
            const val ARG_SUBTITLE_TRACK_INDEX = "subtitleTrackIndex"
            const val ARG_SUBTITLE_AUTO_RESOLVED = "subtitleAutoResolved"
            const val ARG_AUTO_ADVANCE_COUNT = "autoAdvanceCount"
            const val ARG_RESUME_POSITION = VideoPlayerRouteArgs.RESUME_POSITION
            const val ARG_EPISODE_SELECTION_HANDOFF_NONCE = "episodeSelectionHandoffNonce"
        }
    }

    /**
     * Audiobook playback route. Optional `fileId` query param pre-selects a
     * specific version (mirrors [Player]); absent ⇒ the VM auto-selects the
     * first version. No `roomId` — audiobooks have no synced playback.
     */
    data class AudiobookPlayer(
        val contentId: String,
        val fileId: Int? = null,
        val startPositionSeconds: Double? = null,
    ) : TvRoute(
        buildString {
            append("audiobook/${contentId.routeEncode()}")
            val query = buildList {
                if (fileId != null) add("fileId=$fileId")
                VideoPlayerRouteArgs.encodeResumePosition(startPositionSeconds)?.let { value ->
                    add("$ARG_START_POSITION=$value")
                }
            }
            if (query.isNotEmpty()) append("?").append(query.joinToString("&"))
        },
    ) {
        companion object {
            const val ROUTE = "audiobook/{contentId}?fileId={fileId}&startPosition={startPosition}"
            const val ARG_CONTENT_ID = "contentId"
            const val ARG_FILE_ID = "fileId"
            const val ARG_START_POSITION = "startPosition"
        }
    }

    /**
     * Watch Together lobby — the waiting/vote/pick surface for a room that has
     * no selection yet. Reached from the entry dialog's Host/Join when the room
     * snapshot carries no `selectedContentId`.
     */
    data class WatchTogetherLobby(val roomId: String) :
        TvRoute("watch_together/lobby/${roomId.routeEncode()}") {
        companion object {
            const val ROUTE = "watch_together/lobby/{roomId}"
            const val ARG_ROOM_ID = "roomId"
        }
    }

    // --- Library collections (reachable from the Libraries landing) ---
    data class LibraryCollectionDetail(
        val libraryId: Int,
        val collectionId: String,
        val title: String,
        /** Drives which sort keys and filter facets the page offers. */
        val libraryType: String = "",
    ) : TvRoute(
        "library/$libraryId/collection/${collectionId.routeEncode()}" +
            "?title=${title.routeEncode()}&libraryType=${libraryType.routeEncode()}"
    ) {
        companion object {
            const val ROUTE =
                "library/{libraryId}/collection/{collectionId}?title={title}&libraryType={libraryType}"
            const val ARG_LIBRARY_ID = "libraryId"
            const val ARG_COLLECTION_ID = "collectionId"
            const val ARG_TITLE = "title"
            const val ARG_LIBRARY_TYPE = "libraryType"
        }
    }

    // --- Personal data grids (reachable from Settings) ---
    data object Favorites : TvRoute("favorites")
    data object Watchlist : TvRoute("watchlist")
    data object History : TvRoute("history")

    // --- Person detail (immersive; opened from a cast/crew card) ---
    data class PersonDetail(val personId: Long) : TvRoute("person/$personId") {
        companion object {
            const val ROUTE = "person/{personId}"
            const val ARG_PERSON_ID = "personId"
        }
    }

    /**
     * Device pairing (immersive auth surface). A signed-in user approves or
     * denies another device's login by token (from a `silo://device?token=…`
     * deep link) or by manually entering the code shown on that device.
     * Mirrors the phone's `Route.PairDevice` / `DevicePairingScreen`.
     */
    data class PairDevice(val token: String? = null, val code: String? = null) :
        TvRoute(
            buildString {
                append("pair_device")
                val params = listOfNotNull(
                    token?.takeIf { it.isNotBlank() }?.let { "token=${it.routeEncode()}" },
                    code?.takeIf { it.isNotBlank() }?.let { "code=${it.routeEncode()}" },
                )
                if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
            },
        ) {
        companion object {
            const val ROUTE = "pair_device?token={token}&code={code}"
            const val ARG_TOKEN = "token"
            const val ARG_CODE = "code"
        }
    }

    // --- Collections ---
    data object Collections : TvRoute("collections")
    data class CollectionDetail(val collectionId: String, val title: String) :
        TvRoute("collection/${collectionId.routeEncode()}?title=${title.routeEncode()}") {
        companion object {
            const val ROUTE = "collection/{collectionId}?title={title}"
            const val ARG_COLLECTION_ID = "collectionId"
            const val ARG_TITLE = "title"
        }
    }

}

/**
 * Sub-routes inside the [TvRoute.Main] shell. These live in the nested NavHost
 * hosted by `org.siloserver.silo.tv.ui.shell.TvMainShell`. The top menu gives
 * direct access to root content surfaces; settings-linked utility surfaces
 * stay nested so they keep the profile menu and focus behavior.
 */
sealed class TvMainRoute(val route: String) {
    data object Home : TvMainRoute("main/home")
    data object Video : TvMainRoute("main/video")
    data object Search : TvMainRoute("main/search")
    data object Libraries : TvMainRoute("main/libraries")
    data object Audio : TvMainRoute("main/audio")
    data object ForYou : TvMainRoute("main/foryou")

    // Content-type tabs (Skyline §3.1). Each renders the library content scoped
    // to the active library of that type. Movies/Series cover the video side;
    // Music/Audiobooks the audio side.
    data object Movies : TvMainRoute("main/movies")
    data object Series : TvMainRoute("main/series")
    data object Music : TvMainRoute("main/music")
    data object Audiobooks : TvMainRoute("main/audiobooks")
    data object Requests : TvMainRoute("main/requests")
    data object MyRequests : TvMainRoute("main/requests/mine")
    data object Settings : TvMainRoute("main/settings")

    data object Collections : TvMainRoute("main/collections")
    data object Favorites : TvMainRoute("main/favorites")
    data object Watchlist : TvMainRoute("main/watchlist")
    data object History : TvMainRoute("main/history")

    /** Upcoming releases by week — opened from the profile menu. */
    data object Calendar : TvMainRoute("main/calendar")

    /** Global cross-library catalog browse — opened from Settings. */
    data object Browse : TvMainRoute("main/browse")

    /** Request detail for a discover/search result (tmdb id + media type). */
    data class RequestDetail(val mediaType: String, val tmdbId: Int) :
        TvMainRoute("main/request/$mediaType/$tmdbId") {
        companion object {
            const val ROUTE = "main/request/{mediaType}/{tmdbId}"
            const val ARG_MEDIA_TYPE = "mediaType"
            const val ARG_TMDB_ID = "tmdbId"
        }
    }
}

/**
 * Route strings this app used to register and no longer does.
 *
 * Navigation restores a saved back stack by destination id, and an id with no
 * registered destination makes the restore throw — so a build that simply drops
 * a route can crash on first launch after the update, before the replacement
 * surface is ever reachable. The phone graph already keeps no-op aliases for its
 * removed Video/Audio/Reading destinations for exactly this reason; these are
 * the TV equivalents, and they redirect rather than render.
 *
 * Nothing here brings a withdrawn surface back: the admin and session-management
 * screens stay deleted, and each alias lands in Settings.
 */
internal val TvRemovedRoutes: List<String> = listOf(
    // Diagnostics became a Settings category rather than a top-level screen.
    "diagnostics",
)

/** Nested [TvRoute.Main] equivalents of [TvRemovedRoutes]. */
internal val TvRemovedMainRoutes: List<String> = listOf(
    "main/settings/sessions",
    "main/admin",
    "main/admin/dashboard",
    "main/admin/users",
    "main/admin/users/edit?userId={userId}",
    "main/admin/sessions",
    "main/admin/scans",
    "main/admin/logs",
)

private fun String.routeEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.toString()).replace("+", "%20")
