package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.model.audiobook.AudiobookMetadata
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.catalog.VideoTrack
import org.siloserver.silo.model.ebook.MediaPerson
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class TvDetailMetadataTest {
    @Test
    fun episodeFactsPutAirDateBeforeRuntime() {
        val detail = ItemDetail(
            contentId = "e1",
            type = "episode",
            title = "Episode",
            year = 2026,
            airDate = "2026-03-30T00:00:00Z",
            runtime = 52,
        )

        assertEquals(
            listOf(
                TvHeroFactToken.TextToken("Mar 30, 2026"),
                TvHeroFactToken.TextToken("52 min"),
            ),
            TvDetailMetadata.factsLine(detail, zone = ZoneId.of("UTC")),
        )
    }

    @Test
    fun airDateRendersInViewerZoneLikeTvOs() {
        // tvOS parses the timestamp as a UTC instant and formats the LOCAL
        // calendar date, so a midnight-UTC air date shows the previous day for
        // viewers west of UTC. The Android hero mirrors that.
        assertEquals(
            "Mar 29, 2026",
            TvDetailMetadata.abbreviatedDate(
                "2026-03-30T00:00:00Z",
                zone = ZoneId.of("America/Los_Angeles"),
            ),
        )
        // Bare full dates are parsed at UTC midnight too (Apple's
        // `.withFullDate` ISO8601 parser defaults to GMT).
        assertEquals(
            "Mar 29, 2026",
            TvDetailMetadata.abbreviatedDate(
                "2026-03-30",
                zone = ZoneId.of("America/Los_Angeles"),
            ),
        )
    }

    @Test
    fun audiobookSourceTokensIncludeTypePublisherAndNarrator() {
        val detail = ItemDetail(
            contentId = "a1",
            type = "audiobook",
            title = "Audio",
            audiobook = AudiobookMetadata(
                publisher = "Silo Press",
                narrators = listOf(MediaPerson(name = "Nia Narrator")),
            ),
        )

        assertEquals(
            listOf("Audiobook", "Silo Press", "Narrated by Nia Narrator"),
            TvDetailMetadata.sourceTokens(detail),
        )
    }

    @Test
    fun movieDetailUsesNormalizedEditorialMetadataAndOmitsAllTechnicalTokens() {
        val detail = ItemDetail(
            contentId = "movie-detail",
            type = "movie",
            title = "Arrival",
            year = 2016,
            runtime = 116,
            ratingImdb = 7.9,
            genres = listOf(" Science Fiction ", "", "Drama", "Drama", "Thriller"),
            contentRating = " pg-13 ",
            versions = listOf(
                FileVersion(
                    fileId = 2160,
                    resolution = "2160p",
                    hdr = true,
                    videoTracks = listOf(
                        VideoTrack(codec = "hevc", dolbyVision = "Profile 8", hdr = true),
                    ),
                    audioTracks = listOf(
                        AudioTrack(channelLayout = "Atmos", channels = 8, isDefault = true),
                    ),
                    subtitleTracks = listOf(SubtitleTrack(language = "en")),
                ),
            ),
        )

        assertEquals(
            listOf("Movie", "Science Fiction", "Drama"),
            TvDetailMetadata.sourceTokens(detail),
        )
        assertEquals("PG-13", TvDetailMetadata.ratingChip(detail))
        assertEquals(
            listOf(
                TvHeroFactToken.TextToken("2016"),
                TvHeroFactToken.TextToken("1h 56m"),
                TvHeroFactToken.TextToken("★ 7.9"),
            ),
            TvDetailMetadata.factsLine(detail),
        )
    }

    @Test
    fun episodeDetailAllowsTwoNormalizedGenresAndKeepsEditorialFactOrder() {
        val detail = ItemDetail(
            contentId = "episode-detail",
            type = "episode",
            title = "Long, Long Time",
            seasonNumber = 1,
            episodeNumber = 3,
            airDate = "2026-03-30T00:00:00Z",
            runtime = 76,
            ratingImdb = 8.6,
            genres = listOf("Drama", " Horror ", "Drama"),
            contentRating = " tv-ma ",
        )

        assertEquals(
            listOf("Season 1 · Episode 3", "Drama", "Horror"),
            TvDetailMetadata.sourceTokens(detail),
        )
        assertEquals("TV-MA", TvDetailMetadata.ratingChip(detail))
        assertEquals(
            listOf(
                TvHeroFactToken.TextToken("Mar 30, 2026"),
                TvHeroFactToken.TextToken("1h 16m"),
                TvHeroFactToken.TextToken("★ 8.6"),
            ),
            TvDetailMetadata.factsLine(detail, zone = ZoneId.of("UTC")),
        )
    }

    @Test
    fun invalidDetailRatingsAndBlankClassificationsAreOmitted() {
        listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.0,
            -1.0,
            10.1,
        ).forEach { invalid ->
            val detail = ItemDetail(
                contentId = "invalid-$invalid",
                type = "movie",
                title = "Invalid",
                ratingImdb = invalid,
                contentRating = "  ",
            )

            assertEquals(emptyList(), TvDetailMetadata.factsLine(detail))
            assertEquals(null, TvDetailMetadata.ratingChip(detail))
        }
    }
}
