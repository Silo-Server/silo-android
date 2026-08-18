package org.siloserver.silo.domain.player

import org.siloserver.silo.model.catalog.TimeRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** What the intro skip pill should be showing. */
sealed interface IntroAutoSkipState {
    /** No pill: outside an intro, this intro is resolved, or the mode is `never`. */
    data object Hidden : IntroAutoSkipState

    /**
     * `ask`: the "Skip Intro" offer, with [secondsRemaining] left before it
     * withdraws itself. Withdrawal does *not* resolve the intro.
     */
    data class Asking(val secondsRemaining: Int) : IntroAutoSkipState

    /**
     * `always`: the intro has already been skipped and this is the
     * "Intro skipped — Watch Intro" undo, with [secondsRemaining] left.
     *
     * Anchored to the intro it skipped rather than to the position — the seek
     * that produced it necessarily left the range — so position changes never
     * take it down. Only the timer, Select, Back, a content change or a mode
     * change do.
     */
    data class Skipped(val secondsRemaining: Int) : IntroAutoSkipState

    /** Seconds left on the timer, or null while [Hidden]. */
    val secondsRemainingOrNull: Int?
        get() = when (this) {
            is Asking -> secondsRemaining
            is Skipped -> secondsRemaining
            Hidden -> null
        }

    /** True while a pill is on screen. */
    val isVisible: Boolean get() = this !is Hidden
}

/**
 * The one intro-skip prompt state machine, shared by the phone and TV players.
 *
 * The contract is the server repo's `docs/design/2026-08-16-intro-skip-mode.md`
 * ("Prompt behaviour"); its `never` / `ask` / `always` tables are the test
 * oracle and `IntroAutoSkipControllerTest` asserts them case for case. Read that
 * document before changing anything here.
 *
 * Callers drive it with playback inputs through [observe] and act on the pill
 * through [select] / [dismiss]. Everything else — timing, which intros have been
 * decided, when the pill may reappear — lives in here so the three clients
 * cannot drift.
 *
 * ### Seeks
 *
 * The controller performs exactly one seek itself: the immediate skip that
 * `always` is. Everything the *viewer* triggers is returned rather than
 * performed ([select] hands back a position), because the caller may not be
 * allowed to move playback on its own — in a Watch Together room a guest's seek
 * has to route through the room's transport gate. Rooms pin the mode to
 * [IntroSkipMode.ASK] so the automatic path never runs there at all.
 */
