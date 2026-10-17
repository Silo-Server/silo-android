package org.siloserver.silo.domain.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One rung of the plan's quality menu, as far as the controller needs it. */
data class BufferingQualityRung(
    val label: String,
    val bitrateKbps: Int,
)

/** What the controller needs from a plan that just became the playing plan. */
data class BufferingQualityPlan(
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
)

/** Why the player started waiting for data. Only [NETWORK] can become a stall. */
enum class BufferingWaitCause { NETWORK, SEEK, PAUSE, RELOAD }

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
    val planId: String,
    val playedMs: Long,
    val stallCount: Int,
    val stallTotalMs: Long,
    val stallLongestMs: Long,
    val deliveredKbps: Int?,
    val mode: BufferingQualityMode,
    val action: BufferingAction,
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
 */
class BufferingQualityController(
    private val scope: CoroutineScope,
    /** Monotonic milliseconds. Timers run on [scope]; this only reads the time. */
    private val clock: () -> Long,
    private val onReplan: (BufferingQualityReplan) -> Unit,
    private val onNotice: (BufferingQualityNotice) -> Unit,
    private val onPlanSummary: (BufferingPlanSummary) -> Unit = {},
    private val promptSeconds: Int = PROMPT_SECONDS,
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
        const val STEP_DOWN_RATIO: Double = 0.7
        const val MIN_ESTIMATE_KBPS: Int = 100
        const val QUALITY_AUTO: String = "auto"
        const val ESTIMATE_SOURCE_OBSERVED_REBUFFER: String = "observed_rebuffer"
        const val LEARNED_LIMIT_CLEAR: String = "clear"
    }

    private val _pill = MutableStateFlow<BufferingQualityPill>(BufferingQualityPill.Hidden)
    val pill: StateFlow<BufferingQualityPill> = _pill.asStateFlow()

    /** Bumps each time a pill timer (re)starts, so a frame-clock fill can re-anchor. */
    private val _countdownRun = MutableStateFlow(0)
    val countdownRun: StateFlow<Int> = _countdownRun.asStateFlow()

    /** False while a pill is up but its timer is frozen by a pause. */
    private val _timerRunning = MutableStateFlow(false)
    val timerRunning: StateFlow<Boolean> = _timerRunning.asStateFlow()

    val totalCountdownSeconds: Int get() = promptSeconds

    /** The effective `playback.buffering_quality_mode`. */
    fun setMode(mode: BufferingQualityMode): Unit = TODO()

    /** False inside a Watch Party room: the pill hides and events are ignored. */
    fun setEnabled(enabled: Boolean): Unit = TODO()

    /** The intro-skip or Next Up pill is on screen; buffering pills wait for it. */
    fun setOtherPillVisible(visible: Boolean): Unit = TODO()

    /** A plan with a new `plan_id` became the playing plan. */
    fun onPlanAdopted(plan: BufferingQualityPlan): Unit = TODO()

    /** Playback is running: the first frame, or a resume from waiting or pause. */
    fun onPlaying(): Unit = TODO()

    /** The player ran out of data while it should be playing. */
    fun onWaiting(cause: BufferingWaitCause = BufferingWaitCause.NETWORK): Unit = TODO()

    /** The viewer paused. */
    fun onPause(): Unit = TODO()

    /** The viewer seeked. */
    fun onSeek(): Unit = TODO()

    /** Media requests started failing, or the device went offline. */
    fun onTransportErrorStart(): Unit = TODO()

    /** Media requests recovered. */
    fun onTransportErrorEnd(): Unit = TODO()

    /** The controller's last replan failed or will never be adopted. */
    fun onReplanFailed(): Unit = TODO()

    /** The viewer picked a quality from the quality menu. */
    fun onViewerQualityChange(): Unit = TODO()

    /** The playing plan stopped (ended, closed or failed) without a successor. */
    fun onPlaybackStopped(): Unit = TODO()

    /** "Lower quality" on the prompt. True when a prompt was answered. */
    fun selectLower(): Boolean = TODO()

    /** "Keep quality" on the prompt. True when a prompt was answered. */
    fun selectKeep(): Boolean = TODO()

    /** "Restore quality" on the undo pill. True when an undo was answered. */
    fun selectRestore(): Boolean = TODO()

    /** Select/OK at the player root: the visible pill's primary action. */
    fun selectPrimary(): Boolean = TODO()

    /** Back while a pill shows. True when a pill consumed it. */
    fun back(): Boolean = TODO()

    /** Clears all per-title state, for when playback moves to different content. */
    fun reset(): Unit = TODO()
}
