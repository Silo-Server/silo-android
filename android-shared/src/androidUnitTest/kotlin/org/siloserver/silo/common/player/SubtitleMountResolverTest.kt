package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleMountResolverTest {

    @Test
    fun blankExplicitExternalRowCannotMatchEmbeddedMetadata() {
        val row = PlayerSubtitleInfo(
            index = 4,
            language = "en",
            codec = "hdmv_pgs_subtitle",
            label = "English",
            source = "external",
            forced = false,
            url = "",
        )
        val embedded = track(
            index = 0,
            trackId = "decoder-pgs-7",
            label = "English",
            language = "en",
            codec = "application/pgs",
            forced = false,
            hearingImpaired = false,
        )

        assertNull(resolveMountedSubtitle(row, listOf(embedded)))
    }

    @Test
    fun catalogTextIdentityMatchesTheWebvttArtifactTheServerMaterialised() {
        // A catalog row names its source format; the server serves the artifact
        // it materialises for that row as WebVTT. Demanding an exact family
        // match meant a picked embedded SubRip track never resolved to the
        // sidecar produced for it — the mount deadline fired and the selection
        // rolled back to Off.
        val identity = SubtitleIdentity.Embedded(
            serverIndex = 12,
            media = SubtitleMediaIdentity(
                trackId = null,
                label = "SUBRIP",
                language = "nl",
                codecFamily = "subrip",
            ),
        )

        val match = resolveMountedSubtitle(
            identity = identity,
            tracks = listOf(
                track(index = 0, trackId = "1:silo-subtitle:12", language = "nl", codec = "text/vtt"),
            ),
        )

        assertEquals(0, match?.track?.index)
    }

    @Test
    fun bitmapIdentityStillRequiresAnExactCodecFamily() {
        val identity = SubtitleIdentity.Embedded(
            serverIndex = 4,
            media = SubtitleMediaIdentity(
                trackId = null,
                label = "English",
                language = "en",
                codecFamily = "pgs",
            ),
        )

        val match = resolveMountedSubtitle(
            identity = identity,
            tracks = listOf(
                track(index = 0, trackId = "1:silo-subtitle:4", language = "en", codec = "text/vtt"),
            ),
        )

        assertNull(match, "a bitmap identity must not match a text artifact")
    }

    @Test
    fun blankCatalogExternalRowCannotMatchEmbeddedMetadata() {
        val row = PlayerSubtitleInfo(
            index = 4,
            language = "en",
            codec = "hdmv_pgs_subtitle",
            label = "English",
            source = null,
            forced = false,
            url = "",
            catalogSource = "external",
        )
        val embedded = track(
            index = 0,
            trackId = "decoder-pgs-7",
            label = "English",
            language = "en",
            codec = "application/pgs",
            forced = false,
            hearingImpaired = false,
        )

        assertNull(resolveMountedSubtitle(row, listOf(embedded)))
    }

    @Test
    fun extractedEmbeddedTextArtifactResolvesItsReservedServerId() {
        val row = PlayerSubtitleInfo(
            index = 7,
            language = "en",
            codec = "webvtt",
            label = "English",
            source = "embedded",
            forced = false,
            url = "/stream/s2/subtitles/7.vtt",
        )
        val artifact = track(
            index = 2,
            trackId = "silo-subtitle:7",
            label = "English",
            language = "en",
            codec = "text/vtt",
            forced = false,
            hearingImpaired = false,
        )

        assertEquals(2, resolveMountedSubtitle(row, listOf(artifact))?.track?.index)
    }

    @Test
    fun serverSidecarResolvesExactStableIdAcrossSameLabelTracks() {
        val tracks = listOf(
            track(index = 3, trackId = "silo-subtitle:3", label = "Server subtitle"),
            track(index = 4, trackId = "silo-subtitle:4", label = "Server subtitle"),
        )

        val match = resolveMountedSubtitle(SubtitleIdentity.ServerSidecar(4), tracks)

        assertEquals(4, match?.track?.index)
        assertEquals("silo-subtitle:4", match?.track?.trackId)
    }

    @Test
    fun exactLocalIdHasGlobalPriorityOverEarlierMetadataMatch() {
        val identity = SubtitleIdentity.LocalMedia3(
            media(
                trackId = "decoder-text-9",
                label = "English",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = false,
            ),
        )
        val tracks = listOf(
            track(
                index = 0,
                trackId = "decoder-text-8",
                label = "English",
                language = "en",
                codec = "text/vtt",
                forced = false,
                hearingImpaired = false,
            ),
            track(
                index = 1,
                trackId = "decoder-text-9",
                label = "Different runtime label",
                language = "fr",
                codec = "application/x-subrip",
                forced = true,
                hearingImpaired = true,
            ),
        )

        assertEquals(1, resolveMountedSubtitle(identity, tracks)?.track?.index)
    }

    @Test
    fun nonServerIdentitiesCannotClaimReservedServerArtifactId() {
        val media = media(
            trackId = "silo-subtitle:4",
            label = "English",
            language = "en",
            codecFamily = "webvtt",
            forced = false,
            hearingImpaired = false,
        )
        val serverSidecar = track(
            index = 4,
            trackId = "silo-subtitle:4",
            label = "English",
            language = "en",
            codec = "text/vtt",
            forced = false,
            hearingImpaired = false,
        )
        val identities = listOf(
            SubtitleIdentity.Embedded(serverIndex = 4, media = media),
            SubtitleIdentity.Downloaded(downloadId = 9, media = media),
            SubtitleIdentity.LocalMedia3(media),
        )

        identities.forEach { identity ->
            assertNull(
                resolveMountedSubtitle(identity, listOf(serverSidecar)),
                "reserved ID must not resolve for $identity",
            )
        }
        assertEquals(
            4,
            resolveMountedSubtitle(
                SubtitleIdentity.ServerSidecar(serverIndex = 4),
                listOf(serverSidecar),
            )?.track?.index,
        )
    }

    @Test
    fun serverDownloadedAndOrdinaryIdentityNamespacesCannotCrossMatch() {
        val duplicateLabel = "English"
        val tracks = listOf(
            track(
                index = 0,
                trackId = "silo-subtitle:3",
                label = duplicateLabel,
                language = "en",
                codec = "text/vtt",
                forced = false,
                hearingImpaired = false,
            ),
            track(
                index = 1,
                trackId = "silo-downloaded-subtitle:99",
                label = duplicateLabel,
                language = "en",
                codec = "text/vtt",
                forced = false,
                hearingImpaired = false,
            ),
            track(
                index = 2,
                trackId = "decoder-text-5",
                label = duplicateLabel,
                language = "en",
                codec = "text/vtt",
                forced = false,
                hearingImpaired = false,
            ),
        )
        val downloadedMedia = media(
            trackId = "silo-downloaded-subtitle:4",
            label = duplicateLabel,
            language = "en",
            codecFamily = "webvtt",
            forced = false,
            hearingImpaired = false,
        )

        assertEquals(
            0,
            resolveMountedSubtitle(SubtitleIdentity.ServerSidecar(3), tracks)?.track?.index,
        )
        assertEquals(
            1,
            resolveMountedSubtitle(
                SubtitleIdentity.Downloaded(downloadId = 99, media = downloadedMedia),
                tracks,
            )?.track?.index,
        )
        assertEquals(
            2,
            resolveMountedSubtitle(
                SubtitleIdentity.LocalMedia3(
                    downloadedMedia.copy(trackId = "decoder-text-5"),
                ),
                tracks,
            )?.track?.index,
        )

        assertNull(
            resolveMountedSubtitle(
                SubtitleIdentity.LocalMedia3(downloadedMedia),
                tracks,
            ),
        )
        assertNull(
            resolveMountedSubtitle(
                SubtitleIdentity.Embedded(serverIndex = 4, media = downloadedMedia),
                tracks,
            ),
        )
        assertNull(
            resolveMountedSubtitle(
                SubtitleIdentity.Downloaded(
                    downloadId = 99,
                    media = downloadedMedia.copy(trackId = "silo-subtitle:3"),
                ),
                listOf(tracks.first(), tracks.last()),
            ),
        )
    }

    @Test
    fun downloadedIdentityUsesDomainIdInsteadOfMutableArtifactIndex() {
        val tracks = listOf(
            track(
                index = 0,
                trackId = "silo-downloaded-subtitle:312",
                label = "English",
                language = "en",
                codec = "text/vtt",
                forced = false,
                hearingImpaired = false,
            ),
            track(
                index = 1,
                trackId = "silo-downloaded-subtitle:313",
                label = "English",
                language = "en",
                codec = "text/vtt",
                forced = false,
                hearingImpaired = false,
            ),
        )
        val identity = SubtitleIdentity.Downloaded(
            downloadId = 312,
            media = media(
                trackId = "silo-downloaded-subtitle:7",
                label = "English",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = false,
            ),
        )

        assertEquals(0, resolveMountedSubtitle(identity, tracks)?.track?.index)
        assertNull(resolveMountedSubtitle(identity, listOf(tracks[1])))
    }

    @Test
    fun legacyDownloadedRowSelectsUniqueOrdinaryTrackByTypedMetadata() {
        val row = PlayerSubtitleInfo(
            index = 4,
            language = "en",
            codec = "webvtt",
            label = "Legacy English",
            source = "downloaded",
            forced = false,
            url = "/4.vtt",
        )
        val ordinary = track(
            index = 2,
            trackId = null,
            label = "Legacy English",
            language = "en",
            codec = "text/vtt",
            forced = false,
            hearingImpaired = false,
        )
        val reserved = ordinary.copy(
            index = 0,
            trackId = "silo-downloaded-subtitle:312",
        )

        assertEquals(2, resolveMountedSubtitle(row, listOf(reserved, ordinary))?.track?.index)
    }

    @Test
    fun legacyDownloadedRowRejectsSameMetadataOrdinaryTrackAmbiguity() {
        val row = PlayerSubtitleInfo(
            index = 4,
            language = "en",
            codec = "webvtt",
            label = "Legacy English",
            source = "downloaded",
            forced = false,
            url = "/4.vtt",
        )
        val ordinary = track(
            index = 2,
            trackId = null,
            label = "Legacy English",
            language = "en",
            codec = "text/vtt",
            forced = false,
            hearingImpaired = false,
        )

        assertNull(resolveMountedSubtitle(row, listOf(ordinary, ordinary.copy(index = 3))))
    }

    @Test
    fun legacyDownloadedRowCannotClaimAnyReservedArtifactIdentity() {
        val row = PlayerSubtitleInfo(
            index = 4,
            language = "en",
            codec = "webvtt",
            label = "English",
            source = "downloaded",
            forced = false,
            url = "/4.vtt",
        )
        val tracks = listOf(
            track(
                index = 0,
                trackId = "silo-subtitle:4",
                label = "English",
                language = "en",
                codec = "text/vtt",
                forced = false,
                hearingImpaired = false,
            ),
            track(
                index = 1,
                trackId = "silo-downloaded-subtitle:4",
                label = "English",
                language = "en",
                codec = "text/vtt",
                forced = false,
                hearingImpaired = false,
            ),
            track(
                index = 2,
                trackId = "silo-downloaded-subtitle:312",
                label = "English",
                language = "en",
                codec = "text/vtt",
                forced = false,
                hearingImpaired = false,
            ),
        )

        tracks.forEach { reserved ->
            assertNull(
                resolveMountedSubtitle(row, listOf(reserved)),
                "legacy row must not claim reserved track ${reserved.trackId}",
            )
        }
    }

    @Test
    fun modernDownloadedRowRemainsExactDomainIdOnly() {
        val row = PlayerSubtitleInfo(
            index = 4,
            language = "en",
            codec = "webvtt",
            label = "English",
            source = "downloaded",
            forced = false,
            url = "/4.vtt",
            downloadId = 312,
        )
        val ordinary = track(
            index = 2,
            trackId = null,
            label = "English",
            language = "en",
            codec = "text/vtt",
            forced = false,
            hearingImpaired = false,
        )

        assertNull(resolveMountedSubtitle(row, listOf(ordinary)))
    }

    @Test
    fun localIdChangeFallsBackToCompleteTypedMetadata() {
        val identity = SubtitleIdentity.LocalMedia3(
            media(
                trackId = "old-decoder-id",
                label = "English SDH",
                language = "en",
                codecFamily = "subrip",
                forced = false,
                hearingImpaired = true,
            ),
        )
        val tracks = listOf(
            track(
                index = 2,
                trackId = "new-decoder-id",
                label = "English SDH",
                language = "EN",
                codec = "application/x-subrip",
                forced = false,
                hearingImpaired = true,
            ),
        )

        assertEquals(2, resolveMountedSubtitle(identity, tracks)?.track?.index)
    }

    @Test
    fun embeddedPgsFallbackSeparatesForcedAndFullDuplicates() {
        val full = track(
            index = 5,
            trackId = null,
            label = "English",
            language = "eng",
            codec = "application/pgs",
            forced = false,
            hearingImpaired = false,
        )
        val forced = full.copy(index = 6, forced = true)
        val identity = SubtitleIdentity.Embedded(
            serverIndex = 7,
            media = media(
                label = "English",
                language = "en",
                codecFamily = "pgs",
                forced = true,
                hearingImpaired = false,
            ),
        )

        assertEquals(6, resolveMountedSubtitle(identity, listOf(full, forced))?.track?.index)
    }

    @Test
    fun metadataFallbackSeparatesHearingImpairedDuplicate() {
        val identity = SubtitleIdentity.LocalMedia3(
            media(
                label = "English",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = true,
            ),
        )
        val tracks = listOf(
            track(1, null, "English", "en", "text/vtt", false, false),
            track(2, null, "English", "en", "text/vtt", false, true),
        )

        assertEquals(2, resolveMountedSubtitle(identity, tracks)?.track?.index)
    }

    @Test
    fun idLessLegacyTextResolvesByTypedMetadata() {
        val identity = SubtitleIdentity.LocalMedia3(
            media(
                language = "fr",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = false,
            ),
        )
        val tracks = listOf(
            track(0, null, "Français", "fr", "text/vtt", false, false),
        )

        assertEquals(0, resolveMountedSubtitle(identity, tracks)?.track?.index)
    }

    @Test
    fun embeddedBitmapAcceptsOrdinaryDecoderTrackId() {
        val identity = SubtitleIdentity.Embedded(
            serverIndex = 4,
            media = media(
                trackId = "7",
                language = "en",
                codecFamily = "pgs",
                forced = true,
            ),
        )
        val tracks = listOf(
            track(0, "8", "English", "en", "application/pgs", true, false),
            track(1, "7", "English", "en", "application/pgs", false, false),
        )

        assertEquals(1, resolveMountedSubtitle(identity, tracks)?.track?.index)
    }

    @Test
    fun missingLocalIdCannotFallBackToServerSidecarWithSameMetadata() {
        val identity = SubtitleIdentity.LocalMedia3(
            media(
                trackId = "decoder-text-2",
                label = "English",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = false,
            ),
        )
        val tracks = listOf(
            track(
                index = 0,
                trackId = "silo-subtitle:4",
                label = "English",
                language = "en",
                codec = "text/vtt",
                forced = false,
                hearingImpaired = false,
            ),
        )

        assertNull(resolveMountedSubtitle(identity, tracks))
    }

    @Test
    fun displayLabelAloneNeverEstablishesIdentity() {
        val identity = SubtitleIdentity.LocalMedia3(media(label = "English"))

        assertNull(
            resolveMountedSubtitle(
                identity,
                listOf(track(index = 0, trackId = null, label = "English")),
            ),
        )
    }

    @Test
    fun offAndBurnInHaveNoMountedTrack() {
        val tracks = listOf(track(index = 0, trackId = "silo-subtitle:3"))

        assertNull(resolveMountedSubtitle(SubtitleIdentity.Off, tracks))
        assertNull(resolveMountedSubtitle(SubtitleIdentity.ServerBurnIn(3), tracks))
    }

    @Test
    fun stableArtifactIdUsesCombinedServerIndex() {
        assertEquals("silo-subtitle:0", subtitleArtifactTrackId(0))
        assertEquals("silo-subtitle:42", subtitleArtifactTrackId(42))
    }

    // Shield repro (Supergirl): a disc with three English SubRip streams —
    // "Forced", an untitled one the catalog labels with the placeholder
    // "SUBRIP", and "SDH". The v3 row for the untitled one is typed sidecar
    // (url present) but the direct-play stream carries the track, so it must
    // resolve onto the untitled Media3 track — not to nothing.
    @Test
    fun untitledPlaceholderLabelledRowResolvesToTheUntitledSiblingNotSdhOrForced() {
        val tracks = listOf(
            track(index = 0, trackId = "2", label = "Forced", language = "en", codec = "application/x-subrip", forced = true, hearingImpaired = false),
            // The TV synthesises "EN" for a track Media3 exposes without a label.
            track(index = 1, trackId = "3", label = "EN", language = "en", codec = "application/x-subrip", forced = false, hearingImpaired = false),
            track(index = 2, trackId = "4", label = "SDH", language = "en", codec = "application/x-subrip", forced = false, hearingImpaired = true),
        )
        val row = PlayerSubtitleInfo(
            index = 8,
            language = "en",
            codec = "subrip",
            label = "SUBRIP",
            source = "embedded",
            catalogSource = "embedded",
            serverTrackId = "file:22069955:subtitle:8",
            serverDelivery = "sidecar",
            url = "/stream/s/subtitles/8.srt",
        )

        assertEquals(1, resolveMountedSubtitle(row, tracks)?.track?.index)
    }

    @Test
    fun placeholderLabelIsNotUsedAsATitle() {
        val tracks = listOf(
            track(index = 0, trackId = "3", label = null, language = "en", codec = "application/x-subrip"),
        )
        assertEquals(
            0,
            resolveMountedSubtitle(
                SubtitleIdentity.LocalMedia3(media(label = "PGS", language = "en", codecFamily = "subrip")),
                tracks,
            )?.track?.index,
        )
    }

    @Test
    fun untitledRowStaysAmbiguousBetweenTwoUntitledSiblings() {
        val tracks = listOf(
            track(index = 0, trackId = "3", label = null, language = "en", codec = "application/x-subrip"),
            track(index = 1, trackId = "4", label = null, language = "en", codec = "application/x-subrip"),
        )
        assertNull(
            resolveMountedSubtitle(
                SubtitleIdentity.LocalMedia3(media(label = "SUBRIP", language = "en", codecFamily = "subrip")),
                tracks,
            ),
        )
    }

    private fun track(
        index: Int,
        trackId: String?,
        label: String? = null,
        language: String? = null,
        codec: String? = null,
        forced: Boolean? = null,
        hearingImpaired: Boolean? = null,
    ): MountedSubtitleTrack = MountedSubtitleTrack(
        index = index,
        trackId = trackId,
        label = label,
        language = language,
        codec = codec,
        forced = forced,
        hearingImpaired = hearingImpaired,
    )

    private fun media(
        trackId: String? = null,
        label: String? = null,
        language: String? = null,
        codecFamily: String? = null,
        forced: Boolean? = null,
        hearingImpaired: Boolean? = null,
    ): SubtitleMediaIdentity = SubtitleMediaIdentity(
        trackId = trackId,
        label = label,
        language = language,
        codecFamily = codecFamily,
        forced = forced,
        hearingImpaired = hearingImpaired,
    )
}
