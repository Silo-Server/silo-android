package org.siloserver.silo.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.siloserver.silo.model.subtitles.SubtitleSyncJob
import org.siloserver.silo.model.subtitles.SubtitleSyncState
import org.siloserver.silo.model.subtitles.SubtitleTiming
import org.siloserver.silo.model.subtitles.describeSyncTiming
import org.siloserver.silo.model.subtitles.syncFailureMessage
import org.siloserver.silo.model.subtitles.syncPhaseLabel
import org.siloserver.silo.model.subtitles.syncProgressPercent

/** What the player's subtitle sync card shows. */
data class SubtitleSyncNotice(
    /** Stays the same while one job runs, so the card updates in place. */
    val id: String,
    val tone: Tone,
    val title: String,
    val detail: String? = null,
    /** 0..100 while a job runs. */
    val percent: Int? = null,
) {
    enum class Tone { Progress, Success, Info, Warning }
}

/** The most recent job this viewer started, with its subtitle. */
data class SubtitleSyncWatched(
    val key: String,
    /** The subtitle's language name, or null when it has none to show. */
    val name: String?,
    val job: SubtitleSyncJob,
    val timing: SubtitleTiming,
    /** The subtitle's cue revision now. */
    val revision: Int,
)

/**
 * What one update tells the feedback about sync state and the screen. A cue
 * revision grows each time the player fetches a track again because its
 * timing changed; the loaded revision is the one whose cues are on screen.
 */
data class SubtitleSyncFeedbackInput(
    val watched: SubtitleSyncWatched?,
    /** Sync key of the track on screen. */
    val activeKey: String?,
    val activeRevision: Int = 0,
    val activeLoadedRevision: Int = 0,
    /** The entry of the track on screen. */
    val active: SubtitleSyncEntry? = null,
)

data class SubtitleSyncFeedbackState(
    val input: SubtitleSyncFeedbackInput? = null,
    val notice: SubtitleSyncNotice? = null,
    /** Watched jobs whose outcome was announced. */
    val announced: Set<String> = emptySet(),
    /** Watched synced jobs whose corrected cues are showing. */
    val applied: Set<String> = emptySet(),
    /** Cue revision of a watched job's track when the job was first seen. */
    val startRevision: Map<String, Int> = emptyMap(),
    val applying: Applying? = null,
    /** A timing change of the track on screen nobody here asked for, until it shows. */
    val foreign: Foreign? = null,
) {
    data class Applying(
        val key: String,
        val jobId: String,
        val detail: String,
        /** The track's cue revision when the job started; a later one that loads shows the result. */
        val startRevision: Int,
    )

    data class Foreign(val key: String, val revision: Int)
}

/** Shows the success the applying job earned once its cues show. */
fun SubtitleSyncFeedbackState.finishApplying(): SubtitleSyncFeedbackState {
    val applying = applying ?: return this
    return copy(
        applying = null,
        applied = applied + applying.jobId,
        notice = SubtitleSyncNotice(
            id = applying.jobId,
            tone = SubtitleSyncNotice.Tone.Success,
            title = "Subtitles synced",
            detail = applying.detail,
        ),
    )
}

private fun subtitlesOf(name: String?): String = name?.let { "$it subtitles" } ?: "subtitles"

private fun outcomeNotice(watched: SubtitleSyncWatched): SubtitleSyncNotice? {
    val job = watched.job
    val subject = subtitlesOf(watched.name).replaceFirstChar(Char::uppercaseChar)
    return when (job.status) {
        SubtitleSyncJob.SYNCED -> SubtitleSyncNotice(
            id = job.id,
            tone = SubtitleSyncNotice.Tone.Success,
            title = "$subject synced",
            detail = describeSyncTiming(watched.timing),
        )
        SubtitleSyncJob.ALREADY_SYNCED -> SubtitleSyncNotice(
            id = job.id,
            tone = SubtitleSyncNotice.Tone.Info,
            title = "$subject already match the audio",
        )
        SubtitleSyncJob.NO_MATCH -> SubtitleSyncNotice(
            id = job.id,
            tone = SubtitleSyncNotice.Tone.Warning,
            title = "$subject don't match the audio",
            detail = "They're probably for another release. The timing wasn't changed.",
        )
        SubtitleSyncJob.FAILED -> SubtitleSyncNotice(
            id = job.id,
            tone = SubtitleSyncNotice.Tone.Warning,
            title = "Couldn't sync ${subtitlesOf(watched.name)}",
            detail = syncFailureMessage(job.failure),
        )
        else -> null
    }
}

/**
 * Advances the feedback by one update: the progress, then the outcome, of a
 * sync this viewer started; when it changed the subtitle on screen, the
 * moment its corrected cues show; and a note when someone else's change to
 * the subtitle on screen shows. Mirrors the web player's feedback.
 */
