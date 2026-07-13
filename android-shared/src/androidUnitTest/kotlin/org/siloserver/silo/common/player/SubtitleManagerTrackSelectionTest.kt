package org.siloserver.silo.common.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
class SubtitleManagerTrackSelectionTest {

    @Test
    fun relativeServerSubtitleUrlsResolveThroughApiStreamMount() {
        assertEquals(
            "https://silo.example/api/v1/stream/session-1/subtitles/0.srt",
            resolveSubtitleUrl("https://silo.example", "/stream/session-1/subtitles/0.srt"),
        )
    }

    @Test
    fun apiRelativeStreamUrlsAreNotDoublePrefixed() {
        assertEquals(
            "https://silo.example/api/v1/stream/session-1/subtitles/0.srt",
            resolveSubtitleUrl("https://silo.example", "/api/v1/stream/session-1/subtitles/0.srt"),
        )
    }

    @Test
    fun flatSubtitleIndexCanSelectSecondTrackInsideOneMedia3Group() {
        val group = TrackGroup(
            subtitle("English", "en"),
            subtitle("English AI", "en"),
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    group,
                    false,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(false, false),
                ),
            ),
        )

        val selection = resolveSubtitleSelection(tracks, subtitleIndex = 1)

        assertSame(group, selection?.mediaTrackGroup)
        assertEquals(1, selection?.trackIndex)
    }

    @Test
    fun appSubtitleMetadataSelectionSkipsEmbeddedTracksBeforeSidecars() {
        val embedded = TrackGroup(subtitle(null, null))
        val englishSidecar = TrackGroup(
            subtitle(
                "The Day of the Jackal (2024) - S01E02 [Bluray-1080p Remux]-SiCFoI.en.sdh.srt",
                "en",
            )
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(embedded, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
                Tracks.Group(
                    TrackGroup(subtitle("The Day of the Jackal (2024) - S01E02 [Bluray-1080p Remux]-SiCFoI.ar.sdh.srt", "ar")),
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(false),
                ),
                Tracks.Group(
                    TrackGroup(subtitle("The Day of the Jackal (2024) - S01E02 [Bluray-1080p Remux]-SiCFoI.da.sdh.srt", "da")),
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(false),
                ),
                Tracks.Group(
                    TrackGroup(subtitle("The Day of the Jackal (2024) - S01E02 [Bluray-1080p Remux]-SiCFoI.de.sdh.srt", "de")),
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(false),
                ),
                Tracks.Group(englishSidecar, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
            ),
        )

        val selection = resolveSubtitleSelection(
            tracks,
            PlayerSubtitleInfo(
                index = 3,
                language = "en",
                codec = "subrip",
                label = "The Day of the Jackal (2024) - S01E02 [Bluray-1080p Remux]-SiCFoI.en.sdh.srt",
                source = "external",
                forced = null,
                url = "/stream/session-1/subtitles/3.vtt",
            ),
        )

        assertSame(englishSidecar, selection?.mediaTrackGroup)
        assertEquals(0, selection?.trackIndex)
    }

    @Test
    fun appSubtitleLanguageFallbackPrefersExternalTextCuesOverEmbeddedPgs() {
        val embeddedPgs = TrackGroup(
            subtitle(
                label = "English (SDH)",
                language = "en",
                sampleMimeType = "application/x-media3-cues",
                codecs = MimeTypes.APPLICATION_PGS,
            )
        )
        val englishSidecar = TrackGroup(
            subtitle(
                label = "The Day of the Jackal (2024) - S01E06 [Bluray-1080p Remux]-SiCFoI.en.sdh.srt",
                language = "en",
                sampleMimeType = "application/x-media3-cues",
                codecs = MimeTypes.TEXT_VTT,
            )
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(embeddedPgs, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
                Tracks.Group(englishSidecar, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
            ),
        )

        val selection = resolveSubtitleSelection(
            tracks,
            PlayerSubtitleInfo(
                index = 6,
                language = "en",
                codec = "subrip",
                label = "English",
                source = "external",
                forced = null,
                url = "/stream/session-1/subtitles/6.vtt",
            ),
        )

        assertSame(englishSidecar, selection?.mediaTrackGroup)
        assertEquals(0, selection?.trackIndex)
    }

    @Test
    fun embeddedVobSubMetadataSelectsTheEmbeddedBitmapTrack() {
        val ass = TrackGroup(
            subtitle("English ASS", "en", sampleMimeType = "application/x-media3-cues", codecs = MimeTypes.TEXT_SSA),
        )
        val vobSub = TrackGroup(
            subtitle("DVD VobSub", "en", sampleMimeType = "application/x-media3-cues", codecs = "application/vobsub"),
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(ass, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
                Tracks.Group(vobSub, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
            ),
        )

        val selection = resolveSubtitleSelection(
            tracks,
            PlayerSubtitleInfo(
                index = 2,
                codec = "dvd_subtitle",
                source = "embedded",
                url = "",
            ),
        )

        assertSame(vobSub, selection?.mediaTrackGroup)
        assertEquals(0, selection?.trackIndex)
    }

    @Test
    fun deliveredVttSubtitleUrlsUseWebvttMimeEvenWhenSourceCodecIsSubrip() {
        val configuration = SubtitleManager().buildSubtitleConfigurations(
            subtitles = listOf(
                PlayerSubtitleInfo(
                    index = 0,
                    language = "en",
                    codec = "subrip",
                    label = "English",
                    source = "external",
                    forced = null,
                    url = "/stream/session-1/subtitles/0.vtt",
                )
            ),
            serverUrl = "https://silo.example",
        ).single()

        assertEquals(MimeTypes.TEXT_VTT, configuration.mimeType)
    }

    @Test
    fun realSubripSubtitleUrlsStillUseSubripMime() {
        val configuration = SubtitleManager().buildSubtitleConfigurations(
            subtitles = listOf(
                PlayerSubtitleInfo(
                    index = 0,
                    language = "en",
                    codec = "subrip",
                    label = "English",
                    source = "external",
                    forced = null,
                    url = "/stream/session-1/subtitles/0.srt",
                )
            ),
            serverUrl = "https://silo.example",
        ).single()

        assertEquals(MimeTypes.APPLICATION_SUBRIP, configuration.mimeType)
    }

    @Test
    fun bitmapSubtitleUrlsAreNotMountedAsMedia3TextSidecars() {
        val configurations = SubtitleManager().buildSubtitleConfigurations(
            subtitles = listOf(
                PlayerSubtitleInfo(
                    index = 0,
                    language = "en",
                    codec = "subrip",
                    label = "English",
                    source = "external",
                    forced = null,
                    url = "/stream/session-1/subtitles/0.vtt",
                ),
                PlayerSubtitleInfo(
                    index = 1,
                    language = "en",
                    codec = "hdmv_pgs_subtitle",
                    label = "English (PGS)",
                    source = "embedded",
                    forced = null,
                    url = "/stream/session-1/subtitles/1.sup",
                ),
            ),
            serverUrl = "https://silo.example",
        )

        assertEquals(1, configurations.size)
        assertEquals("English", configurations.single().label)
        assertEquals(MimeTypes.TEXT_VTT, configurations.single().mimeType)
    }

    @Test
    fun embeddedBitmapSelectionMetadataIsNeverMountedAsASidecar() {
        val configurations = SubtitleManager().buildSubtitleConfigurations(
            subtitles = listOf(
                PlayerSubtitleInfo(
                    index = 2,
                    codec = "dvd_subtitle",
                    source = "embedded",
                    url = "",
                ),
            ),
            serverUrl = "https://silo.example",
        )

        assertTrue(configurations.isEmpty())
    }

    @Test
    fun bitmapSubtitleClassificationCoversFfprobeAndMedia3Names() {
        // Apple's ApplePlaybackRoutePlanner token set (ffprobe names) plus the
        // Media3 mimes must all classify as bitmap regardless of separator
        // style — the old '_'→'-' normalization missed "dvb_subtitle" and
        // never knew "vobsub" at all.
        listOf(
            "pgs",
            "hdmv_pgs_subtitle",
            "dvd_subtitle",
            "dvb_subtitle",
            "dvbsub",
            "dvbsubs",
            "vobsub",
            MimeTypes.APPLICATION_PGS,
            MimeTypes.APPLICATION_DVBSUBS,
        ).forEach { codec ->
            assertTrue(isBitmapSubtitleCodecOrMime(codec), "expected bitmap: $codec")
        }
        listOf(
            "subrip",
            "srt",
            "ass",
            "webvtt",
            "mov_text",
            "dvb_teletext",
            "hdmv_text_subtitle",
            null,
            " ",
        ).forEach { codec ->
            assertFalse(isBitmapSubtitleCodecOrMime(codec), "expected text: $codec")
        }
    }

    private fun subtitle(
        label: String?,
        language: String?,
        sampleMimeType: String = MimeTypes.APPLICATION_SUBRIP,
        codecs: String? = null,
    ): Format =
        Format.Builder()
            .setLabel(label)
            .setLanguage(language)
            .setSampleMimeType(sampleMimeType)
            .setCodecs(codecs)
            .build()
}
