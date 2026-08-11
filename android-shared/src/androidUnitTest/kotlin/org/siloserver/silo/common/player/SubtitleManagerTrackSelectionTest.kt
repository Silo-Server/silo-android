package org.siloserver.silo.common.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.playback.isBitmapSubtitleCodecFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
class SubtitleManagerTrackSelectionTest {

    @Test
    fun typedLocalSelectionUsesExactMedia3IdAcrossDuplicateMetadata() {
        val first = TrackGroup(
            subtitle(
                label = "English",
                language = "en",
                sampleMimeType = MimeTypes.TEXT_VTT,
                id = "decoder-text-8",
            ),
        )
        val second = TrackGroup(
            subtitle(
                label = "English",
                language = "en",
                sampleMimeType = MimeTypes.TEXT_VTT,
                id = "decoder-text-9",
            ),
        )
        val tracks = Tracks(
            listOf(first, second).map { group ->
                Tracks.Group(
                    group,
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(false),
                )
            },
        )

        val selection = resolveSubtitleSelection(
            tracks,
            SubtitleIdentity.LocalMedia3(
                SubtitleMediaIdentity(
                    trackId = "decoder-text-9",
                    language = "en",
                    codecFamily = "webvtt",
                    hearingImpaired = false,
                ),
            ),
        )

        assertSame(second, selection?.mediaTrackGroup)
        assertEquals(0, selection?.trackIndex)
    }