fun SubtitleSyncFeedbackState.step(input: SubtitleSyncFeedbackInput): SubtitleSyncFeedbackState {
    val before = this.input
    var next = copy(input = input)
    fun show(notice: SubtitleSyncNotice?) {
        if (next.notice != notice) next = next.copy(notice = notice)
    }
    fun loadedAfter(key: String, revision: Int) = key == input.activeKey && input.activeLoadedRevision > revision

    val watched = input.watched
    if (watched != null) {
        val job = watched.job
        if (job.id !in next.startRevision) {
            next = next.copy(startRevision = next.startRevision + (job.id to watched.revision))
        }
        if (job.inProgress) {
            show(
                SubtitleSyncNotice(
                    id = job.id,
                    tone = SubtitleSyncNotice.Tone.Progress,
                    title = "Syncing ${subtitlesOf(watched.name)}",
                    detail = syncPhaseLabel(job),
                    percent = syncProgressPercent(job) ?: 0,
                ),
            )
        } else if (job.id !in next.announced) {
            next = next.copy(announced = next.announced + job.id)
            if (job.status == SubtitleSyncJob.SYNCED && watched.key == input.activeKey) {
                next = next.copy(
                    applying = SubtitleSyncFeedbackState.Applying(
                        key = watched.key,
                        jobId = job.id,
                        detail = describeSyncTiming(watched.timing),
                        startRevision = next.startRevision[job.id] ?: watched.revision,
                    ),
                )
                show(SubtitleSyncNotice(job.id, SubtitleSyncNotice.Tone.Progress, "Applying new timing…", percent = 100))
            } else {
                show(outcomeNotice(watched))
            }
        }
    }
    // A progress card whose job is no longer followed (another file, or its
    // polling gave up) would otherwise stay up forever.
    next.notice?.let { notice ->
        if (notice.tone == SubtitleSyncNotice.Tone.Progress &&
            notice.id != next.applying?.jobId &&
            notice.id != watched?.job?.id
        ) {
            show(null)
        }
    }

    next.applying?.let { applying ->
        if (applying.key != input.activeKey || loadedAfter(applying.key, applying.startRevision)) {
            next = next.finishApplying()
        }
    }

    val activeKey = input.activeKey
    if (activeKey != null && before?.activeKey == activeKey && input.activeRevision > before.activeRevision) {
        val active = input.active
        val job = active?.state?.sync
        val own = active?.watchedJobId != null && job?.id == active.watchedJobId &&
            (job.inProgress || (job.status == SubtitleSyncJob.SYNCED && job.id !in next.applied))
        if (!own) next = next.copy(foreign = SubtitleSyncFeedbackState.Foreign(activeKey, before.activeRevision))
    }
    next.foreign?.let { foreign ->
        if (foreign.key != activeKey) {
            next = next.copy(foreign = null)
        } else if (loadedAfter(foreign.key, foreign.revision)) {
            next = next.copy(foreign = null)
            show(
                SubtitleSyncNotice(
                    id = "timing:$activeKey:${input.activeRevision}",
                    tone = SubtitleSyncNotice.Tone.Info,
                    title = "Subtitle timing updated",
                ),
            )
        }
    }
    return next
}

/** The most recent job this viewer started, among the file's subtitles, while it is still followed. */
fun SubtitleSyncUiState.watchedEntry(): SubtitleSyncEntry? =
    entries.values
        .filter { entry ->
            entry.watchedJobId != null && entry.state.sync?.id == entry.watchedJobId &&
                !(entry.inProgress && entry.pollExpired)
        }
        .maxByOrNull { it.state.sync?.createdAt.orEmpty() }

/** Builds one feedback update from the sync state and what the player shows. */
fun SubtitleSyncUiState.feedbackInput(
    activeKey: String?,
    cueRevisions: Map<String, Int>,
    loadedCueRevisions: Map<String, Int>,
    nameOf: (SubtitleSyncState) -> String?,
): SubtitleSyncFeedbackInput {
    val watched = watchedEntry()?.let { entry ->
        SubtitleSyncWatched(
            key = entry.state.key,
            name = nameOf(entry.state)?.takeIf(String::isNotBlank),
            job = entry.state.sync ?: return@let null,
            timing = entry.state.timing,
            revision = cueRevisions[entry.state.key] ?: 0,
        )
    }
    return SubtitleSyncFeedbackInput(
        watched = watched,
        activeKey = activeKey,
        activeRevision = activeKey?.let(cueRevisions::get) ?: 0,
        activeLoadedRevision = activeKey?.let(loadedCueRevisions::get) ?: 0,
        active = activeKey?.let(entries::get),
    )
}

/**
 * Turns subtitle sync state into one notice at a time for the viewer (see
 * [step]). Finished notices leave on their own; "Applying new timing" gives
 * way to its success after [applyTimeoutMs] even when the reload never
 * reports.
 */
class SubtitleSyncFeedbackTracker(
    private val scope: CoroutineScope,
    private val visibleMs: Long = NOTICE_VISIBLE_MS,
    private val warningVisibleMs: Long = WARNING_VISIBLE_MS,
    private val applyTimeoutMs: Long = APPLY_TIMEOUT_MS,
) {
    private var state = SubtitleSyncFeedbackState()
    private val _notice = MutableStateFlow<SubtitleSyncNotice?>(null)
    val notice: StateFlow<SubtitleSyncNotice?> = _notice.asStateFlow()

    private var applyTimer: Job? = null
    private var hideTimer: Job? = null

    fun update(input: SubtitleSyncFeedbackInput) {
        if (state.input == input) return
        set(state.step(input))
    }

    fun dismiss() = set(state.copy(notice = null))

    private fun set(next: SubtitleSyncFeedbackState) {
        val previous = state
        state = next
        if (next.applying != previous.applying) {
            applyTimer?.cancel()
            val applying = next.applying
            applyTimer = applying?.let {
                scope.launch {
                    delay(applyTimeoutMs)
                    if (state.applying == applying) set(state.finishApplying())
                }
            }
        }
        if (next.notice != previous.notice) {
            hideTimer?.cancel()
            val notice = next.notice
            _notice.value = notice
            hideTimer = notice?.takeIf { it.tone != SubtitleSyncNotice.Tone.Progress }?.let {
                scope.launch {
                    delay(if (notice.tone == SubtitleSyncNotice.Tone.Warning) warningVisibleMs else visibleMs)
                    if (state.notice == notice) set(state.copy(notice = null))
                }
            }
        }
    }

    companion object {
        const val NOTICE_VISIBLE_MS = 6_000L
        const val WARNING_VISIBLE_MS = 9_000L
        const val APPLY_TIMEOUT_MS = 8_000L
    }
}
