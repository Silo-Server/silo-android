package org.siloserver.silo.common.i18n

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.audiobook.AudiobookChapter
import org.siloserver.silo.audiobook.AudiobookChapters
import org.siloserver.silo.audiobook.audiobookChapterLabel
import org.siloserver.silo.catalog.filter.CatalogFacet
import org.siloserver.silo.catalog.filter.facetOptionPairs
import org.siloserver.silo.model.book.BookFormat
import org.siloserver.silo.model.calendar.calendarBadgeLabel
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogBoxSet
import org.siloserver.silo.model.catalog.EpisodeCardCaption
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.model.catalog.boxSetFilmCount
import org.siloserver.silo.model.catalog.cardCaption
import org.siloserver.silo.model.catalog.extraKindLabel
import org.siloserver.silo.model.catalog.heroFacts
import org.siloserver.silo.model.catalog.personMetadataBadges
import org.siloserver.silo.model.catalog.personWorksCountLabel
import org.siloserver.silo.model.catalog.playLabel
import org.siloserver.silo.model.catalog.yearsLabel
import org.siloserver.silo.model.settings.CardPosterSize
import org.siloserver.silo.model.settings.CellularQuality
import org.siloserver.silo.model.settings.LanguageOptions
import org.siloserver.silo.model.settings.QualityChoices
import org.siloserver.silo.model.settings.QualityPresets
import org.siloserver.silo.model.settings.SeekIntervals
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.overlays.OverlayId
import org.siloserver.silo.overlays.OverlayRegistry
import org.siloserver.silo.viewmodel.CollectionSection
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The shared module's settings, catalog and overlay text in English: the words
 * each screen printed before it came from the catalog.
 */
