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

/** What a skip pill (intro, or recap and credits) should be showing. */
sealed interface IntroAutoSkipState {
    /** No pill: outside the range, the range is resolved, or the mode is `never`. */
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

    /**
     * `ask`: the offer timed out, the position is still inside the range, and
     * the controls are up, so the pill is back without a timer. It hides with
     * the controls, never takes focus, and claims neither root Select nor
     * Back; activating it skips the range as [Asking] would.
     */
    data object Returned : IntroAutoSkipState

    /** Seconds left on the timer, or null while no timer runs. */
    val secondsRemainingOrNull: Int?
        get() = when (this) {
            is Asking -> secondsRemaining
            is Skipped -> secondsRemaining
            Returned, Hidden -> null
        }

    /** True while a pill is on screen, timed or not. */
    val isVisible: Boolean get() = this !is Hidden

    /**
     * True while the timed pill is on screen: the one that may take focus and
     * that root Select and Back act on.
     */
    val isTimed: Boolean get() = this is Asking || this is Skipped
}

/**
 * The one intro-skip prompt state machine, shared by the phone and TV players.
 *
 * The contract is the server repo's `docs/design/2026-08-16-intro-skip-mode.md`
 * ("Prompt behaviour"); its `never` / `ask` / `always` tables, "When the
 * controls are up" and "Recap and credits pills" are the test oracle and
 * `IntroAutoSkipControllerTest` asserts them case for case. Read that document
 * before changing anything here.
 *
 * Each player runs two instances: one for the intro through [observe], and one
 * for the recap and credits pill through [observeSegmentPill], which follows
 * the `ask` table whatever the intro mode is.
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
        const val DEFAULT_COUNTDOWN_SECONDS: Int = 10
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

    /** The range [expiredKey] names, so the [IntroAutoSkipState.Returned] pill can still skip it. */
    private var expiredRange: TimeRange? = null

    /** Whether the controls are up, as last observed; decides whether a timed-out offer returns. */
    private var controlsVisible: Boolean = false

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
     * [introKey] names the playback the intro belongs to (session and file);
     * each [introRange] under it is a separate intro, decided on its own.
     * [controlsVisible] is whether the controls overlay is up (and no scrub is
     * running); an `ask` offer that timed out returns, untimed, while it is.
     */
    fun observe(
        position: Flow<Double>,
        introRange: Flow<TimeRange?>,
        mode: Flow<IntroSkipMode>,
        introKey: Flow<String?>,
        onSeek: suspend (toSeconds: Double) -> Unit,
        playbackActive: Flow<Boolean> = flowOf(true),
        controlsVisible: Flow<Boolean> = flowOf(false),
    ): Job {
        val inputs = combine(position, introRange, mode, introKey, playbackActive) {
                pos, range, activeMode, key, playing ->
            Inputs(pos, range, activeMode, key, playing, controlsVisible = false)
        }
        return launchHandling(inputs, controlsVisible, onSeek)
    }

    /**
     * Drives the recap and credits pill: the recap occurrence playing, else
     * the credits one, offered under the `ask` table whatever the intro mode
     * is. It never seeks on its own.
     *
     * Each range is keyed by its kind as well as its bounds, so a recap and a
     * credits range with the same bounds are decided separately. The range
     * and its key come from one emission, so a key can never lag its range.
     *
     * [playbackActive] gates the timer as in [observe]; a caller also holds it
     * false while something covers the pill (the Next Up screen, or the intro
     * pill, which outranks this one), so the timer freezes there and an offer
     * that waited starts its full timer when it first shows.
     */
    fun observeSegmentPill(
        position: Flow<Double>,
        markers: Flow<MarkerTimeline>,
        playbackKey: Flow<String?>,
        playbackActive: Flow<Boolean> = flowOf(true),
        controlsVisible: Flow<Boolean> = flowOf(false),
    ): Job {
        val inputs = combine(position, markers, playbackKey, playbackActive) { pos, timeline, prefix, playing ->
            val recap = timeline.active(MarkerKind.Recap, pos)
            val (kind, range) = if (recap != null) {
                MarkerKind.Recap to recap
            } else {
                MarkerKind.Credits to timeline.active(MarkerKind.Credits, pos)
            }
            Inputs(
                position = pos,
                range = range,
                mode = IntroSkipMode.ASK,
                key = prefix?.let { "$it:${kind.wire}" },
                playbackActive = playing,
                controlsVisible = false,
            )
        }
        return launchHandling(inputs, controlsVisible) { }
    }

    private fun launchHandling(
        inputs: Flow<Inputs>,
        controlsVisible: Flow<Boolean>,
        onSeek: suspend (toSeconds: Double) -> Unit,
    ): Job = scope.launch {
        combine(inputs, controlsVisible) { base, controls -> base.copy(controlsVisible = controls) }
            .distinctUntilChanged()
            .collect { handle(it, onSeek) }
    }

    /**
     * The pill's primary action — click, tap, or Select/OK while it is focused.
     *
     * Resolves the intro, hides the pill, and returns the position the caller
     * must seek to: the intro's `end` for the `ask` offer (skip it) and its
     * `start` for the `always` undo (play it after all). The untimed
     * [IntroAutoSkipState.Returned] pill skips like the `ask` offer. Null when
     * no pill is showing, so a stray press is a no-op.
     */
    fun select(): Double? {
        if (_state.value is IntroAutoSkipState.Returned) {
            val key = expiredKey ?: return null
            val range = expiredRange ?: return null
            resolved.add(key)
            clearExpired()
            clearPrompt()
            return range.end
        }
        val key = activeKey ?: return null
        val range = activeRange ?: return null
        val prompt = activePrompt ?: return null
        resolved.add(key)
        clearExpired()
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
     * second Back must behave normally. Only the timed pill takes Back: with
     * the untimed [IntroAutoSkipState.Returned] pill up this returns false and
     * changes nothing.
     */
    fun dismiss(): Boolean {
        val key = activeKey ?: return false
        resolved.add(key)
        clearExpired()
        clearPrompt()
        return true
    }

    /** Clears all per-intro state, for when playback moves to different content. */
    fun reset() {
        resolved.clear()
        clearExpired()
        lastMode = null
        clearPrompt()
    }

    private suspend fun handle(
        inputs: Inputs,
        onSeek: suspend (toSeconds: Double) -> Unit,
    ) {
        val (pos, range, mode, playbackKey, playbackActive, controls) = inputs
        controlsVisible = controls
        // An episode may have several intros. Keying each by its range here,
        // rather than trusting a caller key derived from the same position,
        // keeps a key that lags its range by one emission from pinning a new
        // intro to the previous one.
        val key = playbackKey?.let { prefix -> range?.let { "$prefix@${it.start}-${it.end}" } }

        // A mode change re-evaluates from scratch: ask -> never takes the offer
        // down, never -> always skips the intro the viewer is sitting in.
        if (mode != lastMode) {
            lastMode = mode
            clearExpired()
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
            clearExpired()
            clearPrompt()
            return
        }

        // inside ⇒ range and key are non-null
        val safeRange = range!!
        val safeKey = key!!

        // A different range drops the old one's offer, timed or timed out. The
        // timed-out anchor needs its own check: expiry already cleared
        // activeKey, and a paused seek straight into the next range would
        // otherwise keep the returned pill aimed at the old range's end.
        if ((activeKey != null && activeKey != safeKey) || (expiredKey != null && expiredKey != safeKey)) {
            clearExpired()
            clearPrompt()
        }

        if (mode == IntroSkipMode.NEVER || safeKey in resolved) {
            clearPrompt()
            return
        }

        if (safeKey == expiredKey) {
            // Timed out, still inside: the offer is back without a timer while
            // the controls are up, and hidden while they are not.
            showReturnedIfControlsUp()
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
        val prompt = activePrompt
        when (prompt) {
            Prompt.SKIPPED -> if (key != null) resolved.add(key)
            Prompt.ASKING -> {
                expiredKey = key
                expiredRange = activeRange
            }
            null -> Unit
        }
        clearPrompt()
        // With the controls already up, the pill stays and loses its timer.
        if (prompt == Prompt.ASKING) showReturnedIfControlsUp()
    }

    private fun showReturnedIfControlsUp() {
        val next = if (controlsVisible && expiredRange != null) {
            IntroAutoSkipState.Returned
        } else {
            IntroAutoSkipState.Hidden
        }
        if (_state.value != next) _state.value = next
    }

    private fun clearExpired() {
        expiredKey = null
        expiredRange = null
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
        val controlsVisible: Boolean,
    )
}
