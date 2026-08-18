package org.siloserver.silo.model.playback

import org.siloserver.silo.model.catalog.SubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The single auto-subtitle resolver, pinned to the TV detail row's semantics —
 * the behaviour the viewer sees and that QA signed off. These cases are ported
 * from `TvPlaybackFormattingTest`'s Auto-preview suite with identical
 * expectations, plus the Shield regression that motivated the extraction.
 */
class AutoSubtitleResolverTest {

    // --- the regression -------------------------------------------------

    @Test
    fun preferredLanguageAlwaysPicksTheExternalTextTrackOverAnEmbeddedBitmapOne() {
        // Shield, direct-play MKV: embedded PGS "English (SDH)" + an external
        // English SRT, preference English/Always. The detail row previewed the
        // SRT; the player, ranking only Media3's mounted tracks, started the
        // PGS. Over the full catalog the SRT wins — and its combined index is
        // what the start request can carry.
        val tracks = listOf(
            SubtitleTrack(index = 2, codec = "hdmv_pgs_subtitle", language = "eng", title = "English (SDH)"),
            SubtitleTrack(index = 0, codec = "srt", language = "eng", external = true),
        )

        val selected = resolveAutoSubtitle(
            candidates = catalogAutoSubtitleCandidates(tracks),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "always", showForced = true),
        ).selectedCandidate()