@RunWith(RobolectricTestRunner::class)
class SharedPresentationTextTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val english: Resources = app.resources
    private val arabic = resourcesIn("ar")

    private fun resourcesIn(tag: String): Resources = app.createConfigurationContext(
        Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
    ).resources

    private fun UiText?.en(): String? = this?.text(english)
    private fun List<UiText>.en(): List<String> = map { it.text(english) }

    @Test
    fun qualityPickersAndTheirFooters() {
        assertEquals(listOf("Auto", "4K", "1080p", "720p", "480p"), QualityChoices.RESOLUTIONS.map { it.label }.en())
        assertEquals("Auto", QualityChoices.resolutionLabel("original").en())
        assertEquals(
            "Plays the original file whenever your device and connection can handle it.",
            QualityChoices.resolutionDescription("auto").en(),
        )
        assertEquals(
            "Plays the original file up to 1080p. Larger files are converted.",
            QualityChoices.resolutionDescription("1080p").en(),
        )
        assertEquals(
            "Plays the original file up to 4K. Larger files are converted.",
            QualityChoices.resolutionDescription("2160p").en(),
        )
        assertEquals(
            "Plays the original file up to 720p. Larger files are converted. " +
                "Files above this bitrate are converted to fit.",
            QualityChoices.description("720p", 4_000).en(),
        )
        assertEquals(
            listOf(
                "No limit", "200 Mbps", "100 Mbps", "60 Mbps", "40 Mbps", "20 Mbps", "15 Mbps",
                "10 Mbps", "6 Mbps", "4 Mbps", "2 Mbps", "1.5 Mbps",
            ),
            QualityChoices.bitrateChoices(null).map { it.label }.en(),
        )
        assertEquals("8.5 Mbps", QualityChoices.bitrateLabel(8_500).en())
        assertEquals("1520 kbps", QualityChoices.bitrateLabel(1_520).en())
        assertEquals("500 kbps", QualityChoices.bitrateLabel(500).en())
        assertEquals("1234 kbps", QualityChoices.bitrateLabel(1_234).en())
        assertEquals("No limit", QualityChoices.bitrateLabel(null).en())
        assertEquals("Auto", QualityChoices.summary("auto", null).en())
        assertEquals("1080p, up to 6 Mbps", QualityChoices.summary("1080p", 6_000).en())
    }

    @Test
    fun cellularPresets() {
        assertEquals(
            listOf("Auto", "4K", "1080p High", "1080p", "1080p Low", "720p High", "720p", "480p"),
            QualityPresets.ALL.map { it.label }.en(),
        )
        assertEquals(
            "720p",
            CellularQuality.label(CellularQuality.DEFAULT_RESOLUTION, CellularQuality.DEFAULT_MAX_BITRATE_KBPS).en(),
        )
        assertEquals("1080p, no limit", CellularQuality.label("1080p", null).en())
        assertEquals("720p, up to 8 Mbps", CellularQuality.label("720p", 8_000).en())
        assertEquals("Used on mobile data and metered networks, such as a phone hotspot.", CellularQuality.FOOTER.en())
    }

    @Test
    fun skipIntervalsCardsAndLanguages() {
        assertEquals(listOf("5 seconds", "10 seconds", "90 seconds"), listOf(5, 10, 90).map { SeekIntervals.label(it) }.en())
        assertEquals(listOf("Compact", "Standard", "Large"), CardPosterSize.entries.map { it.displayName }.en())
        assertEquals("No preference", LanguageOptions.options(SettingKeys.PLAYBACK_AUDIO_LANGUAGE).first().second.en())
        assertEquals("None", LanguageOptions.label(null, SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE).en())
        assertEquals("Library default", LanguageOptions.label("", SettingKeys.CATALOG_METADATA_LANGUAGE).en())
        assertEquals("Match device", LanguageOptions.label(null, SettingKeys.UI_APP_LANGUAGE).en())
        assertEquals("Portuguese (Brazil)", LanguageOptions.label("pt-BR", SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE).en())
        // A tag the platform can't name keeps its spelling in a picker; a badge uppercases it.
        assertEquals("qaa", LanguageOptions.label("qaa", SettingKeys.PLAYBACK_AUDIO_LANGUAGE).en())
        assertEquals("QAA", OverlayRegistry.def(OverlayId.OriginalLanguage)!!.getValue(OverlayData(originalLanguage = "qaa")).en())
    }

    @Test
    fun libraryFilterValues() {
        assertEquals(
            listOf("2020s", "2010s", "2000s", "1990s", "1980s", "1970s", "1960s"),
            facetOptionPairs(CatalogFacet.Decade, null).map { it.second }.en(),
        )
        assertEquals(
            listOf("Unwatched", "In Progress", "Watched", "Favorited", "Watchlist"),
            facetOptionPairs(CatalogFacet.WatchStatus, null).map { it.second }.en(),
        )
        assertEquals(listOf("HDR", "Dolby Vision"), facetOptionPairs(CatalogFacet.DynamicRange, null).map { it.second }.en())
    }

    @Test
    fun personPage() {
        val living = Person(id = 1, name = "Andy Weir", birthDate = "1972-06-16", birthplace = "Davis, California")
        assertEquals(
            listOf("Born Jun 16, 1972", "54 years old", "Davis, California"),
            personMetadataBadges(living, todayIso = "2026-06-19").en(),
        )
        val died = Person(id = 2, name = "Example Person", birthDate = "1950-01-10", deathDate = "2020-01-09")
        assertEquals(
            listOf("Born Jan 10, 1950", "Died Jan 9, 2020 (age 69)"),
            personMetadataBadges(died, todayIso = "2026-06-19").en(),
        )
        assertEquals(listOf("Died Jan 9, 2020"), personMetadataBadges(died.copy(birthDate = null)).en())
        // The plural fix: one year old.
        assertEquals("1 year old", personMetadataBadges(Person(id = 3, name = "B", birthDate = "2025-01-01"), "2026-02-01")[1].en())
        assertEquals(listOf("Born 1980"), personMetadataBadges(Person(id = 4, name = "C", birthDate = "1980")).en())
        assertEquals("12 titles", personWorksCountLabel(total = 12, loaded = 12, hasMore = false).en())
        assertEquals("1 title", personWorksCountLabel(total = 1, loaded = 1, hasMore = false).en())
        assertEquals("60 of 120 titles", personWorksCountLabel(total = 120, loaded = 60, hasMore = true).en())
        assertEquals("1060 of 1200 titles", personWorksCountLabel(total = 1_200, loaded = 1_060, hasMore = true).en())
        assertEquals("60+ titles", personWorksCountLabel(total = 0, loaded = 60, hasMore = true).en())
        assertEquals("3 titles", personWorksCountLabel(total = 0, loaded = 3, hasMore = false).en())
    }

    @Test
    fun boxSets() {
        val boxSet = CatalogBoxSet("1241", "3", "Harry Potter Collection", movieCount = 8, firstYear = 2001, lastYear = 2011)
        assertEquals("2001 – 2011", boxSet.yearsLabel().en())
        assertEquals("2001", boxSet.copy(lastYear = 2001).yearsLabel().en())
        assertEquals("2001 – 2011", boxSet.cardCaption().en())
        assertEquals("8 films", boxSet.copy(firstYear = null, lastYear = null).cardCaption().en())
        assertEquals("5 / 8 watched", boxSet.copy(playedCount = 5).cardCaption().en())
        assertEquals("1 film", boxSetFilmCount(1).en())
        assertEquals(listOf("8 films", "2001 – 2011", "5 of 8 watched"), boxSet.copy(playedCount = 5).heroFacts().en())
        val next = BrowseItem(contentId = "hp2", type = "movie", title = "Chamber of Secrets", positionSeconds = 60.0)
        assertEquals("Continue · Chamber of Secrets", boxSet.playLabel(next).en())
        assertEquals("Play Chamber of Secrets", boxSet.playLabel(next.copy(positionSeconds = null)).en())
    }

    @Test
    fun episodeCaptionsAndTrailers() {
        val episode = EpisodeListItem("e", seasonNumber = 1, episodeNumber = 2, title = " Pilot ")
        assertEquals("S01E02 · Pilot", EpisodeCardCaption.title(episode).en())
        assertEquals("S01E02", EpisodeCardCaption.title(episode.copy(title = null)).en())
        assertEquals("S12E104", EpisodeCardCaption.title(episode.copy(seasonNumber = 12, episodeNumber = 104, title = "")).en())
        assertEquals("Feb 18, 2026  ·  52m", EpisodeCardCaption.metadata("Feb 18, 2026", 52).en())
        assertEquals("1h 5m", EpisodeCardCaption.metadata(null, 65).en())
        assertEquals("1h 0m", EpisodeCardCaption.metadata(" ", 60).en())
        assertEquals("Feb 18, 2026", EpisodeCardCaption.metadata("Feb 18, 2026", 0).en())
        assertNull(EpisodeCardCaption.metadata(null, 0))
        assertEquals(
            listOf("Trailer", "Teaser", "Featurette", "Behind the Scenes", "Deleted Scene", "Interview", "Blooper Reel", "Extra"),
            listOf("trailer", "TEASER", "featurette", "behind-the-scenes", "deleted_scene", "interview", "blooper_reel", " _ ")
                .map { extraKindLabel(it) }.en(),
        )
    }

    @Test
    fun calendarChaptersFormatsAndCollections() {
        assertEquals(
            listOf("PREMIERE", "NEW SEASON", "FINALE", "DIGITAL", "PHYSICAL"),
            listOf("series_premiere", "season_premiere", "finale", "digital", "physical").mapNotNull(::calendarBadgeLabel).en(),
        )
        val chapters = listOf(AudiobookChapter(0.0, 100.0), AudiobookChapter(100.0, 200.0), AudiobookChapter(200.0, 300.0))
        assertEquals("Chapter 2 of 3", AudiobookChapters.countLabel(chapters, 150.0).en())
        assertEquals("Chapter 8", audiobookChapterLabel(7, "007").en())
        assertEquals("Prologue", audiobookChapterLabel(0, " Prologue ").en())
        assertEquals(
            listOf(
                "EPUB", "PDF", "Comic (CBZ)", "Comic (CBR)", "MOBI", "Kindle (AZW)", "Kindle (AZW3)",
                "Plain text", "Markdown", "FictionBook", "FictionBook ZIP", "Unknown",
            ),
            BookFormat.entries.map { it.displayName }.en(),
        )
        assertEquals("Ungrouped", CollectionSection(groupId = null, name = "Ungrouped", collections = emptyList()).title.en())
        assertEquals("Favourites", CollectionSection(groupId = "g", name = "Favourites", collections = emptyList()).title.en())
    }

    @Test
    fun overlayBadges() {
        fun value(id: OverlayId, data: OverlayData) = OverlayRegistry.def(id)!!.getValue(data).en()
        assertEquals("2h 22m", value(OverlayId.Runtime, OverlayData(runtime = 142)))
        assertEquals("45m", value(OverlayId.Runtime, OverlayData(runtime = 45)))
        assertEquals("Returning", value(OverlayId.ShowStatus, OverlayData(showStatus = "returning series")))
        assertEquals("Ended", value(OverlayId.ShowStatus, OverlayData(showStatus = "ended")))
        assertEquals("Cancelled", value(OverlayId.ShowStatus, OverlayData(showStatus = "canceled")))
        assertEquals("Upcoming", value(OverlayId.ShowStatus, OverlayData(showStatus = "planned")))
        assertEquals("Limited Series", value(OverlayId.ShowStatus, OverlayData(showStatus = " Limited Series ")))
        assertEquals("Multi-Audio", value(OverlayId.MultiAudio, OverlayData(multiAudio = true)))
        assertEquals("CC", value(OverlayId.MultiSub, OverlayData(multiSub = true)))
        assertEquals("Certified Fresh", value(OverlayId.RtCertifiedFresh, OverlayData(rtCertifiedFresh = true)))
        assertEquals("English", value(OverlayId.OriginalLanguage, OverlayData(originalLanguage = "en")))
        assertEquals("Japanese", value(OverlayId.OriginalLanguage, OverlayData(originalLanguage = " ja ")))
        assertNull(value(OverlayId.OriginalLanguage, OverlayData(originalLanguage = " ")))
        assertEquals("2014", value(OverlayId.Year, OverlayData(year = 2014)))
    }

    @Test
    fun numbersAreUngrouped() {
        assertEquals("1234", UiText.Number(1234).text(english))
        assertEquals("02", UiText.Number(2, minIntegerDigits = 2).text(english))
        assertEquals("1.5", UiText.Number(1.5, maxFractionDigits = 1).text(english))
        assertEquals("40", UiText.Number(40.0, maxFractionDigits = 1).text(english))
    }

    @Test
    fun aLanguageWithoutACatalogFormatsAsTheEnglishItsStringsFallBackTo() {
        // Only English ships in these tests: an Arabic or German phone shows English, formatted as before.
        assertEquals("1234", UiText.Number(1234).text(arabic))
        assertEquals("1.5 Mbps", QualityChoices.bitrateLabel(1_500).text(resourcesIn("de-DE")))
        assertEquals("Jun 16, 1972", UiText.CalendarDate(1972, 6, 16).text(resourcesIn("de-DE")))
        assertEquals("Japanese", UiText.LanguageName("ja").text(resourcesIn("de-DE")))
    }
}
