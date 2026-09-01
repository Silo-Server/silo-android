package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TrackSelectionFingerprintTest {

    @Test
    fun downloadedSubtitleIdentityRoundTripsThroughVersionedPreference() {
        val identity = SubtitleIdentity.Downloaded(
            downloadId = 312,
            media = SubtitleMediaIdentity(
                trackId = "silo-downloaded-subtitle:312",
                label = "English | SDH",
                language = "eng",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = true,
            ),
        )

        val encoded = encodeSubtitleIdentityPreference(identity)

        assertEquals(identity, decodeSubtitleIdentityPreference(encoded))
    }

    @Test
    fun localMedia3SubtitleIdentityRoundTripsExactTrackMetadata() {
        val identity = SubtitleIdentity.LocalMedia3(
            SubtitleMediaIdentity(
                trackId = "decoder:text:9",
                label = "Commentary",
                language = "fr-CA",
                codecFamily = "vtt",
                forced = null,
                hearingImpaired = false,
            ),
        )

        val encoded = encodeSubtitleIdentityPreference(identity)

        assertEquals(identity, decodeSubtitleIdentityPreference(encoded))
        assertNull(decodeSubtitleIdentityPreference(subtitleTrackFingerprint(
            PlayerSubtitleInfo(index = 1, label = "Legacy", url = "/1.vtt"),
        )))
        assertNull(decodeSubtitleIdentityPreference("silo-subtitle-v2:{broken"))
    }

    @Test
    fun resolvesAudioFingerprintBackToTrackOrdinal() {
        val tracks = listOf(
            AudioTrack(index = 0, codec = "aac", language = "eng", title = "Stereo"),
            AudioTrack(index = 1, codec = "eac3", language = "eng", title = "5.1"),
        )
        val saved = audioTrackFingerprint(tracks[1])

        assertEquals(1, resolveAudioTrackOrdinal(tracks, saved))
    }

    @Test
    fun canonicalLanguageCoversTheWholeIso6392Table() {
        assertEquals("it", canonicalSubtitleLanguage("ita"))
        assertEquals("ko", canonicalSubtitleLanguage("kor"))
        assertEquals("pt", canonicalSubtitleLanguage("por"))
        assertEquals("ru", canonicalSubtitleLanguage("rus"))
        assertEquals("pl", canonicalSubtitleLanguage("pol"))
        assertEquals("zh", canonicalSubtitleLanguage("chi"))
        assertEquals("zh", canonicalSubtitleLanguage("zho"))
        assertEquals("zh", canonicalSubtitleLanguage("zh-Hans"))
        assertEquals("en", canonicalSubtitleLanguage("en-US"))
        assertNull(canonicalSubtitleLanguage("und"))
        assertEquals("xyz", canonicalSubtitleLanguage("xyz"))
    }

    @Test
    fun preferredAudioLanguageUsesAliasesAndTheMatchingDefaultTrack() {
        val tracks = listOf(
            AudioTrack(index = 4, codec = "aac", language = "eng", title = "Commentary"),
            AudioTrack(index = 9, codec = "truehd", language = "en-US", title = "Main", isDefault = true),
            AudioTrack(index = 12, codec = "eac3", language = "jpn", title = "Japanese"),
        )

        assertEquals(1, resolvePreferredAudioTrackOrdinal(tracks, "en"))
        assertEquals(2, resolvePreferredAudioTrackOrdinal(tracks, "ja-JP"))
        assertNull(resolvePreferredAudioTrackOrdinal(tracks, "fr"))
        assertNull(resolvePreferredAudioTrackOrdinal(tracks, ""))
    }

    @Test
    fun resolvesSubtitleOffAndTrackFingerprints() {
        val tracks = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "eng", title = "English"),
            SubtitleTrack(index = 3, codec = "ass", language = "eng", title = "Signs", forced = true),
        )

        assertEquals(-1, resolveSubtitleTrackOrdinal(tracks, SUBTITLE_OFF_FINGERPRINT))
        assertEquals(1, resolveSubtitleTrackOrdinal(tracks, subtitleTrackFingerprint(tracks[1])))
    }

    @Test
    fun playerTypedOffAndServerPreferencesResolveOnDetailScreen() {
        val tracks = catalogTracksInMixedIndexSpaces()

        assertEquals(
            -1,
            resolveCatalogSubtitlePreferenceOrdinal(
                tracks,
                encodeSubtitleIdentityPreference(SubtitleIdentity.Off),
            ),
        )
        assertEquals(
            3,
            resolveCatalogSubtitlePreferenceOrdinal(
                tracks,
                encodeSubtitleIdentityPreference(SubtitleIdentity.ServerSidecar(serverIndex = 1)),
            ),
        )
    }

    @Test
    fun detailTypedPreferenceDecodesForPlayerWithCombinedServerIndex() {
        val tracks = catalogTracksInMixedIndexSpaces()

        val encoded = encodeCatalogSubtitlePreference(
            tracks = tracks,
            selectedOrdinal = 3,
        )

        val identity = assertIs<SubtitleIdentity.ServerSidecar>(
            decodeSubtitleIdentityPreference(encoded),
        )
        assertEquals(1, identity.serverIndex)
        assertEquals("External English", identity.media?.label)
        assertEquals("en", identity.media?.language)
    }

    @Test
    fun detailEmbeddedPreferenceUsesCombinedIndexInsteadOfDemuxIndex() {
        val tracks = catalogTracksInMixedIndexSpaces()

        val encoded = encodeCatalogSubtitlePreference(
            tracks = tracks,
            selectedOrdinal = 0,
        )
        val identity = decodeSubtitleIdentityPreference(encoded)

        assertEquals(2, (identity as SubtitleIdentity.Embedded).serverIndex)
        assertEquals("Embedded English", identity.media.label)
    }

    @Test
    fun embeddedPgsPreferencePersistsAsClientMounted() {
        val tracks = listOf(
            SubtitleTrack(index = 2, codec = "hdmv_pgs_subtitle", language = "eng", title = "English PGS"),
        )

        val encoded = encodeCatalogSubtitlePreference(tracks, selectedOrdinal = 0)

        assertIs<SubtitleIdentity.Embedded>(decodeSubtitleIdentityPreference(encoded!!))
        assertEquals(0, resolveCatalogSubtitlePreferenceOrdinal(tracks, encoded))
    }

    @Test
    fun embeddedVobsubPreferencePersistsAsBurnIn() {
        val tracks = listOf(
            SubtitleTrack(index = 3, codec = "dvd_subtitle", language = "eng", title = "English VobSub"),
        )

        val encoded = encodeCatalogSubtitlePreference(tracks, selectedOrdinal = 0)

        assertIs<SubtitleIdentity.ServerBurnIn>(decodeSubtitleIdentityPreference(encoded!!))
        assertEquals(0, resolveCatalogSubtitlePreferenceOrdinal(tracks, encoded))
    }

    @Test
    fun detailPreferenceResolverFallsBackToLegacyFingerprint() {
        val tracks = catalogTracksInMixedIndexSpaces()

        assertEquals(
            2,
            resolveCatalogSubtitlePreferenceOrdinal(
                tracks,
                subtitleTrackFingerprint(tracks[2]),
            ),
        )
    }

    @Test
    fun externalTypedPreferenceFollowsStableMetadataAcrossCombinedIndexReorder() {
        val original = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "en", title = "English", external = true),
            SubtitleTrack(index = 0, codec = "srt", language = "fr", title = "French", external = true),
        )
        val reordered = listOf(original[1], original[0])
        val saved = encodeCatalogSubtitlePreference(original, selectedOrdinal = 0)

        assertEquals(
            1,
            resolveCatalogSubtitlePreferenceOrdinal(reordered, saved),
        )
    }

    @Test
    fun embeddedTypedPreferenceFollowsStableMetadataAcrossCombinedIndexReorder() {
        val external = SubtitleTrack(
            index = 0,
            codec = "srt",
            language = "fr",
            title = "French",
            external = true,
        )
        val english = SubtitleTrack(
            index = 17,
            codec = "ass",
            language = "en",
            title = "English embedded",
            external = false,
        )
        val dutch = SubtitleTrack(
            index = 23,
            codec = "ass",
            language = "nl",
            title = "Dutch embedded",
            external = false,
        )
        val original = listOf(external, english, dutch)
        val reordered = listOf(external, dutch, english)
        val saved = encodeCatalogSubtitlePreference(original, selectedOrdinal = 1)

        assertEquals(
            2,
            resolveCatalogSubtitlePreferenceOrdinal(reordered, saved),
        )
    }

    @Test
    fun typedServerPreferenceSafelyMissesWhenStableMetadataNoLongerMatches() {
        val original = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "en", title = "English", external = true),
        )
        val replacement = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "es", title = "Spanish", external = true),
        )
        val saved = encodeCatalogSubtitlePreference(original, selectedOrdinal = 0)

        assertNull(resolveCatalogSubtitlePreferenceOrdinal(replacement, saved))
    }

    @Test
    fun typedServerPreferenceWithOnlyForcedFalseDoesNotRemapArbitrarySoleTrack() {
        val weak = encodeSubtitleIdentityPreference(
            SubtitleIdentity.ServerSidecar(
                serverIndex = 0,
                media = SubtitleMediaIdentity(forced = false),
            ),
        )
        val replacement = listOf(
            SubtitleTrack(
                index = 9,
                codec = "srt",
                language = "es",
                title = "Spanish",
                external = true,
            ),
        )

        assertNull(resolveCatalogSubtitlePreferenceOrdinal(replacement, weak))
    }

    @Test
    fun typedServerPreferenceRequiresStoredTrackIdToMatch() {
        val withTrackId = encodeSubtitleIdentityPreference(
            SubtitleIdentity.ServerSidecar(
                serverIndex = 0,
                media = SubtitleMediaIdentity(
                    trackId = "stable-track",
                    label = "English",
                    language = "en",
                    codecFamily = "srt",
                    forced = false,
                ),
            ),
        )
        val rowWithoutTrackId = listOf(
            SubtitleTrack(
                index = 9,
                codec = "srt",
                language = "en",
                title = "English",
                external = true,
            ),
        )

        assertNull(resolveCatalogSubtitlePreferenceOrdinal(rowWithoutTrackId, withTrackId))
    }

    @Test
    fun legacyTypedServerPreferenceWithoutMediaKeepsCombinedIndexCompatibility() {
        val legacy = encodeSubtitleIdentityPreference(
            SubtitleIdentity.ServerSidecar(serverIndex = 1),
        )
        val tracks = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "en", title = "English", external = true),
            SubtitleTrack(index = 0, codec = "srt", language = "fr", title = "French", external = true),
        )

        assertEquals(1, resolveCatalogSubtitlePreferenceOrdinal(tracks, legacy))
    }

    @Test
    fun playerCanonicalSubripPreferenceResolvesCatalogSrt() {
        val preference = encodedPlayerServerPreference("English", "en", "subrip")
        val catalog = listOf(
            SubtitleTrack(
                index = 7,
                codec = "srt",
                language = "en",
                title = "English",
                external = true,
            ),
        )

        assertEquals(0, resolveCatalogSubtitlePreferenceOrdinal(catalog, preference))
    }

    @Test
    fun playerCanonicalSsaPreferenceResolvesCatalogAss() {
        val preference = encodedPlayerServerPreference("Styled English", "en", "ssa")
        val catalog = listOf(
            SubtitleTrack(
                index = 7,
                codec = "ass",
                language = "en",
                title = "Styled English",
                external = true,
            ),
        )

        assertEquals(0, resolveCatalogSubtitlePreferenceOrdinal(catalog, preference))
    }

    @Test
    fun playerCanonicalLanguagePreferenceResolvesCatalogIso639Aliases() {
        val englishPreference = encodedPlayerServerPreference("English", "en", "subrip")
        val frenchPreference = encodedPlayerServerPreference("French", "fr", "subrip")
        val catalog = listOf(
            SubtitleTrack(
                index = 7,
                codec = "srt",
                language = "eng",
                title = "English",
                external = true,
            ),
            SubtitleTrack(
                index = 9,
                codec = "srt",
                language = "fra",
                title = "French",
                external = true,
            ),
        )

        assertEquals(0, resolveCatalogSubtitlePreferenceOrdinal(catalog, englishPreference))
        assertEquals(1, resolveCatalogSubtitlePreferenceOrdinal(catalog, frenchPreference))
    }

    @Test
    fun catalogPreferenceSerializesCanonicalSubtitleLanguage() {
        val catalog = listOf(
            SubtitleTrack(
                index = 7,
                codec = "srt",
                language = "fre",
                title = "French",
                external = true,
            ),
        )

        val identity = assertIs<SubtitleIdentity.ServerSidecar>(
            decodeSubtitleIdentityPreference(encodeCatalogSubtitlePreference(catalog, 0)),
        )

        assertEquals("fr", identity.media?.language)
    }

    @Test
    fun catalogPreferenceDoesNotTreatHindiCodeAsHearingImpaired() {
        val catalog = listOf(
            SubtitleTrack(
                index = 7,
                codec = "srt",
                language = "hin",
                title = "EN - HI",
                external = true,
            ),
        )

        val identity = assertIs<SubtitleIdentity.ServerSidecar>(
            decodeSubtitleIdentityPreference(encodeCatalogSubtitlePreference(catalog, 0)),
        )

        assertNull(identity.media?.hearingImpaired)
    }

    @Test
    fun playerCanonicalLanguagePreferenceSafelyMissesDifferentCatalogLanguage() {
        val preference = encodedPlayerServerPreference("Dialogue", "en", "subrip")
        val catalog = listOf(
            SubtitleTrack(
                index = 7,
                codec = "srt",
                language = "fra",
                title = "Dialogue",
                external = true,
            ),
        )

        assertNull(resolveCatalogSubtitlePreferenceOrdinal(catalog, preference))
    }

    @Test
    fun playerCanonicalLanguagePreferenceSafelyMissesAmbiguousAliasRows() {
        val preference = encodedPlayerServerPreference("English", "en", "subrip")
        val catalog = listOf(
            SubtitleTrack(index = 7, codec = "srt", language = "en", title = "English", external = true),
            SubtitleTrack(index = 9, codec = "srt", language = "eng", title = "English", external = true),
        )

        assertNull(resolveCatalogSubtitlePreferenceOrdinal(catalog, preference))
    }

    @Test
    fun playerCanonicalCodecPreferenceSafelyMissesDifferentCatalogCodec() {
        val preference = encodedPlayerServerPreference("English", "en", "subrip")
        val catalog = listOf(
            SubtitleTrack(
                index = 7,
                codec = "ass",
                language = "en",
                title = "English",
                external = true,
            ),
        )

        assertNull(resolveCatalogSubtitlePreferenceOrdinal(catalog, preference))
    }

    @Test
    fun playerCanonicalCodecPreferenceSafelyMissesAmbiguousCatalogRows() {
        val preference = encodedPlayerServerPreference("English", "en", "subrip")
        val catalog = listOf(
            SubtitleTrack(index = 7, codec = "srt", language = "en", title = "English", external = true),
            SubtitleTrack(index = 9, codec = "subrip", language = "en", title = "English", external = true),
        )

        assertNull(resolveCatalogSubtitlePreferenceOrdinal(catalog, preference))
    }

    @Test
    fun playerSubtitleInfoUsesSameSubtitleFingerprintShape() {
        val catalog = SubtitleTrack(index = 2, codec = "srt", language = "eng", title = "English CC", forced = true)
        val mounted = PlayerSubtitleInfo(
            index = 2,
            codec = "srt",
            language = "eng",
            label = "English CC",
            forced = true,
            url = "/stream/subtitles/2.vtt",
        )

        assertEquals(subtitleTrackFingerprint(catalog), subtitleTrackFingerprint(mounted))
    }

    @Test
    fun resolvesMountedSubtitleFingerprintBackToMountedOrdinal() {
        // The mobile player records selections against the mounted list, so
        // they must round-trip against the SAME list even when the mounted
        // ordinals differ from the catalog demux indices.
        val mounted = listOf(
            PlayerSubtitleInfo(index = 0, codec = "srt", language = "eng", label = "English", url = "/s/0.vtt"),
            PlayerSubtitleInfo(index = 1, codec = "ass", language = "eng", label = "Signs", forced = true, url = "/s/1.vtt"),
        )

        assertEquals(-1, resolveMountedSubtitleOrdinal(mounted, SUBTITLE_OFF_FINGERPRINT))
        assertEquals(1, resolveMountedSubtitleOrdinal(mounted, subtitleTrackFingerprint(mounted[1])))
        assertNull(resolveMountedSubtitleOrdinal(mounted, null))
    }

    @Test
    fun blankOrUnknownFingerprintDoesNotResolve() {
        val tracks = listOf(AudioTrack(index = 0, codec = "aac", language = "eng", title = "Stereo"))

        assertNull(resolveAudioTrackOrdinal(tracks, null))
        assertNull(resolveAudioTrackOrdinal(tracks, ""))
        assertNull(resolveAudioTrackOrdinal(tracks, "missing"))
    }

    private fun encodedPlayerServerPreference(
        label: String,
        language: String,
        codecFamily: String,
    ): String = encodeSubtitleIdentityPreference(
        SubtitleIdentity.ServerSidecar(
            serverIndex = 7,
            media = SubtitleMediaIdentity(
                label = label,
                language = language,
                codecFamily = codecFamily,
                forced = false,
            ),
        ),
    )

    private fun catalogTracksInMixedIndexSpaces(): List<SubtitleTrack> = listOf(
        SubtitleTrack(
            index = 17,
            codec = "ass",
            language = "en",
            title = "Embedded English",
            external = false,
        ),
        SubtitleTrack(
            index = 0,
            codec = "srt",
            language = "fr",
            title = "External French",
            external = true,
        ),
        SubtitleTrack(
            index = 23,
            codec = "ass",
            language = "nl",
            title = "Embedded Dutch",
            external = false,
        ),
        SubtitleTrack(
            index = 0,
            codec = "srt",
            language = "en",
            title = "External English",
            external = true,
        ),
    )
}
