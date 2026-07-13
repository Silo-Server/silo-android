package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class MobileSubtitleAutoSelectionTest {

    @Test
    fun autoSubtitlePreferenceDemotesClosedCaptionTitledTracksWhenPlainDialogueExists() {
        val subtitles = listOf(
            subtitle(index = 4, label = "English (CC)", language = "en"),
            subtitle(index = 7, label = "English", language = "en"),
        )

        assertEquals(
            MobileSubtitleAutoSelection.Select(1),
            resolveMobileAutoSubtitleSelection(
                audioTracks = listOf(audio(index = 0, language = "ja")),
                selectedAudioIndex = 0,
                subtitles = subtitles,
                preferredLanguage = "en",
                subtitleMode = "auto",
                showForcedSubtitles = true,
            ),
        )
    }

    @Test
    fun autoSubtitlePreferenceKeepsClosedCaptionTrackWhenItIsOnlyLanguageMatch() {
        val subtitles = listOf(
            subtitle(index = 4, label = "English (CC)", language = "en"),
        )

        assertEquals(
            MobileSubtitleAutoSelection.Select(0),
            resolveMobileAutoSubtitleSelection(
                audioTracks = listOf(audio(index = 0, language = "ja")),
                selectedAudioIndex = 0,
                subtitles = subtitles,
                preferredLanguage = "en",
                subtitleMode = "auto",
                showForcedSubtitles = true,
            ),
        )
    }

    @Test
    fun autoSubtitlePreferenceDoesNotTreatCcInsideWordsAsClosedCaption() {
        val subtitles = listOf(
            subtitle(index = 4, label = "Soccer Cut", language = "en"),
            subtitle(index = 7, label = "English", language = "en"),
        )

        assertEquals(
            MobileSubtitleAutoSelection.Select(0),
            resolveMobileAutoSubtitleSelection(
                audioTracks = listOf(audio(index = 0, language = "ja")),
                selectedAudioIndex = 0,
                subtitles = subtitles,
                preferredLanguage = "en",
                subtitleMode = "auto",
                showForcedSubtitles = true,
            ),
        )
    }

    @Test
    fun autoSubtitleResolverDisablesWhenAudioAlreadyMatchesPreferredLanguage() {
        assertEquals(
            MobileSubtitleAutoSelection.Disable,
            resolveMobileAutoSubtitleSelection(
                audioTracks = listOf(audio(index = 2, language = "eng")),
                selectedAudioIndex = 2,
                subtitles = listOf(subtitle(index = 1, label = "English", language = "en")),
                preferredLanguage = "en",
                subtitleMode = "auto",
                showForcedSubtitles = true,
            ),
        )
    }

    @Test
    fun autoSubtitleResolverSelectsForcedTrackWhenAudioAlreadyMatchesPreferredLanguage() {
        val subtitles = listOf(
            subtitle(index = 1, label = "English", language = "en"),
            subtitle(index = 2, label = "English Forced", language = "en", forced = true),
        )

        assertEquals(
            MobileSubtitleAutoSelection.Select(1),
            resolveMobileAutoSubtitleSelection(
                audioTracks = listOf(audio(index = 2, language = "eng")),
                selectedAudioIndex = 2,
                subtitles = subtitles,
                preferredLanguage = "en",
                subtitleMode = "auto",
                showForcedSubtitles = true,
            ),
        )
    }

    @Test
    fun alwaysModeSelectsPreferredLanguageEvenWhenAudioAlreadyMatches() {
        assertEquals(
            MobileSubtitleAutoSelection.Select(0),
            resolveMobileAutoSubtitleSelection(
                audioTracks = listOf(audio(index = 0, language = "en")),
                selectedAudioIndex = 0,
                subtitles = listOf(subtitle(index = 1, label = "English", language = "en")),
                preferredLanguage = "en",
                subtitleMode = "always",
                showForcedSubtitles = false,
            ),
        )
    }

    @Test
    fun initialDetailOrdinalTranslatesFromCatalogToMountedList() {
        // Catalog order: [Signs (forced), English] with demux indices 3/5;
        // mounted order is reversed — the detail pick must land by match,
        // not by raw ordinal.
        val catalog = listOf(
            catalogSubtitle(index = 3, title = "Signs", forced = true),
            catalogSubtitle(index = 5, title = "English"),
        )
        val mounted = listOf(
            subtitle(index = 0, label = "English", language = "en"),
            subtitle(index = 1, label = "Signs", language = "en", forced = true),
        )

        assertEquals(1, resolveInitialMobileSubtitleOrdinal(0, catalog, mounted))
        assertEquals(0, resolveInitialMobileSubtitleOrdinal(1, catalog, mounted))
        assertEquals(-1, resolveInitialMobileSubtitleOrdinal(-1, catalog, mounted))
        // Unmatched pick falls back to the raw ordinal when mountable.
        assertEquals(
            1,
            resolveInitialMobileSubtitleOrdinal(
                1,
                listOf(catalog[0], catalogSubtitle(index = 9, title = "Nederlands", language = "nl")),
                mounted,
            ),
        )
    }

    @Test
    fun initialEmbeddedBitmapOrdinalUsesStableServerIndexWithoutSyntheticMetadata() {
        val mounted = listOf(
            PlayerSubtitleInfo(
                index = 2,
                codec = "dvd_subtitle",
                source = "embedded",
                url = "",
            ),
        )

        assertEquals(
            0,
            resolveInitialMobileSubtitleOrdinal(
                requestedOrdinal = 2,
                catalogTracks = listOf(
                    catalogSubtitle(index = 0, title = "English ASS", codec = "ass"),
                    catalogSubtitle(index = 1, title = "Signs", codec = "ass"),
                    catalogSubtitle(index = 2, title = "DVD VobSub", codec = "dvd_subtitle"),
                ),
                mountedSubtitles = mounted,
            ),
        )
    }

    private fun catalogSubtitle(
        index: Int,
        title: String,
        language: String? = "en",
        forced: Boolean = false,
        codec: String = "srt",
    ): org.siloserver.silo.model.catalog.SubtitleTrack =
        org.siloserver.silo.model.catalog.SubtitleTrack(
            index = index,
            language = language,
            codec = codec,
            title = title,
            forced = forced,
        )

    private fun audio(
        index: Int,
        language: String?,
    ): AudioTrack = AudioTrack(index = index, language = language)

    private fun subtitle(
        index: Int,
        label: String,
        language: String?,
        forced: Boolean = false,
        codec: String = "srt",
    ): PlayerSubtitleInfo = PlayerSubtitleInfo(
        index = index,
        language = language,
        codec = codec,
        label = label,
        source = null,
        forced = forced,
        url = "/stream/subtitles/$index.vtt",
    )
}
