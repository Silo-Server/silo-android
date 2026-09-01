package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.model.audiobook.AudiobookMetadata
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.ItemVideo
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.catalog.VideoTrack
import org.siloserver.silo.model.ebook.MediaPerson
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class TvDetailMetadataTest {
    @Test
    fun seriesEpisodeEditorialMatchesTvOsHierarchy() {
        val episode = EpisodeListItem(
            contentId = "e1",
            seasonNumber = 3,
            episodeNumber = 1,
            title = "Smells Like Mean Spirit",
            airDate = "2026-03-30T00:00:00Z",
            runtime = 52,
        )

        assertEquals(
            listOf("Season 3", "Episode 1"),
            TvDetailMetadata.seriesEpisodeSourceTokens(episode),
        )
        assertEquals(
            listOf(
                TvHeroFactToken.TextToken("Mar 30, 2026"),
                TvHeroFactToken.TextToken("52 min"),
            ),
            TvDetailMetadata.seriesEpisodeFactsLine(episode, zone = ZoneId.of("UTC")),
        )
    }

    @Test
    fun seriesSpecialEditorialUsesSpecialsLabel() {
        val episode = EpisodeListItem(
            contentId = "special-1",
            seasonNumber = 0,
            episodeNumber = 1,
        )

        assertEquals(
            listOf("Specials", "Episode 1"),
            TvDetailMetadata.seriesEpisodeSourceTokens(episode),
        )
    }

    @Test
    fun seriesEditorialOmitsUnknownEpisodeNumber() {
        val episode = EpisodeListItem(
            contentId = "special",
            seasonNumber = 0,
            episodeNumber = 0,
        )

        assertEquals(
            listOf("Specials"),
            TvDetailMetadata.seriesEpisodeSourceTokens(episode),
        )
    }

    @Test
    fun trailerEntriesDeduplicateProviderKeysUsedByTheRail() {
        val duplicate = ItemVideo(kind = "trailer", site = "youtube", siteKey = "abc")
        val detail = ItemDetail(
            contentId = "m1",
            type = "movie",
            title = "Movie",
            videos = listOf(duplicate, duplicate.copy(name = "Duplicate")),
        )

        assertEquals(
            listOf("remote:youtube:abc"),
            tvDetailTrailerEntries(detail).map { it.key },
        )
    }

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
    fun factsLineUsesPreferredQualityForVersionBadges() {
        val detail = ItemDetail(
            contentId = "m1",
            type = "movie",
            title = "Movie",
            versions = listOf(
                FileVersion(fileId = 1080, resolution = "1080p"),
                FileVersion(fileId = 2160, resolution = "2160p", hdr = true),
            ),
        )

        assertEquals(
            listOf(TvHeroFactToken.Chip("HD")),
            TvDetailMetadata.factsLine(detail, preferredQuality = "1080p"),
        )
    }

    @Test
    fun factsLineUsesSelectedFileIdForVersionBadges() {
        val detail = ItemDetail(
            contentId = "m1",
            type = "movie",
            title = "Movie",
            versions = listOf(
                FileVersion(
                    fileId = 1080,
                    resolution = "1080p",
                    audioTracks = listOf(AudioTrack(channels = 2, isDefault = true)),
                ),
                FileVersion(
                    fileId = 2160,
                    resolution = "2160p",
                    hdr = true,
                    audioTracks = listOf(AudioTrack(channels = 6, isDefault = true)),
                    subtitleTracks = listOf(SubtitleTrack(language = "en")),
                ),
            ),
        )

        assertEquals(
            listOf(
                TvHeroFactToken.Chip("4K"),
                TvHeroFactToken.Chip("HDR"),
                TvHeroFactToken.Chip("5.1"),
                TvHeroFactToken.Chip("CC"),
            ),
            TvDetailMetadata.factsLine(
                detail = detail,
                preferredQuality = "1080p",
                selectedFileId = 2160,
            ),
        )
    }

    @Test
    fun factsLineNamesDolbyVisionFromVideoTrackMetadata() {
        val detail = ItemDetail(
            contentId = "m1",
            type = "movie",
            title = "Movie",
            versions = listOf(
                FileVersion(
                    fileId = 2160,
                    resolution = "2160p",
                    hdr = true,
                    videoTracks = listOf(
                        VideoTrack(codec = "hevc", dolbyVision = "Profile 8", hdr = true),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                TvHeroFactToken.Chip("4K"),
                TvHeroFactToken.Chip("DOLBY VISION"),
            ),
            TvDetailMetadata.factsLine(detail),
        )
    }
}
