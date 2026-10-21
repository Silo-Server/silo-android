package org.siloserver.silo.domain.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.siloserver.silo.model.playback.BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER
import org.siloserver.silo.model.playback.LEARNED_LIMIT_CLEAR
import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_AUTO
import kotlin.math.max

/** One rung of the plan's quality menu, as far as the controller needs it. */
data class BufferingQualityRung(
    val label: String,
    val bitrateKbps: Int,
)

/** What the controller needs from a plan that just became the playing plan. */
data class BufferingQualityPlan(
    /**
     * The local adoption identity, unique per adoption. The spec's *plan* is
     * one adoption: a replan can return the same server `plan_id` again, and
     * it is still a new plan. The vectors' `plan_id` labels stand for this.
     */
    val planId: String,
    /**
     * The spec's *delivered bitrate*: `effective_recipe.bitrate_kbps`, else
     * `source.bitrate_kbps`. Null or non-positive when the server reported
     * neither, which leaves the controller nothing to step down from.
     */
    val deliveredKbps: Int?,
    /** The plan's quality preference: `auto`, or the rung label the viewer picked. */
    val quality: String,
    /** `available_qualities`, in the server's order. */
    val availableQualities: List<BufferingQualityRung>,
    /** `degradation_warnings` carries `quality_reduction_unavailable`. */
    val qualityReductionUnavailable: Boolean = false,
    /** The server `plan_id`, which can repeat across adoptions. Reported in [BufferingPlanSummary.planId]. */
    val serverPlanId: String = planId,
    /**
     * The `available_qualities` label the plan names in `active_quality`, from
     * a server with `plan_active_quality_v1`; null otherwise. A cap can make it
     * differ from the label the viewer picked.
     */
    val activeQuality: String? = null,
)

/**
 * Why the player started waiting. Only [NETWORK], the player running out of
 * data, can become a stall. [RENDERER] is a wait that began with more than
 * [BufferingQualityController.RENDERER_WAIT_BUFFER_MS] of media buffered past
 * the playhead: the decoder fell behind and the player is catching up. A
 * [NETWORK] wait during it means the buffer fell to that threshold.
 */
enum class BufferingWaitCause { NETWORK, SEEK, PAUSE, RELOAD, RENDERER }

/**
 * The `quality_change` replan the controller wants sent. The caller builds the
 * wire request and drops [bandwidthEstimateSource] and [learnedLimit] when the
 * server does not advertise `buffering_quality_v1`.
 */
data class BufferingQualityReplan(
    val qualityPreference: String,
    val bandwidthEstimateKbps: Int? = null,
    val bandwidthEstimateSource: String? = null,
    val learnedLimit: String? = null,
)

/** Display-only notices. Copy keys match the vectors' `notice.key`. */
enum class BufferingQualityNotice(val key: String) {
    /** "Quality lowered to stop buffering", after an accepted prompt. */
    LOWERED("lowered"),

    /** "Can't lower quality for this video". */
    CANT_LOWER("cant_lower"),
}

/** What the buffering pill should be showing. */
sealed interface BufferingQualityPill {
    data object Hidden : BufferingQualityPill

    /** "Playback keeps buffering", with "Lower quality" and "Keep quality". */
    data class Prompt(val secondsRemaining: Int) : BufferingQualityPill

    /** "Quality lowered to stop buffering", with "Restore quality". */
    data class Undo(val secondsRemaining: Int) : BufferingQualityPill

    val secondsRemainingOrNull: Int?
        get() = when (this) {
            is Prompt -> secondsRemaining
            is Undo -> secondsRemaining
            Hidden -> null
        }

    val isVisible: Boolean get() = this !is Hidden
}

/** `plan_summary.buffering_action`: the last action on a plan. */
enum class BufferingAction(val wireValue: String) {
    NONE("none"),
    IGNORED("ignored"),
    LOWERED("lowered"),
    RESTORED("restored"),
    ACCEPTED("accepted"),
    KEPT("kept"),
    TIMED_OUT("timed_out"),
    CANT_LOWER("cant_lower"),
}

/** One plan's `plan_summary` facts, emitted when it stops being the playing plan. */
data class BufferingPlanSummary(
    /** The server plan_id, for the plan_summary route event. */
    val planId: String,
    val playedMs: Long,
    val stallCount: Int,
    val stallTotalMs: Long,
    val stallLongestMs: Long,
    val deliveredKbps: Int?,
    val mode: BufferingQualityMode,
    val action: BufferingAction,
    /**
     * The summarized adoption, [BufferingQualityPlan.planId]. A summary read
     * early with [BufferingQualityController.runningSummary] and the final one
     * share it, so they can go out as the same event.
     */
    val adoptionId: String = planId,
)