class IntroAutoSkipController(
    private val scope: CoroutineScope,
    private val countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS,
) {
    companion object {
        /**
         * The spec's `INTRO_PROMPT_SECONDS`. Shared with the UI that draws the
         * fill so the timer and the bar cannot drift.
         */
        const val DEFAULT_COUNTDOWN_SECONDS: Int = 5
    }

    /** Which pill the current run is showing; distinguishes the two timers' expiries. */
    private enum class Prompt { ASKING, SKIPPED }

    private val _state = MutableStateFlow<IntroAutoSkipState>(IntroAutoSkipState.Hidden)
    val state: StateFlow<IntroAutoSkipState> = _state.asStateFlow()

    /**
     * Intros the viewer has decided in this playback session. A resolved intro
     * never shows a pill again, including after scrubbing back into it.
     */
    private val resolved = mutableSetOf<String>()

    /**
     * The intro whose `ask` offer timed out while the position is still inside
     * it. Timing out does not resolve the intro — scrubbing back in re-offers —
     * but it must not re-offer on the spot either, so the marker is held until
     * the position leaves the range (or the intro, mode or content changes).
     */
    private var expiredKey: String? = null

    private var timerJob: Job? = null
    private var activeKey: String? = null
    private var activeRange: TimeRange? = null
    private var activePrompt: Prompt? = null
    private var remaining: Int = 0
    private var lastMode: IntroSkipMode? = null

    /**
     * Increments each time the tick job (re)starts — a fresh offer, and also a
     * resume after a pause froze it.
     *
     * The fill is frame-clock driven (Compose scales `AnimationSpec` by the
     * system animator duration scale, and a countdown to an action must ignore
     * that), so it needs to know when to re-anchor its clock: [state] alone
     * cannot tell a run that merely ticked from one that restarted, since both
     * just show a number. Carried outside [IntroAutoSkipState] so that state
     * stays comparable by value.
     */
    private val _countdownRun = MutableStateFlow(0)
    val countdownRun: StateFlow<Int> = _countdownRun.asStateFlow()

    /**
     * False while the pill is up but its timer is frozen by a pause. The fill
     * holds where it is; [countdownRun] bumps when it thaws.
     */
    private val _timerRunning = MutableStateFlow(false)
    val timerRunning: StateFlow<Boolean> = _timerRunning.asStateFlow()

    /** Where a fresh timer starts, for a caller drawing progress against it. */
    val totalCountdownSeconds: Int get() = countdownSeconds

    /**
     * Drives the pill from playback state, returning the job that does so.
     *
     * [mode] is the effective `playback.intro_skip_mode`; changing it mid-intro
     * re-evaluates immediately. [onSeek] is the automatic `always` skip and is
     * the only seek this controller performs — see the class docs.
     * [playbackActive] should already have rebuffer dips filtered out of it
     * (`settlingFalseEdges`); a pause that reaches here freezes the timer.
     */
    fun observe(
        position: Flow<Double>,
        introRange: Flow<TimeRange?>,
        mode: Flow<IntroSkipMode>,
        introKey: Flow<String?>,
        onSeek: suspend (toSeconds: Double) -> Unit,
        playbackActive: Flow<Boolean> = flowOf(true),
    ): Job {
        return scope.launch {
            combine(position, introRange, mode, introKey, playbackActive) {
                    pos, range, activeMode, key, playing ->
                Inputs(pos, range, activeMode, key, playing)
            }
                .distinctUntilChanged()
                .collect { handle(it, onSeek) }
        }
    }

    /**
     * The pill's primary action — click, tap, or Select/OK while it is focused.
     *
     * Resolves the intro, hides the pill, and returns the position the caller
     * must seek to: the intro's `end` for the `ask` offer (skip it) and its
     * `start` for the `always` undo (play it after all). Null when no pill is
     * showing, so a stray press is a no-op.
     */
    fun select(): Double? {
        val key = activeKey ?: return null
        val range = activeRange ?: return null
        val prompt = activePrompt ?: return null
        resolved.add(key)
        expiredKey = null
        clearPrompt()
        return when (prompt) {
            Prompt.ASKING -> range.end
            Prompt.SKIPPED -> range.start
        }
    }

    /**
     * Back / Escape / Android system back while the pill is showing: hide it and
     * resolve the intro without moving playback. Returns true when a pill was
     * actually dismissed, so the caller can consume the press only then — a
     * second Back must behave normally.
     */
    fun dismiss(): Boolean {
        val key = activeKey ?: return false
        resolved.add(key)
        expiredKey = null
        clearPrompt()
        return true
    }

    /** Clears all per-intro state, for when playback moves to different content. */
    fun reset() {
        resolved.clear()
        expiredKey = null
        lastMode = null
        clearPrompt()
    }

    private suspend fun handle(
        inputs: Inputs,
        onSeek: suspend (toSeconds: Double) -> Unit,
    ) {
        val (pos, range, mode, key, playbackActive) = inputs

        // A mode change re-evaluates from scratch: ask -> never takes the offer
        // down, never -> always skips the intro the viewer is sitting in.
        if (mode != lastMode) {
            lastMode = mode
            expiredKey = null
            clearPrompt()
        }

        // The `always` pill is pinned to the intro it skipped, not to the
        // position — the skip itself moved the position out of the range, so
        // the "outside the range" rule below would take the undo down on the
        // very next frame.
        if (_state.value is IntroAutoSkipState.Skipped && key != null && key == activeKey) {
            applyTimerGate(playbackActive)
            return
        }

        val inside = range != null && key != null && pos >= range.start && pos < range.end
        if (!inside) {
            // Leaving the range clears the timed-out marker, so seeking back in
            // re-offers with a full timer. It does not clear `resolved`.
            expiredKey = null
            clearPrompt()
            return
        }

        // inside ⇒ range and key are non-null
        val safeRange = range!!
        val safeKey = key!!

        if (activeKey != null && activeKey != safeKey) {
            expiredKey = null
            clearPrompt()
        }

        if (mode == IntroSkipMode.NEVER || safeKey in resolved || safeKey == expiredKey) {
            clearPrompt()
            return
        }

        if (activeKey == null) {
            // Hold the offer until playback is actually running, so the pill and
            // its fill start together rather than the fill racing a player that
            // is still coming up. Rebuffer dips are filtered upstream.
            if (!playbackActive) return
            activeKey = safeKey
            activeRange = safeRange
            remaining = countdownSeconds
            when (mode) {
                IntroSkipMode.ALWAYS -> {
                    activePrompt = Prompt.SKIPPED
                    _state.value = IntroAutoSkipState.Skipped(remaining)
                    startTimer()
                    onSeek(safeRange.end)
                }
                else -> {
                    activePrompt = Prompt.ASKING
                    _state.value = IntroAutoSkipState.Asking(remaining)
                    startTimer()
                }
            }
            return
        }

        // The offer is already up for this intro; only the pause gate can move.
        applyTimerGate(playbackActive)
    }

    /** Freezes the timer on pause and thaws it on play, keeping the pill up. */
    private fun applyTimerGate(playbackActive: Boolean) {
        if (!playbackActive) {
            stopTimerKeepingState()
            return
        }
        if (timerJob == null && remaining > 0 && activePrompt != null) startTimer()
    }

    /**
     * Runs the wall-clock timer down in whole seconds from [remaining].
     *
     * A freeze cancels the job without touching [remaining], so a resume
     * continues from the same number rather than restarting from full. The
     * partial second in flight when the pause landed is not carried across —
     * the tick model has always been whole seconds, and the alternative is a
     * second clock for the fill to disagree with.
     */
    private fun startTimer() {
        timerJob?.cancel()
        _countdownRun.value += 1
        _timerRunning.value = true
        timerJob = scope.launch {
            while (remaining > 0) {
                publishRemaining()
                delay(1000L)
                remaining -= 1
            }
            timerJob = null
            _timerRunning.value = false
            expire()
        }
    }

    private fun publishRemaining() {
        _state.value = when (activePrompt) {
            Prompt.ASKING -> IntroAutoSkipState.Asking(remaining)
            Prompt.SKIPPED -> IntroAutoSkipState.Skipped(remaining)
            null -> IntroAutoSkipState.Hidden
        }
    }

    /**
     * Timer ran out. The two prompts differ here and only here: the `ask` offer
     * withdraws without deciding anything, while the `always` undo resolves the
     * intro — the viewer was told it was skipped and let it go.
     */
    private fun expire() {
        val key = activeKey
        when (activePrompt) {
            Prompt.SKIPPED -> if (key != null) resolved.add(key)
            Prompt.ASKING -> expiredKey = key
            null -> Unit
        }
        clearPrompt()
    }

    private fun stopTimerKeepingState() {
        timerJob?.cancel()
        timerJob = null
        _timerRunning.value = false
    }

    /** Takes the pill down and drops its anchor, deciding nothing. */
    private fun clearPrompt() {
        stopTimerKeepingState()
        activeKey = null
        activeRange = null
        activePrompt = null
        remaining = 0
        if (_state.value !is IntroAutoSkipState.Hidden) {
            _state.value = IntroAutoSkipState.Hidden
        }
    }

    private data class Inputs(
        val position: Double,
        val range: TimeRange?,
        val mode: IntroSkipMode,
        val key: String?,
        val playbackActive: Boolean,
    )
}
