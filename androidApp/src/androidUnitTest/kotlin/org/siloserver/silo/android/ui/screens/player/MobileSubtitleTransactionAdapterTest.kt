package org.siloserver.silo.android.ui.screens.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.siloserver.silo.model.playback.CommittedSubtitle
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.port.PlaybackWriteScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MobileSubtitleTransactionAdapterTest {
    @Test
    fun `pre-playback server selection commits without staging`() = runTest {
        val harness = harness(backgroundScope, sessionId = null)

        harness.adapter.select(sidecar(4))
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.port.requests.isEmpty())
        assertEquals(listOf(sidecar(4)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `slow older preference write cannot overwrite newer commit`() = runTest {
        val harness = harness(backgroundScope, sessionId = null)
        harness.persistence.suspendFirst = true

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.persistence.awaitFirstStarted()
        harness.adapter.select(sidecar(5))
        runCurrent()
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.persistence.releaseFirst()
        runCurrent()

        assertEquals(
            listOf(sidecar(4), sidecar(5)),
            harness.persistence.persisted.map { it.identity },
        )
    }

    @Test
    fun `A remains committed while B stages and commits`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(4), harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.adapter.snapshot.subtitleApplying)
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.port.completeStage(candidate("b", 4))
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertEquals(listOf("b"), harness.port.committed)
        assertEquals(listOf(sidecar(4)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `A to B to C discards B and commits only latest C`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.adapter.select(sidecar(5))
        runCurrent()
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)

        harness.port.completeStage(candidate("b", 4))
        runCurrent()
        assertEquals(listOf("b"), harness.port.discarded)
        assertEquals(listOf(4, 5), harness.port.requests.map { it.subtitleTrackIndex })

        harness.port.completeStage(candidate("c", 5))
        runCurrent()
        assertEquals(sidecar(5), harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf("c"), harness.port.committed)
        assertEquals(listOf(sidecar(5)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `subtitle then audio merge into one latest reducer transaction`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.adapter.selectAudio(7)
        harness.port.completeStage(candidate("subtitle-only", 4, selectedAudioIndex = 2))
        runCurrent()

        assertEquals(listOf(2, 7), harness.port.requests.map { it.audioTrackIndex })
        assertEquals(listOf(4, 4), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf("subtitle-only"), harness.port.discarded)

        harness.port.completeStage(candidate("combined", 4, selectedAudioIndex = 7))
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals(7, harness.adapter.snapshot.transition.committed.audioTrackIndex)
        assertEquals(listOf(7), harness.persistence.persisted.map { it.audioTrackIndex })
    }

    @Test
    fun `audio then subtitle merge into one latest reducer transaction`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.selectAudio(7)
        runCurrent()
        harness.adapter.select(sidecar(4))
        harness.port.completeStage(candidate("audio-only", 3, selectedAudioIndex = 7))
        runCurrent()

        assertEquals(listOf(7, 7), harness.port.requests.map { it.audioTrackIndex })
        assertEquals(listOf(3, 4), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf("audio-only"), harness.port.discarded)

        harness.port.completeStage(candidate("combined", 4, selectedAudioIndex = 7))
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals(7, harness.adapter.snapshot.transition.committed.audioTrackIndex)
    }

    @Test
    fun `local then audio before mount keeps one client-owned transaction`() = runTest {
        val downloaded = downloadedIdentity()
        val row = downloadedTrack(
            index = 9,
            downloadId = downloaded.downloadId,
            url = "https://silo.test/api/v1/stream/s1/subtitles/9.vtt",
        )
        val harness = harness(backgroundScope, tracks = listOf(row))

        harness.adapter.select(downloaded)
        harness.adapter.selectAudio(7)
        runCurrent()

        assertEquals(downloaded, harness.adapter.snapshot.pendingIdentity)
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)
        assertEquals(-1, harness.port.requests.single().subtitleTrackIndex)
        assertEquals(7, harness.port.requests.single().audioTrackIndex)
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.adapter.reportMountedSelection(
            identity = downloaded,
            selected = true,
            snapshotKey = "pre-adoption-download",
            settled = true,
        )
        runCurrent()
        assertEquals(downloaded, harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.port.completeStage(clientOwnedCandidate("local-audio", audioIndex = 7))
        runCurrent()
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)
        harness.adapter.reportMountedSelection(
            identity = downloaded,
            selected = true,
            snapshotKey = "post-adoption-download",
            settled = true,
        )
        runCurrent()

        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertEquals(7, harness.adapter.snapshot.transition.committed.audioTrackIndex)
        assertEquals(
            listOf(CommittedSubtitle(downloaded, audioTrackIndex = 7, qualityPreference = "auto")),
            harness.persistence.persisted,
        )
    }

    @Test
    fun `stage failure after early local mount clears applying owner`() = runTest {
        val downloaded = downloadedIdentity()
        val harness = harness(backgroundScope)
        prepareEarlyMountedLocalAudioTransaction(harness, downloaded)

        harness.port.failStage(ApiResult.NetworkError(IllegalStateException("stage failed")))
        runCurrent()

        assertFalse(harness.adapter.snapshot.subtitleApplying)
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `validation failure after early local mount clears applying owner`() = runTest {
        val downloaded = downloadedIdentity()
        val harness = harness(backgroundScope)
        prepareEarlyMountedLocalAudioTransaction(harness, downloaded)

        harness.port.completeStage(clientOwnedCandidate("invalid", audioIndex = 2))
        runCurrent()

        assertFalse(harness.adapter.snapshot.subtitleApplying)
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `validation discard exception cannot skip rollback or kill worker`() = runTest {
        val downloaded = downloadedIdentity()
        val harness = harness(backgroundScope)
        prepareEarlyMountedLocalAudioTransaction(harness, downloaded)
        harness.port.discardThrowable = IllegalStateException("discard failed")

        harness.port.completeStage(clientOwnedCandidate("invalid", audioIndex = 2))
        runCurrent()

        assertFalse(harness.adapter.snapshot.subtitleApplying)
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)

        harness.adapter.select(sidecar(5))
        runCurrent()
        assertEquals(listOf(-1, 5), harness.port.requests.map { it.subtitleTrackIndex })
        harness.port.completeStage(candidate("next", selectedIndex = 5))
        runCurrent()
        assertEquals(sidecar(5), harness.adapter.snapshot.committedIdentity)
    }

    @Test
    fun `operation local stale discard cancellation is contained and worker survives`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.adapter.select(sidecar(5))
        harness.port.discardThrowable = CancellationException("discard cancelled locally")
        harness.port.completeStage(candidate("stale", selectedIndex = 4))
        runCurrent()

        assertEquals(listOf(4, 5), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)

        harness.port.completeStage(candidate("next", selectedIndex = 5))
        runCurrent()
        assertEquals(sidecar(5), harness.adapter.snapshot.committedIdentity)
    }

    @Test
    fun `commit failure after early local mount clears applying owner`() = runTest {
        val downloaded = downloadedIdentity()
        val harness = harness(backgroundScope)
        prepareEarlyMountedLocalAudioTransaction(harness, downloaded)
        harness.port.commitFailure = ApiResult.Error(
            code = 503,
            error = "commit_failed",
            message = "commit failed",
        )

        harness.port.completeStage(clientOwnedCandidate("commit-failure", audioIndex = 7))
        runCurrent()

        assertFalse(harness.adapter.snapshot.subtitleApplying)
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `adoption failure after early local mount remounts prior committed local identity`() = runTest {
        val oldDownloaded = downloadedIdentity()
        val newDownloaded = SubtitleIdentity.Downloaded(
            downloadId = 913,
            media = media(
                trackId = "silo-downloaded-subtitle:913",
                label = "French",
                language = "fr",
                codec = "webvtt",
            ),
        )
        val harness = harness(
            backgroundScope,
            adoption = AdoptionControl(failure = IllegalStateException("adoption failed")),
        )
        harness.adapter.resetContent(
            context(sessionId = "s1"),
            committedIdentity = oldDownloaded,
        )
        prepareEarlyMountedLocalAudioTransaction(harness, newDownloaded)

        harness.port.completeStage(clientOwnedCandidate("adoption-failure", audioIndex = 7))
        runCurrent()

        assertEquals(oldDownloaded, harness.adapter.snapshot.committedIdentity)
        assertEquals(oldDownloaded, harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
        assertTrue(harness.adapter.snapshot.subtitleApplying)

        harness.adapter.reportMountedSelection(
            identity = oldDownloaded,
            selected = true,
            snapshotKey = "prior-identity-restored",
            settled = true,
        )
        runCurrent()

        assertFalse(harness.adapter.snapshot.subtitleApplying)
        assertNull(harness.adapter.snapshot.localMountIdentity)
    }

    @Test
    fun `operation local stage cancellation rolls back exact local owner and keeps worker alive`() = runTest {
        val downloaded = downloadedIdentity()
        val harness = harness(backgroundScope)
        prepareEarlyMountedLocalAudioTransaction(harness, downloaded)

        harness.port.cancelStage("stage request cancelled locally")
        runCurrent()

        assertFalse(harness.adapter.snapshot.subtitleApplying)
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)

        harness.adapter.select(sidecar(5))
        runCurrent()
        assertEquals(listOf(-1, 5), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)
    }

    @Test
    fun `operation local commit cancellation rolls back exact local owner and keeps worker alive`() = runTest {
        val downloaded = downloadedIdentity()
        val harness = harness(backgroundScope)
        prepareEarlyMountedLocalAudioTransaction(harness, downloaded)
        harness.port.commitThrowable = CancellationException("commit request cancelled locally")

        harness.port.completeStage(clientOwnedCandidate("cancelled-commit", audioIndex = 7))
        runCurrent()

        assertFalse(harness.adapter.snapshot.subtitleApplying)
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)

        harness.adapter.select(sidecar(5))
        runCurrent()
        assertEquals(listOf(-1, 5), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)
    }

    @Test
    fun `parent cancellation stops stage worker without converting teardown into transaction failure`() = runTest {
        val parent = Job()
        val harness = harness(CoroutineScope(coroutineContext + parent))

        harness.adapter.select(sidecar(4))
        runCurrent()
        parent.cancel(CancellationException("adapter owner stopped"))
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.pendingIdentity)
        assertNull(harness.adapter.snapshot.failureMessage)

        harness.adapter.select(sidecar(5))
        runCurrent()
        assertEquals(listOf(4), harness.port.requests.map { it.subtitleTrackIndex })
    }

    @Test
    fun `audio then local while staging restages combined client-owned transaction`() = runTest {
        val downloaded = downloadedIdentity()
        val harness = harness(backgroundScope)

        harness.adapter.selectAudio(7)
        runCurrent()
        harness.adapter.select(downloaded)
        runCurrent()
        harness.port.completeStage(candidate("audio-only", 3, selectedAudioIndex = 7))
        runCurrent()

        assertEquals(listOf(3, -1), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf(7, 7), harness.port.requests.map { it.audioTrackIndex })
        assertEquals(listOf("audio-only"), harness.port.discarded)

        harness.port.completeStage(clientOwnedCandidate("audio-local", audioIndex = 7))
        runCurrent()
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)
        harness.adapter.reportMountedSelection(
            identity = downloaded,
            selected = true,
            snapshotKey = "audio-local-mounted",
            settled = true,
        )
        runCurrent()

        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertEquals(7, harness.adapter.snapshot.transition.committed.audioTrackIndex)
        assertEquals(downloaded, harness.persistence.persisted.single().identity)
        assertEquals(7, harness.persistence.persisted.single().audioTrackIndex)
    }

    @Test
    fun `modern downloaded row without source keeps server subtitles off during audio replan`() = runTest {
        val row = downloadedTrack(
            index = 9,
            downloadId = 312,
            url = "https://silo.test/api/v1/stream/s1/subtitles/9.vtt",
        ).copy(source = null, catalogSource = null)
        val identity = mobileSubtitleIdentity(row)
        val harness = harness(backgroundScope, tracks = listOf(row))

        assertTrue(identity is SubtitleIdentity.Downloaded)
        harness.adapter.selectAudio(7)
        runCurrent()
        harness.adapter.select(identity)
        runCurrent()
        harness.port.completeStage(candidate("stale-audio", 3, selectedAudioIndex = 7))
        runCurrent()

        assertEquals(-1, harness.port.requests.last().subtitleTrackIndex)
        assertEquals(7, harness.port.requests.last().audioTrackIndex)
        harness.port.completeStage(clientOwnedCandidate("modern-download", audioIndex = 7))
        runCurrent()
        assertEquals(identity, harness.adapter.snapshot.localMountIdentity)
    }

    @Test
    fun `local then audio while server subtitle stages retains local identity`() = runTest {
        val downloaded = downloadedIdentity()
        val harness = harness(backgroundScope)
        harness.adapter.select(sidecar(4))
        runCurrent()

        harness.adapter.select(downloaded)
        harness.adapter.selectAudio(7)
        runCurrent()
        harness.port.completeStage(candidate("server-subtitle", 4, selectedAudioIndex = 2))
        runCurrent()

        assertEquals(listOf(4, -1), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf(2, 7), harness.port.requests.map { it.audioTrackIndex })
        assertEquals(downloaded, harness.adapter.snapshot.pendingIdentity)
        assertEquals(listOf("server-subtitle"), harness.port.discarded)
    }

    @Test
    fun `queued local then audio during adoption preserves both intents`() = runTest {
        verifyQueuedClientOwnedOrderDuringAdoption(
            scope = backgroundScope,
            mutate = { adapter, downloaded ->
                adapter.select(downloaded)
                adapter.selectAudio(7)
            },
        )
    }

    @Test
    fun `queued audio then local during adoption preserves both intents`() = runTest {
        verifyQueuedClientOwnedOrderDuringAdoption(
            scope = backgroundScope,
            mutate = { adapter, downloaded ->
                adapter.selectAudio(7)
                adapter.select(downloaded)
            },
        )
    }

    @Test
    fun `audio change remounts committed downloaded subtitle without sending client index to server`() = runTest {
        val row = downloadedTrack(
            index = 9,
            downloadId = 312,
            url = "https://silo.test/api/v1/stream/s1/subtitles/9.vtt",
        )
        val downloaded = SubtitleIdentity.Downloaded(
            downloadId = 312,
            media = media(
                trackId = "silo-downloaded-subtitle:312",
                language = "en",
                codec = "webvtt",
            ),
        )
        val harness = harness(backgroundScope, tracks = listOf(row))
        harness.adapter.resetContent(
            context(sessionId = "s1", tracks = listOf(row)),
            committedIdentity = downloaded,
        )

        harness.adapter.selectAudio(7)
        runCurrent()

        assertEquals(-1, harness.port.requests.single().subtitleTrackIndex)
        harness.port.completeStage(
            candidate(
                id = "downloaded-audio",
                selectedIndex = null,
                selectedAudioIndex = 7,
                mode = PlaybackSubtitleModeV3.OFF,
                hasSidecar = false,
            ),
        )
        runCurrent()

        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertEquals(7, harness.adapter.snapshot.transition.committed.audioTrackIndex)
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)
        assertEquals(
            312,
            harness.committedPlaybacks.single().subtitleTracks.single().downloadId,
        )
        assertTrue(
            harness.committedPlaybacks.single().subtitleTracks.single().url
                .contains("/stream/s-downloaded-audio/"),
        )
        val persistedBeforeRestoreConfirmation = harness.persistence.persisted.size
        harness.adapter.reportMountedSelection(
            identity = downloaded,
            selected = true,
            snapshotKey = "downloaded-restored",
            settled = true,
        )
        runCurrent()
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertEquals(persistedBeforeRestoreConfirmation + 1, harness.persistence.persisted.size)
        assertEquals(7, harness.persistence.persisted.last().audioTrackIndex)
        assertEquals(downloaded, harness.persistence.persisted.last().identity)
    }

    @Test
    fun `audio change remounts committed local Media3 subtitle with server subtitles off`() = runTest {
        val row = PlayerSubtitleInfo(
            index = 6,
            language = "fr",
            codec = "vtt",
            label = "Legacy local French",
            source = "downloaded",
            forced = false,
            url = "https://silo.test/api/v1/stream/s1/subtitles/6.vtt",
            mediaTrackId = "decoder-text-6",
        )
        val local = SubtitleIdentity.LocalMedia3(
            media(
                trackId = "decoder-text-6",
                label = "Legacy local French",
                language = "fr",
                codec = "webvtt",
            ),
        )
        val harness = harness(backgroundScope, tracks = listOf(row))
        harness.adapter.resetContent(
            context(sessionId = "s1", tracks = listOf(row)),
            committedIdentity = local,
        )

        harness.adapter.selectAudio(7)
        runCurrent()

        assertEquals(-1, harness.port.requests.single().subtitleTrackIndex)
        harness.port.completeStage(
            candidate(
                id = "local-audio",
                selectedIndex = null,
                selectedAudioIndex = 7,
                mode = PlaybackSubtitleModeV3.OFF,
                hasSidecar = false,
            ),
        )
        runCurrent()

        assertEquals(local, harness.adapter.snapshot.committedIdentity)
        assertEquals(local, harness.adapter.snapshot.localMountIdentity)
        assertEquals("decoder-text-6", harness.committedPlaybacks.single().subtitleTracks.single().mediaTrackId)
        assertTrue(
            harness.committedPlaybacks.single().subtitleTracks.single().url
                .contains("/stream/s-local-audio/"),
        )
        harness.adapter.reportMountedSelection(
            identity = local,
            selected = false,
            snapshotKey = "ready-local-restore-miss",
            settled = true,
        )
        runCurrent()
        assertEquals(local, harness.adapter.snapshot.committedIdentity)
        assertEquals(local, harness.adapter.snapshot.localMountIdentity)
        assertNull(harness.adapter.snapshot.failureMessage)

        harness.adapter.reportMountedSelection(
            identity = local,
            selected = false,
            snapshotKey = "ready-local-restore-miss",
            settled = true,
        )
        runCurrent()
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.adapter.snapshot.failureMessage?.contains("mount", ignoreCase = true) == true)
    }

    @Test
    fun `post-adoption local restore timeout keeps committed preference`() = runTest {
        val row = downloadedTrack(
            index = 9,
            downloadId = 312,
            url = "https://silo.test/api/v1/stream/s1/subtitles/9.vtt",
        )
        val downloaded = SubtitleIdentity.Downloaded(
            downloadId = 312,
            media = media(
                trackId = "silo-downloaded-subtitle:312",
                language = "en",
                codec = "webvtt",
            ),
        )
        val harness = harness(backgroundScope, tracks = listOf(row))
        harness.adapter.resetContent(
            context(sessionId = "s1", tracks = listOf(row)),
            committedIdentity = downloaded,
        )
        harness.adapter.selectAudio(7)
        runCurrent()
        harness.port.completeStage(
            candidate(
                id = "restore-timeout",
                selectedIndex = null,
                selectedAudioIndex = 7,
                mode = PlaybackSubtitleModeV3.OFF,
                hasSidecar = false,
            ),
        )
        runCurrent()
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.adapter.snapshot.failureMessage?.contains("mount", ignoreCase = true) == true)
    }

    @Test
    fun `A to Off keeps A mounted until Off candidate commits`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(SubtitleIdentity.Off)
        runCurrent()
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(SubtitleIdentity.Off, harness.adapter.snapshot.pendingIdentity)
        assertEquals(-1, harness.port.requests.single().subtitleTrackIndex)

        harness.port.completeStage(
            candidate(
                id = "off",
                selectedIndex = null,
                mode = PlaybackSubtitleModeV3.OFF,
            ),
        )
        runCurrent()

        assertEquals(SubtitleIdentity.Off, harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(SubtitleIdentity.Off), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `missing sidecar and network failure retain committed selection and preference`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(
            candidate(
                id = "missing-sidecar",
                selectedIndex = 4,
                mode = PlaybackSubtitleModeV3.RENDER,
                hasSidecar = false,
            ),
        )
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertEquals(listOf("missing-sidecar"), harness.port.discarded)
        assertTrue(harness.persistence.persisted.isEmpty())
        assertTrue(harness.adapter.snapshot.failureMessage?.contains("sidecar", ignoreCase = true) == true)

        harness.adapter.select(sidecar(5))
        runCurrent()
        harness.port.failStage(ApiResult.NetworkError(IllegalStateException("offline")))
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `burn-in candidate commits without a sidecar`() = runTest {
        val harness = harness(backgroundScope)
        val burnIn = SubtitleIdentity.ServerBurnIn(8)

        harness.adapter.select(burnIn)
        runCurrent()
        harness.port.completeStage(
            candidate(
                id = "burn-in",
                selectedIndex = 8,
                mode = PlaybackSubtitleModeV3.BURN_IN,
                hasSidecar = false,
            ),
        )
        runCurrent()

        assertEquals(burnIn, harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(burnIn), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `downloaded and embedded choices persist only after mounted resolver confirms`() = runTest {
        val harness = harness(backgroundScope)
        val downloaded = SubtitleIdentity.Downloaded(
            downloadId = 312,
            media = media(
                trackId = "silo-downloaded-subtitle:312",
                label = "English",
                language = "en",
                codec = "webvtt",
            ),
        )
        val embedded = SubtitleIdentity.Embedded(
            serverIndex = 7,
            media = media(
                trackId = "decoder-pgs-7",
                label = "English Forced",
                language = "en",
                codec = "pgs",
                forced = true,
            ),
        )

        harness.adapter.select(downloaded)
        runCurrent()
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(downloaded, harness.adapter.snapshot.pendingIdentity)
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
        harness.adapter.reportMountedSelection(
            identity = downloaded,
            selected = true,
            snapshotKey = "downloaded-mounted",
        )
        runCurrent()
        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)

        harness.adapter.select(embedded)
        runCurrent()
        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertEquals(embedded, harness.adapter.snapshot.localMountIdentity)
        harness.adapter.reportMountedSelection(
            identity = embedded,
            selected = true,
            snapshotKey = "embedded-mounted",
        )
        runCurrent()
        assertEquals(embedded, harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.port.requests.isEmpty())
        assertEquals(
            listOf(downloaded, embedded),
            harness.persistence.persisted.map { it.identity },
        )
    }

    @Test
    fun `first settled local mount snapshot remains provisional`() = runTest {
        val harness = harness(backgroundScope)
        val local = SubtitleIdentity.LocalMedia3(
            media(label = "English", language = "en", codec = "webvtt"),
        )

        harness.adapter.select(local)
        harness.adapter.reportMountedSelection(
            identity = local,
            selected = false,
            snapshotKey = "ready-track-catalog",
            settled = true,
        )
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(local, harness.adapter.snapshot.pendingIdentity)
        assertEquals(local, harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
        assertNull(harness.adapter.snapshot.failureMessage)

        harness.adapter.reportMountedSelection(
            identity = local,
            selected = false,
            snapshotKey = "ready-track-catalog",
            settled = true,
        )
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
        assertTrue(harness.adapter.snapshot.failureMessage?.contains("mount", ignoreCase = true) == true)
    }

    @Test
    fun `changed local mount snapshot must stabilize again before failure`() = runTest {
        val harness = harness(backgroundScope)
        val local = SubtitleIdentity.LocalMedia3(
            media(label = "English", language = "en", codec = "webvtt"),
        )

        harness.adapter.select(local)
        harness.adapter.reportMountedSelection(
            identity = local,
            selected = false,
            snapshotKey = "initial-track-catalog",
            settled = true,
        )
        harness.adapter.reportMountedSelection(
            identity = local,
            selected = false,
            snapshotKey = "changed-track-catalog",
            settled = true,
        )
        runCurrent()

        assertEquals(local, harness.adapter.snapshot.pendingIdentity)
        assertEquals(local, harness.adapter.snapshot.localMountIdentity)
        assertNull(harness.adapter.snapshot.failureMessage)

        harness.adapter.reportMountedSelection(
            identity = local,
            selected = false,
            snapshotKey = "changed-track-catalog",
            settled = true,
        )
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.adapter.snapshot.failureMessage?.contains("mount", ignoreCase = true) == true)
    }

    @Test
    fun `repeated and empty local mount snapshots do not exhaust retry bound`() = runTest {
        val harness = harness(backgroundScope)
        val local = SubtitleIdentity.LocalMedia3(
            media(label = "English", language = "en", codec = "webvtt"),
        )

        harness.adapter.select(local)
        repeat(5) {
            harness.adapter.reportMountedSelection(
                identity = local,
                selected = false,
                snapshotKey = if (it == 0) null else "same-mounted-catalog",
            )
        }

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(local, harness.adapter.snapshot.pendingIdentity)
        assertEquals(local, harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `local mount rolls back after bounded timeout when tracks never settle`() = runTest {
        val harness = harness(backgroundScope)
        val local = SubtitleIdentity.LocalMedia3(
            media(label = "English", language = "en", codec = "webvtt"),
        )

        harness.adapter.select(local)
        repeat(5) {
            harness.adapter.reportMountedSelection(
                identity = local,
                selected = false,
                snapshotKey = if (it == 0) null else "same-transient-catalog",
            )
        }
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(local, harness.adapter.snapshot.pendingIdentity)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
        assertTrue(harness.adapter.snapshot.failureMessage?.contains("mount", ignoreCase = true) == true)
    }

    @Test
    fun `committed session replacement rebases downloaded rows to real session identity`() = runTest {
        val downloaded = downloadedTrack(
            index = 9,
            downloadId = 312,
            url = "https://silo.test/api/v1/stream/s1/subtitles/9.vtt?token=s1",
        )
        val harness = harness(backgroundScope, tracks = listOf(downloaded))
        harness.adapter.select(sidecar(4))
        runCurrent()

        harness.port.completeStage(
            candidate(
                id = "b",
                selectedIndex = 4,
                sessionId = "s2",
                tracks = listOf(serverTrack(4, "/stream/s2/subtitles/4.vtt")),
            ),
        )
        runCurrent()

        val committed = harness.committedPlaybacks.single()
        assertEquals("s2", committed.sessionId)
        assertEquals(
            "https://silo.test/api/v1/stream/s2/subtitles/9.vtt?token=s1",
            committed.subtitleTracks.single { it.downloadId == 312 }.url,
        )
    }

    @Test
    fun `content file version and session reset invalidates staged response`() = runTest {
        val harness = harness(backgroundScope)
        harness.adapter.select(sidecar(4))
        runCurrent()

        harness.adapter.resetContent(
            context(
                contentId = "content-2",
                mediaFileId = 22,
                versionId = "version-2",
                sessionId = "s9",
            ),
            committedIdentity = SubtitleIdentity.Off,
        )
        harness.port.completeStage(candidate("old", 4))
        runCurrent()

        assertEquals(SubtitleIdentity.Off, harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf("old"), harness.port.discarded)
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `new selection during suspended commit is applied after committed base without stale overwrite`() = runTest {
        val harness = harness(backgroundScope)
        harness.port.suspendCommits = true
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("b", 4, sessionId = "s2"))
        runCurrent()
        assertEquals(listOf("b"), harness.port.commitStarted)

        harness.adapter.select(sidecar(5))
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)

        harness.port.completeCommit("b")
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)
        assertEquals(listOf(4, 5), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf("s2"), harness.committedPlaybacks.map { it.sessionId })
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.port.completeStage(candidate("c", 5, sessionId = "s3"))
        harness.port.completeCommit("c")
        runCurrent()
        assertEquals(sidecar(5), harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(sidecar(5)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `reset during suspended commit prevents old playback adoption and persistence`() = runTest {
        val harness = harness(backgroundScope)
        harness.port.suspendCommits = true
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("b", 4, sessionId = "s2"))
        runCurrent()

        harness.adapter.resetContent(
            context(contentId = "content-2", mediaFileId = 22, versionId = "v2", sessionId = "s9"),
            committedIdentity = SubtitleIdentity.Off,
        )
        harness.port.completeCommit("b")
        runCurrent()

        assertEquals(SubtitleIdentity.Off, harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.committedPlaybacks.isEmpty())
        assertTrue(harness.persistence.persisted.isEmpty())
        assertEquals(listOf("s2"), harness.port.abandoned)
    }

    @Test
    fun `failed old commit after reset cannot poison next content commit`() = runTest {
        val harness = harness(backgroundScope)
        harness.port.suspendCommits = true
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("old", 4, sessionId = "s2"))
        runCurrent()
        harness.port.commitFailure = ApiResult.Error(
            code = 503,
            error = "commit_failed",
            message = "old commit failed",
        )

        harness.adapter.resetContent(
            context(
                contentId = "content-2",
                mediaFileId = 22,
                versionId = "v2",
                sessionId = "s9",
            ),
            committedIdentity = SubtitleIdentity.Off,
        )
        harness.port.completeCommit("old")
        runCurrent()
        assertFalse(harness.adapter.snapshot.subtitleApplying)

        harness.port.commitFailure = null
        harness.adapter.select(sidecar(5))
        runCurrent()
        harness.port.completeStage(candidate("new", 5, sessionId = "s10"))
        harness.port.completeCommit("new")
        runCurrent()

        assertEquals(sidecar(5), harness.adapter.snapshot.committedIdentity)
        assertFalse(harness.adapter.snapshot.subtitleApplying)
        assertEquals(listOf("s10"), harness.committedPlaybacks.map { it.sessionId })
        assertEquals(listOf(sidecar(5)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `new selection stays queued until suspended playback adoption completes`() = runTest {
        val adoption = AdoptionControl(suspendAdoption = true)
        val harness = harness(backgroundScope, adoption = adoption)
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("b", 4, sessionId = "s2"))
        runCurrent()
        assertEquals(1, adoption.started)

        harness.adapter.select(sidecar(5))
        runCurrent()

        assertEquals(
            listOf(4),
            harness.port.requests.map { it.subtitleTrackIndex },
            "A newer intent must not stage from the manager-committed base before lifecycle adoption finishes.",
        )
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
        assertTrue(harness.port.abandoned.isEmpty())

        adoption.complete()
        runCurrent()

        assertEquals(listOf(4, 5), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)
    }

    @Test
    fun `audio change during adoption waits and stages from adopted subtitle`() = runTest {
        val adoption = AdoptionControl(suspendAdoption = true)
        val harness = harness(backgroundScope, adoption = adoption)
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("subtitle", 4, selectedAudioIndex = 2, sessionId = "s2"))
        runCurrent()

        harness.adapter.selectAudio(7)
        runCurrent()
        assertEquals(listOf(4), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(sidecar(4), harness.adapter.snapshot.pendingIdentity)

        adoption.complete()
        runCurrent()

        assertEquals(listOf(4, 4), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf(2, 7), harness.port.requests.map { it.audioTrackIndex })
        harness.port.completeStage(candidate("audio", 4, selectedAudioIndex = 7, sessionId = "s3"))
        runCurrent()
        adoption.complete()
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals(7, harness.adapter.snapshot.transition.committed.audioTrackIndex)
    }

    @Test
    fun `reset during suspended playback adoption invalidates stale callback and persistence`() = runTest {
        val adoption = AdoptionControl(suspendAdoption = true)
        val harness = harness(backgroundScope, adoption = adoption)
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("b", 4, sessionId = "s2"))
        runCurrent()
        assertEquals(1, adoption.started)

        harness.adapter.resetContent(
            context(contentId = "content-2", mediaFileId = 22, versionId = "v2", sessionId = "s9"),
            committedIdentity = SubtitleIdentity.Off,
        )
        adoption.complete()
        runCurrent()

        assertEquals(SubtitleIdentity.Off, harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.committedPlaybacks.isEmpty())
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `playback adoption exception is contained and worker remains available`() = runTest {
        val adoption = AdoptionControl(
            failure = IllegalStateException("lifecycle adoption failed"),
        )
        val harness = harness(backgroundScope, adoption = adoption)
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("b", 4, sessionId = "s2"))
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertTrue(
            harness.adapter.snapshot.failureMessage
                ?.contains("adoption", ignoreCase = true) == true,
        )
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.adapter.select(sidecar(5))
        runCurrent()
        assertEquals(listOf(4, 5), harness.port.requests.map { it.subtitleTrackIndex })
    }

    @Test
    fun `first preference write exception is retried and later write remains FIFO`() = runTest {
        val harness = harness(backgroundScope, sessionId = null)
        harness.persistence.throwFirst = true

        harness.adapter.select(sidecar(4))
        harness.adapter.select(sidecar(5))
        runCurrent()

        assertEquals(
            listOf(sidecar(4), sidecar(5)),
            harness.persistence.persisted.map { it.identity },
        )
    }

    @Test
    fun `operation local persistence cancellation retries without killing consumer or flush`() = runTest {
        val harness = harness(
            scope = backgroundScope,
            sessionId = null,
            durablePersistenceScope = backgroundScope,
        )
        harness.persistence.cancelFirst = true

        val flushed = async { harness.adapter.persistCommittedSelectionAndFlush() }
        runCurrent()

        assertTrue(flushed.await())
        assertEquals(listOf(sidecar(3)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `flush reports failure only after bounded primary and durable attempts then later succeeds`() = runTest {
        val harness = harness(
            scope = backgroundScope,
            sessionId = null,
            durablePersistenceScope = backgroundScope,
        )
        harness.persistence.failuresRemaining = 4

        val first = async { harness.adapter.persistCommittedSelectionAndFlush() }
        runCurrent()
        assertFalse(first.await())
        assertTrue(harness.persistence.persisted.isEmpty())

        val second = async { harness.adapter.persistCommittedSelectionAndFlush() }
        runCurrent()
        assertTrue(second.await())
        assertEquals(listOf(sidecar(3)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `false persistence result is not reported durable after bounded flush attempts`() = runTest {
        val harness = harness(
            scope = backgroundScope,
            sessionId = null,
            durablePersistenceScope = backgroundScope,
        )
        harness.persistence.rejectionsRemaining = 4

        val flushed = async { harness.adapter.persistCommittedSelectionAndFlush() }
        runCurrent()

        assertFalse(flushed.await())
        assertEquals(4, harness.persistence.attempts)
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `false persistence result retries until an accepted durable write`() = runTest {
        val harness = harness(
            scope = backgroundScope,
            sessionId = null,
            durablePersistenceScope = backgroundScope,
        )
        harness.persistence.rejectionsRemaining = 3

        val flushed = async { harness.adapter.persistCommittedSelectionAndFlush() }
        runCurrent()

        assertTrue(flushed.await())
        assertEquals(4, harness.persistence.attempts)
        assertEquals(listOf(sidecar(3)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `durable write leapfrogging another content key does not suppress older valid write`() = runTest {
        val durableJob = Job()
        val durableScope = CoroutineScope(
            durableJob + UnconfinedTestDispatcher(testScheduler),
        )
        val harness = harness(
            scope = backgroundScope,
            sessionId = null,
            durablePersistenceScope = durableScope,
        )

        harness.adapter.select(sidecar(4))
        harness.adapter.resetContent(
            context = context(
                contentId = "content-2",
                mediaFileId = 22,
                sessionId = null,
            ),
            committedIdentity = sidecar(8),
        )
        harness.adapter.requestDurableFinalPersistence()
        runCurrent()

        assertEquals(
            listOf("content-2" to 22, "content-1" to 11),
            harness.persistence.persistedContexts,
        )
        assertEquals(
            listOf(sidecar(8), sidecar(4)),
            harness.persistence.persisted.map { it.identity },
        )
        durableJob.cancel()
    }

    @Test
    fun `durable final write is bounded when persistence never completes`() = runTest {
        val harness = harness(
            scope = backgroundScope,
            sessionId = null,
            durablePersistenceScope = backgroundScope,
        )
        harness.persistence.suspendEveryWrite = true

        harness.adapter.requestDurableFinalPersistence()
        runCurrent()
        advanceTimeBy(6_000L)
        runCurrent()

        assertEquals(1, harness.persistence.cancelledWrites)
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `consumer shutdown fails pending ack and flush uses bounded durable fallback`() = runTest {
        val owner = Job()
        val harness = harness(
            scope = CoroutineScope(coroutineContext + owner),
            sessionId = null,
            durablePersistenceScope = backgroundScope,
        )
        harness.persistence.suspendEveryWrite = true

        val flushed = async { harness.adapter.persistCommittedSelectionAndFlush() }
        runCurrent()
        owner.cancel(CancellationException("adapter owner stopped"))
        runCurrent()
        advanceTimeBy(6_000L)
        runCurrent()

        assertFalse(flushed.await())
        assertTrue(harness.persistence.cancelledWrites >= 2)
    }

    @Test
    fun `flush is bounded when active consumer persistence never completes`() = runTest {
        val harness = harness(
            scope = backgroundScope,
            sessionId = null,
            durablePersistenceScope = backgroundScope,
        )
        harness.persistence.suspendEveryWrite = true

        val flushed = async { harness.adapter.persistCommittedSelectionAndFlush() }
        runCurrent()
        advanceTimeBy(11_000L)
        runCurrent()

        assertFalse(flushed.await())
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `refresh owner rejects stale response after intent and session changes`() = runTest {
        val harness = harness(backgroundScope)
        val first = harness.adapter.beginRefresh()
        assertTrue(harness.adapter.ownsRefresh(first))

        harness.adapter.select(sidecar(4))
        runCurrent()
        assertFalse(harness.adapter.ownsRefresh(first))

        val second = harness.adapter.beginRefresh()
        assertTrue(harness.adapter.ownsRefresh(second))
        harness.adapter.replaceSession("s2")
        assertFalse(harness.adapter.ownsRefresh(second))
    }

    @Test
    fun `auto selection enters reducer only for current refresh owner`() = runTest {
        val harness = harness(backgroundScope)
        val stale = harness.adapter.beginRefresh()
        val current = harness.adapter.beginRefresh()
        val downloaded = SubtitleIdentity.Downloaded(
            downloadId = 91,
            media = media(trackId = "silo-downloaded-subtitle:91", language = "en", codec = "webvtt"),
        )

        assertFalse(harness.adapter.selectFromRefresh(stale, downloaded))
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.adapter.selectFromRefresh(current, downloaded))
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
        harness.adapter.reportMountedSelection(
            identity = downloaded,
            selected = true,
            snapshotKey = "auto-downloaded-mounted",
        )
        runCurrent()

        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(downloaded), harness.persistence.persisted.map { it.identity })
    }

    private fun harness(
        scope: CoroutineScope,
        sessionId: String? = "s1",
        tracks: List<PlayerSubtitleInfo> = emptyList(),
        adoption: AdoptionControl = AdoptionControl(),
        durablePersistenceScope: CoroutineScope = scope,
    ): Harness {
        val port = FakeStagedPort()
        val persistence = RecordingPersistence()
        val committedPlaybacks = mutableListOf<MobileSubtitleCommittedPlayback>()
        val adapter = MobileSubtitleTransactionAdapter(
            scope = scope,
            stagedPort = port,
            persistencePort = persistence,
            durablePersistenceScope = durablePersistenceScope,
            onCommittedPlayback = { adoptionRequest ->
                adoption.started += 1
                if (adoption.suspendAdoption) adoption.completions.receive()
                adoption.failure?.let { throw it }
                if (!adoptionRequest.isCurrent()) {
                    MobileSubtitleAdoptionResult.Superseded
                } else {
                    committedPlaybacks += adoptionRequest.playback
                    MobileSubtitleAdoptionResult.Adopted
                }
            },
        )
        adapter.resetContent(
            context(sessionId = sessionId, tracks = tracks),
            committedIdentity = sidecar(3),
        )
        return Harness(adapter, port, persistence, committedPlaybacks)
    }

    private suspend fun TestScope.verifyQueuedClientOwnedOrderDuringAdoption(
        scope: CoroutineScope,
        mutate: (MobileSubtitleTransactionAdapter, SubtitleIdentity.Downloaded) -> Unit,
    ) {
        val adoption = AdoptionControl(suspendAdoption = true)
        val harness = harness(scope, adoption = adoption)
        val downloaded = downloadedIdentity()
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("first", 4, selectedAudioIndex = 2, sessionId = "s2"))
        runCurrent()

        mutate(harness.adapter, downloaded)
        runCurrent()
        adoption.complete()
        runCurrent()

        assertEquals(listOf(4, -1), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf(2, 7), harness.port.requests.map { it.audioTrackIndex })
        harness.port.completeStage(clientOwnedCandidate("combined", audioIndex = 7))
        runCurrent()
        adoption.complete()
        runCurrent()
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)
        harness.adapter.reportMountedSelection(
            identity = downloaded,
            selected = true,
            snapshotKey = "queued-combined-mounted",
            settled = true,
        )
        runCurrent()

        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertEquals(7, harness.adapter.snapshot.transition.committed.audioTrackIndex)
        assertEquals(downloaded, harness.persistence.persisted.single().identity)
        assertEquals(7, harness.persistence.persisted.single().audioTrackIndex)
    }

    private suspend fun TestScope.prepareEarlyMountedLocalAudioTransaction(
        harness: Harness,
        identity: SubtitleIdentity.Downloaded,
    ) {
        harness.adapter.select(identity)
        harness.adapter.selectAudio(7)
        runCurrent()
        harness.adapter.reportMountedSelection(
            identity = identity,
            selected = true,
            snapshotKey = "mounted-before-adoption",
            settled = true,
        )
        runCurrent()
        assertEquals(identity, harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.adapter.snapshot.subtitleApplying)
    }

    private class AdoptionControl(
        val suspendAdoption: Boolean = false,
        val failure: Throwable? = null,
    ) {
        var started: Int = 0
        val completions = Channel<Unit>(Channel.UNLIMITED)

        suspend fun complete() {
            completions.send(Unit)
        }
    }

    private fun context(
        contentId: String = "content-1",
        mediaFileId: Int = 11,
        versionId: String = "version-1",
        sessionId: String? = "s1",
        tracks: List<PlayerSubtitleInfo> = emptyList(),
    ): MobileSubtitlePlaybackContext = MobileSubtitlePlaybackContext(
        contentId = contentId,
        mediaFileId = mediaFileId,
        versionId = versionId,
        sessionId = sessionId,
        positionSeconds = 42.0,
        audioTrackIndex = 2,
        qualityPreference = "auto",
        subtitleTracks = tracks,
        writeScope = PlaybackWriteScope(
            serverId = "server-test",
            profileId = "profile-test",
            credentialGenerationId = null,
            identityGeneration = 1L,
        ),
    )

    private fun candidate(
        id: String,
        selectedIndex: Int?,
        selectedAudioIndex: Int? = null,
        mode: PlaybackSubtitleModeV3 = PlaybackSubtitleModeV3.RENDER,
        hasSidecar: Boolean = mode == PlaybackSubtitleModeV3.RENDER ||
            mode == PlaybackSubtitleModeV3.CONVERT,
        sessionId: String = "s-$id",
        tracks: List<PlayerSubtitleInfo> = emptyList(),
    ): MobileStagedSubtitleCandidate = MobileStagedSubtitleCandidate(
        id = id,
        sessionId = sessionId,
        selectedSubtitleIndex = selectedIndex,
        selectedAudioIndex = selectedAudioIndex,
        subtitleMode = mode,
        hasSidecar = hasSidecar,
        subtitleTracks = tracks,
    )

    private fun clientOwnedCandidate(
        id: String,
        audioIndex: Int,
    ): MobileStagedSubtitleCandidate = candidate(
        id = id,
        selectedIndex = null,
        selectedAudioIndex = audioIndex,
        mode = PlaybackSubtitleModeV3.OFF,
        hasSidecar = false,
    )

    private fun sidecar(index: Int): SubtitleIdentity = SubtitleIdentity.ServerSidecar(index)

    private fun media(
        trackId: String? = null,
        label: String? = null,
        language: String? = null,
        codec: String? = null,
        forced: Boolean? = null,
    ): SubtitleMediaIdentity = SubtitleMediaIdentity(
        trackId = trackId,
        label = label,
        language = language,
        codecFamily = codec,
        forced = forced,
        hearingImpaired = false,
    )

    private fun serverTrack(index: Int, url: String): PlayerSubtitleInfo = PlayerSubtitleInfo(
        index = index,
        language = "en",
        codec = "srt",
        label = "Server subtitle",
        source = "server_artifact",
        forced = false,
        url = url,
    )

    private fun downloadedTrack(index: Int, downloadId: Int, url: String): PlayerSubtitleInfo =
        PlayerSubtitleInfo(
            index = index,
            language = "en",
            codec = "vtt",
            label = "English",
            source = "downloaded",
            forced = false,
            url = url,
            downloadId = downloadId,
        )

    private fun downloadedIdentity(): SubtitleIdentity.Downloaded =
        SubtitleIdentity.Downloaded(
            downloadId = 312,
            media = media(
                trackId = "silo-downloaded-subtitle:312",
                label = "English",
                language = "en",
                codec = "webvtt",
            ),
        )

    private data class Harness(
        val adapter: MobileSubtitleTransactionAdapter,
        val port: FakeStagedPort,
        val persistence: RecordingPersistence,
        val committedPlaybacks: MutableList<MobileSubtitleCommittedPlayback>,
    )

    private class FakeStagedPort : MobileSubtitleStagedReplanPort {
        private sealed interface StageOutcome {
            data class Result(
                val value: ApiResult<MobileStagedSubtitleCandidate>,
            ) : StageOutcome

            data class Failure(val error: Throwable) : StageOutcome
        }

        val requests = mutableListOf<MobileSubtitleStageRequest>()
        val committed = mutableListOf<String>()
        val commitStarted = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val abandoned = mutableListOf<String>()
        var suspendCommits = false
        var commitFailure: ApiResult<MobileSubtitleCommittedPlayback>? = null
        var commitThrowable: Throwable? = null
        var discardThrowable: Throwable? = null
        private val stageResults = Channel<StageOutcome>(Channel.UNLIMITED)
        private val commitResults = Channel<String>(Channel.UNLIMITED)

        override suspend fun stage(request: MobileSubtitleStageRequest): ApiResult<MobileStagedSubtitleCandidate> {
            requests += request
            return when (val outcome = stageResults.receive()) {
                is StageOutcome.Result -> outcome.value
                is StageOutcome.Failure -> throw outcome.error
            }
        }

        override suspend fun commit(
            candidate: MobileStagedSubtitleCandidate,
        ): ApiResult<MobileSubtitleCommittedPlayback> {
            commitStarted += candidate.id
            if (suspendCommits) {
                val committedId = commitResults.receive()
                check(committedId == candidate.id)
            }
            commitThrowable?.let {
                commitThrowable = null
                throw it
            }
            commitFailure?.let { return it }
            committed += candidate.id
            return ApiResult.Success(
                MobileSubtitleCommittedPlayback(
                    sessionId = candidate.sessionId,
                    subtitleTracks = candidate.subtitleTracks,
                ),
            )
        }

        override suspend fun discard(candidate: MobileStagedSubtitleCandidate) {
            discarded += candidate.id
            discardThrowable?.let {
                discardThrowable = null
                throw it
            }
        }

        override suspend fun abandonCommitted(playback: MobileSubtitleCommittedPlayback) {
            abandoned += playback.sessionId
        }

        suspend fun completeStage(candidate: MobileStagedSubtitleCandidate) {
            stageResults.send(StageOutcome.Result(ApiResult.Success(candidate)))
        }

        suspend fun failStage(result: ApiResult<Nothing>) {
            stageResults.send(StageOutcome.Result(result))
        }

        suspend fun cancelStage(message: String) {
            stageResults.send(StageOutcome.Failure(CancellationException(message)))
        }

        suspend fun completeCommit(id: String) {
            commitResults.send(id)
        }
    }

    private class RecordingPersistence : MobileSubtitlePersistencePort {
        val persisted = mutableListOf<CommittedSubtitle>()
        val persistedContexts = mutableListOf<Pair<String, Int>>()
        var suspendFirst = false
        var suspendEveryWrite = false
        var throwFirst = false
        var cancelFirst = false
        var failuresRemaining = 0
        var rejectionsRemaining = 0
        var cancelledWrites = 0
        var attempts = 0
            private set
        private val firstStarted = Channel<Unit>(Channel.CONFLATED)
        private val firstRelease = Channel<Unit>(Channel.CONFLATED)

        override suspend fun persist(
            committed: CommittedSubtitle,
            context: MobileSubtitlePlaybackContext,
        ): Boolean {
            val call = attempts++
            if (cancelFirst && call == 0) {
                throw CancellationException("persistence request cancelled locally")
            }
            if (throwFirst && call == 0) {
                throw IllegalStateException("first persistence write failed")
            }
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                throw IllegalStateException("persistence write failed")
            }
            if (rejectionsRemaining > 0) {
                rejectionsRemaining -= 1
                return false
            }
            if (suspendFirst && call == 0) {
                firstStarted.send(Unit)
                firstRelease.receive()
            }
            if (suspendEveryWrite) {
                try {
                    awaitCancellation()
                } finally {
                    cancelledWrites += 1
                }
            }
            persisted += committed
            persistedContexts += context.contentId to context.mediaFileId
            return true
        }

        suspend fun awaitFirstStarted() {
            firstStarted.receive()
        }

        suspend fun releaseFirst() {
            firstRelease.send(Unit)
        }
    }
}