/**
 * The one buffering-quality state machine, shared by the phone and TV players.
 *
 * The contract is the server repo's `docs/design/2026-10-17-buffering-quality.md`
 * (*Terms* and *Behaviour*); `BufferingQualityVectorsTest` replays its vendored
 * `vectors.json` case for case. Read that document before changing anything
 * here.
 *
 * Callers feed it playback events and the viewer's answers; it hands back
 * replans through [onReplan] and display-only notices through [onNotice], and
 * publishes the actionable pill in [pill]. It never touches the player.
 *
 * ### Stall detection
 *
 * Each plan gets a fresh stall tracker, a copy of the web player's
 * `createStallTracker`: stall time inside the startup grace does not count, a
 * stall must last [STALL_MIN_MS] to count, and the trigger is
 * [STALLS_BEFORE_TRIGGER] counted stalls inside [STALL_WINDOW_MS] or one stall
 * reaching [LONG_STALL_MS]. The trigger is evaluated only on [onPlaying] and
 * when a stall in progress reaches [LONG_STALL_MS], never on [onWaiting]. A
 * trigger that is suppressed leaves the history as it is; a timed-out prompt,
 * the rearm after an unadopted replan, `never`, "can't lower" and a viewer
 * quality change clear it.
 *
 * When a trigger is suppressed only by time (the no-lower cooldown or the
 * re-offer delay) while a stall is still in progress, one re-evaluation is
 * scheduled for the moment the suppression lifts, and it runs only if that
 * stall is still in progress. The undo pill ending, by its timer or by Back,
 * re-evaluates the same way when the stall in progress has reached
 * [LONG_STALL_MS]. A suppression that ends during a shorter wait evaluates
 * nothing; the stall's end does. Nothing evaluates during an outage.
 *
 * The answer, the once-per-title notice and the re-offer wait belong to the
 * title: [onNewAttempt] keeps them, and only [reset] (different content) and a
 * quality change clear them. Inside a Watch Party room the controller is
 * [setSuspended]: it keeps timing stalls and summarizing plans, but nothing it
 * would do happens.
 *
 * ### Pills
 *
 * At most one buffering pill exists. Higher-priority pills (intro or other
 * segment skip, Next Up) win: a buffering pill that wants to show while one is
 * up waits with its full timer, and one already showing hides with its timer
 * frozen and comes back with the remainder. Either way it is dropped if the
 * plan changes first. While it waits or hides, the controller is busy.
 *
 * ### Threading
 *
 * Not thread-safe. Call it from the thread [scope] dispatches on, as with
 * [IntroAutoSkipController].
 */
