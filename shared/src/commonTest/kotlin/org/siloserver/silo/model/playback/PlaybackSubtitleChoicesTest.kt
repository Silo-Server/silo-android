package org.siloserver.silo.model.playback

import org.siloserver.silo.model.catalog.SubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals

// buildPlaybackSubtitleChoices output lives in the server's COMBINED index
// space (the identity session subtitle_urls and subtitle_track_index replans
// use): externals occupy 0..n-1 in catalog order, embedded follow at
// n+ordinal. Catalog `index` values are NOT that space — embedded entries
// carry ffprobe stream indexes and older servers serialize every external as
// index 0 — so the merge derives combined indexes from catalog structure and
// never trusts the raw values.
class PlaybackSubtitleChoicesTest {
    @Test
    fun catalogAlternativesSurviveWhenPlanSelectsOnlyOneArtifact() {
        val choices = buildPlaybackSubtitleChoices(
            catalogTracks = listOf(
                SubtitleTrack(index = 3, codec = "srt", language = "en", title = "English"),
                SubtitleTrack(index = 7, codec = "ass", language = "ja", title = "Signs"),
            ),
            plannedTracks = listOf(
                PlayerSubtitleInfo(index = 1, source = "server_artifact", url = "/planned/signs.ass"),
            ),
        )

        // Two embedded tracks, no externals: combined indexes are 0 and 1.
        assertEquals(listOf(0, 1), choices.map(PlayerSubtitleInfo::index))
        assertEquals("", choices[0].url)
        assertEquals("/planned/signs.ass", choices[1].url)
        assertEquals("Signs", choices[1].label)
        assertEquals("ja", choices[1].language)
    }

    @Test
    fun catalogIdentityAndDefaultFlagReplaceGenericArtifactMetadata() {
        val choices = buildPlaybackSubtitleChoices(
            catalogTracks = listOf(
                SubtitleTrack(
                    index = 4,
                    codec = "subrip",
                    language = "en",
                    title = "English",
                    isDefault = true,
                ),
            ),
            plannedTracks = listOf(
                PlayerSubtitleInfo(
                    index = 0,
                    codec = "srt",
                    label = "Server subtitle",
                    source = "server_artifact",
                    url = "/planned/english.vtt",
                ),
            ),
        )

        assertEquals("Server subtitle", choices.single().label)
        assertEquals("English", choices.single().catalogLabel)
        assertEquals("embedded", choices.single().catalogSource)
        assertEquals(true, choices.single().isDefault)
        assertEquals("/planned/english.vtt", choices.single().url)
    }

    @Test
    fun embeddedBitmapChoiceUsesTheMediaContainerInsteadOfASidecarUrl() {
        val choices = buildPlaybackSubtitleChoices(
            catalogTracks = listOf(
                SubtitleTrack(index = 5, codec = "hdmv_pgs_subtitle", title = "English PGS"),
            ),
            plannedTracks = emptyList(),
        )

        assertEquals("", choices.single().url)
        assertEquals("embedded", choices.single().source)
        assertEquals(0, choices.single().index)
    }

    @Test
    fun duplicateCatalogExternalIndexesCannotCollide() {
        // Older servers serialize every external subtitle with index 0 (the
        // field was never set). Two externals plus one embedded used to yield
        // duplicate index-0 rows — the TV subtitle picker keyed rows by index
        // and crashed (LazyColumn duplicate key "0").
        val choices = buildPlaybackSubtitleChoices(
            catalogTracks = listOf(
                SubtitleTrack(index = 2, codec = "subrip", language = "en", title = "Embedded EN"),
                SubtitleTrack(index = 0, codec = "srt", language = "en", title = "External EN", external = true),
                SubtitleTrack(index = 0, codec = "srt", language = "nl", title = "External NL", external = true),
            ),
            plannedTracks = listOf(
                PlayerSubtitleInfo(index = 0, language = "en", source = "external", url = "/stream/s/subtitles/0.vtt"),
                PlayerSubtitleInfo(index = 1, language = "nl", source = "external", url = "/stream/s/subtitles/1.vtt"),
                PlayerSubtitleInfo(index = 2, language = "en", source = "embedded", url = "/stream/s/subtitles/2.vtt"),
            ),
        )

        // Externals first at combined 0..1, embedded at 2 — all unique.
        assertEquals(listOf(0, 1, 2), choices.map(PlayerSubtitleInfo::index))
        assertEquals("External EN", choices[0].label)
        assertEquals("/stream/s/subtitles/0.vtt", choices[0].url)
        assertEquals("External NL", choices[1].label)
        assertEquals("/stream/s/subtitles/1.vtt", choices[1].url)
        assertEquals("Embedded EN", choices[2].label)
        assertEquals("/stream/s/subtitles/2.vtt", choices[2].url)
    }

    @Test
    fun plannedRowsBeyondCatalogCoverageAreAppended() {
        // Downloaded subtitles mount past the catalog range and must survive
        // the merge untouched.
        val choices = buildPlaybackSubtitleChoices(
            catalogTracks = listOf(
                SubtitleTrack(index = 2, codec = "subrip", language = "en"),
            ),
            plannedTracks = listOf(
                PlayerSubtitleInfo(index = 0, language = "en", source = "embedded", url = "/stream/s/subtitles/0.vtt"),
                PlayerSubtitleInfo(index = 5, language = "nl", source = "downloaded", url = "/stream/s/subtitles/5.vtt"),
            ),
        )

        assertEquals(listOf(0, 5), choices.map(PlayerSubtitleInfo::index))
        assertEquals("downloaded", choices[1].source)
    }

    @Test
    fun emptyCatalogDedupesPlannedRowsByIndex() {
        val choices = buildPlaybackSubtitleChoices(
            catalogTracks = emptyList(),
            plannedTracks = listOf(
                PlayerSubtitleInfo(index = 0, language = "en", url = "/a.vtt"),
                PlayerSubtitleInfo(index = 0, language = "nl", url = "/b.vtt"),
            ),
        )

        // Defense against a misbehaving server: the picker keys rows by
        // index, so the merged list must never publish duplicates.
        assertEquals(listOf(0), choices.map(PlayerSubtitleInfo::index))
        assertEquals("/a.vtt", choices.single().url)
    }

    @Test
    fun authoritativeInventoryNeverSynthesizesCatalogOnlyRows() {
        val catalog = listOf(
            SubtitleTrack(index = 3, language = "en", title = "English"),
            SubtitleTrack(index = 7, language = "ja", title = "Signs"),
        )

        assertEquals(
            emptyList(),
            enrichAuthoritativePlaybackSubtitleChoices(catalog, plannedTracks = emptyList()),
        )

        val authoritative = enrichAuthoritativePlaybackSubtitleChoices(
            catalogTracks = catalog,
            plannedTracks = listOf(PlayerSubtitleInfo(index = 1, url = "/signs.vtt")),
        )
        assertEquals(listOf(1), authoritative.map(PlayerSubtitleInfo::index))
        assertEquals("Signs", authoritative.single().catalogLabel)
    }
}