        // Externals occupy 0..n-1: the sidecar is combined index 0.
        assertEquals(0, selected?.selectionIndex)
        assertEquals("srt", selected?.codec)
    }

    @Test
    fun alwaysWithForcedEnabledStillPrefersTheFullDialogueTrack() {
        // Shield (Supergirl): three English SubRip streams — Forced, plain
        // (untitled), SDH — profile English/Always with "show forced" ON.
        // Forced is a separate setting for the subtitles-otherwise-off case;
        // it must not outrank the viewer's full-subtitle preference.
        val tracks = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "eng", title = "Forced", forced = true),
            SubtitleTrack(index = 1, codec = "srt", language = "eng"),
            SubtitleTrack(index = 2, codec = "srt", language = "eng", title = "SDH"),
        )

        for (mode in listOf("always", "auto")) {
            val selected = resolveAutoSubtitle(
                candidates = catalogAutoSubtitleCandidates(tracks),
                context = AutoSubtitleContext(
                    preferredLanguage = "en",
                    mode = mode,
                    showForced = true,
                    audioLanguage = "ja",
                ),
            ).selectedCandidate()
            assertEquals(1, selected?.selectionIndex, "mode=$mode")
        }
    }

    @Test
    fun forcedIsStillTheLastResortWhenTheLanguageHasNothingElse() {
        val tracks = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "eng", title = "Forced", forced = true),
        )
        val selected = resolveAutoSubtitle(
            candidates = catalogAutoSubtitleCandidates(tracks),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "always", showForced = true),
        ).selectedCandidate()
        assertEquals(0, selected?.selectionIndex)
    }

    @Test
    fun aBitmapTrackStillWinsWhenItIsTheOnlyCandidate() {
        // Bitmap stays deprioritised, never excluded.
        val rows = listOf(
            PlayerSubtitleInfo(index = 0, language = "eng", codec = "pgs", url = ""),
        )

        val selected = resolveAutoSubtitle(
            candidates = inventoryAutoSubtitleCandidates(rows),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "always", showForced = true),
        ).selectedCandidate()

        assertEquals(0, selected?.selectionIndex)
    }

    @Test
    fun theServerInventoryResolvesInCombinedSpace() {
        val rows = listOf(
            PlayerSubtitleInfo(index = 0, language = "fre", codec = "webvtt", url = "", catalogLabel = "French"),
            PlayerSubtitleInfo(index = 1, language = "eng", codec = "webvtt", url = "", catalogLabel = "English"),
        )

        val selected = resolveAutoSubtitle(
            candidates = inventoryAutoSubtitleCandidates(rows),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "always"),
        ).selectedCandidate()

        assertEquals(1, selected?.selectionIndex)
    }

    // --- ported detail-preview cases -------------------------------------

    @Test
    fun resolvesThePreferredLanguageWhenAudioIsAnother() {
        val ordinal = autoOrdinal(
            tracks = listOf(track(lang = "eng"), track(lang = "fre")),
            context = AutoSubtitleContext(preferredLanguage = "fr", mode = "auto", audioLanguage = "eng"),
        )
        assertEquals(1, ordinal)
    }

    @Test
    fun resolvesToNothingWhenAudioAlreadyMatchesThePreferredLanguage() {
        assertNull(
            autoOrdinal(
                tracks = listOf(track(lang = "eng")),
                context = AutoSubtitleContext(preferredLanguage = "en", mode = "auto", audioLanguage = "eng"),
            ),
        )
    }

    @Test
    fun resolvesTheForcedTrackWhenAudioMatchesAndForcedSubsAreOn() {
        val ordinal = autoOrdinal(
            tracks = listOf(track(lang = "eng"), track(lang = "eng", forced = true)),
            context = AutoSubtitleContext(
                preferredLanguage = "en",
                mode = "auto",
                showForced = true,
                audioLanguage = "eng",
            ),
        )
        assertEquals(1, ordinal)
    }

    @Test
    fun modeOffResolvesToNothing() {
        assertNull(
            autoOrdinal(
                tracks = listOf(track(lang = "eng")),
                context = AutoSubtitleContext(preferredLanguage = "en", mode = "off"),
            ),
        )
    }

    @Test
    fun anEmptyPreferredLanguageMeansNoSubtitles() {
        assertNull(
            autoOrdinal(
                tracks = listOf(track(lang = "eng")),
                context = AutoSubtitleContext(preferredLanguage = "", mode = "auto"),
            ),
        )
    }

    @Test
    fun noPreferenceUnderPlainAutoResolvesToNothing() {
        assertNull(
            autoOrdinal(
                tracks = listOf(track(lang = "eng")),
                context = AutoSubtitleContext(preferredLanguage = null, mode = "auto"),
            ),
        )
    }

    @Test
    fun alwaysWithNoPreferencePrefersFullDialogueOverForced() {
        val ordinal = autoOrdinal(
            tracks = listOf(track(lang = "fre", forced = true), track(lang = "fre")),
            context = AutoSubtitleContext(preferredLanguage = null, mode = "always"),
        )
        assertEquals(1, ordinal)
    }

    @Test
    fun fullDialogueBeatsSdh() {
        val ordinal = autoOrdinal(
            tracks = listOf(track(lang = "eng", title = "English SDH"), track(lang = "eng")),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "auto", audioLanguage = "jpn"),
        )
        assertEquals(1, ordinal)
    }

    @Test
    fun dvbBitmapIsSkippedForATextTrack() {
        val ordinal = autoOrdinal(
            tracks = listOf(
                track(lang = "fre", codec = "dvb_subtitle"),
                track(lang = "fre", codec = "subrip"),
            ),
            context = AutoSubtitleContext(preferredLanguage = "fr", mode = "auto", audioLanguage = "eng"),
        )
        assertEquals(1, ordinal)
    }

    @Test
    fun vobsubBitmapIsSkippedForATextTrack() {
        val ordinal = autoOrdinal(
            tracks = listOf(
                track(lang = "eng", codec = "vobsub"),
                track(lang = "eng", codec = "srt"),
            ),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "auto", audioLanguage = "jpn"),
        )
        assertEquals(1, ordinal)
    }

    @Test
    fun aHindiCodeInTheTitleIsNotHearingImpaired() {
        val ordinal = autoOrdinal(
            tracks = listOf(track(lang = "eng", title = "EN - HI"), track(lang = "eng")),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "auto", audioLanguage = "jpn"),
        )
        assertEquals(0, ordinal)
    }

    @Test
    fun anEmptyInventoryResolvesToNothing() {
        assertEquals(
            AutoSubtitleResolution.NoChange,
            resolveAutoSubtitle(
                candidates = emptyList(),
                context = AutoSubtitleContext(preferredLanguage = "en", mode = "always"),
            ),
        )
    }

    @Test
    fun theLanguageTableFoldsIso639BibliographicCodes() {
        assertEquals("en", autoSubtitleLanguageKey("eng"))
        assertEquals("fr", autoSubtitleLanguageKey("fra"))
        assertEquals("fr", autoSubtitleLanguageKey("fre"))
        assertEquals("pt", autoSubtitleLanguageKey("pt-BR"))
        assertNull(autoSubtitleLanguageKey("und"))
        assertNull(autoSubtitleLanguageKey(" "))
    }

    // ------------------------------------------------------------------

    /** Catalog ordinal of the resolved track — no externals, so ordinal == combined. */
    private fun autoOrdinal(
        tracks: List<SubtitleTrack>,
        context: AutoSubtitleContext,
    ): Int? = resolveAutoSubtitle(catalogAutoSubtitleCandidates(tracks), context)
        .selectedCandidate()
        ?.selectionIndex

    private fun track(
        lang: String? = null,
        codec: String? = null,
        title: String? = null,
        forced: Boolean = false,
        external: Boolean = false,
    ) = SubtitleTrack(
        index = 0,
        codec = codec,
        language = lang,
        title = title,
        forced = forced,
        external = external,
    )
}
