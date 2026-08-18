package org.siloserver.silo.tv.ui.screens.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.tv.ui.navigation.TvRoute
import org.siloserver.silo.tv.ui.navigation.TvSubtitleLaunchSelection
import org.siloserver.silo.tv.ui.navigation.explicitTvSubtitleLaunchSelection
import org.siloserver.silo.tv.ui.navigation.tvPlayDestinationFor
import org.siloserver.silo.tv.ui.screens.player.resolveTvPlaybackStartSelection
import org.siloserver.silo.tv.ui.screens.player.resolveTvServerSubtitleTrackIndex

/**
 * Play must launch with exactly the subtitle the detail row is displaying.
 *
 * The Auto case used to hand over nothing: no `subtitle_track_index` in the
 * start request, no sidecar in the initial media item, and a player that then
 * re-derived Auto over Media3's mounted tracks — where the external SRT the row
 * had previewed did not exist. The row's own answer travels now, tagged so an
 * auto-resolved pick is never mistaken for the viewer's own.
 */
class TvSubtitleLaunchHandoffTest {

    private val autoEnglishAlways = TvPlaybackFormatting.SubtitleAutoContext(
        preferredLanguage = "en",
        mode = "always",
        showForced = true,
        audioLanguage = "eng",
    )

    /** The Shield repro: embedded PGS "English (SDH)" + an external English SRT. */
    private val pgsPlusSidecar = fileVersion(
        subtitles = listOf(
            subtitleTrack(index = 2, codec = "hdmv_pgs_subtitle", lang = "eng", title = "English (SDH)"),
            subtitleTrack(index = 0, codec = "srt", lang = "eng", external = true),
        ),
    )

    @Test
    fun autoHandsOverTheResolvedCombinedIndexAndFlagsItAsAutomatic() {
        val selection = TvPlaybackFormatting.subtitleLaunchSelection(
            version = pgsPlusSidecar,
            selectedSubtitleTrackIndex = null,
            autoContext = autoEnglishAlways,
        )

        // Externals occupy combined 0..n-1: the SRT sidecar is 0, the embedded
        // PGS track is 1.
        assertEquals(TvSubtitleLaunchSelection(0, autoResolved = true), selection)
    }

    @Test
    fun theHandoffIsExactlyWhatThePillShows() {
        assertEquals(
            "Auto - English · SRT",
            TvPlaybackFormatting.subtitleValueLabel(pgsPlusSidecar, null, autoEnglishAlways),
        )
        val selection = TvPlaybackFormatting.subtitleLaunchSelection(
            version = pgsPlusSidecar,
            selectedSubtitleTrackIndex = null,
            autoContext = autoEnglishAlways,
        )
        assertEquals(
            "English · SRT",
            TvPlaybackFormatting.subtitleValueLabel(pgsPlusSidecar, selection?.selectionIndex),
        )
    }

    @Test
    fun autoResolvingToNothingHandsOverAnExplicitOff() {
        val version = fileVersion(subtitles = listOf(subtitleTrack(lang = "eng")))
        val context = TvPlaybackFormatting.SubtitleAutoContext(
            preferredLanguage = "en",
            mode = "auto",
            audioLanguage = "eng",
        )

        assertEquals("Auto - None", TvPlaybackFormatting.subtitleValueLabel(version, null, context))
        assertEquals(
            TvSubtitleLaunchSelection(-1, autoResolved = true),
            TvPlaybackFormatting.subtitleLaunchSelection(version, null, context),
        )
    }

    @Test
    fun anExplicitPickTravelsAsTheViewersOwn() {
        val selection = TvPlaybackFormatting.subtitleLaunchSelection(
            version = pgsPlusSidecar,
            selectedSubtitleTrackIndex = 1,
            autoContext = autoEnglishAlways,
        )

        assertEquals(TvSubtitleLaunchSelection(1, autoResolved = false), selection)
        assertEquals(1, selection?.explicitSelectionIndex)
    }

    @Test
    fun anExplicitOffTravelsAsTheViewersOwn() {
        val selection = TvPlaybackFormatting.subtitleLaunchSelection(
            version = pgsPlusSidecar,
            selectedSubtitleTrackIndex = -1,
            autoContext = autoEnglishAlways,
        )

        assertEquals(TvSubtitleLaunchSelection(-1, autoResolved = false), selection)
    }