class BufferingQualityController(
    private val scope: CoroutineScope,
    /** Monotonic milliseconds. Timers run on [scope]; this only reads the time. */
    private val clock: () -> Long,
    private val onReplan: (BufferingQualityReplan) -> Unit,
    private val onNotice: (BufferingQualityNotice) -> Unit,
    private val onPlanSummary: (BufferingPlanSummary) -> Unit,
) {
    companion object {
        const val STARTUP_GRACE_MS: Long = 10_000L
        const val STALL_MIN_MS: Long = 1_000L
        const val STALLS_BEFORE_TRIGGER: Int = 2
        const val STALL_WINDOW_MS: Long = 120_000L
        const val LONG_STALL_MS: Long = 6_000L
        const val PROMPT_SECONDS: Int = 15
        const val REOFFER_MIN_MS: Long = 120_000L
        const val NO_LOWER_COOLDOWN_MS: Long = 300_000L
        const val REARM_AFTER_UNADOPTED_MS: Long = 60_000L
        const val RENDERER_WAIT_BUFFER_MS: Long = 1_000L
        /**
         * Documentation only: the step-down estimate is the exact integer
         * `delivered × 7 / 10`, because a floating-point `0.7` can floor one
         * kbps low (22120 must give 15484).
         */
        const val STEP_DOWN_RATIO: Double = 0.7
        const val MIN_ESTIMATE_KBPS: Int = SpeedTestMeter.MIN_KBPS
    }

    private val _pill = MutableStateFlow<BufferingQualityPill>(BufferingQualityPill.Hidden)
    val pill: StateFlow<BufferingQualityPill> = _pill.asStateFlow()

    /** Bumps each time a pill timer (re)starts, so a frame-clock fill can re-anchor. */
    private val _countdownRun = MutableStateFlow(0)
    val countdownRun: StateFlow<Int> = _countdownRun.asStateFlow()

    /** False while a pill is up but its timer is frozen by a pause. */
    private val _timerRunning = MutableStateFlow(false)
    val timerRunning: StateFlow<Boolean> = _timerRunning.asStateFlow()

    // ---- Settings and player state ----------------------------------------

    private var mode: BufferingQualityMode = BufferingQualityMode.Default
    private var enabled = true

    /** In a Watch Party room: plans are tracked and summarized, but no trigger acts. */
    private var suspended = false
    private var otherPillVisible = false

    /** The viewer paused and has not resumed. Freezes a pill's timer. */
    private var paused = false

    /** Media requests are failing: any wait is an outage, not a stall. */
    private var transportTrouble = false

    // ---- The playing plan --------------------------------------------------

    private var current: PlanState? = null

    // ---- Per-title state ---------------------------------------------------

    /** The quality the viewer answered at (Keep or Restore), until they change quality. */
    private var answeredQuality: String? = null

    /** "Can't lower quality" from a trigger shows once per title. */
    private var cantLowerShown = false

    /** After a prompt timed out, no new prompt before this time. */
    private var reofferNotBefore: Long? = null

    /** The replan this controller sent and is waiting to see adopted. */
    private var pending: Pending? = null

    // ---- Pill --------------------------------------------------------------

    /** The buffering pill on screen. */
    private var activePill: PillKind? = null

    /** The buffering pill waiting behind, or hidden by, a higher-priority pill. */
    private var deferredPill: PillKind? = null

    /** [deferredPill] was already shown, so it comes back with [pillRemainingMs]. */
    private var deferredResumes = false

    /** When the current offer first showed, for [REOFFER_MIN_MS]. A resumed prompt keeps it. */
    private var promptShownAt: Long = 0L

    /** The visible or deferred pill's time left. */
    private var pillRemainingMs: Long = 0L
    private var pillStepStartedAt: Long = 0L
    private var pillJob: Job? = null

    // ---- Timers ------------------------------------------------------------

    private var longStallJob: Job? = null
    private var reevaluateJob: Job? = null
    private var rearmJob: Job? = null

    /** The effective `playback.buffering_quality_mode`. */
    fun setMode(mode: BufferingQualityMode): Unit {
        this.mode = mode
        if (mode == BufferingQualityMode.NEVER) hidePill()
    }

    /**
     * False when playback has no server plan (a downloaded file): the pill
     * hides and events are ignored.
     */
    fun setEnabled(enabled: Boolean): Unit {
        if (enabled == this.enabled) return
        if (!enabled) {
            // The playing plan's summary goes out now, because the controller
            // stops following it. Re-enabling starts from the next adoption.
            dropPlan(clock())
        }
        this.enabled = enabled
    }

    /**
     * True inside a Watch Party room, where the room's own lower-quality offer
     * applies. No trigger lowers, prompts or reports `ignored`, and no pill or
     * notice shows; stall tracking and plan summaries continue. Joining or
     * leaving is not a plan boundary: on leaving, the playing plan's history
     * starts fresh with the grace over.
     */
    fun setSuspended(suspended: Boolean): Unit {
        if (suspended == this.suspended) return
        this.suspended = suspended
        if (!enabled) return
        pending = null
        rearmJob?.cancel()
        rearmJob = null
        reevaluateJob?.cancel()
        reevaluateJob = null
        if (suspended) {
            hidePill()
        } else {
            current?.let { resetTracker(it, clock()) }
        }
    }

    /**
     * A higher-priority pill (intro or other segment skip, Next Up) is on
     * screen. A buffering pill that wants to show waits for it with its full
     * timer; one already showing hides with its timer frozen. When the last
     * such pill hides, the buffering pill shows with the time it had left,
     * unless the plan changed meanwhile.
     */
    fun setOtherPillVisible(visible: Boolean): Unit {
        otherPillVisible = visible
        if (!enabled) return
        if (visible) {
            obscurePill()
            return
        }
        // A plan change hides the pill, so a deferred one is still this plan's.
        val kind = deferredPill ?: return
        present(kind, resume = deferredResumes)
    }

    /** A plan is playing, or loading, under this controller. */
    val hasPlan: Boolean get() = current != null

    /**
     * The playing plan's summary so far, without ending the plan: what
     * [onPlanSummary] would get if the plan stopped now. Null without a plan.
     */
    fun runningSummary(): BufferingPlanSummary? = current?.let { summaryOf(it, clock()) }

    /**
     * A new adoption became the playing plan. [BufferingQualityPlan.planId]
     * identifies the adoption; a repeat of the current one is ignored.
     * [controllerInitiated] false means it answers a replan the controller did
     * not send (a track change, a recovery): a pending lowering is dropped,
     * with no notice or cooldown.
     */
    fun onPlanAdopted(plan: BufferingQualityPlan, controllerInitiated: Boolean = true): Unit {
        if (!enabled) return
        if (current?.plan?.planId == plan.planId) return
        val now = clock()
        // An adopted plan starts with no outage: its transport is a new one.
        transportTrouble = false
        val settled = pending
        dropPlan(now)
        val state = PlanState(plan)
        current = state

        settled ?: return
        if (!controllerInitiated || settled.kind == PendingKind.RESTORE) return
        val delivered = plan.deliveredKbps?.takeIf { it > 0 }
        val noLower = plan.qualityReductionUnavailable ||
            (delivered != null && settled.fromKbps != null && delivered >= settled.fromKbps)
        when {
            noLower -> {
                state.action = BufferingAction.CANT_LOWER
                state.cooldownUntil = now + NO_LOWER_COOLDOWN_MS
                cantLowerShown = true
                onNotice(BufferingQualityNotice.CANT_LOWER)
            }
            settled.kind == PendingKind.AUTOMATIC -> showPill(PillKind.UNDO)
            else -> onNotice(BufferingQualityNotice.LOWERED)
        }
    }

    /** Playback is running: the first frame, or a resume from waiting or pause. */
    fun onPlaying(): Unit {
        paused = false
        if (!enabled) return
        val state = current ?: return
        val now = clock()
        thawPill()
        if (state.playingSince == null) state.playingSince = now
        val tracker = state.tracker
        if (tracker.firstPlayingAt == null) tracker.firstPlayingAt = now
        tracker.stalled = false

        state.statsStallStartedAt?.let { recordStallStat(state, now - it) }
        state.statsStallStartedAt = null

        val startedAt = tracker.stallStartedAt
        if (startedAt != null) {
            clearStallTiming(state)
            // A stall timed during transport trouble cannot exist: trouble
            // drops the timing and only its end re-times the stall.
            val duration = now - startedAt
            if (duration >= LONG_STALL_MS) {
                tracker.longStallEndedAt = now
            } else if (duration >= STALL_MIN_MS) {
                tracker.stallEnds += now
            }
        }
        evaluate(now)
    }

    /**
     * The player is waiting for [cause]; only a [BufferingWaitCause.NETWORK]
     * wait can be a stall. Until the plan first plays, a reload or seek wait
     * counts as one too: a start the viewer means to play is the plan's wait,
     * whatever the start reopens or seeks on the way, and a pause before then
     * ends the current wait without ending that. Any wait
     * but a [BufferingWaitCause.PAUSE] one means the player is trying to play,
     * so the viewer has not paused: a pill frozen by a stop the viewer did not
     * make, such as a player error, runs again.
     */
    fun onWaiting(cause: BufferingWaitCause = BufferingWaitCause.NETWORK): Unit {
        if (cause != BufferingWaitCause.PAUSE) paused = false
        if (!enabled) return
        val state = current ?: return
        val now = clock()
        if (!paused) thawPill()
        stopPlayedClock(state, now)
        val tracker = state.tracker
        val startWait = tracker.starting &&
            (cause == BufferingWaitCause.RELOAD || cause == BufferingWaitCause.SEEK)
        if (cause != BufferingWaitCause.NETWORK && !startWait) {
            interrupt(state, now)
            return
        }
        tracker.stalled = true
        tracker.noteWaiting(now)
        if (transportTrouble) return
        startStallTiming(state, now)
    }

    /** The viewer paused. */
    fun onPause(): Unit {
        paused = true
        if (!enabled) return
        freezePill()
        val state = current ?: return
        val now = clock()
        stopPlayedClock(state, now)
        interrupt(state, now)
    }

    /** The viewer seeked. A seek before the plan first plays leaves the start's wait running. */
    fun onSeek(): Unit {
        if (!enabled) return
        val state = current ?: return
        if (state.tracker.starting) return
        val now = clock()
        stopPlayedClock(state, now)
        interrupt(state, now)
    }

    /** Media requests started failing, or the device went offline; the outage owns any wait until it ends. */
    fun onTransportErrorStart(): Unit {
        transportTrouble = true
        if (!enabled) return
        val state = current ?: return
        if (state.tracker.stallStartedAt != null) {
            // Still stalled, but the outage owns this wait now.
            clearStallTiming(state)
            state.statsStallStartedAt = null
        }
    }

    /** Media requests recovered. */
    fun onTransportErrorEnd(): Unit {
        transportTrouble = false
        if (!enabled) return
        val state = current ?: return
        if (state.tracker.stalled && state.tracker.stallStartedAt == null) {
            startStallTiming(state, clock())
        }
    }

    /** The controller's last replan failed or will never be adopted. */
    fun onReplanFailed(): Unit {
        if (!enabled) return
        // The rearm timer keeps running: the current plan is watched again
        // REARM_AFTER_UNADOPTED_MS after the replan was sent.
        pending = null
    }

    /**
     * A quality change took effect: a quality or version the viewer picked, or
     * the quality pair of the network playback moved to, now plays. Call it
     * before adopting that plan. A change the server refused is none.
     */
    fun onViewerQualityChange(): Unit {
        if (!enabled) return
        hidePill()
        answeredQuality = null
        reofferNotBefore = null
        pending = null
        rearmJob?.cancel()
        rearmJob = null
        current?.let { resetTracker(it, clock()) }
    }

    /** The playing plan stopped (ended, closed or failed) without a successor. */
    fun onPlaybackStopped(): Unit {
        if (!enabled) return
        dropPlan(clock())
    }

    /**
     * A new playback attempt of the same title that the viewer did not choose
     * (a renewal after a server restart, Retry). The playing plan ends; the
     * answer, the once-per-title notice and the re-offer wait stay, because
     * they belong to the title. A version switch the viewer chooses is a
     * quality change as well: call [onViewerQualityChange] too.
     */
    fun onNewAttempt(): Unit = onPlaybackStopped()

    /** "Lower quality" on the prompt. True when a prompt was answered. */
    fun selectLower(): Boolean {
        if (activePill != PillKind.PROMPT) return false
        val state = current ?: return false
        // A prompt shows only for a plan with a lower rung, and adoption hides it.
        val replan = loweringFor(state.plan) ?: return false
        hidePill()
        sendLowering(state, replan, PendingKind.ACCEPTED)
        return true
    }

    /** "Keep quality" on the prompt. True when a prompt was answered. */
    fun selectKeep(): Boolean {
        if (activePill != PillKind.PROMPT) return false
        val state = current ?: return false
        hidePill()
        answeredQuality = state.plan.quality
        state.action = BufferingAction.KEPT
        return true
    }

    /** "Restore quality" on the undo pill. True when an undo was answered. */
    fun selectRestore(): Boolean {
        if (activePill != PillKind.UNDO) return false
        val state = current ?: return false
        hidePill()
        state.action = BufferingAction.RESTORED
        answeredQuality = PLAYBACK_QUALITY_AUTO
        pending = Pending(PendingKind.RESTORE, fromKbps = null)
        startRearm()
        onReplan(BufferingQualityReplan(qualityPreference = PLAYBACK_QUALITY_AUTO, learnedLimit = LEARNED_LIMIT_CLEAR))
        return true
    }

    /** Select/OK at the player root: the visible pill's primary action. */
    fun selectPrimary(): Boolean = when (activePill) {
        PillKind.PROMPT -> selectLower()
        PillKind.UNDO -> selectRestore()
        null -> false
    }

    /** Back while a pill shows. True when a pill consumed it. */
    fun back(): Boolean = when (activePill) {
        // Back on the prompt is Keep quality.
        PillKind.PROMPT -> selectKeep()
        // Back on the undo only hides it; the lower plan keeps playing. Its
        // suppression ends as the timer's would.
        PillKind.UNDO -> {
            hidePill()
            undoEnded()
            true
        }
        null -> false
    }

    /** Clears all per-title state, for when playback moves to different content. */
    fun reset(): Unit {
        dropPlan(clock())
        answeredQuality = null
        cantLowerShown = false
        reofferNotBefore = null
        promptShownAt = 0L
    }

    // ---- Trigger -----------------------------------------------------------

    private fun evaluate(now: Long) {
        if (!enabled || suspended || transportTrouble) return
        val state = current ?: return
        if (!state.tracker.shouldTrigger(now)) return
        if (isBusy()) return
        state.cooldownUntil?.let { until ->
            if (now < until) {
                suppressUntil(state, until, now)
                return
            }
        }
        val plan = state.plan
        if (answeredQuality == plan.quality) return
        if (mode == BufferingQualityMode.NEVER) {
            state.action = BufferingAction.IGNORED
            resetTracker(state, now)
            return
        }
        val lowering = loweringFor(plan)
        if (lowering == null) {
            showCantLower(state)
            resetTracker(state, now)
            return
        }
        if (plan.quality == PLAYBACK_QUALITY_AUTO && mode == BufferingQualityMode.ALWAYS) {
            sendLowering(state, lowering, PendingKind.AUTOMATIC)
            return
        }
        reofferNotBefore?.let { notBefore ->
            if (now < notBefore) {
                suppressUntil(state, notBefore, now)
                return
            }
        }
        showPill(PillKind.PROMPT)
    }

    /**
     * Something of ours is already in flight: a replan, the rearm wait after
     * one that was not adopted, or a pill that is showing, waiting or hidden
     * behind a higher-priority pill.
     */
    private fun isBusy(): Boolean =
        pending != null || rearmJob?.isActive == true || activePill != null || deferredPill != null

    /**
     * A trigger held but a time-based rule blocked it. While the stall is
     * still in progress, look again when the rule lifts; the stall's own end
     * evaluates otherwise.
     */
    private fun suppressUntil(state: PlanState, liftAt: Long, now: Long) {
        if (state.tracker.stallStartedAt == null) return
        reevaluateJob?.cancel()
        reevaluateJob = scope.launch {
            delay(liftAt - now)
            reevaluateJob = null
            if (current === state && state.tracker.stallStartedAt != null) evaluate(clock())
        }
    }

    /** The replan that lowers [plan] one step, or null when nothing is lower. */
    private fun loweringFor(plan: BufferingQualityPlan): BufferingQualityReplan? {
        val rungs = plan.availableQualities
            .filter { it.bitrateKbps > 0 }
            .sortedByDescending { it.bitrateKbps }
        val delivered = plan.deliveredKbps?.takeIf { it > 0 }
        if (plan.quality == PLAYBACK_QUALITY_AUTO) {
            delivered ?: return null
            if (rungs.none { it.bitrateKbps < delivered }) return null
            // The exact integer floor of delivered × 7 / 10; see STEP_DOWN_RATIO.
            val estimate = (delivered.toLong() * 7L / 10L).toInt()
            if (estimate < MIN_ESTIMATE_KBPS) return null
            return BufferingQualityReplan(
                qualityPreference = PLAYBACK_QUALITY_AUTO,
                bandwidthEstimateKbps = estimate,
                bandwidthEstimateSource = BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER,
            )
        }
        // The spec's lower rung: below the lower of the rung the plan names and
        // the delivered bitrate, or the delivered bitrate alone when the plan
        // names no entry. The picked label does not set it.
        val named = plan.activeQuality?.let { name -> rungs.firstOrNull { it.label == name } }?.bitrateKbps
        val ceiling = listOfNotNull(named, delivered).minOrNull() ?: return null
        val lower = rungs.firstOrNull { it.bitrateKbps < ceiling } ?: return null
        return BufferingQualityReplan(qualityPreference = lower.label)
    }

    private fun sendLowering(state: PlanState, replan: BufferingQualityReplan, kind: PendingKind) {
        // "Lower" compares delivered bitrates: the plan the lowering left
        // against the one adopted.
        pending = Pending(kind, fromKbps = state.plan.deliveredKbps?.takeIf { it > 0 })
        state.action =
            if (kind == PendingKind.AUTOMATIC) BufferingAction.LOWERED else BufferingAction.ACCEPTED
        startRearm()
        onReplan(replan)
    }

    private fun showCantLower(state: PlanState) {
        state.action = BufferingAction.CANT_LOWER
        if (cantLowerShown) return
        cantLowerShown = true
        onNotice(BufferingQualityNotice.CANT_LOWER)
    }

    /** Rearm is timed from the send, whether or not the replan later fails. */
    private fun startRearm() {
        rearmJob?.cancel()
        rearmJob = scope.launch {
            delay(REARM_AFTER_UNADOPTED_MS)
            rearmJob = null
            pending = null
            current?.let { resetTracker(it, clock()) }
        }
    }

    // ---- Stall tracker -----------------------------------------------------

    /** Starts timing the stall in progress, from the end of the grace at the earliest. */
    private fun startStallTiming(state: PlanState, now: Long) {
        val tracker = state.tracker
        val startedAt = tracker.stallStartedAt ?: max(now, tracker.graceEndsAt(now))
        tracker.stallStartedAt = startedAt
        if (state.statsStallStartedAt == null) state.statsStallStartedAt = startedAt
        if (longStallJob?.isActive == true) return
        longStallJob = scope.launch {
            delay(startedAt + LONG_STALL_MS - now)
            longStallJob = null
            if (current === state && state.tracker.stallStartedAt == startedAt) evaluate(clock())
        }
    }

    /** Stops timing the current stall without ending it. */
    private fun clearStallTiming(state: PlanState) {
        state.tracker.stallStartedAt = null
        longStallJob?.cancel()
        longStallJob = null
        reevaluateJob?.cancel()
        reevaluateJob = null
    }

    /**
     * A seek, pause or reload: the wait from here on is not the network's. A
     * stall in progress ends uncounted, its time kept for the summary.
     */
    private fun interrupt(state: PlanState, now: Long) {
        state.tracker.stalled = false
        clearStallTiming(state)
        state.statsStallStartedAt?.let { recordStallStat(state, now - it, count = false) }
        state.statsStallStartedAt = null
    }

    /**
     * Clears the stall history with the grace treated as elapsed, and times a
     * stall still in progress from now.
     */
    private fun resetTracker(state: PlanState, now: Long) {
        val tracker = state.tracker
        tracker.firstPlayingAt = now - STARTUP_GRACE_MS
        tracker.firstWaitingAt = null
        tracker.stallEnds.clear()
        tracker.longStallEndedAt = null
        clearStallTiming(state)
        if (tracker.stalled && !transportTrouble) startStallTiming(state, now)
    }

    // ---- Summary -----------------------------------------------------------

    private fun stopPlayedClock(state: PlanState, now: Long) {
        state.playingSince?.let { state.playedMs += (now - it).coerceAtLeast(0L) }
        state.playingSince = null
    }

    /**
     * Adds one stall to the summary: every stall's time counts in the total
     * and the longest, and a [count]ed one of [STALL_MIN_MS] or more in the
     * count.
     */
    private fun recordStallStat(state: PlanState, durationMs: Long, count: Boolean = true) {
        if (durationMs <= 0L) return
        if (count && durationMs >= STALL_MIN_MS) state.stallCount += 1
        state.stallTotalMs += durationMs
        state.stallLongestMs = max(state.stallLongestMs, durationMs)
    }

    /**
     * [state]'s summary as of [now], leaving [state] as it is. Play still
     * running counts up to [now]. A stall still in progress counts like one
     * that resumed at [now], as [recordStallStat] would count it, because a
     * plan replaced mid-stall is usually the one that stalled.
     */
    private fun summaryOf(state: PlanState, now: Long): BufferingPlanSummary {
        val playingMs = state.playingSince?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        val stallMs = state.statsStallStartedAt?.let { now - it }?.takeIf { it > 0L } ?: 0L
        return BufferingPlanSummary(
            planId = state.plan.serverPlanId,
            playedMs = state.playedMs + playingMs,
            stallCount = state.stallCount + if (stallMs >= STALL_MIN_MS) 1 else 0,
            stallTotalMs = state.stallTotalMs + stallMs,
            stallLongestMs = max(state.stallLongestMs, stallMs),
            deliveredKbps = state.plan.deliveredKbps,
            mode = mode,
            action = state.action,
            adoptionId = state.plan.planId,
        )
    }

    /**
     * Emits the playing plan's summary, final now that the plan is over, and
     * forgets the plan, anything in flight and its timers.
     */
    private fun dropPlan(now: Long) {
        current?.let { onPlanSummary(summaryOf(it, now)) }
        current = null
        pending = null
        hidePill()
        longStallJob?.cancel()
        longStallJob = null
        reevaluateJob?.cancel()
        reevaluateJob = null
        rearmJob?.cancel()
        rearmJob = null
    }

    // ---- Pill --------------------------------------------------------------

    /** Offers a fresh [kind] now, or with its full timer once the other pill hides. */
    private fun showPill(kind: PillKind) {
        if (otherPillVisible) {
            deferredPill = kind
            deferredResumes = false
            return
        }
        present(kind, resume = false)
    }

    /**
     * Puts [kind] on screen. A fresh pill starts its full timer and, for the
     * prompt, the re-offer clock; a [resume] keeps both and continues from
     * [pillRemainingMs].
     */
    private fun present(kind: PillKind, resume: Boolean) {
        clearDeferred()
        activePill = kind
        if (!resume) {
            pillRemainingMs = PROMPT_SECONDS * 1000L
            if (kind == PillKind.PROMPT) promptShownAt = clock()
        }
        publishPill()
        if (paused) _timerRunning.value = false else startPillTimer()
    }

    /** A higher-priority pill arrived: hide the visible pill and keep its time left. */
    private fun obscurePill() {
        val kind = activePill ?: return
        stopPillTimer()
        activePill = null
        deferredPill = kind
        deferredResumes = true
        if (_pill.value !is BufferingQualityPill.Hidden) _pill.value = BufferingQualityPill.Hidden
    }

    private fun clearDeferred() {
        deferredPill = null
        deferredResumes = false
    }

    /**
     * Runs the wall-clock timer down from [pillRemainingMs], publishing at
     * each whole second. The remainder is kept in milliseconds so a pause
     * resumes from exactly where it froze.
     */
    private fun startPillTimer() {
        pillJob?.cancel()
        pillJob = null
        if (pillRemainingMs <= 0L) {
            _timerRunning.value = false
            pillTimedOut()
            return
        }
        _countdownRun.value += 1
        _timerRunning.value = true
        pillJob = scope.launch {
            while (pillRemainingMs > 0L) {
                publishPill()
                val step = pillRemainingMs - (secondsFor(pillRemainingMs) - 1) * 1000L
                pillStepStartedAt = clock()
                delay(step)
                // The time that passed, which a late resume makes longer than the step.
                pillRemainingMs -= (clock() - pillStepStartedAt).coerceIn(0L, pillRemainingMs)
            }
            pillJob = null
            _timerRunning.value = false
            pillTimedOut()
        }
    }

    /** Pause: the visible pill stays up with its timer frozen. */
    private fun freezePill() {
        if (pillJob?.isActive != true) return
        stopPillTimer()
        publishPill()
    }

    /** Stops a running pill timer, keeping the exact time left in [pillRemainingMs]. */
    private fun stopPillTimer() {
        val job = pillJob
        if (job != null && job.isActive) {
            pillRemainingMs -= (clock() - pillStepStartedAt).coerceIn(0L, pillRemainingMs)
            job.cancel()
        }
        pillJob = null
        _timerRunning.value = false
    }

    private fun thawPill() {
        if (activePill == null || pillJob != null) return
        startPillTimer()
    }

    private fun pillTimedOut() {
        val kind = activePill ?: return
        hidePill()
        if (kind == PillKind.UNDO) {
            undoEnded()
            return
        }
        // The prompt withdraws without an answer: history clears and a new
        // trigger may offer again after REOFFER_MIN_MS.
        val state = current ?: return
        state.action = BufferingAction.TIMED_OUT
        reofferNotBefore = promptShownAt + REOFFER_MIN_MS
        resetTracker(state, clock())
    }

    /**
     * The undo pill's suppression ended. A stall in progress that has reached
     * [LONG_STALL_MS] is evaluated at once; a shorter one waits for its end.
     */
    private fun undoEnded() {
        val state = current ?: return
        val startedAt = state.tracker.stallStartedAt ?: return
        val now = clock()
        if (now - startedAt >= LONG_STALL_MS) evaluate(now)
    }

    private fun publishPill() {
        val seconds = secondsFor(pillRemainingMs).toInt()
        _pill.value = when (activePill) {
            PillKind.PROMPT -> BufferingQualityPill.Prompt(seconds)
            PillKind.UNDO -> BufferingQualityPill.Undo(seconds)
            null -> BufferingQualityPill.Hidden
        }
    }

    /** Takes any pill down, visible, waiting or hidden, deciding nothing. */
    private fun hidePill() {
        pillJob?.cancel()
        pillJob = null
        activePill = null
        clearDeferred()
        pillRemainingMs = 0L
        _timerRunning.value = false
        if (_pill.value !is BufferingQualityPill.Hidden) _pill.value = BufferingQualityPill.Hidden
    }

    private fun secondsFor(ms: Long): Long = (ms + 999L) / 1000L

    // ---- Private types -----------------------------------------------------

    private enum class PillKind { PROMPT, UNDO }

    private enum class PendingKind {
        /** Lowered on Auto without asking; its plan gets the undo pill. */
        AUTOMATIC,

        /** Lowered after "Lower quality"; its plan gets the "lowered" notice. */
        ACCEPTED,

        /** "Restore quality"; its plan shows nothing. */
        RESTORE,
    }

    private class Pending(val kind: PendingKind, val fromKbps: Int?)

    /** One plan's stall history: the web player's `createStallTracker`. */
    private class StallTracker {
        var firstPlayingAt: Long? = null

        /** The first wait before any frame; a start that never gets going is timed from here. */
        var firstWaitingAt: Long? = null

        /** The plan has not played yet: its reload and seek waits are the start's. */
        val starting: Boolean get() = firstPlayingAt == null

        /** When the stall being timed started counting, or null when none is. */
        var stallStartedAt: Long? = null

        /** A network wait is in progress, timed or not (an outage pauses the timing). */
        var stalled = false

        /** When each counted short stall ended. */
        val stallEnds = mutableListOf<Long>()

        /** When a stall that reached LONG_STALL_MS ended. */
        var longStallEndedAt: Long? = null

        fun noteWaiting(now: Long) {
            if (firstPlayingAt == null && firstWaitingAt == null) firstWaitingAt = now
        }

        /**
         * The grace runs from a wait before playback first runs, or else from
         * that first run; a frame after the wait does not move it.
         */
        fun graceEndsAt(now: Long): Long =
            (firstWaitingAt ?: firstPlayingAt ?: now) + STARTUP_GRACE_MS

        fun shouldTrigger(now: Long): Boolean {
            val longEnded = longStallEndedAt
            if (longEnded != null && now - longEnded < STALL_WINDOW_MS) return true
            val startedAt = stallStartedAt
            if (startedAt != null && now - startedAt >= LONG_STALL_MS) return true
            stallEnds.removeAll { now - it >= STALL_WINDOW_MS }
            return stallEnds.size >= STALLS_BEFORE_TRIGGER
        }
    }

    /** Everything the controller tracks about the playing plan. */
    private class PlanState(val plan: BufferingQualityPlan) {
        val tracker = StallTracker()
        var action: BufferingAction = BufferingAction.NONE

        /** No trigger acts on this plan before this time ("can't lower" result). */
        var cooldownUntil: Long? = null

        var playingSince: Long? = null
        var playedMs: Long = 0L

        /**
         * When the stall in progress started, for the summary. Unlike the
         * tracker's start, a history reset does not move it, so one stall is
         * reported whole.
         */
        var statsStallStartedAt: Long? = null
        var stallCount: Int = 0
        var stallTotalMs: Long = 0L
        var stallLongestMs: Long = 0L
    }
}
