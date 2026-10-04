package org.siloserver.silo.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.subtitles.DownloadedSubtitle
import org.siloserver.silo.model.subtitles.SubtitleSyncCapability
import org.siloserver.silo.model.subtitles.SubtitleSyncJob
import org.siloserver.silo.model.subtitles.SubtitleSyncState
import org.siloserver.silo.model.subtitles.SubtitleTiming
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.SubtitleSyncSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleSyncControllerTest {
    private val sidecar = "external-" + "a".repeat(64)
    private val stored = "stored-9"
    private val corrected = SubtitleTiming(offsetMs = -3_010)

    private class FakeSource : SubtitleSyncSource {
        var capability = SubtitleSyncCapability(state = "available", autoSync = true, external = true, allowed = true)
        val states = linkedMapOf<String, SubtitleSyncState>()
        val reads = mutableListOf<String>()
        var readError: ApiResult.Error? = null
        var startError: ApiResult.Error? = null
        /** When set, a start request waits for it before answering. */
        var startGate: CompletableDeferred<Unit>? = null
        var jobs = 0

        override suspend fun syncCapability() = ApiResult.Success(capability)
        override suspend fun listSync(mediaFileId: Int) = ApiResult.Success(states.values.toList())
        override suspend fun readSync(mediaFileId: Int, key: String): ApiResult<SubtitleSyncState> {
            reads += key
            readError?.let { return it }
            return states[key]?.let { ApiResult.Success(it) } ?: ApiResult.Error(404, "not_found", "")
        }
        override suspend fun startSync(mediaFileId: Int, key: String): ApiResult<SubtitleSyncState> {
            startError?.let { return it }
            val job = SubtitleSyncJob(id = "j${++jobs}", status = SubtitleSyncJob.PENDING, trigger = "manual", phase = "queued", progress = 0.0)
            val response = states.getValue(key).copy(sync = job).also { states[key] = it }
            startGate?.await()
            return ApiResult.Success(response)
        }
        override suspend fun resetTiming(mediaFileId: Int, key: String): ApiResult<SubtitleSyncState> =
            ApiResult.Success(states.getValue(key).copy(timing = SubtitleTiming()).also { states[key] = it })

        fun finish(key: String, status: String, timing: SubtitleTiming = states.getValue(key).timing) {
            val state = states.getValue(key)
            states[key] = state.copy(timing = timing, sync = state.sync?.copy(status = status, phase = null, progress = null, result = timing))
        }
    }

    private fun sidecarState(timing: SubtitleTiming = SubtitleTiming()) =
        SubtitleSyncState(key = sidecar, mediaFileId = "1", source = "external", language = "en", format = "srt", label = "a.en.srt", timing = timing)

    private fun storedState() =
        SubtitleSyncState(key = stored, mediaFileId = "1", source = "downloaded", storedSubtitleId = "9", language = "fr", format = "srt", label = "release")

    private fun TestScope.controller(source: FakeSource, changes: MutableList<String>) =
        SubtitleSyncController(source, backgroundScope, onTimingChanged = { changes += it }, pollIntervalMs = 3_000, pollLimitMs = 9_000)

    @Test
    fun loadsTheFileAndOffersSidecarsOnlyWhenTheServerCanSyncThem() = runTest {
        val source = FakeSource().apply { states[sidecar] = sidecarState(); states[stored] = storedState() }
        val sync = controller(source, mutableListOf())
        sync.bind(1, setOf(sidecar, stored))
        runCurrent()
        val state = sync.state.value
        assertTrue(state.available && state.externalAvailable)
        assertEquals(setOf(sidecar, stored), state.entries.keys)
        val actions = assertNotNull(state.timingActionsFor(sidecar))
        assertTrue(actions.canSync)
        assertFalse(actions.canReset)
        assertEquals(SubtitleTimingActions.SIDECAR_NOTE, actions.note)
        assertEquals(SubtitleTimingActions.STORED_NOTE, state.timingActionsFor(stored)?.note)
        // No sync key, nothing to show.
        assertNull(state.timingActionsFor(null))

        source.capability = source.capability.copy(external = false)
        sync.reload()
        runCurrent()
        assertNull(sync.state.value.timingActionsFor(sidecar))
        assertTrue(sync.state.value.timingActionsFor(stored)!!.canSync)
    }

    @Test
    fun followsAStartedSyncByPollingAndReportsTheTimingChangeOnce() = runTest {
        val source = FakeSource().apply { states[sidecar] = sidecarState() }
        val changes = mutableListOf<String>()
        val sync = controller(source, changes)
        sync.bind(1, setOf(sidecar))
        runCurrent()

        sync.requestSync(sidecar)
        runCurrent()
        val entry = sync.state.value.entries.getValue(sidecar)
        assertEquals("j1", entry.watchedJobId)
        assertTrue(entry.inProgress)
        assertEquals(0, sync.state.value.timingActionsFor(sidecar)?.percent)
        assertFalse(sync.state.value.timingActionsFor(sidecar)!!.actionsEnabled)

        source.finish(sidecar, SubtitleSyncJob.SYNCED, corrected)
        advanceTimeBy(3_001)
        val done = sync.state.value.entries.getValue(sidecar)
        assertEquals(SubtitleSyncJob.SYNCED, done.state.sync?.status)
        assertEquals("Synced −3.0 s", done.statusLabel)
        assertEquals(listOf(sidecar), changes)

        // The realtime event that follows reads the same timing: no second reload.
        sync.timingChanged(sidecar)
        runCurrent()
        assertEquals(listOf(sidecar), changes)

        // Reset brings the original timing back, and its cues reload.
        sync.resetTiming(sidecar)
        runCurrent()
        assertEquals(listOf(sidecar, sidecar), changes)
        assertEquals("Original timing", sync.state.value.entries.getValue(sidecar).statusLabel)
    }

    @Test
    fun realtimeUpdatesCarryProgressAndSkipTheNextPoll() = runTest {
        val source = FakeSource().apply { states[sidecar] = sidecarState() }
        val changes = mutableListOf<String>()
        val sync = controller(source, changes)
        sync.bind(1, setOf(sidecar))
        runCurrent()
        sync.requestSync(sidecar)
        runCurrent()
        val job = sync.state.value.entries.getValue(sidecar).state.sync!!

        source.reads.clear()
        sync.syncUpdated(update(job.copy(status = SubtitleSyncJob.RUNNING, phase = "analyzing", progress = 0.6)))
        assertEquals("Syncing… 60%", sync.state.value.entries.getValue(sidecar).statusLabel)
        advanceTimeBy(3_001)
        assertTrue(source.reads.isEmpty(), "a fresh push makes the poll redundant")

        sync.syncUpdated(update(job.copy(status = SubtitleSyncJob.SYNCED, phase = null, progress = null, result = corrected), corrected))
        assertEquals(listOf(sidecar), changes)
        assertEquals("j1", sync.state.value.watchedEntry()?.state?.sync?.id)
    }

    @Test
    fun aStartResponseThatArrivesAfterTheRealtimeOutcomeDoesNotRewindIt() = runTest {
        val source = FakeSource().apply { states[sidecar] = sidecarState() }
        val changes = mutableListOf<String>()
        val sync = controller(source, changes)
        sync.bind(1, setOf(sidecar))
        runCurrent()

        source.startGate = CompletableDeferred()
        sync.requestSync(sidecar)
        runCurrent()
        // The job ran on cached speech: its outcome arrives before the POST answers.
        val finished = SubtitleSyncJob(id = "j1", status = SubtitleSyncJob.SYNCED, trigger = "manual", result = corrected)
        sync.syncUpdated(update(finished, corrected))
        assertEquals(listOf(sidecar), changes)

        source.startGate!!.complete(Unit)
        runCurrent()
        val entry = sync.state.value.entries.getValue(sidecar)
        assertEquals(SubtitleSyncJob.SYNCED, entry.state.sync?.status)
        assertEquals(corrected, entry.state.timing)
        assertEquals("j1", entry.watchedJobId)
        assertEquals(listOf(sidecar), changes, "no second reload for the stale response")
    }

    @Test
    fun anOlderJobsSnapshotDoesNotReplaceANewerJob() = runTest {
        val older = SubtitleSyncJob(id = "1", status = SubtitleSyncJob.SYNCED, createdAt = "2026-10-04T02:00:00.000Z", result = corrected)
        val newer = SubtitleSyncJob(id = "2", status = SubtitleSyncJob.RUNNING, progress = 0.3, createdAt = "2026-10-04T02:05:00.000Z")
        val source = FakeSource().apply { states[sidecar] = sidecarState(corrected).copy(sync = older) }
        val sync = controller(source, mutableListOf())
        sync.bind(1, setOf(sidecar))
        runCurrent()

        sync.syncUpdated(update(newer, corrected))
        // A read that left before the new job was queued answers with the old one.
        sync.timingChanged(sidecar)
        runCurrent()
        assertEquals("2", sync.state.value.entries.getValue(sidecar).state.sync?.id)
        // The same job going back in progress is stale too.
        sync.syncUpdated(update(newer.copy(progress = 0.1), corrected))
        assertEquals(0.3, sync.state.value.entries.getValue(sidecar).state.sync?.progress)
    }

    @Test
    fun someoneElsesChangeReloadsTheTrack() = runTest {
        val source = FakeSource().apply { states[sidecar] = sidecarState() }
        val changes = mutableListOf<String>()
        val sync = controller(source, changes)
        sync.bind(1, setOf(sidecar))
        runCurrent()

        source.states[sidecar] = sidecarState(SubtitleTiming(offsetMs = 500))
        sync.timingChanged(sidecar)
        runCurrent()
        assertEquals(listOf(sidecar), changes)
        assertEquals("Timing adjusted +0.5 s", sync.state.value.entries.getValue(sidecar).statusLabel)
        assertNull(sync.state.value.watchedEntry())

        // A server without the read (or a lost subtitle) still reloads on the event.
        source.readError = ApiResult.Error(404, "not_found", "")
        sync.timingChanged(stored)
        runCurrent()
        assertEquals(listOf(sidecar, stored), changes)
    }

    @Test
    fun aDemoRefusalReplacesTheActionsAndAnUnsupportedFormatHidesSync() = runTest {
        val source = FakeSource().apply { states[sidecar] = sidecarState(); states[stored] = storedState() }
        val sync = controller(source, mutableListOf())
        sync.bind(1, setOf(sidecar, stored))
        runCurrent()

        source.startError = ApiResult.Error(403, "forbidden", "demo")
        sync.requestSync(sidecar)
        runCurrent()
        val refused = assertNotNull(sync.state.value.timingActionsFor(sidecar))
        assertTrue(refused.forbidden)
        assertNull(refused.note)

        source.startError = ApiResult.Error(422, "unsupported", "")
        sync.requestSync(stored)
        runCurrent()
        val unsupported = assertNotNull(sync.state.value.timingActionsFor(stored))
        assertFalse(unsupported.canSync)
        assertEquals("This format can't be synced.", unsupported.error)
    }

    @Test
    fun followsTheAutomaticSyncOfANewDownload() = runTest {
        val source = FakeSource()
        val sync = controller(source, mutableListOf())
        sync.bind(1, setOf(sidecar))
        runCurrent()
        val auto = SubtitleSyncJob(id = "a1", status = SubtitleSyncJob.PENDING, trigger = "auto", createdAt = "2026-10-04T02:00:00.000Z")
        sync.remember(
            DownloadedSubtitle(id = 9, mediaFileId = 1, provider = "upload", language = "fr", format = "srt", releaseName = "x", sync = auto),
        )
        assertEquals("a1", sync.state.value.watchedEntry()?.watchedJobId)
        // Another file's download is not this player's business.
        sync.remember(DownloadedSubtitle(id = 10, mediaFileId = 2, provider = "upload", language = "fr", format = "srt", releaseName = "x", sync = auto))
        assertFalse("stored-10" in sync.state.value.entries)
        // Polling follows it; the inventory gaining its key keeps the watch.
        source.states[stored] = storedState().copy(sync = auto.copy(status = SubtitleSyncJob.RUNNING, progress = 0.2))
        sync.bind(1, setOf(sidecar, stored))
        advanceTimeBy(3_001)
        assertEquals("a1", sync.state.value.entries.getValue(stored).watchedJobId)
        assertEquals("Syncing… 20%", sync.state.value.entries.getValue(stored).statusLabel)
    }

    @Test
    fun pollingGivesUpAfterItsLimitAndAReloadRearmsIt() = runTest {
        val source = FakeSource().apply { states[sidecar] = sidecarState() }
        val sync = controller(source, mutableListOf())
        sync.bind(1, setOf(sidecar))
        runCurrent()
        sync.requestSync(sidecar)
        runCurrent()
        advanceTimeBy(12_001)
        assertTrue(sync.state.value.entries.getValue(sidecar).pollExpired)
        assertNull(sync.state.value.watchedEntry(), "a job nobody can follow leaves the card")
        val reads = source.reads.size
        advanceTimeBy(6_000)
        assertEquals(reads, source.reads.size)

        sync.reload()
        runCurrent()
        assertFalse(sync.state.value.entries.getValue(sidecar).pollExpired)
        advanceTimeBy(3_001)
        assertTrue(source.reads.size > reads)
    }

    @Test
    fun aNewFileDropsLateResults() = runTest {
        val source = FakeSource().apply { states[sidecar] = sidecarState() }
        val sync = controller(source, mutableListOf())
        sync.bind(1, setOf(sidecar))
        sync.bind(2, emptySet())
        runCurrent()
        assertTrue(sync.state.value.entries.isEmpty())
        assertFalse(sync.state.value.available)
    }

    private fun update(job: SubtitleSyncJob, timing: SubtitleTiming = SubtitleTiming()) =
        PlaybackSubtitleSyncUpdated(sessionId = "s", mediaFileId = 1, syncKey = sidecar, subtitleId = null, timing = timing, job = job)
}