    @Test
    fun extractedEmbeddedTextArtifactSelectsReservedServerTrackEndToEnd() {
        val artifact = TrackGroup(
            subtitle(
                label = "English",
                language = "en",
                sampleMimeType = MimeTypes.TEXT_VTT,
                id = "silo-subtitle:7",
            ),
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    artifact,
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(false),
                ),
            ),
        )

        val selection = resolveSubtitleSelection(
            tracks,
            PlayerSubtitleInfo(
                index = 7,
                language = "en",
                codec = "webvtt",
                label = "English",
                source = "embedded",
                forced = false,
                url = "/stream/s2/subtitles/7.vtt",
            ),
        )

        assertSame(artifact, selection?.mediaTrackGroup)
        assertEquals(0, selection?.trackIndex)
    }

    @Test
    fun serverArtifactConfigurationsCarryStableCombinedIndexes() {
        val configurations = SubtitleManager().buildSubtitleConfigurations(
            subtitles = listOf(
                PlayerSubtitleInfo(3, "en", "webvtt", "Server subtitle", "server_artifact", true, "/3.vtt"),
                PlayerSubtitleInfo(4, "en", "webvtt", "Server subtitle", "server_artifact", false, "/4.vtt"),
            ),
            serverUrl = "https://silo.example",
        )

        assertEquals(
            listOf("silo-subtitle:3", "silo-subtitle:4"),
            configurations.map { it.id },
        )
    }

    @Test
    fun serverAndDownloadedConfigurationsUseDisjointStableIds() {
        val configurations = SubtitleManager().buildSubtitleConfigurations(
            subtitles = listOf(
                PlayerSubtitleInfo(3, "en", "webvtt", "English", "server_artifact", false, "/3.vtt"),
                PlayerSubtitleInfo(
                    index = 4,
                    language = "en",
                    codec = "webvtt",
                    label = "English",
                    source = "downloaded",
                    forced = false,
                    url = "/4.vtt",
                    downloadId = 312,
                ),
                PlayerSubtitleInfo(
                    index = 5,
                    language = "en",
                    codec = "webvtt",
                    label = "English",
                    source = null,
                    forced = false,
                    url = "/5.vtt",
                    catalogSource = "downloaded",
                    downloadId = 313,
                ),
                PlayerSubtitleInfo(
                    index = 6,
                    language = "en",
                    codec = "webvtt",
                    label = "English",
                    source = "server_artifact",
                    forced = false,
                    url = "/6.vtt",
                    catalogSource = "downloaded",
                    downloadId = 314,
                ),
            ),
            serverUrl = "https://silo.example",
        )

        assertEquals(
            listOf(
                "silo-subtitle:3",
                "silo-downloaded-subtitle:312",
                "silo-downloaded-subtitle:313",
                "silo-downloaded-subtitle:314",
            ),
            configurations.map { it.id },
        )
    }

    @Test
    fun downloadedConfigurationIdSurvivesArtifactReorderDeletionAndCatalogGrowth() {
        fun mountedId(index: Int): String? =
            SubtitleManager().buildSubtitleConfigurations(
                subtitles = listOf(
                    PlayerSubtitleInfo(
                        index = index,
                        language = "en",
                        codec = "webvtt",
                        label = "Downloaded English",
                        source = "downloaded",
                        forced = false,
                        url = "/$index.vtt",
                        downloadId = 312,
                    ),
                ),
                serverUrl = "https://silo.example",
            ).single().id

        assertEquals("silo-downloaded-subtitle:312", mountedId(index = 1))
        assertEquals("silo-downloaded-subtitle:312", mountedId(index = 2))
        assertEquals("silo-downloaded-subtitle:312", mountedId(index = 8))
    }

    @Test
    fun legacyDownloadedConfigurationDoesNotFabricateStableIdFromArtifactIndex() {
        val configuration = SubtitleManager().buildSubtitleConfigurations(
            subtitles = listOf(
                PlayerSubtitleInfo(
                    index = 4,
                    language = "en",
                    codec = "webvtt",
                    label = "Legacy downloaded English",
                    source = "downloaded",
                    forced = false,
                    url = "/4.vtt",
                ),
            ),
            serverUrl = "https://silo.example",
        ).single()

        assertNull(configuration.id)
    }

    @Test
    fun mobileSelectionResolvesUniqueLegacyDownloadedTrackWithoutStableId() {
        val ordinary = TrackGroup(
            subtitle(
                label = "Legacy English",
                language = "en",
                sampleMimeType = MimeTypes.TEXT_VTT,
            ),
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    ordinary,
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(false),
                ),
            ),
        )

        val selection = resolveSubtitleSelection(
            tracks,
            PlayerSubtitleInfo(
                index = 4,
                language = "en",
                codec = "webvtt",
                label = "Legacy English",
                source = "downloaded",
                forced = false,
                url = "/4.vtt",
            ),
        )

        assertSame(ordinary, selection?.mediaTrackGroup)
        assertEquals(0, selection?.trackIndex)
    }

    @Test
    fun mobileSelectionUsesDownloadedStableIdAcrossDuplicateLabels() {
        val server = TrackGroup(
            subtitle("English", "en", id = "silo-subtitle:3"),
        )
        val downloaded = TrackGroup(
            subtitle("English", "en", id = "silo-downloaded-subtitle:312"),
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(server, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
                Tracks.Group(downloaded, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
            ),
        )

        val selection = resolveSubtitleSelection(
            tracks,
            PlayerSubtitleInfo(
                index = 4,
                language = "en",
                codec = "webvtt",
                label = "English",
                source = "server_artifact",
                forced = false,
                url = "/4.vtt",
                catalogSource = "downloaded",
                downloadId = 312,
            ),
        )

        assertSame(downloaded, selection?.mediaTrackGroup)
        assertEquals(0, selection?.trackIndex)
    }

    @Test
    fun mobileMetadataSelectionUsesStableIdAcrossDuplicateRuntimeLabels() {
        val forced = TrackGroup(
            subtitle("Server subtitle", "en", id = "silo-subtitle:3", forced = true),
        )
        val full = TrackGroup(
            subtitle("Server subtitle", "en", id = "silo-subtitle:4", forced = false),
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(forced, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
                Tracks.Group(full, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
            ),
        )

        val selection = resolveSubtitleSelection(
            tracks,
            PlayerSubtitleInfo(4, "en", "webvtt", "Server subtitle", "server_artifact", false, "/4.vtt"),
        )

        assertSame(full, selection?.mediaTrackGroup)
        assertEquals(0, selection?.trackIndex)
    }

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
    fun bitmapSubtitleUrlsAreMountedAsMedia3Sidecars() {
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

        assertEquals(2, configurations.size)
        assertEquals("English", configurations[0].label)
        assertEquals(MimeTypes.TEXT_VTT, configurations[0].mimeType)
        assertEquals("English (PGS)", configurations[1].label)
        assertEquals(MimeTypes.APPLICATION_PGS, configurations[1].mimeType)
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
            assertTrue(isBitmapSubtitleCodecFamily(codec), "expected bitmap: $codec")
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
            assertFalse(isBitmapSubtitleCodecFamily(codec), "expected text: $codec")
        }
    }

    private fun subtitle(
        label: String?,
        language: String?,
        sampleMimeType: String = MimeTypes.APPLICATION_SUBRIP,
        codecs: String? = null,
        id: String? = null,
        forced: Boolean = false,
    ): Format =
        Format.Builder()
            .setId(id)
            .setLabel(label)
            .setLanguage(language)
            .setSampleMimeType(sampleMimeType)
            .setCodecs(codecs)
            .setSelectionFlags(if (forced) C.SELECTION_FLAG_FORCED else 0)
            .build()
}