    @Test
    fun withoutResolutionInputsNothingIsClaimed() {
        // The row itself falls back to a bare "Auto" here, so the player keeps
        // its own fallback rather than being handed a guess.
        assertNull(
            TvPlaybackFormatting.subtitleLaunchSelection(
                version = pgsPlusSidecar,
                selectedSubtitleTrackIndex = null,
                autoContext = null,
            ),
        )
    }

    // --- routing --------------------------------------------------------

    @Test
    fun theRouteCarriesTheIndexAndTheAutomaticFlag() {
        val route = tvPlayDestinationFor(
            itemType = "movie",
            contentId = "m-1",
            fileId = 7,
            resumePositionSeconds = null,
            audioTrackIndex = null,
            audioPickedThisSession = false,
            subtitleSelection = TvSubtitleLaunchSelection(0, autoResolved = true),
        )

        assertTrue(route.contains("subtitleTrackIndex=0"), route)
        assertTrue(route.contains("subtitleAutoResolved=true"), route)
    }

    @Test
    fun anExplicitPickNeverCarriesTheAutomaticFlag() {
        val route = tvPlayDestinationFor(
            itemType = "movie",
            contentId = "m-1",
            fileId = 7,
            resumePositionSeconds = null,
            audioTrackIndex = null,
            audioPickedThisSession = false,
            subtitleSelection = explicitTvSubtitleLaunchSelection(3),
        )

        assertTrue(route.contains("subtitleTrackIndex=3"), route)
        assertTrue(!route.contains("subtitleAutoResolved"), route)
    }

    @Test
    fun aRouteWithNoSubtitleDecisionCarriesNeither() {
        val route = TvRoute.Player(contentId = "m-1").route
        assertTrue(!route.contains("subtitleTrackIndex"), route)
        assertTrue(!route.contains("subtitleAutoResolved"), route)
    }

    // --- the start request ---------------------------------------------

    @Test
    fun theAutoResolvedIndexReachesTheServerStartRequest() {
        // With the index in the start request the initial plan mounts the
        // sidecar into the FIRST media item — no replan, no rebuffer.
        val selection = TvPlaybackFormatting.subtitleLaunchSelection(
            version = pgsPlusSidecar,
            selectedSubtitleTrackIndex = null,
            autoContext = autoEnglishAlways,
        )

        assertEquals(
            0,
            resolveTvServerSubtitleTrackIndex(
                episodeSelectionHandoff = null,
                resolvedEpisodeSelection = resolveTvPlaybackStartSelection(
                    preferredFileId = null,
                    episodeSelectionHandoff = null,
                    targetVersions = listOf(pgsPlusSidecar),
                    targetLastFileId = null,
                    preferredQuality = null,
                ),
                requestedSubtitleTrackIndex = selection?.selectionIndex,
            ),
        )
    }

    @Test
    fun anAutoResolvedOffIsNotSentToTheServer() {
        // -1 is the client-side "explicit Off"; the server rejects it.
        assertNull(
            resolveTvServerSubtitleTrackIndex(
                episodeSelectionHandoff = null,
                resolvedEpisodeSelection = resolveTvPlaybackStartSelection(
                    preferredFileId = null,
                    episodeSelectionHandoff = null,
                    targetVersions = listOf(pgsPlusSidecar),
                    targetLastFileId = null,
                    preferredQuality = null,
                ),
                requestedSubtitleTrackIndex = -1,
            ),
        )
    }

    // ------------------------------------------------------------------

    private fun fileVersion(
        fileId: Int = 1,
        subtitles: List<SubtitleTrack>? = null,
    ): FileVersion = FileVersion(fileId = fileId, subtitleTracks = subtitles)

    private fun subtitleTrack(
        index: Int = 0,
        codec: String? = null,
        lang: String? = null,
        title: String? = null,
        forced: Boolean = false,
        external: Boolean = false,
    ): SubtitleTrack = SubtitleTrack(
        index = index,
        codec = codec,
        language = lang,
        title = title,
        forced = forced,
        external = external,
    )
}
