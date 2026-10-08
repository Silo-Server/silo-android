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
    fun aPlainExternalTextTrackBeatsAnSdhEmbeddedBitmapOne() {
        // Shield, direct-play MKV: embedded PGS "English (SDH)" + an external
        // English SRT, preference English/Always. The detail row previewed the
        // SRT; the player, ranking only Media3's mounted tracks, started the
        // PGS. Over the full catalog the SRT wins — and its combined index is
        // what the start request can carry. It wins because the PGS track is
        // SDH, not because it is a bitmap: Android renders embedded PGS itself.
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

    // --- embedded over external (silo-server #1849 parity) -------------

    @Test
    fun anEmbeddedTextTrackBeatsAnExternalOneInTheSameLanguage() {
        // External sidecars are the ones that drift out of sync. The catalog
        // lists the sidecar first here, so caller order alone would pick it.
        val tracks = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "eng", title = "English", external = true),
            SubtitleTrack(index = 3, codec = "subrip", language = "eng", title = "English"),
        )

        val selected = resolveAutoSubtitle(
            candidates = catalogAutoSubtitleCandidates(tracks),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "always"),
        ).selectedCandidate()

        // Embedded tracks follow every external in combined space.
        assertEquals(1, selected?.selectionIndex)
    }

    @Test
    fun theDetailPreviewAndThePlayerInventoryPickTheSameEmbeddedTrack() {
        // The detail page ranks the catalog (embedded first); the player
        // fallback ranks the session inventory (externals first). Source now
        // decides the tie, so the two agree.
        val catalog = listOf(
            SubtitleTrack(index = 3, codec = "subrip", language = "eng", title = "English"),
            SubtitleTrack(index = 0, codec = "srt", language = "eng", title = "English", external = true),
        )
        val inventory = listOf(
            PlayerSubtitleInfo(index = 0, language = "eng", codec = "srt", url = "/s/0.vtt", catalogLabel = "English", catalogSource = "external", serverDelivery = SUBTITLE_DELIVERY_SIDECAR),
            PlayerSubtitleInfo(index = 1, language = "eng", codec = "subrip", url = "/s/1.vtt", catalogLabel = "English", catalogSource = "embedded", serverDelivery = SUBTITLE_DELIVERY_SIDECAR),
        )
        val context = AutoSubtitleContext(preferredLanguage = "en", mode = "always")

        val fromCatalog = resolveAutoSubtitle(catalogAutoSubtitleCandidates(catalog), context).selectedCandidate()
        val fromInventory = resolveAutoSubtitle(inventoryAutoSubtitleCandidates(inventory), context).selectedCandidate()

        assertEquals(1, fromCatalog?.selectionIndex)
        assertEquals(1, fromInventory?.selectionIndex)
    }

    @Test
    fun anEmbeddedPgsTrackTheDeviceRendersBeatsAnExternalTextTrack() {
        // Android plays embedded PGS without a transcode (in-stream on direct
        // play, a raw .sup sidecar over HLS), so being a bitmap is no reason to
        // fall back to the sidecar.
        val tracks = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "eng", external = true),
            SubtitleTrack(index = 2, codec = "hdmv_pgs_subtitle", language = "eng", title = "English"),
        )

        val selected = resolveAutoSubtitle(
            candidates = catalogAutoSubtitleCandidates(tracks),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "always"),
        ).selectedCandidate()

        assertEquals(1, selected?.selectionIndex)
    }

    @Test
    fun anEmbeddedBitmapTrackThatNeedsABurnInLosesToAnExternalTextTrack() {
        // VobSub has no client route, so the server would transcode to show it.
        val tracks = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "eng", external = true),
            SubtitleTrack(index = 2, codec = "dvd_subtitle", language = "eng", title = "English"),
        )

        val selected = resolveAutoSubtitle(
            candidates = catalogAutoSubtitleCandidates(tracks),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "always"),
        ).selectedCandidate()

        assertEquals(0, selected?.selectionIndex)
    }

    @Test
    fun aServerBurnInOnlyEmbeddedPgsRowLosesToAnExternalTextRow() {
        // The inventory's own delivery wins over the codec rule.
        val rows = listOf(
            PlayerSubtitleInfo(index = 0, language = "eng", codec = "srt", url = "/s/0.vtt", catalogSource = "external", serverDelivery = SUBTITLE_DELIVERY_SIDECAR),
            PlayerSubtitleInfo(index = 1, language = "eng", codec = "pgs", url = "", catalogSource = "embedded", serverDelivery = SUBTITLE_DELIVERY_BURN_IN_ONLY),
        )

        val selected = resolveAutoSubtitle(
            candidates = inventoryAutoSubtitleCandidates(rows),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "always"),
        ).selectedCandidate()

        assertEquals(0, selected?.selectionIndex)
    }

    @Test
    fun aFullExternalTrackBeatsAForcedEmbeddedOne() {
        val tracks = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "eng", external = true),
            SubtitleTrack(index = 2, codec = "subrip", language = "eng", title = "Forced", forced = true),
        )

        val selected = resolveAutoSubtitle(
            candidates = catalogAutoSubtitleCandidates(tracks),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "always", showForced = true),
        ).selectedCandidate()

        assertEquals(0, selected?.selectionIndex)
    }

    @Test
    fun anExternalTrackBeatsADownloadedOne() {
        val rows = listOf(
            PlayerSubtitleInfo(index = 1, language = "eng", codec = "srt", url = "/s/1.vtt", catalogSource = "downloaded", serverDelivery = SUBTITLE_DELIVERY_SIDECAR),
            PlayerSubtitleInfo(index = 0, language = "eng", codec = "srt", url = "/s/0.vtt", catalogSource = "external", serverDelivery = SUBTITLE_DELIVERY_SIDECAR),
        )

        val selected = resolveAutoSubtitle(
            candidates = inventoryAutoSubtitleCandidates(rows),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "always"),
        ).selectedCandidate()

        assertEquals(0, selected?.selectionIndex)
    }

    @Test
    fun aBurnInTrackStillWinsWhenItIsTheOnlyMatch() {
        val tracks = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "fre", external = true),
            SubtitleTrack(index = 2, codec = "dvd_subtitle", language = "eng"),
        )

        val selected = resolveAutoSubtitle(
            candidates = catalogAutoSubtitleCandidates(tracks),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "always"),
        ).selectedCandidate()

        assertEquals(1, selected?.selectionIndex)
    }

    @Test
    fun theForcedTrackForMatchingAudioPrefersTheEmbeddedOne() {
        val tracks = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "eng", title = "Forced", forced = true, external = true),
            SubtitleTrack(index = 2, codec = "subrip", language = "eng", title = "Forced", forced = true),
        )

        val selected = resolveAutoSubtitle(
            candidates = catalogAutoSubtitleCandidates(tracks),
            context = AutoSubtitleContext(preferredLanguage = "en", mode = "auto", showForced = true, audioLanguage = "eng"),
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
