package org.siloserver.silo.overlays

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.EpisodeFile
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.OverlaySummary
import org.siloserver.silo.model.section.SectionItem
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayDataExtractorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun browseItem_withResolution_setsResolution_othersNull() {
        val item = BrowseItem(
            contentId = "movie-1",
            type = "movie",
            title = "Test",
            year = 1999,
            contentRating = "R",
            ratingImdb = 8.7,
            overlaySummary = OverlaySummary(
                resolution = "2160p",
                audio = "Atmos",
                releaseType = "REMUX",
            ),
        )
        val data = OverlayDataExtractor.fromBrowseItem(item)
        assertEquals("2160p", data.resolution)
        assertEquals("Atmos", data.audio)
        assertEquals("REMUX", data.releaseType)
        assertEquals(1999, data.year)
        assertEquals("R", data.contentRating)
        assertEquals(8.7, data.ratingImdb)
        // Fields absent from this payload stay null so badges hide.
        assertNull(data.hdr)
        assertNull(data.videoCodec)
        assertNull(data.runtime)
        assertNull(data.studio)
        assertNull(data.ratingTmdb)
    }

    @Test
    fun browseItem_missingSummary_techFieldsNull() {
        val item = BrowseItem(
            contentId = "movie-2",
            type = "movie",
            title = "No Summary",
            year = 0,
        )
        val data = OverlayDataExtractor.fromBrowseItem(item)
        assertNull(data.resolution)
        assertNull(data.audio)
        assertNull(data.releaseType)
        // year 0 → null so the Year badge hides.
        assertNull(data.year)
    }

    @Test
    fun itemDetail_setsRatingsAndMetadata() {
        val detail = ItemDetail(
            contentId = "movie-3",
            type = "movie",
            title = "Detailed",
            year = 2014,
            runtime = 169,
            contentRating = "PG-13",
            ratingImdb = 8.7,
            ratingTmdb = 8.4,
            ratingRtCritic = 73,
            ratingRtAudience = 86,
            studios = listOf("Paramount", "Legendary"),
            networks = emptyList(),
        )
        val data = OverlayDataExtractor.fromItemDetail(detail)
        assertEquals(8.7, data.ratingImdb)
        assertEquals(8.4, data.ratingTmdb)
        assertEquals(73, data.ratingRtCritic)
        assertEquals(86, data.ratingRtAudience)
        assertEquals("PG-13", data.contentRating)
        assertEquals(2014, data.year)
        assertEquals(169, data.runtime)
        assertEquals("Paramount", data.studio)
        assertNull(data.network)
        // Tech subset is not derived from detail alone.
        assertNull(data.resolution)
    }

    @Test
    fun merge_overlaysTechOntoDetailData() {
        val detail = ItemDetail(
            contentId = "movie-4",
            type = "movie",
            title = "Merge",
            year = 2010,
            ratingImdb = 8.8,
        )
        val base = OverlayDataExtractor.fromItemDetail(detail)
        val merged = OverlayDataExtractor.merge(
            base,
            OverlaySummary(resolution = "1080p", releaseType = "BluRay"),
        )
        assertEquals("1080p", merged.resolution)
        assertEquals("BluRay", merged.releaseType)
        // Detail-derived fields survive the merge.
        assertEquals(8.8, merged.ratingImdb)
        assertEquals(2010, merged.year)
    }

    @Test
    fun fromSummary_null_returnsEmpty() {
        val data = OverlayDataExtractor.fromSummary(null)
        assertEquals(OverlayData(), data)
    }

    @Test
    fun fromSummary_carriesEveryExpandedField() {
        val summary = OverlaySummary(
            resolution = "2160p",
            hdr = "DV HDR10",
            audio = "Atmos",
            audioChannels = "7.1",
            videoCodec = "H.265",
            container = "MKV",
            aspectRatio = "2.39:1",
            releaseType = "REMUX",
            edition = "Director's Cut",
            multiAudio = true,
            multiSub = true,
        )
        val data = OverlayDataExtractor.fromSummary(summary)
        assertEquals("2160p", data.resolution)
        assertEquals("DV HDR10", data.hdr)
        assertEquals("Atmos", data.audio)
        assertEquals("7.1", data.audioChannels)
        assertEquals("H.265", data.videoCodec)
        assertEquals("MKV", data.container)
        assertEquals("2.39:1", data.aspectRatio)
        assertEquals("REMUX", data.releaseType)
        assertEquals("Director's Cut", data.edition)
        assertEquals(true, data.multiAudio)
        assertEquals(true, data.multiSub)
    }

    @Test
    fun browseItem_carriesExpandedSummaryFields() {
        val item = BrowseItem(
            contentId = "movie-hdr",
            type = "movie",
            title = "HDR",
            year = 2014,
            ratingImdb = 8.6,
            overlaySummary = OverlaySummary(
                resolution = "2160p",
                hdr = "DV HDR10",
                videoCodec = "H.265",
                container = "MKV",
                multiSub = true,
            ),
        )
        val data = OverlayDataExtractor.fromBrowseItem(item)
        // The combined Resolution+HDR badge ("4K DV") relies on hdr surviving.
        assertEquals("DV HDR10", data.hdr)
        assertEquals("H.265", data.videoCodec)
        assertEquals("MKV", data.container)
        assertEquals(true, data.multiSub)
        assertEquals(8.6, data.ratingImdb)
        assertEquals(2014, data.year)
    }

    @Test
    fun browseItem_decodesAndCarriesAppleOverlayMetadataFields() {
        val item = json.decodeFromString(
            BrowseItem.serializer(),
            """
                {
                  "content_id": "movie-expanded",
                  "type": "movie",
                  "title": "Expanded",
                  "year": 2014,
                  "rating_imdb": 8.6,
                  "rating_tmdb": 8.4,
                  "rating_rt_critic": 96,
                  "rating_rt_audience": 89,
                  "content_rating": "PG-13",
                  "runtime": 169,
                  "original_language": "en",
                  "studios": ["Paramount", "Legendary"],
                  "networks": ["HBO"],
                  "show_status": "Ended"
                }
            """.trimIndent(),
        )

        val data = OverlayDataExtractor.fromBrowseItem(item)

        assertEquals(8.6, data.ratingImdb)
        assertEquals(8.4, data.ratingTmdb)
        assertEquals(96, data.ratingRtCritic)
        assertEquals(89, data.ratingRtAudience)
        assertEquals("PG-13", data.contentRating)
        assertEquals(2014, data.year)
        assertEquals(169, data.runtime)
        assertEquals("en", data.originalLanguage)
        assertEquals("Paramount", data.studio)
        assertEquals("HBO", data.network)
        assertEquals("Ended", data.showStatus)
    }

    @Test
    fun browseItem_carriesAvailability() {
        val item = json.decodeFromString(
            BrowseItem.serializer(),
            """
                {
                  "content_id": "series-1",
                  "type": "series",
                  "title": "Show",
                  "availability": { "have": 2, "total": 3 }
                }
            """.trimIndent(),
        )
        val data = OverlayDataExtractor.fromBrowseItem(item)
        assertEquals(2, data.availabilityHave)
        assertEquals(3, data.availabilityTotal)
    }

    @Test
    fun fromSectionItem_routesSummaryAndMetadata() {
        val item = SectionItem(
            contentId = "section-1",
            type = "movie",
            title = "Section",
            year = 2010,
            ratingImdb = 8.8,
            contentRating = "PG-13",
            overlaySummary = OverlaySummary(
                resolution = "1080p",
                audio = "EAC3",
                releaseType = "WEB-DL",
            ),
        )
        val data = OverlayDataExtractor.fromSectionItem(item)
        // Default-enabled tech badges must come through — not just IMDb/year.
        assertEquals("1080p", data.resolution)
        assertEquals("EAC3", data.audio)
        assertEquals("WEB-DL", data.releaseType)
        assertEquals(8.8, data.ratingImdb)
        assertEquals("PG-13", data.contentRating)
        assertEquals(2010, data.year)
    }

    @Test
    fun sectionItem_decodesAndCarriesAppleOverlayMetadataFields() {
        val item = json.decodeFromString(
            SectionItem.serializer(),
            """
                {
                  "content_id": "section-expanded",
                  "type": "show",
                  "title": "Expanded Show",
                  "year": 2008,
                  "rating_imdb": 9.4,
                  "rating_tmdb": 9.1,
                  "rating_rt_critic": 95,
                  "rating_rt_audience": 93,
                  "content_rating": "TV-MA",
                  "runtime": 49,
                  "original_language": "en",
                  "studios": ["Sony Pictures Television"],
                  "networks": ["AMC"],
                  "show_status": "Ended"
                }
            """.trimIndent(),
        )

        val data = OverlayDataExtractor.fromSectionItem(item)

        assertEquals(9.4, data.ratingImdb)
        assertEquals(9.1, data.ratingTmdb)
        assertEquals(95, data.ratingRtCritic)
        assertEquals(93, data.ratingRtAudience)
        assertEquals("TV-MA", data.contentRating)
        assertEquals(2008, data.year)
        assertEquals(49, data.runtime)
        assertEquals("en", data.originalLanguage)
        assertEquals("Sony Pictures Television", data.studio)
        assertEquals("AMC", data.network)
        assertEquals("Ended", data.showStatus)
    }

    @Test
    fun fromSectionItem_missingSummary_techNullButMetadataKept() {
        val item = SectionItem(
            contentId = "section-2",
            type = "movie",
            title = "No Summary",
            year = 0,
            ratingImdb = 7.1,
        )
        val data = OverlayDataExtractor.fromSectionItem(item)
        assertNull(data.resolution)
        assertNull(data.audio)
        assertNull(data.releaseType)
        assertNull(data.year)
        assertEquals(7.1, data.ratingImdb)
    }

    @Test
    fun itemDetail_decodesAndCarriesAppleOverlayLanguageAndShowStatus() {
        val detail = json.decodeFromString(
            ItemDetail.serializer(),
            """
                {
                  "content_id": "detail-expanded",
                  "type": "show",
                  "title": "Detailed Show",
                  "year": 2008,
                  "runtime": 49,
                  "rating_imdb": 9.4,
                  "rating_tmdb": 9.1,
                  "rating_rt_critic": 95,
                  "rating_rt_audience": 93,
                  "content_rating": "TV-MA",
                  "original_language": "en",
                  "studios": ["Sony Pictures Television"],
                  "networks": ["AMC"],
                  "show_status": "Ended"
                }
            """.trimIndent(),
        )

        val data = OverlayDataExtractor.fromItemDetail(detail)

        assertEquals("en", data.originalLanguage)
        assertEquals("Ended", data.showStatus)
        assertEquals("Sony Pictures Television", data.studio)
        assertEquals("AMC", data.network)
    }

    @Test
    fun itemDetail_decodesAndCarriesOverlaySummaryLikeApple() {
        val detail = json.decodeFromString(
            ItemDetail.serializer(),
            """
                {
                  "content_id": "detail-overlay-summary",
                  "type": "movie",
                  "title": "Detailed Movie",
                  "year": 2014,
                  "overlay_summary": {
                    "resolution": "2160p",
                    "hdr": "DV HDR10",
                    "audio": "Atmos",
                    "release_type": "REMUX"
                  }
                }
            """.trimIndent(),
        )

        val data = OverlayDataExtractor.fromItemDetail(detail)

        assertEquals("2160p", data.resolution)
        assertEquals("DV HDR10", data.hdr)
        assertEquals("Atmos", data.audio)
        assertEquals("REMUX", data.releaseType)
    }

    @Test
    fun fromEpisode_picksBestFileByResolution_notFirst() {
        val episode = EpisodeListItem(
            contentId = "ep-1",
            seasonNumber = 1,
            episodeNumber = 1,
            runtime = 50,
            files = listOf(
                EpisodeFile(fileId = 1, resolution = "720p", audioChannels = 2, container = "MP4"),
                EpisodeFile(fileId = 2, resolution = "2160p", audioChannels = 8, container = "MKV"),
            ),
        )
        val data = OverlayDataExtractor.fromEpisode(episode)
        // Best file is the 2160p MKV (higher resolution rank), not the first.
        assertEquals("2160p", data.resolution)
        assertEquals("MKV", data.container)
        assertEquals("7.1", data.audioChannels)
        assertEquals(50, data.runtime)
    }

    @Test
    fun fromEpisode_formatsChannelCountsToCanonicalLabels() {
        fun channels(count: Int): String? {
            val episode = EpisodeListItem(
                contentId = "ep-$count",
                seasonNumber = 1,
                episodeNumber = count,
                files = listOf(EpisodeFile(fileId = count, resolution = "1080p", audioChannels = count)),
            )
            return OverlayDataExtractor.fromEpisode(episode).audioChannels
        }
        assertEquals("Stereo", channels(2))
        assertEquals("5.1", channels(6))
        assertEquals("6.1", channels(7))
        assertEquals("7.1", channels(8))
        assertEquals("Mono", channels(1))
        assertEquals("4ch", channels(4))
        // Zero/unknown → null so the badge hides.
        assertNull(channels(0))
    }

    @Test
    fun merge_carriesAllExpandedSummaryFields() {
        val base = OverlayData(ratingImdb = 8.0, year = 2020)
        val merged = OverlayDataExtractor.merge(
            base,
            OverlaySummary(
                resolution = "2160p",
                hdr = "DV",
                audioChannels = "5.1",
                videoCodec = "AV1",
                container = "MKV",
                aspectRatio = "16:9",
                edition = "Extended",
                multiAudio = true,
                multiSub = true,
            ),
        )
        assertEquals("2160p", merged.resolution)
        assertEquals("DV", merged.hdr)
        assertEquals("5.1", merged.audioChannels)
        assertEquals("AV1", merged.videoCodec)
        assertEquals("MKV", merged.container)
        assertEquals("16:9", merged.aspectRatio)
        assertEquals("Extended", merged.edition)
        assertEquals(true, merged.multiAudio)
        assertEquals(true, merged.multiSub)
        // Base-only fields survive.
        assertEquals(8.0, merged.ratingImdb)
        assertEquals(2020, merged.year)
    }

    @Test
    fun resolutionHdrBadge_rendersFromExpandedSummary() {
        // End-to-end: server summary with hdr="DV HDR10" must let the combined
        // Resolution+HDR registry badge produce "4K DV" (matches Apple/web).
        val item = BrowseItem(
            contentId = "movie-dv",
            type = "movie",
            title = "DV",
            year = 2014,
            overlaySummary = OverlaySummary(resolution = "2160p", hdr = "DV HDR10"),
        )
        val data = OverlayDataExtractor.fromBrowseItem(item)
        val label = OverlayRegistry.def(OverlayId.ResolutionHdr)?.getValue?.invoke(data)
        assertEquals("4K DV", label)
        assertTrue(data.hdr == "DV HDR10")
    }
}
