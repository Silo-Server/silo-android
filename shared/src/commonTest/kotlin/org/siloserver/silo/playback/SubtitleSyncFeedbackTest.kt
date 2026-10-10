package org.siloserver.silo.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.subtitles.SubtitleSyncJob
import org.siloserver.silo.model.subtitles.SubtitleSyncState
import org.siloserver.silo.model.subtitles.SubtitleTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleSyncFeedbackTest {
    private val key = "external-" + "a".repeat(64)
    private val corrected = SubtitleTiming(offsetMs = -3_000)

    private fun job(status: String, progress: Double? = null, phase: String? = null, failure: String? = null) =
        SubtitleSyncJob(id = "j1", status = status, trigger = "manual", phase = phase, progress = progress, failure = failure)

    private fun entry(job: SubtitleSyncJob?, timing: SubtitleTiming = SubtitleTiming(), watched: Boolean = true) =
        SubtitleSyncEntry(
            state = SubtitleSyncState(key = key, mediaFileId = "1", source = "external", language = "en", timing = timing, sync = job),
            watchedJobId = job?.id?.takeIf { watched },
        )

    /** One update: the sync state of [entry], with [key] on screen at a revision whose cues loaded up to [loaded]. */
    private fun input(entry: SubtitleSyncEntry, onScreen: Boolean = true, revision: Int = 0, loaded: Int = 0) =
        SubtitleSyncUiState(available = true, externalAvailable = true, entries = mapOf(key to entry))
            .feedbackInput(
                activeKey = key.takeIf { onScreen },
                cueRevisions = mapOf(key to revision),
                loadedCueRevisions = mapOf(key to loaded),
                nameOf = { "English" },
            )

    private fun SubtitleSyncFeedbackState.after(vararg inputs: SubtitleSyncFeedbackInput) =
        inputs.fold(this) { state, input -> state.step(input) }

    @Test
    fun followsAnOwnSyncUntilTheNewCuesShow() {
        var state = SubtitleSyncFeedbackState().after(input(entry(job(SubtitleSyncJob.RUNNING, 0.4, "analyzing"))))
        assertEquals(
            SubtitleSyncNotice("j1", SubtitleSyncNotice.Tone.Progress, "Syncing English subtitles", "Listening to the audio…", 40),
            state.notice,
        )

        val synced = entry(job(SubtitleSyncJob.SYNCED), corrected)
        state = state.after(input(synced))
        assertEquals("Applying new timing…", state.notice?.title)
        assertEquals(100, state.notice?.percent)

        // The player asks for the corrected cues; it is this viewer's own change.
        state = state.after(input(synced, revision = 1))
        assertEquals("Applying new timing…", state.notice?.title)
        assertNull(state.foreign)

        state = state.after(input(synced, revision = 1, loaded = 1))
        assertEquals(
            SubtitleSyncNotice("j1", SubtitleSyncNotice.Tone.Success, "Subtitles synced", "−3.0 s"),
            state.notice,
        )
        assertTrue("j1" in state.applied)
        // A later update does not announce it again.
        assertEquals(state.notice, state.after(input(synced, revision = 1, loaded = 1)).notice)
    }

    @Test
    fun aSyncOfATrackNotOnScreenSaysSoAtOnce() {
        val state = SubtitleSyncFeedbackState().after(
            input(entry(job(SubtitleSyncJob.PENDING, 0.0, "queued")), onScreen = false),
            input(entry(job(SubtitleSyncJob.SYNCED), corrected), onScreen = false),
        )
        assertEquals(
            SubtitleSyncNotice("j1", SubtitleSyncNotice.Tone.Success, "English subtitles synced", "−3.0 s"),
            state.notice,
        )
    }

    @Test
    fun warnsWhenASyncCannotHelp() {
        val noMatch = SubtitleSyncFeedbackState().after(input(entry(job(SubtitleSyncJob.NO_MATCH))))
        assertEquals(SubtitleSyncNotice.Tone.Warning, noMatch.notice?.tone)
        assertEquals("English subtitles don't match the audio", noMatch.notice?.title)
        assertEquals("They're probably for another release. The timing wasn't changed.", noMatch.notice?.detail)

        val failed = SubtitleSyncFeedbackState().after(input(entry(job(SubtitleSyncJob.FAILED, failure = "unavailable"))))
        assertEquals("Couldn't sync English subtitles", failed.notice?.title)
        assertEquals("The server is busy. Try again in a few minutes.", failed.notice?.detail)

        val already = SubtitleSyncFeedbackState().after(input(entry(job(SubtitleSyncJob.ALREADY_SYNCED))))
        assertEquals(SubtitleSyncNotice.Tone.Info, already.notice?.tone)
        assertEquals("English subtitles already match the audio", already.notice?.title)
    }

    @Test
    fun someoneElsesChangeIsAnnouncedOnceItsCuesShow() {
        val other = entry(job(SubtitleSyncJob.SYNCED), corrected, watched = false)
        var state = SubtitleSyncFeedbackState().after(input(other))
        assertNull(state.notice)
        state = state.after(input(other, revision = 1))
        assertNull(state.notice)
        state = state.after(input(other, revision = 1, loaded = 1))
        assertEquals("Subtitle timing updated", state.notice?.title)
        assertEquals(SubtitleSyncNotice.Tone.Info, state.notice?.tone)
    }

    @Test
    fun aProgressCardNobodyFollowsLeaves() {
        var state = SubtitleSyncFeedbackState().after(input(entry(job(SubtitleSyncJob.RUNNING, 0.2))))
        assertEquals(SubtitleSyncNotice.Tone.Progress, state.notice?.tone)
        // Another file: the entries are gone.
        state = state.step(SubtitleSyncFeedbackInput(watched = null, activeKey = null))
        assertNull(state.notice)
    }

    @Test
    fun finishedCardsLeaveOnTheirOwnAndApplyingGivesUpWaiting() = runTest {
        val tracker = SubtitleSyncFeedbackTracker(backgroundScope)
        tracker.update(input(entry(job(SubtitleSyncJob.RUNNING, 0.5))))
        tracker.update(input(entry(job(SubtitleSyncJob.SYNCED), corrected)))
        assertEquals("Applying new timing…", tracker.notice.value?.title)
        // The reload never reports: the success shows anyway.
        advanceTimeBy(SubtitleSyncFeedbackTracker.APPLY_TIMEOUT_MS + 1)
        assertEquals("Subtitles synced", tracker.notice.value?.title)
        advanceTimeBy(SubtitleSyncFeedbackTracker.NOTICE_VISIBLE_MS + 1)
        assertNull(tracker.notice.value)

        val warning = SubtitleSyncFeedbackTracker(backgroundScope)
        warning.update(input(entry(job(SubtitleSyncJob.NO_MATCH))))
        advanceTimeBy(SubtitleSyncFeedbackTracker.NOTICE_VISIBLE_MS + 1)
        assertEquals(SubtitleSyncNotice.Tone.Warning, warning.notice.value?.tone)
        advanceTimeBy(SubtitleSyncFeedbackTracker.WARNING_VISIBLE_MS)
        assertNull(warning.notice.value)

        warning.update(input(entry(job(SubtitleSyncJob.FAILED).copy(id = "j2"))))
        runCurrent()
        warning.dismiss()
        assertNull(warning.notice.value)
    }
}
