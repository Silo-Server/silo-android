package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.CommittedSubtitle
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.encodeSubtitleIdentityPreference
import org.siloserver.silo.repository.port.TrackSelectionFingerprintUpdate
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TvPlayerSubtitleIntegrationPolicyTest {
    @Test
    fun `unresolved audio during subtitle persistence preserves the existing preference`() {
        val update = tvAudioTrackPersistenceUpdate(
            committedAudioTrackIndex = 7,
            audioTracks = listOf(AudioTrack(index = 2, language = "en", codec = "aac")),
        )

        assertEquals(TrackSelectionFingerprintUpdate.Preserve, update)
    }

    @Test
    fun `resolved audio during subtitle persistence writes the exact fingerprint`() {
        // The committed value is an ORDINAL. Resolving it against
        // AudioTrack.index matched nothing above 0, so the chosen track was
        // silently never persisted and reopening the item lost it.
        val english = AudioTrack(language = "en", codec = "aac")
        val japanese = AudioTrack(language = "ja", codec = "ac3")

        val update = tvAudioTrackPersistenceUpdate(
            committedAudioTrackIndex = 1,
            audioTracks = listOf(english, japanese),
        )

        assertEquals(
            TrackSelectionFingerprintUpdate.Set(audioTrackFingerprint(japanese)),
            update,
        )
    }

    @Test
    fun `missing audio intent during subtitle persistence preserves rather than clears`() {
        assertEquals(
            TrackSelectionFingerprintUpdate.Preserve,
            tvAudioTrackPersistenceUpdate(
                committedAudioTrackIndex = null,
                audioTracks = emptyList(),
            ),
        )
    }

    @Test
    fun `authoritative empty adapter snapshot clears stale downloaded UI rows`() {
        val stale = listOf(downloadedRow(index = 4, downloadId = 91))

        assertEquals(
            emptyList(),
            authoritativeTvSubtitleRows(snapshotRows = emptyList(), previousRows = stale),
        )
    }

    @Test
    fun `fresh restore resolves an exact authoritative downloaded sidecar`() {
        val plannedRow = PlayerSubtitleInfo(
            index = 4,
            language = "en",
            codec = "vtt",
            label = "Downloaded English",
            source = "downloaded",
            forced = false,
            url = "/stream/s1/subtitles/4.vtt",
            serverTrackId = "file:22:subtitle:4",
            serverDelivery = "sidecar",
        )
        val eventRow = plannedRow.copy(downloadId = 91)
        val persistedIdentity = assertIs<SubtitleIdentity.ServerSidecar>(
            tvSubtitleIdentity(eventRow),
        )
        val plannedIdentity = assertIs<SubtitleIdentity.ServerSidecar>(
            tvSubtitleIdentity(plannedRow),
        )

        assertEquals(
            TvFreshSubtitlePreferenceResolution(plannedIdentity),
            resolveTvFreshSubtitlePreference(
                preference = encodeSubtitleIdentityPreference(persistedIdentity),
                catalogTracks = emptyList(),
                hydratedRows = listOf(plannedRow),
            ),
        )
        assertEquals("file:22:subtitle:4", persistedIdentity.media?.trackId)
    }

    @Test
    fun `fresh restore migrates a legacy downloaded identity by unique plan metadata`() {
        val legacy = SubtitleIdentity.Downloaded(
            downloadId = 91,
            media = org.siloserver.silo.model.playback.SubtitleMediaIdentity(
                trackId = "silo-downloaded-subtitle:91",
                label = "Downloaded English",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = false,
            ),
        )
        val row = PlayerSubtitleInfo(
            index = 4,
            language = "en",
            codec = "vtt",
            label = "Downloaded English",
            source = "downloaded",
            forced = false,
            url = "/stream/s1/subtitles/4.vtt",
            serverTrackId = "file:22:subtitle:4",
            serverDelivery = "sidecar",
        )
        val exact = tvSubtitleIdentity(row)

        assertEquals(
            TvFreshSubtitlePreferenceResolution(
                identity = exact,
                migratedPreference = encodeSubtitleIdentityPreference(exact),
            ),
            resolveTvFreshSubtitlePreference(
                preference = encodeSubtitleIdentityPreference(legacy),
                catalogTracks = emptyList(),
                hydratedRows = listOf(row),
            ),
        )
    }

    @Test
    fun `download auto selection uses the same canonical identity as the HUD row`() {
        val row = downloadedRow(index = 4, downloadId = 91)

        assertEquals(
            tvSubtitleIdentity(row),
            tvDownloadedRefreshIdentity(row),
        )
    }

    @Test
    fun `download auto selection with missing domain id safely returns null`() {
        val legacy = downloadedRow(index = 4, downloadId = null)

        assertEquals(null, tvDownloadedRefreshIdentity(legacy))
    }

    @Test
    fun `embedded PGS stays a client-mounted identity`() {
        val identity = tvSubtitleIdentity(
            PlayerSubtitleInfo(
                index = 8,
                language = "en",
                codec = "hdmv_pgs_subtitle",
                label = "English PGS",
                source = "embedded",
                forced = false,
                url = "",
            ),
        )

        assertIs<SubtitleIdentity.Embedded>(identity)
        assertEquals(8, identity.serverIndex)
    }

    @Test
    fun `embedded bitmap without a sidecar route requests burn-in`() {
        for (codec in listOf("dvd_subtitle", "dvb_subtitle", "vobsub")) {
            val identity = tvSubtitleIdentity(
                PlayerSubtitleInfo(
                    index = 5,
                    language = "en",
                    codec = codec,
                    label = "English bitmap",
                    source = "embedded",
                    forced = false,
                    url = "",
                ),
            )

            assertIs<SubtitleIdentity.ServerBurnIn>(identity)
            assertEquals(5, identity.serverIndex, codec)
        }
    }

    @Test
    fun `materialized PGS artifact uses a server sidecar identity`() {
        val identity = tvSubtitleIdentity(
            PlayerSubtitleInfo(
                index = 8,
                language = "en",
                codec = "hdmv_pgs_subtitle",
                label = "English PGS",
                source = "server_artifact",
                forced = false,
                url = "/stream/s1/subtitles/8.sup",
            ),
        )

        assertIs<SubtitleIdentity.ServerSidecar>(identity)
        assertEquals(8, identity.serverIndex)
    }

    @Test
    fun `T91 remote subtitle intent resolves a typed identity instead of a backend ordinal`() {
        val row = PlayerSubtitleInfo(
            index = 8,
            language = "en",
            codec = "srt",
            label = "English",
            source = "server_artifact",
            forced = false,
            url = "/subtitles/8.srt",
        )
        val mounted = PlayerTrackEntry(
            index = 2,
            label = "English",
            language = "en",
            isSelected = false,
            displayLabel = "English",
            codecOrMime = "srt",
            trackId = "silo-subtitle:8",
        )

        val identity = resolveTvRemoteSubtitleIntent(
            playerOrdinal = 2,
            subtitleTracks = listOf(mounted),
            subtitleRows = listOf(row),
        )

        assertEquals(tvSubtitleIdentity(row), identity)
    }

    @Test
    fun `T91 remote Off intent is typed and does not need mounted tracks`() {
        assertEquals(
            SubtitleIdentity.Off,
            resolveTvRemoteSubtitleIntent(
                playerOrdinal = -1,
                subtitleTracks = emptyList(),
                subtitleRows = emptyList(),
            ),
        )
    }

    @Test
    fun `T92 remote audio intent resolves the catalog ordinal for the adapter`() {
        // Audio is addressed by ORDINAL — the wire carries no audio index, so
        // AudioTrack.index is its 0 default and reading it made every remote
        // pick request track 0.
        val audioTracks = listOf(
            AudioTrack(language = "en", codec = "aac"),
            AudioTrack(language = "ja", codec = "ac3"),
        )

        assertEquals(1, resolveTvRemoteAudioIntent(playerOrdinal = 1, audioTracks = audioTracks))
        assertEquals(0, resolveTvRemoteAudioIntent(playerOrdinal = 0, audioTracks = audioTracks))
        assertEquals(null, resolveTvRemoteAudioIntent(playerOrdinal = 5, audioTracks = audioTracks))
    }

    @Test
    fun `invalid pre-mount remote intents remain unresolved rather than disabling subtitles`() {
        assertEquals(
            null,
            resolveTvRemoteSubtitleIntent(
                playerOrdinal = 4,
                subtitleTracks = emptyList(),
                subtitleRows = emptyList(),
            ),
        )
        assertEquals(
            null,
            resolveTvRemoteAudioIntent(
                playerOrdinal = 4,
                audioTracks = emptyList(),
            ),
        )
    }

    // ---- Single-owner subtitle selection -----------------------------------
    //
    // Regression: TV had two independent subtitle authorities. The legacy
    // ordinal auto path selected a text track straight at the player while the
    // transaction adapter's committed identity never moved, so on an "English –
    // Always" profile the PGS track rendered on screen and the HUD said "Off".
    // Everything below pins the pieces of the single-owner flow.

    @Test
    fun `english always resolves an embedded PGS track to the same identity the HUD checks`() {
        val row = embeddedPgsRow()
        val track = embeddedPgsTrack()

        val selection = resolveAutoSubtitleSelection(
            audioTracks = listOf(
                PlayerTrackEntry(
                    index = 0,
                    label = "English",
                    language = "en",
                    isSelected = true,
                ),
            ),
            subtitleTracks = listOf(track),
            preferredLanguage = "en",
            subtitleMode = "always",
            showForced = true,
        )

        // Bitmap tracks stay deprioritised-but-allowed: it is the only English
        // candidate, so Always must still pick it.
        val selected = assertIs<SubtitleAutoSelection.Select>(selection)
        assertEquals(track.index, selected.index)

        val identity = tvMountedSubtitleIdentity(track, listOf(track), listOf(row))
        assertEquals(tvSubtitleIdentity(row), identity)
        assertIs<SubtitleIdentity.Embedded>(identity)

        // The identity the auto path commits is the identity the HUD ticks.
        val presentation = buildTvSubtitleHudPresentation(
            options = buildTvSubtitleHudOptions(
                subtitleUrls = listOf(row),
                subtitleTracks = listOf(track),
            ),
            committedIdentity = identity,
            pendingIdentity = null,
            hudOpen = true,
            focusedStableId = null,
        )
        val checked = presentation.rows.single { it.checked }
        assertEquals(identity, checked.identity)
        assertEquals(1, presentation.rows.count { it.checked })
    }

    @Test
    fun `an automatic pick does not write the durable subtitle preference`() {
        val identity = tvSubtitleIdentity(embeddedPgsRow())

        assertEquals(
            TrackSelectionFingerprintUpdate.Preserve,
            tvSubtitlePersistenceUpdate(
                committedIdentity = identity,
                automaticIdentity = identity,
            ),
        )
    }

    @Test
    fun `a viewer pick writes the durable subtitle preference`() {
        val automatic = tvSubtitleIdentity(embeddedPgsRow())
        val chosen = SubtitleIdentity.Off

        assertEquals(
            TrackSelectionFingerprintUpdate.Set(encodeSubtitleIdentityPreference(chosen)),
            // The viewer choosing clears the automatic marker in the ViewModel;
            // a stale marker for a different identity must not suppress the write.
            tvSubtitlePersistenceUpdate(committedIdentity = chosen, automaticIdentity = automatic),
        )
    }

    @Test
    fun `reconciliation adopts a text track selected outside the adapter`() {
        val row = embeddedPgsRow()
        val track = embeddedPgsTrack().copy(isSelected = true)

        assertEquals(
            tvSubtitleIdentity(row),
            tvExternalSubtitleAdoption(
                subtitleTracks = listOf(track),
                subtitleRows = listOf(row),
                committedIdentity = SubtitleIdentity.Off,
                pendingIdentity = null,
                selectionInFlight = false,
            ),
        )
    }

    @Test
    fun `reconciliation stands down when the adapter already agrees or is mid-flight`() {
        val row = embeddedPgsRow()
        val track = embeddedPgsTrack().copy(isSelected = true)
        val identity = tvSubtitleIdentity(row)

        assertEquals(
            null,
            tvExternalSubtitleAdoption(
                subtitleTracks = listOf(track),
                subtitleRows = listOf(row),
                committedIdentity = identity,
                pendingIdentity = null,
                selectionInFlight = false,
            ),
        )
        assertEquals(
            null,
            tvExternalSubtitleAdoption(
                subtitleTracks = listOf(track),
                subtitleRows = listOf(row),
                committedIdentity = SubtitleIdentity.Off,
                pendingIdentity = null,
                selectionInFlight = true,
            ),
        )
        assertEquals(
            null,
            tvExternalSubtitleAdoption(
                subtitleTracks = listOf(track),
                subtitleRows = listOf(row),
                committedIdentity = SubtitleIdentity.Off,
                pendingIdentity = identity,
                selectionInFlight = false,
            ),
        )
        // Nothing selected is nothing to reconcile — not an implicit "Off".
        assertEquals(
            null,
            tvExternalSubtitleAdoption(
                subtitleTracks = listOf(embeddedPgsTrack()),
                subtitleRows = listOf(row),
                committedIdentity = identity,
                pendingIdentity = null,
                selectionInFlight = false,
            ),
        )
    }

    // ---- Already-mounted picks must never replan ---------------------------
    //
    // Regression: protocol v3 types EVERY non-burn-in inventory row
    // `delivery = sidecar`, including the row that merely describes a track
    // muxed into a direct-play stream. The launch auto-pick of an embedded PGS
    // track therefore resolved to a ServerSidecar identity, which the mount
    // resolver matches by its authored `silo-subtitle:N` id alone — so the
    // adapter was told the track on screen was not mounted and staged a server
    // replan for it: new session, media-item swap, seconds of rebuffering, and
    // a duplicate of the same PGS track re-extracted as a sidecar.

    @Test
    fun `a v3 row for a muxed track is a sidecar identity that still resolves in place`() {
        val row = planEmbeddedPgsRow()
        val track = embeddedPgsTrack()

        // The identity is genuinely ServerSidecar — this is the shape the HUD,
        // persistence and the adapter all carry, and it is not being changed.
        val identity = assertIs<SubtitleIdentity.ServerSidecar>(tvSubtitleIdentity(row))
        assertEquals(identity, tvMountedSubtitleIdentity(track, listOf(track), listOf(row)))

        assertEquals(
            track.index,
            tvResolveMountedSubtitleTrack(
                identity = identity,
                subtitleRows = listOf(row),
                mounted = listOf(track.toMountedTvSubtitleTrack()),
            )?.index,
        )
    }

    @Test
    fun `a sidecar the player has not mounted still resolves to nothing`() {
        val row = unmountedSidecarRow()

        assertEquals(
            null,
            tvResolveMountedSubtitleTrack(
                identity = tvSubtitleIdentity(row),
                subtitleRows = listOf(planEmbeddedPgsRow(), row),
                mounted = listOf(embeddedPgsTrack().toMountedTvSubtitleTrack()),
            ),
        )
    }

    @Test
    fun `english always commits an already-mounted PGS track in place`() = runTest {
        val row = planEmbeddedPgsRow()
        val track = embeddedPgsTrack()
        val identity = tvSubtitleIdentity(row)
        val harness = harness(backgroundScope, rows = listOf(row), mounted = listOf(track))

        harness.adapter.selectAuto(identity)
        runCurrent()

        assertTrue(
            harness.staged.isEmpty(),
            "an already-mounted track must not ask the server to replan",
        )
        assertEquals(identity, harness.adapter.snapshot.localMountIdentity)

        // The mount the adapter armed resolves onto the muxed ordinal…
        val remount = SubtitleRemountReselection()
        remount.arm(identity, generation = 1L)
        val event = assertIs<TvSubtitleRemountEvent.Select>(
            remount.consume(
                subtitleTracks = listOf(track),
                subtitleRows = listOf(row),
                snapshotKey = "mounted",
                settled = true,
            ),
        )
        assertEquals(track.index, event.trackIndex)

        // …and acknowledging it commits the identity the HUD ticks.
        harness.adapter.reportMountedSelection(
            identity = identity,
            selected = true,
            snapshotKey = "mounted",
            settled = true,
        )
        runCurrent()

        assertEquals(identity, harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.staged.isEmpty())
        val presentation = buildTvSubtitleHudPresentation(
            options = buildTvSubtitleHudOptions(
                subtitleUrls = listOf(row),
                subtitleTracks = listOf(track),
            ),
            committedIdentity = harness.adapter.snapshot.committedIdentity,
            pendingIdentity = harness.adapter.snapshot.pendingIdentity,
            hudOpen = true,
            focusedStableId = null,
        )
        assertEquals(identity, presentation.rows.single { it.checked }.identity)
    }

    @Test
    fun `an unmounted server sidecar still stages a replan`() = runTest {
        val mountedRow = planEmbeddedPgsRow()
        val target = unmountedSidecarRow()
        val harness = harness(
            backgroundScope,
            rows = listOf(mountedRow, target),
            mounted = listOf(embeddedPgsTrack()),
        )

        harness.adapter.selectAuto(tvSubtitleIdentity(target))
        runCurrent()

        assertEquals(
            listOf(target.index),
            harness.staged.map { it.subtitleTrackIndex },
            "a subtitle the player has not loaded must still reach the server",
        )
        assertEquals(null, harness.adapter.snapshot.localMountIdentity)
    }

    private class PolicyHarness(
        val adapter: TvSubtitleTransactionAdapter,
        val staged: List<TvSubtitleStageRequest>,
    )

    /**
     * Wires the adapter to the PRODUCTION mountability rule — the same
     * row-aware resolution `TvPlayerViewModel` installs — so these tests fail
     * if that rule stops recognising a mounted track.
     */
    private fun harness(
        scope: CoroutineScope,
        rows: List<PlayerSubtitleInfo>,
        mounted: List<PlayerTrackEntry>,
    ): PolicyHarness {
        val staged = mutableListOf<TvSubtitleStageRequest>()
        val adapter = TvSubtitleTransactionAdapter(
            scope = scope,
            stagedPort = object : TvSubtitleStagedReplanPort {
                override suspend fun stage(
                    request: TvSubtitleStageRequest,
                ): ApiResult<TvStagedSubtitleCandidate> {
                    staged += request
                    return ApiResult.Error(500, "unused", "Staging is not exercised here.")
                }

                override suspend fun commit(
                    candidate: TvStagedSubtitleCandidate,
                ): ApiResult<TvSubtitleCommittedPlayback> =
                    error("The staged replan path must not commit in these tests.")

                override suspend fun discard(candidate: TvStagedSubtitleCandidate) = Unit

                override suspend fun abandonCommitted(playback: TvSubtitleCommittedPlayback) = Unit
            },
            persistencePort = object : TvSubtitlePersistencePort {
                override suspend fun persist(
                    committed: CommittedSubtitle,
                    context: TvSubtitlePlaybackContext,
                ): Boolean = true
            },
            durablePersistenceScope = scope,
            settlementScope = scope,
            hasMountableTracks = { mounted.isNotEmpty() },
            isLocallyMountable = { identity ->
                tvResolveMountedSubtitleTrack(
                    identity = identity,
                    subtitleRows = rows,
                    mounted = mounted.map { it.toMountedTvSubtitleTrack() },
                ) != null
            },
        )
        adapter.resetContent(
            context = TvSubtitlePlaybackContext(
                contentId = "movie-1",
                mediaFileId = 22,
                versionId = "22:plan-1",
                sessionId = "s1",
                positionSeconds = 236.816,
                audioTrackIndex = 0,
                qualityPreference = "original",
                subtitleTracks = rows,
            ),
            committedIdentity = SubtitleIdentity.Off,
        )
        return PolicyHarness(adapter, staged)
    }

    /** An embedded PGS track exactly as a v3 plan describes it: delivery `sidecar`. */
    private fun planEmbeddedPgsRow() = embeddedPgsRow().copy(
        url = "/stream/s1/subtitles/8.sup",
        catalogLabel = "English (SDH)",
        catalogSource = "embedded",
        mediaTrackId = null,
        serverTrackId = "file:22:subtitle:8",
        serverDelivery = "sidecar",
    )

    private fun unmountedSidecarRow() = PlayerSubtitleInfo(
        index = 9,
        language = "nld",
        codec = "subrip",
        label = "Dutch",
        source = "external",
        forced = false,
        url = "/stream/s1/subtitles/9.vtt",
        catalogLabel = "Dutch",
        catalogSource = "external",
        serverTrackId = "file:22:subtitle:9",
        serverDelivery = "sidecar",
    )

    private fun embeddedPgsRow() = PlayerSubtitleInfo(
        index = 8,
        language = "eng",
        codec = "hdmv_pgs_subtitle",
        label = "English (SDH)",
        source = "embedded",
        forced = false,
        url = "",
        mediaTrackId = "1:pgs:8",
    )

    private fun embeddedPgsTrack() = PlayerTrackEntry(
        index = 3,
        label = "English (SDH)",
        language = "en",
        isSelected = false,
        displayLabel = "English (SDH)",
        codecOrMime = "application/pgs",
        isHearingImpaired = true,
        trackId = "1:pgs:8",
    )

    private fun downloadedRow(
        index: Int,
        downloadId: Int?,
    ) = PlayerSubtitleInfo(
        index = index,
        language = "en",
        codec = "vtt",
        label = "English",
        source = "downloaded",
        forced = false,
        url = "/subtitles/${downloadId ?: "legacy"}.vtt",
        downloadId = downloadId,
        mediaTrackId = downloadId?.let { "silo-downloaded-subtitle:$it" },
    )
}
