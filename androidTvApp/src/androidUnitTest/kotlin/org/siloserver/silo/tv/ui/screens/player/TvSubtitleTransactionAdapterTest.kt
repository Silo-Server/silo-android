package org.siloserver.silo.tv.ui.screens.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import org.siloserver.silo.common.player.PlaybackTrackSelectionWriteCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TvSubtitleTransactionAdapterTest {
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
    fun `an embedded pick the player cannot mount is staged to the server`() = runTest {
        // Catalog-only embedded rows carry a blank URL, so on a remuxed or
        // transcoded route they never become Media3 tracks. Committing one
        // locally could only ever end at the mount deadline and roll back to the
        // previous subtitle — which is what "switching to Dutch does nothing"
        // looked like. Route it to the staged replan that materialises the
        // artifact instead.
        val embedded = SubtitleIdentity.Embedded(
            serverIndex = 13,
            media = media(label = "SUBRIP", language = "nl", codec = "subrip"),
        )
        val harness = harness(backgroundScope, isLocallyMountable = { false })

        harness.adapter.select(embedded)
        runCurrent()

        assertEquals(
            listOf(13),
            harness.port.requests.map { it.subtitleTrackIndex },
            "an unmountable identity must reach the staged replan port",
        )
    }

    @Test
    fun `an embedded pick the player already exposes stays local`() = runTest {
        val embedded = SubtitleIdentity.Embedded(
            serverIndex = 13,
            media = media(trackId = "decoder-subrip-13", label = "SUBRIP", language = "nl"),
        )
        val harness = harness(backgroundScope, isLocallyMountable = { true })

        harness.adapter.select(embedded)
        runCurrent()

        assertTrue(
            harness.port.requests.isEmpty(),
            "a locally mountable identity must not ask the server to replan",
        )
    }

    @Test
    fun `new server negotiated sidecar switches locally without replan`() = runTest {
        val target = sidecar(4)
        val harness = harness(
            backgroundScope,
            isLocallyMountable = { identity -> identity == target },
        )

        harness.adapter.select(target)
        runCurrent()

        assertTrue(
            harness.port.requests.isEmpty(),
            "an already-mounted sidecar must not ask the server to replan",
        )
        assertEquals(target, harness.adapter.snapshot.localMountIdentity)
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)

        harness.adapter.reportMountedSelection(
            identity = target,
            selected = true,
            snapshotKey = "mounted-sidecar-selected",
            settled = true,
        )
        runCurrent()

        assertEquals(target, harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertEquals(listOf(target), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `old server catalog-only sidecar performs one staged replan at current position`() = runTest {
        val adoption = AdoptionControl()
        val harness = harness(
            backgroundScope,
            adoption = adoption,
            isLocallyMountable = { false },
        )

        harness.adapter.select(sidecar(4))
        runCurrent()

        val request = harness.port.requests.single()
        assertEquals(
            listOf(4),
            harness.port.requests.map { it.subtitleTrackIndex },
            "an unmounted sidecar must retain the staged replan fallback",
        )
        assertEquals(42.0, request.positionSeconds)
        assertEquals(2, request.audioTrackIndex)
        assertEquals("auto", request.qualityPreference)
        assertNull(harness.adapter.snapshot.localMountIdentity)

        harness.port.completeStage(candidate("old-server-sidecar", 4))
        runCurrent()
        confirmPendingPlayerBoundary(harness, "old-server-sidecar-mounted")
        runCurrent()

        assertEquals(listOf("old-server-sidecar"), harness.port.committed)
        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(42.0), adoption.requestedSourcePositions)
    }

    @Test
    fun `burn in route stages one replan before switching to an external sidecar`() = runTest {
        // Burn-in plans intentionally mount no negotiated alternatives. The
        // selected SRT therefore follows the same safe fallback as an old
        // server response and replaces the video route before it is mounted.
        val harness = harness(backgroundScope, isLocallyMountable = { false })

        harness.adapter.select(sidecar(4))
        runCurrent()

        assertEquals(listOf(4), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(42.0, harness.port.requests.single().positionSeconds)
        harness.port.completeStage(candidate("burn-in-to-sidecar", 4))
        runCurrent()
        confirmPendingPlayerBoundary(harness, "burn-in-replacement-mounted")
        runCurrent()

        assertEquals(listOf("burn-in-to-sidecar"), harness.port.committed)
        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
    }

    @Test
    fun `an unmounted committed server sidecar is not restored locally`() = runTest {
        val harness = harness(backgroundScope, isLocallyMountable = { false })

        harness.adapter.restoreCommittedLocalMount()
        runCurrent()

        assertFalse(harness.adapter.snapshot.subtitleApplying)
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
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
        confirmPendingPlayerBoundary(harness, "b-mounted")
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
        confirmPendingPlayerBoundary(harness, "c-mounted")
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
        confirmPendingPlayerBoundary(harness, "combined-mounted")
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
        confirmPendingPlayerBoundary(harness, "audio-subtitle-mounted")
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals(7, harness.adapter.snapshot.transition.committed.audioTrackIndex)
    }

    @Test
    fun `adapted edition commits returned audio and subtitle identities`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(
            candidate(
                id = "adapted",
                selectedIndex = 1,
                selectedAudioIndex = 5,
                effectiveMediaFileId = 22,
                selectedSubtitleIdentity = sidecar(1),
            ),
        )
        runCurrent()
        confirmPendingPlayerBoundary(harness, "adapted-mounted")
        runCurrent()

        assertEquals(sidecar(1), harness.adapter.snapshot.committedIdentity)
        assertEquals(5, harness.adapter.snapshot.transition.committed.audioTrackIndex)
        assertEquals(22, harness.committedPlaybacks.single().effectiveMediaFileId)
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
            listOf(
                CommittedSubtitle(
                    downloaded,
                    audioTrackIndex = 7,
                    qualityPreference = "auto",
                    // This scenario changes AUDIO explicitly, which is now
                    // recorded so a subtitle-only commit cannot be mistaken for
                    // the viewer choosing the audio it happened to carry.
                    audioPreferenceSpecified = true,
                ),
            ),
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
        confirmPendingPlayerBoundary(harness, "validation-recovery-mounted")
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
        confirmPendingPlayerBoundary(harness, "discard-recovery-mounted")
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
        val identity = tvSubtitleIdentity(row)
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
        assertEquals(2, harness.adapter.snapshot.transition.committed.audioTrackIndex)
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
        assertTrue(harness.adapter.snapshot.failureMessage?.contains("mount", ignoreCase = true) == true)
        harness.adapter.reportMountedSelection(
            identity = local,
            selected = true,
            snapshotKey = "prior-local-restored",
            settled = true,
        )
        runCurrent()
        assertNull(harness.adapter.snapshot.localMountIdentity)
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
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.adapter.snapshot.failureMessage?.contains("mount", ignoreCase = true) == true)
        harness.adapter.reportMountedSelection(
            identity = downloaded,
            selected = true,
            snapshotKey = "prior-downloaded-restored",
            settled = true,
        )
        runCurrent()
        assertNull(harness.adapter.snapshot.localMountIdentity)
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
        confirmPendingPlayerBoundary(harness, "off-applied")
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
    fun `settled local mount miss rolls back immediately without persistence`() = runTest {
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
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
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
    fun `new selection during suspended commit rolls B publication back before replaying explicit C`() = runTest {
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

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)
        assertEquals(listOf(4, 5), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf("s2"), harness.committedPlaybacks.map { it.sessionId })
        assertEquals(listOf("s2"), harness.port.abandoned)
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.port.completeStage(candidate("c", 5, sessionId = "s3"))
        harness.port.completeCommit("c")
        runCurrent()
        assertEquals(
            sidecar(5),
            harness.adapter.snapshot.localMountIdentity,
            "Explicit C must own the player boundary after its replacement commits.",
        )
        confirmPendingPlayerBoundary(harness, "queued-c-mounted")
        runCurrent()
        assertEquals(sidecar(5), harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(sidecar(5)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `an embedded pick replayed from the queue mounts locally instead of replanning`() = runTest {
        // A direct press on an embedded track switches it locally, because the
        // stream already carries it. The same press arriving while another
        // mutation was committing went down a different path and replanned --
        // tearing the stream down and rebuilding it for the same picture, so the
        // user saw a black flash, a rebuffer and a re-seek.
        val harness = harness(backgroundScope)
        val embedded = SubtitleIdentity.Embedded(
            serverIndex = 7,
            media = media(
                trackId = "decoder-pgs-7",
                label = "English",
                language = "en",
                codec = "pgs",
            ),
        )
        harness.port.suspendCommits = true
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("b", 4, sessionId = "s2"))
        runCurrent()
        assertEquals(listOf("b"), harness.port.commitStarted)

        // Pressed while the sidecar commit is still in flight, so it is folded
        // into queuedMutations and replayed once that commit lands.
        harness.adapter.select(embedded)
        harness.port.completeCommit("b")
        runCurrent()

        assertEquals(
            listOf(4),
            harness.port.requests.map { it.subtitleTrackIndex },
            "the embedded track is already in the stream — replanning for it is pure loss",
        )
        assertEquals(embedded, harness.adapter.snapshot.localMountIdentity)
    }

    @Test
    fun `a queued embedded pick still replans when it carries an audio change`() = runTest {
        // The other half of the contract: the local shortcut skips the server,
        // so it must decline whenever the folded pending also carries an audio,
        // quality or output-route preference — only the server can apply those,
        // and silently dropping them is worse than the rebuffer.
        val harness = harness(backgroundScope)
        val embedded = SubtitleIdentity.Embedded(
            serverIndex = 7,
            media = media(
                trackId = "decoder-pgs-7",
                label = "English",
                language = "en",
                codec = "pgs",
            ),
        )
        harness.port.suspendCommits = true
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("b", 4, sessionId = "s2"))
        runCurrent()

        harness.adapter.selectAudio(7)
        harness.adapter.select(embedded)
        harness.port.completeCommit("b")
        runCurrent()

        assertEquals(
            7,
            harness.port.requests.last().audioTrackIndex,
            "the audio change must still reach the server",
        )
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
        confirmPendingPlayerBoundary(harness, "new-content-mounted")
        runCurrent()

        assertEquals(sidecar(5), harness.adapter.snapshot.committedIdentity)
        assertFalse(harness.adapter.snapshot.subtitleApplying)
        assertEquals(listOf("s10"), harness.committedPlaybacks.map { it.sessionId })
        assertEquals(listOf(sidecar(5)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `new selection waits for adoption then abandons B before replaying explicit C`() = runTest {
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
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)
        assertEquals(listOf("s2"), harness.port.abandoned)
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `audio change during adoption waits and stages from validated B intent`() = runTest {
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
        assertEquals(listOf("s2"), harness.port.abandoned)
        assertTrue(harness.persistence.persisted.isEmpty())
        harness.port.completeStage(candidate("audio", 4, selectedAudioIndex = 7, sessionId = "s3"))
        runCurrent()
        adoption.complete()
        runCurrent()
        confirmPendingPlayerBoundary(harness, "audio-replan-mounted")
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
    fun `retirement ticket reserved before settlement cannot overwrite replacement adapter`() = runTest {
        val coordinator = PlaybackTrackSelectionWriteCoordinator()
        val persistence = RecordingPersistence()
        val old = harness(
            scope = backgroundScope,
            sessionId = null,
            durablePersistenceScope = backgroundScope,
            persistenceCoordinator = coordinator,
            persistence = persistence,
        )
        old.adapter.select(sidecar(4))
        runCurrent()
        persistence.persisted.clear()
        persistence.persistedContexts.clear()

        val reservation = requireNotNull(old.adapter.reserveDurableFinalPersistence())
        val releaseSettlement = CompletableDeferred<Unit>()
        old.adapter.invalidateAndSettleAsync(restoreUi = false) {
            releaseSettlement.await()
            old.adapter.requestDurableFinalPersistence(reservation)
        }
        runCurrent()

        val replacement = harness(
            scope = backgroundScope,
            sessionId = null,
            durablePersistenceScope = backgroundScope,
            persistenceCoordinator = coordinator,
            persistence = persistence,
        )
        replacement.adapter.select(sidecar(5))
        runCurrent()
        releaseSettlement.complete(Unit)
        runCurrent()

        assertEquals(listOf(sidecar(5)), persistence.persisted.map { it.identity })
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

    @Test
    fun `authoritative downloaded refresh auto selects the exact server sidecar`() = runTest {
        val harness = harness(backgroundScope)
        val owner = harness.adapter.beginRefresh()
        val row = PlayerSubtitleInfo(
            index = 4,
            language = "en",
            codec = "vtt",
            label = "Downloaded English",
            source = "downloaded",
            forced = false,
            url = "https://silo.test/api/v1/stream/s1/subtitles/4.vtt",
            downloadId = 91,
            serverTrackId = "file:22:subtitle:4",
            serverDelivery = "sidecar",
        )

        assertTrue(
            harness.adapter.applyRefresh(
                owner = owner,
                subtitleTracks = listOf(row),
                autoSelectDownloadId = 91,
            ),
        )
        runCurrent()

        assertEquals(tvSubtitleIdentity(row), harness.adapter.snapshot.pendingIdentity)
        assertEquals(listOf(4), harness.port.requests.map { it.subtitleTrackIndex })
    }

    @Test
    fun `HUD catalog selection while controls are open enters one transaction`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()

        assertEquals(listOf(4), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(4), harness.adapter.snapshot.pendingIdentity)
    }

    @Test
    fun `subtitle then quality merges into one latest staged request`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.adapter.selectQuality("720p")
        harness.port.completeStage(candidate("subtitle-only", 4, qualityPreference = "auto"))
        runCurrent()

        assertEquals(listOf("auto", "720p"), harness.port.requests.map { it.qualityPreference })
        assertEquals(listOf(4, 4), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf("subtitle-only"), harness.port.discarded)

        harness.port.completeStage(candidate("combined", 4, qualityPreference = "720p"))
        runCurrent()
        confirmPendingPlayerBoundary(harness, "subtitle-quality-mounted")
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals("720p", harness.adapter.snapshot.transition.committed.qualityPreference)
    }

    @Test
    fun `quality then subtitle merges into one latest staged request`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.selectQuality("720p")
        runCurrent()
        harness.adapter.select(sidecar(4))
        harness.port.completeStage(candidate("quality-only", 3, qualityPreference = "720p"))
        runCurrent()

        assertEquals(listOf("720p", "720p"), harness.port.requests.map { it.qualityPreference })
        assertEquals(listOf(3, 4), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf("quality-only"), harness.port.discarded)

        harness.port.completeStage(candidate("combined", 4, qualityPreference = "720p"))
        runCurrent()
        confirmPendingPlayerBoundary(harness, "quality-subtitle-mounted")
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals("720p", harness.adapter.snapshot.transition.committed.qualityPreference)
    }

    @Test
    fun `failed combined quality subtitle replan retains committed HUD and quality`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        harness.adapter.selectQuality("720p")
        runCurrent()
        harness.port.failStage(ApiResult.NetworkError(IllegalStateException("quality failed")))
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals("auto", harness.adapter.snapshot.transition.committed.qualityPreference)
        assertNull(harness.adapter.snapshot.pendingIdentity)
    }

    @Test
    fun `operation-local combined quality cancellation rolls back and worker survives`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        harness.adapter.selectQuality("720p")
        runCurrent()
        harness.port.cancelStage("quality cancelled locally")
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals("auto", harness.adapter.snapshot.transition.committed.qualityPreference)
        harness.adapter.select(sidecar(5))
        runCurrent()
        assertEquals(listOf(4, 5), harness.port.requests.map { it.subtitleTrackIndex })
    }

    @Test
    fun `stale quality subtitle candidate cannot publish`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        harness.adapter.selectQuality("720p")
        runCurrent()
        harness.adapter.selectQuality("1080p")
        harness.port.completeStage(candidate("stale", 4, qualityPreference = "720p"))
        runCurrent()

        assertEquals(listOf("stale"), harness.port.discarded)
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals("auto", harness.adapter.snapshot.transition.committed.qualityPreference)
    }

    @Test
    fun `reset while combined quality subtitle replan is suspended invalidates it`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        harness.adapter.selectQuality("720p")
        runCurrent()
        harness.adapter.resetContent(
            context(contentId = "content-2", versionId = "version-2", sessionId = "s2"),
            committedIdentity = sidecar(8),
        )
        harness.port.completeStage(candidate("old", 4, qualityPreference = "720p"))
        runCurrent()

        assertEquals(listOf("old"), harness.port.discarded)
        assertEquals(sidecar(8), harness.adapter.snapshot.committedIdentity)
        assertEquals("auto", harness.adapter.snapshot.transition.committed.qualityPreference)
    }

    @Test
    fun `exit flush captures only the committed quality subtitle snapshot`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        harness.adapter.selectQuality("720p")
        runCurrent()
        harness.port.completeStage(candidate("committed", 4, qualityPreference = "720p"))
        runCurrent()
        confirmPendingPlayerBoundary(harness, "flush-base-mounted")
        runCurrent()
        harness.adapter.select(sidecar(5))
        assertTrue(harness.adapter.persistCommittedSelectionAndFlush())
        runCurrent()

        assertEquals(
            CommittedSubtitle(sidecar(4), audioTrackIndex = 2, qualityPreference = "720p"),
            harness.persistence.persisted.last(),
        )
    }

    @Test
    fun `subtitle then route generation merges into one latest staged request`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.adapter.updateOutputRouteGeneration(7)
        harness.port.completeStage(candidate("subtitle-only", 4, outputRouteGeneration = 0))
        runCurrent()

        assertEquals(listOf(0L, 7L), harness.port.requests.map { it.outputRouteGeneration })
        assertEquals(listOf("subtitle-only"), harness.port.discarded)

        harness.port.completeStage(candidate("combined", 4, outputRouteGeneration = 7))
        runCurrent()
        confirmPendingPlayerBoundary(harness, "route-subtitle-mounted")
        runCurrent()
        assertEquals(7L, harness.adapter.snapshot.committedOutputRouteGeneration)
        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
    }

    @Test
    fun `route generation then subtitle merges into one latest staged request`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.updateOutputRouteGeneration(7)
        runCurrent()
        harness.adapter.select(sidecar(4))
        harness.port.completeStage(candidate("route-only", 3, outputRouteGeneration = 7))
        runCurrent()

        assertEquals(listOf(7L, 7L), harness.port.requests.map { it.outputRouteGeneration })
        assertEquals(listOf(3, 4), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf("route-only"), harness.port.discarded)
    }

    @Test
    fun `failed route subtitle replan retains committed playback`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        harness.adapter.updateOutputRouteGeneration(7)
        runCurrent()
        harness.port.failStage(ApiResult.NetworkError(IllegalStateException("route failed")))
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(0L, harness.adapter.snapshot.committedOutputRouteGeneration)
        assertNull(harness.adapter.snapshot.pendingIdentity)
    }

    @Test
    fun `operation-local route cancellation rolls back and worker survives`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.updateOutputRouteGeneration(7)
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.cancelStage("route cancelled locally")
        runCurrent()
        harness.adapter.select(sidecar(5))
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(0L, harness.adapter.snapshot.committedOutputRouteGeneration)
        assertEquals(listOf(4, 5), harness.port.requests.map { it.subtitleTrackIndex })
    }

    @Test
    fun `stale route subtitle candidate cannot publish`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        harness.adapter.updateOutputRouteGeneration(7)
        runCurrent()
        harness.adapter.updateOutputRouteGeneration(8)
        harness.port.completeStage(candidate("stale", 4, outputRouteGeneration = 7))
        runCurrent()

        assertEquals(listOf("stale"), harness.port.discarded)
        assertEquals(0L, harness.adapter.snapshot.committedOutputRouteGeneration)
    }

    @Test
    fun `content reset while route subtitle replan is suspended invalidates it`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        harness.adapter.updateOutputRouteGeneration(7)
        runCurrent()
        harness.adapter.resetContent(
            context(contentId = "content-2", versionId = "version-2", sessionId = "s2"),
            committedIdentity = sidecar(8),
        )
        harness.port.completeStage(candidate("old", 4, outputRouteGeneration = 7))
        runCurrent()

        assertEquals(listOf("old"), harness.port.discarded)
        assertEquals(sidecar(8), harness.adapter.snapshot.committedIdentity)
        assertEquals(0L, harness.adapter.snapshot.committedOutputRouteGeneration)
    }

    @Test
    fun `exit invalidates route subtitle work`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.updateOutputRouteGeneration(7)
        runCurrent()
        harness.adapter.invalidate()
        harness.port.completeStage(candidate("route", 3, outputRouteGeneration = 7))
        runCurrent()

        assertEquals(listOf("route"), harness.port.discarded)
        assertEquals(0L, harness.adapter.snapshot.committedOutputRouteGeneration)
    }

    @Test
    fun `reset between manager commit and lifecycle publication abandons the committed session`() = runTest {
        val adoption = AdoptionControl(suspendAdoption = true)
        val harness = harness(backgroundScope, adoption = adoption)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("committed", 4, sessionId = "replacement"))
        runCurrent()
        harness.adapter.resetContent(
            context(contentId = "content-2", versionId = "version-2", sessionId = "s2"),
            committedIdentity = sidecar(8),
        )
        adoption.complete()
        runCurrent()

        assertEquals(listOf("replacement"), harness.port.abandoned)
        assertEquals(sidecar(8), harness.adapter.snapshot.committedIdentity)
    }

    @Test
    fun `adoption exception abandons replacement and the worker survives`() = runTest {
        val adoption = AdoptionControl(failure = IllegalStateException("adoption failed"))
        val harness = harness(backgroundScope, adoption = adoption)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("failed-adoption", 4, sessionId = "replacement"))
        runCurrent()

        assertEquals(listOf("replacement"), harness.port.abandoned)
        adoption.failure = null
        harness.adapter.select(sidecar(5))
        runCurrent()
        harness.port.completeStage(candidate("next", 5, sessionId = "replacement-2"))
        runCurrent()
        confirmPendingPlayerBoundary(harness, "adoption-recovery-mounted")
        runCurrent()
        assertEquals(sidecar(5), harness.adapter.snapshot.committedIdentity)
    }

    @Test
    fun `superseded committed Ready never changes the lifecycle active session`() = runTest {
        val adoption = AdoptionControl(suspendAdoption = true, forceSuperseded = true)
        val harness = harness(backgroundScope, adoption = adoption)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("b", 4, sessionId = "replacement-b"))
        runCurrent()
        harness.adapter.select(sidecar(5))
        adoption.complete()
        runCurrent()

        assertEquals(listOf("replacement-b"), harness.port.abandoned)
        assertTrue(harness.committedPlaybacks.isEmpty())
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)
    }

    @Test
    fun `version switch during stage invalidates and discards the candidate`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.adapter.resetContent(
            context(versionId = "version-2", sessionId = "s2"),
            committedIdentity = sidecar(8),
        )
        harness.port.completeStage(candidate("version-1", 4))
        runCurrent()

        assertEquals(listOf("version-1"), harness.port.discarded)
        assertEquals(sidecar(8), harness.adapter.snapshot.committedIdentity)
    }

    @Test
    fun `version switch during commit abandons a committed unpublished session`() = runTest {
        val harness = harness(backgroundScope)
        harness.port.suspendCommits = true

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("version-1", 4, sessionId = "replacement"))
        runCurrent()
        harness.adapter.resetContent(
            context(versionId = "version-2", sessionId = "s2"),
            committedIdentity = sidecar(8),
        )
        harness.port.completeCommit("version-1")
        runCurrent()

        assertEquals(listOf("replacement"), harness.port.abandoned)
        assertEquals(sidecar(8), harness.adapter.snapshot.committedIdentity)
    }

    @Test
    fun `version switch during adoption cannot publish the older playback`() = runTest {
        val adoption = AdoptionControl(suspendAdoption = true)
        val harness = harness(backgroundScope, adoption = adoption)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("version-1", 4, sessionId = "replacement"))
        runCurrent()
        harness.adapter.resetContent(
            context(versionId = "version-2", sessionId = "s2"),
            committedIdentity = sidecar(8),
        )
        adoption.complete()
        runCurrent()

        assertEquals(listOf("replacement"), harness.port.abandoned)
        assertEquals(sidecar(8), harness.adapter.snapshot.committedIdentity)
    }

    @Test
    fun `fresh typed sidecar remains pending until staged playback is mounted`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.restoreFreshPreference(sidecar(4))
        runCurrent()
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(4), harness.adapter.snapshot.pendingIdentity)

        harness.port.completeStage(candidate("fresh-sidecar", 4))
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(4), harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.adapter.reportMountedSelection(
            identity = sidecar(4),
            selected = true,
            snapshotKey = "fresh-sidecar-mounted",
            settled = true,
        )
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(sidecar(4)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `fresh typed burn in commits without waiting for a mounted track`() = runTest {
        val harness = harness(backgroundScope)
        val burnIn = SubtitleIdentity.ServerBurnIn(4)

        harness.adapter.restoreFreshPreference(burnIn)
        runCurrent()
        harness.port.completeStage(
            candidate(
                id = "fresh-burn-in",
                selectedIndex = 4,
                mode = PlaybackSubtitleModeV3.BURN_IN,
                hasSidecar = false,
            ),
        )
        runCurrent()

        assertEquals(burnIn, harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertEquals(listOf(burnIn), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `fresh typed Off emits one owned disable and persists only after backend accepts`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.restoreFreshPreference(SubtitleIdentity.Off)
        runCurrent()
        harness.port.completeStage(
            candidate(
                id = "fresh-off",
                selectedIndex = -1,
                mode = PlaybackSubtitleModeV3.OFF,
                hasSidecar = false,
            ),
        )
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(SubtitleIdentity.Off, harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.adapter.reportMountedSelection(
            identity = SubtitleIdentity.Off,
            selected = true,
            snapshotKey = "fresh-off-disabled",
            settled = true,
        )
        runCurrent()

        assertEquals(SubtitleIdentity.Off, harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(SubtitleIdentity.Off), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `backend rejection rolls fresh restore back without committed or persisted false success`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.restoreFreshPreference(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("fresh-rejected", 4))
        runCurrent()
        harness.adapter.reportMountedSelection(
            identity = sidecar(4),
            selected = false,
            snapshotKey = "fresh-sidecar-rejected",
            settled = true,
        )
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(3), harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
        assertEquals("The selected subtitle could not be mounted.", harness.adapter.snapshot.failureMessage)

        harness.port.completeStage(candidate("fresh-restore-a", 3))
        runCurrent()
        assertEquals(sidecar(3), harness.adapter.snapshot.localMountIdentity)
        harness.adapter.reportMountedSelection(
            identity = sidecar(3),
            selected = true,
            snapshotKey = "fresh-prior-a-restored",
            settled = true,
        )
        runCurrent()
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `legacy migration of already planned sidecar persists only after exact mount`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.resetContent(context(), committedIdentity = sidecar(4))
        harness.adapter.restoreFreshPreference(sidecar(4), migrationRequired = true)
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.adapter.reportMountedSelection(
            identity = sidecar(4),
            selected = true,
            snapshotKey = "fresh-legacy-sidecar-mounted",
            settled = true,
        )
        runCurrent()

        assertEquals(listOf(sidecar(4)), harness.persistence.persisted.map { it.identity })
    }

    private fun harness(
        scope: CoroutineScope,
        sessionId: String? = "s1",
        tracks: List<PlayerSubtitleInfo> = emptyList(),
        adoption: AdoptionControl = AdoptionControl(),
        durablePersistenceScope: CoroutineScope = scope,
        isLocallyMountable: (SubtitleIdentity) -> Boolean = { identity ->
            identity !is SubtitleIdentity.ServerSidecar
        },
        persistenceCoordinator: PlaybackTrackSelectionWriteCoordinator =
            PlaybackTrackSelectionWriteCoordinator(),
        persistence: RecordingPersistence = RecordingPersistence(),
    ): Harness {
        val port = FakeStagedPort()
        val committedPlaybacks = mutableListOf<TvSubtitleCommittedPlayback>()
        val adapter = TvSubtitleTransactionAdapter(
            scope = scope,
            stagedPort = port,
            persistencePort = persistence,
            durablePersistenceScope = durablePersistenceScope,
            persistenceCoordinator = persistenceCoordinator,
            onCommittedPlayback = { adoptionRequest ->
                adoption.started += 1
                adoption.requestedSourcePositions += adoptionRequest.requestedSourcePositionSeconds
                if (adoption.suspendAdoption) adoption.completions.receive()
                adoption.failure?.let { throw it }
                if (adoption.forceSuperseded || !adoptionRequest.isCurrent()) {
                    TvSubtitleAdoptionResult.Superseded
                } else {
                    committedPlaybacks += adoptionRequest.playback
                    TvSubtitleAdoptionResult.Adopted
                }
            },
            isLocallyMountable = isLocallyMountable,
        )
        adapter.resetContent(
            context(sessionId = sessionId, tracks = tracks),
            committedIdentity = sidecar(3),
        )
        return Harness(adapter, port, persistence, committedPlaybacks)
    }

    private suspend fun TestScope.verifyQueuedClientOwnedOrderDuringAdoption(
        scope: CoroutineScope,
        mutate: (TvSubtitleTransactionAdapter, SubtitleIdentity.Downloaded) -> Unit,
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
        var failure: Throwable? = null,
        var forceSuperseded: Boolean = false,
    ) {
        var started: Int = 0
        val requestedSourcePositions = mutableListOf<Double>()
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
        outputRouteGeneration: Long = 0L,
    ): TvSubtitlePlaybackContext = TvSubtitlePlaybackContext(
        contentId = contentId,
        mediaFileId = mediaFileId,
        versionId = versionId,
        sessionId = sessionId,
        positionSeconds = 42.0,
        audioTrackIndex = 2,
        qualityPreference = "auto",
        subtitleTracks = tracks,
        outputRouteGeneration = outputRouteGeneration,
        writeScope = tvTestPlaybackWriteScope,
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
        qualityPreference: String = "auto",
        outputRouteGeneration: Long = 0L,
        effectiveMediaFileId: Int? = null,
        selectedSubtitleIdentity: SubtitleIdentity? = null,
    ): TvStagedSubtitleCandidate = TvStagedSubtitleCandidate(
        id = id,
        sessionId = sessionId,
        selectedSubtitleIndex = selectedIndex,
        selectedAudioIndex = selectedAudioIndex,
        subtitleMode = mode,
        hasSidecar = hasSidecar,
        subtitleTracks = tracks,
        effectiveMediaFileId = effectiveMediaFileId,
        selectedSubtitleIdentity = selectedSubtitleIdentity,
        qualityPreference = qualityPreference,
        outputRouteGeneration = outputRouteGeneration,
    )

    private fun clientOwnedCandidate(
        id: String,
        audioIndex: Int,
    ): TvStagedSubtitleCandidate = candidate(
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
        val adapter: TvSubtitleTransactionAdapter,
        val port: FakeStagedPort,
        val persistence: RecordingPersistence,
        val committedPlaybacks: MutableList<TvSubtitleCommittedPlayback>,
    )

    private fun confirmPendingPlayerBoundary(harness: Harness, snapshotKey: String) {
        val identity = requireNotNull(harness.adapter.snapshot.localMountIdentity)
        harness.adapter.reportMountedSelection(
            identity = identity,
            selected = true,
            snapshotKey = snapshotKey,
            settled = true,
        )
    }

    private class FakeStagedPort : TvSubtitleStagedReplanPort {
        private sealed interface StageOutcome {
            data class Result(
                val value: ApiResult<TvStagedSubtitleCandidate>,
            ) : StageOutcome

            data class Failure(val error: Throwable) : StageOutcome
        }

        val requests = mutableListOf<TvSubtitleStageRequest>()
        val committed = mutableListOf<String>()
        val commitStarted = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val abandoned = mutableListOf<String>()
        var suspendCommits = false
        var commitFailure: ApiResult<TvSubtitleCommittedPlayback>? = null
        var commitThrowable: Throwable? = null
        var discardThrowable: Throwable? = null
        private val stageResults = Channel<StageOutcome>(Channel.UNLIMITED)
        private val commitResults = Channel<String>(Channel.UNLIMITED)

        override suspend fun stage(request: TvSubtitleStageRequest): ApiResult<TvStagedSubtitleCandidate> {
            requests += request
            return when (val outcome = stageResults.receive()) {
                is StageOutcome.Result -> outcome.value
                is StageOutcome.Failure -> throw outcome.error
            }
        }

        override suspend fun commit(
            candidate: TvStagedSubtitleCandidate,
        ): ApiResult<TvSubtitleCommittedPlayback> {
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
                TvSubtitleCommittedPlayback(
                    sessionId = candidate.sessionId,
                    subtitleTracks = candidate.subtitleTracks,
                    effectiveMediaFileId = candidate.effectiveMediaFileId,
                    outputRouteGeneration = candidate.outputRouteGeneration,
                ),
            )
        }

        override suspend fun discard(candidate: TvStagedSubtitleCandidate) {
            discarded += candidate.id
            discardThrowable?.let {
                discardThrowable = null
                throw it
            }
        }

        override suspend fun abandonCommitted(playback: TvSubtitleCommittedPlayback) {
            abandoned += playback.sessionId
        }

        suspend fun completeStage(candidate: TvStagedSubtitleCandidate) {
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

    private class RecordingPersistence : TvSubtitlePersistencePort {
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
            context: TvSubtitlePlaybackContext,
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
