package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class MobileSubtitleAutoSelectionTest {

    @Test
    fun downloadedSelectionUsesPersistentDownloadIdentity() {
        val identity = mobileSubtitleIdentity(
            subtitle(
                index = 9,
                label = "English",
                language = "en",
            ).copy(source = "downloaded", downloadId = 312),
        )

        assertEquals(
            SubtitleIdentity.Downloaded::class,
            identity::class,
        )
        assertEquals(312, (identity as SubtitleIdentity.Downloaded).downloadId)
    }

    @Test
    fun downloadIdIsAuthoritativeWhenModernRowOmitsSourceStrings() {
        val identity = mobileSubtitleIdentity(
            subtitle(
                index = 9,
                label = "English",
                language = "en",
                codec = "vtt",
            ).copy(
                source = null,
                catalogSource = null,
                downloadId = 312,
                url = "/stream/s1/subtitles/9.vtt",
            ),
        )

        assertEquals(SubtitleIdentity.Downloaded::class, identity::class)
        assertEquals(312, (identity as SubtitleIdentity.Downloaded).downloadId)
    }

    @Test
    fun downloadedSelectionRestoresByUniqueDomainIdAfterMetadataChanges() {
        val persisted = SubtitleIdentity.Downloaded(
            downloadId = 312,
            media = org.siloserver.silo.model.playback.SubtitleMediaIdentity(
                trackId = "silo-downloaded-subtitle:312",
                label = "English",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = false,
            ),
        )
        val changed = subtitle(
            index = 8,
            label = "Français corrigé",
            language = "fra",
            codec = "subrip",
        ).copy(source = "downloaded", downloadId = 312)

        assertEquals(0, resolveMobileSubtitleOrdinal(persisted, listOf(changed)))
    }

    @Test
    fun duplicateDownloadedDomainIdSafelyMissesAsAmbiguous() {
        val persisted = SubtitleIdentity.Downloaded(
            downloadId = 312,
            media = org.siloserver.silo.model.playback.SubtitleMediaIdentity(
                label = "English",
                language = "en",
                codecFamily = "webvtt",
            ),
        )
        val duplicates = listOf(
            subtitle(8, "English", "en", codec = "webvtt")
                .copy(source = "downloaded", downloadId = 312),
            subtitle(9, "French", "fr", codec = "subrip")
                .copy(source = "downloaded", downloadId = 312),
        )

        assertEquals(null, resolveMobileSubtitleOrdinal(persisted, duplicates))
    }

    @Test
    fun legacyDownloadedPreferenceMigratesToAUniqueAuthoritativePlanRow() {
        val persisted = SubtitleIdentity.Downloaded(
            downloadId = 312,
            media = SubtitleMediaIdentity(
                trackId = "silo-downloaded-subtitle:312",
                label = "English",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = false,
            ),
        )
        val authoritative = subtitle(
            index = 4,
            label = "English",
            language = "en",
            codec = "vtt",
            forced = false,
        ).copy(
            source = "downloaded",
            downloadId = null,
            serverTrackId = "file:22:subtitle:4",
            serverDelivery = "sidecar",
        )

        assertEquals(0, resolveMobileSubtitleOrdinal(persisted, listOf(authoritative)))
        assertEquals(
            "file:22:subtitle:4",
            (mobileSubtitleIdentity(authoritative) as SubtitleIdentity.ServerSidecar).media?.trackId,
        )
    }

    @Test
    fun genericLabelKeepsHearingImpairedMetadataUnknown() {
        val identity = mobileSubtitleIdentity(
            subtitle(
                index = 4,
                label = "English",
                language = "en",
                codec = "vtt",
            ).copy(source = "downloaded"),
        ) as SubtitleIdentity.LocalMedia3

        assertEquals(null, identity.media.hearingImpaired)
    }

    @Test
    fun authoritativeHearingImpairedTrackDoesNotMatchUnknownMetadata() {
        val identity = SubtitleIdentity.LocalMedia3(
            org.siloserver.silo.model.playback.SubtitleMediaIdentity(
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = true,
            ),
        )
        val generic = subtitle(4, "English", "en", codec = "vtt").copy(
            source = "downloaded",
            downloadId = null,
        )

        assertEquals(null, resolveMobileSubtitleOrdinal(identity, listOf(generic)))
    }

    @Test
    fun embeddedSelectionRetainsCombinedServerIndexAndTypedMetadata() {
        val identity = mobileSubtitleIdentity(
            subtitle(
                index = 7,
                label = "English Forced",
                language = "en",
                forced = true,
                codec = "hdmv_pgs_subtitle",
            ).copy(source = "embedded", url = ""),
        )

        assertEquals(
            SubtitleIdentity.Embedded(
                serverIndex = 7,
                media = identity.let { (it as SubtitleIdentity.Embedded).media },
            ),
            identity,
        )
        assertEquals("pgs", (identity as SubtitleIdentity.Embedded).media.codecFamily)
        assertEquals(true, identity.media.forced)
    }

    @Test
    fun embeddedBitmapWithoutSidecarRouteRequestsBurnIn() {
        val identity = mobileSubtitleIdentity(
            subtitle(
                index = 6,
                label = "French VobSub",
                language = "fr",
                codec = "dvd_subtitle",
            ).copy(source = "embedded", url = ""),
        )

        val burnIn = identity as SubtitleIdentity.ServerBurnIn
        assertEquals(6, burnIn.serverIndex)
    }

    @Test
    fun materializedPgsArtifactUsesServerSidecarTransaction() {
        val identity = mobileSubtitleIdentity(
            subtitle(
                index = 8,
                label = "English PGS",
                language = "en",
                codec = "hdmv_pgs_subtitle",
            ).copy(source = "server_artifact", url = "/stream/s1/subtitles/8.sup"),
        )

        val sidecar = identity as SubtitleIdentity.ServerSidecar
        assertEquals(8, sidecar.serverIndex)
    }

    @Test
    fun extractedEmbeddedTextArtifactUsesServerSidecarTransaction() {
        val identity = mobileSubtitleIdentity(
            subtitle(
                index = 7,
                label = "English",
                language = "en",
                codec = "webvtt",
            ).copy(
                source = "embedded",
                url = "/stream/s1/subtitles/7.vtt",
            ),
        )

        val sidecar = identity as SubtitleIdentity.ServerSidecar
        assertEquals(7, sidecar.serverIndex)
        assertEquals("English", sidecar.media?.label)
        assertEquals("en", sidecar.media?.language)
        assertEquals("webvtt", sidecar.media?.codecFamily)
    }

    @Test
    fun externalBitmapSelectionRequestsBurnIn() {
        val identity = mobileSubtitleIdentity(
            subtitle(
                index = 8,
                label = "English PGS",
                language = "en",
                codec = "hdmv_pgs_subtitle",
            ).copy(source = "external", url = ""),
        )

        val burnIn = identity as SubtitleIdentity.ServerBurnIn
        assertEquals(8, burnIn.serverIndex)
        assertEquals("English PGS", burnIn.media?.label)
        assertEquals("en", burnIn.media?.language)
        assertEquals("pgs", burnIn.media?.codecFamily)
    }

    @Test
    fun persistedServerSidecarFollowsMetadataWhenMountedIndicesReorder() {
        val persisted = mobileSubtitleIdentity(
            subtitle(
                index = 4,
                label = "English",
                language = "en",
                codec = "webvtt",
            ).copy(source = "external"),
        )
        val reordered = listOf(
            subtitle(4, "Deutsch", "de", codec = "webvtt").copy(source = "external"),
            subtitle(9, "English", "en", codec = "webvtt").copy(source = "external"),
        )

        assertEquals(1, resolveMobileSubtitleOrdinal(persisted, reordered))
    }

    @Test
    fun persistedServerSidecarDoesNotFallBackToStaleIndexAfterMetadataMismatch() {
        val persisted = mobileSubtitleIdentity(
            subtitle(
                index = 4,
                label = "English",
                language = "en",
                codec = "webvtt",
            ).copy(source = "external"),
        )
        val changed = listOf(
            subtitle(4, "Deutsch", "de", codec = "webvtt").copy(source = "external"),
        )

        assertEquals(null, resolveMobileSubtitleOrdinal(persisted, changed))
    }

    @Test
    fun persistedServerSidecarRequiresEveryStoredMetadataFieldToMatch() {
        val persisted = SubtitleIdentity.ServerSidecar(
            serverIndex = 4,
            media = org.siloserver.silo.model.playback.SubtitleMediaIdentity(
                trackId = "stable-track",
                label = "English SDH",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = true,
            ),
        )
        val mismatched = subtitle(
            index = 9,
            label = "English",
            language = "en",
            codec = "webvtt",
        ).copy(
            source = "external",
            mediaTrackId = "different-track",
        )

        assertEquals(null, resolveMobileSubtitleOrdinal(persisted, listOf(mismatched)))
    }

    @Test
    fun weakTypedMetadataDoesNotRemapToArbitrarySoleMountedRow() {
        val persisted = SubtitleIdentity.ServerSidecar(
            serverIndex = 4,
            media = org.siloserver.silo.model.playback.SubtitleMediaIdentity(
                forced = false,
            ),
        )
        val soleRow = subtitle(
            index = 9,
            label = "Deutsch",
            language = "de",
        ).copy(source = "external")

        assertEquals(null, resolveMobileSubtitleOrdinal(persisted, listOf(soleRow)))
    }

    @Test
    fun legacyServerIdentityWithoutMediaStillUsesStoredIndex() {
        val legacy = SubtitleIdentity.ServerSidecar(serverIndex = 4)
        val rows = listOf(
            subtitle(9, "English", "en").copy(source = "external"),
            subtitle(4, "Deutsch", "de").copy(source = "external"),
        )

        assertEquals(1, resolveMobileSubtitleOrdinal(legacy, rows))
    }

    @Test
    fun embeddedIdentityWithoutTrackIdFollowsStrictMetadataAfterReorder() {
        val persisted = SubtitleIdentity.Embedded(
            serverIndex = 4,
            media = org.siloserver.silo.model.playback.SubtitleMediaIdentity(
                label = "English",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
            ),
        )
        val rows = listOf(
            subtitle(4, "Deutsch", "de", codec = "webvtt").copy(source = "embedded", url = ""),
            subtitle(9, "English", "en", codec = "webvtt").copy(source = "embedded", url = ""),
        )

        assertEquals(1, resolveMobileSubtitleOrdinal(persisted, rows))
    }

    @Test
    fun typedMobileOrdinalNormalizesVttAndWebvtt() {
        val identity = SubtitleIdentity.LocalMedia3(
            org.siloserver.silo.model.playback.SubtitleMediaIdentity(
                label = "English",
                language = "eng",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = null,
            ),
        )

        assertEquals(
            0,
            resolveMobileSubtitleOrdinal(
                identity,
                listOf(
                    subtitle(4, "English", "en", codec = "vtt").copy(
                        source = "downloaded",
                        downloadId = null,
                    ),
                ),
            ),
        )
    }

    @Test
    fun typedMobileOrdinalUsesTrackIdWhenAllStoredMetadataAlsoMatches() {
        val identity = SubtitleIdentity.LocalMedia3(
            org.siloserver.silo.model.playback.SubtitleMediaIdentity(
                trackId = "decoder-text-9",
                label = "English",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = null,
            ),
        )
        val rows = listOf(
            subtitle(4, "English", "en", codec = "webvtt").copy(
                source = "downloaded",
                downloadId = null,
                mediaTrackId = "decoder-text-8",
            ),
            subtitle(5, "English", "en", codec = "webvtt").copy(
                source = "downloaded",
                downloadId = null,
                mediaTrackId = "decoder-text-9",
            ),
        )

        assertEquals(1, resolveMobileSubtitleOrdinal(identity, rows))
    }

    @Test
    fun typedMobileOrdinalSeparatesHearingImpairedDuplicatesAndRejectsAmbiguity() {
        val hearingIdentity = SubtitleIdentity.LocalMedia3(
            org.siloserver.silo.model.playback.SubtitleMediaIdentity(
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = true,
            ),
        )
        val rows = listOf(
            subtitle(4, "English", "en", codec = "vtt").copy(
                source = "downloaded",
                downloadId = null,
            ),
            subtitle(5, "English SDH", "en", codec = "webvtt").copy(
                source = "downloaded",
                downloadId = null,
            ),
        )

        assertEquals(1, resolveMobileSubtitleOrdinal(hearingIdentity, rows))
        assertEquals(
            null,
            resolveMobileSubtitleOrdinal(
                hearingIdentity.copy(media = hearingIdentity.media.copy(hearingImpaired = null)),
                rows,
            ),
        )
    }

    @Test
    fun autoSubtitlePreferenceDemotesClosedCaptionTitledTracksWhenPlainDialogueExists() {
        val subtitles = listOf(
            subtitle(index = 4, label = "English (CC)", language = "en"),
            subtitle(index = 7, label = "English", language = "en"),
        )

        assertEquals(
            MobileSubtitleAutoSelection.Select(1),
            resolveMobileAutoSubtitleSelection(
                audioTracks = listOf(audio(language = "ja")),
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
                audioTracks = listOf(audio(language = "ja")),
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
                audioTracks = listOf(audio(language = "ja")),
                selectedAudioIndex = 0,
                subtitles = subtitles,
                preferredLanguage = "en",
                subtitleMode = "auto",
                showForcedSubtitles = true,
            ),
        )
    }

    @Test
    fun autoSubtitlePreferenceDoesNotTreatHindiCodeAsHearingImpaired() {
        val subtitles = listOf(
            subtitle(index = 4, label = "EN - HI", language = "en"),
            subtitle(index = 7, label = "English", language = "en"),
        )

        assertEquals(
            MobileSubtitleAutoSelection.Select(0),
            resolveMobileAutoSubtitleSelection(
                audioTracks = listOf(audio(language = "ja")),
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
                // selectedAudioIndex is an ORDINAL. The wire sends no audio
                // index, so a fixture keying on one tested a shape that cannot
                // occur; two rows make the ordinal meaningful.
                audioTracks = listOf(audio(language = "nld"), audio(language = "eng")),
                selectedAudioIndex = 1,
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
                // selectedAudioIndex is an ORDINAL. The wire sends no audio
                // index, so a fixture keying on one tested a shape that cannot
                // occur; two rows make the ordinal meaningful.
                audioTracks = listOf(audio(language = "nld"), audio(language = "eng")),
                selectedAudioIndex = 1,
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
                audioTracks = listOf(audio(language = "en")),
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
        language: String?,
    ): AudioTrack = AudioTrack(language = language)

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
