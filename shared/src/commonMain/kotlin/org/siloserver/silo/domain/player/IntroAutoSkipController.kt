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

sealed interface IntroAutoSkipState {
    data object Hidden : IntroAutoSkipState
    data object ShowingButton : IntroAutoSkipState
    data class CountingDown(val secondsRemaining: Int) : IntroAutoSkipState
}

class IntroAutoSkipController(
    private val scope: CoroutineScope,
    private val countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS,
) {
    companion object {
        /** Shared with the UI that renders the countdown so the timer and the bar cannot drift. */
        const val DEFAULT_COUNTDOWN_SECONDS: Int = 5
    }

    private val _state = MutableStateFlow<IntroAutoSkipState>(IntroAutoSkipState.Hidden)
    val state: StateFlow<IntroAutoSkipState> = _state.asStateFlow()

    private val cancelledKeys = mutableSetOf<String>()
    private var countdownJob: Job? = null
    private var activeKey: String? = null

    private fun cancelJob() {
        countdownJob?.cancel()
        countdownJob = null
    }

    fun observe(
        position: Flow<Double>,
        introRange: Flow<TimeRange?>,
        autoSkipEnabled: Flow<Boolean>,
        introKey: Flow<String?>,
        onAutoSkipFire: suspend (toSeconds: Double) -> Unit,
        playbackActive: Flow<Boolean> = flowOf(true),
    ): Job {
        return scope.launch {
            combine(position, introRange, autoSkipEnabled, introKey, playbackActive) {
                    pos, range, enabled, key, playing ->
                Inputs(pos, range, enabled, key, playing)
            }
                .distinctUntilChanged()
                .collect { handle(it, onAutoSkipFire) }
        }
    }

    fun cancelCountdown() {
        val key = activeKey ?: return
        cancelledKeys.add(key)
        val wasCounting = countdownJob != null || _state.value is IntroAutoSkipState.CountingDown
        countdownJob?.cancel()
        countdownJob = null
        if (!wasCounting) return
        _state.value = IntroAutoSkipState.ShowingButton
    }

    fun reset() {
        cancelledKeys.clear()
        countdownJob?.cancel()
        countdownJob = null
        activeKey = null
        _state.value = IntroAutoSkipState.Hidden
    }

    private suspend fun handle(
        inputs: Inputs,
        onAutoSkipFire: suspend (toSeconds: Double) -> Unit,
    ) {
        val (pos, range, enabled, key, playbackActive) = inputs

        val insideRange = range != null &&
            key != null &&
            pos >= range.start &&
            pos < range.end

        if (!insideRange) {
            cancelJob()
            activeKey = null
            if (_state.value !is IntroAutoSkipState.Hidden) {
                _state.value = IntroAutoSkipState.Hidden
            }
            return
        }

        // insideRange ⇒ range and key non-null
        val safeRange = range!!
        val safeKey = key!!

        // If the active key changed, drop any in-flight countdown.
        if (activeKey != null && activeKey != safeKey) {
            cancelJob()
        }
        activeKey = safeKey

        val isCancelled = safeKey in cancelledKeys
        if (!enabled || isCancelled) {
            cancelJob()
            if (_state.value !is IntroAutoSkipState.ShowingButton) {
                _state.value = IntroAutoSkipState.ShowingButton
            }
            return
        }

        // Hold the countdown until playback is actually running, so the prompt
        // and the timer start together.
        if (!playbackActive) {
            cancelJob()
            if (_state.value !is IntroAutoSkipState.ShowingButton) {
                _state.value = IntroAutoSkipState.ShowingButton
            }
            return
        }

        // Auto-skip enabled, key not cancelled — start countdown if not already running for this key.
        if (countdownJob?.isActive == true) return
        countdownJob = scope.launch {
            var remaining = countdownSeconds
            while (remaining > 0) {
                _state.value = IntroAutoSkipState.CountingDown(remaining)
                delay(1000L)
                remaining -= 1
            }
            _state.value = IntroAutoSkipState.Hidden
            countdownJob = null
            onAutoSkipFire(safeRange.end)
        }
    }

    private data class Inputs(
        val position: Double,
        val range: TimeRange?,
        val enabled: Boolean,
        val key: String?,
        val playbackActive: Boolean,
    )
}
