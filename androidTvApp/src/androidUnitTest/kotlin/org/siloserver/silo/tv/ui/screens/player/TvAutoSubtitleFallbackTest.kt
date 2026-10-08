package org.siloserver.silo.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity

/**
 * The player's Auto fallback — the only path left for launches that carried no
 * decision (deep link, cast, remote start, recovery).
 *
 * It used to rank Media3's MOUNTED text tracks, so an external sidecar the
 * initial plan never mounted could not be a candidate at all: on an
 * English/Always profile the lone mounted PGS track won by default while the
 * detail row had previewed the SRT. It resolves over the server inventory now.
 */
class TvAutoSubtitleFallbackTest {

    private val pgsRow = PlayerSubtitleInfo(
        index = 1,
        language = "eng",
        codec = "pgs",
        label = "English (SDH)",
        source = "embedded",
        url = "",
        catalogLabel = "English (SDH)",
        catalogSource = "embedded",
    )

    private val sidecarRow = PlayerSubtitleInfo(
        index = 0,
        language = "eng",
        codec = "srt",
        label = "English",
        source = "external",
        url = "https://silo.example/stream/s1/subtitles/0.vtt",
        catalogLabel = "English",
        catalogSource = "external",
    )

    /** Only the embedded PGS track is mounted — the sidecar is not in the media item yet. */
    private val mountedPgsOnly = listOf(
        PlayerTrackEntry(
            index = 0,
            label = "English (SDH)",
            language = "en",
            isSelected = false,
            codecOrMime = MimeTypes.APPLICATION_PGS,
        ),
    )

    @Test
    fun theFallbackPrefersAnUnmountedPlainExternalTrackOverTheMountedSdhOne() {
        // The SRT wins because the mounted PGS track is SDH. Being a bitmap
        // alone no longer demotes it: see the plain-PGS case below.
        val identity = resolveTvAutoSubtitleIdentity(
            audioTracks = emptyList(),
            subtitleTracks = mountedPgsOnly,
            subtitleRows = listOf(sidecarRow, pgsRow),
            preferredLanguage = "en",
            subtitleMode = "always",
            showForced = true,
        )

        assertEquals(tvSubtitleIdentity(sidecarRow), identity)
    }

    @Test
    fun theFallbackPrefersAPlainEmbeddedPgsTrackOverAnExternalSrt() {
        // Android renders embedded PGS itself, so with nothing else to tell
        // them apart the file's own track beats the sidecar.
        val plainPgs = pgsRow.copy(label = "English", catalogLabel = "English")
        val identity = resolveTvAutoSubtitleIdentity(
            audioTracks = emptyList(),
            subtitleTracks = mountedPgsOnly,
            subtitleRows = listOf(sidecarRow, plainPgs),
            preferredLanguage = "en",
            subtitleMode = "always",
            showForced = true,
        )

        assertEquals(tvSubtitleIdentity(plainPgs), identity)
    }

    @Test
    fun theFallbackPrefersAnEmbeddedTextTrackOverAnExternalOne() {
        // The inventory lists externals first; source, not that order, decides.
        val embeddedText = PlayerSubtitleInfo(
            index = 1,
            language = "eng",
            codec = "subrip",
            label = "English",
            source = "embedded",
            url = "https://silo.example/stream/s1/subtitles/1.vtt",
            catalogLabel = "English",
            catalogSource = "embedded",
        )
        val identity = resolveTvAutoSubtitleIdentity(
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            subtitleRows = listOf(sidecarRow, embeddedText),
            preferredLanguage = "en",
            subtitleMode = "always",
            showForced = false,
        )

        assertEquals(tvSubtitleIdentity(embeddedText), identity)
    }

    @Test
    fun theFallbackPrefersAnExternalSrtOverAnEmbeddedTrackThatNeedsABurnIn() {
        val vobSub = pgsRow.copy(codec = "dvd_subtitle", label = "English", catalogLabel = "English")
        val identity = resolveTvAutoSubtitleIdentity(
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            subtitleRows = listOf(sidecarRow, vobSub),
            preferredLanguage = "en",
            subtitleMode = "always",
            showForced = false,
        )

        assertEquals(tvSubtitleIdentity(sidecarRow), identity)
    }

    @Test
    fun theFallbackStillTakesTheBitmapTrackWhenItIsTheOnlyCandidate() {
        val identity = resolveTvAutoSubtitleIdentity(
            audioTracks = emptyList(),
            subtitleTracks = mountedPgsOnly,
            subtitleRows = listOf(pgsRow),
            preferredLanguage = "en",
            subtitleMode = "always",
            showForced = true,
        )

        assertEquals(tvSubtitleIdentity(pgsRow), identity)
    }

    @Test
    fun autoResolvingToNothingStartsExplicitlyOff() {
        val identity = resolveTvAutoSubtitleIdentity(
            audioTracks = listOf(
                PlayerTrackEntry(index = 0, label = "English", language = "eng", isSelected = true),
            ),
            subtitleTracks = mountedPgsOnly,
            subtitleRows = listOf(sidecarRow, pgsRow),
            preferredLanguage = "en",
            subtitleMode = "auto",
            showForced = false,
        )

        assertEquals(SubtitleIdentity.Off, identity)
    }

    @Test
    fun withoutAServerInventoryTheMountedTracksAreRanked() {
        val identity = resolveTvAutoSubtitleIdentity(
            audioTracks = emptyList(),
            subtitleTracks = listOf(
                PlayerTrackEntry(
                    index = 0,
                    label = "English (SDH)",
                    language = "en",
                    isSelected = false,
                    codecOrMime = MimeTypes.TEXT_VTT,
                ),
                PlayerTrackEntry(
                    index = 1,
                    label = "English",
                    language = "en",
                    isSelected = false,
                    codecOrMime = MimeTypes.TEXT_VTT,
                ),
            ),
            subtitleRows = emptyList(),
            preferredLanguage = "en",
            subtitleMode = "always",
            showForced = true,
        )

        // Full dialogue beats SDH, and a player-discovered track keeps its own
        // Media3 identity.
        val local = assertIs<SubtitleIdentity.LocalMedia3>(identity)
        assertEquals("English", local.media.label)
    }
}
