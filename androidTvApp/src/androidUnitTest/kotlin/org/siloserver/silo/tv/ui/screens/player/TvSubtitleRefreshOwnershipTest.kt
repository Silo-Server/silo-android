package org.siloserver.silo.tv.ui.screens.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.playback.CommittedSubtitle
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TvSubtitleRefreshOwnershipTest {
    @Test
    fun `download refresh merges and selects only the returned downloadId`() = runTest {
        val harness = harness(backgroundScope)
        val owner = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Download)

        assertTrue(
            harness.adapter.applyRefresh(
                owner = owner,
                subtitleTracks = listOf(downloaded(90), downloaded(91)),
                autoSelectDownloadId = 91,
            ),
        )
        runCurrent()

        assertEquals(listOf(90, 91), harness.adapter.snapshot.subtitleTracks.mapNotNull { it.downloadId })
        assertEquals(downloadedIdentity(91), harness.adapter.snapshot.pendingIdentity)
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
    }

    @Test
    fun `AI completion refresh merges and selects only the returned downloadId`() = runTest {
        val harness = harness(backgroundScope)
        val owner = harness.adapter.beginRefresh(TvSubtitleRefreshSource.AiCompletion)

        assertTrue(
            harness.adapter.applyRefresh(
                owner = owner,
                subtitleTracks = listOf(downloaded(40), downloaded(41)),
                autoSelectDownloadId = 40,
            ),
        )
        runCurrent()

        assertEquals(downloadedIdentity(40), harness.adapter.snapshot.pendingIdentity)
        assertEquals(downloadedIdentity(40), harness.adapter.snapshot.localMountIdentity)
    }

    @Test
    fun `realtime refresh preserves committed identity without optimistic selection`() = runTest {
        val harness = harness(backgroundScope)
        val owner = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Realtime)

        assertTrue(
            harness.adapter.applyRefresh(
                owner = owner,
                subtitleTracks = listOf(downloaded(91)),
                autoSelectDownloadId = null,
            ),
        )

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertEquals(1L, harness.adapter.snapshot.subtitleRefreshNonce)
    }

    @Test
    fun `refresh failure leaves rows nonce and committed identity unchanged`() = runTest {
        val harness = harness(backgroundScope)
        val before = harness.adapter.snapshot
        val owner = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Download)

        assertTrue(harness.adapter.completeRefreshFailure(owner, "network failed"))

        assertEquals(before.subtitleTracks, harness.adapter.snapshot.subtitleTracks)
        assertEquals(before.subtitleRefreshNonce, harness.adapter.snapshot.subtitleRefreshNonce)
        assertEquals(before.committedIdentity, harness.adapter.snapshot.committedIdentity)
    }

    @Test
    fun `operation-local refresh cancellation leaves the worker available`() = runTest {
        val harness = harness(backgroundScope)
        val cancelled = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Download)
        harness.adapter.cancelRefresh(cancelled)
        val next = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Realtime)

        assertFalse(harness.adapter.ownsRefresh(cancelled))
        assertTrue(harness.adapter.applyRefresh(next, listOf(downloaded(91)), null))
        assertEquals(listOf(91), harness.adapter.snapshot.subtitleTracks.mapNotNull { it.downloadId })
    }

    @Test
    fun `older download refresh cannot overwrite newer manual intent`() = runTest {
        val harness = harness(backgroundScope)
        val old = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Download)

        harness.adapter.select(sidecar(4))

        assertFalse(harness.adapter.applyRefresh(old, listOf(downloaded(91)), 91))
        assertEquals(sidecar(4), harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.adapter.snapshot.subtitleTracks.none { it.downloadId == 91 })
    }

    @Test
    fun `older AI refresh cannot overwrite newer download refresh`() = runTest {
        val harness = harness(backgroundScope)
        val ai = harness.adapter.beginRefresh(TvSubtitleRefreshSource.AiCompletion)
        val download = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Download)

        assertFalse(harness.adapter.applyRefresh(ai, listOf(downloaded(40)), 40))
        assertTrue(harness.adapter.applyRefresh(download, listOf(downloaded(91)), 91))
        assertEquals(listOf(91), harness.adapter.snapshot.subtitleTracks.mapNotNull { it.downloadId })
    }

    @Test
    fun `realtime refresh for an old session cannot publish`() = runTest {
        val harness = harness(backgroundScope)
        val old = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Realtime)

        harness.adapter.replaceSession("s2")

        assertFalse(harness.adapter.applyRefresh(old, listOf(downloaded(91, sessionId = "s1")), null))
        assertTrue(harness.adapter.snapshot.subtitleTracks.none { it.downloadId == 91 })
    }

    @Test
    fun `content reset while refresh is suspended invalidates the response`() = runTest {
        val harness = harness(backgroundScope)
        val old = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Download)

        harness.adapter.resetContent(
            context(contentId = "content-2", versionId = "version-2", sessionId = "s2"),
            committedIdentity = sidecar(8),
        )

        assertFalse(harness.adapter.applyRefresh(old, listOf(downloaded(91)), 91))
        assertEquals(sidecar(8), harness.adapter.snapshot.committedIdentity)
    }

    @Test
    fun `exit invalidates every refresh source`() = runTest {
        TvSubtitleRefreshSource.entries.forEach { source ->
            val harness = harness(backgroundScope)
            val owner = harness.adapter.beginRefresh(source)

            harness.adapter.invalidate()

            assertFalse(harness.adapter.applyRefresh(owner, listOf(downloaded(91)), 91))
        }
    }

    @Test
    fun `duplicate downloaded IDs safely miss during refresh auto-selection`() = runTest {
        val harness = harness(backgroundScope)
        val owner = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Download)

        assertTrue(
            harness.adapter.applyRefresh(
                owner,
                listOf(
                    downloaded(91, label = "English"),
                    downloaded(91, label = "English forced").copy(forced = true),
                ),
                autoSelectDownloadId = 91,
            ),
        )

        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
    }

    @Test
    fun `newest authoritative empty refresh clears every stale row`() = runTest {
        val harness = harness(backgroundScope, tracks = listOf(server(3), downloaded(91)))
        val owner = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Realtime)

        assertTrue(harness.adapter.applyRefresh(owner, emptyList(), autoSelectDownloadId = null))

        assertEquals(emptyList(), harness.adapter.snapshot.subtitleTracks)
        assertEquals(1L, harness.adapter.snapshot.subtitleRefreshNonce)
    }

    @Test
    fun `accepted authoritative refresh preserves the exact server URL`() = runTest {
        val harness = harness(backgroundScope)
        val owner = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Download)

        assertTrue(
            harness.adapter.applyRefresh(
                owner,
                listOf(downloaded(91, sessionId = "stale")),
                autoSelectDownloadId = null,
            ),
        )

        assertEquals(
            "https://silo.test/api/v1/stream/stale/subtitles/91.vtt?token=stale",
            harness.adapter.snapshot.subtitleTracks.single { it.downloadId == 91 }.url,
        )
    }

    @Test
    fun `quality intent during refresh invalidates the older refresh owner`() = runTest {
        val harness = harness(backgroundScope)
        val owner = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Download)

        harness.adapter.selectQuality("720p")

        assertFalse(harness.adapter.applyRefresh(owner, listOf(downloaded(91)), 91))
        assertEquals("720p", harness.adapter.snapshot.transition.pending?.qualityPreference)
    }

    private fun harness(
        scope: CoroutineScope,
        tracks: List<PlayerSubtitleInfo> = listOf(server(3)),
    ): Harness {
        val adapter = TvSubtitleTransactionAdapter(
            scope = scope,
            stagedPort = SuspendedPort(),
            persistencePort = NoopPersistence(),
        )
        adapter.resetContent(context(tracks = tracks), committedIdentity = sidecar(3))
        return Harness(adapter)
    }

    private fun context(
        contentId: String = "content-1",
        versionId: String = "version-1",
        sessionId: String? = "s1",
        tracks: List<PlayerSubtitleInfo> = listOf(server(3)),
    ): TvSubtitlePlaybackContext = TvSubtitlePlaybackContext(
        contentId = contentId,
        mediaFileId = 11,
        versionId = versionId,
        sessionId = sessionId,
        positionSeconds = 42.0,
        audioTrackIndex = 2,
        qualityPreference = "auto",
        subtitleTracks = tracks,
        outputRouteGeneration = 0,
        writeScope = tvTestPlaybackWriteScope,
    )

    private fun server(index: Int): PlayerSubtitleInfo = PlayerSubtitleInfo(
        index = index,
        language = "en",
        codec = "webvtt",
        label = "Server $index",
        source = "server_artifact",
        url = "https://silo.test/api/v1/stream/s1/subtitles/$index.vtt",
    )

    private fun downloaded(
        id: Int,
        label: String = "English",
        sessionId: String = "s1",
    ): PlayerSubtitleInfo = PlayerSubtitleInfo(
        index = id,
        language = "en",
        codec = "webvtt",
        label = label,
        source = "downloaded",
        url = "https://silo.test/api/v1/stream/$sessionId/subtitles/$id.vtt?token=$sessionId",
        downloadId = id,
    )

    private fun downloadedIdentity(id: Int): SubtitleIdentity.Downloaded =
        tvSubtitleIdentity(downloaded(id)) as SubtitleIdentity.Downloaded

    private fun sidecar(index: Int): SubtitleIdentity = SubtitleIdentity.ServerSidecar(index)

    private data class Harness(val adapter: TvSubtitleTransactionAdapter)

    private class SuspendedPort : TvSubtitleStagedReplanPort {
        override suspend fun stage(
            request: TvSubtitleStageRequest,
        ): ApiResult<TvStagedSubtitleCandidate> = awaitCancellation()

        override suspend fun commit(
            candidate: TvStagedSubtitleCandidate,
        ): ApiResult<TvSubtitleCommittedPlayback> = awaitCancellation()

        override suspend fun discard(candidate: TvStagedSubtitleCandidate) = Unit

        override suspend fun abandonCommitted(playback: TvSubtitleCommittedPlayback) = Unit
    }

    private class NoopPersistence : TvSubtitlePersistencePort {
        override suspend fun persist(
            committed: CommittedSubtitle,
            context: TvSubtitlePlaybackContext,
        ) = true
    }
}
