package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.encodeSubtitleIdentityPreference
import org.siloserver.silo.repository.port.TrackSelectionFingerprintUpdate
import kotlin.test.assertIs

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
